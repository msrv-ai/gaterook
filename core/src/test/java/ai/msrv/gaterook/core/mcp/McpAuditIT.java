package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.audit.AuditExports;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.audit.export.Destinations;
import ai.msrv.gaterook.core.audit.internal.AuditRetentionJob;
import ai.msrv.gaterook.core.audit.internal.ChainHash;
import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ExportKind;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The audit as MCP calls write it (ARCHITECTURE.md §4 "Audit", M1.5): one hash chain across every row, the Activity
 * query language over real rows, traces, a chain edited behind the core's back found at the next start, and the
 * retention that deletes only what is old enough and exported.
 */
class McpAuditIT {

  static final String TRACE = "4f2a9c71e8d04b5a9c3e2b1f0a6d8e81";

  @Test
  void activityQueriesTracesAndTheChainOverRealCalls() {
    try (TestCore core = world("audit-queries")) {
      McpClient dev = client(core, "dev", "codex-laptop");
      McpClient prod = client(core, "incident-prod", "bridge");
      // Legacy calls of dev say who they are through initialize; prod's never do.
      dev.initialize("2025-11-25");
      // A task the client named, under its own trace.
      for (int i = 0; i < 3; i++) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("name", "sql_query");
        p.set("arguments", args("connection", "dev-db", "sql", "SELECT id FROM orders LIMIT " + i));
        p.putObject("_meta").put("traceparent", "00-" + TRACE + "-7a1c2e9b4d3f6a1" + i + "-01")
            .put("ai.msrv.gaterook/task", "Fix failing order sync");
        assertThat(dev.modern("tools/call", p, false).result().path("isError").asBoolean()).isFalse();
      }
      dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT SLEEP1200"));
      dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT fail"));
      prod.legacyCall("sql_execute", args("connection", "prod-db", "sql", "DROP TABLE order_sync_queue_old;"));
      McpClient.Answer pending = dev.modernCall("sql_execute", args("connection", "dev-db", "sql",
          "UPDATE orders SET status = 'cancelled' WHERE id = 48213;"), false);
      String handle = pending.result().at("/structuredContent/handle").asString();
      ApprovalService approvals = core.bean(ApprovalService.class);
      approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, approvals.find(handle).orElseThrow()
          .digest());
      TestCore.await(() -> approvals.find(handle).orElseThrow().terminal(), "execution");

      AuditQueries q = core.bean(AuditQueries.class);
      assertThat(tools(q, "decision:denied")).containsExactly("sql_execute");
      assertThat(q.events("decision:denied", null, null, null, 10).events().getFirst().rule())
          .isEqualTo("prod.sql.block_ddl");
      // A call is what it came to: approved, not the pending it was first.
      assertThat(q.events("decision:approved", null, null, null, 10).events()).singleElement()
          .satisfies(e -> assertThat(e.approvalId()).isEqualTo(handle));
      assertThat(q.events("decision:pending", null, null, null, 10).events()).isEmpty();
      assertThat(tools(q, "decision:error")).containsExactly("sql_query");
      assertThat(tools(q, "decision:denied decision:approved")).containsExactlyInAnyOrder("sql_execute",
          "sql_execute");
      assertThat(tools(q, "-rule:prod.sql.block_ddl")).hasSize(6);
      assertThat(q.events("env:prod", null, null, null, 10).events()).hasSize(1);
      assertThat(q.events("tool:sql_* -tool:sql_query", null, null, null, 10).events()).hasSize(2);
      assertThat(q.events("tool:sql_q*", null, null, null, 10).events()).hasSize(5);
      assertThat(q.events("duration:>1s", null, null, null, 10).events()).singleElement()
          .satisfies(e -> assertThat(e.durationMs()).isGreaterThanOrEqualTo(1_200));
      assertThat(q.events("trace:4f2a9c", null, null, null, 10).events()).hasSize(3);
      assertThat(q.events("token:bridge", null, null, null, 10).events()).hasSize(1);
      assertThat(q.events("endpoint:incident-prod connection:prod-db", null, null, null, 10).events()).hasSize(1);
      assertThat(q.events("client:codex", null, null, null, 50).events()).hasSize(6);
      assertThat(q.events("-client:codex", null, null, null, 50).events()).hasSize(1);
      assertThat(q.events("order_sync", null, null, null, 10).events()).isEmpty();
      assertThat(q.events("runbook:compose-ps", null, null, null, 10).events()).isEmpty();
      assertThat(q.events("prod-db", null, null, null, 10).events()).hasSize(1);
      assertThatThrownBy(() -> q.events("status:denied", null, null, null, 10)).isInstanceOf(ProblemException.class);

      // Pages, newest first.
      AuditQueries.Events first = q.events(null, null, null, null, 3);
      assertThat(first.events()).hasSize(3);
      AuditQueries.Events rest = q.events(null, null, null, first.nextCursor(), 50);
      assertThat(rest.events()).hasSize(4);
      assertThat(rest.nextCursor()).isNull();
      assertThat(first.events().getLast().id()).isGreaterThan(rest.events().getFirst().id());
      assertThat(q.events(null, Instant.now().plusSeconds(60), null, null, 10).events()).isEmpty();

      // The client's trace, titled by its task.
      TestCore.await(() -> q.trace("4f2a9c").isPresent(), "the trace");
      TestCore.await(() -> q.trace("4f2a9c").orElseThrow().calls() == 3, "three calls in the trace");
      AuditQueries.Trace t = q.trace("4f2a9c").orElseThrow();
      assertThat(t.traceId()).isEqualTo(TRACE);
      assertThat(t.title()).isEqualTo("Fix failing order sync");
      assertThat(t.titleSource()).isEqualTo("meta");
      assertThat(t.client()).isEqualTo("codex");
      assertThat(t.endpoint()).isEqualTo("dev");
      assertThat(String.valueOf(t.summary().get("phases"))).contains("Explore");

      assertChainLinks(core);
    }
  }

  /**
   * A decision rewritten, or only the derived hour bucket moved (which the canonical form leaves out, so a call would
   * change its Insights and histogram bucket unseen): either way the chain breaks.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "set {0}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"decision = 'denied'", "hour_bucket = hour_bucket - 24"})
  void aRowEditedAroundTheTriggersIsFoundAtTheNextStart(String edit) throws Exception {
    Path home;
    try (TestCore a = world("audit-break")) {
      McpClient dev = client(a, "dev", "codex-laptop");
      for (int i = 0; i < 4; i++) {
        dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT " + i));
      }
      assertChainLinks(a);
      home = a.home();
    }
    try (TestCore b = TestCore.reopen(home)) {
      // A clean restart: nothing to report.
      Thread.sleep(500);
      assertThat(b.bean(IntegrityAlerts.class).has("audit", "chain")).isFalse();
      assertThat(b.bean(AuditQueries.class).changes("Audit history", null, 10)).isEmpty();
      // Someone with the database file: drop the trigger, rewrite a decision, put the trigger back.
      Long middle = b.jdbc().queryForObject("select min(id) + 2 from tool_call", Long.class);
      b.jdbc().execute("DROP TRIGGER tool_call_no_update");
      b.jdbc().update("update tool_call set " + edit + " where id = ?", middle);
      b.jdbc().execute("CREATE TRIGGER tool_call_no_update BEFORE UPDATE ON tool_call BEGIN SELECT RAISE(ABORT, "
          + "'audit is append-only'); END");
    }
    try (TestCore c = TestCore.reopen(home)) {
      IntegrityAlerts alerts = c.bean(IntegrityAlerts.class);
      TestCore.await(() -> alerts.has("audit", "chain"), "the integrity alert");
      List<AuditQueries.Change> changes = c.bean(AuditQueries.class).changes("Audit history", null, 10);
      assertThat(changes).singleElement().satisfies(ch -> {
        assertThat(ch.from()).isEqualTo("verified");
        assertThat(ch.to()).isEqualTo("not verified before this point");
        assertThat(ch.reason()).contains("was changed");
      });
      // The core keeps working: new calls chain on from the new anchor.
      client(c, "dev", "after-break").legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT 9"));
    }
    try (TestCore d = TestCore.reopen(home)) {
      Thread.sleep(500);
      // Re-anchored: the break is reported once, by the Setup change that stays.
      assertThat(d.bean(IntegrityAlerts.class).has("audit", "chain")).isFalse();
      assertThat(d.bean(AuditQueries.class).changes("Audit history", null, 10)).hasSize(1);
    }
  }

  /**
   * The agent's shell runs as the same OS user and may open the database while the core runs: drop a trigger, delete
   * the newest calls, put the trigger back. The minute's anchor checks the rows it vouched for before it moves, so the
   * deletion is reported in the running core, and the Setup change stays after a restart.
   */
  @Test
  void callsDeletedWhileTheCoreRunsAreReportedBeforeTheAnchorMovesOverThem() throws Exception {
    Path home;
    try (TestCore a = world("audit-delete-running", "--gateway.audit.anchor-interval=200ms")) {
      McpClient dev = client(a, "dev", "codex-laptop");
      for (int i = 0; i < 3; i++) {
        dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT " + i));
      }
      long newest = awaitAnchoredHead(a);
      assertThat(a.bean(IntegrityAlerts.class).has("audit", "chain")).isFalse();
      a.jdbc().execute("DROP TRIGGER tool_call_no_early_delete");
      assertThat(a.jdbc().update("delete from tool_call where id > ?", newest - 2)).isEqualTo(2);
      a.jdbc().execute(NO_EARLY_DELETE);
      IntegrityAlerts alerts = a.bean(IntegrityAlerts.class);
      AuditQueries q = a.bean(AuditQueries.class);
      TestCore.await(() -> alerts.has("audit", "chain"), "the integrity alert");
      TestCore.await(() -> !q.changes("Audit history", null, 10).isEmpty(), "the Setup change");
      assertThat(q.changes("Audit history", null, 10)).singleElement()
          .satisfies(ch -> assertThat(ch.reason()).contains("agent call row " + newest + " was removed or changed"));
      home = a.home();
    }
    try (TestCore b = TestCore.reopen(home)) {
      Thread.sleep(500);
      // Reported once, by the Setup change that stays; the chain goes on from the new anchor.
      assertThat(b.bean(IntegrityAlerts.class).has("audit", "chain")).isFalse();
      assertThat(b.bean(AuditQueries.class).changes("Audit history", null, 10)).hasSize(1);
    }
  }

  /**
   * A decision rewritten and every hash after it recomputed, so the chain links again from the genesis on — while the
   * core runs. Only the anchor can tell: the stop doesn't move it over the rewritten head, and the next start reports
   * the break.
   */
  @Test
  void aRewriteWithItsHashesRecomputedWhileTheCoreRunsIsFoundAtTheNextStart() throws Exception {
    Path home;
    try (TestCore a = world("audit-rewrite", "--gateway.audit.anchor-interval=1h")) {
      McpClient dev = client(a, "dev", "codex-laptop");
      for (int i = 0; i < 4; i++) {
        dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT " + i));
      }
      home = a.home();
    }
    try (TestCore b = TestCore.reopen(home, "--gateway.audit.anchor-interval=1h")) {
      // The start anchored every row above; now someone rewrites the third and recomputes the chain after it.
      long head = awaitAnchoredHead(b);
      long middle = b.jdbc().queryForObject("select min(id) + 2 from tool_call", Long.class);
      b.jdbc().execute("DROP TRIGGER tool_call_no_update");
      b.jdbc().update("update tool_call set decision = 'denied' where id = ?", middle);
      String prev = b.jdbc().queryForObject("select row_sha256 from tool_call where id = ?", String.class, middle - 1);
      ToolCallRepository rows = b.bean(ToolCallRepository.class);
      for (long id = middle; id <= head; id++) {
        b.jdbc().update("update tool_call set prev_sha256 = ? where id = ?", prev, id);
        String sha = ChainHash.ofStored(prev, rows.findById(id).orElseThrow());
        b.jdbc().update("update tool_call set row_sha256 = ? where id = ?", sha, id);
        prev = sha;
      }
      b.jdbc().execute("CREATE TRIGGER tool_call_no_update BEFORE UPDATE ON tool_call BEGIN SELECT RAISE(ABORT, "
          + "'audit is append-only'); END");
      assertChainLinks(b);
    }
    try (TestCore c = TestCore.reopen(home)) {
      IntegrityAlerts alerts = c.bean(IntegrityAlerts.class);
      TestCore.await(() -> alerts.has("audit", "chain"), "the integrity alert");
      assertThat(c.bean(AuditQueries.class).changes("Audit history", null, 10)).singleElement()
          .satisfies(ch -> assertThat(ch.reason()).contains("was removed or changed"));
    }
  }

  @Test
  void anApprovedCallLeftRunningByAStopIsFailedWithAnUnknownOutcomeAtTheNextStart() {
    Path home;
    String handle;
    String token = null;
    int port;
    try (TestCore a = world("approval-stop")) {
      token = a.bean(TokenService.class).issue("dev", "codex-laptop", 7, null).token();
      McpClient dev = new McpClient(a.bean(LocalListener.class).port(), "dev", token);
      McpClient.Answer pending = dev.modernCall("sql_execute", args("connection", "dev-db", "sql",
          "UPDATE orders SET status = 'lost' WHERE id = 5;"), false);
      handle = pending.result().at("/structuredContent/handle").asString();
      // What a kill between the Approve and the outcome leaves: the request running and no completed row.
      a.jdbc().update("update approval set status = 'running', decided_via = 'app', decided_at = ? where id = ?",
          System.currentTimeMillis(), handle);
      home = a.home();
    }
    FakeConnectors.EXECUTIONS.clear();
    try (TestCore b = TestCore.reopen(home)) {
      port = b.bean(LocalListener.class).port();
      ApprovalService approvals = b.bean(ApprovalService.class);
      ApprovalView after = approvals.find(handle).orElseThrow();
      assertThat(after.status()).isEqualTo("failed");
      assertThat(after.error()).isEqualTo("MCP Gateway stopped while it ran, so whether it applied is unknown. "
          + "Not retried.");
      McpClient.Answer status = new McpClient(port, "dev", token).legacyCall("approval_status", args("handle", handle));
      SpecSchemas.assertValid("2025-11-25", "CallToolResult", status.result());
      assertThat(status.result().path("isError").asBoolean()).isTrue();
      assertThat(status.result().at("/structuredContent/status").asString()).isEqualTo("failed");
      assertThat(status.result().at("/content/0/text").asString()).contains("whether it applied is unknown");
      Map<String, Object> completed = b.jdbc().queryForMap("select decision, outcome_unknown, error_kind, "
          + "action_sha256, executed_sha256 from tool_call where approval_id = ? and phase = 'completed'", handle);
      assertThat(completed.get("decision")).isEqualTo("error");
      assertThat(String.valueOf(completed.get("outcome_unknown"))).isIn("1", "true");
      assertThat(completed.get("error_kind")).isEqualTo("OUTCOME_UNKNOWN");
      assertThat(completed.get("action_sha256")).isNotNull();
      assertThat(completed.get("executed_sha256")).isNull();
      assertThat(b.jdbc().queryForList("select kind from approval_event where approval_id = ? order by id",
          String.class, handle)).endsWith("failed");
      assertThat(FakeConnectors.EXECUTIONS).as("never run again").isEmpty();
      assertChainLinks(b);
    }
    try (TestCore c = TestCore.reopen(home)) {
      // Closed once: the next start finds nothing to close.
      assertThat(c.jdbc().queryForObject("select count(*) from tool_call where approval_id = ? and phase = "
          + "'completed'", Integer.class, handle)).isEqualTo(1);
    }
  }

  /**
   * The database took the completed row of an executed mutation but not its request's status change, and the core
   * stopped before the retry: the next start ends the request as the row says — completed, no second row, not
   * "unknown".
   */
  @Test
  void anApprovedCallWhoseOutcomeReachedTheAuditEndsAsTheAuditSaysAtTheNextStart() throws Exception {
    Path home;
    String handle;
    String token;
    FakeConnectors.EXECUTIONS.clear();
    try (TestCore a = world("approval-outcome-kept")) {
      token = a.bean(TokenService.class).issue("dev", "codex-laptop", 7, null).token();
      McpClient dev = new McpClient(a.bean(LocalListener.class).port(), "dev", token);
      handle = dev.modernCall("sql_execute", args("connection", "dev-db", "sql",
          "UPDATE orders SET status = 'kept' WHERE id = 6;"), false).result().at("/structuredContent/handle").asString();
      a.jdbc().execute("CREATE TRIGGER it_status_down BEFORE UPDATE OF status ON approval WHEN NEW.status IN "
          + "('completed', 'failed') BEGIN SELECT RAISE(ABORT, 'disk full'); END");
      ApprovalService approvals = a.bean(ApprovalService.class);
      approvals.decide(handle, ApprovalService.Decision.APPROVE, "app", null, approvals.find(handle).orElseThrow()
          .digest());
      String h = handle;
      TestCore.await(() -> a.jdbc().queryForObject("select count(*) from tool_call where approval_id = ? and phase = "
          + "'completed'", Integer.class, h) == 1, "the completed row");
      assertThat(approvals.find(handle).orElseThrow().status()).isEqualTo("running");
      home = a.home();
    }
    // The outage outlived the core; the next start finds the database writable again.
    try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + home.resolve("gateway.db"));
        java.sql.Statement st = c.createStatement()) {
      st.execute("DROP TRIGGER it_status_down");
    }
    try (TestCore b = TestCore.reopen(home)) {
      ApprovalView after = b.bean(ApprovalService.class).find(handle).orElseThrow();
      assertThat(after.status()).isEqualTo("completed");
      assertThat(after.error()).isNull();
      McpClient.Answer status = new McpClient(b.bean(LocalListener.class).port(), "dev", token)
          .legacyCall("approval_status", args("handle", handle));
      SpecSchemas.assertValid("2025-11-25", "CallToolResult", status.result());
      assertThat(status.result().path("isError").asBoolean()).isFalse();
      assertThat(status.result().at("/structuredContent/status").asString()).isEqualTo("completed");
      assertThat(status.result().at("/structuredContent/resultExpired").asBoolean()).isTrue();
      assertThat(b.jdbc().queryForList("select decision from tool_call where approval_id = ? and phase = 'completed'",
          String.class, handle)).containsExactly("approved");
      assertThat(b.jdbc().queryForList("select kind from approval_event where approval_id = ? order by id",
          String.class, handle)).endsWith("completed");
      assertThat(FakeConnectors.EXECUTIONS).as("ran once, never again").hasSize(1);
      assertChainLinks(b);
    }
  }

  @Test
  void retentionDeletesOnlyOldAndExportedRows() {
    try (TestCore core = world("audit-retention")) {
      McpClient dev = client(core, "dev", "codex-laptop");
      dev.legacyCall("sql_query", args("connection", "dev-db", "sql", "SELECT 1"));
      long old = Instant.now().minus(Duration.ofDays(400)).toEpochMilli();
      long older = Instant.now().minus(Duration.ofDays(800)).toEpochMilli();
      // Rows from before the history this database verifies (ids below its chain), as an older install left them.
      long id = -2;
      for (long at : new long[] {older, old}) {
        core.jdbc().update("insert into tool_call (id, call_id, phase, created_at, hour_bucket, endpoint_slug, "
            + "tool_name, decision, prev_sha256, row_sha256) values (?, ?, 'received', ?, ?, 'dev', 'sql_query', "
            + "'allowed', ?, ?)", id++, "tc_old" + at, at, at / 3_600_000, "0".repeat(64), "0".repeat(64));
      }
      // Younger than a year: not even SQL deletes it.
      Long young = core.jdbc().queryForObject("select max(id) from tool_call", Long.class);
      assertThatThrownBy(() -> core.jdbc().update("delete from tool_call where id = ?", young))
          .hasMessageContaining("audit is kept at least 1 year");

      AuditRetentionJob job = core.bean(AuditRetentionJob.class);
      SettingStore settings = core.bean(SettingStore.class);
      settings.put(AuditRetentionJob.SETTING, JsonNodeFactory.instance.objectNode().put("retentionYears", 3), null);
      assertThat(job.run().toolCalls()).as("3 years keeps both").isZero();

      // An export that took only the older row holds the other one back. Its position is the core's, signed with
      // the row (review K5): one written around the core holds everything.
      long olderId = core.jdbc().queryForObject("select id from tool_call where call_id = ?", Long.class,
          "tc_old" + older);
      AuditExportEntity export = new AuditExportEntity(ExportKind.SIEM, "siem", Map.of("host", "siem.example",
          "port", 6514), "hourly");
      export.exported(olderId, Instant.now());
      new TransactionTemplate(core.bean(PlatformTransactionManager.class))
          .executeWithoutResult(s -> core.bean(Destinations.class).sign(export));
      settings.put(AuditRetentionJob.SETTING, JsonNodeFactory.instance.objectNode().put("retentionYears", 1), null);
      AuditRetentionJob.Run run = job.run();
      assertThat(run.toolCalls()).isEqualTo(1);
      assertThat(core.jdbc().queryForList("select call_id from tool_call where call_id like 'tc_old%'", String.class))
          .containsExactly("tc_old" + old);
      // Paused, it still holds it back (K5: "removed only after every export destination has received them"); gone,
      // it no longer does.
      AuditExports exports = core.bean(AuditExports.class);
      exports.update(export.getId(), new AuditExports.Patch(null, false, null, null, null, null), null);
      assertThat(job.run().toolCalls()).isZero();
      exports.delete(export.getId(), null);
      assertThat(job.run().toolCalls()).isEqualTo(1);
      assertThat(core.jdbc().queryForObject("select count(*) from tool_call where call_id like 'tc_old%'",
          Integer.class)).isZero();
      // What the agent did today is all there.
      assertThat(core.jdbc().queryForObject("select count(*) from tool_call where created_at > ?", Integer.class,
          Instant.now().minus(Duration.ofDays(1)).toEpochMilli())).isEqualTo(2);
      assertThat(job.retentionYears()).isEqualTo(1);
      // The anchor followed the deletes: retention is no break of the history.
      assertThat(core.bean(IntegrityAlerts.class).has("audit", "chain")).isFalse();
      assertThat(core.bean(AuditQueries.class).changes("Audit history", null, 10)).isEmpty();
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** V1's floor on deletes, as a script that dropped it puts it back. */
  static final String NO_EARLY_DELETE = "CREATE TRIGGER tool_call_no_early_delete BEFORE DELETE ON tool_call WHEN "
      + "OLD.created_at > (unixepoch('now','-365 days') * 1000) BEGIN SELECT RAISE(ABORT, 'audit is kept at least 1 "
      + "year'); END";

  /** dev (dev-db) and incident-prod (prod-db), EMPTY, the first answer of an approval without waiting. */
  static TestCore world(String name, String... args) {
    TestCore core = TestCore.start(name, args);
    core.connection("dev-db", "pgfake", Env.DEV, "db.dev.internal");
    core.connection("prod-db", "pgfake", Env.PROD, "db.prod.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    EndpointConnections wiring = core.bean(EndpointConnections.class);
    for (Map.Entry<String, String> e : Map.of("dev", "dev-db", "incident-prod", "prod-db").entrySet()) {
      endpoints.create(e.getKey(), null, EndpointService.StartFrom.EMPTY, null);
      wiring.add(e.getKey(), e.getValue(), null);
      endpoints.updateSettings(e.getKey(), new EndpointService.SettingsUpdate(null, null, null, null, null,
          JsonNodeFactory.instance.objectNode().put("firstWaitSeconds", 0), null), endpoints.get(e.getKey()).version(),
          null);
    }
    return core;
  }

  static McpClient client(TestCore core, String slug, String tokenLabel) {
    return new McpClient(core.bean(LocalListener.class).port(), slug, core.bean(TokenService.class)
        .issue(slug, tokenLabel, 7, null).token());
  }

  static List<String> tools(AuditQueries q, String query) {
    return q.events(query, null, null, null, 50).events().stream().map(AuditQueries.Event::tool).toList();
  }

  /** Waits until the stored anchor names the newest agent call row; returns its id. */
  static long awaitAnchoredHead(TestCore core) {
    long newest = core.jdbc().queryForObject("select max(id) from tool_call", Long.class);
    SettingStore settings = core.bean(SettingStore.class);
    TestCore.await(() -> settings.find("audit.head-mac").map(s -> s.value().at("/heads/toolCall").asLong())
        .orElse(0L) == newest, "the anchor over row " + newest);
    return newest;
  }

  /** Every row links to the one before it, from the genesis on. */
  static void assertChainLinks(TestCore core) {
    List<Map<String, Object>> rows = core.jdbc().queryForList("select id, prev_sha256, row_sha256 from tool_call order "
        + "by id");
    assertThat(rows).isNotEmpty();
    String expected = "0".repeat(64);
    for (Map<String, Object> r : rows) {
      assertThat(r.get("prev_sha256")).as("row " + r.get("id")).isEqualTo(expected);
      expected = (String) r.get("row_sha256");
      assertThat(expected).hasSize(64).isNotEqualTo("0".repeat(64));
    }
    JsonNode anchor = core.bean(SettingStore.class).find("audit.head-mac").map(SettingStore.Setting::value)
        .orElse(null);
    assertThat(anchor).as("the anchor").isNotNull();
  }
}
