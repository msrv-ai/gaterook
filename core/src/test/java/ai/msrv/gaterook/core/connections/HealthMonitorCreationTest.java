package ai.msrv.gaterook.core.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connectors.CallContexts;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HealthMonitorCreationTest {

  private final ConnectionConfigs configs = mock(ConnectionConfigs.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final MasterKeyLock lock = new MasterKeyLock();

  @Test
  void creationChecksOnlyTheNewConnectionWithoutBlockingTheCaller() throws Exception {
    ConnectionConfigs.Stored stored = stored(true);
    when(configs.byId("new-id")).thenReturn(Optional.of(stored));
    when(registry.usable("pgfake")).thenReturn(true);
    HealthMonitor monitor = monitor(Duration.ofDays(1), "false");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    doAnswer(call -> {
      started.countDown();
      assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
      return null;
    }).when(monitor).check(stored);
    try {
      monitor.onConnectionCreated(new ConnectionCreated("new-id"));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      verify(monitor).check(stored);
      verify(configs).byId("new-id");
      verify(configs, never()).all();
    } finally {
      finish.countDown();
      monitor.destroy();
    }
  }

  @Test
  void automaticChecksStayOffForDemoDisabledAndLockedCores() throws Exception {
    for (Duration interval : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
      HealthMonitor monitor = monitor(interval, "false");
      try {
        monitor.onConnectionCreated(new ConnectionCreated("new-id"));
      } finally {
        monitor.destroy();
      }
    }
    HealthMonitor demo = monitor(Duration.ofDays(1), "1");
    try {
      demo.onConnectionCreated(new ConnectionCreated("new-id"));
    } finally {
      demo.destroy();
    }
    lock.lock(MasterKeyLock.Reason.MASTER_KEY_MISSING);
    HealthMonitor locked = monitor(Duration.ofDays(1), "false");
    try {
      locked.onConnectionCreated(new ConnectionCreated("new-id"));
    } finally {
      locked.destroy();
    }
    verifyNoInteractions(configs, registry);
  }

  @Test
  void removedTamperedAndDisabledConnectionsAreNotProbed() throws Exception {
    for (Optional<ConnectionConfigs.Stored> result : java.util.List.of(Optional.<ConnectionConfigs.Stored>empty(),
        Optional.of(stored(false)), Optional.of(stored(true)))) {
      when(configs.byId("new-id")).thenReturn(result);
      HealthMonitor monitor = monitor(Duration.ofDays(1), "false");
      try {
        monitor.onConnectionCreated(new ConnectionCreated("new-id"));
        verify(monitor, after(100).never()).check(any());
      } finally {
        monitor.destroy();
      }
    }
    verify(configs, timeout(1_000).times(3)).byId("new-id");
    verify(registry).usable("pgfake");
  }

  private HealthMonitor monitor(Duration interval, String demo) {
    return spy(new HealthMonitor(configs, registry, mock(ConnectionService.class), mock(ConnectionTester.class),
        mock(SecretService.class), mock(CallContexts.class), mock(EnvironmentPolicies.class), Masker.standard(), lock,
        interval, Duration.ofDays(1), Duration.ofSeconds(1), demo));
  }

  private static ConnectionConfigs.Stored stored(boolean verified) {
    ConnectionConfig config = new ConnectionConfig("new-id", "new-db", "pgfake", Env.DEV, 1, null,
        Set.of(), null);
    return new ConnectionConfigs.Stored(config, verified, null, null, null, Map.of());
  }
}
