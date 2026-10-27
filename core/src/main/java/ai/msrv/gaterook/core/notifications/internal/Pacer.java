package ai.msrv.gaterook.core.notifications.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Two clocks of the channels (report 13 §6.2, §7): at most one message per {@link NotifyConfig#pace()} to one chat or
 * channel (Slack: "1 message per second to a specific channel"; Telegram: "avoid sending more than one message per
 * second" in a chat), and a sliding window that tells a flood — more new requests in a window than a channel announces
 * one by one — from ordinary traffic. A flood is how an agent fishes for a mistaken tap, so channels answer it with
 * one digest message without Approve.
 */
public final class Pacer {

  private final Clock clock;
  private final Duration pace;
  private final Map<String, Instant> nextSend = new HashMap<>();
  private final Map<String, Deque<Instant>> windows = new HashMap<>();

  public Pacer(Clock clock, Duration pace) {
    this.clock = clock;
    this.pace = pace;
  }

  /** Reserves the next slot to send to {@code target} and waits for it; returns at once when the target was idle. */
  public void await(String target) throws InterruptedException {
    long waitMs;
    synchronized (this) {
      Instant now = clock.instant();
      Instant slot = nextSend.getOrDefault(target, now);
      if (slot.isBefore(now)) {
        slot = now;
      }
      nextSend.put(target, slot.plus(pace));
      waitMs = Duration.between(now, slot).toMillis();
    }
    if (waitMs > 0) {
      Thread.sleep(waitMs);
    }
  }

  /**
   * Counts one event of {@code key} now and returns how many happened within {@code window} (this one included):
   * the caller compares with its limit.
   */
  public synchronized int hit(String key, Duration window) {
    Instant now = clock.instant();
    Deque<Instant> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
    q.addLast(now);
    Instant from = now.minus(window);
    while (!q.isEmpty() && !q.peekFirst().isAfter(from)) {
      q.pollFirst();
    }
    return q.size();
  }

  /** How many events of {@code key} happened within {@code window}, without counting one. */
  public synchronized int count(String key, Duration window) {
    Deque<Instant> q = windows.get(key);
    if (q == null) {
      return 0;
    }
    Instant from = clock.instant().minus(window);
    return (int) q.stream().filter(t -> t.isAfter(from)).count();
  }
}
