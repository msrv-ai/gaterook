package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bypass persists per pair and runs prepared mutations without manufacturing a human approval. */
class McpBypassIT {

  @Test
  void externalReadToolsRunInBypassWithoutAReadOnlyAttestation() {
    try (TestCore core = TestCore.start("bypass-external")) {
      EndpointService endpoints = core.bean(EndpointService.class);
      endpoints.create("external", null, EndpointService.StartFrom.EMPTY, null);
      EndpointTools tools = core.bean(EndpointTools.class);
      FakeConnectors.EXECUTIONS.clear();
      for (String type : List.of("jirafake", "mcpfake")) {
        core.connection(type, type, Env.DEV, "service.test");
        core.bean(EndpointConnections.class).add("external", type, null);
        var pair = endpoints.snapshot("external").pairs().stream()
            .filter(p -> p.connectionName().equals(type)).findFirst().orElseThrow();
        assertThat(pair.readOnlyConfirmed()).isFalse();
        tools.setMode("external", pair.toolKey(), type, Mode.BYPASS, null, pair.rowVersion(), "Use Bypass");
        McpClient client = new McpClient(core.bean(LocalListener.class).port(), "external",
            core.bean(TokenService.class).issue("external", type, 7, null).token());
        assertThat(client.legacyCall(pair.tool().name(), args("connection", type, "query", "sample"))
            .result().path("isError").asBoolean()).isFalse();
      }
      assertThat(FakeConnectors.EXECUTIONS).hasSize(2).allSatisfy(e -> {
        assertThat(e.approved()).isFalse();
        assertThat(e.approvalId()).isNull();
      });
      assertThat(core.jdbc().queryForObject("select count(*) from approval", Integer.class)).isZero();
    }
  }

  @Test
  void bypassExecutesAndAuditsWhileKeepingDenialsAndPreparationChecks() {
    try (TestCore core = TestCore.start("bypass")) {
      core.connection("bypass-db", "pgfake", Env.PROD, "db.test");
      EndpointService endpoints = core.bean(EndpointService.class);
      endpoints.create("bypass-prod", null, EndpointService.StartFrom.EMPTY, null);
      core.bean(EndpointConnections.class).add("bypass-prod", "bypass-db", null);
      EndpointTools tools = core.bean(EndpointTools.class);
      var pair = endpoints.snapshot("bypass-prod").pairs().stream()
          .filter(p -> p.toolKey().equals("sql_execute")).findFirst().orElseThrow();
      tools.setMode("bypass-prod", pair.toolKey(), "bypass-db", Mode.BYPASS, null, pair.rowVersion(), "Test Bypass");
      assertThat(endpoints.get("bypass-prod").tools().bypass()).isEqualTo(1);
      assertThat(tools.list("bypass-prod").stream().flatMap(g -> g.tools().stream())
          .filter(t -> t.toolKey().equals("sql_execute")).findFirst().orElseThrow().storedMode()).isEqualTo("Bypass");
      McpClient client = new McpClient(core.bean(LocalListener.class).port(), "bypass-prod",
          core.bean(TokenService.class).issue("bypass-prod", "bypass-test", 7, null).token());
      FakeConnectors.EXECUTIONS.clear();
      String sql = "UPDATE orders SET status = 'ready' WHERE id = 1";
      for (boolean modern : List.of(false, true)) {
        var reply = modern ? client.modernCall("sql_execute", args("connection", "bypass-db", "sql", sql), true)
            : client.legacyCall("sql_execute", args("connection", "bypass-db", "sql", sql));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.result().path("isError").asBoolean()).isFalse();
        assertThat(reply.result().toString()).doesNotContain("NOT EXECUTED YET", "approvalUrl");
      }
      assertThat(FakeConnectors.EXECUTIONS).hasSize(2).allSatisfy(e -> {
        assertThat(e.sql()).isEqualTo(sql);
        assertThat(e.approved()).isTrue();
        assertThat(e.approvalId()).isNull();
      });
      assertThat(core.jdbc().queryForObject("select count(*) from approval", Integer.class)).isZero();
      var audit = core.jdbc().queryForList("select phase, decision, approval_id, facets from tool_call "
          + "where tool_name = 'sql_execute' order by id");
      assertThat(audit).hasSize(4).allSatisfy(row -> {
        assertThat(row.get("decision")).isEqualTo("allowed");
        assertThat(row.get("approval_id")).isNull();
        if ("received".equals(row.get("phase"))) {
          assertThat(row.get("facets").toString()).contains("BYPASS", "core.bypass");
        }
      });
      assertThat(client.legacyCall("sql_execute", args("connection", "bypass-db", "sql", "DROP TABLE orders"))
          .result().path("isError").asBoolean()).isTrue();
      assertThat(client.legacyCall("sql_execute", args("connection", "bypass-db", "sql", "UPDATE no_change"))
          .result().at("/content/0/text").asString()).contains("nothing to change");
      assertThat(client.legacyCall("sql_execute", args("connection", "bypass-db", "sql", "UPDATE leak_payload"))
          .result().path("isError").asBoolean()).isTrue();
      assertThat(FakeConnectors.EXECUTIONS).hasSize(2);
      endpoints.updateSettings("bypass-prod", new EndpointService.SettingsUpdate(null, "read-only", null, null,
          null, null, null), endpoints.get("bypass-prod").version(), "Make read only");
      var clamped = endpoints.snapshot("bypass-prod").pairs().stream()
          .filter(p -> p.toolKey().equals("sql_execute")).findFirst().orElseThrow();
      assertThat(clamped.mode()).isEqualTo(Mode.OFF);
      assertThatThrownBy(() -> tools.setMode("bypass-prod", clamped.toolKey(), "bypass-db", Mode.BYPASS, null,
          clamped.rowVersion(), "Test refusal")).isInstanceOf(ProblemException.class).hasMessageContaining("read only");
    }
  }
}
