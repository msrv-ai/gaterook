package ai.msrv.gaterook.core.audit.internal;

import ai.msrv.gaterook.core.audit.TraceSummarizer;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.TraceTitleSource;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the {@code trace} table up to date (ARCHITECTURE.md §4 "Traces"): every committed audit row of a traced call
 * marks its trace, and a background worker summarizes marked traces at most every 250 ms. Off the call path on
 * purpose: a long task has hundreds of rows, and the agent must not wait for its own history.
 */
@Component
public class TraceBook implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(TraceBook.class);

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

  @SuppressWarnings("unchecked")
  void summarize(String traceId) {
    List<ToolCallEntity> rows = readTx.execute(s -> calls.findByTraceIdOrderByIdAsc(traceId));
    if (rows == null || rows.isEmpty()) {
      return;
    }
    TraceSummarizer.Summary summary = TraceSummarizer.summarize(TraceSummarizer.steps(rows));
    Map<String, Object> stored = json.convertValue(TraceSummarizer.toJson(summary), Map.class);
    writeTx.executeWithoutResult(s -> {
      TraceEntity trace = traces.findById(traceId).orElseGet(() -> new TraceEntity(traceId, summary.firstAt()));
      if (summary.title() != null) {
        trace.setTitle(summary.title(), TraceTitleSource.valueOf(summary.titleSource().toUpperCase(java.util.Locale.ROOT)));
      }
      trace.summarize(summary.lastAt(), summary.calls(), summary.client(), summary.endpoint(), stored);
      traces.save(trace);
    });
  }

  @Override
  public void destroy() throws InterruptedException {
    worker.shutdown();
    worker.awaitTermination(5, TimeUnit.SECONDS);
  }
}
