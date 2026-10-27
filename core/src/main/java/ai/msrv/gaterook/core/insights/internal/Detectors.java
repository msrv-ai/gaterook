package ai.msrv.gaterook.core.insights.internal;

import ai.msrv.gaterook.core.audit.AuditQueries.CallFacts;
import ai.msrv.gaterook.core.audit.AuditQueries.Trace;
import ai.msrv.gaterook.core.insights.Insights.Unusual;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The Unusual activity detectors (ARCHITECTURE.md §10 K5), each over the calls of the range and of the 7 days before
 * it, narrowed by the same query. Pure functions: the golden test runs them on the demo world.
 * <ul>
 *   <li><b>prod-ddl</b> — an agent's task in which a policy denied DDL on prod ({@code *.sql.block_ddl}, at least
 *       once): the statements in the order they came, how far apart, who sent them;</li>
 *   <li><b>tool-spike</b> — a tool on a connection with at least {@value #SPIKE_MIN_CALLS} calls in the range and at
 *       least {@value #SPIKE_FACTOR}× its daily average of the 7 days before, scaled to the range; with the client,
 *       the endpoint and the hours most of the calls came from. Needs the audit to reach back those 7 days;</li>
 *   <li><b>slow-connection</b> — a connection whose p95 time in the resource is at least {@value #SLOW_FACTOR}× its
 *       median of the 7 days before (completed calls; at least five of them before): since when, and after how long
 *       its calls time out, as its timeouts in the range show.</li>
 * </ul>
 */
public final class Detectors {

  public static final double SPIKE_FACTOR = 4;
  public static final int SPIKE_MIN_CALLS = 20;
  public static final double SLOW_FACTOR = 10;
  public static final Duration BASELINE = Duration.ofDays(7);
  private static final int SLOW_MIN_BASELINE = 5;

  private Detectors() {}

  /**
   * @param range            the calls of {@code [from, to)}
   * @param baseline         the calls of the 7 days before {@code from}
   * @param baselineComplete the audit reaches back to the start of those 7 days
   * @param tasks            the agents' tasks among the calls, by trace id
   * @param zone             this computer's time zone: the hours of a spike are whole hours here
   */
  public static List<Unusual> find(List<CallFacts> range, List<CallFacts> baseline, Instant from, Instant to,
      boolean baselineComplete, Map<String, Trace> tasks, ZoneId zone) {
    List<Unusual> out = new ArrayList<>(prodDdl(range, tasks));
    if (baselineComplete) {
      out.addAll(toolSpikes(range, baseline, from, to, zone));
    }
    out.addAll(slowConnections(range, baseline));
    return List.copyOf(out);
  }

  // ── prod-ddl ────────────────────────────────────────────────────────────────────────────────────────────────

  static List<Unusual> prodDdl(List<CallFacts> range, Map<String, Trace> tasks) {
    Map<String, List<CallFacts>> byTask = new LinkedHashMap<>();
    range.stream().filter(c -> "denied".equals(c.decision()) && blockDdl(c.rule()) && "prod".equals(c.env())
            && c.traceId() != null && tasks.containsKey(c.traceId()))
        .sorted(Comparator.comparingLong(CallFacts::id))
        .forEach(c -> byTask.computeIfAbsent(c.traceId(), k -> new ArrayList<>()).add(c));
    List<Unusual> out = new ArrayList<>();
    byTask.forEach((traceId, calls) -> {
      List<String> statements = new ArrayList<>();
      for (CallFacts c : calls) {
        Object sql = c.args() == null ? null : c.args().get("sql");
        String s = sql instanceof String text ? Literals.statement(text) : null;
        s = s == null ? c.tool() : s;
        if (statements.isEmpty() || !statements.getLast().equals(s)) {
          statements.add(s);
        }
      }
      CallFacts first = calls.getFirst();
      CallFacts last = calls.getLast();
      long within = Duration.between(first.at(), last.at()).toSeconds();
      String client = mostCommon(calls, CallFacts::client);
      String connection = mostCommon(calls, CallFacts::connection);
      String text = (client == null ? "An agent" : client) + " sent " + sequence(statements) + " to "
          + (connection == null ? "a database" : connection) + " (prod)" + (calls.size() > 1 ? " within " + within
          + " s" : "") + " in one task; " + calls.size() + " of " + calls.size() + " denied.";
      Trace task = tasks.get(traceId);
      out.add(new Unusual("prod-ddl", text, traceId, task == null ? null : task.title(), client, null, null,
          connection, "prod", List.copyOf(statements), within, (long) calls.size(), (long) calls.size(), null, null,
          null, null, null, null, null, null));
    });
    return out;
  }

  static boolean blockDdl(String rule) {
    return rule != null && (rule.equals("sql.block_ddl") || rule.endsWith(".sql.block_ddl"));
  }

  // ── tool-spike ──────────────────────────────────────────────────────────────────────────────────────────────

  private record Pair(String tool, String connection) {}

  static List<Unusual> toolSpikes(List<CallFacts> range, List<CallFacts> baseline, Instant from, Instant to,
      ZoneId zone) {
    Map<Pair, List<CallFacts>> now = new LinkedHashMap<>();
    range.stream().filter(c -> c.connection() != null)
        .forEach(c -> now.computeIfAbsent(new Pair(c.tool(), c.connection()), k -> new ArrayList<>()).add(c));
    Map<Pair, Long> before = new LinkedHashMap<>();
    baseline.stream().filter(c -> c.connection() != null)
        .forEach(c -> before.merge(new Pair(c.tool(), c.connection()), 1L, Long::sum));
    double rangeDays = Duration.between(from, to).toMillis() / (double) Duration.ofDays(1).toMillis();
    List<Unusual> out = new ArrayList<>();
    now.forEach((pair, calls) -> {
      if (calls.size() < SPIKE_MIN_CALLS) {
        return;
      }
      double daily = before.getOrDefault(pair, 0L) / (double) BASELINE.toDays();
      if (daily <= 0) {
        // Nothing to compare with: a tool new on this connection isn't a spike of itself.
        return;
      }
      double factor = calls.size() / (daily * rangeDays);
      if (factor < SPIKE_FACTOR) {
        return;
      }
      Instant[] window = window(calls, zone);
      String text = pair.tool() + " on " + pair.connection() + " is " + round(factor, 1) + "× its 7-day average: "
          + calls.size() + " calls against " + round(daily, 1) + " a day.";
      out.add(new Unusual("tool-spike", text, null, null, mostCommon(calls, CallFacts::client),
          mostCommon(calls, CallFacts::endpoint), pair.tool(), pair.connection(), mostCommon(calls, CallFacts::env),
          null, null, (long) calls.size(), null, round(daily, 2), round(factor, 2), window[0], window[1], null, null,
          null, null));
    });
    out.sort(Comparator.comparing(Unusual::factor, Comparator.reverseOrder()));
    return out;
  }

  /**
   * The densest three whole hours (in {@code zone}) of the calls, when they hold more than half of them; trimmed to
   * the hours that have calls. {@code [null, null]} otherwise.
   */
  static Instant[] window(List<CallFacts> calls, ZoneId zone) {
    TreeMap<Instant, Integer> perHour = new TreeMap<>();
    for (CallFacts c : calls) {
      perHour.merge(c.at().atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant(), 1, Integer::sum);
    }
    Instant best = null;
    int bestSum = -1;
    for (Instant start : perHour.keySet()) {
      int sum = perHour.subMap(start, true, start.plus(Duration.ofHours(3)), false).values().stream()
          .mapToInt(Integer::intValue).sum();
      if (sum > bestSum) {
        best = start;
        bestSum = sum;
      }
    }
    if (best == null || bestSum * 2 <= calls.size()) {
      return new Instant[] {null, null};
    }
    Instant lastHour = perHour.subMap(best, true, best.plus(Duration.ofHours(3)), false).lastKey();
    return new Instant[] {best, lastHour.plus(Duration.ofHours(1))};
  }

  // ── slow-connection ─────────────────────────────────────────────────────────────────────────────────────────

  static List<Unusual> slowConnections(List<CallFacts> range, List<CallFacts> baseline) {
    Map<String, List<CallFacts>> now = new LinkedHashMap<>();
    range.stream().filter(c -> c.connection() != null)
        .sorted(Comparator.comparingLong(CallFacts::id))
        .forEach(c -> now.computeIfAbsent(c.connection(), k -> new ArrayList<>()).add(c));
    Map<String, List<Long>> usual = new LinkedHashMap<>();
    baseline.stream().filter(Detectors::completed)
        .forEach(c -> usual.computeIfAbsent(c.connection(), k -> new ArrayList<>()).add(latency(c)));
    List<Unusual> out = new ArrayList<>();
    now.forEach((connection, calls) -> {
      List<Long> before = usual.getOrDefault(connection, List.of());
      List<Long> times = calls.stream().filter(Detectors::completed).map(Detectors::latency).toList();
      if (before.size() < SLOW_MIN_BASELINE || times.isEmpty()) {
        return;
      }
      long median = Math.max(1, Percentiles.of(before, 0.5));
      long p95 = Percentiles.of(times, 0.95);
      if (p95 < SLOW_FACTOR * median) {
        return;
      }
      Instant since = calls.stream().filter(c -> completed(c) && latency(c) >= SLOW_FACTOR * median)
          .map(CallFacts::at).min(Comparator.naturalOrder()).orElse(null);
      List<Long> timeouts = calls.stream().filter(c -> "error".equals(c.decision())
              && "TIMEOUT".equalsIgnoreCase(c.errorKind()))
          .map(c -> c.resourceMs() != null ? c.resourceMs() : c.durationMs()).filter(Objects::nonNull).toList();
      Long timeout = timeouts.isEmpty() ? null : Math.round(Percentiles.of(timeouts, 0.5) / 100.0) * 100;
      long factor = Math.round(p95 / (double) median);
      String text = connection + " answers " + factor + "× slower than usual: " + p95 + " ms at p95 against "
          + median + " ms over the last 7 days.";
      out.add(new Unusual("slow-connection", text, null, null, null, null, null, connection,
          mostCommon(calls, CallFacts::env), null, null, (long) calls.size(), null, null, (double) factor, null, null,
          p95, median, since, timeout));
    });
    out.sort(Comparator.comparing(Unusual::factor, Comparator.reverseOrder()));
    return out;
  }

  /** A call that ran and returned: its time in the resource says how fast the connection is. */
  static boolean completed(CallFacts c) {
    return c.connection() != null && ("allowed".equals(c.decision()) || "approved".equals(c.decision()))
        && latency(c) != null;
  }

  /** The time in the resource, else the call's whole duration. */
  static Long latency(CallFacts c) {
    return c.resourceMs() != null ? c.resourceMs() : c.durationMs();
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** The value most calls have (the first such in order on a tie), or {@code null}. */
  static String mostCommon(List<CallFacts> calls, Function<CallFacts, String> field) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (CallFacts c : calls) {
      String v = field.apply(c);
      if (v != null) {
        counts.merge(v, 1, Integer::sum);
      }
    }
    return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
  }

  private static String sequence(List<String> items) {
    if (items.size() == 1) {
      return items.getFirst();
    }
    return String.join(", ", items.subList(0, items.size() - 1)) + " and then " + items.getLast();
  }

  private static double round(double value, int digits) {
    double scale = Math.pow(10, digits);
    return Math.round(value * scale) / scale;
  }
}
