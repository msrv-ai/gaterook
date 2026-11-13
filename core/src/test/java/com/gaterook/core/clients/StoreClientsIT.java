package com.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.core.clients.internal.ClientEnvironment;
import com.gaterook.core.endpoints.EndpointService;
import com.gaterook.core.platform.ProblemException;
import com.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Store path restrictions are enforced before every file mutation and before a token or grant is made, not only by the
 * UI; ending an access never waits for a file the edition doesn't write.
 */
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
  void vsCodeUsesItsInstallerWithoutWritingVirtualizedAppDataAndCopilotIsDetectionOnly() throws IOException {
    Path copilot = Files.createDirectories(home.resolve("AppData/Local/Packages/Microsoft.Copilot_8wekyb3d8bbwe"));
    endpoints.create("store-vscode", null, EndpointService.StartFrom.EMPTY, null);
    var cards = clients.scan().clients();
    var vscode = cards.stream().filter(c -> c.client().equals("vscode")).findFirst().orElseThrow();
    assertThat(vscode.methods()).extracting(ClientViews.Method::id).containsExactly("oauth");
    assertThat(vscode.warnings()).isEmpty();
    var consumer = cards.stream().filter(c -> c.client().equals("microsoft-copilot")).findFirst().orElseThrow();
    assertThat(consumer.detected()).isTrue();
    assertThat(consumer.methods()).isEmpty();
    assertThat(consumer.configPath()).isNull();
    var before = accessRows();
    assertThat(clients.browserSignIn("vscode", "store-vscode").link()).startsWith("vscode:mcp/install?");
    assertThat(Files.exists(home.resolve("AppData/Roaming/Code"))).isFalse();
    assertThat(accessRows()).isEqualTo(before);
    Files.delete(copilot);
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
  void relocatedExtensionDirectoryCreatesNoGrantAndRemoveBridgeStillEndsAccessWithoutWritingThere() throws IOException {
    endpoints.create("store-bundle-moved", null, EndpointService.StartFrom.EMPTY, null);
    endpoints.create("store-bundle-kept", null, EndpointService.StartFrom.EMPTY, null);
    Path dir = Files.createDirectories(clients.environment().mcpbDirectory());
    var kept = clients.connect("claude-desktop",
        clients.previewConnect("claude-desktop", request("store-bundle-kept", "mcpb")).previewId());
    var preview = clients.previewConnect("claude-desktop", request("store-bundle-moved", "mcpb"));
    Path saved = Files.move(dir, dir.resolveSibling("claude-original"));
    Path redirected = Files.createDirectories(home.resolve("AppData/ClaudeBundles"));
    Files.createSymbolicLink(dir, redirected);
    Map<String, Integer> before = accessRows();
    try {
      assertThatThrownBy(() -> clients.connect("claude-desktop", preview.previewId())).isInstanceOf(ProblemException.class);
      assertThat(accessRows()).as("the refused Connect made no grant, token or link").isEqualTo(before);
      // The file work stays refused there; ending the access doesn't wait for it.
      clients.removeBridge();
      assertThat(core.jdbc().queryForObject("select revoked_at from client_grant where id = (select grant_id from "
          + "client_link where id = ?)", String.class, kept.link().id())).isNotNull();
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
      // A preview names the entry the person has to remove: this edition doesn't write into AppData.
      var again = clients.previewDisconnect(connected.link().id());
      assertThat(again.files()).isEmpty();
      assertThat(again.byHand()).isNotNull();
      assertThat(again.byHand().reason()).contains("AppData");
      assertThatThrownBy(() -> clients.restore("cursor", restore.previewId())).isInstanceOf(ProblemException.class);
      // Confirming the earlier preview ends the access and leaves the file alone.
      var done = clients.disconnect(connected.link().id(), disconnect.previewId());
      assertThat(done.entryRemoved()).isFalse();
      assertThat(done.byHand()).isNotNull();
      assertThat(done.byHand().reason()).contains("AppData");
      assertThat(accessRows()).as("no row was added or deleted").isEqualTo(before);
      assertThat(Files.readString(redirected.resolve("mcp.json"))).isEqualTo(original);
      assertThat(core.jdbc().queryForObject("select revoked_at from api_token where id = (select token_id from client_link where id = ?)",
          String.class, connected.link().id())).isNotNull();
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
