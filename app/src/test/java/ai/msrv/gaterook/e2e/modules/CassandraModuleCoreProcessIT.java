package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.e2e.TestServices;
import ai.msrv.gaterook.e2e.platform.CoreProcess;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
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
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Cassandra connector as the connector module a release downloads (docs/connector-delivery.md): the package of
 * {@code modules/cassandra}, listed by a signed module channel over HTTPS, installed by the assembled core when the
 * type's form is asked for, and run in a module host against Apache Cassandra 5.0 of the Compose services (profile
 * {@code cassandra}; {@code -Pservices,cassandra-it}). The connection verifies the node's certificate over TLS; reads
 * run at once, a change waits for approval and runs the approved statement.
 *
 * <p>The keyspace is made with {@code cqlsh} in the service's container: the connector itself makes no keyspace.
 *
 * <p>With {@code -Dgateway.e2e.officialLists=true} the core reads GateRook's published list on GitHub instead of
 * this test's own channel ({@code gateway.lists=official}), and installs the released package: a check of what is
 * published, which needs the network and is never part of a build.
 */
@Tag("cassandra-it")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CassandraModuleCoreProcessIT {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("Modules e2e CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("127.0.0.1")
      .addSubjectAlternativeName("127.0.0.1").signedBy(CA).build();
  private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();
  /** Read GateRook's published list on GitHub instead of this test's own channel. */
  private static final boolean OFFICIAL = Boolean.getBoolean("gateway.e2e.officialLists");
  private static final String KS = "gaterook_module_it";
  private static final String DC = "gw-dc1";

  private static MockWebServer channel;
  private static CoreProcess core;
  private static PluginManifest module;
  private static byte[] jar;
  private static String token;

  @TempDir
  static Path home;

  private record Answer(int status, String body) {}

  @BeforeAll
  static void start() throws Exception {
    assumeTrue(TestServices.enabled(), "The Cassandra module test needs the Compose test services");
    assumeTrue(Files.isReadable(TestServices.file("run/cassandra-certs/node.crt")),
        "Start the cassandra profile: scripts/test-services.sh up cassandra");
    Path built = modulePackage();
    assumeTrue(built != null, "Build modules/cassandra first: mvn -pl modules/cassandra -am package");
    jar = Files.readAllBytes(built);
    try (ZipFile zip = new ZipFile(built.toFile())) {
      module = PluginManifest.read(zip.getInputStream(zip.getEntry(PluginManifest.FILE)).readAllBytes());
    }
    seed();

    channel = new MockWebServer();
    channel.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF).build().sslSocketFactory());
    channel.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        byte[] body = FILES.get(request.getTarget());
        return body == null ? new MockResponse.Builder().code(404).build()
            : new MockResponse.Builder().code(200).body(new okio.Buffer().write(body)).build();
      }
    });
    channel.start(InetAddress.getByName("127.0.0.1"), 0);
    KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    FILES.put("/modules/" + built.getFileName(), jar);
    byte[] index = index(built.getFileName().toString());
    FILES.put("/modules/index.json", index);
    FILES.put("/modules/index.json.sig", signature(key, index));

    Path ca = home.resolve("channel-ca.pem");
    Files.writeString(ca, CA.certificatePem());
    Path runtimeJar = Files.copy(Path.of(System.getProperty("gateway.pluginHostJar")),
        Files.createDirectories(home.resolve("cache")).resolve("plugin-host.jar"));
    List<String> flags = new ArrayList<>(List.of("-Dgateway.pluginHostJar=" + runtimeJar.toAbsolutePath(),
        "-Dgateway.plugins.aot-cache=false"));
    if (OFFICIAL) {
      flags.add("-Dgateway.lists=official");
    } else {
      flags.addAll(List.of("-Dgateway.modules.channel-url=https://127.0.0.1:" + channel.getPort() + "/modules/",
          "-Dgateway.modules.channel-keys=e2e-modules=" + Base64.getUrlEncoder().withoutPadding()
              .encodeToString(raw(key)), "-Dgateway.test.catalog.ca=" + ca));
    }
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
  void cassandraIsACardThatDownloadsAndItsFormInstallsTheModule() throws Exception {
    JsonNode card = card("cassandra");
    assertThat(card.path("name").asString()).isEqualTo("Apache Cassandra");
    assertThat(card.path("kind").asString()).isEqualTo("built-in");
    assertThat(card.path("tools").asInt()).isEqualTo(4);
    assertThat(card.at("/module/installed").asBoolean()).isFalse();
    assertThat(card.at("/module/sizeBytes").asLong()).isEqualTo(jar.length);

    JsonNode form = json(admin("GET", "/api/connector-types/cassandra/form", null), 200);
    assertThat(form.at("/schema/properties/contactPoints").isObject()).as(form.toString()).isTrue();
    JsonNode installed = module(json(admin("GET", "/api/modules", null), 200), "cassandra");
    assertThat(installed.path("state").asString()).isEqualTo("ready");
    assertThat(installed.path("version").asString()).isEqualTo(module.version());
  }

  @Test
  @Order(2)
  void aConnectionIsTestedAndReadThroughTheModulesHost() throws Exception {
    ObjectNode config = CoreProcess.JSON.createObjectNode();
    config.putArray("contactPoints").add("127.0.0.1:" + TestServices.port("GW_CASSANDRA_PORT"));
    config.put("localDataCenter", DC).put("keyspace", KS);
    config.putArray("keyspaces").add(KS);
    config.putObject("credentials").put("user", TestServices.value("GW_CASSANDRA_USER"));
    config.put("tls", true).put("caCertificate", Files.readString(TestServices.file("run/cassandra-certs/node.crt")));
    ObjectNode connection = CoreProcess.JSON.createObjectNode().put("name", "cass-dev").put("type", "cassandra")
        .put("env", "dev");
    connection.set("config", config);
    connection.putObject("secrets").putObject("/credentials/password").put("source", "local")
        .put("value", TestServices.value("GW_CASSANDRA_PASSWORD"));
    json(admin("POST", "/api/connections", connection.toString()), 201);

    JsonNode tested = json(admin("POST", "/api/connections/cass-dev/test", null), 200);
    assertThat(tested.path("ok").asBoolean()).as(tested.toString()).isTrue();
    assertThat(tested.path("serverVersion").asString()).contains("Cassandra 5.");

    json(admin("POST", "/api/endpoints", "{\"slug\":\"cass\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/cass/connections/cass-dev", "{}"), 200);
    assertThat(modes("cass", "cass-dev")).containsEntry("cassandra_query", "READ_ONLY")
        .containsEntry("cassandra_execute", "APPROVAL").hasSize(4);
    JsonNode settings = json(admin("GET", "/api/endpoints/cass/settings", null), 200);
    json(admin("PUT", "/api/endpoints/cass/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);
    token = json(admin("POST", "/api/endpoints/cass/tokens", "{\"name\":\"e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"modules-e2e","version":"1"}}}""");

    JsonNode keyspaces = call(2, "cassandra_list_keyspaces", "{\"connection\":\"cass-dev\"}");
    assertThat(keyspaces.at("/result/isError").asBoolean(true)).as(keyspaces.toString()).isFalse();
    assertThat(keyspaces.at("/result/structuredContent").toString()).contains(KS);

    JsonNode rows = call(3, "cassandra_query", "{\"connection\":\"cass-dev\",\"statement\":\"SELECT id, owner, "
        + "balance FROM " + KS + ".accounts WHERE id = 'a1'\"}");
    assertThat(rows.at("/result/isError").asBoolean(true)).as(rows.toString()).isFalse();
    assertThat(rows.at("/result/structuredContent").toString()).contains("ann").contains("120");

    JsonNode table = call(4, "cassandra_describe_table", "{\"connection\":\"cass-dev\",\"table\":\"accounts\"}");
    assertThat(table.at("/result/structuredContent").toString()).as(table.toString()).contains("balance");

    // The connector's own classification: a write sent as a query is refused before anything runs.
    JsonNode refused = call(5, "cassandra_query", "{\"connection\":\"cass-dev\",\"statement\":\"DELETE FROM " + KS
        + ".accounts WHERE id = 'a1'\"}");
    assertThat(refused.toString()).doesNotContain("\"isError\":false").contains("cassandra_execute");
  }

  @Test
  @Order(3)
  void aChangeWaitsForApprovalAndRunsTheApprovedStatementOnce() throws Exception {
    String statement = "UPDATE " + KS + ".accounts SET balance = 75 WHERE id = 'a1'";
    JsonNode pending = call(6, "cassandra_execute", "{\"connection\":\"cass-dev\",\"statement\":\"" + statement
        + "\"}");
    String handle = pending.at("/result/structuredContent/handle").asString();
    assertThat(handle).as(pending.toString()).startsWith("ap_");
    JsonNode detail = json(admin("GET", "/api/approvals/" + handle, null), 200);
    assertThat(detail.at("/action/text").asString()).as("the connector's exact action").contains(statement);
    json(admin("POST", "/api/approvals/" + handle + "/decision", "{\"decision\":\"approve\",\"digest\":\""
        + detail.path("digest").asString() + "\",\"via\":\"app\"}"), 200);
    JsonNode status = call(7, "approval_status", "{\"handle\":\"" + handle + "\",\"wait_seconds\":20}");
    assertThat(status.at("/result/structuredContent/status").asString()).as(status.toString())
        .isEqualTo("completed");

    JsonNode rows = call(8, "cassandra_query", "{\"connection\":\"cass-dev\",\"statement\":\"SELECT balance FROM "
        + KS + ".accounts WHERE id = 'a1'\"}");
    assertThat(rows.at("/result/structuredContent").toString()).as(rows.toString()).contains("75");
  }

  // ── The service, the package and its channel ────────────────────────────────────────────────────────────────

  /** {@code modules/cassandra/target/cassandra-<version>.jar}, or {@code null} when it isn't built. */
  private static Path modulePackage() throws Exception {
    Path target = Path.of(System.getProperty("gateway.modulesDir", "../modules"), "cassandra", "target");
    if (!Files.isDirectory(target)) {
      return null;
    }
    try (var files = Files.list(target)) {
      return files.filter(f -> f.getFileName().toString().matches("cassandra-\\d+\\.\\d+\\.\\d+\\.jar")).findFirst()
          .orElse(null);
    }
  }

  /** A keyspace with one table and one row, made in the service's container. */
  private static void seed() throws Exception {
    String cql = "CREATE KEYSPACE IF NOT EXISTS " + KS + " WITH replication = {'class': 'SimpleStrategy', "
        + "'replication_factor': 1}; CREATE TABLE IF NOT EXISTS " + KS + ".accounts (id text PRIMARY KEY, owner text, "
        + "balance int); INSERT INTO " + KS + ".accounts (id, owner, balance) VALUES ('a1', 'ann', 120);";
    Process cqlsh;
    try {
      cqlsh = new ProcessBuilder("docker", "exec", "gw-test-cassandra", "cqlsh", "-u",
          TestServices.value("GW_CASSANDRA_USER"), "-p", TestServices.value("GW_CASSANDRA_PASSWORD"), "-e", cql)
          .redirectErrorStream(true).start();
    } catch (java.io.IOException e) {
      assumeTrue(false, "docker isn't available to seed the Cassandra service: " + e.getMessage());
      return;
    }
    String said = new String(cqlsh.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(cqlsh.waitFor(120, TimeUnit.SECONDS)).as(said).isTrue();
    assertThat(cqlsh.exitValue()).as(said).isZero();
  }

  /** The channel's list with the package as its own manifest describes it. */
  private static byte[] index(String file) throws Exception {
    ObjectNode index = CoreProcess.JSON.createObjectNode();
    index.put("schema", 1).put("sequence", 1);
    index.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    index.put("expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
    ObjectNode entry = index.putArray("entries").addObject();
    entry.put("kind", "module").put("id", module.id()).put("version", module.version()).put("name", module.name())
        .put("description", module.description());
    entry.set("requires", module.raw().path("requires"));
    entry.putObject("artifact").put("url", file).put("sha256", sha256(jar)).put("size", jar.length);
    for (PluginManifest.Type type : module.types()) {
      ObjectNode t = entry.withArray("types").addObject().put("id", type.id()).put("name", type.name())
          .put("icon", type.icon()).put("group", type.group()).put("description", type.description());
      type.tools().forEach(tool -> t.withArray("tools").add(tool.name()));
    }
    index.putArray("revoked");
    return CoreProcess.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(index);
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

  private static JsonNode json(Answer answer, int status) {
    assertThat(answer.status()).as(answer.body()).isEqualTo(status);
    return CoreProcess.JSON.readTree(answer.body());
  }

  private static JsonNode mcp(String message) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/cass"))
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
