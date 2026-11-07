package ai.msrv.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Manual apps persist without config writes; only their own endpoint token proves a connection. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManualClientsIT {

  private static TestCore core;
  private static AdminMvc api;
  private static Path config;
  private static final String ORIGINAL_CONFIG = "{\"mcpServers\":{\"personal\":{\"url\":\"https://example.test/mcp\"}}}";
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  static void start() throws Exception {
    core = TestCore.start("manual-clients");
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
    config = Files.createDirectories(core.home().resolve("user-home/.cursor")).resolve("mcp.json");
    Files.writeString(config, ORIGINAL_CONFIG);
  }

  @AfterAll
  static void stop() {
    if (core != null) core.close();
  }

  @Test
  void addedAppWaitsForItsOwnTokenAndNeverWritesClientConfiguration() throws Exception {
    endpoint("manual-dev");
    endpoint("manual-other");
    JsonNode added = add("  My AI app  ", "manual-dev");
    String id = added.path("client").path("client").asString();
    String token = added.path("token").path("token").asString();
    assertThat(id).startsWith("manual-");
    assertThat(added.path("client").path("name").asString()).isEqualTo("My AI app");
    assertThat(added.path("client").path("manual").asBoolean()).isTrue();
    assertThat(added.path("client").path("status").asString()).isEqualTo("pending");
    assertThat(added.path("client").path("configPath").isNull()).isTrue();
    assertThat(added.path("client").path("methods")).isEmpty();
    assertThat(added.path("client").path("links").get(0).path("method").asString()).isEqualTo("manual");
    assertThat(core.bean(ClientService.class).statuses()).containsEntry(id, id + " pending");
    JsonNode overview = api.get("/api/overview").json();
    JsonNode node = overview.path("setupMap").path("nodes").valueStream()
        .filter(n -> n.path("id").asString().equals("clients")).findFirst().orElseThrow();
    assertThat(node.path("state").asString()).isEqualTo("attention");
    assertThat(node.path("status").asString()).contains("awaiting connection");
    assertThat(added.path("setup").path("url").asString()).endsWith("/mcp/manual-dev");
    assertThat(added.path("setup").toString()).contains("‹token›").doesNotContain(token);
    assertThat(token).matches("gwt_[A-Za-z0-9_-]{43}");
    assertThat(card(id).toString()).doesNotContain(token);

    String unrelated = core.bean(TokenService.class).issue("manual-dev",
        added.path("token").path("name").asString(), 1, null).token();
    assertThat(initialize("manual-dev", unrelated)).isEqualTo(200);
    assertThat(card(id).path("status").asString()).isEqualTo("pending");
    assertThat(initialize("manual-other", token)).isEqualTo(401);
    assertThat(card(id).path("status").asString()).isEqualTo("pending");
    assertThat(initialize("manual-dev", token)).isEqualTo(200);
    assertThat(card(id).path("status").asString()).isEqualTo("connected");
    assertThat(core.bean(ClientService.class).statuses()).containsEntry(id, id + " connected");
    assertThat(card(id).path("links").get(0).path("usedAt").isNull()).isFalse();
    JsonNode scan = api.expect(200, HttpMethod.POST, "/api/clients/scan", null);
    assertThat(scan.path("clients").findValuesAsString("client")).contains(id, "cursor", "codex");
    assertThat(scan.toString()).doesNotContain(token);
    assertThat(Files.readString(config)).isEqualTo(ORIGINAL_CONFIG);
    assertThat(core.jdbc().queryForObject("select hash from api_token where id = ?", String.class,
        added.path("token").path("id").asString())).doesNotContain(token);
    assertThat(core.jdbc().queryForList("select * from setup_change").toString()).doesNotContain(token);
  }

  @Test
  void tokenReplacementRevokesPreviousAccessAndRemovalRevokesOnlyThisApp() throws Exception {
    endpoint("manual-renew");
    JsonNode first = add("Review assistant", "manual-renew");
    String id = first.path("client").path("client").asString();
    String oldToken = first.path("token").path("token").asString();
    String other = core.bean(TokenService.class).issue("manual-renew", "separate-app", 1, null).token();
    assertThat(initialize("manual-renew", oldToken)).isEqualTo(200);
    JsonNode replaced = api.expect(200, HttpMethod.POST, "/api/clients/manual/" + id + "/token", "{\"ttlDays\":7}");
    String replacement = replaced.path("token").path("token").asString();
    assertThat(replaced.path("client").path("client").asString()).isEqualTo(id);
    assertThat(replaced.path("client").path("status").asString()).isEqualTo("pending");
    assertThat(replacement).isNotEqualTo(oldToken);
    assertThat(initialize("manual-renew", oldToken)).isEqualTo(401);
    assertThat(initialize("manual-renew", replacement)).isEqualTo(200);
    assertThat(card(id).path("status").asString()).isEqualTo("connected");
    JsonNode removed = api.expect(200, HttpMethod.DELETE, "/api/clients/manual/" + id, null);
    assertThat(removed.path("linkId").asString()).isEqualTo(id);
    assertThat(removed.path("entryRemoved").asBoolean()).isFalse();
    assertThat(initialize("manual-renew", replacement)).isEqualTo(401);
    assertThat(initialize("manual-renew", other)).isEqualTo(200);
    assertThat(api.get("/api/clients").json().path("clients").findValuesAsString("client")).doesNotContain(id);
    api.expect(404, HttpMethod.DELETE, "/api/clients/manual/" + id, null);
    assertThat(Files.readString(config)).isEqualTo(ORIGINAL_CONFIG);
  }

  @Test
  void externallyRevokedAndExpiredTokensRetainTheirAppAndCanBeReplaced() throws Exception {
    endpoint("manual-expired");
    JsonNode added = add("Expired assistant", "manual-expired");
    String id = added.path("client").path("client").asString();
    TokenService tokens = core.bean(TokenService.class);
    tokens.revoke(added.path("token").path("id").asString(), "test");
    assertThat(card(id).path("status").asString()).isEqualTo("error");
    assertThat(card(id).path("statusText").asString()).contains("revoked");
    TokenService.Issued expired = tokens.issue("manual-expired", "expired-app", 1, null,
        Instant.now().minus(Duration.ofDays(2)));
    core.jdbc().update("update manual_client set token_id = ? where id = ?", expired.id(), id.substring(7));
    assertThat(card(id).path("status").asString()).isEqualTo("error");
    assertThat(card(id).path("statusText").asString()).contains("expired");
    JsonNode renewed = api.expect(200, HttpMethod.POST, "/api/clients/manual/" + id + "/token", "{}");
    assertThat(renewed.path("client").path("status").asString()).isEqualTo("pending");
    assertThat(initialize("manual-expired", renewed.path("token").path("token").asString())).isEqualTo(200);
  }

  @Test
  void invalidRequestsIssueNoTokenAndDuplicateDisplayNamesStayIndependent() {
    endpoint("manual-validation");
    for (Map<String, Object> body : java.util.List.of(
        Map.<String, Object>of("name", " ", "endpoint", "manual-validation"),
        Map.<String, Object>of("name", "a".repeat(81), "endpoint", "manual-validation"),
        Map.<String, Object>of("name", "App\nname", "endpoint", "manual-validation"),
        Map.<String, Object>of("name", "Valid app", "endpoint", "manual-validation", "ttlDays", 0),
        Map.<String, Object>of("name", "Valid app", "endpoint", "manual-validation", "ttlDays", 185),
        Map.<String, Object>of("name", "Valid app"))) {
      assertThat(api.post("/api/clients/manual", body).status()).isEqualTo(422);
    }
    assertThat(api.post("/api/clients/manual", Map.of("name", "Valid app", "endpoint", "missing")).status())
        .isEqualTo(404);
    assertThat(core.bean(TokenService.class).list("manual-validation")).isEmpty();
    JsonNode first = add("Same name", "manual-validation");
    JsonNode second = add("Same name", "manual-validation");
    assertThat(first.path("client").path("client")).isNotEqualTo(second.path("client").path("client"));
    assertThat(first.path("token").path("name")).isNotEqualTo(second.path("token").path("name"));
    api.expect(422, HttpMethod.POST,
        "/api/clients/manual/" + first.path("client").path("client").asString() + "/token", "{\"ttlDays\":0}");
    assertThat(core.bean(TokenService.class).list("manual-validation")).hasSize(2)
        .allMatch(t -> t.state().equals("active"));
  }

  @Test
  void endpointDeletionRemovesManualRegistrationsAndReportsTheirNames() throws Exception {
    endpoint("manual-delete");
    JsonNode added = add("Endpoint companion", "manual-delete");
    String id = added.path("client").path("client").asString();
    var deleted = core.bean(EndpointService.class).delete("manual-delete", "manual-delete", null);
    assertThat(deleted.clients()).contains("Endpoint companion");
    assertThat(core.jdbc().queryForObject("select count(*) from manual_client where id = ?", Integer.class,
        id.substring(7))).isZero();
    assertThat(api.get("/api/clients").json().path("clients").findValuesAsString("client")).doesNotContain(id);
  }

  @Test
  void disabledEndpointRejectsSetupAndReplacementWithoutRevokingTheExistingToken() {
    endpoint("manual-disabled");
    JsonNode added = add("Paused assistant", "manual-disabled");
    String id = added.path("client").path("client").asString();
    core.bean(EndpointService.class).disable("manual-disabled", null);
    api.expect(422, HttpMethod.POST, "/api/clients/manual", Map.of("name", "Another app", "endpoint", "manual-disabled"));
    api.expect(422, HttpMethod.POST, "/api/clients/manual/" + id + "/token", "{}");
    assertThat(core.bean(TokenService.class).list("manual-disabled")).hasSize(1)
        .allMatch(t -> t.state().equals("active"));
    api.expect(200, HttpMethod.DELETE, "/api/clients/manual/" + id, null);
  }

  @Test
  @Order(100)
  void registrationAndConnectionEvidenceSurviveACoreRestart() throws Exception {
    endpoint("manual-restart");
    JsonNode added = add("Persistent assistant", "manual-restart");
    String id = added.path("client").path("client").asString();
    String token = added.path("token").path("token").asString();
    assertThat(initialize("manual-restart", token)).isEqualTo(200);
    Path home = core.home();
    core.close();
    core = TestCore.reopen(home);
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
    JsonNode restored = card(id);
    assertThat(restored.path("name").asString()).isEqualTo("Persistent assistant");
    assertThat(restored.path("status").asString()).isEqualTo("connected");
    assertThat(restored.toString()).doesNotContain(token);
    assertThat(initialize("manual-restart", token)).isEqualTo(200);
  }

  private static void endpoint(String slug) {
    core.bean(EndpointService.class).create(slug, null, EndpointService.StartFrom.EMPTY, null);
  }

  private static JsonNode add(String name, String endpoint) {
    return api.expect(200, HttpMethod.POST, "/api/clients/manual", Map.of("name", name, "endpoint", endpoint));
  }

  private static JsonNode card(String id) {
    for (JsonNode card : api.get("/api/clients").json().path("clients")) {
      if (card.path("client").asString().equals(id)) return card;
    }
    throw new AssertionError("Manual app is missing: " + id);
  }

  private static int initialize(String endpoint, String token) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(core.bean(LocalListener.class).origin() + "/mcp/" + endpoint))
        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
            "capabilities":{},"clientInfo":{"name":"custom-assistant","version":"1.0"}}}
            """)).build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
  }
}
