package ai.msrv.gaterook.plugin.protocol.host;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code ExecContext.acquire} inside a plugin process: a sliding minute per key. One process serves one plugin, so
 * the plugin's calls share it as the core's connectors share theirs.
 */
public final class LocalQuotas {

  private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

  /** Takes a permit for {@code key}; false when {@code permitsPerMinute} were taken in the last minute. */
  public boolean acquire(String key, int permitsPerMinute) {
    if (permitsPerMinute <= 0) {
      return false;
    }
    long now = System.nanoTime();
    Deque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && now - window.peekFirst() >= 60_000_000_000L) {
        window.pollFirst();
      }
      if (window.size() >= permitsPerMinute) {
        return false;
      }
      window.addLast(now);
      return true;
    }
  }
}
