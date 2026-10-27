package gaterook.plugins.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * The plugin against RabbitMQ's management API in MockWebServer (step K6): the paths it asks (the virtual host
 * {@code /} as {@code %2F}), Basic credentials, how queues, consumers and dead letters read, that a publish needs the
 * approval and sends exactly the message shown, and what RabbitMQ's refusals mean for the agent.
 */
class RabbitMqConnectorTest {

  private MockWebServer server;
  private final Map<String, MockResponse> routes = new ConcurrentHashMap<>();
  private final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
  private final RabbitMqConnector connector = new RabbitMqConnector(TestCalls.manifest());

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
        return r != null ? r : new MockResponse.Builder().code(404)
            .body("{\"error\":\"Object Not Found\",\"reason\":\"Not Found\"}").build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  @AfterEach
  void stop() throws Exception {
    server.close();
  }

  private void on(String route, int code, String json) {
    routes.put(route, new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
        .body(json).build());
  }

  @Test
  void queuesAreListedDeepestFirstWithTheirDeadLetterExchange() throws Exception {
    on("GET /api/queues/%2F", 200, """
        {"filtered_count":3,"item_count":2,"page":1,"page_count":2,"page_size":2,"total_count":3,"items":[
         {"name":"orders","messages":1200,"messages_ready":1100,"messages_unacknowledged":100,"consumers":3,
          "state":"running","type":"quorum","arguments":{"x-dead-letter-exchange":"dlx"}},
         {"name":"emails","messages":7,"messages_ready":7,"messages_unacknowledged":0,"consumers":0,
          "state":"idle","type":"classic","arguments":{},"effective_policy_definition":{"dead-letter-exchange":""}}]}""");
    ToolResult r = connector.execute(TestCalls.call("rabbitmq_queues", "{\"name\":\"o r\",\"limit\":2}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));

    RecordedRequest req = seen.getFirst();
    assertThat(req.getTarget()).startsWith("/api/queues/%2F?page=1&page_size=2&sort=messages&sort_reverse=true")
        .contains("&name=o+r&use_regex=false");
    String basic = Base64.getEncoder().encodeToString(("gw-monitor:" + TestCalls.PASSWORD)
        .getBytes(StandardCharsets.UTF_8));
    assertThat(req.getHeaders().get("Authorization")).isEqualTo("Basic " + basic);

    assertThat(r.isError()).isFalse();
    assertThat(r.table().rows()).hasSize(2);
    assertThat(r.table().rows().get(0).get(0).asString()).isEqualTo("orders");
    assertThat(r.table().rows().get(0).get(1).asLong()).isEqualTo(1200);
    assertThat(r.table().rows().get(0).get(7).asString()).isEqualTo("dlx");
    assertThat(r.table().rows().get(1).get(7).asString()).isEqualTo("(default)");
    assertThat(r.truncated()).isTrue();
    assertThat(r.structuredContent().path("messages").asLong()).isEqualTo(1207);
  }

  @Test
  void consumersCanBeLimitedToOneQueue() throws Exception {
    on("GET /api/consumers/%2F", 200, """
        [{"consumer_tag":"amq.ctag-1","queue":{"name":"orders","vhost":"/"},"ack_required":true,"prefetch_count":50,
          "active":true,"channel_details":{"connection_name":"10.0.0.4:51234 -> 10.0.0.9:5672","peer_host":"10.0.0.4"}},
         {"consumer_tag":"amq.ctag-2","queue":{"name":"emails","vhost":"/"},"ack_required":false,"prefetch_count":0,
          "active":false,"channel_details":{"connection_name":"mailer","peer_host":"10.0.0.5"}}]""");
    ToolResult r = connector.execute(TestCalls.call("rabbitmq_consumers", "{\"queue\":\"orders\"}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(r.table().rows()).hasSize(1);
    JsonNode row = r.table().rows().get(0);
    assertThat(row.get(1).asString()).isEqualTo("amq.ctag-1");
    assertThat(row.get(4).asLong()).isEqualTo(50);
    assertThat(row.get(5).asString()).isEqualTo("manual");
    assertThat(r.structuredContent().path("consumers").asInt()).isEqualTo(1);
  }

  @Test
  void deadLettersFollowTheExchangesAsTheirTypesRoute() throws Exception {
    on("GET /api/queues/%2F", 200, """
        {"page":1,"page_count":1,"items":[
         {"name":"orders","messages":10,"arguments":{"x-dead-letter-exchange":"dlx","x-dead-letter-routing-key":"orders.failed"}},
         {"name":"payments","messages":4,"arguments":{"x-dead-letter-exchange":"dlx"}},
         {"name":"emails","messages":2,"arguments":{"x-dead-letter-exchange":""}, "effective_policy_definition":{}},
         {"name":"sms","messages":0,"arguments":{"x-dead-letter-exchange":"","x-dead-letter-routing-key":"sms.dlq"}},
         {"name":"push","messages":0,"arguments":{"x-dead-letter-exchange":"","x-dead-letter-routing-key":"gone"}},
         {"name":"audit","messages":1,"effective_policy_definition":{"dead-letter-exchange":"nowhere"}},
         {"name":"orders.dlq","messages":42,"messages_ready":40,"consumers":0,"arguments":{}},
         {"name":"payments.dlq","messages":3,"messages_ready":3,"consumers":1,"arguments":{}},
         {"name":"sms.dlq","messages":5,"messages_ready":5,"consumers":0,"arguments":{}}]}""");
    on("GET /api/exchanges/%2F", 200, """
        [{"name":"dlx","type":"topic"},{"name":"nowhere","type":"direct"},{"name":"","type":"direct"}]""");
    on("GET /api/bindings/%2F", 200, """
        [{"source":"dlx","destination":"orders.dlq","destination_type":"queue","routing_key":"orders.*"},
         {"source":"dlx","destination":"payments.dlq","destination_type":"queue","routing_key":"payments"},
         {"source":"dlx","destination":"archive","destination_type":"exchange","routing_key":"#"}]""");
    ToolResult r = connector.execute(TestCalls.call("rabbitmq_dead_letters", "{}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));

    // orders keys its dead letters orders.failed (topic orders.* takes it); payments keeps the keys it was published
    // with, so any binding of dlx may take its dead letters.
    assertThat(r.table().rows()).extracting(row -> row.get(0).asString())
        .containsExactly("orders.dlq", "payments.dlq", "sms.dlq");
    assertThat(r.table().rows().get(0).get(1).asLong()).isEqualTo(42);
    assertThat(r.table().rows().get(0).get(5).asString()).isEqualTo("orders, payments");
    assertThat(r.table().rows().get(1).get(5).asString()).isEqualTo("payments");
    assertThat(r.table().rows().get(2).get(4).asString()).isEqualTo("(default)");
    JsonNode s = r.structuredContent();
    assertThat(s.path("sourceQueues").asInt()).isEqualTo(6);
    // push dead-letters to a queue that doesn't exist; audit to an exchange nothing is bound to.
    assertThat(s.path("unrouted")).extracting(u -> u.path("queue").asString()).containsExactly("push", "audit");
    assertThat(s.path("onward")).extracting(u -> u.path("continuesTo").asString()).containsExactly("archive",
        "archive");
    assertThat(seen).allSatisfy(req -> assertThat(req.getMethod()).isEqualTo("GET"));
  }

  @Test
  void topicMatchingFollowsAmqp() {
    assertThat(RabbitMqConnector.topicMatches("orders.*", "orders.failed")).isTrue();
    assertThat(RabbitMqConnector.topicMatches("orders.*", "orders.failed.twice")).isFalse();
    assertThat(RabbitMqConnector.topicMatches("orders.#", "orders.failed.twice")).isTrue();
    assertThat(RabbitMqConnector.topicMatches("#", "anything.at.all")).isTrue();
    assertThat(RabbitMqConnector.topicMatches("*.failed", "orders.failed")).isTrue();
    assertThat(RabbitMqConnector.topicMatches("payments", "orders")).isFalse();
  }

  @Test
  void aPublishRunsOnlyApprovedAndSendsTheMessageAsShown() throws Exception {
    PolicyView policy = new PolicyView(Env.DEV, "dev.rabbitmq", null, null);
    String args = "{\"exchange\":\"orders\",\"routingKey\":\"orders.retry\",\"payload\":\"{\\\"id\\\":48213}\","
        + "\"headers\":{\"x-retry\":\"1\"}}";
    assertThat(connector.classify(TestCalls.call("rabbitmq_publish", args), TestCalls.local(server.getPort()),
        policy).kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThatThrownBy(() -> connector.execute(TestCalls.call("rabbitmq_publish", args),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false)))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("approved");
    assertThat(seen).isEmpty();

    ActionPreview preview = connector.describeAction(TestCalls.call("rabbitmq_publish", args),
        TestCalls.local(server.getPort()));
    on("POST /api/exchanges/%2F/orders/publish", 200, "{\"routed\":true}");
    ToolResult r = connector.execute(TestCalls.call("rabbitmq_publish", args), TestCalls.local(server.getPort()),
        new TestCalls.Secrets(), new TestCalls.Context(true));

    RecordedRequest req = seen.getFirst();
    assertThat(req.getMethod()).isEqualTo("POST");
    String sent = req.getBody().utf8();
    assertThat(preview.text()).isEqualTo("POST http://127.0.0.1:" + server.getPort()
        + "/api/exchanges/%2F/orders/publish\n" + sent);
    JsonNode body = Manifests.JSON.readTree(sent);
    assertThat(body.path("routing_key").asString()).isEqualTo("orders.retry");
    assertThat(body.path("payload").asString()).isEqualTo("{\"id\":48213}");
    assertThat(body.path("payload_encoding").asString()).isEqualTo("string");
    assertThat(body.path("properties").path("delivery_mode").asInt()).isEqualTo(2);
    assertThat(body.path("properties").path("headers").path("x-retry").asString()).isEqualTo("1");
    assertThat(r.structuredContent().path("routed").asBoolean()).isTrue();
  }

  @Test
  void anUnroutedMessageAndAMissingExchangeAreSaid() throws Exception {
    on("POST /api/exchanges/%2F/amq.default/publish", 200, "{\"routed\":false}");
    ToolResult dropped = connector.execute(TestCalls.call("rabbitmq_publish",
        "{\"routingKey\":\"no-such-queue\",\"payload\":\"x\"}"), TestCalls.local(server.getPort()),
        new TestCalls.Secrets(), new TestCalls.Context(true));
    assertThat(dropped.isError()).isFalse();
    assertThat(dropped.content().getFirst().text()).contains("no queue is bound").contains("dropped");

    ToolResult missing = connector.execute(TestCalls.call("rabbitmq_publish",
        "{\"exchange\":\"ghost\",\"routingKey\":\"k\",\"payload\":\"x\"}"), TestCalls.local(server.getPort()),
        new TestCalls.Secrets(), new TestCalls.Context(true));
    assertThat(missing.isError()).isTrue();
    assertThat(missing.content().getFirst().text()).isEqualTo("The exchange ghost doesn't exist in the virtual host /.");
  }

  @Test
  void aBrokenPublishIsAnUnknownOutcomeNotARetry() {
    routes.put("POST /api/exchanges/%2F/orders/publish", new MockResponse.Builder()
        .onResponseStart(new SocketEffect.CloseSocket()).build());
    assertThatThrownBy(() -> connector.execute(TestCalls.call("rabbitmq_publish",
        "{\"exchange\":\"orders\",\"routingKey\":\"k\",\"payload\":\"x\"}"), TestCalls.local(server.getPort()),
        new TestCalls.Secrets(), new TestCalls.Context(true)))
        .isInstanceOf(ConnectorException.class)
        .satisfies(e -> assertThat(((ConnectorException) e).kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
  }

  @Test
  void refusalsAreTheAgentsAndOutagesTheConnections() throws Exception {
    on("GET /api/consumers/%2F", 401, "{\"error\":\"not_authorised\",\"reason\":\"Login failed\"}");
    ToolResult auth = connector.execute(TestCalls.call("rabbitmq_consumers", "{}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false));
    assertThat(auth.content().getFirst().text()).isEqualTo("RabbitMQ rejected the user or the password. Test the "
        + "connection.");
    on("GET /api/consumers/%2F", 503, "<html>down</html>");
    assertThatThrownBy(() -> connector.execute(TestCalls.call("rabbitmq_consumers", "{}"),
        TestCalls.local(server.getPort()), new TestCalls.Secrets(), new TestCalls.Context(false)))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("HTTP 503");
  }

  @Test
  void testConnectionReadsTheVersionTheTagsAndTheWritePermission() {
    on("GET /api/overview", 200, "{\"rabbitmq_version\":\"4.1.4\",\"cluster_name\":\"rabbit@acme\"}");
    on("GET /api/whoami", 200, "{\"name\":\"gw-monitor\",\"tags\":[\"monitoring\"]}");
    on("GET /api/vhosts/%2F", 200, "{\"name\":\"/\"}");
    on("GET /api/permissions/%2F/gw-monitor", 200, "{\"configure\":\"\",\"write\":\"^orders$\",\"read\":\".*\"}");
    TestResult t = connector.test(TestCalls.local(server.getPort()), new TestCalls.Secrets(),
        new TestCalls.Context(false));
    assertThat(t.ok()).isTrue();
    assertThat(t.serverVersion()).isEqualTo("4.1.4");
    assertThat(t.identity()).isEqualTo("gw-monitor");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(t.permissions().write()).isEqualTo("publish to exchanges matching ^orders$");

    on("GET /api/vhosts/%2F", 404, "{\"error\":\"Object Not Found\",\"reason\":\"Not Found\"}");
    TestResult noVhost = connector.test(TestCalls.local(server.getPort()), new TestCalls.Secrets(),
        new TestCalls.Context(false));
    assertThat(noVhost.ok()).isFalse();
    assertThat(noVhost.error().code()).isEqualTo("no-vhost");
  }
}
