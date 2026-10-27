package ai.msrv.gaterook.core.tunnel;

import ai.msrv.gaterook.core.platform.EventBus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;

/** The events of one type the bus publishes while a test runs, as the UI's SSE stream would get them. */
final class TunnelEvents {

  private TunnelEvents() {}

  static AutoCloseable listen(EventBus bus, String type, List<JsonNode> into) {
    EventBus.Subscription subscription = bus.subscribe(OptionalLong.empty());
    Thread reader = Thread.ofVirtual().name("test-events").start(() -> {
      try {
        while (!subscription.isClosed()) {
          Optional<EventBus.GatewayEvent> e = subscription.next(Duration.ofMillis(200));
          if (e.isPresent() && type.equals(e.get().type())) {
            synchronized (into) {
              into.add(e.get().data());
            }
          }
        }
      } catch (InterruptedException ignored) {
        // closed
      }
    });
    return () -> {
      Thread.sleep(300);
      subscription.close();
      reader.interrupt();
      reader.join(2000);
    };
  }
}
