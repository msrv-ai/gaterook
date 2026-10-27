package ai.msrv.gaterook.core.notifications;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.policy.PolicyService;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A core with the fake connectors, a fake Slack and a fake Telegram (MockWebServer), the dev and prod endpoints of
 * the approval tests and an agent on each; every SSE event and every log line of the core is kept, so a test can
 * look for what must never leave.
 */
final class NotifyWorld implements AutoCloseable {

  final FakeSlack slack;
  final FakeTelegram telegram;
  final Path home;
  final List<EventBus.GatewayEvent> events = new CopyOnWriteArrayList<>();
  final Path log;
  TestCore core;
  AdminMvc api;
  AgentCalls dev;
  AgentCalls prod;
  String devTokenId;
  private String devToken;
  private String prodToken;
  private final String[] extra;
  private FileAppender<ILoggingEvent> tap;
  private volatile EventBus.Subscription subscription;

  private NotifyWorld(String name, String... extra) throws IOException {
    this.slack = new FakeSlack();
    this.telegram = new FakeTelegram();
    this.home = TestGateway.newHome(name);
    this.extra = extra;
    this.log = home.resolve("logs/notify-tap.log");
    Files.createDirectories(log.getParent());
  }

  /** A new installation: connections, endpoints and tokens, every channel announcing dev and prod requests. */
  static NotifyWorld start(String name, String... extra) throws IOException {
    NotifyWorld w = new NotifyWorld(name, extra);
    w.open();
    w.core.connection("dev-db", "pgfake", Env.DEV, "db.dev.internal");
    w.core.connection("prod-db", "pgfake", Env.PROD, "db.prod.internal");
    EndpointService endpoints = w.core.bean(EndpointService.class);
    EndpointConnections wiring = w.core.bean(EndpointConnections.class);
    endpoints.create("dev", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("dev", "dev-db", null);
    endpoints.create("incident-prod", null, EndpointService.StartFrom.EMPTY, null);
    wiring.add("incident-prod", "prod-db", null);
    // The agent gets its handle at once: the tests decide from the channels, not within the first wait.
    for (String slug : List.of("dev", "incident-prod")) {
      endpoints.updateSettings(slug, new EndpointService.SettingsUpdate(null, null, null, null, null,
          JsonNodeFactory.instance.objectNode().put("firstWaitSeconds", 0), null), endpoints.get(slug).version(),
          null);
    }
    PolicyService policies = w.core.bean(PolicyService.class);
    for (Env env : List.of(Env.DEV, Env.PROD)) {
      policies.update(env, TestCore.json("{\"approvals\":{\"announceIn\":[\"desktop\",\"slack\",\"telegram\"]}}"),
          policies.get(env).version(), "notification tests", null);
    }
    TokenService tokens = w.core.bean(TokenService.class);
    TokenService.Issued devToken = tokens.issue("dev", "codex-laptop", 7, null);
    w.devTokenId = devToken.id();
    w.devToken = devToken.token();
    w.prodToken = tokens.issue("incident-prod", "ops", 7, null).token();
    w.agents();
    return w;
  }

  /** The agents, on this start's port. */
  private void agents() {
    int port = core.bean(LocalListener.class).port();
    dev = new AgentCalls(port, "dev", devToken);
    prod = new AgentCalls(port, "incident-prod", prodToken);
  }

  private void open() {
    List<String> args = new ArrayList<>(List.of(
        "--gateway.test.notifications.slack-api=" + slack.apiBase(),
        "--gateway.test.notifications.telegram-api=" + telegram.apiBase(),
        "--gateway.notifications.telegram.poll-timeout=1s",
        "--gateway.notifications.pace=10ms",
        "--gateway.notifications.retry-delay=50ms",
        "--gateway.notifications.slack.hello-timeout=3s",
        "--logging.level.ai.msrv.gaterook.core.notifications=DEBUG"));
    args.addAll(List.of(extra));
    core = TestCore.reopen(home, args.toArray(String[]::new));
    // After the start: Boot resets Logback when a context starts, which drops an appender added before.
    tap = tap(log);
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
    EventBus.Subscription s = core.bean(EventBus.class).subscribe(OptionalLong.empty());
    subscription = s;
    Thread.ofVirtual().name("notify-it-events").start(() -> {
      try {
        while (!s.isClosed()) {
          s.next(Duration.ofMillis(200)).ifPresent(events::add);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

  /** The next start of the same installation (the fakes keep running). */
  void reopen() {
    closeCore();
    open();
    agents();
  }

  private void closeCore() {
    EventBus.Subscription s = subscription;
    if (s != null) {
      s.close();
    }
    if (core != null) {
      core.close();
      core = null;
    }
    if (tap != null) {
      ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(tap);
      tap.stop();
      tap = null;
    }
  }

  // ── Events ──────────────────────────────────────────────────────────────────────────────────────────────────

  List<JsonNode> events(String type) {
    return events.stream().filter(e -> e.type().equals(type)).map(EventBus.GatewayEvent::data).toList();
  }

  JsonNode awaitEvent(String type, Predicate<JsonNode> match, String what) {
    TestCore.await(() -> events(type).stream().anyMatch(match), what);
    return events(type).stream().filter(match).findFirst().orElseThrow();
  }

  // ── Slack ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** The {@code chat.postMessage} calls whose buttons name {@code approvalId}. */
  List<FakeSlack.Call> slackPosts(String approvalId) {
    return slack.calls("chat.postMessage").stream().filter(c -> c.args().toString().contains("|" + approvalId + "|"))
        .toList();
  }

  FakeSlack.Call awaitSlackPost(String approvalId) {
    TestCore.await(() -> !slackPosts(approvalId).isEmpty(), "the Slack message of " + approvalId);
    return slackPosts(approvalId).getFirst();
  }

  List<FakeSlack.Call> slackUpdates(String ts) {
    return slack.calls("chat.update").stream().filter(c -> ts.equals(c.args().path("ts").asString())).toList();
  }

  FakeSlack.Call awaitEphemeral(String user, String text) {
    Predicate<FakeSlack.Call> match = c -> user.equals(c.args().path("user").asString())
        && c.args().path("text").asString().contains(text);
    TestCore.await(() -> slack.calls("chat.postEphemeral").stream().anyMatch(match), "an ephemeral \"" + text
        + "\" to " + user);
    return slack.calls("chat.postEphemeral").stream().filter(match).findFirst().orElseThrow();
  }

  /** The action ids of a message's buttons, in order. */
  static List<String> actionIds(JsonNode message) {
    List<String> out = new ArrayList<>();
    message.path("blocks").forEach(b -> {
      if ("actions".equals(b.path("type").asString())) {
        b.path("elements").forEach(e -> out.add(e.path("action_id").asString()));
      }
    });
    return out;
  }

  /** The {@code value} of a message's button. */
  static String buttonValue(JsonNode message, String actionId) {
    for (JsonNode b : message.path("blocks")) {
      for (JsonNode e : b.path("elements")) {
        if (actionId.equals(e.path("action_id").asString())) {
          return e.path("value").asString();
        }
      }
    }
    throw new AssertionError("No " + actionId + " in " + message);
  }

  /** A click of {@code user} in Ravi's workspace, through the app, on {@code post}. */
  String slackClick(String actionId, String value, String user, FakeSlack.Call post) {
    return slack.envelope("interactive", FakeSlack.click(actionId, value, user, FakeSlack.TEAM, FakeSlack.APP_ID,
        post.args().path("channel").asString(), post.answer().path("ts").asString()));
  }

  // ── Telegram ────────────────────────────────────────────────────────────────────────────────────────────────

  /** The {@code sendMessage} calls whose buttons name {@code approvalId}. */
  List<FakeTelegram.Call> telegramMessages(String approvalId) {
    return telegram.calls("sendMessage").stream()
        .filter(c -> c.args().path("reply_markup").toString().contains(":" + approvalId)).toList();
  }

  FakeTelegram.Call awaitTelegramMessage(String approvalId) {
    TestCore.await(() -> !telegramMessages(approvalId).isEmpty(), "the Telegram message of " + approvalId);
    return telegramMessages(approvalId).getFirst();
  }

  static long messageId(FakeTelegram.Call sent) {
    return sent.answer().path("result").path("message_id").asLong();
  }

  /** Every {@code callback_data} of a keyboard, in order. */
  static List<String> callbackData(JsonNode markup) {
    List<String> out = new ArrayList<>();
    markup.path("inline_keyboard").forEach(row -> row.forEach(b -> out.add(b.path("callback_data").asString())));
    return out;
  }

  /** Clicks and waits for the core's answer to the click ({@code answerCallbackQuery}). */
  JsonNode telegramClick(long from, long chat, long messageId, String data) {
    String query = telegram.click(from, chat, messageId, data);
    TestCore.await(() -> telegram.answerTo(query) != null, "the answer to " + data);
    return telegram.answerTo(query);
  }

  // ── Requests ────────────────────────────────────────────────────────────────────────────────────────────────

  /** The {@code approval} row's column, as stored. */
  Object column(String approvalId, String column) {
    return core.jdbc().queryForObject("select " + column + " from approval where id = ?", Object.class, approvalId);
  }

  /** Reads the whole log the core wrote while this world ran. */
  String logText() throws IOException {
    return Files.exists(log) ? Files.readString(log) : "";
  }

  /**
   * A file appender on the root logger with the raw message (stricter than the core's {@code %mask} pattern: what the
   * code logs, before masking). Boot sets Logback up once per JVM, so a test taps the root logger itself to see every
   * line the core writes, as {@code CanaryLeakIT} does.
   */
  private static FileAppender<ILoggingEvent> tap(Path file) {
    LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(logback);
    encoder.setPattern("%d %5p [%t] %logger : %m%n%wEx");
    encoder.start();
    FileAppender<ILoggingEvent> appender = new FileAppender<>();
    appender.setContext(logback);
    appender.setName("notify-tap-" + file.getFileName());
    appender.setFile(file.toString());
    appender.setAppend(true);
    appender.setEncoder(encoder);
    appender.start();
    logback.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    return appender;
  }

  @Override
  public void close() throws IOException {
    closeCore();
    slack.close();
    telegram.close();
  }
}
