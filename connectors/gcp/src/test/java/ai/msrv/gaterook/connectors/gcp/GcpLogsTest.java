package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Cloud Logging through an existing GCP connection keeps its scopes, credentials and Cloud policy. */
class GcpLogsTest {

  private static final Instant NOW = Instant.parse("2026-10-22T12:30:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String GKE_QUERY = "resource.type=\"k8s_container\" AND "
      + "resource.labels.cluster_name=\"stage-cluster\" AND resource.labels.namespace_name=\"app\"";
  private static final String LIST = "/v2/entries:list";
  private static final ConnectionConfig CFG = Configs.of("gcp-stage", Env.STAGE, """
      {"scopes":["projects/acme-stage","folders/123456","organizations/456789"],
       "credentials":{"source":"adc","impersonate":"%s"}}""".formatted(FakeGoogle.SA));
  private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private FakeGoogle google;
  private GcpConnector connector;

  @BeforeEach
  void start() throws Exception {
    google = new FakeGoogle();
    google.on(LIST, request -> FakeGoogle.json(200, entries(1)));
    connector = new GcpConnector(google.cloud, CLOCK);
  }

  @AfterEach
  void close() {
    connector.close();
    google.close();
  }

  @Test
  void searchesGkeLogsWithTheExistingConnectionsScopesAndImpersonatedCredentials() throws Exception {
    ToolCall call = call("logs_search", search(GKE_QUERY).put("limit", 5));
    TestCtx ctx = context();

    assertRead(call, CFG, Configs.policy(Env.STAGE));
    assertThat(google.requests).as("classification does no I/O").isEmpty();
    ToolResult result = valid(call, connector.execute(call, CFG, new TestSecrets(), ctx));

    JsonNode body = requestBody();
    assertThat(body.path("resourceNames")).isEqualTo(CFG.config().path("scopes"));
    assertThat(body.path("filter").asString()).isEqualTo("(" + GKE_QUERY + ")\n"
        + "AND timestamp>=\"2026-10-22T12:00:00Z\"\nAND timestamp<=\"2026-10-22T12:30:00Z\"");
    assertThat(body.path("orderBy").asString()).isEqualTo("timestamp desc");
    assertThat(body.path("pageSize").asInt()).isEqualTo(6);
    assertThat(google.to(LIST).getFirst().getHeaders().get("Authorization")).isEqualTo("Bearer " + google.token);
    assertThat(ctx.taints).contains(google.token);
    assertThat(ctx.quotaKeys).containsExactly("gcp:projects/acme-stage@30");
    assertThat(google.cloud.issued()).singleElement().satisfies(issued -> {
      assertThat(issued.owner()).isEqualTo(CFG.name());
      assertThat(issued.identity()).isEqualTo(FakeGoogle.SA);
    });
    JsonNode output = result.structuredContent();
    assertThat(output.path("backend").asString()).isEqualTo("gcp-logging");
    assertThat(output.path("count").asInt()).isEqualTo(1);
    assertThat(output.at("/entries/0/message").asString()).isEqualTo("request failed 0");
    assertThat(output.at("/entries/0/severity").asString()).isEqualTo("ERROR");
    assertThat(output.at("/entries/0/labels/cluster_name").asString()).isEqualTo("stage-cluster");
    assertThat(output.at("/entries/0/labels/namespace_name").asString()).isEqualTo("app");
    assertThat(output.at("/entries/0/fields/requestId").asInt()).isEqualTo(100);
  }

  @ParameterizedTest
  @ValueSource(strings = {"projects/acme-stage", "folders/123456", "organizations/456789"})
  void sourceNarrowsTheNativeResourceNamesToOneConfiguredScope(String source) throws Exception {
    ToolCall call = call("logs_search", search(GKE_QUERY).put("source", source));

    execute(call, Configs.policy(Env.STAGE), context());

    assertThat(requestBody().path("resourceNames").toString()).isEqualTo("[\"" + source + "\"]");
  }

  @Test
  void queryCannotReplaceTheNativeResourceScope() throws Exception {
    String query = "resource.labels.project_id=\"acme-prod\" OR severity>=ERROR";
    ToolCall call = call("logs_search", search(query).put("source", "projects/acme-stage"));

    execute(call, Configs.policy(Env.STAGE), context());

    assertThat(requestBody().path("resourceNames").toString()).isEqualTo("[\"projects/acme-stage\"]");
    assertThat(requestBody().path("filter").asString()).startsWith("(" + query + ")\nAND timestamp>=");
  }

  @ParameterizedTest
  @ValueSource(strings = {"logs_search", "logs_tail"})
  void refusesSourcesOutsideTheConfiguredScopesWithoutNetworkIo(String tool) {
    for (String source : List.of("acme-stage", "projects/acme-prod", "folders/999999",
        "organizations/999999", "projects/acme-stage/locations/global/buckets/_Default/views/all")) {
      ToolCall call = call(tool, TestCtx.JSON.createObjectNode().put("query", GKE_QUERY).put("source", source));
      Classification result = connector.classify(call, CFG, Configs.policy(Env.STAGE));
      assertThat(result.kind()).as(source).isEqualTo(Classification.Kind.DENY);
      assertThat(result.ruleId()).endsWith(".source_not_allowed");
      assertThat(result.reason()).contains(source);
    }
    assertThat(google.requests).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"logs_search", "logs_tail"})
  void refusesCloudRestrictionsThatCannotBeAppliedToLogEntries(String tool) {
    for (String section : List.of("{\"allowedServices\":[\"container\"]}",
        "{\"allowedServices\":[\"logging\"]}", "{\"allowedServices\":[\"\\u0000none\"]}",
        "{\"regions\":[\"europe-west1\"]}", "{\"resources\":[\"//container.googleapis.com/projects/acme-stage/*\"]}")) {
      ToolCall call = call(tool, TestCtx.JSON.createObjectNode().put("query", GKE_QUERY));
      Classification result = connector.classify(call, CFG, Configs.policy(Env.STAGE, section));
      assertThat(result.kind()).as(section).isEqualTo(Classification.Kind.DENY);
      assertThat(result.ruleId()).isEqualTo("stage.cloud.log_scope");
      assertThat(result.reason()).isNotBlank();
    }
    assertThat(google.requests).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"2,1000,2", "100,1,1"})
  void cloudAndCallLineLimitsBothConstrainReturnedEntries(int maxItems, int maxLines, int expected) throws Exception {
    google.on(LIST, request -> FakeGoogle.json(200, entries(5)));
    Limits limits = limits(maxLines, Duration.ofDays(7));
    PolicyView policy = policy("{\"maxItems\":" + maxItems + "}", limits);
    ToolCall call = call("logs_search", search(GKE_QUERY).put("limit", 500));

    ToolResult result = execute(call, policy, context().with(limits));

    assertThat(requestBody().path("pageSize").asInt()).isEqualTo(expected + 1);
    assertThat(result.structuredContent().path("count").asInt()).isEqualTo(expected);
    assertThat(result.structuredContent().path("truncated").asBoolean()).isTrue();
    assertThat(result.structuredContent().path("truncatedReason").asString()).isEqualTo("limit");
    assertThat(result.structuredContent().path("notes").toString()).contains("limit lowered to " + expected);
  }

  @Test
  void omittedLimitUsesTheCloudPolicyDefaultDuringExecution() throws Exception {
    google.on(LIST, request -> FakeGoogle.json(200, entries(5)));
    ToolCall call = call("logs_search", search(GKE_QUERY));

    ToolResult result = execute(call, Configs.policy(Env.STAGE, "{\"maxItems\":3}"), context());

    assertThat(result.structuredContent().path("count").asInt()).isEqualTo(3);
    assertThat(requestBody().path("pageSize").asInt()).isEqualTo(4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"logs_search", "logs_tail"})
  void cloudAndCallTimeRangesAreBothEnforced(String tool) {
    ObjectNode args = TestCtx.JSON.createObjectNode().put("query", GKE_QUERY);
    args.put(tool.equals("logs_search") ? "from" : "since", "-2d");
    Classification cloudRange = connector.classify(call(tool, args), CFG,
        policy("{\"maxRangeDays\":1}", limits(1000, Duration.ofDays(7))));
    args.put(tool.equals("logs_search") ? "from" : "since", "-3h");
    Classification callRange = connector.classify(call(tool, args), CFG,
        policy("{\"maxRangeDays\":14}", limits(1000, Duration.ofHours(2))));

    for (Classification result : List.of(cloudRange, callRange)) {
      assertThat(result.kind()).as(result.reason()).isEqualTo(Classification.Kind.DENY);
      assertThat(result.ruleId()).endsWith(".range");
    }
    assertThat(google.requests).isEmpty();
  }

  @Test
  void executionRechecksTighterCallLimits() {
    ToolCall call = call("logs_search", search(GKE_QUERY));
    assertRead(call, CFG, Configs.policy(Env.STAGE));

    assertThatThrownBy(() -> connector.execute(call, CFG, new TestSecrets(),
        context().with(limits(1000, Duration.ofMinutes(10)))))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("range");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void tailReadsTheRequestedBacklogWithItsCursorAndPreservesTheNativeScope() throws Exception {
    ToolCall call = call("logs_tail", TestCtx.JSON.createObjectNode().put("query", GKE_QUERY)
        .put("since", NOW.minusSeconds(120).toString()).put("wait", 0).put("limit", 5));

    ToolResult result = execute(call, Configs.policy(Env.STAGE), context());

    assertThat(requestBody().path("resourceNames")).isEqualTo(CFG.config().path("scopes"));
    assertThat(requestBody().path("orderBy").asString()).isEqualTo("timestamp asc");
    assertThat(result.structuredContent().path("count").asInt()).isEqualTo(1);
    assertThat(result.structuredContent().path("nextCursor").asString()).startsWith("lc1.");
    assertThat(result.structuredContent().path("waitedMs").asLong()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void logExecutionRequiresAClassificationForTheSameConnectionAndVersion() {
    ToolCall unclassified = call("logs_search", search(GKE_QUERY));
    assertThatThrownBy(() -> connector.execute(unclassified, CFG, new TestSecrets(), context()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("no policy decision");

    for (ConnectionConfig other : List.of(
        new ConnectionConfig("other-id", CFG.name(), CFG.type(), CFG.env(), CFG.version(), CFG.config(),
            CFG.secretFields(), CFG.metadata()),
        new ConnectionConfig(CFG.id(), CFG.name(), CFG.type(), CFG.env(), CFG.version() + 1, CFG.config(),
            CFG.secretFields(), CFG.metadata()))) {
      ToolCall call = call("logs_search", search(GKE_QUERY));
      assertRead(call, CFG, Configs.policy(Env.STAGE));
      assertThatThrownBy(() -> connector.execute(call, other, new TestSecrets(), context()))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("no policy decision");
    }
    assertThat(google.requests).isEmpty();
  }

  @Test
  void aLogDecisionIsConsumedOnce() throws Exception {
    ToolCall call = call("logs_search", search(GKE_QUERY));
    execute(call, Configs.policy(Env.STAGE), context());

    assertThatThrownBy(() -> connector.execute(call, CFG, new TestSecrets(), context()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("no policy decision");
    assertThat(google.to(LIST)).hasSize(1);
  }

  @Test
  void anInventoryDecisionCannotAuthorizeALogCallWithTheSameId() {
    ToolCall inventory = call("gcp_describe", TestCtx.JSON.createObjectNode());
    assertRead(inventory, CFG, Configs.policy(Env.STAGE));
    ToolCall logs = new ToolCall(inventory.callId(), "logs_search", inventory.connection(), search(GKE_QUERY), null);

    assertThatThrownBy(() -> connector.execute(logs, CFG, new TestSecrets(), context()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("no policy decision");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void deniedReclassificationRemovesTheEarlierLogDecision() {
    ToolCall call = call("logs_search", search(GKE_QUERY));
    assertRead(call, CFG, Configs.policy(Env.STAGE));
    Classification denied = connector.classify(call, CFG, Configs.policy(Env.STAGE,
        "{\"regions\":[\"europe-west1\"]}"));
    assertThat(denied.kind()).isEqualTo(Classification.Kind.DENY);

    assertThatThrownBy(() -> connector.execute(call, CFG, new TestSecrets(), context()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("no policy decision");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void executionRechecksSourcesChangedAfterClassification() {
    ToolCall call = call("logs_search", search(GKE_QUERY));
    assertRead(call, CFG, Configs.policy(Env.STAGE));
    ToolCall changed = new ToolCall(call.callId(), call.tool(), call.connection(),
        search(GKE_QUERY).put("source", "projects/acme-prod"), null);

    assertThatThrownBy(() -> connector.execute(changed, CFG, new TestSecrets(), context()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("isn't one of");
    assertThat(google.requests).isEmpty();
  }

  @Test
  void approvedLogsUseTheExactPreviewedQueryAndAbsoluteWindow() throws Exception {
    ToolCall call = call("logs_search", search(GKE_QUERY).put("source", "projects/acme-stage").put("limit", 5));
    ActionPreview preview = connector.describeAction(call, CFG);
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.TEXT);
    assertThat(preview.text()).contains(GKE_QUERY, "2026-10-22T12:00:00Z", "2026-10-22T12:30:00Z");
    assertThat(preview.payload().path("tool").asString()).isEqualTo("logs_search");
    assertRead(call, CFG, Configs.policy(Env.STAGE));
    ToolCall changed = new ToolCall(call.callId(), call.tool(), call.connection(),
        search("severity>=INFO").put("source", "folders/123456"), null);

    valid(call, connector.execute(changed, CFG, new TestSecrets(), context().approved(preview.payload())));

    assertThat(requestBody().path("resourceNames").toString()).isEqualTo("[\"projects/acme-stage\"]");
    assertThat(requestBody().path("filter").asString()).isEqualTo("(" + GKE_QUERY + ")\n"
        + "AND timestamp>=\"2026-10-22T12:00:00Z\"\nAND timestamp<=\"2026-10-22T12:30:00Z\"");
    assertThat(requestBody().path("pageSize").asInt()).isEqualTo(6);
  }

  @Test
  void approvedPayloadCannotSwapToolsOrWidenTheConnectionsScopes() {
    for (ObjectNode payload : List.of(
        TestCtx.JSON.createObjectNode().put("tool", "logs_tail")
            .set("arguments", TestCtx.JSON.createObjectNode().put("query", GKE_QUERY).put("wait", 0)),
        TestCtx.JSON.createObjectNode().put("tool", "logs_search")
            .set("arguments", search(GKE_QUERY).put("source", "projects/acme-prod")))) {
      ToolCall call = call("logs_search", search(GKE_QUERY));
      assertRead(call, CFG, Configs.policy(Env.STAGE));
      assertThatThrownBy(() -> connector.execute(call, CFG, new TestSecrets(), context().approved(payload)))
          .isInstanceOf(ConnectorException.class);
    }
    assertThat(google.requests).isEmpty();
  }

  @Test
  void logCursorsRemainBoundToTheGcpConnectionIdentity() throws Exception {
    google.on(LIST, request -> FakeGoogle.json(200, entries(3)));
    ToolCall initial = call("logs_search", search(GKE_QUERY).put("limit", 1));
    ToolResult result = execute(initial, Configs.policy(Env.STAGE), context());
    String cursor = result.structuredContent().path("nextCursor").asString();
    assertThat(cursor).startsWith("lc1.");
    ConnectionConfig other = new ConnectionConfig("other-id", "gcp-other", CFG.type(), CFG.env(), CFG.version(),
        CFG.config(), CFG.secretFields(), CFG.metadata());
    ToolCall resume = Configs.call("logs_search", other.name(), search(GKE_QUERY).put("cursor", cursor).toString());
    assertRead(resume, other, Configs.policy(Env.STAGE));

    assertThatThrownBy(() -> connector.execute(resume, other, new TestSecrets(), context()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("cursor");
    assertThat(google.to(LIST)).hasSize(1);
  }

  private ToolResult execute(ToolCall call, PolicyView policy, TestCtx ctx) throws Exception {
    assertRead(call, CFG, policy);
    return valid(call, connector.execute(call, CFG, new TestSecrets(), ctx));
  }

  private void assertRead(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    Classification classification = connector.classify(call, cfg, policy);
    assertThat(classification.kind()).as(classification.reason()).isEqualTo(Classification.Kind.READ);
  }

  private ToolResult valid(ToolCall call, ToolResult result) {
    ToolDefinition definition = connector.manifest().tools().stream().filter(t -> t.name().equals(call.tool()))
        .findFirst().orElseThrow();
    assertThat(result.isError()).isFalse();
    assertThat(result.structuredContent()).isNotNull();
    assertThat(SCHEMAS.getSchema(definition.outputSchema()).validate(result.structuredContent()))
        .as(result.structuredContent().toString()).isEmpty();
    assertThat(result.content()).isNotEmpty();
    return result;
  }

  private JsonNode requestBody() {
    return TestCtx.JSON.readTree(google.to(LIST).getLast().getBody().utf8());
  }

  private static ToolCall call(String tool, ObjectNode arguments) {
    return Configs.call(tool, CFG.name(), arguments.toString());
  }

  private static ObjectNode search(String query) {
    return TestCtx.JSON.createObjectNode().put("query", query).put("from", NOW.minusSeconds(1800).toString())
        .put("to", NOW.toString());
  }

  private static TestCtx context() {
    return TestCtx.of().env(Env.STAGE).clock(CLOCK);
  }

  private static PolicyView policy(String section, Limits limits) {
    return new PolicyView(Env.STAGE, "stage.cloud", TestCtx.JSON.readTree(section), limits);
  }

  private static Limits limits(int maxLines, Duration maxRange) {
    Limits d = TestCtx.dev();
    return new Limits(d.maxRows(), maxLines, d.maxResponseBytes(), d.maxRequestBytes(), d.maxOutputBytes(),
        d.maxStderrBytes(), d.maxCellChars(), d.statementTimeout(), d.commandTimeout(), d.toolTimeout(), maxRange);
  }

  private static String entries(int count) {
    ObjectNode response = TestCtx.JSON.createObjectNode();
    var entries = response.putArray("entries");
    for (int i = 0; i < count; i++) {
      ObjectNode entry = entries.addObject().put("insertId", "entry-" + i)
          .put("logName", "projects/acme-stage/logs/stderr").put("timestamp", NOW.minusSeconds(60 + i).toString())
          .put("severity", "ERROR");
      entry.putObject("resource").put("type", "k8s_container").putObject("labels")
          .put("cluster_name", "stage-cluster").put("namespace_name", "app").put("pod_name", "api-7d9");
      entry.putObject("jsonPayload").put("message", "request failed " + i).put("requestId", 100 + i);
    }
    return response.toString();
  }
}
