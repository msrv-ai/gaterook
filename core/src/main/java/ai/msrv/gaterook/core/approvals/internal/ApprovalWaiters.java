package ai.msrv.gaterook.core.approvals.internal;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Wake-ups for long polls on a request ({@code approval_status(wait_seconds)}, the first wait of a call, a held MRTR
 * round). In memory: whoever changes a request's status signals it after commit; waiters re-read the row in a short
 * transaction, so a missed signal costs at most one poll interval and never a wrong answer. No transaction or
 * database connection is held while waiting (ARCHITECTURE.md §5 step 11).
 */
@Component
public class ApprovalWaiters {

  private final Map<String, CompletableFuture<Void>> signals = new ConcurrentHashMap<>();

  /** The future the next change of {@code id} completes; take it before re-reading the row. */
  public CompletableFuture<Void> next(String id) {
    return signals.computeIfAbsent(id, k -> new CompletableFuture<>());
  }

  /** {@code id} changed (called after commit). */
  public void signal(String id) {
    CompletableFuture<Void> f = signals.remove(id);
    if (f != null) {
      f.complete(null);
    }
  }

  /** Waits for {@code signal} up to {@code millis}; returns early on a signal. */
  public static void await(CompletableFuture<Void> f, long millis) throws InterruptedException {
    if (millis <= 0) {
      return;
    }
    try {
      f.get(millis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      // Time to read the row again.
    }
  }
}
