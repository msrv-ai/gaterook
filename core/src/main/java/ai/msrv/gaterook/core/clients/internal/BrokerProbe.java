package ai.msrv.gaterook.core.clients.internal;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whether Electron's broker answers (check step 2, report 03 §5): reads {@code run/broker.json} for the socket or pipe
 * the broker chose at random, connects as a bridge would and sends {@code {"v":1,"op":"ping"}}. The core never serves
 * the broker's socket itself: the broker lives in Electron's main process (A7), so a core started without the app has
 * no broker, and this says so.
 */
public final class BrokerProbe {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The answer: running or not, where, and a sentence for the check. */
  public record Result(boolean running, String endpoint, String text) {}

  private BrokerProbe() {}

  public static Result ping(Path brokerInfo, Duration timeout) {
    if (!Files.isRegularFile(brokerInfo)) {
      return new Result(false, null, "The broker isn't running: open MCP Gateway (the desktop app starts it).");
    }
    String endpoint;
    try {
      JsonNode info = JSON.readTree(Files.readString(brokerInfo));
      endpoint = info.path("path").asString(null);
    } catch (IOException | RuntimeException e) {
      return new Result(false, null, brokerInfo + " can't be read: restart MCP Gateway.");
    }
    if (endpoint == null || endpoint.isBlank()) {
      return new Result(false, null, brokerInfo + " names no socket: restart MCP Gateway.");
    }
    String target = endpoint;
    CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> exchange(target),
        r -> Thread.ofVirtual().start(r));
    try {
      String line = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      JsonNode answer = JSON.readTree(line);
      return answer.path("ok").asBoolean(false)
          ? new Result(true, endpoint, "The broker answers.")
          : new Result(false, endpoint, "The broker answered with an error: " + answer.path("error").asString(""));
    } catch (TimeoutException e) {
      call.cancel(true);
      return new Result(false, endpoint, "The broker didn't answer within " + timeout.toMillis() + " ms.");
    } catch (ExecutionException e) {
      return new Result(false, endpoint, "The broker isn't running: open MCP Gateway.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(false, endpoint, "Interrupted.");
    } catch (RuntimeException e) {
      return new Result(false, endpoint, "The broker's answer can't be read.");
    }
  }

  private static String exchange(String endpoint) {
    byte[] request = "{\"v\":1,\"op\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8);
    try {
      if (endpoint.startsWith("\\\\.\\pipe\\")) {
        try (RandomAccessFile pipe = new RandomAccessFile(endpoint, "rw")) {
          pipe.write(request);
          return readLine(pipe);
        }
      }
      try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
        ch.connect(UnixDomainSocketAddress.of(endpoint));
        ch.write(ByteBuffer.wrap(request));
        ByteBuffer buf = ByteBuffer.allocate(4096);
        StringBuilder out = new StringBuilder();
        while (ch.read(buf) > 0) {
          buf.flip();
          out.append(StandardCharsets.UTF_8.decode(buf));
          buf.clear();
          int nl = out.indexOf("\n");
          if (nl >= 0) {
            return out.substring(0, nl);
          }
        }
        return out.toString();
      }
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static String readLine(RandomAccessFile pipe) throws IOException {
    StringBuilder b = new StringBuilder();
    int c;
    while ((c = pipe.read()) >= 0 && c != '\n' && b.length() < 4096) {
      b.append((char) c);
    }
    return b.toString();
  }
}
