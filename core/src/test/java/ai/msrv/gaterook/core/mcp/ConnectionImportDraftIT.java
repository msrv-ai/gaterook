package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Incomplete imports survive the handoff without creating an executable connection. */
class ConnectionImportDraftIT {
  private static TestCore core;
  private static AdminMvc admin;
  private static McpClient agent;

  @BeforeAll static void start() {
    core = TestCore.start("connection-import-drafts");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("draft-source", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.create("draft-target", null, EndpointService.StartFrom.EMPTY, null);
    int port = core.bean(LocalListener.class).port();
    agent = new McpClient(port, "draft-source", core.bean(TokenService.class)
        .issue("draft-source", "draft-agent", 7, null).token());
    admin = new AdminMvc(core.context(), port);
  }

  @AfterAll static void stop() { if (core != null) core.close(); }

  @Test void preservesAnAvailablePasswordWhileMissingSettingsStayDisabledUntilAFullEdit() throws Exception {
    ObjectNode manifest = manifest("draft-known-password", JsonNodeFactory.instance.objectNode());
    ((ObjectNode) manifest.path("connections").get(0)).putObject("secrets").putObject("/credentials/password")
        .put("source", "local").put("value", "available-private-password");
    JsonNode session = stage(manifest);
    JsonNode result = save(session, "draft-known-password", JsonNodeFactory.instance.objectNode());
    assertThat(result.path("needsSetup").get(0).path("name").asString()).isEqualTo("draft-known-password");
    JsonNode draft = admin.ok("/api/connections/draft-known-password");
    assertThat(draft.path("setupRequired").asBoolean()).isTrue();
    assertThat(draft.path("enabled").asBoolean()).isFalse();
    assertThat(draft.path("verified").asBoolean()).isTrue();
    assertThat(draft.path("secrets")).hasSize(1);
    assertThat(draft.toString()).doesNotContain("available-private-password");
    assertThat(core.bean(EndpointService.class).snapshot("draft-target").pairs())
        .noneMatch(p -> p.config().name().equals("draft-known-password"));
    admin.expect(422, HttpMethod.POST, "/api/connections/draft-known-password/enable", null);
    admin.expect(409, HttpMethod.POST, "/api/connections/draft-known-password/test", null);
    ObjectNode incomplete = args("env", "dev").put("version", draft.path("version").asLong());
    incomplete.set("config", JsonNodeFactory.instance.objectNode());
    admin.expect(422, HttpMethod.PUT, "/api/connections/draft-known-password", incomplete);

    // Connector facts cannot clear the signed setup gate, even if a connector supplies those reserved keys.
    var configs = core.bean(ConnectionConfigs.class);
    core.bean(ConnectionService.class).recordMetadata(configs.byName("draft-known-password").orElseThrow().id(),
        args("setupMissingFields", "forged", "serverVersion", "SQL 1").put("setupRequired", false));
    draft = admin.ok("/api/connections/draft-known-password");
    assertThat(draft.path("setupRequired").asBoolean()).isTrue();
    ObjectNode complete = args("env", "dev", "confirm", "send-secret-to-new-host").put("version", draft.path("version").asLong());
    complete.set("config", config());
    JsonNode saved = admin.expect(200, HttpMethod.PUT, "/api/connections/draft-known-password", complete);
    assertThat(saved.path("setupRequired").asBoolean()).isFalse();
    assertThat(saved.path("enabled").asBoolean()).isFalse();
    assertThat(admin.ok("/api/connections/draft-known-password").path("secrets")).hasSize(1);
    EndpointService endpoints = core.bean(EndpointService.class);
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    while (endpoints.snapshot("draft-target").pairs().stream().noneMatch(p -> p.config().name().equals("draft-known-password"))
        && System.nanoTime() < deadline) Thread.sleep(20);
    assertThat(endpoints.snapshot("draft-target").pairs()).anySatisfy(pair -> {
      assertThat(pair.config().name()).isEqualTo("draft-known-password");
      assertThat(pair.tool().name()).isEqualTo("sql_query");
      assertThat(pair.mode()).isEqualTo(ai.msrv.gaterook.connector.api.ToolDefinition.Mode.READ_ONLY);
    });
    McpClient destination = new McpClient(core.bean(LocalListener.class).port(), "draft-target", core.bean(TokenService.class)
        .issue("draft-target", "completed-draft-agent", 7, null).token());
    assertThat(destination.legacyCall("sql_query", args("connection", "draft-known-password", "sql", "SELECT 1"))
        .result().path("isError").asBoolean()).isTrue();
    admin.expect(200, HttpMethod.POST, "/api/connections/draft-known-password/enable", null);
    assertThat(destination.legacyCall("sql_query", args("connection", "draft-known-password", "sql", "SELECT 1"))
        .result().path("isError").asBoolean()).isFalse();
  }

  @Test void anUnavailableFileRemainsASafeDescriptorAndANewCredentialCompletesTheSameConnection() throws Exception {
    ObjectNode manifest = manifest("draft-unavailable-file", config());
    String missing = core.home().resolve("not-created-password.txt").toString();
    ((ObjectNode) manifest.path("connections").get(0)).putObject("secrets").putObject("/credentials/password")
        .put("source", "file").put("path", missing);
    JsonNode session = stage(manifest);
    save(session, "draft-unavailable-file", config());
    JsonNode draft = admin.ok("/api/connections/draft-unavailable-file");
    assertThat(draft.path("setupRequired").asBoolean()).isTrue();
    assertThat(draft.path("secrets")).isEmpty();
    assertThat(draft.at("/setupSources/0/path").asString()).isEqualTo(missing);
    ObjectNode update = args("env", "dev").put("version", draft.path("version").asLong());
    update.set("config", config());
    update.putObject("secrets").putObject("/credentials/password").put("source", "local").put("value", "new-private-password");
    JsonNode saved = admin.expect(200, HttpMethod.PUT, "/api/connections/draft-unavailable-file", update);
    assertThat(saved.path("setupRequired").asBoolean()).isFalse();
    assertThat(saved.path("enabled").asBoolean()).isFalse();
    JsonNode complete = admin.ok("/api/connections/draft-unavailable-file");
    assertThat(complete.path("secrets")).hasSize(1);
    assertThat(complete.path("setupSources")).isEmpty();
    assertThat(complete.toString()).doesNotContain("new-private-password");
  }

  @Test void anUnknownSecretStoreIsKeptForSetupWithoutBindingItOrReturningAValue() throws Exception {
    ObjectNode manifest = manifest("draft-unknown-store", config());
    ((ObjectNode) manifest.path("connections").get(0)).putObject("secrets").putObject("/credentials/password")
        .put("source", "reference").put("ref", "vault://kv/dev/database#password").put("store", "missing-store");
    JsonNode session = stage(manifest);
    save(session, "draft-unknown-store", config());
    JsonNode draft = admin.ok("/api/connections/draft-unknown-store");
    assertThat(draft.path("setupRequired").asBoolean()).isTrue();
    assertThat(draft.path("secrets")).isEmpty();
    assertThat(draft.at("/setupSources/0/reference").asString()).isEqualTo("vault://kv/dev/database#password");
    ObjectNode badComplete = args("env", "dev").put("version", draft.path("version").asLong());
    badComplete.set("config", config());
    badComplete.putObject("secrets").putObject("/credentials/password").put("source", "reference")
        .put("ref", "vault://kv/dev/database#password").put("store", "missing-store");
    admin.expect(422, HttpMethod.PUT, "/api/connections/draft-unknown-store", badComplete);
    assertThat(admin.ok("/api/connections/draft-unknown-store").path("setupRequired").asBoolean()).isTrue();
  }

  @Test void missingDataDoesNotPermitInvalidKnownValuesOrUnknownProperties() throws Exception {
    ObjectNode invalid = config();
    ((ObjectNode) invalid.path("connection")).put("host", 42);
    JsonNode session = stage(manifest("draft-invalid-type", invalid));
    ObjectNode request = commit("draft-invalid-type", invalid);
    admin.expect(422, HttpMethod.POST, path(session) + "/commit", request);
    admin.expect(404, HttpMethod.GET, "/api/connections/draft-invalid-type", null);
  }

  private static JsonNode stage(ObjectNode manifest) throws Exception {
    JsonNode session = admin.expect(201, HttpMethod.POST, "/api/connection-imports", args("repository", "/repositories/example", "endpoint", "draft-source"));
    Files.writeString(Path.of(session.path("importFile").asString()), manifest.toString());
    JsonNode result = agent.legacyCall("submit_connection_import", args("sessionId", session.path("id").asString(),
        "submissionToken", session.path("submissionToken").asString())).result();
    assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
    return session;
  }

  private static JsonNode save(JsonNode session, String name, ObjectNode config) {
    return admin.expect(200, HttpMethod.POST, path(session) + "/commit", commit(name, config));
  }

  private static String path(JsonNode session) { return "/api/connection-imports/" + session.path("id").asString(); }

  private static ObjectNode commit(String name, ObjectNode config) {
    ObjectNode request = args("endpoint", "draft-target");
    request.putArray("selections").addObject().put("candidateId", "database").put("name", name).put("env", "dev")
        .put("allowIncomplete", true).set("config", config);
    return request;
  }

  private static ObjectNode manifest(String name, ObjectNode config) {
    ObjectNode out = JsonNodeFactory.instance.objectNode().put("version", 1);
    out.putArray("connections").addObject().put("id", "database").put("name", name).put("type", "pgfake")
        .put("env", "dev").set("config", config);
    return out;
  }

  private static ObjectNode config() {
    ObjectNode config = JsonNodeFactory.instance.objectNode();
    config.putObject("connection").put("host", "database.test");
    config.putObject("credentials").put("user", "example-reader");
    return config;
  }
}
