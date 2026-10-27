package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.sql.SqlExecutor.BoundSql;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection, health, TLS, pools, short-lived logins and Rotate at the source against a real PostgreSQL 16
 * (report 04 §5, §6.5, §6.7, §7.3).
 */
class PostgresConnectionIT {

  @RegisterExtension
  static final LocalPostgres PG = new LocalPostgres();

  private static final JdbcSqlConnector CONNECTOR = new JdbcSqlConnector(new PostgresDialect());
  private static final Path WORK = Path.of("target", "pg-work").toAbsolutePath();

  @AfterAll
  static void close() {
    CONNECTOR.close();
  }

  private static ConnectionConfig cfg(String user, Env env) {
    return Fixtures.config("postgres", env, "127.0.0.1", PG.port(), "app", user, null, null);
  }

  private static TestResult test(String user, Env env) {
    return CONNECTOR.test(cfg(user, env), Fixtures.Secrets.password(LocalPostgres.password(user)),
        Fixtures.Context.of(env, WORK));
  }

  private static Hint hint(TestResult r, String code) {
    return r.hints().stream().filter(h -> h.code().equals(code)).findFirst().orElse(null);
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aReadOnlyUserReadsAndTheTestFillsTheMetadataClassifyNeeds() {
    TestResult r = test("ro", Env.PROD);

    assertThat(r.ok()).isTrue();
    assertThat(r.serverVersion()).startsWith("PostgreSQL 16.");
    assertThat(r.identity()).isEqualTo("ro");
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(r.permissions().read()).matches("SELECT on \\d+ tables");
    assertThat(r.permissions().write()).isNull();
    assertThat(hint(r, "sql.user-can-write")).isNull();
    assertThat(r.hints()).noneMatch(h -> h.severity() == Severity.DANGER);
    assertThat(r.checks()).extracting(TestResult.Check::name)
        .containsExactly("connect", "latency", "tls", "permissions", "allowlist");
    assertThat(r.metadataUpdate().path("serverVersion").asString()).isEqualTo("PostgreSQL 16");
    assertThat(r.metadataUpdate().path("builtinFunctions").toString()).contains("\"nextval\"", "\"now\"",
        "\"pg_terminate_backend\"").doesNotContain("\"f_write\"");
    // TLS is off on this connection, and this is prod.
    assertThat(hint(r, "sql.tls-off").severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void aUserThatCanWriteIsFineOnDevAndAWarningOnProd() {
    TestResult dev = test("rw", Env.DEV);
    Hint devHint = hint(dev, "sql.user-can-write");
    assertThat(devHint.severity()).isEqualTo(Severity.INFO);
    assertThat(devHint.text()).isEqualTo("This user can write. Fine for dev: every write still waits for approval. "
        + "For stage and prod use a read-only user or a replica.");
    assertThat(dev.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(dev.permissions().write()).isEqualTo("INSERT, UPDATE, DELETE on public.*");
    assertThat(dev.details().at("/snippets/sql.user-can-write/text").asString()).contains("CREATE ROLE gw_reader")
        .contains("\\password gw_reader").doesNotContain("PASSWORD '");

    TestResult prod = test("rw", Env.PROD);
    assertThat(hint(prod, "sql.user-can-write").severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void aSuperuserIsADanger() {
    TestResult r = CONNECTOR.test(cfg("postgres", Env.DEV), Fixtures.Secrets.password(
        LocalPostgres.SUPERUSER_PASSWORD), Fixtures.Context.of(Env.DEV, WORK));

    assertThat(r.ok()).isTrue();
    Hint h = hint(r, "sql.superuser");
    assertThat(h.severity()).isEqualTo(Severity.DANGER);
    assertThat(h.text()).contains("superuser");
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(r.permissions().admin()).contains("SUPERUSER");
    assertThat(r.details().at("/snippets/sql.superuser/text").asString()).contains("GRANT SELECT");
  }

  @Test
  void aRoleThatSignalsOtherSessionsIsADanger() {
    TestResult r = test("signal", Env.DEV);

    Hint h = hint(r, "sql.dangerous-privilege");
    assertThat(h.severity()).isEqualTo(Severity.DANGER);
    assertThat(h.text()).isEqualTo("signal has pg_signal_backend: it can cancel and stop other sessions. Use a user "
        + "without it.");
    assertThat(r.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
  }

  @Test
  void anExtensionThatActsOutsideTheTransactionIsADanger() throws Exception {
    try (Connection c = PG.connect("postgres", "postgres", LocalPostgres.SUPERUSER_PASSWORD)) {
      c.createStatement().execute("CREATE DATABASE app_ext");
    }
    try (Connection c = PG.connect("app_ext", "postgres", LocalPostgres.SUPERUSER_PASSWORD)) {
      c.createStatement().execute("CREATE EXTENSION dblink");
    }
    ConnectionConfig cfg = Fixtures.config("postgres", Env.DEV, "127.0.0.1", PG.port(), "app_ext", "ro", null,
        null);

    TestResult r = CONNECTOR.test(cfg, Fixtures.Secrets.password(LocalPostgres.password("ro")),
        Fixtures.Context.of(Env.DEV, WORK));

    Hint h = hint(r, "sql.dangerous-extension");
    assertThat(h.severity()).isEqualTo(Severity.DANGER);
    assertThat(h.text()).startsWith("dblink is installed in app_ext: its functions run code outside the read-only "
        + "transaction.");
  }

  @Test
  void aSchemaOfTheAllowlistThatTheUserCantSeeIsSaid() {
    ObjectNode advanced = Fixtures.object();
    advanced.putArray("schemaAllowlist").add("public").add("secret_stuff").add("nope");
    ConnectionConfig cfg = Fixtures.config("postgres", Env.DEV, "127.0.0.1", PG.port(), "app", "ro", advanced, null);

    TestResult r = CONNECTOR.test(cfg, Fixtures.Secrets.password(LocalPostgres.password("ro")),
        Fixtures.Context.of(Env.DEV, WORK));

    assertThat(hint(r, "sql.schema-not-found").text()).isEqualTo("Schemas secret_stuff, nope not found for ro.");
    assertThat(r.checks()).filteredOn(c -> c.name().equals("allowlist")).singleElement()
        .satisfies(c -> assertThat(c.ok()).isFalse());
  }

  @Test
  void whatKeepsTheTestFromConnectingIsSaidInTheKitsWords() throws Exception {
    TestResult wrongPassword = CONNECTOR.test(cfg("ro", Env.DEV), Fixtures.Secrets.password("nope-nope-nope"),
        Fixtures.Context.of(Env.DEV, WORK));
    assertThat(wrongPassword.ok()).isFalse();
    assertThat(wrongPassword.error().code()).isEqualTo("sql.auth");
    assertThat(wrongPassword.error().message()).isEqualTo("Password authentication failed for user ro.");
    assertThat(wrongPassword.error().technical()).doesNotContain("nope-nope-nope");

    ConnectionConfig noDb = Fixtures.config("postgres", Env.DEV, "127.0.0.1", PG.port(), "missing", "ro", null, null);
    TestResult missing = CONNECTOR.test(noDb, Fixtures.Secrets.password(LocalPostgres.password("ro")),
        Fixtures.Context.of(Env.DEV, WORK));
    assertThat(missing.error().message()).isEqualTo("Database missing doesn't exist.");

    int closed;
    try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      closed = s.getLocalPort();
    }
    ConnectionConfig refused = Fixtures.config("postgres", Env.DEV, "127.0.0.1", closed, "app", "ro", null, null);
    TestResult r = CONNECTOR.test(refused, Fixtures.Secrets.password("x"), Fixtures.Context.of(Env.DEV, WORK));
    assertThat(r.error().code()).isEqualTo("sql.refused");
    assertThat(r.error().message()).isEqualTo("Connection refused on port " + closed + ". Is the database running?");
  }

  // ── TLS ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static ConnectionConfig tls(String host, String mode, String ca) {
    ConnectionConfig base = Fixtures.config("postgres", Env.PROD, host, PG.port(), "app", "ro", null, null);
    ObjectNode c = (ObjectNode) base.config().deepCopy();
    ObjectNode connection = (ObjectNode) c.get("connection");
    connection.put("sslMode", mode);
    if (ca != null) {
      connection.put("caCertificate", ca);
    }
    return new ConnectionConfig(base.id() + "-tls-" + host + mode, base.name(), base.type(), base.env(), 1, c,
        base.secretFields(), null);
  }

  @Test
  void verifyFullTrustsThePrivateCaAndChecksTheHostName() throws Exception {
    Fixtures.Secrets ro = Fixtures.Secrets.password(LocalPostgres.password("ro"));
    String ca = PG.certificatePem();

    TestResult ok = CONNECTOR.test(tls("localhost", "verify-full", ca), ro, Fixtures.Context.of(Env.PROD, WORK));
    assertThat(ok.ok()).as(String.valueOf(ok.error())).isTrue();
    assertThat(ok.details().at("/tls/enabled").asBoolean()).isTrue();
    assertThat(ok.details().at("/tls/version").asString()).startsWith("TLSv1.");
    assertThat(hint(ok, "sql.tls-off")).isNull();

    TestResult wrongHost = CONNECTOR.test(tls("127.0.0.1", "verify-full", ca), ro,
        Fixtures.Context.of(Env.PROD, WORK));
    assertThat(wrongHost.error().code()).as(String.valueOf(wrongHost.error())).isEqualTo("sql.tls");
    assertThat(wrongHost.error().message()).contains("doesn't match the host");

    TestResult noCa = CONNECTOR.test(tls("localhost", "verify-full", null), ro, Fixtures.Context.of(Env.PROD, WORK));
    assertThat(noCa.error().code()).as(String.valueOf(noCa.error())).isEqualTo("sql.tls");

    assertThat(CONNECTOR.test(tls("127.0.0.1", "verify-ca", ca), ro, Fixtures.Context.of(Env.PROD, WORK)).ok())
        .isTrue();
    assertThat(CONNECTOR.test(tls("127.0.0.1", "require", null), ro, Fixtures.Context.of(Env.PROD, WORK)).ok())
        .isTrue();
  }

  @Test
  void callsOverTlsWork() throws Exception {
    ConnectionConfig cfg = tls("localhost", "verify-full", PG.certificatePem());
    ToolResult r = CONNECTOR.runRead(cfg, ConnectionSettings.of(cfg, false), Fixtures.Secrets.password(
        LocalPostgres.password("ro")), Fixtures.Context.of(Env.PROD, WORK), BoundSql.plain(
        "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()"), 1);

    assertThat(r.table().rows().get(0).get(0).asBoolean()).isTrue();
  }

  // ── Health ──────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void healthIsADirectSelectOne() {
    HealthResult ok = CONNECTOR.health(cfg("ro", Env.DEV), Fixtures.Secrets.password(LocalPostgres.password("ro")),
        Fixtures.Context.of(Env.DEV, WORK));
    assertThat(ok.state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(CONNECTOR.pools().current(cfg("ro", Env.DEV).id())).isNull();

    HealthResult bad = CONNECTOR.health(cfg("ro", Env.DEV), Fixtures.Secrets.password("wrong-wrong"),
        Fixtures.Context.of(Env.DEV, WORK));
    assertThat(bad.state()).isEqualTo(HealthResult.State.UNREACHABLE);
    assertThat(bad.message()).isEqualTo("Password authentication failed for user ro.");
  }

  // ── Pools ───────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aPoolIsLazyAndBelongsToOneVersionOfItsConnection() throws Exception {
    ConnectionConfig v1 = Fixtures.withVersion(Fixtures.config("postgres", Env.DEV, "127.0.0.1", PG.port(), "app",
        "ro", null, null), 1);
    Fixtures.Secrets ro = Fixtures.Secrets.password(LocalPostgres.password("ro"));
    ConnectionSettings s = ConnectionSettings.of(v1, false);
    String id = v1.id() + "-pools";
    ConnectionConfig first = new ConnectionConfig(id, v1.name(), v1.type(), v1.env(), 1, v1.config(),
        v1.secretFields(), null);

    CONNECTOR.runRead(first, s, ro, Fixtures.Context.of(Env.DEV, WORK), BoundSql.plain("SELECT 1"), 1);
    SqlPools.Pool p1 = CONNECTOR.pools().current(id);
    assertThat(p1.server().full()).startsWith("PostgreSQL 16.");

    ConnectionConfig second = Fixtures.withVersion(first, 2);
    CONNECTOR.runRead(second, s, ro, Fixtures.Context.of(Env.DEV, WORK), BoundSql.plain("SELECT 1"), 1);
    SqlPools.Pool p2 = CONNECTOR.pools().current(id);
    assertThat(p2).isNotSameAs(p1);
    waitFor(() -> p1.hikari.isClosed());

    CONNECTOR.onConnectionRemoved(id);
    assertThat(CONNECTOR.pools().current(id)).isNull();
    waitFor(() -> p2.hikari.isClosed());
  }

  @Test
  void aReplacedPoolLetsItsRunningCallFinish() throws Exception {
    ConnectionConfig base = cfg("ro", Env.DEV);
    ConnectionConfig v1 = new ConnectionConfig(base.id() + "-drain", base.name(), base.type(), base.env(), 1,
        base.config(), base.secretFields(), null);
    Fixtures.Secrets ro = Fixtures.Secrets.password(LocalPostgres.password("ro"));
    ConnectionSettings s = ConnectionSettings.of(v1, false);
    try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var running = threads.submit(() -> CONNECTOR.runRead(v1, s, ro, Fixtures.Context.of(Env.DEV, WORK),
          BoundSql.plain("SELECT 7 FROM pg_sleep(1.5)"), 1));
      waitFor(() -> CONNECTOR.pools().current(v1.id()) != null && CONNECTOR.pools().current(v1.id()).active() == 1);
      SqlPools.Pool old = CONNECTOR.pools().current(v1.id());

      CONNECTOR.runRead(Fixtures.withVersion(v1, 2), s, ro, Fixtures.Context.of(Env.DEV, WORK),
          BoundSql.plain("SELECT 1"), 1);

      assertThat(old.hikari.isClosed()).isFalse();
      assertThat(running.get(10, java.util.concurrent.TimeUnit.SECONDS).table().rows().get(0).get(0).asInt())
          .isEqualTo(7);
      waitFor(() -> old.hikari.isClosed());
    }
  }

  @Test
  void aNewPoolHasNoConnectionUntilTheFirstCall() {
    try (SqlPools pools = new SqlPools(new PostgresDialect())) {
      ConnectionConfig cfg = cfg("ro", Env.DEV);
      SqlPools.Pool p = assertDoesNotThrow(() -> pools.pool(cfg, ConnectionSettings.of(cfg, false),
          new Credentials("ro", LocalPostgres.password("ro").toCharArray(), "1", null), WORK));
      assertThat(p.total()).isZero();
    }
  }

  @Test
  void idleConnectionsAndIdlePoolsGoAway() throws Exception {
    try (SqlPools pools = new SqlPools(new PostgresDialect())) {
      ConnectionConfig cfg = cfg("ro", Env.DEV);
      ConnectionSettings s = ConnectionSettings.of(cfg, false);
      SqlPools.Pool p = pools.pool(cfg, s, new Credentials("ro", LocalPostgres.password("ro").toCharArray(), "1",
          null), WORK);
      // Connections: at most poolSize, none kept when idle, gone after a minute without a call.
      assertThat(p.hikari.getMaximumPoolSize()).isEqualTo(2);
      assertThat(p.hikari.getMinimumIdle()).isZero();
      assertThat(Duration.ofMillis(p.hikari.getIdleTimeout())).isEqualTo(Duration.ofMinutes(1));
      assertThat(Duration.ofMillis(p.hikari.getMaxLifetime())).isEqualTo(SqlPools.MAX_LIFETIME);
      try (Connection c = p.borrow()) {
        c.rollback();
        // A pool with a call running stays, however long it was idle before.
        pools.sweep(Duration.ZERO);
        assertThat(pools.current(cfg.id())).isSameAs(p);
      }

      // The whole pool goes once nobody used it for ten minutes (here: at once).
      pools.sweep(Duration.ofMinutes(10));
      assertThat(pools.current(cfg.id())).isSameAs(p);
      pools.sweep(Duration.ZERO);
      assertThat(pools.current(cfg.id())).isNull();
      waitFor(() -> p.hikari.isClosed());
    }
  }

  @Test
  void shortLivedLoginsGetPoolsThatEndBeforeTheirLease() throws Exception {
    Instant until = Instant.now().plus(5, ChronoUnit.MINUTES);
    PG.exec("CREATE ROLE ro_v1 LOGIN PASSWORD 'v1-pw-0001' VALID UNTIL '" + until + "'",
        "CREATE ROLE ro_v2 LOGIN PASSWORD 'v2-pw-0001' VALID UNTIL '" + until + "'",
        "GRANT USAGE ON SCHEMA public TO ro_v1, ro_v2", "GRANT SELECT ON orders TO ro_v1, ro_v2");
    AtomicInteger lease = new AtomicInteger();
    SecretResolver vault = Fixtures.Secrets.dynamic(() -> lease.get() == 0
        ? Fixtures.Secrets.login("ro_v1", "v1-pw-0001", "lease-1", until)
        : Fixtures.Secrets.login("ro_v2", "v2-pw-0001", "lease-2", until));
    ConnectionConfig base = cfg("ignored", Env.DEV);
    ConnectionConfig cfg = new ConnectionConfig(base.id() + "-vault", base.name(), base.type(), base.env(), 1,
        base.config(), base.secretFields(), null);
    ConnectionSettings s = ConnectionSettings.of(cfg, false);

    ToolResult first = CONNECTOR.runRead(cfg, s, vault, Fixtures.Context.of(Env.DEV, WORK),
        BoundSql.plain("SELECT current_user"), 1);
    assertThat(first.table().rows().get(0).get(0).asString()).isEqualTo("ro_v1");
    SqlPools.Pool p1 = CONNECTOR.pools().current(cfg.id());
    assertThat(Duration.ofMillis(p1.hikari.getMaxLifetime())).isLessThanOrEqualTo(Duration.ofMinutes(4));

    lease.set(1);
    ToolResult second = CONNECTOR.runRead(cfg, s, vault, Fixtures.Context.of(Env.DEV, WORK),
        BoundSql.plain("SELECT current_user"), 1);
    assertThat(second.table().rows().get(0).get(0).asString()).isEqualTo("ro_v2");
    assertThat(CONNECTOR.pools().current(cfg.id())).isNotSameAs(p1);
    waitFor(() -> p1.hikari.isClosed());
  }

  @Test
  void aNewValueOfTheSameLoginEvictsThePoolsConnectionsSoftly() throws Exception {
    PG.exec("CREATE ROLE ro_soft LOGIN PASSWORD 'soft-pw-0001'", "GRANT USAGE ON SCHEMA public TO ro_soft");
    ConnectionConfig base = cfg("ro_soft", Env.DEV);
    ConnectionConfig cfg = new ConnectionConfig(base.id() + "-soft", base.name(), base.type(), base.env(), 1,
        base.config(), base.secretFields(), null);
    ConnectionSettings s = ConnectionSettings.of(cfg, false);
    Fixtures.Secrets secrets = Fixtures.Secrets.password("soft-pw-0001");
    CONNECTOR.runRead(cfg, s, secrets, Fixtures.Context.of(Env.DEV, WORK), BoundSql.plain("SELECT 1"), 1);
    SqlPools.Pool pool = CONNECTOR.pools().current(cfg.id());

    PG.exec("ALTER ROLE ro_soft PASSWORD 'soft-pw-0002'");
    secrets.set("soft-pw-0002", "2");
    CONNECTOR.runRead(cfg, s, secrets, Fixtures.Context.of(Env.DEV, WORK), BoundSql.plain("SELECT 1"), 1);

    assertThat(CONNECTOR.pools().current(cfg.id())).isSameAs(pool);
    assertThat(pool.source.current().version()).isEqualTo("2");
  }

  // ── Rotate at the source ────────────────────────────────────────────────────────────────────────────────

  @Test
  void rotateSetsTheNewPasswordOfTheConnectionsOwnUser() throws Exception {
    ConnectionConfig cfg = cfg("rotator", Env.DEV);
    assertThat(CONNECTOR.canRotate(cfg, ConnectionSettings.PASSWORD)).isTrue();
    char[] next = "rotated-pw-000000002".toCharArray();

    CONNECTOR.rotate(cfg, Fixtures.Secrets.password(LocalPostgres.password("rotator")), ConnectionSettings.PASSWORD,
        next, Fixtures.Context.of(Env.DEV, WORK));

    try (Connection c = PG.connect("app", "rotator", "rotated-pw-000000002")) {
      assertThat(c.isValid(2)).isTrue();
    }
    assertThatThrownBy(() -> PG.connect("app", "rotator", LocalPostgres.password("rotator")))
        .isInstanceOf(SQLException.class);
    // The server stored a SCRAM verifier, never the password itself.
    try (Connection c = PG.superuser(); var rs = c.createStatement().executeQuery(
        "SELECT rolpassword FROM pg_authid WHERE rolname = 'rotator'")) {
      rs.next();
      assertThat(rs.getString(1)).startsWith("SCRAM-SHA-256$").doesNotContain("rotated-pw");
    }
    assertThatThrownBy(() -> CONNECTOR.rotate(cfg, Fixtures.Secrets.password("rotated-pw-000000002"),
        ConnectionSettings.PASSWORD, "short".toCharArray(), Fixtures.Context.of(Env.DEV, WORK)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────

  private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > until) {
        throw new AssertionError("Condition not met within 10 s");
      }
      Thread.sleep(50);
    }
  }

  private static <T> T assertDoesNotThrow(java.util.concurrent.Callable<T> c) {
    try {
      return c.call();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
