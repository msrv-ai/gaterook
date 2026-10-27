package ai.msrv.gaterook.core.notifications.internal.telegram;

import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.Eligibility;
import ai.msrv.gaterook.core.notifications.internal.ChannelRules;
import ai.msrv.gaterook.core.notifications.internal.ChannelStore;
import ai.msrv.gaterook.core.notifications.internal.ChatChannel;
import ai.msrv.gaterook.core.notifications.internal.DeliveryStore;
import ai.msrv.gaterook.core.notifications.internal.Instance;
import ai.msrv.gaterook.core.notifications.internal.LinkStore;
import ai.msrv.gaterook.core.notifications.internal.Linking;
import ai.msrv.gaterook.core.notifications.internal.Notices;
import ai.msrv.gaterook.core.notifications.internal.NotifyConfig;
import ai.msrv.gaterook.core.notifications.internal.NotifyHttp;
import ai.msrv.gaterook.core.notifications.internal.NotifySecrets;
import ai.msrv.gaterook.core.notifications.internal.Outcomes;
import ai.msrv.gaterook.core.notifications.internal.Pacer;
import ai.msrv.gaterook.core.notifications.internal.Pacers;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.SettingStore;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telegram (ARCHITECTURE.md §7.4, report 13): long polling {@code getUpdates} (50 s, offset kept in
 * {@code setting notifications.telegram.offset}) on one virtual thread, so no webhook and no public URL. Messages go
 * to the linked private chats as plain text with entities and {@code protect_content}.
 *
 * <p>{@code callback_data} comes from the client and can be forged ("the message originated the query can contain no
 * callback buttons with this data"), so a click counts only from a linked account ({@code from.id}) in its linked chat
 * ({@code message.chat.id}) on a message we sent for that very request ({@code message_id}); everyone else gets an
 * alert. Approve takes two steps: Approve shows Confirm approve with a nonce good for 60 s. Every click is answered
 * ({@code answerCallbackQuery}), or the client spins.
 */
@Component
public class TelegramChannel implements ChatChannel, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);

  static final String OFFSET = "notifications.telegram.offset";
  static final Duration CONFIRM_TTL = Duration.ofSeconds(60);
  private static final long[] BACKOFF_SECONDS = {1, 2, 5, 10, 30, 60};

  private final NotifyConfig config;
  private final ChannelStore channels;
  private final LinkStore links;
  private final Linking linking;
  private final DeliveryStore deliveries;
  private final NotifySecrets secrets;
  private final ChannelRules rules;
  private final Notices notices;
  private final Instance instance;
  private final EventBus events;
  private final ApprovalService approvals;
  private final SettingStore settings;
  private final Masker masker;
  private final Clock clock;
  private final Pacer pacer;
  private final TelegramApi api;
  private final ZoneId zone = ZoneId.systemDefault();
  /** Confirm approve nonces: nonce → what it confirms. */
  private final Map<String, Pending> confirmations = new ConcurrentHashMap<>();
  /** When a chat last got "This code is invalid or expired" (at most once a minute). */
  private final Map<Long, Instant> lastRefusal = new ConcurrentHashMap<>();

  private record Pending(String approvalId, long chatId, long messageId, Instant expiresAt) {}

  private volatile Thread poller;
  private volatile boolean running;
  /** The digest message during a flood ({@code <chat>:<message id>}), or {@code null}. */
  private volatile String digestRef;

  TelegramChannel(NotifyConfig config, ChannelStore channels, LinkStore links, Linking linking,
      DeliveryStore deliveries, NotifySecrets secrets, ChannelRules rules, Notices notices, Instance instance,
      EventBus events, ApprovalService approvals, SettingStore settings, Masker masker, JsonMapper json, Clock clock,
      Pacers pacers, NotifyHttp http) {
    this.config = config;
    this.channels = channels;
    this.links = links;
    this.linking = linking;
    this.deliveries = deliveries;
    this.secrets = secrets;
    this.rules = rules;
    this.notices = notices;
    this.instance = instance;
    this.events = events;
    this.approvals = approvals;
    this.settings = settings;
    this.masker = masker;
    this.clock = clock;
    this.pacer = pacers.sends();
    this.api = new TelegramApi(http.client(), json, config.telegramApi());
  }

  @Override
  public NotifyChannel kind() {
    return NotifyChannel.TELEGRAM;
  }

  @Override
  public boolean active() {
    ChannelStore.Row row = channels.read(NotifyChannel.TELEGRAM);
    return row.active() && row.secretId("botToken") != null;
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────────────────────

  @EventListener(ApplicationReadyEvent.class)
  void onReady() {
    Thread.ofVirtual().name("telegram-start").start(this::restart);
  }

  /** (Re)starts the poller with the stored settings; stops it when the channel is off. */
  public synchronized void restart() {
    stop();
    ChannelStore.Row row = channels.read(NotifyChannel.TELEGRAM);
    if (!row.stored()) {
      return;
    }
    if (!row.verified()) {
      channels.state(NotifyChannel.TELEGRAM, "changed-outside", "The Telegram settings were changed outside MCP "
          + "Gateway. Save them again to turn Telegram back on.");
      return;
    }
    if (!row.enabled() || row.secretId("botToken") == null) {
      channels.state(NotifyChannel.TELEGRAM, "off", null);
      return;
    }
    running = true;
    // Assigned before it runs: a poller polls only while it is the current one, so one that outlived a restart (past
    // the join below) stops by itself instead of reading next to the new one (Telegram answers that with 409).
    Thread next = Thread.ofVirtual().name("telegram-poller").unstarted(this::poll);
    poller = next;
    next.start();
  }

  public synchronized void stop() {
    running = false;
    Thread t = poller;
    poller = null;
    digestRef = null;
    if (t != null) {
      t.interrupt();
      try {
        t.join(2_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void destroy() {
    stop();
  }

  /** Whether the calling thread is the poller of the channel's current start. */
  private boolean current() {
    return running && poller == Thread.currentThread();
  }

  private void poll() {
    // The offset counts this bot's updates: another bot's offset would confirm (drop) every update of the new one
    // below it — its /start codes and clicks would never arrive.
    String bot = channels.read(NotifyChannel.TELEGRAM).config().path("bot").path("id").asString("");
    long offset = settings.find(OFFSET).map(s -> s.value())
        .filter(v -> !bot.isEmpty() && bot.equals(v.path("bot").asString(""))).map(v -> v.path("offset").asLong(0))
        .orElse(0L);
    int attempt = 0;
    boolean connected = false;
    while (current() && !Thread.currentThread().isInterrupted()) {
      try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.TELEGRAM, null, "botToken")) {
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("offset", offset)
            .put("timeout", config.pollTimeout().toSeconds());
        args.putArray("allowed_updates").add("message").add("callback_query");
        JsonNode updates = api.call("getUpdates", args, tokens.get("botToken"),
            config.pollTimeout().plusSeconds(15));
        if (!connected) {
          connected = true;
          channels.state(NotifyChannel.TELEGRAM, "connected", "Connected · @"
              + channels.read(NotifyChannel.TELEGRAM).config().path("bot").path("username").asString("bot"));
        }
        attempt = 0;
        long next = offset;
        for (JsonNode u : updates) {
          next = Math.max(next, u.path("update_id").asLong() + 1);
          try {
            route(u);
          } catch (RuntimeException e) {
            log.warn("A Telegram update failed: {}", e.toString());
          }
        }
        if (next != offset) {
          // Telegram forgets updates below the offset of the next getUpdates: store it first.
          offset = next;
          settings.put(OFFSET, JsonNodeFactory.instance.objectNode().put("offset", offset).put("bot", bot), null);
        }
      } catch (InterruptedException e) {
        return;
      } catch (TelegramApi.TelegramException e) {
        connected = false;
        if (!current()) {
          return; // A poller of an earlier start: its errors aren't the channel's.
        }
        switch (e.code()) {
          case 401, 404 -> {
            running = false;
            channels.state(NotifyChannel.TELEGRAM, "error", "Telegram rejected the bot token. Replace it in "
                + "Settings › Notifications.");
            return;
          }
          case 409 -> {
            running = false;
            channels.state(NotifyChannel.TELEGRAM, "error", "Another program reads updates for this bot (a webhook "
                + "or a second MCP Gateway). Test Telegram in Settings › Notifications.");
            return;
          }
          case 429 -> {
            if (!sleep(Math.max(1, e.retryAfterSeconds()) * 1_000)) {
              return;
            }
          }
          default -> {
            channels.state(NotifyChannel.TELEGRAM, "error", "Telegram answered " + e.code() + ". Trying again.");
            if (!sleep(backoff(attempt++))) {
              return;
            }
          }
        }
      } catch (IOException | RuntimeException e) {
        connected = false;
        if (!current()) {
          return;
        }
        channels.state(NotifyChannel.TELEGRAM, "error", "MCP Gateway can't reach Telegram. Trying again.");
        if (!sleep(backoff(attempt++))) {
          return;
        }
      }
    }
  }

  private static long backoff(int attempt) {
    long s = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
    return (long) (s * 1_000 * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
  }

  private boolean sleep(long ms) {
    try {
      Thread.sleep(ms);
      return current();
    } catch (InterruptedException e) {
      return false;
    }
  }

  // ── Updates ─────────────────────────────────────────────────────────────────────────────────────────────────

  void route(JsonNode u) {
    if (u.has("callback_query")) {
      callback(u.path("callback_query"));
    } else if (u.has("message")) {
      message(u.path("message"));
    }
  }

  /** {@code /start <code>} in a private chat presents a pairing code; every other message is ignored. */
  private void message(JsonNode m) {
    String text = m.path("text").asString("");
    if (!"private".equals(m.at("/chat/type").asString("")) || !text.startsWith("/start")) {
      return;
    }
    long chat = m.at("/chat/id").asLong();
    long from = m.at("/from/id").asLong();
    String code = text.length() > 6 ? text.substring(6).strip() : "";
    Optional<LinkStore.Pairing> pairing = code.isEmpty() ? Optional.empty()
        : links.open(LinkStore.hash(code)).filter(p -> p.channel() == NotifyChannel.TELEGRAM
            && p.candidate() == null);
    if (pairing.isEmpty()) {
      Instant minuteAgo = clock.instant().minus(Duration.ofMinutes(1));
      if (lastRefusal.size() > 256) {
        // Anyone can write to the bot: what is older than the minute it counts goes, the map stays small.
        lastRefusal.values().removeIf(t -> t.isBefore(minuteAgo));
      }
      Instant last = lastRefusal.get(chat);
      if (last == null || last.isBefore(minuteAgo)) {
        lastRefusal.put(chat, clock.instant());
        reply(chat, code.isEmpty() ? "To link this chat, open MCP Gateway › Settings › Notifications › Telegram."
            : "This code is invalid or expired. Make a new one in MCP Gateway.");
      }
      return;
    }
    ObjectNode candidate = JsonNodeFactory.instance.objectNode().put("userId", Long.toString(from))
        .put("chatId", Long.toString(chat)).put("display", display(m.path("from")));
    if (linking.present(pairing.get().id(), candidate).isPresent()) {
      reply(chat, "Code accepted. Confirm this account in MCP Gateway on " + instance.host() + " to finish linking.");
    }
  }

  /** A Telegram username (5–32, collectible ones from 4): unique, so nobody else can show it. */
  private static final java.util.regex.Pattern USERNAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{4,32}$");

  /**
   * How the app shows the account that presented a code, for the person to confirm (the confirmation is what stops a
   * leaked link): {@code @ravi (Ravi T.)} only with a Telegram username, which can't be borrowed; an account without
   * one is {@code no username · id 999001 · <name>} — a name is anyone's to choose, and one written
   * {@code @ravi (Ravi T.)} would pass for the person's own account. The marker comes first, so no cut of a long name
   * loses it; invisible and bidi characters show as their escapes.
   */
  static String display(JsonNode from) {
    String name = (from.path("first_name").asString("") + " " + from.path("last_name").asString("")).strip();
    StringBuilder visible = new StringBuilder();
    ai.msrv.gaterook.core.approvals.ActionInspector.inspect(name, null).segments().forEach(s -> visible.append(s.v()));
    String username = from.path("username").asString(null);
    if (username != null && USERNAME.matcher(username).matches()) {
      return "@" + username + (visible.isEmpty() ? "" : " (" + visible + ")");
    }
    return "no username · id " + from.path("id").asLong() + (visible.isEmpty() ? "" : " · " + visible);
  }

  private void callback(JsonNode q) {
    String queryId = q.path("id").asString("");
    String data = q.path("data").asString("");
    long from = q.at("/from/id").asLong();
    long chat = q.at("/message/chat/id").asLong();
    long messageId = q.at("/message/message_id").asLong();
    String answer = null;
    boolean alert = false;
    try {
      if (data.startsWith("t:")) {
        answer = "Test button works. Nothing ran.";
        return;
      }
      String[] parts = data.split(":", -1);
      if (parts.length < 2) {
        return;
      }
      String kind = parts[0];
      String id = parts[1];
      Optional<LinkStore.Link> link = links.active(NotifyChannel.TELEGRAM).stream()
          .filter(l -> Long.toString(from).equals(l.userId()) && Long.toString(chat).equals(l.chatId())).findFirst();
      if (link.isEmpty()) {
        answer = "This bot only accepts decisions from its linked account.";
        alert = true;
        return;
      }
      if ("digest".equals(id)) {
        if ("o".equals(kind)) {
          open("/approvals");
          answer = "Opened in MCP Gateway on " + instance.host() + ".";
        }
        return;
      }
      Optional<DeliveryStore.Delivery> delivery = deliveries.at(NotifyChannel.TELEGRAM, chat + ":" + messageId, id);
      if (delivery.isEmpty()) {
        answer = "This message isn't one MCP Gateway sent for this request.";
        alert = true;
        return;
      }
      answer = switch (kind) {
        case "o" -> {
          open("/approvals/" + id);
          yield "Opened in MCP Gateway on " + instance.host() + ".";
        }
        case "d" -> rules.decide(Eligibility.Channel.TELEGRAM, id, false, null, Long.toString(from),
            delivery.get().offeredApprove(), this::fits).text();
        case "a" -> firstStep(id, parts.length > 2 ? parts[2] : null, from, chat, messageId,
            delivery.get().offeredApprove());
        case "A" -> confirmStep(parts, from, chat, messageId, delivery.get().offeredApprove());
        case "b" -> {
          restoreButtons(id, chat, messageId);
          yield null;
        }
        default -> null;
      };
    } finally {
      answerQuery(queryId, answer, alert);
    }
  }

  private String firstStep(String id, String digest, long from, long chat, long messageId, boolean offered) {
    if (!offered) {
      return "Approve isn't available here. Open the request in the app.";
    }
    ChannelStore.Row row = channels.read(NotifyChannel.TELEGRAM);
    if (!row.config().path("confirmApprove").asBoolean(true)) {
      return rules.decide(Eligibility.Channel.TELEGRAM, id, true, digest, Long.toString(from), true, this::fits)
          .text();
    }
    Optional<ApprovalView> a = approvals.find(id);
    if (a.isEmpty() || !a.get().pending()) {
      return a.map(ChannelRules::alreadyText).orElse("This request no longer exists in MCP Gateway.");
    }
    ApprovalNotice n = notices.of(a.get());
    if (digest == null || !n.digestPrefix().equals(digest)) {
      return "The request changed. Open it in the app.";
    }
    String nonce = Linking.nonce().substring(0, 8);
    confirmations.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(clock.instant()));
    confirmations.put(nonce, new Pending(id, chat, messageId, clock.instant().plus(CONFIRM_TTL)));
    editMarkup(chat, messageId, TelegramMessages.confirm(n, nonce));
    return "Tap Confirm approve to run it.";
  }

  private String confirmStep(String[] parts, long from, long chat, long messageId, boolean offered) {
    if (parts.length < 4) {
      return null;
    }
    Pending p = confirmations.remove(parts[3]);
    if (p == null || p.expiresAt().isBefore(clock.instant()) || !p.approvalId().equals(parts[1])
        || p.chatId() != chat || p.messageId() != messageId) {
      restoreButtons(parts[1], chat, messageId);
      return "This confirmation expired. Tap Approve again.";
    }
    return rules.decide(Eligibility.Channel.TELEGRAM, parts[1], true, parts[2], Long.toString(from), offered,
        this::fits).text();
  }

  private void restoreButtons(String id, long chat, long messageId) {
    approvals.find(id).ifPresent(a -> {
      ApprovalNotice n = notices.of(a);
      Eligibility.Verdict v = verdict(n, a.pending());
      editMarkup(chat, messageId, a.pending() ? TelegramMessages.keyboard(n, v) : TelegramMessages.noButtons());
    });
  }

  private void open(String path) {
    events.publish("ui.open", JsonNodeFactory.instance.objectNode().put("path", path));
  }

  // ── Messages ────────────────────────────────────────────────────────────────────────────────────────────────

  /** Whether the whole message with the action fits Telegram's 4,096. */
  boolean fits(ApprovalNotice n) {
    Eligibility.Verdict shown = new Eligibility.Verdict(false, false, true, null, null);
    return TelegramMessages.request(n, shown, zone, null).fits();
  }

  private Eligibility.Verdict verdict(ApprovalNotice n, boolean pending) {
    return rules.verdict(Eligibility.Channel.TELEGRAM, n, fits(n), false, pending);
  }

  private List<Long> targets() {
    return links.active(NotifyChannel.TELEGRAM).stream().map(LinkStore.Link::chatId).filter(c -> c != null)
        .map(Long::parseLong).distinct().toList();
  }

  @Override
  public Delivered deliver(ApprovalNotice n) throws InterruptedException {
    ChannelStore.Row row = channels.read(NotifyChannel.TELEGRAM);
    if (!row.active()) {
      return Delivered.failed("Telegram is off");
    }
    List<Long> chats = targets();
    if (chats.isEmpty()) {
      return Delivered.failed("No linked Telegram chat: link one in Settings › Notifications");
    }
    boolean protect = row.config().path("protectContent").asBoolean(true);
    Eligibility.Verdict v = verdict(n, true);
    TgText text = TelegramMessages.request(n, v, zone, null);
    boolean any = false;
    String error = null;
    for (long chat : chats) {
      DeliveryStore.Delivery d = deliveries.start(n.id(), NotifyChannel.TELEGRAM, Long.toString(chat), v.approve());
      try {
        JsonNode sent = send("sendMessage", TelegramMessages.body(chat, text, TelegramMessages.keyboard(n, v),
            protect), chat, n.id());
        deliveries.sent(d.id(), chat + ":" + sent.path("message_id").asLong());
        any = true;
      } catch (TelegramApi.TelegramException e) {
        error = "Telegram answered " + e.code() + ": " + safe(e.description());
        deliveries.failed(d.id(), error);
      } catch (IOException e) {
        error = "MCP Gateway can't reach Telegram";
        deliveries.failed(d.id(), error);
      }
    }
    if (any) {
      channels.delivered(NotifyChannel.TELEGRAM, clock.instant());
      return Delivered.sent();
    }
    return Delivered.failed("Telegram failed: " + error);
  }

  /** A call to a chat, paced (1/s per chat), up to 3 attempts while the request waits. */
  private JsonNode send(String method, ObjectNode body, long chat, String approvalId)
      throws TelegramApi.TelegramException, IOException, InterruptedException {
    Exception last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      if (attempt > 0 && approvalId != null && approvals.find(approvalId).map(a -> !a.pending()).orElse(true)) {
        break;
      }
      pacer.await("telegram:" + chat);
      try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.TELEGRAM, null, "botToken")) {
        return api.call(method, body, tokens.get("botToken"), Duration.ofSeconds(15));
      } catch (TelegramApi.TelegramException e) {
        last = e;
        if (e.code() != 429 && e.code() < 500) {
          throw e;
        }
        Thread.sleep(Math.max(config.retryDelay().toMillis() * (attempt == 0 ? 1 : 3),
            e.retryAfterSeconds() * 1_000));
      } catch (IOException e) {
        last = e;
        Thread.sleep(config.retryDelay().toMillis() * (attempt == 0 ? 1 : 3));
      }
    }
    if (last instanceof TelegramApi.TelegramException te) {
      throw te;
    }
    throw last instanceof IOException io ? io : new IOException("not sent: the request was decided");
  }

  private void editMarkup(long chat, long messageId, ObjectNode markup) {
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("chat_id", chat).put("message_id", messageId);
    args.set("reply_markup", markup);
    try {
      send("editMessageReplyMarkup", args, chat, null);
    } catch (TelegramApi.TelegramException e) {
      if (!e.notModified()) {
        log.info("Couldn't change Telegram buttons: {} {}", e.code(), safe(e.description()));
      }
    } catch (IOException e) {
      log.info("Couldn't change Telegram buttons: {}", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void editText(long chat, long messageId, TgText text, ObjectNode markup) throws InterruptedException {
    ObjectNode args = TelegramMessages.body(chat, text, markup, false);
    args.remove("protect_content");
    args.put("message_id", messageId);
    try {
      send("editMessageText", args, chat, null);
    } catch (TelegramApi.TelegramException e) {
      if (!e.notModified()) {
        log.info("Couldn't update a Telegram message: {} {}", e.code(), safe(e.description()));
      }
    } catch (IOException e) {
      log.info("Couldn't update a Telegram message: {}", e.getMessage());
    }
  }

  @Override
  public void resolved(ApprovalView a, List<DeliveryStore.Delivery> sent, Long durationMs)
      throws InterruptedException {
    ApprovalNotice n = notices.of(a);
    Outcomes.Outcome outcome = Outcomes.of(a, durationMs, zone);
    Eligibility.Verdict v = verdict(n, false);
    TgText text = TelegramMessages.request(n, v, zone, notices.mask(outcome.text(), a.env()));
    for (DeliveryStore.Delivery d : sent) {
      String ref = d.externalRef();
      int colon = ref == null ? -1 : ref.indexOf(':');
      if (colon < 0) {
        continue;
      }
      editText(Long.parseLong(ref.substring(0, colon)), Long.parseLong(ref.substring(colon + 1)), text,
          TelegramMessages.noButtons());
      if (outcome.done()) {
        deliveries.resolved(d.id());
      }
    }
  }

  @Override
  public Delivered digest(long waiting) throws InterruptedException {
    List<Long> chats = targets();
    if (chats.isEmpty()) {
      return Delivered.failed("No linked Telegram chat");
    }
    long chat = chats.get(0);
    boolean protect = channels.read(NotifyChannel.TELEGRAM).config().path("protectContent").asBoolean(true);
    TgText text = TelegramMessages.digest(waiting);
    try {
      String ref = digestRef;
      if (ref != null && ref.startsWith(chat + ":")) {
        editText(chat, Long.parseLong(ref.substring(ref.indexOf(':') + 1)), text, TelegramMessages.digestKeyboard());
      } else {
        JsonNode sent = send("sendMessage", TelegramMessages.body(chat, text, TelegramMessages.digestKeyboard(),
            protect), chat, null);
        digestRef = chat + ":" + sent.path("message_id").asLong();
      }
      channels.delivered(NotifyChannel.TELEGRAM, clock.instant());
      return Delivered.sent();
    } catch (TelegramApi.TelegramException e) {
      return Delivered.failed("Telegram answered " + e.code() + ": " + safe(e.description()));
    } catch (IOException e) {
      return Delivered.failed("MCP Gateway can't reach Telegram");
    }
  }

  @Override
  public void refreshDigest(long waiting) throws InterruptedException {
    String ref = digestRef;
    if (ref == null) {
      return;
    }
    int colon = ref.indexOf(':');
    editText(Long.parseLong(ref.substring(0, colon)), Long.parseLong(ref.substring(colon + 1)),
        TelegramMessages.digest(waiting), waiting == 0 ? TelegramMessages.noButtons()
            : TelegramMessages.digestKeyboard());
    if (waiting == 0) {
      digestRef = null;
    }
  }

  private void reply(long chat, String text) {
    try {
      send("sendMessage", TelegramMessages.body(chat, new TgText().text(text), null, false), chat, null);
    } catch (TelegramApi.TelegramException | IOException e) {
      log.info("Couldn't answer a Telegram chat: {}", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void answerQuery(String queryId, String text, boolean alert) {
    if (queryId.isEmpty()) {
      return;
    }
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("callback_query_id", queryId);
    if (text != null) {
      args.put("text", text.length() > 200 ? text.substring(0, 199) + "…" : text);
      if (alert) {
        args.put("show_alert", true);
      }
    }
    try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.TELEGRAM, null, "botToken")) {
      api.call("answerCallbackQuery", args, tokens.get("botToken"), Duration.ofSeconds(10));
    } catch (TelegramApi.TelegramException | IOException e) {
      // "query is too old" after a long pause: nothing to do (report 13 §9.5).
      log.debug("answerCallbackQuery failed: {}", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ── Pairing, tests (Settings › Notifications) ──────────────────────────────────────────────────────────────

  /** A new code and its link {@code https://t.me/<bot>?start=<code>}; the code is shown this once. */
  public record PairingStarted(LinkStore.Pairing pairing, String code, String link) {}

  public PairingStarted startPairing() {
    ChannelStore.Row row = channels.read(NotifyChannel.TELEGRAM);
    String bot = row.config().path("bot").path("username").asString(null);
    if (!row.active() || bot == null) {
      throw new IllegalStateException("Connect the Telegram bot first.");
    }
    String code = Linking.nonce();
    LinkStore.Pairing p = linking.pairing(NotifyChannel.TELEGRAM, code, null);
    return new PairingStarted(p, code, "https://t.me/" + bot + "?start=" + code);
  }

  /** The person confirmed the account that presented the code: linked, and the chat hears it. */
  public Optional<LinkStore.Link> confirmPairing(String pairingId, String reason) {
    Optional<LinkStore.Pairing> p = links.open(pairingId).filter(x -> x.channel() == NotifyChannel.TELEGRAM
        && x.candidate() != null);
    if (p.isEmpty()) {
      return Optional.empty();
    }
    JsonNode c = p.get().candidate();
    Optional<LinkStore.Link> link = linking.complete(pairingId, NotifyChannel.TELEGRAM, null,
        c.path("userId").asString(), c.path("chatId").asString(), c.path("display").asString(null), reason);
    link.ifPresent(l -> reply(Long.parseLong(l.chatId()), "Linked to MCP Gateway on " + instance.host()
        + ". Approval requests come here."));
    return link;
  }

  /**
   * Test (report 13 §5): {@code getMe} (the bot, its group settings) and {@code getWebhookInfo} (a webhook blocks
   * long polling: removing it needs the person's yes, {@code confirm: "remove-webhook"}).
   */
  public TestResult test(Map<String, char[]> typed, boolean removeWebhook) throws InterruptedException {
    long t0 = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    List<TestResult.Hint> hints = new ArrayList<>();
    ObjectNode details = JsonNodeFactory.instance.objectNode();
    try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.TELEGRAM, typed, "botToken")) {
      if (!tokens.has("botToken")) {
        return TestResult.failure("missing_token", "Add the bot token from @BotFather.", null);
      }
      long s = System.nanoTime();
      JsonNode me;
      try {
        me = api.call("getMe", null, tokens.get("botToken"), Duration.ofSeconds(10));
      } catch (TelegramApi.TelegramException e) {
        checks.add(new TestResult.Check("getMe", false, ms(s), Integer.toString(e.code())));
        return TestResult.failure(e.code() == 401 || e.code() == 404 ? "invalid_token" : "telegram_" + e.code(),
            e.code() == 401 || e.code() == 404 ? "Telegram rejected the bot token. Copy it again from @BotFather "
                + "(/mybots › API Token)." : "Telegram answered " + e.code() + ": " + safe(e.description()),
            null).withChecks(checks);
      }
      String username = me.path("username").asString("?");
      checks.add(new TestResult.Check("getMe", true, ms(s), "@" + username));
      details.put("botId", me.path("id").asLong()).put("username", username);
      if (me.path("can_read_all_group_messages").asBoolean(false)) {
        hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "privacy-off", null, "Privacy mode is off: in "
            + "groups the bot reads every message. MCP Gateway only uses private chats."));
      }
      s = System.nanoTime();
      JsonNode hook = api.call("getWebhookInfo", null, tokens.get("botToken"), Duration.ofSeconds(10));
      String url = hook.path("url").asString("");
      if (!url.isEmpty()) {
        String shown = masker.text(url, tokens.taint(), MaskPolicy.SECRETS_ONLY).text();
        if (!removeWebhook) {
          checks.add(new TestResult.Check("webhook", false, ms(s), "set"));
          return TestResult.confirm(new TestResult.Confirmation("remove-webhook", "This bot sends updates to a "
              + "webhook (" + shown + "). Use it here? Its webhook will be removed.",
              JsonNodeFactory.instance.objectNode().put("url", shown), null), checks);
        }
        api.call("deleteWebhook", JsonNodeFactory.instance.objectNode().put("drop_pending_updates", false),
            tokens.get("botToken"), Duration.ofSeconds(10));
        checks.add(new TestResult.Check("webhook", true, ms(s), "removed"));
      } else {
        checks.add(new TestResult.Check("webhook", true, ms(s), "none"));
      }
      if (links.active(NotifyChannel.TELEGRAM).isEmpty()) {
        hints.add(new TestResult.Hint(TestResult.Severity.INFO, "no-linked-chat", null, "Link your Telegram account "
            + "to get requests in a private chat with @" + username + "."));
      }
      return new TestResult(true, ms(t0), null, "@" + username, null, List.copyOf(hints), List.copyOf(checks), null,
          null, null, null, details);
    } catch (TelegramApi.TelegramException e) {
      return TestResult.failure("telegram_" + e.code(), "Telegram answered " + e.code() + ": "
          + safe(e.description()), null).withChecks(checks);
    } catch (IOException e) {
      return TestResult.failure("network", "MCP Gateway can't reach api.telegram.org. Telegram is blocked in some "
          + "networks: check the network or the proxy.", null).withChecks(checks);
    }
  }

  /** Sends the test message to every linked chat ("Send test notification"). */
  public Delivered sendTest() throws InterruptedException {
    if (!active()) {
      return Delivered.failed("Telegram is off");
    }
    List<Long> chats = targets();
    if (chats.isEmpty()) {
      return Delivered.failed("No linked Telegram chat");
    }
    boolean protect = channels.read(NotifyChannel.TELEGRAM).config().path("protectContent").asBoolean(true);
    String error = null;
    boolean any = false;
    for (long chat : chats) {
      try {
        send("sendMessage", TelegramMessages.body(chat, TelegramMessages.test(), TelegramMessages.testKeyboard(),
            protect), chat, null);
        any = true;
      } catch (TelegramApi.TelegramException e) {
        error = "Telegram answered " + e.code();
      } catch (IOException e) {
        error = "MCP Gateway can't reach Telegram";
      }
    }
    return any ? Delivered.sent() : Delivered.failed("Telegram failed: " + error);
  }

  /** A Bot API description with any echo of a token masked. */
  private String safe(String description) {
    return description == null ? "" : masker.log(description);
  }

  private static long ms(long since) {
    return Duration.ofNanos(System.nanoTime() - since).toMillis();
  }
}
