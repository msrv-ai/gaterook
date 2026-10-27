package ai.msrv.gaterook.bridge;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The bridge's side of the broker protocol v1 (report 03 §3.1): one connection, one JSON line each way. The broker is
 * Electron's: a Unix socket on macOS and Linux, a named pipe on Windows, under a random name that
 * {@code <home>/run/broker.json} names ({@code GW_BROKER} may name the socket or that file directly).
 */
final class BrokerClient {

  static final String VERSION = "0.4.0";
  static final int MAX_ANSWER = 64 * 1024;

  /** The broker isn't there, or didn't answer in time. */
  static final class Unavailable extends Exception {
    private static final long serialVersionUID = 1L;

    Unavailable(String message) {
      super(message);
    }
  }

  private final Path home;
  private final String override;

  /**
   * @param home     the gateway home ({@code GW_HOME}, else {@code ~/.mcp-gateway})
   * @param override {@code GW_BROKER}, or {@code null}
   */
  BrokerClient(Path home, String override) {
    this.home = home;
    this.override = override == null || override.isBlank() ? null : override;
  }

  static BrokerClient fromEnvironment(Map<String, String> env) {
    return new BrokerClient(home(env), env.get("GW_BROKER"));
  }

  /** {@code GW_HOME}, else {@code ~/.mcp-gateway}. */
  static Path home(Map<String, String> env) {
    String h = env.get("GW_HOME");
    return h == null || h.isBlank() ? Path.of(System.getProperty("user.home"), ".mcp-gateway") : Path.of(h);
  }

  Path home() {
    return home;
  }

  /** Where the broker listens now. */
  String address() throws Unavailable {
    if (override != null && (override.endsWith(".sock") || override.startsWith("\\\\.\\pipe\\"))) {
      return override;
    }
    Path info = override != null ? Path.of(override) : home.resolve("run").resolve("broker.json");
    try {
      Object parsed = Json.parse(Files.readString(info, StandardCharsets.UTF_8));
      String path = Json.string(parsed, "path");
      if (path == null || path.isBlank()) {
        throw new Unavailable("MCP Gateway's broker file names no socket");
      }
      return path;
    } catch (IOException | RuntimeException e) {
      throw new Unavailable("MCP Gateway is not running (no broker at " + info + ")");
    }
  }

  /** {@code {v:1, op:"token", …}} → the broker's answer. */
  Object token(String client, String endpoint, String grant, Duration wait) throws Unavailable {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("v", 1L);
    request.put("op", "token");
    request.put("client", client);
    request.put("endpoint", endpoint);
    request.put("grant", grant);
    request.put("pid", ProcessHandle.current().pid());
    request.put("bridge", VERSION);
    return call(Json.write(request), wait);
  }

  Object call(String requestLine, Duration wait) throws Unavailable {
    String address = address();
    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> exchange(address, requestLine),
        r -> Thread.ofVirtual().start(r));
    try {
      return Json.parse(f.get(wait.toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      f.cancel(true);
      throw new Unavailable("MCP Gateway's broker didn't answer within " + wait.toSeconds() + " s");
    } catch (ExecutionException e) {
      throw new Unavailable("MCP Gateway is not running (" + rootMessage(e) + ")");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Unavailable("interrupted");
    } catch (IllegalArgumentException e) {
      throw new Unavailable("MCP Gateway's broker answered something that isn't JSON");
    }
  }

  private static String exchange(String address, String line) {
    byte[] request = (line + "\n").getBytes(StandardCharsets.UTF_8);
    try {
      if (address.startsWith("\\\\.\\pipe\\")) {
        // "All pipe instances are busy": the broker serves one connection per instance; try again shortly.
        for (int attempt = 0; ; attempt++) {
          try (RandomAccessFile pipe = new RandomAccessFile(address, "rw")) {
            pipe.write(request);
            StringBuilder b = new StringBuilder();
            int c;
            while ((c = pipe.read()) >= 0 && c != '\n') {
              b.append((char) c);
              if (b.length() > MAX_ANSWER) {
                throw new IOException("answer too long");
              }
            }
            return new String(b.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
          } catch (IOException e) {
            if (attempt >= 2 || !String.valueOf(e.getMessage()).contains("busy")) {
              throw e;
            }
            Thread.sleep(50);
          }
        }
      }
      try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
        ch.connect(UnixDomainSocketAddress.of(address));
        ByteBuffer out = ByteBuffer.wrap(request);
        while (out.hasRemaining()) {
          ch.write(out);
        }
        ByteBuffer buf = ByteBuffer.allocate(4096);
        java.io.ByteArrayOutputStream answer = new java.io.ByteArrayOutputStream();
        while (ch.read(buf) >= 0) {
          buf.flip();
          while (buf.hasRemaining()) {
            byte b = buf.get();
            if (b == '\n') {
              return answer.toString(StandardCharsets.UTF_8);
            }
            answer.write(b);
          }
          buf.clear();
          if (answer.size() > MAX_ANSWER) {
            throw new IOException("answer too long");
          }
        }
        return answer.toString(StandardCharsets.UTF_8);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new IOException("interrupted"));
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null) {
      c = c.getCause();
    }
    return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
  }
}
