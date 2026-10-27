package ai.msrv.gaterook.e2e.platform;

import ai.msrv.gaterook.core.platform.LaunchSecret;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core started the way Electron starts it (ARCHITECTURE.md §7.1): a minimal environment, {@code -D} flags only, the
 * bootstrap line on stdin, protocol lines on stdout. Runs {@code target/app.jar} on this JDK, or the native executable
 * named by {@code -Dgateway.core.binary} (then {@code -Dgateway.core.libs} is the folder of {@code libsqlitejdbc}).
 * With {@code -Dgateway.core.aot=true} the jar runs its AOT-processed context ({@code -Dspring.aot.enabled=true}; the
 * jar of {@code -Pnative -DskipNativeBuild=true}): the quick AOT detector of {@code scripts/aot-check.sh}.
 */
public final class CoreProcess implements AutoCloseable {

  public static final JsonMapper JSON = JsonMapper.builder().build();

  /** The bootstrap secret of these tests: 32 bytes, base64url. */
  public static final String SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("core-process-it-launch-secret-32".getBytes(StandardCharsets.US_ASCII));

  public final Path home;
  public final int port;
  public final int publicPort;
  public final Process process;
  private final OutputStream stdin;
  private final LinkedBlockingQueue<String> stdout = new LinkedBlockingQueue<>();
  private final List<String> allStdout = new ArrayList<>();
  public final long startedNanos;

  private CoreProcess(Path home, int port, int publicPort, Process process, long startedNanos) {
    this.home = home;
    this.port = port;
    this.publicPort = publicPort;
    this.process = process;
    this.stdin = process.getOutputStream();
    this.startedNanos = startedNanos;
    Thread.ofPlatform().daemon(true).name("core-stdout-" + process.pid()).start(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
          StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          synchronized (allStdout) {
            allStdout.add(line);
          }
          stdout.add(line);
        }
      } catch (IOException ignored) {
        // The process ended.
      }
    });
  }

  public static boolean nativeBinary() {
    return System.getProperty("gateway.core.binary") != null;
  }

  public static Path newHome(String name) {
    try {
      Path parent = Path.of("target", "core-homes").toAbsolutePath();
      Files.createDirectories(parent);
      return Files.createTempDirectory(parent, name + "-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static int freePort() {
    // On the loopback address the core binds, without SO_REUSEADDR: a wildcard probe on macOS was handed a port that
    // a Docker test service holds on 127.0.0.1 (Vault's 55820), and the core then failed with PORT_IN_USE.
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(false);
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Starts the core; {@code jvmFlags} are extra {@code -D}/{@code -X} flags as Electron would pass them. */
  public static CoreProcess start(Path home, int port, List<String> jvmFlags) {
    int publicPort = freePort();
    while (publicPort == port) {
      publicPort = freePort();
    }
    List<String> command = new ArrayList<>();
    String binary = System.getProperty("gateway.core.binary");
    if (binary != null) {
      command.add(binary);
      command.add("-Xmx384m");
      command.add("-Dorg.sqlite.lib.path=" + System.getProperty("gateway.core.libs",
          Path.of(binary).toAbsolutePath().getParent().resolve("native-libs").toString()));
    } else {
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.add("-Xmx384m");
      if (Boolean.getBoolean("gateway.core.aot")) {
        command.add("-Dspring.aot.enabled=true");
      }
      // On the JVM every answer of the core's by-type cache is checked against Spring's (platform.boot); the native
      // smoke measures the start, so the binary runs as released.
      command.add("-Dgateway.platform.verify-type-cache=true");
    }
    command.add("-Dgateway.home=" + home);
    command.add("-Djava.io.tmpdir=" + home.resolve("tmp"));
    command.addAll(jvmFlags);
    if (binary == null) {
      command.add("-jar");
      command.add(Path.of(System.getProperty("gateway.core.jar", "target/app.jar")).toAbsolutePath().toString());
    }
    ProcessBuilder builder = new ProcessBuilder(command);
    // Electron's minimal environment; HOME points into target/ so nothing can reach the real one.
    Map<String, String> env = builder.environment();
    env.clear();
    env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
    env.put("HOME", home.toString());
    env.put("LANG", "C.UTF-8");
    builder.redirectError(home.resolve("stderr.log").toFile());
    try {
      Files.createDirectories(home.resolve("tmp"));
      // From just before exec: the warm start Electron waits for is exec → ready line.
      long started = System.nanoTime();
      return new CoreProcess(home, port, publicPort, builder.start(), started);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes the bootstrap line without OS protection (stdin stays open). */
  public CoreProcess bootstrap() {
    return bootstrap(null, "off");
  }

  /** Writes the bootstrap line with {@code fileKey} as Electron encoded it ({@code null}: none). */
  public CoreProcess bootstrap(String fileKey, String protection) {
    return writeLine("{\"v\":1,\"launchSecret\":\"" + SECRET + "\",\"instanceNonce\":\"it-instance-0123456789\","
        + "\"fileKey\":" + (fileKey == null ? "null" : "\"" + fileKey + "\"") + ",\"protection\":\"" + protection
        + "\",\"port\":" + port + ",\"publicPort\":" + publicPort + "}");
  }

  public CoreProcess writeLine(String line) {
    try {
      stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
      stdin.flush();
      return this;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The next stdout line as JSON, waiting at most {@code timeout}. */
  public JsonNode nextLine(Duration timeout) throws InterruptedException {
    String line = stdout.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (line == null) {
      throw new AssertionError("No stdout line within " + timeout + "; stderr: " + stderr());
    }
    return JSON.readTree(line);
  }

  /** Lines until (and including) the ready line, of either kind. */
  public List<JsonNode> untilReady(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<JsonNode> lines = new ArrayList<>();
    while (true) {
      long left = deadline - System.nanoTime();
      JsonNode line = nextLine(Duration.ofNanos(Math.max(left, 1)));
      lines.add(line);
      if (line.has("ready")) {
        return lines;
      }
    }
  }

  public List<String> stdoutSoFar() {
    synchronized (allStdout) {
      return List.copyOf(allStdout);
    }
  }

  /** Closes stdin, as Electron's exit does. */
  public void closeStdin() {
    try {
      stdin.close();
    } catch (IOException ignored) {
      // Already closed.
    }
  }

  public boolean exited(Duration within) throws InterruptedException {
    return process.waitFor(within.toMillis(), TimeUnit.MILLISECONDS);
  }

  public String stderr() {
    try {
      return Files.readString(home.resolve("stderr.log"));
    } catch (IOException e) {
      return "<no stderr: " + e.getMessage() + ">";
    }
  }

  public String coreLog() {
    try {
      return Files.readString(home.resolve("logs").resolve("core.log"));
    } catch (IOException e) {
      return "";
    }
  }

  public LaunchSecret secret() {
    return LaunchSecret.fromBase64Url(SECRET);
  }

  @Override
  public void close() {
    closeStdin();
    try {
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
