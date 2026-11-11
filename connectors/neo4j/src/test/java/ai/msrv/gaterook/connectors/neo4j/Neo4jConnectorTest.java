package ai.msrv.gaterook.connectors.neo4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.neo4j.FakeBolt.Answer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.exceptions.AuthenticationException;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;
import org.neo4j.driver.exceptions.TransientException;
import org.neo4j.driver.internal.value.NodeValue;
import org.neo4j.driver.internal.value.PathValue;
import org.neo4j.driver.internal.value.RelationshipValue;
import org.neo4j.driver.internal.InternalPath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Neo4j connector against a fake server behind its Bolt seam: what reaches the server, and what never does. */
class Neo4jConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PASSWORD = "graph-reader-pw-0001";

  private final FakeBolt server = new FakeBolt();
  private final Neo4jConnector neo4j = new Neo4jConnector(server);
  private final TestSecrets secrets = new TestSecrets().with(Neo4jSettings.PASSWORD, PASSWORD);

  static ConnectionConfig config(Env env) {
    return config(env, "neo4j+s://graph.example.com");
  }

  static ConnectionConfig config(Env env, String uri) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("uri", uri).put("database", "movies");
    c.putObject("authentication").put("method", "password").put("user", "gw_reader");
    return new ConnectionConfig("conn-neo4j-" + env.id(), env.id() + "-graph", "neo4j", env, 1, c,
        Set.of(Neo4jSettings.PASSWORD), null);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".neo4j", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-graph",
        (ObjectNode) JSON.readTree(args), null);
  }

  private static String cypher(String cypher) {
    return JSON.writeValueAsString(Map.of("cypher", cypher));
  }

  private static String cypher(String cypher, Map<String, Object> parameters) {
    return JSON.writeValueAsString(Map.of("cypher", cypher, "parameters", parameters));
  }

  private Classification classify(String tool, String args) {
    return neo4j.classify(call(tool, args), config(Env.DEV), policy(Env.DEV));
  }

  // ── Tools and routes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesTwoReadsAndOneApprovedChange() {
    assertThat(neo4j.manifest().id()).isEqualTo("neo4j");
    assertThat(neo4j.manifest().name()).isEqualTo("Neo4j");
    assertThat(neo4j.manifest().group()).isEqualTo("Databases");
    assertThat(neo4j.manifest().icon()).isEqualTo("waypoints");
    assertThat(neo4j.manifest().tools()).extracting(ToolDefinition::name)
        .containsExactly("neo4j_schema", "neo4j_query", "neo4j_execute");
    ToolDefinition execute = neo4j.manifest().tools().getLast();
    assertThat(execute.mutating()).isTrue();
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(execute.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.inputSchema().at("/properties/note/maxLength").asInt()).isEqualTo(500);
    assertThat(execute.inputSchema().at("/properties/note/description").asString())
        .isEqualTo("Why you need this change. Shown to the approver as unverified.");
    for (ToolDefinition read : neo4j.manifest().tools().subList(0, 2)) {
      assertThat(read.nature()).isEqualTo(ToolDefinition.Nature.READ);
      assertThat(read.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
    }
  }

  @Test
  void statementsAreRoutedByWhatTheyDo() {
    assertThat(classify("neo4j_schema", "{}").kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("neo4j_query", cypher("MATCH (n:Person) WHERE n.note = 'SET; DELETE' RETURN n")).kind())
        .isEqualTo(Classification.Kind.READ);
    Classification writes = classify("neo4j_query", cypher("MATCH (n) SET n.x = 1"));
    assertThat(writes.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(writes.ruleId()).isEqualTo("dev.neo4j.query-writes");
    assertThat(writes.reason()).contains("SET");
    assertThat(writes.hint()).contains("neo4j_execute");
    Classification change = classify("neo4j_execute", cypher("MERGE (n:Person {id: $id}) SET n.seen = true",
        Map.of("id", 7)));
    assertThat(change.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(change.facts().path("changes").asString()).isEqualTo("MERGE, SET");
    Classification schema = classify("neo4j_execute", cypher("CREATE INDEX i FOR (n:Person) ON (n.name)"));
    assertThat(schema.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(schema.facts().path("schema").asBoolean()).isTrue();
    assertThat(classify("neo4j_query", cypher("DROP INDEX i")).ruleId()).isEqualTo("dev.neo4j.query-writes");
    assertThat(classify("neo4j_execute", cypher("MATCH (n) RETURN n")).ruleId()).isEqualTo("dev.neo4j.execute-read");
    assertThat(classify("neo4j_drop", "{}").ruleId()).isEqualTo("dev.neo4j.unknown-tool");
  }

  @Test
  void whatGateRookNeverRunsIsRefusedBeforeAnyIo() {
    Map<String, String> refused = Map.ofEntries(
        Map.entry("SHOW USERS", "admin"), Map.entry("CREATE USER x SET PASSWORD 'y-0000000'", "admin"),
        Map.entry("LOAD CSV FROM 'file:///etc/passwd' AS r RETURN r", "load-csv"),
        Map.entry("CALL dbms.listConfig()", "procedure"), Map.entry("CALL apoc.load.json('x')", "procedure"),
        Map.entry("USE system SHOW USERS", "use"), Map.entry("RETURN 1; RETURN 2", "multiple-statements"),
        Map.entry("CALL { CREATE (n) } IN TRANSACTIONS", "in-transactions"),
        Map.entry("EXPLAIN MATCH (n) RETURN n", "query-options"),
        Map.entry("RETURN apoc.cypher.runFirstColumnSingle('x', {})", "function"),
        Map.entry("RETURN 'open", "unparsed"), Map.entry("RETURN '\\u0027'", "unparsed"));
    for (String tool : List.of("neo4j_query", "neo4j_execute")) {
      refused.forEach((text, rule) -> {
        Classification c = classify(tool, cypher(text));
        assertThat(c.kind()).as(tool + " " + text).isEqualTo(Classification.Kind.DENY);
        assertThat(c.ruleId()).as(tool + " " + text).isEqualTo("dev.neo4j." + rule);
      });
    }
    assertThat(classify("neo4j_query", cypher("RETURN 'open")).reason())
        .endsWith("GateRook doesn't run what it can't read with certainty.");
    assertThat(server.transactions).isEmpty();
    assertThat(server.leases).isZero();
  }

  @Test
  void parametersAndSettingsAreCheckedToo() {
    assertThat(classify("neo4j_query", "{\"cypher\": \"RETURN $x\", \"parameters\": {\"x\": 18446744073709551616}}")
        .ruleId()).isEqualTo("dev.neo4j.bad-argument");
    assertThat(classify("neo4j_query", "{\"cypher\": \"RETURN $x\", \"parameters\": {\"bad name\": 1}}").ruleId())
        .isEqualTo("dev.neo4j.bad-argument");
    assertThat(classify("neo4j_query", "{\"cypher\": \"RETURN $x\", \"parameters\": [1]}").ruleId())
        .isEqualTo("dev.neo4j.bad-argument");
    assertThat(classify("neo4j_query", "{\"cypher\": \"RETURN $x\", \"parameters\": {\"x\": {\"a\": [1, \"b\", "
        + "null, true, 1.5]}}}").kind()).isEqualTo(Classification.Kind.READ);
    Classification plain = neo4j.classify(call("neo4j_query", cypher("RETURN 1")), config(Env.DEV,
        "bolt://graph.example.com"), policy(Env.DEV));
    assertThat(plain.ruleId()).isEqualTo("dev.neo4j.bad-config");
    assertThat(plain.reason()).contains("Without TLS");
  }

  // ── The exact action and its plan ───────────────────────────────────────────────────────────────────────────

  @Test
  void theApproverSeesTheExactStatementAndItsPayloadIsWhatRuns() {
    ToolCall c = call("neo4j_execute", "{\"cypher\": \"MATCH (n:Person {id: $id}) DETACH DELETE n\", "
        + "\"parameters\": {\"id\": 7}, \"note\": \"cleanup of a test person\"}");
    ActionPreview p = neo4j.describeAction(c, config(Env.PROD));
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.COMMAND);
    assertThat(p.language()).isEqualTo("cypher");
    assertThat(p.text()).isEqualTo("MATCH (n:Person {id: $id}) DETACH DELETE n");
    assertThat(p.target()).isEqualTo("prod-graph · graph.example.com · movies");
    assertThat(p.summary()).isEqualTo("neo4j_execute on prod-graph: DETACH, DELETE");
    assertThat(p.payload().toString()).isEqualTo("{\"cypher\":\"MATCH (n:Person {id: $id}) DETACH DELETE n\","
        + "\"parameters\":{\"id\":7}}");
    assertThat(p.fields()).extracting(ActionPreview.Field::label).containsExactly("Parameters");
    assertThat(p.impact()).extracting(ActionPreview.Field::label).contains("Changes", "Transaction", "Retried");
    ActionPreview read = neo4j.describeAction(call("neo4j_query", cypher("MATCH (n) RETURN n")), config(Env.PROD));
    assertThat(read.payload().path("arguments").path("cypher").asString()).isEqualTo("MATCH (n) RETURN n");
  }

  @Test
  void prepareShowsNeo4jsPlanFromAReadTransaction() throws Exception {
    server.on("EXPLAIN ", Answer.plan("rw").withSummary(new Bolt.Summary("rw", Map.of(), new Bolt.Plan(
        "ProduceResults", 12.0, null, List.of(new Bolt.Plan("SetProperty", 12.0, "n.seen = true", List.of(
            new Bolt.Plan("NodeByLabelScan", 12.0, "n:Person", List.of()))))), List.of(new Bolt.Note("01N50",
        "warn: label does not exist. The label `Persn` does not exist.", "warn: label does not exist. The label "
        + "`Persn` does not exist.", "WARNING")), FakeBolt.AGENT, "5.8")));
    ToolCall c = call("neo4j_execute", cypher("MATCH (n:Person) SET n.seen = $seen", Map.of("seen", true)));
    PreparedAction prepared = neo4j.prepare(c, config(Env.DEV), secrets, TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    Map<String, String> impact = new java.util.LinkedHashMap<>();
    prepared.preview().impact().forEach(f -> impact.put(f.label(), f.value()));
    assertThat(impact.get("Query type")).isEqualTo("read and write (rw)");
    assertThat(impact.get("Plan")).isEqualTo("ProduceResults ← SetProperty (n.seen = true) ← NodeByLabelScan "
        + "(n:Person)");
    assertThat(impact.get("Estimated rows")).isEqualTo("12");
    assertThat(prepared.preview().warnings()).anyMatch(w -> w.contains("Persn"));
    FakeBolt.Tx tx = server.last();
    assertThat(tx.mode).isEqualTo(AccessMode.READ);
    assertThat(tx.database).isEqualTo("movies");
    assertThat(tx.statements).containsExactly("EXPLAIN MATCH (n:Person) SET n.seen = $seen");
    assertThat(tx.parameters.getFirst()).isEqualTo(Map.of("seen", true));
    assertThat(tx.committed).isFalse();
    assertThat(tx.closed).isTrue();
    assertThat(server.released).isEqualTo(server.leases);
  }

  @Test
  void prepareNeverPutsAReadOrWhatNeo4jCantPlanToAPerson() throws Exception {
    server.on("EXPLAIN MATCH (n) RETURN n", Answer.plan("r"));
    server.on("EXPLAIN MATCH (n) SET", Answer.plan("w"));
    server.on("EXPLAIN CREATE (n", Answer.fails(new ClientException("Neo.ClientError.Statement.SyntaxError",
        "Invalid input ')': expected an expression (line 1, column 12)")));
    assertThat(neo4j.prepare(call("neo4j_execute", cypher("MATCH (n) SET n.x = 1 RETURN n")), config(Env.DEV),
        secrets, TestCtx.of()).outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    server.on("EXPLAIN MERGE", Answer.plan("r"));
    PreparedAction read = neo4j.prepare(call("neo4j_execute", cypher("MERGE (n:X) RETURN n")), config(Env.DEV),
        secrets, TestCtx.of());
    assertThat(read.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(read.message()).contains("read only").contains("neo4j_query");
    PreparedAction syntax = neo4j.prepare(call("neo4j_execute", cypher("CREATE (n:X {a: })")), config(Env.DEV),
        secrets, TestCtx.of());
    assertThat(syntax.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(syntax.message()).startsWith("Neo4j refused the statement: Invalid input");
    // A read an endpoint holds for approval: Neo4j must plan it read only too.
    assertThat(neo4j.prepare(call("neo4j_query", cypher("MATCH (n) RETURN n")), config(Env.DEV), secrets,
        TestCtx.of()).outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    server.on("EXPLAIN MATCH (n) WITH", Answer.plan("w"));
    PreparedAction sneaky = neo4j.prepare(call("neo4j_query", cypher("MATCH (n) WITH n RETURN n")), config(Env.DEV),
        secrets, TestCtx.of());
    assertThat(sneaky.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(sneaky.message()).contains("write only");
    assertThat(server.transactions).allSatisfy(t -> assertThat(t.mode).isEqualTo(AccessMode.READ))
        .allSatisfy(t -> assertThat(t.committed).isFalse());
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aReadRunsInAReadTransactionAfterNeo4jCallsItReadOnly() throws Exception {
    server.on("EXPLAIN ", Answer.plan("r"));
    server.on("MATCH p = ", Answer.rows(List.of("p", "n", "since", "at"), List.of(
        List.of(new PathValue(new InternalPath(Neo4jJsonTest.ada(), Neo4jJsonTest.knows(), Neo4jJsonTest.bob())),
            new NodeValue(Neo4jJsonTest.ada()), Values.value(2020), Values.value(java.time.LocalDate.of(2026, 1, 2))),
        List.of(Values.NULL, new NodeValue(Neo4jJsonTest.bob()), Values.value(9_007_199_254_740_993L),
            Values.NULL))).withSummary(FakeBolt.summary("r")));
    TestCtx ctx = TestCtx.of();
    ToolResult r = neo4j.execute(call("neo4j_query", cypher("MATCH p = (n)-[:KNOWS]->() WHERE n.name = $name "
        + "RETURN p, n, 2020 AS since, date() AS at", Map.of("name", "Ada"))), config(Env.DEV), secrets, ctx);
    FakeBolt.Tx tx = server.last();
    assertThat(tx.mode).isEqualTo(AccessMode.READ);
    assertThat(tx.statements).containsExactly("EXPLAIN MATCH p = (n)-[:KNOWS]->() WHERE n.name = $name RETURN p, n, "
        + "2020 AS since, date() AS at", "MATCH p = (n)-[:KNOWS]->() WHERE n.name = $name RETURN p, n, 2020 AS "
        + "since, date() AS at");
    assertThat(tx.parameters).allSatisfy(p -> assertThat(p).isEqualTo(Map.of("name", "Ada")));
    assertThat(tx.committed).isFalse();
    assertThat(tx.closed).isTrue();
    assertThat(tx.metadata).containsEntry("app", "GateRook").containsEntry("purpose", "read")
        .containsEntry("call", "tc_neo4j_test");
    assertThat(tx.timeout).isEqualTo(TestCtx.dev().statementTimeout());
    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("p", "n", "since", "at");
    assertThat(r.table().columns()).extracting(ToolResult.Column::type).containsExactly("PATH", "NODE", "INTEGER",
        "DATE");
    JsonNode first = r.table().rows().get(0);
    assertThat(first.at("/0/relationships/0/type").asString()).isEqualTo("KNOWS");
    assertThat(first.at("/1/labels/0").asString()).isEqualTo("Person");
    assertThat(first.get(3).asString()).isEqualTo("2026-01-02");
    assertThat(r.table().rows().get(1).get(2).asString()).isEqualTo("9007199254740993");
    assertThat(r.structuredContent().path("database").asString()).isEqualTo("movies");
    assertThat(r.structuredContent().path("queryType").asString()).isEqualTo("r");
    assertThat(r.truncated()).isFalse();
    assertThat(ctx.taints).contains(PASSWORD);
    assertThat(server.events).contains("lease conn-neo4j-dev PASSWORD gw_reader");
  }

  @Test
  void neo4jsPlanHasTheLastWordOverTheReader() {
    // A server that names no query type runs nothing.
    server.on("EXPLAIN RETURN", Answer.plan(null));
    // The reader sees a read; the server's plan says it writes: nothing runs but the EXPLAIN.
    server.on("EXPLAIN ", Answer.plan("rw"));
    assertThatThrownBy(() -> neo4j.execute(call("neo4j_query", cypher("MATCH (n) RETURN n")), config(Env.DEV),
        secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("read and write, not read only");
        });
    assertThat(server.statements()).containsExactly("EXPLAIN MATCH (n) RETURN n");
    assertThatThrownBy(() -> neo4j.execute(call("neo4j_query", cypher("RETURN 1")), config(Env.DEV), secrets,
        TestCtx.of())).hasMessageContaining("unknown, not read only");
    assertThat(server.statements()).doesNotContain("RETURN 1");
  }

  @Test
  void theReadTransactionsOwnRefusalAndReportedChangesEndTheRead() {
    server.on("EXPLAIN ", Answer.plan("r"));
    server.on("MATCH (n) RETURN n", Answer.fails(new ClientException("Neo.ClientError.Statement.AccessMode",
        "Writing in read access mode not allowed. Attempted write to movies")));
    assertThatThrownBy(() -> neo4j.execute(call("neo4j_query", cypher("MATCH (n) RETURN n")), config(Env.DEV),
        secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).startsWith("Neo4j refused a write in a read transaction: Writing in read");
        });
    server.on("MATCH (m) RETURN m", Answer.rows(List.of("m"), List.of(List.of(Values.value(1))))
        .withSummary(FakeBolt.counters("r", Map.of("propertiesSet", 1))));
    assertThatThrownBy(() -> neo4j.execute(call("neo4j_query", cypher("MATCH (m) RETURN m")), config(Env.DEV),
        secrets, TestCtx.of())).hasMessageContaining("rolled it back");
    assertThat(server.transactions).noneMatch(t -> t.committed);
  }

  @Test
  void readsStopAtTheRowLimitTheSizeLimitAndTheCellSize() throws Exception {
    server.on("EXPLAIN ", Answer.plan("r"));
    List<List<Value>> many = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      many.add(List.of(Values.value(i), Values.value("x".repeat(9_000))));
    }
    server.on("UNWIND", Answer.rows(List.of("i", "s"), many).withSummary(FakeBolt.summary("r")));
    ToolResult limited = neo4j.execute(call("neo4j_query", "{\"cypher\": \"UNWIND range(1, 30) AS i RETURN i, "
        + "'x' AS s\", \"limit\": 10}"), config(Env.DEV), secrets, TestCtx.of());
    assertThat(limited.table().rows()).hasSize(10);
    assertThat(limited.truncatedReason()).isEqualTo("row_limit");
    assertThat(limited.table().rows().get(0).get(1).asString()).endsWith("…‹9000 characters›");
    assertThat(server.last().fetchSize).isEqualTo(11);
    Limits small = new Limits(1_000, 1_000, 32 * 1024, 256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024,
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null);
    ToolResult sized = neo4j.execute(call("neo4j_query", cypher("UNWIND range(1, 30) AS i RETURN i, 'x' AS s")),
        config(Env.DEV), secrets, TestCtx.of().with(small));
    assertThat(sized.truncatedReason()).isEqualTo("size_limit");
    assertThat(sized.table().rows().size()).isBetween(1, 3);
    // The endpoint's row limit wins over the agent's.
    ToolResult prod = neo4j.execute(call("neo4j_query", "{\"cypher\": \"UNWIND range(1, 30) AS i RETURN i, 'x' AS "
        + "s\", \"limit\": 10000}"), config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(prod.table().rows().size()).isLessThanOrEqualTo(100);
  }

  @Test
  void theTransactionTimeoutIsTheShortestOfConnectionPolicyAndCall() throws Exception {
    server.on("EXPLAIN ", Answer.plan("r"));
    neo4j.execute(call("neo4j_query", cypher("RETURN 1")), config(Env.DEV), secrets,
        TestCtx.of().timeout(Duration.ofSeconds(3)));
    assertThat(server.last().timeout).isLessThanOrEqualTo(Duration.ofSeconds(2)).isGreaterThan(Duration.ofSeconds(1));
    ObjectNode c = (ObjectNode) config(Env.DEV).config().deepCopy();
    c.putObject("advanced").put("transactionTimeoutMs", 700);
    ConnectionConfig fast = new ConnectionConfig("conn-fast", "fast", "neo4j", Env.DEV, 1, c,
        Set.of(Neo4jSettings.PASSWORD), null);
    neo4j.execute(call("neo4j_query", cypher("RETURN 1")), fast, secrets, TestCtx.of());
    assertThat(server.last().timeout).isEqualTo(Duration.ofMillis(700));
    assertThatThrownBy(() -> neo4j.execute(call("neo4j_query", cypher("RETURN 1")), config(Env.DEV), secrets,
        TestCtx.of().timeout(Duration.ofMillis(500)))).isInstanceOfSatisfying(ConnectorException.class,
        e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    server.on("UNWIND", Answer.fails(new ClientException("Neo.ClientError.Transaction.TransactionTimedOutClientConfiguration",
        "The transaction has been terminated. The transaction has not completed within the timeout specified at its "
        + "start by the client.")));
    assertThatThrownBy(() -> neo4j.execute(call("neo4j_query", cypher("UNWIND range(1, 100000000) AS x RETURN "
        + "count(x)")), fast, secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).isEqualTo("The transaction ran longer than 0.7 s, so Neo4j stopped it. Narrow "
              + "the query, or add a LIMIT.");
        });
  }

  @Test
  void theSchemaComesFromTheSchemaProceduresAndShowCommands() throws Exception {
    server.on("CALL db.labels()", Answer.rows(List.of("label"), List.of(List.of(Values.value("Movie")),
        List.of(Values.value("Person")), List.of(Values.value("Studio")))));
    server.on("CALL db.relationshipTypes()", Answer.rows(List.of("relationshipType"),
        List.of(List.of(Values.value("ACTED_IN")))));
    server.on("CALL db.propertyKeys()", Answer.rows(List.of("propertyKey"), List.of(List.of(Values.value("name")))));
    server.on("SHOW INDEXES", Answer.rows(List.of("name", "type", "entityType", "labelsOrTypes", "properties",
        "state", "owningConstraint"), List.of(List.of(Values.value("person_name"), Values.value("RANGE"),
        Values.value("NODE"), Values.value(List.of("Person")), Values.value(List.of("name")), Values.value("ONLINE"),
        Values.NULL))));
    server.on("SHOW CONSTRAINTS", Answer.rows(List.of("name", "type"), List.of()));
    ToolResult r = neo4j.execute(call("neo4j_schema", "{\"limit\": 2}"), config(Env.DEV), secrets, TestCtx.of());
    JsonNode s = r.structuredContent();
    assertThat(s.path("labels").toString()).isEqualTo("[\"Movie\",\"Person\"]");
    assertThat(s.path("truncated").toString()).isEqualTo("[\"labels\"]");
    assertThat(s.at("/indexes/0/labelsOrTypes/0").asString()).isEqualTo("Person");
    assertThat(s.at("/indexes/0").has("owningConstraint")).isFalse();
    assertThat(s.path("constraints").size()).isZero();
    assertThat(r.content().getFirst().text()).startsWith("movies: 2 labels (Movie, Person), 1 relationship type "
        + "(ACTED_IN), 1 property key, 1 index, 0 constraints");
    assertThat(server.transactions).allSatisfy(t -> assertThat(t.mode).isEqualTo(AccessMode.READ));
    assertThat(server.statements()).noneMatch(st -> st.contains("visualization"));
    assertThat(server.transactions.getFirst().parameters.getFirst()).isEqualTo(Map.of("limit", 3L));
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static JsonNode approved(String cypher, Map<String, Object> parameters) {
    ObjectNode p = JsonNodeFactory.instance.objectNode().put("cypher", cypher);
    p.set("parameters", JSON.valueToTree(parameters));
    return p;
  }

  @Test
  void aChangeNeedsApprovalAndRunsTheApprovedStatementOnce() throws Exception {
    ToolCall c = call("neo4j_execute", cypher("CREATE (n:Person {name: $name}) RETURN n", Map.of("name",
        "Mallory")));
    assertThatThrownBy(() -> neo4j.execute(c, config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
            .isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(server.transactions).isEmpty();
    Map<String, Integer> counters = new java.util.LinkedHashMap<>();
    counters.put("nodesCreated", 1);
    counters.put("propertiesSet", 1);
    counters.put("labelsAdded", 1);
    server.on("CREATE (n:Person", Answer.rows(List.of("n"), List.of(List.of(new NodeValue(Neo4jJsonTest.ada()))))
        .withSummary(FakeBolt.counters("rw", counters)));
    // What runs is the approved payload, not the call's arguments.
    ToolResult r = neo4j.execute(c, config(Env.DEV), secrets, TestCtx.of().approved(approved(
        "CREATE (n:Person {name: $name}) RETURN n", Map.of("name", "Ada"))));
    FakeBolt.Tx tx = server.last();
    assertThat(tx.mode).isEqualTo(AccessMode.WRITE);
    assertThat(tx.statements).containsExactly("CREATE (n:Person {name: $name}) RETURN n");
    assertThat(tx.parameters.getFirst()).isEqualTo(Map.of("name", "Ada"));
    assertThat(tx.committed).isTrue();
    assertThat(server.events.stream().filter("commit"::equals)).hasSize(1);
    assertThat(tx.metadata).containsEntry("purpose", "change");
    JsonNode s = r.structuredContent();
    assertThat(s.path("committed").asBoolean()).isTrue();
    assertThat(s.path("queryType").asString()).isEqualTo("rw");
    assertThat(s.path("counters").toString()).isEqualTo("{\"nodesCreated\":1,\"propertiesSet\":1,\"labelsAdded\":1}");
    assertThat(s.at("/rows/0/0/properties/name").asString()).isEqualTo("Ada");
    assertThat(r.content().getFirst().text()).isEqualTo("Committed: 1 node created, 1 property set, 1 label added.");
    assertThatThrownBy(() -> neo4j.execute(c, config(Env.DEV), secrets, TestCtx.of().approved(approved(
        "MATCH (n) RETURN n", Map.of())))).hasMessageContaining("one Cypher write");
  }

  @Test
  void aLostCommitIsAnUnknownOutcomeAndAFailureBeforeItChangesNothing() {
    ToolCall c = call("neo4j_execute", cypher("MATCH (n:Person) SET n.x = 1"));
    JsonNode payload = approved("MATCH (n:Person) SET n.x = 1", Map.of());
    server.on("MATCH (n:Person) SET", Answer.rows(List.of(), List.of()).withSummary(FakeBolt.counters("w",
        Map.of("propertiesSet", 3))).failingCommit(new ServiceUnavailableException("Connection to the database "
        + "terminated")));
    assertThatThrownBy(() -> neo4j.execute(c, config(Env.DEV), secrets, TestCtx.of().approved(payload)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
          assertThat(e.getMessage()).contains("whether Neo4j applied the change is not known");
        });
    FakeBolt other = new FakeBolt();
    Neo4jConnector second = new Neo4jConnector(other);
    other.on("MATCH (n:Person) SET", Answer.rows(List.of(), List.of()).failingCommit(new TransientException(
        "Neo.TransientError.Transaction.DeadlockDetected", "ForsetiClient can't acquire ExclusiveLock")));
    assertThatThrownBy(() -> second.execute(c, config(Env.DEV), secrets, TestCtx.of().approved(payload)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).startsWith("Neo4j refused the commit, so nothing changed");
        });
    FakeBolt third = new FakeBolt();
    Neo4jConnector before = new Neo4jConnector(third);
    third.on("MATCH (n:Person) SET", Answer.fails(new SessionExpiredException("Server at graph.example.com is no "
        + "longer available")));
    assertThatThrownBy(() -> before.execute(c, config(Env.DEV), secrets, TestCtx.of().approved(payload)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).endsWith("Nothing changed.");
        });
    assertThat(third.last().committed).isFalse();
    FakeBolt fourth = new FakeBolt();
    fourth.on("MATCH (n:Person) SET", Answer.fails(new ClientException(
        "Neo.ClientError.Schema.ConstraintValidationFailed", "Node(1) already exists with label `Person` and "
        + "property `id` = 7")));
    assertThatThrownBy(() -> new Neo4jConnector(fourth).execute(c, config(Env.DEV), secrets,
        TestCtx.of().approved(payload))).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).startsWith("Neo4j failed: Node(1) already exists").endsWith("Nothing changed.");
        });
    assertThat(fourth.events).doesNotContain("commit");
  }

  // ── Test connection, health ─────────────────────────────────────────────────────────────────────────────────

  private void enterprise(List<String> roles) {
    server.on("RETURN 1 AS ok", Answer.rows(List.of("ok"), List.of(List.of(Values.value(1))))
        .withSummary(FakeBolt.summary("r")));
    server.on("CALL dbms.components()", Answer.rows(List.of("name", "versions", "edition"), List.of(List.of(
        Values.value("Neo4j Kernel"), Values.value(List.of("5.26.31")), Values.value("enterprise")))));
    server.on("SHOW DATABASE", Answer.rows(List.of("name", "currentStatus", "requestedStatus", "access"),
        List.of(List.of(Values.value("movies"), Values.value("online"), Values.value("online"),
            Values.value("read-write")))));
    server.on("SHOW CURRENT USER", Answer.rows(List.of("user", "roles", "passwordChangeRequired"), List.of(List.of(
        Values.value("gw_reader"), Values.value(roles), Values.value(false)))));
  }

  @Test
  void testConnectionReportsTheServerTheDatabaseAndTheUsersRoles() {
    enterprise(List.of("reader", "PUBLIC"));
    TestResult t = neo4j.test(config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("Neo4j 5.26.31 enterprise");
    assertThat(t.identity()).isEqualTo("gw_reader");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("connect", "database", "user");
    assertThat(t.checks().get(1).detail()).isEqualTo("movies online, read-write");
    assertThat(t.hints()).extracting(TestResult.Hint::code).doesNotContain("neo4j.admin", "neo4j.can-write");
    assertThat(server.fresh).isEqualTo(1);
    assertThat(server.released).isEqualTo(1);
    assertThat(server.leases).isZero();
    assertThat(server.transactions.get(2).database).isEqualTo("system");
    assertThat(server.transactions).allSatisfy(tx -> assertThat(tx.mode).isEqualTo(AccessMode.READ));
  }

  @Test
  void anAdminOutsideDevAndCommunityEditionAreWarnedAbout() {
    enterprise(List.of("admin", "PUBLIC"));
    TestResult admin = neo4j.test(config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(admin.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(admin.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("neo4j.admin");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
    });
    TestResult dev = neo4j.test(config(Env.DEV), secrets, TestCtx.of());
    assertThat(dev.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("neo4j.admin");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.INFO);
    });
    FakeBolt editor = new FakeBolt();
    Neo4jConnector withEditor = new Neo4jConnector(editor);
    editor.on("CALL dbms.components()", Answer.rows(List.of("name", "versions", "edition"), List.of(List.of(
        Values.value("Neo4j Kernel"), Values.value(List.of("5.26.31")), Values.value("enterprise")))));
    editor.on("SHOW CURRENT USER", Answer.rows(List.of("user", "roles", "passwordChangeRequired"), List.of(List.of(
        Values.value("gw_writer"), Values.value(List.of("editor")), Values.value(false)))));
    TestResult writer = withEditor.test(config(Env.STAGE), secrets, TestCtx.of().env(Env.STAGE));
    assertThat(writer.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(writer.hints()).extracting(TestResult.Hint::code).contains("neo4j.can-write");
    FakeBolt community = new FakeBolt();
    community.on("CALL dbms.components()", Answer.rows(List.of("name", "versions", "edition"), List.of(List.of(
        Values.value("Neo4j Kernel"), Values.value(List.of("5.26.31")), Values.value("community")))));
    community.on("SHOW CURRENT USER", Answer.rows(List.of("user", "roles", "passwordChangeRequired"), List.of(List.of(
        Values.value("neo4j"), Values.NULL, Values.value(true)))));
    TestResult c = new Neo4jConnector(community).test(config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(c.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(c.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("neo4j.no-roles");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
    });
    assertThat(c.hints()).extracting(TestResult.Hint::code).contains("neo4j.password-change");
    // Memgraph answers Bolt as "Neo4j/v5.11.0 compatible graph database server - Memgraph" and plans everything rw.
    FakeBolt memgraph = new FakeBolt();
    memgraph.on("RETURN 1 AS ok", Answer.rows(List.of("ok"), List.of(List.of(Values.value(1)))).withSummary(
        new Bolt.Summary("rw", Map.of(), null, List.of(), "Neo4j/v5.11.0 compatible graph database server - Memgraph",
            "5.2")));
    TestResult mg = new Neo4jConnector(memgraph).test(config(Env.DEV), secrets, TestCtx.of());
    assertThat(mg.ok()).isTrue();
    assertThat(mg.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("neo4j.memgraph");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
    });
  }

  @Test
  void testConnectionSaysWhatFailedWithoutTheSecret() {
    server.beginFailure = new AuthenticationException("Neo.ClientError.Security.Unauthorized", "The client is "
        + "unauthorized due to authentication failure.");
    TestResult t = neo4j.test(config(Env.DEV), secrets, TestCtx.of());
    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("neo4j.auth");
    assertThat(t.error().message()).isEqualTo("Neo4j refused the sign-in of gw_reader: check the user and password "
        + "under Authentication.").doesNotContain(PASSWORD);
    TestResult noSecret = neo4j.test(config(Env.DEV), new TestSecrets(), TestCtx.of());
    assertThat(noSecret.error().code()).isEqualTo("neo4j.credentials");
    TestResult badUri = neo4j.test(config(Env.DEV, "neo4j+ssc://graph.example.com"), secrets, TestCtx.of());
    assertThat(badUri.error().code()).isEqualTo("neo4j.settings");
    server.beginFailure = new ServiceUnavailableException("Unable to connect to graph.example.com:7687");
    assertThat(neo4j.test(config(Env.DEV), secrets, TestCtx.of()).error().code()).isEqualTo("neo4j.connect");
    assertThat(neo4j.health(config(Env.DEV), secrets, TestCtx.of()).state())
        .isEqualTo(HealthResult.State.UNREACHABLE);
    server.beginFailure = null;
    assertThat(neo4j.health(config(Env.DEV), secrets, TestCtx.of()).state()).isEqualTo(HealthResult.State.HEALTHY);
  }

  @Test
  void aTokenSignsInAsItsOwnUserAndChangesDropTheDriver() {
    ObjectNode c = (ObjectNode) config(Env.DEV).config().deepCopy();
    c.putObject("authentication").put("method", "token");
    ConnectionConfig sso = new ConnectionConfig("conn-sso", "sso", "neo4j", Env.DEV, 1, c,
        Set.of(Neo4jSettings.TOKEN), null);
    server.on("SHOW CURRENT USER", Answer.rows(List.of("user", "roles", "passwordChangeRequired"), List.of(List.of(
        Values.value("ada@example.com"), Values.value(List.of("reader")), Values.value(false)))));
    TestCtx ctx = TestCtx.of();
    TestResult t = neo4j.test(sso, new TestSecrets().with(Neo4jSettings.TOKEN, "  eyJ.token.sig-0001\n"), ctx);
    assertThat(t.ok()).isTrue();
    assertThat(t.identity()).isEqualTo("ada@example.com");
    assertThat(ctx.taints).contains("eyJ.token.sig-0001");
    assertThat(server.events).contains("fresh TOKEN null");
    neo4j.onConnectionChanged("conn-sso");
    neo4j.onConnectionRemoved("conn-sso");
    assertThat(server.events).contains("evict conn-sso");
    neo4j.close();
    assertThat(server.closed).isTrue();
  }

  @Test
  void driverFailuresMapToWhatTheyMean() {
    Neo4jSettings s;
    try {
      s = Neo4jSettings.of(config(Env.DEV));
    } catch (ConnectorException e) {
      throw new AssertionError(e);
    }
    assertThat(Neo4jErrors.read(new ServiceUnavailableException("x", new java.net.ConnectException("refused")), s,
        Duration.ofSeconds(3)).getMessage()).startsWith("Nothing answers at graph.example.com:7687");
    assertThat(Neo4jErrors.read(new ServiceUnavailableException("x", new javax.net.ssl.SSLHandshakeException(
        "PKIX path building failed")), s, null).getMessage()).startsWith("TLS with graph.example.com:7687 failed: "
        + "PKIX path building failed. If the server's certificate comes from a private CA");
    assertThat(Neo4jErrors.read(new ServiceUnavailableException("x", new java.net.UnknownHostException("graph")), s,
        null).kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
    assertThat(Neo4jErrors.read(new ClientException("Neo.ClientError.Database.DatabaseNotFound", "Graph not found: "
        + "movies"), s, null).getMessage()).isEqualTo("Neo4j has no database movies: check the connection's "
        + "database.");
    assertThat(Neo4jErrors.read(new ClientException("Neo.ClientError.Statement.SyntaxError", "Invalid input 'x'\n"
        + "\"MATCH x\"\n       ^"), s, null).getMessage()).isEqualTo("Neo4j refused the statement: Invalid input 'x'.");
    assertThat(Neo4jErrors.beforeCommit(new ClientException("Neo.ClientError.Schema.ConstraintValidationFailed",
        "Node(1) already exists"), s, null).getMessage()).endsWith("Nothing changed.");
    assertThat(Neo4jErrors.commit(new RuntimeException("Thread interrupted while committing the transaction"), s)
        .kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
    assertThat(Neo4jErrors.said(new ClientException("evil \u001b[31m red\u202e"))).isEqualTo("evil \\e[31m red"
        + "\\u202E");
  }
}
