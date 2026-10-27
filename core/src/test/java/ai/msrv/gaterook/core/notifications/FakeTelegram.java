package ai.msrv.gaterook.core.notifications;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Bot API on MockWebServer under {@code /bot<token>/<method>} (report 13 §8.4): {@code getUpdates} holds the
 * request up to a second and answers what the test queued (from the core's offset on), sends and edits are recorded,
 * a wrong token is 401.
 */
final class FakeTelegram implements AutoCloseable {

  static final String TOKEN = "123456789:AAk2TelegramBotTokenForTestsOnly_xyz";
  static final long BOT_ID = 123_456_789L;
  /** Another bot ({@link #switchBot}): its own update ids, its own chats numbering their messages from the start. */
  static final String TOKEN_B = "987654321:BBk2SecondBotTokenForTestsOnly_xyzw";
  static final long BOT_B = 987_654_321L;
  static final long RAVI = 424_242L;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One Bot API call: the method, its arguments, what the fake answered. */
  record Call(String method, JsonNode args, JsonNode answer) {}

  final MockWebServer server = new MockWebServer();
  final List<Call> calls = new CopyOnWriteArrayList<>();
  final List<Long> offsets = new CopyOnWriteArrayList<>();
  volatile String webhook = "";
  private volatile String token = TOKEN;
  private volatile long botId = BOT_ID;
  private volatile String username = "mcpgw_k2_bot";
  private final BlockingQueue<JsonNode> queued = new LinkedBlockingQueue<>();
  private final List<JsonNode> pending = new ArrayList<>();
  private final AtomicLong updateIds = new AtomicLong(1000);
  private final AtomicInteger messageIds = new AtomicInteger(500);

  FakeTelegram() throws IOException {
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest r) throws InterruptedException {
        String path = r.getUrl().encodedPath();
        String prefix = "/bot" + token + "/";
        String body = r.getBody() == null ? "" : r.getBody().utf8();
        JsonNode args = body.isEmpty() ? JsonNodeFactory.instance.objectNode() : JSON.readTree(body);
        if (!path.startsWith(prefix)) {
          return json(401, JsonNodeFactory.instance.objectNode().put("ok", false).put("error_code", 401)
              .put("description", "Unauthorized"));
        }
        String method = path.substring(prefix.length());
        ObjectNode answer = answer(method, args);
        calls.add(new Call(method, args, answer));
        return json(200, answer);
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
  }

  String apiBase() {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  private ObjectNode answer(String method, JsonNode a) throws InterruptedException {
    ObjectNode out = JsonNodeFactory.instance.objectNode().put("ok", true);
    switch (method) {
      case "getMe" -> out.putObject("result").put("id", botId).put("is_bot", true).put("first_name", "MCP Gateway")
          .put("username", username).put("can_join_groups", true).put("can_read_all_group_messages", false);
      case "getWebhookInfo" -> out.putObject("result").put("url", webhook).put("pending_update_count", 0);
      case "deleteWebhook" -> {
        webhook = "";
        out.put("result", true);
      }
      case "getUpdates" -> {
        long offset = a.path("offset").asLong(0);
        offsets.add(offset);
        ArrayNode result = out.putArray("result");
        synchronized (pending) {
          // Telegram forgets what lies below the offset: the core confirmed it.
          pending.removeIf(u -> u.path("update_id").asLong() < offset);
          if (pending.isEmpty()) {
            JsonNode first = queued.poll(1, TimeUnit.SECONDS);
            if (first != null) {
              pending.add(first);
            }
          }
          queued.drainTo(pending);
          // An update below the offset counts as confirmed, also one that arrived meanwhile: Telegram returns only
          // update_id >= offset (another bot's offset would drop this bot's updates).
          pending.removeIf(u -> u.path("update_id").asLong() < offset);
          pending.forEach(result::add);
        }
      }
      case "sendMessage" -> out.putObject("result").put("message_id", messageIds.incrementAndGet())
          .put("date", 1_792_672_452L).put("text", a.path("text").asString()).putObject("chat")
          .put("id", a.path("chat_id").asLong()).put("type", "private");
      case "editMessageText", "editMessageReplyMarkup", "answerCallbackQuery" -> out.put("result", true);
      default -> {
        return JsonNodeFactory.instance.objectNode().put("ok", false).put("error_code", 404)
            .put("description", "Not Found: method not found");
      }
    }
    return out;
  }

  private static MockResponse json(int code, JsonNode body) {
    return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json")
        .body(body.toString()).build();
  }

  /** A text message from {@code from} in its private chat. */
  long message(long from, String text) {
    long id = updateIds.incrementAndGet();
    ObjectNode u = JsonNodeFactory.instance.objectNode().put("update_id", id);
    ObjectNode m = u.putObject("message").put("message_id", messageIds.incrementAndGet()).put("date", 1_792_672_452L)
        .put("text", text);
    m.putObject("from").put("id", from).put("is_bot", false).put("first_name", "Ravi").put("last_name", "T.")
        .put("username", "ravi");
    m.putObject("chat").put("id", from).put("type", "private");
    queued.add(u);
    return id;
  }

  /** A click on a button of message {@code messageId} in {@code chat}; {@code data} is whatever the client sends. */
  String click(long from, long chat, long messageId, String data) {
    long id = updateIds.incrementAndGet();
    String query = "cq-" + id;
    ObjectNode u = JsonNodeFactory.instance.objectNode().put("update_id", id);
    ObjectNode q = u.putObject("callback_query").put("id", query).put("data", data).put("chat_instance", "ci");
    q.putObject("from").put("id", from).put("is_bot", false).put("first_name", "Ravi");
    ObjectNode m = q.putObject("message").put("message_id", messageId).put("date", 1_792_672_452L);
    m.putObject("chat").put("id", chat).put("type", "private");
    queued.add(u);
    return query;
  }

  List<Call> calls(String method) {
    return calls.stream().filter(c -> c.method().equals(method)).toList();
  }

  /**
   * From now on the Bot API is another bot's: only its token works, its update ids start at {@code firstUpdateId}
   * (Telegram's sequences are per bot), and its private chats number their messages from the start again — with the
   * same chat ids, which are the users' ids.
   */
  void switchBot(String newToken, long newBotId, String newUsername, long firstUpdateId) {
    token = newToken;
    botId = newBotId;
    username = newUsername;
    synchronized (pending) {
      pending.clear();
      queued.clear();
      updateIds.set(firstUpdateId - 1);
      messageIds.set(500);
    }
  }

  /** The id the next message gets: a test makes a message take the number another one had. */
  void nextMessageId(long id) {
    messageIds.set((int) id - 1);
  }

  /** The answer the core gave to a click, once it has. */
  JsonNode answerTo(String queryId) {
    return calls("answerCallbackQuery").stream().map(Call::args)
        .filter(a -> queryId.equals(a.path("callback_query_id").asString())).findFirst().orElse(null);
  }

  @Override
  public void close() throws IOException {
    server.close();
  }
}
