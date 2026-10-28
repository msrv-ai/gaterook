package ai.msrv.gaterook.core.connections;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.core.GatewayApplication;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.platform.boot.GatewayLauncher;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConnectionCreationHealthIT {

  private static ConfigurableApplicationContext core;
  private static ConnectionService connections;
  private static ConnectionConfigs configs;
  private static EventBus events;

  @BeforeAll
  static void start() {
    Path home = TestGateway.newHome("creation-health");
    core = new SpringApplicationBuilder(GatewayApplication.class).sources(FakeConnectors.class)
        .contextFactory(GatewayLauncher.contexts(true))
        .run("--gateway.home=" + home, "--gateway.dev=true",
            "--gateway.dev.launch-secret=" + TestGateway.LAUNCH_SECRET, "--server.port=0",
            "--gateway.clients.user-home=" + home.resolve("user-home"),
            "--gateway.connections.health-interval=1d", "--gateway.connections.health-initial-delay=1d");
    connections = core.getBean(ConnectionService.class);
    configs = core.getBean(ConnectionConfigs.class);
    events = core.getBean(EventBus.class);
  }

  @AfterAll
  static void stop() {
    if (core != null) core.close();
  }

  @Test
  void creationChecksHealthAndPublishesItBeforeTheFirstScheduledRound() throws Exception {
    for (String host : new String[] {"db.test", "down.test"}) {
      String name = host.equals("db.test") ? "new-healthy" : "new-unreachable";
      HealthResult.State expected = host.equals("db.test") ? HealthResult.State.HEALTHY
          : HealthResult.State.UNREACHABLE;
      try (EventBus.Subscription subscription = events.subscribe(OptionalLong.of(events.lastId()))) {
        ConnectionService.Summary saved = create(name, host);
        TestCore.await(() -> configs.byId(saved.id()).orElseThrow().health().checkedAt() != null,
            "the new connection's initial health check");
        ConnectionConfigs.Stored stored = configs.byId(saved.id()).orElseThrow();
        assertThat(stored.health().state()).isEqualTo(expected);
        assertThat(stored.verified()).isTrue();
        if (expected == HealthResult.State.UNREACHABLE) {
          assertThat(stored.health().message()).isEqualTo("Connection refused");
          assertThat(stored.health().latencyMs()).isNull();
          assertThat(stored.config().version()).isEqualTo(saved.version());
        }
        assertThat(nextHealth(subscription, name).data().path("state").asString())
            .isEqualTo(expected.name().toLowerCase(java.util.Locale.ROOT));
      }
    }
  }

  @Test
  void rolledBackCreationDoesNotPublishHealth() throws Exception {
    try (EventBus.Subscription subscription = events.subscribe(OptionalLong.of(events.lastId()))) {
      new TransactionTemplate(core.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> {
        create("rolled-back", "db.test");
        tx.setRollbackOnly();
      });
      assertThat(configs.byName("rolled-back")).isEmpty();
      long deadline = System.nanoTime() + Duration.ofMillis(300).toNanos();
      while (System.nanoTime() < deadline) {
        subscription.next(Duration.ofMillis(25)).ifPresent(event -> {
          if (event.type().equals("connection.health")) {
            assertThat(event.data().path("name").asString()).isNotEqualTo("rolled-back");
          }
        });
      }
    }
  }

  private static ConnectionService.Summary create(String name, String host) {
    return connections.create(name, "pgfake", Env.DEV, TestCore.config(host),
        Map.of("/credentials/password", SecretInput.local("test-password".toCharArray())), null);
  }

  private static EventBus.GatewayEvent nextHealth(EventBus.Subscription subscription, String name)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      var event = subscription.next(Duration.ofMillis(100));
      if (event.isPresent() && event.get().type().equals("connection.health")
          && event.get().data().path("name").asString().equals(name)) return event.get();
    }
    throw new AssertionError("No initial health event for " + name);
  }
}
