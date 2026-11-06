package ai.msrv.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.clients.internal.JsonEntryEditor;
import ai.msrv.gaterook.core.clients.internal.TomlBlockEditor;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Reconnect adopts only the same local endpoint, after a preview, in an isolated client home. */
class ClientReconnectIT {
  private static final String OLD_TOKEN = "gwt_" + "a".repeat(43);
  private static TestCore core;
  private static ClientService clients;
  private static Path home;
  private static Path cursor;
  private static String origin;

  @BeforeAll
  static void start() throws IOException {
    home = Files.createTempDirectory(Files.createDirectories(Path.of("target/client-homes").toAbsolutePath()), "reconnect-");
    cursor = Files.createDirectories(home.resolve(".cursor")).resolve("mcp.json");
    core = TestCore.start("client-reconnect", "--gateway.clients.user-home=" + home, "--gateway.clients.os=linux");
    clients = core.bean(ClientService.class);
    origin = core.bean(LocalListener.class).origin();
  }

  @AfterAll
  static void stop() {
    if (core != null) core.close();
  }

  @Test
  void reconnectReplacesStaleCredentialsAndConsolidatesGeneratedDuplicatesOnlyAfterConfirm() throws IOException {
    String slug = endpoint("reconnect");
    String name = "gaterook-" + slug;
    String before = "{\n  \"theme\": \"dark\",\n  \"mcpServers\": {\n    \"external\": { \"url\": \"https://example.test/mcp\" }\n  }\n}\n";
    before = JsonEntryEditor.upsert(before, "mcpServers", name, entry(url(slug)));
    ObjectNode alias = entry(url(slug));
    ((ObjectNode) alias.get("headers")).put("Authorization", "Bearer ‹new token›");
    before = JsonEntryEditor.upsert(before, "mcpServers", "custom-alias", alias);
    before = JsonEntryEditor.upsert(before, "mcpServers", name + "-2", entry(url(slug)));
    before = JsonEntryEditor.upsert(before, "mcpServers", name + "-3", entry("http://example.test:8475/mcp/" + slug));
    Files.writeString(cursor, before);

    var preview = clients.previewConnect("cursor", request(slug, "token"));
    assertThat(preview.repair()).isTrue();
    assertThat(preview.toString()).doesNotContain(OLD_TOKEN);
    assertThat(Files.readString(cursor)).isEqualTo(before);
    assertThat(activeTokens(slug)).isZero();
    var connected = clients.connect("cursor", preview.previewId());
    assertThat(connected.link().entryName()).isEqualTo(name);
    String after = Files.readString(cursor);
    JsonNode servers = TestCore.json(after).path("mcpServers");
    assertThat(JsonEntryEditor.names(after, "mcpServers"))
        .containsExactlyInAnyOrder("external", "custom-alias", name, name + "-3");
    assertThat(after).contains("\"external\": { \"url\": \"https://example.test/mcp\" }");
    assertThat(servers.path("custom-alias")).isEqualTo(alias);
    assertThat(servers.path(name + "-3")).isEqualTo(entry("http://example.test:8475/mcp/" + slug));
    assertThat(servers.path(name).path("headers").path("Authorization").asString())
        .startsWith("Bearer gwt_").isNotEqualTo("Bearer " + OLD_TOKEN);
    String backup = core.jdbc().queryForObject("select backup_path from client_link where id = ?", String.class, connected.link().id());
    assertThat(Files.readString(Path.of(backup))).isEqualTo(before);

    String oldTokenId = core.jdbc().queryForObject("select token_id from client_link where id = ?", String.class, connected.link().id());
    var again = clients.previewConnect("cursor", request(slug, "token"));
    assertThat(Files.readString(cursor)).isEqualTo(after);
    var replaced = clients.connect("cursor", again.previewId());
    assertThat(replaced.link().id()).isEqualTo(connected.link().id());
    assertThat(replaced.link().entryName()).isEqualTo(name);
    assertThat(activeTokens(slug)).isEqualTo(1);
    assertThat(core.jdbc().queryForObject("select revoked_at from api_token where id = ?", String.class, oldTokenId)).isNotNull();
  }

  @Test
  void reconnectReusesAMatchingSuffixWithoutOverwritingTheSameNameOnAnotherServer() throws IOException {
    String slug = endpoint("suffix");
    String name = "gaterook-" + slug;
    String before = JsonEntryEditor.upsert("{}", "mcpServers", name, entry("http://example.test:8475/mcp/" + slug));
    before = JsonEntryEditor.upsert(before, "mcpServers", name + "-2", entry(url(slug)));
    Files.writeString(cursor, before);
    var connected = clients.connect("cursor", clients.previewConnect("cursor", request(slug, "token")).previewId());
    assertThat(connected.link().entryName()).isEqualTo(name + "-2");
    String after = Files.readString(cursor);
    assertThat(JsonEntryEditor.names(after, "mcpServers")).containsExactlyInAnyOrder(name, name + "-2");
    assertThat(JsonEntryEditor.entry(after, "mcpServers", name)).isEqualTo(JsonEntryEditor.entry(before, "mcpServers", name));
  }

  @Test
  void similarAddressesAreNotTreatedAsTheSameEndpoint() throws IOException {
    for (int i = 0; i < 7; i++) {
      String slug = endpoint("different-" + i);
      String target = url(slug);
      String different = switch (i) {
        case 0 -> target.replace("127.0.0.1", "localhost");
        case 1 -> "http://127.0.0.1:1/mcp/" + slug;
        case 2 -> target + "-other";
        case 3 -> target + "?scope=other";
        case 4 -> target + "#other";
        case 5 -> target.replace("http:", "https:");
        default -> target.replace("127.0.0.1", "user@127.0.0.1");
      };
      String name = "gaterook-" + slug;
      String before = JsonEntryEditor.upsert("{}", "mcpServers", name, entry(different));
      Files.writeString(cursor, before);
      var connected = clients.connect("cursor", clients.previewConnect("cursor", request(slug, "token")).previewId());
      assertThat(connected.link().entryName()).isEqualTo(name + "-2");
      assertThat(JsonEntryEditor.entry(Files.readString(cursor), "mcpServers", name))
          .isEqualTo(JsonEntryEditor.entry(before, "mcpServers", name));
    }
  }

  @Test
  void codexAdoptsAnUnmanagedTableAndPreservesOtherSettings() throws IOException {
    String slug = endpoint("codex-reconnect");
    Path file = Files.createDirectories(home.resolve(".codex")).resolve("config.toml");
    String name = "gaterook-" + slug;
    String before = "# Keep my settings\nmodel = \"example-model\"\n\n[mcp_servers.external]\nurl = \"https://example.test/mcp\"\n\n"
        + "[mcp_servers." + name + "]\nurl = \"" + url(slug) + "\"\n"
        + "[mcp_servers." + name + ".http_headers]\nAuthorization = \"Bearer " + OLD_TOKEN + "\"\n";
    Files.writeString(file, before);
    var preview = clients.previewConnect("codex", request(slug, "helper"));
    assertThat(preview.repair()).isTrue();
    assertThat(Files.readString(file)).isEqualTo(before);
    assertThat(preview.toString()).doesNotContain(OLD_TOKEN);
    var connected = clients.connect("codex", preview.previewId());
    assertThat(connected.link().entryName()).isEqualTo(name);
    String after = Files.readString(file);
    assertThat(TomlBlockEditor.names(after)).containsExactlyInAnyOrder("external", name);
    assertThat(after).contains("# Keep my settings\nmodel = \"example-model\"", "http_headers_helper").doesNotContain(OLD_TOKEN);
    assertThat(TomlBlockEditor.table(after, "external")).isEqualTo(TomlBlockEditor.table(before, "external"));
  }

  @Test
  void aReplacementStillRefusesAFileChangedAfterPreview() throws IOException {
    String slug = endpoint("stale-reconnect");
    String before = JsonEntryEditor.upsert("{}", "mcpServers", "gaterook-" + slug, entry(url(slug)));
    Files.writeString(cursor, before);
    var preview = clients.previewConnect("cursor", request(slug, "token"));
    String edited = before + "\n";
    Files.writeString(cursor, edited);
    assertThatThrownBy(() -> clients.connect("cursor", preview.previewId())).isInstanceOf(ProblemException.class)
        .hasMessageContaining("changed since the preview");
    assertThat(Files.readString(cursor)).isEqualTo(edited);
    assertThat(activeTokens(slug)).isZero();
  }

  private static String endpoint(String slug) {
    core.bean(EndpointService.class).create(slug, null, EndpointService.StartFrom.EMPTY, null);
    return slug;
  }

  private static String url(String slug) {
    return origin + "/mcp/" + slug;
  }

  private static ObjectNode entry(String url) {
    ObjectNode entry = JsonNodeFactory.instance.objectNode().put("url", url);
    entry.putObject("headers").put("Authorization", "Bearer " + OLD_TOKEN);
    return entry;
  }

  private static ClientService.ConnectRequest request(String slug, String method) {
    return new ClientService.ConnectRequest(slug, method, null,
        method.equals("token") ? new ClientService.TokenOptions("test-" + slug, 7) : null, null);
  }

  private static int activeTokens(String slug) {
    return core.jdbc().queryForObject("select count(*) from api_token where name = ? and revoked_at is null",
        Integer.class, "test-" + slug);
  }
}
