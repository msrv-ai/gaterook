package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The redirect URI of one sign-in on this computer (RFC 8252 §7.3, a native app's loopback redirect): a listener on
 * {@code 127.0.0.1} (and {@code [::1]} for {@code localhost}) that answers {@code GET <path>?code=…&state=…} once and
 * closes. The browser lands here after the person allowed access at the authorization server; nothing else is served
 * — no other path, no other method, no Host but this one.
 *
 * <p>It is not the core's listener: a sign-in needs a port the authorization server accepts (any free one, or the
 * exact one an OAuth app was registered with), and the admin API never shares a socket with a browser page. A plain
 * {@link ServerSocket} keeps it free of the JDK's HTTP server, which the native core doesn't carry.
 */
public final class LoopbackCallback implements AutoCloseable {

  /** What the page shows and whether the listener is done. */
  public record Page(int status, String title, String text, boolean done) {}

  /** Decides about one callback with its decoded query parameters. */
  public interface Handler {
    Page handle(Map<String, String> query);
  }

  private static final int MAX_REQUEST = 16 * 1024;
  private static final int READ_TIMEOUT_MS = 10_000;
  private static final String STYLE = "body{font:15px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
      + "margin:0;min-height:100vh;display:grid;place-items:center;background:#f6f6f8;color:#1b1b1f}"
      + "main{max-width:28rem;padding:2rem}h1{font-size:1.25rem;margin:0 0 .5rem}p{margin:0;color:#4a4a55}"
      + "@media(prefers-color-scheme:dark){body{background:#151518;color:#ececf1}p{color:#b4b4c0}}";
  private static final String STYLE_HASH = sha256(STYLE);

  private final String host;
  private final String path;
  private final int port;
  private final Handler handler;
  private final List<ServerSocket> sockets;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Semaphore connections = new Semaphore(16);

  private LoopbackCallback(String host, String path, int port, List<ServerSocket> sockets, Handler handler) {
    this.host = host;
    this.path = path;
    this.port = port;
    this.sockets = sockets;
    this.handler = handler;
  }

  /**
   * Listens on {@code host} ({@code 127.0.0.1} or {@code localhost}) at {@code port} (0: any free one) for
   * {@code path}.
   *
   * @throws IOException when the port is taken
   */
  public static LoopbackCallback open(String host, int port, String path, Handler handler) throws IOException {
    boolean localhost = "localhost".equalsIgnoreCase(host);
    List<ServerSocket> sockets = new ArrayList<>();
    ServerSocket v4 = new ServerSocket();
    try {
      v4.bind(new InetSocketAddress(InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1}), port), 16);
    } catch (IOException e) {
      v4.close();
      throw e;
    }
    sockets.add(v4);
    int bound = v4.getLocalPort();
    if (localhost) {
      // A browser may try ::1 first for "localhost"; where IPv6 loopback exists it gets the same answer.
      ServerSocket v6 = new ServerSocket();
      try {
        v6.bind(new InetSocketAddress(InetAddress.getByName("::1"), bound), 16);
        sockets.add(v6);
      } catch (IOException e) {
        v6.close();
      }
    }
    LoopbackCallback cb = new LoopbackCallback(localhost ? "localhost" : "127.0.0.1", path, bound,
        List.copyOf(sockets), handler);
    for (ServerSocket s : cb.sockets) {
      Thread.ofVirtual().name("mcp-sign-in-callback-" + bound).start(() -> cb.accept(s));
    }
    return cb;
  }

  public URI redirectUri() {
    return URI.create("http://" + host + ":" + port + path);
  }

  public int port() {
    return port;
  }

  public boolean closed() {
    return closed.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      for (ServerSocket s : sockets) {
        try {
          s.close();
        } catch (IOException e) {
          // Closing anyway.
        }
      }
    }
  }

  private void accept(ServerSocket server) {
    while (!closed.get()) {
      Socket socket;
      try {
        socket = server.accept();
      } catch (IOException e) {
        return;
      }
      if (!connections.tryAcquire()) {
        closeQuietly(socket);
        continue;
      }
      Thread.ofVirtual().start(() -> {
        try (Socket s = socket) {
          serve(s);
        } catch (IOException e) {
          // The browser went away.
        } finally {
          connections.release();
        }
      });
    }
  }

  private void serve(Socket s) throws IOException {
    s.setSoTimeout(READ_TIMEOUT_MS);
    String head;
    try {
      head = readHead(s.getInputStream());
    } catch (SocketTimeoutException e) {
      // A browser's speculative connection that never sent anything.
      return;
    }
    if (head == null) {
      return;
    }
    String[] lines = head.split("\r\n");
    String[] request = lines[0].split(" ");
    OutputStream out = s.getOutputStream();
    if (request.length != 3 || !request[2].startsWith("HTTP/1.")) {
      write(out, new Page(400, "Bad request", "This isn't a request GateRook understands.", false));
      return;
    }
    if (!hostMatches(lines)) {
      write(out, new Page(421, "Wrong address", "This page only answers at " + redirectUri() + ".", false));
      return;
    }
    String target = request[1];
    int q = target.indexOf('?');
    String rawPath = q < 0 ? target : target.substring(0, q);
    if (!rawPath.equals(path)) {
      write(out, new Page(404, "Not found", "Nothing is here.", false));
      return;
    }
    if (!"GET".equals(request[0])) {
      write(out, new Page(405, "Not allowed", "The browser brings the sign-in back with GET.", false));
      return;
    }
    Page page;
    try {
      page = handler.handle(OAuthHttp.query(q < 0 ? "" : target.substring(q + 1)));
    } catch (RuntimeException e) {
      page = new Page(500, "Sign-in failed", "GateRook couldn't finish the sign-in. Go back to GateRook and try "
          + "again.", true);
    }
    write(out, page);
    if (page.done()) {
      close();
    }
  }

  /** The request line and headers, without the body; {@code null} when the request is too big or cut off. */
  private static String readHead(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int matched = 0;
    byte[] end = {'\r', '\n', '\r', '\n'};
    while (buf.size() < MAX_REQUEST) {
      int b = in.read();
      if (b < 0) {
        return null;
      }
      buf.write(b);
      matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
      if (matched == 4) {
        return buf.toString(StandardCharsets.ISO_8859_1);
      }
    }
    return null;
  }

  /** The Host header is this listener's: a page elsewhere can't use a rebound DNS name to reach it. */
  private boolean hostMatches(String[] lines) {
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon > 0 && lines[i].substring(0, colon).trim().equalsIgnoreCase("host")) {
        String value = lines[i].substring(colon + 1).trim().toLowerCase(Locale.ROOT);
        return value.equals("127.0.0.1:" + port) || value.equals("localhost:" + port)
            || value.equals("[::1]:" + port);
      }
    }
    return false;
  }

  private static void write(OutputStream out, Page page) throws IOException {
    String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
        + "content=\"width=device-width,initial-scale=1\"><title>" + escape(page.title()) + " · GateRook</title><style>"
        + STYLE + "</style></head><body><main><h1>" + escape(page.title()) + "</h1><p>" + escape(page.text())
        + "</p></main></body></html>";
    byte[] body = html.getBytes(StandardCharsets.UTF_8);
    String reason = switch (page.status()) {
      case 200 -> "OK";
      case 400 -> "Bad Request";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 421 -> "Misdirected Request";
      default -> "Error";
    };
    String headers = "HTTP/1.1 " + page.status() + " " + reason + "\r\n"
        + "Content-Type: text/html; charset=utf-8\r\n"
        + "Content-Length: " + body.length + "\r\n"
        + "Cache-Control: no-store\r\n"
        // The address bar holds the code: no page this one could lead to may see it.
        + "Referrer-Policy: no-referrer\r\n"
        + "Content-Security-Policy: default-src 'none'; style-src 'sha256-" + STYLE_HASH + "'; "
        + "frame-ancestors 'none'; form-action 'none'; base-uri 'none'\r\n"
        + "X-Content-Type-Options: nosniff\r\n"
        + "Connection: close\r\n\r\n";
    out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
    out.write(body);
    out.flush();
  }

  static String escape(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (char c : s.toCharArray()) {
      switch (c) {
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '&' -> out.append("&amp;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  private static String sha256(String s) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(
          s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void closeQuietly(Socket s) {
    try {
      s.close();
    } catch (IOException e) {
      // Gone already.
    }
  }
}
