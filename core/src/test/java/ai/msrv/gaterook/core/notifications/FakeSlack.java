package ai.msrv.gaterook.core.notifications;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Slack's Web API and Socket Mode on MockWebServer (report 13 §8.2): {@code apps.connections.open} hands out a
 * {@code ws://} URL on the same server, whose upgrade sends {@code hello} and records the acks; the test pushes
 * envelopes through the server side of the socket. Every Web API call is recorded with its body and token.
 */
final class FakeSlack implements AutoCloseable {

  // Construct synthetic credentials for the local mock instead of storing secret-shaped token literals.
  static final String BOT = String.join("-", "xoxb", "1".repeat(10), "2".repeat(10), "k2BotTokenForTests");
  static final String APP = String.join("-", "xapp", "1", "A0MCPGWTEST", "3".repeat(10), "k2AppTokenForTests");
  static final String TEAM = "T0ACME00001";
  static final String APP_ID = "A0MCPGWTEST";
  static final String RAVI = "U0RAVI00001";
  static final String RAVI_DM = "D0RAVI00001";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One Web API call: the method, its arguments (JSON or form), the bearer token, what the fake answered. */
  record Call(String method, JsonNode args, String token, JsonNode answer) {}

  private record Reply(ObjectNode body, String scopes) {}

  final MockWebServer server = new MockWebServer();
  final List<Call> calls = new CopyOnWriteArrayList<>();
  final List<WebSocket> sockets = new CopyOnWriteArrayList<>();
  final List<String> acks = new CopyOnWriteArrayList<>();
  /** What the next hello says the app is; a test may make another app answer. */
  volatile String helloAppId = APP_ID;
  volatile boolean channelMember = true;
  private final AtomicInteger ts = new AtomicInteger(1000);
  private final AtomicInteger tickets = new AtomicInteger();
  private final AtomicInteger envelopes = new AtomicInteger();
  private final Map<String, String> emails = new ConcurrentHashMap<>(Map.of("ravi@acme.test", RAVI,
      "eve@acme.test", "U0EVE000001"));

  FakeSlack() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest r) {
        String path = r.getUrl().encodedPath();
        if (path.startsWith("/link/")) {
          return new MockResponse.Builder().webSocketUpgrade(new Socket()).build();
        }
        String method = path.substring(path.lastIndexOf('/') + 1);
        String auth = r.getHeaders().get("Authorization");
        String token = auth == null ? null : auth.replace("Bearer ", "");
        JsonNode args = args(r);
        Reply reply = answer(method, args, token);
        calls.add(new Call(method, args, token, reply.body()));
        MockResponse.Builder b = new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body(reply.body().toString());
        if (reply.scopes() != null) {
          b.addHeader("x-oauth-scopes", reply.scopes());
        }
        return b.build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  String apiBase() {
    return server.url("/api/").toString();
  }

  private Reply answer(String method, JsonNode a, String token) {
    boolean app = method.equals("apps.connections.open");
    if (!(app ? APP : BOT).equals(token)) {
      return refused("invalid_auth");
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode().put("ok", true);
    switch (method) {
      case "auth.test" -> {
        out.put("url", "https://acme.slack.com/").put("team", "acme").put("user", "mcp-gateway")
            .put("team_id", TEAM).put("user_id", "U0BOT000001").put("bot_id", "B0BOT000001");
        return new Reply(out, "chat:write,im:write,users:read,users:read.email,channels:read,groups:read");
      }
      case "apps.connections.open" -> out.put("url", "ws://127.0.0.1:" + server.getPort() + "/link/?ticket="
          + tickets.incrementAndGet());
      case "chat.postMessage" -> out.put("channel", a.path("channel").asString()).put("ts", "1792672452."
          + String.format("%06d", ts.incrementAndGet()));
      case "chat.update" -> out.put("channel", a.path("channel").asString()).put("ts", a.path("ts").asString());
      case "chat.postEphemeral" -> out.put("message_ts", "1792672452.999999");
      case "users.lookupByEmail" -> {
        String id = emails.get(a.path("email").asString());
        if (id == null) {
          return refused("users_not_found");
        }
        ObjectNode user = out.putObject("user").put("id", id).put("name", id.equals(RAVI) ? "ravi" : "eve")
            .put("real_name", id.equals(RAVI) ? "Ravi T." : "Eve");
        user.putObject("profile").put("real_name", id.equals(RAVI) ? "Ravi T." : "Eve")
            .put("display_name", id.equals(RAVI) ? "ravi" : "eve");
      }
      case "conversations.open" -> out.putObject("channel").put("id", a.path("users").asString().equals(RAVI)
          ? RAVI_DM : "D0EVE000001");
      case "conversations.info" -> out.putObject("channel").put("id", a.path("channel").asString())
          .put("name", "deploy-approvals").put("is_member", channelMember).put("is_private", false)
          .put("num_members", 23);
      case "users.conversations" -> {
        out.putArray("channels").addObject().put("id", "C0DEPLOY0001").put("name", "deploy-approvals")
            .put("is_private", false);
        out.putObject("response_metadata").put("next_cursor", "");
      }
      default -> {
        return refused("unknown_method");
      }
    }
    return new Reply(out, null);
  }

  private static Reply refused(String error) {
    return new Reply(JsonNodeFactory.instance.objectNode().put("ok", false).put("error", error), null);
  }

  private static JsonNode args(RecordedRequest r) {
    String body = r.getBody() == null ? "" : r.getBody().utf8();
    String type = r.getHeaders().get("Content-Type");
    if (type != null && type.startsWith("application/json")) {
      return body.isEmpty() ? JsonNodeFactory.instance.objectNode() : JSON.readTree(body);
    }
    ObjectNode form = JsonNodeFactory.instance.objectNode();
    if (!body.isEmpty()) {
      for (String pair : body.split("&")) {
        int eq = pair.indexOf('=');
        form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return form;
  }

  /** The server side of one Socket Mode connection. */
  private final class Socket extends WebSocketListener {
    @Override
    public void onOpen(WebSocket ws, Response response) {
      sockets.add(ws);
      ObjectNode hello = JsonNodeFactory.instance.objectNode().put("type", "hello").put("num_connections", 1);
      hello.putObject("connection_info").put("app_id", helloAppId);
      hello.putObject("debug_info").put("approximate_connection_time", 18_060);
      ws.send(hello.toString());
    }

    @Override
    public void onMessage(WebSocket ws, String text) {
      acks.add(text);
    }

    @Override
    public void onClosing(WebSocket ws, int code, String reason) {
      ws.close(1000, null);
      sockets.remove(ws);
    }

    @Override
    public void onFailure(WebSocket ws, Throwable t, Response response) {
      sockets.remove(ws);
    }
  }

  /** Pushes an envelope through the newest connection; returns its id. */
  String envelope(String type, JsonNode payload) {
    String id = "e-" + envelopes.incrementAndGet();
    ObjectNode e = JsonNodeFactory.instance.objectNode().put("envelope_id", id).put("type", type)
        .put("accepts_response_payload", false);
    e.set("payload", payload);
    sockets.get(sockets.size() - 1).send(e.toString());
    return id;
  }

  /** {@code disconnect} with a reason, as Slack sends it before closing a connection. */
  void disconnect(String reason) {
    ObjectNode e = JsonNodeFactory.instance.objectNode().put("type", "disconnect").put("reason", reason);
    sockets.get(sockets.size() - 1).send(e.toString());
  }

  /** A click on a button of a message, as {@code block_actions} carries it. */
  static ObjectNode click(String actionId, String value, String user, String team, String app, String channel,
      String ts) {
    ObjectNode p = JsonNodeFactory.instance.objectNode().put("type", "block_actions").put("api_app_id", app);
    p.putObject("team").put("id", team).put("domain", "acme");
    p.putObject("user").put("id", user).put("team_id", team);
    p.putObject("container").put("type", "message").put("channel_id", channel).put("message_ts", ts);
    p.putObject("channel").put("id", channel);
    p.putArray("actions").addObject().put("type", "button").put("action_id", actionId).put("value", value)
        .put("block_id", "dec.x.1");
    return p;
  }

  List<Call> calls(String method) {
    return calls.stream().filter(c -> c.method().equals(method)).toList();
  }

  @Override
  public void close() throws IOException {
    sockets.forEach(ws -> ws.close(1001, "bye"));
    server.close();
  }
}
