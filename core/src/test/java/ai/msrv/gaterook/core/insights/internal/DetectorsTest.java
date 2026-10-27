package ai.msrv.gaterook.core.insights.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.audit.AuditQueries.CallFacts;
import ai.msrv.gaterook.core.insights.Insights;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Unusual activity detectors on hand-made calls: each finds its case with the kit's facts and keeps quiet just
 * below its threshold.
 */
class DetectorsTest {

  static final Instant NOW = Instant.parse("2026-10-22T12:30:00Z");
  static final Instant FROM = NOW.minus(Duration.ofDays(1));
  static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static long ids = 1;

  @Test
  void ddlDeniedOnProdInOneTaskIsReportedWithItsStatementsInOrder() {
    List<CallFacts> range = new ArrayList<>();
    range.add(call("sql_query", "prod-replica", "prod", "allowed", null, "b81e44", NOW.minusSeconds(1500), null));
    range.add(call("sql_execute", "prod-replica", "prod", "denied", "prod.sql.block_ddl", "b81e44",
        NOW.minusSeconds(1082), "TRUNCATE order_sync_queue"));
    range.add(call("sql_execute", "prod-replica", "prod", "denied", "prod.sql.block_ddl", "b81e44",
        NOW.minusSeconds(1028), "drop table order_sync_queue_old;"));
    // Not in a task, and DDL denied on dev: not this detector's.
    range.add(call("sql_execute", "prod-replica", "prod", "denied", "prod.sql.block_ddl", "lonely",
        NOW.minusSeconds(900), "DROP TABLE x"));
    range.add(call("sql_execute", "dev-db", "dev", "denied", "dev.sql.block_ddl", "b81e44", NOW.minusSeconds(800),
        "DROP TABLE y"));

    List<Insights.Unusual> found = Detectors.find(range, List.of(), FROM, NOW, true, tasks("b81e44",
        "Investigate failed order sync on prod"), BERLIN);

    assertThat(found).singleElement().satisfies(u -> {
      assertThat(u.kind()).isEqualTo("prod-ddl");
      assertThat(u.traceId()).isEqualTo("b81e44");
      assertThat(u.traceTitle()).isEqualTo("Investigate failed order sync on prod");
      assertThat(u.statements()).containsExactly("TRUNCATE", "DROP TABLE");
      assertThat(u.withinS()).isEqualTo(54);
      assertThat(u.calls()).isEqualTo(2);
      assertThat(u.denied()).isEqualTo(2);
      assertThat(u.client()).isEqualTo("claude-desktop");
      assertThat(u.connection()).isEqualTo("prod-replica");
      assertThat(u.text()).isEqualTo("claude-desktop sent TRUNCATE and then DROP TABLE to prod-replica (prod) within "
          + "54 s in one task; 2 of 2 denied.");
    });
  }

  @Test
  void aToolOnAConnectionAtFourTimesItsDailyAverageIsASpike() {
    List<CallFacts> range = new ArrayList<>();
    // 65 calls, 60 of them from 09:00 to 12:00 local (07:00–10:00 UTC).
    for (int i = 0; i < 60; i++) {
      range.add(call("sql_query", "prod-replica", "prod", "allowed", null, null,
          Instant.parse("2026-10-22T07:00:00Z").plusSeconds(i * 179L), null));
    }
    for (int i = 0; i < 5; i++) {
      range.add(call("sql_query", "prod-replica", "prod", "allowed", null, null, NOW.minusSeconds(1500 - i * 60L),
          null));
    }
    List<CallFacts> baseline = new ArrayList<>();
    for (int i = 0; i < 112; i++) {
      baseline.add(call("sql_query", "prod-replica", "prod", "allowed", null, null,
          FROM.minus(Duration.ofHours(1 + i)), null));
    }

    List<Insights.Unusual> found = Detectors.find(range, baseline, FROM, NOW, true, Map.of(), BERLIN);

    assertThat(found).singleElement().satisfies(u -> {
      assertThat(u.kind()).isEqualTo("tool-spike");
      assertThat(u.calls()).isEqualTo(65);
      assertThat(u.dailyAverage()).isEqualTo(16.0);
      assertThat(u.factor()).isEqualTo(4.06);
      assertThat(u.from()).isEqualTo(Instant.parse("2026-10-22T07:00:00Z"));
      assertThat(u.to()).isEqualTo(Instant.parse("2026-10-22T10:00:00Z"));
      assertThat(u.client()).isEqualTo("claude-desktop");
      assertThat(u.endpoint()).isEqualTo("incident-prod");
    });
    // 63 against 16 a day is 3.9×: quiet; so is any spike when the audit doesn't reach back a week.
    assertThat(Detectors.find(range.subList(2, 65), baseline, FROM, NOW, true, Map.of(), BERLIN)).isEmpty();
    assertThat(Detectors.find(range, baseline, FROM, NOW, false, Map.of(), BERLIN)).isEmpty();
    // Fewer than 20 calls is never a spike.
    assertThat(Detectors.find(range.subList(0, 19), baseline.subList(0, 7), FROM, NOW, true, Map.of(), BERLIN))
        .isEmpty();
  }

  @Test
  void aConnectionTenTimesSlowerThanItsWeekIsReportedWithSinceAndTheTimeout() {
    List<CallFacts> range = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      range.add(timed("allowed", 40, NOW.minus(Duration.ofHours(5)).plusSeconds(i * 60L)));
    }
    long[] slow = {1100, 1200, 1250, 1300, 1350, 1400, 1450, 1500, 1550, 1600, 1650, 1700, 1750, 1800, 1800};
    for (int i = 0; i < slow.length; i++) {
      range.add(timed("allowed", slow[i], NOW.minus(Duration.ofMinutes(59)).plusSeconds(i * 200L)));
    }
    for (int i = 0; i < 5; i++) {
      CallFacts t = timed("error", 2000, NOW.minus(Duration.ofMinutes(30)).plusSeconds(i));
      range.add(new CallFacts(t.id(), t.callId(), t.at(), t.decision(), t.tool(), t.connection(), t.env(), t.client(),
          t.endpoint(), null, null, null, null, 2010L, 10L, 2000L, null, "TIMEOUT", "ETIMEDOUT after 2.0 s", null));
    }
    List<CallFacts> week = new ArrayList<>();
    for (int i = 0; i < 70; i++) {
      week.add(timed("allowed", 36 + i % 9, FROM.minus(Duration.ofHours(1 + i))));
    }

    List<Insights.Unusual> found = Detectors.find(range, week, FROM, NOW, true, Map.of(), BERLIN);

    assertThat(found).singleElement().satisfies(u -> {
      assertThat(u.kind()).isEqualTo("slow-connection");
      assertThat(u.connection()).isEqualTo("cache-redis");
      assertThat(u.p95Ms()).isEqualTo(1800);
      assertThat(u.usualMs()).isEqualTo(40);
      assertThat(u.factor()).isEqualTo(45.0);
      assertThat(u.since()).isEqualTo(NOW.minus(Duration.ofMinutes(59)));
      assertThat(u.timeoutMs()).isEqualTo(2000);
      assertThat(u.calls()).isEqualTo(31);
    });
    // Without a week to compare with (fewer than five calls before), no slow connection.
    assertThat(Detectors.find(range, week.subList(0, 4), FROM, NOW, true, Map.of(), BERLIN))
        .extracting(Insights.Unusual::kind).doesNotContain("slow-connection");
  }

  @Test
  void statementsAndLiteralsNameWhatWasStopped() {
    assertThat(Literals.statement("  /* agent */ truncate table x")).isEqualTo("TRUNCATE TABLE");
    assertThat(Literals.statement("CREATE INDEX i ON t(c)")).isEqualTo("CREATE INDEX");
    assertThat(Literals.statement("DELETE FROM sessions")).isEqualTo("DELETE");
    assertThat(Literals.of("mode.off", "ssh_exec", Map.of("command", "rm -rf /"))).isEqualTo("ssh_exec");
    assertThat(Literals.of("stage.http.block_private_ranges", "http_request", Map.of("method", "get", "url",
        "http://169.254.169.254/latest/meta-data/"))).isEqualTo("GET 169.254.169.254");
    assertThat(Literals.of("dev.kubernetes.namespace_allowlist", "k8s_get", Map.of("kind", "secrets", "namespace",
        "kube-system"))).isEqualTo("kube-system");
    assertThat(Literals.of("x", "tool", null)).isEqualTo("tool");
  }

  @Test
  void aStatementBehindAnyNumberOfCommentsIsNamedAndInsightsStillAnswer() {
    // The agent writes the SQL, comments included: 400 KB of them is far below the 1 MiB a call may carry.
    String sql = "/**/ ".repeat(80_000) + "DROP TABLE orders_archive";
    assertThat(Literals.statement(sql)).isEqualTo("DROP TABLE");
    CallFacts denied = call("sql_execute", "prod-replica", "prod", "denied", "prod.sql.block_ddl", "b81e44",
        NOW.minusSeconds(60), sql);
    assertThat(Aggregates.deniedByRule(List.of(denied))).singleElement()
        .satisfies(r -> assertThat(r.example().literal()).isEqualTo("DROP TABLE"));
    assertThat(Detectors.find(List.of(denied), List.of(), FROM, NOW, true, tasks("b81e44", "Clean up"), BERLIN))
        .singleElement().satisfies(u -> assertThat(u.statements()).containsExactly("DROP TABLE"));
    assertThat(Literals.statement("/* never closed DROP TABLE x")).isNull();
    assertThat(Literals.statement("/* a */ 1")).isNull();
    assertThat(Literals.statement("\t/*a*/\n/* b */drop\r\nview v")).isEqualTo("DROP VIEW");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  static CallFacts call(String tool, String connection, String env, String decision, String rule, String trace,
      Instant at, String sql) {
    long id = ids++;
    String endpoint = env.equals("prod") ? "incident-prod" : "dev";
    return new CallFacts(id, "tc_" + id, at, decision, tool, connection, env, "claude-desktop", endpoint, rule, null,
        null, trace, null, null, null, null, null, null, sql == null ? null : Map.of("sql", sql));
  }

  private static CallFacts timed(String decision, long resourceMs, Instant at) {
    long id = ids++;
    return new CallFacts(id, "tc_" + id, at, decision, "redis_get", "cache-redis", "dev", "codex", "dev", null, null,
        null, null, resourceMs + 9, 9L, resourceMs, null, null, null, null);
  }

  static Map<String, AuditQueries.Trace> tasks(String id, String title) {
    return Map.of(id, new AuditQueries.Trace(id, title, "meta", NOW.minusSeconds(1500), NOW.minusSeconds(1028),
        "claude-desktop", "incident-prod", 3, Map.of()));
  }
}
