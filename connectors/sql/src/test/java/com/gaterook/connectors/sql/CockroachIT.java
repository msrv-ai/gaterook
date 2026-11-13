package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.sql.SqlExecutor.BoundSql;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * CockroachDB through the PostgreSQL connector: one node of the cockroach Compose profile, with TLS and password
 * sign-in (start it with {@code scripts/test-services.sh up cockroach}, then use {@code -Pservices,cockroach-it}).
 * What keeps a read from writing is the READ ONLY transaction, not the user's grants: reads run as the admin user.
 */
@Tag("cockroach-it")
class CockroachIT {

  static final String DATABASE = "gaterook_crdb_it";
  static final String READER = "gaterook_reader";
  static final String READER_PASSWORD = "cockroach-reader-pw-0001";
  static final Path WORK = Path.of("target", "cockroach-work").toAbsolutePath();
  static final JdbcSqlConnector CONNECTOR = new JdbcSqlConnector(new PostgresDialect());
  static String ca;

  @BeforeAll
  static void seed() throws Exception {
    assumeTrue(TestServices.enabled(), "CockroachDB integration tests need the Compose test services");
    Path caFile = TestServices.file("run/cockroach-certs/ca.crt");
    assumeTrue(Files.isReadable(caFile), "Start the cockroach profile: scripts/test-services.sh up cockroach");
    ca = Files.readString(caFile);
    try (Connection c = admin("defaultdb"); Statement st = c.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS " + DATABASE + " CASCADE");
      st.execute("CREATE DATABASE " + DATABASE);
      st.execute("DROP USER IF EXISTS " + READER);
      st.execute("CREATE USER " + READER + " WITH PASSWORD '" + READER_PASSWORD + "'");
    }
    try (Connection c = admin(DATABASE); Statement st = c.createStatement()) {
      st.execute("CREATE TABLE orders (id INT8 PRIMARY KEY, customer STRING NOT NULL, amount DECIMAL(12,2), "
          + "paid BOOL DEFAULT false, placed TIMESTAMPTZ, payload BYTES, attrs JSONB, tags STRING[], "
          + "ref UUID DEFAULT gen_random_uuid(), INDEX orders_customer (customer))");
      st.execute("INSERT INTO orders (id, customer, amount, paid, placed, payload, attrs, tags) VALUES "
          + "(1, 'ada', 12.30, true, '2026-01-02 03:04:05+02', b'\\x01\\x02', '{\"tier\": \"gold\"}', "
          + "ARRAY['a', 'b']), (2, 'bob', 45.60, false, NULL, NULL, NULL, NULL)");
      st.execute("CREATE TABLE items (id INT8 PRIMARY KEY, order_id INT8 REFERENCES orders (id))");
      st.execute("CREATE VIEW order_names AS SELECT id, customer FROM orders");
      st.execute("CREATE SEQUENCE ticket_seq");
      // CockroachDB, like PostgreSQL before 15, lets every user create tables in public: a reader may not.
      st.execute("REVOKE CREATE ON SCHEMA public FROM public");
      st.execute("GRANT CONNECT ON DATABASE " + DATABASE + " TO " + READER);
      st.execute("GRANT SELECT ON TABLE orders, items, order_names TO " + READER);
    }
  }

  @AfterAll
  static void close() {
    CONNECTOR.close();
  }

  static Connection admin(String database) throws Exception {
    Properties p = new Properties();
    p.setProperty("user", "gaterook");
    p.setProperty("password", TestServices.value("GW_COCKROACH_PASSWORD"));
    p.setProperty("sslmode", "verify-full");
    p.setProperty("sslrootcert", TestServices.file("run/cockroach-certs/ca.crt").toString());
    p.setProperty("connectTimeout", "5");
    return new org.postgresql.Driver().connect("jdbc:postgresql://127.0.0.1:" + TestServices.port("GW_COCKROACH_PORT")
        + "/" + database, p);
  }

  static ConnectionConfig config(String user, Env env, ObjectNode advanced) {
    ConnectionConfig base = Fixtures.config("postgres", env, "127.0.0.1", TestServices.port("GW_COCKROACH_PORT"),
        DATABASE, user, advanced, null);
    ObjectNode c = (ObjectNode) base.config().deepCopy();
    ((ObjectNode) c.get("connection")).put("sslMode", "verify-full").put("caCertificate", ca);
    return new ConnectionConfig(base.id() + "-crdb-" + user, base.name(), base.type(), base.env(), 1, c,
        base.secretFields(), null);
  }

  static Fixtures.Secrets secrets(String user) {
    return Fixtures.Secrets.password(user.equals(READER) ? READER_PASSWORD
        : TestServices.value("GW_COCKROACH_PASSWORD"));
  }

  static Fixtures.Context ctx() {
    return Fixtures.Context.of(Env.DEV, WORK);
  }

  static ToolResult query(ConnectionConfig cfg, String sql) throws Exception {
    ToolCall call = Fixtures.sql(SqlTools.QUERY, sql);
    assertThat(CONNECTOR.classify(call, cfg, Fixtures.policy(cfg.env(), null)).kind())
        .isEqualTo(Classification.Kind.READ);
    return CONNECTOR.execute(call, cfg, secrets(cfg.string("/credentials/user")), ctx());
  }

  static long count(String sql) throws Exception {
    try (Connection c = admin(DATABASE); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void testConnectionVerifiesTlsAndReadsTheReadersPermissions() {
    TestResult t = CONNECTOR.test(config(READER, Env.PROD, null), secrets(READER), Fixtures.Context.of(Env.PROD,
        WORK));
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).contains("CockroachDB");
    assertThat(t.details().at("/tls/enabled").asBoolean()).isTrue();
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(CONNECTOR.health(config(READER, Env.DEV, null), secrets(READER), ctx()).state())
        .isEqualTo(HealthResult.State.HEALTHY);
    TestResult admin = CONNECTOR.test(config("gaterook", Env.DEV, null), secrets("gaterook"), ctx());
    assertThat(admin.ok()).as(String.valueOf(admin.error())).isTrue();
    assertThat(admin.permissions().level()).isNotEqualTo(TestResult.Level.READ);
  }

  @Test
  void queriesReturnCockroachTypes() throws Exception {
    ToolResult r = query(config(READER, Env.DEV, null), "SELECT id, customer, amount, paid, placed, payload, attrs, "
        + "tags, ref FROM orders ORDER BY id");
    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).asInt()).isEqualTo(1);
    assertThat(row.get(1).asString()).isEqualTo("ada");
    assertThat(row.get(2).asString()).isEqualTo("12.30");
    assertThat(row.get(3).asBoolean()).isTrue();
    assertThat(row.get(4).asString()).startsWith("2026-01-02T01:04:05");
    assertThat(row.get(5).asString()).isEqualTo("\\x0102");
    assertThat(row.get(6).toString()).contains("gold");
    assertThat(row.get(7).toString()).contains("a", "b");
    assertThat(row.get(8).asString()).hasSize(36);
    assertThat(r.table().rows()).hasSize(2);
  }

  @Test
  void listsAndDescribesTablesViewsKeysAndIndexes() throws Exception {
    ConnectionConfig cfg = config(READER, Env.DEV, null);
    ToolCall list = Fixtures.call(SqlTools.LIST_SCHEMAS, Fixtures.object());
    CONNECTOR.classify(list, cfg, Fixtures.policy(Env.DEV, null));
    ToolResult schemas = CONNECTOR.execute(list, cfg, secrets(READER), ctx());
    assertThat(String.valueOf(schemas.structuredContent())).contains("public", "orders", "items", "order_names");
    ToolResult described = CONNECTOR.execute(Fixtures.call(SqlTools.DESCRIBE_TABLE, Fixtures.object()
        .put("table", "orders")), cfg, secrets(READER), ctx());
    String d = String.valueOf(described.structuredContent());
    assertThat(d).contains("customer", "amount", "orders_customer");
    ToolResult items = CONNECTOR.execute(Fixtures.call(SqlTools.DESCRIBE_TABLE, Fixtures.object()
        .put("table", "items")), cfg, secrets(READER), ctx());
    assertThat(String.valueOf(items.structuredContent())).contains("order_id", "orders");
  }

  @Test
  void aReadCantWriteEvenAsAnAdmin() throws Exception {
    ConnectionConfig cfg = config("gaterook", Env.DEV, null);
    for (String sql : new String[] {"INSERT INTO orders (id, customer) VALUES (99, 'x')",
        "UPDATE orders SET paid = true WHERE id = 2", "SELECT nextval('ticket_seq')",
        "SELECT * FROM orders FOR UPDATE"}) {
      assertThatThrownBy(() -> CONNECTOR.runRead(cfg, ConnectionSettings.of(cfg, false), secrets("gaterook"), ctx(),
          BoundSql.plain(sql), 10)).as(sql).isInstanceOf(ConnectorException.class);
    }
    assertThat(count("SELECT count(*) FROM orders WHERE id = 99 OR paid AND id = 2")).isZero();
  }

  @Test
  void aSlowQueryStopsAtTheStatementTimeout() {
    ConnectionConfig cfg = config(READER, Env.DEV, Fixtures.object().put("statementTimeoutMs", 1_000));
    long started = System.nanoTime();
    // The database's answer, as on PostgreSQL: the statement ran too long, the server is fine.
    assertThatThrownBy(() -> query(cfg, "SELECT pg_sleep(10)"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).startsWith("Statement timeout (1 s on dev) exceeded.");
        });
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
  }

  @Test
  void anApprovedChangeRunsAsApprovedAndPrepareEstimatesIt() throws Exception {
    ConnectionConfig cfg = config("gaterook", Env.DEV, null);
    ToolCall call = Fixtures.sql(SqlTools.EXECUTE, "UPDATE orders SET paid = true WHERE id = 2 RETURNING id, paid");
    assertThat(CONNECTOR.classify(call, cfg, Fixtures.policy(Env.DEV, null)).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThatThrownBy(() -> CONNECTOR.execute(call, cfg, secrets("gaterook"), ctx()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    PreparedAction prepared = CONNECTOR.prepare(call, cfg, secrets("gaterook"), ctx());
    assertThat(prepared.outcome()).as(String.valueOf(prepared.message())).isEqualTo(PreparedAction.Outcome.APPROVAL);
    ActionPreview preview = CONNECTOR.describeAction(call, cfg);
    ToolResult r = CONNECTOR.execute(call, cfg, secrets("gaterook"), ctx().approved(preview.payload()));
    assertThat(String.valueOf(r.structuredContent()) + r.content()).contains("1");
    assertThat(count("SELECT count(*) FROM orders WHERE id = 2 AND paid")).isEqualTo(1);
    try (Connection c = admin(DATABASE); Statement st = c.createStatement()) {
      st.execute("UPDATE orders SET paid = false WHERE id = 2");
    }
  }
}
