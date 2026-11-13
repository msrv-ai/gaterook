package com.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PluginRuntimeTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path home;
  private final MockWebServer server = new MockWebServer();
  private byte[] archive;
  private ObjectNode pin;

  @BeforeEach void setup() throws Exception {
    server.start();
    archive = archive("java");
    pin = JSON.createObjectNode().put("version", "25-test").put("url", server.url("/java.zip").toString())
        .put("sha256", sha(archive)).put("size", archive.length).put("prefix", "jre/").put("home", ".");
    pin.putArray("files").addObject().put("path", "bin/java").put("size", 4).put("sha256", sha("java".getBytes()))
        .put("executable", true);
  }

  @AfterEach void cleanup() { server.close(); }

  @Test void nativeLocatorStartsPreparationOnUseAndKeepsFailedDownloadsOutOfTheCrashPhase() throws Exception {
    Path hostJar = Files.writeString(home.resolve("plugin-host.jar"), "host");
    try (var runtime = runtime()) {
      HostRuntimeLocator locator = new HostRuntimeLocator(null, hostJar.toString(), runtime, true);
      assertThat(locator.available()).isFalse();
      assertThat(locator.runtimeStatus().state()).isEqualTo("missing");
      assertThat(server.getRequestCount()).isZero();
      server.enqueue(new MockResponse.Builder().code(503).build());
      var first = org.junit.jupiter.api.Assertions.assertThrows(HostLauncher.HostStartException.class, locator::requireForLaunch);
      assertThat(first.phase()).isEqualTo("runtime");
      done(runtime);
      var second = org.junit.jupiter.api.Assertions.assertThrows(HostLauncher.HostStartException.class, locator::requireForLaunch);
      assertThat(second.phase()).isEqualTo("runtime");
      assertThat(second.getMessage()).contains("retry");
      assertThat(server.getRequestCount()).isEqualTo(1);
      respond(archive);
      locator.prepareRuntime();
      done(runtime);
      assertThat(locator.available()).isTrue();
      assertThat(locator.verifiedJavaExecutable()).isPresent();
    }
  }

  @Test void legacyBundlesAndDevelopmentJdksNeedNoDownloadAndPlaceholdersAreNotRuntimes() throws Exception {
    Path resources = Files.createDirectories(home.resolve("resources/jre"));
    Files.writeString(resources.resolve("README.txt"), "Java support downloads on first use.");
    Path hostJar = Files.writeString(home.resolve("plugin-host.jar"), "host");
    try (var runtime = runtime()) {
      HostRuntimeLocator development = new HostRuntimeLocator(resources.getParent().toString(), hostJar.toString(), runtime, false);
      assertThat(development.jreHome()).contains(Path.of(System.getProperty("java.home")));
      Path java = Files.createDirectories(resources.resolve("bin")).resolve("java");
      Files.writeString(java, "bundled");
      HostRuntimeLocator bundled = new HostRuntimeLocator(resources.getParent().toString(), hostJar.toString(), runtime, true);
      assertThat(bundled.prepareRuntime().state()).isEqualTo("ready");
      assertThat(bundled.javaExecutable()).contains(java);
      assertThat(server.getRequestCount()).isZero();
    }
  }

  @Test void noStartupNetworkAndVerifiedCacheWorksAcrossRestartsOffline() throws Exception {
    try (var runtime = runtime()) {
      assertThat(runtime.status().state()).isEqualTo("missing");
      assertThat(runtime.cachedHome()).isEmpty();
      assertThat(server.getRequestCount()).isZero();
      respond(archive);
      runtime.prepare();
      done(runtime);
      assertThat(runtime.status().state()).isEqualTo("ready");
      assertThat(runtime.verifyForLaunch()).isTrue();
      assertThat(Files.isExecutable(runtime.cachedHome().orElseThrow().resolve("bin/java"))).isTrue();
    }
    // A new locator must establish trust from the bundled file pins, without trusting a cache marker or network.
    try (var runtime = runtime()) {
      assertThat(runtime.status().state()).isEqualTo("ready");
      runtime.prepare();
      assertThat(server.getRequestCount()).isEqualTo(1);
    }
  }

  @Test void aFailureIsStableUntilExplicitRetryAndRequestsAreCoalesced() throws Exception {
    try (var runtime = runtime()) {
      server.enqueue(new MockResponse.Builder().code(503).build());
      runtime.prepare();
      done(runtime);
      assertThat(runtime.status().state()).isEqualTo("error");
      assertThat(runtime.status().message()).contains("retry", "internet");
      assertThat(runtime.cachedHome()).isEmpty();
      assertThat(server.getRequestCount()).isEqualTo(1);
      server.enqueue(new MockResponse.Builder().body(new Buffer().write(archive))
          .bodyDelay(150, TimeUnit.MILLISECONDS).build());
      for (int i = 0; i < 20; i++) runtime.prepare();
      done(runtime);
      assertThat(runtime.status().state()).isEqualTo("ready");
      assertThat(server.getRequestCount()).isEqualTo(2);
    }
  }

  @Test void archiveHashAndCachedFileTamperingFailClosedThenRetryRepairs() throws Exception {
    try (var runtime = runtime()) {
      byte[] corrupt = archive.clone();
      corrupt[0] ^= 1;
      respond(corrupt);
      runtime.prepare();
      done(runtime);
      assertThat(runtime.status().state()).isEqualTo("error");
      assertThat(runtime.cachedHome()).isEmpty();
      respond(archive);
      runtime.prepare();
      done(runtime);
      Path java = runtime.cachedHome().orElseThrow().resolve("bin/java");
      Files.writeString(java, "evil");
      assertThat(runtime.verifyForLaunch()).isFalse();
      assertThat(runtime.cachedHome()).isEmpty();
      respond(archive);
      runtime.prepare();
      done(runtime);
      assertThat(runtime.status().state()).isEqualTo("ready");
      assertThat(Files.readString(java)).isEqualTo("java");
      assertNoPartialFiles();
    }
  }

  @Test void pinnedFileHashesAndSizesAreEnforcedAfterArchiveVerification() throws Exception {
    ((ObjectNode) pin.path("files").get(0)).put("sha256", "0".repeat(64));
    try (var runtime = runtime()) {
      respond(archive);
      runtime.prepare();
      done(runtime);
      assertThat(runtime.status().state()).isEqualTo("error");
      assertThat(runtime.cachedHome()).isEmpty();
      assertNoPartialFiles();
    }
  }

  @Test void unexpectedEntriesAndTraversalAreNotExtracted() throws Exception {
    archive = zip(List.of("jre/bin/java", "jre/../../escape"), List.of("java", "evil"));
    pin.put("sha256", sha(archive)).put("size", archive.length);
    try (var runtime = runtime()) {
      respond(archive);
      runtime.prepare();
      done(runtime);
      assertThat(runtime.status().state()).isEqualTo("error");
      assertThat(Files.exists(home.resolve("escape"))).isFalse();
      assertNoPartialFiles();
    }
  }

  @Test void symlinkedStorageIsNeverFollowed() throws Exception {
    assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
    Path elsewhere = Files.createDirectory(home.resolve("elsewhere"));
    Files.createSymbolicLink(home.resolve("runtimes"), elsewhere);
    try (var runtime = runtime()) {
      runtime.prepare();
      done(runtime);
      assertThat(runtime.status().state()).isEqualTo("error");
      assertThat(server.getRequestCount()).isZero();
      try (var files = Files.list(elsewhere)) { assertThat(files).isEmpty(); }
    }
  }

  @Test void stalledBodiesHaveADeadlineAndDoNotLeavePartialInstallations() throws Exception {
    try (var runtime = new PluginRuntime(home, pin, Duration.ofMillis(100))) {
      server.enqueue(new MockResponse.Builder().body(new Buffer().write(archive)).bodyDelay(2, TimeUnit.SECONDS).build());
      runtime.prepare();
      done(runtime);
      assertThat(runtime.status().state()).isEqualTo("error");
      assertThat(runtime.status().message()).contains("timed out");
      assertNoPartialFiles();
    }
  }

  @Test void releasePinsUseImmutableHttpsArchivesAndCoverDesktopPlatforms() {
    for (String platform : List.of("darwin-arm64", "darwin-x64", "windows-x64", "linux-x64")) {
      var release = PluginRuntime.loadPin(platform);
      assertThat(release.path("url").asString()).startsWith("https://cdn.azul.com/zulu/bin/zulu25.").endsWith(".zip");
      assertThat(release.path("sha256").asString()).matches("[0-9a-f]{64}");
      assertThat(release.path("files").size()).isGreaterThan(100);
      assertThat(release.path("size").asLong()).isBetween(10_000_000L, 128_000_000L);
    }
  }

  /** Opt-in verification of a real, already downloaded publisher archive; no internet access in the test. */
  @Test void publisherRuntimePreservesLinksLicensesAndLaunchesJava() throws Exception {
    String archivePath = System.getProperty("gateway.test.runtimeArchive");
    assumeTrue(archivePath != null);
    ObjectNode realPin = (ObjectNode) PluginRuntime.loadPin(PluginRuntime.platform()).deepCopy();
    realPin.put("url", server.url("/real.zip").toString());
    try (var runtime = new PluginRuntime(home, realPin, Duration.ofMinutes(2))) {
      respond(Files.readAllBytes(Path.of(archivePath)));
      runtime.prepare();
      long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
      while (runtime.status().state().equals("downloading") && System.nanoTime() < end) Thread.sleep(20);
      assertThat(runtime.status().state()).as(runtime.status().message()).isEqualTo("ready");
      Path jre = runtime.cachedHome().orElseThrow();
      assertThat(Files.isRegularFile(jre.resolve("legal/java.base/LICENSE"))).isTrue();
      Process javaProcess = new ProcessBuilder(jre.resolve("bin/java").toString(), "-version").redirectErrorStream(true).start();
      String output = new String(javaProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(javaProcess.waitFor()).as(output).isZero();
      assertThat(output).contains("25.0.5");
      assertThat(runtime.verifyForLaunch()).isTrue();
      try (var gateway = com.gaterook.core.platform.GatewayPaths.open(home)) {
        PluginPaths paths = new PluginPaths(gateway);
        Path hostJar = Files.copy(Path.of("..", "plugin-host", "target", "plugin-host.jar"),
            paths.cache().resolve("plugin-host.jar"));
        HostRuntimeLocator locator = new HostRuntimeLocator(null, hostJar.toString(), runtime, true);
        assertThat(locator.managedHome()).contains(jre);
        var clock = java.time.Clock.systemUTC();
        AotCacheManager aot = new AotCacheManager(locator, paths, true);
        aot.trainInBackground();
        long trainedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (aot.cacheFor(locator.jreRelease(), hostJar).isEmpty() && System.nanoTime() < trainedBy) Thread.sleep(50);
        assertThat(aot.cacheFor(locator.jreRelease(), hostJar)).as("AOT training with the downloaded runtime").isPresent();
        HostLauncher launcher = new HostLauncher(locator, paths, new SecretCallbackRegistry(),
            new com.gaterook.core.platform.SealedHandles((purpose, bytes) -> new byte[32],
                com.gaterook.plugin.protocol.GppJson.MAPPER, clock), aot,
            () -> java.util.Set.of(7465, 7466), clock);
        Path plugin = paths.jar("jira-service-desk", "1.3.0");
        Files.createDirectories(plugin.getParent());
        Files.copy(Path.of("..", "plugins", "jira-service-desk", "target", "plugins-jira-service-desk-"
            + System.getProperty("gateway.build.version") + "-1.3.0.jar"), plugin);
        var manifest = PluginSupervisorIT.manifestOf(plugin);
        HostProcess host = launcher.start(new HostLauncher.Spec("jira-service-desk", "1.3.0", plugin,
            PluginInstaller.sha256(plugin), 128, manifest.definitionHashes(), List.of()), "info");
        try {
          assertThat(host.loaded().sandbox()).isEqualTo(launcher.sandboxKind());
          assertThat(host.loaded().tools()).isNotEmpty();
        } finally { host.shutdown(Duration.ofSeconds(2)); }
      }

    }
  }

  private PluginRuntime runtime() { return new PluginRuntime(home, pin, Duration.ofSeconds(5)); }
  private void respond(byte[] body) { server.enqueue(new MockResponse.Builder().body(new Buffer().write(body)).build()); }
  private void done(PluginRuntime runtime) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (runtime.status().state().equals("downloading") && System.nanoTime() < end) Thread.sleep(10);
    assertThat(runtime.status().state()).isNotEqualTo("downloading");
  }
  private void assertNoPartialFiles() throws Exception {
    try (var files = Files.walk(home)) { assertThat(files.filter(p -> p.getFileName().toString().startsWith(".java-"))).isEmpty(); }
  }
  private static byte[] archive(String value) throws Exception { return zip(List.of("jre/bin/java"), List.of(value)); }
  private static byte[] zip(List<String> names, List<String> contents) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (int i = 0; i < names.size(); i++) {
        zip.putNextEntry(new ZipEntry(names.get(i)));
        zip.write(contents.get(i).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
  private static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
