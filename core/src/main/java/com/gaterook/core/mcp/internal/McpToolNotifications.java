package com.gaterook.core.mcp.internal;

import com.gaterook.core.connectors.ConnectionNotified;
import com.gaterook.core.endpoints.EndpointCatalogInvalidated;
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
import java.util.function.LongSupplier;
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
   * Streams one token, grant or sign-in holds at once. Several clients often share one principal: every Claude Code
   * session configured with the same named token, every bridge process of one grant, every window that keeps the same
   * sign-in. Each holds a stream of its own, so the bound sits well above what people run side by side, and it still
   * keeps one principal from filling the gateway. A stream beyond it takes the place of an earlier one, at most once per
   * {@link #MAKE_ROOM_EVERY}: a client that reconnects after a drop the gateway hasn't noticed yet (it learns of a
   * closed client at its next write) gets through.
   */
  public static final int PER_PRINCIPAL = 16;

  /**
   * How often a principal at {@value #PER_PRINCIPAL} streams may close one of them to make room for a new one. Another
   * stream within this time is refused instead, so clients beyond the bound cannot take turns closing each other: each
   * reopened stream sends {@code tools/list_changed}, and the clients would list their tools again and again.
   */
  public static final Duration MAKE_ROOM_EVERY = Duration.ofMinutes(1);

  /** Streams the gateway holds at once, all principals together; more are refused. */
  public static final int OVERALL = 256;

  private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
  /** Each principal's open streams, oldest first. */
  private final Map<String, Deque<Subscription>> perPrincipal = new HashMap<>();
  /** When each principal last closed one of its streams to make room ({@link System#nanoTime()}). */
  private final Map<String, Long> madeRoomAt = new HashMap<>();
  private final LongSupplier nanoTime;
  private boolean running = true;

  public McpToolNotifications() {
    this(System::nanoTime);
  }

  /** With a clock that tests move past {@link #MAKE_ROOM_EVERY}. */
  McpToolNotifications(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
  }

  /**
   * A stream that was opened, or why none was.
   *
   * @param refusal for the person in front of the client; {@code null} when there is a subscription
   */
  public record Opened(Subscription subscription, String refusal) {

    public Optional<Subscription> stream() {
      return Optional.ofNullable(subscription);
    }
  }

  /** A stream for tests and callers without a principal: counted toward the overall limit only. */
  public Subscription subscribe(String endpointId) {
    return subscribe(endpointId, null).orElseGet(() -> {
      Subscription closed = new Subscription(endpointId, null, null);
      closed.close();
      return closed;
    });
  }

  /** {@link #open} without telling the principal's clients apart. */
  public Optional<Subscription> subscribe(String endpointId, String principal) {
    return open(endpointId, principal, null).stream();
  }

  /**
   * A stream of {@code principal} for {@code endpointId}. {@code client} tells the principal's clients apart where the
   * credential does: an opaque digest of the presented token, so each bridge process (it has a token of its own) is
   * one client, while clients that share a named token or a sign-in look alike. It is never logged. At
   * {@value #PER_PRINCIPAL} streams the new one replaces the oldest stream of the same client, else the principal's
   * oldest, at most once per {@link #MAKE_ROOM_EVERY}; otherwise it is refused, as it is when the gateway holds
   * {@value #OVERALL}. A stream opened after shutdown comes closed.
   */
  public synchronized Opened open(String endpointId, String principal, String client) {
    if (running && principal != null) {
      Deque<Subscription> own = perPrincipal.get(principal);
      if (own != null && own.size() >= PER_PRINCIPAL) {
        long now = nanoTime.getAsLong();
        Long last = madeRoomAt.get(principal);
        if (last != null && now - last < MAKE_ROOM_EVERY.toNanos()) {
          return new Opened(null, "This access holds " + PER_PRINCIPAL + " notification streams already, and one "
              + "of them made room for another less than a minute ago. Close a client that uses this access, or try "
              + "again in a minute.");
        }
        while (own != null && own.size() >= PER_PRINCIPAL) {
          Subscription replaced = own.stream().filter(x -> client != null && client.equals(x.client)).findFirst()
              .orElse(own.peekFirst());
          replaced.close();
          own = perPrincipal.get(principal);
        }
        madeRoomAt.put(principal, now);
      }
    }
    if (running && subscriptions.size() >= OVERALL) {
      return new Opened(null, "The gateway holds " + OVERALL + " notification streams already. Try again later.");
    }
    Subscription subscription = new Subscription(endpointId, principal, client);
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
    return new Opened(subscription, null);
  }

  private synchronized void released(Subscription subscription) {
    if (subscriptions.remove(subscription) && subscription.principal != null) {
      Deque<Subscription> own = perPrincipal.get(subscription.principal);
      if (own != null) {
        own.remove(subscription);
        if (own.isEmpty()) {
          perPrincipal.remove(subscription.principal);
          madeRoomAt.remove(subscription.principal);
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
    private final String client;
    private final ArrayBlockingQueue<Boolean> pending = new ArrayBlockingQueue<>(1);
    private final ArrayBlockingQueue<ConnectionNotified> notices = new ArrayBlockingQueue<>(64);
    private volatile boolean catalog;
    private volatile boolean closed;

    private Subscription(String endpointId, String principal, String client) {
      this.endpointId = endpointId;
      this.principal = principal;
      this.client = client;
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
