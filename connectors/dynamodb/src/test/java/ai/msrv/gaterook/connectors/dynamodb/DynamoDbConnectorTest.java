package ai.msrv.gaterook.connectors.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The DynamoDB connector against a fake of DynamoDB's JSON API and STS: what is sent, signed and shown. */
class DynamoDbConnectorTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ROLE = "arn:aws:iam::412398765012:role/gw-dynamodb";

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Function<RecordedRequest, MockResponse>> handlers = new ConcurrentHashMap<>();
  private CloudCredentials cloud;
  private DynamoDbConnector dynamo;
  private final TestSecrets secrets = new TestSecrets().with("/credentials/secretAccessKey", "test-only-secret-key");

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        String op = operation(request);
        Function<RecordedRequest, MockResponse> h = handlers.get(op);
        if (h != null) {
          return h.apply(request);
        }
        return switch (op) {
          case "AssumeRole" -> xml("<AssumeRoleResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\">"
              + "<AssumeRoleResult><Credentials><AccessKeyId>ASIATESTSESSION00001</AccessKeyId><SecretAccessKey>"
              + "session-secret</SecretAccessKey><SessionToken>session-token</SessionToken><Expiration>"
              + Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "</Expiration>"
              + "</Credentials><AssumedRoleUser><AssumedRoleId>AROATEST:gaterook</AssumedRoleId><Arn>"
              + "arn:aws:sts::412398765012:assumed-role/gw-dynamodb/gaterook</Arn></AssumedRoleUser>"
              + "</AssumeRoleResult><ResponseMetadata><RequestId>1</RequestId></ResponseMetadata>"
              + "</AssumeRoleResponse>");
          case "GetCallerIdentity" -> xml("<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/"
              + "2011-06-15/\"><GetCallerIdentityResult><Arn>" + (String.valueOf(request.getHeaders()
              .get("Authorization")).contains("Credential=ASIA")
              ? "arn:aws:sts::412398765012:assumed-role/gw-dynamodb/gaterook" : "arn:aws:iam::412398765012:user/reader")
              + "</Arn><UserId>"
              + "AIDATEST</UserId><Account>412398765012</Account></GetCallerIdentityResult><ResponseMetadata>"
              + "<RequestId>2</RequestId></ResponseMetadata></GetCallerIdentityResponse>");
          default -> new MockResponse.Builder().code(404).body("no fake for " + op).build();
        };
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    URI base = URI.create("http://127.0.0.1:" + server.getPort());
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, base, null, null,
        Map.of("aws.dynamodb", base)), Clock.systemUTC());
    dynamo = new DynamoDbConnector(cloud);
  }

  @AfterEach
  void stop() throws IOException {
    dynamo.close();
    cloud.close();
    server.close();
  }

  private static String operation(RecordedRequest r) {
    String target = r.getHeaders().get("X-Amz-Target");
    if (target != null) {
      return target.substring(target.indexOf('.') + 1);
    }
    String body = r.getBody() == null ? "" : r.getBody().utf8();
    int at = body.indexOf("Action=");
    if (at < 0) {
      return r.getUrl().encodedPath();
    }
    int end = body.indexOf('&', at);
    return body.substring(at + 7, end < 0 ? body.length() : end);
  }

  private List<RecordedRequest> to(String operation) {
    return requests.stream().filter(r -> operation(r).equals(operation)).toList();
  }

  private static MockResponse xml(String body) {
    return new MockResponse.Builder().code(200).addHeader("Content-Type", "text/xml").body(body).build();
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", DynamoApi.CONTENT_TYPE).body(body).build();
  }

  private static JsonNode body(RecordedRequest r) {
    return JSON.readTree(r.getBody().utf8());
  }

  private ConnectionConfig config(Env env, String... tables) {
    return config(env, false, tables);
  }

  private ConnectionConfig config(Env env, boolean role, String... tables) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "eu-central-1");
    ObjectNode credentials = c.putObject("credentials").put("source", "accessKey")
        .put("accessKeyId", "AKIAIOSFODNN7EXAMPLE");
    if (role) {
      credentials.put("roleArn", ROLE).put("externalId", "gw-ext-0123456789ab");
    }
    if (tables.length > 0) {
      var list = c.putArray("tables");
      for (String t : tables) {
        list.add(t);
      }
    }
    return new ConnectionConfig("conn-ddb-" + env.id(), env.id() + "-ddb", "dynamodb", env, 1, c,
        Set.of("/credentials/secretAccessKey"), null);
  }

  private static PolicyView policy(Env env) {
    return new PolicyView(env, env.id() + ".dynamodb", JsonNodeFactory.instance.objectNode(),
        env == Env.PROD ? TestCtx.prod() : TestCtx.dev());
  }

  private static ToolCall call(String tool, String args) {
    return new ToolCall("tc_" + Long.toHexString(System.nanoTime()), tool, "dev-ddb", (ObjectNode) JSON.readTree(args),
        null);
  }

  private Classification classify(String tool, String args, String... tables) {
    return dynamo.classify(call(tool, args), config(Env.DEV, tables), policy(Env.DEV));
  }

  // ── Tools and routes ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void publishesItsToolsWithScanAskingAPersonOnProd() {
    assertThat(dynamo.manifest().id()).isEqualTo("dynamodb");
    assertThat(dynamo.manifest().group()).isEqualTo("Databases");
    assertThat(dynamo.manifest().tools()).extracting(ToolDefinition::name).containsExactly("dynamodb_list_tables",
        "dynamodb_describe_table", "dynamodb_query", "dynamodb_scan", "dynamodb_partiql", "dynamodb_execute");
    ToolDefinition scan = dynamo.manifest().tools().get(3);
    assertThat(scan.defaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
    assertThat(scan.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.READ_ONLY);
    ToolDefinition execute = dynamo.manifest().tools().getLast();
    assertThat(execute.mutating()).isTrue();
    assertThat(execute.readOnlyModeAllowed()).isFalse();
  }

  @Test
  void partiqlIsRoutedByWhatTheStatementDoes() {
    assertThat(classify("dynamodb_partiql", "{\"statement\": \"SELECT * FROM \\\"orders\\\" WHERE id = ?\", "
        + "\"parameters\": [{\"S\": \"o-1\"}]}").kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("dynamodb_partiql", "{\"statement\": \"DELETE FROM orders WHERE id = 'o-1'\"}").kind())
        .isEqualTo(Classification.Kind.DENY);
    Classification change = classify("dynamodb_execute", "{\"statement\": \"UPDATE \\\"orders\\\" SET status = "
        + "'paid' WHERE id = 'o-1'\"}");
    assertThat(change.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(change.facts().path("table").asString()).isEqualTo("orders");
    assertThat(classify("dynamodb_execute", "{\"statement\": \"SELECT * FROM orders\"}").kind())
        .isEqualTo(Classification.Kind.DENY);
    for (String statement : List.of("SELECT * FROM orders -- WHERE x", "SELECT * FROM orders /* c */",
        "SELECT * FROM orders; DELETE FROM orders WHERE id = 'x'", "SELECT * FROM orders WHERE id = 'open",
        "EXISTS(SELECT * FROM orders)", "SELECT * FROM \"a\"")) {
      assertThat(classify("dynamodb_partiql", JSON.writeValueAsString(Map.of("statement", statement))).kind())
          .as(statement).isEqualTo(Classification.Kind.DENY);
    }
    // The table of 'FROM' inside a string isn't the statement's.
    assertThat(PartiQl.read("SELECT * FROM \"orders\" WHERE note = 'FROM secrets'").table()).isEqualTo("orders");
    assertThat(PartiQl.read("SELECT * FROM \"orders\".\"by_status\" WHERE status = 'x'").table()).isEqualTo("orders");
    assertThat(PartiQl.read("INSERT INTO \"or\"\"ders\" VALUE {'id': 'x'}").table()).isEqualTo("or\"ders");
  }

  @Test
  void onlyListedTablesAndTypedValuesPass() {
    assertThat(classify("dynamodb_query", "{\"table\": \"orders\", \"keyCondition\": \"id = :id\", \"values\": "
        + "{\":id\": {\"S\": \"o-1\"}}}", "orders", "audit-*").kind()).isEqualTo(Classification.Kind.READ);
    assertThat(classify("dynamodb_query", "{\"table\": \"secrets\", \"keyCondition\": \"id = :id\"}", "orders",
        "audit-*").kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(classify("dynamodb_scan", "{\"table\": \"audit-2026\"}", "orders", "audit-*").kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(classify("dynamodb_partiql", "{\"statement\": \"SELECT * FROM secrets\"}", "orders").kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(classify("dynamodb_execute", "{\"statement\": \"DELETE FROM secrets WHERE id = 'x'\"}", "orders")
        .kind()).isEqualTo(Classification.Kind.DENY);
    for (String values : List.of("{\":id\": \"o-1\"}", "{\":id\": {\"S\": 1}}", "{\":id\": {\"N\": \"ten\"}}",
        "{\":id\": {\"X\": \"1\"}}", "{\":id\": {\"S\": \"a\", \"N\": \"1\"}}", "{\":id\": {\"NS\": []}}")) {
      assertThat(classify("dynamodb_query", "{\"table\": \"orders\", \"keyCondition\": \"id = :id\", \"values\": "
          + values + "}").kind()).as(values).isEqualTo(Classification.Kind.DENY);
    }
  }

  // ── What is sent and what comes back ───────────────────────────────────────────────────────────────────────

  @Test
  void aQueryIsSignedWithItsTargetAndPagesWithASealedCursor() throws Exception {
    handlers.put("Query", r -> body(r).has("ExclusiveStartKey")
        ? json(200, "{\"Items\": [{\"id\": {\"S\": \"o-3\"}}], \"Count\": 1, \"ScannedCount\": 1}")
        : json(200, "{\"Items\": [{\"id\": {\"S\": \"o-1\"}, \"total\": {\"N\": \"12.5\"}, \"qty\": {\"N\": \"3\"}, "
            + "\"big\": {\"N\": \"123456789012345678901\"}, \"tags\": {\"SS\": [\"a\", \"b\"]}, \"meta\": {\"M\": "
            + "{\"paid\": {\"BOOL\": true}, \"notes\": {\"L\": [{\"NULL\": true}]}}}, \"raw\": {\"B\": \"AP8Q\"}}, "
            + "{\"id\": {\"S\": \"o-2\"}}], \"Count\": 2, \"ScannedCount\": 2, \"LastEvaluatedKey\": {\"id\": {\"S\": "
            + "\"o-2\"}}, \"ConsumedCapacity\": {\"TableName\": \"orders\", \"CapacityUnits\": 0.5}}"));
    ConnectionConfig cfg = config(Env.DEV);
    String args = "{\"table\": \"orders\", \"keyCondition\": \"customer_id = :c\", \"values\": {\":c\": {\"S\": "
        + "\"c-42\"}}, \"limit\": 2}";
    TestCtx ctx = TestCtx.of();
    ToolResult first = dynamo.execute(call("dynamodb_query", args), cfg, secrets, ctx);

    RecordedRequest sent = to("Query").getFirst();
    assertThat(sent.getHeaders().get("Content-Type")).isEqualTo("application/x-amz-json-1.0");
    assertThat(sent.getHeaders().get("Authorization")).contains("AWS4-HMAC-SHA256", "/eu-central-1/dynamodb/",
        "x-amz-target");
    JsonNode req = body(sent);
    assertThat(req.path("TableName").asString()).isEqualTo("orders");
    assertThat(req.path("KeyConditionExpression").asString()).isEqualTo("customer_id = :c");
    assertThat(req.path("ExpressionAttributeValues").path(":c").path("S").asString()).isEqualTo("c-42");
    assertThat(req.path("Limit").asInt()).isEqualTo(2);
    assertThat(req.path("ReturnConsumedCapacity").asString()).isEqualTo("TOTAL");

    JsonNode out = first.structuredContent();
    JsonNode item = out.path("items").get(0);
    assertThat(item.path("id").asString()).isEqualTo("o-1");
    assertThat(item.path("total").asDouble()).isEqualTo(12.5);
    assertThat(item.path("qty").isIntegralNumber()).isTrue();
    assertThat(item.path("big").asString()).isEqualTo("123456789012345678901");
    assertThat(item.path("tags").toString()).isEqualTo("[\"a\",\"b\"]");
    assertThat(item.path("meta").path("paid").asBoolean()).isTrue();
    assertThat(item.path("meta").path("notes").get(0).isNull()).isTrue();
    assertThat(item.path("raw").asString()).isEqualTo("\\x00ff10");
    assertThat(out.path("consumedCapacity").asDouble()).isEqualTo(0.5);
    String cursor = out.path("nextCursor").asString();
    assertThat(cursor).startsWith("dd1.");
    assertThat(first.content().getFirst().text()).contains("2 items from orders", "More items");

    ObjectNode more = (ObjectNode) JSON.readTree(args);
    more.put("cursor", cursor);
    ToolResult second = dynamo.execute(call("dynamodb_query", more.toString()), cfg, secrets, ctx.next());
    assertThat(body(to("Query").getLast()).path("ExclusiveStartKey").path("id").path("S").asString()).isEqualTo("o-2");
    assertThat(second.structuredContent().path("items").get(0).path("id").asString()).isEqualTo("o-3");
    assertThat(second.structuredContent().has("nextCursor")).isFalse();

    // The cursor belongs to this request of this connection.
    ObjectNode other = (ObjectNode) JSON.readTree(args);
    other.put("keyCondition", "customer_id = :other").put("cursor", cursor);
    assertThatThrownBy(() -> dynamo.execute(call("dynamodb_query", other.toString()), cfg, secrets, ctx.next()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
  }

  @Test
  void aScanReadsOnWhileAFilterKeepsFewItems() throws Exception {
    handlers.put("Scan", r -> body(r).has("ExclusiveStartKey")
        ? json(200, "{\"Items\": [{\"id\": {\"S\": \"b\"}}, {\"id\": {\"S\": \"c\"}}], \"Count\": 2, "
            + "\"ScannedCount\": 40}")
        : json(200, "{\"Items\": [{\"id\": {\"S\": \"a\"}}], \"Count\": 1, \"ScannedCount\": 60, "
            + "\"LastEvaluatedKey\": {\"id\": {\"S\": \"z\"}}}"));
    ToolResult r = dynamo.execute(call("dynamodb_scan", "{\"table\": \"orders\", \"filter\": \"#s = :s\", \"names\": "
        + "{\"#s\": \"status\"}, \"values\": {\":s\": {\"S\": \"open\"}}, \"limit\": 3}"), config(Env.DEV), secrets,
        TestCtx.of());
    assertThat(to("Scan")).hasSize(2);
    assertThat(body(to("Scan").get(1)).path("Limit").asInt()).isEqualTo(2);
    assertThat(r.structuredContent().path("count").asInt()).isEqualTo(3);
    assertThat(r.structuredContent().path("scannedCount").asInt()).isEqualTo(100);
    assertThat(body(to("Scan").getFirst()).path("ExpressionAttributeNames").path("#s").asString()).isEqualTo("status");
  }

  @Test
  void withARoleEachCallAssumesItUnderTheSessionPolicyOfWhatItDoes() throws Exception {
    handlers.put("ExecuteStatement", r -> json(200, "{\"Items\": [], \"ConsumedCapacity\": {\"CapacityUnits\": 1}}"));
    ConnectionConfig cfg = config(Env.DEV, true, "orders");
    dynamo.execute(call("dynamodb_partiql", "{\"statement\": \"SELECT * FROM orders WHERE id = ?\", \"parameters\": "
        + "[{\"S\": \"o-1\"}]}"), cfg, secrets, TestCtx.of());
    String readPolicy = policyOf(to("AssumeRole").getLast());
    assertThat(readPolicy).contains("dynamodb:PartiQLSelect", "dynamodb:Query",
        "arn:aws:dynamodb:eu-central-1:*:table/orders").doesNotContain("PartiQLInsert", "PartiQLDelete");
    JsonNode sent = body(to("ExecuteStatement").getLast());
    assertThat(sent.path("Parameters").get(0).path("S").asString()).isEqualTo("o-1");

    String statement = "INSERT INTO \"orders\" VALUE {'id': ?, 'status': 'new'}";
    ObjectNode approved = JsonNodeFactory.instance.objectNode().put("statement", statement);
    approved.putArray("parameters").addObject().put("S", "o-9");
    // The call's own arguments say something else: what runs is what the person approved.
    ToolResult done = dynamo.execute(call("dynamodb_execute", "{\"statement\": \"DELETE FROM orders WHERE id = "
        + "'o-1'\"}"), cfg, secrets, TestCtx.of().approved(approved));
    assertThat(body(to("ExecuteStatement").getLast()).path("Statement").asString()).isEqualTo(statement);
    String writePolicy = policyOf(to("AssumeRole").getLast());
    assertThat(writePolicy).contains("dynamodb:PartiQLInsert", "table/orders").doesNotContain("dynamodb:Scan",
        "table/*");
    assertThat(done.structuredContent().path("committed").asBoolean()).isTrue();
    assertThat(done.content().getFirst().text()).isEqualTo("1 item inserted into orders.");
  }

  private static String policyOf(RecordedRequest assumeRole) {
    for (String pair : assumeRole.getBody().utf8().split("&")) {
      if (pair.startsWith("Policy=")) {
        return URLDecoder.decode(pair.substring(7), StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  @Test
  void aChangeNeedsApprovalAndALostAnswerIsUnknown() {
    String statement = "UPDATE orders SET status = 'paid' WHERE id = 'o-1'";
    assertThatThrownBy(() -> dynamo.execute(call("dynamodb_execute", JSON.writeValueAsString(Map.of("statement",
        statement))), config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    handlers.put("ExecuteStatement", r -> json(500, "{\"__type\": \"com.amazonaws.dynamodb.v20120810#"
        + "InternalServerError\", \"message\": \"Internal server error\"}"));
    assertThatThrownBy(() -> dynamo.execute(call("dynamodb_execute", "{}"), config(Env.DEV), secrets,
        TestCtx.of().approved(JsonNodeFactory.instance.objectNode().put("statement", statement))))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
  }

  @Test
  void dynamoErrorsSayWhatToDo() {
    handlers.put("DescribeTable", r -> json(400, "{\"__type\": \"com.amazonaws.dynamodb.v20120810#"
        + "ResourceNotFoundException\", \"message\": \"Requested resource not found: Table: orders not found\"}"));
    assertThatThrownBy(() -> dynamo.execute(call("dynamodb_describe_table", "{\"table\": \"orders\"}"),
        config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).contains("Table: orders not found", "dynamodb_list_tables");
        });
    handlers.put("Query", r -> json(400, "{\"__type\": \"com.amazon.coral.service#AccessDeniedException\", "
        + "\"Message\": \"User is not authorized to perform: dynamodb:Query\"}"));
    assertThatThrownBy(() -> dynamo.execute(call("dynamodb_query", "{\"table\": \"orders\", \"keyCondition\": "
        + "\"id = :i\", \"values\": {\":i\": {\"S\": \"x\"}}}"), config(Env.DEV), secrets, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.getMessage())
            .contains("may not do this", "dynamodb:Query"));
  }

  @Test
  void describesTheTableItsIndexesAndTimeToLive() throws Exception {
    handlers.put("DescribeTable", r -> json(200, "{\"Table\": {\"TableName\": \"orders\", \"TableStatus\": \"ACTIVE\", "
        + "\"KeySchema\": [{\"AttributeName\": \"customer_id\", \"KeyType\": \"HASH\"}, {\"AttributeName\": "
        + "\"created\", \"KeyType\": \"RANGE\"}], \"AttributeDefinitions\": [{\"AttributeName\": \"customer_id\", "
        + "\"AttributeType\": \"S\"}, {\"AttributeName\": \"created\", \"AttributeType\": \"S\"}, {\"AttributeName\": "
        + "\"status\", \"AttributeType\": \"S\"}], \"ItemCount\": 1204, \"TableSizeBytes\": 52011, "
        + "\"BillingModeSummary\": {\"BillingMode\": \"PAY_PER_REQUEST\"}, \"GlobalSecondaryIndexes\": [{"
        + "\"IndexName\": \"by_status\", \"KeySchema\": [{\"AttributeName\": \"status\", \"KeyType\": \"HASH\"}], "
        + "\"Projection\": {\"ProjectionType\": \"ALL\"}, \"IndexStatus\": \"ACTIVE\"}]}}"));
    handlers.put("DescribeTimeToLive", r -> json(200, "{\"TimeToLiveDescription\": {\"TimeToLiveStatus\": "
        + "\"ENABLED\", \"AttributeName\": \"expires_at\"}}"));
    ToolResult r = dynamo.execute(call("dynamodb_describe_table", "{\"table\": \"orders\"}"), config(Env.DEV),
        secrets, TestCtx.of());
    JsonNode d = r.structuredContent();
    assertThat(d.path("key").get(1).path("role").asString()).isEqualTo("sort");
    assertThat(d.path("indexes").get(0).path("name").asString()).isEqualTo("by_status");
    assertThat(d.path("billingMode").asString()).isEqualTo("PAY_PER_REQUEST");
    assertThat(d.path("ttlAttribute").asString()).isEqualTo("expires_at");
    assertThat(r.content().getFirst().text()).isEqualTo("orders (ACTIVE, ~1,204 items): customer_id S partition, "
        + "created S sort · indexes: by_status (global) · TTL on expires_at");
  }

  @Test
  void listingKeepsToTheListedTablesAndGoesOnAfterTheLastShown() throws Exception {
    handlers.put("ListTables", r -> body(r).has("ExclusiveStartTableName")
        ? json(200, "{\"TableNames\": [\"orders-2\", \"users\"]}")
        : json(200, "{\"TableNames\": [\"audit\", \"orders-1\", \"orders-2\"], \"LastEvaluatedTableName\": "
            + "\"orders-2\"}"));
    ConnectionConfig cfg = config(Env.DEV, "orders-*");
    ToolResult page = dynamo.execute(call("dynamodb_list_tables", "{\"limit\": 1}"), cfg, secrets, TestCtx.of());
    assertThat(page.structuredContent().path("tables").toString()).isEqualTo("[\"orders-1\"]");
    String cursor = page.structuredContent().path("nextCursor").asString();
    ToolResult rest = dynamo.execute(call("dynamodb_list_tables", "{\"limit\": 5, \"cursor\": \"" + cursor + "\"}"),
        cfg, secrets, TestCtx.of());
    assertThat(body(to("ListTables").getLast()).path("ExclusiveStartTableName").asString()).isEqualTo("orders-1");
    assertThat(rest.structuredContent().path("tables").toString()).doesNotContain("users", "audit");
  }

  @Test
  void testConnectionNamesTheIdentityAndAdvisesARole() {
    handlers.put("ListTables", r -> json(200, "{\"TableNames\": [\"orders\", \"users\"]}"));
    TestResult r = dynamo.test(config(Env.PROD), secrets, TestCtx.of().env(Env.PROD));
    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.identity()).isEqualTo("arn:aws:iam::412398765012:user/reader");
    assertThat(r.checks()).extracting(TestResult.Check::name).containsExactly("identity", "tables");
    assertThat(r.hints()).anySatisfy(h -> {
      assertThat(h.code()).isEqualTo("dynamodb.no-role");
      assertThat(h.severity()).isEqualTo(TestResult.Severity.WARNING);
    });
    assertThat(r.metadataUpdate().path("account").asString()).isEqualTo("412398765012");
    TestResult withRole = dynamo.test(config(Env.PROD, true), secrets, TestCtx.of().env(Env.PROD));
    assertThat(withRole.identity()).contains("assumed-role/gw-dynamodb");
    assertThat(withRole.hints()).noneSatisfy(h -> assertThat(h.code()).isEqualTo("dynamodb.no-role"));
  }

  @Test
  void anIncompleteConnectionIsRefusedWithoutAnyRequest() {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "nowhere");
    ConnectionConfig broken = new ConnectionConfig("conn-x", "x", "dynamodb", Env.DEV, 1, c, Set.of(), null);
    assertThat(dynamo.classify(call("dynamodb_list_tables", "{}"), broken, policy(Env.DEV)).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(dynamo.test(broken, secrets, TestCtx.of()).error().code()).isEqualTo("dynamodb.settings");
    assertThat(requests).isEmpty();
  }

  @Test
  void numbersStayExactAndStringsAreCut() {
    assertThat(Items.number("42").isIntegralNumber()).isTrue();
    assertThat(Items.number("1E+3").asLong()).isEqualTo(1000);
    assertThat(Items.number("9007199254740993").isString()).isTrue();
    assertThat(Items.number("0.1").asDouble()).isEqualTo(0.1);
    assertThat(Items.number("3.14159265358979323846").isString()).isTrue();
    assertThat(Items.text("x".repeat(20), 10).asString()).startsWith("xxxxxxxxxx…‹20");
  }
}
