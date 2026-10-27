package ai.msrv.gaterook.connectors.kubernetes;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The namespaces {@code classify} allowed for a listing across namespaces, handed to {@code execute} of the same call.
 *
 * <p>Only {@code classify} sees the policy (environment, connection, endpoint and tool narrowed together), and the
 * core calls it right before {@code execute} for the same call id — on the direct path and again before an approved
 * call runs. Every other check is pure and repeats in {@code execute}; this one needs the policy's list. A call
 * {@code execute} finds nothing for (another process classified it) lists the connection's own namespace only: the
 * narrowest reading is the safe one.
 */
final class ClassifiedScopes {

  private static final int MAX = 4096;

  private final Map<String, List<String>> byCall = new ConcurrentHashMap<>();

  void put(String callId, List<String> namespaces) {
    if (byCall.size() >= MAX) {
      // Calls classified and denied never reach execute; drop the oldest-looking entries rather than grow.
      Iterator<String> it = byCall.keySet().iterator();
      for (int i = 0; i < MAX / 4 && it.hasNext(); i++) {
        it.next();
        it.remove();
      }
    }
    byCall.put(callId, List.copyOf(namespaces));
  }

  List<String> take(String callId, String fallback) {
    List<String> ns = byCall.remove(callId);
    return ns == null ? List.of(fallback) : ns;
  }
}
