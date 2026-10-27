package ai.msrv.gaterook.core.tunnel.internal;

import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tunnel agents and their pins (ARCHITECTURE.md A6, decision 8; {@code binaries.json} next to this class).
 * {@code cloudflared} comes with the installer: {@code <resources>/cloudflared/<platform>/cloudflared[.exe]}, staged by
 * {@code scripts/stage-cloudflared.mjs} from the same pins. The ngrok agent is downloaded when the person chooses
 * ngrok, into {@code <home>/bin/ngrok/<version>/}. Either is hashed before every start and refused unless its
 * SHA-256 is the pinned one: a binary swapped on disk never runs with the person's tunnel token.
 *
 * <p>A development run (no {@code gateway.resources}, never a release) downloads the pinned cloudflared on first use
 * into {@code <home>/bin/cloudflared/<version>/} instead. Tests of a non-release build replace the pins with
 * {@code gateway.test.tunnel-pins} (a file of the same shape whose platform entries may name a local {@code path}).
 */
public final class TunnelBinaries {

  /** Most a downloaded asset may weigh (cloudflared for Windows is 55 MB). */
  static final long MAX_DOWNLOAD = 128L * 1024 * 1024;

  /**
   * The longest a download may take, first byte to last: the request timeout of {@code java.net.http} ends with the
   * headers, and a connection that stalls after them would leave the download (and ngrok's "Downloading…") forever.
   */
  static final Duration DOWNLOAD_DEADLINE = Duration.ofMinutes(15);

  private static final Logger log = LoggerFactory.getLogger(TunnelBinaries.class);
  private static final String PINS = "/ai/msrv/gaterook/core/tunnel/binaries.json";

  /** One tool on one platform as pinned. */
  public record Pin(String tool, String version, String platform, String url, String sha256, String archive,
      String binary, String binarySha256, Path path) {}

  /** What a tool's state is for Settings › Tunnel. */
  public record Status(String tool, String version, boolean present, boolean sha256Ok, String path, String error) {}

  /** A file as last hashed for the status: its size and time stand for its content until either changes. */
  private record Stamp(long size, FileTime modified, boolean ok) {}

  private final JsonNode pins;
  private final String platform;
  private final Path home;
  private final Path resources;
  private final HttpClient http;
  private final Duration downloadDeadline;
  private final Map<Path, Stamp> stamps = new ConcurrentHashMap<>();
  private volatile String ngrokError;

  public TunnelBinaries(Path home, String resources, String testPins) {
    this(home, resources, testPins, DOWNLOAD_DEADLINE);
  }

  TunnelBinaries(Path home, String resources, String testPins, Duration downloadDeadline) {
    this.downloadDeadline = downloadDeadline;
    this.home = home;
    this.resources = resources == null || resources.isBlank() ? null : Path.of(resources);
    this.platform = platform(System.getProperty("os.name"), System.getProperty("os.arch"));
    this.pins = load(testPins);
    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10)).proxy(ProxySelector.getDefault()).build();
  }

  /** {@code linux-x64}, {@code darwin-arm64}, {@code windows-x64}: the key of the pins and of the bundle. */
  public String platform() {
    return platform;
  }

  public Optional<Pin> pin(String tool) {
    JsonNode t = pins.path(tool);
    JsonNode p = t.path("platforms").path(platform);
    if (!p.isObject()) {
      return Optional.empty();
    }
    String path = p.path("path").asString("");
    return Optional.of(new Pin(tool, t.path("version").asString(""), platform, p.path("url").asString(""),
        p.path("sha256").asString(""), p.path("archive").asString("none"), p.path("binary").asString(tool),
        p.path("binarySha256").asString(""), path.isEmpty() ? null : Path.of(path)));
  }

  // ── cloudflared ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The verified cloudflared to start now.
   *
   * @throws TunnelException when it's missing or doesn't match its pin
   */
  public Path cloudflared() throws TunnelException {
    Pin pin = pin("cloudflared").orElseThrow(() -> new TunnelException("Cloudflare Tunnel isn't available for "
        + platform + "."));
    Path file = cloudflaredLocation(pin);
    if (!Files.isRegularFile(file)) {
      if (resources == null && !BuildFlags.RELEASE && pin.path() == null) {
        install(pin, file);
      } else {
        throw new TunnelException("cloudflared is missing from this installation (" + file + "). Reinstall MCP "
            + "Gateway.");
      }
    }
    verify(pin, file);
    return file;
  }

  public Status cloudflaredStatus() {
    Optional<Pin> pin = pin("cloudflared");
    if (pin.isEmpty()) {
      return new Status("cloudflared", null, false, false, null, "Not available for " + platform);
    }
    Path file = cloudflaredLocation(pin.get());
    boolean present = Files.isRegularFile(file);
    boolean ok = present && matchesCached(pin.get(), file);
    String error = !present ? (resources == null && !BuildFlags.RELEASE ? null : "cloudflared is missing")
        : ok ? null : "cloudflared doesn't match its pinned SHA-256";
    return new Status("cloudflared", pin.get().version(), present, ok, file.toString(), error);
  }

  private Path cloudflaredLocation(Pin pin) {
    if (pin.path() != null) {
      return pin.path();
    }
    if (resources != null) {
      return resources.resolve("cloudflared").resolve(platform).resolve(pin.binary());
    }
    return home.resolve("bin").resolve("cloudflared").resolve(pin.version()).resolve(pin.binary());
  }

  // ── ngrok ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** The verified ngrok agent, or empty while it isn't downloaded. */
  public Optional<Path> ngrok() throws TunnelException {
    Pin pin = pin("ngrok").orElseThrow(() -> new TunnelException("ngrok isn't available for " + platform + "."));
    Path file = ngrokLocation(pin);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    verify(pin, file);
    return Optional.of(file);
  }

  public Status ngrokStatus() {
    Optional<Pin> pin = pin("ngrok");
    if (pin.isEmpty()) {
      return new Status("ngrok", null, false, false, null, "Not available for " + platform);
    }
    Path file = ngrokLocation(pin.get());
    boolean present = Files.isRegularFile(file);
    boolean ok = present && matchesCached(pin.get(), file);
    String error = present && !ok ? "ngrok doesn't match its pinned SHA-256" : present ? null : ngrokError;
    return new Status("ngrok", pin.get().version(), present, ok, file.toString(), error);
  }

  /**
   * Downloads the pinned ngrok agent (the person chose ngrok): the archive must hash to its pin, the agent inside to
   * its own; only then it lands, executable, in {@code <home>/bin/ngrok/<version>/}.
   */
  public synchronized Path installNgrok() throws TunnelException {
    Pin pin = pin("ngrok").orElseThrow(() -> new TunnelException("ngrok isn't available for " + platform + "."));
    Path file = ngrokLocation(pin);
    if (Files.isRegularFile(file) && matches(pin, file)) {
      return file;
    }
    try {
      install(pin, file);
      ngrokError = null;
      return file;
    } catch (TunnelException e) {
      ngrokError = e.getMessage();
      throw e;
    }
  }

  private Path ngrokLocation(Pin pin) {
    if (pin.path() != null) {
      return pin.path();
    }
    return home.resolve("bin").resolve("ngrok").resolve(pin.version()).resolve(pin.binary());
  }

  // ── Download and verification ───────────────────────────────────────────────────────────────────────────────

  private synchronized void install(Pin pin, Path target) throws TunnelException {
    if (Files.isRegularFile(target) && matches(pin, target)) {
      return;
    }
    log.info("Downloading {} {} for {}", pin.tool(), pin.version(), platform);
    byte[] asset = download(pin);
    if (!sha256(asset).equals(pin.sha256())) {
      throw new TunnelException("The downloaded " + pin.tool() + " doesn't match its pinned SHA-256; nothing was "
          + "installed.");
    }
    byte[] binary;
    try {
      binary = switch (pin.archive()) {
        case "none" -> asset;
        case "zip" -> fromZip(asset, pin.binary());
        case "tgz" -> fromTarGz(asset, pin.binary());
        default -> throw new TunnelException("Unknown archive " + pin.archive());
      };
    } catch (IOException e) {
      throw new TunnelException("The downloaded " + pin.tool() + " archive can't be read: " + e.getMessage());
    }
    if (!sha256(binary).equals(pin.binarySha256())) {
      throw new TunnelException("The " + pin.tool() + " agent in the download doesn't match its pinned SHA-256; "
          + "nothing was installed.");
    }
    try {
      Path dir = target.getParent();
      Files.createDirectories(dir);
      GatewayPaths.ownerOnlyDirectory(dir);
      Path tmp = Files.createTempFile(dir, ".download-", ".tmp");
      try {
        Files.write(tmp, binary);
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
          Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwx------"));
        }
        try {
          Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (IOException e) {
      throw new TunnelException("Can't install " + pin.tool() + ": " + e.getMessage());
    }
    log.info("Installed {} {} at {}", pin.tool(), pin.version(), target);
  }

  private byte[] download(Pin pin) throws TunnelException {
    CompletableFuture<HttpResponse<byte[]>> answer = http.sendAsync(HttpRequest.newBuilder(URI.create(pin.url()))
        .timeout(Duration.ofMinutes(3)).header("User-Agent", "mcp-gateway").GET().build(),
        info -> info.statusCode() == 200
            ? HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofByteArray(), MAX_DOWNLOAD)
            : HttpResponse.BodySubscribers.replacing(null));
    HttpResponse<byte[]> rs;
    try {
      rs = answer.get(downloadDeadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      answer.cancel(true);
      throw new TunnelException("Downloading " + pin.tool() + " didn't finish within " + downloadDeadline.toSeconds()
          + " s. Check the connection, then try again.");
    } catch (InterruptedException e) {
      answer.cancel(true);
      Thread.currentThread().interrupt();
      throw new TunnelException("The " + pin.tool() + " download was interrupted.");
    } catch (ExecutionException e) {
      for (Throwable t = e.getCause(); t != null; t = t.getCause() == t ? null : t.getCause()) {
        if (t instanceof IOException && String.valueOf(t.getMessage()).contains("exceeds capacity")) {
          throw new TunnelException("The " + pin.tool() + " download is larger than expected.");
        }
      }
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new TunnelException("Can't download " + pin.tool() + ": " + (cause.getMessage() == null
          ? cause.getClass().getSimpleName() : cause.getMessage()));
    }
    if (rs.statusCode() != 200) {
      throw new TunnelException("Downloading " + pin.tool() + " failed: HTTP " + rs.statusCode() + ".");
    }
    return rs.body();
  }

  /** The one file named {@code name} at the top of a zip; directories and other entries are skipped. */
  static byte[] fromZip(byte[] zip, String name) throws IOException {
    try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
      for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
        if (!e.isDirectory() && e.getName().equals(name)) {
          return bounded(in);
        }
      }
    }
    throw new IOException("no " + name + " in the archive");
  }

  /** The one regular file named {@code name} (optionally {@code ./name}) at the top of a gzipped ustar archive. */
  static byte[] fromTarGz(byte[] tgz, String name) throws IOException {
    try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(tgz))) {
      byte[] header = new byte[512];
      while (in.readNBytes(header, 0, 512) == 512) {
        String entry = cString(header, 0, 100);
        if (entry.isEmpty()) {
          break;
        }
        String prefix = cString(header, 345, 155);
        if (!prefix.isEmpty()) {
          entry = prefix + "/" + entry;
        }
        long size = Long.parseLong(cString(header, 124, 12).strip().isEmpty() ? "0" : cString(header, 124, 12)
            .strip(), 8);
        char type = (char) header[156];
        long padded = (size + 511) / 512 * 512;
        if ((type == '0' || type == '\0') && (entry.equals(name) || entry.equals("./" + name))) {
          if (size > MAX_DOWNLOAD) {
            throw new IOException("entry too large");
          }
          byte[] data = in.readNBytes((int) size);
          if (data.length != size) {
            throw new IOException("truncated archive");
          }
          return data;
        }
        if (in.skip(padded) != padded) {
          throw new IOException("truncated archive");
        }
      }
    }
    throw new IOException("no " + name + " in the archive");
  }

  private static String cString(byte[] b, int offset, int length) {
    int end = offset;
    while (end < offset + length && b[end] != 0) {
      end++;
    }
    return new String(b, offset, end - offset, StandardCharsets.US_ASCII);
  }

  private static byte[] bounded(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[64 * 1024];
    long total = 0;
    for (int n; (n = in.read(buffer)) > 0; ) {
      total += n;
      if (total > MAX_DOWNLOAD) {
        throw new IOException("entry too large");
      }
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  private static void verify(Pin pin, Path file) throws TunnelException {
    if (!matches(pin, file)) {
      throw new TunnelException(file.getFileName() + " doesn't match the SHA-256 pinned for " + pin.tool() + " "
          + pin.version() + ", so it isn't started. Reinstall MCP Gateway"
          + ("ngrok".equals(pin.tool()) ? " or choose ngrok again to download it." : "."));
    }
  }

  /**
   * {@link #matches} for the status shown on every {@code GET /api/tunnel}: cloudflared weighs 40 MB, so the hash is
   * redone only when the file's size or time changed. A start always hashes it in full ({@link #verify}).
   */
  private boolean matchesCached(Pin pin, Path file) {
    try {
      long size = Files.size(file);
      FileTime modified = Files.getLastModifiedTime(file);
      Stamp s = stamps.get(file);
      if (s != null && s.size() == size && s.modified().equals(modified)) {
        return s.ok();
      }
      boolean ok = matches(pin, file);
      stamps.put(file, new Stamp(size, modified, ok));
      return ok;
    } catch (IOException e) {
      return false;
    }
  }

  static boolean matches(Pin pin, Path file) {
    try {
      return sha256(file).equals(pin.binarySha256());
    } catch (IOException e) {
      return false;
    }
  }

  static String sha256(Path file) throws IOException {
    MessageDigest digest = digest();
    try (InputStream in = Files.newInputStream(file)) {
      byte[] buffer = new byte[256 * 1024];
      for (int n; (n = in.read(buffer)) > 0; ) {
        digest.update(buffer, 0, n);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String platform(String osName, String osArch) {
    String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    String name = os.startsWith("mac") || os.contains("darwin") ? "darwin" : os.startsWith("windows") ? "windows"
        : "linux";
    String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
    String a = arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "x64";
    return name + "-" + a;
  }

  private static JsonNode load(String testPins) {
    JsonMapper json = JsonMapper.builder().build();
    if (testPins != null && !testPins.isBlank()) {
      if (BuildFlags.RELEASE) {
        log.warn("gateway.test.tunnel-pins is ignored: this is a release build");
      } else {
        log.warn("Test override: tunnel agents are pinned by {}", testPins);
        try {
          return json.readTree(Files.readAllBytes(Path.of(testPins)));
        } catch (IOException e) {
          throw new IllegalStateException("Can't read " + testPins, e);
        }
      }
    }
    try (InputStream in = TunnelBinaries.class.getResourceAsStream(PINS)) {
      if (in == null) {
        throw new IllegalStateException(PINS + " is missing");
      }
      return json.readTree(in.readAllBytes());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
