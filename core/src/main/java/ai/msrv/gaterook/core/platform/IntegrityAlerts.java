package ai.msrv.gaterook.core.platform;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Objects whose stored {@code mac} doesn't verify: rows edited in {@code gateway.db} outside the core, or written
 * before the current master key (ARCHITECTURE.md §4, §7.1). The owner of such a row turns the object off (fail closed)
 * and reports it here; the UI shows the banner "Changed outside MCP Gateway" with the object, and the person reviews it
 * and confirms through the owner, which re-signs the row and records a Setup change.
 *
 * <p>Kept in memory: each owner verifies its rows whenever it loads them, so after a restart the alerts come back on
 * the first load. Publishes {@code integrity.alert} once per object.
 */
public class IntegrityAlerts {

  private static final Logger log = LoggerFactory.getLogger(IntegrityAlerts.class);

  /** One object that failed verification. {@code kind}: {@code connection}, {@code endpoint}, {@code policy}, … */
  public record Alert(String kind, String id, String label, String detail, Instant since) {}

  private final Map<String, Alert> alerts = new ConcurrentHashMap<>();
  private final EventBus events;
  private final Clock clock;

  public IntegrityAlerts(EventBus events, Clock clock) {
    this.events = Objects.requireNonNull(events, "events");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Records that {@code kind}/{@code id} didn't verify; idempotent. */
  public void report(String kind, String id, String label, String detail) {
    Alert alert = new Alert(kind, id, label, detail, clock.instant());
    if (alerts.putIfAbsent(key(kind, id), alert) == null) {
      log.warn("{} {} was changed outside MCP Gateway ({}); it stays off until you review it", kind, label, detail);
      ObjectNode data = JsonNodeFactory.instance.objectNode();
      data.put("kind", kind);
      data.put("id", id);
      data.put("label", label);
      data.put("detail", detail);
      events.publish("integrity.alert", data);
    }
  }

  /** The person reviewed and confirmed the object, or it was deleted. */
  public void resolve(String kind, String id) {
    alerts.remove(key(kind, id));
  }

  public boolean has(String kind, String id) {
    return alerts.containsKey(key(kind, id));
  }

  public List<Alert> current() {
    return alerts.values().stream().sorted(Comparator.comparing(Alert::since).thenComparing(Alert::label)).toList();
  }

  private static String key(String kind, String id) {
    return kind + "\0" + id;
  }
}
