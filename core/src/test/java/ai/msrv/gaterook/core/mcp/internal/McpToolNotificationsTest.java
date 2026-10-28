package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.msrv.gaterook.core.endpoints.EndpointCatalogInvalidated;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
}
