package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.internal.GrantRows;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.TokenKind;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Grants ({@code gr_…}) and the bridge tokens issued for them ({@code gwb_…}, ARCHITECTURE.md A4, A7, §7.2): a grant
 * lets one client on this computer obtain 15-minute tokens for one endpoint. Only the grant's id goes into the client's
 * config, the token never does: Claude Desktop's bridge and Codex's headers helper ask Electron's broker, which asks
 * {@code /internal/bridge/tokens}, for a fresh one.
 *
 * <p>A token is issued only for a grant that verifies, isn't revoked and was made for exactly the endpoint and client
 * that ask ({@code grant_mismatch} otherwise), on an enabled endpoint. A grant's id is readable by every process of the
 * account (it sits in plain config files), so the first token of a grant to a prod endpoint in each run of the core
 * waits until the person allows it on the desktop ({@code /internal/bridge/confirm}). A grant has at most
 * {@value #MAX_LIVE_TOKENS} live tokens (a fifth replaces the oldest) and gets at most {@value #TOKENS_PER_MINUTE} a
 * minute. Logs and messages carry the grant's prefix only.
 */
@Service
public class ClientGrants implements DisposableBean {

  public static final Duration BRIDGE_TTL = Duration.ofMinutes(15);
  public static final int MAX_LIVE_TOKENS = 4;
  public static final int TOKENS_PER_MINUTE = 20;
  /** Expired bridge tokens are not audit: their rows go a day after they expire (ARCHITECTURE.md §4). */
  static final Duration PURGE_AFTER = Duration.ofHours(24);

  /** The four clients the app connects in one click (column {@code client_grant.client}). */
  public static final Set<String> CLIENTS = Set.of("claude-desktop", "codex", "cursor", "antigravity");

  /** Errors of the broker protocol v1 (report 03 §3.1, ARCHITECTURE.md §6 internal). */
  public static final String BAD_REQUEST = "bad_request";
  public static final String GRANT_UNKNOWN = "grant_unknown";
  public static final String GRANT_CHANGED = "grant_changed";
  public static final String GRANT_REVOKED = "grant_revoked";
  public static final String GRANT_MISMATCH = "grant_mismatch";
  public static final String ENDPOINT_OFF = "endpoint_off";
  public static final String CONFIRMATION_REQUIRED = "confirmation_required";
  public static final String CONFIRMATION_DENIED = "confirmation_denied";
  public static final String RATE_LIMITED = "rate_limited";

  private static final Logger log = LoggerFactory.getLogger(ClientGrants.class);
  private static final Pattern LABEL = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
  private static final Pattern BRIDGE_VERSION = Pattern.compile("^[0-9A-Za-z.+-]{1,32}$");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration CONFIRM_EVENT_EVERY = Duration.ofSeconds(30);

  /**
   * One grant as the clients module and the admin API see it.
   *
   * @param verified the row is as the core wrote it; a grant that isn't gets no tokens
   */
  public record Grant(String id, String client, String endpointId, String endpointSlug, Env env, String label,
      Instant createdAt, Instant revokedAt, Instant lastTokenAt, Long lastPid, boolean verified) {

    /** Not revoked and as the core wrote it. */
    public boolean active() {
      return revokedAt == null && verified;
    }

    /** {@code gr_Cx7q…}: what logs, messages and lists show. */
    public String prefix() {
      return ClientGrants.prefix(id);
    }
  }

  /** {@code {v, op:"token", client, endpoint, grant, pid, bridge}} as the broker forwards it. */
  public record TokenRequest(String client, String endpoint, String grant, Long pid, String bridge) {}

  /**
   * The answer to a token request: a token and where to use it, or an error code of the protocol and a sentence for
   * the person in front of the client.
   *
   * @param instance the id of this run of the core, which every {@code /mcp} answer repeats in
   *                 {@code X-Gateway-Bridge-Instance}: a bridge talks to the core that issued its token, or to nobody
   */
  public record TokenAnswer(boolean ok, String token, Instant expiresAt, String url, String instance, String error,
      String message) {

    static TokenAnswer refused(String error, String message) {
      return new TokenAnswer(false, null, null, null, null, error, message);
    }

    /** The value stays out of logs and exception messages. */
    @Override
    public String toString() {
      return ok ? "TokenAnswer[ok, expiresAt=" + expiresAt + ", url=" + url + "]"
          : "TokenAnswer[" + error + ": " + message + "]";
    }
  }

  private final ClientGrantRepository grants;
  private final ApiTokenRepository tokens;
  private final EndpointRepository endpoints;
  private final GrantRows rows;
  private final TokenService tokenService;
  private final LocalListener listener;
  private final EventBus events;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final Duration bridgeTtl;
  private final String instance;
  /** Prod grants the person allowed in this run of the core; cleared by a restart, like the app's session. */
  private final Set<String> confirmed = ConcurrentHashMap.newKeySet();
  private final Set<String> denied = ConcurrentHashMap.newKeySet();
  private final Map<String, Instant> confirmAsked = new ConcurrentHashMap<>();
  private final Map<String, Deque<Instant>> issuedRecently = new ConcurrentHashMap<>();
  private ScheduledExecutorService purger;

  public ClientGrants(ClientGrantRepository grants, ApiTokenRepository tokens, EndpointRepository endpoints,
      GrantRows rows, TokenService tokenService, LocalListener listener, EventBus events,
      ObjectProvider<SetupChangeRecorder> setupChanges, Clock clock, PlatformTransactionManager transactions,
      Environment environment) {
    this.grants = grants;
    this.tokens = tokens;
    this.endpoints = endpoints;
    this.rows = rows;
    this.tokenService = tokenService;
    this.listener = listener;
    this.events = events;
    this.setupChanges = setupChanges;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
    // Tests make tokens expire within seconds to see a client rotate them; a release always issues 15 minutes.
    Duration ttl = environment.getProperty("gateway.clients.bridge-token-ttl", Duration.class, BRIDGE_TTL);
    this.bridgeTtl = BuildFlags.RELEASE || ttl.isNegative() || ttl.isZero() ? BRIDGE_TTL : ttl;
    byte[] random = new byte[16];
    RANDOM.nextBytes(random);
    this.instance = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  }

  /** {@code gr_Cx7q…}. */
  public static String prefix(String grantId) {
    return grantId == null ? null : grantId.length() <= 7 ? grantId + "…" : grantId.substring(0, 7) + "…";
  }

  /** The id this run of the core puts on its {@code /mcp} answers ({@link TokenAnswer#instance()}). */
  public String instance() {
    return instance;
  }

  public Duration bridgeTtl() {
    return bridgeTtl;
  }

  // ── Grants ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A new grant for {@code client} on endpoint {@code slug}, signed, with its Setup change. Joins the caller's
   * transaction when there is one, so a Connect records the grant and its client link together.
   *
   * @param id a grant id chosen beforehand ({@code Ids.next("gr_")}), or {@code null} for a new one
   */
  public Grant create(String id, String client, String slug, String label, String reason) {
    if (!CLIENTS.contains(client)) {
      throw new IllegalArgumentException("Unknown client " + client);
    }
    if (label == null || label.length() > 40 || !LABEL.matcher(label).matches()) {
      throw ProblemException.unprocessable("Invalid access name", "Lowercase letters, digits and hyphens, like "
          + "codex-laptop.", "/grantLabel");
    }
    return writeTx.execute(s -> {
      EndpointEntity e = endpoints.findBySlug(slug).orElseThrow(() -> EndpointService.notFound(slug));
      Instant now = clock.instant();
      ClientGrantEntity g = rows.saveSigned(id == null ? new ClientGrantEntity(client, e, label, now)
          : new ClientGrantEntity(id, client, e, label, now));
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.GRANT, "Grant " + label + " ("
          + client + ") for " + slug, null, "Active", reason, e.getEnv(), slug, null, null));
      return view(g, true);
    });
  }

  public Optional<Grant> find(String grantId) {
    if (grantId == null) {
      return Optional.empty();
    }
    return readTx.execute(s -> grants.findById(grantId).map(g -> view(g, rows.verify(g))));
  }

  /** Every grant of {@code client}, revoked ones included, oldest first. */
  public List<Grant> forClient(String client) {
    return readTx.execute(s -> grants.findAll().stream().filter(g -> g.getClient().equals(client))
        .sorted(Comparator.comparing(ClientGrantEntity::getCreatedAt))
        .map(g -> view(g, rows.verify(g))).toList());
  }

  /**
   * Revokes a grant (Disconnect, Remove bridge, {@code DELETE /api/grants/{id}}): no new token, the live ones stop on
   * their next call, pending requests are rejected.
   *
   * @return how many pending approval requests were rejected
   */
  public int revoke(String grantId, String reason) {
    confirmed.remove(grantId);
    denied.remove(grantId);
    issuedRecently.remove(grantId);
    return tokenService.revokeGrant(grantId, reason);
  }

  // ── Bridge tokens ───────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /internal/bridge/tokens}: a fresh bridge token, or why there is none. */
  public TokenAnswer issue(TokenRequest r) {
    if (r == null || r.client() == null || !CLIENTS.contains(r.client()) || r.endpoint() == null
        || !EndpointService.SLUG.matcher(r.endpoint()).matches() || !Ids.matches("gr_", r.grant())
        || (r.bridge() != null && !BRIDGE_VERSION.matcher(r.bridge()).matches())
        || (r.pid() != null && r.pid() <= 0)) {
      return TokenAnswer.refused(BAD_REQUEST, "The bridge sent a request GateRook doesn't understand. Update "
          + "GateRook and connect the client again.");
    }
    TokenAnswer answer = writeTx.execute(s -> issueInTransaction(r));
    if (answer.ok()) {
      log.debug("Bridge token for {} ({} on {}), pid {}", prefix(r.grant()), r.client(), r.endpoint(), r.pid());
    } else {
      log.info("No bridge token for {} ({} on {}): {}", prefix(r.grant()), r.client(), r.endpoint(), answer.error());
    }
    return answer;
  }

  private TokenAnswer issueInTransaction(TokenRequest r) {
    Optional<ClientGrantEntity> found = grants.findById(r.grant());
    String who = clientName(r.client());
    if (found.isEmpty()) {
      return TokenAnswer.refused(GRANT_UNKNOWN, "GateRook doesn't know the access " + prefix(r.grant()) + ". "
          + "Connect " + who + " again from the app.");
    }
    ClientGrantEntity g = found.get();
    if (!rows.verify(g)) {
      return TokenAnswer.refused(GRANT_CHANGED, "The access " + prefix(g.getId()) + " was changed outside GateRook, so it gets no tokens. Disconnect " + who + " and connect it again in the app.");
    }
    if (g.isRevoked()) {
      return TokenAnswer.refused(GRANT_REVOKED, "Access for " + who + " to " + g.getEndpoint().getSlug()
          + " was revoked in GateRook. Connect again from the app.");
    }
    EndpointEntity e = g.getEndpoint();
    if (!e.getSlug().equals(r.endpoint()) || !g.getClient().equals(r.client())) {
      return TokenAnswer.refused(GRANT_MISMATCH, "The access " + prefix(g.getId()) + " isn't for " + who + " on "
          + r.endpoint() + ". Connect " + who + " to " + r.endpoint() + " from the app.");
    }
    if (!e.isEnabled()) {
      return TokenAnswer.refused(ENDPOINT_OFF, "Endpoint " + e.getSlug() + " is disabled in GateRook.");
    }
    if (e.getEnv() == Env.PROD && !confirmed.contains(g.getId())) {
      if (denied.contains(g.getId())) {
        return TokenAnswer.refused(CONFIRMATION_DENIED, "You didn't allow " + who + " to use " + e.getSlug()
            + " in this session of GateRook.");
      }
      askToConfirm(g, e);
      return TokenAnswer.refused(CONFIRMATION_REQUIRED, "Allow " + who + " to use " + e.getSlug() + " (prod) in "
          + "the GateRook window, then try again.");
    }
    Instant now = clock.instant();
    if (!withinRate(g.getId(), now)) {
      return TokenAnswer.refused(RATE_LIMITED, "Too many tokens for " + g.getLabel() + " in the last minute. Try "
          + "again in a minute.");
    }
    List<ApiTokenEntity> live = new ArrayList<>(tokens.findByGrantId(g.getId()).stream()
        .filter(t -> t.isLive(now)).sorted(Comparator.comparing(ApiTokenEntity::getCreatedAt)).toList());
    while (live.size() >= MAX_LIVE_TOKENS) {
      // A bridge that asks for a fifth token has usually dropped an older one; the oldest goes.
      live.removeFirst().revoke(now, "superseded");
    }
    byte[] random = new byte[32];
    RANDOM.nextBytes(random);
    String token = TokenService.BRIDGE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    java.util.Arrays.fill(random, (byte) 0);
    Instant expires = now.plus(bridgeTtl);
    tokens.save(ApiTokenEntity.bridge(g, TokenService.hash(token), now, expires));
    g.tokenIssued(now, r.pid());
    rows.saveSigned(g);
    return new TokenAnswer(true, token, expires, listener.origin() + "/mcp/" + e.getSlug(), instance, null, null);
  }

  /** Records an issue in the grant's last minute, unless that minute is full. */
  private boolean withinRate(String grantId, Instant now) {
    Deque<Instant> recent = issuedRecently.computeIfAbsent(grantId, k -> new ArrayDeque<>());
    synchronized (recent) {
      Instant from = now.minus(Duration.ofMinutes(1));
      while (!recent.isEmpty() && !recent.peekFirst().isAfter(from)) {
        recent.removeFirst();
      }
      if (recent.size() >= TOKENS_PER_MINUTE) {
        return false;
      }
      recent.addLast(now);
      return true;
    }
  }

  /**
   * The desktop asks the person once per grant and half minute, however many bridges retry meanwhile. The event names
   * the grant by its prefix, like every list and log: Electron's broker, which asks the person, has the grant from the
   * bridge's request.
   */
  private void askToConfirm(ClientGrantEntity g, EndpointEntity e) {
    Instant now = clock.instant();
    Instant last = confirmAsked.get(g.getId());
    if (last != null && last.plus(CONFIRM_EVENT_EVERY).isAfter(now)) {
      return;
    }
    confirmAsked.put(g.getId(), now);
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("grantPrefix", prefix(g.getId()));
    data.put("client", g.getClient());
    data.put("clientName", clientName(g.getClient()));
    data.put("label", g.getLabel());
    data.put("endpoint", e.getSlug());
    data.put("env", e.getEnv().id());
    data.put("text", clientName(g.getClient()) + " wants to use " + e.getSlug());
    events.publish("bridge.confirm", data);
  }

  /**
   * {@code POST /internal/bridge/confirm}: the person's answer to "Codex wants to use incident-prod", valid until the
   * core stops. Only Electron's main process can call it (the launch secret signs {@code /internal/**}).
   *
   * @return the grant, for the answer
   */
  public Grant confirm(String grantId, boolean allow) {
    Grant g = find(grantId).orElseThrow(() -> ProblemException.notFound("Access not found", "There is no access "
        + prefix(grantId) + "."));
    if (!g.active()) {
      throw ProblemException.conflict("Access is not active", "The access " + g.prefix() + " is revoked or was "
          + "changed outside GateRook.");
    }
    confirmAsked.remove(grantId);
    if (allow) {
      denied.remove(grantId);
      confirmed.add(grantId);
    } else {
      confirmed.remove(grantId);
      denied.add(grantId);
    }
    return g;
  }

  /** Whether the next token of this grant waits for the person ({@code /api/clients/links/{id}/check}). */
  public boolean awaitsConfirmation(Grant g) {
    return g.env() == Env.PROD && !confirmed.contains(g.id());
  }

  /** {@code Claude Desktop} for {@code claude-desktop}. */
  public static String clientName(String client) {
    return switch (client) {
      case "claude-desktop" -> "Claude Desktop";
      case "codex" -> "Codex";
      case "cursor" -> "Cursor";
      case "antigravity" -> "Antigravity";
      default -> client;
    };
  }

  // ── Purge ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** Bridge token rows a day past their expiry go; returns how many. */
  public int purgeExpired() {
    Integer n = writeTx.execute(s -> tokens.deleteExpired(TokenKind.BRIDGE, clock.instant().minus(PURGE_AFTER)));
    return n == null ? 0 : n;
  }

  @EventListener(ApplicationReadyEvent.class)
  synchronized void startPurging() {
    if (purger != null) {
      return;
    }
    purger = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("bridge-token-purge").factory());
    purger.scheduleWithFixedDelay(() -> {
      try {
        purgeExpired();
      } catch (RuntimeException e) {
        log.debug("Purging expired bridge tokens failed; the next hour tries again", e);
      }
    }, 1, 60, TimeUnit.MINUTES);
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    if (purger != null) {
      purger.shutdownNow();
      purger.awaitTermination(5, TimeUnit.SECONDS);
      purger = null;
    }
  }

  private static Grant view(ClientGrantEntity g, boolean verified) {
    EndpointEntity e = g.getEndpoint();
    return new Grant(g.getId(), g.getClient(), e.getId(), e.getSlug(), e.getEnv(), g.getLabel(), g.getCreatedAt(),
        g.getRevokedAt(), g.getLastTokenAt(), g.getLastPid(), verified);
  }
}
