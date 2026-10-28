package ai.msrv.gaterook.e2e.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.e2e.platform.CoreProcess;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The plugins admin API through the assembled core (steps K4 and K6), with real plugin hosts and the signed catalog
 * served over HTTPS from MockWebServer (the test switch {@code gateway.test.catalog.ca}): Jira Service Desk 1.2.0 is
 * installed from a file (unsigned: only with the acknowledgement), a connection and an endpoint use it, an agent's
 * {@code jira_add_comment} waits for approval; the update to 1.3.0 from the catalog loads side by side and rejects that
 * request — "Plugin updated to 1.3.0, ask again" — while the new {@code jira_request_sla} starts Off; Roll back returns
 * to 1.2.0; deleting a plugin a connection uses answers 409. On the way: the plugin's {@code connector_type} row points
 * at its plugin row, its type carries the signed badge, the catalog's template {@code pg-slow-queries} compiles on a
 * PostgreSQL connection as two reads, and the catalog's {@code github-mcp} reviews as {@code node <bin>}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PluginsCoreProcessIT {

  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final HeldCertificate CA = new HeldCertificate.Builder().certificateAuthority(0)
      .commonName("Catalog e2e CA").build();
  private static final HeldCertificate LEAF = new HeldCertificate.Builder().commonName("127.0.0.1")
      .addSubjectAlternativeName("127.0.0.1").signedBy(CA).build();
  private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();
  private static final String TOKEN = "cnry-jira-e2e-token-4b1f";

  private static MockWebServer catalog;
  private static KeyPair key;
  private static CoreProcess core;
  private static String token;
  private static String pending;

  @BeforeAll
  static void start() throws Exception {
    catalog = new MockWebServer();
    catalog.useHttps(new HandshakeCertificates.Builder().heldCertificate(LEAF).build().sslSocketFactory());
    catalog.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        byte[] body = FILES.get(request.getTarget());
        return body == null ? new MockResponse.Builder().code(404).build()
            : new MockResponse.Builder().code(200).body(new okio.Buffer().write(body)).build();
      }
    });
    catalog.start(InetAddress.getByName("127.0.0.1"), 0);
    key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    FILES.put("/catalog/gw-jira-service-desk-1.3.0.jar", Files.readAllBytes(jiraJar("1.3.0")));
    FILES.put("/catalog/gw-clickhouse-0.6.1.jar", Files.readAllBytes(plugin("clickhouse", "gw-clickhouse-0.6.1.jar")));
    FILES.put("/catalog/gw-rabbitmq-0.3.0.jar", Files.readAllBytes(plugin("rabbitmq", "gw-rabbitmq-0.3.0.jar")));
    FILES.put("/catalog/github-mcp-0.6.2.package-lock.json", Files.readAllBytes(catalogDir().resolve("mcp-servers")
        .resolve("github-mcp-0.6.2.package-lock.json")));
    byte[] index = index(1, List.of());
    FILES.put("/catalog/index.json", index);
    FILES.put("/catalog/index.json.sig", signature(key, index));

    Path home = CoreProcess.newHome("plugins");
    Path ca = home.resolve("catalog-ca.pem");
    Files.writeString(ca, CA.certificatePem());
    List<String> flags = new ArrayList<>(List.of(
        "-Dgateway.pluginHostJar=" + Path.of(System.getProperty("gateway.pluginHostJar")).toAbsolutePath(),
        "-Dgateway.plugins.catalog-url=https://127.0.0.1:" + catalog.getPort() + "/catalog/",
        "-Dgateway.plugins.catalog-keys=e2e-k6=" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw(key)),
        "-Dgateway.test.catalog.ca=" + ca, "-Dgateway.plugins.aot-cache=false"));
    if (CoreProcess.nativeBinary()) {
      // A native core has no java.home: the bundle's jre is this test's JDK.
      Path resources = home.resolve("resources");
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
    if (catalog != null) {
      catalog.close();
    }
  }

  @Test
  @Order(1)
  void jira120InstallsFromAFileOnlyWithTheUnsignedAcknowledgement() throws Exception {
    JsonNode check = json(multipart("/api/plugins/check", "gw-jira-service-desk-1.2.0.jar",
        Files.readAllBytes(jiraJar("1.2.0"))), 200);
    JsonNode report = check.path("report");
    assertThat(report.path("id").asString()).isEqualTo("jira-service-desk");
    assertThat(report.path("version").asString()).isEqualTo("1.2.0");
    assertThat(report.path("name").asString()).isEqualTo("Jira Service Desk");
    assertThat(report.path("file").asString()).isEqualTo("gw-jira-service-desk-1.2.0.jar");
    assertThat(report.path("signed").asBoolean()).as("the catalog lists 1.3.0 only").isFalse();
    assertThat(report.path("tools")).hasSize(5);
    String staging = check.path("stagingId").asString();

    Answer refused = admin("POST", "/api/plugins/install", "{\"stagingId\":\"" + staging + "\"}");
    assertThat(refused.status()).as(refused.body()).isEqualTo(422);
    assertThat(refused.body()).contains("acknowledgeUnsigned");
    json(admin("POST", "/api/plugins/install", "{\"stagingId\":\"" + staging + "\",\"acknowledgeUnsigned\":true}"),
        200);

    JsonNode type = installed("jira-service-desk");
    assertThat(type.path("kind").asString()).isEqualTo("plugin");
    assertThat(type.path("version").asString()).isEqualTo("1.2.0");
    assertThat(type.path("tools").asInt()).isEqualTo(5);
    assertThat(type.path("signed").isBoolean()).as("the badge of K4's follow-up").isTrue();
    assertThat(type.path("signed").asBoolean()).isFalse();
    assertThat(pluginIdOf("jira-service-desk")).isEqualTo("jira-service-desk");
  }

  @Test
  @Order(2)
  void aMutationOfThePluginWaitsForApproval() throws Exception {
    json(admin("POST", "/api/connections", """
        {"name":"acme-jsm","type":"jira-service-desk","env":"dev",
         "config":{"baseUrl":"https://acme.atlassian.example","deployment":"cloud","email":"svc@acme.example"},
         "secrets":{"/apiToken":{"source":"local","value":"%s"}}}""".formatted(TOKEN)), 201);
    json(admin("POST", "/api/endpoints", "{\"slug\":\"jsm\",\"startFrom\":\"empty\"}"), 201);
    json(admin("POST", "/api/endpoints/jsm/connections/acme-jsm", "{}"), 200);
    assertThat(modes("jsm", "acme-jsm")).as("plugin tools start in Approval").containsEntry("jira_add_comment",
        "APPROVAL").containsEntry("jira_search_requests", "APPROVAL").hasSize(5);
    JsonNode settings = json(admin("GET", "/api/endpoints/jsm/settings", null), 200);
    json(admin("PUT", "/api/endpoints/jsm/settings", "{\"approvalWait\":{\"firstWaitSeconds\":0},\"version\":"
        + settings.path("version").asLong() + "}"), 200);
    token = json(admin("POST", "/api/endpoints/jsm/tokens", "{\"name\":\"e2e\",\"ttlDays\":1}"), 201)
        .path("token").asString();
    mcp("""
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},
         "clientInfo":{"name":"plugins-e2e","version":"1"}}}""");

    JsonNode call = mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
        + "\"jira_add_comment\",\"arguments\":{\"connection\":\"acme-jsm\",\"issueKey\":\"SD-123\","
        + "\"body\":\"Restarted the payment worker.\"}}}");
    pending = call.at("/result/structuredContent/handle").asString();
    assertThat(pending).as(call.toString()).startsWith("ap_");
    JsonNode approval = json(admin("GET", "/api/approvals/" + pending, null), 200);
    assertThat(approval.path("status").asString()).isEqualTo("pending");
    // The exact action is the core's: the arguments the plugin gets; the plugin's own words come second (§3, §9).
    assertThat(approval.path("action").path("kind").asString()).isEqualToIgnoringCase("json");
    assertThat(approval.path("action").path("text").asString())
        .isEqualTo("{\"body\":\"Restarted the payment worker.\",\"issueKey\":\"SD-123\"}");
    assertThat(approval.path("fields").get(0).path("label").asString())
        .isEqualTo("Described by jira-service-desk 1.2.0 (unsigned), not verified");
    assertThat(approval.path("fields").get(0).path("text").asString()).contains("SD-123");
  }

  @Test
  @Order(3)
  void theUpdateTo130FromTheCatalogRejectsTheWaitingRequestAndItsNewToolStartsOff() throws Exception {
    // A release the catalog lists as unsigned loads only with the acknowledgement, as an unsigned install (§9).
    publish(index(2, List.of(), "jira-service-desk"));
    Answer unsigned = admin("POST", "/api/plugins/jira-service-desk/update", "{\"to\":\"1.3.0\"}");
    assertThat(unsigned.status()).as(unsigned.body()).isEqualTo(422);
    assertThat(unsigned.body()).contains("Unsigned plugin").contains("acknowledgeUnsigned");
    assertThat(installed("jira-service-desk").path("version").asString()).isEqualTo("1.2.0");
    assertThat(json(admin("GET", "/api/approvals/" + pending, null), 200).path("status").asString())
        .isEqualTo("pending");
    publish(index(3, List.of()));

    JsonNode c = json(admin("GET", "/api/catalog", null), 200);
    assertThat(c.path("offline").asBoolean()).as(c.toString()).isFalse();
    JsonNode jira = entry(c, "jira-service-desk");
    assertThat(jira.path("installed").asString()).isEqualTo("1.2.0");
    assertThat(jira.path("version").asString()).isEqualTo("1.3.0");

    JsonNode preview = json(admin("GET", "/api/plugins/jira-service-desk/update-preview?to=1.3.0", null), 200);
    assertThat(preview.path("signed").asBoolean()).isTrue();
    assertThat(preview.path("package").path("file").asString()).isEqualTo("gw-jira-service-desk-1.3.0.jar");
    assertThat(preview.path("package").path("sha256").asString()).isEqualTo(sha256(Files.readAllBytes(
        jiraJar("1.3.0"))));
    assertThat(preview.path("toolsAdded")).extracting(JsonNode::asString).containsExactly("jira_request_sla");
    assertThat(preview.path("toolsRemoved")).isEmpty();
    assertThat(preview.path("summary").asString()).isEqualTo("1 tool added · annotations, hosts and commands "
        + "unchanged");
    assertThat(preview.path("diff").path("added").asInt()).isEqualTo(2);
    assertThat(preview.path("diff").path("removed").asInt()).isEqualTo(1);
    assertThat(preview.path("diff").toString()).contains("jira_request_sla readOnly").contains("commands: none");

    json(admin("POST", "/api/plugins/jira-service-desk/update", "{\"to\":\"1.3.0\"}"), 200);

    JsonNode rejected = json(admin("GET", "/api/approvals/" + pending, null), 200);
    assertThat(rejected.path("status").asString()).as(rejected.toString()).isEqualTo("rejected");
    assertThat(rejected.toString()).contains("Plugin updated to 1.3.0, ask again");
    Map<String, String> modes = modes("jsm", "acme-jsm");
    assertThat(modes).containsEntry("jira_request_sla", "OFF").containsEntry("jira_add_comment", "APPROVAL")
        .hasSize(6);

    JsonNode type = installed("jira-service-desk");
    assertThat(type.path("version").asString()).isEqualTo("1.3.0");
    assertThat(type.path("signed").asBoolean()).as("1.3.0 is the catalog's signed build").isTrue();
    assertThat(type.path("tools").asInt()).isEqualTo(6);
    assertThat(pluginIdOf("jira-service-desk")).isEqualTo("jira-service-desk");
  }

  @Test
  @Order(4)
  void rollBackReturnsTo120AndDeletingAPluginInUseIsRefused() throws Exception {
    // The kept 1.2.0 must be the file that was installed (its SHA-256 is in the row): changed on disk, no roll back.
    Path kept = core.home.resolve("plugins/jira-service-desk/1.2.0/plugin.jar");
    byte[] original = Files.readAllBytes(kept);
    Files.write(kept, withExtraEntry(original));
    Answer tampered = admin("POST", "/api/plugins/jira-service-desk/rollback", "{}");
    assertThat(tampered.status()).as(tampered.body()).isEqualTo(409);
    assertThat(tampered.body()).contains("Plugin file changed on disk");
    assertThat(installed("jira-service-desk").path("version").asString()).isEqualTo("1.3.0");
    Files.write(kept, original);

    json(admin("POST", "/api/plugins/jira-service-desk/rollback", "{}"), 200);
    JsonNode type = installed("jira-service-desk");
    assertThat(type.path("version").asString()).isEqualTo("1.2.0");
    assertThat(type.path("tools").asInt()).isEqualTo(5);
    assertThat(type.path("signed").asBoolean()).as("1.2.0 came from a file: not the catalog's").isFalse();
    assertThat(modes("jsm", "acme-jsm")).doesNotContainKey("jira_request_sla").hasSize(5);

    Answer inUse = admin("DELETE", "/api/plugins/jira-service-desk", null);
    assertThat(inUse.status()).as(inUse.body()).isEqualTo(409);
    assertThat(inUse.body()).contains("In use");
    assertThat(installed("jira-service-desk").path("version").asString()).isEqualTo("1.2.0");

    // Another build of the installed version, installed again: refused before it touches the installed JAR (the
    // install used to copy it over plugins/<id>/1.2.0/ and only then say "Already installed").
    Path installedJar = core.home.resolve("plugins/jira-service-desk/1.2.0/plugin.jar");
    String before = sha256(Files.readAllBytes(installedJar));
    String staging = json(multipart("/api/plugins/check", "rebuilt-jira-1.2.0.jar",
        withExtraEntry(Files.readAllBytes(jiraJar("1.2.0")))), 200).path("stagingId").asString();
    Answer again = admin("POST", "/api/plugins/install", "{\"stagingId\":\"" + staging
        + "\",\"acknowledgeUnsigned\":true}");
    assertThat(again.status()).as(again.body()).isEqualTo(409);
    assertThat(again.body()).contains("Already installed");
    assertThat(sha256(Files.readAllBytes(installedJar))).as("the installed JAR is untouched").isEqualTo(before);
  }

  /** The same JAR with one more entry: the same plugin and version, other bytes. */
  private static byte[] withExtraEntry(byte[] jar) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jar));
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
      for (java.util.zip.ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        zip.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
        in.transferTo(zip);
        zip.closeEntry();
      }
      zip.putNextEntry(new java.util.zip.ZipEntry("rebuilt.txt"));
      zip.write("another build".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  @Test
  @Order(5)
  void theCatalogsTemplateCompilesOnPostgresAsTwoReads() throws Exception {
    json(admin("POST", "/api/connections", """
        {"name":"dev-db","type":"postgres","env":"dev",
         "config":{"connection":{"host":"db.dev.example","port":5432,"database":"app"},"credentials":{"user":"gw_reader"}},
         "secrets":{"/credentials/password":{"source":"local","value":"cnry-pg-e2e-pass"}}}"""), 201);
    JsonNode saved = json(admin("POST", "/api/templates",
        "{\"catalogRef\":\"pg-slow-queries@1.0.0\",\"connection\":\"dev-db\",\"kind\":\"sql-query\"}"), 201);
    assertThat(saved.path("compile").path("ok").asBoolean()).as(saved.toString()).isTrue();
    assertThat(saved.path("compile").path("tools")).extracting(t -> t.path("name").asString() + ":"
        + t.path("nature").asString()).containsExactly("pg_slow_queries:read", "pg_locks:read");
    assertThat(saved.path("template").path("catalogRef").asString()).isEqualTo("pg-slow-queries@1.0.0");
  }

  @Test
  @Order(6)
  void theCatalogsMcpServerReviewsAsNodeAndItsBin() throws Exception {
    JsonNode review = json(admin("POST", "/api/mcp-servers/review", "{\"catalog\":\"github-mcp@0.6.2\"}"), 200);
    // node as found on the PATH the gateway gives servers, then the package's executable: never npx.
    assertThat(review.path("argv")).hasSize(2);
    assertThat(review.path("argv").get(0).asString()).endsWith("node");
    assertThat(review.path("argv").get(1).asString())
        .isEqualTo("node_modules/@modelcontextprotocol/server-github/dist/index.js");
    assertThat(review.path("env")).extracting(JsonNode::asString).containsExactly("GITHUB_PERSONAL_ACCESS_TOKEN");
    assertThat(review.path("catalogEntry").asString()).isEqualTo("github-mcp@0.6.2");
    assertThat(review.path("notPinned").asBoolean()).isFalse();
    assertThat(core.coreLog()).doesNotContain(TOKEN).doesNotContain("cnry-pg-e2e-pass");
  }

  /**
   * The catalog's {@code github-mcp} added for real (profile {@code mcp-servers}: npm's registry): the C10 runtime runs
   * {@code npm ci --ignore-scripts} from the lockfile the catalog pinned, checks the integrity, starts {@code node
   * <bin>} with the token, and the catalog's card says "Installed as github-mcp" (kit Catalog).
   */
  @Test
  @Order(7)
  @Tag("mcp-servers")
  void theCatalogsGithubMcpInstallsWithNpmCiAndItsCardSaysInstalled() throws Exception {
    String ghToken = "ghp_" + "k6e2e".repeat(7);
    JsonNode added = json(admin("POST", "/api/mcp-servers", "{\"catalog\":\"github-mcp@0.6.2\",\"acknowledge\":true,"
        + "\"secrets\":{\"/env/0/secretRef\":{\"source\":\"local\",\"value\":\"" + ghToken + "\"}}}"), 201);
    assertThat(added.path("name").asString()).isEqualTo("github-mcp");
    assertThat(added.path("tools").asInt()).isEqualTo(26);
    assertThat(Files.isRegularFile(core.home.resolve("mcp/github-mcp/node_modules/@modelcontextprotocol/"
        + "server-github/package.json"))).isTrue();

    JsonNode github = entry(json(admin("GET", "/api/catalog", null), 200), "github-mcp");
    assertThat(github.path("installed").asString()).isEqualTo("github-mcp");
    assertThat(core.coreLog()).doesNotContain(ghToken);
  }

  /**
   * The catalog revokes the build the core runs (Jira 1.2.0 since the roll back): the plugin and its type go off and
   * can't be switched on again; an update to a build that isn't revoked brings them back.
   */
  @Test
  @Order(8)
  void aRevokedBuildIsSwitchedOffUntilAGoodOneReplacesIt() throws Exception {
    JsonNode c = publish(index(4, List.of(sha256(Files.readAllBytes(jiraJar("1.2.0"))))));
    assertThat(c.path("signature").path("sequence").asLong()).as(c.toString()).isEqualTo(4);

    JsonNode type = installed("jira-service-desk");
    assertThat(type.path("version").asString()).isEqualTo("1.2.0");
    assertThat(type.path("enabled").asBoolean()).as("revoked: switched off").isFalse();
    Answer enable = admin("POST", "/api/plugins/jira-service-desk/enable", "{}");
    assertThat(enable.status()).as(enable.body()).isEqualTo(409);
    assertThat(enable.body()).contains("Revoked").contains("leaked e2e build");

    json(admin("POST", "/api/plugins/jira-service-desk/update", "{\"to\":\"1.3.0\"}"), 200);
    JsonNode back = installed("jira-service-desk");
    assertThat(back.path("version").asString()).isEqualTo("1.3.0");
    assertThat(back.path("enabled").asBoolean()).isTrue();
    assertThat(back.path("signed").asBoolean()).isTrue();
  }

  // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────────────────

  private static Path jiraJar(String version) {
    return plugin("jira-service-desk", "plugins-jira-service-desk-0.4.1-" + version + ".jar");
  }

  private static Path plugin(String module, String file) {
    return Path.of(System.getProperty("gateway.pluginsDir"), module, "target", file);
  }

  private static Path catalogDir() {
    return Path.of(System.getProperty("gateway.catalogDir"));
  }

  /** Serves a new signed index and has the core check the catalog again; returns what the refresh answered. */
  private static JsonNode publish(byte[] index) throws Exception {
    FILES.put("/catalog/index.json", index);
    FILES.put("/catalog/index.json.sig", signature(key, index));
    return json(admin("POST", "/api/catalog/refresh", null), 200);
  }

  /**
   * The catalog's source index signed as a release signs it, with the hashes of the JARs and lockfile served; the
   * plugins named in {@code unsigned} are listed with {@code signed:false}.
   */
  private static byte[] index(long sequence, List<String> revoked, String... unsigned) throws IOException {
    ObjectNode index = (ObjectNode) CoreProcess.JSON.readTree(Files.readString(catalogDir().resolve("index.json")));
    index.put("sequence", sequence);
    ArrayNode r = index.putArray("revoked");
    revoked.forEach(sha -> r.addObject().put("sha256", sha).put("reason", "leaked e2e build"));
    index.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    index.put("expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
    for (JsonNode e : (ArrayNode) index.get("entries")) {
      if (List.of(unsigned).contains(e.path("id").asString())) {
        ((ObjectNode) e).put("signed", false);
      }
      ObjectNode ref = e.path("kind").asString().equals("plugin") ? (ObjectNode) e.path("artifact")
          : e.path("kind").asString().equals("mcp-server") ? (ObjectNode) e.path("npm").path("lockfile") : null;
      if (ref != null) {
        byte[] file = FILES.get("/catalog/" + ref.path("url").asString());
        ref.put("sha256", sha256(file)).put("size", file.length);
      }
    }
    return CoreProcess.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(index);
  }

  private static byte[] signature(KeyPair key, byte[] index) throws Exception {
    Signature s = Signature.getInstance("Ed25519");
    s.initSign(key.getPrivate());
    s.update(index);
    return ("{\"keyId\":\"e2e-k6\",\"alg\":\"Ed25519\",\"sig\":\"" + Base64.getEncoder().encodeToString(s.sign())
        + "\"}").getBytes(StandardCharsets.UTF_8);
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

  private static JsonNode entry(JsonNode catalog, String id) {
    for (JsonNode e : catalog.path("entries")) {
      if (e.path("id").asString().equals(id)) {
        return e;
      }
    }
    throw new AssertionError("No catalog entry " + id + ": " + catalog);
  }

  private static JsonNode installed(String id) throws Exception {
    for (JsonNode t : json(admin("GET", "/api/connector-types", null), 200).path("installed")) {
      if (t.path("id").asString().equals(id)) {
        return t;
      }
    }
    throw new AssertionError("Not installed: " + id);
  }

  /** {@code connector_type.plugin_id} as the core wrote it, read from the gateway's database. */
  private static String pluginIdOf(String type) throws Exception {
    org.sqlite.SQLiteConfig readOnly = new org.sqlite.SQLiteConfig();
    readOnly.setReadOnly(true);
    try (Connection c = readOnly.createConnection("jdbc:sqlite:" + core.home.resolve("gateway.db"));
        var st = c.prepareStatement("SELECT plugin_id FROM connector_type WHERE id = ?")) {
      st.setString(1, type);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  /** Tool → mode of one connection on an endpoint. */
  private static Map<String, String> modes(String slug, String connection) throws Exception {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    json(admin("GET", "/api/endpoints/" + slug + "/tools", null), 200).forEach(g -> {
      if (g.path("connection").asString().equals(connection)) {
        g.path("tools").forEach(t -> out.put(t.path("name").asString(), t.path("mode").asString()));
      }
    });
    return out;
  }

  private record Answer(int status, String body) {}

  private static URI uri(String target) {
    return URI.create("http://127.0.0.1:" + core.port + target);
  }

  private static Answer admin(String method, String target, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(target)).timeout(Duration.ofSeconds(120))
        .header("X-Gateway-Auth", core.secret().sign(method, target, System.currentTimeMillis(), nonce()))
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body());
  }

  /** A JAR as the UI uploads it: multipart, the field {@code file}. */
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
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(uri("/mcp/jsm"))
        .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
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
