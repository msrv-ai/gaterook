package ai.msrv.gaterook.connectors.redis;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * A Redis stand-in on a loopback port for the connector's own sockets, plain or TLS (a certificate for
 * {@code localhost} and {@code 127.0.0.1} from {@code okhttp-tls}). It speaks enough RESP2 for a connection to sign in
 * ({@code HELLO 2 AUTH}, {@code CLIENT}, {@code SELECT}) and for the first reads of Test connection, and it reads a TLS
 * ClientHello on its plain port the way redis-server does: as an inline command whose line end it never finds before
 * the hello's first NUL byte, so it waits, silently.
 */
final class FakeRedis implements AutoCloseable {

  /** How the server behaves: Redis, a web server, or a Redis in protected mode that refuses every client. */
  enum Mode { REDIS, HTTP, DENIED }

  final HeldCertificate certificate;
  final int port;
  /** Every command received, the password masked. */
  final List<String> commands = new CopyOnWriteArrayList<>();
  final AtomicInteger connections = new AtomicInteger();
  /** {@code PING}s that arrived over plain TCP. */
  final AtomicInteger plainPings = new AtomicInteger();
  private final ServerSocket server;
  private final String password;
  private final Mode mode;
  private final long delayMs;
  private volatile String keyspace = "# Keyspace\r\ndb0:keys=3,expires=0,avg_ttl=0\r\n"
      + "db2:keys=12,expires=1,avg_ttl=9\r\n";

  private FakeRedis(boolean tls, String password, Mode mode, long delayMs) throws IOException {
    this.certificate = new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1").build();
    this.password = password;
    this.mode = mode;
    this.delayMs = delayMs;
    if (tls) {
      SSLContext context = new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslContext();
      this.server = context.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
    } else {
      this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }
    this.port = server.getLocalPort();
    Thread.ofVirtual().name("fake-redis-" + port).start(this::accept);
  }

  static FakeRedis plain(String password) throws IOException {
    return new FakeRedis(false, password, Mode.REDIS, 0);
  }

  static FakeRedis tls(String password) throws IOException {
    return new FakeRedis(true, password, Mode.REDIS, 0);
  }

  /** A TLS server that waits {@code delayMs} before it reads anything of a new connection. */
  static FakeRedis slowTls(String password, long delayMs) throws IOException {
    return new FakeRedis(true, password, Mode.REDIS, delayMs);
  }

  static FakeRedis other(Mode mode) throws IOException {
    return new FakeRedis(false, null, mode, 0);
  }

  FakeRedis keyspace(String info) {
    this.keyspace = info;
    return this;
  }

  String caPem() {
    return certificate.certificatePem();
  }

  @Override
  public void close() throws IOException {
    server.close();
  }

  private void accept() {
    while (!server.isClosed()) {
      try {
        Socket s = server.accept();
        connections.incrementAndGet();
        Thread.ofVirtual().start(() -> serve(s));
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket socket) {
    boolean plain = !(socket instanceof SSLSocket);
    try (socket) {
      if (delayMs > 0) {
        Thread.sleep(delayMs);
      }
      OutputStream out = socket.getOutputStream();
      if (mode == Mode.DENIED) {
        out.write("-DENIED Redis is running in protected mode\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return;
      }
      InputStream in = new BufferedInputStream(socket.getInputStream());
      if (mode == Mode.HTTP) {
        // A web server answers whatever arrives, a TLS hello included.
        if (in.read() >= 0) {
          out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          out.flush();
        }
        return;
      }
      boolean signedIn = password == null;
      List<String> command;
      while ((command = read(in)) != null) {
        String name = command.getFirst().toUpperCase(Locale.ROOT);
        commands.add(String.join(" ", command.stream().map(w -> w.equals(password) ? "‹password›" : w).toList()));
        if (name.equals("PING") && plain) {
          plainPings.incrementAndGet();
        }
        String reply;
        if (name.equals("HELLO")) {
          int auth = command.indexOf("AUTH");
          if (auth > 0 && password != null && !password.equals(command.get(auth + 2))) {
            reply = "-WRONGPASS invalid username-password pair or user is disabled.\r\n";
          } else if (auth < 0 && !signedIn) {
            reply = "-NOAUTH HELLO must be called with the client already authenticated\r\n";
          } else {
            signedIn = true;
            reply = hello();
          }
        } else if (!signedIn) {
          reply = "-NOAUTH Authentication required.\r\n";
        } else {
          reply = switch (name) {
            case "PING" -> "+PONG\r\n";
            case "CLIENT", "SELECT" -> "+OK\r\n";
            case "INFO" -> bulk(keyspace);
            case "CONFIG" -> command.size() == 3 && command.get(2).equalsIgnoreCase("databases")
                ? "*2\r\n" + bulk("databases") + bulk("16") : "*0\r\n";
            case "DBSIZE" -> ":3\r\n";
            default -> "-ERR unknown command '" + command.getFirst() + "'\r\n";
          };
        }
        out.write(reply.getBytes(StandardCharsets.UTF_8));
        out.flush();
      }
    } catch (IOException e) {
      // The client went away, or a TLS port met a client that doesn't speak TLS.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** {@code HELLO 2}'s answer: a flat map of the server's facts. */
  private static String hello() {
    return "*14\r\n" + bulk("server") + bulk("redis") + bulk("version") + bulk("7.2.4") + bulk("proto") + ":2\r\n"
        + bulk("id") + ":7\r\n" + bulk("mode") + bulk("standalone") + bulk("role") + bulk("master")
        + bulk("modules") + "*0\r\n";
  }

  /**
   * One command: a RESP array of bulk strings, or an inline line. Like redis-server, an inline request looks for its
   * line end only up to its first NUL byte: a TLS ClientHello never ends, so the server reads on, silently.
   */
  private static List<String> read(InputStream in) throws IOException {
    int first = in.read();
    if (first < 0) {
      return null;
    }
    if (first != '*') {
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      boolean nul = first == 0;
      line.write(first);
      for (int b = in.read(); b >= 0; b = in.read()) {
        nul |= b == 0;
        if (b == '\n' && !nul) {
          return List.of(line.toString(StandardCharsets.ISO_8859_1).strip().split(" "));
        }
        line.write(b);
      }
      return null;
    }
    int n = Integer.parseInt(lineOf(in));
    List<String> words = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int length = Integer.parseInt(lineOf(in).substring(1));
      byte[] data = in.readNBytes(length);
      in.readNBytes(2);
      words.add(new String(data, StandardCharsets.UTF_8));
    }
    return words;
  }

  private static String lineOf(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    for (int c = in.read(); c >= 0 && c != '\r'; c = in.read()) {
      b.write(c);
    }
    in.read();
    return b.toString(StandardCharsets.ISO_8859_1);
  }

  private static String bulk(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return "$" + bytes.length + "\r\n" + text + "\r\n";
  }
}
