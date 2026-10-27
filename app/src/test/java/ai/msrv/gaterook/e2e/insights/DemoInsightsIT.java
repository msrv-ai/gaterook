package ai.msrv.gaterook.e2e.insights;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.audit.AuditExports;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.insights.Insights;
import ai.msrv.gaterook.core.insights.InsightsService;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Insights on the demo world (ARCHITECTURE.md §10 K5 "demo Insights totals match BLUEPRINT section 9 (three findings
 * Unusual activity)"): the last 24 hours of {@code GW_DEMO=1} counted by {@code GET /api/insights}'s service equal the
 * kit's numbers — calls by decision, tool, environment, endpoint, connection and client, the gateway's p50 and p95,
 * the median wait for a person, the agents' tasks — and the detectors find exactly the kit's three: DDL on prod in
 * task b81e44, {@code sql_query} on {@code prod-replica} at 4× its 7-day average, {@code cache-redis} 45× slower than
 * usual. The 24 hours before are the kit's "previous".
 */
class DemoInsightsIT {

  private static final String LAUNCH_SECRET = Base64.getUrlEncoder().withoutPadding()
      .encodeToString("demo-insights-it-launch-secret-0".getBytes(StandardCharsets.US_ASCII));

  private static ConfigurableApplicationContext core;
  private static Instant seededBy;

  @BeforeAll
  static void seedTheDemoWorld() {
    core = new SpringApplicationBuilder(GatewayApplication.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + newHome(), "--gateway.dev=true", "--gateway.dev.launch-secret=" + LAUNCH_SECRET,
            "--server.port=0", "--gateway.connections.health-interval=0s", "--gateway.demo=true");
    seededBy = Instant.now();
    // Traces are summarized in the background after the rows commit: the day's tasks show once they are.
    InsightsService insights = core.getBean(InsightsService.class);
    await(() -> insights.compute(null, null, null).kpis().current().tasks() == 61, "the day's 61 agent tasks");
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  private static Insights day() {
    Instant now = Instant.now();
    return core.getBean(InsightsService.class).compute(null, now.minus(Duration.ofDays(1)), now);
  }

  @Test
  void theLast24HoursAreBlueprintNine() {
    Insights i = day();
    Insights.Totals now = i.kpis().current();
    assertThat(now.calls()).as("1,284 decided").isEqualTo(1_284);
    assertThat(now.pending()).as("3 waiting").isEqualTo(3);
    assertThat(now.approved()).isEqualTo(46);
    assertThat(now.denied()).isEqualTo(17);
    assertThat(now.errors()).isEqualTo(8);
    assertThat(now.tasks()).as("61 agent tasks").isEqualTo(61);
    assertThat(now.gatewayP50Ms()).isEqualTo(9);
    assertThat(now.gatewayP95Ms()).as("p95 of the gateway's overhead").isEqualTo(31);
    assertThat(i.unfilteredCalls()).isEqualTo(1_284);
    assertThat(i.decisions()).containsExactlyInAnyOrderEntriesOf(Map.of("allowed", 1_201L, "approved", 46L,
        "rejected", 9L, "denied", 17L, "expired", 3L, "error", 8L));
    assertThat(i.firstCallAt()).isBefore(seededBy.minus(Duration.ofDays(8)));

    assertThat(i.topTools()).extracting(t -> t.name() + " " + t.count()).containsExactly("sql_query 512",
        "logs_search 341", "k8s_get 150", "ssh_run 98", "http_request 61", "sql_execute 47", "redis_get 31",
        "mongo_find 23", "k8s_apply 9", "k8s_rollout_restart 6", "ssh_exec 6");
    assertThat(i.byClient()).extracting(c -> c.client() + " " + c.count()).containsExactly("codex 772",
        "claude-desktop 493", "claude-ai 19");
    Insights.ClientCalls codex = i.byClient().getFirst();
    assertThat(codex.decisions()).containsExactlyInAnyOrderEntriesOf(Map.of("allowed", 720L, "approved", 30L,
        "denied", 10L, "rejected", 6L, "error", 4L, "expired", 2L));
    assertThat(i.byClient().get(2).endpoints()).containsExactly("stage-readonly");
    long buckets = i.callsOverTime().buckets().stream().mapToLong(AuditQueries.Bucket::total).sum();
    assertThat(buckets).as("calls over time, waiting ones included").isEqualTo(1_287);

    // BLUEPRINT §9 counts the waiting calls too: Activity's facets over the same range.
    Map<String, List<AuditQueries.FacetValue>> facets = core.getBean(AuditQueries.class).facets(null,
        i.from(), i.to());
    assertThat(counts(facets.get("tool"))).containsExactlyEntriesOf(ordered("sql_query", 512, "logs_search", 341,
        "k8s_get", 150, "ssh_run", 98, "http_request", 62, "sql_execute", 48, "redis_get", 31, "mongo_find", 23,
        "k8s_apply", 9, "k8s_rollout_restart", 7, "ssh_exec", 6));
    assertThat(counts(facets.get("env"))).containsExactlyEntriesOf(ordered("dev", 1_095, "prod", 111, "stage", 81));
    assertThat(counts(facets.get("endpoint"))).containsExactlyEntriesOf(ordered("dev", 1_157, "incident-prod", 111,
        "stage-readonly", 19));
    assertThat(counts(facets.get("connection"))).containsExactlyEntriesOf(ordered("dev-db", 472, "app-logs", 300,
        "dev-cluster", 166, "dev-vm", 103, "prod-replica", 69, "billing-api", 62, "prod-logs", 41, "cache-redis", 31,
        "orders-mongo", 23, "stage-db", 19, "prod-vm", 1));
    assertThat(counts(facets.get("client"))).containsExactlyEntriesOf(ordered("codex", 774, "claude-desktop", 494,
        "claude-ai", 19));
  }

  @Test
  void approvalsDenialsLatencyAndErrorsAreTheKits() {
    Insights i = day();
    Insights.Approvals a = i.approvals();
    assertThat(List.of(a.approved(), a.rejected(), a.expired())).containsExactly(46L, 9L, 3L);
    assertThat(a.medianWaitS()).as("median wait for a person").isEqualTo(47);
    assertThat(a.p90WaitS()).isEqualTo(100);
    assertThat(a.waitHistogram()).extracting(Insights.WaitBucket::count).containsExactly(38L, 14L, 3L, 0L);
    // The kit's slowest decisions, then the next one (a request the history drew the 104 s wait for).
    assertThat(a.slowest()).hasSize(5);
    assertThat(a.slowest().subList(0, 4)).extracting(s -> s.tool() + " " + s.connection() + " " + s.status() + " "
        + s.waitS()).containsExactly("k8s_rollout_restart dev-cluster approved 185",
        "sql_execute dev-db approved 151", "http_request billing-api rejected 132", "ssh_run dev-vm approved 108");
    assertThat(a.slowest().get(4).waitS()).isEqualTo(104);
    // Requested at 09:47:12 of the kit, 4 h 42 min 48 s before its 14:30.
    assertThat(a.slowest().getFirst().requestedAt()).isBetween(seededBy.minusSeconds(16_968 + 120),
        seededBy.minusSeconds(16_968 - 5));
    assertThat(a.slowest()).allSatisfy(s -> {
      assertThat(s.eventId()).isNotNull();
      assertThat(s.requestedAt()).isBefore(seededBy);
    });

    assertThat(i.deniedByRule()).extracting(d -> d.rule() + " " + d.count()).containsExactly("mode.off 6",
        "prod.sql.block_ddl 4", "dev.sql.read_only_tool 3", "dev.kubernetes.namespace_allowlist 2",
        "stage.http.block_private_ranges 2");
    assertThat(i.deniedByRule()).extracting(d -> d.example().literal()).containsExactly("ssh_exec", "DROP TABLE",
        "DELETE", "kube-system", "GET 169.254.169.254");

    assertThat(i.errorsByConnection()).extracting(e -> e.connection() + " " + e.count() + " " + e.kind() + " "
        + e.message()).containsExactly("cache-redis 5 TIMEOUT ETIMEDOUT after 2.0 s",
        "billing-api 3 RESOURCE_UNAVAILABLE ECONNREFUSED billing.stage.acme.dev:443");
    Insights.ConnectionLatency redis = i.latency().byConnection().getFirst();
    assertThat(redis.connection()).as("slowest first").isEqualTo("cache-redis");
    assertThat(redis.resourceP95()).isEqualTo(1_800);
    assertThat(redis.resourceP50()).isEqualTo(1_200);
  }

  @Test
  void theDetectorsFindExactlyTheKitsThree() {
    List<Insights.Unusual> unusual = day().unusual();
    assertThat(unusual).extracting(Insights.Unusual::kind).containsExactly("prod-ddl", "tool-spike",
        "slow-connection");

    Insights.Unusual ddl = unusual.get(0);
    assertThat(ddl.traceId()).isEqualTo("b81e44d0920a4203e4b733f52954db89");
    assertThat(ddl.traceTitle()).isEqualTo("Investigate failed order sync on prod");
    assertThat(ddl.client()).isEqualTo("claude-desktop");
    assertThat(ddl.connection()).isEqualTo("prod-replica");
    assertThat(ddl.env()).isEqualTo("prod");
    assertThat(ddl.statements()).containsExactly("TRUNCATE", "DROP TABLE");
    assertThat(ddl.withinS()).as("14:11:58 to 14:12:52").isEqualTo(54);
    assertThat(List.of(ddl.calls(), ddl.denied())).containsExactly(2L, 2L);

    Insights.Unusual spike = unusual.get(1);
    assertThat(List.of(spike.tool(), spike.connection(), spike.env())).containsExactly("sql_query", "prod-replica",
        "prod");
    assertThat(spike.calls()).isEqualTo(65);
    assertThat(spike.dailyAverage()).isEqualTo(16.0);
    assertThat(spike.factor()).as("65 against 16 a day").isEqualTo(4.06);
    assertThat(spike.client()).isEqualTo("claude-desktop");
    assertThat(spike.endpoint()).isEqualTo("incident-prod");
    assertThat(Duration.between(spike.from(), spike.to())).isLessThanOrEqualTo(Duration.ofHours(3));

    Insights.Unusual slow = unusual.get(2);
    assertThat(slow.connection()).isEqualTo("cache-redis");
    assertThat(slow.env()).isEqualTo("dev");
    assertThat(slow.p95Ms()).isEqualTo(1_800);
    assertThat(slow.usualMs()).isEqualTo(40);
    assertThat(slow.factor()).isEqualTo(45.0);
    assertThat(slow.timeoutMs()).isEqualTo(2_000);
    assertThat(slow.calls()).isEqualTo(31);
    assertThat(slow.since()).as("an hour before the seeding").isBetween(seededBy.minus(Duration.ofMinutes(75)),
        seededBy.minus(Duration.ofMinutes(55)));
  }

  @Test
  void theDayBeforeIsTheKitsPrevious() {
    Insights.Totals before = day().kpis().previous();
    assertThat(before).isNotNull();
    assertThat(before.calls()).isEqualTo(1_146);
    assertThat(before.approved()).isEqualTo(40);
    assertThat(before.denied()).isEqualTo(12);
    assertThat(before.errors()).isEqualTo(11);
    assertThat(before.tasks()).isEqualTo(54);
    assertThat(before.gatewayP50Ms()).isEqualTo(10);
    assertThat(before.gatewayP95Ms()).isEqualTo(35);
  }

  @Test
  void theKitsExportDestinationsAreShownAndNeverContacted() {
    List<AuditExports.Destination> destinations = core.getBean(AuditExports.class).list();
    assertThat(destinations).extracting(d -> d.kind() + " " + d.target() + " " + d.schedule() + " " + d.state())
        .containsExactlyInAnyOrder("siem siem.acme.internal:6514 stream ok",
            "s3 s3://acme-audit/gaterook/ daily@02:00 ok", "bigquery acme-prod.audit.mcp_calls hourly failing");
    assertThat(destinations).allSatisfy(d -> {
      assertThat(d.demo()).isTrue();
      assertThat(d.verified()).isTrue();
      assertThat(d.config().has("demo")).isFalse();
    });
    assertThat(destinations.stream().filter(d -> d.kind().equals("bigquery")).findFirst().orElseThrow().lastError())
        .startsWith("403 Forbidden: Access Denied: Table acme-prod:audit.mcp_calls");
    assertThat(core.getBean(IntegrityAlerts.class).current()).as("the seeded history verifies").isEmpty();
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static Map<String, Long> counts(List<AuditQueries.FacetValue> values) {
    Map<String, Long> out = new LinkedHashMap<>();
    values.forEach(v -> out.put(v.name(), v.count()));
    return out;
  }

  private static Map<String, Long> ordered(Object... pairs) {
    Map<String, Long> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      out.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
    }
    return out;
  }

  private static void await(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private static String newHome() {
    try {
      Path parent = Path.of("target", "gateway-homes").toAbsolutePath();
      Files.createDirectories(parent);
      return Files.createTempDirectory(parent, "demo-insights-").toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
