package ai.msrv.gaterook.core.notifications;

import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.core.notifications.internal.ChannelRules;
import ai.msrv.gaterook.core.notifications.internal.ChannelStore;
import ai.msrv.gaterook.core.notifications.internal.ChatChannel;
import ai.msrv.gaterook.core.notifications.internal.DeliveryStore;
import ai.msrv.gaterook.core.notifications.internal.DesktopNotices;
import ai.msrv.gaterook.core.notifications.internal.Instance;
import ai.msrv.gaterook.core.notifications.internal.LinkStore;
import ai.msrv.gaterook.core.notifications.internal.Linking;
import ai.msrv.gaterook.core.notifications.internal.NotifySecrets;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.notifications.internal.slack.SlackApi;
import ai.msrv.gaterook.core.notifications.internal.slack.SlackChannel;
import ai.msrv.gaterook.core.notifications.internal.telegram.TelegramChannel;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Settings › Notifications (ARCHITECTURE.md §6 settings, kit {@code settings.html#settings-notifications}): the three
 * channels, their tests, linked accounts and the test message. Tokens are tested before they are saved (a 422 with
 * the test when they don't work), stored in the {@code secrets} module, and never answered. Every change is a Setup
 * change.
 */
@Service
public class NotificationService {

  static final Pattern BOT_TOKEN = Pattern.compile("^xoxb-[A-Za-z0-9-]+$");
  static final Pattern APP_TOKEN = Pattern.compile("^(xapp-|xoxe\\.xapp-)[A-Za-z0-9-]+$");
  static final Pattern CHANNEL_ID = Pattern.compile("^[CG][A-Z0-9]{8,}$");
  static final Pattern TELEGRAM_TOKEN = Pattern.compile("^[0-9]{5,}:[A-Za-z0-9_-]{30,}$");
  static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}$");

  /** {@code PUT /api/notifications/desktop}; a {@code null} keeps the stored value. */
  public record DesktopUpdate(Boolean enabled, Boolean soundForProd, Integer maxBurst) {}

  /**
   * {@code PUT /api/notifications/slack}; a {@code null} keeps the stored value, a token is replaced only when given.
   *
   * @param deliverTo {@code dm} (linked approvers, recommended) or {@code channel}
   */
  public record SlackUpdate(Boolean enabled, String deliverTo, String channelId, char[] botToken, char[] appToken) {}

  /**
   * {@code PUT /api/notifications/telegram}.
   *
   * @param removeWebhook the person agreed to remove the bot's webhook ({@code confirm: "remove-webhook"})
   */
  public record TelegramUpdate(Boolean enabled, Boolean protectContent, Boolean confirmApprove, char[] botToken,
      boolean removeWebhook) {}

  private final ChannelStore channels;
  private final LinkStore links;
  private final Linking linking;
  private final DeliveryStore deliveries;
  private final NotifySecrets secrets;
  private final DesktopNotices desktop;
  private final SlackChannel slack;
  private final TelegramChannel telegram;
  private final ChannelRules rules;
  private final Instance instance;
  private final EventBus events;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  /** What Electron last reported about OS notifications ({@code ok}/{@code failed} + why). */
  private volatile String desktopState;
  private volatile String desktopDetail;

  NotificationService(ChannelStore channels, LinkStore links, Linking linking, DeliveryStore deliveries,
      NotifySecrets secrets, DesktopNotices desktop, SlackChannel slack, TelegramChannel telegram, ChannelRules rules,
      Instance instance, EventBus events, ObjectProvider<SetupChangeRecorder> setupChanges, Clock clock,
      PlatformTransactionManager transactions) {
    this.channels = channels;
    this.links = links;
    this.linking = linking;
    this.deliveries = deliveries;
    this.secrets = secrets;
    this.desktop = desktop;
    this.slack = slack;
    this.telegram = telegram;
    this.rules = rules;
    this.instance = instance;
    this.events = events;
    this.setupChanges = setupChanges;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
  }

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────────────

  public NotificationViews.Overview overview() {
    List<NotificationViews.Channel> out = new ArrayList<>();
    for (NotifyChannel c : NotifyChannel.values()) {
      out.add(view(channels.read(c)));
    }
    List<NotificationViews.Link> all = new ArrayList<>();
    for (LinkStore.Link l : links.current()) {
      all.add(new NotificationViews.Link(l.id(), l.channel().id(), l.display(), l.linkedAt(),
          l.verified() ? "linked" : "changed-outside", null, null));
    }
    for (LinkStore.Pairing p : links.openPairings()) {
      JsonNode c = p.candidate();
      boolean slackPending = p.channel() == NotifyChannel.SLACK;
      String state = slackPending || c == null ? "pending" : "presented";
      all.add(new NotificationViews.Link(p.id(), p.channel().id(), c == null ? null : c.path("display")
          .asString(null), null, state, slackPending && c != null ? c.path("code").asString(null) : null,
          p.expiresAt()));
    }
    return new NotificationViews.Overview(out, all, rules.decisionsFromLinked() ? "app-and-linked" : "app",
        instance.host());
  }

  /** One channel by id ({@code desktop}, {@code slack}, {@code telegram}). */
  public NotificationViews.Channel channel(String id) {
    return view(channels.read(channelOf(id)));
  }

  private NotificationViews.Channel view(ChannelStore.Row row) {
    ObjectNode config = row.config().deepCopy();
    NotifySecrets.FIELDS.keySet().forEach(config::remove);
    config.remove("demo");
    List<NotificationViews.StoredToken> tokens = new ArrayList<>();
    if (row.channel() != NotifyChannel.DESKTOP) {
      for (SecretInfo s : secrets.stored(row.channel())) {
        tokens.add(new NotificationViews.StoredToken(s.field(), s.state(), s.version(), s.rotatedAt()));
      }
    }
    String state = row.state();
    String detail = row.stateDetail();
    if (row.channel() == NotifyChannel.DESKTOP) {
      state = row.enabled() ? (desktopState == null ? "ok" : desktopState) : "off";
      detail = row.enabled() ? desktopDetail : null;
    } else if (!row.stored()) {
      state = "off";
    } else if (!row.verified()) {
      state = "changed-outside";
    }
    return new NotificationViews.Channel(row.channel().id(), row.enabled(), config, state, detail,
        row.lastDeliveryAt(), row.version(), row.verified(), List.copyOf(tokens));
  }

  // ── Saving ──────────────────────────────────────────────────────────────────────────────────────────────────

  public NotificationViews.Channel updateDesktop(DesktopUpdate u, long version, String reason) {
    ChannelStore.Row row = fresh(channels.read(NotifyChannel.DESKTOP), version);
    ObjectNode config = row.config().deepCopy();
    if (u.soundForProd() != null) {
      config.put("soundForProd", u.soundForProd());
    }
    if (u.maxBurst() != null) {
      if (u.maxBurst() < 1 || u.maxBurst() > 10) {
        throw ProblemException.unprocessable("Check the burst limit", "Use 1 to 10 notifications in 10 s.",
            "/maxBurst");
      }
      config.put("maxBurst", u.maxBurst());
    }
    boolean enabled = u.enabled() == null ? row.enabled() : u.enabled();
    writeTx.executeWithoutResult(s -> {
      channels.save(NotifyChannel.DESKTOP, enabled, config, version);
      record(NotifyChannel.DESKTOP, row, enabled, config, List.of(), reason);
    });
    return view(channels.read(NotifyChannel.DESKTOP));
  }

  /**
   * Saves Slack. Turning it on (or changing a token) tests the tokens first — {@code auth.test}, Socket Mode's
   * {@code hello}, the channel — and saves nothing when they fail ({@link NotifyTestFailedException}, 422).
   */
  public NotificationViews.Channel updateSlack(SlackUpdate u, long version, String reason) throws InterruptedException {
    try {
      ChannelStore.Row row = fresh(channels.read(NotifyChannel.SLACK), version);
      ObjectNode config = row.config().deepCopy();
      config.remove("demo");
      String deliverTo = u.deliverTo() == null ? config.path("deliverTo").asString("dm") : u.deliverTo();
      if (!deliverTo.equals("dm") && !deliverTo.equals("channel")) {
        throw ProblemException.unprocessable("Choose where requests go", "Send requests as direct messages to "
            + "linked approvers (dm) or to a channel (channel).", "/deliverTo");
      }
      String channelId = u.channelId() == null ? config.path("channelId").asString(null)
          : u.channelId().isBlank() ? null : u.channelId().strip();
      if ("channel".equals(deliverTo) && (channelId == null || !CHANNEL_ID.matcher(channelId).matches())) {
        throw ProblemException.unprocessable("Choose a channel", "Pick a channel the MCP Gateway app was added to.",
            "/channelId");
      }
      check(u.botToken(), BOT_TOKEN, "/botToken", "The bot token is the Bot User OAuth Token: it starts with xoxb-.");
      check(u.appToken(), APP_TOKEN, "/appToken", "The app-level token starts with xapp- and needs "
          + "connections:write.");
      boolean enabled = u.enabled() == null ? row.enabled() || u.botToken() != null : u.enabled();
      boolean hasBot = u.botToken() != null || row.secretId("botToken") != null;
      boolean hasApp = u.appToken() != null || row.secretId("appToken") != null;
      if (enabled && (!hasBot || !hasApp)) {
        throw ProblemException.unprocessable("Add the tokens", "Slack needs both tokens: the Bot User OAuth Token "
            + "(xoxb-…) and an app-level token (xapp-…).", hasBot ? "/appToken" : "/botToken");
      }
      config.put("deliverTo", deliverTo);
      if (channelId == null) {
        config.remove("channelId");
        config.remove("channelName");
      } else {
        config.put("channelId", channelId);
      }
      List<String> tokens = new ArrayList<>();
      if (enabled) {
        Map<String, char[]> typed = new LinkedHashMap<>();
        if (u.botToken() != null) {
          typed.put("botToken", u.botToken());
        }
        if (u.appToken() != null) {
          typed.put("appToken", u.appToken());
        }
        TestResult test = slack.test(typed, deliverTo, channelId);
        if (!test.ok() || test.hints().stream().anyMatch(h -> h.severity() == TestResult.Severity.ERROR)) {
          throw new NotifyTestFailedException(test);
        }
        JsonNode d = test.details();
        ObjectNode workspace = config.putObject("workspace");
        for (String key : List.of("team", "teamId", "bot", "botUserId", "appId")) {
          if (d != null && d.path(key).isString()) {
            workspace.put(key, d.path(key).asString());
          }
        }
        if (d != null && d.path("channelName").isString()) {
          config.put("channelName", d.path("channelName").asString());
        }
      }
      writeTx.executeWithoutResult(s -> {
        for (Map.Entry<String, char[]> t : tokensOf(u.botToken(), u.appToken()).entrySet()) {
          String id = secrets.store(NotifyChannel.SLACK, t.getKey(), t.getValue(), reason);
          config.putObject(t.getKey()).put("$secret", id);
          tokens.add(t.getKey());
        }
        channels.save(NotifyChannel.SLACK, enabled, config, version);
        record(NotifyChannel.SLACK, row, enabled, config, tokens, reason);
        afterCommit(slack::restart);
      });
      return view(channels.read(NotifyChannel.SLACK));
    } finally {
      clear(u.botToken());
      clear(u.appToken());
    }
  }

  /**
   * Saves Telegram, testing a new or newly enabled bot token first ({@code getMe}, {@code getWebhookInfo}); a webhook
   * is removed only with the person's yes. A different bot makes the linked chats useless, so they are unlinked.
   */
  public NotificationViews.Channel updateTelegram(TelegramUpdate u, long version, String reason)
      throws InterruptedException {
    try {
      ChannelStore.Row row = fresh(channels.read(NotifyChannel.TELEGRAM), version);
      ObjectNode config = row.config().deepCopy();
      check(u.botToken(), TELEGRAM_TOKEN, "/botToken", "A bot token from @BotFather looks like 123456789:AA… .");
      if (u.protectContent() != null) {
        config.put("protectContent", u.protectContent());
      }
      if (u.confirmApprove() != null) {
        config.put("confirmApprove", u.confirmApprove());
      }
      boolean enabled = u.enabled() == null ? row.enabled() || u.botToken() != null : u.enabled();
      if (enabled && u.botToken() == null && row.secretId("botToken") == null) {
        throw ProblemException.unprocessable("Add the bot token", "Telegram needs the bot token from @BotFather.",
            "/botToken");
      }
      String oldBot = config.path("bot").path("id").asString(null);
      if (enabled) {
        Map<String, char[]> typed = u.botToken() == null ? Map.of() : Map.of("botToken", u.botToken());
        TestResult test = telegram.test(typed, u.removeWebhook());
        if (!test.ok()) {
          throw new NotifyTestFailedException(test);
        }
        JsonNode d = test.details();
        if (d != null) {
          config.putObject("bot").put("id", d.path("botId").asString()).put("username", d.path("username")
              .asString());
        }
      }
      boolean otherBot = oldBot != null && !oldBot.equals(config.path("bot").path("id").asString(oldBot));
      List<String> tokens = new ArrayList<>();
      writeTx.executeWithoutResult(s -> {
        for (Map.Entry<String, char[]> t : tokensOf(u.botToken(), null).entrySet()) {
          String id = secrets.store(NotifyChannel.TELEGRAM, t.getKey(), t.getValue(), reason);
          config.putObject(t.getKey()).put("$secret", id);
          tokens.add(t.getKey());
        }
        if (otherBot) {
          for (LinkStore.Link l : linking.revokeAll(NotifyChannel.TELEGRAM)) {
            setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
                "Settings › Notifications · Telegram account", l.display(), "Unlinked: another bot", reason, null,
                null, null, null));
          }
          // The old bot's messages stay as they are: the new bot's chat numbers its own messages from 1 again.
          deliveries.closeOpen(NotifyChannel.TELEGRAM);
        }
        channels.save(NotifyChannel.TELEGRAM, enabled, config, version);
        record(NotifyChannel.TELEGRAM, row, enabled, config, tokens, reason);
        afterCommit(telegram::restart);
      });
      return view(channels.read(NotifyChannel.TELEGRAM));
    } finally {
      clear(u.botToken());
    }
  }

  /**
   * Disconnect: the channel stops, its tokens are destroyed and its accounts unlinked; the defaults come back. Desktop
   * notifications can only be turned off.
   */
  public void disconnect(String id, String reason) {
    NotifyChannel channel = channelOf(id);
    if (channel == NotifyChannel.DESKTOP) {
      throw ProblemException.unprocessable("Turn it off instead", "Desktop notifications can be turned off, not "
          + "disconnected.", null);
    }
    ChannelStore.Row row = channels.read(channel);
    if (!row.stored()) {
      throw ProblemException.notFound("Not set up", title(channel) + " isn't set up.");
    }
    if (channel == NotifyChannel.SLACK) {
      slack.stop();
    } else {
      telegram.stop();
    }
    writeTx.executeWithoutResult(s -> {
      List<String> destroyed = secrets.destroy(channel);
      List<LinkStore.Link> unlinked = linking.revokeAll(channel);
      deliveries.closeOpen(channel);
      channels.delete(channel);
      ObjectNode details = JsonNodeFactory.instance.objectNode();
      details.putArray("tokensDestroyed").addAll(destroyed.stream().map(JsonNodeFactory.instance::stringNode)
          .toList());
      details.put("accountsUnlinked", unlinked.size());
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS, "Settings › Notifications · "
          + title(channel), row.enabled() ? "On" : "Off", "Disconnected", reason, null, null, null, details));
    });
    events.publish("notify.state", JsonNodeFactory.instance.objectNode().put("channel", channel.id())
        .put("state", "off"));
  }

  // ── Tests ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** Test of the stored Slack or Telegram settings. */
  public TestResult test(String id) throws InterruptedException {
    NotifyChannel channel = channelOf(id);
    ChannelStore.Row row = channels.read(channel);
    return switch (channel) {
      case SLACK -> slack.test(Map.of(), row.text("deliverTo"), row.text("channelId"));
      case TELEGRAM -> telegram.test(Map.of(), false);
      case DESKTOP -> throw ProblemException.unprocessable("Test in the app", "Desktop notifications are tested "
          + "with Send test notification.", null);
    };
  }

  /** "Send test notification": the sample to every channel that is on ("Test · nothing will run"). */
  public NotificationViews.Sent sendTest() throws InterruptedException {
    List<NotificationViews.SentTo> out = new ArrayList<>();
    out.add(new NotificationViews.SentTo("desktop", desktop.test() ? "sent" : "off", null));
    out.add(sent("slack", slack.active() ? slack.sendTest() : null));
    out.add(sent("telegram", telegram.active() ? telegram.sendTest() : null));
    return new NotificationViews.Sent(out);
  }

  private static NotificationViews.SentTo sent(String channel, ChatChannel.Delivered d) {
    return d == null ? new NotificationViews.SentTo(channel, "off", null)
        : new NotificationViews.SentTo(channel, d.state(), d.error());
  }

  /** What Electron main saw showing desktop notifications ({@code POST /internal/notifications/desktop}). */
  public void desktopReport(String event, String detail) {
    String state = switch (event == null ? "" : event) {
      case "shown" -> "ok";
      case "failed", "unsupported" -> "failed";
      default -> throw ProblemException.unprocessable("Unknown event", "Report shown, failed or unsupported.",
          "/event");
    };
    String text = "unsupported".equals(event) ? "This system has no desktop notifications."
        : detail == null || detail.isBlank() ? ("failed".equals(state) ? "Notifications are blocked or unsigned: "
            + "check the system settings." : null) : detail.length() > 300 ? detail.substring(0, 300) : detail;
    if (!Objects.equals(state, desktopState) || !Objects.equals(text, desktopDetail)) {
      desktopState = state;
      desktopDetail = text;
      ObjectNode e = JsonNodeFactory.instance.objectNode().put("channel", "desktop").put("state", state);
      e.put("detail", text);
      events.publish("notify.state", e);
    }
  }

  // ── Slack channels and links ────────────────────────────────────────────────────────────────────────────────

  public List<NotificationViews.SlackChannelOption> slackChannels() throws InterruptedException {
    requireConnected(NotifyChannel.SLACK);
    try {
      return slack.channelsOfTheApp().stream()
          .map(c -> new NotificationViews.SlackChannelOption(c.id(), "#" + c.name(), c.isPrivate())).toList();
    } catch (SlackApi.SlackException e) {
      throw ProblemException.unprocessable("Slack refused", "Slack answered " + e.error() + ".", null);
    } catch (IOException e) {
      throw ProblemException.unprocessable("Slack unreachable", "MCP Gateway can't reach slack.com.", null);
    }
  }

  /**
   * Starts linking a Slack account: a DM with a code, active once that account clicks Link account. The code the
   * answer shows is the one in the DM.
   */
  public NotificationViews.Link linkSlack(String email) throws InterruptedException {
    if (email == null || !EMAIL.matcher(email.strip()).matches()) {
      throw ProblemException.unprocessable("Check the email", "Type the email of your Slack account.", "/email");
    }
    requireConnected(NotifyChannel.SLACK);
    try {
      SlackChannel.LinkStarted started = slack.startLink(email.strip());
      return new NotificationViews.Link(started.pairing().id(), "slack", started.display(), null, "pending",
          started.code(), started.pairing().expiresAt());
    } catch (SlackApi.SlackException e) {
      throw switch (e.error()) {
        case "users_not_found" -> ProblemException.unprocessable("No such account", "No Slack account in this "
            + "workspace has that email.", "/email");
        case "not_connected" -> ProblemException.conflict("Connect Slack first", "Slack isn't connected.");
        case "missing_scope" -> ProblemException.unprocessable("Missing scope", "The Slack app needs "
            + (e.needed() == null ? "users:read.email and im:write" : e.needed()) + ": add it, reinstall the app and "
            + "replace the bot token.", "/email");
        default -> ProblemException.unprocessable("Slack refused", "Slack answered " + e.error() + ".", "/email");
      };
    } catch (IOException e) {
      throw ProblemException.unprocessable("Slack unreachable", "MCP Gateway can't reach slack.com.", null);
    }
  }

  /** A Telegram code and its link: the account that sends {@code /start <code>} is confirmed in the app next. */
  public NotificationViews.Pairing telegramPairing() {
    requireConnected(NotifyChannel.TELEGRAM);
    TelegramChannel.PairingStarted p;
    try {
      p = telegram.startPairing();
    } catch (IllegalStateException e) {
      throw ProblemException.conflict("Test Telegram first", "MCP Gateway doesn't know the bot's name yet: save "
          + "Telegram again.");
    }
    return new NotificationViews.Pairing(p.pairing().id(), p.code(), p.link(), p.pairing().expiresAt());
  }

  public NotificationViews.Link confirmTelegramPairing(String pairingId, String reason) {
    Optional<LinkStore.Pairing> p = links.open(pairingId);
    if (p.isEmpty() || p.get().channel() != NotifyChannel.TELEGRAM) {
      throw ProblemException.notFound("Code not found", "The code expired or was used. Make a new one.");
    }
    if (p.get().candidate() == null) {
      throw ProblemException.conflict("Nobody used the code yet", "Open the link on the phone with your Telegram "
          + "account, or send /start with the code to the bot, then confirm.");
    }
    LinkStore.Link l = telegram.confirmPairing(pairingId, reason).orElseThrow(() -> ProblemException.conflict(
        "Already answered", "The code was used meanwhile."));
    return new NotificationViews.Link(l.id(), "telegram", l.display(), l.linkedAt(), "linked", null, null);
  }

  /** Unlinks an account, or drops a code that is still waiting. */
  public void deleteLink(String id, String reason) {
    if (links.find(id).filter(l -> l.revokedAt() == null).isPresent()) {
      linking.revoke(id, reason);
      return;
    }
    if (links.open(id).isPresent()) {
      linking.decline(id);
      return;
    }
    throw ProblemException.notFound("Link not found", "There is no linked account or open code " + id + ".");
  }

  // ── Demo ────────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The demo world's notifications (BLUEPRINT §9): desktop on, Slack connected to {@code acme} posting to
   * {@code #deploy-approvals}, Telegram not set up. The demo's Slack has no tokens and never calls Slack.
   */
  public void seedDemo(JsonNode demo) {
    if (demo == null || demo.isMissingNode()) {
      return;
    }
    JsonNode s = demo.path("slack");
    if (s.isObject()) {
      ObjectNode config = (ObjectNode) s.path("config").deepCopy();
      config.put("demo", true);
      writeTx.executeWithoutResult(t -> channels.save(NotifyChannel.SLACK, true, config, 0));
      channels.state(NotifyChannel.SLACK, "connected", "Connected · workspace "
          + config.path("workspace").path("team").asString("?"));
      if (s.has("lastDeliveryMinutesAgo")) {
        channels.delivered(NotifyChannel.SLACK, clock.instant().minusSeconds(60 * s.path("lastDeliveryMinutesAgo")
            .asLong()));
      }
    }
    if (demo.path("desktop").isObject()) {
      ObjectNode config = ChannelStore.defaults(NotifyChannel.DESKTOP);
      demo.path("desktop").path("config").properties().forEach(e -> config.set(e.getKey(), e.getValue()));
      writeTx.executeWithoutResult(t -> channels.save(NotifyChannel.DESKTOP, demo.path("desktop").path("enabled")
          .asBoolean(true), config, 0));
    }
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The row the form edited, or 409: a stale form is refused before its tokens are tested (and the save checks the
   * version again, in its transaction).
   */
  private static ChannelStore.Row fresh(ChannelStore.Row row, long version) {
    if (row.version() != version) {
      throw new org.springframework.dao.OptimisticLockingFailureException("The " + row.channel().id()
          + " channel is at version " + row.version() + ", not " + version);
    }
    return row;
  }

  private void requireConnected(NotifyChannel channel) {
    ChannelStore.Row row = channels.read(channel);
    if (!row.active() || row.secretId("botToken") == null) {
      throw ProblemException.conflict("Connect " + title(channel) + " first", title(channel) + " isn't connected.");
    }
  }

  /** One Setup change per changed setting; tokens as "stored"/"replaced", never their value. */
  private void record(NotifyChannel channel, ChannelStore.Row before, boolean enabled, ObjectNode after,
      List<String> tokens, String reason) {
    SetupChangeRecorder recorder = setupChanges.getObject();
    String base = "Settings › Notifications · " + title(channel);
    if (!before.stored() || before.enabled() != enabled) {
      recorder.record(new SetupChange(SetupChangeRecorder.What.SETTINGS, base, before.stored() ? onOff(before
          .enabled()) : null, onOff(enabled), reason, null, null, null, null));
    }
    for (String key : List.of("soundForProd", "maxBurst", "deliverTo", "channelId", "protectContent",
        "confirmApprove")) {
      JsonNode was = before.config().get(key);
      JsonNode now = after.get(key);
      if (!Objects.equals(was, now) && (was != null || now != null)) {
        recorder.record(new SetupChange(SetupChangeRecorder.What.SETTINGS, base + " · " + key, text(was), text(now),
            reason, null, null, null, null));
      }
    }
    for (String token : tokens) {
      recorder.record(new SetupChange(SetupChangeRecorder.What.SECRET, base + " · " + ("appToken".equals(token)
          ? "app-level token" : "bot token"), before.secretId(token) == null ? null : "stored",
          before.secretId(token) == null ? "stored" : "replaced", reason, null, null, null, null));
    }
  }

  private static String text(JsonNode v) {
    return v == null || v.isNull() ? null : v.isString() ? v.asString() : v.toString();
  }

  private static String onOff(boolean on) {
    return on ? "On" : "Off";
  }

  static String title(NotifyChannel channel) {
    return switch (channel) {
      case DESKTOP -> "Desktop";
      case SLACK -> "Slack";
      case TELEGRAM -> "Telegram";
    };
  }

  private static void check(char[] token, Pattern pattern, String pointer, String hint) {
    if (token != null && !pattern.matcher(java.nio.CharBuffer.wrap(token)).matches()) {
      throw ProblemException.unprocessable("Check the token", hint, pointer);
    }
  }

  private static Map<String, char[]> tokensOf(char[] bot, char[] app) {
    Map<String, char[]> out = new LinkedHashMap<>();
    if (bot != null) {
      out.put("botToken", bot.clone());
    }
    if (app != null) {
      out.put("appToken", app.clone());
    }
    return out;
  }

  private static void clear(char[] value) {
    if (value != null) {
      Arrays.fill(value, '\0');
    }
  }

  private static void afterCommit(Runnable r) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        Thread.ofVirtual().name("notify-restart").start(r);
      }
    });
  }

  /** {@code desktop}, {@code slack}, {@code telegram}; anything else is no channel (404). */
  private static NotifyChannel channelOf(String id) {
    for (NotifyChannel c : NotifyChannel.values()) {
      if (c.id().equals(id)) {
        return c;
      }
    }
    throw ProblemException.notFound("No such channel", "The channels are desktop, slack and telegram.");
  }
}
