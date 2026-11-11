package ai.msrv.gaterook.connectors.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The DynamoDB connector against moto 5.2.4's DynamoDB and STS (test-services, profile {@code dynamodb}; run with
 * {@code -Pservices}): tables made through the same JSON API, then every tool as an agent calls it, an approved
 * PartiQL change, and Test connection with and without a role. moto signs nothing and enforces no session policy:
 * the policies' contents are checked against the fake in {@link DynamoDbConnectorTest}.
 */
@Tag("cloud-it")
class MotoDynamoDbIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TestSecrets SECRETS = new TestSecrets().with("/credentials/secretAccessKey", "moto-secret");
  private static CloudCredentials cloud;
  private static DynamoDbConnector dynamo;
  private static ConnectionConfig cfg;

  @BeforeAll
  static void tables() throws Exception {
    assumeTrue(TestServices.enabled(), "moto runs in the Compose services: scripts/test-services.sh up dynamodb, "
        + "then -Pservices");
    URI base = URI.create("http://127.0.0.1:" + TestServices.port("GW_MOTO_PORT"));
    HttpClient http = HttpClient.newHttpClient();
    http.send(HttpRequest.newBuilder(base.resolve("/moto-api/reset")).POST(HttpRequest.BodyPublishers.noBody())
        .build(), HttpResponse.BodyHandlers.discarding());
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, base, null, null, Map.of("aws.dynamodb", base)),
        Clock.systemUTC());
    dynamo = new DynamoDbConnector(cloud);
    cfg = config(false);
    DynamoApi api = new DynamoApi(cloud);
    DynamoApi.Call setup = new DynamoApi.Call(cfg, DynamoSettings.of(cfg), SECRETS, TestCtx.of());
    api.call(setup, "CreateTable", (ObjectNode) JSON.readTree("""
        {"TableName": "orders", "BillingMode": "PAY_PER_REQUEST",
         "AttributeDefinitions": [{"AttributeName": "customer_id", "AttributeType": "S"},
           {"AttributeName": "created", "AttributeType": "S"}, {"AttributeName": "status", "AttributeType": "S"}],
         "KeySchema": [{"AttributeName": "customer_id", "KeyType": "HASH"}, {"AttributeName": "created", "KeyType": "RANGE"}],
         "GlobalSecondaryIndexes": [{"IndexName": "by_status", "KeySchema": [{"AttributeName": "status", "KeyType": "HASH"}],
           "Projection": {"ProjectionType": "ALL"}}]}"""), null, false);
    api.call(setup, "CreateTable", (ObjectNode) JSON.readTree("""
        {"TableName": "secrets", "BillingMode": "PAY_PER_REQUEST",
         "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
         "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}]}"""), null, false);
    for (int i = 1; i <= 7; i++) {
      api.call(setup, "PutItem", (ObjectNode) JSON.readTree("{\"TableName\": \"orders\", \"Item\": {\"customer_id\": "
          + "{\"S\": \"c-1\"}, \"created\": {\"S\": \"2026-11-0" + i + "\"}, \"status\": {\"S\": \"" + (i % 2 == 0
          ? "paid" : "open") + "\"}, \"total\": {\"N\": \"" + (i * 10) + ".5\"}}}"), null, false);
    }
    api.call(setup, "PutItem", (ObjectNode) JSON.readTree("{\"TableName\": \"secrets\", \"Item\": {\"id\": {\"S\": "
        + "\"api-key\"}}}"), null, false);
  }

  @AfterAll
  static void close() {
    if (cloud != null) {
      cloud.close();
    }
  }

  private static ConnectionConfig config(boolean role) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("region", "eu-central-1");
    ObjectNode credentials = c.putObject("credentials").put("source", "accessKey")
        .put("accessKeyId", "AKIAIOSFODNN7EXAMPLE");
    if (role) {
      credentials.put("roleArn", "arn:aws:iam::123456789012:role/gw-dynamodb").put("externalId", "gw-ext-0123456789ab");
    }
    c.putArray("tables").add("orders");
    return new ConnectionConfig("conn-moto-ddb" + (role ? "-role" : ""), "moto-ddb", "dynamodb", Env.DEV, 1, c,
        Set.of("/credentials/secretAccessKey"), null);
  }

  private static ToolResult run(String tool, String args) throws ConnectorException {
    return run(tool, args, TestCtx.of());
  }

  private static ToolResult run(String tool, String args, TestCtx ctx) throws ConnectorException {
    ToolCall call = new ToolCall("tc_it", tool, "moto-ddb", (ObjectNode) JSON.readTree(args), null);
    Classification c = dynamo.classify(call, cfg, new PolicyView(Env.DEV, "dev.dynamodb",
        JsonNodeFactory.instance.objectNode(), TestCtx.dev()));
    assertThat(c.kind()).as(c.reason()).isNotEqualTo(Classification.Kind.DENY);
    return dynamo.execute(call, cfg, SECRETS, ctx);
  }

  @Test
  void listsOnlyTheConnectionsTablesAndDescribesOne() throws Exception {
    assertThat(run("dynamodb_list_tables", "{}").structuredContent().path("tables").toString())
        .isEqualTo("[\"orders\"]");
    JsonNode d = run("dynamodb_describe_table", "{\"table\": \"orders\"}").structuredContent();
    assertThat(d.path("key").get(0).path("name").asString()).isEqualTo("customer_id");
    assertThat(d.path("indexes").get(0).path("name").asString()).isEqualTo("by_status");
    assertThat(d.path("itemCount").asLong()).isEqualTo(7);
  }

  @Test
  void queriesPageWithCursorsNewestFirst() throws Exception {
    String args = "{\"table\": \"orders\", \"keyCondition\": \"customer_id = :c\", \"values\": {\":c\": {\"S\": "
        + "\"c-1\"}}, \"ascending\": false, \"limit\": 3}";
    JsonNode first = run("dynamodb_query", args).structuredContent();
    assertThat(first.path("items").get(0).path("created").asString()).isEqualTo("2026-11-07");
    assertThat(first.path("items").get(0).path("total").asDouble()).isEqualTo(70.5);
    ObjectNode more = (ObjectNode) JSON.readTree(args);
    more.put("cursor", first.path("nextCursor").asString());
    JsonNode second = run("dynamodb_query", more.toString()).structuredContent();
    assertThat(second.path("items").get(0).path("created").asString()).isEqualTo("2026-11-04");
  }

  @Test
  void scansWithAFilterAndReadsPartiql() throws Exception {
    JsonNode paid = run("dynamodb_scan", "{\"table\": \"orders\", \"filter\": \"#s = :s\", \"names\": {\"#s\": "
        + "\"status\"}, \"values\": {\":s\": {\"S\": \"paid\"}}, \"limit\": 10}").structuredContent();
    assertThat(paid.path("count").asInt()).isEqualTo(3);
    JsonNode selected = run("dynamodb_partiql", "{\"statement\": \"SELECT * FROM \\\"orders\\\" WHERE customer_id "
        + "= ? AND created = ?\", \"parameters\": [{\"S\": \"c-1\"}, {\"S\": \"2026-11-02\"}]}").structuredContent();
    assertThat(selected.path("items").get(0).path("status").asString()).isEqualTo("paid");
  }

  @Test
  void anApprovedPartiqlChangeRunsAndReadsNeverWrite() throws Exception {
    String insert = "INSERT INTO \"orders\" VALUE {'customer_id': 'c-2', 'created': '2026-11-09', 'status': 'new'}";
    assertThatThrownBy(() -> dynamo.execute(new ToolCall("tc_it", "dynamodb_execute", "moto-ddb",
        JsonNodeFactory.instance.objectNode().put("statement", insert), null), cfg, SECRETS, TestCtx.of()))
        .isInstanceOf(ConnectorException.class);
    ToolResult done = dynamo.execute(new ToolCall("tc_it", "dynamodb_execute", "moto-ddb",
        JsonNodeFactory.instance.objectNode(), null), cfg, SECRETS,
        TestCtx.of().approved(JsonNodeFactory.instance.objectNode().put("statement", insert)));
    assertThat(done.structuredContent().path("committed").asBoolean()).isTrue();
    JsonNode inserted = run("dynamodb_query", "{\"table\": \"orders\", \"keyCondition\": \"customer_id = :c\", "
        + "\"values\": {\":c\": {\"S\": \"c-2\"}}}").structuredContent();
    assertThat(inserted.path("count").asInt()).isEqualTo(1);
    // A write sent through the read tool is refused before it leaves GateRook.
    assertThatThrownBy(() -> dynamo.execute(new ToolCall("tc_it", "dynamodb_partiql", "moto-ddb",
        JsonNodeFactory.instance.objectNode().put("statement", "DELETE FROM \"orders\" WHERE customer_id = 'c-2' "
            + "AND created = '2026-11-09'"), null), cfg, SECRETS, TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(run("dynamodb_query", "{\"table\": \"orders\", \"keyCondition\": \"customer_id = :c\", \"values\": "
        + "{\":c\": {\"S\": \"c-2\"}}}").structuredContent().path("count").asInt()).isEqualTo(1);
  }

  @Test
  void testConnectionWorksWithAKeyAndWithARole() {
    TestResult key = dynamo.test(cfg, SECRETS, TestCtx.of());
    assertThat(key.ok()).as(String.valueOf(key.error())).isTrue();
    assertThat(key.checks().get(1).detail()).contains("1 table", "orders");
    TestResult role = dynamo.test(config(true), SECRETS, TestCtx.of());
    assertThat(role.ok()).as(String.valueOf(role.error())).isTrue();
    assertThat(role.identity()).contains("assumed-role");
    assertThat(dynamo.health(cfg, SECRETS, TestCtx.of().timeout(Duration.ofSeconds(5))).state().name())
        .isEqualTo("HEALTHY");
  }
}
