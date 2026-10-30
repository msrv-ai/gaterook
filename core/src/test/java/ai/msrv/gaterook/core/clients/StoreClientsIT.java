package ai.msrv.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.clients.internal.ClientEnvironment;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Store path restrictions are enforced before file, token and grant mutations, not only by the UI. */
class StoreClientsIT {
  private static TestCore core;
  private static ClientService clients;
  private static EndpointService endpoints;
  private static Path home;

  @BeforeAll
  static void start() throws IOException {
    home = Files.createTempDirectory(Files.createDirectories(Path.of("target/client-homes").toAbsolutePath()), "store-");
    core = TestCore.start("store-clients", "--gateway.clients.user-home=" + home,
        "--gateway.clients.os=windows", "--gateway.clients.windows-store=true");
    clients = core.bean(ClientService.class);
    endpoints = core.bean(EndpointService.class);
    assertThat(core.bean(ClientEnvironment.class).windowsStore()).isTrue();
  }

  @AfterAll
  static void stop() {
    if (core != null) core.close();
  }

  @Test
  void claudeOffersOnlyTheExtensionAndDeniedConfigSetupCreatesNoAccessOrFiles() throws IOException {
    endpoints.create("store-claude", null, EndpointService.StartFrom.EMPTY, null);
    var card = clients.clients().clients().stream().filter(c -> c.client().equals("claude-desktop")).findFirst().orElseThrow();
    assertThat(card.methods()).extracting(ClientViews.Method::id).containsExactly("mcpb");
    var before = accessRows();
    assertThatThrownBy(() -> clients.previewConnect("claude-desktop", request("store-claude", "config-bridge")))
        .isInstanceOf(ProblemException.class).hasMessageContaining("extension");
    assertThat(accessRows()).isEqualTo(before);
    assertThat(Files.exists(home.resolve("AppData/Roaming/Claude/claude_desktop_config.json"))).isFalse();

    var preview = clients.previewConnect("claude-desktop", request("store-claude", "mcpb"));
    var connected = clients.connect("claude-desktop", preview.previewId());
    assertThat(Files.isRegularFile(Path.of(connected.mcpbPath()))).isTrue();
    assertThat(clients.mcpbPath(connected.link().id())).isEqualTo(Path.of(connected.mcpbPath()).toRealPath());
    var disconnect = clients.previewDisconnect(connected.link().id());
    clients.disconnect(connected.link().id(), disconnect.previewId());
    assertThat(Files.exists(Path.of(connected.mcpbPath()))).isFalse();
  }

  @Test
  void defaultCodexConfigurationCanBeCreatedWithoutAStoredToken() throws IOException {
    endpoints.create("store-codex", null, EndpointService.StartFrom.EMPTY, null);
    var preview = clients.previewConnect("codex", request("store-codex", "helper"));
    var connected = clients.connect("codex", preview.previewId());
    String config = Files.readString(home.resolve(".codex/config.toml"));
    assertThat(config).contains("http_headers_helper", "store-codex").doesNotContain("gwt_", "gwb_");
    var disconnect = clients.previewDisconnect(connected.link().id());
    clients.disconnect(connected.link().id(), disconnect.previewId());
    assertThat(Files.readString(home.resolve(".codex/config.toml"))).doesNotContain("store-codex");
  }

  @Test
  void relocatedExtensionDirectoryCannotCreateAGrantOrRemoveExistingAccess() throws IOException {
    endpoints.create("store-bundle-moved", null, EndpointService.StartFrom.EMPTY, null);
    Path dir = Files.createDirectories(clients.environment().mcpbDirectory());
    var preview = clients.previewConnect("claude-desktop", request("store-bundle-moved", "mcpb"));
    Path saved = Files.move(dir, dir.resolveSibling("claude-original"));
    Path redirected = Files.createDirectories(home.resolve("AppData/ClaudeBundles"));
    Files.createSymbolicLink(dir, redirected);
    Map<String, Integer> before = accessRows();
    try {
      assertThatThrownBy(() -> clients.connect("claude-desktop", preview.previewId())).isInstanceOf(ProblemException.class);
      assertThatThrownBy(clients::removeBridge).isInstanceOf(ProblemException.class);
      assertThat(accessRows()).isEqualTo(before);
      try (var files = Files.list(redirected)) {
        assertThat(files.toList()).isEmpty();
      }
    } finally {
      Files.delete(dir);
      Files.move(saved, dir);
    }
  }

  @Test
  void cursorParentMovedIntoAppDataAfterPreviewDoesNotIssueAToken() throws IOException {
    endpoints.create("store-cursor", null, EndpointService.StartFrom.EMPTY, null);
    Path dir = Files.createDirectories(home.resolve(".cursor"));
    Path file = Files.writeString(dir.resolve("mcp.json"), "{}");
    var preview = clients.previewConnect("cursor", request("store-cursor", "token"));
    Map<String, Integer> before = accessRows();
    Path saved = Files.move(dir, home.resolve("cursor-original"));
    Path redirected = Files.createDirectories(home.resolve("AppData/Cursor"));
    Files.writeString(redirected.resolve("mcp.json"), "{}");
    Files.createSymbolicLink(dir, redirected);
    try {
      assertThatThrownBy(() -> clients.connect("cursor", preview.previewId())).isInstanceOf(ProblemException.class);
      assertThat(accessRows()).isEqualTo(before);
      assertThat(Files.readString(file)).isEqualTo("{}");
      try (var files = Files.list(redirected)) {
        assertThat(files.toList()).containsExactly(redirected.resolve("mcp.json"));
      }
    } finally {
      Files.delete(dir);
      Files.move(saved, dir);
    }
  }

  @Test
  void disconnectAndRestoreRecheckRecordedAndPreviewedPaths() throws IOException {
    endpoints.create("store-moved", null, EndpointService.StartFrom.EMPTY, null);
    Path dir = Files.createDirectories(home.resolve(".cursor"));
    Files.writeString(dir.resolve("mcp.json"), "{}");
    var preview = clients.previewConnect("cursor", request("store-moved", "token"));
    var connected = clients.connect("cursor", preview.previewId());
    var disconnect = clients.previewDisconnect(connected.link().id());
    String backup = clients.backups("cursor").getFirst().id();
    var restore = clients.previewRestore("cursor", backup);
    Map<String, Integer> before = accessRows();
    Path saved = Files.move(dir, home.resolve("cursor-connected"));
    Path redirected = Files.createDirectories(home.resolve("AppData/CursorMoved"));
    String original = Files.readString(saved.resolve("mcp.json"));
    Files.writeString(redirected.resolve("mcp.json"), original);
    Files.createSymbolicLink(dir, redirected);
    try {
      assertThatThrownBy(() -> clients.previewDisconnect(connected.link().id())).isInstanceOf(ProblemException.class);
      assertThatThrownBy(() -> clients.disconnect(connected.link().id(), disconnect.previewId())).isInstanceOf(ProblemException.class);
      assertThatThrownBy(() -> clients.restore("cursor", restore.previewId())).isInstanceOf(ProblemException.class);
      assertThat(accessRows()).isEqualTo(before);
      assertThat(Files.readString(redirected.resolve("mcp.json"))).isEqualTo(original);
      assertThat(core.jdbc().queryForObject("select revoked_at from api_token where id = (select token_id from client_link where id = ?)",
          String.class, connected.link().id())).isNull();
    } finally {
      Files.delete(dir);
      Files.move(saved, dir);
    }
  }

  private static ClientService.ConnectRequest request(String endpoint, String method) {
    return new ClientService.ConnectRequest(endpoint, method, null, null, null);
  }

  private static Map<String, Integer> accessRows() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String table : List.of("client_link", "client_grant", "api_token", "oauth_grant", "oauth_token")) {
      counts.put(table, core.jdbc().queryForObject("select count(*) from " + table, Integer.class));
    }
    return counts;
  }
}
