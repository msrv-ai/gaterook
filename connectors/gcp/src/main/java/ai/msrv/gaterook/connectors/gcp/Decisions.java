package ai.msrv.gaterook.connectors.gcp;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Cloud policy reaches {@code classify} only ({@code PolicyView}); {@code execute} sees the call's {@code Limits},
 * which know nothing of services, locations or resource masks. What {@code classify} saw travels to {@code execute}
 * of the same call here, by call id and bound to the tool and connection's id and version — the core classifies
 * right before it executes, also for an approved call. Without a decision {@code execute} refuses: guessing "no
 * restriction" would show what the policy hides.
 */
final class Decisions {

  static final Duration TTL = Duration.ofMinutes(5);
  static final int MAX = 4_096;

  private record Decided(String tool, String connectionId, long version, GcpPolicy policy, long atNanos) {}

  private final Map<String, Decided> decided = new ConcurrentHashMap<>();

  void remember(String callId, String tool, String connectionId, long version, GcpPolicy policy) {
    if (callId == null) {
      return;
    }
    long now = System.nanoTime();
    if (decided.size() >= MAX) {
      decided.values().removeIf(d -> now - d.atNanos() > TTL.toNanos());
      if (decided.size() >= MAX) {
        // Calls classified but never run (denied later by the policy): the oldest go first.
        decided.entrySet().stream().sorted(Comparator.comparingLong(e -> e.getValue().atNanos())).limit(MAX / 4)
            .map(Map.Entry::getKey).toList().forEach(decided::remove);
      }
    }
    decided.put(callId, new Decided(tool, connectionId, version, policy, now));
  }

  void forget(String callId) {
    if (callId != null) decided.remove(callId);
  }

  /** The policy {@code classify} applied to this tool and connection version, once; {@code null} if none. */
  GcpPolicy take(String callId, String tool, String connectionId, long version) {
    if (callId == null) {
      return null;
    }
    Decided d = decided.remove(callId);
    if (d == null || System.nanoTime() - d.atNanos() > TTL.toNanos() || !d.tool().equals(tool)
        || !d.connectionId().equals(connectionId) || d.version() != version) {
      return null;
    }
    return d.policy();
  }
}
