package ai.msrv.gaterook.core.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import ai.msrv.gaterook.core.audit.AuditQueries;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/insights} (ARCHITECTURE.md §6 insights; the UI's contract is {@code ui/src/app/core/api/insights.api.ts}):
 * numbers and names over the audit of one range, narrowed by Activity's query language, and the same numbers for the
 * range before it. No sentences and no queries: the page writes those from these facts — except {@link Unusual#text},
 * the plain sentence of a finding for a kind the page doesn't know.
 *
 * @param firstCallAt     when the oldest call the audit keeps reached the gateway; {@code null} before the first call
 * @param unfilteredCalls decided calls of the range without the query ("312 of 1,284 calls")
 * @param callsOverTime   calls per bucket and decision, waiting ones included: the shape of Activity's histogram
 * @param topTools        every tool with decided calls, most first
 * @param byClient        every client with decided calls, most first
 * @param decisions       decided calls by decision; the sum is {@code kpis.current.calls}
 * @param deniedByRule    most denials first
 * @param errorsByConnection most errors first
 */
public record Insights(Instant from, Instant to, Instant firstCallAt, long unfilteredCalls, Kpis kpis,
    AuditQueries.Histogram callsOverTime, List<ToolCount> topTools, List<ClientCalls> byClient,
    Map<String, Long> decisions, Approvals approvals, List<DeniedRule> deniedByRule, Latency latency,
    List<ConnectionErrors> errorsByConnection, List<Unusual> unusual) {

  /** This range and the one of the same length before it ({@code null} when the audit doesn't reach back that far). */
  public record Kpis(Totals current, Totals previous) {}

  /**
   * The headline numbers of a range.
   *
   * @param calls   calls with a decision (waiting ones have none yet)
   * @param pending calls still waiting for a person
   * @param tasks   agents' tasks (Activity › Traces) with calls in the range
   * @param gatewayP50Ms the gateway's own time per call ({@code tool_call.gateway_ms}), {@code null} without
   *                     completed calls
   */
  public record Totals(long calls, long pending, long approved, long denied, long errors, long tasks,
      Long gatewayP50Ms, Long gatewayP95Ms) {}

  public record ToolCount(String name, long count) {}

  /** One client's decided calls, the endpoints and connections it called (most calls first). */
  public record ClientCalls(String client, long count, Map<String, Long> decisions, List<String> endpoints,
      List<String> connections, Instant firstAt, Instant lastAt) {}

  /**
   * Approval requests that ended in the range.
   *
   * @param medianWaitS   over requests a person decided (approved and rejected); expired ones got no answer
   * @param approvedShare approved ÷ (approved + rejected + expired), {@code null} without requests
   * @param waitHistogram how long a person took, by upper bound in seconds; the last bucket's {@code toS} is
   *                      {@code null}
   * @param slowest       the longest waits, longest first (up to 5)
   */
  public record Approvals(long approved, long rejected, long expired, Long medianWaitS, Long p90WaitS,
      Double approvedShare, List<WaitBucket> waitHistogram, List<SlowDecision> slowest) {}

  public record WaitBucket(Long toS, long count) {}

  /** @param eventId the call's event in Activity ({@code #event-<id>}) */
  public record SlowDecision(String approvalId, Long eventId, String tool, String connection, String env,
      Instant requestedAt, String status, long waitS) {}

  /** Calls one rule denied and the most recent example of what it stopped. */
  public record DeniedRule(String rule, long count, Example example) {}

  /** @param literal what was stopped: a tool, a statement ({@code DROP TABLE}), a request, a namespace */
  public record Example(String literal, String connection, String env) {}

  public record Latency(List<ConnectionLatency> byConnection) {}

  /** Where the time of a call goes on one connection, approval waits excluded; slowest p95 first. */
  public record ConnectionLatency(String connection, String env, long calls, long gatewayP50, long gatewayP95,
      long resourceP50, long resourceP95) {}

  /** Failed calls on one connection and their most common error, masked as the audit stores it. */
  public record ConnectionErrors(String connection, String env, long count, String kind, String message) {}

  /**
   * One finding of the Unusual activity detectors, compared with the 7 days before the range: {@code prod-ddl}
   * (DDL denied on prod within one task), {@code tool-spike} (a tool on a connection at ≥ 4× its daily average, ≥ 20
   * calls), {@code slow-connection} (a connection's p95 ≥ 10× its usual median). Only the facts of its kind are set.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Unusual(String kind, String text, String traceId, String traceTitle, String client, String endpoint,
      String tool, String connection, String env, List<String> statements, Long withinS, Long calls, Long denied,
      Double dailyAverage, Double factor, Instant from, Instant to, Long p95Ms, Long usualMs, Instant since,
      Long timeoutMs) {}
}
