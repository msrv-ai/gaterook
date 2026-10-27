package ai.msrv.gaterook.core.mcp;

import static ai.msrv.gaterook.core.mcp.McpClient.args;
import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A request that ended without running lasted as long as it waited (REMAINING §4, K5): its calls' rows carry the wait
 * as their {@code duration_ms} — Activity shows "52 s" instead of nothing, Insights counts the waits, and
 * {@code duration:>…} finds them. An approved call keeps the duration of its run.
 */
class McpApprovalDurationIT {

  @Test
  void rejectedAndExpiredRequestsCarryTheirWaitAsTheCallsDuration() throws Exception {
    try (TestCore core = McpAuditIT.world("approval-duration")) {
      McpClient dev = McpAuditIT.client(core, "dev", "codex-laptop");
      ApprovalService approvals = core.bean(ApprovalService.class);

      String rejected = handle(dev.modernCall("sql_execute", args("connection", "dev-db", "sql",
          "UPDATE orders SET status = 'cancelled' WHERE id = 48213;"), false));
      String expired = handle(dev.modernCall("sql_execute", args("connection", "dev-db", "sql",
          "UPDATE orders SET status = 'shipped' WHERE id = 48190;"), false));
      String approved = handle(dev.modernCall("sql_execute", args("connection", "dev-db", "sql",
          "UPDATE orders SET status = 'paid' WHERE id = 48177;"), false));
      Thread.sleep(60);
      approvals.decide(rejected, ApprovalService.Decision.DENY, "app", "Not now", null);
      core.jdbc().update("update approval set expires_at = ? where id = ?", Instant.now().toEpochMilli() - 1_000,
          expired);
      assertThat(approvals.expireDue()).isEqualTo(1);
      approvals.decide(approved, ApprovalService.Decision.APPROVE, "app", null,
          approvals.find(approved).orElseThrow().digest());
      TestCore.await(() -> approvals.find(approved).orElseThrow().terminal(), "the approved call ran");

      AuditQueries q = core.bean(AuditQueries.class);
      for (String decision : List.of("rejected", "expired")) {
        AuditQueries.Event event = q.events("decision:" + decision, null, null, null, 10).events().getFirst();
        AuditQueries.Row resolved = q.detail(event.id()).orElseThrow().rows().getLast();
        assertThat(resolved.phase()).isEqualTo("resolved");
        assertThat(resolved.approvalWaitMs()).isNotNull();
        assertThat(event.durationMs()).as(decision + ": the wait is the call's duration")
            .isEqualTo(resolved.approvalWaitMs()).isGreaterThanOrEqualTo(60);
        assertThat(resolved.durationMs()).isEqualTo(resolved.approvalWaitMs());
      }
      assertThat(q.events("decision:rejected duration:>=50ms", null, null, null, 10).events()).hasSize(1);

      AuditQueries.Event ran = q.events("decision:approved", null, null, null, 10).events().getFirst();
      AuditQueries.Row completed = q.detail(ran.id()).orElseThrow().rows().getLast();
      assertThat(completed.phase()).isEqualTo("completed");
      assertThat(completed.durationMs()).isNotNull();
      assertThat(ran.durationMs()).as("an approved call: the time of its run").isEqualTo(completed.durationMs());
      McpAuditIT.assertChainLinks(core);
    }
  }

  private static String handle(McpClient.Answer answer) {
    return answer.result().at("/structuredContent/handle").asString();
  }
}
