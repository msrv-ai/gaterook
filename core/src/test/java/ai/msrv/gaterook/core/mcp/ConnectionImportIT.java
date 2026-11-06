package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.secrets.store.FakeVault;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** MCP can hand off discovery and navigate; only the signed desktop admin request can create connections. */
class ConnectionImportIT {
  private static TestCore core;
  private static AdminMvc admin;
  private static McpClient agent;
  private static McpClient other;
  private static FakeVault vault;

  @BeforeAll static void start() {
    core = TestCore.start("connection-import");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("import-source", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.create("import-other", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.create("import-destination", null, EndpointService.StartFrom.EMPTY, null);
    int port = core.bean(LocalListener.class).port();
    TokenService tokens = core.bean(TokenService.class);
    agent = new McpClient(port, "import-source", tokens.issue("import-source", "import-agent", 7, null).token());
    other = new McpClient(port, "import-other", tokens.issue("import-other", "other-agent", 7, null).token());
    admin = new AdminMvc(core.context(), port);
  }

  @AfterAll static void stop() throws Exception {
    if (core != null) core.close();
    if (vault != null) vault.close();
  }

  @Test void fileHandoffKeepsLiteralsPrivateAndOnlyThePersonCanCreateTheSelectedConnections() throws Exception {
    JsonNode session = session();
    ObjectNode manifest = manifest("imported-example");
    ObjectNode candidate = (ObjectNode) manifest.path("connections").get(0);
    candidate.putObject("secrets").putObject("/credentials/password").put("source", "local")
        .put("value", "private-import-test-literal");
    Path file = Path.of(session.path("importFile").asString());
    Files.writeString(file, manifest.toString());
    long count = core.jdbc().queryForObject("select count(*) from connection", Long.class);
    JsonNode result = agent.legacyCall("submit_connection_import", capability(session)).result();
    assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
    SpecSchemas.assertValid("2025-11-25", "CallToolResult", result);
    assertThat(result.toString()).contains("Open import in GateRook", "gaterook://connection-import/")
        .doesNotContain("private-import-test-literal", session.path("submissionToken").asString(), file.toString());
    assertThat(core.jdbc().queryForObject("select count(*) from connection", Long.class)).isEqualTo(count);
    assertThat(Files.exists(file)).isFalse();
    JsonNode review = admin.ok(path(session));
    assertThat(review.path("status").asString()).isEqualTo("review");
    assertThat(review.toString()).doesNotContain("private-import-test-literal");
    assertThat(review.at("/manifest/connections/0/secretSources/0/source").asString()).isEqualTo("local");

    // A retry and a card opening do not write a connection, even with MCP's mutation hint or a guessed tool name.
    assertThat(agent.legacyCall("submit_connection_import", capability(session)).result().path("isError").asBoolean()).isFalse();
    assertThat(agent.legacyCall("open_connection_import", args("sessionId", session.path("id").asString()))
        .result().path("isError").asBoolean()).isFalse();
    for (String forbidden : new String[] {"commit_connection_import", "create_connection", "delete_connection", "update_connection"}) {
      assertThat(agent.legacyCall(forbidden, capability(session)).error().isMissingNode()).isFalse();
    }
    ObjectNode commit = commit("database", "imported-example");
    assertThat(admin.sendUnsigned(HttpMethod.POST, path(session) + "/commit", commit).status()).isEqualTo(401);
    assertThat(core.jdbc().queryForObject("select count(*) from connection", Long.class)).isEqualTo(count);
    JsonNode imported = admin.expect(200, HttpMethod.POST, path(session) + "/commit", commit);
    assertThat(imported.path("connections").toString()).isEqualTo("[\"imported-example\"]");
    assertThat(imported.path("endpoint").asString()).isEqualTo("import-destination");
    assertThat(imported.path("destinations").toString())
        .isEqualTo("[{\"connection\":\"imported-example\",\"endpoint\":\"import-destination\"}]");
    assertThat(core.bean(EndpointService.class).snapshot("import-destination").connections())
        .extracting(c -> c.config().name()).contains("imported-example");
    assertThat(core.bean(ConnectionService.class).get("imported-example").secrets()).hasSize(1);
    assertThat(admin.ok("/api/connections/imported-example").toString()).doesNotContain("private-import-test-literal");
    admin.expect(409, HttpMethod.POST, path(session) + "/commit", commit);
  }

  @Test void modernDiscoveryRequiresBothTheSessionCapabilityAndItsEndpoint() {
    JsonNode session = session();
    ObjectNode schema = capability(session).put("type", "pgfake");
    JsonNode response = agent.modernCall("connection_import_schema", schema, false).result();
    assertThat(response.path("isError").asBoolean()).as(response.toString()).isFalse();
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", response);
    assertThat(response.path("structuredContent").toString()).contains("configSchema", "/credentials/password")
        .doesNotContain(session.path("submissionToken").asString());
    assertThat(other.modernCall("connection_import_schema", schema.deepCopy(), false).result().path("isError").asBoolean()).isTrue();
    ObjectNode badToken = capability(session).put("submissionToken", "00000000-0000-0000-0000-000000000000");
    assertThat(agent.modernCall("connection_import_schema", badToken, false).result().path("isError").asBoolean()).isTrue();
    ObjectNode submit = capability(session);
    submit.set("manifest", manifest("modern-example"));
    JsonNode staged = agent.modernCall("submit_connection_import", submit, false).result();
    assertThat(staged.path("isError").asBoolean()).as(staged.toString()).isFalse();
    SpecSchemas.assertValid("2026-07-28", "CallToolResult", staged);
    assertThat(other.legacyCall("open_connection_import", args("sessionId", session.path("id").asString()))
        .result().path("isError").asBoolean()).isTrue();
    assertThat(core.bean(EndpointService.class).snapshot("import-source").connections()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"2025-11-25", "2026-07-28"})
  void contentOnlyClientsCanReadTheCatalogAndCompleteConnectorSchema(String revision) {
    JsonNode session = session();
    long before = core.jdbc().queryForObject("select count(*) from connection", Long.class);
    for (String type : new String[] {"", "pgfake"}) {
      ObjectNode request = capability(session);
      if (!type.isEmpty()) request.put("type", type);
      JsonNode response = (revision.equals(McpClient.MODERN)
          ? agent.modernCall("connection_import_schema", request, false)
          : agent.legacyCall("connection_import_schema", request)).result();
      assertThat(response.path("isError").asBoolean()).as(response.toString()).isFalse();
      SpecSchemas.assertValid(revision, "CallToolResult", response);
      assertThat(response.path("content").size()).isEqualTo(1);
      assertThat(response.at("/content/0/type").asString()).isEqualTo("text");
      JsonNode textOnly = McpClient.JSON.readTree(response.at("/content/0/text").asString());
      assertThat(textOnly).isEqualTo(response.path("structuredContent"));
      assertThat(textOnly.path("version").asInt()).isEqualTo(1);
      assertThat(response.toString()).doesNotContain(session.path("submissionToken").asString(),
          session.path("importFile").asString());
      JsonNode vault = textOnly.path("storeKinds").get(0);
      assertThat(vault.path("kind").asString()).isEqualTo("vault");
      assertThat(vault.path("configSchema").isObject()).isTrue();
      assertThat(vault.path("secretFields")).isEqualTo(McpClient.JSON.readTree("[\"/auth/token\",\"/auth/secretId\"]"));
      if (type.isEmpty()) {
        assertThat(textOnly.path("types").isArray()).isTrue();
        boolean found = false;
        for (JsonNode connector : textOnly.path("types")) {
          if (connector.path("type").asString().equals("pgfake")) {
            found = true;
            assertThat(connector.path("supported").asBoolean()).isTrue();
          }
        }
        assertThat(found).as("the text catalog includes the installed connector").isTrue();
      } else {
        assertThat(textOnly.path("type").asString()).isEqualTo("pgfake");
        assertThat(textOnly.at("/configSchema/properties/connection/properties/host/type").asString()).isEqualTo("string");
        assertThat(textOnly.path("secretFields")).isEqualTo(McpClient.JSON.readTree("[\"/credentials/password\"]"));
        assertThat(textOnly.path("referenceOnlyFields")).isEqualTo(McpClient.JSON.readTree("""
            [{"pointer":"/credentials/user","kind":"login-user","allowedSources":["reference"],
              "passwordFields":["/credentials/password"]}]
            """));
      }
    }
    assertThat(admin.ok(path(session)).path("status").asString()).isEqualTo("awaiting");
    assertThat(core.jdbc().queryForObject("select count(*) from connection", Long.class)).isEqualTo(before);
  }

  @Test void storeUserReferencesStageWithoutReadingTheStoreAndCommitOnlyWithAPassword() throws Exception {
    vault = new FakeVault();
    ObjectNode store = args("kind", "vault", "name", "import-login-store");
    ObjectNode storeConfig = store.putObject("config").put("address", vault.address()).put("allowPlainHttp", true);
    storeConfig.putObject("auth").put("method", "approle").put("roleId", FakeVault.ROLE_ID);
    store.putObject("secrets").put("/auth/secretId", FakeVault.SECRET_ID);
    admin.expect(201, HttpMethod.POST, "/api/secret-stores", store);

    JsonNode session = session();
    ObjectNode manifest = manifest("imported-store-login");
    ObjectNode candidate = (ObjectNode) manifest.path("connections").get(0);
    ((ObjectNode) candidate.path("config")).putObject("credentials");
    candidate.putObject("secrets").putObject("/credentials/user").put("source", "reference")
        .put("ref", "vault://kv/stage/db#username").put("store", "import-login-store");
    Files.writeString(Path.of(session.path("importFile").asString()), manifest.toString());
    long count = core.jdbc().queryForObject("select count(*) from connection", Long.class);
    int reads = vault.requests.size();
    JsonNode result = agent.legacyCall("submit_connection_import", capability(session)).result();
    assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
    assertThat(vault.requests).hasSize(reads);
    assertThat(core.jdbc().queryForObject("select count(*) from connection", Long.class)).isEqualTo(count);
    assertThat(admin.ok(path(session)).at("/manifest/connections/0/secretSources/0/field").asString())
        .isEqualTo("/credentials/user");

    ObjectNode request = commit("database", "imported-store-login");
    ObjectNode selection = (ObjectNode) request.path("selections").get(0);
    ((ObjectNode) selection.path("config")).putObject("credentials");
    // A user read from a store doesn't stand in for the password the connector requires.
    JsonNode refused = admin.expect(422, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(refused.path("title").asString()).isEqualTo("Check the settings");
    assertThat(vault.requests).hasSize(reads);
    assertThat(core.jdbc().queryForObject("select count(*) from connection", Long.class)).isEqualTo(count);
    // Each credential has its own source: the password may be typed next to a user read from a store.
    selection.putObject("secrets").putObject("/credentials/password").put("source", "local")
        .put("value", "fixture-literal-password");
    admin.expect(200, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(vault.requests).as("committing binds the chosen store without reading it").hasSize(reads);
    var saved = core.bean(ConnectionService.class).get("imported-store-login");
    assertThat(saved.secrets()).extracting(secret -> secret.field()).containsExactlyInAnyOrder(
        "/credentials/user", "/credentials/password");
  }

  @Test void capableChatsReceiveANavigationOnlyImportCardWithoutAnImportAction() {
    ObjectNode capabilities = JsonNodeFactory.instance.objectNode();
    capabilities.putObject("extensions").putObject("io.modelcontextprotocol/ui").putArray("mimeTypes")
        .add("text/html;profile=mcp-app");
    JsonNode tools = agent.modern("tools/list", JsonNodeFactory.instance.objectNode(), capabilities)
        .result().path("tools");
    JsonNode submit = null;
    JsonNode open = null;
    for (JsonNode tool : tools) {
      if (tool.path("name").asString().equals("submit_connection_import")) submit = tool;
      if (tool.path("name").asString().equals("open_connection_import")) open = tool;
      assertThat(tool.path("name").asString()).doesNotContain("commit_connection", "create_connection", "delete_connection");
    }
    String uri = "ui://gaterook/connection-import-card/v1.html";
    assertThat(submit).isNotNull();
    assertThat(submit.at("/_meta/ui/resourceUri").asString()).isEqualTo(uri);
    assertThat(open).isNotNull();
    assertThat(open.at("/_meta/ui/visibility").toString()).isEqualTo("[\"app\"]");
    assertThat(open.at("/inputSchema/properties").propertyNames()).containsExactly("sessionId");
    JsonNode resource = agent.modern("resources/read", args("uri", uri), capabilities).result().path("contents").get(0);
    assertThat(resource.path("text").asString()).contains("Open import in GateRook", "open_connection_import")
        .doesNotContain("submit_connection_import", "submissionToken", "{{KIT_CSS}}", "{{APP_JS}}");
    assertThat(resource.at("/_meta/ui/csp/connectDomains").toString()).isEqualTo("[]");
  }

  @Test void rejectsLiteralCredentialsInToolArgumentsAndCancelledSessions() {
    JsonNode session = session();
    ObjectNode request = capability(session);
    ObjectNode manifest = manifest("rejected-example");
    ((ObjectNode) manifest.path("connections").get(0)).putObject("secrets").putObject("/credentials/password")
        .put("source", "local").put("value", "never-echo-tool-secret");
    request.set("manifest", manifest);
    JsonNode rejected = agent.legacyCall("submit_connection_import", request).result();
    assertThat(rejected.path("isError").asBoolean()).isTrue();
    assertThat(rejected.toString()).doesNotContain("never-echo-tool-secret");
    assertThat(admin.ok(path(session)).path("status").asString()).isEqualTo("awaiting");
    admin.expect(204, HttpMethod.DELETE, path(session), null);
    assertThat(agent.legacyCall("submit_connection_import", capability(session)).result().path("isError").asBoolean()).isTrue();
    assertThat(Files.exists(Path.of(session.path("importFile").asString()))).isFalse();
  }

  @Test void aLaterEndpointRefusalRollsBackEveryConnectionAndSecretInTheBatch() {
    JsonNode session = session();
    ObjectNode discovered = manifest("rollback-first");
    ObjectNode second = ((ObjectNode) discovered.path("connections").get(0)).deepCopy();
    second.put("id", "production").put("name", "rollback-second").put("env", "prod");
    ((tools.jackson.databind.node.ArrayNode) discovered.path("connections")).add(second);
    ObjectNode submit = capability(session);
    submit.set("manifest", discovered);
    assertThat(agent.legacyCall("submit_connection_import", submit).result().path("isError").asBoolean()).isFalse();
    ObjectNode request = commit("database", "rollback-first");
    ObjectNode first = (ObjectNode) request.path("selections").get(0);
    first.putObject("secrets").putObject("/credentials/password").put("source", "local").put("value", "batch-first-password");
    ObjectNode production = first.deepCopy().put("candidateId", "production").put("name", "rollback-second").put("env", "prod");
    ((tools.jackson.databind.node.ArrayNode) request.path("selections")).add(production);
    long before = core.jdbc().queryForObject("select count(*) from connection", Long.class);
    admin.expect(422, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(core.jdbc().queryForObject("select count(*) from connection", Long.class)).isEqualTo(before);
    assertThat(core.jdbc().queryForObject("select count(*) from connection where name like 'rollback-%'", Long.class)).isZero();
    assertThat(admin.ok(path(session)).path("status").asString()).isEqualTo("review");
  }

  @Test void eachReviewedConnectionCanChooseItsOwnEndpointIncludingIncompleteDiscoveries() throws Exception {
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("routed-development", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.create("routed-production", null, EndpointService.StartFrom.EMPTY, null);
    JsonNode session = session();
    ObjectNode discovered = manifest("routed-incomplete");
    ObjectNode first = (ObjectNode) discovered.path("connections").get(0);
    ObjectNode second = first.deepCopy().put("id", "production").put("name", "routed-complete").put("env", "prod");
    ((tools.jackson.databind.node.ArrayNode) discovered.path("connections")).add(second);
    first.putObject("config");
    for (JsonNode candidate : discovered.path("connections")) {
      ((ObjectNode) candidate).putObject("secrets").putObject("/credentials/password")
          .put("source", "local").put("value", "routing-fixture-password");
    }
    Files.writeString(Path.of(session.path("importFile").asString()), discovered.toString());
    assertThat(agent.legacyCall("submit_connection_import", capability(session)).result().path("isError").asBoolean()).isFalse();

    ObjectNode request = JsonNodeFactory.instance.objectNode();
    var selections = request.putArray("selections");
    selections.addObject().put("candidateId", "database").put("name", "routed-incomplete").put("env", "dev")
        .put("endpoint", "routed-development").put("allowIncomplete", true).putObject("config");
    selections.addObject().put("candidateId", "production").put("name", "routed-complete").put("env", "prod")
        .put("endpoint", "routed-production").put("confirm", "danger").set("config", config());
    JsonNode imported = admin.expect(200, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(imported.path("endpoint").isNull()).isTrue();
    assertThat(imported.path("destinations").toString()).isEqualTo("""
        [{"connection":"routed-incomplete","endpoint":"routed-development"},{"connection":"routed-complete","endpoint":"routed-production"}]""");
    assertThat(imported.path("needsSetup").get(0).path("name").asString()).isEqualTo("routed-incomplete");
    assertThat(admin.ok("/api/connections/routed-incomplete").path("enabled").asBoolean()).isFalse();
    assertThat(endpoints.snapshot("routed-development").connections()).extracting(c -> c.config().name())
        .containsExactly("routed-incomplete");
    assertThat(endpoints.snapshot("routed-production").connections()).extracting(c -> c.config().name())
        .containsExactly("routed-complete");
    assertThat(endpoints.snapshot("routed-production").env()).isEqualTo(ai.msrv.gaterook.connector.api.Env.PROD);
    assertThat(endpoints.snapshot("import-source").connections()).isEmpty();
  }

  @Test void aRefusedPerConnectionDestinationRollsBackEveryEndpointAndReportsTheSelection() {
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("routed-rollback-a", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.create("routed-rollback-b", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(TokenService.class).issue("routed-rollback-b", "development-access", 7, null);
    JsonNode session = session();
    ObjectNode discovered = manifest("routed-rollback-first");
    ObjectNode second = ((ObjectNode) discovered.path("connections").get(0)).deepCopy()
        .put("id", "production").put("name", "routed-rollback-second").put("env", "prod");
    ((tools.jackson.databind.node.ArrayNode) discovered.path("connections")).add(second);
    ObjectNode submit = capability(session);
    submit.set("manifest", discovered);
    assertThat(agent.legacyCall("submit_connection_import", submit).result().path("isError").asBoolean()).isFalse();
    ObjectNode request = commit("database", "routed-rollback-first");
    request.put("endpoint", "routed-rollback-a");
    ObjectNode first = (ObjectNode) request.path("selections").get(0);
    first.putObject("secrets").putObject("/credentials/password").put("source", "local").put("value", "routing-rollback-password");
    ObjectNode production = first.deepCopy().put("candidateId", "production").put("name", "routed-rollback-second")
        .put("env", "prod").put("confirm", "danger").put("endpoint", "routed-rollback-b");
    ((tools.jackson.databind.node.ArrayNode) request.path("selections")).add(production);
    long connectionsBefore = core.jdbc().queryForObject("select count(*) from connection", Long.class);
    long secretsBefore = core.jdbc().queryForObject("select count(*) from secret", Long.class);
    JsonNode refused = admin.expect(422, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(refused.path("field").asString()).isEqualTo("/selections/1/endpoint");
    assertThat(core.jdbc().queryForObject("select count(*) from connection", Long.class)).isEqualTo(connectionsBefore);
    assertThat(core.jdbc().queryForObject("select count(*) from secret", Long.class)).isEqualTo(secretsBefore);
    assertThat(endpoints.snapshot("routed-rollback-a").connections()).isEmpty();
    assertThat(endpoints.snapshot("routed-rollback-b").connections()).isEmpty();
    assertThat(admin.ok(path(session)).path("status").asString()).isEqualTo("review");
  }

  private static JsonNode session() {
    JsonNode session = admin.expect(201, HttpMethod.POST, "/api/connection-imports",
        args("repository", core.home().resolve("example-repository").toString(), "endpoint", "import-source"));
    assertThat(session.path("endpointUrl").asString())
        .isEqualTo(core.bean(LocalListener.class).origin() + "/mcp/import-source");
    return session;
  }

  private static String path(JsonNode session) { return "/api/connection-imports/" + session.path("id").asString(); }

  private static ObjectNode capability(JsonNode session) {
    return args("sessionId", session.path("id").asString(), "submissionToken", session.path("submissionToken").asString());
  }

  private static ObjectNode manifest(String name) {
    ObjectNode manifest = JsonNodeFactory.instance.objectNode().put("version", 1);
    ObjectNode candidate = manifest.putArray("connections").addObject().put("id", "database")
        .put("name", name).put("type", "pgfake").put("env", "dev");
    candidate.set("config", config());
    candidate.putArray("evidence").add("config/application-dev.yml");
    candidate.putArray("credentialHints");
    candidate.putArray("warnings");
    manifest.putArray("warnings");
    return manifest;
  }

  private static ObjectNode config() {
    ObjectNode config = JsonNodeFactory.instance.objectNode();
    config.putObject("connection").put("host", "database.test");
    config.putObject("credentials").put("user", "example-reader");
    return config;
  }

  private static ObjectNode commit(String candidateId, String name) {
    ObjectNode request = JsonNodeFactory.instance.objectNode().put("endpoint", "import-destination");
    request.putArray("selections").addObject().put("candidateId", candidateId).put("name", name).put("env", "dev")
        .set("config", config());
    return request;
  }
}
