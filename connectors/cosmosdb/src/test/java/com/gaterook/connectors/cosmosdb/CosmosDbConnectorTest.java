package com.gaterook.connectors.cosmosdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The Cosmos DB connector against a fake of Cosmos DB's gateway and of Microsoft Entra ID's token endpoint. */
class CosmosDbConnectorTest {

  static final String KEY_RO = "{\"method\": \"key\", \"keyKind\": \"readOnly\"}";
  static final String KEY_RW = "{\"method\": \"key\", \"keyKind\": \"readWrite\"}";
  static final String ENTRA = "{\"method\": \"entra\", \"tenantId\": \"" + FakeCosmos.TENANT + "\", \"clientId\": \""
      + FakeCosmos.CLIENT + "\"}";
  static final String DOCS = "/dbs/app/colls/orders/docs";
  private static final AtomicInteger CALLS = new AtomicInteger();

  private FakeCosmos fake;
  private CosmosDbConnector cosmos;
  private final TestSecrets secrets = new TestSecrets().with(CosmosSettings.ACCOUNT_KEY, FakeCosmos.KEY)
      .with(CosmosSettings.CLIENT_SECRET, "entra-test-secret-0001");

  @BeforeEach
  void start() throws Exception {
    fake = new FakeCosmos();
    cosmos = new CosmosDbConnector(true, fake.base(), fake.base());
  }

  @AfterEach
  void stop() {
    cosmos.close();
    fake.close();
  }

  static ConnectionConfig config(Env env, String auth, String more) {
    ObjectNode c = (ObjectNode) TestCtx.JSON.readTree("{\"endpoint\": \"https://acme.documents.azure.com\""
        + (more == null || more.isBlank() ? "" : ", " + more) + "}");
    c.set("auth", TestCtx.JSON.readTree(auth));
    return new ConnectionConfig("conn-cdb-" + env.id(), env.id() + "-cosmos", "cosmosdb", env, 1, c,
        Set.of(CosmosSettings.ACCOUNT_KEY, CosmosSettings.CLIENT_SECRET), null);
  }

  static ConnectionConfig config(String more) {
    return config(Env.DEV, KEY_RW, more);
  }

  static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".cosmosdb", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  static ToolCall call(String tool, String args) {
    return new ToolCall("tc_cdb_" + CALLS.incrementAndGet(), tool, "dev-cosmos", (ObjectNode) TestCtx.JSON.readTree(args),
        null);
  }

  private Classification classify(String tool, String args, String more) {
    return cosmos.classify(call(tool, args), config(more), policy(Env.DEV));
  }

  static MockResponse page(String continuation, String... documents) {
    String body = "{\"_rid\": \"r1==\", \"Documents\": [" + String.join(", ", documents) + "], \"_count\": "
        + documents.length + "}";
    return continuation == null ? FakeCosmos.json(200, body) : FakeCosmos.json(200, body, "x-ms-continuation",
        continuation);
  }

  // ── Tools and settings ──────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesItsToolsWithTheChangeAwaitingApproval() {
    var m = cosmos.manifest();
    assertThat(m.id()).isEqualTo("cosmosdb");
    assertThat(m.name()).isEqualTo("Azure Cosmos DB");
    assertThat(m.icon()).isEqualTo("database");
    assertThat(m.group()).isEqualTo("Databases");
    assertThat(m.description()).endsWith(".");
    assertThat(m.configSchema().at("/x-card/description").asString())
        .isEqualTo("Databases, containers, SQL queries and items. Changes wait for approval.");
    assertThat(m.tools()).extracting(ToolDefinition::name).containsExactly("cosmosdb_list_databases",
        "cosmosdb_list_containers", "cosmosdb_describe_container", "cosmosdb_query", "cosmosdb_read_item",
        "cosmosdb_execute");
    for (ToolDefinition t : m.tools()) {
      assertThat(t.inputSchema().path("additionalProperties").asBoolean(true)).as(t.name()).isFalse();
      assertThat(t.inputSchema().at("/properties/connection")).as(t.name())
          .isEqualTo(JsonNodeFactory.instance.objectNode().put("type", "string"));
      assertThat(t.title()).isNotEqualTo(t.name());
    }
    assertThat(m.tools().subList(0, 5)).allSatisfy(t -> {
      assertThat(t.nature()).isEqualTo(ToolDefinition.Nature.READ);
      assertThat(List.of(t.defaultMode(), t.prodDefaultMode())).containsOnly(ToolDefinition.Mode.READ_ONLY);
      assertThat(t.annotations()).isEqualTo(ToolDefinition.Annotations.read());
    });
    ToolDefinition execute = m.tools().getLast();
    assertThat(execute.nature()).isEqualTo(ToolDefinition.Nature.MUTATION);
    assertThat(execute.readOnlyModeAllowed()).isFalse();
    assertThat(execute.annotations()).isEqualTo(ToolDefinition.Annotations.write(true));
    assertThat(execute.outputSchema()).isNull();
    assertThat(List.of(execute.defaultMode(), execute.prodDefaultMode())).containsOnly(ToolDefinition.Mode.APPROVAL);
    assertThat(execute.inputSchema().at("/properties/note")).isEqualTo(TestCtx.JSON.readTree("{\"type\": \"string\", "
        + "\"maxLength\": 500, \"description\": \"Why you need this change. Shown to the approver as unverified.\"}"));
    assertThat(m.access().hosts()).contains("*.documents.azure.com", "login.microsoftonline.com");
  }

  @Test
  void theEndpointIsAnAzureCosmosDbHostOrTheEmulator() throws Exception {
    Map<String, String> good = Map.of(
        "https://acme.documents.azure.com", "acme.documents.azure.com",
        "https://ACME-westeurope.documents.azure.com:443/", "acme-westeurope.documents.azure.com",
        "https://acme.documents.azure.us", "acme.documents.azure.us",
        " https://acme.documents.azure.cn/ ", "acme.documents.azure.cn",
        "http://127.0.0.1:8081", "127.0.0.1:8081",
        "https://localhost:8081/", "localhost:8081",
        "http://[::1]:8081", "[::1]:8081");
    for (Map.Entry<String, String> e : good.entrySet()) {
      assertThat(CosmosEndpoint.parse(e.getKey(), true).label()).as(e.getKey()).isEqualTo(e.getValue());
    }
    assertThat(CosmosEndpoint.parse("https://acme.documents.azure.us", false).cloud())
        .isEqualTo(CosmosEndpoint.Cloud.GOVERNMENT);
    assertThat(CosmosEndpoint.parse("https://acme.documents.azure.cn", false).cloud().login)
        .isEqualTo("login.chinacloudapi.cn");
    assertThat(CosmosEndpoint.parse("https://acme.documents.azure.com", false).scope())
        .isEqualTo("https://acme.documents.azure.com/.default");
    for (String bad : List.of("http://acme.documents.azure.com", "https://acme.example.com",
        "https://acme.documents.azure.com.evil.example.com", "https://evil.example.com/acme.documents.azure.com",
        "https://acme.documents.azure.com/dbs/app", "https://acme.documents.azure.com:8443",
        "https://ab.documents.azure.com", "https://-acme.documents.azure.com", "https://a.b.documents.azure.com",
        "https://user@acme.documents.azure.com", "https://acme.documents.azure.com?x=1",
        "https://acme.documents.azure.com#x", "http://10.0.0.5:8081", "http://localhost", "ftp://acme.documents.azure.com",
        "AccountEndpoint=https://acme.documents.azure.com/;AccountKey=abc", "", "acme")) {
      assertThatThrownBy(() -> CosmosEndpoint.parse(bad, true)).as(bad).isInstanceOf(CosmosEndpoint.Invalid.class);
    }
    assertThatThrownBy(() -> CosmosEndpoint.parse("http://127.0.0.1:8081", false))
        .isInstanceOf(CosmosEndpoint.Invalid.class).hasMessageContaining("development builds");
    // A release build's connector refuses the plain emulator in every call, before any request.
    try (CosmosDbConnector release = new CosmosDbConnector(false, fake.base(), fake.base())) {
      ConnectionConfig plain = new ConnectionConfig("c", "emu", "cosmosdb", Env.DEV, 1, TestCtx.JSON.readTree(
          "{\"endpoint\": \"http://127.0.0.1:8081\", \"auth\": " + KEY_RW + "}"), Set.of(), null);
      Classification c = release.classify(call("cosmosdb_list_databases", "{}"), plain, policy(Env.DEV));
      assertThat(c.ruleId()).isEqualTo("dev.cosmosdb.bad-config");
      assertThat(release.test(plain, secrets, TestCtx.of()).error().message()).contains("development builds");
    }
    ConnectionConfig entraOnEmulator = new ConnectionConfig("c", "emu", "cosmosdb", Env.DEV, 1, TestCtx.JSON.readTree(
        "{\"endpoint\": \"http://127.0.0.1:8081\", \"auth\": " + ENTRA + "}"), Set.of(), null);
    assertThat(cosmos.classify(call("cosmosdb_list_databases", "{}"), entraOnEmulator, policy(Env.DEV)).reason())
        .contains("emulator takes its key");
    assertThat(fake.requests).isEmpty();
  }

  @Test
  void idsAndTheListsAreReadStrictly() {
    String lists = "\"databases\": [\"app\", \"audit-*\"], \"containers\": [\"orders\", \"app/customers\", \"audit-*/*\"]";
    Map<String, String> verdicts = Map.of(
        "{\"database\": \"app\", \"container\": \"orders\"}", "READ",
        "{\"database\": \"app\", \"container\": \"customers\"}", "READ",
        "{\"database\": \"audit-2026\", \"container\": \"events\"}", "READ",
        "{\"database\": \"audit-2026\", \"container\": \"orders\"}", "READ",
        "{\"database\": \"app\", \"container\": \"secrets\"}", "dev.cosmosdb.container-not-allowed",
        "{\"database\": \"hr\", \"container\": \"orders\"}", "dev.cosmosdb.database-not-allowed",
        "{\"database\": \"other\", \"container\": \"customers\"}", "dev.cosmosdb.database-not-allowed");
    verdicts.forEach((args, verdict) -> {
      Classification c = classify("cosmosdb_describe_container", args, lists);
      assertThat(c.kind() == Classification.Kind.READ ? "READ" : c.ruleId()).as(args).isEqualTo(verdict);
    });
    for (String name : List.of("a/b", "a\\\\b", "a#b", "a?b", "..", ".", "a\\u0000b", "a\\u202eb", "a\\u200bb", "end ",
        "")) {
      Classification c = classify("cosmosdb_describe_container", "{\"database\": \"app\", \"container\": \"" + name
          + "\"}", null);
      assertThat(c.ruleId()).as(name).isEqualTo("dev.cosmosdb.bad-argument");
    }
    for (String id : List.of("a/b", "a\\\\b", "..", "a\\u0007b", "a\\u2066b")) {
      Classification c = classify("cosmosdb_read_item", "{\"database\": \"app\", \"container\": \"orders\", \"id\": \""
          + id + "\", \"partitionKey\": \"c-1\"}", null);
      assertThat(c.ruleId()).as(id).isEqualTo("dev.cosmosdb.bad-argument");
    }
    // Item IDs may hold what URLs reserve: GateRook encodes it.
    assertThat(classify("cosmosdb_read_item", "{\"database\": \"app\", \"container\": \"orders\", \"id\": "
        + "\"a b?c#d%e+f&\\u00e9\", \"partitionKey\": \"c-1\"}", null).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("cosmosdb_read_item", "{\"database\": \"app\", \"container\": \"orders\", \"id\": \"o-1\"}",
        null).reason()).contains("partitionKey");
    for (String pk : List.of("{\"a\": 1}", "[]", "[1, 2, 3, 4]", "[[1]]", "9007199254740993", "1e400", "\"a\\u007fb\"")) {
      Classification c = classify("cosmosdb_read_item", "{\"database\": \"app\", \"container\": \"orders\", \"id\": "
          + "\"o-1\", \"partitionKey\": " + pk + "}", null);
      assertThat(c.ruleId()).as(pk).isEqualTo("dev.cosmosdb.bad-argument");
    }
    for (String pk : List.of("\"c-1\"", "42", "4.5", "true", "null", "[\"acme\", \"u-1\"]", "[\"acme\", 7, null]")) {
      assertThat(classify("cosmosdb_read_item", "{\"database\": \"app\", \"container\": \"orders\", \"id\": \"o-1\", "
          + "\"partitionKey\": " + pk + "}", null).kind()).as(pk).isEqualTo(Classification.Kind.READ);
    }
    assertThat(classify("cosmosdb_nope", "{}", null).ruleId()).isEqualTo("dev.cosmosdb.unknown-tool");
    assertThat(classify("cosmosdb_list_containers", "{\"database\": \"hr\"}", lists).ruleId())
        .isEqualTo("dev.cosmosdb.database-not-allowed");
  }

  @Test
  void aQueryIsOneSelectWithParametersAndAPartitionKey() {
    String q = "{\"database\": \"app\", \"container\": \"orders\", ";
    assertThat(classify("cosmosdb_query", q + "\"query\": \"SELECT * FROM c WHERE c.s = @s\", \"parameters\": "
        + "{\"@s\": \"paid\"}}", null).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("cosmosdb_query", q + "\"query\": \"  -- the paid ones\\n(SELECT VALUE c.id FROM c)\"}", null)
        .kind()).isEqualTo(Classification.Kind.READ);
    for (String bad : List.of("\"query\": \"UPDATE c SET c.x = 1\"", "\"query\": \"DELETE FROM c\"",
        "\"query\": \"   \"", "\"query\": \"SELECT \\u0000 FROM c\"",
        "\"query\": \"SELECT * FROM c\", \"parameters\": {\"s\": 1}",
        "\"query\": \"SELECT * FROM c\", \"parameters\": [1]",
        "\"query\": \"SELECT * FROM c\", \"partitionKey\": {\"x\": 1}")) {
      assertThat(classify("cosmosdb_query", q + bad + "}", null).ruleId()).as(bad).isEqualTo("dev.cosmosdb.bad-argument");
    }
    String strict = "\"advanced\": {\"crossPartitionQueries\": false}";
    Classification fanOut = classify("cosmosdb_query", q + "\"query\": \"SELECT * FROM c\"}", strict);
    assertThat(fanOut.ruleId()).isEqualTo("dev.cosmosdb.cross-partition");
    assertThat(fanOut.hint()).contains("partitionKey");
    assertThat(classify("cosmosdb_query", q + "\"query\": \"SELECT * FROM c\", \"partitionKey\": \"c-1\"}", strict)
        .kind()).isEqualTo(Classification.Kind.READ);
  }

  // ── Queries ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aQueryIsSignedPagedAndContinuedWithASealedCursor() throws Exception {
    AtomicInteger n = new AtomicInteger();
    fake.on("POST " + DOCS, r -> switch (n.incrementAndGet()) {
      case 1 -> page("+RID:~p1#RT:1", "{\"id\": \"o-1\", \"total\": 10}", "{\"id\": \"o-2\", \"total\": 20}");
      case 2 -> page("+RID:~p2#RT:2", "{\"id\": \"o-3\", \"total\": 30}");
      default -> page(null, "{\"id\": \"o-4\", \"total\": 40}");
    });
    String args = "{\"database\": \"app\", \"container\": \"orders\", \"query\": \"SELECT c.id, c.total FROM c WHERE "
        + "c.total > @min\", \"parameters\": {\"@min\": 5}, \"limit\": 3}";
    TestCtx ctx = TestCtx.of();
    ToolResult first = cosmos.execute(call("cosmosdb_query", args), config(null), secrets, ctx);

    assertThat(fake.refused).isEmpty();
    List<RecordedRequest> sent = fake.to("POST " + DOCS);
    assertThat(sent).hasSize(2);
    RecordedRequest r1 = sent.getFirst();
    assertThat(r1.getHeaders().get("x-ms-documentdb-isquery")).isEqualTo("True");
    assertThat(r1.getHeaders().get("Content-Type")).startsWith("application/query+json");
    assertThat(r1.getHeaders().get("x-ms-documentdb-query-enablecrosspartition")).isEqualTo("True");
    assertThat(r1.getHeaders().get("x-ms-documentdb-partitionkey")).isNull();
    assertThat(r1.getHeaders().get("x-ms-max-item-count")).isEqualTo("3");
    assertThat(r1.getHeaders().get("x-ms-continuation")).isNull();
    assertThat(r1.getHeaders().get("x-ms-version")).isEqualTo("2020-07-15");
    assertThat(r1.getHeaders().get("x-ms-date")).matches("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT");
    assertThat(FakeCosmos.body(r1).toString()).isEqualTo("{\"query\":\"SELECT c.id, c.total FROM c WHERE c.total > "
        + "@min\",\"parameters\":[{\"name\":\"@min\",\"value\":5}]}");
    RecordedRequest r2 = sent.get(1);
    assertThat(r2.getHeaders().get("x-ms-max-item-count")).isEqualTo("1");
    assertThat(r2.getHeaders().get("x-ms-continuation")).isEqualTo("+RID:~p1#RT:1");

    JsonNode out = first.structuredContent();
    assertThat(out.path("count").asInt()).isEqualTo(3);
    assertThat(out.path("items").toString()).isEqualTo("[{\"id\":\"o-1\",\"total\":10},{\"id\":\"o-2\",\"total\":20},"
        + "{\"id\":\"o-3\",\"total\":30}]");
    assertThat(out.path("requestCharge").asDouble()).isEqualTo(5.0);
    assertThat(out.path("crossPartition").asBoolean()).isTrue();
    String cursor = out.path("nextCursor").asString();
    assertThat(cursor).startsWith("cdb1.");
    assertThat(first.content().getFirst().text()).startsWith("3 items from app/orders (all partitions) · 5.00 RU")
        .contains("More items: pass nextCursor.");
    // The account key, each signature and each authorization header went to the call's taint.
    assertThat(ctx.taints).contains(FakeCosmos.KEY);
    String auth = URLDecoder.decode(r1.getHeaders().get("authorization"), StandardCharsets.UTF_8);
    assertThat(ctx.taints).contains(auth.substring(auth.indexOf("sig=") + 4), r1.getHeaders().get("authorization"));

    ObjectNode next = (ObjectNode) TestCtx.JSON.readTree(args);
    next.put("cursor", cursor);
    ToolResult second = cosmos.execute(call("cosmosdb_query", next.toString()), config(null), secrets, ctx.next());
    assertThat(fake.to("POST " + DOCS).get(2).getHeaders().get("x-ms-continuation")).isEqualTo("+RID:~p2#RT:2");
    assertThat(second.structuredContent().path("items").toString()).isEqualTo("[{\"id\":\"o-4\",\"total\":40}]");
    assertThat(second.structuredContent().has("nextCursor")).isFalse();

    // The cursor belongs to this connection and this query.
    ObjectNode other = next.deepCopy();
    other.put("query", "SELECT * FROM c");
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", other.toString()), config(null), secrets,
        ctx.next())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("another connection or request");
        });
    ObjectNode forged = next.deepCopy();
    forged.put("cursor", "cdb1.eyJ4IjoxfQ.AAAA");
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", forged.toString()), config(null), secrets,
        ctx.next())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("invalid"));
  }

  @Test
  void aQueryOfOnePartitionNamesItsKeyInAsciiAndAsksForTheConnectionsConsistency() throws Exception {
    fake.on("POST " + DOCS, r -> page(null, "{\"id\": \"o-1\", \"city\": \"Z\\u00fcrich\"}"));
    String args = "{\"database\": \"app\", \"container\": \"orders\", \"query\": \"SELECT * FROM c\", "
        + "\"partitionKey\": \"Z\\u00fcrich\"}";
    ToolResult r = cosmos.execute(call("cosmosdb_query", args), config("\"readConsistency\": \"Eventual\""), secrets,
        TestCtx.of());
    RecordedRequest sent = fake.to("POST " + DOCS).getFirst();
    assertThat(sent.getHeaders().get("x-ms-documentdb-partitionkey")).isEqualTo("[\"Z\\u00FCrich\"]");
    assertThat(sent.getHeaders().get("x-ms-documentdb-query-enablecrosspartition")).isNull();
    assertThat(sent.getHeaders().get("x-ms-consistency-level")).isEqualTo("Eventual");
    assertThat(r.structuredContent().path("crossPartition").asBoolean()).isFalse();
    assertThat(r.content().getFirst().text()).contains("(partition [\"Zürich\"])");

    cosmos.execute(call("cosmosdb_query", "{\"database\": \"app\", \"container\": \"orders\", \"query\": \"SELECT * "
        + "FROM c\", \"partitionKey\": [\"acme\", 7]}"), config(null), secrets, TestCtx.of());
    assertThat(fake.to("POST " + DOCS).getLast().getHeaders().get("x-ms-documentdb-partitionkey"))
        .isEqualTo("[\"acme\",7]");
    assertThat(fake.to("POST " + DOCS).getLast().getHeaders().get("x-ms-consistency-level")).isNull();
  }

  @Test
  void aQueryThatNeedsStateAcrossPagesIsNeverContinued() throws Exception {
    // One partition, ORDER BY: the first page is the beginning of the answer, but no cursor goes on from it.
    fake.on("POST " + DOCS, r -> page("+RID:~sorted#RT:1", "{\"id\": \"o-9\"}", "{\"id\": \"o-8\"}"));
    ToolResult sorted = cosmos.execute(call("cosmosdb_query", "{\"database\": \"app\", \"container\": \"orders\", "
        + "\"query\": \"SELECT c.id FROM c ORDER BY c._ts DESC\", \"partitionKey\": \"c-1\", \"limit\": 2}"),
        config(null), secrets, TestCtx.of());
    assertThat(fake.to("POST " + DOCS)).hasSize(1);
    assertThat(sorted.structuredContent().has("nextCursor")).isFalse();
    assertThat(sorted.structuredContent().path("count").asInt()).isEqualTo(2);
    assertThat(sorted.truncated()).isTrue();
    assertThat(sorted.structuredContent().path("note").asString()).contains("can't continue ORDER BY page by page");

    // An aggregate in more than one page: a page alone is no answer.
    fake.on("POST " + DOCS, r -> page("+RID:~count#RT:1", "41"));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", "{\"database\": \"app\", \"container\": "
        + "\"orders\", \"query\": \"SELECT VALUE COUNT(1) FROM c\", \"partitionKey\": \"c-1\"}"), config(null), secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("more than one page").contains("COUNT(…)");
        });
    // In one page it is the answer.
    fake.on("POST " + DOCS, r -> page(null, "41"));
    ToolResult count = cosmos.execute(call("cosmosdb_query", "{\"database\": \"app\", \"container\": \"orders\", "
        + "\"query\": \"SELECT VALUE COUNT(1) FROM c\", \"partitionKey\": \"c-1\"}"), config(null), secrets,
        TestCtx.of());
    assertThat(count.structuredContent().path("items").toString()).isEqualTo("[41]");
    assertThat(count.truncated()).isFalse();

    // Across partitions the gateway refuses it, and the agent hears why (REST API: queries the gateway can't serve).
    fake.on("POST " + DOCS, r -> FakeCosmos.json(400, "{\"code\": \"BadRequest\", \"message\": \"The provided cross "
        + "partition query can not be directly served by the gateway. This is a first chance (internal) exception "
        + "that all newer clients will know how to handle gracefully. This exception is traced, but unless you see it "
        + "bubble up as an exception (which only happens on older SDK clients), then you can safely ignore this "
        + "message.\\r\\nActivityId: db660ee4-350a-40e9-bc2c-99f92f2b445d, Microsoft.Azure.Documents.Common/2.2.0.0\", "
        + "\"additionalErrorInfo\": \"{\\\"partitionedQueryExecutionInfoVersion\\\":2,\\\"queryInfo\\\":"
        + "{\\\"distinctType\\\":\\\"None\\\",\\\"top\\\":null,\\\"offset\\\":null,\\\"limit\\\":null,\\\"orderBy\\\":"
        + "[\\\"Descending\\\"],\\\"orderByExpressions\\\":[\\\"c._ts\\\"],\\\"aggregates\\\":[],\\\"rewrittenQuery\\\":"
        + "\\\"\\\"},\\\"queryRanges\\\":[{\\\"min\\\":\\\"\\\",\\\"max\\\":\\\"FF\\\",\\\"isMinInclusive\\\":true,"
        + "\\\"isMaxInclusive\\\":false}]}\"}", "x-ms-substatus", "1004"));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", "{\"database\": \"app\", \"container\": "
        + "\"orders\", \"query\": \"SELECT * FROM c ORDER BY c._ts DESC\"}"), config(null), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).startsWith("Cosmos DB's gateway doesn't run ORDER BY across partitions")
              .contains("Give partitionKey").doesNotContain("ActivityId");
        });
  }

  @Test
  void anAnswerCutAtTheResponseSizeGoesOnFromAWholePage() throws Exception {
    String big = "\"" + "x".repeat(6_000) + "\"";
    AtomicInteger n = new AtomicInteger();
    fake.on("POST " + DOCS, r -> n.incrementAndGet() == 1
        ? page("+RID:~p1", "{\"id\": \"o-1\", \"note\": " + big + "}")
        : page("+RID:~p2", "{\"id\": \"o-2\", \"note\": " + big + "}", "{\"id\": \"o-3\", \"note\": " + big + "}"));
    com.gaterook.connector.api.Limits small = new com.gaterook.connector.api.Limits(1_000, 1_000, 40_000,
        256 * 1024, 256 * 1024, 64 * 1024, 8 * 1024, Duration.ofSeconds(5), Duration.ofSeconds(30),
        Duration.ofSeconds(10), null);
    ToolResult r = cosmos.execute(call("cosmosdb_query", "{\"database\": \"app\", \"container\": \"orders\", "
        + "\"query\": \"SELECT * FROM c\", \"limit\": 10}"), config(null), secrets, TestCtx.of().with(small));
    // The second page doesn't fit whole: the cursor reads it again from its start.
    assertThat(r.structuredContent().path("count").asInt()).isEqualTo(1);
    assertThat(r.structuredContent().path("items").get(0).path("id").asString()).isEqualTo("o-1");
    String cursor = r.structuredContent().path("nextCursor").asString();
    assertThat(cursor).startsWith("cdb1.");
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
    ObjectNode next = (ObjectNode) TestCtx.JSON.readTree("{\"database\": \"app\", \"container\": \"orders\", "
        + "\"query\": \"SELECT * FROM c\", \"limit\": 10}");
    next.put("cursor", cursor);
    cosmos.execute(call("cosmosdb_query", next.toString()), config(null), secrets, TestCtx.of().with(small));
    assertThat(fake.to("POST " + DOCS).getLast().getHeaders().get("x-ms-continuation")).isEqualTo("+RID:~p1");

    // A first item too large for the answer shows the properties that fit, and the cursor goes on after it.
    fake.on("POST " + DOCS, r3 -> page("+RID:~after-big", "{\"id\": \"o-big\", \"a\": " + big + ", \"b\": " + big
        + ", \"c\": " + big + ", \"d\": " + big + "}"));
    ToolResult huge = cosmos.execute(call("cosmosdb_query", "{\"database\": \"app\", \"container\": \"orders\", "
        + "\"query\": \"SELECT * FROM c\", \"limit\": 10}"), config(null), secrets, TestCtx.of().with(small));
    assertThat(huge.structuredContent().at("/items/0/id").asString()).isEqualTo("o-big");
    assertThat(huge.structuredContent().at("/items/0/‹omittedProperties›").asInt()).isEqualTo(3);
    assertThat(huge.structuredContent().path("nextCursor").asString()).startsWith("cdb1.");
    assertThat(huge.truncatedReason()).isEqualTo("size_limit");
    assertThat(huge.structuredContent().path("note").asString()).contains("pass nextCursor");

    // Long strings are cut to the cell size, and say how long they were.
    fake.on("POST " + DOCS, r2 -> page(null, "{\"id\": \"o-1\", \"note\": \"" + "y".repeat(9_000) + "\"}"));
    ToolResult cut = cosmos.execute(call("cosmosdb_query", "{\"database\": \"app\", \"container\": \"orders\", "
        + "\"query\": \"SELECT * FROM c\"}"), config(null), secrets, TestCtx.of());
    assertThat(cut.structuredContent().at("/items/0/note").asString()).endsWith("…‹9000 characters›").hasSize(
        8 * 1024 + "…‹9000 characters›".length());
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void anItemIsReadWithItsEtagOrIsReportedMissing() throws Exception {
    String id = "a b?c#d%e+f&é";
    String encoded = "a%20b%3Fc%23d%25e%2Bf%26%C3%A9";
    fake.on("GET " + DOCS + "/" + encoded, r -> FakeCosmos.json(200, "{\"id\": \"" + id + "\", \"customerId\": "
        + "\"c-1\", \"_etag\": \"\\\"00000000-0000-0000-1111-000000000001\\\"\", \"_ts\": 1794403734}", "etag",
        "\"00000000-0000-0000-1111-000000000001\""));
    ObjectNode args = (ObjectNode) TestCtx.JSON.readTree("{\"database\": \"app\", \"container\": \"orders\", "
        + "\"partitionKey\": \"c-1\"}");
    args.put("id", id);
    ToolResult found = cosmos.execute(call("cosmosdb_read_item", args.toString()), config("\"readConsistency\": "
        + "\"Session\""), secrets, TestCtx.of());
    assertThat(fake.refused).isEmpty();
    RecordedRequest sent = fake.to("GET " + DOCS).getFirst();
    assertThat(sent.getUrl().encodedPath()).isEqualTo(DOCS + "/" + encoded);
    assertThat(sent.getHeaders().get("x-ms-documentdb-partitionkey")).isEqualTo("[\"c-1\"]");
    assertThat(sent.getHeaders().get("x-ms-consistency-level")).isEqualTo("Session");
    assertThat(found.structuredContent().path("found").asBoolean()).isTrue();
    assertThat(found.structuredContent().path("etag").asString()).isEqualTo("\"00000000-0000-0000-1111-000000000001\"");
    assertThat(found.structuredContent().at("/item/customerId").asString()).isEqualTo("c-1");

    args.put("id", "o-404");
    ToolResult missing = cosmos.execute(call("cosmosdb_read_item", args.toString()), config(null), secrets,
        TestCtx.of());
    assertThat(missing.isError()).isFalse();
    assertThat(missing.structuredContent().path("found").asBoolean()).isFalse();
    assertThat(missing.content().getFirst().text()).isEqualTo("No item o-404 with partition key [\"c-1\"] in "
        + "app/orders.");

    fake.on("GET " + DOCS + "/o-x", r -> FakeCosmos.json(404, "{\"code\": \"NotFound\", \"message\": \"Owner "
        + "resource does not exist\"}", "x-ms-substatus", "1003"));
    args.put("id", "o-x");
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_read_item", args.toString()), config(null), secrets,
        TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("doesn't exist").contains("cosmosdb_list_containers"));
  }

  @Test
  void listingsKeepToTheConnectionsLists() throws Exception {
    AtomicInteger n = new AtomicInteger();
    fake.on("GET /dbs", r -> n.incrementAndGet() == 1
        ? FakeCosmos.json(200, "{\"_rid\": \"\", \"Databases\": [{\"id\": \"app\"}, {\"id\": \"hr\"}, {\"id\": "
            + "\"audit-1\"}], \"_count\": 3}", "x-ms-continuation", "dbs-page-2")
        : FakeCosmos.json(200, "{\"_rid\": \"\", \"Databases\": [{\"id\": \"audit-2\"}], \"_count\": 1}"));
    ToolResult dbs = cosmos.execute(call("cosmosdb_list_databases", "{}"), config("\"databases\": [\"app\", "
        + "\"audit-*\"]"), secrets, TestCtx.of());
    assertThat(dbs.structuredContent().path("databases").toString()).isEqualTo("[\"app\",\"audit-1\",\"audit-2\"]");
    assertThat(fake.to("GET /dbs").get(1).getHeaders().get("x-ms-continuation")).isEqualTo("dbs-page-2");
    assertThat(dbs.content().getFirst().text()).isEqualTo("3 databases: app, audit-1, audit-2");

    fake.on("GET /dbs/app/colls", r -> FakeCosmos.json(200, "{\"DocumentCollections\": [{\"id\": \"orders\", "
        + "\"partitionKey\": {\"paths\": [\"/customerId\"], \"kind\": \"Hash\", \"version\": 2}}, {\"id\": \"secrets\", "
        + "\"partitionKey\": {\"paths\": [\"/id\"]}}, {\"id\": \"events\", \"partitionKey\": {\"paths\": [\"/tenantId\", "
        + "\"/userId\"], \"kind\": \"MultiHash\", \"version\": 2}}]}"));
    ToolResult containers = cosmos.execute(call("cosmosdb_list_containers", "{\"database\": \"app\"}"),
        config("\"containers\": [\"orders\", \"app/events\"]"), secrets, TestCtx.of());
    assertThat(containers.structuredContent().path("containers").toString()).isEqualTo("[{\"id\":\"orders\","
        + "\"partitionKeyPaths\":[\"/customerId\"],\"partitionKeyKind\":\"Hash\"},{\"id\":\"events\","
        + "\"partitionKeyPaths\":[\"/tenantId\",\"/userId\"],\"partitionKeyKind\":\"MultiHash\"}]");
    assertThat(containers.content().getFirst().text()).isEqualTo("2 containers in app: orders (/customerId), events "
        + "(/tenantId, /userId)");

    // A principal whose roles are scoped to databases may not list the account's: the connection's are looked up.
    fake.on("GET /dbs", r -> FakeCosmos.json(403, "{\"code\": \"Forbidden\", \"message\": \"Request blocked by Auth "
        + "acme : Request is blocked because principal [" + FakeCosmos.OID + "] does not have required RBAC "
        + "permissions to perform action [Microsoft.DocumentDB/databaseAccounts/readMetadata] on resource [/].\"}",
        "x-ms-substatus", "5301"));
    fake.on("GET /dbs/app", r -> FakeCosmos.json(200, "{\"id\": \"app\", \"_rid\": \"r==\"}"));
    fake.on("GET /dbs/hr", r -> FakeCosmos.json(403, "{\"code\": \"Forbidden\", \"message\": \"blocked\"}",
        "x-ms-substatus", "5301"));
    ToolResult looked = cosmos.execute(call("cosmosdb_list_databases", "{}"), config(Env.DEV, ENTRA,
        "\"databases\": [\"app\", \"hr\", \"audit-*\"]"), secrets, TestCtx.of());
    assertThat(looked.structuredContent().path("databases").toString()).isEqualTo("[\"app\"]");
    assertThat(looked.content().getFirst().text()).contains("looked up one by one");
    // Any other refusal is reported as it is: nothing is looked up past the account's firewall.
    int before = fake.to("GET /dbs/").size();
    fake.on("GET /dbs", r -> FakeCosmos.json(403, "{\"code\": \"Forbidden\", \"message\": \"Request originated from "
        + "IP 203.0.113.7 through public internet. This is blocked by your Cosmos DB account firewall settings.\"}"));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_list_databases", "{}"), config(Env.DEV, ENTRA,
        "\"databases\": [\"app\"]"), secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("firewall"));
    assertThat(fake.to("GET /dbs/")).hasSize(before);
  }

  @Test
  void aContainerIsDescribedWithItsPolicyAndThroughput() throws Exception {
    fake.on("GET /dbs/app/colls/orders", r -> FakeCosmos.json(200, "{\"id\": \"orders\", \"_rid\": \"cRid==\", "
        + "\"partitionKey\": {\"paths\": [\"/customerId\"], \"kind\": \"Hash\", \"version\": 2}, \"defaultTtl\": -1, "
        + "\"uniqueKeyPolicy\": {\"uniqueKeys\": [{\"paths\": [\"/orderNo\"]}]}, \"indexingPolicy\": {\"automatic\": "
        + "true, \"indexingMode\": \"consistent\", \"includedPaths\": [{\"path\": \"/*\"}], \"excludedPaths\": "
        + "[{\"path\": \"/\\\"_etag\\\"/?\"}], \"compositeIndexes\": [[{\"path\": \"/status\", \"order\": "
        + "\"ascending\"}, {\"path\": \"/total\", \"order\": \"descending\"}]]}, \"conflictResolutionPolicy\": "
        + "{\"mode\": \"LastWriterWins\"}}"));
    fake.on("POST /offers", r -> FakeCosmos.body(r).path("parameters").get(0).path("value").asString()
        .equals("cRid==") ? FakeCosmos.json(200, "{\"Offers\": [{\"offerVersion\": \"V2\", \"content\": "
        + "{\"offerThroughput\": 400}}]}") : FakeCosmos.json(200, "{\"Offers\": []}"));
    ToolResult r = cosmos.execute(call("cosmosdb_describe_container", "{\"database\": \"app\", \"container\": "
        + "\"orders\"}"), config(null), secrets, TestCtx.of());
    assertThat(fake.refused).isEmpty();
    JsonNode d = r.structuredContent();
    assertThat(d.path("partitionKey").toString()).isEqualTo("{\"paths\":[\"/customerId\"],\"kind\":\"Hash\","
        + "\"version\":2}");
    assertThat(d.path("defaultTtl").asString()).isEqualTo("on, no default");
    assertThat(d.path("uniqueKeys").toString()).isEqualTo("[[\"/orderNo\"]]");
    assertThat(d.at("/indexing/compositeIndexes/0").asString()).isEqualTo("/status ascending, /total descending");
    assertThat(d.at("/throughput/manualRUs").asLong()).isEqualTo(400);
    RecordedRequest offers = fake.to("POST /offers").getFirst();
    assertThat(offers.getHeaders().get("x-ms-documentdb-isquery")).isEqualTo("True");
    assertThat(r.content().getFirst().text()).isEqualTo("app/orders: partition key /customerId (Hash v2) · TTL on, no "
        + "default · unique keys /orderNo · indexing consistent, 1 composite · 400 RU/s");

    // Shared throughput of the database; and with Entra ID offers aren't readable.
    fake.on("GET /dbs/app/colls/orders", req -> FakeCosmos.json(200, "{\"id\": \"orders\", \"_rid\": \"c2==\"}"));
    fake.on("GET /dbs/app", req -> FakeCosmos.json(200, "{\"id\": \"app\", \"_rid\": \"dRid==\"}"));
    fake.on("POST /offers", req -> FakeCosmos.body(req).path("parameters").get(0).path("value").asString()
        .equals("dRid==") ? FakeCosmos.json(200, "{\"Offers\": [{\"content\": {\"offerThroughput\": 1000, "
        + "\"offerAutopilotSettings\": {\"maxThroughput\": 4000}}}]}") : FakeCosmos.json(200, "{\"Offers\": []}"));
    JsonNode shared = cosmos.execute(call("cosmosdb_describe_container", "{\"database\": \"app\", \"container\": "
        + "\"orders\"}"), config(null), secrets, TestCtx.of()).structuredContent();
    assertThat(shared.at("/throughput/autoscaleMaxRUs").asLong()).isEqualTo(4000);
    assertThat(shared.at("/throughput/sharedWithDatabase").asBoolean()).isTrue();
    assertThat(shared.path("defaultTtl").asString()).isEqualTo("off");
    fake.on("POST /offers", req -> FakeCosmos.json(403, "{\"code\": \"Forbidden\", \"message\": \"no\"}",
        "x-ms-substatus", "5301"));
    JsonNode entra = cosmos.execute(call("cosmosdb_describe_container", "{\"database\": \"app\", \"container\": "
        + "\"orders\"}"), config(Env.DEV, ENTRA, null), secrets, TestCtx.of()).structuredContent();
    assertThat(entra.at("/throughput/readable").asBoolean(true)).isFalse();
    assertThat(entra.at("/throughput/note").asString()).contains("control-plane");
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aChangeRunsOnlyAfterApprovalAndRunsExactlyWhatWasShown() throws Exception {
    fake.on("POST " + DOCS, r -> FakeCosmos.json(201, "{\"id\": \"o-1\", \"customerId\": \"c-42\", \"total\": 12.5, "
        + "\"_etag\": \"\\\"e1\\\"\", \"_ts\": 1794403734}", "etag", "\"e1\""));
    ConnectionConfig cfg = config("\"containers\": [\"orders\"]");
    ToolCall create = call("cosmosdb_execute", "{\"database\": \"app\", \"container\": \"orders\", \"operation\": "
        + "\"create\", \"partitionKey\": \"c-42\", \"item\": {\"id\": \"o-1\", \"customerId\": \"c-42\", \"total\": "
        + "12.5, \"tags\": [\"new\"]}, \"note\": \"New order\"}");
    Classification c = cosmos.classify(create, cfg, policy(Env.DEV));
    assertThat(c.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(c.facts().toString()).isEqualTo("{\"operation\":\"create\",\"database\":\"app\",\"container\":"
        + "\"orders\"}");
    ActionPreview preview = cosmos.describeAction(create, cfg);
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(preview.target()).isEqualTo("dev-cosmos · acme.documents.azure.com · app/orders · o-1");
    assertThat(preview.text()).isEqualTo("""
        POST https://acme.documents.azure.com/dbs/app/colls/orders/docs
        x-ms-documentdb-partitionkey: ["c-42"]
        Content-Type: application/json

        {
          "id" : "o-1",
          "customerId" : "c-42",
          "total" : 12.5,
          "tags" : [ "new" ]
        }""");
    assertThat(preview.payload().path("body").asString()).isEqualTo(preview.text().substring(preview.text()
        .indexOf("\n\n") + 2));
    assertThat(preview.auditFacets()).containsEntry("operation", "create").containsEntry("container", "orders");
    assertThat(preview.fields()).extracting(ActionPreview.Field::label).contains("Partition key", "If-Match");

    assertThatThrownBy(() -> cosmos.execute(create, cfg, secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(fake.to("POST " + DOCS)).isEmpty();

    // The call's own arguments say something else: what runs is what the person approved.
    ToolCall other = call("cosmosdb_execute", "{\"database\": \"app\", \"container\": \"orders\", \"operation\": "
        + "\"delete\", \"id\": \"o-7\", \"partitionKey\": \"c-7\"}");
    ToolResult done = cosmos.execute(other, cfg, secrets, TestCtx.of().approved(preview.payload()));
    assertThat(fake.refused).isEmpty();
    RecordedRequest sent = fake.to("POST " + DOCS).getFirst();
    assertThat(sent.getBody().utf8()).isEqualTo(preview.payload().path("body").asString());
    assertThat(sent.getHeaders().get("x-ms-documentdb-partitionkey")).isEqualTo("[\"c-42\"]");
    assertThat(sent.getHeaders().get("x-ms-documentdb-is-upsert")).isNull();
    assertThat(sent.getHeaders().get("If-Match")).isNull();
    assertThat(sent.getHeaders().get("Content-Type")).startsWith("application/json");
    assertThat(done.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(done.structuredContent().path("etag").asString()).isEqualTo("\"e1\"");
    assertThat(done.content().getFirst().text()).isEqualTo("Created o-1 in app/orders (partition [\"c-42\"]) · 2.50 "
        + "RU.");
    assertThat(fake.to("DELETE")).isEmpty();
  }

  @Test
  void eachOperationIsItsOwnRequest() throws Exception {
    fake.on("POST " + DOCS, r -> FakeCosmos.json(200, "{\"id\": \"o-1\"}"));
    fake.on("PUT " + DOCS + "/o-1", r -> FakeCosmos.json(200, "{\"id\": \"o-1\"}"));
    fake.on("PATCH " + DOCS + "/o-1", r -> FakeCosmos.json(200, "{\"id\": \"o-1\", \"status\": \"paid\"}"));
    fake.on("DELETE " + DOCS + "/o-1", r -> new MockResponse.Builder().code(204).addHeader("x-ms-request-charge",
        "5.7").build());
    ConnectionConfig cfg = config(null);
    String base = "{\"database\": \"app\", \"container\": \"orders\", \"partitionKey\": \"c-1\", ";

    ActionPreview upsert = cosmos.describeAction(call("cosmosdb_execute", base + "\"operation\": \"upsert\", "
        + "\"item\": {\"id\": \"o-1\", \"customerId\": \"c-1\"}, \"ifMatch\": \"e2\"}"), cfg);
    assertThat(upsert.text()).contains("x-ms-documentdb-is-upsert: True\nIf-Match: \"e2\"\n");
    assertThat(upsert.warnings()).noneMatch(w -> w.contains("No ifMatch"));
    ToolResult up = cosmos.execute(call("cosmosdb_execute", "{}"), cfg, secrets, TestCtx.of().approved(upsert.payload()));
    assertThat(fake.to("POST " + DOCS).getLast().getHeaders().get("x-ms-documentdb-is-upsert")).isEqualTo("True");
    assertThat(fake.to("POST " + DOCS).getLast().getHeaders().get("If-Match")).isEqualTo("\"e2\"");
    assertThat(up.content().getFirst().text()).startsWith("Replaced (upsert) o-1");

    ActionPreview replace = cosmos.describeAction(call("cosmosdb_execute", base + "\"operation\": \"replace\", "
        + "\"id\": \"o-1\", \"item\": {\"id\": \"o-1\", \"customerId\": \"c-1\", \"total\": 9007199254740993}}"), cfg);
    assertThat(replace.text()).startsWith("PUT https://acme.documents.azure.com/dbs/app/colls/orders/docs/o-1\n");
    assertThat(replace.warnings()).anyMatch(w -> w.contains("No ifMatch")).anyMatch(w -> w.contains("2^53"));
    cosmos.execute(call("cosmosdb_execute", "{}"), cfg, secrets, TestCtx.of().approved(replace.payload()));
    assertThat(fake.to("PUT " + DOCS + "/o-1")).hasSize(1);

    ActionPreview patch = cosmos.describeAction(call("cosmosdb_execute", base + "\"operation\": \"patch\", \"id\": "
        + "\"o-1\", \"condition\": \"FROM c WHERE c.status = 'open'\", \"operations\": [{\"op\": \"set\", \"path\": "
        + "\"/status\", \"value\": \"paid\"}, {\"op\": \"incr\", \"path\": \"/version\", \"value\": 1}, {\"op\": "
        + "\"move\", \"from\": \"/draft\", \"path\": \"/final\"}, {\"op\": \"remove\", \"path\": \"/tmp\"}]}"), cfg);
    assertThat(patch.text()).contains("Content-Type: application/json_patch+json");
    cosmos.execute(call("cosmosdb_execute", "{}"), cfg, secrets, TestCtx.of().approved(patch.payload()));
    RecordedRequest p = fake.to("PATCH " + DOCS + "/o-1").getFirst();
    assertThat(p.getHeaders().get("Content-Type")).startsWith("application/json_patch+json");
    assertThat(FakeCosmos.body(p).toString()).isEqualTo("{\"condition\":\"FROM c WHERE c.status = 'open'\","
        + "\"operations\":[{\"op\":\"set\",\"path\":\"/status\",\"value\":\"paid\"},{\"op\":\"incr\",\"path\":"
        + "\"/version\",\"value\":1},{\"op\":\"move\",\"path\":\"/final\",\"from\":\"/draft\"},{\"op\":\"remove\","
        + "\"path\":\"/tmp\"}]}");

    ActionPreview delete = cosmos.describeAction(call("cosmosdb_execute", base + "\"operation\": \"delete\", \"id\": "
        + "\"o-1\"}"), cfg);
    assertThat(delete.text()).isEqualTo("DELETE https://acme.documents.azure.com/dbs/app/colls/orders/docs/o-1\n"
        + "x-ms-documentdb-partitionkey: [\"c-1\"]\n");
    ToolResult deleted = cosmos.execute(call("cosmosdb_execute", "{}"), cfg, secrets,
        TestCtx.of().approved(delete.payload()));
    assertThat(deleted.content().getFirst().text()).isEqualTo("Deleted o-1 in app/orders (partition [\"c-1\"]) · "
        + "5.70 RU.");
    assertThat(fake.refused).isEmpty();
  }

  @Test
  void aChangeOutsideTheRulesIsRefusedBeforeAnyRequest() {
    ConnectionConfig cfg = config("\"advanced\": {\"maxItemKb\": 1}");
    String base = "{\"database\": \"app\", \"container\": \"orders\", \"partitionKey\": \"c-1\", ";
    Map<String, String> refusals = Map.ofEntries(
        Map.entry("\"operation\": \"create\", \"item\": {\"id\": \"o-1\"}, \"ifMatch\": \"e\"}", "create takes none"),
        Map.entry("\"operation\": \"create\", \"item\": {\"customerId\": \"c-1\"}}", "needs its id"),
        Map.entry("\"operation\": \"create\", \"item\": {\"id\": 7}}", "needs its id"),
        Map.entry("\"operation\": \"replace\", \"id\": \"o-2\", \"item\": {\"id\": \"o-1\"}}", "differ"),
        Map.entry("\"operation\": \"patch\", \"id\": \"o-1\", \"item\": {\"id\": \"o-1\"}}", "not item"),
        Map.entry("\"operation\": \"patch\", \"id\": \"o-1\", \"operations\": [{\"op\": \"set\", \"path\": \"/id\", "
            + "\"value\": \"o-9\"}]}", "can't change"),
        Map.entry("\"operation\": \"patch\", \"id\": \"o-1\", \"operations\": [{\"op\": \"set\", \"path\": \"/_etag\", "
            + "\"value\": \"x\"}]}", "system property"),
        Map.entry("\"operation\": \"patch\", \"id\": \"o-1\", \"operations\": [{\"op\": \"remove\", \"path\": \"/a\", "
            + "\"value\": 1}]}", "takes no value"),
        Map.entry("\"operation\": \"patch\", \"id\": \"o-1\", \"operations\": [{\"op\": \"incr\", \"path\": \"/a\", "
            + "\"value\": \"1\"}]}", "adds a number"),
        Map.entry("\"operation\": \"patch\", \"id\": \"o-1\", \"operations\": [{\"op\": \"move\", \"path\": \"/a\"}]}",
            "needs from"),
        Map.entry("\"operation\": \"patch\", \"id\": \"o-1\", \"operations\": [{\"op\": \"test\", \"path\": \"/a\"}]}",
            "op is add"),
        Map.entry("\"operation\": \"patch\", \"id\": \"o-1\", \"condition\": \"WHERE c.a = 1\", \"operations\": "
            + "[{\"op\": \"remove\", \"path\": \"/a\"}]}", "starts with FROM"),
        Map.entry("\"operation\": \"patch\", \"id\": \"o-1\", \"operations\": []}", "needs operations"),
        Map.entry("\"operation\": \"delete\", \"id\": \"o-1\", \"item\": {\"id\": \"o-1\"}}", "only the id"),
        Map.entry("\"operation\": \"delete\"}", "needs id"),
        Map.entry("\"operation\": \"truncate\", \"id\": \"o-1\"}", "operation is"),
        Map.entry("\"operation\": \"create\", \"item\": {\"id\": \"o-1\", \"blob\": \"" + "z".repeat(2_000) + "\"}}",
            "larger than"));
    refusals.forEach((args, why) -> {
      Classification c = cosmos.classify(call("cosmosdb_execute", base + args), cfg, policy(Env.DEV));
      assertThat(c.kind()).as(args).isEqualTo(Classification.Kind.DENY);
      assertThat(c.reason()).as(args).contains(why);
    });
    StringBuilder eleven = new StringBuilder("[");
    for (int i = 0; i < 11; i++) {
      eleven.append(i == 0 ? "" : ", ").append("{\"op\": \"set\", \"path\": \"/a").append(i).append("\", \"value\": 1}");
    }
    assertThat(cosmos.classify(call("cosmosdb_execute", base + "\"operation\": \"patch\", \"id\": \"o-1\", "
        + "\"operations\": " + eleven + "]}"), cfg, policy(Env.DEV)).reason()).contains("at most 10");
    assertThat(cosmos.classify(call("cosmosdb_execute", base + "\"operation\": \"create\", \"item\": {\"id\": \"o-1\", "
        + "\"blob\": \"" + "z".repeat(2_000) + "\"}}"), cfg, policy(Env.DEV)).ruleId()).isEqualTo("dev.cosmosdb.too-large");
    assertThat(fake.requests).isEmpty();
  }

  @Test
  void aReadOnlyKeyIsOfferedNoChange() {
    ConnectionConfig ro = config(Env.DEV, KEY_RO, null);
    assertThat(cosmos.supportsTool("cosmosdb_execute", ro)).isFalse();
    assertThat(cosmos.supportsTool("cosmosdb_query", ro)).isTrue();
    assertThat(cosmos.previewTools(ro)).extracting(ToolDefinition::name).doesNotContain("cosmosdb_execute");
    assertThat(cosmos.supportsTool("cosmosdb_execute", config(null))).isTrue();
    assertThat(cosmos.supportsTool("cosmosdb_execute", config(Env.DEV, ENTRA, null))).isTrue();
    Classification c = cosmos.classify(call("cosmosdb_execute", "{\"database\": \"app\", \"container\": \"orders\", "
        + "\"operation\": \"delete\", \"id\": \"o-1\", \"partitionKey\": \"c-1\"}"), ro, policy(Env.DEV));
    assertThat(c.ruleId()).isEqualTo("dev.cosmosdb.read-only-key");
    assertThat(c.reason()).contains("can't write");
  }

  @Test
  void anApprovedChangeIsReadBackAndNothingElseRuns() {
    ConnectionConfig cfg = config("\"databases\": [\"app\"], \"containers\": [\"orders\"]");
    ObjectNode good = (ObjectNode) cosmos.describeAction(call("cosmosdb_execute", "{\"database\": \"app\", "
        + "\"container\": \"orders\", \"operation\": \"replace\", \"id\": \"o-1\", \"partitionKey\": \"c-1\", "
        + "\"item\": {\"id\": \"o-1\", \"customerId\": \"c-1\", \"total\": 3}}"), cfg).payload();
    List<ObjectNode> tampered = List.of(
        with(good, "container", "secrets"),
        with(good, "database", "hr"),
        with(good, "operation", "delete"),
        with(good, "id", "o-2"),
        with(good, "body", good.path("body").asString().replace("3", "4 ")),
        with(good, "body", "{\"id\": \"o-1\", \"total\": 4}"),
        with(good, "body", good.path("body").asString() + " {}"),
        with(good, "ifMatch", "e"),
        with(good, "extra", "x"));
    for (ObjectNode t : tampered) {
      assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_execute", "{}"), cfg, secrets,
          TestCtx.of().approved(t))).as(t.toString()).isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    ObjectNode pk = good.deepCopy();
    pk.putObject("partitionKey").put("c", "c-1");
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_execute", "{}"), cfg, secrets, TestCtx.of().approved(pk)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_execute", "{}"), config(Env.DEV, KEY_RO, null), secrets,
        TestCtx.of().approved(good))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("can't write"));
    assertThat(fake.requests).isEmpty();
  }

  private static ObjectNode with(ObjectNode tree, String key, String value) {
    ObjectNode copy = tree.deepCopy();
    copy.put(key, value);
    return copy;
  }

  @Test
  void aLostAnswerToAChangeIsUnknownAndRefusalsSayWhatHappened() {
    ConnectionConfig cfg = config(null);
    JsonNode payload = cosmos.describeAction(call("cosmosdb_execute", "{\"database\": \"app\", \"container\": "
        + "\"orders\", \"operation\": \"create\", \"partitionKey\": \"c-1\", \"item\": {\"id\": \"o-1\", "
        + "\"customerId\": \"c-1\"}}"), cfg).payload();
    Map<MockResponse, String> answers = new java.util.LinkedHashMap<>();
    answers.put(FakeCosmos.json(503, "{\"code\": \"ServiceUnavailable\", \"message\": \"Service is currently "
        + "unavailable.\"}"), "OUTCOME_UNKNOWN");
    answers.put(new MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection.INSTANCE).build(),
        "OUTCOME_UNKNOWN");
    answers.put(FakeCosmos.json(409, "{\"code\": \"Conflict\", \"message\": \"Entity with the specified id already "
        + "exists in the system.\"}"), "already exists");
    answers.put(FakeCosmos.json(412, "{\"code\": \"PreconditionFailed\", \"message\": \"Operation cannot be "
        + "performed because one of the specified precondition is not met.\"}"), "changed since");
    answers.put(FakeCosmos.json(429, "{\"code\": \"429\", \"message\": \"Request rate is large.\"}",
        "x-ms-retry-after-ms", "1200"), "after 2 s");
    answers.put(FakeCosmos.json(403, "{\"code\": \"Forbidden\", \"message\": \"Request blocked by Auth acme : "
        + "write operations aren't allowed with a read-only key.\"}"), "This key can't write");
    answers.put(FakeCosmos.json(400, "{\"code\": \"BadRequest\", \"message\": \"PartitionKey extracted from document "
        + "doesn't match the one specified in the header. Learn more: https://aka.ms/CosmosDB/sql/errors/wrong-pk-"
        + "value\\r\\nActivityId: 1\"}", "x-ms-substatus", "1001"), "partitionKey doesn't match");
    int sent = 0;
    for (Map.Entry<MockResponse, String> e : answers.entrySet()) {
      fake.on("POST " + DOCS, r -> e.getKey());
      assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_execute", "{}"), cfg, secrets,
          TestCtx.of().approved(payload))).as(e.getValue()).isInstanceOfSatisfying(ConnectorException.class, ex -> {
            if (e.getValue().equals("OUTCOME_UNKNOWN")) {
              assertThat(ex.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
            } else {
              assertThat(ex.kind()).isNotEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN);
              assertThat(ex.getMessage()).contains(e.getValue()).contains("Nothing changed");
            }
          });
      sent++;
      assertThat(fake.to("POST " + DOCS)).as("never retried").hasSize(sent);
    }
    // With Microsoft Entra ID, a refusal of the data-plane roles names the role to assign.
    fake.on("POST " + DOCS, r -> FakeCosmos.json(403, "{\"code\": \"Forbidden\", \"message\": \"Request blocked by "
        + "Auth acme : principal does not have required RBAC permissions\"}", "x-ms-substatus", "5301"));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_execute", "{}"), config(Env.DEV, ENTRA, null), secrets,
        TestCtx.of().approved(payload))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("Data Contributor"));
  }

  @Test
  void prepareChecksThePartitionKeyAgainstTheContainer() throws Exception {
    fake.on("GET /dbs/app/colls/orders", r -> FakeCosmos.json(200, "{\"id\": \"orders\", \"partitionKey\": "
        + "{\"paths\": [\"/customer/id\"], \"kind\": \"Hash\"}}"));
    ConnectionConfig cfg = config(null);
    String base = "{\"database\": \"app\", \"container\": \"orders\", ";
    PreparedAction ok = cosmos.prepare(call("cosmosdb_execute", base + "\"operation\": \"create\", \"partitionKey\": "
        + "\"c-1\", \"item\": {\"id\": \"o-1\", \"customer\": {\"id\": \"c-1\"}}}"), cfg, secrets, TestCtx.of());
    assertThat(ok.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(ok.preparedState().toString()).isEqualTo("{\"partitionKeyPaths\":[\"/customer/id\"]}");
    assertThat(ok.preview().payload().path("operation").asString()).isEqualTo("create");
    PreparedAction mismatch = cosmos.prepare(call("cosmosdb_execute", base + "\"operation\": \"upsert\", "
        + "\"partitionKey\": \"c-2\", \"item\": {\"id\": \"o-1\", \"customer\": {\"id\": \"c-1\"}}}"), cfg, secrets,
        TestCtx.of());
    assertThat(mismatch.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(mismatch.message()).contains("isn't the item's own value at /customer/id ([\"c-1\"])");
    PreparedAction missing = cosmos.prepare(call("cosmosdb_execute", base + "\"operation\": \"create\", "
        + "\"partitionKey\": \"c-2\", \"item\": {\"id\": \"o-1\"}}"), cfg, secrets, TestCtx.of());
    assertThat(missing.message()).contains("has no value at the container's partition key path");
    PreparedAction movesKey = cosmos.prepare(call("cosmosdb_execute", base + "\"operation\": \"patch\", \"id\": "
        + "\"o-1\", \"partitionKey\": \"c-1\", \"operations\": [{\"op\": \"set\", \"path\": \"/customer\", \"value\": "
        + "{}}]}"), cfg, secrets, TestCtx.of());
    assertThat(movesKey.message()).contains("can't change the partition key");
    PreparedAction levels = cosmos.prepare(call("cosmosdb_execute", base + "\"operation\": \"delete\", \"id\": "
        + "\"o-1\", \"partitionKey\": [\"a\", \"b\"]}"), cfg, secrets, TestCtx.of());
    assertThat(levels.message()).contains("partitionKey holds one value");
    PreparedAction bad = cosmos.prepare(call("cosmosdb_execute", base + "\"operation\": \"delete\"}"), cfg, secrets,
        TestCtx.of());
    assertThat(bad.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    fake.on("GET /dbs/app/colls/nope", r -> FakeCosmos.json(404, "{\"code\": \"NotFound\", \"message\": \"no\"}"));
    PreparedAction gone = cosmos.prepare(call("cosmosdb_execute", "{\"database\": \"app\", \"container\": \"nope\", "
        + "\"operation\": \"delete\", \"id\": \"o-1\", \"partitionKey\": \"c-1\"}"), cfg, secrets, TestCtx.of());
    assertThat(gone.message()).contains("doesn't exist");
    assertThat(fake.refused).isEmpty();
  }

  // ── Microsoft Entra ID, Test connection, errors ─────────────────────────────────────────────────────────────

  @Test
  void anEntraIdTokenIsAskedForTheAccountKeptAndTainted() throws Exception {
    fake.on("POST " + DOCS, r -> page(null, "{\"id\": \"o-1\"}"));
    ConnectionConfig cfg = config(Env.DEV, ENTRA, null);
    TestCtx ctx = TestCtx.of();
    String q = "{\"database\": \"app\", \"container\": \"orders\", \"query\": \"SELECT * FROM c\"}";
    cosmos.execute(call("cosmosdb_query", q), cfg, secrets, ctx);
    cosmos.execute(call("cosmosdb_query", q), cfg, secrets, ctx.next());
    assertThat(fake.tokenRequests.get()).as("kept for the connection").isEqualTo(1);
    RecordedRequest token = fake.requests.stream().filter(r -> r.getUrl().encodedPath().endsWith("/token"))
        .findFirst().orElseThrow();
    assertThat(token.getUrl().encodedPath()).isEqualTo("/" + FakeCosmos.TENANT + "/oauth2/v2.0/token");
    String form = token.getBody().utf8();
    assertThat(form).contains("grant_type=client_credentials", "client_id=" + FakeCosmos.CLIENT,
        "scope=https%3A%2F%2Facme.documents.azure.com%2F.default", "client_secret=entra-test-secret-0001");
    RecordedRequest sent = fake.to("POST " + DOCS).getFirst();
    assertThat(URLDecoder.decode(sent.getHeaders().get("authorization"), StandardCharsets.UTF_8))
        .isEqualTo("type=aad&ver=1.0&sig=" + fake.token);
    assertThat(ctx.taints).contains(fake.token, sent.getHeaders().get("authorization"), "entra-test-secret-0001");
    assertThat(fake.refused).isEmpty();

    // A new version of the connection (a rotated secret) signs in again.
    ConnectionConfig rotated = new ConnectionConfig(cfg.id(), cfg.name(), cfg.type(), cfg.env(), 2, cfg.config(),
        cfg.secretFields(), null);
    cosmos.execute(call("cosmosdb_query", q), rotated, secrets, TestCtx.of());
    assertThat(fake.tokenRequests.get()).isEqualTo(2);
    cosmos.onConnectionChanged(cfg.id());
    cosmos.execute(call("cosmosdb_query", q), rotated, secrets, TestCtx.of());
    assertThat(fake.tokenRequests.get()).isEqualTo(3);

    // A tenant without the account's own resource gets Cosmos DB's common one (AADSTS500011).
    cosmos.onConnectionChanged(cfg.id());
    AtomicInteger asks = new AtomicInteger();
    fake.tokenAnswer = r -> asks.incrementAndGet() == 1 ? FakeCosmos.json(400, "{\"error\": \"invalid_resource\", "
        + "\"error_description\": \"AADSTS500011: The resource principal named https://acme.documents.azure.com was "
        + "not found in the tenant.\"}") : FakeCosmos.json(200, "{\"access_token\": \"" + fake.token + "\", "
        + "\"expires_in\": 3599}");
    cosmos.execute(call("cosmosdb_query", q), cfg, secrets, TestCtx.of());
    assertThat(fake.requests.stream().filter(r -> r.getUrl().encodedPath().endsWith("/token")).toList().getLast()
        .getBody().utf8()).contains("scope=https%3A%2F%2Fcosmos.azure.com%2F.default");

    // A wrong secret is reported without the secret.
    cosmos.onConnectionChanged(cfg.id());
    fake.tokenAnswer = r -> FakeCosmos.json(401, "{\"error\": \"invalid_client\", \"error_description\": "
        + "\"AADSTS7000215: Invalid client secret provided. Ensure the secret being sent in the request is the client "
        + "secret value, not the client secret ID.\\r\\nTrace ID: 1\"}");
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", q), cfg, secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.getMessage()).contains("AADSTS7000215").contains("Value (not its Secret ID)")
              .doesNotContain("entra-test-secret-0001");
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
        });
  }

  @Test
  void testConnectionReportsTheAccountAndWarnsAboutAReadWriteKeyOutsideDev() throws Exception {
    fake.on("GET /", r -> r.getUrl().encodedPath().equals("/") ? FakeCosmos.json(200, "{\"id\": \"acme\", "
        + "\"writableLocations\": [{\"name\": \"West Europe\"}], \"readableLocations\": [{\"name\": \"West Europe\"}, "
        + "{\"name\": \"North Europe\"}], \"userConsistencyPolicy\": {\"defaultConsistencyLevel\": \"Session\"}}",
        "x-ms-gatewayversion", "version=2.14.0") : FakeCosmos.json(404, "{}"));
    fake.on("GET /dbs", r -> FakeCosmos.json(200, "{\"Databases\": [{\"id\": \"app\"}, {\"id\": \"hr\"}]}"));
    TestResult prod = cosmos.test(config(Env.PROD, KEY_RW, "\"databases\": [\"app\"]"), secrets,
        TestCtx.of().env(Env.PROD));
    assertThat(prod.ok()).isTrue();
    assertThat(prod.serverVersion()).isEqualTo("Azure Cosmos DB for NoSQL · acme · gateway 2.14.0");
    assertThat(prod.identity()).isEqualTo("Read-write key of acme");
    assertThat(prod.permissions().level()).isEqualTo(TestResult.Level.ADMIN);
    assertThat(prod.checks()).extracting(TestResult.Check::name).containsExactly("account", "databases");
    assertThat(prod.checks().get(0).detail()).isEqualTo("acme · writes in West Europe, reads in West Europe, North "
        + "Europe · default consistency Session");
    assertThat(prod.checks().get(1).detail()).isEqualTo("1 database agents may use: app");
    assertThat(prod.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("cosmosdb.read-write-key");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
    });
    assertThat(prod.metadataUpdate().path("defaultConsistency").asString()).isEqualTo("Session");

    TestResult dev = cosmos.test(config(Env.DEV, KEY_RW, "\"readConsistency\": \"Session\""), secrets, TestCtx.of());
    assertThat(dev.hints()).filteredOn(h -> h.code().equals("cosmosdb.read-write-key"))
        .allSatisfy(h -> assertThat(h.severity()).isEqualTo(TestResult.Severity.INFO));
    TestResult ro = cosmos.test(config(Env.STAGE, KEY_RO, null), secrets, TestCtx.of());
    assertThat(ro.identity()).isEqualTo("Read-only key of acme");
    assertThat(ro.permissions().level()).isEqualTo(TestResult.Level.READ);
    assertThat(ro.hints()).extracting(TestResult.Hint::code).contains("cosmosdb.read-only-key")
        .doesNotContain("cosmosdb.read-write-key");

    fake.on("GET /", r -> r.getUrl().encodedPath().equals("/") ? FakeCosmos.json(200, "{\"id\": \"acme\", "
        + "\"userConsistencyPolicy\": {\"defaultConsistencyLevel\": \"Eventual\"}}") : FakeCosmos.json(404, "{}"));
    TestResult strong = cosmos.test(config(Env.DEV, KEY_RO, "\"readConsistency\": \"Session\""), secrets,
        TestCtx.of());
    assertThat(strong.hints()).extracting(TestResult.Hint::code).contains("cosmosdb.consistency");

    // The emulator: a key that isn't its well-known one doesn't belong to an address on this computer.
    ConnectionConfig emulator = new ConnectionConfig("c-emu", "emu", "cosmosdb", Env.DEV, 1, TestCtx.JSON.readTree(
        "{\"endpoint\": \"http://127.0.0.1:8081\", \"auth\": " + KEY_RW + "}"), Set.of(), null);
    TestResult emu = cosmos.test(emulator, secrets, TestCtx.of());
    assertThat(emu.hints()).extracting(TestResult.Hint::code).contains("cosmosdb.emulator", "cosmosdb.emulator-key")
        .doesNotContain("cosmosdb.read-write-key");
    // The connector knows the emulator's key by its digest: the one of the key the test services use.
    String envKey = java.nio.file.Files.readAllLines(java.nio.file.Path.of("../../test-services/.env")).stream()
        .filter(l -> l.startsWith("GW_COSMOSDB_KEY=")).map(l -> l.substring("GW_COSMOSDB_KEY=".length()))
        .findFirst().orElseThrow();
    assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(envKey.getBytes(StandardCharsets.UTF_8)))).isEqualTo(CosmosDbConnector.EMULATOR_KEY_SHA256);

    TestResult entra = cosmos.test(config(Env.DEV, ENTRA, null), secrets, TestCtx.of());
    assertThat(entra.ok()).isTrue();
    assertThat(entra.identity()).isEqualTo("Service principal " + FakeCosmos.CLIENT + " (object ID " + FakeCosmos.OID
        + ")");
    assertThat(entra.checks()).extracting(TestResult.Check::name).containsExactly("sign-in", "account", "databases");
    assertThat(entra.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("cosmosdb.rbac");
      assertThat(h.text()).contains("Built-in Data Reader").contains("--principal-id " + FakeCosmos.OID);
    });
    assertThat(fake.refused).isEmpty();
  }

  @Test
  void testConnectionAndHealthSayWhatFailed() {
    TestResult wrongKey = cosmos.test(config(null), new TestSecrets().with(CosmosSettings.ACCOUNT_KEY,
        java.util.Base64.getEncoder().encodeToString("another-key-of-sixty-four-bytes-for-the-fake-account-0123456789xx"
            .getBytes(StandardCharsets.US_ASCII))), TestCtx.of());
    assertThat(wrongKey.ok()).isFalse();
    assertThat(wrongKey.error().code()).isEqualTo("cosmosdb.refused");
    assertThat(wrongKey.error().message()).contains("(401)").contains("Keys page").doesNotContain("ActivityId");
    TestResult notAKey = cosmos.test(config(null), new TestSecrets().with(CosmosSettings.ACCOUNT_KEY, "not base64!"),
        TestCtx.of());
    assertThat(notAKey.error().message()).contains("isn't one");
    TestResult noKey = cosmos.test(config(null), new TestSecrets(), TestCtx.of());
    assertThat(noKey.error().message()).contains("isn't available");
    assertThat(cosmos.test(config(Env.DEV, "{\"method\": \"key\"}", null), secrets, TestCtx.of()).error().message())
        .contains("Say which key");

    fake.on("GET /", r -> new MockResponse.Builder().onResponseStart(SocketEffect.Stall.INSTANCE).build());
    HealthResult stalled = cosmos.health(config(null), secrets, TestCtx.of().timeout(Duration.ofSeconds(1)));
    assertThat(stalled.state()).isEqualTo(HealthResult.State.UNREACHABLE);
    fake.on("GET /", r -> FakeCosmos.json(200, "{\"id\": \"acme\"}"));
    assertThat(cosmos.health(config(null), secrets, TestCtx.of()).state()).isNotEqualTo(HealthResult.State.UNREACHABLE);
  }

  @Test
  void cosmosDbsRefusalsSayWhatToDo() {
    String q = "{\"database\": \"app\", \"container\": \"orders\", \"query\": \"SELECT * FROM\"}";
    fake.on("POST " + DOCS, r -> FakeCosmos.json(400, "{\"code\": \"BadRequest\", \"message\": \"Message: "
        + "{\\\"errors\\\":[{\\\"severity\\\":\\\"Error\\\",\\\"location\\\":{\\\"start\\\":13,\\\"end\\\":13},"
        + "\\\"code\\\":\\\"SC1002\\\",\\\"message\\\":\\\"Syntax error, unexpected end-of-file.\\\"}]}\\r\\n"
        + "ActivityId: 2, Microsoft.Azure.Documents.Common/2.14.0\"}"));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", q), config(null), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).isEqualTo("Cosmos DB refused the query on app/orders (400): Syntax error, "
              + "unexpected end-of-file (at character 13).");
        });
    fake.on("POST " + DOCS, r -> FakeCosmos.json(429, "{\"code\": \"429\", \"message\": \"Request rate is large\"}",
        "x-ms-retry-after-ms", "250"));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", q), config(null), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("throttled")
            .contains("after 250 ms"));
    fake.on("POST " + DOCS, r -> FakeCosmos.json(403, "{\"code\": \"Forbidden\", \"message\": \"Request originated "
        + "from IP 203.0.113.7 through public internet. This is blocked by your Cosmos DB account firewall settings.\"}"));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", q), config(null), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).startsWith("The account's "
            + "firewall blocks this computer"));
    fake.on("POST " + DOCS, r -> FakeCosmos.json(400, "{\"code\": \"BadRequest\", \"message\": \"The requested "
        + "consistency level is stronger than the account's default consistency level\"}"));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", q), config("\"readConsistency\": \"Session\""),
        secrets, TestCtx.of())).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.getMessage()).contains("can't be stronger than the account's default"));
    fake.on("POST " + DOCS, r -> new MockResponse.Builder().onResponseStart(SocketEffect.Stall.INSTANCE).build());
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", q), config(null), secrets,
        TestCtx.of().timeout(Duration.ofSeconds(1)))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    fake.on("POST " + DOCS, r -> FakeCosmos.json(200, "not json"));
    assertThatThrownBy(() -> cosmos.execute(call("cosmosdb_query", q), config(null), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage()).contains("isn't JSON"));
  }

  @Test
  void theConnectionDescribesItself() {
    JsonNode d = cosmos.describe(config(Env.DEV, KEY_RO, "\"databases\": [\"app\"], \"containers\": [\"orders\"]"),
        secrets, TestCtx.of(), "orders").orElseThrow();
    assertThat(d.path("endpoint").asString()).isEqualTo("https://acme.documents.azure.com");
    assertThat(d.path("auth").asString()).isEqualTo("Account key (read-only)");
    assertThat(d.path("databases").toString()).isEqualTo("[\"app\"]");
    assertThat(d.path("changes").asString()).contains("not offered with a read-only key");
    assertThat(d.path("container").asString()).contains("cosmosdb_describe_container");
    assertThat(fake.requests).isEmpty();
  }
}
