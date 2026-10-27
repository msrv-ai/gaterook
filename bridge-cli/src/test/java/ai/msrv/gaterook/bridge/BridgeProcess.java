package ai.msrv.gaterook.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@code gw-bridge} as a client starts it: a process of its own. That is the native executable named by
 * {@code -Dgateway.bridge.binary} (the release's {@code gw-bridge}, built with {@code -Pnative}), otherwise this
 * module's classes on a child JVM, the way the shim's JVM fallback runs the jar.
 */
final class BridgeProcess {

  /** {@code -Dgateway.bridge.binary}: the native executable under test, or {@code null}. */
  static final String BINARY = System.getProperty("gateway.bridge.binary");

  record Result(int code, String out, String err) {}

  private BridgeProcess() {}

  static boolean nativeBinary() {
    return BINARY != null && !BINARY.isBlank();
  }

  /** Runs {@code gw-bridge args…} with only {@code env} (plus {@code PATH}), {@code stdin} as its input. */
  static Result run(List<String> args, Map<String, String> env, String stdin, Duration timeout)
      throws IOException, InterruptedException {
    List<String> argv = new ArrayList<>();
    if (nativeBinary()) {
      Path binary = Path.of(BINARY).toAbsolutePath();
      if (!Files.isExecutable(binary)) {
        throw new IllegalStateException("-Dgateway.bridge.binary=" + BINARY + " is not an executable");
      }
      argv.add(binary.toString());
    } else {
      argv.addAll(List.of(ProcessHandle.current().info().command().orElse("java"), "-XX:+UseSerialGC",
          "-XX:TieredStopAtLevel=1", "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
          "ai.msrv.gaterook.bridge.Main"));
    }
    argv.addAll(args);
    ProcessBuilder pb = new ProcessBuilder(argv);
    // Codex clears the helper's environment the same way: nothing of the test JVM leaks in.
    pb.environment().clear();
    pb.environment().put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
    pb.environment().putAll(env);
    Process p = pb.start();
    // Both pipes are drained while the process runs, so a chatty child never blocks on a full pipe.
    CompletableFuture<String> out = drain(p.getInputStream());
    CompletableFuture<String> err = drain(p.getErrorStream());
    try (OutputStream in = p.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      p.destroyForcibly();
      throw new AssertionError("gw-bridge " + args + " didn't finish within " + timeout);
    }
    return new Result(p.exitValue(), out.join(), err.join());
  }

  private static CompletableFuture<String> drain(InputStream stream) {
    CompletableFuture<String> text = new CompletableFuture<>();
    Thread.ofVirtual().start(() -> {
      try (stream) {
        text.complete(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        text.complete("");
      }
    });
    return text;
  }
}
