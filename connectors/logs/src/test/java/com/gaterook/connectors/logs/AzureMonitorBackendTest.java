package com.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Azure Monitor Logs against a fake Entra ID and Log Analytics query API: the connection's workspaces and components
 * only, KQL shaped for entries or answered as a table, the tenant pin, and Test connection.
 */
class AzureMonitorBackendTest {

  private static final String TENANT = "6f1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
  private static final String CLIENT = "0a1b2c3d-1111-4222-8333-444455556666";
  private static final String W1 = "5b2f8c1e-0a3d-4e6f-9b7a-1c2d3e4f5a6b";
  private static final String W2 = "7c3a9d2f-1b4e-4f7a-8c9b-2d3e4f5a6b7c";
  private static final String COMPONENT = "/subscriptions/0b1f6471-1bf0-4dda-aec3-111122223333/resourceGroups/"
      + "stage-app/providers/Microsoft.Insights/components/stage-api";

  private final MockWebServer server = new MockWebServer();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  private final AtomicReference<Function<RecordedRequest, MockResponse>> query = new AtomicReference<>();
  private volatile String tokenTenant = TENANT;
  private CloudCredentials cloud;
  private BackendConnector connector;

  @BeforeEach
  void start() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        requests.add(request);
        if (request.getUrl().encodedPath().endsWith("/oauth2/v2.0/token")) {
          return json(200, "{\"expires_in\":3599,\"access_token\":\"" + token() + "\"}");
        }
        return query.get().apply(request);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    URI base = server.url("/").uri();
    cloud = new CloudCredentials(new CloudEndpoints(null, null, null, null, null, null,
        Map.of("azure.login", base, "azure.logs", base)), Clock.systemUTC());
    connector = new BackendConnector("azure-monitor", cloud, Clock.systemUTC());
  }

  @AfterEach
  void stop() throws IOException {
    connector.close();
    cloud.close();
    server.close();
  }

  private String token() {
    ObjectNode c = TestCtx.JSON.createObjectNode().put("tid", tokenTenant).put("appid", CLIENT).put("idtyp", "app")
        .put("exp", Instant.now().plusSeconds(3600).getEpochSecond());
    Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
    return e.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "." + e.encodeToString(
        TestCtx.JSON.writeValueAsBytes(c)) + ".c2ln";
  }

  private static ConnectionConfig config(String sources) {
    return new ConnectionConfig("c-azlogs", "stage-azure-logs", "azure-monitor", Env.STAGE, 1, TestCtx.JSON.readTree(
        "{\"backend\":\"azure-monitor\",\"tenantId\":\"" + TENANT + "\",\"credentials\":{\"source\":\"clientSecret\","
            + "\"clientId\":\"" + CLIENT + "\"}," + sources + "}"), Set.of("/credentials/clientSecret"), null);
  }

  private static TestSecrets secrets() {
    return new TestSecrets().with("/credentials/clientSecret", "cnry-logs-secret");
  }

  private ToolResult run(ConnectionConfig cfg, String tool, String args) throws ConnectorException {
    ToolCall call = new ToolCall("tc_az_" + System.nanoTime(), tool, cfg.name(),
        (ObjectNode) TestCtx.JSON.readTree(args), null);
    Classification c = connector.classify(call, cfg, Configs.policy(Env.STAGE, TestCtx.dev()));
    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.READ);
    return connector.execute(call, cfg, secrets(), TestCtx.of());
  }

  private static String table(String columns, String rows) {
    return "{\"tables\":[{\"name\":\"PrimaryResult\",\"columns\":[" + columns + "],\"rows\":[" + rows + "]}]}";
  }

  private RecordedRequest firstQuery() {
    return requests.stream().filter(q -> q.getUrl().encodedPath().endsWith("/query")).findFirst().orElseThrow();
  }

  @Test
  void theCatalogCardHasItsOwnToolNames() {
    assertThat(connector.manifest().name()).isEqualTo("Azure Monitor Logs");
    assertThat(connector.manifest().group()).isEqualTo("Logs");
    assertThat(connector.manifest().tools()).extracting(ToolDefinition::name)
        .containsExactly("azure_logs_search", "azure_logs_tail");
    assertThat(connector.manifest().configSchema().at("/properties/backend/const").asString())
        .isEqualTo("azure-monitor");
    assertThat(connector.manifest().configSchema().at("/properties/trustedCli/x-person-only").asBoolean()).isTrue();
  }

  @Test
  void aSearchOfTwoWorkspacesIsOneRequestTheGatewayScopes() throws Exception {
    String t1 = Instant.now().minusSeconds(30).toString();
    String t2 = Instant.now().minusSeconds(90).toString();
    query.set(r -> json(200, table("{\"name\":\"TimeGenerated\",\"type\":\"datetime\"},{\"name\":\"Message\","
        + "\"type\":\"string\"},{\"name\":\"Level\",\"type\":\"string\"},{\"name\":\"_ResourceId\",\"type\":"
        + "\"string\"}", "[\"" + t1 + "\",\"payment timeout\",\"Error\",\"/subscriptions/x/web/api\"],[\"" + t2
        + "\",\"started\",\"Information\",\"\"]")));

    ToolResult r = run(config("\"workspaces\":[\"" + W1 + "\",\"" + W2 + "\"]"), "azure_logs_search",
        "{\"query\":\"AppTraces\",\"from\":\"-1h\",\"limit\":10}");

    JsonNode s = r.structuredContent();
    assertThat(s.path("entries")).hasSize(2);
    assertThat(s.at("/entries/0/message").asString()).isEqualTo("payment timeout");
    assertThat(s.at("/entries/0/severity").asString()).isEqualTo("ERROR");
    assertThat(s.at("/entries/0/labels/_ResourceId").asString()).isEqualTo("/subscriptions/x/web/api");
    RecordedRequest sent = firstQuery();
    assertThat(sent.getUrl().encodedPath()).isEqualTo("/v1/workspaces/" + W1 + "/query");
    assertThat(sent.getHeaders().get("Authorization")).startsWith("Bearer ");
    JsonNode body = TestCtx.JSON.readTree(sent.getBody().utf8());
    assertThat(body.path("workspaces").toString()).isEqualTo("[\"" + W2 + "\"]");
    assertThat(body.path("query").asString()).startsWith("AppTraces\n| where TimeGenerated between (datetime(")
        .endsWith("| top 11 by TimeGenerated desc");
  }

  @Test
  void aComponentIsQueriedByItsResourceIdWithItsOwnTimeColumn() throws Exception {
    String t = Instant.now().minusSeconds(30).toString();
    query.set(r -> json(200, table("{\"name\":\"timestamp\",\"type\":\"datetime\"},{\"name\":\"outerMessage\","
        + "\"type\":\"string\"},{\"name\":\"severityLevel\",\"type\":\"int\"},{\"name\":\"itemId\",\"type\":"
        + "\"string\"}", "[\"" + t + "\",\"NullReferenceException\",4,\"i-1\"]")));

    ToolResult r = run(config("\"applicationInsights\":[\"" + COMPONENT + "\"]"), "azure_logs_search",
        "{\"query\":\"exceptions\"}");

    assertThat(r.structuredContent().at("/entries/0/severity").asString()).isEqualTo("CRITICAL");
    assertThat(r.structuredContent().at("/entries/0/id").asString()).isEqualTo("i-1");
    RecordedRequest sent = firstQuery();
    assertThat(sent.getUrl().encodedPath()).isEqualTo("/v1" + COMPONENT + "/query");
    assertThat(TestCtx.JSON.readTree(sent.getBody().utf8()).path("query").asString())
        .contains("| where timestamp between (datetime(").contains("| top 101 by timestamp desc");
  }

  @Test
  void anAggregateIsATable() throws Exception {
    query.set(r -> json(200, table("{\"name\":\"AppRoleName\",\"type\":\"string\"},{\"name\":\"count_\",\"type\":"
        + "\"long\"}", "[\"api\",12],[\"worker\",3]")));

    ToolResult r = run(config("\"workspaces\":[\"" + W1 + "\"]"), "azure_logs_search",
        "{\"query\":\"AppRequests | summarize count() by AppRoleName\"}");

    assertThat(r.structuredContent().toString()).contains("\"api\"").contains("12");
    assertThat(TestCtx.JSON.readTree(firstQuery().getBody().utf8()).path("query").asString())
        .isEqualTo("AppRequests | summarize count() by AppRoleName\n| take 101");
  }

  @Test
  void aQueryWithoutItsTimeColumnSaysHowToFixIt() {
    query.set(r -> json(200, table("{\"name\":\"Message\",\"type\":\"string\"}", "[\"x\"]")));

    assertThatThrownBy(() -> run(config("\"workspaces\":[\"" + W1 + "\"]"), "azure_logs_search",
        "{\"query\":\"AppTraces\"}")).hasMessageContaining("keep TimeGenerated");
  }

  @Test
  void crossResourceQueriesAndTailAggregatesAreDenied() {
    ConnectionConfig cfg = config("\"workspaces\":[\"" + W1 + "\"]");
    ToolCall other = new ToolCall("tc_1", "azure_logs_search", cfg.name(), (ObjectNode) TestCtx.JSON.readTree(
        "{\"query\":\"union app('x').requests\"}"), null);
    assertThat(connector.classify(other, cfg, Configs.policy(Env.STAGE, TestCtx.dev())).ruleId())
        .isEqualTo("stage.logs.kql_cross_resource");
    ToolCall tail = new ToolCall("tc_2", "azure_logs_tail", cfg.name(), (ObjectNode) TestCtx.JSON.readTree(
        "{\"query\":\"AppRequests | summarize count()\"}"), null);
    assertThat(connector.classify(tail, cfg, Configs.policy(Env.STAGE, TestCtx.dev())).ruleId())
        .isEqualTo("stage.logs.kql_tail_aggregate");
    ToolCall source = new ToolCall("tc_3", "azure_logs_search", cfg.name(), (ObjectNode) TestCtx.JSON.readTree(
        "{\"query\":\"AppTraces\",\"source\":\"" + W2 + "\"}"), null);
    assertThat(connector.classify(source, cfg, Configs.policy(Env.STAGE, TestCtx.dev())).ruleId())
        .isEqualTo("stage.logs.source_not_allowed");
  }

  @Test
  void aTokenOfAnotherTenantIsRefused() {
    tokenTenant = "11111111-2222-4333-8444-555555555555";
    query.set(r -> json(200, table("", "")));

    assertThatThrownBy(() -> run(config("\"workspaces\":[\"" + W1 + "\"]"), "azure_logs_search",
        "{\"query\":\"AppTraces\"}")).isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("now belongs to tenant");
        });
    assertThat(requests).noneMatch(q -> q.getUrl().encodedPath().endsWith("/query"));
  }

  @Test
  void testConnectionQueriesEverySourceAndRecordsTheTenant() {
    query.set(r -> json(200, table("{\"name\":\"gaterook\",\"type\":\"long\"}", "[1]")));

    TestResult r = connector.test(config("\"workspaces\":[\"" + W1 + "\"],\"applicationInsights\":[\"" + COMPONENT
        + "\"]"), secrets(), TestCtx.of());

    assertThat(r.ok()).as(String.valueOf(r.error())).isTrue();
    assertThat(r.metadataUpdate().path("tenant").asString()).isEqualTo(TENANT);
    assertThat(r.checks()).extracting(TestResult.Check::name).contains("token", "workspace " + W1,
        "component stage-api");
    assertThat(requests.stream().filter(q -> q.getUrl().encodedPath().endsWith("/query"))
        .map(q -> q.getBody().utf8()).toList()).hasSize(2).allMatch(b -> b.contains("print gaterook = 1"));
  }

  @Test
  void a403NamesTheRoleToAssign() {
    query.set(r -> json(403, "{\"error\":{\"code\":\"InsufficientAccessError\",\"message\":\"The provided "
        + "credentials have insufficient access to perform the requested operation\"}}"));

    assertThatThrownBy(() -> run(config("\"workspaces\":[\"" + W1 + "\"]"), "azure_logs_search",
        "{\"query\":\"AppTraces\"}")).hasMessageContaining("Log Analytics Reader");
  }

  @Test
  void settingsAreChecked() {
    assertThatThrownBy(() -> Settings.parse(config("\"workspaces\":[]"))).hasMessageContaining("at least one");
    assertThatThrownBy(() -> Settings.parse(config("\"workspaces\":[\"not-a-guid\"]")))
        .hasMessageContaining("workspace ID");
    assertThatThrownBy(() -> Settings.parse(config("\"applicationInsights\":[\"/subscriptions/x/components/y\"]")))
        .hasMessageContaining("Application Insights resource ID");
    Settings s = Settings.parse(config("\"workspaces\":[\"" + W1.toUpperCase(java.util.Locale.ROOT) + "\"]"));
    assertThat(s.sources()).containsExactly(W1);
    assertThat(s.title()).isEqualTo("Azure Monitor Logs");
  }

  private static MockResponse json(int code, String body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
  }
}
