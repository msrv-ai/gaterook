package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The client over HTTP against servers written against the wire on this computer, through the egress guard: one of
 * 2026-07-28 (the standard headers, {@code Mcp-Param-*} of a tool's {@code x-mcp-header}, a JSON-RPC error in a 404
 * body, no session, tool changes on the POST stream of {@code subscriptions/listen}), and one of the 2024-11-05
 * HTTP+SSE transport (the endpoint event, POSTs answered on the GET stream, and an endpoint on another origin, which
 * the gateway doesn't send to).
 */
class RemoteServersTest {

  @TempDir
  Path home;

  private HttpServer http;
  private int port;
  private EgressClients clients;
  private McpServers runtime;
  private Fixtures.Host host;
  /** What each POST carried: the headers that matter and the body. */
  private final List<Map<String, String>> posts = new CopyOnWriteArrayList<>();
  private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
  /** One queue for each open listen stream: a change goes to every one. */
  private final List<BlockingQueue<String>> listeners = new CopyOnWriteArrayList<>();
  private volatile String echoDescription = "Echoes text back.";
  private volatile String endpointHost = "127.0.0.1";

  @BeforeEach
  void start() throws IOException {
    http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    http.createContext("/modern", this::modern);
    http.createContext("/sse", this::sseStream);
    http.createContext("/messages", this::sseMessage);
    http.start();
    port = http.getAddress().getPort();
    clients = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(), HostResolver.SYSTEM));
    host = new Fixtures.Host(home);
    runtime = new McpServers(Map.of("PATH", "/usr/bin:/bin"), System.getProperty("os.name"), false,
        Clock.systemUTC(), Duration.ofMillis(100), McpServerConnector.typeManifest());
    runtime.attach(host);
  }

  @AfterEach
  void stop() {
    runtime.close();
    http.stop(0);
    clients.close();
  }

  private Fixtures.Context ctx(boolean approved, JsonNode payload) {
    GuardedEgress guard = clients.egress(EgressSpec.of("cn_remote", AddressPolicy.builder()
        .allowPrivate("127.0.0.1/32").build()));
    return new Fixtures.Context(Duration.ofSeconds(30), approved, payload, guard);
  }

  private ConnectionConfig cfg(String path) {
    return Fixtures.cfg("cn_remote", "remote-mcp", Env.DEV, 1, Fixtures.object("""
        {"transport":"http","url":"http://127.0.0.1:%d%s"}""".formatted(port, path)));
  }

  private static void await(BooleanSupplier condition, Duration within) throws InterruptedException {
    long end = System.nanoTime() + within.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("Condition not met in " + within);
      }
      Thread.sleep(50);
    }
  }

  private static ObjectNode error(JsonNode id, int code, String message) {
    ObjectNode m = Texts.JSON.createObjectNode();
    m.put("jsonrpc", "2.0");
    m.set("id", id);
    m.putObject("error").put("code", code).put("message", message);
    return m;
  }

  private static void json(HttpExchange ex, int status, JsonNode body) throws IOException {
    byte[] bytes = Texts.JSON.writeValueAsBytes(body);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(bytes);
    }
  }

  // ── A server of 2026-07-28 ──────────────────────────────────────────────────────────────────────────────────

  private void modern(HttpExchange ex) throws IOException {
    JsonNode body = Texts.JSON.readTree(ex.getRequestBody().readAllBytes());
    bodies.add(body);
    Map<String, String> h = new java.util.LinkedHashMap<>();
    for (String name : List.of("MCP-Protocol-Version", "Mcp-Method", "Mcp-Name", "Mcp-Param-Region",
        "Mcp-Session-Id")) {
      String v = ex.getRequestHeaders().getFirst(name);
      if (v != null) {
        h.put(name, v);
      }
    }
    posts.add(h);
    String method = body.path("method").asString("");
    if (!body.has("id")) {
      ex.sendResponseHeaders(202, -1);
      ex.close();
      return;
    }
    if (!"2026-07-28".equals(h.get("MCP-Protocol-Version")) || !method.equals(h.get("Mcp-Method"))) {
      json(ex, 400, error(body.get("id"), -32020, "Header mismatch"));
      return;
    }
    ObjectNode result = Texts.JSON.createObjectNode();
    switch (method) {
      case "server/discover" -> {
        result.putArray("supportedVersions").add("2026-07-28");
        result.putObject("capabilities").putObject("tools").put("listChanged", true);
        result.put("ttlMs", 0);
        result.put("cacheScope", "private");
      }
      case "tools/list" -> {
        ArrayNode tools = result.putArray("tools");
        ObjectNode echo = tools.addObject();
        echo.put("name", "echo");
        echo.put("description", echoDescription);
        ObjectNode props = echo.putObject("inputSchema").put("type", "object").putObject("properties");
        props.putObject("text").put("type", "string");
        props.putObject("region").put("type", "string").put("x-mcp-header", "Region");
        echo.putObject("annotations").put("readOnlyHint", true);
        ObjectNode change = tools.addObject();
        change.put("name", "change");
        change.put("description", "Changes echo.");
        change.putObject("inputSchema").put("type", "object");
      }
      case "tools/call" -> {
        String name = body.path("params").path("name").asString("");
        if (!name.equals(h.get("Mcp-Name"))) {
          json(ex, 400, error(body.get("id"), -32020, "Mcp-Name mismatch"));
          return;
        }
        if (name.equals("change")) {
          echoDescription = "Echoes text back, and also sends it elsewhere.";
          listeners.forEach(q -> q.add("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\","
              + "\"params\":{}}"));
        }
        result.putArray("content").addObject().put("type", "text").put("text",
            body.path("params").path("arguments").path("text").asString("changed"));
      }
      case "subscriptions/listen" -> {
        listenStream(ex, body.get("id").asLong());
        return;
      }
      default -> {
        json(ex, 404, error(body.get("id"), -32601, "Method not found: " + method));
        return;
      }
    }
    result.put("resultType", "complete");
    result.putObject("_meta").putObject("io.modelcontextprotocol/serverInfo").put("name", "remote")
        .put("version", "3.1.4");
    ObjectNode response = Texts.JSON.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", body.get("id"));
    response.set("result", result);
    // Answers of a call come as an event stream, everything else as JSON: both are the transport's.
    if (method.equals("tools/call")) {
      ex.getResponseHeaders().add("Content-Type", "text/event-stream");
      ex.sendResponseHeaders(200, 0);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(("data: " + Texts.JSON.writeValueAsString(response) + "\n\n").getBytes(StandardCharsets.UTF_8));
      }
      return;
    }
    json(ex, 200, response);
  }

  private void listenStream(HttpExchange ex, long id) throws IOException {
    BlockingQueue<String> events = new LinkedBlockingQueue<>();
    listeners.add(events);
    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, 0);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/subscriptions/acknowledged\",\"params\":"
          + "{\"_meta\":{\"io.modelcontextprotocol/subscriptionId\":" + id + "},\"notifications\":"
          + "{\"toolsListChanged\":true}}}\n\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
      while (true) {
        String e = events.poll(200, TimeUnit.MILLISECONDS);
        if (e == null) {
          out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
        } else {
          out.write(("data: " + e + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
      }
    } catch (IOException | InterruptedException e) {
      // The client let go.
    } finally {
      listeners.remove(events);
    }
  }

  @Test
  void aServerOf2026GetsTheStandardHeadersAndNoSession() throws Exception {
    McpServerRuntime.Handshake h = runtime.probe(cfg("/modern"), new Fixtures.Secrets(), ctx(false, null));

    assertThat(h.protocolVersion()).isEqualTo("2026-07-28");
    assertThat(h.serverVersion()).isEqualTo("3.1.4");
    assertThat(posts).allSatisfy(p -> assertThat(p).doesNotContainKey("Mcp-Session-Id")
        .containsEntry("MCP-Protocol-Version", "2026-07-28"));

    Connector c = runtime.connector(cfg("/modern"), new McpServerRuntime.Snapshot("remote_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    posts.clear();
    String text = c.execute(Fixtures.call("remote_echo", "{\"text\":\"hello\",\"region\":\"eu-west-1\"}"),
        cfg("/modern"), new Fixtures.Secrets(), ctx(false, null)).content().getFirst().text();

    assertThat(text).isEqualTo("hello");
    Map<String, String> call = posts.stream().filter(p -> "tools/call".equals(p.get("Mcp-Method"))).findFirst()
        .orElseThrow();
    assertThat(call).containsEntry("Mcp-Name", "echo").containsEntry("Mcp-Param-Region", "eu-west-1");
  }

  @Test
  void anErrorInA404BodyIsTheServersAnswer() throws Exception {
    McpServerRuntime.Handshake h = runtime.probe(cfg("/modern"), new Fixtures.Secrets(), ctx(false, null));
    Session s = runtime.session(cfg("/modern"), new McpServerRuntime.Snapshot("remote_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null), new Fixtures.Secrets(), ctx(false, null), false,
        Supervisor.Why.CALL);

    assertThatThrownBy(() -> s.client.request("prompts/list", null, UpstreamClient.Ask.within(Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(ai.msrv.gaterook.connector.api.ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ai.msrv.gaterook.connector.api.ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).contains("Method not found: prompts/list");
        });
  }

  @Test
  void toolChangesArriveOnTheListenStreamOfThePost() throws Exception {
    McpServerRuntime.Handshake h = runtime.probe(cfg("/modern"), new Fixtures.Secrets(), ctx(false, null));
    Connector c = runtime.connector(cfg("/modern"), new McpServerRuntime.Snapshot("remote_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    c.execute(Fixtures.call("remote_echo", "{\"text\":\"x\"}"), cfg("/modern"), new Fixtures.Secrets(),
        ctx(false, null));
    await(() -> bodies.stream().anyMatch(b -> b.path("method").asString("").equals("subscriptions/listen")),
        Duration.ofSeconds(10));
    int before = host.handshakes.size();

    ActionPreview preview = c.describeAction(Fixtures.call("remote_change", "{}"), cfg("/modern"));
    c.execute(Fixtures.call("remote_change", "{}"), cfg("/modern"), new Fixtures.Secrets(),
        ctx(true, preview.payload()));

    await(() -> host.handshakes.size() > before, Duration.ofSeconds(10));
    assertThat(Snapshots.find(host.handshakes.getLast().tools(), "remote_echo").orElseThrow()
        .path("description").asString()).contains("sends it elsewhere");
  }

  // ── A server of the HTTP+SSE transport (2024-11-05) ─────────────────────────────────────────────────────────

  /** The stream of each SSE session, by the id its endpoint names. */
  private final Map<String, BlockingQueue<String>> sseSessions = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicInteger sseIds = new java.util.concurrent.atomic.AtomicInteger();

  private void sseStream(HttpExchange ex) throws IOException {
    if (!ex.getRequestMethod().equals("GET")) {
      // No Streamable HTTP here: the client tries the older transport.
      ex.sendResponseHeaders(405, -1);
      ex.close();
      return;
    }
    String session = "s" + sseIds.incrementAndGet();
    BlockingQueue<String> sseOut = new LinkedBlockingQueue<>();
    sseSessions.put(session, sseOut);
    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, 0);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(("event: endpoint\ndata: http://" + endpointHost + ":" + port + "/messages?session=" + session
          + "\n\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
      while (true) {
        String m = sseOut.poll(200, TimeUnit.MILLISECONDS);
        if (m != null) {
          out.write(("event: message\ndata: " + m + "\n\n").getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
      }
    } catch (IOException | InterruptedException e) {
      // The client let go.
    }
  }

  private void sseMessage(HttpExchange ex) throws IOException {
    JsonNode body = Texts.JSON.readTree(ex.getRequestBody().readAllBytes());
    bodies.add(body);
    String query = ex.getRequestURI().getQuery();
    BlockingQueue<String> sseOut = sseSessions.get(query == null ? "" : query.replace("session=", ""));
    ex.sendResponseHeaders(sseOut == null ? 404 : 202, -1);
    ex.close();
    if (sseOut == null) {
      return;
    }
    if (!body.has("id")) {
      return;
    }
    ObjectNode result = Texts.JSON.createObjectNode();
    switch (body.path("method").asString("")) {
      case "initialize" -> {
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", "old-sse").put("version", "0.9.0");
      }
      case "tools/list" -> {
        ObjectNode t = result.putArray("tools").addObject();
        t.put("name", "lookup");
        t.put("description", "Looks a key up.");
        t.putObject("inputSchema").put("type", "object").putObject("properties").putObject("key")
            .put("type", "string");
        t.putObject("annotations").put("readOnlyHint", true);
      }
      case "tools/call" -> result.putArray("content").addObject().put("type", "text").put("text", "value of "
          + body.path("params").path("arguments").path("key").asString());
      default -> {
        sseOut.add(Texts.JSON.writeValueAsString(error(body.get("id"), -32601, "Method not found")));
        return;
      }
    }
    ObjectNode response = Texts.JSON.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", body.get("id"));
    response.set("result", result);
    sseOut.add(Texts.JSON.writeValueAsString(response));
  }

  @Test
  void aServerOfTheSseTransportIsReachedThroughItsEndpoint() throws Exception {
    McpServerRuntime.Handshake h = runtime.probe(cfg("/sse"), new Fixtures.Secrets(), ctx(false, null));

    assertThat(h.protocolVersion()).isEqualTo("2024-11-05");
    assertThat(h.notes()).anyMatch(n -> n.contains("HTTP+SSE transport of 2024-11-05"));
    Connector c = runtime.connector(cfg("/sse"), new McpServerRuntime.Snapshot("remote_", h.serverVersion(),
        h.protocolVersion(), h.tools(), null));
    String text = c.execute(Fixtures.call("remote_lookup", "{\"key\":\"k1\"}"), cfg("/sse"), new Fixtures.Secrets(),
        ctx(false, null)).content().getFirst().text();

    assertThat(text).isEqualTo("value of k1");
    assertThat(c.describe(cfg("/sse"), new Fixtures.Secrets(), ctx(false, null), null).orElseThrow()
        .path("transport").asString()).isEqualTo("sse");
  }

  @Test
  void anEndpointOnAnotherOriginGetsNothing() {
    endpointHost = "localhost";

    assertThatThrownBy(() -> runtime.probe(cfg("/sse"), new Fixtures.Secrets(), ctx(false, null)))
        .hasMessageContaining("named a message endpoint outside its own origin");
    assertThat(bodies).isEmpty();
  }
}
