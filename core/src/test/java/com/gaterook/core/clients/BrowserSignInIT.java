package com.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Env;
import com.gaterook.core.admin.AdminMvc;
import com.gaterook.core.endpoints.EndpointConnections;
import com.gaterook.core.endpoints.EndpointService;
import com.gaterook.core.platform.LocalListener;
import com.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Browser installation enables OAuth without taking ownership of Cursor's configuration or access. */
class BrowserSignInIT {

  private static TestCore core;
  private static AdminMvc api;
  private static EndpointService endpoints;
  private static Path config;
  private static int port;

  @BeforeAll
  static void start() throws IOException {
    Path parent = Files.createDirectories(Path.of("target", "client-homes").toAbsolutePath());
    Path userHome = Files.createTempDirectory(parent, "browser-sign-in-");
    config = userHome.resolve(".cursor/mcp.json");
    core = TestCore.start("browser-sign-in", "--gateway.clients.user-home=" + userHome,
        "--gateway.clients.os=linux");
    port = core.bean(LocalListener.class).port();
    api = new AdminMvc(core.context(), port);
    endpoints = core.bean(EndpointService.class);
  }

  @AfterAll
  static void stop() {
    if (core != null) core.close();
  }

  @Test
  void malformedConfigDoesNotBlockBrowserInstallationOrGetChanged() throws IOException {
    endpoints.create("browser-dev", null, EndpointService.StartFrom.EMPTY, null);
    Files.createDirectories(config.getParent());
    String original = "{invalid existing Cursor configuration";
    Files.writeString(config, original);
    var modifiedAt = Files.getLastModifiedTime(config);
    Map<String, Integer> before = accessRows();
    assertThat(endpoints.snapshot("browser-dev").oauthEnabled()).isFalse();

    JsonNode handoff = api.expect(200, HttpMethod.POST, "/api/clients/cursor/browser-sign-in",
        "{\"endpoint\":\"browser-dev\"}");

    assertThat(handoff.properties()).hasSize(1);
    assertLink(handoff.path("link").asString(), "browser-dev");
    assertThat(endpoints.snapshot("browser-dev").oauthEnabled()).isTrue();
    assertThat(Files.readString(config)).isEqualTo(original);
    assertThat(Files.getLastModifiedTime(config)).isEqualTo(modifiedAt);
    assertThat(accessRows()).isEqualTo(before);
    assertThat(core.bean(ClientService.class).backups("cursor")).isEmpty();
    try (var files = Files.list(config.getParent())) {
      assertThat(files.toList()).containsExactly(config);
    }

    long enabledVersion = endpoints.get("browser-dev").version();
    assertThat(core.bean(ClientService.class).browserSignIn("cursor", "browser-dev").link())
        .isEqualTo(handoff.path("link").asString());
    assertThat(endpoints.get("browser-dev").version()).isEqualTo(enabledVersion);
    assertThat(accessRows()).isEqualTo(before);
  }

  @Test
  void missingConfigIsNotCreated() throws IOException {
    endpoints.create("browser-empty", null, EndpointService.StartFrom.EMPTY, null);
    Files.deleteIfExists(config);
    Files.deleteIfExists(config.getParent());
    Map<String, Integer> before = accessRows();

    JsonNode handoff = api.expect(200, HttpMethod.POST, "/api/clients/cursor/browser-sign-in",
        "{\"endpoint\":\"browser-empty\"}");

    assertLink(handoff.path("link").asString(), "browser-empty");
    assertThat(Files.exists(config.getParent())).isFalse();
    assertThat(endpoints.snapshot("browser-empty").oauthEnabled()).isTrue();
    assertThat(accessRows()).isEqualTo(before);
    assertThat(core.bean(ClientService.class).backups("cursor")).isEmpty();
  }

  @Test
  void vsCodeInstallationCarriesOnlyTheEndpointAndCannotUseManagedConfigRoutes() throws IOException {
    endpoints.create("vscode-dev", null, EndpointService.StartFrom.EMPTY, null);
    Path file = core.bean(ClientService.class).environment().userHome().resolve(".config/Code/User/mcp.json");
    Files.createDirectories(file.getParent());
    String original = "{ // Keep user comments\n \"servers\": {},\n}\n";
    Files.writeString(file, original);
    Map<String, Integer> before = accessRows();
    JsonNode handoff = api.expect(200, HttpMethod.POST, "/api/clients/vscode/browser-sign-in",
        "{\"endpoint\":\"vscode-dev\"}");
    String link = handoff.path("link").asString();
    assertThat(link).startsWith("vscode:mcp/install?");
    JsonNode payload = JsonMapper.builder().build().readTree(URLDecoder.decode(
        link.substring(link.indexOf('?') + 1), StandardCharsets.UTF_8));
    assertThat(payload.properties()).hasSize(3);
    assertThat(payload.path("name").asString()).isEqualTo("gaterook-vscode-dev");
    assertThat(payload.path("type").asString()).isEqualTo("http");
    assertThat(payload.path("url").asString()).isEqualTo("http://127.0.0.1:" + port + "/mcp/vscode-dev");
    assertThat(endpoints.snapshot("vscode-dev").oauthEnabled()).isTrue();
    api.expect(422, HttpMethod.POST, "/api/clients/vscode/connect/preview", "{\"endpoint\":\"vscode-dev\"}");
    api.expect(422, HttpMethod.POST, "/api/clients/microsoft-copilot/connect/preview", "{\"endpoint\":\"vscode-dev\"}");
    api.expect(422, HttpMethod.POST, "/api/clients/microsoft-copilot/browser-sign-in", "{\"endpoint\":\"vscode-dev\"}");
    assertThat(Files.readString(file)).isEqualTo(original);
    assertThat(accessRows()).isEqualTo(before);
    assertThat(core.bean(ClientService.class).backups("vscode")).isEmpty();
  }

  @Test
  void disallowedClientsAndEndpointsCannotEnableBrowserSignIn() {
    endpoints.create("browser-policy", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.create("browser-disabled", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.disable("browser-disabled", null);
    core.connection("browser-prod-db", "pgfake", Env.PROD, "db.test");
    endpoints.create("browser-prod", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("browser-prod", "browser-prod-db", null);
    Map<String, Integer> before = accessRows();

    api.expect(422, HttpMethod.POST, "/api/clients/antigravity/browser-sign-in",
        "{\"endpoint\":\"browser-policy\"}");
    api.expect(422, HttpMethod.POST, "/api/clients/cursor/browser-sign-in", "{}");
    api.expect(404, HttpMethod.POST, "/api/clients/cursor/browser-sign-in", "{\"endpoint\":\"absent\"}");
    api.expect(422, HttpMethod.POST, "/api/clients/cursor/browser-sign-in",
        "{\"endpoint\":\"browser-disabled\"}");
    JsonNode prod = api.expect(422, HttpMethod.POST, "/api/clients/cursor/browser-sign-in",
        "{\"endpoint\":\"browser-prod\"}");
    assertThat(prod.path("title").asString()).isEqualTo("Not on a prod endpoint");
    api.expect(422, HttpMethod.POST, "/api/clients/vscode/browser-sign-in", "{\"endpoint\":\"browser-disabled\"}");
    for (String slug : List.of("browser-policy", "browser-disabled", "browser-prod")) {
      assertThat(endpoints.snapshot(slug).oauthEnabled()).as(slug).isFalse();
    }
    assertThat(accessRows()).isEqualTo(before);
    api.expect(200, HttpMethod.POST, "/api/clients/vscode/browser-sign-in", "{\"endpoint\":\"browser-prod\"}");
    assertThat(endpoints.snapshot("browser-prod").oauthEnabled()).isTrue();
    assertThat(accessRows()).isEqualTo(before);
    assertThat(core.bean(ClientService.class).clients().clients().stream()
        .filter(c -> c.client().equals("vscode")).findFirst().orElseThrow().methods())
        .singleElement().satisfies(m -> assertThat(m.prodAllowed()).isTrue());
  }

  private static void assertLink(String link, String slug) {
    URI uri = URI.create(link);
    assertThat(uri.getScheme()).isEqualTo("cursor");
    assertThat(uri.getHost()).isEqualTo("anysphere.cursor-deeplink");
    assertThat(uri.getPath()).isEqualTo("/mcp/install");
    Map<String, String> query = new LinkedHashMap<>();
    Arrays.stream(uri.getRawQuery().split("&")).forEach(pair -> {
      String[] parts = pair.split("=", 2);
      query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    });
    assertThat(query).containsOnlyKeys("name", "config");
    assertThat(query.get("name")).isEqualTo("gaterook-" + slug);
    JsonNode payload = JsonMapper.builder().build().readTree(Base64.getDecoder().decode(query.get("config")));
    assertThat(payload.properties()).hasSize(1);
    assertThat(payload.path("url").asString()).isEqualTo("http://127.0.0.1:" + port + "/mcp/" + slug);
  }

  private static Map<String, Integer> accessRows() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String table : List.of("client_link", "client_grant", "api_token", "oauth_grant", "oauth_token")) {
      counts.put(table, core.jdbc().queryForObject("select count(*) from " + table, Integer.class));
    }
    return counts;
  }
}
