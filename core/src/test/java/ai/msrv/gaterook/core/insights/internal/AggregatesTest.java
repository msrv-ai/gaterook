package ai.msrv.gaterook.core.insights.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.audit.AuditQueries.CallFacts;
import ai.msrv.gaterook.core.insights.Insights;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The counts of Insights over hand-made calls: what is a decided call, a request, a wait, a task. */
class AggregatesTest {

  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");
  private long ids = 1;

  @Test
  void totalsCountDecidedCallsWaitingOnesAndTasks() {
    List<CallFacts> calls = List.of(call("allowed", "t1", 5L), call("allowed", "t1", 9L), call("approved", "t2", 31L),
        call("pending", "t3", null), call("denied", "x", null), call("error", null, 12L));

    Insights.Totals t = Aggregates.totals(calls, Set.of("t1", "t2", "t3"));

    assertThat(t.calls()).isEqualTo(5);
    assertThat(t.pending()).isEqualTo(1);
    assertThat(List.of(t.approved(), t.denied(), t.errors())).containsExactly(1L, 1L, 1L);
    assertThat(t.tasks()).as("t1, t2 and the waiting t3; x is no task").isEqualTo(3);
    assertThat(t.gatewayP50Ms()).isEqualTo(9);
    assertThat(t.gatewayP95Ms()).isEqualTo(31);
    assertThat(Aggregates.decisions(calls)).containsExactlyInAnyOrderEntriesOf(Map.of("allowed", 2L, "approved", 1L,
        "denied", 1L, "error", 1L));
  }

  @Test
  void approvalsCountRequestsOnceAndWaitsOfPeopleOnly() {
    List<CallFacts> calls = new ArrayList<>();
    calls.add(approval("approved", "ap_1", "app", 47_000));
    calls.add(approval("approved", "ap_1", "app", 47_000));
    calls.add(approval("rejected", "ap_2", "slack", 132_000));
    calls.add(approval("expired", "ap_3", "timeout", 300_000));
    calls.add(approval("rejected", "ap_4", "core", 10_000));
    calls.add(approval("error", "ap_5", "app", 185_000));

    Insights.Approvals a = Aggregates.approvals(calls, Map.of());

    assertThat(List.of(a.approved(), a.rejected(), a.expired())).as("ap_5 ran and failed: approved")
        .containsExactly(2L, 2L, 1L);
    assertThat(a.approvedShare()).isEqualTo(0.4);
    assertThat(a.medianWaitS()).as("47, 132, 185: the expired and the core's are no person's").isEqualTo(132);
    assertThat(a.waitHistogram()).extracting(Insights.WaitBucket::count).containsExactly(1L, 0L, 2L, 0L);
    assertThat(a.waitHistogram()).extracting(Insights.WaitBucket::toS).containsExactly(60L, 120L, 300L, null);
    assertThat(a.slowest()).extracting(Insights.SlowDecision::waitS).containsExactly(185L, 132L, 47L);
    assertThat(a.slowest().getFirst().requestedAt()).isEqualTo(AT.minusSeconds(185));

    Insights.Approvals none = Aggregates.approvals(List.of(call("allowed", null, 1L)), Map.of());
    assertThat(none.approvedShare()).isNull();
    assertThat(none.medianWaitS()).isNull();
    assertThat(none.waitHistogram()).isEmpty();
  }

  @Test
  void deniedRulesErrorsAndLatencyAreRankedWithTheirExamples() {
    List<CallFacts> calls = new ArrayList<>();
    calls.add(denied("mode.off", "ssh_exec", Map.of("command", "ls")));
    calls.add(denied("mode.off", "ssh_exec", Map.of("command", "ls")));
    calls.add(denied("prod.sql.block_ddl", "sql_execute", Map.of("sql", "DROP TABLE x")));
    calls.add(failed("cache-redis", "TIMEOUT", "ETIMEDOUT after 2.0 s"));
    calls.add(failed("cache-redis", "TIMEOUT", "ETIMEDOUT after 2.0 s"));
    calls.add(failed("cache-redis", "RESOURCE_ERROR", "WRONGTYPE"));
    calls.add(failed("billing-api", "RESOURCE_UNAVAILABLE", "ECONNREFUSED"));

    assertThat(Aggregates.deniedByRule(calls)).extracting(d -> d.rule() + " " + d.count() + " "
        + d.example().literal()).containsExactly("mode.off 2 ssh_exec", "prod.sql.block_ddl 1 DROP TABLE");
    assertThat(Aggregates.errorsByConnection(calls)).extracting(e -> e.connection() + " " + e.count() + " " + e.kind()
        + " " + e.message()).containsExactly("cache-redis 3 TIMEOUT ETIMEDOUT after 2.0 s",
        "billing-api 1 RESOURCE_UNAVAILABLE ECONNREFUSED");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private CallFacts call(String decision, String trace, Long gatewayMs) {
    long id = ids++;
    return new CallFacts(id, "tc_" + id, AT, decision, "sql_query", "dev-db", "dev", "codex", "dev", null, null, null,
        trace, gatewayMs == null ? null : gatewayMs + 10, gatewayMs, gatewayMs == null ? null : 10L, null, null, null,
        null);
  }

  private CallFacts approval(String decision, String approvalId, String via, long waitMs) {
    long id = ids++;
    return new CallFacts(id, "tc_" + id, AT, decision, "sql_execute", "dev-db", "dev", "codex", "dev",
        "dev.sql.write-approval", approvalId, via, null, null, null, null, waitMs, null, null, null);
  }

  private CallFacts denied(String rule, String tool, Map<String, Object> args) {
    long id = ids++;
    return new CallFacts(id, "tc_" + id, AT, "denied", tool, "dev-vm", "dev", "codex", "dev", rule, null, null, null,
        null, null, null, null, null, null, args);
  }

  private CallFacts failed(String connection, String kind, String message) {
    long id = ids++;
    return new CallFacts(id, "tc_" + id, AT, "error", "redis_get", connection, "dev", "codex", "dev", null, null, null,
        null, 2010L, 10L, 2000L, null, kind, message, null);
  }
}
