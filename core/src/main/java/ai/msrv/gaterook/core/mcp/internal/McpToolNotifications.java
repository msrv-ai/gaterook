package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.core.endpoints.EndpointCatalogInvalidated;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Wakes MCP streams after a committed change invalidates an endpoint snapshot. Each stream has one pending wakeup:
 * bursts coalesce, and a slow client never blocks the committing thread. The stream's own request thread reads the
 * new catalog and compares its hash, outside the transaction that published the invalidation.
 */
@Component
public class McpToolNotifications implements SmartLifecycle {

  private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
  private boolean running = true;

  public synchronized Subscription subscribe(String endpointId) {
    Subscription subscription = new Subscription(endpointId);
    if (running) {
      subscriptions.add(subscription);
      // Refresh the client's unknown cached view on open, including changes missed before registration.
      subscription.signal();
    } else {
      subscription.close();
    }
    return subscription;
  }

  @EventListener
  public void invalidated(EndpointCatalogInvalidated event) {
    for (Subscription subscription : subscriptions) {
      if (event.endpointId() == null || subscription.endpointId.equals(event.endpointId())) {
        subscription.signal();
      }
    }
  }

  /** One independent client stream; no notification payload or credential is retained here. */
  public final class Subscription implements AutoCloseable {

    private final String endpointId;
    private final ArrayBlockingQueue<Boolean> pending = new ArrayBlockingQueue<>(1);
    private volatile boolean closed;

    private Subscription(String endpointId) {
      this.endpointId = endpointId;
    }

    private void signal() {
      pending.offer(Boolean.TRUE);
    }

    /** True means the catalog may have changed; false means the heartbeat interval elapsed or the stream closed. */
    public boolean awaitChange(Duration timeout) throws InterruptedException {
      return !closed && pending.poll(timeout.toNanos(), TimeUnit.NANOSECONDS) != null && !closed;
    }

    public boolean isClosed() {
      return closed;
    }

    @Override
    public void close() {
      closed = true;
      subscriptions.remove(this);
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
