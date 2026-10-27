package ai.msrv.gaterook.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.audit.internal.persistence.ApprovalDeliveryMode;
import ai.msrv.gaterook.core.audit.internal.persistence.CallDecision;
import ai.msrv.gaterook.core.audit.internal.persistence.CallPhase;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Golden traces of the kit (design/pages/activity.html, design/elements/event-detail.html): 4f2a9c, "Fix failing order
 * sync" (23 calls, 18 min, Explore → Change → Wait for deploy → Verify, 47 s waiting for you) and b81e44, "Investigate
 * failed order sync on prod" (14 calls, 8 min, Explore → Clean up), built as the audit rows the gateway writes.
 */
class TraceSummarizerTest {

  static final ZoneOffset CEST = ZoneOffset.ofHours(2);
  static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
  static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

  @Test
  void fixFailingOrderSync() {
    Trace t = new Trace("dev", "codex", "codex-mcp-client", "Fix failing order sync");
    // Explore · 9 calls · 13:44–13:51 · 8 allowed, 1 error
    t.read("13:44:02", "logs_search", "app-logs", Env.DEV, "query", "order sync failed");
    t.read("13:44:40", "logs_search", "app-logs", Env.DEV, "query", "order-sync level:error");
    t.read("13:45:12", "sql_query", "dev-db", Env.DEV, "sql", "SELECT * FROM sync_failures ORDER BY at DESC LIMIT 20");
    t.read("13:46:00", "sql_query", "dev-db", Env.DEV, "sql", "SELECT status, count(*) FROM orders GROUP BY 1");
    t.timeout("13:47:05", "redis_get", "cache-redis", Env.DEV, "key", "order-sync:cursor");
    t.read("13:48:10", "logs_search", "app-logs", Env.DEV, "query", "order-sync cursor");
    t.read("13:49:00", "sql_query", "dev-db", Env.DEV, "sql", "SELECT * FROM sync_cursor");
    t.read("13:50:02", "redis_get", "cache-redis", Env.DEV, "key", "order-sync:lock");
    t.read("13:51:10", "logs_search", "app-logs", Env.DEV, "query", "order-sync lock held");
    // Change · 3 calls · 13:51–13:52 · 2 allowed, 1 approved after 47 s
    t.read("13:51:20", "sql_query", "dev-db", Env.DEV, "sql", "SELECT * FROM orders WHERE id = 48213");
    t.read("13:51:35", "sql_query", "dev-db", Env.DEV, "sql", "SELECT * FROM sync_cursor WHERE id = 1");
    t.approved("13:51:50", "sql_execute", "dev-db", Env.DEV, "UPDATE orders SET status = 'cancelled' WHERE id = 48213;",
        47_000);
    // Wait for deploy · 6 calls · 13:53–13:58 · 6 allowed
    for (String at : List.of("13:53:00", "13:54:00", "13:55:00", "13:56:00", "13:57:00", "13:58:00")) {
      t.k8s(at, "dev-cluster", Env.DEV, "Deployment", "api");
    }
    // Verify · 5 calls · 13:58–14:02 · 5 allowed
    t.read("13:58:30", "logs_search", "app-logs", Env.DEV, "query", "order-sync since:13:58");
    t.read("13:59:30", "sql_query", "dev-db", Env.DEV, "sql", "SELECT count(*) FROM sync_failures WHERE at > now() - "
        + "interval '1 minute'");
    t.read("14:00:30", "logs_search", "app-logs", Env.DEV, "query", "order-sync level:error since:13:58");
    t.read("14:01:30", "sql_query", "dev-db", Env.DEV, "sql", "SELECT status FROM orders WHERE id = 48213");
    t.read("14:02:10", "logs_search", "app-logs", Env.DEV, "query", "order-sync ok");

    TraceSummarizer.Summary s = TraceSummarizer.summarize(TraceSummarizer.steps(t.rows()));
    assertThat(s.title()).isEqualTo("Fix failing order sync");
    assertThat(s.titleSource()).isEqualTo("meta");
    assertThat(s.calls()).isEqualTo(23);
    assertThat(local(s.firstAt(), HMS)).isEqualTo("13:44:02");
    assertThat(local(s.lastAt(), HMS)).isEqualTo("14:02:10");
    assertThat(minutes(s.duration())).isEqualTo(18);
    assertThat(s.decisions()).containsExactly(Map.entry("allowed", 21), Map.entry("approved", 1),
        Map.entry("error", 1));
    assertThat(s.waitedForPeopleMs()).isEqualTo(47_000);
    assertThat(s.client()).isEqualTo("codex");
    assertThat(s.endpoint()).isEqualTo("dev");
    assertThat(s.waiting()).isFalse();
    assertThat(s.status(at("14:30:00"))).isEqualTo("ended");
    assertThat(s.status(at("14:05:00"))).isEqualTo("in-progress");

    assertThat(s.phases()).extracting(p -> p.name() + " · " + p.calls() + " calls · " + local(p.from(), HM) + "–"
        + local(p.to(), HM) + " · " + p.decisions() + " · " + p.summary()).containsExactly(
        "Explore · 9 calls · 13:44–13:51 · {allowed=8, error=1} · logs_search on app-logs, sql_query on dev-db, "
            + "redis_get on cache-redis",
        "Change · 3 calls · 13:51–13:52 · {allowed=2, approved=1} · sql_query twice, then sql_execute approved by "
            + "you after 47 s",
        "Wait for deploy · 6 calls · 13:53–13:58 · {allowed=6} · k8s_get on dev-cluster, polling the rollout of "
            + "deployment/api",
        "Verify · 5 calls · 13:58–14:02 · {allowed=5} · logs_search on app-logs, sql_query on dev-db");

    JsonNode json = TraceSummarizer.toJson(s);
    assertThat(json.path("waitedForPeopleMs").asLong()).isEqualTo(47_000);
    assertThat(json.path("phases").size()).isEqualTo(4);
    assertThat(json.at("/decisions/approved").asInt()).isEqualTo(1);
    assertThat(json.at("/phases/1/summary").asString()).startsWith("sql_query twice");
  }

  @Test
  void investigateFailedOrderSyncOnProd() {
    Trace t = new Trace("incident-prod", "claude-desktop", "claude-ai", "Investigate failed order sync on prod");
    // Explore · 12 calls · 14:05–14:11 · 12 allowed
    String[] times = {"14:05:10", "14:05:40", "14:06:15", "14:06:50", "14:07:30", "14:08:05", "14:08:40", "14:09:10",
        "14:09:45", "14:10:20", "14:10:50", "14:11:05"};
    for (int i = 0; i < times.length; i++) {
      if (i % 2 == 0) {
        t.read(times[i], "logs_search", "prod-logs", Env.PROD, "query", "order-sync failed #" + i);
      } else {
        t.read(times[i], "sql_query", "prod-replica", Env.PROD, "sql", "SELECT * FROM sync_failures LIMIT " + i);
      }
    }
    // Clean up · 2 calls · 14:11–14:12 · 2 denied by policy
    t.denied("14:11:40", "sql_execute", "prod-replica", Env.PROD, "TRUNCATE order_sync_queue;", "prod.sql.block_ddl");
    t.denied("14:12:52", "sql_execute", "prod-replica", Env.PROD, "DROP TABLE order_sync_queue_old;",
        "prod.sql.block_ddl");

    TraceSummarizer.Summary s = TraceSummarizer.summarize(TraceSummarizer.steps(t.rows()));
    assertThat(s.title()).isEqualTo("Investigate failed order sync on prod");
    assertThat(s.calls()).isEqualTo(14);
    assertThat(local(s.firstAt(), HMS)).isEqualTo("14:05:10");
    assertThat(local(s.lastAt(), HMS)).isEqualTo("14:12:52");
    assertThat(minutes(s.duration())).isEqualTo(8);
    assertThat(s.decisions()).containsExactly(Map.entry("allowed", 12), Map.entry("denied", 2));
    assertThat(s.waitedForPeopleMs()).isZero();
    assertThat(s.client()).isEqualTo("claude-desktop");
    assertThat(s.endpoint()).isEqualTo("incident-prod");
    assertThat(s.phases()).extracting(p -> p.name() + " · " + p.calls() + " calls · " + local(p.from(), HM) + "–"
        + local(p.to(), HM) + " · " + p.decisions() + " · " + p.summary()).containsExactly(
        "Explore · 12 calls · 14:05–14:11 · {allowed=12} · logs_search on prod-logs, sql_query on prod-replica",
        "Clean up · 2 calls · 14:11–14:12 · {denied=2} · sql_execute with TRUNCATE, then DROP TABLE on prod-replica; "
            + "both stopped by prod.sql.block_ddl");
  }

  @Test
  void aWaitingCallMakesTheTraceWaitAndAChangeInTheMiddleIsNoCleanUp() {
    Trace t = new Trace("dev", "claude-desktop", "claude-ai", null);
    t.read("14:20:12", "k8s_get", "dev-cluster", Env.DEV, "name", "api");
    t.pending("14:21:00", "k8s_rollout_restart", "dev-cluster", Env.DEV, "deployment/api");
    TraceSummarizer.Summary s = TraceSummarizer.summarize(TraceSummarizer.steps(t.rows()));
    assertThat(s.waiting()).isTrue();
    assertThat(s.status(at("16:00:00"))).isEqualTo("waiting");
    assertThat(s.phases()).extracting(TraceSummarizer.Phase::name).containsExactly("Change");
    assertThat(s.phases().getFirst().summary()).isEqualTo("k8s_get once, then k8s_rollout_restart waiting for you");
    // Without a task name, the client's name is the title.
    assertThat(s.title()).isEqualTo("claude-ai");
    assertThat(s.titleSource()).isEqualTo("client");
  }

  @Test
  void theTitleFallsBackToTheFirstNoteAndIsCleanedAndCut() {
    Trace t = new Trace("dev", "codex", "codex-mcp-client", null);
    t.readWithNote("14:00:00", "sql_query", "dev-db", "SELECT 1", "Check‮ the\tqueue " + "x".repeat(200));
    TraceSummarizer.Summary s = TraceSummarizer.summarize(TraceSummarizer.steps(t.rows()));
    assertThat(s.titleSource()).isEqualTo("note");
    assertThat(s.title()).startsWith("Check the queue xxx").hasSizeLessThanOrEqualTo(TraceSummarizer.TITLE_CHARS)
        .doesNotContain("‮").doesNotContain("\t");
  }

  // ── Rows as the audit writes them ───────────────────────────────────────────────────────────────────────────

  static Instant at(String hms) {
    return OffsetDateTime.of(2026, 10, 22, Integer.parseInt(hms.substring(0, 2)), Integer.parseInt(hms.substring(3,
        5)), Integer.parseInt(hms.substring(6, 8)), 0, CEST).toInstant();
  }

  static String local(Instant i, DateTimeFormatter f) {
    return i.truncatedTo(ChronoUnit.SECONDS).atOffset(CEST).format(f);
  }

  static long minutes(Duration d) {
    return Math.round(d.toSeconds() / 60.0);
  }

  /** The rows of one trace: received and completed (and resolved for approvals), as the gateway writes them. */
  static final class Trace {
    private final List<ToolCallEntity> rows = new ArrayList<>();
    private final String endpoint;
    private final String client;
    private final String clientName;
    private final String task;
    private int n;

    Trace(String endpoint, String client, String clientName, String task) {
      this.endpoint = endpoint;
      this.client = client;
      this.clientName = clientName;
      this.task = task;
    }

    List<ToolCallEntity> rows() {
      return rows;
    }

    void read(String hms, String tool, String connection, Env env, String arg, String value) {
      Map<String, Object> args = args("connection", connection, arg, value);
      String id = id();
      rows.add(row(id, CallPhase.RECEIVED, at(hms), tool, connection, env, CallDecision.ALLOWED, args, "read")
          .policy(Classification.Kind.READ, null).build());
      rows.add(row(id, CallPhase.COMPLETED, at(hms).plusMillis(150), tool, connection, env, CallDecision.ALLOWED, args,
          "read").timing(150L, 8L, 142L, null).build());
    }

    void readWithNote(String hms, String tool, String connection, String sql, String note) {
      Map<String, Object> args = args("connection", connection, "sql", sql, "note", note);
      String id = id();
      rows.add(row(id, CallPhase.RECEIVED, at(hms), tool, connection, Env.DEV, CallDecision.ALLOWED, args, "read")
          .build());
      rows.add(row(id, CallPhase.COMPLETED, at(hms).plusMillis(40), tool, connection, Env.DEV, CallDecision.ALLOWED,
          args, "read").build());
    }

    void k8s(String hms, String connection, Env env, String kind, String name) {
      Map<String, Object> args = args("connection", connection, "kind", kind, "name", name, "namespace", "default");
      String id = id();
      rows.add(row(id, CallPhase.RECEIVED, at(hms), "k8s_get", connection, env, CallDecision.ALLOWED, args, "read")
          .build());
      rows.add(row(id, CallPhase.COMPLETED, at(hms).plusMillis(90), "k8s_get", connection, env, CallDecision.ALLOWED,
          args, "read").build());
    }

    void timeout(String hms, String tool, String connection, Env env, String arg, String value) {
      Map<String, Object> args = args("connection", connection, arg, value);
      String id = id();
      rows.add(row(id, CallPhase.RECEIVED, at(hms), tool, connection, env, CallDecision.ALLOWED, args, "read")
          .build());
      rows.add(row(id, CallPhase.COMPLETED, at(hms).plusMillis(2_000), tool, connection, env, CallDecision.ERROR, args,
          "read").error("TIMEOUT", connection + " did not answer within 2.0 s", null).build());
    }

    void approved(String hms, String tool, String connection, Env env, String sql, long waitMs) {
      Map<String, Object> args = args("connection", connection, "sql", sql);
      String id = id();
      rows.add(row(id, CallPhase.RECEIVED, at(hms), tool, connection, env, CallDecision.PENDING, args, "write")
          .policy(Classification.Kind.MUTATION, "dev.sql.mutations")
          .approval("ap_7c41e2d0", ApprovalDeliveryMode.HANDLE, null, null, null, null).build());
      Instant decided = at(hms).plusMillis(waitMs);
      rows.add(row(id, CallPhase.RESOLVED, decided, tool, connection, env, CallDecision.APPROVED, args, "write")
          .approval("ap_7c41e2d0", null, "app", null, "a".repeat(64), null).timing(null, null, null, waitMs).build());
      rows.add(row(id, CallPhase.COMPLETED, decided.plusMillis(46), tool, connection, env, CallDecision.APPROVED, args,
          "write").approval("ap_7c41e2d0", null, "app", null, "a".repeat(64), "a".repeat(64))
          .timing(waitMs + 46, 8L, 38L, waitMs).build());
    }

    void pending(String hms, String tool, String connection, Env env, String target) {
      Map<String, Object> args = args("connection", connection, "target", target);
      rows.add(row(id(), CallPhase.RECEIVED, at(hms), tool, connection, env, CallDecision.PENDING, args, "write")
          .policy(Classification.Kind.MUTATION, null).approval("ap_x", ApprovalDeliveryMode.HANDLE, null, null, null,
              null).build());
    }

    void denied(String hms, String tool, String connection, Env env, String sql, String rule) {
      Map<String, Object> args = args("connection", connection, "sql", sql);
      rows.add(row(id(), CallPhase.RECEIVED, at(hms), tool, connection, env, CallDecision.DENIED, args, "write")
          .policy(Classification.Kind.DENY, rule).build());
    }

    private String id() {
      return "tc_" + (++n);
    }

    private ToolCallEntity.Builder row(String callId, CallPhase phase, Instant at, String tool, String connection,
        Env env, CallDecision decision, Map<String, Object> args, String nature) {
      Map<String, Object> facets = new LinkedHashMap<>();
      facets.put("nature", nature);
      if (task != null) {
        facets.put("task", task);
      }
      return ToolCallEntity.builder(callId, phase, at, endpoint, tool, decision)
          .tool("fake/" + tool, connection, env)
          .client(client, Map.of("name", clientName, "version", "1"), "2026-07-28")
          .arguments(args, sha256(args.toString()))
          .facets(facets)
          .chain("0".repeat(64), "0".repeat(64));
    }

    private static Map<String, Object> args(String... kv) {
      Map<String, Object> m = new LinkedHashMap<>();
      for (int i = 0; i < kv.length; i += 2) {
        m.put(kv[i], kv[i + 1]);
      }
      return m;
    }

    private static String sha256(String s) {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(
            StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
