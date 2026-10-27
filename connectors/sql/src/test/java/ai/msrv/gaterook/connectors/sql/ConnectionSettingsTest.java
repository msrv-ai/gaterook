package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ConnectionSettingsTest {

  @Test
  void mysqlDefaultsMatchTheFormAndAllowAutomaticTlsAndKeyRetrieval() throws Exception {
    ConnectionSettings settings = ConnectionSettings.of(config("mysql"), true);
    JsonNode schema = new JdbcSqlConnector(new MySqlDialect()).manifest().configSchema();

    assertThat(settings.sslMode()).isEqualTo("auto")
        .isEqualTo(schema.at("/properties/connection/properties/sslMode/default").asString());
    assertThat(settings.allowPublicKeyRetrieval()).isTrue()
        .isEqualTo(schema.at("/properties/advanced/properties/allowPublicKeyRetrieval/default").asBoolean());
    assertThat(new MySqlDialect().properties(settings, null, 5_000))
        .containsEntry("sslMode", "PREFERRED").containsEntry("allowPublicKeyRetrieval", "true");
  }

  @Test
  void postgresStillDefaultsToCertificateAndHostnameVerification() {
    ConnectionSettings settings = ConnectionSettings.of(config("postgres"), false);
    JsonNode schema = new JdbcSqlConnector(new PostgresDialect()).manifest().configSchema();

    assertThat(settings.sslMode()).isEqualTo("verify-full")
        .isEqualTo(schema.at("/properties/connection/properties/sslMode/default").asString());
    assertThat(settings.allowPublicKeyRetrieval()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"auto,PREFERRED", "disable,DISABLED", "require,REQUIRED", "verify-ca,VERIFY_CA",
      "verify-full,VERIFY_IDENTITY"})
  void explicitConnectionChoicesOverrideDefaults(String mode, String driverMode) throws Exception {
    ConnectionConfig config = config("mysql");
    ((ObjectNode) config.config().get("connection")).put("sslMode", mode);
    ((ObjectNode) config.config()).putObject("advanced").put("allowPublicKeyRetrieval", false);

    Properties properties = new MySqlDialect().properties(ConnectionSettings.of(config, true), null, 5_000);

    assertThat(properties).containsEntry("sslMode", driverMode).containsEntry("allowPublicKeyRetrieval", "false");
  }

  @ParameterizedTest
  @ValueSource(strings = {"auto", "disable", "require"})
  void modesWithoutCertificateVerificationIgnoreAnUnusedCa(String mode) throws Exception {
    ConnectionConfig config = config("mysql");
    ((ObjectNode) config.config().get("connection")).put("sslMode", mode)
        .put("caCertificate", "unused certificate from an earlier configuration");

    assertThat(new MySqlDialect().properties(ConnectionSettings.of(config, true), null, 5_000))
        .doesNotContainKey("trustCertificateKeyStoreUrl");
  }

  @ParameterizedTest
  @ValueSource(strings = {"verify-ca", "verify-full"})
  void verifyingModesStillRejectAnInvalidCa(String mode) {
    ConnectionConfig config = config("mysql");
    ((ObjectNode) config.config().get("connection")).put("sslMode", mode).put("caCertificate", "invalid");

    assertThatThrownBy(() -> new MySqlDialect().properties(ConnectionSettings.of(config, true), null, 5_000))
        .isInstanceOf(IOException.class).hasMessageContaining("certificate");
  }

  private static ConnectionConfig config(String type) {
    ConnectionConfig config = Fixtures.config(type, Env.DEV, "127.0.0.1", "mysql".equals(type) ? 3306 : 5432,
        "app", "app_ro", null, null);
    ((ObjectNode) config.config().get("connection")).remove("sslMode");
    return config;
  }
}
