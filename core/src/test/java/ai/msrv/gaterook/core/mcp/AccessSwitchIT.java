package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** User pauses preserve discovery and configuration while refusing every resource access path. */
class AccessSwitchIT {

  @Test
  void endpointAndGlobalConnectionSwitchesComposeWithoutChangingModes() {
    try (TestCore core = TestCore.start("access-switch")) {
      core.connection("shared-db", "pgfake", Env.DEV, "res.test");
      var endpoints = core.bean(EndpointService.class);
      var connections = core.bean(ConnectionService.class);
      var snapshots = core.bean(EndpointSnapshotService.class);
      var modes = core.bean(EndpointTools.class);
      for (String slug : List.of("first", "second")) {
        endpoints.create(slug, null, EndpointService.StartFrom.EMPTY, null);
        core.bean(EndpointConnections.class).add(slug, "shared-db", null);
        for (String tool : CoreTools.WITH_MODE) {
          modes.setMode(slug, tool, null, Mode.READ_ONLY, null,
              endpoints.snapshot(slug).coreTool(tool).orElseThrow().rowVersion(), null);
        }
      }
      McpClient first = client(core, "first");
      McpClient second = client(core, "second");
      JsonNode originalTools = first.legacy("tools/list", null).result().path("tools");
      FakeConnectors.EXECUTIONS.clear();
      FakeConnectors.RESOURCE_READS.clear();
      endpoints.disable("first", null);
      assertThat(first.initialize("2025-11-25").status()).isEqualTo(200);
      assertThat(first.modern("server/discover", args(), false).status()).isEqualTo(200);
      assertThat(first.legacy("tools/list", null).result().path("tools")).isEqualTo(originalTools);
      refused(first.legacyCall("sql_query", args("connection", "shared-db", "sql", "SELECT 1")), "Endpoint first");
      refused(first.modernCall("sql_query", args("connection", "shared-db", "sql", "SELECT 1"), false), "Endpoint first");
      refused(first.legacyCall("describe_connection", args("connection", "shared-db")), "Endpoint first");
      assertThat(first.legacy("resources/read", args("uri", "mcpgw://connections/shared-db/schema"))
          .error().path("message").asString()).contains("Endpoint first", "disabled by the user");
      assertThat(FakeConnectors.EXECUTIONS).isEmpty();
      assertThat(FakeConnectors.RESOURCE_READS).isEmpty();
      assertThat(second.legacyCall("sql_query", args("connection", "shared-db", "sql", "SELECT 1"))
          .result().path("isError").asBoolean()).isFalse();

      AdminMvc api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
      JsonNode off = api.expect(200, HttpMethod.POST, "/api/connections/shared-db/disable", "{}");
      assertThat(off.path("enabled").asBoolean()).isFalse();
      assertThat(api.expect(200, HttpMethod.GET, "/api/connections/shared-db", null)
          .path("enabled").asBoolean(true)).isFalse();
      assertThat(connections.summary("shared-db").verified()).isTrue();
      assertThat(core.jdbc().queryForObject("select enabled from connection where name = 'shared-db'", Boolean.class))
          .isFalse();
      endpoints.enable("first", null);
      assertThat(first.legacy("tools/list", null).result().path("tools")).isEqualTo(originalTools);
      assertThat(first.legacy("resources/list", null).result().path("resources")).isNotEmpty();
      for (McpClient agent : List.of(first, second)) {
        refused(agent.legacyCall("sql_query", args("connection", "shared-db", "sql", "SELECT 1")), "Connection shared-db");
        refused(agent.legacyCall("sql_execute", args("connection", "shared-db", "sql", "DELETE FROM orders")), "Connection shared-db");
        refused(agent.legacyCall("describe_connection", args("connection", "shared-db")), "Connection shared-db");
        assertThat(agent.legacy("resources/read", args("uri", "mcpgw://connections/shared-db/schema"))
            .error().path("message").asString()).contains("Connection shared-db", "disabled by the user");
      }
      var listed = first.legacyCall("list_connections", args());
      assertThat(listed.result().at("/structuredContent/connections/0/enabled").asBoolean(true))
          .as("disabled connection in list_connections response: %s", listed.body()).isFalse();
      assertThat(FakeConnectors.EXECUTIONS).hasSize(1);
      assertThat(FakeConnectors.RESOURCE_READS).isEmpty();
      assertThatThrownBy(() -> snapshots.requireAvailable(endpoints.get("first").id(), "shared-db"))
          .hasMessageContaining("disabled by the user");
      JsonNode on = api.expect(200, HttpMethod.POST, "/api/connections/shared-db/enable", "{}");
      assertThat(on.path("enabled").asBoolean()).isTrue();
      assertThat(api.expect(200, HttpMethod.GET, "/api/connections/shared-db", null)
          .path("enabled").asBoolean()).isTrue();
      assertThat(first.legacy("tools/list", null).result().path("tools")).isEqualTo(originalTools);
      assertThat(first.legacyCall("sql_query", args("connection", "shared-db", "sql", "SELECT 1"))
          .result().path("isError").asBoolean()).isFalse();

      // The new flag is signed. An external edit must not turn a disabled connection back on.
      connections.setEnabled("shared-db", false, null);
      core.jdbc().update("update connection set enabled = true where name = 'shared-db'");
      assertThat(core.bean(ConnectionConfigs.class).byName("shared-db").orElseThrow().verified()).isFalse();
      api.expect(409, HttpMethod.POST, "/api/connections/shared-db/enable", "{}");
    }
  }

  @Test
  void pausesSurviveRestartAndRefusePreviouslyPendingApprovals() {
    java.nio.file.Path home;
    try (TestCore core = TestCore.start("access-pending")) {
      home = core.home();
      core.connection("pending-db", "pgfake", Env.DEV, "db.example.test");
      var endpoints = core.bean(EndpointService.class);
      var connections = core.bean(ConnectionService.class);
      var approvals = core.bean(ai.msrv.gaterook.core.approvals.ApprovalService.class);
      endpoints.create("paused", null, EndpointService.StartFrom.EMPTY, null);
      core.bean(EndpointConnections.class).add("paused", "pending-db", null);
      endpoints.updateSettings("paused", new EndpointService.SettingsUpdate(null, null, null, null, null,
          args().put("firstWaitSeconds", 0), null), endpoints.get("paused").version(), null);
      McpClient agent = client(core, "paused");
      FakeConnectors.EXECUTIONS.clear();
      for (boolean endpointPause : List.of(true, false)) {
        var result = agent.legacyCall("sql_execute", args("connection", "pending-db", "sql",
            "UPDATE orders SET status = 'cancelled' WHERE id = " + (endpointPause ? 1 : 2))).result();
        String handle = result.at("/structuredContent/handle").asString();
        assertThat(handle).startsWith("ap_");
        var waiting = approvals.find(handle).orElseThrow();
        if (endpointPause) endpoints.disable("paused", null);
        else connections.setEnabled("pending-db", false, null);
        approvals.decide(handle, ai.msrv.gaterook.core.approvals.ApprovalService.Decision.APPROVE,
            "app", null, waiting.digest());
        TestCore.await(() -> "rejected".equals(approvals.find(handle).orElseThrow().status()),
            "paused approval to be refused");
        assertThat(approvals.find(handle).orElseThrow().error()).contains("disabled by the user");
        if (endpointPause) endpoints.enable("paused", null);
      }
      assertThat(FakeConnectors.EXECUTIONS).isEmpty();
      endpoints.disable("paused", null);
    }
    try (TestCore restored = TestCore.reopen(home)) {
      assertThat(restored.bean(EndpointService.class).get("paused").status()).isEqualTo("disabled");
      var connection = restored.bean(ConnectionService.class).summary("pending-db");
      assertThat(connection.enabled()).isFalse();
      assertThat(connection.verified()).isTrue();
    }
  }

  private static McpClient client(TestCore core, String slug) {
    return new McpClient(core.bean(LocalListener.class).port(), slug,
        core.bean(TokenService.class).issue(slug, "test-client", 7, null).token());
  }

  private static void refused(McpClient.Answer answer, String object) {
    assertThat(answer.status()).isEqualTo(200);
    assertThat(answer.result().path("isError").asBoolean()).isTrue();
    assertThat(answer.result().at("/content/0/text").asString())
        .contains(object, "disabled by the user", "Ask the user", "Nothing ran");
  }
}
