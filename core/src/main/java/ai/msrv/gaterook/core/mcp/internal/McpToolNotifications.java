package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.core.connectors.ConnectionNotified;
import ai.msrv.gaterook.core.endpoints.EndpointCatalogInvalidated;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Wakes MCP streams after a committed change invalidates an endpoint snapshot, or when a connection's resource says
 * something outside any call ({@link ConnectionNotified}: an MCP server's resources or prompts changed, a resource was
 * updated). Each stream has one pending wakeup: bursts coalesce, and a slow client never blocks the committing thread;
 * what a connection said waits in a short queue of its own (the oldest goes when it is full). The stream's own request
 * thread reads the new catalog and compares its hash, outside the transaction that published the invalidation, and
 * decides which notices concern its endpoint and its subscriptions.
 */
@Component
public class McpToolNotifications implements SmartLifecycle {

  /**
   * Streams one token or grant holds at once: a client and its reconnect, not a way to fill the connections. A
   * further one closes the principal's oldest: a client that reconnects after a drop the gateway hasn't noticed yet
   * (it learns of a closed client at its next write) gets through, and the principal never holds more than this.
   */
  public static final int PER_PRINCIPAL = 2;

  /** Streams the gateway holds at once, all principals together; more are refused. */
  public static final int OVERALL = 64;

  private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
  /** Each principal's open streams, oldest first. */
  private final Map<String, Deque<Subscription>> perPrincipal = new HashMap<>();
  private boolean running = true;

  /** A stream for tests and callers without a principal: counted toward the overall limit only. */
  public Subscription subscribe(String endpointId) {
    return subscribe(endpointId, null).orElseGet(() -> {
      Subscription closed = new Subscription(endpointId, null);
      closed.close();
      return closed;
    });
  }

  /**
   * A stream of {@code principal} for {@code endpointId}, closing the principal's oldest when it holds
   * {@value #PER_PRINCIPAL} already; empty when the gateway holds {@value #OVERALL}. A subscription after shutdown
   * comes closed.
   */
  public synchronized Optional<Subscription> subscribe(String endpointId, String principal) {
    if (running && principal != null) {
      Deque<Subscription> own = perPrincipal.get(principal);
      while (own != null && own.size() >= PER_PRINCIPAL) {
        own.peekFirst().close();
        own = perPrincipal.get(principal);
      }
    }
    if (running && subscriptions.size() >= OVERALL) {
      return Optional.empty();
    }
    Subscription subscription = new Subscription(endpointId, principal);
    if (running) {
      subscriptions.add(subscription);
      if (principal != null) {
        perPrincipal.computeIfAbsent(principal, k -> new ArrayDeque<>()).addLast(subscription);
      }
      // Refresh the client's unknown cached view on open, including changes missed before registration.
      subscription.signal();
    } else {
      subscription.close();
    }
    return Optional.of(subscription);
  }

  private synchronized void released(Subscription subscription) {
    if (subscriptions.remove(subscription) && subscription.principal != null) {
      Deque<Subscription> own = perPrincipal.get(subscription.principal);
      if (own != null) {
        own.remove(subscription);
        if (own.isEmpty()) {
          perPrincipal.remove(subscription.principal);
        }
      }
    }
  }

  @EventListener
  public void invalidated(EndpointCatalogInvalidated event) {
    for (Subscription subscription : subscriptions) {
      if (event.endpointId() == null || subscription.endpointId.equals(event.endpointId())) {
        subscription.catalog = true;
        subscription.signal();
      }
    }
  }

  @EventListener
  public void notified(ConnectionNotified event) {
    for (Subscription subscription : subscriptions) {
      subscription.notice(event);
    }
  }

  /** What woke a stream: the catalog may have changed, and what connections said since the last wake. */
  public record Wake(boolean catalog, List<ConnectionNotified> notices) {

    public boolean any() {
      return catalog || !notices.isEmpty();
    }
  }

  /** One independent client stream; no notification payload or credential is retained here. */
  public final class Subscription implements AutoCloseable {

    private final String endpointId;
    private final String principal;
    private final ArrayBlockingQueue<Boolean> pending = new ArrayBlockingQueue<>(1);
    private final ArrayBlockingQueue<ConnectionNotified> notices = new ArrayBlockingQueue<>(64);
    private volatile boolean catalog;
    private volatile boolean closed;

    private Subscription(String endpointId, String principal) {
      this.endpointId = endpointId;
      this.principal = principal;
    }

    private void signal() {
      pending.offer(Boolean.TRUE);
    }

    private void notice(ConnectionNotified n) {
      while (!notices.offer(n)) {
        notices.poll();
      }
      signal();
    }

    public String endpointId() {
      return endpointId;
    }

    /** True means the catalog may have changed; false means the heartbeat interval elapsed or the stream closed. */
    public boolean awaitChange(Duration timeout) throws InterruptedException {
      return await(timeout).catalog();
    }

    /** What happened until {@code timeout}: nothing ({@link Wake#any} false) when it passed or the stream closed. */
    public Wake await(Duration timeout) throws InterruptedException {
      boolean woken = !closed && pending.poll(timeout.toNanos(), TimeUnit.NANOSECONDS) != null && !closed;
      if (!woken) {
        return new Wake(false, List.of());
      }
      boolean changed = catalog;
      catalog = false;
      List<ConnectionNotified> said = new ArrayList<>();
      notices.drainTo(said);
      // The first wake of a stream (subscribe's own) refreshes whatever the client cached before.
      return new Wake(changed || said.isEmpty(), List.copyOf(said));
    }

    public boolean isClosed() {
      return closed;
    }

    @Override
    public void close() {
      closed = true;
      released(this);
      signal();
    }
  }

  @Override
  public synchronized void start() {
    running = true;
  }

  @Override
  public synchronized void stop() {
    running = false;
    Set.copyOf(subscriptions).forEach(Subscription::close);
  }

  @Override
  public synchronized boolean isRunning() {
    return running;
  }

  /** Close streams before the web server's graceful shutdown waits for their requests. */
  @Override
  public int getPhase() {
    return SmartLifecycle.DEFAULT_PHASE;
  }
}
