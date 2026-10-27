package ai.msrv.gaterook.core.notifications.internal.slack;

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
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Taint;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
 * Slack (ARCHITECTURE.md §7.4, report 13): Socket Mode, so no public URL — {@code apps.connections.open} with the
 * app-level token, a WebSocket, {@code hello}, envelopes acknowledged at once; ping every 30 s, a new connection
 * when Slack asks to refresh (opened before the old one closes), backoff 1, 2, 5, 10, 30, 60 s ± 20 % after a drop.
 * Messages go by default as DMs to linked accounts, or to one channel the person chose ("Everyone in the channel sees
 * the exact action").
 *
 * <p>A click counts only when everything matches (report 13 §6.3): our installation's id in the button, the workspace
 * ({@code team.id}) and the app ({@code api_app_id}) of this connection, a linked account ({@code user.id}), and the
 * message ({@code channel} + {@code ts}) of a delivery of the same request; Approve is checked again
 * ({@link ChannelRules#decide}), and the request's versioned compare-and-set lets exactly one decision win. Everyone
 * else gets an ephemeral answer and nothing happens.
 */
@Component
public class SlackChannel implements ChatChannel, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);

  /** Scopes the app's manifest asks for (report 13 §4); a missing one is named in Test. */
  static final List<String> SCOPES = List.of("chat:write", "im:write", "users:read", "users:read.email",
      "channels:read", "groups:read");

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
  private final Masker masker;
  private final JsonMapper json;
  private final Clock clock;
  private final Pacer pacer;
  private final NotifyHttp http;
  private final SlackApi api;
  private final ZoneId zone = ZoneId.systemDefault();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
      .name("slack-timer").factory());
  private final ExecutorService work = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("slack-", 0)
      .factory());
  /** Envelopes seen lately: Slack may deliver one twice. */
  private final Map<String, Boolean> seen = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f,
      false) {
    private static final long serialVersionUID = 1L;

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
      return size() > 512;
    }
  });

  private volatile SlackSocket socket;
  private volatile String appId;
  private volatile boolean running;
  private ScheduledFuture<?> pings;
  private ScheduledFuture<?> retry;
  private int attempt;
  /**
   * Which start of the channel a connection belongs to (guarded by {@code this}): one opened before a restart — with
   * the tokens of before — never becomes the socket, and its failure never stops or reschedules the new start.
   */
  private long generation;
  /** The digest message during a flood ({@code <channel>:<ts>}), or {@code null}. */
  private volatile String digestRef;

  SlackChannel(NotifyConfig config, ChannelStore channels, LinkStore links, Linking linking, DeliveryStore deliveries,
      NotifySecrets secrets, ChannelRules rules, Notices notices, Instance instance, EventBus events,
      ApprovalService approvals, Masker masker, JsonMapper json, Clock clock, Pacers pacers, NotifyHttp http) {
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
    this.masker = masker;
    this.json = json;
    this.clock = clock;
    this.pacer = pacers.sends();
    this.http = http;
    this.api = new SlackApi(http.client(), json, config.slackApi());
  }

  @Override
  public NotifyChannel kind() {
    return NotifyChannel.SLACK;
  }

  @Override
  public boolean active() {
    ChannelStore.Row row = channels.read(NotifyChannel.SLACK);
    return row.active() && row.secretId("botToken") != null && !demo(row);
  }

  /** The demo world's Slack ({@code GW_DEMO=1}) shows as connected and never calls Slack. */
  static boolean demo(ChannelStore.Row row) {
    return row.config().path("demo").asBoolean(false) && !ai.msrv.gaterook.core.platform.BuildFlags.RELEASE;
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────────────────────

  @EventListener(ApplicationReadyEvent.class)
  void onReady() {
    work.execute(this::restart);
  }

  /** (Re)connects with the stored settings: after a save, at start; stops when the channel is off. */
  public synchronized void restart() {
    stopSocket();
    ChannelStore.Row row = channels.read(NotifyChannel.SLACK);
    if (!row.stored()) {
      return;
    }
    if (!row.verified()) {
      channels.state(NotifyChannel.SLACK, "changed-outside", "The Slack settings were changed outside GateRook. "
          + "Save them again to turn Slack back on.");
      return;
    }
    if (demo(row)) {
      return;
    }
    if (!row.enabled() || row.secretId("appToken") == null || row.secretId("botToken") == null) {
      channels.state(NotifyChannel.SLACK, "off", null);
      return;
    }
    running = true;
    attempt = 0;
    connect();
  }

  /** Disconnects for good (Disconnect Slack, the channel turned off). */
  public synchronized void stop() {
    stopSocket();
  }

  private void stopSocket() {
    running = false;
    generation++;
    cancel(retry);
    cancel(pings);
    SlackSocket s = socket;
    socket = null;
    appId = null;
    digestRef = null;
    if (s != null) {
      s.close();
    }
  }

  private synchronized void connect() {
    if (!running) {
      return;
    }
    long gen = generation;
    channels.state(NotifyChannel.SLACK, "connecting", null);
    work.execute(() -> {
      try {
        SlackSocket next = open(new Handler()).get(config.helloTimeout().toMillis() + 2_000, TimeUnit.MILLISECONDS);
        synchronized (this) {
          if (!running || gen != generation) {
            next.close();
            return;
          }
          SlackSocket old = socket;
          socket = next;
          if (old != null) {
            old.close();
          }
          // A connection that never says hello is no connection: drop it, and the retry takes over.
          timer.schedule(() -> {
            if (!next.greeted()) {
              log.info("Slack sent no hello within {}: reconnecting", config.helloTimeout());
              next.abort();
            }
          }, config.helloTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        failed(e, gen);
      }
    });
  }

  /** {@code apps.connections.open} and a WebSocket to the URL it hands out (one use, valid for a short time). */
  private CompletableFuture<SlackSocket> open(SlackSocket.Handler handler) throws IOException, InterruptedException,
      SlackApi.SlackException {
    URI url;
    try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.SLACK, null, "appToken")) {
      url = URI.create(api.call("apps.connections.open", null, tokens.get("appToken")).body().path("url")
          .asString(""));
    }
    if (!config.socketUrlAllowed(url)) {
      throw new SlackApi.SlackException("unexpected_socket_url", null, 0);
    }
    return SlackSocket.connect(http.client(), url, json, handler, config.helloTimeout());
  }

  private synchronized void failed(Throwable e, long gen) {
    if (gen != generation) {
      return; // A connection of an earlier start: what happened to it is no state of this one.
    }
    Throwable cause = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
    if (cause instanceof SlackApi.SlackException se && se.authFailed()) {
      running = false;
      channels.state(NotifyChannel.SLACK, "error", "Slack rejected the app-level token (" + se.error() + "). "
          + "Generate a new one with connections:write and replace it in Settings › Notifications.");
      return;
    }
    if (!running) {
      return;
    }
    String why = cause instanceof SlackApi.SlackException se ? "Slack answered " + se.error()
        : "GateRook can't reach Slack (" + cause.getClass().getSimpleName() + ")";
    long seconds = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
    attempt++;
    long ms = Math.max(200, (long) (seconds * 1_000 * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble())));
    channels.state(NotifyChannel.SLACK, "error", why + ". Trying again in " + Math.max(1, ms / 1_000) + " s.");
    cancel(retry);
    retry = timer.schedule(this::connect, ms, TimeUnit.MILLISECONDS);
  }

  /** The socket's events, for the current connection only (an old one that closes late changes nothing). */
  private final class Handler implements SlackSocket.Handler {

    @Override
    public void hello(SlackSocket s, String app) {
      synchronized (SlackChannel.this) {
        if (!running) {
          s.close();
          return;
        }
        appId = app;
        attempt = 0;
        cancel(pings);
        long every = config.pingInterval().toMillis();
        pings = timer.scheduleWithFixedDelay(SlackChannel.this::ping, every, every, TimeUnit.MILLISECONDS);
      }
      ChannelStore.Row row = channels.read(NotifyChannel.SLACK);
      channels.state(NotifyChannel.SLACK, "connected", "Connected · workspace "
          + row.config().path("workspace").path("team").asString("?"));
    }

    @Override
    public void envelope(SlackSocket s, String envelopeId, String type, JsonNode payload) {
      if (seen.put(envelopeId, Boolean.TRUE) != null) {
        return;
      }
      if ("interactive".equals(type)) {
        work.execute(() -> {
          try {
            interaction(payload);
          } catch (RuntimeException e) {
            log.warn("A Slack interaction failed: {}", e.toString());
          }
        });
      }
    }

    @Override
    public void disconnect(SlackSocket s, String reason) {
      if ("link_disabled".equals(reason)) {
        synchronized (SlackChannel.this) {
          stopSocket();
        }
        channels.state(NotifyChannel.SLACK, "stopped", "Socket Mode is off in the Slack app's settings. Turn it on, "
            + "then save Slack again.");
        return;
      }
      // refresh_requested, warning: a new connection first, then this one closes (no gap without a socket).
      connect();
    }

    @Override
    public void closed(SlackSocket s, Throwable error) {
      long gen;
      synchronized (SlackChannel.this) {
        if (s != socket || !running) {
          return;
        }
        socket = null;
        cancel(pings);
        gen = generation;
      }
      failed(error == null ? new IOException("the connection closed") : error, gen);
    }
  }

  private void ping() {
    SlackSocket s = socket;
    if (s == null) {
      return;
    }
    if (s.pongAge().compareTo(config.pingInterval().plus(config.pongTimeout())) > 0) {
      log.info("Slack didn't answer a ping: reconnecting");
      s.abort();
      return;
    }
    s.ping();
  }

  @Override
  public void destroy() {
    stop();
    timer.shutdownNow();
    work.shutdownNow();
  }

  private static void cancel(ScheduledFuture<?> f) {
    if (f != null) {
      f.cancel(false);
    }
  }

  // ── Deliveries ──────────────────────────────────────────────────────────────────────────────────────────────

  /** Where requests go: the chosen channel, or the DMs of the linked accounts of this workspace. */
  private List<String> targets(ChannelStore.Row row) {
    if ("channel".equals(row.text("deliverTo"))) {
      String id = row.text("channelId");
      return id == null ? List.of() : List.of(id);
    }
    String team = row.config().path("workspace").path("teamId").asString(null);
    return links.active(NotifyChannel.SLACK).stream()
        .filter(l -> team == null || team.equals(l.teamId())).map(LinkStore.Link::chatId)
        .filter(c -> c != null && !c.isBlank()).distinct().toList();
  }

  static boolean fits(ApprovalNotice n) {
    return n.action().text().length() <= Eligibility.SLACK_ACTION_LIMIT;
  }

  @Override
  public Delivered deliver(ApprovalNotice n) throws InterruptedException {
    ChannelStore.Row row = channels.read(NotifyChannel.SLACK);
    if (!row.active()) {
      return Delivered.failed("Slack is off");
    }
    List<String> targets = targets(row);
    if (targets.isEmpty()) {
      return Delivered.failed("channel".equals(row.text("deliverTo")) ? "No Slack channel is chosen"
          : "No linked Slack account: link yours in Settings › Notifications");
    }
    Eligibility.Verdict verdict = rules.verdict(Eligibility.Channel.SLACK, n, fits(n), false, true);
    boolean any = false;
    String error = null;
    for (String target : targets) {
      DeliveryStore.Delivery d = deliveries.start(n.id(), NotifyChannel.SLACK, target, verdict.approve());
      ObjectNode body = SlackBlocks.request(n, verdict, instance.id(), "1", zone, null);
      try {
        String ts = post(target, body, n.id());
        deliveries.sent(d.id(), target + ":" + ts);
        any = true;
      } catch (SlackApi.SlackException e) {
        if ("invalid_blocks".equals(e.error()) && verdict.showAction()) {
          // Slack refused the code block (a language it doesn't know?): the action stays in the app, no Approve.
          Eligibility.Verdict plain = new Eligibility.Verdict(false, verdict.deny(), false,
              "Slack couldn't show this action: approve in the app.",
              "Slack couldn't show this action, so it is shown only in GateRook.");
          try {
            String ts = post(target, SlackBlocks.request(n, plain, instance.id(), "1", zone, null), n.id());
            deliveries.sent(d.id(), target + ":" + ts);
            any = true;
            continue;
          } catch (SlackApi.SlackException | IOException again) {
            e = again instanceof SlackApi.SlackException s ? s : e;
          }
        }
        error = describe(e);
        deliveries.failed(d.id(), error);
      } catch (IOException e) {
        error = "GateRook can't reach Slack";
        deliveries.failed(d.id(), error);
      }
    }
    if (any) {
      channels.delivered(NotifyChannel.SLACK, clock.instant());
      return Delivered.sent();
    }
    return Delivered.failed("Slack failed: " + error);
  }

  /**
   * {@code chat.postMessage} to {@code channel}, paced (1/s per channel), up to 3 attempts while the request waits
   * (a rate limit waits as long as Slack asks). Returns the message's {@code ts}.
   */
  private String post(String channel, ObjectNode body, String approvalId) throws SlackApi.SlackException, IOException,
      InterruptedException {
    ObjectNode args = body.deepCopy();
    args.put("channel", channel);
    Exception last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      if (attempt > 0 && approvalId != null && approvals.find(approvalId).map(a -> !a.pending()).orElse(true)) {
        break;
      }
      pacer.await("slack:" + channel);
      try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.SLACK, null, "botToken")) {
        return api.call("chat.postMessage", args, tokens.get("botToken")).body().path("ts").asString();
      } catch (SlackApi.SlackException e) {
        last = e;
        if (!"ratelimited".equals(e.error()) && !e.error().startsWith("http_5")
            && !"internal_error".equals(e.error()) && !"service_unavailable".equals(e.error())) {
          throw e;
        }
        Thread.sleep(Math.max(config.retryDelay().toMillis() * (attempt == 0 ? 1 : 3),
            e.retryAfterSeconds() * 1_000));
      } catch (IOException e) {
        last = e;
        Thread.sleep(config.retryDelay().toMillis() * (attempt == 0 ? 1 : 3));
      }
    }
    if (last instanceof SlackApi.SlackException se) {
      throw se;
    }
    throw last instanceof IOException io ? io : new IOException("not sent: the request was decided");
  }

  private void update(String ref, ObjectNode body) throws SlackApi.SlackException, IOException,
      InterruptedException {
    int colon = ref.indexOf(':');
    if (colon < 0) {
      return;
    }
    ObjectNode args = body.deepCopy();
    args.put("channel", ref.substring(0, colon));
    args.put("ts", ref.substring(colon + 1));
    pacer.await("slack:" + ref.substring(0, colon));
    try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.SLACK, null, "botToken")) {
      api.call("chat.update", args, tokens.get("botToken"));
    }
  }

  @Override
  public void resolved(ApprovalView a, List<DeliveryStore.Delivery> sent, Long durationMs)
      throws InterruptedException {
    ApprovalNotice n = notices.of(a);
    Outcomes.Outcome outcome = Outcomes.of(a, durationMs, zone);
    Eligibility.Verdict verdict = rules.verdict(Eligibility.Channel.SLACK, n, fits(n), false, false);
    String text = notices.mask(outcome.text(), a.env());
    for (DeliveryStore.Delivery d : sent) {
      if (d.externalRef() == null) {
        continue;
      }
      try {
        update(d.externalRef(), SlackBlocks.request(n, verdict, instance.id(), rev(), zone, text));
        if (outcome.done()) {
          deliveries.resolved(d.id());
        }
      } catch (SlackApi.SlackException | IOException e) {
        log.info("Couldn't update the Slack message of {}: {}", a.id(), e.toString());
        if (outcome.done()) {
          deliveries.resolved(d.id());
        }
      }
    }
  }

  @Override
  public Delivered digest(long waiting) throws InterruptedException {
    ChannelStore.Row row = channels.read(NotifyChannel.SLACK);
    List<String> targets = targets(row);
    if (targets.isEmpty()) {
      return Delivered.failed("No Slack channel or linked account");
    }
    String target = targets.get(0);
    ObjectNode body = SlackBlocks.digest((int) Math.min(waiting, Integer.MAX_VALUE), instance.id(), rev());
    try {
      String ref = digestRef;
      if (ref != null && ref.startsWith(target + ":")) {
        update(ref, body);
      } else {
        digestRef = target + ":" + post(target, body, null);
      }
      channels.delivered(NotifyChannel.SLACK, clock.instant());
      return Delivered.sent();
    } catch (SlackApi.SlackException e) {
      return Delivered.failed("Slack failed: " + describe(e));
    } catch (IOException e) {
      return Delivered.failed("Slack failed: GateRook can't reach Slack");
    }
  }

  @Override
  public void refreshDigest(long waiting) throws InterruptedException {
    String ref = digestRef;
    if (ref == null) {
      return;
    }
    try {
      update(ref, SlackBlocks.digest((int) Math.min(waiting, Integer.MAX_VALUE), instance.id(), rev()));
    } catch (SlackApi.SlackException | IOException e) {
      log.debug("Couldn't update the Slack digest: {}", e.toString());
    }
    if (waiting == 0) {
      digestRef = null;
    }
  }

  private String rev() {
    return Long.toString(clock.millis(), 36);
  }

  // ── Interactions ────────────────────────────────────────────────────────────────────────────────────────────

  /** A {@code block_actions} payload from the socket: checked, then decided, linked or answered. */
  void interaction(JsonNode p) {
    if (!"block_actions".equals(p.path("type").asString())) {
      return;
    }
    JsonNode action = p.path("actions").path(0);
    String actionId = action.path("action_id").asString("");
    String[] v = action.path("value").asString("").split("\\|", -1);
    if (v.length != 4 || !"v1".equals(v[0])) {
      return;
    }
    String channel = p.at("/container/channel_id").asString(p.at("/channel/id").asString(""));
    String ts = p.at("/container/message_ts").asString("");
    String user = p.at("/user/id").asString("");
    if (!instance.id().equals(v[1])) {
      ephemeral(channel, user, "This request belongs to another GateRook.");
      return;
    }
    ChannelStore.Row row = channels.read(NotifyChannel.SLACK);
    String team = row.config().path("workspace").path("teamId").asString(null);
    String app = appId;
    if (!row.active() || team == null || !team.equals(p.at("/team/id").asString(null)) || app == null
        || !app.equals(p.path("api_app_id").asString(null))) {
      log.warn("A Slack click from another workspace or app was ignored");
      return;
    }
    String subject = v[2];
    String check = "-".equals(v[3]) ? null : v[3];
    switch (actionId) {
      case SlackBlocks.TEST -> ephemeral(channel, user, "Test button works. Nothing ran.");
      case SlackBlocks.LINK, SlackBlocks.NOT_ME -> link(SlackBlocks.LINK.equals(actionId), check, team, user,
          channel, ts);
      case SlackBlocks.OPEN, SlackBlocks.APPROVE, SlackBlocks.DENY -> decision(actionId, subject, check, team, user,
          channel, ts);
      default -> { }
    }
  }

  private void decision(String actionId, String subject, String check, String team, String user, String channel,
      String ts) {
    Optional<LinkStore.Link> link = links.active(NotifyChannel.SLACK).stream()
        .filter(l -> user.equals(l.userId()) && team.equals(l.teamId())).findFirst();
    if ("digest".equals(subject)) {
      if (link.isPresent() && SlackBlocks.OPEN.equals(actionId)) {
        open("/approvals");
        ephemeral(channel, user, "Opened in GateRook on " + instance.host() + ".");
      }
      return;
    }
    Optional<DeliveryStore.Delivery> delivery = deliveries.at(NotifyChannel.SLACK, channel + ":" + ts, subject);
    if (link.isEmpty() || delivery.isEmpty()) {
      ephemeral(channel, user, "Only the linked approver can act on this request. It is decided in GateRook.");
      return;
    }
    switch (actionId) {
      case SlackBlocks.OPEN -> {
        open("/approvals/" + subject);
        ephemeral(channel, user, "Opened in GateRook on " + instance.host() + ".");
      }
      case SlackBlocks.DENY, SlackBlocks.APPROVE -> {
        ChannelRules.Clicked c = rules.decide(Eligibility.Channel.SLACK, subject, SlackBlocks.APPROVE.equals(actionId),
            check, user, delivery.get().offeredApprove(), SlackChannel::fits);
        if (!c.decided()) {
          ephemeral(channel, user, c.text());
        }
      }
      default -> { }
    }
  }

  private void link(boolean accept, String nonce, String team, String user, String channel, String ts) {
    if (nonce == null) {
      return;
    }
    String pairingId = LinkStore.hash(nonce);
    Optional<LinkStore.Pairing> pairing = links.open(pairingId).filter(p -> p.channel() == NotifyChannel.SLACK);
    if (pairing.isEmpty()) {
      ephemeral(channel, user, "This link request expired or was already answered. Start again in GateRook.");
      return;
    }
    JsonNode c = pairing.get().candidate();
    if (c == null || !user.equals(c.path("userId").asString(null)) || !team.equals(c.path("teamId").asString(null))
        || !channel.equals(c.path("dm").asString(null)) || !ts.equals(c.path("messageTs").asString(ts))) {
      ephemeral(channel, user, "This link request is for another Slack account.");
      return;
    }
    String host = instance.host();
    if (accept) {
      Optional<LinkStore.Link> linked = linking.complete(pairingId, NotifyChannel.SLACK, team, user, channel,
          c.path("display").asString(null), null);
      answerLink(channel, ts, linked.isPresent() ? "Linked to GateRook on " + host + ". Approval requests come "
          + "here, and you can decide them with the buttons." : "This link request was already answered.");
    } else {
      linking.decline(pairingId);
      answerLink(channel, ts, "Not linked: nothing changed. If you didn't ask for this, ignore it.");
    }
  }

  private void answerLink(String channel, String ts, String text) {
    try {
      update(channel + ":" + ts, SlackBlocks.linkAnswered(text));
    } catch (SlackApi.SlackException | IOException e) {
      log.info("Couldn't update the Slack link message: {}", e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void open(String path) {
    events.publish("ui.open", JsonNodeFactory.instance.objectNode().put("path", path));
  }

  /** {@code chat.postEphemeral}: only the clicker sees it, nothing else changes. */
  private void ephemeral(String channel, String user, String text) {
    if (channel == null || channel.isBlank() || user == null || user.isBlank()) {
      return;
    }
    ObjectNode args = JsonNodeFactory.instance.objectNode().put("channel", channel).put("user", user)
        .put("text", text);
    try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.SLACK, null, "botToken")) {
      api.call("chat.postEphemeral", args, tokens.get("botToken"));
    } catch (SlackApi.SlackException | IOException e) {
      log.info("Couldn't answer a Slack click: {}", e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ── Linking, channels, tests (Settings › Notifications) ─────────────────────────────────────────────────────

  /** A link request by email: the account found, a DM with the code and the two buttons. */
  public record LinkStarted(LinkStore.Pairing pairing, String code, String display) {}

  /**
   * {@code POST /api/notifications/slack/links {email}}: looks the account up, opens a DM, posts "Link GateRook on
   * ravi-mbp to this Slack account? Code 4F7-2KQ". The link exists once that account clicks Link account.
   *
   * @throws SlackApi.SlackException {@code users_not_found} and the like, in Slack's words
   */
  public LinkStarted startLink(String email) throws SlackApi.SlackException, IOException, InterruptedException {
    ChannelStore.Row row = channels.read(NotifyChannel.SLACK);
    String team = row.config().path("workspace").path("teamId").asString(null);
    if (!row.active() || team == null) {
      throw new SlackApi.SlackException("not_connected", null, 0);
    }
    try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.SLACK, null, "botToken")) {
      String bot = tokens.get("botToken");
      JsonNode user = api.call("users.lookupByEmail", JsonNodeFactory.instance.objectNode().put("email", email), bot)
          .body().path("user");
      String userId = user.path("id").asString(null);
      if (userId == null || user.path("is_bot").asBoolean(false) || user.path("deleted").asBoolean(false)) {
        throw new SlackApi.SlackException("users_not_found", null, 0);
      }
      String display = display(user);
      String dm = api.call("conversations.open", JsonNodeFactory.instance.objectNode().put("users", userId), bot)
          .body().at("/channel/id").asString(null);
      if (dm == null) {
        throw new SlackApi.SlackException("channel_not_found", null, 0);
      }
      String nonce = Linking.nonce();
      String code = Linking.shortCode();
      ObjectNode candidate = JsonNodeFactory.instance.objectNode().put("userId", userId).put("teamId", team)
          .put("display", display).put("dm", dm).put("code", code);
      LinkStore.Pairing pairing = linking.pairing(NotifyChannel.SLACK, nonce, candidate);
      ObjectNode args = SlackBlocks.linkRequest(instance.host(), code, instance.id(), nonce);
      args.put("channel", dm);
      pacer.await("slack:" + dm);
      String ts = api.call("chat.postMessage", args, bot).body().path("ts").asString(null);
      candidate.put("messageTs", ts);
      links.present(pairing.id(), candidate);
      return new LinkStarted(pairing, code, display);
    }
  }

  private static String display(JsonNode user) {
    String real = user.path("profile").path("real_name").asString(user.path("real_name").asString(""));
    String name = user.path("profile").path("display_name").asString("");
    if (name.isBlank()) {
      name = user.path("name").asString("");
    }
    String out = real.isBlank() ? name : name.isBlank() || name.equals(real) ? real : real + " (@" + name + ")";
    return out.isBlank() ? user.path("id").asString() : out;
  }

  /** A channel the app was added to, for the "Post to a channel" list. */
  public record ChannelOption(String id, String name, boolean isPrivate) {}

  /** {@code users.conversations}: the channels the bot is a member of. */
  public List<ChannelOption> channelsOfTheApp() throws SlackApi.SlackException, IOException, InterruptedException {
    List<ChannelOption> out = new ArrayList<>();
    try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.SLACK, null, "botToken")) {
      String cursor = "";
      for (int page = 0; page < 10; page++) {
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("types", "public_channel,private_channel")
            .put("exclude_archived", true).put("limit", 200);
        if (!cursor.isEmpty()) {
          args.put("cursor", cursor);
        }
        JsonNode body = api.call("users.conversations", args, tokens.get("botToken")).body();
        body.path("channels").forEach(c -> out.add(new ChannelOption(c.path("id").asString(),
            c.path("name").asString(), c.path("is_private").asBoolean(false))));
        cursor = body.at("/response_metadata/next_cursor").asString("");
        if (cursor.isEmpty()) {
          break;
        }
      }
    }
    out.sort(java.util.Comparator.comparing(ChannelOption::name));
    return out;
  }

  /**
   * Test (report 13 §5): {@code auth.test} with the bot token (workspace, bot, scopes), {@code apps.connections.open}
   * with the app-level token and a WebSocket until {@code hello} (≤ 10 s), the channel's membership and audience when
   * requests go to a channel. Tokens typed into the form are tested before anything is saved.
   *
   * @param typed tokens from the form ({@code botToken}, {@code appToken}); the stored ones fill the rest
   */
  public TestResult test(Map<String, char[]> typed, String deliverTo, String channelId) throws InterruptedException {
    long t0 = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    List<TestResult.Hint> hints = new ArrayList<>();
    ObjectNode details = JsonNodeFactory.instance.objectNode();
    try (NotifySecrets.Tokens tokens = secrets.open(NotifyChannel.SLACK, typed, "botToken", "appToken")) {
      if (!tokens.has("botToken") || !tokens.has("appToken")) {
        return TestResult.failure("missing_token", "Add both tokens: the Bot User OAuth Token (xoxb-…) and an "
            + "app-level token (xapp-…) with connections:write.", null);
      }
      long s = System.nanoTime();
      SlackApi.Reply auth;
      try {
        auth = api.call("auth.test", null, tokens.get("botToken"));
      } catch (SlackApi.SlackException e) {
        return fail(checks, "auth.test", s, e.authFailed() ? "Slack rejected the bot token (" + e.error() + "). Copy "
            + "the Bot User OAuth Token (xoxb-…) again from the app's OAuth & Permissions page." : "Slack refused the "
            + "bot token: " + e.error() + ".", e.error(), tokens.taint());
      }
      JsonNode a = auth.body();
      checks.add(new TestResult.Check("auth.test", true, ms(s), "workspace " + a.path("team").asString("?")));
      details.put("teamId", a.path("team_id").asString(null)).put("team", a.path("team").asString(null))
          .put("bot", a.path("user").asString(null)).put("botUserId", a.path("user_id").asString(null))
          .put("botId", a.path("bot_id").asString(null));
      if (auth.scopes() != null) {
        List<String> have = List.of(auth.scopes().replace(" ", "").split(","));
        List<String> missing = SCOPES.stream().filter(x -> !have.contains(x)).toList();
        if (!missing.isEmpty()) {
          hints.add(new TestResult.Hint(missing.contains("chat:write") ? TestResult.Severity.ERROR
              : TestResult.Severity.WARNING, "missing-scopes", "/botToken", "The app lacks " + String.join(", ",
              missing) + ". Add them under OAuth & Permissions, reinstall the app and copy the new bot token."));
        }
      }
      s = System.nanoTime();
      try {
        String app = hello(tokens.get("appToken"));
        details.put("appId", app);
        checks.add(new TestResult.Check("socket-mode", true, ms(s), "hello from app " + app));
      } catch (SlackApi.SlackException e) {
        return fail(checks, "socket-mode", s, e.authFailed() ? "Slack rejected the app-level token (" + e.error()
            + "). Generate a new one with connections:write under Basic Information › App-Level Tokens."
            : "unexpected_socket_url".equals(e.error()) ? "Slack handed out an unexpected Socket Mode address."
            : "Socket Mode didn't start: " + e.error() + ". Turn Socket Mode on in the app's settings.", e.error(),
            tokens.taint());
      } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException | IOException e) {
        return fail(checks, "socket-mode", s, "Socket Mode didn't answer within " + config.helloTimeout().toSeconds()
            + " s. Check that Socket Mode is on in the app's settings and that this network allows WebSocket.",
            e.getClass().getSimpleName(), tokens.taint());
      }
      if ("channel".equals(deliverTo) && channelId != null) {
        s = System.nanoTime();
        try {
          JsonNode c = api.call("conversations.info", JsonNodeFactory.instance.objectNode().put("channel", channelId)
              .put("include_num_members", true), tokens.get("botToken")).body().path("channel");
          String name = "#" + c.path("name").asString(channelId);
          checks.add(new TestResult.Check("channel", true, ms(s), name));
          details.put("channelName", name);
          if (!c.path("is_member").asBoolean(false)) {
            hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "not-in-channel", "/channelId",
                "The app isn't in " + name + ". Invite it with /invite @" + a.path("user").asString("mcp-gateway")
                    + "."));
          }
          if (!c.path("is_private").asBoolean(false)) {
            hints.add(new TestResult.Hint(TestResult.Severity.WARNING, "public-channel", "/channelId", name
                + " is public: anyone in " + a.path("team").asString("the workspace") + " can open it and read "
                + "exact actions."));
          }
          int members = c.path("num_members").asInt(0);
          hints.add(new TestResult.Hint(TestResult.Severity.INFO, "audience", "/channelId", "Everyone in the channel"
              + (members > 0 ? " (" + members + " members)" : "") + " sees the exact action."));
        } catch (SlackApi.SlackException e) {
          return fail(checks, "channel", s, "Slack can't find that channel for the app (" + e.error() + "). Pick a "
              + "channel the app was added to.", e.error(), tokens.taint());
        }
      } else if (links.active(NotifyChannel.SLACK).stream().noneMatch(l -> l.teamId() != null
          && l.teamId().equals(details.path("teamId").asString(null)))) {
        hints.add(new TestResult.Hint(TestResult.Severity.INFO, "no-linked-account", null, "Link your Slack account "
            + "to get requests as direct messages."));
      }
      String id = a.path("team").asString("?");
      return new TestResult(true, ms(t0), null, "workspace " + id + " · bot @" + a.path("user").asString("?"), null,
          List.copyOf(hints), List.copyOf(checks), null, null, null, null, details);
    } catch (IOException e) {
      return TestResult.failure("network", "GateRook can't reach slack.com. Check the network or the proxy.",
          e.getClass().getSimpleName()).withChecks(checks);
    }
  }

  /** A throwaway Socket Mode connection until {@code hello}: proves the app-level token and Socket Mode. */
  private String hello(String appToken) throws SlackApi.SlackException, IOException, InterruptedException,
      java.util.concurrent.TimeoutException, java.util.concurrent.ExecutionException {
    URI url = URI.create(api.call("apps.connections.open", null, appToken).body().path("url").asString(""));
    if (!config.socketUrlAllowed(url)) {
      throw new SlackApi.SlackException("unexpected_socket_url", null, 0);
    }
    CompletableFuture<String> hello = new CompletableFuture<>();
    SlackSocket probe = SlackSocket.connect(http.client(), url, json, new SlackSocket.Handler() {
      @Override
      public void hello(SlackSocket s, String app) {
        hello.complete(app == null ? "?" : app);
      }

      @Override
      public void envelope(SlackSocket s, String envelopeId, String type, JsonNode payload) {
        // Acknowledged by the socket; the running connection handles interactions.
      }

      @Override
      public void disconnect(SlackSocket s, String reason) {
        hello.completeExceptionally(new SlackApi.SlackException(reason, null, 0));
      }

      @Override
      public void closed(SlackSocket s, Throwable error) {
        hello.completeExceptionally(error == null ? new IOException("closed before hello") : error);
      }
    }, config.helloTimeout()).get(config.helloTimeout().toMillis() + 1_000, TimeUnit.MILLISECONDS);
    try {
      return hello.get(config.helloTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof SlackApi.SlackException se) {
        throw se;
      }
      throw e;
    } finally {
      probe.close();
    }
  }

  /** Posts the test message to every target ("Send test notification"). */
  public Delivered sendTest() throws InterruptedException {
    ChannelStore.Row row = channels.read(NotifyChannel.SLACK);
    if (!active()) {
      return Delivered.failed("Slack is off");
    }
    List<String> targets = targets(row);
    if (targets.isEmpty()) {
      return Delivered.failed("No Slack channel or linked account");
    }
    String error = null;
    boolean any = false;
    for (String target : targets) {
      try {
        post(target, SlackBlocks.test(instance.id()), null);
        any = true;
      } catch (SlackApi.SlackException e) {
        error = describe(e);
      } catch (IOException e) {
        error = "GateRook can't reach Slack";
      }
    }
    return any ? Delivered.sent() : Delivered.failed("Slack failed: " + error);
  }

  private TestResult fail(List<TestResult.Check> checks, String name, long started, String message, String code,
      Taint taint) {
    checks.add(new TestResult.Check(name, false, ms(started), code));
    String safe = masker.text(message, taint, MaskPolicy.SECRETS_ONLY).text();
    return TestResult.failure(code == null ? "failed" : code, safe, code).withChecks(checks);
  }

  /** Slack's error in words a person can act on. */
  static String describe(SlackApi.SlackException e) {
    return switch (e.error()) {
      case "not_in_channel" -> "the app isn't in the channel (not_in_channel): invite it with /invite";
      case "channel_not_found" -> "the channel doesn't exist for the app (channel_not_found)";
      case "is_archived" -> "the channel is archived (is_archived)";
      case "ratelimited" -> "Slack asked to slow down (ratelimited)";
      default -> e.authFailed() ? "Slack rejected the bot token (" + e.error() + ")" : e.error();
    };
  }

  private static long ms(long since) {
    return Duration.ofNanos(System.nanoTime() - since).toMillis();
  }
}
