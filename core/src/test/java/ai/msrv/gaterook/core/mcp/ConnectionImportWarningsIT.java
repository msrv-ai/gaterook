package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A batch reviews every production rights warning before any connection is created. */
class ConnectionImportWarningsIT {
  private static TestCore core;
  private static AdminMvc admin;
  private static McpClient agent;

  @BeforeAll static void start() {
    core = TestCore.start("connection-import-warnings");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("warnings-source", null, EndpointService.StartFrom.EMPTY, null);
    int port = core.bean(LocalListener.class).port();
    agent = new McpClient(port, "warnings-source", core.bean(TokenService.class).issue("warnings-source", "agent", 7, null).token());
    admin = new AdminMvc(core.context(), port);
  }

  @AfterAll static void stop() { if (core != null) core.close(); }

  @Test void returnsAllRightsWarningsAndImportsTheWholeBatchAfterOneExplicitConfirmation() {
    endpoint("warnings-prod");
    endpoint("warnings-dev");
    ObjectNode manifest = manifest();
    candidate(manifest, "first", "warnings-first", "prod", "danger-one.test");
    candidate(manifest, "middle", "warnings-middle", "dev", "database.test");
    candidate(manifest, "last", "warnings-last", "prod", "danger-two.test");
    JsonNode session = stage(manifest);
    ObjectNode request = request();
    selection(request, "first", "warnings-first", "prod", "danger-one.test", "warnings-prod");
    selection(request, "middle", "warnings-middle", "dev", "database.test", "warnings-dev");
    selection(request, "last", "warnings-last", "prod", "danger-two.test", "warnings-prod");
    long connections = count("connection");
    long secrets = count("secret");
    int probes = FakeConnectors.DANGER_TESTS.size();
    JsonNode warning = admin.expect(409, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(warning.path("confirm").asString()).isEqualTo("danger");
    assertThat(warning.path("field").asString()).isEqualTo("/selections/0");
    assertThat(warning.path("errors").size()).isEqualTo(2);
    assertThat(warning.at("/errors/0/field").asString()).isEqualTo("/selections/0");
    assertThat(warning.at("/errors/1/field").asString()).isEqualTo("/selections/2");
    assertThat(warning.at("/errors/0/message").asString()).contains("warnings-first", "broad database rights", "administer database users");
    assertThat(warning.at("/errors/1/message").asString()).contains("warnings-last", "broad database rights", "administer database users");
    assertThat(warning.toString()).doesNotContain("fixture-review-password");
    assertThat(count("connection")).isEqualTo(connections);
    assertThat(count("secret")).isEqualTo(secrets);
    assertThat(FakeConnectors.DANGER_TESTS.size()).isEqualTo(probes + 2);
    assertThat(admin.ok(path(session)).path("status").asString()).isEqualTo("review");

    ((ObjectNode) request.path("selections").get(0)).put("confirm", "danger");
    ((ObjectNode) request.path("selections").get(2)).put("confirm", "danger");
    JsonNode imported = admin.expect(200, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(imported.path("connections").size()).isEqualTo(3);
    assertThat(count("connection")).isEqualTo(connections + 3);
    assertThat(FakeConnectors.DANGER_TESTS.size()).as("confirmation does not repeat already reviewed privilege probes").isEqualTo(probes + 2);
    assertThat(core.bean(EndpointService.class).snapshot("warnings-prod").connections()).hasSize(2);
    assertThat(core.bean(EndpointService.class).snapshot("warnings-dev").connections()).hasSize(1);
  }

  @Test void hardValidationAfterAWarnedCandidateStillBlocksTheBatchWithoutOfferingConfirmation() {
    endpoint("warnings-invalid-prod");
    ObjectNode manifest = manifest();
    candidate(manifest, "first", "warnings-valid", "prod", "danger-one.test");
    candidate(manifest, "second", "warnings-invalid", "prod", "danger-two.test");
    JsonNode session = stage(manifest);
    ObjectNode request = request();
    selection(request, "first", "warnings-valid", "prod", "danger-one.test", "warnings-invalid-prod");
    ObjectNode second = selection(request, "second", "warnings-invalid", "prod", "danger-two.test", "warnings-invalid-prod");
    ((ObjectNode) second.at("/config/connection")).remove("host");
    long connections = count("connection");
    int probes = FakeConnectors.DANGER_TESTS.size();
    JsonNode refused = admin.expect(422, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(refused.path("field").asString()).startsWith("/selections/1/config");
    assertThat(refused.has("confirm")).isFalse();
    assertThat(count("connection")).isEqualTo(connections);
    assertThat(FakeConnectors.DANGER_TESTS.size()).isEqualTo(probes + 1);
    second.set("config", TestCore.config("danger-two.test"));
    second.put("env", "dev");
    JsonNode downgrade = admin.expect(422, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(downgrade.path("field").asString()).isEqualTo("/selections/1/env");
    assertThat(FakeConnectors.DANGER_TESTS.size()).isEqualTo(probes + 1);
    assertThat(count("connection")).isEqualTo(connections);
  }

  @Test void singleWarningRemainsCompatibleAndConfirmingRightsCannotBypassEndpointRules() {
    endpoint("warnings-live-dev");
    core.bean(TokenService.class).issue("warnings-live-dev", "existing-dev-access", 7, null);
    ObjectNode manifest = manifest();
    candidate(manifest, "first", "warnings-endpoint-blocked", "prod", "danger-one.test");
    JsonNode session = stage(manifest);
    ObjectNode request = request();
    ObjectNode selected = selection(request, "first", "warnings-endpoint-blocked", "prod", "danger-one.test", "warnings-live-dev");
    long connections = count("connection");
    long secrets = count("secret");
    int probes = FakeConnectors.DANGER_TESTS.size();
    JsonNode warning = admin.expect(409, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(warning.path("title").asString()).isEqualTo("Keep these rights on prod?");
    assertThat(warning.path("field").asString()).isEqualTo("/selections/0");
    assertThat(warning.path("errors").size()).isEqualTo(1);
    assertThat(warning.at("/errors/0/message")).isEqualTo(warning.path("detail"));
    selected.put("confirm", "danger");
    JsonNode refused = admin.expect(422, HttpMethod.POST, path(session) + "/commit", request);
    assertThat(refused.path("field").asString()).isEqualTo("/selections/0/endpoint");
    assertThat(refused.has("confirm")).isFalse();
    assertThat(count("connection")).isEqualTo(connections);
    assertThat(count("secret")).isEqualTo(secrets);
    assertThat(FakeConnectors.DANGER_TESTS.size()).isEqualTo(probes + 1);
    assertThat(core.bean(EndpointService.class).snapshot("warnings-live-dev").connections()).isEmpty();
  }

  private static void endpoint(String slug) {
    core.bean(EndpointService.class).create(slug, null, EndpointService.StartFrom.EMPTY, null);
  }
  private static long count(String table) {
    return core.jdbc().queryForObject("select count(*) from " + table, Long.class);
  }
  private static ObjectNode manifest() {
    ObjectNode manifest = args().put("version", 1);
    manifest.putArray("connections");
    return manifest;
  }
  private static void candidate(ObjectNode manifest, String id, String name, String env, String host) {
    ((ArrayNode) manifest.path("connections")).addObject().put("id", id).put("name", name).put("type", "pgfake")
        .put("env", env).set("config", TestCore.config(host));
  }
  private static JsonNode stage(ObjectNode manifest) {
    JsonNode session = admin.expect(201, HttpMethod.POST, "/api/connection-imports", args("repository", "example-repository", "endpoint", "warnings-source"));
    ObjectNode submit = args("sessionId", session.path("id").asString(), "submissionToken", session.path("submissionToken").asString());
    submit.set("manifest", manifest);
    JsonNode result = agent.legacyCall("submit_connection_import", submit).result();
    assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
    return session;
  }
  private static String path(JsonNode session) { return "/api/connection-imports/" + session.path("id").asString(); }
  private static ObjectNode request() {
    ObjectNode request = args();
    request.putArray("selections");
    return request;
  }
  private static ObjectNode selection(ObjectNode request, String id, String name, String env, String host, String endpoint) {
    ObjectNode selection = ((ArrayNode) request.path("selections")).addObject().put("candidateId", id).put("name", name)
        .put("env", env).put("endpoint", endpoint);
    selection.set("config", TestCore.config(host));
    selection.putObject("secrets").putObject("/credentials/password").put("source", "local").put("value", "fixture-review-password");
    return selection;
  }
}
