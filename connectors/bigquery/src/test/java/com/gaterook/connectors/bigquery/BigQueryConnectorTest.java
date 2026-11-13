package com.gaterook.connectors.bigquery;

import static com.gaterook.connectors.bigquery.FakeBigQuery.body;
import static com.gaterook.connectors.bigquery.FakeBigQuery.dryRun;
import static com.gaterook.connectors.bigquery.FakeBigQuery.json;
import static com.gaterook.connectors.bigquery.FakeBigQuery.param;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The BigQuery connector against a fake of Google's token server and BigQuery's REST API. */
class BigQueryConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String P = "acme-analytics";
  private static final String SCHEMA = "{\"fields\":[{\"name\":\"id\",\"type\":\"INTEGER\",\"mode\":\"REQUIRED\"},"
      + "{\"name\":\"big\",\"type\":\"INTEGER\"},{\"name\":\"amount\",\"type\":\"NUMERIC\"},{\"name\":\"paid\","
      + "\"type\":\"BOOLEAN\"},{\"name\":\"at\",\"type\":\"TIMESTAMP\"},{\"name\":\"raw\",\"type\":\"BYTES\"},"
      + "{\"name\":\"tags\",\"type\":\"STRING\",\"mode\":\"REPEATED\"},{\"name\":\"buyer\",\"type\":\"RECORD\","
      + "\"fields\":[{\"name\":\"name\",\"type\":\"STRING\"},{\"name\":\"score\",\"type\":\"FLOAT\"}]}]}";
  private static final String ROW = "{\"f\":[{\"v\":\"1\"},{\"v\":\"123456789012345678901\"},{\"v\":\"12.50\"},"
      + "{\"v\":\"true\"},{\"v\":\"1700000000123456\"},{\"v\":\"AP8Q\"},{\"v\":[{\"v\":\"a\"},{\"v\":\"b\"}]},"
      + "{\"v\":{\"f\":[{\"v\":\"Ada\"},{\"v\":\"0.5\"}]}}]}";

  private FakeBigQuery google;
  private BigQueryConnector bigquery;
  private final TestSecrets secrets = new TestSecrets();

  @BeforeEach
  void start() throws Exception {
    google = new FakeBigQuery();
    bigquery = new BigQueryConnector(google.cloud);
  }

  @AfterEach
  void stop() throws Exception {
    bigquery.close();
    google.close();
  }

  private static ConnectionConfig config(Env env, String... datasets) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("project", P).put("location", "EU");
    c.putObject("credentials").put("source", "adc");
    if (datasets.length > 0) {
      var list = c.putArray("datasets");
      for (String d : datasets) {
        list.add(d);
      }
    }
    c.putObject("advanced").put("maxMbBilled", 1024);
    return new ConnectionConfig("conn-bq-" + env.id(), env.id() + "-bq", "bigquery", env, 1, c, Set.of(), null);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".bigquery", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-bq", (ObjectNode) JSON.readTree(args),
        null);
  }

  private static String sql(String sql) {
    return JSON.writeValueAsString(Map.of("sql", sql));
  }

  private Classification classify(String tool, String args, String... datasets) {
    return bigquery.classify(call(tool, args), config(Env.DEV, datasets), policy(Env.DEV));
  }

  // ── Tools and routes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesReadsAndOneApprovedChange() throws Exception {
    assertThat(bigquery.manifest().id()).isEqualTo("bigquery");
    assertThat(bigquery.manifest().name()).isEqualTo("Google BigQuery");
    assertThat(bigquery.manifest().group()).isEqualTo("Databases");
    assertThat(bigquery.manifest().tools()).extracting(ToolDefinition::name).containsExactly("bigquery_list_datasets",
        "bigquery_list_tables", "bigquery_describe_table", "bigquery_query", "bigquery_execute");
    ToolDefinition execute = bigquery.manifest().tools().getLast();
    assertThat(execute.mutating()).isTrue();
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(execute.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(bigquery.manifest().tools().get(3).defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
  }

  @Test
  void statementsAreRoutedByWhatTheyDo() throws Exception {
    assertThat(classify("bigquery_query", sql("SELECT id FROM sales.orders WHERE note = 'DELETE; DROP'")).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("bigquery_query", sql("WITH t AS (SELECT 1 AS x) SELECT x FROM t")).kind())
        .isEqualTo(Classification.Kind.READ);
    Classification writes = classify("bigquery_query", sql("DELETE FROM sales.orders WHERE true"));
    assertThat(writes.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(writes.ruleId()).isEqualTo("dev.bigquery.query-writes");
    Classification change = classify("bigquery_execute", sql("UPDATE sales.orders SET paid = true WHERE id = 1"));
    assertThat(change.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(change.facts().path("target").asString()).isEqualTo("sales.orders");
    assertThat(classify("bigquery_execute", sql("SELECT 1")).ruleId()).isEqualTo("dev.bigquery.execute-read");
    assertThat(classify("bigquery_execute", sql("CREATE TABLE sales.daily AS SELECT 1 AS x")).kind())
        .isEqualTo(Classification.Kind.MUTATION);
  }

  @Test
  void scriptsDataMovementAccessAndOtherServicesAreRefused() throws Exception {
    for (String text : List.of("SELECT 1; SELECT 2", "SELECT 1; DELETE FROM sales.orders WHERE true",
        "EXPORT DATA OPTIONS(uri='gs://b/*.csv', format='CSV') AS SELECT * FROM sales.orders",
        "LOAD DATA INTO sales.orders FROM FILES (uris = ['gs://b/x.csv'])", "CALL sales.refresh()",
        "BEGIN TRANSACTION", "DECLARE x INT64", "GRANT `roles/bigquery.dataViewer` ON SCHEMA sales TO 'user:a@b.c'",
        "CREATE ROW ACCESS POLICY p ON sales.orders GRANT TO ('user:a@b.c') FILTER USING (true)",
        "CREATE CAPACITY `acme-analytics.region-eu.commitment` OPTIONS (slot_count = 100)",
        "ALTER PROJECT `acme-analytics` SET OPTIONS (`region-eu.default_time_zone` = 'UTC')",
        "SELECT * FROM EXTERNAL_QUERY('acme.eu.pg', 'UPDATE users SET admin = true')",
        "SELECT ml.generate_text(MODEL sales.gemini, (SELECT 'hi' AS prompt))",
        "SELECT AI.GENERATE('hello', connection_id => 'eu.vertex')")) {
      Classification c = classify("bigquery_execute", sql(text));
      Classification q = classify("bigquery_query", sql(text));
      assertThat(c.kind()).as(text).isEqualTo(Classification.Kind.DENY);
      assertThat(q.kind()).as(text).isEqualTo(Classification.Kind.DENY);
    }
    // A function name in a string or a comment isn't a call.
    assertThat(classify("bigquery_query", sql("SELECT 'EXTERNAL_QUERY(x)' AS t # AI.GENERATE(")).kind())
        .isEqualTo(Classification.Kind.READ);
  }

  @Test
  void whatCantBeReadWithCertaintyIsRefused() throws Exception {
    for (String text : List.of("SELECT 'open", "SELECT 1 /* open", "SELECT r'a\\'b'", "SELECT `a\\`b` FROM x",
        "SELECT \"\"\"open", "", "   ", "SELECT 'a\nb'", "FROBNICATE sales.orders")) {
      Classification c = classify("bigquery_query", sql(text));
      assertThat(c.kind()).as(text).isEqualTo(Classification.Kind.DENY);
    }
    // Comments hide nothing: a semicolon inside one is not a second statement.
    assertThat(classify("bigquery_query", sql("SELECT 1 -- ; DROP TABLE sales.orders")).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("bigquery_query", sql("SELECT 1 /* ; DELETE */ ;")).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("bigquery_query", sql("SELECT '''a ; b''', b\"x;\" , r'c\\d'")).kind())
        .isEqualTo(Classification.Kind.DENY); // r'c\d': a backslash in a raw string
    assertThat(classify("bigquery_query", sql("SELECT '''a ; b''', b\"x;\"")).kind())
        .isEqualTo(Classification.Kind.READ);
  }

  @Test
  void onlyListedDatasetsPass() throws Exception {
    assertThat(classify("bigquery_describe_table", "{\"table\": \"sales.orders\"}", "sales", "audit_*").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("bigquery_describe_table", "{\"table\": \"hr.salaries\"}", "sales").ruleId())
        .isEqualTo("dev.bigquery.table-not-allowed");
    assertThat(classify("bigquery_list_tables", "{\"dataset\": \"audit_2026\"}", "sales", "audit_*").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("bigquery_list_tables", "{\"dataset\": \"other-project.sales\"}", "sales").kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("bigquery_execute", sql("DELETE FROM hr.salaries WHERE true"), "sales").kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("bigquery_execute", sql("DROP SCHEMA hr CASCADE"), "sales").kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("bigquery_execute", sql("CREATE SCHEMA `acme-analytics.sales`"), "sales").kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(classify("bigquery_execute", sql("CREATE SEARCH INDEX idx ON sales.orders(ALL COLUMNS)"), "sales")
        .kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(classify("bigquery_execute", sql("CREATE SEARCH INDEX idx ON hr.salaries(ALL COLUMNS)"), "sales")
        .kind()).isEqualTo(Classification.Kind.DENY);
    // Without a dataset the gateway can't tell what a change touches: refused while the list limits datasets.
    assertThat(classify("bigquery_execute", sql("DELETE FROM orders WHERE true"), "sales").kind())
        .isEqualTo(Classification.Kind.DENY);
    // A domain-scoped project is read from the right.
    BigQuerySettings s = new BigQuerySettings(P, null, List.of("example.com:legacy.sales"), 1L << 30, null);
    assertThat(s.table("example.com:legacy.sales.orders")).containsExactly("example.com:legacy", "sales", "orders");
    assertThat(s.allows("example.com:legacy", "sales")).isTrue();
    assertThat(s.allows(P, "sales")).isFalse();
  }

  @Test
  void parametersAreNamedScalarValues() throws Exception {
    assertThat(classify("bigquery_query", "{\"sql\": \"SELECT * FROM sales.orders WHERE id = @id\", \"parameters\": "
        + "[{\"name\": \"id\", \"type\": \"INT64\", \"value\": 42}]}").kind()).isEqualTo(Classification.Kind.READ);
    for (String p : List.of("{\"name\": \"1x\", \"type\": \"INT64\", \"value\": 1}",
        "{\"name\": \"id\", \"type\": \"JSON\", \"value\": 1}",
        "{\"name\": \"id\", \"type\": \"STRING\", \"value\": {\"a\": 1}}")) {
      assertThat(classify("bigquery_query", "{\"sql\": \"SELECT @id\", \"parameters\": [" + p + "]}").kind())
          .as(p).isEqualTo(Classification.Kind.DENY);
    }
  }

  // ── What is sent and what comes back ───────────────────────────────────────────────────────────────────────

  @Test
  void aReadIsCheckedByADryRunThenRunsWithTheByteLimit() throws Exception {
    google.on("POST /projects/" + P + "/jobs", dryRun("SELECT", 2048, P + ".sales.orders"))
        .on("POST /projects/" + P + "/queries", "{\"jobReference\":{\"projectId\":\"" + P + "\",\"jobId\":\"job_r1\","
            + "\"location\":\"EU\"},\"jobComplete\":true,\"schema\":" + SCHEMA + ",\"rows\":[" + ROW + "],"
            + "\"totalRows\":\"1\",\"totalBytesProcessed\":\"2048\",\"cacheHit\":false}");
    TestCtx ctx = TestCtx.of();
    ToolResult r = bigquery.execute(call("bigquery_query", "{\"sql\": \"SELECT * FROM sales.orders WHERE id = @id\", "
        + "\"parameters\": [{\"name\": \"id\", \"type\": \"INT64\", \"value\": 1}, {\"name\": \"n\", \"type\": "
        + "\"STRING\", \"value\": null}]}"), config(Env.DEV, "sales"), secrets, ctx);

    JsonNode dry = body(google.to("POST /projects/" + P + "/jobs").getFirst());
    assertThat(dry.at("/configuration/dryRun").asBoolean()).isTrue();
    assertThat(dry.at("/configuration/query/useLegacySql").asBoolean()).isFalse();
    assertThat(dry.at("/configuration/query/parameterMode").asString()).isEqualTo("NAMED");
    assertThat(dry.at("/configuration/query/queryParameters/0/parameterValue/value").asString()).isEqualTo("1");
    assertThat(dry.at("/configuration/query/queryParameters/1/parameterValue").isEmpty()).isTrue();
    assertThat(dry.at("/jobReference/location").asString()).isEqualTo("EU");

    RecordedRequest sent = google.to("POST /projects/" + P + "/queries").getFirst();
    assertThat(sent.getHeaders().get("Authorization")).isEqualTo("Bearer " + FakeBigQuery.USER_TOKEN);
    JsonNode q = body(sent);
    assertThat(q.path("maximumBytesBilled").asString()).isEqualTo(Long.toString(1L << 30));
    assertThat(q.path("useLegacySql").asBoolean()).isFalse();
    assertThat(q.at("/formatOptions/useInt64Timestamp").asBoolean()).isTrue();
    assertThat(q.at("/labels/gaterook").asString()).isEqualTo("read");
    assertThat(q.path("location").asString()).isEqualTo("EU");
    assertThat(q.has("requestId")).isFalse();
    assertThat(ctx.taints).contains(FakeBigQuery.USER_TOKEN);

    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("id", "big", "amount",
        "paid", "at", "raw", "tags", "buyer");
    assertThat(r.table().columns().get(6).type()).isEqualTo("ARRAY<STRING>");
    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(0).isIntegralNumber()).isTrue();
    assertThat(row.get(1).asString()).isEqualTo("123456789012345678901");
    assertThat(row.get(2).asString()).isEqualTo("12.50");
    assertThat(row.get(3).asBoolean()).isTrue();
    assertThat(row.get(4).asString()).isEqualTo("2023-11-14T22:13:20.123456Z");
    assertThat(row.get(5).asString()).isEqualTo("\\x00ff10");
    assertThat(row.get(6).toString()).isEqualTo("[\"a\",\"b\"]");
    assertThat(row.get(7).path("name").asString()).isEqualTo("Ada");
    assertThat(row.get(7).path("score").asDouble()).isEqualTo(0.5);
    assertThat(r.table().nextCursor()).isNull();
    assertThat(r.structuredContent().path("bytesProcessed").asLong()).isEqualTo(2048);
    assertThat(r.structuredContent().path("job").asString()).isEqualTo("job_r1");
  }

  @Test
  void theDryRunDecidesWhatRuns() throws Exception {
    // BigQuery's own word wins over the gateway's reading: a statement it doesn't call a SELECT never runs.
    google.on("POST /projects/" + P + "/jobs", dryRun("SCRIPT", 10));
    assertThatThrownBy(() -> bigquery.execute(call("bigquery_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of())).isInstanceOf(ConnectorException.class).hasMessageContaining("SCRIPT, not a SELECT");
    // Too many bytes.
    google.on("POST /projects/" + P + "/jobs", dryRun("SELECT", 5L << 30, P + ".sales.orders"));
    assertThatThrownBy(() -> bigquery.execute(call("bigquery_query", sql("SELECT * FROM sales.orders")),
        config(Env.DEV), secrets, TestCtx.of())).hasMessageContaining("5.0 GiB, more than the connection's limit of "
        + "1.0 GiB");
    // A table off the list, read through a view or a join.
    google.on("POST /projects/" + P + "/jobs", dryRun("SELECT", 10, P + ".sales.orders", P + ".hr.salaries"));
    assertThatThrownBy(() -> bigquery.execute(call("bigquery_query", sql("SELECT * FROM sales.v")),
        config(Env.DEV, "sales"), secrets, TestCtx.of())).hasMessageContaining(P + ".hr");
    assertThat(google.to("POST /projects/" + P + "/queries")).isEmpty();
  }

  @Test
  void pagesGoOnFromTheNextRowOfTheSameJob() throws Exception {
    String rows = "\"rows\":[{\"f\":[{\"v\":\"1\"}]},{\"f\":[{\"v\":\"2\"}]}]";
    String schema = "{\"fields\":[{\"name\":\"id\",\"type\":\"INTEGER\"}]}";
    google.on("POST /projects/" + P + "/jobs", dryRun("SELECT", 10, P + ".sales.orders"))
        .on("POST /projects/" + P + "/queries", "{\"jobReference\":{\"jobId\":\"job_p\",\"location\":\"EU\"},"
            + "\"jobComplete\":false}")
        .on("GET /projects/" + P + "/queries/job_p", r -> "0".equals(param(r, "startIndex"))
            ? json(200, "{\"jobComplete\":true,\"schema\":" + schema + "," + rows + ",\"totalRows\":\"3\","
                + "\"pageToken\":\"t2\"}")
            : json(200, "{\"jobComplete\":true,\"schema\":" + schema + ",\"rows\":[{\"f\":[{\"v\":\"3\"}]}],"
                + "\"totalRows\":\"3\"}"));
    String args = "{\"sql\": \"SELECT id FROM sales.orders\", \"limit\": 2}";
    ToolResult first = bigquery.execute(call("bigquery_query", args), config(Env.DEV), secrets, TestCtx.of());
    assertThat(first.table().rows()).hasSize(2);
    assertThat(first.table().nextCursor()).startsWith("bq1.");

    ToolResult second = bigquery.execute(call("bigquery_query", "{\"sql\": \"SELECT id FROM sales.orders\", "
        + "\"limit\": 2, \"cursor\": \"" + first.table().nextCursor() + "\"}"), config(Env.DEV), secrets,
        TestCtx.of());
    assertThat(second.table().rows().get(0).get(0).asInt()).isEqualTo(3);
    assertThat(second.table().nextCursor()).isNull();
    List<RecordedRequest> pages = google.to("GET /projects/" + P + "/queries/job_p");
    assertThat(param(pages.getLast(), "startIndex")).isEqualTo("2");
    assertThat(param(pages.getLast(), "location")).isEqualTo("EU");
    // One dry run and one job: the next page reads the same job's results.
    assertThat(google.to("POST /projects/" + P + "/jobs")).hasSize(1);
    assertThat(google.to("POST /projects/" + P + "/queries")).hasSize(1);

    // A cursor belongs to its statement and connection.
    assertThatThrownBy(() -> bigquery.execute(call("bigquery_query", "{\"sql\": \"SELECT 2\", \"cursor\": \""
        + first.table().nextCursor() + "\"}"), config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class);
    assertThatThrownBy(() -> bigquery.execute(call("bigquery_query", "{\"sql\": \"SELECT id FROM sales.orders\", "
        + "\"cursor\": \"bq1.forged\"}"), config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class);
  }

  @Test
  void aQueryThatOutlivesTheCallIsCancelled() throws Exception {
    google.on("POST /projects/" + P + "/jobs", dryRun("SELECT", 10))
        .on("POST /projects/" + P + "/queries", "{\"jobReference\":{\"jobId\":\"job_slow\"},\"jobComplete\":false}")
        .on("GET /projects/" + P + "/queries/job_slow", "{\"jobComplete\":false}")
        .on("POST /projects/" + P + "/jobs/job_slow/cancel", "{}");
    assertThatThrownBy(() -> bigquery.execute(call("bigquery_query", sql("SELECT 1")), config(Env.DEV), secrets,
        TestCtx.of().timeout(java.time.Duration.ofMillis(2_500))))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("cancelled job job_slow");
    assertThat(google.to("POST /projects/" + P + "/jobs/job_slow/cancel")).hasSize(1);
  }

  @Test
  void aChangeRunsOnlyApprovedWithARequestIdAndNeverTwice() throws Exception {
    google.on("POST /projects/" + P + "/jobs", dryRun("UPDATE", 4096, P + ".sales.orders"))
        .on("POST /projects/" + P + "/queries", "{\"jobReference\":{\"jobId\":\"job_c1\"},\"jobComplete\":true,"
            + "\"numDmlAffectedRows\":\"3\"}");
    String text = "UPDATE sales.orders SET paid = true WHERE id IN (1, 2, 3)";
    ToolCall c = call("bigquery_execute", sql(text));
    assertThatThrownBy(() -> bigquery.execute(c, config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("Not approved");

    ActionPreview preview = bigquery.describeAction(c, config(Env.DEV));
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(preview.text()).isEqualTo(text);
    TestCtx approved = TestCtx.of().approved(preview.payload());
    ToolResult r = bigquery.execute(c, config(Env.DEV), secrets, approved);
    assertThat(r.structuredContent().path("rowsAffected").asInt()).isEqualTo(3);
    assertThat(r.content().getFirst().text()).isEqualTo("3 rows affected by UPDATE.");
    JsonNode sent = body(google.to("POST /projects/" + P + "/queries").getFirst());
    assertThat(sent.path("query").asString()).isEqualTo(text);
    assertThat(sent.path("requestId").asString()).hasSize(36).isEqualTo(BigQueryConnector.requestId(approved));
    assertThat(sent.at("/labels/gaterook").asString()).isEqualTo("change");
    assertThat(sent.path("maximumBytesBilled").asString()).isEqualTo(Long.toString(1L << 30));

    // What runs is the approved text, whatever the call says now.
    google.requests.clear();
    bigquery.execute(call("bigquery_execute", sql("DELETE FROM sales.orders WHERE true")), config(Env.DEV), secrets,
        approved);
    assertThat(body(google.to("POST /projects/" + P + "/queries").getFirst()).path("query").asString())
        .isEqualTo(text);
  }

  @Test
  void aLostAnswerToAChangeIsAnUnknownOutcome() throws Exception {
    google.on("POST /projects/" + P + "/jobs", dryRun("DELETE", 10, P + ".sales.orders"))
        .on("POST /projects/" + P + "/queries", r -> json(503, "{\"error\":{\"code\":503,\"message\":\"backend\"}}"));
    ToolCall c = call("bigquery_execute", sql("DELETE FROM sales.orders WHERE id = 1"));
    TestCtx approved = TestCtx.of().approved(bigquery.describeAction(c, config(Env.DEV)).payload());
    assertThatThrownBy(() -> bigquery.execute(c, config(Env.DEV), secrets, approved))
        .isInstanceOf(ConnectorException.class)
        .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
  }

  @Test
  void aFailedChangeJobChangedNothing() throws Exception {
    AtomicInteger polls = new AtomicInteger();
    google.on("POST /projects/" + P + "/jobs", dryRun("UPDATE", 10, P + ".sales.orders"))
        .on("POST /projects/" + P + "/queries", "{\"jobReference\":{\"jobId\":\"job_f\"},\"jobComplete\":false}")
        .on("GET /projects/" + P + "/queries/job_f", r -> {
          polls.incrementAndGet();
          return json(400, "{\"error\":{\"code\":400,\"message\":\"Could not serialize access to table due to "
              + "concurrent update\"}}");
        })
        .on("GET /projects/" + P + "/jobs/job_f", "{\"status\":{\"state\":\"DONE\",\"errorResult\":{\"reason\":"
            + "\"invalidQuery\",\"message\":\"Could not serialize access to table due to concurrent update\"}}}");
    ToolCall c = call("bigquery_execute", sql("UPDATE sales.orders SET paid = true WHERE id = 1"));
    TestCtx approved = TestCtx.of().approved(bigquery.describeAction(c, config(Env.DEV)).payload());
    assertThatThrownBy(() -> bigquery.execute(c, config(Env.DEV), secrets, approved))
        .isInstanceOf(ConnectorException.class)
        .hasMessageContaining("Nothing changed")
        .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR));
    assertThat(polls.get()).isEqualTo(1);
  }

  @Test
  void theApprovalShowsTheDryRunAndNeverAsksForWhatCantRun() throws Exception {
    google.on("POST /projects/" + P + "/jobs", dryRun("MERGE", 3L << 20, P + ".sales.orders", P + ".sales.staging"));
    ToolCall c = call("bigquery_execute", sql("MERGE sales.orders T USING sales.staging S ON T.id = S.id "
        + "WHEN MATCHED THEN UPDATE SET paid = S.paid"));
    PreparedAction prepared = bigquery.prepare(c, config(Env.DEV, "sales"), secrets, TestCtx.of());
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::label).startsWith("Statement type",
        "Bytes processed", "Tables read");
    assertThat(prepared.preview().impact().get(1).value()).startsWith("3.0 MiB");

    google.on("POST /projects/" + P + "/jobs", dryRun("SELECT", 10));
    assertThat(bigquery.prepare(call("bigquery_execute", sql("UPDATE sales.orders SET a = 1 WHERE true")),
        config(Env.DEV), secrets, TestCtx.of()).outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    google.on("POST /projects/" + P + "/jobs", r -> json(400, "{\"error\":{\"code\":400,\"message\":\"Unrecognized "
        + "name: paidd at [1:31]\",\"errors\":[{\"reason\":\"invalidQuery\"}]}}"));
    PreparedAction refused = bigquery.prepare(call("bigquery_execute", sql("UPDATE sales.orders SET paidd = 1 "
        + "WHERE true")), config(Env.DEV), secrets, TestCtx.of());
    assertThat(refused.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(refused.message()).contains("Unrecognized name: paidd");
  }

  @Test
  void listsAndDescribesWithinTheList() throws Exception {
    google.on("GET /projects/" + P + "/datasets", "{\"datasets\":[{\"datasetReference\":{\"projectId\":\"" + P
            + "\",\"datasetId\":\"sales\"},\"location\":\"EU\"},{\"datasetReference\":{\"projectId\":\"" + P
            + "\",\"datasetId\":\"hr\"}}],\"nextPageToken\":\"n2\"}")
        .on("GET /projects/" + P + "/datasets/sales/tables/orders", "{\"type\":\"TABLE\",\"numRows\":\"1204\","
            + "\"numBytes\":\"88000\",\"schema\":" + SCHEMA + ",\"timePartitioning\":{\"type\":\"DAY\",\"field\":"
            + "\"at\"},\"requirePartitionFilter\":true,\"clustering\":{\"fields\":[\"id\"]}}");
    ToolResult list = bigquery.execute(call("bigquery_list_datasets", "{}"), config(Env.DEV, "sales"), secrets,
        TestCtx.of());
    assertThat(list.structuredContent().path("datasets").toString()).contains("sales").doesNotContain("hr");
    assertThat(list.structuredContent().path("nextCursor").asString()).startsWith("bq1.");

    ToolResult d = bigquery.execute(call("bigquery_describe_table", "{\"table\": \"sales.orders\"}"),
        config(Env.DEV, "sales"), secrets, TestCtx.of());
    assertThat(d.structuredContent().path("rows").asLong()).isEqualTo(1204);
    assertThat(d.structuredContent().path("partitioning").asString()).isEqualTo("DAY on at");
    assertThat(d.content().getFirst().text()).contains("1,204 rows", "tags ARRAY<STRING>",
        "queries must filter on the partitioning column");
    assertThat(d.structuredContent().at("/columns/7/fields/0/name").asString()).isEqualTo("name");
    assertThatThrownBy(() -> bigquery.execute(call("bigquery_describe_table", "{\"table\": \"hr.salaries\"}"),
        config(Env.DEV, "sales"), secrets, TestCtx.of())).isInstanceOf(ConnectorException.class);
  }

  @Test
  void googlesErrorsSayWhatToDo() throws Exception {
    google.on("GET /projects/" + P + "/datasets", r -> json(403, "{\"error\":{\"code\":403,\"message\":\"BigQuery "
        + "API has not been used in project 1 before or it is disabled.\",\"status\":\"PERMISSION_DENIED\","
        + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"SERVICE_DISABLED\"}]}}"));
    TestResult t = bigquery.test(config(Env.DEV), secrets, TestCtx.of());
    assertThat(t.ok()).isFalse();
    assertThat(t.error().message()).contains("gcloud services enable bigquery.googleapis.com --project=" + P);
  }

  @Test
  void theTestListsDatasetsAndRunsADryRun() throws Exception {
    google.on("GET /projects/" + P + "/datasets", "{\"datasets\":[{\"datasetReference\":{\"projectId\":\"" + P
            + "\",\"datasetId\":\"sales\"}}]}")
        .on("POST /projects/" + P + "/jobs", dryRun("SELECT", 0));
    TestResult t = bigquery.test(config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(t.ok()).isTrue();
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("datasets", "jobs");
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("bigquery.personal-credentials",
        "bigquery.read-only-boundary");
    JsonNode dry = body(google.to("POST /projects/" + P + "/jobs").getFirst());
    assertThat(dry.at("/configuration/query/query").asString()).isEqualTo("SELECT 1");
  }

  @Test
  void googleTokensGoOnlyToGoogle() throws Exception {
    try (FakeBigQuery noTokenServer = new FakeBigQuery(false)) {
      BigQueryConnector c = new BigQueryConnector(noTokenServer.cloud);
      noTokenServer.on("GET /projects/" + P + "/datasets", "{\"datasets\":[]}");
      assertThatThrownBy(() -> c.execute(call("bigquery_list_datasets", "{}"), config(Env.DEV), secrets,
          TestCtx.of())).hasMessageContaining("only to Google's APIs");
      assertThat(noTokenServer.requests).isEmpty();
    }
  }
}
