package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class EventBusTest {

  private final EventBus bus = new EventBus(Clock.systemUTC());

  private static ObjectNode data(int n) {
    return JsonNodeFactory.instance.objectNode().put("n", n);
  }

  @Test
  void subscribersGetEventsInOrderWithIncreasingIds() throws InterruptedException {
    try (EventBus.Subscription subscription = bus.subscribe(OptionalLong.empty())) {
      bus.publish("approval.requested", data(1));
      bus.publish("audit.call", data(2));

      EventBus.GatewayEvent first = subscription.next(Duration.ofSeconds(1)).orElseThrow();
      EventBus.GatewayEvent second = subscription.next(Duration.ofSeconds(1)).orElseThrow();
      assertThat(first.type()).isEqualTo("approval.requested");
      assertThat(second.id()).isEqualTo(first.id() + 1);
      assertThat(second.data().get("n").asInt()).isEqualTo(2);
      assertThat(subscription.next(Duration.ofMillis(20))).isEmpty();
      assertThat(subscription.isClosed()).isFalse();
    }
  }

  @Test
  void aReconnectReplaysWhatWasMissed() throws InterruptedException {
    long before = bus.publish("connection.health", data(1)).id();
    bus.publish("connection.health", data(2));
    bus.publish("connection.health", data(3));

    try (EventBus.Subscription subscription = bus.subscribe(OptionalLong.of(before))) {
      assertThat(subscription.next(Duration.ZERO).orElseThrow().data().get("n").asInt()).isEqualTo(2);
      assertThat(subscription.next(Duration.ZERO).orElseThrow().data().get("n").asInt()).isEqualTo(3);
      assertThat(subscription.next(Duration.ZERO)).isEmpty();
    }
  }

  @Test
  void onlyTheReplayWindowIsKept() throws InterruptedException {
    for (int i = 0; i < EventBus.REPLAY + 10; i++) {
      bus.publish("audit.call", data(i));
    }
    try (EventBus.Subscription subscription = bus.subscribe(OptionalLong.of(0))) {
      assertThat(subscription.next(Duration.ZERO).orElseThrow().data().get("n").asInt()).isEqualTo(10);
    }
  }

  @Test
  void aSubscriberThatFallsBehindIsClosedInsteadOfBlockingPublishers() {
    EventBus.Subscription slow = bus.subscribe(OptionalLong.empty());
    for (int i = 0; i <= EventBus.QUEUE; i++) {
      bus.publish("audit.call", data(i));
    }
    assertThat(bus.subscriberCount()).isZero();
    slow.close();
  }

  @Test
  void stoppingTheBusEndsEveryStream() throws InterruptedException {
    EventBus.Subscription subscription = bus.subscribe(OptionalLong.empty());
    bus.stop();

    assertThat(subscription.next(Duration.ofSeconds(5))).isEmpty();
    assertThat(subscription.isClosed()).isTrue();
    assertThat(bus.subscribe(OptionalLong.empty()).isClosed()).isTrue();
  }

  @Test
  void typesAreDottedIds() {
    assertThatThrownBy(() -> bus.publish("approval", data(1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> bus.publish("Approval.Requested", data(1))).isInstanceOf(IllegalArgumentException.class);
    assertThat(bus.publish("mcp-server.state", data(1)).type()).isEqualTo("mcp-server.state");
  }
}
