package ai.msrv.gaterook.core.invocation;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.core.connectors.CallContext;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * What protects resources and the core from a call (ARCHITECTURE.md A15, §5 steps 5 and 10; report 14 §7.4), with
 * Resilience4j core modules used programmatically (its annotations can't key on a connection):
 * <ul>
 *   <li>rate limits per principal (120/min, prod 60) and per principal × tool (30/min, prod 20), from the policy;</li>
 *   <li>a semaphore bulkhead per connection ({@code Connector.maxConcurrency()}, wait ≤ 200 ms) — fine on virtual
 *       threads;</li>
 *   <li>a circuit breaker per connection that counts only {@code RESOURCE_UNAVAILABLE} and {@code TIMEOUT}: a SQL
 *       syntax error says nothing about the database's health, and is <em>ignored</em>, not counted as a success
 *       (Resilience4j records an exception it doesn't count as a success unless told to ignore it);</li>
 *   <li>the deadline: the call runs on a virtual thread and {@code Future.get} waits until it; then the thread is
 *       interrupted, the connector's {@code onCancel} callbacks run and the call is a timeout.</li>
 * </ul>
 * Not transactional and holds no database connection: the wait for the resource must never pin one (§5).
 */
@Component
public class ToolGuard implements DisposableBean {

  /**
   * The answer of {@link #admit}: allowed, or refused with when to try again.
   *
   * @param principalCalls calls of the principal in the current minute, this one included (the audit's "14 of 120
   *                       calls per minute"); 0 when refused before it was counted
   * @param toolCalls      the same for the principal × tool
   */
  public record Admission(boolean allowed, long retryAfterMs, String scope, int principalCalls, int toolCalls) {

    public Admission(boolean allowed, long retryAfterMs, String scope) {
      this(allowed, retryAfterMs, scope, 0, 0);
    }
  }

  /** A connector call: may throw what the connector throws. */
  @FunctionalInterface
  public interface ConnectorCall<T> {
    T call() throws ConnectorException;
  }

  static final Duration BULKHEAD_WAIT = Duration.ofMillis(200);

  private final CircuitBreakerConfig breakerConfig;
  private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
  private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
  private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
  private final ExecutorService calls = Executors.newThreadPerTaskExecutor(
      Thread.ofVirtual().name("tool-call-", 0).factory());

  public ToolGuard() {
    this(Duration.ofSeconds(30));
  }

  /** @param openFor how long a tripped breaker refuses calls before it lets one through (tests shorten it) */
  ToolGuard(Duration openFor) {
    this.breakerConfig = CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(20)
        .minimumNumberOfCalls(5)
        .failureRateThreshold(50)
        .waitDurationInOpenState(openFor)
        .permittedNumberOfCallsInHalfOpenState(1)
        .recordException(ToolGuard::countsForBreaker)
        .ignoreException(e -> !countsForBreaker(e))
        .build();
  }

  static boolean countsForBreaker(Throwable e) {
    return e instanceof ConnectorException ce ? ce.countsForCircuitBreaker() : e instanceof TimeoutException;
  }

  // ── Rate limits ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * One call of {@code principalId} to {@code tool}: takes a permit from both limiters or refuses (step 5, before
   * anything else runs). The limits come from the environment's policy and may change between calls.
   */
  public Admission admit(String principalId, String tool, int perPrincipal, int perTool) {
    Admission p = take("p\0" + principalId, perPrincipal, "principal");
    if (!p.allowed()) {
      return p;
    }
    Admission t = take("t\0" + principalId + "\0" + tool, perTool, "tool");
    return t.allowed() ? new Admission(true, 0, null, p.principalCalls(), t.principalCalls()) : t;
  }

  /** One permit of {@code key}'s limiter; an allowed answer says in {@code principalCalls} how many it gave. */
  private Admission take(String key, int perMinute, String scope) {
    int limit = Math.max(1, perMinute);
    RateLimiter limiter = limiters.computeIfAbsent(key, k -> RateLimiter.of(k, RateLimiterConfig.custom()
        .limitForPeriod(limit).limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build()));
    if (limiter.getRateLimiterConfig().getLimitForPeriod() != limit) {
      limiter.changeLimitForPeriod(limit);
    }
    if (limiter.acquirePermission()) {
      int used = Math.max(1, limit - Math.max(0, limiter.getMetrics().getAvailablePermissions()));
      return new Admission(true, 0, null, used, 0);
    }
    long wait = 60_000;
    if (limiter.getMetrics() instanceof AtomicRateLimiter.AtomicRateLimiterMetrics m && m.getNanosToWait() > 0) {
      wait = TimeUnit.NANOSECONDS.toMillis(m.getNanosToWait()) + 1;
    }
    return new Admission(false, wait, scope);
  }

  /** A principal was revoked: its limiters go, so the registry doesn't grow forever. */
  public void forgetPrincipal(String principalId) {
    // Exactly this principal's: tok_a must not take tok_ab's limiters with it.
    limiters.keySet().removeIf(k -> k.equals("p\0" + principalId) || k.startsWith("t\0" + principalId + "\0"));
  }

  // ── Calls ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Runs {@code call} on {@code connectionId} under its bulkhead and circuit breaker until {@code timeout}.
   *
   * <p>The breaker records each call exactly once. Whoever settles it first records it: the call when it ends (an
   * outcome after the deadline counts as the timeout the caller was told about), or the caller when the deadline
   * passes while the call still runs; the other side then records nothing. A cancelled call that maps the interrupt
   * to {@code TIMEOUT} doesn't add a second failure, one that ignores it doesn't add a success. A call that never got
   * past the bulkhead never reached the resource and isn't recorded at all.
   *
   * @param ctx the call's context: cancelled when the deadline passes
   * @throws ConnectorException what the connector threw; {@code RESOURCE_UNAVAILABLE} when the connection is busy or
   *     its breaker is open; {@code TIMEOUT} when the deadline passed
   */
  public <T> T run(String connectionId, String connectionName, int maxConcurrency, Duration timeout, CallContext ctx,
      ConnectorCall<T> call) throws ConnectorException {
    CircuitBreaker breaker = breakers.computeIfAbsent(connectionId, id -> CircuitBreaker.of(id, breakerConfig));
    Bulkhead bulkhead = bulkhead(connectionId, maxConcurrency);
    long timeoutNanos = Math.max(1, timeout.toMillis()) * 1_000_000L;
    long submitted = System.nanoTime();
    Settlement settlement = new Settlement();
    Callable<T> recorded = () -> {
      breaker.acquirePermission();
      long started = System.nanoTime();
      if (!settlement.start(started)) {
        // The deadline passed while this waited for the bulkhead: nothing reached the resource.
        breaker.releasePermission();
        throw new CancellationException("past the deadline");
      }
      try {
        T result = call.call();
        if (settlement.settle()) {
          if (System.nanoTime() - submitted > timeoutNanos) {
            breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, new TimeoutException());
          } else {
            breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
          }
        }
        return result;
      } catch (Throwable t) {
        if (settlement.settle()) {
          Throwable outcome = System.nanoTime() - submitted > timeoutNanos ? new TimeoutException() : t;
          breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, outcome);
        }
        throw t;
      }
    };
    Future<T> future = calls.submit(Bulkhead.decorateCallable(bulkhead, recorded));
    try {
      return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      if (settlement.settleUnfinished()) {
        breaker.onError(System.nanoTime() - settlement.startedAt(), TimeUnit.NANOSECONDS, e);
      }
      future.cancel(true);
      if (ctx != null) {
        ctx.cancel();
      }
      throw ConnectorException.timeout("No answer from " + connectionName + " within " + seconds(timeout) + ".");
    } catch (InterruptedException e) {
      if (settlement.settleUnfinished()) {
        // Not the resource's fault: its permission goes back without a verdict.
        breaker.releasePermission();
      }
      future.cancel(true);
      if (ctx != null) {
        ctx.cancel();
      }
      Thread.currentThread().interrupt();
      throw new ConnectorException(ConnectorException.Kind.INTERNAL, "The call was cancelled.", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ConnectorException ce) {
        throw ce;
      }
      if (cause instanceof BulkheadFullException) {
        throw ConnectorException.unavailable(connectionName + " is busy: " + maxConcurrency + " calls are running. "
            + "Try again in a moment.", null);
      }
      if (cause instanceof CallNotPermittedException) {
        throw ConnectorException.unavailable(connectionName + " keeps failing, so calls are paused for a while. Try "
            + "again later.", null);
      }
      throw new ConnectorException(ConnectorException.Kind.INTERNAL, "Internal error in the connector", cause);
    }
  }

  /**
   * Who records one call: waiting (not past the bulkhead yet), running, or settled. Each step is one compare-and-set,
   * so the call and its caller never both record it.
   */
  private static final class Settlement {
    private static final int WAITING = 0;
    private static final int RUNNING = 1;
    private static final int SETTLED = 2;
    private final AtomicInteger state = new AtomicInteger(WAITING);
    private volatile long startedAt;

    /** The call got its permission at {@code nanos}; false if the caller already gave up on it. */
    boolean start(long nanos) {
      startedAt = nanos;
      return state.compareAndSet(WAITING, RUNNING);
    }

    /** The call ended: true if it records its own outcome. */
    boolean settle() {
      return state.compareAndSet(RUNNING, SETTLED);
    }

    /**
     * The caller gives up: true if the call is still running, which the caller then records (it started at
     * {@link #startedAt}); false when it never started or already recorded itself.
     */
    boolean settleUnfinished() {
      if (state.compareAndSet(WAITING, SETTLED)) {
        return false;
      }
      return state.compareAndSet(RUNNING, SETTLED);
    }

    long startedAt() {
      return startedAt;
    }
  }

  private Bulkhead bulkhead(String connectionId, int maxConcurrency) {
    int limit = Math.max(1, maxConcurrency);
    Bulkhead b = bulkheads.computeIfAbsent(connectionId, id -> Bulkhead.of(id, BulkheadConfig.custom()
        .maxConcurrentCalls(limit).maxWaitDuration(BULKHEAD_WAIT).build()));
    if (b.getBulkheadConfig().getMaxConcurrentCalls() != limit) {
      b.changeConfig(BulkheadConfig.custom().maxConcurrentCalls(limit).maxWaitDuration(BULKHEAD_WAIT).build());
    }
    return b;
  }

  /** The breaker's state of a connection ("calls paused"), {@code CLOSED} for one never called. */
  public CircuitBreaker.State state(String connectionId) {
    CircuitBreaker b = breakers.get(connectionId);
    return b == null ? CircuitBreaker.State.CLOSED : b.getState();
  }

  /** What the breaker of a connection counted (tests); {@code null} for one never called. */
  CircuitBreaker.Metrics metrics(String connectionId) {
    CircuitBreaker b = breakers.get(connectionId);
    return b == null ? null : b.getMetrics();
  }

  /** A connection changed or was removed: its breaker and bulkhead start fresh. */
  public void forget(String connectionId) {
    breakers.remove(connectionId);
    bulkheads.remove(connectionId);
  }

  private static String seconds(Duration d) {
    long ms = d.toMillis();
    return ms % 1000 == 0 ? (ms / 1000) + " s" : String.format(java.util.Locale.US, "%.1f s", ms / 1000.0);
  }

  @Override
  public void destroy() {
    calls.shutdownNow();
  }
}
