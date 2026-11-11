package ai.msrv.gaterook.connectors.redshift;

import static ai.msrv.gaterook.connectors.redshift.FakeRedshift.batch;
import static ai.msrv.gaterook.connectors.redshift.FakeRedshift.body;
import static ai.msrv.gaterook.connectors.redshift.FakeRedshift.json;
import static ai.msrv.gaterook.connectors.redshift.FakeRedshift.page;
import static ai.msrv.gaterook.connectors.redshift.FakeRedshift.policyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Redshift connector against a fake of the Redshift Data API and STS: what is sent, signed and shown. */
class RedshiftConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-redshift";
  private static final String ID = "d9b6c0c9-0747-4bf4-b142-e8883122f766";
  private static final String COLUMNS = "[{\"name\":\"id\",\"label\":\"id\",\"typeName\":\"int8\",\"schemaName\":"
      + "\"sales\",\"tableName\":\"orders\"},{\"name\":\"amount\",\"label\":\"amount\",\"typeName\":\"numeric\"},"
      + "{\"name\":\"paid\",\"label\":\"paid\",\"typeName\":\"bool\"},{\"name\":\"at\",\"label\":\"at\",\"typeName\":"
      + "\"timestamptz\"},{\"name\":\"day\",\"label\":\"day\",\"typeName\":\"timestamp\"},{\"name\":\"raw\",\"label\":"
      + "\"raw\",\"typeName\":\"varbyte\"},{\"name\":\"meta\",\"label\":\"meta\",\"typeName\":\"super\"},{\"name\":"
      + "\"score\",\"label\":\"score\",\"typeName\":\"float8\"},{\"name\":\"note\",\"label\":\"note\",\"typeName\":"
      + "\"varchar\"}]";
  private static final String RECORD = "[{\"longValue\":9007199254740993},{\"stringValue\":\"12.50\"},"
      + "{\"booleanValue\":true},{\"stringValue\":\"2023-11-14 22:13:20.123456+00\"},{\"stringValue\":"
      + "\"2023-11-14 22:13:20\"},{\"blobValue\":\"AP8Q\"},"
      + "{\"stringValue\":\"{\\\"tags\\\":[\\\"a\\\"],\\\"n\\\":1}\"},{\"doubleValue\":0.5},{\"isNull\":true}]";

  private FakeRedshift aws;
  private RedshiftConnector redshift;
  private final TestSecrets secrets = new TestSecrets().with("/credentials/secretAccessKey", "test-only-secret-key");

  @BeforeEach
  void start() throws Exception {
    aws = new FakeRedshift();
    redshift = new RedshiftConnector(aws.cloud);
  }

  @AfterEach
  void stop() {
    redshift.close();
    aws.close();
  }

  private static ConnectionConfig config(Env env) {
    return config(env, c -> {});
  }

  private static ConnectionConfig config(Env env, Consumer<ObjectNode> change) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "eu-central-1").put("database", "dev");
    c.putObject("target").put("kind", "cluster").put("cluster", "acme-analytics");
    c.putObject("credentials").put("source", "accessKey").put("accessKeyId", "AKIAIOSFODNN7EXAMPLE");
    change.accept(c);
    return new ConnectionConfig("conn-rs-" + env.id(), env.id() + "-rs", "redshift", env, 1, c,
        Set.of("/credentials/secretAccessKey"), null);
  }

  private static Consumer<ObjectNode> role() {
    return c -> ((ObjectNode) c.get("credentials")).put("roleArn", ROLE).put("externalId", "gw-ext-0123456789ab");
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".redshift", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-rs", (ObjectNode) JSON.readTree(args),
        null);
  }

  private static String sql(String sql) {
    return JSON.writeValueAsString(Map.of("sql", sql));
  }

  private Classification classify(String tool, String args) {
    return redshift.classify(call(tool, args), config(Env.DEV), policy(Env.DEV));
  }

  /** A batch that finishes at once, its agent statement with {@code rows} rows, and one page of {@code records}. */
  private void readFinishes(long rows, String records) {
    aws.on("BatchExecuteStatement", "{\"Id\":\"" + ID + "\",\"Status\":\"SUBMITTED\"}")
        .on("DescribeStatement", batch(ID, rows, "FINISHED", "FINISHED", "FINISHED", "FINISHED"))
        .on("GetStatementResult", page(COLUMNS, records, null, rows));
  }

  // ── Tools and routes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesReadsAndOneApprovedChange() {
    assertThat(redshift.manifest().id()).isEqualTo("redshift");
    assertThat(redshift.manifest().name()).isEqualTo("Amazon Redshift");
    assertThat(redshift.manifest().group()).isEqualTo("Databases");
    assertThat(redshift.manifest().icon()).isEqualTo("database");
    assertThat(redshift.manifest().tools()).extracting(ToolDefinition::name).containsExactly("redshift_list_schemas",
        "redshift_list_tables", "redshift_describe_table", "redshift_query", "redshift_execute");
    ToolDefinition execute = redshift.manifest().tools().getLast();
    assertThat(execute.mutating()).isTrue();
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(execute.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.inputSchema().at("/properties/note/maxLength").asInt()).isEqualTo(500);
    assertThat(redshift.manifest().tools().subList(0, 4)).allSatisfy(t -> {
      assertThat(t.nature()).isEqualTo(ToolDefinition.Nature.READ);
      assertThat(t.defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
    });
  }

  @Test
  void statementsAreRoutedByWhatTheyDo() {
    assertThat(classify("redshift_query", sql("SELECT id FROM sales.orders WHERE note = 'DELETE; DROP'")).kind())
        .isEqualTo(Classification.Kind.READ);
    Classification writes = classify("redshift_query", sql("DELETE FROM sales.orders WHERE true"));
    assertThat(writes.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(writes.ruleId()).isEqualTo("dev.redshift.query-writes");
    Classification change = classify("redshift_execute", sql("UPDATE sales.orders SET paid = true WHERE id = 1"));
    assertThat(change.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(change.facts().path("target").asString()).isEqualTo("sales.orders");
    assertThat(change.facts().path("statement").asString()).isEqualTo("UPDATE");
    assertThat(classify("redshift_execute", sql("SELECT 1")).ruleId()).isEqualTo("dev.redshift.execute-read");
    assertThat(classify("redshift_query", sql("UNLOAD ('SELECT 1') TO 's3://acme/x' IAM_ROLE default")).ruleId())
        .isEqualTo("dev.redshift.forbidden");
    assertThat(classify("redshift_execute", sql("GRANT ALL ON sales.orders TO mallory")).ruleId())
        .isEqualTo("dev.redshift.forbidden");
    assertThat(classify("redshift_query", sql("SELECT 'open")).ruleId()).isEqualTo("dev.redshift.unparsed");
    assertThat(classify("redshift_describe_table", "{\"table\": \"sales.orders\"}").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("redshift_describe_table", "{\"table\": \"a.b.c\"}").kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("redshift_list_tables", "{\"schema\": \"bad\\\"quote\"}").kind())
        .isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void parametersAreNamedTextValuesTheStatementUses() {
    String query = "SELECT * FROM sales.orders WHERE id = :id AND day >= :since";
    assertThat(classify("redshift_query", "{\"sql\": \"" + query + "\", \"parameters\": [{\"name\": \"id\", "
        + "\"value\": 42}, {\"name\": \"since\", \"value\": \"2026-01-01\"}]}").kind())
        .isEqualTo(Classification.Kind.READ);
    for (String p : List.of("[{\"name\": \"id\", \"value\": 42}]",
        "[{\"name\": \"id\", \"value\": 42}, {\"name\": \"since\", \"value\": null}]",
        "[{\"name\": \"id\", \"value\": 42}, {\"name\": \"since\", \"value\": \"\"}]",
        "[{\"name\": \"id\", \"value\": 42}, {\"name\": \"id\", \"value\": 1}, {\"name\": \"since\", \"value\": 1}]",
        "[{\"name\": \"id\", \"value\": 42}, {\"name\": \"since\", \"value\": 1}, {\"name\": \"other\", \"value\": 1}]",
        "[{\"name\": \"id-x\", \"value\": 42}, {\"name\": \"since\", \"value\": 1}]")) {
      Classification c = classify("redshift_query", "{\"sql\": \"" + query + "\", \"parameters\": " + p + "}");
      assertThat(c.kind()).as(p).isEqualTo(Classification.Kind.DENY);
      assertThat(c.ruleId()).as(p).isEqualTo("dev.redshift.bad-argument");
    }
    // A cast is no parameter.
    assertThat(classify("redshift_query", sql("SELECT amount::int FROM sales.orders")).kind())
        .isEqualTo(Classification.Kind.READ);
  }

  // ── What is sent and what comes back ───────────────────────────────────────────────────────────────────────

  @Test
  void aReadRunsInAReadOnlyTransactionThatIsRolledBack() throws Exception {
    readFinishes(1, "[" + RECORD + "]");
    TestCtx ctx = TestCtx.of();
    ToolResult r = redshift.execute(call("redshift_query", "{\"sql\": \"SELECT * FROM sales.orders WHERE id = :id\", "
        + "\"parameters\": [{\"name\": \"id\", \"value\": 7}]}"), config(Env.DEV), secrets, ctx);

    RecordedRequest sent = aws.to("BatchExecuteStatement").getFirst();
    assertThat(sent.getHeaders().get("Content-Type")).isEqualTo("application/x-amz-json-1.1");
    assertThat(sent.getHeaders().get("X-Amz-Target")).isEqualTo("RedshiftData.BatchExecuteStatement");
    assertThat(sent.getHeaders().get("Authorization")).contains("AWS4-HMAC-SHA256",
        "Credential=AKIAIOSFODNN7EXAMPLE/", "/eu-central-1/redshift-data/aws4_request", "x-amz-target");
    JsonNode batch = body(sent);
    assertThat(batch.path("Sqls").toString()).isEqualTo("[\"SET statement_timeout TO 5000\",\"BEGIN READ ONLY\","
        + "\"SELECT * FROM sales.orders WHERE id = :id\",\"ROLLBACK\"]");
    assertThat(batch.path("ExecutionMode").asString()).isEqualTo("AUTO_COMMIT");
    assertThat(batch.path("ClusterIdentifier").asString()).isEqualTo("acme-analytics");
    assertThat(batch.path("Database").asString()).isEqualTo("dev");
    assertThat(batch.has("DbUser")).isFalse();
    assertThat(batch.has("SecretArn")).isFalse();
    assertThat(batch.path("Parameters").toString()).isEqualTo("[{\"name\":\"id\",\"value\":\"7\"}]");
    assertThat(batch.path("WaitTimeSeconds").asInt()).isBetween(1, 20);
    assertThat(batch.path("StatementName").asString()).isEqualTo("gaterook tc_rs_test");
    assertThat(body(aws.to("GetStatementResult").getFirst()).path("Id").asString()).isEqualTo(ID + ":3");
    assertThat(ctx.taints).contains("test-only-secret-key");

    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("id", "amount", "paid", "at",
        "day", "raw", "meta", "score", "note");
    assertThat(r.table().columns().getFirst().type()).isEqualTo("int8");
    assertThat(r.table().columns().getFirst().sourceTable()).isEqualTo("orders");
    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).isString()).as("beyond 2^53: text").isTrue();
    assertThat(row.get(0).asString()).isEqualTo("9007199254740993");
    assertThat(row.get(1).asString()).isEqualTo("12.50");
    assertThat(row.get(2).asBoolean()).isTrue();
    assertThat(row.get(3).asString()).isEqualTo("2023-11-14T22:13:20.123456Z");
    assertThat(row.get(4).asString()).isEqualTo("2023-11-14T22:13:20");
    assertThat(row.get(5).asString()).isEqualTo("\\x00ff10");
    assertThat(row.get(6).path("tags").get(0).asString()).isEqualTo("a");
    assertThat(row.get(6).path("n").isIntegralNumber()).isTrue();
    assertThat(row.get(7).asDouble()).isEqualTo(0.5);
    assertThat(row.get(8).isNull()).isTrue();
    assertThat(r.table().nextCursor()).isNull();
    assertThat(r.structuredContent().path("statement").asString()).isEqualTo(ID + ":3");
    assertThat(r.structuredContent().path("resultRows").asLong()).isEqualTo(1);
    assertThat(r.structuredContent().path("resultBytes").asLong()).isEqualTo(40);
    assertThat(r.structuredContent().path("totalRows").asLong()).isEqualTo(1);
  }

  @Test
  void aResultCountsOnlyWhenTheReadOnlyTransactionOpened() {
    aws.on("BatchExecuteStatement", "{\"Id\":\"" + ID + "\"}")
        .on("DescribeStatement", batch(ID, 1, "FINISHED", "FAILED:ERROR: cannot begin", "FINISHED", "FINISHED"));
    assertThatThrownBy(() -> redshift.execute(call("redshift_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of())).isInstanceOf(ConnectorException.class).hasMessageContaining("didn't open the read-only "
        + "transaction");
    assertThat(aws.to("GetStatementResult")).isEmpty();
  }

  @Test
  void redshiftsRefusalsSayWhatHappened() {
    aws.on("BatchExecuteStatement", "{\"Id\":\"" + ID + "\"}");
    aws.on("DescribeStatement", batch(ID, 0, "FINISHED", "FINISHED", "FAILED:ERROR: relation \"sales.nope\" does "
        + "not exist", "FINISHED"));
    assertThatThrownBy(() -> redshift.execute(call("redshift_query", sql("SELECT * FROM sales.nope")),
        config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("relation \"sales.nope\" does not exist");
        });
    aws.on("DescribeStatement", batch(ID, 0, "FINISHED", "FINISHED", "FAILED:ERROR: cannot execute INSERT in a "
        + "read-only transaction", "FINISHED"));
    assertThatThrownBy(() -> redshift.execute(call("redshift_query", sql("SELECT write_something()")),
        config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    aws.on("DescribeStatement", batch(ID, 0, "FINISHED", "FINISHED", "FAILED:ERROR: Query (4711) cancelled on "
        + "user's request due to statement timeout", "FINISHED"));
    assertThatThrownBy(() -> redshift.execute(call("redshift_query", sql("SELECT * FROM sales.big")),
        config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).contains("statement timeout of 5 s");
        });
  }

  @Test
  void pagesGoOnFromTheRowAndPageWhereTheyStopped() throws Exception {
    String columns = "[{\"name\":\"id\",\"label\":\"id\",\"typeName\":\"int4\"}]";
    aws.on("BatchExecuteStatement", "{\"Id\":\"" + ID + "\"}")
        .on("DescribeStatement", batch(ID, 5, "FINISHED", "FINISHED", "FINISHED", "FINISHED"))
        .on("GetStatementResult", r -> body(r).has("NextToken")
            ? json(200, page(columns, "[[{\"longValue\":4}],[{\"longValue\":5}]]", null, 5))
            : json(200, page(columns, "[[{\"longValue\":1}],[{\"longValue\":2}],[{\"longValue\":3}]]", "t2", 5)));
    String args = "{\"sql\": \"SELECT id FROM sales.orders\", \"limit\": 2}";
    ToolResult first = redshift.execute(call("redshift_query", args), config(Env.DEV), secrets, TestCtx.of());
    assertThat(first.table().rows().toString()).isEqualTo("[[1],[2]]");
    assertThat(first.table().nextCursor()).startsWith("rs1.");

    ToolResult second = redshift.execute(call("redshift_query", "{\"sql\": \"SELECT id FROM sales.orders\", "
        + "\"limit\": 2, \"cursor\": \"" + first.table().nextCursor() + "\"}"), config(Env.DEV), secrets,
        TestCtx.of());
    // Row 3 is the rest of the first result page, row 4 the start of the next one.
    assertThat(second.table().rows().toString()).isEqualTo("[[3],[4]]");
    List<RecordedRequest> pages = aws.to("GetStatementResult");
    assertThat(body(pages.get(1)).has("NextToken")).isFalse();
    assertThat(body(pages.get(2)).path("NextToken").asString()).isEqualTo("t2");

    ToolResult third = redshift.execute(call("redshift_query", "{\"sql\": \"SELECT id FROM sales.orders\", "
        + "\"limit\": 2, \"cursor\": \"" + second.table().nextCursor() + "\"}"), config(Env.DEV), secrets,
        TestCtx.of());
    assertThat(third.table().rows().toString()).isEqualTo("[[5]]");
    assertThat(third.table().nextCursor()).isNull();
    // One batch: the next pages read the same statement's result.
    assertThat(aws.to("BatchExecuteStatement")).hasSize(1);

    // A cursor belongs to its statement and connection.
    assertThatThrownBy(() -> redshift.execute(call("redshift_query", "{\"sql\": \"SELECT 2\", \"cursor\": \""
        + first.table().nextCursor() + "\"}"), config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class);
    assertThatThrownBy(() -> redshift.execute(call("redshift_query", "{\"sql\": \"SELECT id FROM sales.orders\", "
        + "\"limit\": 2, \"cursor\": \"rs1.forged\"}"), config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class);
  }

  @Test
  void aPageCutAtTheResponseSizeGoesOnWhereItStopped() throws Exception {
    String columns = "[{\"name\":\"note\",\"label\":\"note\",\"typeName\":\"varchar\"}]";
    StringBuilder records = new StringBuilder("[");
    for (int i = 0; i < 40; i++) {
      records.append(i == 0 ? "" : ",").append("[{\"stringValue\":\"").append(String.valueOf((char) ('a' + i % 26))
          .repeat(1_000)).append("\"}]");
    }
    records.append(']');
    aws.on("BatchExecuteStatement", "{\"Id\":\"" + ID + "\"}")
        .on("DescribeStatement", batch(ID, 40, "FINISHED", "FINISHED", "FINISHED", "FINISHED"))
        .on("GetStatementResult", page(columns, records.toString(), null, 40));
    TestCtx small = TestCtx.of().with(new ai.msrv.gaterook.connector.api.Limits(1_000, 1_000, 16 * 1024, 64 * 1024,
        256 * 1024, 64 * 1024, 8 * 1024, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10),
        null));
    ToolResult r = redshift.execute(call("redshift_query", sql("SELECT note FROM sales.notes")), config(Env.DEV),
        secrets, small);
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
    int shown = r.table().rows().size();
    assertThat(shown).isBetween(1, 39);
    ToolResult rest = redshift.execute(call("redshift_query", "{\"sql\": \"SELECT note FROM sales.notes\", "
        + "\"cursor\": \"" + r.table().nextCursor() + "\"}"), config(Env.DEV), secrets, small.next());
    assertThat(rest.table().rows().get(0).get(0).asString()).startsWith(String.valueOf((char) ('a' + shown % 26)));
  }

  @Test
  void aQueryThatOutlivesTheCallIsCancelled() {
    AtomicInteger cancels = new AtomicInteger();
    aws.on("BatchExecuteStatement", "{\"Id\":\"" + ID + "\"}")
        .on("DescribeStatement", "{\"Id\":\"" + ID + "\",\"Status\":\"STARTED\"}")
        .on("CancelStatement", r -> {
          cancels.incrementAndGet();
          return json(200, "{\"Status\":true}");
        });
    assertThatThrownBy(() -> redshift.execute(call("redshift_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of().timeout(Duration.ofMillis(2_500))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
          assertThat(e.getMessage()).contains("cancelled statement " + ID);
        });
    assertThat(cancels.get()).isEqualTo(1);
    assertThat(body(aws.to("CancelStatement").getFirst()).path("Id").asString()).isEqualTo(ID);
  }

  @Test
  void aLongPollRefusedForTooManyWaitersFallsBackToShortPolls() throws Exception {
    AtomicInteger describes = new AtomicInteger();
    aws.on("BatchExecuteStatement", "{\"Id\":\"" + ID + "\"}")
        .on("DescribeStatement", r -> describes.incrementAndGet() == 1
            ? json(400, "{\"__type\":\"ActiveWaitingRequestsExceededException\",\"message\":\"Too many waiting\"}")
            : json(200, batch(ID, 0, "FINISHED", "FINISHED", "FINISHED", "FINISHED")))
        .on("GetStatementResult", page("[{\"name\":\"x\",\"typeName\":\"int4\"}]", "[]", null, 0));
    redshift.execute(call("redshift_query", sql("SELECT 1 AS x")), config(Env.DEV), secrets, TestCtx.of());
    List<RecordedRequest> polls = aws.to("DescribeStatement");
    assertThat(body(polls.get(0)).has("WaitTimeSeconds")).isTrue();
    assertThat(body(polls.get(1)).has("WaitTimeSeconds")).isFalse();
  }

  @Test
  void withARoleEachCallAssumesItUnderTheSessionPolicyOfWhatItDoes() throws Exception {
    readFinishes(0, "[]");
    aws.on("ExecuteStatement", "{\"Id\":\"" + ID + "\"}");
    ConnectionConfig cfg = config(Env.DEV, role());
    redshift.execute(call("redshift_query", sql("SELECT 1")), cfg, secrets, TestCtx.of());
    String read = policyOf(aws.to("AssumeRole").getLast());
    assertThat(read).contains("redshift-data:BatchExecuteStatement", "redshift-data:DescribeTable",
        "arn:aws:redshift:eu-central-1:*:cluster:acme-analytics", "redshift-data:statement-owner-iam-userid",
        "${aws:userid}", "redshift:GetClusterCredentialsWithIAM", "arn:aws:redshift:eu-central-1:*:dbname:"
            + "acme-analytics/dev").doesNotContain("redshift-data:ExecuteStatement\"", "secretsmanager");
    JsonNode sigs = JSON.readTree(read).path("Statement");
    assertThat(sigs).hasSize(3);
    // The session's own credentials sign the Data API's requests, and are tainted.
    assertThat(aws.to("BatchExecuteStatement").getLast().getHeaders().get("Authorization"))
        .contains("Credential=ASIATESTSESSION00001/");
    assertThat(aws.to("BatchExecuteStatement").getLast().getHeaders().get("X-Amz-Security-Token"))
        .isEqualTo(FakeRedshift.SESSION_TOKEN);

    aws.on("DescribeStatement", "{\"Id\":\"" + ID + "\",\"Status\":\"FINISHED\",\"ResultRows\":1}");
    ToolCall change = call("redshift_execute", sql("DELETE FROM sales.orders WHERE id = 1"));
    TestCtx approved = TestCtx.of().approved(redshift.describeAction(change, cfg).payload());
    redshift.execute(change, cfg, secrets, approved);
    String write = policyOf(aws.to("AssumeRole").getLast());
    assertThat(write).contains("redshift-data:ExecuteStatement", "cluster:acme-analytics")
        .doesNotContain("BatchExecuteStatement", "ListTables");
    assertThat(approved.taints).contains(FakeRedshift.SESSION_SECRET, FakeRedshift.SESSION_TOKEN);
  }

  @Test
  void theSessionPolicyNamesTheOneWayOfSigningIn() throws Exception {
    RedshiftSettings dbUser = RedshiftSettings.of(config(Env.DEV, c -> c.putObject("databaseAuth")
        .put("method", "dbUser").put("dbUser", "gw_reader")));
    assertThat(dbUser.readPolicy()).contains("redshift:GetClusterCredentials\"",
        "arn:aws:redshift:eu-central-1:*:dbuser:acme-analytics/gw_reader",
        "arn:aws:redshift:eu-central-1:*:dbname:acme-analytics/dev");
    assertThat(dbUser.target().path("DbUser").asString()).isEqualTo("gw_reader");

    RedshiftSettings secret = RedshiftSettings.of(config(Env.DEV, c -> c.putObject("databaseAuth")
        .put("method", "secret").put("secretArn", "redshift/gw-reader")));
    assertThat(secret.readPolicy()).contains("secretsmanager:GetSecretValue",
        "arn:aws:secretsmanager:eu-central-1:*:secret:redshift/gw-reader-??????").doesNotContain("GetClusterCred");
    String arn = "arn:aws:secretsmanager:eu-central-1:412398765012:secret:redshift/gw-reader-AbCdEf";
    assertThat(RedshiftSettings.of(config(Env.DEV, c -> c.putObject("databaseAuth").put("method", "secret")
        .put("secretArn", arn))).readPolicy()).contains("\"" + arn + "\"");

    String workgroupArn = "arn:aws:redshift-serverless:eu-central-1:412398765012:workgroup/"
        + "0f2b4c6d-8e9f-4a1b-9c3d-5e7f9a1b3c5d";
    RedshiftSettings serverless = RedshiftSettings.of(config(Env.DEV, c -> c.putObject("target")
        .put("kind", "serverless").put("workgroup", workgroupArn)));
    assertThat(serverless.readPolicy()).contains("redshift-serverless:GetCredentials", "\"" + workgroupArn + "\"");
    assertThat(serverless.target().path("WorkgroupName").asString()).isEqualTo(workgroupArn);
    assertThat(serverless.target().has("ClusterIdentifier")).isFalse();
    RedshiftSettings byName = RedshiftSettings.of(config(Env.DEV, c -> c.putObject("target")
        .put("kind", "serverless").put("workgroup", "acme-wg")));
    assertThat(byName.readPolicy()).contains("arn:aws:redshift-serverless:eu-central-1:*:workgroup/*");
    // China has its own partition and endpoint.
    RedshiftSettings china = RedshiftSettings.of(config(Env.DEV, c -> c.put("region", "cn-north-1")));
    assertThat(china.endpoint()).isEqualTo("https://redshift-data.cn-north-1.amazonaws.com.cn");
    assertThat(china.readPolicy()).contains("arn:aws-cn:redshift:cn-north-1:*:cluster:acme-analytics");
    // A database user of a Serverless workgroup can't sign in with temporary credentials.
    assertThatThrownBy(() -> RedshiftSettings.of(config(Env.DEV, c -> {
      c.putObject("target").put("kind", "serverless").put("workgroup", "acme-wg");
      c.putObject("databaseAuth").put("method", "dbUser").put("dbUser", "gw_reader");
    }))).hasMessageContaining("provisioned clusters only");
  }

  @Test
  void aChangeRunsOnlyApprovedOnceWithAClientTokenAndNeverTwice() throws Exception {
    aws.on("ExecuteStatement", "{\"Id\":\"" + ID + "\",\"Status\":\"SUBMITTED\"}")
        .on("DescribeStatement", "{\"Id\":\"" + ID + "\",\"Status\":\"FINISHED\",\"ResultRows\":3}");
    String text = "UPDATE sales.orders SET paid = true WHERE id IN (1, 2, 3) AND region = :region";
    ToolCall c = call("redshift_execute", "{\"sql\": \"" + text + "\", \"parameters\": [{\"name\": \"region\", "
        + "\"value\": \"eu\"}], \"note\": \"Close the paid orders\"}");
    assertThatThrownBy(() -> redshift.execute(c, config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("Not approved");
        });
    assertThat(aws.requests).isEmpty();

    ActionPreview preview = redshift.describeAction(c, config(Env.DEV));
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(preview.text()).isEqualTo(text);
    assertThat(preview.payload().toString()).isEqualTo("{\"sql\":\"" + text + "\",\"parameters\":[{\"name\":"
        + "\"region\",\"value\":\"eu\"}]}");
    assertThat(preview.warnings()).anySatisfy(w -> assertThat(w).contains("doesn't limit this change"));
    TestCtx approved = TestCtx.of().approved(preview.payload());
    ToolResult r = redshift.execute(c, config(Env.DEV), secrets, approved);
    assertThat(r.structuredContent().path("rowsAffected").asInt()).isEqualTo(3);
    assertThat(r.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(r.content().getFirst().text()).isEqualTo("3 rows affected by UPDATE.");
    JsonNode sent = body(aws.to("ExecuteStatement").getFirst());
    assertThat(sent.path("Sql").asString()).isEqualTo(text);
    assertThat(sent.path("ClientToken").asString()).hasSize(36).isEqualTo(RedshiftConnector.clientToken(approved));
    assertThat(sent.path("Parameters").toString()).isEqualTo("[{\"name\":\"region\",\"value\":\"eu\"}]");
    assertThat(sent.path("ClusterIdentifier").asString()).isEqualTo("acme-analytics");
    assertThat(aws.to("BatchExecuteStatement")).isEmpty();

    // What runs is the approved text, whatever the call says now.
    aws.requests.clear();
    redshift.execute(call("redshift_execute", sql("DELETE FROM sales.orders WHERE true")), config(Env.DEV), secrets,
        approved);
    assertThat(body(aws.to("ExecuteStatement").getFirst()).path("Sql").asString()).isEqualTo(text);
  }

  @Test
  void aLostOrUnfinishedChangeIsAnUnknownOutcomeAndAFailedOneChangedNothing() {
    String text = "DELETE FROM sales.orders WHERE id = 1";
    ToolCall c = call("redshift_execute", sql(text));
    aws.on("ExecuteStatement", r -> json(500, "{\"__type\":\"InternalServerException\",\"message\":\"boom\"}"));
    assertThatThrownBy(() -> redshift.execute(c, config(Env.DEV), secrets,
        TestCtx.of().approved(redshift.describeAction(c, config(Env.DEV)).payload())))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));

    aws.on("ExecuteStatement", "{\"Id\":\"" + ID + "\"}")
        .on("DescribeStatement", "{\"Id\":\"" + ID + "\",\"Status\":\"STARTED\"}");
    assertThatThrownBy(() -> redshift.execute(c, config(Env.DEV), secrets, TestCtx.of().timeout(
        Duration.ofMillis(2_500)).approved(redshift.describeAction(c, config(Env.DEV)).payload())))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
          assertThat(e.getMessage()).contains("still running the change as statement " + ID);
        });
    assertThat(aws.to("CancelStatement")).as("a change is never cancelled").isEmpty();

    aws.on("DescribeStatement", "{\"Id\":\"" + ID + "\",\"Status\":\"FAILED\",\"Error\":\"ERROR: permission "
        + "denied for relation orders\"}");
    assertThatThrownBy(() -> redshift.execute(c, config(Env.DEV), secrets,
        TestCtx.of().approved(redshift.describeAction(c, config(Env.DEV)).payload())))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("permission denied for relation orders", "changes nothing");
        });
  }

  @Test
  void theApprovalShowsRedshiftsPlanAndNeverAsksForWhatRedshiftRefuses() throws Exception {
    String plan = "[[{\"stringValue\":\"XN Seq Scan on orders  (cost=0.00..12.04 rows=1204 width=6)\"}],"
        + "[{\"stringValue\":\"  Filter: (paid = false)\"}]]";
    aws.on("BatchExecuteStatement", "{\"Id\":\"" + ID + "\"}")
        .on("DescribeStatement", batch(ID, 2, "FINISHED", "FINISHED", "FINISHED", "FINISHED"))
        .on("GetStatementResult", page("[{\"name\":\"QUERY PLAN\",\"typeName\":\"varchar\"}]", plan, null, 2));
    ToolCall c = call("redshift_execute", sql("UPDATE sales.orders SET paid = true WHERE paid = false"));
    PreparedAction prepared = redshift.prepare(c, config(Env.DEV), secrets, TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(body(aws.to("BatchExecuteStatement").getFirst()).path("Sqls").toString()).isEqualTo("[\"SET "
        + "statement_timeout TO 5000\",\"BEGIN\",\"EXPLAIN UPDATE sales.orders SET paid = true WHERE paid = false\","
        + "\"ROLLBACK\"]");
    assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::label).startsWith("Rows (estimate)",
        "Plan", "Target");
    assertThat(prepared.preview().impact().getFirst().value()).startsWith("1,204");
    assertThat(prepared.preview().impact().get(1).value()).contains("Filter: (paid = false)");
    assertThat(prepared.preview().text()).isEqualTo("UPDATE sales.orders SET paid = true WHERE paid = false");

    aws.on("DescribeStatement", batch(ID, 0, "FINISHED", "FINISHED", "FAILED:ERROR: column \"paidd\" does not "
        + "exist", "FINISHED"));
    PreparedAction refused = redshift.prepare(call("redshift_execute", sql("UPDATE sales.orders SET paidd = true")),
        config(Env.DEV), secrets, TestCtx.of());
    assertThat(refused.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(refused.message()).contains("column \"paidd\" does not exist");

    // DDL has no plan: shown as written, without a request.
    aws.requests.clear();
    PreparedAction ddl = redshift.prepare(call("redshift_execute", sql("ALTER TABLE sales.orders ADD COLUMN note "
        + "varchar(64)")), config(Env.DEV), secrets, TestCtx.of());
    assertThat(ddl.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(ddl.preview().impact().getFirst().value()).contains("shown as written");
    assertThat(aws.requests).isEmpty();
  }

  @Test
  void listsAndDescribesByExactNames() throws Exception {
    aws.on("ListSchemas", "{\"Schemas\":[\"public\",\"sales\",\"sales_eu\"],\"NextToken\":\"s2\"}")
        .on("ListTables", "{\"Tables\":[{\"name\":\"orders\",\"schema\":\"sales\",\"type\":\"TABLE\"},{\"name\":"
            + "\"v_paid\",\"schema\":\"sales\",\"type\":\"VIEW\"},{\"name\":\"x\",\"schema\":\"salesXeu\",\"type\":"
            + "\"TABLE\"}]}")
        .on("DescribeTable", "{\"TableName\":\"orders\",\"ColumnList\":[{\"name\":\"id\",\"typeName\":\"int8\","
            + "\"nullable\":0,\"schemaName\":\"sales\",\"tableName\":\"orders\",\"columnDefault\":\"\\\"identity\\\""
            + "(1, 0, '1,1'::text)\"},{\"name\":\"amount\",\"typeName\":\"numeric\",\"precision\":12,\"scale\":2,"
            + "\"nullable\":1,\"schemaName\":\"sales\",\"tableName\":\"orders\"},{\"name\":\"note\",\"typeName\":"
            + "\"varchar\",\"length\":256,\"nullable\":1,\"schemaName\":\"sales\",\"tableName\":\"orders\"},{\"name\":"
            + "\"other\",\"typeName\":\"int4\",\"schemaName\":\"sales\",\"tableName\":\"ordersX\"}]}");
    ToolResult schemas = redshift.execute(call("redshift_list_schemas", "{}"), config(Env.DEV), secrets,
        TestCtx.of());
    assertThat(schemas.structuredContent().path("schemas").toString()).isEqualTo("[\"public\",\"sales\","
        + "\"sales_eu\"]");
    assertThat(schemas.structuredContent().path("nextCursor").asString()).startsWith("rs1.");

    ToolResult tables = redshift.execute(call("redshift_list_tables", "{\"schema\": \"Sales\"}"), config(Env.DEV),
        secrets, TestCtx.of());
    assertThat(body(aws.to("ListTables").getFirst()).path("SchemaPattern").asString()).isEqualTo("sales");
    assertThat(tables.structuredContent().path("tables").toString()).isEqualTo("[{\"table\":\"orders\",\"kind\":"
        + "\"table\"},{\"table\":\"v_paid\",\"kind\":\"view\"}]");

    ToolResult d = redshift.execute(call("redshift_describe_table", "{\"table\": \"sales.orders\"}"),
        config(Env.DEV), secrets, TestCtx.of());
    JsonNode req = body(aws.to("DescribeTable").getFirst());
    assertThat(req.path("Schema").asString()).isEqualTo("sales");
    assertThat(req.path("Table").asString()).isEqualTo("orders");
    assertThat(req.path("ClusterIdentifier").asString()).isEqualTo("acme-analytics");
    assertThat(d.structuredContent().path("columns")).hasSize(3);
    assertThat(d.content().getFirst().text()).isEqualTo("sales.orders: id int8, amount numeric(12,2), note "
        + "varchar(256)");
    assertThat(d.structuredContent().at("/columns/0/nullable").asBoolean()).isFalse();

    // A quoted name is taken as written: Redshift has no "Sales"."Orders" here.
    assertThatThrownBy(() -> redshift.execute(call("redshift_describe_table", "{\"table\": "
        + "\"\\\"Sales\\\".\\\"Orders\\\"\"}"), config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("No table Sales.Orders");
        });
    assertThat(body(aws.to("DescribeTable").getLast()).path("Schema").asString()).isEqualTo("Sales");
    assertThat(RedshiftConnector.table("orders")).containsExactly("public", "orders");
  }

  @Test
  void dataApiErrorsSayWhatToDo() {
    aws.on("ListSchemas", r -> json(500, "{\"__type\":\"DatabaseConnectionException\",\"message\":\"Connection "
        + "refused: cluster is paused\"}"));
    assertThatThrownBy(() -> redshift.execute(call("redshift_list_schemas", "{}"), config(Env.DEV), secrets,
        TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.getMessage()).contains("cluster is paused", "cluster acme-analytics", "the AWS identity");
        });
    aws.on("ListSchemas", r -> json(400, "{\"__type\":\"com.amazon.coral.service#AccessDeniedException\","
        + "\"Message\":\"User is not authorized to perform: redshift-data:ListSchemas\"}"));
    assertThatThrownBy(() -> redshift.execute(call("redshift_list_schemas", "{}"), config(Env.DEV), secrets,
        TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
            .contains("may not do this", "redshift-data:ListSchemas"));
    aws.on("ListSchemas", r -> json(400, "{\"__type\":\"ValidationException\",\"message\":\"Cluster identifier is "
        + "invalid\\u001b[31m\"}"));
    assertThatThrownBy(() -> redshift.execute(call("redshift_list_schemas", "{}"), config(Env.DEV), secrets,
        TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("\\e[31m").doesNotContain("\u001b");
        });
  }

  @Test
  void testConnectionNamesTheIdentityTheDatabaseUserAndASuperuser() {
    aws.on("ListSchemas", "{\"Schemas\":[\"public\",\"sales\"]}")
        .on("BatchExecuteStatement", "{\"Id\":\"" + ID + "\"}")
        .on("DescribeStatement", batch(ID, 1, "FINISHED", "FINISHED", "FINISHED", "FINISHED"))
        .on("GetStatementResult", page("[{\"name\":\"db_user\",\"typeName\":\"name\"},{\"name\":\"superuser\","
            + "\"typeName\":\"bool\"},{\"name\":\"version\",\"typeName\":\"text\"}]", "[[{\"stringValue\":"
            + "\"IAM:reader\"},{\"booleanValue\":true},{\"stringValue\":\"PostgreSQL 8.0.2 on i686-pc-linux-gnu, "
            + "Redshift 1.0.77467\"}]]", null, 1));
    TestResult r = redshift.test(config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo("arn:aws:iam::412398765012:user/reader · database user IAM:reader");
    assertThat(r.serverVersion()).isEqualTo("Amazon Redshift 1.0.77467 · cluster acme-analytics");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("identity", "schemas", "read-only");
    assertThat(r.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("redshift.superuser");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.DANGER);
    });
    assertThat(r.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("redshift.no-role");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
    });
    assertThat(r.metadataUpdate().path("databaseUser").asString()).isEqualTo("IAM:reader");
    assertThat(body(aws.to("BatchExecuteStatement").getFirst()).path("Sqls").get(1).asString())
        .isEqualTo("BEGIN READ ONLY");
  }

  @Test
  void testConnectionPassesOnWhatCloudAuthSaysAboutTheProfile() throws Exception {
    assertThat(RedshiftConnector.hints(Env.PROD, RedshiftSettings.of(config(Env.PROD)), "gw_reader", false, 2,
        aws.cloud.profileWarnings(RedshiftSettings.of(config(Env.PROD)).credentials())))
        .extracting(TestResult.Hint::code).doesNotContain("redshift.profile");
    RedshiftSettings profile = RedshiftSettings.of(config(Env.PROD, c -> c.putObject("credentials")
        .put("source", "profile").put("profile", "acme-analytics")));
    assertThat(RedshiftConnector.hints(Env.PROD, profile, "gw_reader", false, 2,
        List.of("This profile runs a command to get credentials.")))
        .anySatisfy(h -> {
          assertThat(h.code()).isEqualTo("redshift.profile");
          assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
          assertThat(h.field()).isEqualTo("/credentials/profile");
          assertThat(h.text()).isEqualTo("This profile runs a command to get credentials.");
        });
  }

  @Test
  void theHealthCheckRunsNothingOnTheWarehouse() {
    aws.on("ListStatements", "{\"Statements\":[]}");
    assertThat(redshift.health(config(Env.DEV), secrets, TestCtx.of()).state())
        .isEqualTo(ai.msrv.gaterook.connector.api.HealthResult.State.HEALTHY);
    assertThat(aws.requests).extracting(FakeRedshift::operation).containsExactly("ListStatements");
    assertThat(body(aws.to("ListStatements").getFirst()).path("RoleLevel").asBoolean(true)).isFalse();
  }

  @Test
  void anIncompleteConnectionIsRefusedWithoutAnyRequest() {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "nowhere");
    ConnectionConfig broken = new ConnectionConfig("conn-x", "x", "redshift", Env.DEV, 1, c, Set.of(), null);
    assertThat(redshift.classify(call("redshift_list_schemas", "{}"), broken, policy(Env.DEV)).ruleId())
        .isEqualTo("dev.redshift.bad-config");
    assertThat(redshift.test(broken, secrets, TestCtx.of()).error().code()).isEqualTo("redshift.settings");
    assertThat(aws.requests).isEmpty();
  }

  @Test
  void cellsKeepTheirTypes() {
    assertThat(Records.cell(JSON.readTree("{\"longValue\":42}"), "int4", 100).isIntegralNumber()).isTrue();
    assertThat(Records.cell(JSON.readTree("{\"longValue\":-9007199254740991}"), "int8", 100).isIntegralNumber())
        .isTrue();
    assertThat(Records.cell(JSON.readTree("{\"longValue\":-9007199254740993}"), "int8", 100).isString()).isTrue();
    assertThat(Records.cell(JSON.readTree("{\"doubleValue\":\"NaN\"}"), "float8", 100).asString()).isEqualTo("NaN");
    assertThat(Records.cell(JSON.readTree("{\"stringValue\":\"2023-11-14 22:13:20+05:30\"}"), "timestamptz", 100)
        .asString()).isEqualTo("2023-11-14T16:43:20Z");
    assertThat(Records.cell(JSON.readTree("{\"stringValue\":\"not a time\"}"), "timestamp", 100).asString())
        .isEqualTo("not a time");
    assertThat(Records.cell(JSON.readTree("{\"stringValue\":\"" + "x".repeat(20) + "\"}"), "varchar", 10)
        .asString()).startsWith("xxxxxxxxxx…‹20");
    assertThat(Records.cell(JSON.readTree("{\"stringValue\":\"[1, 9007199254740993]\"}"), "super", 100).toString())
        .isEqualTo("[1,\"9007199254740993\"]");
    assertThat(Records.cell(JSON.readTree("{\"blobValue\":\"" + "A".repeat(400) + "\"}"), "varbyte", 100)
        .asString()).endsWith("…‹300 bytes›");
  }
}
