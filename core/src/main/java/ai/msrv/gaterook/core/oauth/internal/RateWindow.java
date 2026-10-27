package ai.msrv.gaterook.core.oauth.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sliding-window limit per key (≤ 10 {@code authorize} or registrations a minute per IP, report 02 §6.3, §6.6).
 * Memory is bounded: keys whose window emptied are dropped, and past 10,000 keys the oldest windows go first.
 */
final class RateWindow {

  private static final int MAX_KEYS = 10_000;

  private final int limit;
  private final Duration window;
  private final Clock clock;
  private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

  RateWindow(int limit, Duration window, Clock clock) {
    this.limit = limit;
    this.window = window;
    this.clock = clock;
  }

  /**
   * The key of a caller's address: an IPv4 address as it is, an IPv6 one by its /64 — one subscriber gets a whole
   * /64 and could take a new address for every request.
   */
  static String addressKey(String ip) {
    if (ip == null || ip.indexOf(':') < 0) {
      return ip;
    }
    try {
      byte[] a = java.net.InetAddress.ofLiteral(ip).getAddress();
      if (a.length != 16) {
        return ip;
      }
      return java.util.HexFormat.of().formatHex(a, 0, 8) + "::/64";
    } catch (IllegalArgumentException e) {
      return ip;
    }
  }

  /** Counts one hit of {@code key} and says whether it is within the limit. */
  boolean tryAcquire(String key) {
    Instant now = clock.instant();
    Instant floor = now.minus(window);
    if (hits.size() > MAX_KEYS) {
      hits.entrySet().removeIf(e -> {
        synchronized (e.getValue()) {
          return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(floor);
        }
      });
    }
    Deque<Instant> q = hits.computeIfAbsent(key == null ? "?" : key, k -> new ArrayDeque<>());
    synchronized (q) {
      while (!q.isEmpty() && q.peekFirst().isBefore(floor)) {
        q.pollFirst();
      }
      if (q.size() >= limit) {
        return false;
      }
      q.addLast(now);
      return true;
    }
  }
}
