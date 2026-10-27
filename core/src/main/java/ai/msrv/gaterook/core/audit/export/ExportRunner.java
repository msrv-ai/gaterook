package ai.msrv.gaterook.core.audit.export;

import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.AuditExportRepository;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallEntity;
import ai.msrv.gaterook.core.audit.internal.persistence.ToolCallRepository;
import ai.msrv.gaterook.core.platform.HmacKeys;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * One run of a destination (ARCHITECTURE.md §10 K5: "export cursors neither lose nor duplicate rows"): batch after
 * batch of agent call rows after its cursor, each in three steps.
 * <ol>
 *   <li><b>Claim</b> — in a write transaction, the batch's last row id goes into {@code pending_to_id} before anything
 *       leaves the core; a batch already pending (a stop or a failure before the cursor moved) is taken as it is. The
 *       position is under the row's MAC: claim and advance check the row and sign the position they write.</li>
 *   <li><b>Deliver</b> — the rows after the cursor up to that id, read again from the audit (they are immutable) with a
 *       signed head, go out under a name made of that range: a retry sends the very same rows under the same name.</li>
 *   <li><b>Advance</b> — the cursor moves to the batch's end in one conditional update; if the destination was deleted
 *       or another run finished the batch meanwhile, nothing changes.</li>
 * </ol>
 * Rows join the audit with ids in commit order (one writer connection), so no row can appear behind a cursor. A
 * failure keeps the batch and the cursor where they are, and marks the destination {@code failing} with the error,
 * masked with everything the run opened. Never holds a transaction while it talks to a destination.
 */
@Component
public class ExportRunner {

  private static final Logger log = LoggerFactory.getLogger(ExportRunner.class);

  /** What one run did. */
  public record Outcome(int batches, long rows, long bytes, boolean more, String error) {

    public boolean failed() {
      return error != null;
    }
  }

  @PersistenceContext
  private EntityManager em;

  private final Destinations destinations;
  private final AuditExportRepository exports;
  private final ToolCallRepository calls;
  private final ExportSenders senders;
  private final HmacKeys keys;
  private final Clock clock;
  private final int siemBatch;
  private final int s3Batch;
  private final int bigQueryBatch;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  /**
   * Tests only: runs after a batch reached its destination and before the cursor moves, where a stop of the core
   * would leave the batch pending. Throwing here stands for that stop.
   */
  volatile Consumer<Batch> afterDelivery = b -> {};

  ExportRunner(Destinations destinations, AuditExportRepository exports, ToolCallRepository calls,
      ExportSenders senders, HmacKeys keys, Clock clock, PlatformTransactionManager tx,
      @Value("${gateway.audit.export.batch.siem:1000}") int siemBatch,
      @Value("${gateway.audit.export.batch.s3:10000}") int s3Batch,
      @Value("${gateway.audit.export.batch.bigquery:500}") int bigQueryBatch) {
    this.destinations = destinations;
    this.exports = exports;
    this.calls = calls;
    this.senders = senders;
    this.keys = keys;
    this.clock = clock;
    this.siemBatch = siemBatch;
    this.s3Batch = s3Batch;
    this.bigQueryBatch = bigQueryBatch;
    this.writeTx = new TransactionTemplate(tx);
    this.readTx = new TransactionTemplate(tx);
    this.readTx.setReadOnly(true);
  }

  /**
   * Exports what {@code d} hasn't received yet, batch after batch, for up to {@code budget}; the caller serializes
   * runs of one destination.
   */
  public Outcome run(Destinations.Destination d, Duration budget) {
    Instant until = clock.instant().plus(budget);
    int batches = 0;
    long rows = 0;
    long bytes = 0;
    try (ExportSenders.Opened opened = openFor(d)) {
      if (opened == null) {
        return new Outcome(0, 0, 0, false, null);
      }
      try {
        while (true) {
          Optional<Range> range = claim(d.id());
          if (range.isEmpty()) {
            return new Outcome(batches, rows, bytes, false, null);
          }
          Range r = range.get();
          Batch batch;
          try {
            batch = batch(r);
          } catch (IllegalStateException locked) {
            return failed(d, "The master key is locked: exports wait until it opens, since each one carries the "
                + "signed head of the audit", opened.taint(), batches, rows, bytes);
          }
          try {
            bytes += opened.sender().send(batch);
          } catch (ExportException e) {
            return failed(d, e.getMessage(), opened.taint(), batches, rows, bytes);
          }
          afterDelivery.accept(batch);
          Instant now = clock.instant();
          Integer moved = writeTx.execute(s -> advance(d.id(), r.to(), now));
          if (moved == null || moved == 0) {
            // Deleted meanwhile, another run finished this batch, or the row was changed outside the core (its
            // view raised the alert): nothing of ours to move, the batch stays in flight.
            return new Outcome(batches, rows, bytes, false, null);
          }
          batches++;
          rows += batch.rows().size();
          if (clock.instant().isAfter(until)) {
            return new Outcome(batches, rows, bytes, true, null);
          }
        }
      } catch (RuntimeException e) {
        // Masked with what the run opened, like a destination's own error.
        log.warn("Audit export {} failed unexpectedly", d.name(), e);
        return failed(d, "The export failed: " + e.getMessage(), opened.taint(), batches, rows, bytes);
      }
    } catch (ExportException e) {
      return failed(d, e.getMessage(), new Taint(), batches, rows, bytes);
    } catch (RuntimeException e) {
      log.warn("Audit export {} failed unexpectedly", d.name(), e);
      return failed(d, "The export failed: " + e.getMessage(), new Taint(), batches, rows, bytes);
    }
  }

  /**
   * Sends {@code rows} once, outside the cursor (Activity › Send to SIEM): the selected calls with a head that names
   * the chain's head at the time of sending.
   *
   * @return the bytes sent
   */
  public long sendOnce(Destinations.Destination d, List<ToolCallEntity> rows, int perBatch) throws ExportException {
    try (ExportSenders.Opened opened = senders.open(d)) {
      long bytes = 0;
      String key = "activity-" + clock.instant().toEpochMilli();
      for (int i = 0; i < rows.size() || i == 0; i += perBatch) {
        List<ToolCallEntity> part = rows.subList(Math.min(i, rows.size()), Math.min(rows.size(), i + perBatch));
        ToolCallEntity head = readTx.execute(s -> calls.findTopByOrderByIdDesc().orElse(null));
        List<ObjectNode> records = part.stream().map(ExportRecords::row).toList();
        ObjectNode signed;
        try {
          signed = ExportRecords.head(keys, null, head == null ? 0 : head.getId(), records.size(), null,
              head == null ? null : head.getRowSha256(), clock.instant());
        } catch (IllegalStateException locked) {
          throw new ExportException("The master key is locked: nothing is sent without the signed head of the audit");
        }
        Instant first = part.isEmpty() ? clock.instant() : part.getFirst().getCreatedAt();
        try {
          bytes += opened.sender().send(new Batch(key + "-" + i, records, signed, first));
        } catch (ExportException e) {
          throw new ExportException(mask(e.getMessage(), opened.taint()), e);
        }
        if (rows.size() <= perBatch) {
          break;
        }
      }
      return bytes;
    }
  }

  /** Test: the destination checked without exporting. */
  public String test(Destinations.Destination d) throws ExportException {
    try (ExportSenders.Opened opened = senders.open(d)) {
      try {
        return opened.sender().test();
      } catch (ExportException e) {
        throw new ExportException(mask(e.getMessage(), opened.taint()), e);
      }
    }
  }

  /** The rows a destination may take per batch. */
  public int batchSize(Destinations.Destination d) {
    return switch (d.kind()) {
      case SIEM -> siemBatch;
      case S3 -> s3Batch;
      case BIGQUERY -> bigQueryBatch;
    };
  }

  /** The cursor range of a batch: rows with {@code from ≤ id ≤ to}. */
  record Range(long from, long to) {}

  private ExportSenders.Opened openFor(Destinations.Destination d) throws ExportException {
    // Nothing to send and nothing pending: no secret is opened, no credential asked for.
    Destinations.Destination now = destinations.find(d.id()).orElse(null);
    if (now == null || !now.runs()) {
      return null;
    }
    if (now.pendingToId() == null && !hasRowsAfter(now.cursor())) {
      Instant at = clock.instant();
      writeTx.executeWithoutResult(s -> exports.upToDate(d.id(), at));
      return null;
    }
    return senders.open(now);
  }

  private boolean hasRowsAfter(long cursor) {
    return Boolean.TRUE.equals(readTx.execute(s -> !calls.findByIdGreaterThanOrderByIdAsc(cursor,
        PageRequest.ofSize(1)).isEmpty()));
  }

  /**
   * The batch in flight, or a new one claimed now; empty when the destination has everything (or is gone, or its row
   * doesn't verify). The row is read and checked in the claim's own write transaction, and the claim signs the new
   * position over what it checked.
   */
  private Optional<Range> claim(String id) {
    return Optional.ofNullable(writeTx.execute(s -> {
      AuditExportEntity e = exports.findById(id).orElse(null);
      Destinations.Destination d = e == null ? null : destinations.view(e);
      if (d == null || !d.runs()) {
        return null;
      }
      long from = d.cursor() + 1;
      if (d.pendingToId() != null) {
        return new Range(from, d.pendingToId());
      }
      List<Long> ids = em.createQuery("select c.id from ToolCallEntity c where c.id > :after order by c.id",
          Long.class).setParameter("after", d.cursor()).setFirstResult(batchSize(d) - 1).setMaxResults(1)
          .getResultList();
      Long to = ids.isEmpty() ? em.createQuery("select max(c.id) from ToolCallEntity c where c.id > :after",
          Long.class).setParameter("after", d.cursor()).getSingleResult() : ids.getFirst();
      if (to == null) {
        exports.upToDate(id, clock.instant());
        return null;
      }
      byte[] signed = destinations.position(e, e.getLastExportedId(), to);
      return exports.claim(id, to, clock.instant(), signed, e.getVersion()) == 1 ? new Range(from, to) : null;
    }));
  }

  /**
   * Moves the cursor to the end of the batch that reached the destination, signing the new position — only over a row
   * that still verifies, in the same write transaction: a row changed outside meanwhile isn't vouched for by moving
   * its cursor.
   *
   * @return the rows changed: 0 when the destination is gone, the batch isn't the one in flight or the row doesn't
   *     verify
   */
  private int advance(String id, long to, Instant now) {
    AuditExportEntity e = exports.findById(id).orElse(null);
    if (e == null || !Long.valueOf(to).equals(e.getPendingToId()) || !destinations.view(e).verified()) {
      return 0;
    }
    return exports.advance(id, to, now, destinations.position(e, to, null), e.getVersion());
  }

  /** The rows of {@code r}, read again, and their signed head. */
  private Batch batch(Range r) {
    List<ToolCallEntity> rows = readTx.execute(s -> {
      List<ToolCallEntity> out = new ArrayList<>();
      long after = r.from() - 1;
      while (true) {
        List<ToolCallEntity> page = calls.findByIdGreaterThanOrderByIdAsc(after, PageRequest.ofSize(1_000));
        for (ToolCallEntity e : page) {
          if (e.getId() > r.to()) {
            return out;
          }
          out.add(e);
        }
        if (page.size() < 1_000) {
          return out;
        }
        after = page.getLast().getId();
      }
    });
    List<ObjectNode> records = rows.stream().map(ExportRecords::row).toList();
    ToolCallEntity first = rows.isEmpty() ? null : rows.getFirst();
    ToolCallEntity last = rows.isEmpty() ? null : rows.getLast();
    ObjectNode head = ExportRecords.head(keys, r.from(), last == null ? r.to() : last.getId(), rows.size(),
        first == null ? null : first.getPrevSha256(), last == null ? null : last.getRowSha256(), clock.instant());
    return new Batch(String.format("%012d-%012d", r.from(), r.to()), records, head,
        first == null ? clock.instant() : first.getCreatedAt());
  }

  private Outcome failed(Destinations.Destination d, String error, Taint taint, int batches, long rows, long bytes) {
    String masked = mask(error, taint);
    Instant now = clock.instant();
    try {
      writeTx.executeWithoutResult(s -> exports.failed(d.id(), masked, now));
    } catch (RuntimeException e) {
      log.warn("Couldn't record the failure of audit export {}: {}", d.name(), e.toString());
    }
    log.warn("Audit export {} to {} failed: {}", d.name(), d.target(), masked);
    return new Outcome(batches, rows, bytes, false, masked);
  }

  /** An error as stored and shown: what the run opened masked, secret-looking text masked, at most 1,000 characters. */
  static String mask(String error, Taint taint) {
    String text = error == null || error.isBlank() ? "The export failed" : error;
    String masked = Masker.standard().text(text, taint, MaskPolicy.SECRETS_ONLY).text();
    return masked.length() <= 1_000 ? masked : masked.substring(0, 999) + "…";
  }
}
