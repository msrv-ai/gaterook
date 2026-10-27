package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SshSettingsTest {

  @Test
  void anOmittedOrInheritedBastionLoginReusesTheConnectionUserAndSecretFields() throws Exception {
    for (boolean explicitInherit : new boolean[] {false, true}) {
      ObjectNode config = TestConfigs.password(22, null);
      ObjectNode jump = config.putObject("jump").put("host", "bastion.example.test");
      if (explicitInherit) {
        jump.putObject("credentials").put("method", "inherit");
      }
      SshSettings settings = settings(config);
      assertThat(settings.jump().target().username()).isEqualTo(settings.target().username());
      assertThat(settings.jump().target().port()).isEqualTo(22);
      assertThat(settings.jump().credentials()).isSameAs(settings.credentials());
      assertThat(settings.jump().credentials().password()).isEqualTo("/credentials/password");
      assertThat(settings.jump().hostKeys().pointer()).isEqualTo("/jump/hostKeys");
    }
  }

  @Test
  void anExplicitBastionLoginKeepsItsOwnUserAndSecretFields() throws Exception {
    for (String method : new String[] {"password", "privateKey", "vaultSshCa"}) {
      ObjectNode config = TestConfigs.password(22, null);
      config.putObject("jump").put("host", "bastion.example.test").put("username", "jumpuser")
          .putObject("credentials").put("method", method);
      SshSettings settings = settings(config);
      assertThat(settings.jump().target().username()).isEqualTo("jumpuser");
      assertThat(settings.jump().credentials().method()).isEqualTo(SshSettings.Method.of(method));
      assertThat(settings.jump().credentials().pointer()).isEqualTo("/jump/credentials");
    }
  }

  @Test
  void theConnectionCannotInheritItsOwnCredentials() {
    ObjectNode config = TestConfigs.password(22, null);
    config.withObjectProperty("credentials").put("method", "inherit");
    assertThatThrownBy(() -> settings(config)).isInstanceOf(ConnectorException.class)
        .hasMessageContaining("credentials.method");
  }

  private static SshSettings settings(ObjectNode config) throws ConnectorException {
    return SshSettings.of(TestConfigs.connection("ssh-settings", "test-ssh", Env.DEV, 1, config));
  }
}
