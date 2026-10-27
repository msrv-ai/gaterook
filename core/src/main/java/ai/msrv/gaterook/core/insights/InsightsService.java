package ai.msrv.gaterook.core.insights;

import ai.msrv.gaterook.core.audit.ActivityQueryParser;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.audit.AuditQueries.CallFacts;
import ai.msrv.gaterook.core.insights.internal.Aggregates;
import ai.msrv.gaterook.core.insights.internal.Detectors;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Insights (ARCHITECTURE.md §6 insights, A17 "Insights are computed from audit queries"): the calls of a range and of
 * the time before it, read from the audit in two queries — each call as its latest row says, narrowed by Activity's
 * query language — then counted here: the KPIs of this range and the previous one, calls over time, tools, clients,
 * approvals and their waits, denials by rule, latency and errors by connection, and the Unusual activity detectors.
 * Nothing is stored: the audit is the only source, and a new call shows at the next read.
 */
@Component
public class InsightsService {

  /** The longest range: the page's is 30 days; a quarter keeps the memory of one read bounded. */
  public static final Duration MAX_RANGE = Duration.ofDays(93);

  /** Calls one read takes per window at most (a heavy month is far below). */
  static final int LIMIT = 500_000;

  private final AuditQueries audit;
  private final Clock clock;
  private final ZoneId zone;

  InsightsService(AuditQueries audit, Clock clock) {
    this.audit = audit;
    this.clock = clock;
    this.zone = ZoneId.systemDefault();
  }

  /**
   * The numbers of {@code [from, to)} for the calls matching {@code q}.
   *
   * @param from the start, or {@code null}: a day before {@code to}
   * @param to   the end, or {@code null}: now
   * @throws ProblemException 422 for an invalid query ({@code /q}) or range ({@code /from}, {@code /to})
   */
  public Insights compute(String q, Instant from, Instant to) {
    ActivityQueryParser.parse(q);
    Instant end = to == null ? clock.instant() : to;
    Instant start = from == null ? end.minus(Duration.ofDays(1)) : from;
    if (!start.isBefore(end)) {
      throw ProblemException.unprocessable("Invalid range", "The range must end after it starts.", "/to");
    }
    Duration length = Duration.between(start, end);
    if (length.compareTo(MAX_RANGE) > 0) {
      throw ProblemException.unprocessable("Range too long", "Insights cover at most 93 days at a time.", "/from");
    }
    Instant previousFrom = start.minus(length);
    Instant baselineFrom = start.minus(Detectors.BASELINE);
    Instant historyFrom = previousFrom.isBefore(baselineFrom) ? previousFrom : baselineFrom;

    List<CallFacts> current = audit.callFacts(q, start, end, LIMIT);
    List<CallFacts> history = audit.callFacts(q, historyFrom, start, LIMIT);
    List<CallFacts> previous = history.stream().filter(c -> !c.at().isBefore(previousFrom)).toList();
    List<CallFacts> baseline = history.stream().filter(c -> !c.at().isBefore(baselineFrom)).toList();
    Optional<Instant> firstCall = audit.firstCallAt();
    boolean previousCovered = firstCall.isPresent() && !firstCall.get().isAfter(previousFrom);
    boolean baselineCovered = firstCall.isPresent() && !firstCall.get().isAfter(baselineFrom);

    Set<String> traceIds = new HashSet<>();
    current.forEach(c -> add(traceIds, c.traceId()));
    previous.forEach(c -> add(traceIds, c.traceId()));
    Map<String, AuditQueries.Trace> tasks = audit.tasks(traceIds);

    Insights.Totals totals = Aggregates.totals(current, tasks.keySet());
    Insights.Totals before = previousCovered ? Aggregates.totals(previous, tasks.keySet()) : null;
    long unfiltered = q == null || q.isBlank() ? totals.calls() : audit.decided(null, start, end);
    Map<String, java.time.Instant> received = audit.receivedAt(Aggregates.slowestCalls(current).stream()
        .map(CallFacts::callId).toList());

    return new Insights(start, end, firstCall.orElse(null), unfiltered, new Insights.Kpis(totals, before),
        audit.histogram(q, start, end, step(length)), Aggregates.topTools(current), Aggregates.byClient(current),
        Aggregates.decisions(current), Aggregates.approvals(current, received), Aggregates.deniedByRule(current),
        new Insights.Latency(Aggregates.latency(current)), Aggregates.errorsByConnection(current),
        Detectors.find(current, baseline, start, end, baselineCovered, tasks, zone));
  }

  /**
   * Hours per bar of Calls over time: one for a day, a few for a week, one day for a month — at most 48 bars, in steps
   * a person reads (1, 2, 3, 4, 6, 8, 12, 24 hours, then whole days).
   */
  static int step(Duration length) {
    long hours = Math.max(1, (length.toMinutes() + 59) / 60);
    for (int step : new int[] {1, 2, 3, 4, 6, 8, 12, 24}) {
      if ((hours + step - 1) / step <= 48) {
        return step;
      }
    }
    return (int) (24 * ((hours + 24 * 48 - 1) / (24 * 48)));
  }

  private static void add(Set<String> ids, String id) {
    if (id != null) {
      ids.add(id);
    }
  }
}
