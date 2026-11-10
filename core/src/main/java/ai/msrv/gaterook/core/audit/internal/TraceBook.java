package ai.msrv.gaterook.core.audit.internal;

import ai.msrv.gaterook.core.audit.TraceSummarizer;
import ai.msrv.gaterook.core.audit.internal.persistence.CallDecision;
import ai.msrv.gaterook.core.audit.internal.persistence.CallPhase;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceTitleSource;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the {@code trace} table up to date (ARCHITECTURE.md §4 "Traces"): every committed audit row of a traced call
 * marks its trace, and a background worker summarizes marked traces at most every 250 ms. Off the call path on
 * purpose: a long task has hundreds of rows, and the agent must not wait for its own history.
 *
 * <p>The trace id is the client's, and a client may keep one for a whole session, so the work a new row causes must not
 * grow with its trace. A trace of up to {@value TraceSummarizer#MAX_CALLS} calls (and {@value #ROWS_PER_READ} rows) is
 * summarized from its rows each time. A longer one is <em>cut</em>: its phases describe its first calls, and from then
 * on a run reads only the rows after the last one it counted and adds them to the counts (calls, decisions, time
 * waited for people, last call). The phases are rebuilt, from a bounded read, only when a row of one of the calls they
 * describe arrives. The stored summary says how many calls its phases describe ({@code timelineCalls}).
 */
@Component
public class TraceBook implements DisposableBean {

  /** Rows one run reads at most: the phases' calls with their approvals and results, with room to spare. */
  static final int ROWS_PER_READ = 4 * TraceSummarizer.MAX_CALLS;

  /** Calls of a cut trace's phases that may still change and are read again, by call, when the phases are rebuilt. */
  static final int REREAD_CALLS = 100;

  /** Summary key: the last row the counts include. */
  static final String THROUGH = "throughId";
  /** Summary key of a cut trace: the first row of the last call its phases describe. */
  static final String TIMELINE_END = "timelineEndId";
  /** Summary key: the calls the phases describe, when fewer than the trace has. */
  static final String TIMELINE_CALLS = "timelineCalls";

  private static final Logger log = LoggerFactory.getLogger(TraceBook.class);
  private static final List<String> DECISIONS = List.of("allowed", "approved", "pending", "denied", "rejected",
      "expired", "error");

  private final ToolCallRepository calls;
  private final TraceRepository traces;
  private final JsonMapper json;
  private final TransactionTemplate readTx;
  private final TransactionTemplate writeTx;
  private final Set<String> marked = new LinkedHashSet<>();
  /** Traces to refresh only if they are traces already (guarded by {@code marked}). */
  private final Set<String> maybe = new LinkedHashSet<>();
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("trace-summarizer").factory());
  private boolean scheduled;

  TraceBook(ToolCallRepository calls, TraceRepository traces, JsonMapper json, PlatformTransactionManager tx) {
    this.calls = calls;
    this.traces = traces;
    this.json = json;
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
    this.writeTx = new TransactionTemplate(tx);
  }

  /** A row of {@code traceId} committed: summarize the trace soon. */
  public void touch(String traceId) {
    synchronized (marked) {
      marked.add(traceId);
      schedule();
    }
  }

  /** Holding {@code marked}'s lock. */
  private void schedule() {
    if (!scheduled && !worker.isShutdown()) {
      scheduled = true;
      worker.schedule(this::drain, 250, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * A row of {@code traceId} committed for a call whose origin this writer doesn't know (another call that reused an
   * approval request): its trace is refreshed only if it is a trace already — a call's own one-span trace is no task.
   */
  public void touchIfKnown(String traceId) {
    // Checked by the worker: this runs in a transaction's afterCommit, where no query should run.
    synchronized (marked) {
      maybe.add(traceId);
      schedule();
    }
  }

  /** Summarizes everything marked now (tests; the worker otherwise). */
  public void flush() {
    drain();
  }

  private void drain() {
    List<String> todo;
    List<String> ifKnown;
    synchronized (marked) {
      todo = List.copyOf(marked);
      ifKnown = maybe.stream().filter(id -> !marked.contains(id)).toList();
      marked.clear();
      maybe.clear();
      scheduled = false;
    }
    for (String traceId : ifKnown) {
      try {
        if (Boolean.TRUE.equals(readTx.execute(s -> traces.existsById(traceId)))) {
          summarize(traceId);
        }
      } catch (RuntimeException e) {
        log.warn("Couldn't summarize trace {}: {}", traceId, e.toString());
      }
    }
    for (String traceId : todo) {
      try {
        summarize(traceId);
      } catch (RuntimeException e) {
        log.warn("Couldn't summarize trace {}: {}", traceId, e.toString());
      }
    }
  }

  void summarize(String traceId) {
    TraceEntity stored = readTx.execute(s -> traces.findById(traceId).orElse(null));
    Map<String, Object> was = stored == null ? null : stored.getSummary();
    if (was != null && was.get(TIMELINE_END) instanceof Number end && was.get(THROUGH) instanceof Number through) {
      count(traceId, stored, was, end.longValue(), through.longValue());
      return;
    }
    List<ToolCallEntity> rows = readTx.execute(s -> calls.findByTraceIdOrderByIdAsc(traceId,
        PageRequest.of(0, ROWS_PER_READ + 1)));
    if (rows == null || rows.isEmpty()) {
      return;
    }
    List<TraceSummarizer.Step> steps = TraceSummarizer.steps(rows);
    if (rows.size() <= ROWS_PER_READ && steps.size() <= TraceSummarizer.MAX_CALLS) {
      TraceSummarizer.Summary summary = TraceSummarizer.summarize(steps);
      Map<String, Object> out = stored(summary);
      out.put(THROUGH, rows.getLast().getId());
      save(traceId, summary, summary.lastAt(), summary.calls(), out);
      return;
    }
    // Longer than the phases describe: they keep its first calls, and the rows after this read are counted.
    List<ToolCallEntity> window = rows.subList(0, Math.min(rows.size(), ROWS_PER_READ));
    Counts counts = Counts.of(TraceSummarizer.summarize(TraceSummarizer.steps(window)));
    long end = timelineEnd(window);
    TraceSummarizer.Summary timeline = timeline(traceId, window, end);
    Map<String, Object> out = stored(timeline);
    out.put(TIMELINE_CALLS, timeline.timelineCalls());
    counts.writeTo(out, timeline.firstAt());
    out.put(TIMELINE_END, end);
    out.put(THROUGH, window.getLast().getId());
    save(traceId, timeline, counts.lastAt, counts.calls, out);
    if (rows.size() > ROWS_PER_READ) {
      touch(traceId);
    }
  }

  /** A cut trace: counts the rows after {@code through}, a bounded batch per run. */
  private void count(String traceId, TraceEntity stored, Map<String, Object> was, long end, long through) {
    List<ToolCallEntity> rows = readTx.execute(s -> calls.findByTraceIdAndIdGreaterThanOrderByIdAsc(traceId, through,
        PageRequest.of(0, ROWS_PER_READ + 1)));
    if (rows == null || rows.isEmpty()) {
      return;
    }
    boolean more = rows.size() > ROWS_PER_READ;
    List<ToolCallEntity> batch = more ? rows.subList(0, ROWS_PER_READ) : rows;
    Counts counts = Counts.of(stored, was);
    Map<String, Seen> seen = new HashMap<>();
    boolean described = false;
    for (ToolCallEntity r : batch) {
      String decision = KebabCaseEnumConverter.idOf(r.getDecision());
      long wait = waited(r);
      Seen before = seen.get(r.getCallId());
      if (before == null && r.getPhase() != CallPhase.RECEIVED) {
        before = earlier(r);
      }
      if (before == null) {
        counts.calls++;
        counts.add(decision, 1);
        counts.waited += wait;
        seen.put(r.getCallId(), new Seen(decision, wait, r.getId()));
      } else {
        // A later row of a call already counted: its decision changes, and it may have waited longer.
        counts.add(before.decision(), -1);
        counts.add(decision, 1);
        counts.waited += Math.max(0, wait - before.waited());
        seen.put(r.getCallId(), new Seen(decision, Math.max(wait, before.waited()), before.firstId()));
        described |= before.firstId() <= end;
      }
      if (r.getCreatedAt().isAfter(counts.lastAt)) {
        counts.lastAt = r.getCreatedAt();
      }
    }
    TraceSummarizer.Summary timeline = null;
    if (described) {
      List<ToolCallEntity> window = readTx.execute(s -> calls.findByTraceIdOrderByIdAsc(traceId,
          PageRequest.of(0, ROWS_PER_READ)));
      timeline = window == null ? null : timeline(traceId, window, end);
    }
    Map<String, Object> out;
    if (timeline != null) {
      out = stored(timeline);
      out.put(TIMELINE_CALLS, timeline.timelineCalls());
    } else {
      out = new LinkedHashMap<>(was);
    }
    counts.writeTo(out, stored.getFirstAt());
    out.put(TIMELINE_END, end);
    out.put(THROUGH, batch.getLast().getId());
    save(traceId, timeline, counts.lastAt, counts.calls, out);
    if (more) {
      touch(traceId);
    }
  }

  /** The first row of the last call the phases describe: the {@value TraceSummarizer#MAX_CALLS}th of the window. */
  private static long timelineEnd(List<ToolCallEntity> window) {
    Set<String> started = new HashSet<>();
    long end = window.getFirst().getId();
    for (ToolCallEntity r : window) {
      if (started.size() < TraceSummarizer.MAX_CALLS && started.add(r.getCallId())) {
        end = r.getId();
      }
    }
    return end;
  }

  /**
   * The phases of the calls that started at or before {@code end}, from the window's rows and, for a call that may
   * still change (it waits for a person, or runs), from all of its rows: at most {@value #REREAD_CALLS} such calls.
   * {@code null} when the window holds none of them any more (retention removed the oldest rows).
   */
  private TraceSummarizer.Summary timeline(String traceId, List<ToolCallEntity> window, long end) {
    Set<String> described = new HashSet<>();
    for (ToolCallEntity r : window) {
      if (r.getId() <= end) {
        described.add(r.getCallId());
      }
    }
    List<ToolCallEntity> rows = new ArrayList<>();
    Set<Long> have = new HashSet<>();
    Map<String, ToolCallEntity> last = new LinkedHashMap<>();
    for (ToolCallEntity r : window) {
      if (described.contains(r.getCallId())) {
        rows.add(r);
        have.add(r.getId());
        last.put(r.getCallId(), r);
      }
    }
    int reread = 0;
    for (ToolCallEntity r : last.values()) {
      if (reread == REREAD_CALLS) {
        break;
      }
      if (mayChange(r)) {
        reread++;
        List<ToolCallEntity> all = readTx.execute(s -> calls.findByCallIdOrderByIdAsc(r.getCallId()));
        for (ToolCallEntity x : all == null ? List.<ToolCallEntity>of() : all) {
          if (traceId.equals(x.getTraceId()) && have.add(x.getId())) {
            rows.add(x);
          }
        }
      }
    }
    return rows.isEmpty() ? null : TraceSummarizer.summarize(TraceSummarizer.steps(rows));
  }

  /** The call of {@code row} has rows to come: it waits for a person, or it was let through and runs. */
  private static boolean mayChange(ToolCallEntity row) {
    CallDecision d = row.getDecision();
    return row.getPhase() != CallPhase.COMPLETED
        && (d == CallDecision.PENDING || d == CallDecision.ALLOWED || d == CallDecision.APPROVED);
  }

  /** What the rows of {@code row}'s call before it said, or {@code null} when it has none. */
  private Seen earlier(ToolCallEntity row) {
    List<ToolCallEntity> all = readTx.execute(s -> calls.findByCallIdOrderByIdAsc(row.getCallId()));
    Seen out = null;
    for (ToolCallEntity x : all == null ? List.<ToolCallEntity>of() : all) {
      if (x.getId() >= row.getId()) {
        break;
      }
      out = new Seen(KebabCaseEnumConverter.idOf(x.getDecision()),
          Math.max(waited(x), out == null ? 0 : out.waited()), out == null ? x.getId() : out.firstId());
    }
    return out;
  }

  private static long waited(ToolCallEntity row) {
    return row.getApprovalWaitMs() == null ? 0 : row.getApprovalWaitMs();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> stored(TraceSummarizer.Summary summary) {
    return new LinkedHashMap<>(json.convertValue(TraceSummarizer.toJson(summary), Map.class));
  }

  /** Saves the trace; {@code timeline} {@code null} keeps its title, client and endpoint. */
  private void save(String traceId, TraceSummarizer.Summary timeline, Instant lastAt, int callCount,
      Map<String, Object> summary) {
    writeTx.executeWithoutResult(s -> {
      TraceEntity trace = traces.findById(traceId).orElseGet(() -> new TraceEntity(traceId, timeline.firstAt()));
      if (timeline != null && timeline.title() != null) {
        trace.setTitle(timeline.title(), TraceTitleSource.valueOf(timeline.titleSource().toUpperCase(
            java.util.Locale.ROOT)));
      }
      trace.summarize(lastAt, callCount, timeline == null ? trace.getClient() : timeline.client(),
          timeline == null ? trace.getEndpointSlug() : timeline.endpoint(), summary);
      traces.save(trace);
    });
  }

  /** What one call's counted rows said: its decision, the longest it waited for a person, its first row. */
  private record Seen(String decision, long waited, long firstId) {}

  /** A trace's counts: every call, not only those its phases describe. */
  private static final class Counts {
    int calls;
    Instant lastAt;
    long waited;
    final Map<String, Integer> decisions = new LinkedHashMap<>();

    static Counts of(TraceSummarizer.Summary s) {
      Counts c = new Counts();
      c.calls = s.calls();
      c.lastAt = s.lastAt();
      c.waited = s.waitedForPeopleMs();
      c.decisions.putAll(s.decisions());
      return c;
    }

    static Counts of(TraceEntity stored, Map<String, Object> was) {
      Counts c = new Counts();
      c.calls = stored.getCalls();
      c.lastAt = stored.getLastAt();
      c.waited = was.get("waitedForPeopleMs") instanceof Number n ? n.longValue() : 0;
      if (was.get("decisions") instanceof Map<?, ?> m) {
        m.forEach((k, v) -> {
          if (v instanceof Number n) {
            c.decisions.put(String.valueOf(k), n.intValue());
          }
        });
      }
      return c;
    }

    void add(String decision, int n) {
      decisions.merge(decision, n, Integer::sum);
    }

    void writeTo(Map<String, Object> out, Instant firstAt) {
      Map<String, Integer> ordered = new LinkedHashMap<>();
      for (String d : DECISIONS) {
        int n = decisions.getOrDefault(d, 0);
        if (n > 0) {
          ordered.put(d, n);
        }
      }
      out.put("waiting", ordered.getOrDefault("pending", 0) > 0);
      out.put("waitedForPeopleMs", waited);
      out.put("durationMs", Duration.between(firstAt, lastAt).toMillis());
      out.put("decisions", ordered);
    }
  }

  @Override
  public void destroy() throws InterruptedException {
    worker.shutdown();
    worker.awaitTermination(5, TimeUnit.SECONDS);
  }
}
