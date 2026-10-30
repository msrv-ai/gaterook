package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.mock.env.MockEnvironment;

class ClientsConfigurationTest {
  @TempDir Path home;

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void theDesktopJvmPropertyCannotBeDowngradedOrOverriddenByApplicationConfiguration() {
    String name = "gateway.clients.windows-store";
    String previous = System.getProperty(name);
    try {
      for (boolean store : new boolean[] { true, false }) {
        System.setProperty(name, Boolean.toString(store));
        var configuration = new MockEnvironment().withProperty("gateway.clients.user-home", home.toString())
            .withProperty(name, Boolean.toString(!store));
        assertThat(new ClientsConfiguration().clientEnvironment(home.resolve(".gaterook"), "", configuration)
            .windowsStore()).isEqualTo(store);
      }
    } finally {
      if (previous == null) System.clearProperty(name);
      else System.setProperty(name, previous);
    }
  }
}
