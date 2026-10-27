package gaterook.plugins.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The plugin against Jira's REST API in MockWebServer (report 11 §8 item 3): the paths and bodies it sends, how it
 * reads lists, requests, queues and SLAs, Basic and Bearer credentials, what an answer of Jira means for the agent
 * (not found, rejected token, refused transition) and what is the service's trouble (5xx, a broken POST).
 */
class JiraServiceDeskConnectorTest {

  private MockWebServer server;
  private final Map<String, MockResponse> routes = new ConcurrentHashMap<>();
  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
  private String base;
  private final JiraServiceDeskConnector v13 = new JiraServiceDeskConnector(TestCalls.release("1.3.0"));
  private final JiraServiceDeskConnector v12 = new JiraServiceDeskConnector(TestCalls.release("1.2.0"));

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        seen.add(request);
        String target = request.getTarget();
        String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
        MockResponse r = routes.get(request.getMethod() + " " + path);
        return r != null ? r : new MockResponse.Builder().code(404).body("{\"errorMessage\":\"no route\"}").build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    base = "http://127.0.0.1:" + server.getPort();
  }

  @AfterEach
  void stop() throws Exception {
    server.close();
  }

  private void on(String route, int code, String json) {
    routes.put(route, new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
        .body(json).build());
  }

  private RecordedRequest last() throws InterruptedException {
    return seen.getLast();
  }

  @Test
  void searchReadsRequestsAsATableAndKeepsToTheConnectionsServiceDesks() throws Exception {
    on("GET /rest/servicedeskapi/request", 200, """
        {"size":3,"start":0,"limit":20,"isLastPage":false,"values":[
         {"issueKey":"SD-123","serviceDeskId":"1","requestTypeId":"10",
          "createdDate":{"iso8601":"2026-10-22T14:30:00+0200"},"reporter":{"displayName":"Ann Lee"},
          "requestFieldValues":[{"fieldId":"summary","label":"Summary","value":"Payment worker stuck"}],
          "currentStatus":{"status":"Waiting for support","statusCategory":"INDETERMINATE"}},
         {"issueKey":"HR-7","serviceDeskId":"9","requestFieldValues":[],"currentStatus":{"status":"Open"}},
         {"issueKey":"SD-124","serviceDeskId":"1","requestFieldValues":[{"fieldId":"summary","value":"VPN"}],
          "currentStatus":{"status":"Open"}}]}""");
    TestCalls.Context ctx = new TestCalls.Context(false);
    ToolResult r = v13.execute(TestCalls.call("jira_search_requests",
        "{\"searchTerm\":\"payment worker\",\"requestStatus\":\"ALL_REQUESTS\",\"limit\":20}"),
        TestCalls.cloud(base, 1L), new TestCalls.Secrets(), ctx);

    assertThat(r.isError()).isFalse();
    assertThat(r.table().columns()).extracting(ToolResult.Column::name).containsExactly("key", "summary", "status",
        "serviceDeskId", "reporter", "created");
    assertThat(r.table().rows()).hasSize(2);
    assertThat(r.table().rows().get(0).get(0).asString()).isEqualTo("SD-123");
    assertThat(r.table().rows().get(0).get(1).asString()).isEqualTo("Payment worker stuck");
    assertThat(r.structuredContent().path("hiddenByConnection").asInt()).isEqualTo(1);
    assertThat(r.table().nextCursor()).isEqualTo("start=3");
    RecordedRequest req = last();
    assertThat(req.getTarget()).isEqualTo(
        "/rest/servicedeskapi/request?requestStatus=ALL_REQUESTS&searchTerm=payment+worker&start=0&limit=20");
    String basic = Base64.getEncoder().encodeToString(("svc-gateway@acme.com:" + TestCalls.TOKEN)
        .getBytes(StandardCharsets.UTF_8));
    assertThat(req.getHeaders().get("Authorization")).isEqualTo("Basic " + basic);
    // The Basic value is derived from the token: the core is told to mask it.
    assertThat(ctx.tainted).containsExactly(basic);

    assertThatThrownBy(() -> v13.execute(TestCalls.call("jira_search_requests", "{\"serviceDeskId\":9}"),
        TestCalls.cloud(base, 1L), new TestCalls.Secrets(), new TestCalls.Context(false)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void aRequestComesWithItsFieldsAndTheTransitionsItCanTake() throws Exception {
    on("GET /rest/servicedeskapi/request/SD-123", 200, """
        {"issueKey":"SD-123","serviceDeskId":"1","requestTypeId":"10","reporter":{"displayName":"Ann Lee"},
         "createdDate":{"iso8601":"2026-10-22T14:30:00+0200"},"currentStatus":{"status":"Open","statusCategory":"NEW"},
         "requestFieldValues":[{"fieldId":"summary","label":"Summary","value":"Payment worker stuck"},
          {"fieldId":"description","label":"Description","value":"Since 14:00"}]}""");
    on("GET /rest/servicedeskapi/request/SD-123/transition", 200, """
        {"values":[{"id":"21","name":"Resolve this issue"},{"id":"31","name":"Escalate"}]}""");
    ToolResult r = v13.execute(TestCalls.call("jira_get_request", "{\"issueKey\":\"SD-123\"}"),
        TestCalls.cloud(base), new TestCalls.Secrets(), new TestCalls.Context(false));

    JsonNode s = r.structuredContent();
    assertThat(s.path("summary").asString()).isEqualTo("Payment worker stuck");
    assertThat(s.path("status").asString()).isEqualTo("Open");
    assertThat(s.path("fields")).hasSize(2);
    assertThat(s.path("transitions").get(0).path("id").asString()).isEqualTo("21");
    assertThat(r.content().getFirst().text()).contains("SD-123", "Resolve this issue (21)");

    ToolResult missing = v13.execute(TestCalls.call("jira_get_request", "{\"issueKey\":\"SD-999\"}"),
        TestCalls.cloud(base), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(missing.isError()).isTrue();
    assertThat(missing.content().getFirst().text()).isEqualTo("SD-999 not found or not visible to this account.");
    // A desk outside the allowlist looks like a request this account can't see.
    ToolResult elsewhere = v13.execute(TestCalls.call("jira_get_request", "{\"issueKey\":\"SD-123\"}"),
        TestCalls.cloud(base, 5L), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(elsewhere.content().getFirst().text()).contains("not found or not visible");
  }

  @Test
  void slasReadTheOngoingCycleElseTheLastCompletedOne() throws Exception {
    on("GET /rest/servicedeskapi/request/SD-123/sla", 200, """
        {"isLastPage":true,"values":[
         {"name":"Time to first response","ongoingCycle":{"breached":false,"paused":false,
           "goalDuration":{"millis":14400000},"elapsedTime":{"millis":5400000},"remainingTime":{"millis":9000000},
           "breachTime":{"iso8601":"2026-10-22T18:30:00+0200"}}},
         {"name":"Time to resolution","ongoingCycle":{"breached":false,"paused":true,
           "goalDuration":{"millis":28800000},"elapsedTime":{"millis":600000},"remainingTime":{"millis":28200000}}},
         {"name":"Time to approve","completedCycles":[
           {"breached":false,"goalDuration":{"millis":3600000},"elapsedTime":{"millis":60000},"remainingTime":{"millis":3540000}},
           {"breached":true,"goalDuration":{"millis":3600000},"elapsedTime":{"millis":4000000},"remainingTime":{"millis":-400000}}]}]}""");
    ToolResult r = v13.execute(TestCalls.call("jira_request_sla", "{\"issueKey\":\"SD-123\"}"),
        TestCalls.cloud(base), new TestCalls.Secrets(), new TestCalls.Context(false));

    JsonNode slas = r.structuredContent().path("slas");
    assertThat(slas).hasSize(3);
    assertThat(slas.get(0).path("state").asString()).isEqualTo("ongoing");
    assertThat(slas.get(0).path("remainingMillis").asLong()).isEqualTo(9_000_000);
    assertThat(slas.get(0).path("breachTime").asString()).isEqualTo("2026-10-22T18:30:00+0200");
    assertThat(slas.get(1).path("state").asString()).isEqualTo("paused");
    assertThat(slas.get(1).path("breachTime").isNull()).isTrue();
    assertThat(slas.get(2).path("state").asString()).isEqualTo("completed");
    assertThat(slas.get(2).path("breached").asBoolean()).isTrue();
    assertThat(r.structuredContent().path("truncated").asBoolean()).isFalse();
    assertThat(r.content().getFirst().text()).startsWith(
        "SD-123 · Time to first response: ongoing, 2h 30m left (goal 4h)");
    assertThat(last().getTarget()).isEqualTo("/rest/servicedeskapi/request/SD-123/sla?start=0&limit=50");

    // Release 1.2.0 doesn't have the tool.
    assertThatThrownBy(() -> v12.execute(TestCalls.call("jira_request_sla", "{\"issueKey\":\"SD-123\"}"),
        TestCalls.cloud(base), new TestCalls.Secrets(), new TestCalls.Context(false)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
  }

  @Test
  void queuesOfOneServiceDesk() throws Exception {
    on("GET /rest/servicedeskapi/servicedesk/1/queue", 200, """
        {"isLastPage":true,"values":[{"id":"1","name":"All open","jql":"resolution = EMPTY","issueCount":12},
         {"id":"2","name":"Breached","jql":"\\"Time to resolution\\" = breached()"}]}""");
    ToolResult r = v13.execute(TestCalls.call("jira_list_queues", "{\"serviceDeskId\":1}"),
        TestCalls.cloud(base), new TestCalls.Secrets(), new TestCalls.Context(false));

    assertThat(r.table().rows()).hasSize(2);
    assertThat(r.table().rows().get(0).get(2).asLong()).isEqualTo(12);
    assertThat(r.table().rows().get(1).get(2).isNull()).isTrue();
    assertThat(last().getTarget()).isEqualTo("/rest/servicedeskapi/servicedesk/1/queue?includeCount=true&start=0"
        + "&limit=50");
  }

  @Test
  void aCommentRunsOnlyApprovedAndSendsExactlyWhatTheApproverSaw() throws Exception {
    on("POST /rest/servicedeskapi/request/SD-123/comment", 201, """
        {"id":"10010","body":"Restarted the payment worker","public":false,"created":{"iso8601":"2026-10-22T15:00:00+0200"}}""");
    var call = TestCalls.call("jira_add_comment", "{\"issueKey\":\"SD-123\",\"body\":\"Restarted the payment worker\"}");
    ConnectionConfig cfg = TestCalls.cloud(base);

    assertThat(v13.classify(call, cfg, new PolicyView(Env.DEV, "dev.jira", null, null)).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThatThrownBy(() -> v13.execute(call, cfg, new TestCalls.Secrets(), new TestCalls.Context(false)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(seen).isEmpty();

    ActionPreview preview = v13.describeAction(call, cfg);
    ToolResult r = v13.execute(call, cfg, new TestCalls.Secrets(), new TestCalls.Context(true));
    assertThat(r.structuredContent().path("commentId").asString()).isEqualTo("10010");
    assertThat(r.content().getFirst().text()).isEqualTo("Internal comment 10010 added to SD-123.");
    RecordedRequest req = last();
    assertThat(preview.kind()).isEqualTo(ActionPreview.Kind.HTTP);
    assertThat(preview.summary()).isEqualTo("Internal comment on SD-123");
    assertThat(preview.text()).isEqualTo("POST " + base + "/rest/servicedeskapi/request/SD-123/comment\n"
        + req.getBody().utf8());
    assertThat(req.getBody().utf8()).isEqualTo("{\"body\":\"Restarted the payment worker\",\"public\":false}");
    assertThat(req.getHeaders().get("Content-Type")).isEqualTo("application/json");
  }

  @Test
  void aRefusedTransitionIsJirasAnswerABrokenOneMayHaveHappened() throws Exception {
    on("POST /rest/servicedeskapi/request/SD-123/transition", 400, """
        {"errorMessage":"The transition 99 isn't valid for SD-123"}""");
    ToolResult refused = v13.execute(TestCalls.call("jira_transition_request",
        "{\"issueKey\":\"SD-123\",\"transitionId\":\"99\",\"comment\":\"done\"}"), TestCalls.cloud(base),
        new TestCalls.Secrets(), new TestCalls.Context(true));
    assertThat(refused.isError()).isTrue();
    assertThat(refused.content().getFirst().text()).contains("Jira refused transition 99 of SD-123",
        "The transition 99 isn't valid", "jira_get_request lists");
    assertThat(last().getBody().utf8()).isEqualTo("{\"id\":\"99\",\"additionalComment\":{\"body\":\"done\"}}");

    routes.put("POST /rest/servicedeskapi/request/SD-124/transition", new MockResponse.Builder()
        .onResponseStart(new SocketEffect.CloseSocket()).build());
    assertThatThrownBy(() -> v13.execute(TestCalls.call("jira_transition_request",
        "{\"issueKey\":\"SD-124\",\"transitionId\":\"21\"}"), TestCalls.cloud(base), new TestCalls.Secrets(),
        new TestCalls.Context(true)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
  }

  @Test
  void jirasRefusalsAreResultsItsOutagesAreErrors() throws Exception {
    on("GET /rest/servicedeskapi/request/SD-1", 401, "{\"errorMessage\":\"Client must be authenticated\"}");
    on("GET /rest/servicedeskapi/request/SD-2", 503, "{}");
    ToolResult rejected = v13.execute(TestCalls.call("jira_get_request", "{\"issueKey\":\"SD-1\"}"),
        TestCalls.cloud(base), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(rejected.content().getFirst().text()).isEqualTo("Jira rejected the token. Test the connection.");
    assertThatThrownBy(() -> v13.execute(TestCalls.call("jira_get_request", "{\"issueKey\":\"SD-2\"}"),
        TestCalls.cloud(base), new TestCalls.Secrets(), new TestCalls.Context(false)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.countsForCircuitBreaker()).isTrue());
  }

  @Test
  void testConnectionNamesTheAccountAndWarnsWhenItCanWrite() {
    on("GET /rest/servicedeskapi/info", 200, "{\"version\":\"5.25.0\",\"platformVersion\":\"1001.0.0\"}");
    on("GET /rest/api/3/myself", 200, "{\"accountId\":\"5b10\",\"emailAddress\":\"svc-gateway@acme.com\"}");
    on("GET /rest/api/3/mypermissions", 200, """
        {"permissions":{"BROWSE_PROJECTS":{"havePermission":true},"ADD_COMMENTS":{"havePermission":true},
         "TRANSITION_ISSUES":{"havePermission":false}}}""");
    TestResult t = v13.test(TestCalls.cloud(base), new TestCalls.Secrets(), new TestCalls.Context(false));

    assertThat(t.ok()).isTrue();
    assertThat(t.serverVersion()).isEqualTo("5.25.0");
    assertThat(t.identity()).isEqualTo("svc-gateway@acme.com");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(t.hints()).extracting(TestResult.Hint::text)
        .containsExactly("This account can comment requests. Use a read-only account for prod.");
    assertThat(t.checks()).extracting(TestResult.Check::name).containsExactly("Service Management", "Account",
        "Permissions");
    assertThat(seen.getLast().getTarget()).isEqualTo(
        "/rest/api/3/mypermissions?permissions=BROWSE_PROJECTS,ADD_COMMENTS,TRANSITION_ISSUES");
  }

  @Test
  void dataCenterUsesTheTokenAsABearerAndTheV2PlatformApi() throws Exception {
    on("GET /rest/servicedeskapi/info", 200, "{\"version\":\"10.3.1\"}");
    on("GET /rest/api/2/myself", 200, "{\"name\":\"svc-gateway\",\"displayName\":\"Gateway\"}");
    on("GET /rest/api/2/mypermissions", 200, "{\"permissions\":{}}");
    TestCalls.Context ctx = new TestCalls.Context(false);
    TestResult t = v13.test(TestCalls.datacenter(base), new TestCalls.Secrets(), ctx);

    assertThat(t.ok()).isTrue();
    assertThat(t.identity()).isEqualTo("Gateway");
    assertThat(seen.get(1).getHeaders().get("Authorization")).isEqualTo("Bearer " + TestCalls.TOKEN);
    // The raw token is the core's to mask; nothing derived to report.
    assertThat(ctx.tainted).isEmpty();
  }

  @Test
  void aWrongTokenFailsTheTestWithWhatToCheck() {
    on("GET /rest/servicedeskapi/info", 200, "{\"version\":\"5.25.0\"}");
    on("GET /rest/api/3/myself", 401, "{}");
    TestResult t = v13.test(TestCalls.cloud(base), new TestCalls.Secrets(), new TestCalls.Context(false));

    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("auth");
    assertThat(t.error().message()).contains("check the email and the token");
  }

  @Test
  void anUnknownToolIsDeniedByClassify() {
    Classification c = v12.classify(TestCalls.call("jira_request_sla", "{\"issueKey\":\"SD-1\"}"),
        TestCalls.cloud(base), new PolicyView(Env.DEV, "dev.jira", null, null));
    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo("dev.jira.unknown-tool");
  }
}
