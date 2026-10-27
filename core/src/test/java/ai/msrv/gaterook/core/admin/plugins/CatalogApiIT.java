package ai.msrv.gaterook.core.admin.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.mcpservers.McpServerCatalog;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The signed catalog through the admin API (step K6): the catalog's own {@code catalog/index.json}, signed here with a
 * test key, served over real HTTPS from MockWebServer with a {@code HeldCertificate} — reachable only through the
 * test switch {@code gateway.test.catalog.ca}, which a release ignores. The whole download path runs: the index and
 * its signature, a plugin JAR checked against the signed SHA-256 (and refused with a flipped byte), an MCP server's
 * pinned lockfile, a catalog template installed as templates without its read-only confirmations, the wizard's cards of
 * plugins that aren't installed, and the cache that keeps the Catalog working offline — refused signatures, an outage,
 * a restart — with "Checked … ago".
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogApiIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("Catalog test CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("127.0.0.1")
      .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").signedBy(CA).build();
  private static final Map<String, MockResponse> FILES = new ConcurrentHashMap<>();
  private static final Map<String, byte[]> JARS = new ConcurrentHashMap<>();

  private static MockWebServer server;
  private static KeyPair key;
  private static Path caFile;
  private static TestCore core;
  private static AdminMvc api;
  private static Instant firstCheck;

  @BeforeAll
  static void start() throws Exception {
    server = new MockWebServer();
    server.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF).build().sslSocketFactory());
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        MockResponse r = FILES.get(request.getTarget());
        return r != null ? r : new MockResponse.Builder().code(404).body("not found").build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    caFile = TestGateway.newHome("catalog-ca").resolve("ca.pem");
    Files.writeString(caFile, CA.certificatePem());
    key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

    JARS.put("gw-jira-service-desk-1.3.0.jar", jar(Path.of("..", "plugins", "jira-service-desk", "src", "main",
        "releases", "1.3.0", "mcp-gateway-plugin.json")));
    JARS.put("gw-clickhouse-0.6.1.jar", jar(Path.of("..", "plugins", "clickhouse", "src", "main", "resources",
        "mcp-gateway-plugin.json")));
    JARS.put("gw-rabbitmq-0.3.0.jar", jar(Path.of("..", "plugins", "rabbitmq", "src", "main", "resources",
        "mcp-gateway-plugin.json")));
    JARS.forEach((name, bytes) -> serve("/catalog/" + name, bytes));
    serve("/catalog/github-mcp-0.6.2.package-lock.json", lockfile());
    publish(index(5, Instant.now().plus(30, ChronoUnit.DAYS), List.of()));

    core = TestCore.start("catalog-api", "--gateway.plugins.catalog-url=https://127.0.0.1:" + server.getPort()
        + "/catalog/", "--gateway.plugins.catalog-keys=test-k6=" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(raw(key)), "--gateway.test.catalog.ca=" + caFile, "--gateway.test.fake-connector=true");
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
  }

  @AfterAll
  static void stop() throws IOException {
    if (core != null) {
      core.close();
    }
    if (server != null) {
      server.close();
    }
  }

  // ── The catalog ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void theFirstLookChecksTheCatalogAndOffersItInTheKitsTerms() {
    JsonNode c = api.ok("/api/catalog");
    assertThat(c.path("offline").asBoolean()).as(c.toString()).isFalse();
    assertThat(c.path("problem").isNull()).isTrue();
    assertThat(c.path("source").asString()).isEqualTo("https://127.0.0.1:" + server.getPort()
        + "/catalog/index.json");
    assertThat(c.path("signature").path("keyId").asString()).isEqualTo("test-k6");
    assertThat(c.path("signature").path("sequence").asLong()).isEqualTo(5);
    assertThat(c.path("signature").path("ok").asBoolean()).isTrue();
    firstCheck = Instant.parse(c.path("checkedAt").asString());

    List<String> cards = new ArrayList<>();
    for (JsonNode e : c.path("entries")) {
      cards.add(String.join(" | ", e.path("kind").asString(), e.path("id").asString(), e.path("name").asString(),
          "v" + e.path("version").asString(), e.path("signed").asBoolean() ? "Signed" : "Unsigned",
          e.path("description").asString(), e.path("tools").asInt() + " tools", e.path("icon").asString("-"),
          e.path("installed").isNull() ? "not installed" : e.path("installed").asString()));
    }
    // The kit's Catalog tab (design/pages/connectors.html #catalog), card by card.
    assertThat(cards).containsExactly(
        "plugin | jira-service-desk | Jira Service Desk | v1.3.0 | Signed | This release adds SLA timers: one new "
            + "read-only tool, no new hosts. | 6 tools | puzzle | not installed",
        "plugin | clickhouse | ClickHouse | v0.6.1 | Signed | Analytics queries with row and time limits. Read only. "
            + "| 6 tools | database | not installed",
        "plugin | rabbitmq | RabbitMQ | v0.3.0 | Unsigned | Queue depth, consumers and dead letters. Community code, "
            + "not reviewed. | 4 tools | puzzle | not installed",
        "template | pg-slow-queries | PostgreSQL slow queries | v1.0.0 | Signed | pg_slow_queries and pg_locks as "
            + "read-only SQL tools. No code runs. | 2 tools | file-code | not installed",
        "mcp-server | github-mcp | GitHub | v0.6.2 | Signed | Issues, pull requests and code search. Needs a GitHub "
            + "token. | 26 tools | plug | not installed",
        "template | fake-slow | Fake slow queries | v1.0.0 | Signed | A test template of two reads. | 2 tools | "
            + "file-code | not installed");
    assertThat(c.path("entries").get(3).path("connectionTypes")).extracting(JsonNode::asString)
        .containsExactly("postgres");
  }

  @Test
  @Order(2)
  void aCatalogPluginIsDownloadedAndCheckedAgainstTheSignedHash() throws Exception {
    JsonNode check = api.expect(200, HttpMethod.POST, "/api/plugins/check", "{\"catalog\":\"clickhouse@0.6.1\"}");
    JsonNode r = check.path("report");
    assertThat(check.path("stagingId").asString()).startsWith("stg_");
    assertThat(r.path("signed").asBoolean()).isTrue();
    assertThat(r.path("id").asString()).isEqualTo("clickhouse");
    assertThat(r.path("version").asString()).isEqualTo("0.6.1");
    assertThat(r.path("name").asString()).isEqualTo("ClickHouse");
    assertThat(r.path("file").asString()).isEqualTo("gw-clickhouse-0.6.1.jar");
    assertThat(r.path("sha256").asString()).isEqualTo(sha256(JARS.get("gw-clickhouse-0.6.1.jar")));
    assertThat(r.path("size").asLong()).isEqualTo(JARS.get("gw-clickhouse-0.6.1.jar").length);
    assertThat(r.path("tools")).hasSize(6);
    // "Host, port, database, user and password, from its config schema" (kit dlg-review-clickhouse).
    assertThat(r.path("configPreview")).extracting(JsonNode::asString)
        .containsExactly("Host", "Port", "Database", "User", "Password");
    assertThat(r.path("access").path("hosts")).extracting(JsonNode::asString)
        .containsExactly("{connection.host}:8443", "{connection.host}:8123");
    // Where it will run: this system's sandbox, or none — the review then says "Not sandboxed on this system" (§7.5).
    assertThat(r.has("sandbox")).isTrue();
    if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac")) {
      assertThat(r.path("sandbox").asString()).isEqualTo("sandbox-exec");
    }

    JsonNode rabbit = api.expect(200, HttpMethod.POST, "/api/plugins/check", "{\"catalog\":\"rabbitmq@0.3.0\"}");
    assertThat(rabbit.path("report").path("signed").asBoolean()).as("signed:false in the catalog").isFalse();
    assertThat(rabbit.path("report").path("configPreview")).extracting(JsonNode::asString)
        .containsExactly("Host", "Port", "Virtual host", "User", "Password");

    JsonNode missing = api.expect(404, HttpMethod.POST, "/api/plugins/check", "{\"catalog\":\"clickhouse@9.9.9\"}");
    assertThat(missing.path("title").asString()).isEqualTo("Not in the catalog");
  }

  @Test
  @Order(3)
  void aFlippedByteOfAJarIsRefused() {
    byte[] jar = JARS.get("gw-clickhouse-0.6.1.jar").clone();
    jar[jar.length / 2] ^= 0x01;
    serve("/catalog/gw-clickhouse-0.6.1.jar", jar);
    try {
      JsonNode refused = api.expect(409, HttpMethod.POST, "/api/plugins/check",
          "{\"catalog\":\"clickhouse@0.6.1\"}");
      assertThat(refused.path("title").asString()).isEqualTo("Package doesn't match the signed catalog");
    } finally {
      serve("/catalog/gw-clickhouse-0.6.1.jar", JARS.get("gw-clickhouse-0.6.1.jar"));
    }
  }

  @Test
  @Order(4)
  void anMcpServerOfTheCatalogPinsItsLockfile() throws Exception {
    McpServerCatalog catalog = core.bean(McpServerCatalog.class);
    McpServerCatalog.Entry e = catalog.entry("github-mcp@0.6.2").orElseThrow();
    assertThat(e.name()).isEqualTo("github-mcp");
    assertThat(e.signed()).isTrue();
    assertThat(e.variables()).containsExactly("GITHUB_PERSONAL_ACCESS_TOKEN");
    assertThat(e.pkg().name()).isEqualTo("@modelcontextprotocol/server-github");
    assertThat(e.pkg().version()).isEqualTo("2025.4.8");
    assertThat(e.pkg().integrity()).startsWith("sha512-8N43bQw9");
    assertThat(e.pkg().bin()).isEqualTo("mcp-server-github");
    assertThat(e.pkg().ignoreScripts()).isTrue();
    assertThat(e.pkg().lockfile()).isEqualTo(lockfile());
    assertThat(catalog.entry("github-mcp@9.9.9")).isEmpty();
  }

  @Test
  @Order(5)
  void aCatalogTemplateInstallsAsTemplatesThatWaitForTheirConfirmations() {
    core.connection("dev-pg", "pgfake", Env.DEV, "db.dev.internal");
    core.connection("dev-my", "myfake", Env.DEV, "db.dev.internal");
    JsonNode saved = api.expect(201, HttpMethod.POST, "/api/templates",
        "{\"catalogRef\":\"fake-slow@1.0.0\",\"connection\":\"dev-pg\",\"kind\":\"sql-query\"}");
    assertThat(saved.path("compile").path("ok").asBoolean()).isTrue();
    assertThat(saved.path("compile").path("tools")).extracting(t -> t.path("name").asString())
        .containsExactly("fake_slow_queries", "fake_locks");
    assertThat(saved.path("template").path("catalogRef").asString()).isEqualTo("fake-slow@1.0.0");
    JsonNode list = api.ok("/api/templates");
    assertThat(list).filteredOn(t -> "fake-slow@1.0.0".equals(t.path("catalogRef").asString()))
        .extracting(t -> t.path("name").asString()).containsExactlyInAnyOrder("fake_slow_queries", "fake_locks");
    // The catalog's read-only confirmation never reaches the stored definition: the person confirms here.
    List<String> definitions = core.jdbc().queryForList("SELECT v.definition FROM template_version v JOIN template t "
        + "ON t.id = v.template_id WHERE t.catalog_ref = 'fake-slow@1.0.0'", String.class);
    assertThat(definitions).hasSize(2).allSatisfy(d -> assertThat(d).doesNotContain("readOnlyConfirmed"));

    JsonNode elsewhere = api.expect(422, HttpMethod.POST, "/api/templates",
        "{\"catalogRef\":\"fake-slow@1.0.0\",\"connection\":\"dev-my\"}");
    assertThat(elsewhere.path("detail").asString()).contains("runs on pgfake connections");
    JsonNode postgres = api.expect(422, HttpMethod.POST, "/api/templates",
        "{\"catalogRef\":\"pg-slow-queries@1.0.0\",\"connection\":\"dev-pg\",\"kind\":\"sql-query\"}");
    assertThat(postgres.path("detail").asString()).contains("runs on postgres connections");
    api.expect(422, HttpMethod.POST, "/api/templates", "{\"catalogRef\":\"nope@1.0.0\",\"connection\":\"dev-pg\"}");
    // Installed twice on the same connection: its tools are taken, nothing more is saved.
    JsonNode twice = api.expect(200, HttpMethod.POST, "/api/templates",
        "{\"catalogRef\":\"fake-slow@1.0.0\",\"connection\":\"dev-pg\"}");
    assertThat(twice.path("template").isNull()).isTrue();
    assertThat(twice.path("compile").path("ok").asBoolean()).isFalse();
    assertThat(twice.path("compile").path("errors").toString()).contains("is already a tool on dev-pg");
  }

  @Test
  @Order(6)
  void theWizardOffersPluginsThatArentInstalledAsDisabledSignedCards() {
    JsonNode types = api.ok("/api/connector-types");
    JsonNode clickhouse = card(types, "Databases", "clickhouse");
    assertThat(clickhouse.path("available").asBoolean()).isFalse();
    assertThat(clickhouse.path("reason").asString())
        .isEqualTo("Not installed. Install it from the catalog in Connectors first.");
    assertThat(clickhouse.path("signed").asBoolean()).isTrue();
    assertThat(clickhouse.path("tools").asInt()).isEqualTo(6);
    assertThat(clickhouse.path("kind").asString()).isEqualTo("plugin");
    assertThat(clickhouse.path("icon").asString()).isEqualTo("database");
    JsonNode rabbit = card(types, "HTTP and MCP", "rabbitmq");
    assertThat(rabbit.path("signed").asBoolean()).isFalse();
    assertThat(rabbit.path("available").asBoolean()).isFalse();
  }

  // ── Offline, refused, revoked ───────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(7)
  void anOutageKeepsTheLastGoodCatalogWithItsCheckTime() {
    FILES.put("/catalog/index.json", new MockResponse.Builder().code(503).body("down").build());
    JsonNode c = api.expect(200, HttpMethod.POST, "/api/catalog/refresh", null);
    assertThat(c.path("offline").asBoolean()).isTrue();
    assertThat(Instant.parse(c.path("checkedAt").asString())).as("Checked … ago").isEqualTo(firstCheck);
    assertThat(c.path("signature").path("ok").asBoolean()).isTrue();
    assertThat(c.path("problem").asString()).contains("can't be reached").contains("HTTP 503");
    assertThat(c.path("entries")).hasSize(6);
    // The tab's look doesn't wait on the network again right away.
    assertThat(api.ok("/api/catalog").path("offline").asBoolean()).isTrue();
  }

  @Test
  @Order(8)
  void aFlippedByteOfTheIndexIsRefusedAndNothingOfItIsOffered() throws Exception {
    byte[] next = index(6, Instant.now().plus(30, ChronoUnit.DAYS), List.of());
    byte[] sig = signature(next);
    byte[] flipped = next.clone();
    flipped[flipped.length / 3] ^= 0x01;
    serve("/catalog/index.json", flipped);
    serve("/catalog/index.json.sig", sig);
    JsonNode c = api.expect(200, HttpMethod.POST, "/api/catalog/refresh", null);
    assertThat(c.path("signature").path("ok").asBoolean()).isFalse();
    assertThat(c.path("signature").path("sequence").asLong()).as("the last good one").isEqualTo(5);
    assertThat(c.path("offline").asBoolean()).isTrue();
    assertThat(c.path("problem").asString()).contains("doesn't match");
    assertThat(c.path("entries")).hasSize(6);

    // An index signed long ago that expired: refused, the last good stays.
    publish(index(7, Instant.now().minus(1, ChronoUnit.DAYS), List.of()));
    JsonNode expired = api.expect(200, HttpMethod.POST, "/api/catalog/refresh", null);
    assertThat(expired.path("signature").path("ok").asBoolean()).isFalse();
    assertThat(expired.path("problem").asString()).contains("expired");
  }

  @Test
  @Order(9)
  void aRevokedBuildIsRefused() throws Exception {
    publish(index(8, Instant.now().plus(30, ChronoUnit.DAYS),
        List.of(sha256(JARS.get("gw-clickhouse-0.6.1.jar")))));
    JsonNode c = api.expect(200, HttpMethod.POST, "/api/catalog/refresh", null);
    assertThat(c.path("offline").asBoolean()).as(c.toString()).isFalse();
    assertThat(c.path("signature").path("sequence").asLong()).isEqualTo(8);
    JsonNode refused = api.expect(409, HttpMethod.POST, "/api/plugins/check", "{\"catalog\":\"clickhouse@0.6.1\"}");
    assertThat(refused.path("title").asString()).isEqualTo("Revoked");
    assertThat(refused.path("detail").asString()).contains("leaked test build");
    // A later index without the revocation (this test only: a real release keeps revocations).
    publish(index(9, Instant.now().plus(30, ChronoUnit.DAYS), List.of()));
    api.expect(200, HttpMethod.POST, "/api/catalog/refresh", null);
  }

  @Test
  @Order(10)
  void afterARestartWithoutTheCatalogTheCacheIsShownWithItsAge() throws Exception {
    JsonNode before = api.ok("/api/catalog");
    Instant checked = Instant.parse(before.path("checkedAt").asString());
    Path home = core.home();
    core.close();
    int closedPort;
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closedPort = s.getLocalPort();
    }
    core = TestCore.reopen(home, "--gateway.plugins.catalog-url=https://127.0.0.1:" + closedPort + "/catalog/",
        "--gateway.plugins.catalog-keys=test-k6=" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw(key)),
        "--gateway.test.catalog.ca=" + caFile, "--gateway.test.fake-connector=true");
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
    // Checked minutes ago: the cache is shown as it is, without waiting on the network.
    JsonNode c = api.ok("/api/catalog");
    assertThat(c.path("offline").asBoolean()).isTrue();
    assertThat(Instant.parse(c.path("checkedAt").asString())).isEqualTo(checked);
    assertThat(c.path("signature").path("sequence").asLong()).isEqualTo(9);
    assertThat(c.path("signature").path("ok").asBoolean()).isTrue();
    assertThat(c.path("entries")).hasSize(6);
    // Try again: the catalog can't be reached, the cache stays with its age.
    JsonNode again = api.expect(200, HttpMethod.POST, "/api/catalog/refresh", null);
    assertThat(again.path("offline").asBoolean()).isTrue();
    assertThat(Instant.parse(again.path("checkedAt").asString())).isEqualTo(checked);
    assertThat(again.path("entries")).hasSize(6);
    assertThat(again.path("problem").asString()).contains("can't be reached");
  }

  // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────────────────

  private static JsonNode card(JsonNode types, String group, String type) {
    for (JsonNode g : types.path("groups")) {
      if (g.path("name").asString().equals(group)) {
        for (JsonNode c : g.path("cards")) {
          if (c.path("type").asString().equals(type)) {
            return c;
          }
        }
      }
    }
    throw new AssertionError("No card " + type + " in " + group + ": " + types.path("groups"));
  }

  /**
   * The catalog's source index ({@code catalog/index.json}) as a release signs it: sequence, times, the SHA-256 and
   * size of every artifact it serves, revocations; plus one template for the fake SQL type of this core.
   */
  private static byte[] index(long sequence, Instant expiresAt, List<String> revoked) throws Exception {
    ObjectNode index = (ObjectNode) JSON.readTree(Files.readString(Path.of("..", "catalog", "index.json")));
    index.put("sequence", sequence);
    index.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    index.put("expiresAt", expiresAt.truncatedTo(ChronoUnit.SECONDS).toString());
    ArrayNode entries = (ArrayNode) index.get("entries");
    for (JsonNode e : entries) {
      if (e.path("kind").asString().equals("plugin")) {
        byte[] jar = JARS.get(e.path("artifact").path("url").asString());
        ((ObjectNode) e.path("artifact")).put("sha256", sha256(jar)).put("size", jar.length);
      } else if (e.path("kind").asString().equals("mcp-server")) {
        ((ObjectNode) e.path("npm").path("lockfile")).put("sha256", sha256(lockfile())).put("size",
            lockfile().length);
      }
    }
    entries.add(JSON.readTree("""
        {"kind":"template","id":"fake-slow","version":"1.0.0","name":"Fake slow queries",
         "description":"A test template of two reads.","icon":"file-code","signed":true,"templateKind":"sql-query",
         "connectionTypes":["pgfake"],"tools":2,"definitions":[
          {"name":"fake_slow_queries","sql":"SELECT 1","readOnlyConfirmed":true,
           "nested":{"readOnlyConfirmedFor":"SELECT 1"}},
          {"name":"fake_locks","sql":"SELECT 2","readOnlyConfirmedFor":"SELECT 2"}]}"""));
    ArrayNode r = index.putArray("revoked");
    revoked.forEach(sha -> r.addObject().put("sha256", sha).put("reason", "leaked test build"));
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(index);
  }

  private static void publish(byte[] index) throws Exception {
    serve("/catalog/index.json", index);
    serve("/catalog/index.json.sig", signature(index));
  }

  private static byte[] signature(byte[] index) throws Exception {
    Signature s = Signature.getInstance("Ed25519");
    s.initSign(key.getPrivate());
    s.update(index);
    return ("{\"keyId\":\"test-k6\",\"alg\":\"Ed25519\",\"sig\":\"" + Base64.getEncoder().encodeToString(s.sign())
        + "\"}").getBytes(StandardCharsets.UTF_8);
  }

  private static void serve(String path, byte[] body) {
    FILES.put(path, new MockResponse.Builder().code(200).addHeader("Content-Type", "application/octet-stream")
        .body(new okio.Buffer().write(body)).build());
  }

  /** A plugin JAR with only its manifest: the check reads the manifest and never loads a class. */
  private static byte[] jar(Path manifest) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("mcp-gateway-plugin.json"));
      zip.write(Files.readAllBytes(manifest));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  private static byte[] lockfile() throws IOException {
    return Files.readAllBytes(Path.of("..", "catalog", "mcp-servers", "github-mcp-0.6.2.package-lock.json"));
  }

  private static byte[] raw(KeyPair pair) {
    byte[] spki = pair.getPublic().getEncoded();
    return java.util.Arrays.copyOfRange(spki, spki.length - 32, spki.length);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
