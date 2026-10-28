package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import java.nio.file.Path;
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

  @Test
  void localKeyFilesUsePrivateKeyPolicyAndTheCorrectCredentialScope() throws Exception {
    ObjectNode config = TestConfigs.password(22, null);
    config.putObject("credentials").put("method", "privateKeyFile").put("privateKeyPath", "~/keys/target");
    config.putObject("jump").put("host", "bastion.example.test");
    SshSettings inherited = settings(config);
    assertThat(inherited.credentials().method()).isEqualTo(SshSettings.Method.PRIVATE_KEY);
    assertThat(inherited.credentials().privateKeyPath()).isEqualTo(Path.of(System.getProperty("user.home"), "keys/target"));
    assertThat(inherited.jump().credentials()).isSameAs(inherited.credentials());

    Path bastionKey = Path.of("target", "test-keys", "bastion").toAbsolutePath();
    config.withObjectProperty("jump").putObject("credentials").put("method", "privateKeyFile")
        .put("privateKeyPath", bastionKey.toString());
    SshSettings separate = settings(config);
    assertThat(separate.jump().credentials().privateKeyPath()).isEqualTo(bastionKey);
    assertThat(separate.jump().credentials().passphrase()).isEqualTo("/jump/credentials/passphrase");
    assertThat(config.at("/credentials/method").asString()).isEqualTo("privateKeyFile");
  }

  @Test
  void keyFileAuthenticationRequiresAnAbsolutePathWithoutControlCharacters() {
    for (String path : new String[] {"", "keys/id_ed25519", "~other/key", "/keys/\nkey", "/keys/\u200bkey", "/" + "a".repeat(4096)}) {
      ObjectNode config = TestConfigs.password(22, null);
      config.putObject("credentials").put("method", "privateKeyFile").put("privateKeyPath", path);
      assertThatThrownBy(() -> settings(config)).isInstanceOf(ConnectorException.class);
    }
    ObjectNode missing = TestConfigs.password(22, null);
    missing.putObject("credentials").put("method", "privateKeyFile");
    assertThatThrownBy(() -> settings(missing)).isInstanceOf(ConnectorException.class)
        .hasMessage("Enter the local private key file path.");
  }

  private static SshSettings settings(ObjectNode config) throws ConnectorException {
    return SshSettings.of(TestConfigs.connection("ssh-settings", "test-ssh", Env.DEV, 1, config));
  }
}
