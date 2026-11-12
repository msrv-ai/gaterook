package ai.msrv.gaterook.connectors.cassandra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.OptionsMap;
import com.datastax.oss.driver.api.core.config.TypedDriverOption;
import com.datastax.oss.driver.api.core.cql.Row;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Cassandra connector against Apache Cassandra 5.0 of the Compose services, profile {@code cassandra}: one node
 * with password sign-in, roles and permissions, and TLS (start it with {@code scripts/test-services.sh up cassandra},
 * then use {@code -Pservices,cassandra-it}). The connection verifies the node's certificate and its address over TLS;
 * the setup runs as the image's superuser over plain CQL and adds a reader (SELECT only) and a writer (SELECT, MODIFY
 * and schema changes) on its keyspace.
 */
@Tag("cassandra-it")
class CassandraIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String KS = "gaterook_it";
  static final String OTHER = "gaterook_it_other";
  static final String READER = "gaterook_reader";
  static final String READER_PASSWORD = "reader-pw-0001";
  static final String WRITER = "gaterook_writer";
  static final String WRITER_PASSWORD = "writer-pw-0001";
  static final String DC = "gw-dc1";
  static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
  static final String TIMEUUID = "50554d6e-29bb-11e5-b345-feff819cdc9f";
  static CassandraConnector connector;
  static CqlSession admin;
  static String ca;
  static String otherCa;
  static int port;

  @BeforeAll
  static void seed() throws Exception {
    assumeTrue(TestServices.enabled(), "Cassandra integration tests need the Compose test services");
    Path caFile = TestServices.file("run/cassandra-certs/node.crt");
    assumeTrue(Files.isReadable(caFile), "Start the cassandra profile: scripts/test-services.sh up cassandra");
    ca = Files.readString(caFile);
    otherCa = Files.readString(TestServices.file("run/cassandra-certs/other.crt"));
    port = TestServices.port("GW_CASSANDRA_PORT");
    // The connector's builder: the driver's own would need Jackson 2 (CassandraSessions.builder). Schema changes of
    // the setup may take longer than the driver's default of two seconds on a busy machine.
    OptionsMap options = OptionsMap.driverDefaults();
    options.put(TypedDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(60));
    options.put(TypedDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, Duration.ofSeconds(30));
    options.put(TypedDriverOption.METRICS_FACTORY_CLASS, "NoopMetricsFactory");
    admin = CassandraSessions.builder().withConfigLoader(DriverConfigLoader.fromMap(options))
        .addContactPoint(new InetSocketAddress("127.0.0.1", port))
        .withLocalDatacenter(DC)
        .withAuthCredentials(TestServices.value("GW_CASSANDRA_USER"), TestServices.value("GW_CASSANDRA_PASSWORD"))
        .build();
    for (String cql : List.of(
        "DROP KEYSPACE IF EXISTS " + KS,
        "DROP KEYSPACE IF EXISTS " + OTHER,
        "CREATE KEYSPACE " + KS + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}",
        "CREATE KEYSPACE " + OTHER + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}",
        "CREATE TYPE " + KS + ".address (street text, city text)",
        "CREATE TABLE " + KS + ".typed (id int PRIMARY KEY, big bigint, huge varint, amount decimal, name text, "
            + "flag boolean, at timestamp, day date, moment time, uid uuid, tid timeuuid, raw blob, ip inet, "
            + "span duration, ratio double, score float, small smallint, tiny tinyint, tags list<text>, "
            + "labels set<int>, counts map<text, int>, home frozen<address>, pair tuple<int, text>, "
            + "embedding vector<float, 3>)",
        "CREATE TABLE " + KS + ".events (sensor text, at timestamp, reading int, note text, PRIMARY KEY (sensor, at))"
            + " WITH CLUSTERING ORDER BY (at DESC) AND default_time_to_live = 86400 AND comment = 'readings'",
        "CREATE INDEX events_reading ON " + KS + ".events (reading)",
        "CREATE CUSTOM INDEX events_note ON " + KS + ".events (note) USING 'StorageAttachedIndex'",
        "CREATE TABLE " + KS + ".accounts (id text PRIMARY KEY, owner text, balance int)",
        "CREATE TABLE " + OTHER + ".hidden (id int PRIMARY KEY, secret text)",
        "INSERT INTO " + OTHER + ".hidden (id, secret) VALUES (1, 'not for agents')",
        "DROP ROLE IF EXISTS " + READER,
        "DROP ROLE IF EXISTS " + WRITER,
        "CREATE ROLE " + READER + " WITH PASSWORD = '" + READER_PASSWORD + "' AND LOGIN = true",
        "CREATE ROLE " + WRITER + " WITH PASSWORD = '" + WRITER_PASSWORD + "' AND LOGIN = true",
        "GRANT SELECT ON KEYSPACE " + KS + " TO " + READER,
        "GRANT SELECT ON KEYSPACE " + KS + " TO " + WRITER,
        "GRANT MODIFY ON KEYSPACE " + KS + " TO " + WRITER,
        "GRANT CREATE ON KEYSPACE " + KS + " TO " + WRITER,
        "GRANT ALTER ON KEYSPACE " + KS + " TO " + WRITER,
        "GRANT DROP ON KEYSPACE " + KS + " TO " + WRITER)) {
      admin.execute(cql);
    }
    admin.execute("INSERT INTO " + KS + ".typed (id, big, huge, amount, name, flag, at, day, moment, uid, tid, raw, "
        + "ip, span, ratio, score, small, tiny, tags, labels, counts, home, pair, embedding) VALUES (1, "
        + "9007199254740993, 123456789012345678901234567890, 12.30, 'Grüße', true, '2026-01-02T03:04:05.678Z', "
        + "'2026-11-11', '13:45:30.123456789', " + UUID + ", " + TIMEUUID + ", 0x0102ff, '10.0.4.12', 1h30m, 0.5, "
        + "1.25, -7, 3, ['a', 'b'], {3, 1, 2}, {'x': 1, 'y': 2}, {street: '1 Main St', city: 'Springfield'}, "
        + "(7, 'seven'), [0.5, 1.5, -2.0])");
    admin.execute("INSERT INTO " + KS + ".typed (id) VALUES (2)");
    for (int i = 0; i < 25; i++) {
      admin.execute("INSERT INTO " + KS + ".events (sensor, at, reading, note) VALUES ('s1', " + (1_700_000_000_000L
          + i * 1_000L) + ", " + i + ", 'n" + i + "')");
    }
    connector = new CassandraConnector();
  }

  @AfterAll
  static void close() {
    if (connector != null) {
      connector.close();
    }
    if (admin != null) {
      admin.close();
    }
  }

  static ConnectionConfig config(String user, Env env, Consumer<ObjectNode> tweak) {
    ObjectNode c = JsonNodeFactory.instance.objectNode();
    c.putArray("contactPoints").add("127.0.0.1:" + port);
    c.put("localDataCenter", DC);
    c.put("keyspace", KS);
    c.putArray("keyspaces").add(KS);
    c.putObject("credentials").put("user", user);
    c.put("tls", true);
    c.put("caCertificate", ca);
    if (tweak != null) {
      tweak.accept(c);
    }
    return new ConnectionConfig("conn-cass-" + user + "-" + env.id() + "-" + Integer.toHexString(c.toString()
        .hashCode()), "it-cassandra", "cassandra", env, 1, c, Set.of(CassandraSettings.PASSWORD), null);
  }

  static ConnectionConfig reader() {
    return config(READER, Env.DEV, null);
  }

  static ConnectionConfig writer() {
    return config(WRITER, Env.DEV, null);
  }

  static TestSecrets secrets(ConnectionConfig cfg) {
    String user = cfg.string(CassandraSettings.USER);
    return TestSecrets.password(READER.equals(user) ? READER_PASSWORD : WRITER.equals(user) ? WRITER_PASSWORD
        : TestServices.value("GW_CASSANDRA_PASSWORD"));
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".cassandra", JsonNodeFactory.instance.objectNode(), env == Env.PROD
        ? TestCtx.prod() : TestCtx.dev());
  }

  static ToolCall call(String tool, String args) {
    return new ToolCall("tc_it", tool, "it-cassandra", (ObjectNode) JSON.readTree(args), null);
  }

  /** A call as the core makes it: classified first, run only when it is a read. */
  static ToolResult read(ConnectionConfig cfg, String tool, String args, TestCtx ctx) throws ConnectorException {
    ToolCall call = call(tool, args);
    Classification c = connector.classify(call, cfg, policy(cfg.env()));
    assertThat(c.kind()).as(c.ruleId() + ": " + c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, secrets(cfg), ctx);
  }

  static ToolResult read(String tool, String args) throws ConnectorException {
    return read(reader(), tool, args, TestCtx.of());
  }

  static String query(String statement) {
    return JSON.writeValueAsString(java.util.Map.of("statement", statement));
  }

  /** An approved change as the core runs it: classified, described, prepared, then executed with the payload. */
  static ToolResult change(ConnectionConfig cfg, String args) throws ConnectorException {
    ToolCall call = call(CassandraTools.EXECUTE, args);
    Classification c = connector.classify(call, cfg, policy(cfg.env()));
    assertThat(c.kind()).as(c.ruleId() + ": " + c.reason()).isEqualTo(Classification.Kind.MUTATION);
    PreparedAction prepared = connector.prepare(call, cfg, secrets(cfg), TestCtx.of());
    assertThat(prepared.outcome()).as(prepared.message()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    ActionPreview preview = connector.describeAction(call, cfg);
    return connector.execute(call, cfg, secrets(cfg), TestCtx.of().approved(preview.payload()));
  }

  static Row adminOne(String cql) {
    return admin.execute(cql).one();
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionVerifiesTlsAndReportsTheClusterAndTheRolesPermissions() {
    TestResult t = connector.test(config(READER, Env.PROD, null), secrets(reader()), TestCtx.of().env(Env.PROD)
        .timeout(Duration.ofSeconds(20)));
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Apache Cassandra 5.0.9");
    assertThat(t.identity()).isEqualTo(READER);
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(t.permissions().read()).contains("SELECT on <keyspace gaterook_it>");
    assertThat(t.details().path("cluster").asString()).isEqualTo("gw-test");
    assertThat(t.details().at("/dataCenters/" + DC).asInt()).isEqualTo(1);
    assertThat(t.details().at("/tls/verify").asString()).isEqualTo("full");
    assertThat(t.metadataUpdate().path("serverVersion").asString()).isEqualTo("Apache Cassandra 5.0.9");
    assertThat(t.checks()).extracting(TestResult.Check::name).contains("connect", "cluster", "local data center",
        "keyspaces", "permissions");
    assertThat(t.checks().get(3).detail()).contains("1 keyspace", KS);
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("cassandra.read-only-boundary")
        .doesNotContain("cassandra.write-role", "cassandra.admin-role", "cassandra.no-tls");

    TestResult writer = connector.test(writer(), secrets(writer()), TestCtx.of());
    assertThat(writer.ok()).as(String.valueOf(writer.error())).isTrue();
    assertThat(writer.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(writer.permissions().write()).contains("MODIFY on <keyspace gaterook_it>");
    assertThat(writer.hints()).extracting(TestResult.Hint::code).contains("cassandra.write-role");
    assertThat(writer.hints().stream().filter(h -> h.code().equals("cassandra.write-role")).findFirst().orElseThrow()
        .text()).contains("CREATE ROLE gw_reader", "GRANT SELECT ON KEYSPACE gaterook_it TO gw_reader;");

    ConnectionConfig superuser = config(TestServices.value("GW_CASSANDRA_USER"), Env.STAGE, null);
    TestResult su = connector.test(superuser, secrets(superuser), TestCtx.of().env(Env.STAGE)
        .timeout(Duration.ofSeconds(20)));
    assertThat(su.ok()).as(String.valueOf(su.error())).isTrue();
    assertThat(su.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(su.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("cassandra.admin-role");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.DANGER);
    });
    assertThat(connector.health(reader(), secrets(reader()), TestCtx.of().timeout(Duration.ofSeconds(10))).state())
        .isEqualTo(HealthResult.State.HEALTHY);
  }

  @Test
  void autoAndServiceSuggestionsFindTheTransportTheDataCenterAndTheKeyspaces() {
    // The test node speaks TLS and plain CQL on one port: Auto prefers TLS.
    TestResult auto = connector.test(config(READER, Env.DEV, c -> c.put("tls", "auto")), secrets(reader()),
        TestCtx.of().timeout(Duration.ofSeconds(20)));
    assertThat(auto.ok()).as(String.valueOf(auto.error())).isTrue();
    assertThat(auto.checks().getFirst().detail()).contains(" over TLS (Auto) to ");

    ConnectionConfig draft = config(READER, Env.DEV, c -> {
      c.remove("localDataCenter");
      c.put("tls", "auto");
    });
    Suggestions s = connector.suggest(draft, secrets(reader()), TestCtx.of().timeout(Duration.ofSeconds(20)),
        Suggestions.Scope.SERVICE);
    Suggestions.Field dc = s.fields().get("/localDataCenter");
    assertThat(dc).as("%s", s.notes()).isNotNull();
    assertThat(dc.value()).isEqualTo(DC);
    assertThat(dc.options()).extracting(Suggestions.Option::value).containsExactly(DC);
    assertThat(s.fields().get("/keyspaces").options()).extracting(Suggestions.Option::value).contains(KS, OTHER);
    assertThat(s.fields().get("/keyspace").options()).extracting(Suggestions.Option::value).containsExactly(KS);
    assertThat(s.notes()).contains("The nodes speak TLS: Auto connects with TLS.");
  }

  @Test
  void testConnectionSaysWhatIsWrong() {
    TestResult password = connector.test(reader(), TestSecrets.password("wrong-password"), TestCtx.of());
    assertThat(password.ok()).isFalse();
    assertThat(password.error().code()).isEqualTo("cassandra.auth");
    assertThat(password.error().message()).doesNotContain("wrong-password");

    TestResult dc = connector.test(config(READER, Env.DEV, c -> c.put("localDataCenter", "dc-nowhere")),
        secrets(reader()), TestCtx.of());
    assertThat(dc.ok()).isFalse();
    assertThat(dc.error().code()).isEqualTo("cassandra.local-dc");
    assertThat(dc.error().message()).contains("dc-nowhere", DC);

    TestResult wrongCa = connector.test(config(READER, Env.DEV, c -> c.put("caCertificate", otherCa)),
        secrets(reader()), TestCtx.of());
    assertThat(wrongCa.ok()).isFalse();
    assertThat(wrongCa.error().code()).isEqualTo("cassandra.tls");

    // The platform's CAs don't know the test node's certificate either.
    TestResult platform = connector.test(config(READER, Env.DEV, c -> c.remove("caCertificate")), secrets(reader()),
        TestCtx.of());
    assertThat(platform.error().code()).isEqualTo("cassandra.tls");

    TestResult plain = connector.test(config(READER, Env.STAGE, c -> c.put("tls", false)), secrets(reader()),
        TestCtx.of().env(Env.STAGE));
    assertThat(plain.ok()).as(String.valueOf(plain.error())).isTrue();
    assertThat(plain.hints()).anySatisfy(h -> assertThat(h.code()).isEqualTo("cassandra.no-tls"));

    TestResult closed = connector.test(config(READER, Env.DEV, c -> c.putArray("contactPoints").add("127.0.0.1:1")),
        secrets(reader()), TestCtx.of());
    assertThat(closed.ok()).isFalse();
    assertThat(closed.error().code()).isEqualTo("cassandra.unreachable");

    TestResult keyspace = connector.test(config(READER, Env.DEV, c -> c.put("keyspace", "no_such_keyspace")),
        secrets(reader()), TestCtx.of());
    assertThat(keyspace.ok()).isFalse();
    assertThat(keyspace.error().code()).isEqualTo("cassandra.keyspace");
  }

  // ── Listing and describing ──────────────────────────────────────────────────────────────────────────────────

  @Test
  void listsOnlyTheKeyspacesOfTheConnectionAndDescribesTables() throws Exception {
    JsonNode list = read(CassandraTools.LIST_KEYSPACES, "{}").structuredContent();
    assertThat(list.path("keyspaces")).hasSize(1);
    JsonNode ks = list.path("keyspaces").get(0);
    assertThat(ks.path("keyspace").asString()).isEqualTo(KS);
    assertThat(ks.at("/replication/class").asString()).isEqualTo("SimpleStrategy");
    assertThat(ks.path("tables").toString()).contains("typed", "events", "accounts");
    assertThat(list.toString()).doesNotContain(OTHER, "system_auth");

    JsonNode events = read(CassandraTools.DESCRIBE_TABLE, "{\"table\": \"events\"}").structuredContent();
    assertThat(events.path("partitionKey").toString()).isEqualTo("[\"sensor\"]");
    assertThat(events.path("clustering").get(0).path("name").asString()).isEqualTo("at");
    assertThat(events.path("clustering").get(0).path("order").asString()).isEqualTo("desc");
    assertThat(events.path("columns")).extracting(c -> c.path("name").asString() + ":" + c.path("kind").asString()
        + ":" + c.path("type").asString()).containsExactly("sensor:partition_key:text", "at:clustering:timestamp",
        "note:regular:text", "reading:regular:int");
    assertThat(events.at("/options/defaultTimeToLive").asInt()).isEqualTo(86_400);
    assertThat(events.at("/options/compaction/class").asString()).isNotBlank();
    assertThat(events.at("/options/comment").asString()).isEqualTo("readings");
    assertThat(events.path("indexes")).extracting(i -> i.path("name").asString() + " on " + i.path("target")
        .asString()).contains("events_reading on reading", "events_note on note");
    JsonNode typed = read(CassandraTools.DESCRIBE_TABLE, "{\"table\": \"gaterook_it.typed\"}").structuredContent();
    assertThat(typed.toString()).contains("\"home\"", "frozen<address>", "map<text, int>", "vector<float, 3>");

    assertThatThrownBy(() -> connector.execute(call(CassandraTools.DESCRIBE_TABLE, "{\"table\": \"" + OTHER
        + ".hidden\"}"), reader(), secrets(reader()), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.DENIED));
    assertThatThrownBy(() -> read(CassandraTools.DESCRIBE_TABLE, "{\"table\": \"nothing_here\"}"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("No table or view gaterook_it.nothing_here");
        });
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void queriesReturnCassandraTypes() throws Exception {
    ToolResult r = read(CassandraTools.QUERY, query("SELECT * FROM typed WHERE id = 1"));
    List<String> names = new ArrayList<>();
    r.table().columns().forEach(c -> names.add(c.name()));
    JsonNode row = r.table().rows().get(0);
    java.util.function.Function<String, JsonNode> cell = n -> row.get(names.indexOf(n));
    assertThat(cell.apply("id").asInt()).isEqualTo(1);
    assertThat(cell.apply("big").isString()).isTrue();
    assertThat(cell.apply("big").asString()).isEqualTo("9007199254740993");
    assertThat(cell.apply("huge").asString()).isEqualTo("123456789012345678901234567890");
    assertThat(cell.apply("amount").asString()).isEqualTo("12.30");
    assertThat(cell.apply("name").asString()).isEqualTo("Grüße");
    assertThat(cell.apply("flag").asBoolean()).isTrue();
    assertThat(cell.apply("at").asString()).isEqualTo("2026-01-02T03:04:05.678Z");
    assertThat(cell.apply("day").asString()).isEqualTo("2026-11-11");
    assertThat(cell.apply("moment").asString()).isEqualTo("13:45:30.123456789");
    assertThat(cell.apply("uid").asString()).isEqualTo(UUID);
    assertThat(cell.apply("tid").asString()).isEqualTo(TIMEUUID);
    assertThat(cell.apply("raw").asString()).isEqualTo("0x0102ff");
    assertThat(cell.apply("ip").asString()).isEqualTo("10.0.4.12");
    assertThat(cell.apply("span").asString()).isEqualTo("1h30m");
    assertThat(cell.apply("ratio").asDouble()).isEqualTo(0.5);
    assertThat(cell.apply("score").asDouble()).isEqualTo(1.25);
    assertThat(cell.apply("small").asInt()).isEqualTo(-7);
    assertThat(cell.apply("tiny").asInt()).isEqualTo(3);
    assertThat(cell.apply("tags").toString()).isEqualTo("[\"a\",\"b\"]");
    assertThat(cell.apply("labels").toString()).isEqualTo("[1,2,3]");
    assertThat(cell.apply("counts").toString()).isEqualTo("{\"x\":1,\"y\":2}");
    assertThat(cell.apply("home").toString()).isEqualTo("{\"street\":\"1 Main St\",\"city\":\"Springfield\"}");
    assertThat(cell.apply("pair").toString()).isEqualTo("[7,\"seven\"]");
    assertThat(cell.apply("embedding").toString()).isEqualTo("[0.5,1.5,-2.0]");
    assertThat(r.table().columns().get(names.indexOf("counts")).type()).isEqualTo("map<text, int>");
    assertThat(r.table().columns().get(0).sourceSchema()).isEqualTo(KS);

    ToolResult empty = read(CassandraTools.QUERY, query("SELECT id, tags, home FROM gaterook_it.typed WHERE id = 2"));
    assertThat(empty.table().rows().get(0).toString()).isEqualTo("[2,null,null]");
  }

  @Test
  void valuesAreBoundByTheirMarkersTypes() throws Exception {
    ToolResult positional = read(CassandraTools.QUERY, "{\"statement\": \"SELECT name FROM typed WHERE id = ?\", "
        + "\"values\": [1]}");
    assertThat(positional.table().rows().get(0).get(0).asString()).isEqualTo("Grüße");
    ToolResult named = read(CassandraTools.QUERY, "{\"statement\": \"SELECT reading FROM events WHERE sensor = "
        + ":s AND at = :at\", \"values\": {\"s\": \"s1\", \"at\": \"2023-11-14T22:13:21Z\"}}");
    assertThat(named.table().rows().get(0).get(0).asInt()).isEqualTo(1);
    ToolResult in = read(CassandraTools.QUERY, "{\"statement\": \"SELECT id FROM typed WHERE id IN ?\", "
        + "\"values\": [[1, 2]]}");
    assertThat(in.table().rows()).hasSize(2);
    assertThatThrownBy(() -> read(CassandraTools.QUERY, "{\"statement\": \"SELECT name FROM typed WHERE id = ?\", "
        + "\"values\": [\"one\"]}"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("values[0]", "int");
        });
    assertThatThrownBy(() -> read(CassandraTools.QUERY, "{\"statement\": \"SELECT name FROM typed WHERE id = ?\", "
        + "\"values\": [1, 2]}"))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("1 marker"));
  }

  @Test
  void pagesFollowASealedCursorExactlyAndNoFurther() throws Exception {
    String args = "{\"statement\": \"SELECT reading FROM events WHERE sensor = 's1'\", \"limit\": 10}";
    List<Integer> seen = new ArrayList<>();
    ToolResult page = read(CassandraTools.QUERY, args);
    int pages = 1;
    page.table().rows().forEach(r -> seen.add(r.get(0).asInt()));
    while (page.table().nextCursor() != null) {
      ObjectNode next = (ObjectNode) JSON.readTree(args);
      next.put("cursor", page.table().nextCursor());
      page = read(CassandraTools.QUERY, next.toString());
      page.table().rows().forEach(r -> seen.add(r.get(0).asInt()));
      pages++;
    }
    assertThat(pages).isEqualTo(3);
    List<Integer> expected = new ArrayList<>();
    for (int i = 24; i >= 0; i--) {
      expected.add(i);
    }
    assertThat(seen).isEqualTo(expected);

    String cursor = read(CassandraTools.QUERY, args).table().nextCursor();
    ObjectNode other = (ObjectNode) JSON.readTree("{\"statement\": \"SELECT note FROM events WHERE sensor = 's1'\", "
        + "\"limit\": 10}");
    other.put("cursor", cursor);
    assertThatThrownBy(() -> read(CassandraTools.QUERY, other.toString()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
            .contains("another connection or request"));
    ObjectNode forged = (ObjectNode) JSON.readTree(args);
    forged.put("cursor", cursor.substring(0, cursor.length() - 3) + "abc");
    assertThatThrownBy(() -> read(CassandraTools.QUERY, forged.toString()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("invalid"));
  }

  @Test
  void limitsCapRowsCellsAndTheResponseAndAPageCutAtItsSizeGoesOnWhereItStopped() throws Exception {
    ConnectionConfig cfg = reader();
    // The endpoint's row limit caps what an agent asks for, and a cell is cut to the cell size.
    Limits small = new Limits(5, 100, 64 * 1024, 1_024, 1_024, 1_024, 300, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(30), null);
    ToolResult capped = read(cfg, CassandraTools.QUERY, "{\"statement\": \"SELECT reading FROM events WHERE sensor = "
        + "'s1'\", \"limit\": 50}", TestCtx.of().with(small));
    assertThat(capped.table().rows()).hasSize(5);
    assertThat(capped.table().nextCursor()).isNotNull();
    String note = "0123456789".repeat(150);
    for (int i = 0; i < 12; i++) {
      admin.execute("INSERT INTO " + KS + ".events (sensor, at, reading, note) VALUES ('wide', " + i + ", " + i
          + ", '" + note + "')");
    }
    ToolResult cut = read(cfg, CassandraTools.QUERY, query("SELECT note FROM events WHERE sensor = 'wide' LIMIT 1"),
        TestCtx.of().with(small));
    assertThat(cut.table().rows().get(0).get(0).asString()).hasSize(300 + "…‹1500 bytes›".length())
        .endsWith("…‹1500 bytes›");

    // About two rows of 1,500 characters fit the response: each answer is cut, and its cursor resumes in the page.
    Limits narrow = new Limits(50, 100, 6_000, 1_024, 1_024, 1_024, 2_000, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(30), null);
    String args = "{\"statement\": \"SELECT reading, note FROM events WHERE sensor = 'wide'\", \"limit\": 50}";
    List<Integer> seen = new ArrayList<>();
    ToolResult page = read(cfg, CassandraTools.QUERY, args, TestCtx.of().with(narrow));
    int answers = 1;
    int cuts = page.truncated() ? 1 : 0;
    page.table().rows().forEach(r -> seen.add(r.get(0).asInt()));
    while (page.table().nextCursor() != null) {
      ObjectNode next = (ObjectNode) JSON.readTree(args);
      next.put("cursor", page.table().nextCursor());
      page = read(cfg, CassandraTools.QUERY, next.toString(), TestCtx.of().with(narrow));
      if (page.truncated()) {
        cuts++;
        assertThat(page.truncatedReason()).isEqualTo("size_limit");
      }
      page.table().rows().forEach(r -> seen.add(r.get(0).asInt()));
      answers++;
      assertThat(answers).isLessThan(20);
    }
    assertThat(cuts).isGreaterThan(3);
    assertThat(seen).containsExactly(11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0);
  }

  // ── Refusals ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void refusedStatementsNeverReachTheCluster() throws Exception {
    ConnectionConfig cfg = writer();
    for (String[] c : new String[][] {
        {CassandraTools.QUERY, "DELETE FROM accounts WHERE id = 'a'", "dev.cassandra.query-writes"},
        {CassandraTools.QUERY, "SELECT * FROM events WHERE reading > 3 ALLOW FILTERING", "dev.cassandra.allow-filtering"},
        {CassandraTools.QUERY, "SELECT * FROM " + OTHER + ".hidden", "dev.cassandra.keyspace-not-allowed"},
        {CassandraTools.QUERY, "SELECT * FROM system_auth.roles", "dev.cassandra.auth-tables"},
        {CassandraTools.QUERY, "SELECT * FROM accounts; SELECT * FROM typed", "dev.cassandra.unparsed"},
        {CassandraTools.EXECUTE, "SELECT * FROM accounts", "dev.cassandra.execute-read"},
        {CassandraTools.EXECUTE, "BEGIN BATCH INSERT INTO accounts (id) VALUES ('b'); APPLY BATCH", "dev.cassandra.batch"},
        {CassandraTools.EXECUTE, "GRANT MODIFY ON KEYSPACE " + KS + " TO " + READER, "dev.cassandra.access-control"},
        {CassandraTools.EXECUTE, "CREATE ROLE intruder WITH PASSWORD = 'x' AND LOGIN = true", "dev.cassandra.access-control"},
        {CassandraTools.EXECUTE, "DROP KEYSPACE " + KS, "dev.cassandra.keyspace-change"},
        {CassandraTools.EXECUTE, "CREATE FUNCTION " + KS + ".f(a int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE "
            + "java AS $$ return a; $$", "dev.cassandra.server-code"},
        {CassandraTools.EXECUTE, "USE " + OTHER, "dev.cassandra.use-keyspace"},
        {CassandraTools.EXECUTE, "INSERT INTO " + OTHER + ".hidden (id, secret) VALUES (2, 'x')",
            "dev.cassandra.keyspace-not-allowed"},
        {CassandraTools.EXECUTE, "UPDATE system_views.settings SET value = 'x' WHERE name = 'y'",
            "dev.cassandra.system-keyspace"}}) {
      ToolCall call = call(c[0], query(c[1]));
      Classification cl = connector.classify(call, cfg, policy(Env.DEV));
      assertThat(cl.kind()).as(c[1]).isEqualTo(Classification.Kind.DENY);
      assertThat(cl.ruleId()).as(c[1]).isEqualTo(c[2]);
      // execute checks again: what classify refuses never runs, approved or not.
      ToolCall approvedCall = call(c[0], query(c[1]));
      TestCtx ctx = c[0].equals(CassandraTools.EXECUTE) ? TestCtx.of().approved(connector.describeAction(approvedCall,
          cfg).payload()) : TestCtx.of();
      assertThatThrownBy(() -> connector.execute(approvedCall, cfg, secrets(cfg), ctx)).as(c[1])
          .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
              .isEqualTo(ConnectorException.Kind.DENIED));
    }
    assertThat(adminOne("SELECT count(*) FROM " + OTHER + ".hidden").getLong(0)).isEqualTo(1);
    assertThat(admin.execute("SELECT role FROM system_auth.roles WHERE role = 'intruder'").one()).isNull();

    // On dev the connection may allow ALLOW FILTERING; Cassandra then filters.
    ConnectionConfig filtering = config(READER, Env.DEV, c -> c.putObject("advanced").put("allowFiltering", true));
    ToolResult filtered = read(filtering, CassandraTools.QUERY, query("SELECT reading FROM events WHERE reading > 22 "
        + "ALLOW FILTERING"), TestCtx.of());
    assertThat(filtered.table().rows()).hasSize(2);
  }

  @Test
  void aReadOnlyRoleCantChangeAnythingEvenWhenApproved() {
    ConnectionConfig cfg = reader();
    ToolCall call = call(CassandraTools.EXECUTE, query("INSERT INTO accounts (id, owner, balance) VALUES ('r', "
        + "'reader', 1)"));
    PreparedAction prepared;
    try {
      prepared = connector.prepare(call, cfg, secrets(cfg), TestCtx.of());
    } catch (ConnectorException e) {
      throw new AssertionError(e);
    }
    // Cassandra checks the role's permissions: the change is refused before a person is asked, or when it runs.
    if (prepared.outcome() == PreparedAction.Outcome.APPROVAL) {
      assertThatThrownBy(() -> connector.execute(call, cfg, secrets(cfg), TestCtx.of().approved(
          connector.describeAction(call, cfg).payload())))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            // Cassandra's refusal, not GateRook's: nothing changed.
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
            assertThat(e.getMessage()).contains("may not");
          });
    } else {
      assertThat(prepared.message()).contains("may not");
    }
    assertThat(admin.execute("SELECT id FROM " + KS + ".accounts WHERE id = 'r'").one()).isNull();
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anApprovedChangeRunsOnceAsApproved() throws Exception {
    ConnectionConfig cfg = writer();
    String insert = "{\"statement\": \"INSERT INTO accounts (id, owner, balance) VALUES (?, ?, ?)\", \"values\": "
        + "[\"acme-1\", \"Acme\", 100], \"note\": \"opening balance\"}";
    ToolCall call = call(CassandraTools.EXECUTE, insert);
    assertThatThrownBy(() -> connector.execute(call, cfg, secrets(cfg), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.DENIED));
    ActionPreview preview = connector.describeAction(call, cfg);
    assertThat(preview.text()).isEqualTo("INSERT INTO accounts (id, owner, balance) VALUES (?, ?, ?)");
    assertThat(preview.target()).contains("gaterook_it.accounts");
    assertThat(preview.fields()).extracting(ActionPreview.Field::value).contains("[\"acme-1\",\"Acme\",100]");
    ToolResult done = change(cfg, insert);
    assertThat(done.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(adminOne("SELECT balance FROM " + KS + ".accounts WHERE id = 'acme-1'").getInt(0)).isEqualTo(100);

    // What runs is the approved payload, not the call's arguments.
    ToolCall tampered = call(CassandraTools.EXECUTE, "{\"statement\": \"DELETE FROM accounts WHERE id = 'acme-1'\"}");
    ToolResult updated = connector.execute(tampered, cfg, secrets(cfg), TestCtx.of().approved(connector
        .describeAction(call(CassandraTools.EXECUTE, "{\"statement\": \"UPDATE accounts SET balance = 150 WHERE id = "
            + "'acme-1'\"}"), cfg).payload()));
    assertThat(updated.structuredContent().path("statement").asString()).isEqualTo("UPDATE");
    assertThat(adminOne("SELECT balance FROM " + KS + ".accounts WHERE id = 'acme-1'").getInt(0)).isEqualTo(150);

    ToolResult ddl = change(cfg, query("CREATE TABLE gaterook_it.scratch (id int PRIMARY KEY, v text)"));
    assertThat(ddl.structuredContent().path("schemaInAgreement").asBoolean()).isTrue();
    change(cfg, query("ALTER TABLE scratch ADD extra int"));
    change(cfg, query("INSERT INTO scratch (id, v, extra) VALUES (1, 'x', 2)"));
    change(cfg, query("TRUNCATE scratch"));
    assertThat(adminOne("SELECT count(*) FROM " + KS + ".scratch").getLong(0)).isZero();
    change(cfg, query("DROP TABLE scratch"));
    assertThat(adminOne("SELECT table_name FROM system_schema.tables WHERE keyspace_name = '" + KS + "' AND "
        + "table_name = 'scratch'")).isNull();

    // A type that doesn't fit is refused before a person is asked.
    PreparedAction bad = connector.prepare(call(CassandraTools.EXECUTE, "{\"statement\": \"UPDATE accounts SET "
        + "balance = ? WHERE id = 'acme-1'\", \"values\": [\"lots\"]}"), cfg, secrets(cfg), TestCtx.of());
    assertThat(bad.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(bad.message()).contains("values[0]", "int");
    PreparedAction unknownTable = connector.prepare(call(CassandraTools.EXECUTE, query("UPDATE nowhere SET a = 1 "
        + "WHERE id = 1")), cfg, secrets(cfg), TestCtx.of());
    assertThat(unknownTable.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(unknownTable.message()).contains("nowhere");
  }

  @Test
  void lightweightTransactionsSayWhetherTheyApplied() throws Exception {
    ConnectionConfig cfg = writer();
    String create = "{\"statement\": \"INSERT INTO accounts (id, owner, balance) VALUES ('lwt-1', 'Acme', 5) IF NOT "
        + "EXISTS\"}";
    ToolResult first = change(cfg, create);
    assertThat(first.structuredContent().path("applied").asBoolean()).isTrue();
    ToolResult again = change(cfg, create);
    assertThat(again.structuredContent().path("applied").asBoolean()).isFalse();
    assertThat(again.structuredContent().at("/current/balance").asInt()).isEqualTo(5);
    ToolResult guarded = change(cfg, query("UPDATE accounts SET balance = 6 WHERE id = 'lwt-1' IF balance = 999"));
    assertThat(guarded.structuredContent().path("applied").asBoolean()).isFalse();
    assertThat(guarded.structuredContent().at("/current/balance").asInt()).isEqualTo(5);
    ToolResult applied = change(cfg, "{\"statement\": \"UPDATE accounts SET balance = :b WHERE id = 'lwt-1' IF "
        + "balance = :old\", \"values\": {\"b\": 6, \"old\": 5}}");
    assertThat(applied.structuredContent().path("applied").asBoolean()).isTrue();
    assertThat(adminOne("SELECT balance FROM " + KS + ".accounts WHERE id = 'lwt-1'").getInt(0)).isEqualTo(6);
  }

  @Test
  void errorsSayWhatHappened() {
    assertThatThrownBy(() -> read(CassandraTools.QUERY, query("SELECT nope FROM typed WHERE id = 1")))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("failed. Cassandra said:", "nope");
        });
    assertThatThrownBy(() -> read(CassandraTools.QUERY, query("SELECT * FROM missing_table")))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("missing_table"));
    ConnectionConfig unreachable = config(READER, Env.DEV, c -> c.putArray("contactPoints").add("127.0.0.1:1"));
    assertThatThrownBy(() -> read(unreachable, CassandraTools.QUERY, query("SELECT * FROM typed"), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE));
    // Another connection: the session of the reader's own is open already.
    ConnectionConfig wrongPassword = config(READER, Env.DEV, c -> c.put("consistency", "LOCAL_QUORUM"));
    assertThatThrownBy(() -> connector.execute(call(CassandraTools.QUERY, query("SELECT * FROM typed")),
        wrongPassword, TestSecrets.password("not-it"), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.getMessage()).contains("refused the sign-in").doesNotContain("not-it");
        });
  }
}
