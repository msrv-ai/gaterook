package com.gaterook.connectors.neo4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Neo4j 5 Community of the neo4j Compose profile: Bolt on 127.0.0.1 with optional TLS from gw-test-ca (start it with
 * {@code scripts/test-services.sh up neo4j}, then use {@code -Pservices,neo4j-it}). The IT signs in as neo4j, which
 * Community Edition can't restrict: what keeps a read from writing is the plan check and the READ transaction, which
 * the tests below take one at a time.
 */
@Tag("neo4j-it")
class Neo4jIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Neo4jConnector CONNECTOR = new Neo4jConnector();
  static Driver admin;
  static String ca;
  static String otherCa;

  @BeforeAll
  static void seed() throws Exception {
    assumeTrue(TestServices.enabled(), "Neo4j integration tests need the Compose test services");
    Path caFile = TestServices.file("run/neo4j-certs/ca.crt");
    assumeTrue(Files.isReadable(caFile), "Start the neo4j profile: scripts/test-services.sh up neo4j");
    ca = Files.readString(caFile);
    otherCa = Files.readString(TestServices.file("run/neo4j-certs/other-ca.crt"));
    admin = GraphDatabase.driver("bolt://127.0.0.1:" + TestServices.port("GW_NEO4J_PORT"),
        AuthTokens.basic("neo4j", TestServices.value("GW_NEO4J_PASSWORD")));
    try (Session s = admin.session()) {
      s.run("MATCH (n) DETACH DELETE n").consume();
      for (String drop : List.of("DROP INDEX it_person_name IF EXISTS", "DROP INDEX it_item_i IF EXISTS",
          "DROP CONSTRAINT it_person_id IF EXISTS")) {
        s.run(drop).consume();
      }
      s.run("CREATE INDEX it_person_name IF NOT EXISTS FOR (p:Person) ON (p.name)").consume();
      s.run("CREATE CONSTRAINT it_person_id IF NOT EXISTS FOR (p:Person) REQUIRE p.id IS UNIQUE").consume();
      s.run("CREATE (ada:Person {id: 1, name: 'Ada', born: date('1815-12-10'), big: 1152921504606846976, "
          + "score: 0.5, tags: ['math', 'engines'], home: point({longitude: -0.1276, latitude: 51.5072}), "
          + "desk: point({x: 1, y: 2, z: 3}), seen: datetime('2026-01-02T03:04:05[Europe/Berlin]'), "
          + "at: localdatetime('2026-01-02T03:04:05'), wakes: time('07:30:00+01:00'), alarm: localtime('06:45:00'), "
          + "stint: duration('P1Y2M3DT4H5M6.5S')})-[:KNOWS {since: 2020}]->(:Person {id: 2, name: 'Bob'})").consume();
      s.run("UNWIND range(1, 30) AS i CREATE (:Item {i: i, text: $long})", Map.of("long", "x".repeat(10_000)))
          .consume();
    }
  }

  @AfterAll
  static void close() {
    CONNECTOR.close();
    if (admin != null) {
      try (Session s = admin.session()) {
        s.run("DROP INDEX it_item_i IF EXISTS").consume();
      }
      admin.close();
    }
  }

  static ConnectionConfig config(String uri, Env env, ObjectNode extra) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("uri", uri).put("database", "neo4j");
    c.putObject("authentication").put("method", "password").put("user", "neo4j");
    if (extra != null) {
      c.setAll(extra);
    }
    return new ConnectionConfig("it-" + Integer.toHexString(c.hashCode()), env.id() + "-graph", "neo4j", env, 1, c,
        Set.of(Neo4jSettings.PASSWORD), null);
  }

  static ConnectionConfig plain() {
    return config("bolt://127.0.0.1:" + TestServices.port("GW_NEO4J_PORT"), Env.DEV, null);
  }

  static ConnectionConfig tls(String scheme, String host, String caPem) {
    ObjectNode extra = JsonNodeFactory.instance.objectNode();
    if (caPem != null) {
      extra.put("caCertificate", caPem);
    }
    return config(scheme + "://" + host + ":" + TestServices.port("GW_NEO4J_PORT"), Env.DEV, extra);
  }

  static TestSecrets secrets() {
    return new TestSecrets().with(Neo4jSettings.PASSWORD, TestServices.value("GW_NEO4J_PASSWORD"));
  }

  static ToolCall call(String tool, Map<String, Object> args) {
    return new ToolCall("tc_it", tool, "dev-graph", (ObjectNode) JSON.valueToTree(args), null);
  }

  static ToolResult query(ConnectionConfig cfg, String cypher, TestCtx ctx) throws Exception {
    ToolCall c = call(Neo4jTools.QUERY, Map.of("cypher", cypher));
    assertThat(CONNECTOR.classify(c, cfg, new PolicyView(Env.DEV, "dev.neo4j", JsonNodeFactory.instance.objectNode(),
        TestCtx.dev())).kind()).as(cypher).isEqualTo(Classification.Kind.READ);
    return CONNECTOR.execute(c, cfg, secrets(), ctx);
  }

  static long count(String cypher) {
    try (Session s = admin.session()) {
      return s.run(cypher).single().get(0).asLong();
    }
  }

  // ── Test connection and TLS ─────────────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionNamesTheServerTheEditionAndWhatTheUserMayDo() {
    TestResult t = CONNECTOR.test(plain(), secrets(), TestCtx.of());
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).startsWith("Neo4j 5.26").endsWith("community");
    assertThat(t.identity()).isEqualTo("neo4j");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("connect", "database", "user");
    assertThat(t.checks().getFirst().detail()).startsWith("Neo4j/5.26").contains("over Bolt 5.");
    assertThat(t.checks().get(1).detail()).isEqualTo("neo4j online, read-write");
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("neo4j.no-roles", "neo4j.no-tls");
    TestResult prod = CONNECTOR.test(config("bolt://127.0.0.1:" + TestServices.port("GW_NEO4J_PORT"), Env.PROD,
        null), secrets(), TestCtx.of().env(Env.PROD));
    assertThat(prod.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("neo4j.no-roles");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
    });
    assertThat(CONNECTOR.health(plain(), secrets(), TestCtx.of()).state()).isEqualTo(HealthResult.State.HEALTHY);
    TestResult wrong = CONNECTOR.test(plain(), new TestSecrets().with(Neo4jSettings.PASSWORD, "not-the-pw-0001"),
        TestCtx.of());
    assertThat(wrong.ok()).isFalse();
    assertThat(wrong.error().code()).isEqualTo("neo4j.auth");
    assertThat(wrong.error().message()).doesNotContain("not-the-pw-0001");
  }

  @Test
  void tlsWithThePrivateCaVerifiesTheServerAndItsName() throws Exception {
    for (ConnectionConfig cfg : List.of(tls("bolt+s", "127.0.0.1", ca), tls("bolt+s", "localhost", ca),
        tls("neo4j+s", "127.0.0.1", ca))) {
      TestResult t = CONNECTOR.test(cfg, secrets(), TestCtx.of());
      assertThat(t.ok()).as(cfg.string("/uri") + ": " + t.error()).isTrue();
      assertThat(t.details().path("tls").asBoolean()).isTrue();
      assertThat(t.checks().getFirst().detail()).contains("with TLS");
      ToolResult r = query(cfg, "MATCH (p:Person {id: 1}) RETURN p.name AS name", TestCtx.of());
      assertThat(r.table().rows().get(0).get(0).asString()).isEqualTo("Ada");
    }
    TestResult other = CONNECTOR.test(tls("bolt+s", "127.0.0.1", otherCa), secrets(), TestCtx.of());
    assertThat(other.ok()).isFalse();
    assertThat(other.error().code()).isEqualTo("neo4j.tls");
    assertThat(other.error().message()).startsWith("TLS with 127.0.0.1:").contains("CA certificate under Advanced");
    TestResult system = CONNECTOR.test(tls("bolt+s", "127.0.0.1", null), secrets(), TestCtx.of());
    assertThat(system.ok()).isFalse();
    assertThat(system.error().code()).isEqualTo("neo4j.tls");
    assertThat(system.error().message()).contains("private CA");
    // The CA file lived only while the driver was built.
    Path work = TestCtx.of().workDir();
    if (Files.isDirectory(work)) {
      try (var files = Files.list(work)) {
        assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("neo4j-ca-"));
      }
    }
  }

  // ── Schema and typed results ────────────────────────────────────────────────────────────────────────────────

  @Test
  void theSchemaListsLabelsTypesKeysIndexesAndConstraints() throws Exception {
    ToolResult r = CONNECTOR.execute(call(Neo4jTools.SCHEMA, Map.of("visualization", true)), plain(), secrets(),
        TestCtx.of());
    JsonNode s = r.structuredContent();
    assertThat(s.path("labels").toString()).contains("\"Person\"", "\"Item\"");
    assertThat(s.path("relationshipTypes").toString()).contains("\"KNOWS\"");
    assertThat(s.path("propertyKeys").toString()).contains("\"name\"", "\"born\"");
    assertThat(s.path("indexes").toString()).contains("it_person_name", "RANGE");
    assertThat(s.path("constraints").toString()).contains("it_person_id", "UNIQUENESS");
    assertThat(s.at("/graph/relationships").toString()).contains("{\"type\":\"KNOWS\",\"from\":[\"Person\"],"
        + "\"to\":[\"Person\"]}");
    assertThat(r.content().getFirst().text()).startsWith("neo4j: ");
  }

  @Test
  void resultsKeepNeo4jsTypes() throws Exception {
    ToolResult r = query(plain(), "MATCH path = (a:Person {id: 1})-[k:KNOWS]->(b) RETURN a, k, path, a.born AS born, "
        + "a.seen AS seen, a.at AS at, a.wakes AS wakes, a.alarm AS alarm, a.stint AS stint, a.home AS home, "
        + "a.desk AS desk, a.tags AS tags, {name: a.name, n: 1} AS m, a.big AS big, a.score AS score, "
        + "datetime('2026-01-02T03:04:05+02:00') AS offset", TestCtx.of());
    JsonNode row = r.table().rows().get(0);
    List<String> keys = r.table().columns().stream().map(ToolResult.Column::name).toList();
    Map<String, JsonNode> cell = new LinkedHashMap<>();
    for (int i = 0; i < keys.size(); i++) {
      cell.put(keys.get(i), row.get(i));
    }
    assertThat(cell.get("a").path("labels").toString()).isEqualTo("[\"Person\"]");
    assertThat(cell.get("a").path("elementId").asString()).matches("4:[0-9a-f-]+:\\d+");
    assertThat(cell.get("a").at("/properties/name").asString()).isEqualTo("Ada");
    assertThat(cell.get("k").path("type").asString()).isEqualTo("KNOWS");
    assertThat(cell.get("k").path("startElementId").asString()).isEqualTo(cell.get("a").path("elementId").asString());
    assertThat(cell.get("k").at("/properties/since").asInt()).isEqualTo(2020);
    assertThat(cell.get("path").path("length").asInt()).isEqualTo(1);
    assertThat(cell.get("path").at("/nodes/1/properties/name").asString()).isEqualTo("Bob");
    assertThat(cell.get("born").asString()).isEqualTo("1815-12-10");
    assertThat(cell.get("seen").asString()).isEqualTo("2026-01-02T03:04:05+01:00[Europe/Berlin]");
    assertThat(cell.get("offset").asString()).isEqualTo("2026-01-02T03:04:05+02:00");
    assertThat(cell.get("at").asString()).isEqualTo("2026-01-02T03:04:05");
    assertThat(cell.get("wakes").asString()).isEqualTo("07:30:00+01:00");
    assertThat(cell.get("alarm").asString()).isEqualTo("06:45:00");
    assertThat(cell.get("stint").asString()).startsWith("P14M3DT14706.5");
    assertThat(cell.get("home").path("crs").asString()).isEqualTo("wgs-84");
    assertThat(cell.get("home").path("srid").asInt()).isEqualTo(4326);
    assertThat(cell.get("home").path("x").asDouble()).isEqualTo(-0.1276);
    assertThat(cell.get("desk").path("crs").asString()).isEqualTo("cartesian-3d");
    assertThat(cell.get("desk").path("z").asDouble()).isEqualTo(3.0);
    assertThat(cell.get("tags").toString()).isEqualTo("[\"math\",\"engines\"]");
    assertThat(cell.get("m").toString()).isEqualTo("{\"name\":\"Ada\",\"n\":1}");
    assertThat(cell.get("big").asString()).isEqualTo("1152921504606846976");
    assertThat(cell.get("score").asDouble()).isEqualTo(0.5);
    assertThat(r.table().columns()).extracting(ToolResult.Column::type).startsWith("NODE", "RELATIONSHIP", "PATH",
        "DATE", "DATE_TIME", "LOCAL_DATE_TIME", "TIME", "LOCAL_TIME", "DURATION", "POINT", "POINT", "LIST", "MAP",
        "INTEGER", "FLOAT");
    ToolResult params = CONNECTOR.execute(call(Neo4jTools.QUERY, Map.of("cypher", "RETURN $s AS s, $n AS n, $big AS "
        + "big, $f AS f, $b AS b, $l AS l, $m AS m, date($day) AS day", "parameters", Map.of("s", "a", "n", 42,
        "big", 9_007_199_254_740_993L, "f", 1.5, "b", true, "l", List.of(1, "x"), "m", Map.of("k", "v"), "day",
        "2026-11-11"))), plain(), secrets(), TestCtx.of());
    assertThat(params.table().rows().get(0).toString()).isEqualTo("[\"a\",42,\"9007199254740993\",1.5,true,[1,\"x\"],"
        + "{\"k\":\"v\"},\"2026-11-11\"]");
  }

  // ── Reads can't write: the reader, Neo4j's plan, the READ transaction ───────────────────────────────────────

  @Test
  void aWriteIsRefusedByTheReaderByNeo4jsPlanAndByTheReadTransaction() throws Exception {
    ConnectionConfig cfg = plain();
    String write = "MATCH (p:Person {id: 1}) SET p.flagged = true RETURN p";
    // 1. The reader: classify refuses it before any I/O, and execute refuses it again.
    assertThat(CONNECTOR.classify(call(Neo4jTools.QUERY, Map.of("cypher", write)), cfg, new PolicyView(Env.DEV,
        "dev.neo4j", JsonNodeFactory.instance.objectNode(), TestCtx.dev())).ruleId()).isEqualTo("dev.neo4j.query-writes");
    assertThatThrownBy(() -> CONNECTOR.execute(call(Neo4jTools.QUERY, Map.of("cypher", write)), cfg, secrets(),
        TestCtx.of())).isInstanceOf(ConnectorException.class);
    Neo4jSettings s = Neo4jSettings.of(cfg);
    TestCtx ctx = TestCtx.of();
    Neo4jAuth auth = Neo4jAuth.resolve(s, secrets(), ctx);
    try (DriverBolt bolt = new DriverBolt(); Bolt.Lease lease = bolt.fresh(s, auth, ctx)) {
      Bolt.Client client = lease.client();
      // 2. Past the reader (classify skipped): Neo4j's EXPLAIN calls it a write, so it never runs.
      for (String disguised : List.of(write, "FOREACH (x IN [1] | CREATE (:Disguised {x: x}))",
          "MERGE (d:Disguised {x: 1}) RETURN d", "CALL { CREATE (:Disguised) } RETURN 1 AS one")) {
        try (Bolt.Tx tx = client.begin(AccessMode.READ, "neo4j", Duration.ofSeconds(5), Map.of(), 10)) {
          assertThatThrownBy(() -> Neo4jConnector.read(tx, disguised, Map.of(), 10, ctx, "neo4j", true))
              .as(disguised).isInstanceOfSatisfying(ConnectorException.class, e -> {
                assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
                assertThat(e.getMessage()).contains("not read only");
              });
        }
      }
      // 3. Past the reader and the plan check: the READ transaction itself refuses the write.
      for (String disguised : List.of(write, "FOREACH (x IN [1] | CREATE (:Disguised {x: x}))",
          "MERGE (d:Disguised {x: 1}) RETURN d", "CALL { CREATE (:Disguised) } RETURN 1 AS one")) {
        try (Bolt.Tx tx = client.begin(AccessMode.READ, "neo4j", Duration.ofSeconds(5), Map.of(), 10)) {
          assertThatThrownBy(() -> Neo4jConnector.read(tx, disguised, Map.of(), 10, ctx, "neo4j", false))
              .as(disguised).isInstanceOfSatisfying(RuntimeException.class, e -> {
                ConnectorException c = Neo4jErrors.read(e, s, Duration.ofSeconds(5));
                assertThat(c.kind()).isEqualTo(ConnectorException.Kind.DENIED);
                assertThat(c.getMessage()).startsWith("Neo4j refused a write in a read transaction: Writing in "
                    + "read access mode not allowed");
              });
        }
      }
      // What only the reader refuses: LOAD CSV is a read to Neo4j, and DBMS procedures plan as schema commands.
      try (Bolt.Tx tx = client.begin(AccessMode.READ, "neo4j", Duration.ofSeconds(5), Map.of(), 1)) {
        assertThat(tx.run("EXPLAIN LOAD CSV FROM 'file:///gaterook-it.csv' AS r RETURN r", Map.of()).consume()
            .queryType()).isEqualTo("r");
      }
      try (Bolt.Tx tx = client.begin(AccessMode.READ, "neo4j", Duration.ofSeconds(5), Map.of(), 1)) {
        assertThatThrownBy(() -> Neo4jConnector.read(tx, "CALL dbms.listConfig() YIELD name RETURN name", Map.of(),
            10, ctx, "neo4j", true)).hasMessageContaining("not read only");
      }
    }
    assertThat(count("MATCH (d:Disguised) RETURN count(d)")).isZero();
    assertThat(count("MATCH (p:Person) WHERE p.flagged IS NOT NULL RETURN count(p)")).isZero();
  }

  @Test
  void administrationLoadCsvAndProceduresAreRefusedBeforeAnyIo() {
    PolicyView policy = new PolicyView(Env.DEV, "dev.neo4j", JsonNodeFactory.instance.objectNode(), TestCtx.dev());
    Map<String, String> refused = Map.of(
        "SHOW USERS", "dev.neo4j.admin",
        "CREATE USER mallory SET PASSWORD 'mallory-pw-0001'", "dev.neo4j.admin",
        "ALTER CURRENT USER SET PASSWORD FROM 'a' TO 'b'", "dev.neo4j.admin",
        "LOAD CSV FROM 'file:///etc/passwd' AS r RETURN r", "dev.neo4j.load-csv",
        "CALL dbms.listConfig() YIELD name RETURN name", "dev.neo4j.procedure",
        "CALL dbms.security.listUsers()", "dev.neo4j.procedure",
        "USE system SHOW USERS", "dev.neo4j.use",
        "CALL { CREATE (:Disguised) } IN TRANSACTIONS", "dev.neo4j.in-transactions");
    refused.forEach((text, rule) -> {
      for (String tool : List.of(Neo4jTools.QUERY, Neo4jTools.EXECUTE)) {
        assertThat(CONNECTOR.classify(call(tool, Map.of("cypher", text)), plain(), policy).ruleId()).as(tool + " "
            + text).isEqualTo(rule);
      }
    });
    try (Session s = admin.session(org.neo4j.driver.SessionConfig.forDatabase("system"))) {
      assertThat(s.run("SHOW USERS YIELD user RETURN user").list(r -> r.get(0).asString())).doesNotContain("mallory");
    }
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anApprovedChangeRunsOnceAndReportsNeo4jsCounters() throws Exception {
    ConnectionConfig cfg = plain();
    Map<String, Object> args = Map.of("cypher", "MATCH (a:Person {id: 1}) CREATE (a)-[:MENTORS {since: $since}]->"
        + "(c:Person {id: 3, name: $name}) RETURN c.name AS name", "parameters", Map.of("since", 2026, "name",
        "Grace"), "note", "Add Grace as Ada's mentee");
    ToolCall c = call(Neo4jTools.EXECUTE, args);
    assertThat(CONNECTOR.classify(c, cfg, new PolicyView(Env.DEV, "dev.neo4j", JsonNodeFactory.instance.objectNode(),
        TestCtx.dev())).kind()).isEqualTo(Classification.Kind.MUTATION);
    PreparedAction prepared = CONNECTOR.prepare(c, cfg, secrets(), TestCtx.of());
    assertThat(prepared.outcome()).as(prepared.message()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    Map<String, String> impact = new LinkedHashMap<>();
    prepared.preview().impact().forEach(f -> impact.put(f.label(), f.value()));
    assertThat(impact.get("Query type")).isEqualTo("read and write (rw)");
    assertThat(impact.get("Plan")).startsWith("ProduceResults").contains("Create");
    assertThat(count("MATCH (p:Person {id: 3}) RETURN count(p)")).as("prepare changes nothing").isZero();
    assertThatThrownBy(() -> CONNECTOR.execute(c, cfg, secrets(), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.DENIED));
    ToolResult r = CONNECTOR.execute(c, cfg, secrets(), TestCtx.of().approved(prepared.preview().payload()));
    JsonNode s = r.structuredContent();
    assertThat(s.path("committed").asBoolean()).isTrue();
    assertThat(s.path("queryType").asString()).isEqualTo("rw");
    assertThat(s.path("counters").toString()).isEqualTo("{\"nodesCreated\":1,\"relationshipsCreated\":1,"
        + "\"propertiesSet\":3,\"labelsAdded\":1}");
    assertThat(s.path("rows").toString()).isEqualTo("[[\"Grace\"]]");
    assertThat(r.content().getFirst().text()).isEqualTo("Committed: 1 node created, 1 relationship created, 3 "
        + "properties set, 1 label added.");
    assertThat(count("MATCH (:Person {id: 1})-[:MENTORS]->(p:Person {id: 3}) RETURN count(p)")).isEqualTo(1);
    // A constraint Neo4j enforces leaves nothing changed.
    ToolCall dup = call(Neo4jTools.EXECUTE, Map.of("cypher", "CREATE (:Person {id: 1, name: 'Copy'})"));
    assertThatThrownBy(() -> CONNECTOR.execute(dup, cfg, secrets(), TestCtx.of().approved(CONNECTOR.describeAction(dup,
        cfg).payload()))).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("already exists").containsIgnoringCase("nothing changed");
        });
    assertThat(count("MATCH (p:Person {name: 'Copy'}) RETURN count(p)")).isZero();
    // A schema change: Neo4j plans it as s and counts the index.
    ToolCall index = call(Neo4jTools.EXECUTE, Map.of("cypher", "CREATE INDEX it_item_i IF NOT EXISTS FOR (n:Item) "
        + "ON (n.i)"));
    PreparedAction planned = CONNECTOR.prepare(index, cfg, secrets(), TestCtx.of());
    assertThat(planned.preview().impact().getFirst().value()).isEqualTo("a schema change (s)");
    ToolResult made = CONNECTOR.execute(index, cfg, secrets(), TestCtx.of().approved(planned.preview().payload()));
    assertThat(made.structuredContent().path("counters").path("indexesAdded").asInt()).isEqualTo(1);
    // What Neo4j can't plan is never put to a person.
    PreparedAction broken = CONNECTOR.prepare(call(Neo4jTools.EXECUTE, Map.of("cypher", "CREATE (n:Person {id: })")),
        cfg, secrets(), TestCtx.of());
    assertThat(broken.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(broken.message()).startsWith("Neo4j refused the statement: Invalid input");
  }

  // ── Limits and timeouts ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void readsStopAtTheRowAndSizeLimitsAndCutLongValues() throws Exception {
    ToolResult rows = CONNECTOR.execute(call(Neo4jTools.QUERY, Map.of("cypher", "MATCH (n:Item) RETURN n.i AS i, "
        + "n.text AS text ORDER BY i", "limit", 10)), plain(), secrets(), TestCtx.of());
    assertThat(rows.table().rows()).hasSize(10);
    assertThat(rows.truncatedReason()).isEqualTo("row_limit");
    assertThat(rows.table().rows().get(0).get(1).asString()).hasSize(8_192 + "…‹10000 characters›".length());
    Limits small = new Limits(1_000, 1_000, 48 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024,
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    ToolResult sized = CONNECTOR.execute(call(Neo4jTools.QUERY, Map.of("cypher", "MATCH (n:Item) RETURN n ORDER BY "
        + "n.i")), plain(), secrets(), TestCtx.of().with(small));
    assertThat(sized.truncatedReason()).isEqualTo("size_limit");
    assertThat(sized.table().rows().size()).isBetween(1, 6);
  }

  @Test
  void aSlowQueryStopsAtTheTransactionTimeout() {
    ConnectionConfig cfg = config("bolt://127.0.0.1:" + TestServices.port("GW_NEO4J_PORT"), Env.DEV,
        JsonNodeFactory.instance.objectNode().set("advanced", JsonNodeFactory.instance.objectNode()
            .put("transactionTimeoutMs", 1_000)));
    long started = System.nanoTime();
    assertThatThrownBy(() -> query(cfg, "UNWIND range(1, 2000000000) AS x WITH x WHERE x % 7 = 3 RETURN count(x) "
        + "AS n", TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).startsWith("The transaction ran longer than 1 s, so Neo4j stopped it.");
        });
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
    // The connection still works after Neo4j stopped the transaction.
    assertThat(CONNECTOR.health(cfg, secrets(), TestCtx.of()).state()).isEqualTo(HealthResult.State.HEALTHY);
  }
}
