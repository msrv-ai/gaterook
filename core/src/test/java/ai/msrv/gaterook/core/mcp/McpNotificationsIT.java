package ai.msrv.gaterook.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointTools;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Real HTTP streams observe committed changes to an endpoint's published MCP tools. */
class McpNotificationsIT {

  private static TestCore core;
  private static HttpClient http;
  private static EndpointService endpoints;
  private static EndpointConnections wiring;
  private static EndpointTools modes;
  private static TokenService tokens;
  private static String origin;

  @BeforeAll
  static void start() {
    core = TestCore.start("mcp-notifications");
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    endpoints = core.bean(EndpointService.class);
    wiring = core.bean(EndpointConnections.class);
    modes = core.bean(EndpointTools.class);
    tokens = core.bean(TokenService.class);
    origin = core.bean(LocalListener.class).origin();
  }

  @AfterAll
  static void stop() {
    if (http != null) {
      http.shutdownNow();
    }
    if (core != null) {
      core.close();
    }
  }

  @Test
  void attachingAndRemovingConnectionsRefreshesToolsAndExistingConnectionEnums() throws Exception {
    Endpoint endpoint = endpoint("catalog");
    core.connection("catalog-ssh", "sshfake", Env.DEV, "ssh.example.test");
    core.connection("catalog-second", "pgfake", Env.DEV, "db.example.test");
    try (Stream stream = open(endpoint)) {
      wiring.add(endpoint.slug(), "catalog-ssh", null);
      stream.awaitChange();
      JsonNode list = endpoint.tools();
      assertThat(names(list)).contains("ssh_run", "sql_query", "describe_connection");
      assertThat(connections(list, "describe_connection")).containsExactly("catalog-db", "catalog-ssh");

      // No new tool names: a client must still refresh the schemas of its existing tools.
      wiring.add(endpoint.slug(), "catalog-second", null);
      stream.awaitChange();
      list = endpoint.tools();
      assertThat(connections(list, "sql_query")).containsExactly("catalog-db", "catalog-second");
      assertThat(connections(list, "describe_connection"))
          .containsExactly("catalog-db", "catalog-second", "catalog-ssh");

      wiring.remove(endpoint.slug(), "catalog-ssh", null);
      stream.awaitChange();
      list = endpoint.tools();
      assertThat(names(list)).doesNotContain("ssh_run");
      assertThat(connections(list, "describe_connection")).containsExactly("catalog-db", "catalog-second");
      stream.assertQuiet();
    }
  }

  @Test
  void switchingToolsOffAndBackOnNotifiesConnectedClients() throws Exception {
    Endpoint endpoint = endpoint("modes");
    try (Stream stream = open(endpoint)) {
      setMode(endpoint, "sql_query", Mode.OFF);
      stream.awaitChange();
      assertThat(names(endpoint.tools())).doesNotContain("sql_query");

      setMode(endpoint, "sql_query", Mode.READ_ONLY);
      stream.awaitChange();
      assertThat(names(endpoint.tools())).contains("sql_query");
      stream.assertQuiet();
    }
  }

  @Test
  void everyConnectedClientOfTheAffectedEndpointReceivesTheChange() throws Exception {
    Endpoint endpoint = endpoint("fanout");
    Endpoint secondClient = new Endpoint(endpoint.slug(), tokens.issue(endpoint.slug(), "second", 7, null));
    Endpoint other = endpoint("isolated");
    core.connection("fanout-second", "pgfake", Env.DEV, "db.example.test");
    try (Stream first = open(endpoint); Stream second = open(secondClient); Stream unrelated = open(other)) {
      wiring.add(endpoint.slug(), "fanout-second", null);
      first.awaitChange();
      second.awaitChange();
      unrelated.assertQuiet();
      first.assertQuiet();
      second.assertQuiet();
    }
  }

  @Test
  void theJavaSdkAutomaticallyRefreshesToolsOverItsStatelessNotificationStream() throws Exception {
    Endpoint endpoint = endpoint("sdk-refresh");
    core.connection("sdk-refresh-ssh", "sshfake", Env.DEV, "ssh.example.test");
    BlockingQueue<List<io.modelcontextprotocol.spec.McpSchema.Tool>> changes = new LinkedBlockingQueue<>();
    var transport = io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.builder(origin)
        .endpoint("/mcp/" + endpoint.slug())
        .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + endpoint.token().token()))
        .build();
    try (var client = io.modelcontextprotocol.client.McpClient.sync(transport)
        .requestTimeout(Duration.ofSeconds(10)).toolsChangeConsumer(changes::add).build()) {
      assertThat(client.initialize().capabilities().tools().listChanged()).isTrue();
      List<io.modelcontextprotocol.spec.McpSchema.Tool> initial = changes.poll(10, TimeUnit.SECONDS);
      assertThat(initial).as("SDK initial tools refresh callback").isNotNull();
      assertThat(initial).extracting(io.modelcontextprotocol.spec.McpSchema.Tool::name)
          .doesNotContain("ssh_run");

      wiring.add(endpoint.slug(), "sdk-refresh-ssh", null);
      List<io.modelcontextprotocol.spec.McpSchema.Tool> refreshed = changes.poll(10, TimeUnit.SECONDS);
      assertThat(refreshed).as("SDK refreshed tools callback").isNotNull();
      assertThat(refreshed).extracting(io.modelcontextprotocol.spec.McpSchema.Tool::name).contains("ssh_run");
      var describe = refreshed.stream().filter(tool -> tool.name().equals("describe_connection"))
          .findFirst().orElseThrow();
      assertThat(McpClient.JSON.valueToTree(describe.inputSchema()).at("/properties/connection/enum"))
          .isEqualTo(McpClient.JSON.readTree("[\"sdk-refresh-db\",\"sdk-refresh-ssh\"]"));
    }
  }

  @Test
  void rolledBackChangesAndUnchangedCatalogsProduceNoNotification() throws Exception {
    Endpoint endpoint = endpoint("unchanged");
    core.connection("unchanged-second", "pgfake", Env.DEV, "db.example.test");
    try (Stream stream = open(endpoint)) {
      TransactionTemplate transaction = new TransactionTemplate(core.bean(PlatformTransactionManager.class));
      transaction.executeWithoutResult(status -> {
        wiring.add(endpoint.slug(), "unchanged-second", null);
        status.setRollbackOnly();
      });
      assertThat(connections(endpoint.tools(), "sql_query")).containsExactly("unchanged-db");
      stream.assertQuiet();

      endpoints.updateSettings(endpoint.slug(), new EndpointService.SettingsUpdate("Updated description", null,
          null, null, null, null, null), endpoints.get(endpoint.slug()).version(), null);
      stream.assertQuiet();

      // A connection outside this endpoint invalidates snapshots, but changes none of its published tools.
      core.connection("unwired", "pgfake", Env.DEV, "db.example.test");
      stream.assertQuiet();

      wiring.add(endpoint.slug(), "unchanged-second", null);
      stream.awaitChange();
      assertThat(connections(endpoint.tools(), "sql_query"))
          .containsExactly("unchanged-db", "unchanged-second");
    }
  }

  @Test
  void streamRequestsRequireEndpointCredentialsAndASupportedProtocolAndAcceptHeader() throws Exception {
    Endpoint endpoint = endpoint("transport");
    Endpoint other = endpoint("transport-other");
    assertThat(get(endpoint.slug(), null, "text/event-stream", Map.of()).statusCode()).isEqualTo(401);
    assertThat(get(endpoint.slug(), "gwt_invalid", "text/event-stream", Map.of()).statusCode()).isEqualTo(401);
    assertThat(get(endpoint.slug(), other.token().token(), "text/event-stream", Map.of()).statusCode())
        .isEqualTo(401);
    assertThat(get(endpoint.slug(), endpoint.token().token(), "application/json", Map.of()).statusCode())
        .isEqualTo(406);
    assertThat(get(endpoint.slug(), endpoint.token().token(), "text/event-stream",
        Map.of("MCP-Protocol-Version", "2027-01-01")).statusCode()).isEqualTo(400);
    assertThat(get(endpoint.slug(), endpoint.token().token(), "text/event-stream",
        Map.of("MCP-Protocol-Version", "2026-07-28")).statusCode()).isEqualTo(405);
    assertThat(get(endpoint.slug(), endpoint.token().token(), "text/event-stream",
        Map.of("Origin", "https://untrusted.example")).statusCode()).isEqualTo(403);

    try (Stream stream = open(endpoint, Map.of())) {
      // Omitting the version header retains MCP's 2025-03-26 compatibility default.
      setMode(endpoint, "sql_query", Mode.OFF);
      stream.awaitChange();
    }
  }

  @Test
  void revocationClosesTheOldStreamWithoutNotifyingIt() throws Exception {
    Endpoint endpoint = endpoint("revoked");
    Endpoint activeClient = new Endpoint(endpoint.slug(), tokens.issue(endpoint.slug(), "active", 7, null));
    try (Stream revoked = open(endpoint); Stream active = open(activeClient)) {
      tokens.revoke(endpoint.token().id(), null);
      setMode(endpoint, "sql_query", Mode.OFF);
      active.awaitChange();
      revoked.awaitClosed();
      revoked.assertQuiet();
    }
  }

  @Test
  void disablingAnEndpointClosesItsNotificationStream() throws Exception {
    Endpoint endpoint = endpoint("disabled");
    try (Stream stream = open(endpoint)) {
      endpoints.disable(endpoint.slug(), null);
      stream.awaitClosed();
      stream.assertQuiet();
    }
  }

  @Test
  void reconnectingRefreshesChangesThatHappenedWhileTheClientWasDisconnected() throws Exception {
    Endpoint endpoint = endpoint("reconnected");
    try (Stream first = open(endpoint)) {
      assertThat(names(endpoint.tools())).contains("sql_query");
      first.assertQuiet();
    }
    setMode(endpoint, "sql_query", Mode.OFF);
    // Opening the replacement stream must request a refresh without requiring another mutation.
    try (Stream reconnected = open(endpoint)) {
      assertThat(names(endpoint.tools())).doesNotContain("sql_query");
      reconnected.assertQuiet();
    }
  }

  private static Endpoint endpoint(String slug) {
    core.connection(slug + "-db", "pgfake", Env.DEV, "db.example.test");
    endpoints.create(slug, null, EndpointService.StartFrom.EMPTY, null);
    wiring.add(slug, slug + "-db", null);
    for (String tool : CoreTools.WITH_MODE) {
      var pair = endpoints.snapshot(slug).coreTool(tool).orElseThrow();
      modes.setMode(slug, tool, null, Mode.READ_ONLY, null, pair.rowVersion(), null);
    }
    return new Endpoint(slug, tokens.issue(slug, "notifications", 7, null));
  }

  private static void setMode(Endpoint endpoint, String tool, Mode mode) {
    var pair = endpoints.snapshot(endpoint.slug()).pairs().stream()
        .filter(p -> p.toolKey().equals(tool)).findFirst().orElseThrow();
    modes.setMode(endpoint.slug(), tool, pair.connectionName(), mode, null, pair.rowVersion(), null);
  }

  private record Endpoint(String slug, TokenService.Issued token) {
    JsonNode tools() {
      McpClient client = new McpClient(core.bean(LocalListener.class).port(), slug, token.token());
      McpClient.Answer answer = client.legacy("tools/list", null);
      assertThat(answer.status()).isEqualTo(200);
      return answer.result();
    }
  }

  private static List<String> names(JsonNode list) {
    List<String> names = new ArrayList<>();
    list.path("tools").forEach(tool -> names.add(tool.path("name").asString()));
    return names;
  }

  private static List<String> connections(JsonNode list, String name) {
    for (JsonNode tool : list.path("tools")) {
      if (name.equals(tool.path("name").asString())) {
        List<String> connections = new ArrayList<>();
        tool.at("/inputSchema/properties/connection/enum").forEach(value -> connections.add(value.asString()));
        return connections;
      }
    }
    throw new AssertionError("Tool not found: " + name);
  }

  private static HttpRequest request(String slug, String token, String accept, Map<String, String> headers) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + "/mcp/" + slug))
        .timeout(Duration.ofSeconds(10)).header("Accept", accept).GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    headers.forEach(request::header);
    return request.build();
  }

  private static HttpResponse<String> get(String slug, String token, String accept, Map<String, String> headers)
      throws Exception {
    return http.send(request(slug, token, accept, headers), HttpResponse.BodyHandlers.ofString());
  }

  private static Stream open(Endpoint endpoint) throws Exception {
    return open(endpoint, Map.of("MCP-Protocol-Version", "2025-11-25"));
  }

  private static Stream open(Endpoint endpoint, Map<String, String> headers) throws Exception {
    HttpResponse<InputStream> response = http.send(request(endpoint.slug(), endpoint.token().token(),
        "text/event-stream", headers), HttpResponse.BodyHandlers.ofInputStream());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
        assertThat(type).startsWith("text/event-stream"));
    assertThat(response.headers().firstValue("Mcp-Session-Id")).isEmpty();
    assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value ->
        assertThat(value).contains("no-store"));
    Stream stream = new Stream(response.body());
    try {
      // Each stream first invalidates any tools the client cached before opening or reconnecting.
      stream.awaitChange();
      return stream;
    } catch (Exception | AssertionError failure) {
      stream.close();
      throw failure;
    }
  }

  /** Background reading keeps every assertion bounded even when no SSE event arrives. */
  private static final class Stream implements AutoCloseable {
    private final InputStream body;
    private final BlockingQueue<String> data = new LinkedBlockingQueue<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Thread reader;

    Stream(InputStream body) {
      this.body = body;
      reader = Thread.ofVirtual().name("mcp-notification-test").start(() -> {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
          StringBuilder event = new StringBuilder();
          for (String line; (line = lines.readLine()) != null;) {
            if (line.isEmpty()) {
              if (!event.isEmpty()) {
                data.add(event.toString());
                event.setLength(0);
              }
            } else if (line.startsWith("data:")) {
              if (!event.isEmpty()) {
                event.append('\n');
              }
              event.append(line.substring(5).stripLeading());
            }
          }
        } catch (IOException ignored) {
          // Closing the client response also wakes a blocked read.
        } finally {
          closed.countDown();
        }
      });
    }

    void awaitChange() throws InterruptedException {
      String event = data.poll(10, TimeUnit.SECONDS);
      assertThat(event).as("tools/list_changed notification").isNotNull();
      assertThat(McpClient.JSON.readTree(event)).isEqualTo(McpClient.JSON.readTree(
          "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}"));
    }

    void assertQuiet() throws InterruptedException {
      assertThat(data.poll(400, TimeUnit.MILLISECONDS)).as("unexpected notification").isNull();
    }

    void awaitClosed() throws InterruptedException {
      assertThat(closed.await(10, TimeUnit.SECONDS)).as("stream closed").isTrue();
    }

    @Override
    public void close() throws IOException, InterruptedException {
      body.close();
      reader.join(Duration.ofSeconds(5));
      assertThat(reader.isAlive()).as("SSE reader stopped").isFalse();
    }
  }
}
