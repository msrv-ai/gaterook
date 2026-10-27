package ai.msrv.gaterook.core.insights.internal;

import ai.msrv.gaterook.core.audit.AuditQueries.CallFacts;
import ai.msrv.gaterook.core.insights.Insights;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The numbers of Insights over the calls of one range (ARCHITECTURE.md §6 insights), each call as its latest audit row
 * says: what it came to, its timings, its approval. Pure functions: tests feed them rows and compare with the kit.
 */
public final class Aggregates {

  /** Who decides a request without a person: the core (a connection deleted) or the clock (expired). */
  private static final Set<String> NOT_A_PERSON = Set.of("core", "timeout");
  private static final long[] WAIT_BOUNDS = {60, 120, 300};

  private Aggregates() {}

  /** The headline numbers; {@code tasks} counts the traces among the calls that are agents' tasks. */
  public static Insights.Totals totals(List<CallFacts> calls, Set<String> taskIds) {
    long decided = 0;
    long pending = 0;
    long approved = 0;
    long denied = 0;
    long errors = 0;
    List<Long> gateway = new ArrayList<>();
    Set<String> tasks = new java.util.HashSet<>();
    for (CallFacts c : calls) {
      switch (c.decision()) {
        case "pending" -> pending++;
        case "approved" -> approved++;
        case "denied" -> denied++;
        case "error" -> errors++;
        default -> {
          // allowed, rejected, expired: counted as decided below.
        }
      }
      if (!"pending".equals(c.decision())) {
        decided++;
      }
      if (c.gatewayMs() != null) {
        gateway.add(c.gatewayMs());
      }
      if (c.traceId() != null && taskIds.contains(c.traceId())) {
        tasks.add(c.traceId());
      }
    }
    return new Insights.Totals(decided, pending, approved, denied, errors, tasks.size(),
        Percentiles.of(gateway, 0.5), Percentiles.of(gateway, 0.95));
  }

  /** Decided calls by decision. */
  public static Map<String, Long> decisions(List<CallFacts> calls) {
    Map<String, Long> out = new TreeMap<>();
    decided(calls).forEach(c -> out.merge(c.decision(), 1L, Long::sum));
    return out;
  }

  /** Every tool with decided calls, most first (by name on a tie). */
  public static List<Insights.ToolCount> topTools(List<CallFacts> calls) {
    return ranked(decided(calls).collect(Collectors.groupingBy(CallFacts::tool, Collectors.counting()))).stream()
        .map(e -> new Insights.ToolCount(e.getKey(), e.getValue())).toList();
  }

  /** Every client with decided calls, most first; a call without a client counts as {@code other}. */
  public static List<Insights.ClientCalls> byClient(List<CallFacts> calls) {
    Map<String, List<CallFacts>> byClient = decided(calls).collect(Collectors.groupingBy(
        c -> c.client() == null ? "other" : c.client(), LinkedHashMap::new, Collectors.toList()));
    List<Insights.ClientCalls> out = new ArrayList<>();
    byClient.forEach((client, list) -> out.add(new Insights.ClientCalls(client, list.size(), decisions(list),
        names(list, CallFacts::endpoint), names(list, CallFacts::connection),
        list.stream().map(CallFacts::at).min(Comparator.naturalOrder()).orElse(null),
        list.stream().map(CallFacts::at).max(Comparator.naturalOrder()).orElse(null))));
    out.sort(Comparator.comparingLong(Insights.ClientCalls::count).reversed()
        .thenComparing(Insights.ClientCalls::client));
    return List.copyOf(out);
  }

  /**
   * Approval requests that ended in the range, each counted once (calls that reused a request share its id): approved
   * — it ran, even if it then failed — rejected and expired; the waits of those a person decided.
   *
   * @param receivedAt when each slow call was received (its request), by call id
   */
  public static Insights.Approvals approvals(List<CallFacts> calls, Map<String, Instant> receivedAt) {
    Map<String, CallFacts> requests = new LinkedHashMap<>();
    for (CallFacts c : calls) {
      if (c.approvalId() != null && Set.of("approved", "rejected", "expired", "error").contains(c.decision())
          && !("error".equals(c.decision()) && NOT_A_PERSON.contains(c.decidedVia()))) {
        requests.putIfAbsent(c.approvalId(), c);
      }
    }
    long approved = 0;
    long rejected = 0;
    long expired = 0;
    List<CallFacts> decidedByPerson = new ArrayList<>();
    for (CallFacts c : requests.values()) {
      String outcome = outcome(c);
      switch (outcome) {
        case "approved" -> approved++;
        case "rejected" -> rejected++;
        default -> expired++;
      }
      if (!"expired".equals(outcome) && !NOT_A_PERSON.contains(c.decidedVia()) && c.approvalWaitMs() != null) {
        decidedByPerson.add(c);
      }
    }
    long total = approved + rejected + expired;
    List<Long> waits = decidedByPerson.stream().map(c -> seconds(c.approvalWaitMs())).toList();
    List<Insights.WaitBucket> histogram = new ArrayList<>();
    if (!waits.isEmpty()) {
      long lower = Long.MIN_VALUE;
      for (long bound : WAIT_BOUNDS) {
        long from = lower;
        histogram.add(new Insights.WaitBucket(bound, waits.stream().filter(w -> w > from && w <= bound).count()));
        lower = bound;
      }
      long last = lower;
      histogram.add(new Insights.WaitBucket(null, waits.stream().filter(w -> w > last).count()));
    }
    List<Insights.SlowDecision> slowest = decidedByPerson.stream()
        .sorted(Comparator.comparingLong((CallFacts c) -> c.approvalWaitMs()).reversed()
            .thenComparingLong(CallFacts::id))
        .limit(5)
        .map(c -> new Insights.SlowDecision(c.approvalId(), c.id(), c.tool(), c.connection(), c.env(),
            receivedAt.getOrDefault(c.callId(), c.at().minusMillis(c.approvalWaitMs())), outcome(c),
            seconds(c.approvalWaitMs())))
        .toList();
    return new Insights.Approvals(approved, rejected, expired, Percentiles.of(waits, 0.5),
        Percentiles.of(waits, 0.9), total == 0 ? null : approved / (double) total, List.copyOf(histogram), slowest);
  }

  /**
   * The calls the slowest list may name (those a person decided, longest wait first, with room for calls that share
   * a request): their request times are read for {@link #approvals}.
   */
  public static List<CallFacts> slowestCalls(List<CallFacts> calls) {
    return calls.stream().filter(c -> c.approvalId() != null && c.approvalWaitMs() != null
            && Set.of("approved", "rejected", "error").contains(c.decision()) && !NOT_A_PERSON.contains(c.decidedVia()))
        .sorted(Comparator.comparingLong((CallFacts c) -> c.approvalWaitMs()).reversed())
        .limit(10).toList();
  }

  /** Denied calls by rule, most first, each with the most recent example of what it stopped. */
  public static List<Insights.DeniedRule> deniedByRule(List<CallFacts> calls) {
    Map<String, List<CallFacts>> byRule = calls.stream().filter(c -> "denied".equals(c.decision()))
        .collect(Collectors.groupingBy(c -> c.rule() == null ? "policy" : c.rule(), LinkedHashMap::new,
            Collectors.toList()));
    List<Insights.DeniedRule> out = new ArrayList<>();
    byRule.forEach((rule, list) -> {
      CallFacts latest = list.stream().max(Comparator.comparingLong(CallFacts::id)).orElseThrow();
      String literal = Literals.of(latest.rule(), latest.tool(), latest.args());
      out.add(new Insights.DeniedRule(rule, list.size(), literal == null ? null
          : new Insights.Example(literal, latest.connection(), latest.env())));
    });
    out.sort(Comparator.comparingLong(Insights.DeniedRule::count).reversed().thenComparing(Insights.DeniedRule::rule));
    return List.copyOf(out);
  }

  /**
   * Gateway and resource time per connection over the calls that ran and returned (allowed, approved), approval waits
   * excluded; slowest p95 (gateway + resource) first.
   */
  public static List<Insights.ConnectionLatency> latency(List<CallFacts> calls) {
    Map<String, List<CallFacts>> byConnection = calls.stream()
        .filter(c -> c.connection() != null && ("allowed".equals(c.decision()) || "approved".equals(c.decision()))
            && (c.gatewayMs() != null || c.resourceMs() != null))
        .collect(Collectors.groupingBy(CallFacts::connection, LinkedHashMap::new, Collectors.toList()));
    List<Insights.ConnectionLatency> out = new ArrayList<>();
    byConnection.forEach((connection, list) -> {
      List<Long> gateway = list.stream().map(CallFacts::gatewayMs).filter(Objects::nonNull).toList();
      List<Long> resource = list.stream().map(CallFacts::resourceMs).filter(Objects::nonNull).toList();
      out.add(new Insights.ConnectionLatency(connection, Detectors.mostCommon(list, CallFacts::env), list.size(),
          orZero(Percentiles.of(gateway, 0.5)), orZero(Percentiles.of(gateway, 0.95)),
          orZero(Percentiles.of(resource, 0.5)), orZero(Percentiles.of(resource, 0.95))));
    });
    out.sort(Comparator.comparingLong((Insights.ConnectionLatency l) -> l.gatewayP95() + l.resourceP95()).reversed()
        .thenComparing(Insights.ConnectionLatency::connection));
    return List.copyOf(out);
  }

  /** Failed calls per connection, most first, with the most common error (kind and text as stored). */
  public static List<Insights.ConnectionErrors> errorsByConnection(List<CallFacts> calls) {
    Map<String, List<CallFacts>> byConnection = calls.stream()
        .filter(c -> "error".equals(c.decision()) && c.connection() != null)
        .collect(Collectors.groupingBy(CallFacts::connection, LinkedHashMap::new, Collectors.toList()));
    List<Insights.ConnectionErrors> out = new ArrayList<>();
    byConnection.forEach((connection, list) -> {
      Map<List<String>, Long> kinds = new LinkedHashMap<>();
      list.stream().sorted(Comparator.comparingLong(CallFacts::id).reversed())
          .forEach(c -> kinds.merge(java.util.Arrays.asList(c.errorKind(), c.errorMessage()), 1L, Long::sum));
      List<String> common = kinds.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
          .orElse(java.util.Arrays.asList(null, null));
      out.add(new Insights.ConnectionErrors(connection, Detectors.mostCommon(list, CallFacts::env), list.size(),
          common.get(0), common.get(1)));
    });
    out.sort(Comparator.comparingLong(Insights.ConnectionErrors::count).reversed()
        .thenComparing(Insights.ConnectionErrors::connection));
    return List.copyOf(out);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** What a request came to: approved (it ran, also when the run failed), rejected or expired. */
  private static String outcome(CallFacts c) {
    return switch (c.decision()) {
      case "approved", "error" -> "approved";
      case "rejected" -> "rejected";
      default -> "expired";
    };
  }

  private static java.util.stream.Stream<CallFacts> decided(List<CallFacts> calls) {
    return calls.stream().filter(c -> !"pending".equals(c.decision()));
  }

  private static List<String> names(List<CallFacts> calls, Function<CallFacts, String> field) {
    return ranked(calls.stream().map(field).filter(Objects::nonNull)
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))).stream()
        .map(Map.Entry::getKey).toList();
  }

  /** Most first, then by name. */
  private static List<Map.Entry<String, Long>> ranked(Map<String, Long> counts) {
    return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
        .thenComparing(Map.Entry.comparingByKey())).toList();
  }

  private static long seconds(Long ms) {
    return ms == null ? 0 : Math.round(ms / 1000.0);
  }

  private static long orZero(Long v) {
    return v == null ? 0 : v;
  }
}
