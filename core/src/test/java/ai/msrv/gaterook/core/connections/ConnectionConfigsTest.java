package ai.msrv.gaterook.core.connections;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class ConnectionConfigsTest {
  @Test
  void needsSetupAlwaysMakesStoredAccessDisabledEvenWhenTheRowEnabledFlagIsTrue() {
    ConnectionConfig config = config(true);
    assertThat(new ConnectionConfigs.Stored(config, true, null, null, null, Map.of(), true).enabled()).isFalse();
    assertThat(new ConnectionConfigs.Stored(config, true, null, null, null, Map.of()).enabled()).isFalse();
  }

  @Test
  void completeConnectionsRetainTheirExplicitEnabledState() {
    assertThat(new ConnectionConfigs.Stored(config(false), true, null, null, null, Map.of(), true).enabled()).isTrue();
    assertThat(new ConnectionConfigs.Stored(config(false), true, null, null, null, Map.of(), false).enabled()).isFalse();
  }

  private static ConnectionConfig config(boolean needsSetup) {
    return new ConnectionConfig("connection", "example", "mysql", Env.DEV, 1,
        JsonNodeFactory.instance.objectNode(), Set.of(),
        JsonNodeFactory.instance.objectNode().put("setupRequired", needsSetup));
  }
}
