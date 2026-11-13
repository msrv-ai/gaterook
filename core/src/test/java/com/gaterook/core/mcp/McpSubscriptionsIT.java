package com.gaterook.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition.Mode;
import com.gaterook.core.connectors.ConnectionNotified;
import com.gaterook.core.endpoints.EndpointConnections;
import com.gaterook.core.endpoints.EndpointService;
import com.gaterook.core.endpoints.EndpointTools;
import com.gaterook.core.endpoints.TokenService;
import com.gaterook.core.platform.LocalListener;
import com.gaterook.testfixtures.FakeConnectors;
import com.gaterook.testfixtures.TestCore;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a client hears outside its calls (ARCHITECTURE.md §5.5): a 2026-07-28 {@code subscriptions/listen} stream that
 * acknowledges what it honors and carries the lists' changes and the updates of the resources it subscribed to, each
 * with its id, and a 2025 client's {@code resources/subscribe} whose updates come on its GET stream. The server behind
 * the resources is told what its clients subscribe to, and when nobody does any more.
 */
class McpSubscriptionsIT {

  static final String CONNECTION = "listen-mcp";
  static final String WELCOME = "mcpgw://connections/listen-mcp/mcp/notes://welcome";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final HttpClient HTTP = HttpClient.newHttpClient();

  static TestCore core;
  static McpClient dev;
  static String token;
  static int port;

  @BeforeAll
  static void start() {
    // Keepalives often enough that a closed stream is noticed within the test's waits.
    core = TestCore.start("subscriptions", "--gateway.mcp.heartbeat=300ms");
    core.connection(CONNECTION, "mcpfake", Env.DEV, "share.internal");
    EndpointService endpoints = core.bean(EndpointService.class);
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    core.bean(EndpointConnections.class).add("dev", CONNECTION, null);
    port = core.bean(LocalListener.class).port();
    token = core.bean(TokenService.class).issue("dev", "listener", 7, null).token();
    dev = new McpClient(port, "dev", token);
  }

  @AfterAll
  static void stop() {
    if (core != null) {
      core.close();
    }
  }

  static void notify(ConnectionNotified.Kind kind, String uri) {
    String id = core.jdbc().queryForObject("select id from connection where name = ?", String.class, CONNECTION);
    core.context().publishEvent(new ConnectionNotified(id, kind, uri));
  }

  static void await(BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition within 10 s").isLessThan(end);
      Thread.sleep(50);
    }
  }

  @Test
  void aListenStreamAcknowledgesWhatItHonorsAndCarriesItWithItsId() throws Exception {
    ObjectNode params = JsonNodeFactory.instance.objectNode();
    params.putObject("notifications").put("toolsListChanged", true).put("resourcesListChanged", true)
        .putArray("resourceSubscriptions").add(WELCOME).add("mcpgw://connections/someone-else/mcp/x");
    ObjectNode meta = params.putObject("_meta");
    meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
    meta.putObject("io.modelcontextprotocol/clientCapabilities");
    ObjectNode body = dev.request("subscriptions/listen", params);
    long id = body.path("id").asLong();
    HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
        + "/mcp/dev")).header("Content-Type", "application/json").header("Accept", "text/event-stream")
        .header("Authorization", "Bearer " + token).header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", "subscriptions/listen").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build(), HttpResponse.BodyHandlers.ofInputStream());
    assertThat(r.statusCode()).isEqualTo(200);
    try (Events events = new Events(r.body())) {
      JsonNode ack = events.next();
      assertThat(ack.path("method").asString()).isEqualTo("notifications/subscriptions/acknowledged");
      assertThat(ack.path("params").path("_meta").path("io.modelcontextprotocol/subscriptionId").asLong())
          .isEqualTo(id);
      assertThat(ack.path("params").path("notifications").path("resourceSubscriptions").toString())
          .isEqualTo("[\"" + WELCOME + "\"]");
      assertThat(ack.path("params").path("notifications").has("promptsListChanged")).isFalse();
      SpecSchemas.assertValid("2026-07-28", "SubscriptionsAcknowledgedNotification", ack);
      await(() -> Set.of(WELCOME).equals(FakeConnectors.SUBSCRIBED.get(CONNECTION)));

      notify(ConnectionNotified.Kind.RESOURCE_UPDATED, WELCOME);
      JsonNode updated = events.next();
      assertThat(updated.path("method").asString()).isEqualTo("notifications/resources/updated");
      assertThat(updated.path("params").path("uri").asString()).isEqualTo(WELCOME);
      assertThat(updated.path("params").path("_meta").path("io.modelcontextprotocol/subscriptionId").asLong())
          .isEqualTo(id);
      SpecSchemas.assertValid("2026-07-28", "ResourceUpdatedNotification", updated);

      // Not subscribed, and a list it didn't opt in to: nothing.
      notify(ConnectionNotified.Kind.RESOURCE_UPDATED, "mcpgw://connections/listen-mcp/mcp/notes://other");
      notify(ConnectionNotified.Kind.PROMPTS_CHANGED, null);
      notify(ConnectionNotified.Kind.RESOURCES_CHANGED, null);
      JsonNode changed = events.next();
      assertThat(changed.path("method").asString()).isEqualTo("notifications/resources/list_changed");

      // A tool turned on or off changes the list the endpoint publishes.
      EndpointService endpoints = core.bean(EndpointService.class);
      var pair = endpoints.snapshot("dev").pairs().stream().filter(p -> p.tool().name().equals("gh_search"))
          .findFirst().orElseThrow();
      core.bean(EndpointTools.class).setMode("dev", pair.toolKey(), CONNECTION, pair.mode() == Mode.OFF
          ? Mode.APPROVAL : Mode.OFF, null, null, pair.rowVersion(), "Test");
      JsonNode tools = events.next();
      assertThat(tools.path("method").asString()).isEqualTo("notifications/tools/list_changed");
      assertThat(tools.path("params").path("_meta").path("io.modelcontextprotocol/subscriptionId").asLong())
          .isEqualTo(id);
    }
    // The client closed its stream: nobody subscribes any more, and the server is told.
    await(() -> FakeConnectors.SUBSCRIBED.getOrDefault(CONNECTION, Set.of()).isEmpty());
  }

  @Test
  void aLegacyClientSubscribesAndHearsTheUpdatesOnItsGetStream() throws Exception {
    String legacyToken = core.bean(TokenService.class).issue("dev", "legacy", 7, null).token();
    McpClient legacy = dev.as(legacyToken);
    JsonNode init = legacy.initialize("2025-11-25").result();
    assertThat(init.path("capabilities").path("resources").path("subscribe").asBoolean()).isTrue();
    assertThat(init.path("capabilities").path("resources").path("listChanged").asBoolean()).isTrue();
    assertThat(legacy.legacy("resources/subscribe", JsonNodeFactory.instance.objectNode().put("uri", WELCOME))
        .result().isEmpty()).isTrue();
    assertThat(legacy.legacy("resources/subscribe", JsonNodeFactory.instance.objectNode()
        .put("uri", "mcpgw://connections/someone-else/mcp/x")).error().path("code").asInt()).isEqualTo(-32002);

    HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
        + "/mcp/dev")).header("Accept", "text/event-stream").header("Authorization", "Bearer " + legacyToken)
        .header("MCP-Protocol-Version", "2025-11-25").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    try (Events events = new Events(r.body())) {
      assertThat(events.next().path("method").asString()).isEqualTo("notifications/tools/list_changed");
      notify(ConnectionNotified.Kind.RESOURCE_UPDATED, WELCOME);
      JsonNode updated = events.next();
      assertThat(updated.path("method").asString()).isEqualTo("notifications/resources/updated");
      assertThat(updated.path("params").path("uri").asString()).isEqualTo(WELCOME);
    }
    assertThat(legacy.legacy("resources/unsubscribe", JsonNodeFactory.instance.objectNode().put("uri", WELCOME))
        .result().isEmpty()).isTrue();
    await(() -> FakeConnectors.SUBSCRIBED.getOrDefault(CONNECTION, Set.of()).isEmpty());
  }

  /** The messages of an event stream, read in the background so that every wait is bounded. */
  static final class Events implements AutoCloseable {
    private final InputStream body;
    private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();

    Events(InputStream body) {
      this.body = body;
      Thread.ofVirtual().start(() -> {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
          for (String line; (line = lines.readLine()) != null;) {
            if (line.startsWith("data:")) {
              messages.add(JSON.readTree(line.substring(5).strip()));
            }
          }
        } catch (Exception e) {
          // Closed.
        }
      });
    }

    JsonNode next() throws InterruptedException {
      JsonNode m = messages.poll(10, TimeUnit.SECONDS);
      assertThat(m).as("a message within 10 s").isNotNull();
      return m;
    }

    @Override
    public void close() throws Exception {
      body.close();
    }
  }
}
