package ai.msrv.gaterook.core.audit.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code completed} rows that couldn't be written when their call finished (ARCHITECTURE.md §5 step 11): an executed
 * mutation stays executed, so its row must not be lost, but nothing may run while the audit lags behind. Bounded and in
 * memory only; {@code core.log} names every row that came here. {@link BacklogFlusher} writes them in order.
 *
 * <p>Next to the rows, the writes that belong with them: the status of an approved request whose outcome is known but
 * couldn't be saved. They are retried after the rows, and they hold new calls just the same.
 */
@Component
public class AuditBacklog {

  /** At most this many rows wait; a full backlog only logs (new calls are refused long before it fills). */
  public static final int CAPACITY = 1_000;

  /** One waiting row and the trace it refreshes once written ({@code null}: none). */
  public record Pending(CallRow row, String traceId) {}

  /** A write retried until it returns normally; {@code what} names it in {@code core.log}. */
  public record Retry(String what, Runnable write) {}

  private final Deque<Pending> rows = new ArrayDeque<>();
  private final Deque<Retry> retries = new ArrayDeque<>();

  public synchronized boolean offer(CallRow row, String traceId) {
    if (rows.size() + retries.size() >= CAPACITY) {
      return false;
    }
    rows.addLast(new Pending(row, traceId));
    return true;
  }

  /** Queues {@code retry} after everything that waits; {@code false} when the backlog is full. */
  public synchronized boolean offer(Retry retry) {
    if (rows.size() + retries.size() >= CAPACITY) {
      return false;
    }
    retries.addLast(retry);
    return true;
  }

  public synchronized boolean isEmpty() {
    return rows.isEmpty() && retries.isEmpty();
  }

  public synchronized int size() {
    return rows.size() + retries.size();
  }

  synchronized Optional<Pending> peek() {
    return Optional.ofNullable(rows.peekFirst());
  }

  synchronized void remove(Pending written) {
    if (rows.peekFirst() == written) {
      rows.removeFirst();
    }
  }

  synchronized Optional<Retry> peekRetry() {
    return Optional.ofNullable(retries.peekFirst());
  }

  synchronized void remove(Retry done) {
    if (retries.peekFirst() == done) {
      retries.removeFirst();
    }
  }
}
