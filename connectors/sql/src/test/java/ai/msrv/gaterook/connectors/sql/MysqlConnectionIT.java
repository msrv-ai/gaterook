package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/** TLS negotiation and RSA authentication against the disposable MySQL 8.4 server. */
@Tag("mysql-it")
class MysqlConnectionIT {

  @RegisterExtension
  static final LocalMysql MYSQL = LocalMysql.mysql();

  private static final Path WORK = Path.of("target", "mysql-connection-work").toAbsolutePath();
  private final JdbcSqlConnector connector = new JdbcSqlConnector(new MySqlDialect());

  @AfterEach
  void close() {
    connector.close();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "auto")
  void autoAndOmittedModeUseTlsWhenAvailable(String mode) {
    TestResult result = test(mode, null);

    assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
    assertThat(result.details().at("/tls/enabled").asBoolean()).isTrue();
    assertThat(result.details().at("/tls/version").asString()).startsWith("TLS");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "auto")
  void autoAndOmittedModeAuthenticateWithoutTlsUsingDefaultKeyRetrieval(String mode) throws Exception {
    withoutTls(() -> {
      // Empty the authentication cache so this connection must complete RSA authentication.
      MYSQL.exec("FLUSH PRIVILEGES");
      try (Connection c = MYSQL.root(); Statement st = c.createStatement();
          var rs = st.executeQuery("SELECT plugin FROM mysql.user WHERE user = 'ro' AND host = '%'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("caching_sha2_password");
      }

      TestResult result = test(mode, null);

      assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
      assertThat(result.details().at("/tls/enabled").asBoolean()).isFalse();
      assertThat(result.identity()).isEqualTo("ro@%");
    });
  }

  @Test
  void explicitKeyRetrievalOffStillRefusesUncachedAuthenticationWithoutTls() throws Exception {
    withoutTls(() -> {
      MYSQL.exec("FLUSH PRIVILEGES");

      TestResult result = test("auto", false);

      assertThat(result.ok()).isFalse();
      assertThat(result.error().technical()).contains("Public Key Retrieval is not allowed");
    });
  }

  @Test
  void requiredTlsDoesNotFallBackWhenTheServerHasNoTls() throws Exception {
    withoutTls(() -> {
      TestResult result = test("require", null);

      assertThat(result.ok()).isFalse();
      assertThat(result.error().technical()).contains("SSL Connection required");
    });
  }

  @Test
  void explicitDisableStillConnectsWithoutTlsWhenTheServerSupportsIt() {
    TestResult result = test("disable", null);

    assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
    assertThat(result.details().at("/tls/enabled").asBoolean()).isFalse();
  }

  private TestResult test(String mode, Boolean retrieval) {
    ObjectNode advanced = retrieval == null ? null : Fixtures.object().put("allowPublicKeyRetrieval", retrieval);
    ConnectionConfig cfg = Fixtures.config("mysql", Env.DEV, "127.0.0.1", MYSQL.port(), "app", "ro", advanced,
        null);
    ObjectNode connection = (ObjectNode) cfg.config().get("connection");
    if (mode == null) {
      connection.remove("sslMode");
    } else {
      connection.put("sslMode", mode);
    }
    return connector.test(cfg, Fixtures.Secrets.password(LocalMysql.password("ro")),
        Fixtures.Context.of(Env.DEV, WORK));
  }

  /** Disable TLS for new sessions, then restore the disposable server's original TLS context. */
  private static void withoutTls(CheckedRunnable check) throws Exception {
    try (Connection c = MYSQL.root(); Statement st = c.createStatement()) {
      String certificate;
      try (var rs = st.executeQuery("SELECT @@GLOBAL.ssl_cert")) {
        rs.next();
        certificate = rs.getString(1);
      }
      try {
        st.execute("SET GLOBAL ssl_cert = 'gaterook-missing-tls-test-certificate.pem'");
        st.execute("ALTER INSTANCE RELOAD TLS NO ROLLBACK ON ERROR");
        check.run();
      } finally {
        try (PreparedStatement restore = c.prepareStatement("SET GLOBAL ssl_cert = ?")) {
          restore.setString(1, certificate);
          restore.execute();
        }
        st.execute("ALTER INSTANCE RELOAD TLS");
      }
    }
  }

  @FunctionalInterface
  private interface CheckedRunnable {
    void run() throws Exception;
  }
}
