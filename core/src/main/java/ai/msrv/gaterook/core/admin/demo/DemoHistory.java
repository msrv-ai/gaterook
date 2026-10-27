package ai.msrv.gaterook.core.admin.demo;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.core.audit.AuditBackfill;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The past of the demo world's audit (design/BLUEPRINT.md §9 "24-hour totals", design/pages/insights.html): the day the
 * kit shows and the eight before it, so that Activity, Overview and Insights have the kit's numbers and the Unusual
 * activity detectors have their 7 days to compare with.
 *
 * <p>The last 24 hours, with the rows {@link DemoSeed} writes at the seeding itself (three waiting requests, a
 * rejected {@code k8s_apply}, five calls), come to the kit's 1,287 calls: 1,201 allowed, 46 approved, 9 rejected, 17
 * denied by policy, 3 expired, 8 errors and 3 waiting; per tool, environment, endpoint, connection and client as
 * BLUEPRINT §9 lists them; the gateway's p50 9 ms and p95 31 ms; a median wait for a person of 47 s; 61 agent tasks.
 * In them the kit's stories: task {@code 4f2a9c} "Fix failing order sync" (23 calls in 18 minutes), task {@code b81e44}
 * "Investigate failed order sync on prod" ending in {@code TRUNCATE} and {@code DROP TABLE} denied on
 * {@code prod-replica}, {@code sql_query} on {@code prod-replica} at 65 calls against 16 a day, {@code cache-redis}
 * slow (1.8 s at p95, 40 ms usually) since an hour before the seeding, claude.ai's 19 calls while the tunnel was on.
 * The 24 hours before: the kit's "previous" numbers (1,146 calls, 40 approved, 12 denied, 11 errors, 54 tasks, p50
 * 10 ms, p95 35 ms). The older days are lighter versions of the same work.
 *
 * <p>Times are relative to the seeding, hour by hour as the kit's chart, and stay half an hour clear of the start of
 * each day: Insights read within that half hour sees exactly these numbers. Deterministic for a given seed.
 */
final class DemoHistory {

  static final String TASK_FIX = "4f2a9c71e8d04b5a9c3e2b1f0a6d8e81";
  static final String TASK_PROD = "b81e44d0920a4203e4b733f52954db89";

  private static final Duration HOUR = Duration.ofHours(1);
  private static final Duration DAY = Duration.ofDays(1);
  /** Calls stay this far from the start of their day (Insights read a little later still sees all of them). */
  private static final Duration MARGIN = Duration.ofMinutes(30);
  private static final String PROTOCOL = "2025-11-25";

  /** Calls per hour of the kit's chart, 15:00 yesterday to 14:00 today, waiting ones included. */
  private static final int[] KIT_HOURS = {96, 116, 104, 68, 38, 20, 14, 10, 7, 3, 1, 0, 1, 0, 3, 9, 24, 56, 104, 150,
      166, 90, 121, 86};
  /**
   * Denied, rejected and failed calls per hour of the generated day: the kit's chart, with the last hour's moved to
   * the stories that happen in it (and the rows of the seeding itself, which are three of them).
   */
  private static final int[] DANGER_HOURS = {1, 4, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 4, 6, 4, 0, 2, 6};
  private static final int[] EXPIRED_HOURS = {0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0};

  /** How long a person took on the generated requests of the day, seconds (with the seeding's own 0 s: 55 waits). */
  private static final long[] WAITS = {8, 11, 14, 16, 18, 19, 21, 22, 24, 25, 27, 28, 29, 31, 32, 33, 35, 36, 38,
      39, 40, 41, 42, 43, 44, 46, 47, 48, 50, 51, 52, 53, 55, 56, 57, 58, 60, 62, 65, 68, 71, 75, 79, 83, 88, 92, 96,
      97, 100, 104, 108, 132, 151, 185};

  /** Resource time per connection, p50 and p95 in ms (the kit's Latency by connection). */
  private static final Map<String, long[]> RESOURCE = Map.ofEntries(Map.entry("dev-db", new long[] {12, 41}),
      Map.entry("prod-replica", new long[] {28, 62}), Map.entry("stage-db", new long[] {22, 58}),
      Map.entry("app-logs", new long[] {120, 240}), Map.entry("prod-logs", new long[] {640, 1100}),
      Map.entry("dev-cluster", new long[] {41, 96}), Map.entry("dev-vm", new long[] {380, 820}),
      Map.entry("prod-vm", new long[] {650, 900}), Map.entry("billing-api", new long[] {210, 480}),
      Map.entry("cache-redis", new long[] {40, 44}), Map.entry("orders-mongo", new long[] {9, 27}));

  /** The clients' callers as the seeding resolved them, and how the pairs of the endpoints are found. */
  record World(Caller codex, Caller claudeDesktop, Caller claudeAi, Instant tunnelFrom, Instant tunnelTo,
      PairLookup pairs) {

    Caller caller(String client) {
      return switch (client) {
        case "codex" -> codex;
        case "claude-desktop" -> claudeDesktop;
        default -> claudeAi;
      };
    }
  }

  @FunctionalInterface
  interface PairLookup {
    EndpointSnapshot.Pair pair(String endpoint, String tool, String connection);
  }

  /** One call to write: where, who, how it ended, when, its timings and its task. */
  private static final class Plan {
    final String endpoint;
    final String connection;
    final String tool;
    final String client;
    final String decision;
    final String rule;
    Instant at;
    long waitS;
    String via = "app";
    boolean commented;
    long gatewayMs;
    long resourceMs;
    String errorKind;
    String errorMessage;
    ObjectNode args;
    String traceId;
    String task;
    boolean noTask;
    boolean placed;

    Plan(String endpoint, String connection, String tool, String client, String decision, String rule) {
      this.endpoint = endpoint;
      this.connection = connection;
      this.tool = tool;
      this.client = client;
      this.decision = decision;
      this.rule = rule;
    }

    String category() {
      return switch (decision) {
        case "denied", "rejected", "error" -> "danger";
        case "expired" -> "expired";
        default -> "ok";
      };
    }

    boolean approval() {
      return decision.equals("approved") || decision.equals("rejected") || decision.equals("expired");
    }

    /** It ran: a completed row with timings. */
    boolean ran() {
      return decision.equals("allowed") || decision.equals("approved") || decision.equals("error");
    }

    /** When its last row is written. */
    Instant end() {
      return at.plusSeconds(approval() ? waitS : 0).plusMillis(ran() ? gatewayMs + resourceMs : 0);
    }
  }

  private final Random random;
  private final Instant now;
  private final World world;

  DemoHistory(long seed, Instant now, World world) {
    this.random = new Random(seed);
    this.now = now;
    this.world = world;
  }

  /** Every past call, oldest day first. */
  List<AuditBackfill.PastCall> calls(int days) {
    List<Plan> all = new ArrayList<>();
    for (int day = days; day >= 2; day--) {
      all.addAll(olderDay(day));
    }
    all.addAll(yesterday());
    all.addAll(today());
    List<AuditBackfill.PastCall> out = new ArrayList<>(all.size());
    all.stream().sorted(Comparator.comparing((Plan p) -> p.at)).forEach(p -> out.add(past(p)));
    return out;
  }

  // ── The day the kit shows ───────────────────────────────────────────────────────────────────────────────────

  private List<Plan> today() {
    Instant start = now.minus(DAY);
    List<Plan> pool = new ArrayList<>();
    // The kit's calls of the last 24 hours (BLUEPRINT §9) less the rows the seeding writes itself (a sql_query,
    // ssh_run, logs_search and redis_get of the moment, the denied DROP TABLE, the rejected k8s_apply, three waiting).
    add(pool, 274, "dev", "dev-db", "sql_query", "codex", "allowed", null);
    add(pool, 150, "dev", "dev-db", "sql_query", "claude-desktop", "allowed", null);
    add(pool, 3, "dev", "dev-db", "sql_query", "codex", "denied", "dev.sql.read_only_tool");
    add(pool, 17, "dev", "dev-db", "sql_execute", "codex", "approved", "dev.sql.write-approval");
    add(pool, 2, "dev", "dev-db", "sql_execute", "codex", "rejected", "dev.sql.write-approval");
    add(pool, 1, "dev", "dev-db", "sql_execute", "codex", "expired", "dev.sql.write-approval");
    add(pool, 23, "dev", "dev-db", "sql_execute", "codex", "allowed", null);
    add(pool, 65, "incident-prod", "prod-replica", "sql_query", "claude-desktop", "allowed", null);
    add(pool, 3, "incident-prod", "prod-replica", "sql_execute", "claude-desktop", "denied", "prod.sql.block_ddl");
    add(pool, 19, "stage-readonly", "stage-db", "sql_query", "claude-ai", "allowed", null);
    add(pool, 259, "dev", "app-logs", "logs_search", "codex", "allowed", null);
    add(pool, 40, "dev", "app-logs", "logs_search", "claude-desktop", "allowed", null);
    add(pool, 41, "incident-prod", "prod-logs", "logs_search", "claude-desktop", "allowed", null);
    add(pool, 88, "dev", "dev-cluster", "k8s_get", "codex", "allowed", null);
    add(pool, 60, "dev", "dev-cluster", "k8s_get", "claude-desktop", "allowed", null);
    add(pool, 2, "dev", "dev-cluster", "k8s_get", "claude-desktop", "denied", "dev.kubernetes.namespace_allowlist");
    add(pool, 2, "dev", "dev-cluster", "k8s_apply", "claude-desktop", "approved", "dev.kubernetes.write-approval");
    add(pool, 2, "dev", "dev-cluster", "k8s_apply", "claude-desktop", "rejected", "dev.kubernetes.write-approval");
    add(pool, 3, "dev", "dev-cluster", "k8s_apply", "codex", "approved", "dev.kubernetes.write-approval");
    add(pool, 1, "dev", "dev-cluster", "k8s_apply", "codex", "rejected", "dev.kubernetes.write-approval");
    add(pool, 5, "dev", "dev-cluster", "k8s_rollout_restart", "claude-desktop", "approved",
        "dev.kubernetes.write-approval");
    add(pool, 1, "dev", "dev-cluster", "k8s_rollout_restart", "claude-desktop", "expired",
        "dev.kubernetes.write-approval");
    add(pool, 88, "dev", "dev-vm", "ssh_run", "claude-desktop", "allowed", null);
    add(pool, 8, "dev", "dev-vm", "ssh_run", "claude-desktop", "approved", "dev.ssh.write-approval");
    add(pool, 5, "dev", "dev-vm", "ssh_exec", "codex", "denied", "mode.off");
    add(pool, 1, "dev", "dev-vm", "ssh_exec", "claude-desktop", "denied", "mode.off");
    add(pool, 1, "incident-prod", "prod-vm", "ssh_run", "claude-desktop", "approved", "prod.ssh.write-approval");
    add(pool, 10, "dev", "billing-api", "http_request", "codex", "approved", "stage.http.write-approval");
    add(pool, 3, "dev", "billing-api", "http_request", "codex", "rejected", "stage.http.write-approval");
    add(pool, 1, "dev", "billing-api", "http_request", "codex", "expired", "stage.http.write-approval");
    add(pool, 2, "dev", "billing-api", "http_request", "codex", "denied", "stage.http.block_private_ranges");
    add(pool, 2, "dev", "billing-api", "http_request", "codex", "error", null);
    add(pool, 42, "dev", "billing-api", "http_request", "codex", "allowed", null);
    add(pool, 1, "dev", "billing-api", "http_request", "claude-desktop", "error", null);
    add(pool, 9, "dev", "cache-redis", "redis_get", "codex", "allowed", null);
    add(pool, 1, "dev", "cache-redis", "redis_get", "codex", "error", null);
    add(pool, 17, "dev", "cache-redis", "redis_get", "claude-desktop", "allowed", null);
    add(pool, 3, "dev", "cache-redis", "redis_get", "claude-desktop", "error", null);
    add(pool, 23, "dev", "orders-mongo", "mongo_find", "codex", "allowed", null);

    List<Plan> fixed = new ArrayList<>();
    fixed.addAll(fixTask(pool));
    fixed.addAll(prodTask(pool));
    fixed.addAll(spike(pool));
    fixed.addAll(slowCache(pool));
    fixed.addAll(knownApprovals(pool));
    fixed.addAll(claudeAi(pool));
    take(pool, 1, p -> p.rule != null && p.rule.equals("prod.sql.block_ddl")).forEach(p -> {
      // A DDL statement outside any task: denied like the others, but no task to open.
      p.noTask = true;
      p.args = sql("ALTER TABLE orders ADD COLUMN sync_note text");
      pool.add(p);
    });

    int[] totals = KIT_HOURS.clone();
    // The seeding writes nine rows at its own time, in the last hour.
    totals[23] -= 9;
    place(pool, fixed, start, totals, DANGER_HOURS, EXPIRED_HOURS);
    List<Plan> day = new ArrayList<>(pool);
    day.addAll(fixed);
    waits(day, WAITS);
    latencies(day, gateway(1_251, 558, 119, 499, 20, 55, 9, 31));
    // 59 with 4f2a9c and b81e44: DemoSeed's own calls are two tasks more (c7e19d of Codex, 7d19b0 of Claude Desktop).
    tasks(day, Map.of("codex/dev", 33, "claude-desktop/dev", 16, "claude-desktop/incident-prod", 6,
        "claude-ai/stage-readonly", 2));
    finish(day);
    return day;
  }

  /** Task 4f2a9c, "Fix failing order sync": 23 calls of Codex on dev, 13:44:02 to 14:02:10 in the kit. */
  private List<Plan> fixTask(List<Plan> pool) {
    List<Plan> out = new ArrayList<>();
    long[] logsAt = {2758, 2700, 2640, 2330, 1690, 1670};
    long[] sqlAt = {2600, 2560, 2510, 2470, 2290, 2250, 1760, 1730, 1700};
    long[] k8sAt = {2100, 2040, 1980, 1920, 1860, 1800};
    for (Plan p : take(pool, logsAt.length, match("app-logs", "logs_search", "codex", "allowed"))) {
      out.add(at(p, logsAt[out.size()]));
    }
    int i = 0;
    for (Plan p : take(pool, sqlAt.length, match("dev-db", "sql_query", "codex", "allowed"))) {
      out.add(at(p, sqlAt[i++]));
    }
    i = 0;
    for (Plan p : take(pool, k8sAt.length, match("dev-cluster", "k8s_get", "codex", "allowed"))) {
      out.add(at(p, k8sAt[i++]));
    }
    Plan timeout = at(take(pool, 1, match("cache-redis", "redis_get", "codex", "error")).getFirst(), 2575);
    timeout(timeout);
    out.add(timeout);
    Plan update = at(take(pool, 1, match("dev-db", "sql_execute", "codex", "approved")).getFirst(), 2213);
    update.waitS = 47;
    update.args = sql("UPDATE order_sync_state SET cursor = 'ord_48190' WHERE id = 1;");
    out.add(update);
    out.forEach(p -> trace(p, TASK_FIX, "Fix failing order sync"));
    return out;
  }

  /** Task b81e44 on incident-prod: twelve reads, then TRUNCATE and DROP TABLE, both denied, 54 s apart. */
  private List<Plan> prodTask(List<Plan> pool) {
    List<Plan> out = new ArrayList<>();
    long[] logsAt = {1490, 1420, 1330, 1250, 1170, 1115, 1100};
    long[] sqlAt = {1460, 1375, 1290, 1210, 1140};
    int i = 0;
    for (Plan p : take(pool, logsAt.length, match("prod-logs", "logs_search", "claude-desktop", "allowed"))) {
      out.add(at(p, logsAt[i++]));
    }
    i = 0;
    for (Plan p : take(pool, sqlAt.length, match("prod-replica", "sql_query", "claude-desktop", "allowed"))) {
      out.add(at(p, sqlAt[i++]));
    }
    List<Plan> ddl = take(pool, 2, p -> "prod.sql.block_ddl".equals(p.rule));
    ddl.get(0).args = sql("TRUNCATE order_sync_queue");
    out.add(at(ddl.get(0), 1082));
    ddl.get(1).args = sql("DROP TABLE order_sync_queue_old");
    out.add(at(ddl.get(1), 1028));
    out.forEach(p -> trace(p, TASK_PROD, "Investigate failed order sync on prod"));
    return out;
  }

  /** sql_query on prod-replica: the other 60 of its 65 calls from 09:00 to 12:00 of the kit, most in the middle. */
  private List<Plan> spike(List<Plan> pool) {
    List<Plan> out = take(pool, 60, match("prod-replica", "sql_query", "claude-desktop", "allowed"));
    long from = 5 * 3600 + 1800;
    long to = 2 * 3600 + 1800;
    for (int i = 0; i < out.size(); i++) {
      double u = (i + 0.5) / out.size();
      // Denser in the middle hour: a smooth step instead of an even spread.
      double s = u * u * (3 - 2 * u);
      at(out.get(i), (long) (from - s * (from - to)) - random.nextInt(60));
    }
    return out;
  }

  /**
   * cache-redis: eleven fast calls before, then slow from an hour before the seeding — 1.1 to 1.8 s in the resource —
   * and three time-outs (the fourth is task 4f2a9c's, the fifth the seeding's own).
   */
  private List<Plan> slowCache(List<Plan> pool) {
    List<Plan> out = new ArrayList<>();
    long[] slow = {1100, 1200, 1250, 1300, 1350, 1400, 1450, 1500, 1550, 1600, 1650, 1700, 1750, 1800, 1800};
    List<Plan> slowCalls = new ArrayList<>(take(pool, 11, match("cache-redis", "redis_get", "claude-desktop",
        "allowed")));
    slowCalls.addAll(take(pool, 4, match("cache-redis", "redis_get", "codex", "allowed")));
    Collections.shuffle(slowCalls, random);
    for (int i = 0; i < slowCalls.size(); i++) {
      Plan p = at(slowCalls.get(i), 3540 - i * 230L);
      p.resourceMs = slow[i];
      out.add(p);
    }
    long[] errorsAt = {3300, 1600, 600};
    int i = 0;
    for (Plan p : take(pool, 3, match("cache-redis", "redis_get", "claude-desktop", "error"))) {
      timeout(at(p, errorsAt[i++]));
      out.add(p);
    }
    List<Plan> fast = new ArrayList<>(take(pool, 6, match("cache-redis", "redis_get", "claude-desktop", "allowed")));
    fast.addAll(take(pool, 5, match("cache-redis", "redis_get", "codex", "allowed")));
    for (Plan p : fast) {
      at(p, 3600 + 600 + random.nextInt(20 * 3600));
      p.resourceMs = 36 + random.nextInt(9);
      out.add(p);
    }
    return out;
  }

  /**
   * The requests the kit names: the slowest decisions (185, 151, 132 and 108 s), restart-api on prod-vm approved
   * with a comment, deployment/order-sync approved from Slack.
   */
  private List<Plan> knownApprovals(List<Plan> pool) {
    List<Plan> out = new ArrayList<>();
    Plan restart = at(take(pool, 1, match("dev-cluster", "k8s_rollout_restart", "claude-desktop", "approved"))
        .getFirst(), 16968);
    restart.waitS = 185;
    restart.args = rollout("api");
    out.add(restart);
    Plan update = at(take(pool, 1, match("dev-db", "sql_execute", "codex", "approved")).getFirst(), 11397);
    update.waitS = 151;
    out.add(update);
    Plan refund = at(take(pool, 1, match("billing-api", "http_request", "codex", "rejected")).getFirst(), 15800);
    refund.waitS = 132;
    out.add(refund);
    Plan runbook = at(take(pool, 1, match("dev-vm", "ssh_run", "claude-desktop", "approved")).getFirst(), 78531);
    runbook.waitS = 108;
    out.add(runbook);
    Plan prod = at(take(pool, 1, match("prod-vm", "ssh_run", "claude-desktop", "approved")).getFirst(), 17030);
    prod.waitS = 55;
    prod.commented = true;
    prod.args = runbook("restart-api");
    out.add(prod);
    Plan slack = at(take(pool, 1, match("dev-cluster", "k8s_rollout_restart", "claude-desktop", "approved"))
        .getFirst(), 7138);
    slack.waitS = 42;
    slack.via = "slack";
    slack.commented = true;
    slack.args = rollout("order-sync");
    out.add(slack);
    return out;
  }

  /** claude.ai's 19 reads of stage-db through stage-readonly while the tunnel was on. */
  private List<Plan> claudeAi(List<Plan> pool) {
    List<Plan> out = take(pool, 19, p -> p.client.equals("claude-ai"));
    Instant from = world.tunnelFrom().plus(Duration.ofMinutes(1));
    long span = Math.max(60, Duration.between(from, world.tunnelTo().minus(Duration.ofMinutes(2))).toSeconds());
    List<Long> offsets = new ArrayList<>();
    for (int i = 0; i < out.size(); i++) {
      offsets.add((long) random.nextInt((int) span));
    }
    Collections.sort(offsets);
    for (int i = 0; i < out.size(); i++) {
      Plan p = out.get(i);
      p.at = from.plusSeconds(offsets.get(i));
      p.placed = true;
    }
    return out;
  }

  // ── The day before ──────────────────────────────────────────────────────────────────────────────────────────

  /** The 24 hours before: the kit's "previous" numbers. */
  private List<Plan> yesterday() {
    List<Plan> pool = new ArrayList<>(allowed(1_057));
    add(pool, 16, "incident-prod", "prod-replica", "sql_query", "claude-desktop", "allowed", null);
    add(pool, 15, "dev", "dev-db", "sql_execute", "codex", "approved", "dev.sql.write-approval");
    add(pool, 8, "dev", "billing-api", "http_request", "codex", "approved", "stage.http.write-approval");
    add(pool, 5, "dev", "dev-cluster", "k8s_rollout_restart", "claude-desktop", "approved",
        "dev.kubernetes.write-approval");
    add(pool, 2, "dev", "dev-cluster", "k8s_apply", "codex", "approved", "dev.kubernetes.write-approval");
    add(pool, 2, "dev", "dev-cluster", "k8s_apply", "claude-desktop", "approved", "dev.kubernetes.write-approval");
    add(pool, 8, "dev", "dev-vm", "ssh_run", "claude-desktop", "approved", "dev.ssh.write-approval");
    add(pool, 2, "dev", "dev-db", "sql_execute", "codex", "rejected", "dev.sql.write-approval");
    add(pool, 2, "dev", "billing-api", "http_request", "codex", "rejected", "stage.http.write-approval");
    add(pool, 3, "dev", "dev-cluster", "k8s_apply", "claude-desktop", "rejected", "dev.kubernetes.write-approval");
    add(pool, 1, "dev", "dev-cluster", "k8s_apply", "codex", "rejected", "dev.kubernetes.write-approval");
    add(pool, 1, "dev", "dev-db", "sql_execute", "codex", "expired", "dev.sql.write-approval");
    add(pool, 1, "dev", "billing-api", "http_request", "codex", "expired", "stage.http.write-approval");
    add(pool, 4, "dev", "dev-db", "sql_query", "codex", "denied", "dev.sql.read_only_tool");
    add(pool, 3, "dev", "billing-api", "http_request", "codex", "denied", "stage.http.block_private_ranges");
    add(pool, 3, "dev", "dev-cluster", "k8s_get", "claude-desktop", "denied", "dev.kubernetes.namespace_allowlist");
    add(pool, 2, "incident-prod", "prod-replica", "sql_execute", "claude-desktop", "denied", "prod.sql.block_ddl");
    add(pool, 6, "dev", "billing-api", "http_request", "codex", "error", null);
    add(pool, 2, "dev", "app-logs", "logs_search", "codex", "error", null);
    add(pool, 1, "dev", "app-logs", "logs_search", "claude-desktop", "error", null);
    add(pool, 2, "dev", "dev-cluster", "k8s_get", "claude-desktop", "error", null);
    take(pool, 2, p -> "prod.sql.block_ddl".equals(p.rule)).forEach(p -> {
      p.noTask = true;
      p.args = sql("DROP TABLE orders_backup_2026_09");
      pool.add(p);
    });
    Instant start = now.minus(DAY.multipliedBy(2));
    place(pool, List.of(), start, scaled(pool.size()), null, null);
    waits(pool, sample(55));
    latencies(pool, gateway(1_124, 500, 100, 460, 20, 44, 10, 35));
    tasks(pool, Map.of("codex/dev", 31, "claude-desktop/dev", 15, "claude-desktop/incident-prod", 8));
    finish(pool);
    return pool;
  }

  /** An older day: the same work, lighter; sql_query on prod-replica at its usual 16 a day. */
  private List<Plan> olderDay(int day) {
    List<Plan> pool = new ArrayList<>(allowed(335));
    add(pool, 16, "incident-prod", "prod-replica", "sql_query", "claude-desktop", "allowed", null);
    add(pool, 4, "dev", "dev-db", "sql_execute", "codex", "approved", "dev.sql.write-approval");
    add(pool, 2, "dev", "billing-api", "http_request", "codex", "approved", "stage.http.write-approval");
    add(pool, 2, "dev", "dev-cluster", "k8s_rollout_restart", "claude-desktop", "approved",
        "dev.kubernetes.write-approval");
    add(pool, 2, "dev", "dev-vm", "ssh_run", "claude-desktop", "approved", "dev.ssh.write-approval");
    add(pool, 1, "dev", "dev-db", "sql_execute", "codex", "rejected", "dev.sql.write-approval");
    add(pool, 1, "dev", "dev-cluster", "k8s_apply", "claude-desktop", "rejected", "dev.kubernetes.write-approval");
    add(pool, 1, "dev", "billing-api", "http_request", "codex", "expired", "stage.http.write-approval");
    add(pool, 1, "dev", "dev-db", "sql_query", "codex", "denied", "dev.sql.read_only_tool");
    add(pool, 1, "dev", "dev-cluster", "k8s_get", "claude-desktop", "denied", "dev.kubernetes.namespace_allowlist");
    add(pool, 1, "dev", "billing-api", "http_request", "codex", "denied", "stage.http.block_private_ranges");
    add(pool, 1, "dev", "billing-api", "http_request", "codex", "error", null);
    add(pool, 1, "dev", "app-logs", "logs_search", "codex", "error", null);
    Instant start = now.minus(DAY.multipliedBy(day + 1L));
    place(pool, List.of(), start, scaled(pool.size()), null, null);
    waits(pool, sample(pool.size()));
    latencies(pool, null);
    tasks(pool, Map.of("codex/dev", 10, "claude-desktop/dev", 5, "claude-desktop/incident-prod", 3));
    finish(pool);
    return pool;
  }

  /**
   * The allowed reads of a day, {@code total} of them, spread like the kit's (largest remainder), without prod-replica
   * (its own 16) and stage-db (only when the tunnel is on).
   */
  private List<Plan> allowed(int total) {
    String[][] lines = {
        {"dev", "dev-db", "sql_query", "codex", "275"}, {"dev", "dev-db", "sql_query", "claude-desktop", "150"},
        {"dev", "dev-db", "sql_execute", "codex", "23"}, {"dev", "app-logs", "logs_search", "codex", "260"},
        {"dev", "app-logs", "logs_search", "claude-desktop", "40"},
        {"incident-prod", "prod-logs", "logs_search", "claude-desktop", "41"},
        {"dev", "dev-cluster", "k8s_get", "codex", "88"}, {"dev", "dev-cluster", "k8s_get", "claude-desktop", "60"},
        {"dev", "dev-vm", "ssh_run", "claude-desktop", "89"}, {"dev", "billing-api", "http_request", "codex", "42"},
        {"dev", "cache-redis", "redis_get", "codex", "9"}, {"dev", "cache-redis", "redis_get", "claude-desktop", "17"},
        {"dev", "orders-mongo", "mongo_find", "codex", "23"}};
    int sum = 0;
    for (String[] l : lines) {
      sum += Integer.parseInt(l[4]);
    }
    int[] counts = new int[lines.length];
    double[] rest = new double[lines.length];
    int given = 0;
    for (int i = 0; i < lines.length; i++) {
      double exact = Integer.parseInt(lines[i][4]) * (double) total / sum;
      counts[i] = (int) Math.floor(exact);
      rest[i] = exact - counts[i];
      given += counts[i];
    }
    while (given < total) {
      int best = 0;
      for (int i = 1; i < rest.length; i++) {
        if (rest[i] > rest[best]) {
          best = i;
        }
      }
      counts[best]++;
      rest[best] = -1;
      given++;
    }
    List<Plan> out = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      add(out, counts[i], lines[i][0], lines[i][1], lines[i][2], lines[i][3], "allowed", null);
    }
    return out;
  }

  // ── Placing calls in time ───────────────────────────────────────────────────────────────────────────────────

  /**
   * Gives every unplaced call of {@code pool} a time in one of the 24 hours from {@code start}: denied, rejected and
   * failed calls where {@code danger} wants them, expired ones where {@code expired} does, the rest filling each
   * hour up to {@code totals} (the {@code fixed} calls already count). Without targets the calls of each category
   * spread like the totals.
   */
  private void place(List<Plan> pool, List<Plan> fixed, Instant start, int[] totals, int[] danger, int[] expired) {
    int[] capacity = totals.clone();
    int[] dangerNeed = danger == null ? null : danger.clone();
    int[] expiredNeed = expired == null ? null : expired.clone();
    for (Plan p : fixed) {
      int hour = (int) Math.min(23, Math.max(0, Duration.between(start, p.at).toHours()));
      capacity[hour]--;
      if (dangerNeed != null && p.category().equals("danger")) {
        dangerNeed[hour]--;
      }
      if (expiredNeed != null && p.category().equals("expired")) {
        expiredNeed[hour]--;
      }
    }
    List<Plan> unplaced = new ArrayList<>(pool.stream().filter(p -> !p.placed).toList());
    Collections.shuffle(unplaced, random);
    List<List<Plan>> byHour = new ArrayList<>();
    for (int h = 0; h < 24; h++) {
      byHour.add(new ArrayList<>());
    }
    for (String category : List.of("danger", "expired", "ok")) {
      int[] need = switch (category) {
        case "danger" -> dangerNeed;
        case "expired" -> expiredNeed;
        default -> null;
      };
      for (Plan p : unplaced) {
        if (!p.category().equals(category)) {
          continue;
        }
        int hour = pick(need != null ? need : capacity, capacity);
        if (need != null) {
          need[hour]--;
        }
        capacity[hour]--;
        byHour.get(hour).add(p);
      }
    }
    for (int h = 0; h < 24; h++) {
      Instant from = start.plus(HOUR.multipliedBy(h));
      Instant to = from.plus(HOUR);
      if (h == 0) {
        from = from.plus(MARGIN);
      }
      if (to.isAfter(now.minusSeconds(90))) {
        to = now.minusSeconds(90);
      }
      long span = Math.max(1, Duration.between(from, to).toSeconds());
      for (Plan p : byHour.get(h)) {
        p.at = from.plusSeconds((long) (random.nextDouble() * span));
        p.placed = true;
      }
    }
  }

  /** The hour with the most of {@code want} left (any with room when none is wanted any more). */
  private static int pick(int[] want, int[] capacity) {
    int best = -1;
    for (int h = 0; h < want.length; h++) {
      if (want[h] > 0 && (best < 0 || want[h] > want[best])) {
        best = h;
      }
    }
    if (best >= 0) {
      return best;
    }
    for (int h = 0; h < capacity.length; h++) {
      if (best < 0 || capacity[h] > capacity[best]) {
        best = h;
      }
    }
    return best;
  }

  /** The kit's hours scaled to {@code total} calls (largest remainder). */
  private static int[] scaled(int total) {
    int sum = 0;
    for (int v : KIT_HOURS) {
      sum += v;
    }
    int[] out = new int[24];
    double[] rest = new double[24];
    int given = 0;
    for (int h = 0; h < 24; h++) {
      double exact = KIT_HOURS[h] * (double) total / sum;
      out[h] = (int) Math.floor(exact);
      rest[h] = exact - out[h];
      given += out[h];
    }
    while (given < total) {
      int best = 0;
      for (int h = 1; h < 24; h++) {
        if (rest[h] > rest[best]) {
          best = h;
        }
      }
      out[best]++;
      rest[best] = -1;
      given++;
    }
    return out;
  }

  // ── Waits, timings, tasks ───────────────────────────────────────────────────────────────────────────────────

  /**
   * How long people took: the requests without a wait yet get the remaining values of {@code waits}; an expired
   * request waited its whole window (5 minutes on dev, 10 on stage and prod).
   */
  private void waits(List<Plan> calls, long[] waits) {
    List<Long> left = new ArrayList<>();
    for (long w : waits) {
      left.add(w);
    }
    for (Plan p : calls) {
      if (p.approval() && p.waitS > 0 && !p.decision.equals("expired")) {
        left.remove(p.waitS);
      }
    }
    Collections.shuffle(left, random);
    Iterator<Long> next = left.iterator();
    for (Plan p : calls) {
      if (p.decision.equals("expired")) {
        p.waitS = p.connection.equals("billing-api") || p.endpoint.equals("incident-prod") ? 600 : 300;
        p.via = "timeout";
      } else if (p.approval() && p.waitS == 0) {
        p.waitS = next.hasNext() ? next.next() : 20 + random.nextInt(60);
      }
    }
  }

  /** {@code n} waits drawn from the day's (an older day's people answered alike). */
  private long[] sample(int n) {
    long[] out = new long[n];
    for (int i = 0; i < n; i++) {
      out[i] = WAITS[random.nextInt(WAITS.length)];
    }
    return out;
  }

  /**
   * Gateway and resource times of the calls that ran: the gateway's from {@code gateway} (a multiset with the day's
   * exact p50 and p95; {@code null}: drawn like the kit's day), the resource's by connection.
   */
  private void latencies(List<Plan> calls, List<Long> gateway) {
    List<Plan> ran = calls.stream().filter(Plan::ran).toList();
    List<Long> values = gateway == null ? null : new ArrayList<>(gateway);
    if (values != null) {
      Collections.shuffle(values, random);
    }
    int i = 0;
    for (Plan p : ran) {
      p.gatewayMs = values != null && i < values.size() ? values.get(i++) : drawGateway();
      if (p.resourceMs == 0) {
        p.resourceMs = p.decision.equals("error") ? errorTime(p) : resource(p.connection);
      }
      if (p.decision.equals("error") && p.errorKind == null) {
        failure(p);
      }
    }
  }

  /**
   * A multiset of {@code n} gateway times with the given p50 and p95 (nearest rank): {@code below} values under the
   * median, {@code atMedian} at it, {@code between} up to the p95, {@code atP95} at it, {@code above} over it.
   */
  private static List<Long> gateway(int n, int below, int atMedian, int between, int atP95, int above, long p50,
      long p95) {
    if (below + atMedian + between + atP95 + above != n) {
      throw new IllegalStateException("The gateway times don't add up");
    }
    List<Long> out = new ArrayList<>(n);
    for (int i = 0; i < below; i++) {
      out.add(3 + (long) (i % (p50 - 3)));
    }
    for (int i = 0; i < atMedian; i++) {
      out.add(p50);
    }
    for (int i = 0; i < between; i++) {
      out.add(p50 + 1 + (long) (i % (p95 - p50 - 1)));
    }
    for (int i = 0; i < atP95; i++) {
      out.add(p95);
    }
    for (int i = 0; i < above; i++) {
      out.add(p95 + 1 + (long) (i % 29));
    }
    return out;
  }

  private long drawGateway() {
    double u = random.nextDouble();
    return u < 0.45 ? 3 + random.nextInt(6) : u < 0.55 ? 9 : u < 0.95 ? 10 + random.nextInt(21) : 31 + random.nextInt(30);
  }

  /** Resource time of a call that returned, from its connection's p50 and p95 (log-normal). */
  private long resource(String connection) {
    if (connection.equals("cache-redis")) {
      // Its usual median is exactly 40 ms: what the slow-connection finding compares with.
      return 36 + random.nextInt(9);
    }
    long[] p = RESOURCE.getOrDefault(connection, new long[] {20, 60});
    double sigma = Math.log((double) p[1] / p[0]) / 1.645;
    return Math.max(1, Math.round(p[0] * Math.exp(sigma * random.nextGaussian())));
  }

  private long errorTime(Plan p) {
    return switch (p.connection) {
      case "cache-redis" -> 2_000;
      case "dev-cluster" -> 10_000;
      default -> 20 + random.nextInt(40);
    };
  }

  /** What a failed call reported, as the connectors word it. */
  private static void failure(Plan p) {
    switch (p.connection) {
      case "cache-redis" -> timeout(p);
      case "billing-api" -> {
        p.errorKind = "RESOURCE_UNAVAILABLE";
        p.errorMessage = "ECONNREFUSED billing.stage.acme.dev:443";
      }
      case "app-logs" -> {
        p.errorKind = "RESOURCE_ERROR";
        p.errorMessage = "Loki answered 502 Bad Gateway";
      }
      case "dev-cluster" -> {
        p.errorKind = "TIMEOUT";
        p.errorMessage = "The Kubernetes API didn't answer within 10 s";
      }
      default -> {
        p.errorKind = "RESOURCE_ERROR";
        p.errorMessage = "The resource returned an error";
      }
    }
  }

  private static void timeout(Plan p) {
    p.errorKind = "TIMEOUT";
    p.errorMessage = "ETIMEDOUT after 2.0 s";
    p.resourceMs = 2_000;
  }

  /**
   * Agents' tasks: per client and endpoint, the calls not in a task yet split at their largest pauses into
   * {@code count} tasks, each with a trace id of its own and a name the client gave it.
   */
  private void tasks(List<Plan> calls, Map<String, Integer> counts) {
    Map<String, List<Plan>> groups = new LinkedHashMap<>();
    for (Plan p : calls) {
      if (p.traceId == null && !p.noTask) {
        groups.computeIfAbsent(p.client + "/" + p.endpoint, k -> new ArrayList<>()).add(p);
      }
    }
    groups.forEach((key, list) -> {
      list.sort(Comparator.comparing(p -> p.at));
      int n = Math.max(1, Math.min(list.size(), counts.getOrDefault(key, 1)));
      List<Integer> cuts = new ArrayList<>();
      for (int i = 1; i < list.size(); i++) {
        cuts.add(i);
      }
      cuts.sort(Comparator.comparingLong((Integer i) -> Duration.between(list.get(i - 1).at, list.get(i).at)
          .toMillis()).reversed());
      List<Integer> splits = new ArrayList<>(cuts.subList(0, n - 1));
      Collections.sort(splits);
      List<String> titles = TITLES.getOrDefault(key.substring(key.indexOf('/') + 1), List.of("Look into it"));
      int task = 0;
      String traceId = traceId();
      for (int i = 0; i < list.size(); i++) {
        if (task < splits.size() && i == splits.get(task)) {
          task++;
          traceId = traceId();
        }
        trace(list.get(i), traceId, titles.get(Math.floorMod(task + random.nextInt(3), titles.size())));
      }
    });
  }

  private static final Map<String, List<String>> TITLES = Map.of(
      "dev", List.of("Investigate slow checkout queries", "Add an index for order lookups", "Check the cache hit rate",
          "Review the order-sync deployment", "Clean up stale sessions", "Debug the flaky payment webhook",
          "Backfill order statuses", "Find why refunds fail", "Tune the worker pool", "Check yesterday's errors"),
      "incident-prod", List.of("Investigate checkout latency on prod", "Check replica lag on prod",
          "Look into the 5xx spike on api", "Confirm the order-sync backlog on prod"),
      "stage-readonly", List.of("Try stage-readonly from claude.ai", "Compare the stage schema with dev"));

  /** Arguments, results and the trace id of every call that has none yet. */
  private void finish(List<Plan> calls) {
    for (Plan p : calls) {
      if (p.args == null) {
        p.args = args(p);
      }
      if (p.at.isAfter(now.minusSeconds(60))) {
        p.at = now.minusSeconds(60);
      }
      Instant latest = now.minusSeconds(5);
      if (p.end().isAfter(latest)) {
        p.at = p.at.minus(Duration.between(latest, p.end()));
      }
    }
  }

  // ── Arguments ───────────────────────────────────────────────────────────────────────────────────────────────

  private static final List<String> SELECTS = List.of(
      "SELECT id, status FROM orders WHERE created_at > now() - interval '1 hour' LIMIT 50",
      "SELECT count(*) FROM order_sync_queue WHERE state = 'failed'",
      "SELECT id, total, currency FROM orders WHERE customer_id = 48213 ORDER BY created_at DESC LIMIT 20",
      "SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY n_live_tup DESC LIMIT 10",
      "SELECT state, count(*) FROM order_sync_state GROUP BY state",
      "EXPLAIN SELECT * FROM orders WHERE status = 'pending' AND updated_at < now() - interval '1 day'");
  private static final List<String> UPDATES = List.of(
      "UPDATE orders SET status = 'shipped' WHERE id = 48190;",
      "UPDATE order_sync_state SET retries = 0 WHERE state = 'failed';",
      "DELETE FROM sessions WHERE expires_at < now() - interval '30 days';",
      "UPDATE orders SET status = 'cancelled' WHERE id = 48177;");
  private static final List<String> LOGS = List.of("{app=\"api\"} |= \"ERROR\"", "{app=\"order-sync\"} |= \"retry\"",
      "{app=\"worker\"} |~ \"timeout|deadline\"", "{app=\"api\"} |= \"POST /v1/refunds\"");

  private ObjectNode args(Plan p) {
    ObjectNode a = JsonNodeFactory.instance.objectNode();
    switch (p.tool) {
      case "sql_query" -> {
        return p.decision.equals("denied") ? sql("DELETE FROM sessions WHERE expires_at < now()")
            : sql(pick(SELECTS));
      }
      case "sql_execute" -> {
        return p.approval() ? sql(pick(UPDATES)) : sql("EXPLAIN ANALYZE SELECT * FROM order_sync_queue LIMIT 100");
      }
      case "logs_search" -> {
        a.put("query", p.connection.equals("prod-logs") ? "resource.type=\"k8s_container\" severity>=ERROR"
            : pick(LOGS));
        a.put("from", "-1h");
      }
      case "k8s_get" -> {
        a.put("kind", p.decision.equals("denied") ? "secrets" : pick(List.of("pods", "deployments", "events")));
        a.put("namespace", p.decision.equals("denied") ? "kube-system" : "app-dev");
      }
      case "k8s_apply" -> {
        a.put("namespace", "app-dev");
        a.put("manifest", "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: order-sync\nspec:\n  replicas: "
            + (3 + random.nextInt(3)) + "\n");
      }
      case "k8s_rollout_restart" -> {
        return rollout(pick(List.of("api", "worker", "order-sync")));
      }
      case "ssh_run" -> {
        return runbook(p.approval() ? pick(List.of("restart-worker", "rotate-logs"))
            : pick(List.of("compose-ps", "disk-usage", "tail-api-log")));
      }
      case "ssh_exec" -> a.put("command", "docker compose restart worker");
      case "http_request" -> {
        if (p.decision.equals("denied")) {
          a.put("method", "GET");
          a.put("url", "http://169.254.169.254/latest/meta-data/");
        } else if (p.approval()) {
          a.put("method", "POST");
          a.put("path", "/v1/refunds");
          a.putObject("json").put("order", 48000 + random.nextInt(300)).put("amount", 900 + random.nextInt(9000))
              .put("currency", "EUR");
        } else {
          a.put("method", "GET");
          a.put("path", "/v1/orders/" + (48000 + random.nextInt(300)));
        }
      }
      case "redis_get" -> a.put("key", "session:" + (48000 + random.nextInt(300)));
      case "mongo_find" -> {
        a.put("collection", "orders");
        a.putObject("filter").put("status", pick(List.of("pending", "failed", "shipped")));
        a.put("limit", 20);
      }
      default -> a.put("note", "demo");
    }
    return a;
  }

  private static ObjectNode sql(String text) {
    ObjectNode a = JsonNodeFactory.instance.objectNode();
    a.put("sql", text);
    return a;
  }

  private static ObjectNode rollout(String name) {
    ObjectNode a = JsonNodeFactory.instance.objectNode();
    a.put("kind", "deployment");
    a.put("name", name);
    a.put("namespace", "app-dev");
    return a;
  }

  private static ObjectNode runbook(String name) {
    ObjectNode a = JsonNodeFactory.instance.objectNode();
    a.put("runbook", name);
    return a;
  }

  /** What a read returned, in the words of the kit's previews. */
  private String preview(Plan p) {
    return switch (p.tool) {
      case "sql_query", "sql_execute" -> (1 + random.nextInt(48)) + " rows";
      case "logs_search" -> (random.nextInt(120)) + " lines";
      case "k8s_get" -> (1 + random.nextInt(12)) + " " + p.args.path("kind").asString("items");
      case "ssh_run" -> p.args.path("runbook").asString().equals("compose-ps") ? "api, worker, order-sync: running"
          : "done";
      case "http_request" -> "200 OK · " + (1 + random.nextInt(9)) + "." + random.nextInt(10) + " KB";
      case "redis_get" -> "hit";
      case "mongo_find" -> (1 + random.nextInt(20)) + " documents";
      default -> "ok";
    };
  }

  // ── Rows ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** The rows of a planned call, as the call path writes them. */
  private AuditBackfill.PastCall past(Plan p) {
    EndpointSnapshot.Pair pair = world.pairs().pair(p.endpoint, p.tool, p.connection);
    Caller caller = world.caller(p.client);
    ObjectNode args = p.args.deepCopy();
    args.put("connection", pair.connectionName());
    ObjectNode facets = JsonNodeFactory.instance.objectNode();
    facets.put("nature", pair.tool().nature().name().toLowerCase(Locale.ROOT));
    if (p.task != null) {
      facets.put("task", p.task);
    }
    if (args.hasNonNull("runbook")) {
      facets.put("runbook", args.path("runbook").asString());
    }
    boolean traced = p.traceId != null;
    AuditWriter.Call call = new AuditWriter.Call(Ids.next("tc_"), p.endpoint, pair.tool().name(), pair.toolKey(),
        pair.connectionName(), pair.config().env(), caller, caller.clientHint(), null, PROTOCOL,
        traced ? p.traceId : hex(16), hex(8), traced, args, CanonicalJson.sha256Hex(args), facets);
    List<AuditBackfill.PastRow> rows = new ArrayList<>();
    long duration = p.gatewayMs + p.resourceMs;
    switch (p.decision) {
      case "denied" -> rows.add(AuditBackfill.PastRow.received(p.at, AuditWriter.Received.of(
          AuditWriter.Verdict.DENIED, Classification.Kind.DENY, p.rule)));
      case "allowed", "error" -> {
        rows.add(AuditBackfill.PastRow.received(p.at, AuditWriter.Received.of(AuditWriter.Verdict.ALLOWED,
            Classification.Kind.READ, null)));
        boolean error = p.decision.equals("error");
        String text = error ? p.errorMessage : preview(p);
        rows.add(AuditBackfill.PastRow.completed(p.at.plusMillis(duration), new AuditWriter.Completion(error
            ? AuditWriter.Verdict.ERROR : AuditWriter.Verdict.ALLOWED, null, null, null, null, duration, p.gatewayMs,
            p.resourceMs, null, (long) text.getBytes(StandardCharsets.UTF_8).length,
            CanonicalJson.sha256Hex(JsonNodeFactory.instance.stringNode(text)), error ? null : text, 0, false,
            error ? p.errorKind : null, error ? text : null, null, null, null)));
      }
      default -> {
        String approvalId = Ids.next("ap_");
        String action = CanonicalJson.sha256Hex(args);
        String comment = p.commented ? AuditWriter.commentSha256("Checked with the on-call runbook") : null;
        long waitMs = p.waitS * 1_000;
        Instant decided = p.at.plusMillis(waitMs);
        rows.add(AuditBackfill.PastRow.received(p.at, new AuditWriter.Received(AuditWriter.Verdict.PENDING,
            Classification.Kind.MUTATION, p.rule, approvalId, AuditWriter.Delivery.HANDLE, null, null)));
        AuditWriter.Verdict verdict = switch (p.decision) {
          case "approved" -> AuditWriter.Verdict.APPROVED;
          case "rejected" -> AuditWriter.Verdict.REJECTED;
          default -> AuditWriter.Verdict.EXPIRED;
        };
        rows.add(AuditBackfill.PastRow.resolved(decided, new AuditWriter.Resolution(verdict, approvalId, p.rule,
            p.via, comment, action, waitMs, null)));
        if (verdict == AuditWriter.Verdict.APPROVED) {
          String text = preview(p);
          rows.add(AuditBackfill.PastRow.completed(decided.plusMillis(duration), new AuditWriter.Completion(
              AuditWriter.Verdict.APPROVED, p.rule, approvalId, AuditWriter.Delivery.HANDLE, p.via, duration,
              p.gatewayMs, p.resourceMs, waitMs, (long) text.length(), CanonicalJson.sha256Hex(
                  JsonNodeFactory.instance.stringNode(text)), text, 0, false, null, null, null, action, action)));
        }
      }
    }
    return new AuditBackfill.PastCall(call, rows);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static void add(List<Plan> out, int n, String endpoint, String connection, String tool, String client,
      String decision, String rule) {
    for (int i = 0; i < n; i++) {
      out.add(new Plan(endpoint, connection, tool, client, decision, rule));
    }
  }

  private static Predicate<Plan> match(String connection, String tool, String client, String decision) {
    return p -> p.connection.equals(connection) && p.tool.equals(tool) && p.client.equals(client)
        && p.decision.equals(decision);
  }

  /** Takes {@code n} calls matching {@code which} out of {@code pool}. */
  private static List<Plan> take(List<Plan> pool, int n, Predicate<Plan> which) {
    List<Plan> out = new ArrayList<>();
    Iterator<Plan> it = pool.iterator();
    while (it.hasNext() && out.size() < n) {
      Plan p = it.next();
      if (which.test(p)) {
        out.add(p);
        it.remove();
      }
    }
    if (out.size() < n) {
      throw new IllegalStateException("The demo history has fewer such calls than its stories need");
    }
    return out;
  }

  /** {@code p} received {@code secondsAgo} before the seeding. */
  private Plan at(Plan p, long secondsAgo) {
    p.at = now.minusSeconds(secondsAgo);
    p.placed = true;
    return p;
  }

  private static void trace(Plan p, String traceId, String task) {
    p.traceId = traceId;
    p.task = task;
  }

  private String traceId() {
    return hex(16);
  }

  private String hex(int bytes) {
    byte[] b = new byte[bytes];
    random.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  private <T> T pick(List<T> values) {
    return values.get(random.nextInt(values.size()));
  }
}
