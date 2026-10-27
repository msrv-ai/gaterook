package ai.msrv.gaterook.core.platform.boot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * stdin of the core: the first line is the bootstrap, then the stream stays open for as long as Electron lives. EOF
 * means the parent is gone (a crash closes the pipe too), and the core exits instead of serving agents without a
 * window and without anyone to approve (ARCHITECTURE.md §7.1, A20).
 *
 * <p>One platform daemon thread does both: it reads the line, hands it over, and keeps reading (and discarding) until
 * EOF. A platform thread because the read blocks in native code for the whole life of the process.
 */
final class StdinChannel {

  /** The bootstrap line is small; anything longer is refused rather than buffered. */
  static final int MAX_LINE_BYTES = 64 * 1024;

  private final InputStream in;
  private final Runnable onEof;
  private final CompletableFuture<byte[]> line = new CompletableFuture<>();

  private StdinChannel(InputStream in, Runnable onEof) {
    this.in = in;
    this.onEof = onEof;
  }

  /** Starts the reader thread. {@code onEof} runs on it once stdin closes, after the bootstrap line or before it. */
  static StdinChannel open(InputStream in, Runnable onEof) {
    StdinChannel channel = new StdinChannel(in, onEof);
    Thread reader = Thread.ofPlatform().name("gateway-stdin").daemon(true).unstarted(channel::run);
    reader.start();
    return channel;
  }

  /**
   * The bootstrap line without its line break; the caller zeroes the array after parsing it.
   *
   * @throws GatewayStartupException {@code BOOTSTRAP_TIMEOUT} after {@code timeout}, {@code BOOTSTRAP_INVALID} when
   *     stdin closed first or the line is too long
   */
  byte[] awaitLine(Duration timeout) {
    try {
      return line.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new GatewayStartupException(StartupReason.BOOTSTRAP_TIMEOUT,
          "No bootstrap line on stdin within " + timeout.toSeconds() + " s: the core must be started by MCP Gateway");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof GatewayStartupException startup) {
        throw startup;
      }
      throw new GatewayStartupException(StartupReason.BOOTSTRAP_INVALID, "Can't read the bootstrap line", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GatewayStartupException(StartupReason.BOOTSTRAP_TIMEOUT, "Interrupted while waiting for the bootstrap");
    }
  }

  private void run() {
    try {
      readLine();
      byte[] discard = new byte[4096];
      while (in.read(discard) >= 0) {
        // Nothing else is sent on stdin today; whatever comes is ignored until EOF.
      }
    } catch (IOException e) {
      line.completeExceptionally(e);
    } finally {
      line.completeExceptionally(new GatewayStartupException(StartupReason.BOOTSTRAP_INVALID,
          "stdin closed before the bootstrap line"));
      onEof.run();
    }
  }

  private void readLine() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
    int b;
    while ((b = in.read()) >= 0) {
      if (b == '\n') {
        byte[] bytes = buffer.toByteArray();
        int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        byte[] result = Arrays.copyOf(bytes, length);
        Arrays.fill(bytes, (byte) 0);
        line.complete(result);
        return;
      }
      if (buffer.size() >= MAX_LINE_BYTES) {
        line.completeExceptionally(new GatewayStartupException(StartupReason.BOOTSTRAP_INVALID,
            "The bootstrap line is longer than " + MAX_LINE_BYTES + " bytes"));
        return;
      }
      buffer.write(b);
    }
  }
}
