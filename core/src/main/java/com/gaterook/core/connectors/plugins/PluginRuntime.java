package com.gaterook.core.connectors.plugins;

import com.gaterook.core.connectors.Plugins;
import com.gaterook.core.platform.CoreBuild;
import com.gaterook.core.platform.GatewayPaths;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Optional Java support, fetched only after a plugin operation. URLs, archive hashes, file hashes, permissions and
 * link targets are part of the application, never remote instructions. A verified cache works offline; a failed
 * attempt is retried only by an explicit prepare action. No code is run to install the runtime.
 */
public final class PluginRuntime implements AutoCloseable {
  static final String RESOURCE = "/com/gaterook/core/connectors/plugin-runtimes.json";
  private static final Duration DEADLINE = Duration.ofMinutes(15);
  private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
  private final Path home;
  private final JsonNode pin;
  private final Path target;
  private final HttpClient http;
  private final Duration deadline;
  private volatile String state = "missing";
  private volatile String message = "Java support is downloaded once when you first use a plugin. Internet access is required.";
  private volatile Thread worker;
  private boolean checked;
  private boolean closed;

  public PluginRuntime(Path home) {
    this(home, loadPin(platform()), DEADLINE);
  }

  // Package-private injection keeps test URLs out of release configuration.
  PluginRuntime(Path home, JsonNode pin, Duration deadline) {
    this.home = home.toAbsolutePath().normalize();
    this.pin = pin;
    this.deadline = deadline;
    this.target = this.home.resolve("runtimes/java/" + pin.path("sha256").asString("unsupported"));
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).proxy(ProxySelector.getDefault()).build();
  }

  public synchronized Plugins.RuntimeStatus status() {
    checkCache();
    return new Plugins.RuntimeStatus(state, pin.path("version").asString(null), pin.path("size").asLong(0), message);
  }

  public synchronized Optional<Path> cachedHome() {
    checkCache();
    return "ready".equals(state) ? Optional.of(target.resolve(pin.path("home").asString("."))) : Optional.empty();
  }

  /** Idempotent while in flight. Explicit calls also retry a failed or damaged download. */
  public synchronized Plugins.RuntimeStatus prepare() {
    checkCache();
    if (closed || "downloading".equals(state) || "unsupported".equals(state)) return status();
    if ("ready".equals(state) && verifyForLaunch()) return status();
    state = "downloading";
    message = "Downloading Java support (" + Math.round(pin.path("size").asLong() / 1_000_000.0)
        + " MB). This is needed once; cached support works offline.";
    worker = Thread.ofVirtual().name("plugin-runtime-download").unstarted(() -> {
      try {
        install();
        message = null;
        state = "ready";
      } catch (Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        message = "Java support could not be prepared: " + safeMessage(e)
            + " Connect to the internet and retry the plugin action. Existing built-in connections still work.";
        state = "error";
      }
    });
    worker.start();
    return status();
  }

  /** Full integrity check before starting an executable from the mutable cache. Never downloads. */
  public synchronized boolean verifyForLaunch() {
    checkCache();
    if (!"ready".equals(state)) return false;
    try {
      verify(target);
      return true;
    } catch (IOException e) {
      state = "error";
      message = "Cached Java support failed its integrity check. Retry the plugin action to download a verified copy.";
      return false;
    }
  }

  private void checkCache() {
    if (checked) return;
    checked = true;
    if (!pin.isObject()) {
      state = "unsupported";
      message = "Automatic Java support isn't available for this platform. Use a build with a bundled Java runtime.";
      return;
    }
    if (Files.exists(target, NOFOLLOW)) {
      state = "ready";
      verifyForLaunch();
    }
  }

  private void install() throws Exception {
    checkedPath(target);
    Path parent = target.getParent();
    GatewayPaths.ownerOnlyDirectory(parent.getParent());
    GatewayPaths.ownerOnlyDirectory(parent);
    // Serialize installation across processes sharing this home.
    Path lock = parent.resolve(".download.lock");
    checkedPath(lock);
    try (var channel = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        var held = channel.tryLock()) {
      if (held == null) throw new IOException("another GateRook instance is preparing Java support");
      if (Files.exists(target, NOFOLLOW)) {
        try { verify(target); return; } catch (IOException ignored) { /* An explicit retry repairs this cache. */ }
      }
      try (var leftovers = Files.newDirectoryStream(parent, ".java-*")) {
        for (Path leftover : leftovers) removeTree(leftover);
      }
      Path stage = Files.createTempDirectory(parent, ".java-");
      GatewayPaths.ownerOnlyDirectory(stage);
      Path archive = stage.resolve("runtime.zip");
      Path content = stage.resolve("content");
      try {
        download(archive);
        if (!sha256(archive).equals(pin.path("sha256").asString())) throw new IOException("archive SHA-256 mismatch");
        extract(archive, content);
        verify(content);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        removeTree(target);
        Files.move(content, target, StandardCopyOption.ATOMIC_MOVE);
      } finally {
        removeTree(stage);
      }
    }
  }

  private void download(Path archive) throws Exception {
    CompletableFuture<HttpResponse<Path>> answer = http.sendAsync(HttpRequest.newBuilder(URI.create(pin.path("url").asString()))
        .timeout(Duration.ofMinutes(3)).header("User-Agent", "GateRook/" + CoreBuild.VERSION).GET().build(),
        info -> info.statusCode() == 200
            ? HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofFile(archive), pin.path("size").asLong())
            : HttpResponse.BodySubscribers.replacing(null));
    try {
      HttpResponse<Path> response = answer.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200) throw new IOException("download returned HTTP " + response.statusCode());
      if (Files.size(archive) != pin.path("size").asLong()) throw new IOException("incomplete runtime download");
    } finally {
      if (!answer.isDone()) answer.cancel(true);
    }
  }

  private void extract(Path archive, Path directory) throws IOException {
    Files.createDirectory(directory);
    Map<String, JsonNode> expected = new HashMap<>();
    for (JsonNode file : pin.path("files")) expected.put(pin.path("prefix").asString() + file.path("path").asString(), file);
    Set<String> seen = new HashSet<>();
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (entry.isDirectory()) continue; // Only pinned files create directories.
        JsonNode file = expected.get(entry.getName());
        if (file == null || !seen.add(entry.getName())) throw new IOException("unexpected or duplicate archive entry");
        Path output = member(directory, file.path("path").asString());
        Files.createDirectories(output.getParent());
        long size = file.path("size").asLong();
        if (entry.getSize() != size) throw new IOException("unexpected runtime file size");
        // Links are created last, from pinned targets, so extraction never follows one.
        if (file.has("link")) continue;
        try (InputStream in = zip.getInputStream(entry);
            var out = Files.newOutputStream(output, java.nio.file.StandardOpenOption.CREATE_NEW)) {
          byte[] buffer = new byte[64 * 1024];
          long total = 0;
          for (int n; (n = in.read(buffer)) != -1;) {
            total += n;
            if (total > size) throw new IOException("expanded runtime file exceeds its pin");
            out.write(buffer, 0, n);
          }
          if (total != size) throw new IOException("truncated runtime file");
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
          Files.setPosixFilePermissions(output, PosixFilePermissions.fromString(
              file.path("executable").asBoolean(false) ? "rwx------" : "rw-------"));
        }
      }
    }
    if (seen.size() != expected.size()) throw new IOException("missing runtime files");
    for (JsonNode file : pin.path("files")) {
      if (file.has("link")) {
        Path output = member(directory, file.path("path").asString());
        Path link = Path.of(file.path("link").asString());
        if (link.isAbsolute() || !output.getParent().resolve(link).normalize().startsWith(directory)) {
          throw new IOException("unsafe runtime link");
        }
        Files.createSymbolicLink(output, link);
      }
    }
  }

  private void verify(Path directory) throws IOException {
    checkedPath(directory);
    if (!Files.isDirectory(directory, NOFOLLOW)) throw new IOException("missing runtime directory");
    Set<Path> expected = new HashSet<>();
    for (JsonNode file : pin.path("files")) {
      Path path = member(directory, file.path("path").asString());
      expected.add(path);
      for (Path p = path.getParent(); p.startsWith(directory); p = p.getParent()) {
        if (!Files.isDirectory(p, NOFOLLOW)) throw new IOException("unsafe runtime directory");
      }
      if (file.has("link")) {
        if (!Files.isSymbolicLink(path) || !Files.readSymbolicLink(path).toString().equals(file.path("link").asString())
            || !path.toRealPath().startsWith(directory.toRealPath())) throw new IOException("runtime link mismatch");
      } else if (!Files.isRegularFile(path, NOFOLLOW) || Files.size(path) != file.path("size").asLong()
          || !sha256(path).equals(file.path("sha256").asString())
          || (file.path("executable").asBoolean(false) && !Files.isExecutable(path))) {
        throw new IOException("runtime file integrity mismatch");
      }
    }
    try (var tree = Files.walk(directory)) {
      if (tree.anyMatch(p -> !Files.isDirectory(p, NOFOLLOW) && !expected.contains(p))) {
        throw new IOException("unexpected cached runtime file");
      }
    }
  }

  private void checkedPath(Path path) throws IOException {
    Path current = home;
    if (!path.startsWith(home) || path.equals(home)) throw new IOException("unsafe runtime path");
    for (Path part : home.relativize(path)) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new IOException("runtime storage cannot be a symbolic link");
    }
  }

  private static Path member(Path root, String name) throws IOException {
    if (name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/")
        || java.util.Arrays.asList(name.split("/")).contains("..")) throw new IOException("unsafe archive path");
    Path path = root.resolve(name).normalize();
    if (!path.startsWith(root) || path.equals(root)) throw new IOException("unsafe archive path");
    return path;
  }

  private static String sha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(file)) {
        byte[] buffer = new byte[256 * 1024];
        for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  private static void removeTree(Path directory) throws IOException {
    if (!Files.exists(directory, NOFOLLOW)) return;
    try (var tree = Files.walk(directory)) {
      for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }

  private static String safeMessage(Exception e) {
    if (e instanceof java.util.concurrent.TimeoutException) return "the download timed out.";
    if (e instanceof InterruptedException) return "the download was interrupted.";
    // Transport exception messages can expose proxy addresses and local paths.
    return e instanceof IOException && e.getMessage() != null && !e.getMessage().contains("/")
        ? e.getMessage() + "." : "the download or verification failed.";
  }

  static JsonNode loadPin(String platform) {
    try (InputStream in = PluginRuntime.class.getResourceAsStream(RESOURCE)) {
      if (in == null) throw new IOException("Missing runtime pins");
      return JsonMapper.builder().build().readTree(in.readAllBytes()).path(platform);
    } catch (IOException e) { throw new IllegalStateException(e); }
  }

  static String platform() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    String system = os.startsWith("mac") || os.contains("darwin") ? "darwin" : os.startsWith("windows") ? "windows"
        : os.startsWith("linux") ? "linux" : "unsupported";
    String cpu = switch (arch) { case "amd64", "x86_64" -> "x64"; case "aarch64", "arm64" -> "arm64"; default -> "unsupported"; };
    return system + "-" + cpu;
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (worker != null) worker.interrupt();
    http.shutdownNow();
  }
}
