package com.gaterook.core.connections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gaterook.core.connectors.CallContexts;
import com.gaterook.core.connectors.ConnectorRegistry;
import com.gaterook.core.connectors.EnvironmentPolicies;
import com.gaterook.core.platform.Masker;
import com.gaterook.core.platform.MasterKeyLock;
import com.gaterook.core.secrets.SecretService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The demo world ({@code GW_DEMO=1}) isn't checked in rounds (review API #6): its hosts don't exist, and a round would
 * reach for them by itself — DNS, AWS with the demo's key, GCP with this computer's default credentials.
 */
class HealthMonitorDemoTest {

  @Test
  void theDemoWorldIsNotCheckedByItselfAndAnyOtherHomeIs() throws Exception {
    ConnectionConfigs demoConfigs = mock(ConnectionConfigs.class);
    when(demoConfigs.all()).thenReturn(List.of());
    HealthMonitor demo = monitor(demoConfigs, "1");
    try {
      demo.onApplicationEvent(null);
      Thread.sleep(300);
      verify(demoConfigs, never()).all();
    } finally {
      demo.destroy();
    }

    ConnectionConfigs configs = mock(ConnectionConfigs.class);
    when(configs.all()).thenReturn(List.of());
    HealthMonitor normal = monitor(configs, "false");
    try {
      normal.onApplicationEvent(null);
      verify(configs, timeout(5_000).atLeastOnce()).all();
    } finally {
      normal.destroy();
    }
  }

  private static HealthMonitor monitor(ConnectionConfigs configs, String demo) {
    return new HealthMonitor(configs, mock(ConnectorRegistry.class), mock(ConnectionService.class),
        mock(ConnectionTester.class), mock(SecretService.class), mock(CallContexts.class),
        mock(EnvironmentPolicies.class), mock(Masker.class), new MasterKeyLock(), Duration.ofMillis(20), Duration.ZERO,
        Duration.ofSeconds(1), demo);
  }
}
