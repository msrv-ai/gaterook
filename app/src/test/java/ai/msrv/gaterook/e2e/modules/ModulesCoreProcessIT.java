package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.e2e.modules.fixture.EchoModule;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import ai.msrv.gaterook.plugin.protocol.ModuleManifests;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GateRook's connector modules through the assembled core, with a real module host and the module channel served
 * over HTTPS from MockWebServer (docs/connector-delivery.md). A module the channel lists is a card among the
 * built-in types before it is installed; asking for the form of one of its connector types installs it, and nobody
 * reviews or confirms anything. Its types are built-in types with another delivery: plain tool names, the modes the
 * connector declares, the connector's own classification and exact action. When its package is gone from the disk,
 * as after a backup restored on another computer, the next call downloads it again and is answered.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModulesCoreProcessIT {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("Modules e2e CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("127.0.0.1")
      .addSubjectAlternativeName("127.0.0.1").signedBy(CA).build();
  private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();
  private static final AtomicInteger DOWNLOADS = new AtomicInteger();
  private static final String SECRET = "cnry-echo-e2e-secret-91c7";

  private static MockWebServer channel;
  private static CoreProcess core;
  private static KeyPair key;
  /** The package of 1.0.0. */
  private static byte[] jar;
  /** Every released package, by version. */
  private static final Map<String, byte[]> RELEASED = new java.util.LinkedHashMap<>();
  private static String token;

  @TempDir
  static Path home;

  private record Answer(int status, String body) {}

  @BeforeAll
  static void start() throws Exception {
    jar = release("1.0.0");
    channel = new MockWebServer();
    channel.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF).build().sslSocketFactory());
    channel.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        byte[] body = FILES.get(request.getTarget());
        if (body != null && request.getTarget().endsWith(".jar")) {
          DOWNLOADS.incrementAndGet();
        }
        return body == null ? new MockResponse.Builder().code(404).build()
            : new MockResponse.Builder().code(200).body(new okio.Buffer().write(body)).build();
      }
    });
    channel.start(InetAddress.getByName("127.0.0.1"), 0);
    key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    publish(index(1, List.of("1.0.0"), List.of()));

    Path ca = home.resolve("channel-ca.pem");
    Files.writeString(ca, CA.certificatePem());
    Path runtimeJar = Files.copy(Path.of(System.getProperty("gateway.pluginHostJar")),
        Files.createDirectories(home.resolve("cache")).resolve("plugin-host.jar"));
    List<String> flags = new ArrayList<>(List.of(
        "-Dgateway.pluginHostJar=" + runtimeJar.toAbsolutePath(),
        "-Dgateway.modules.channel-url=https://127.0.0.1:" + channel.getPort() + "/modules/",
        "-Dgateway.modules.channel-keys=e2e-modules=" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw(key)),
        "-Dgateway.test.catalog.ca=" + ca, "-Dgateway.plugins.aot-cache=false"));
    if (CoreProcess.nativeBinary()) {
      Path resources = home.resolve("cache/resources");
      Files.createDirectories(resources);
      Files.createSymbolicLink(resources.resolve("jre"), Path.of(System.getProperty("java.home")));
      flags.add("-Dgateway.resources=" + resources);
    }
    core = CoreProcess.start(home, CoreProcess.freePort(), flags).bootstrap();
    core.untilReady(Duration.ofSeconds(90));
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
    if (channel != null) {
      channel.close();
    }
  }

  @Test
  @Order(1)
  void aListedModuleIsACardAmongTheBuiltInTypesBeforeItIsInstalled() throws Exception {
    JsonNode modules = json(admin("GET", "/api/modules", null), 200);
    assertThat(modules.at("/channel/configured").asBoolean()).isTrue();
    assertThat(modules.at("/channel/problem").isNull()).as(modules.toString()).isTrue();
    JsonNode echo = module(modules, "echo");
    assertThat(echo.path("state").asString()).isEqualTo("available");
    assertThat(echo.path("installed").asBoolean()).isFalse();
    assertThat(echo.path("version").asString()).isEqualTo("1.0.0");
    assertThat(echo.path("sizeBytes").asLong()).isEqualTo(jar.length);
    assertThat(echo.path("types")).extracting(t -> t.path("id").asString()).containsExactly("echo-db", "echo-log");

    JsonNode card = card("echo-db");
    assertThat(card.path("kind").asString()).as("one of GateRook's own types").isEqualTo("built-in");
    assertThat(card.path("available").asBoolean()).isTrue();
    assertThat(card.at("/module/id").asString()).isEqualTo("echo");
    assertThat(card.at("/module/state").asString()).isEqualTo("available");
    assertThat(card.at("/module/installed").asBoolean()).isFalse();
    assertThat(DOWNLOADS.get()).as("listing downloads nothing").isZero();
  }

  @Test
  @Order(2)
  void aModulePackageIsNeverInstalledAsAPlugin() throws Exception {
    Answer refused = multipart("/api/plugins/check", "echo-1.0.0.jar", jar);
    assertThat(refused.status()).as(refused.body()).isEqualTo(422);
    assertThat(refused.body()).contains("can't be installed as plugins");
  }

  @Test
  @Order(3)
  void askingForATypesFormInstallsItsModuleWithoutAnyQuestion() throws Exception {
    JsonNode form = json(admin("GET", "/api/connector-types/echo-db/form", null), 200);
    assertThat(form.at("/schema/properties/host/title").asString()).isEqualTo("Host");
    assertThat(DOWNLOADS.get()).isEqualTo(1);

    JsonNode echo = module(json(admin("GET", "/api/modules", null), 200), "echo");
    assertThat(echo.path("state").asString()).isEqualTo("ready");
    assertThat(echo.path("installed").asBoolean()).isTrue();
    assertThat(Files.isRegularFile(packageFile())).isTrue();

    // Both of its types are built-in types now, with the connector's own modes; the plugins list knows nothing of it.
    JsonNode types = json(admin("GET", "/api/connector-types", null), 200);
    for (String id : List.of("echo-db", "echo-log")) {
      JsonNode installed = installed(types, id);
      assertThat(installed.path("kind").asString()).isEqualTo("built-in");
      assertThat(installed.path("version").asString()).isEqualTo("1.0.0");
      assertThat(installed.path("state").asString()).isEqualTo("enabled");
    }
    assertThat(card("echo-db").at("/module/state").asString()).isEqualTo("ready");
    JsonNode catalog = json(admin("GET", "/api/catalog", null), 200);
    assertThat(catalog.toString()).doesNotContain("echo-db");
  }

  @Test
  @Order(4)
  void aCallRunsInTheModulesHostWithTheConnectorsOwnClassificationAndSecret() throws Exception {
    json(admin("POST", "/api/connections", """
        {"name":"echo-dev","type":"echo-db","env":"dev","config":{"host":"echo.example.com"},
         "secrets":{"/password":{"source":"local","value":"%s"}}}""".formatted(SECRET)), 201);
    json(admin("POST", "/api/endpoints", "{\"slug\":\"echo\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/echo/connections/echo-dev", "{}"), 200);
    assertThat(modes("echo", "echo-dev")).as("the modes the connector declares, under plain tool names")
        .containsEntry("echo_read", "READ_ONLY").containsEntry("echo_write", "APPROVAL").hasSize(2);
    JsonNode settings = json(admin("GET", "/api/endpoints/echo/settings", null), 200);
    json(admin("PUT", "/api/endpoints/echo/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);
    token = json(admin("POST", "/api/endpoints/echo/tokens", "{\"name\":\"e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"modules-e2e","version":"1"}}}""");

    JsonNode read = call(2, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"hello\"}");
    assertThat(read.at("/result/isError").asBoolean(true)).as(read.toString()).isFalse();
    assertThat(read.at("/result/structuredContent/echo").asString()).isEqualTo("hello");
    assertThat(read.at("/result/structuredContent/host").asString()).isEqualTo("echo.example.com");
    assertThat(read.at("/result/structuredContent/passwordLength").asInt()).as("the secret came by callback")
        .isEqualTo(SECRET.length());
    assertThat(read.at("/result/structuredContent/pid").asLong()).as("not the core's process")
        .isNotEqualTo(core.process.pid());
    assertThat(read.toString()).doesNotContain(SECRET);

    JsonNode denied = call(3, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"forbidden words\"}");
    assertThat(denied.toString()).as("the connector's own classification refuses it")
        .contains("This text isn't echoed on echo-dev.");
  }

  @Test
  @Order(5)
  void aChangeIsApprovedAsTheConnectorDescribesItAndRunsWithItsPreparedState() throws Exception {
    JsonNode pending = call(4, "echo_write", "{\"connection\":\"echo-dev\",\"text\":\"ledger\"}");
    String handle = pending.at("/result/structuredContent/handle").asString();
    assertThat(handle).as(pending.toString()).startsWith("ap_");
    JsonNode detail = json(admin("GET", "/api/approvals/" + handle, null), 200);
    assertThat(detail.at("/action/text").asString()).as("the connector's exact action, not the call's arguments")
        .isEqualTo("WRITE 'ledger' TO echo.example.com");
    assertThat(detail.toString()).doesNotContain("not verified");
    json(admin("POST", "/api/approvals/" + handle + "/decision", "{\"decision\":\"approve\",\"digest\":\""
        + detail.path("digest").asString() + "\",\"via\":\"app\"}"), 200);
    JsonNode status = call(5, "approval_status", "{\"handle\":\"" + handle + "\",\"wait_seconds\":20}");
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString())
        .isEqualTo("completed");
    assertThat(status.at("/result/structuredContent/result/written").asBoolean()).isTrue();
    assertThat(status.at("/result/structuredContent/result/preparedState/preparedFor").asString())
        .as("the state the connector prepared came back to its approved call").isEqualTo("ledger");
  }

  @Test
  @Order(6)
  void aModuleWhosePackageIsGoneIsDownloadedAgainByTheCallThatNeedsIt() throws Exception {
    // As after a backup restored on another computer: the database knows the module, the disk doesn't have it.
    assertThat(admin("POST", "/api/plugins/echo/restart", null).status()).as("no plugin of that name").isEqualTo(404);
    Files.delete(packageFile());
    assertThat(module(json(admin("GET", "/api/modules", null), 200), "echo").path("state").asString())
        .isEqualTo("missing");
    assertThat(modes("echo", "echo-dev")).as("its tools stay listed: the database has its manifest").hasSize(2);

    JsonNode read = call(6, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"again\"}");
    assertThat(read.at("/result/structuredContent/echo").asString()).as(read.toString()).isEqualTo("again");
    assertThat(DOWNLOADS.get()).as("downloaded once more, by the call").isEqualTo(2);
    assertThat(Files.isRegularFile(packageFile())).isTrue();
    assertThat(sha256(Files.readAllBytes(packageFile()))).isEqualTo(sha256(jar));
    assertThat(module(json(admin("GET", "/api/modules", null), 200), "echo").path("state").asString())
        .isEqualTo("ready");
  }

  @Test
  @Order(7)
  void aNewVersionInTheListUpdatesTheModuleByItselfAndRejectsWhatWaitedOnTheOldCode() throws Exception {
    assertThat(call(10, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"x\"}")
        .at("/result/structuredContent/build").asString()).isEqualTo("1.0.0");
    // A change waits for approval: it was prepared and described by 1.0.0.
    JsonNode waiting = call(11, "echo_write", "{\"connection\":\"echo-dev\",\"text\":\"before the update\"}");
    String handle = waiting.at("/result/structuredContent/handle").asString();
    assertThat(handle).as(waiting.toString()).startsWith("ap_");

    release("1.1.0");
    publish(index(2, List.of("1.0.0", "1.1.0"), List.of()));
    json(admin("POST", "/api/modules/refresh", null), 200);
    JsonNode echo = awaitModule("1.1.0", "ready");
    assertThat(echo.path("previousVersion").asString()).isEqualTo("1.0.0");
    assertThat(echo.path("listedVersion").isNull()).isTrue();

    assertThat(call(12, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"x\"}")
        .at("/result/structuredContent/build").asString()).as("the new package serves the call").isEqualTo("1.1.0");
    JsonNode approval = json(admin("GET", "/api/approvals/" + handle, null), 200);
    assertThat(approval.path("status").asString()).isEqualTo("rejected");
    assertThat(approval.toString()).contains("Connector updated to 1.1.0, ask again");
    assertThat(installed(json(admin("GET", "/api/connector-types", null), 200), "echo-db").path("version").asString())
        .isEqualTo("1.1.0");
  }

  @Test
  @Order(8)
  void aRollBackReturnsToTheKeptVersionAndOnlyALaterVersionUpdatesItAgain() throws Exception {
    JsonNode rolled = json(admin("POST", "/api/modules/echo/rollback", "{\"reason\":\"1.1.0 misbehaves\"}"), 200);
    assertThat(rolled.path("version").asString()).isEqualTo("1.0.0");
    assertThat(rolled.path("previousVersion").asString()).isEqualTo("1.1.0");
    assertThat(call(13, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"x\"}")
        .at("/result/structuredContent/build").asString()).isEqualTo("1.0.0");

    // The list still has 1.1.0: a check doesn't bring it back.
    json(admin("POST", "/api/modules/refresh", null), 200);
    Thread.sleep(2_000);
    JsonNode held = module(json(admin("GET", "/api/modules", null), 200), "echo");
    assertThat(held.path("version").asString()).isEqualTo("1.0.0");
    assertThat(held.path("state").asString()).isEqualTo("ready");
    assertThat(held.path("listedVersion").asString()).isEqualTo("1.1.0");

    // A version past the one rolled back from is installed again by itself.
    release("1.2.0");
    publish(index(3, List.of("1.0.0", "1.1.0", "1.2.0"), List.of()));
    json(admin("POST", "/api/modules/refresh", null), 200);
    awaitModule("1.2.0", "ready");
    assertThat(call(14, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"x\"}")
        .at("/result/structuredContent/build").asString()).isEqualTo("1.2.0");
  }

  @Test
  @Order(9)
  void aRevokedPackageIsSwitchedOffAndReplacedByTheBestOneThatIsNot() throws Exception {
    publish(index(4, List.of("1.0.0", "1.1.0"), List.of(sha256(RELEASED.get("1.2.0")))));
    json(admin("POST", "/api/modules/refresh", null), 200);
    // 1.1.0 is the version a person rolled back from: 1.0.0 replaces the revoked package.
    JsonNode echo = awaitModule("1.0.0", "ready");
    assertThat(echo.path("installed").asBoolean()).isTrue();
    assertThat(installed(json(admin("GET", "/api/connector-types", null), 200), "echo-db").path("state").asString())
        .as("its types are on again").isEqualTo("enabled");
    assertThat(call(15, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"x\"}")
        .at("/result/structuredContent/build").asString()).isEqualTo("1.0.0");
  }

  @Test
  @Order(10)
  void aModuleInUseIsNotRemovedAndOneThatIsNotLeavesWithItsTypes() throws Exception {
    Answer inUse = admin("DELETE", "/api/modules/echo", null);
    assertThat(inUse.status()).as(inUse.body()).isEqualTo(409);
    assertThat(inUse.body()).contains("Connections still use Echo");
    assertThat(call(7, "echo_read", "{\"connection\":\"echo-dev\",\"text\":\"still\"}")
        .at("/result/structuredContent/echo").asString()).isEqualTo("still");

    Answer removed = admin("DELETE", "/api/connections/echo-dev", "{\"confirmName\":\"echo-dev\"}");
    assertThat(removed.status()).as(removed.body()).isBetween(200, 204);
    Answer gone = admin("DELETE", "/api/modules/echo", null);
    assertThat(gone.status()).as(gone.body()).isEqualTo(200);
    JsonNode echo = module(json(admin("GET", "/api/modules", null), 200), "echo");
    assertThat(echo.path("state").asString()).as("listed again, not installed").isEqualTo("available");
    assertThat(Files.exists(packageFile())).isFalse();
    assertThat(card("echo-db").at("/module/installed").asBoolean()).isFalse();
  }

  // ── The module and its channel ──────────────────────────────────────────────────────────────────────────────

  /** Builds and releases the package of {@code version}: its bytes are what the channel serves from then on. */
  private static byte[] release(String version) throws Exception {
    byte[] bytes = moduleJar(version);
    RELEASED.put(version, bytes);
    FILES.put("/modules/echo-" + version + ".jar", bytes);
    return bytes;
  }

  /**
   * The module as it is published: its classes, the manifest written from its connectors, and the build it says it
   * is when it answers a call.
   */
  private static byte[] moduleJar(String version) throws Exception {
    List<Connector> connectors = new EchoModule().connectors(null);
    ObjectNode descriptor = (ObjectNode) CoreProcess.JSON.readTree(EchoModule.DESCRIPTOR);
    descriptor.put("version", version);
    ObjectNode manifest = ModuleManifests.write(descriptor, connectors);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(PluginManifest.FILE));
      zip.write(CoreProcess.JSON.writeValueAsBytes(manifest));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("META-INF/echo-build.txt"));
      zip.write(version.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      String folder = EchoModule.class.getPackageName().replace('.', '/') + "/";
      for (String name : List.of("EchoModule", "EchoModule$EchoDb", "EchoModule$EchoLog")) {
        try (InputStream in = EchoModule.class.getResourceAsStream("/" + folder + name + ".class")) {
          zip.putNextEntry(new ZipEntry(folder + name + ".class"));
          zip.write(in.readAllBytes());
          zip.closeEntry();
        }
      }
    }
    return bytes.toByteArray();
  }

  /** The channel's list at {@code sequence}: the given released versions, and the packages it revokes. */
  private static byte[] index(long sequence, List<String> versions, List<String> revoked) throws Exception {
    ObjectNode index = CoreProcess.JSON.createObjectNode();
    index.put("schema", 1).put("sequence", sequence);
    index.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    index.put("expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
    index.putArray("entries");
    for (String version : versions) {
      byte[] bytes = RELEASED.get(version);
      ObjectNode entry = index.withArray("entries").addObject();
      entry.put("kind", "module").put("id", "echo").put("version", version).put("name", "Echo")
          .put("description", "A connector module for tests.");
      entry.putObject("requires").put("gateway", ">=0.1.0 & <99.0.0").put("connectorApi", ">=1.2.0 & <2.0.0");
      entry.putObject("artifact").put("url", "echo-" + version + ".jar").put("sha256", sha256(bytes))
          .put("size", bytes.length);
      ObjectNode db = entry.putArray("types").addObject().put("id", "echo-db").put("name", "Echo DB")
          .put("icon", "database").put("group", "Databases").put("description", "Answers what it is asked.");
      db.putArray("tools").add("echo_read").add("echo_write");
      ObjectNode log = entry.withArray("types").addObject().put("id", "echo-log").put("name", "Echo Log")
          .put("icon", "activity").put("group", "Logs").put("description", "Reads lines that aren't there.");
      log.putArray("tools").add("echolog_tail");
    }
    for (String sha : revoked) {
      index.withArray("revoked").addObject().put("sha256", sha).put("reason", "leaked e2e build");
    }
    if (!index.has("revoked")) {
      index.putArray("revoked");
    }
    return CoreProcess.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(index);
  }

  /** Puts a list on the channel, signed. */
  private static void publish(byte[] index) throws Exception {
    FILES.put("/modules/index.json.sig", signature(key, index));
    FILES.put("/modules/index.json", index);
  }

  /** Waits until the module is at {@code version} in {@code state}. */
  private static JsonNode awaitModule(String version, String state) throws Exception {
    JsonNode echo = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      echo = module(json(admin("GET", "/api/modules", null), 200), "echo");
      if (echo.path("version").asString().equals(version) && echo.path("state").asString().equals(state)) {
        return echo;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("echo didn't reach " + version + " " + state + ": " + echo);
  }

  private static byte[] signature(KeyPair key, byte[] index) throws Exception {
    Signature s = Signature.getInstance("Ed25519");
    s.initSign(key.getPrivate());
    s.update(index);
    return ("{\"keyId\":\"e2e-modules\",\"alg\":\"Ed25519\",\"sig\":\"" + Base64.getEncoder().encodeToString(s.sign())
        + "\"}").getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] raw(KeyPair pair) {
    byte[] spki = pair.getPublic().getEncoded();
    return java.util.Arrays.copyOfRange(spki, spki.length - 32, spki.length);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static Path packageFile() {
    return home.resolve("plugins/echo/1.0.0/plugin.jar");
  }

  // ── The admin API and MCP ───────────────────────────────────────────────────────────────────────────────────

  private static JsonNode module(JsonNode modules, String id) {
    for (JsonNode m : modules.path("modules")) {
      if (m.path("id").asString().equals(id)) {
        return m;
      }
    }
    throw new AssertionError("No module " + id + " in " + modules);
  }

  private static JsonNode card(String type) throws Exception {
    for (JsonNode group : json(admin("GET", "/api/connector-types", null), 200).path("groups")) {
      for (JsonNode card : group.path("cards")) {
        if (card.path("type").asString().equals(type)) {
          return card;
        }
      }
    }
    throw new AssertionError("No card of " + type);
  }

  private static JsonNode installed(JsonNode types, String id) {
    for (JsonNode t : types.path("installed")) {
      if (t.path("id").asString().equals(id)) {
        return t;
      }
    }
    throw new AssertionError("Not installed: " + id);
  }

  private static Map<String, String> modes(String slug, String connection) throws Exception {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    json(admin("GET", "/api/endpoints/" + slug + "/tools", null), 200).forEach(g -> {
      if (g.path("connection").asString().equals(connection)) {
        g.path("tools").forEach(t -> out.put(t.path("name").asString(), t.path("mode").asString()));
      }
    });
    return out;
  }

  private static JsonNode call(int id, String tool, String arguments) throws Exception {
    return mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
        + "\",\"arguments\":" + arguments + "}}");
  }

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(150))
        .header("X-Gateway-Auth", core.secret().sign(method, target, System.currentTimeMillis(), nonce()))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  private static Answer multipart(String target, String fileName, byte[] bytes) throws Exception {
    String boundary = "gw-e2e-" + Long.toHexString(System.nanoTime());
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
        + "\"\r\nContent-Type: application/java-archive\r\n\r\n").getBytes(StandardCharsets.UTF_8));
    body.write(bytes);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    HttpRequest request = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(120))
        .header("X-Gateway-Auth", core.secret().sign("POST", target, System.currentTimeMillis(), nonce()))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
    HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  private static JsonNode json(Answer answer, int status) {
    assertThat(answer.status()).as(answer.body()).isEqualTo(status);
    return CoreProcess.JSON.readTree(answer.body());
  }

  private static JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/echo"))
        .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25")
        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(message)).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return CoreProcess.JSON.readTree(r.body());
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
