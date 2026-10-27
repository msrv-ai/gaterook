package ai.msrv.gaterook.core.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.JsonNode;

/**
 * Events for the desktop app, streamed by {@code GET /api/events} (ARCHITECTURE.md §6): {@code approval.requested},
 * {@code audit.call}, {@code connection.health}, {@code tunnel.state}, … Payloads are JSON trees, so publishing needs no
 * reflection metadata in the native image (report 15 §9 rule 4).
 *
 * <p>Each event gets the next sequence number, the SSE {@code id}. The last {@link #REPLAY} events are kept so that a
 * reconnecting client ({@code Last-Event-ID}) misses nothing a short gap could lose; a subscriber that falls more than
 * {@link #QUEUE} events behind is closed and reconnects. Publishing never blocks on a slow client.
 *
 * <p>Events carry no secrets and no agent notes by construction of their publishers; the bus doesn't inspect them.
 */
public class EventBus implements SmartLifecycle {

  /** Events kept for {@code Last-Event-ID} replay. */
  public static final int REPLAY = 512;

  /** Events one subscriber may have pending before it is dropped. */
  public static final int QUEUE = 1024;

  private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+");

  /** One published event. */
  public record GatewayEvent(long id, String type, Instant at, JsonNode data) {}

  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();
  private final Deque<GatewayEvent> recent = new ArrayDeque<>(REPLAY);
  private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
  private long sequence;
  private volatile boolean running = true;

  public EventBus(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Publishes {@code data} as an event of {@code type} ({@code approval.requested}).
   *
   * @return the event as delivered
   */
  public GatewayEvent publish(String type, JsonNode data) {
    if (type == null || !TYPE.matcher(type).matches()) {
      throw new IllegalArgumentException("Invalid event type: " + type);
    }
    Objects.requireNonNull(data, "data");
    lock.lock();
    try {
      GatewayEvent event = new GatewayEvent(++sequence, type, clock.instant(), data.deepCopy());
      if (recent.size() == REPLAY) {
        recent.removeFirst();
      }
      recent.addLast(event);
      for (Subscription subscription : subscriptions) {
        subscription.offer(event);
      }
      return event;
    } finally {
      lock.unlock();
    }
  }

  /**
   * A new subscription. With {@code lastEventId} it starts with the kept events after that id (those older than the
   * replay window are gone), else with the next published event.
   */
  public Subscription subscribe(OptionalLong lastEventId) {
    lock.lock();
    try {
      Subscription subscription = new Subscription();
      if (lastEventId.isPresent()) {
        for (GatewayEvent event : recent) {
          if (event.id() > lastEventId.getAsLong()) {
            subscription.offer(event);
          }
        }
      }
      if (running) {
        subscriptions.add(subscription);
      } else {
        subscription.closed = true;
      }
      return subscription;
    } finally {
      lock.unlock();
    }
  }

  /** The id of the latest event, 0 before the first. */
  public long lastId() {
    lock.lock();
    try {
      return sequence;
    } finally {
      lock.unlock();
    }
  }

  int subscriberCount() {
    return subscriptions.size();
  }

  /** One client's stream. Not thread-safe for consumers: one reader per subscription. */
  public final class Subscription implements AutoCloseable {

    private final LinkedBlockingQueue<GatewayEvent> queue = new LinkedBlockingQueue<>(QUEUE);
    private volatile boolean closed;

    private Subscription() {}

    private void offer(GatewayEvent event) {
      if (!queue.offer(event)) {
        // Too slow: drop it rather than block publishers or grow without bound. The client reconnects with its
        // Last-Event-ID and replays what is still kept.
        close();
      }
    }

    /**
     * The next event, waiting at most {@code timeout}; empty on timeout or once closed ({@link #isClosed()} tells
     * them apart). Events queued before the close are still handed out.
     */
    public Optional<GatewayEvent> next(Duration timeout) throws InterruptedException {
      GatewayEvent event = queue.poll();
      if (event != null || closed) {
        return Optional.ofNullable(event);
      }
      long deadline = System.nanoTime() + timeout.toNanos();
      // Short slices so that a close (shutdown, overflow) ends the wait promptly.
      while (!closed) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return Optional.empty();
        }
        event = queue.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS);
        if (event != null) {
          return Optional.of(event);
        }
      }
      return Optional.ofNullable(queue.poll());
    }

    public boolean isClosed() {
      return closed && queue.isEmpty();
    }

    @Override
    public void close() {
      closed = true;
      subscriptions.remove(this);
    }
  }

  // ── Lifecycle: streams end before the web server's graceful shutdown waits for them ──────────────────────────

  @Override
  public void start() {
    running = true;
  }

  @Override
  public void stop() {
    running = false;
    List<Subscription> open = new ArrayList<>(subscriptions);
    open.forEach(Subscription::close);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** The highest phase stops first: before graceful shutdown starts waiting for the open SSE requests. */
  @Override
  public int getPhase() {
    return SmartLifecycle.DEFAULT_PHASE;
  }
}
