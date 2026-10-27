package ai.msrv.gaterook.core.audit;

import ai.msrv.gaterook.core.audit.internal.CallRow;
import ai.msrv.gaterook.core.audit.internal.ChainHash;
import ai.msrv.gaterook.core.audit.internal.TraceBook;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.platform.BuildFlags;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The demo world's past (ARCHITECTURE.md §2.4, design/BLUEPRINT.md §9: a day of 1,287 calls, the week Insights compares
 * it with): calls written as the call path writes them — the same phases, decisions, timings and one hash chain — but
 * at the times the world says they happened. Only into an audit whose newest row is no newer than the first one given,
 * so that ids keep the order of time (the events table pages by id, the export cursor moves by id), and never in a
 * release build. A few hundred rows per transaction: the head of the chain is read once per transaction and followed
 * in memory, which the single writer connection makes safe.
 */
@Component
public class AuditBackfill {

  private static final int CHUNK = 500;

  /** One past call: who made it, how it is traced, and its rows in the order they were written. */
  public record PastCall(AuditWriter.Call call, List<PastRow> rows) {

    public PastCall {
      Objects.requireNonNull(call, "call");
      rows = List.copyOf(rows);
      if (rows.isEmpty()) {
        throw new IllegalArgumentException("A call has at least its received row");
      }
    }
  }

  /** One row of a past call, written at {@code at}: exactly one of the three is set. */
  public record PastRow(Instant at, AuditWriter.Received received, AuditWriter.Resolution resolution,
      AuditWriter.Completion completion) {

    public PastRow {
      Objects.requireNonNull(at, "at");
      int set = (received == null ? 0 : 1) + (resolution == null ? 0 : 1) + (completion == null ? 0 : 1);
      if (set != 1) {
        throw new IllegalArgumentException("A row is received, resolved or completed");
      }
    }

    public static PastRow received(Instant at, AuditWriter.Received r) {
      return new PastRow(at, r, null, null);
    }

    public static PastRow resolved(Instant at, AuditWriter.Resolution r) {
      return new PastRow(at, null, r, null);
    }

    public static PastRow completed(Instant at, AuditWriter.Completion c) {
      return new PastRow(at, null, null, c);
    }
  }

  private record Timed(Instant at, int order, CallRow row) {}

  private final AuditWriter writer;
  private final ToolCallRepository calls;
  private final TraceBook traces;
  private final TransactionTemplate writeTx;

  AuditBackfill(AuditWriter writer, ToolCallRepository calls, TraceBook traces, PlatformTransactionManager tx) {
    this.writer = writer;
    this.calls = calls;
    this.traces = traces;
    this.writeTx = new TransactionTemplate(tx);
  }

  /**
   * Writes {@code past} in the order of time; the traces of calls that came with the client's trace id are
   * summarized afterwards, like after any call.
   *
   * @return the rows written
   * @throws IllegalStateException in a release build, or when the audit already has a row newer than the first given
   */
  public int write(List<PastCall> past) {
    if (BuildFlags.RELEASE) {
      throw new IllegalStateException("A release build writes no past calls");
    }
    List<Timed> rows = new ArrayList<>();
    Set<String> traced = new LinkedHashSet<>();
    for (PastCall p : past) {
      for (PastRow r : p.rows()) {
        CallRow row = r.received() != null ? writer.receivedRow(p.call(), r.received(), r.at())
            : r.resolution() != null ? writer.resolvedRow(p.call(), r.resolution(), r.at())
            : writer.completedRow(p.call(), r.completion(), r.at());
        rows.add(new Timed(row.createdAt(), rows.size(), row));
      }
      if (p.call().traceFromClient()) {
        traced.add(p.call().traceId());
      }
    }
    if (rows.isEmpty()) {
      return 0;
    }
    rows.sort(Comparator.comparing(Timed::at).thenComparingInt(Timed::order));
    Instant first = rows.getFirst().at();
    for (int from = 0; from < rows.size(); from += CHUNK) {
      List<Timed> chunk = rows.subList(from, Math.min(rows.size(), from + CHUNK));
      boolean firstChunk = from == 0;
      writeTx.executeWithoutResult(s -> {
        ToolCallEntity head = calls.findTopByOrderByIdDesc().orElse(null);
        if (firstChunk && head != null && head.getCreatedAt().isAfter(first)) {
          throw new IllegalStateException("The audit has calls newer than the past being written");
        }
        String prev = head == null ? ChainHash.GENESIS : head.getRowSha256();
        for (Timed t : chunk) {
          prev = calls.save(t.row().chained(prev)).getRowSha256();
        }
      });
    }
    traced.forEach(traces::touch);
    return rows.size();
  }
}
