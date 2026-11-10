package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.msrv.gaterook.core.endpoints.EndpointCatalogInvalidated;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class McpToolNotificationsTest {

  @Test
  void subscriptionsRecheckOnOpenAndCoalesceBurstsWithoutLosingTheNextChange() throws Exception {
    McpToolNotifications notifications = new McpToolNotifications();
    try (var subscription = notifications.subscribe("one")) {
      assertThat(subscription.awaitChange(Duration.ZERO)).as("close the initial catalog/subscribe race").isTrue();
      assertThat(subscription.awaitChange(Duration.ZERO)).isFalse();
      for (int i = 0; i < 1000; i++) {
        notifications.invalidated(new EndpointCatalogInvalidated("one"));
      }
      assertThat(subscription.awaitChange(Duration.ZERO)).isTrue();
      assertThat(subscription.awaitChange(Duration.ZERO)).as("one pending wakeup for a slow client").isFalse();
      notifications.invalidated(new EndpointCatalogInvalidated("one"));
      assertThat(subscription.awaitChange(Duration.ZERO)).isTrue();
    }
  }

  @Test
  void endpointChangesReachEveryMatchingClientAndSharedChangesReachAllEndpoints() throws Exception {
    McpToolNotifications notifications = new McpToolNotifications();
    try (var first = notifications.subscribe("one");
        var second = notifications.subscribe("one");
        var other = notifications.subscribe("two")) {
      first.awaitChange(Duration.ZERO);
      second.awaitChange(Duration.ZERO);
      other.awaitChange(Duration.ZERO);
      notifications.invalidated(new EndpointCatalogInvalidated("one"));
      assertThat(first.awaitChange(Duration.ZERO)).isTrue();
      assertThat(second.awaitChange(Duration.ZERO)).isTrue();
      assertThat(other.awaitChange(Duration.ZERO)).isFalse();
      notifications.invalidated(new EndpointCatalogInvalidated(null));
      assertThat(first.awaitChange(Duration.ZERO)).isTrue();
      assertThat(second.awaitChange(Duration.ZERO)).isTrue();
      assertThat(other.awaitChange(Duration.ZERO)).isTrue();
    }
  }

  @Test
  void closingASubscriptionWakesAnIdleRequestAndPreventsFurtherDelivery() throws Exception {
    McpToolNotifications notifications = new McpToolNotifications();
    var subscription = notifications.subscribe("one");
    subscription.awaitChange(Duration.ZERO);
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    Thread reader = Thread.ofVirtual().start(() -> {
      try {
        result.complete(subscription.awaitChange(Duration.ofMinutes(1)));
      } catch (InterruptedException e) {
        result.completeExceptionally(e);
      }
    });
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
        while (reader.getState() != Thread.State.TIMED_WAITING) {
          Thread.sleep(1);
        }
      });
      subscription.close();
      assertThat(result.get(1, TimeUnit.SECONDS)).isFalse();
      notifications.invalidated(new EndpointCatalogInvalidated("one"));
      assertThat(subscription.awaitChange(Duration.ZERO)).isFalse();
      assertThat(subscription.isClosed()).isTrue();
      subscription.close();
    } finally {
      reader.interrupt();
      subscription.close();
    }
  }

  @Test
  void shutdownClosesExistingAndNewStreamsAndRestartAllowsFreshSubscriptions() throws Exception {
    McpToolNotifications notifications = new McpToolNotifications();
    var first = notifications.subscribe("one");
    var second = notifications.subscribe("two");
    notifications.stop();
    assertThat(notifications.isRunning()).isFalse();
    assertThat(first.isClosed()).isTrue();
    assertThat(second.isClosed()).isTrue();
    assertThat(first.awaitChange(Duration.ZERO)).isFalse();
    assertThat(notifications.subscribe("three").isClosed()).isTrue();
    notifications.start();
    assertThat(notifications.isRunning()).isTrue();
    try (var resumed = notifications.subscribe("one")) {
      assertThat(resumed.isClosed()).isFalse();
      assertThat(resumed.awaitChange(Duration.ZERO)).isTrue();
      assertThat(first.isClosed()).isTrue();
    } finally {
      notifications.stop();
    }
  }

  @Test
  void severalClientsOfOnePrincipalKeepTheirStreams() {
    McpToolNotifications notifications = new McpToolNotifications();
    List<McpToolNotifications.Subscription> held = new ArrayList<>();
    // Claude Code sessions sharing a named token, or the bridge processes of one grant: each holds a stream. Three of
    // them used to close each other's streams in turn.
    for (int i = 0; i < 5; i++) {
      held.add(notifications.open(i % 2 == 0 ? "one" : "two", "tok_a", "client-" + i).subscription());
    }
    assertThat(held).as("five clients of one principal").noneMatch(McpToolNotifications.Subscription::isClosed);
    while (held.size() < McpToolNotifications.PER_PRINCIPAL) {
      held.add(notifications.open("one", "tok_a", "client-" + held.size()).subscription());
    }
    assertThat(held).as("up to the bound").noneMatch(McpToolNotifications.Subscription::isClosed);
    var other = notifications.subscribe("one", "tok_b");
    assertThat(other).as("another principal").hasValueSatisfying(s -> assertThat(s.isClosed()).isFalse());
    held.forEach(McpToolNotifications.Subscription::close);
    other.get().close();
  }

  @Test
  void aClientReconnectingAtTheBoundReplacesItsOwnStream() {
    McpToolNotifications notifications = new McpToolNotifications();
    List<McpToolNotifications.Subscription> held = new ArrayList<>();
    for (int i = 0; i < McpToolNotifications.PER_PRINCIPAL; i++) {
      held.add(notifications.open("one", "gr_a", "client-" + i).subscription());
    }
    // client-5 dropped its stream without the gateway noticing yet, and opens a new one.
    var again = notifications.open("one", "gr_a", "client-5").subscription();
    assertThat(again.isClosed()).isFalse();
    assertThat(held.get(5).isClosed()).as("its own earlier stream gave way").isTrue();
    assertThat(held.stream().filter(s -> s != held.get(5))).as("every other client keeps its stream")
        .noneMatch(McpToolNotifications.Subscription::isClosed);
    held.forEach(McpToolNotifications.Subscription::close);
    again.close();
  }

  @Test
  void clientsBeyondTheBoundCannotTakeTurnsClosingEachOther() {
    AtomicLong now = new AtomicLong();
    McpToolNotifications notifications = new McpToolNotifications(now::get);
    List<McpToolNotifications.Subscription> held = new ArrayList<>();
    for (int i = 0; i < McpToolNotifications.PER_PRINCIPAL; i++) {
      held.add(notifications.open("one", "tok_a", "client-" + i).subscription());
    }
    var newcomer = notifications.open("one", "tok_a", "client-new").subscription();
    assertThat(newcomer.isClosed()).isFalse();
    assertThat(held.getFirst().isClosed()).as("one stream made room: the principal's oldest").isTrue();

    // The client whose stream closed reconnects at once: within the pause it is refused, nobody else is closed.
    now.addAndGet(Duration.ofSeconds(1).toNanos());
    McpToolNotifications.Opened refused = notifications.open("one", "tok_a", "client-0");
    assertThat(refused.subscription()).isNull();
    assertThat(refused.refusal()).contains(McpToolNotifications.PER_PRINCIPAL + " notification streams", "minute");
    assertThat(held.subList(1, held.size())).noneMatch(McpToolNotifications.Subscription::isClosed);
    assertThat(newcomer.isClosed()).isFalse();

    // A minute later one more stream may make room again.
    now.addAndGet(McpToolNotifications.MAKE_ROOM_EVERY.toNanos());
    var later = notifications.open("one", "tok_a", "client-0").subscription();
    assertThat(later.isClosed()).isFalse();
    assertThat(held.get(1).isClosed()).isTrue();
    assertThat(held.subList(2, held.size())).noneMatch(McpToolNotifications.Subscription::isClosed);
    held.forEach(McpToolNotifications.Subscription::close);
    newcomer.close();
    later.close();
  }

  @Test
  void theGatewayHoldsAtMostItsOverallBound() {
    McpToolNotifications notifications = new McpToolNotifications();
    List<McpToolNotifications.Subscription> held = new ArrayList<>();
    for (int i = 0; i < McpToolNotifications.OVERALL; i++) {
      held.add(notifications.subscribe("one", "tok_" + i).orElseThrow());
    }
    McpToolNotifications.Opened full = notifications.open("one", "tok_new", "client");
    assertThat(full.subscription()).as("the gateway's limit").isNull();
    assertThat(full.refusal()).contains(String.valueOf(McpToolNotifications.OVERALL));
    assertThat(notifications.subscribe("one", null)).as("a stream without a principal counts too").isEmpty();
    held.getFirst().close();
    assertThat(notifications.subscribe("one", "tok_new")).isPresent();
    held.forEach(McpToolNotifications.Subscription::close);
  }
}
