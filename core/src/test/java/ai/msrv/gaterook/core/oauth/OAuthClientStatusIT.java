package ai.msrv.gaterook.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.tunnel.TunnelWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** Cursor's local browser sign-in must appear connected before its first audited tool call. */
class OAuthClientStatusIT {

  private static final String REDIRECT = "http://localhost:43127/callback";
  private static final String ORIGINAL_CONFIG = """
      {"mcpServers":{"existing":{"url":"https://mcp.example.com/api"}}}
      """;

  private TunnelWorld world;
  private AdminMvc api;
  private OAuthFlow local;
  private JdbcTemplate jdbc;
  private Path config;
  private String resource;

  @BeforeEach
  void start() throws IOException {
    Path parent = Files.createDirectories(Path.of("target", "client-homes").toAbsolutePath());
    Path userHome = Files.createTempDirectory(parent, "oauth-status-");
    config = Files.createDirectories(userHome.resolve(".cursor")).resolve("mcp.json");
    Files.writeString(config, ORIGINAL_CONFIG);
    world = TunnelWorld.start("oauth-client-status", "--gateway.clients.user-home=" + userHome,
        "--gateway.clients.os=linux");
    api = world.admin();
    local = new OAuthFlow(world, false);
    jdbc = new JdbcTemplate(world.bean(DataSource.class));
    world.endpoint("dev", Env.DEV);
    EndpointService endpoints = world.bean(EndpointService.class);
    endpoints.updateSettings("dev", new EndpointService.SettingsUpdate(null, null, null, null, null, null, true),
        endpoints.get("dev").version(), null);
    resource = "http://127.0.0.1:" + world.localPort() + "/mcp/dev";
    api.expect(200, HttpMethod.POST, "/api/clients/scan", null);
    assertThat(cursor().path("status").asString()).isEqualTo("detected");
  }

  @AfterEach
  void stop() {
    if (world != null) {
      world.close();
    }
  }

  @Test
  void aBrowserInstalledCursorConnectsAfterExchangeAndDiscoveryWithoutAConfigWrite() throws IOException {
    assertOverviewConnection(false);
    Authorized approved = approveCursor();
    assertThat(cursor().path("status").asString()).as("consent alone does not prove a connection")
        .isEqualTo("detected");
    assertThat(cursor().path("links")).isEmpty();
    assertOverviewConnection(false);

    String access = exchange(approved);
    JsonNode connected = cursor();
    assertThat(connected.path("status").asString()).isEqualTo("connected");
    assertThat(connected.path("links")).hasSize(1);
    JsonNode link = connected.path("links").get(0);
    assertThat(link.path("id").asString()).isEqualTo(approved.grantId());
    assertThat(link.path("endpoint").asString()).isEqualTo("dev");
    assertThat(link.path("method").asString()).isEqualTo("oauth");
    for (String field : new String[] {"configPath", "configDisplay", "entryName", "lastCallAt"}) {
      assertThat(link.path(field).isNull()).as(field).isTrue();
    }
    assertThat(link.path("usedAt").isNull()).isFalse();
    assertThat(link.path("status").asString()).isEqualTo("connected");
    assertOverviewConnection(true);

    discoverTools(access);
    assertThat(cursor().path("status").asString()).isEqualTo("connected");
    assertThat(cursor().path("links").get(0).path("lastCallAt").isNull()).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from tool_call", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from client_link", Integer.class)).isZero();
    assertThat(Files.readString(config)).isEqualTo(ORIGINAL_CONFIG);
    assertOverviewConnection(true);

    api.expect(204, HttpMethod.DELETE, "/api/oauth/grants/" + approved.grantId(), null);
    assertThat(cursor().path("links")).isEmpty();
    assertThat(cursor().path("status").asString()).isEqualTo("detected");
    assertOverviewConnection(false);
    assertThat(local.mcp("dev", access, "tools/list").status()).isEqualTo(401);
    assertThat(jdbc.queryForObject("select count(*) from client_link", Integer.class)).isZero();
    assertThat(Files.readString(config)).isEqualTo(ORIGINAL_CONFIG);
  }

  @Test
  void aCurrentBrowserSignInStaysVisibleWhenCursorReplacesAnOldManagedTokenConfig() throws IOException {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/cursor/connect/preview", """
        {"endpoint":"dev","method":"token","token":{"name":"old-cursor-token","ttlDays":7}}""");
    JsonNode result = api.expect(200, HttpMethod.POST, "/api/clients/cursor/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    String managedId = result.path("link").path("id").asString();
    String withToken = Files.readString(config);
    api.expect(200, HttpMethod.POST, "/api/clients/cursor/browser-sign-in", "{\"endpoint\":\"dev\"}");
    assertThat(Files.readString(config)).isEqualTo(withToken);
    // Cursor owns its installation now; GateRook must retain the old config problem without hiding live access.
    Files.writeString(config, ORIGINAL_CONFIG);
    Authorized approved = approveCursor();
    discoverTools(exchange(approved));

    JsonNode connected = cursor();
    assertThat(connected.path("status").asString()).isEqualTo("connected");
    assertOverviewConnection(true);
    assertThat(connected.path("links")).hasSize(2);
    JsonNode observed = connected.path("links").get(0);
    assertThat(observed.path("id").asString()).isEqualTo(approved.grantId());
    assertThat(observed.path("status").asString()).isEqualTo("connected");
    assertThat(observed.path("configPath").isNull()).isTrue();
    JsonNode managed = connected.path("links").get(1);
    assertThat(managed.path("id").asString()).isEqualTo(managedId);
    assertThat(managed.path("status").asString()).isEqualTo("removed-outside");
    assertThat(jdbc.queryForObject("select count(*) from client_link", Integer.class)).isEqualTo(1);
    assertThat(Files.readString(config)).isEqualTo(ORIGINAL_CONFIG);
  }

  @Test
  void browserSignInUpdatesAnExistingManagedLinkWithoutAddingAnObservedDuplicate() throws IOException {
    JsonNode preview = api.expect(200, HttpMethod.POST, "/api/clients/cursor/connect/preview", """
        {"endpoint":"dev","method":"oauth"}""");
    JsonNode result = api.expect(200, HttpMethod.POST, "/api/clients/cursor/connect",
        "{\"previewId\":\"" + preview.path("previewId").asString() + "\"}");
    String linkId = result.path("link").path("id").asString();
    String managedConfig = Files.readString(config);
    assertThat(cursor().path("status").asString()).isEqualTo("needs-restart");

    Authorized approved = approveCursor();
    assertThat(cursor().path("status").asString()).isEqualTo("needs-restart");
    String access = exchange(approved);
    assertThat(cursor().path("status").asString()).isEqualTo("connected");
    discoverTools(access);

    JsonNode connected = cursor();
    assertThat(connected.path("links")).hasSize(1);
    JsonNode link = connected.path("links").get(0);
    assertThat(link.path("id").asString()).isEqualTo(linkId).isNotEqualTo(approved.grantId());
    assertThat(link.path("configPath").asString()).isEqualTo(config.toString());
    assertThat(link.path("status").asString()).isEqualTo("connected");
    assertThat(link.path("usedAt").isNull()).isFalse();
    assertThat(link.path("lastCallAt").isNull()).isTrue();
    assertOverviewConnection(true);
    assertThat(jdbc.queryForObject("select count(*) from tool_call", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from client_link", Integer.class)).isEqualTo(1);
    assertThat(Files.readString(config)).isEqualTo(managedConfig);
  }

  private record Authorized(String clientId, OAuthFlow.Pkce pkce, String code, String grantId) {}

  private Authorized approveCursor() {
    TunnelWorld.Answer registered = local.post(null, "/oauth/register", Map.of("Content-Type", "application/json"),
        "{\"client_name\":\"Cursor\",\"redirect_uris\":[\"" + REDIRECT + "\"],\"grant_types\":"
            + "[\"authorization_code\",\"refresh_token\"],\"token_endpoint_auth_method\":\"none\"}");
    assertThat(registered.status()).as(registered.body()).isEqualTo(201);
    String clientId = registered.json().path("client_id").asString();
    OAuthFlow.Pkce pkce = OAuthFlow.Pkce.create();
    OAuthFlow.Waiting waiting = local.waiting(OAuthFlow.params(clientId, REDIRECT, pkce, resource, "cursor-status"));
    JsonNode consent = local.consent(waiting);
    assertThat(consent.path("clientHost").asString()).isEqualTo("localhost");
    assertThat(consent.path("clientName").asString()).isEqualTo("Cursor");
    String code = local.allow(waiting).get("code");
    JsonNode grants = api.ok("/api/oauth/grants");
    assertThat(grants).hasSize(1);
    JsonNode grant = grants.get(0);
    assertThat(grant.path("clientName").asString()).isEqualTo("Cursor");
    assertThat(grant.path("ingress").asString()).isEqualTo("LOCAL");
    assertThat(grant.path("lastUsedAt").isNull()).isTrue();
    return new Authorized(clientId, pkce, code, grant.path("id").asString());
  }

  private String exchange(Authorized approved) {
    TunnelWorld.Answer exchanged = local.exchange(approved.clientId(), REDIRECT, approved.code(), approved.pkce(),
        resource);
    assertThat(exchanged.status()).as(exchanged.body()).isEqualTo(200);
    return exchanged.json().path("access_token").asString();
  }

  private void discoverTools(String access) {
    TunnelWorld.Answer initialized = local.post(null, "/mcp/dev", Map.of("Content-Type", "application/json",
        "Accept", "application/json, text/event-stream", "MCP-Protocol-Version", "2025-11-25", "Authorization",
        "Bearer " + access), """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
        "capabilities":{},"clientInfo":{"name":"Cursor","version":"1.0.0"}}}
        """);
    assertThat(initialized.status()).as(initialized.body()).isEqualTo(200);
    TunnelWorld.Answer tools = local.mcp("dev", access, "tools/list");
    assertThat(tools.status()).as(tools.body()).isEqualTo(200);
    assertThat(tools.body()).contains("\"tools\"");
  }

  private JsonNode cursor() {
    return StreamSupport.stream(api.ok("/api/clients").path("clients").spliterator(), false)
        .filter(c -> c.path("client").asString().equals("cursor")).findFirst().orElseThrow();
  }

  private void assertOverviewConnection(boolean connected) {
    for (String path : new String[] {"/api/overview", "/api/onboarding"}) {
      JsonNode overview = api.ok(path);
      JsonNode clientStep = byId(overview.path("gettingStarted").path("steps"), "client");
      assertThat(clientStep.path("state").asString().equals("done")).as(path + " client setup step")
          .isEqualTo(connected);
      if (connected) {
        assertThat(clientStep.path("detail").path("status").asString()).isEqualTo("Cursor");
      }
      JsonNode clientsNode = byId(overview.path("setupMap").path("nodes"), "clients");
      assertThat(clientsNode.path("state").asString()).as(path + " clients node")
          .isEqualTo(connected ? "done" : "todo");
      assertThat(clientsNode.path("count").asInt()).isEqualTo(connected ? 1 : 0);
      assertThat(byId(overview.path("gettingStarted").path("steps"), "playground").path("state").asString())
          .as("sign-in and discovery are not a Playground tool call").isNotEqualTo("done");
      if (path.equals("/api/overview")) {
        assertThat(overview.path("stats").path("calls24h").asLong()).isZero();
      }
    }
  }

  private static JsonNode byId(JsonNode values, String id) {
    return StreamSupport.stream(values.spliterator(), false).filter(value -> value.path("id").asString().equals(id))
        .findFirst().orElseThrow();
  }
}
