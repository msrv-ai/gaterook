package ai.msrv.gaterook.connectors.logs;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.databind.JsonNode;

/**
 * Real backends of the {@code -Plogs-it} tests (README, "Development"): downloads cached in
 * {@code ~/.cache/mcp-gateway-test} and checked against pinned SHA-256 before every use, processes on free loopback
 * ports, their data and logs under {@code target/logs-it}. A download goes through {@code curl}, which honours the
 * machine's proxy settings; a binary of another platform comes from the variable each test names.
 */
final class Fixtures {

  static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "mcp-gateway-test");
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .connectTimeout(Duration.ofSeconds(2)).build();

  private Fixtures() {}

  /** Whether this is Linux on x86-64, the platform of the pinned archives. */
  static boolean linuxX64() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"));
  }

  /** A cached download, fetched once; its SHA-256 is checked every time, so a changed file is never run. */
  static Path download(String dir, String file, String url, String sha256) throws IOException, InterruptedException {
    Path target = CACHE.resolve(dir).resolve(file);
    if (!Files.isRegularFile(target)) {
      Files.createDirectories(target.getParent());
      Path part = target.resolveSibling(file + "." + UUID.randomUUID() + ".part");
      Process curl = new ProcessBuilder("curl", "-fsSL", "--retry", "3", "-o", part.toString(), url)
          .redirectErrorStream(true).start();
      String out = new String(curl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (curl.waitFor() != 0) {
        Files.deleteIfExists(part);
        throw new IllegalStateException("Couldn't download " + url + " into " + target + ": " + out.strip());
      }
      Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
    }
    String actual = sha256(target);
    if (!actual.equals(sha256)) {
      throw new IllegalStateException(target + " has SHA-256 " + actual + ", expected " + sha256 + ": delete it and "
          + "run again to download it afresh");
    }
    return target;
  }

  static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest d = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      for (int n; (n = in.read(buf)) > 0; ) {
        d.update(buf, 0, n);
      }
      return HexFormat.of().formatHex(d.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Extracts one entry of a zip archive as an executable file. */
  static Path unzipOne(Path zip, String entry, Path into) throws IOException {
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        if (e.getName().equals(entry)) {
          Path out = into.resolve(Path.of(entry).getFileName());
          Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
          if (!out.toFile().setExecutable(true, false)) {
            throw new IOException("Couldn't make " + out + " executable");
          }
          return out;
        }
      }
    }
    throw new IOException(zip + " has no " + entry);
  }

  /**
   * A fresh directory of one test run, under {@code target/logs-it}. The previous run's directory of the same name
   * goes first: an unpacked OpenSearch is half a gigabyte, and only the last run's logs are worth reading.
   */
  static Path workDir(String name) throws IOException {
    Path root = Files.createDirectories(Path.of("target", "logs-it").toAbsolutePath());
    try (var old = Files.list(root)) {
      for (Path p : old.filter(p -> p.getFileName().toString().startsWith(name + "-")).toList()) {
        deleteTree(p);
      }
    }
    return Files.createDirectories(root.resolve(name + "-" + UUID.randomUUID().toString().substring(0, 8)));
  }

  private static void deleteTree(Path dir) throws IOException {
    try (var paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  static boolean root() {
    return "root".equals(ProcessHandle.current().info().user().orElse(""));
  }

  /** A server process, its output in {@code log}. */
  static Process start(List<String> command, Path dir, Path log, Map<String, String> env) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
        .redirectOutput(log.toFile());
    pb.environment().putAll(env);
    return pb.start();
  }

  /** {@code command} run as nobody when the tests run as root: OpenSearch refuses to start as root. */
  static List<String> unprivileged(List<String> command) {
    if (!root()) {
      return command;
    }
    List<String> c = new ArrayList<>(List.of("setpriv", "--reuid=65534", "--regid=65534", "--clear-groups"));
    c.addAll(command);
    return c;
  }

  /**
   * Waits until {@code uri} answers 200, or fails with the end of the server's log. A server of the Compose services
   * ({@link TestServices}) has neither a process nor a log here: both are {@code null}.
   */
  static void awaitOk(URI uri, Duration timeout, Process process, Path log) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    String last = "no answer";
    while (System.nanoTime() < deadline) {
      if (process != null && !process.isAlive()) {
        throw new IllegalStateException(uri.getHost() + ":" + uri.getPort() + " exited with " + process.exitValue()
            + ":\n" + tail(log));
      }
      try {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 200) {
          return;
        }
        last = r.statusCode() + " " + r.body().strip();
      } catch (IOException notYet) {
        last = notYet.toString();
      }
      Thread.sleep(250);
    }
    throw new IllegalStateException(uri + " isn't ready after " + timeout.toSeconds() + " s (" + last + "):\n"
        + tail(log));
  }

  /** One request to a fixture, for setting it up. */
  static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  static void stop(Process p) throws InterruptedException {
    if (p == null) {
      return;
    }
    p.destroy();
    if (!p.waitFor(15, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      p.waitFor(5, TimeUnit.SECONDS);
    }
  }

  /** The entries' timestamps as instants: as text {@code …57.5Z} sorts before {@code …57Z}. */
  static List<Instant> instants(List<JsonNode> entries) {
    return entries.stream().map(e -> Instant.parse(e.path("timestamp").asString())).toList();
  }

  private static String tail(Path log) throws IOException {
    if (log == null) {
      return "(a server of the test services: scripts/test-services.sh logs)";
    }
    if (!Files.exists(log)) {
      return "(no log)";
    }
    List<String> lines = Files.readAllLines(log);
    return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
  }
}
