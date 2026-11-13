package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.TestResult;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Auto for PostgreSQL's TLS: the server's answer to an SSLRequest, asked before the login goes anywhere, decides. A
 * server without TLS gets an unencrypted connection; one with TLS a verified one; a certificate that fails is an error
 * and never a reason to retry without TLS. The fake server refuses every login, so the tests see where the startup
 * message (and the password after it) would have gone.
 */
class PostgresAutoTlsTest {

  private static final Path WORK = Path.of("target", "pg-auto-work").toAbsolutePath();
  private final JdbcSqlConnector connector = new JdbcSqlConnector(new PostgresDialect());
  private FakePostgres server;

  @AfterEach
  void stop() throws Exception {
    connector.close();
    if (server != null) {
      server.close();
    }
  }

  private static ConnectionConfig auto(Env env, String host, int port, String ca) {
    ConnectionConfig base = Fixtures.config("postgres", env, host, port, "app", "ro", null, null);
    ObjectNode c = (ObjectNode) base.config().deepCopy();
    ObjectNode connection = (ObjectNode) c.get("connection");
    connection.put("sslMode", "auto");
    if (ca != null) {
      connection.put("caCertificate", ca);
    }
    return new ConnectionConfig(base.id() + "-auto", base.name(), base.type(), env, 1, c, base.secretFields(), null);
  }

  private TestResult test(ConnectionConfig cfg) {
    return connector.test(cfg, Fixtures.Secrets.password("ro-pw-0001"), Fixtures.Context.of(cfg.env(), WORK));
  }

  @Test
  void aServerWithoutTlsGetsAnUnencryptedConnectionOnlyAfterItSaidSo() throws Exception {
    server = new FakePostgres(FakePostgres.Answer.PLAIN);

    TestResult r = test(auto(Env.DEV, "127.0.0.1", server.port(), null));

    // The fake refuses every password: the login reached it, unencrypted, after the server said it has no TLS.
    assertThat(r.error().code()).isEqualTo("sql.auth");
    assertThat(server.connections).hasSize(2);
    assertThat(await(server.connections.get(0), 2)).containsExactly("ssl-request", "closed");
    assertThat(server.connections.get(1)).containsExactly("startup plain user=ro database=app");
  }

  @Test
  void aServerWithTrustedTlsGetsAVerifiedConnection() throws Exception {
    server = new FakePostgres(FakePostgres.Answer.TLS);

    TestResult r = test(auto(Env.DEV, "localhost", server.port(), TestCertificate.localhost().pem));

    assertThat(r.error().code()).as(String.valueOf(r.error())).isEqualTo("sql.auth");
    // The question, then a connection that verified the certificate and the host name before the login.
    assertThat(server.connections.getLast()).containsExactly("ssl-request", "tls",
        "startup tls user=ro database=app");
    assertThat(server.events()).noneMatch(e -> e.startsWith("startup plain"));
  }

  @Test
  void anUntrustedCertificateIsAnErrorAndNothingGoesUnencrypted() throws Exception {
    server = new FakePostgres(FakePostgres.Answer.TLS);

    TestResult r = test(auto(Env.DEV, "localhost", server.port(), null));

    assertThat(r.ok()).isFalse();
    assertThat(r.error().code()).isEqualTo("sql.tls");
    assertThat(r.error().message()).contains("isn't signed by a trusted CA").contains("Auto")
        .contains("under Advanced");
    // No login anywhere, and no second try without TLS.
    assertThat(server.events()).noneMatch(e -> e.startsWith("startup"));
    assertThat(server.connections).hasSize(2);
  }

  @Test
  void aCertificateForAnotherHostIsAnErrorAndNothingGoesUnencrypted() throws Exception {
    server = new FakePostgres(FakePostgres.Answer.TLS);

    TestResult r = test(auto(Env.DEV, "127.0.0.1", server.port(), TestCertificate.localhost().pem));

    assertThat(r.error().code()).as(String.valueOf(r.error())).isEqualTo("sql.tls");
    assertThat(r.error().message()).contains("doesn't match the host 127.0.0.1").contains("Auto");
    assertThat(server.events()).noneMatch(e -> e.startsWith("startup"));
    assertThat(server.connections).hasSize(2);
  }

  @Test
  void anAnswerThatIsNeitherYesNorNoStopsBeforeTheLogin() throws Exception {
    for (FakePostgres.Answer answer : List.of(FakePostgres.Answer.OTHER, FakePostgres.Answer.ERROR,
        FakePostgres.Answer.CLOSE)) {
      try (FakePostgres fake = new FakePostgres(answer)) {
        TestResult r = test(auto(Env.DEV, "127.0.0.1", fake.port(), null));

        assertThat(r.ok()).as(answer.name()).isFalse();
        assertThat(r.error().message()).as(answer.name()).startsWith("Can't connect to 127.0.0.1:" + fake.port());
        assertThat(fake.connections).as(answer.name()).hasSize(1);
        if (answer == FakePostgres.Answer.ERROR) {
          assertThat(r.error().message()).contains("refused the connection: sorry, too many clients already");
        }
      }
    }
  }

  @Test
  void noAnswerInTimeIsATimeoutWithNothingElseSent() throws Exception {
    server = new FakePostgres(FakePostgres.Answer.SILENT);

    assertThatThrownBy(() -> PostgresTls.offered("127.0.0.1", server.port(), 300))
        .isInstanceOf(SQLException.class)
        .satisfies(e -> {
          assertThat(((SQLException) e).getSQLState()).startsWith("08");
          assertThat(e.getCause()).isInstanceOf(SocketTimeoutException.class);
        });
    assertThat(server.connections).hasSize(1);
  }

  @Test
  void aClosedPortIsRefusedAsPgjdbcWouldSay() throws Exception {
    int closed;
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closed = s.getLocalPort();
    }

    TestResult r = test(auto(Env.DEV, "127.0.0.1", closed, null));

    assertThat(r.error().code()).isEqualTo("sql.refused");
    assertThat(r.error().message()).isEqualTo("Connection refused on port " + closed + ". Is the database running?");
  }

  @Test
  void aPoolAsksOnceAndKeepsTheAnswer() throws Exception {
    server = new FakePostgres(FakePostgres.Answer.TLS);
    ConnectionConfig cfg = auto(Env.DEV, "localhost", server.port(), TestCertificate.localhost().pem);
    PostgresDialect dialect = new PostgresDialect();

    CredentialAwareDataSource.Transport transport = dialect.poolTransport(ConnectionSettings.of(cfg, false), WORK,
        SqlPools.SOCKET_TIMEOUT_MS, false);
    assertThat(server.connections).as("no I/O while the pool is made").isEmpty();
    Properties first = transport.properties();
    Properties second = transport.properties();

    assertThat(first).isSameAs(second).containsEntry("sslmode", "verify-full")
        .containsEntry("sslfactory", PostgresSslFactory.class.getName()).containsKey("sslrootcert");
    assertThat(server.connections).hasSize(1);

    try (FakePostgres plain = new FakePostgres(FakePostgres.Answer.PLAIN)) {
      ConnectionConfig open = auto(Env.DEV, "127.0.0.1", plain.port(), null);
      Properties p = dialect.poolTransport(ConnectionSettings.of(open, false), WORK, 5_000, false).properties();
      assertThat(p).containsEntry("sslmode", "disable").doesNotContainKey("sslfactory");
    }
  }

  @Test
  void aPoolThatCouldNotAskAsksAgainAtItsNextConnection() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    CredentialAwareDataSource.Transport once = CredentialAwareDataSource.Transport.once(() -> {
      if (asked.incrementAndGet() == 1) {
        throw new SQLException("asking whether it speaks TLS failed: Connection refused", "08001");
      }
      return new Properties();
    });

    assertThatThrownBy(once::properties).isInstanceOf(SQLException.class);
    assertThat(once.properties()).isSameAs(once.properties());
    assertThat(asked).hasValue(2);
  }

  @Test
  void newConnectionsStartWithAutoAndProdWithVerifyFullWhileStoredOnesKeepVerifyFull() {
    JsonNode sslMode = connector.manifest().configSchema().at("/properties/connection/properties/sslMode");

    assertThat(sslMode.path("default").asString()).isEqualTo("auto");
    assertThat(sslMode.at("/x-env-default/prod").asString()).isEqualTo("verify-full");
    assertThat(sslMode.path("enum").get(0).asString()).isEqualTo("auto");
    assertThat(sslMode.path("x-section").asString()).isEqualTo("/advanced");
    // A connection saved before Auto existed has no sslMode: it keeps verify-full, as it always had.
    ObjectNode stored = Fixtures.json("""
        {"connection":{"host":"db.example.test","port":5432,"databases":["app"]},"credentials":{"user":"ro"}}""");
    ConnectionConfig cfg = new ConnectionConfig("conn-stored", "stored", "postgres", Env.DEV, 1, stored,
        Set.of(ConnectionSettings.PASSWORD), null);
    assertThat(ConnectionSettings.of(cfg, false).sslMode()).isEqualTo("verify-full");
  }

  @Test
  void anUnresolvedAutoNeverReachesPgjdbcAsPlainText() throws Exception {
    ConnectionConfig cfg = auto(Env.DEV, "db.example.test", 5432, null);

    Properties p = new PostgresDialect().properties(ConnectionSettings.of(cfg, false), WORK, 5_000);

    assertThat(p).containsEntry("sslmode", "verify-full");
  }

  @Test
  void theTlsCheckSaysWhatAutoChose() {
    ConnectionConfig cfg = auto(Env.DEV, "db.example.test", 5432, null);
    ConnectionSettings s = ConnectionSettings.of(cfg, false);

    assertThat(TestProbe.tlsDetail(s.withTransport("verify-full"), new SqlProbe.Tls(true, "TLSv1.3")))
        .isEqualTo("Encrypted · TLSv1.3 · Auto: the server offers TLS, so its certificate and host name were verified");
    assertThat(TestProbe.tlsDetail(s.withTransport("disable"), new SqlProbe.Tls(false, null)))
        .isEqualTo("Not encrypted · Auto: the server doesn't offer TLS");
    assertThat(TestProbe.tlsDetail(s.withTransport("disable"), new SqlProbe.Tls(true, null)))
        .startsWith("Encrypted · Auto");
    // An explicit mode says only what happened.
    assertThat(TestProbe.tlsDetail(ConnectionSettings.of(Fixtures.config("postgres", Env.DEV, "h", 5432, "app",
        "ro", null, null), false), new SqlProbe.Tls(false, null))).isEqualTo("Not encrypted");
  }

  /** Waits for a connection of the fake to have recorded {@code n} events: it records them on its own thread. */
  static List<String> await(List<String> events, int n) throws InterruptedException {
    long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (events.size() < n && System.nanoTime() < until) {
      Thread.sleep(10);
    }
    return events;
  }
}
