package ai.msrv.gaterook.core.oauth.internal;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointRemovalParticipant;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientEntity;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientKind;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientRepository;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthGrantEntity;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthGrantRepository;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthGrantState;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthTokenEntity;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthTokenKind;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthTokenRepository;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.PrincipalRevoked;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sign-ins the person allowed ({@code oauth_grant}, {@code og_…}) and their tokens ({@code oauth_token}, by hash
 * only; report 02 §6.4). A grant lives 184 days from Allow, and refreshing never extends it; one grant at a time per
 * client and endpoint (a new Allow revokes the previous one). Access tokens last until the grant expires; refresh
 * tokens rotate on every use, and presenting a used one revokes the whole grant (OAuth 2.1 §4.3.1). Every check reads
 * the database, so a Revoke takes effect on the next call. Revoking rejects what the grant still waits for and tells
 * the rest of the core ({@link PrincipalRevoked}), all in one transaction.
 */
public class Grants {

  /** 184 days covers at least six calendar months, including the longest six-month spans. */
  public static final Duration GRANT_TTL = Duration.ofDays(184);
  public static final Duration ACCESS_TTL = GRANT_TTL;
  private static final Duration USE_EVENT_INTERVAL = Duration.ofSeconds(1);
  private static final int MAX_USE_EVENTS = 1_024;

  /** Tokens just issued; the values exist only in this answer. */
  public record Issued(String accessToken, String refreshToken, long expiresIn, String scope) {

    @Override
    public String toString() {
      return "Issued[access=" + OAuthTokens.prefixOf(accessToken) + ", refresh=" + OAuthTokens.prefixOf(refreshToken)
          + ", expiresIn=" + expiresIn + "]";
    }
  }

  /** A grant as the authenticator and the token endpoint check it. */
  public record Live(String grantId, String clientRowId, String clientId, String clientHost, String clientName,
      String endpointId, Ingress ingress, String resource, String scope, Instant expiresAt) {}

  /** One row of "Signed in". */
  public record Row(String id, String clientHost, String clientName, String clientKind, String endpointId,
      String endpointSlug, Ingress ingress, String resource, String scope, Instant createdAt, Instant expiresAt,
      Instant lastUsedAt, Instant revokedAt, String revokeReason, String state) {}

  private final OAuthGrantRepository grants;
  private final OAuthTokenRepository tokens;
  private final OAuthClientRepository clients;
  private final EndpointSnapshotService snapshots;
  private final ObjectProvider<EndpointRemovalParticipant> participants;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ApplicationEventPublisher publisher;
  private final EventBus events;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  private final Map<String, Instant> lastUsed = new ConcurrentHashMap<>();
  private final Map<String, Instant> lastUseEvents = new LinkedHashMap<>();

  Grants(OAuthGrantRepository grants, OAuthTokenRepository tokens, OAuthClientRepository clients,
      EndpointSnapshotService snapshots, ObjectProvider<EndpointRemovalParticipant> participants,
      ObjectProvider<SetupChangeRecorder> setupChanges, ApplicationEventPublisher publisher, EventBus events, Clock clock,
      PlatformTransactionManager transactions) {
    this.grants = grants;
    this.tokens = tokens;
    this.clients = clients;
    this.snapshots = snapshots;
    this.participants = participants;
    this.setupChanges = setupChanges;
    this.publisher = publisher;
    this.events = events;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  // ── Allow ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The person allowed a sign-in: a new grant for 184 days; the client's previous grant for the endpoint is revoked
   * in the same transaction.
   *
   * @param why the Setup change's reason ("Code 4F7-2KQ · from 203.0.113.24")
   */
  String create(Clients.Client client, String endpointId, Ingress ingress, String resource, String scope, String why) {
    return create(client, endpointId, ingress, resource, scope, why, clock.instant(), true);
  }

  /**
   * {@link #create} as of {@code at}, 184 days from then: the demo world's sign-in of this morning, without a Setup
   * change ({@code record} false), as the kit's Activity lists none for it.
   */
  String create(Clients.Client client, String endpointId, Ingress ingress, String resource, String scope, String why,
      Instant at, boolean record) {
    return writeTx.execute(s -> {
      OAuthClientEntity c = clients.findById(client.rowId()).orElseThrow(() -> new IllegalStateException(
          "The client is gone"));
      for (OAuthGrantEntity previous : grants.findByClient_IdAndEndpointIdAndState(c.getId(), endpointId,
          OAuthGrantState.ACTIVE)) {
        revokeInTx(previous, "replaced by a new sign-in", "Replaced by a new sign-in");
      }
      Instant now = at;
      OAuthGrantEntity g = grants.saveAndFlush(new OAuthGrantEntity(c, endpointId, resource, scope, now,
          now.plus(GRANT_TTL)));
      c.setLastUsedAt(now);
      if (record) {
        Optional<EndpointSnapshot> e = snapshots.get(endpointId);
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.OAUTH, "Sign-in · " + label(c)
            + " → " + e.map(EndpointSnapshot::slug).orElse(endpointId), null, "Allowed", why,
            e.map(EndpointSnapshot::env).orElse(null), e.map(EndpointSnapshot::slug).orElse(null), null, null));
      }
      return g.getId();
    });
  }

  /** Access and refresh token for a live grant (the code was exchanged). */
  Issued issue(String grantId) {
    return writeTx.execute(s -> {
      OAuthGrantEntity g = grants.findById(grantId).orElseThrow(() -> new IllegalStateException("No grant"));
      return issueInTx(g);
    });
  }

  private Issued issueInTx(OAuthGrantEntity g) {
    Instant now = clock.instant();
    String access = OAuthTokens.random(OAuthTokens.ACCESS);
    String refresh = OAuthTokens.random(OAuthTokens.REFRESH);
    Instant accessEnd = min(now.plus(ACCESS_TTL), g.getExpiresAt());
    tokens.save(new OAuthTokenEntity(OAuthTokens.hash(access), g, OAuthTokenKind.ACCESS, now, accessEnd));
    tokens.save(new OAuthTokenEntity(OAuthTokens.hash(refresh), g, OAuthTokenKind.REFRESH, now, g.getExpiresAt()));
    g.setLastUsedAt(now);
    String id = g.getId();
    String endpoint = snapshots.get(g.getEndpointId()).map(EndpointSnapshot::slug).orElse(null);
    afterCommit(() -> {
      lastUsed.put(id, now);
      changed(id, endpoint, "active", now, true);
    });
    return new Issued(access, refresh, Math.max(1, Duration.between(now, accessEnd).toSeconds()), g.getScope());
  }

  /** Why a refresh was refused: always {@code invalid_grant} for the client. */
  enum RefreshRefusal { UNKNOWN, REUSED, NOT_LIVE, WRONG_CLIENT, WRONG_RESOURCE, WRONG_SERVER }

  /** The outcome of a refresh: new tokens, or the refusal. */
  record Refresh(Issued issued, RefreshRefusal refusal) {}

  /**
   * {@code grant_type=refresh_token}: the old refresh token is consumed and replaced; a consumed one revokes the
   * grant (theft or a replay). The grant's lifetime doesn't move.
   */
  Refresh refresh(String refreshToken, String clientId, String resource, Ingress ingress) {
    if (refreshToken == null || !refreshToken.startsWith(OAuthTokens.REFRESH) || refreshToken.length() > 128) {
      return new Refresh(null, RefreshRefusal.UNKNOWN);
    }
    return writeTx.execute(s -> {
      Optional<OAuthTokenEntity> row = tokens.findById(OAuthTokens.hash(refreshToken))
          .filter(t -> t.getKind() == OAuthTokenKind.REFRESH);
      if (row.isEmpty()) {
        return new Refresh(null, RefreshRefusal.UNKNOWN);
      }
      OAuthTokenEntity t = row.get();
      OAuthGrantEntity g = t.getGrant();
      if (g.getIngress() != ingress) {
        return new Refresh(null, RefreshRefusal.WRONG_SERVER);
      }
      if (t.getConsumedAt() != null) {
        if (g.getRevokedAt() == null) {
          revokeInTx(g, "refresh_token_reuse", "A used refresh token was presented again");
          return new Refresh(null, RefreshRefusal.REUSED);
        }
        // Revoked before (by the person, a replay or a sign-out): its tokens were consumed with it, nothing new.
        return new Refresh(null, RefreshRefusal.NOT_LIVE);
      }
      if (!g.getClient().getClientId().equals(clientId)) {
        return new Refresh(null, RefreshRefusal.WRONG_CLIENT);
      }
      if (resource != null && !OAuthUris.canonicalResource(resource).map(g.getResource()::equals).orElse(false)) {
        return new Refresh(null, RefreshRefusal.WRONG_RESOURCE);
      }
      if (!live(g, clock.instant())) {
        return new Refresh(null, RefreshRefusal.NOT_LIVE);
      }
      Issued issued = issueInTx(g);
      t.consume(clock.instant(), OAuthTokens.hash(issued.refreshToken()));
      tokens.save(t);
      return new Refresh(issued, null);
    });
  }

  // ── Checks ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** The live grant behind an access token, or empty (unknown, expired, revoked, not live). */
  Optional<Live> access(String accessToken) {
    if (accessToken == null || !accessToken.startsWith(OAuthTokens.ACCESS) || accessToken.length() > 128) {
      return Optional.empty();
    }
    String hash = OAuthTokens.hash(accessToken);
    Instant now = clock.instant();
    return readTx.execute(s -> tokens.findById(hash).filter(t -> t.getKind() == OAuthTokenKind.ACCESS
        && t.getConsumedAt() == null && t.getExpiresAt().isAfter(now)).map(OAuthTokenEntity::getGrant)
        .filter(g -> live(g, now)).map(Grants::live));
  }

  /** A grant the authorization code was issued for, if it is still live. */
  Optional<Live> live(String grantId) {
    Instant now = clock.instant();
    return readTx.execute(s -> grants.findById(grantId).filter(g -> live(g, now)).map(Grants::live));
  }

  private static boolean live(OAuthGrantEntity g, Instant now) {
    return g.getRevokedAt() == null && g.getState() == OAuthGrantState.ACTIVE && g.getExpiresAt().isAfter(now);
  }

  private static Live live(OAuthGrantEntity g) {
    OAuthClientEntity c = g.getClient();
    return new Live(g.getId(), c.getId(), c.getClientId(), c.getClientHost(), c.getClientName(), g.getEndpointId(),
        g.getIngress(), g.getResource(), g.getScope(), g.getExpiresAt());
  }

  /** Revoked, or never existed; an expired grant isn't revoked (the person's decisions about its calls stand). */
  boolean revoked(String grantId) {
    return Boolean.TRUE.equals(readTx.execute(s -> grants.findById(grantId).map(g -> g.getRevokedAt() != null)
        .orElse(true)));
  }

  boolean hasAny(String clientRowId) {
    return readTx.execute(s -> grants.findAll().stream().anyMatch(g -> g.getClient().getId().equals(clientRowId)));
  }

  /** Whether the client holds a sign-in that still works (not revoked, not expired, not of an old address). */
  boolean hasLive(String clientRowId) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(readTx.execute(s -> grants.findAll().stream()
        .anyMatch(g -> g.getClient().getId().equals(clientRowId) && live(g, now))));
  }

  /**
   * Deletes the rows of a client's sign-ins, every one of which has ended (its tokens go with them, {@code ON DELETE
   * CASCADE}); their Setup changes stay. Joins the caller's transaction.
   */
  void deleteEnded(String clientRowId) {
    Instant now = clock.instant();
    writeTx.executeWithoutResult(s -> {
      for (OAuthGrantEntity g : grants.findAll()) {
        if (g.getClient().getId().equals(clientRowId)) {
          if (live(g, now)) {
            throw new IllegalStateException("Sign-in " + g.getId() + " is live");
          }
          lastUsed.remove(g.getId());
          grants.delete(g);
        }
      }
    });
  }

  /** "1 OAuth sign-in": what keeps an empty endpoint from crossing the prod line. */
  List<String> liveAccess(String endpointId) {
    Instant now = clock.instant();
    long n = readTx.execute(s -> grants.findByEndpointId(endpointId).stream().filter(g -> live(g, now)).count());
    return n == 0 ? List.of() : List.of(n + " OAuth sign-in" + (n == 1 ? "" : "s"));
  }

  // ── Revoke ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code DELETE /api/oauth/grants/{id}}: access and refresh stop at once. */
  boolean revoke(String grantId, String reason) {
    return Boolean.TRUE.equals(writeTx.execute(s -> grants.findById(grantId).map(g -> {
      if (g.getRevokedAt() != null) {
        return false;
      }
      revokeInTx(g, reason == null ? "revoked" : reason, reason);
      return true;
    }).orElseThrow(() -> ai.msrv.gaterook.core.platform.ProblemException.notFound("Sign-in not found",
        "There is no sign-in " + grantId + "."))));
  }

  /** {@code /oauth/revoke} with a refresh token: the whole grant goes; with an access token, that token. */
  void revokeToken(String token, String clientId, Ingress ingress) {
    if (token == null || token.length() > 128) {
      return;
    }
    writeTx.executeWithoutResult(s -> tokens.findById(OAuthTokens.hash(token)).ifPresent(t -> {
      OAuthGrantEntity g = t.getGrant();
      if (g.getIngress() != ingress || clientId != null && !g.getClient().getClientId().equals(clientId)) {
        return;
      }
      if (t.getKind() == OAuthTokenKind.REFRESH) {
        if (g.getRevokedAt() == null) {
          revokeInTx(g, "revoked by the client", "The client signed out");
        }
      } else if (t.getConsumedAt() == null) {
        t.consume(clock.instant(), null);
        tokens.save(t);
      }
    }));
  }

  /** An authorization code was presented twice: the grant it made is revoked (OAuth 2.1 §4.1.3). */
  void revokeForCodeReplay(String grantId) {
    writeTx.executeWithoutResult(s -> grants.findById(grantId).filter(g -> g.getRevokedAt() == null)
        .ifPresent(g -> revokeInTx(g, "authorization_code_reuse", "An authorization code was used twice")));
  }

  /** The client's redirects changed (its metadata document) or its row was edited: its sign-ins end. */
  void revokeAllOfClient(String clientRowId, String reason) {
    for (OAuthGrantEntity g : grants.findAll()) {
      if (g.getClient().getId().equals(clientRowId) && g.getRevokedAt() == null) {
        revokeInTx(g, "client changed", reason);
      }
    }
  }

  /** Inside the caller's transaction: the grant, its tokens, what it waits for; then the core hears of it. */
  private void revokeInTx(OAuthGrantEntity g, String code, String reason) {
    Instant now = clock.instant();
    g.revoke(now, code.length() > 64 ? code.substring(0, 64) : code);
    grants.save(g);
    for (OAuthTokenEntity t : tokens.findByGrantId(g.getId())) {
      if (t.getConsumedAt() == null) {
        t.consume(now, null);
        tokens.save(t);
      }
    }
    Optional<EndpointSnapshot> e = snapshots.get(g.getEndpointId());
    String slug = e.map(EndpointSnapshot::slug).orElse(null);
    Env env = e.map(EndpointSnapshot::env).orElse(null);
    setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.OAUTH, "Sign-in · "
        + label(g.getClient()) + " → " + (slug == null ? g.getEndpointId() : slug), "Active", "Revoked", reason, env,
        slug, null, null));
    for (EndpointRemovalParticipant p : participants.orderedStream().toList()) {
      p.onPrincipalRevoked(g.getEndpointId(), slug == null ? "" : slug, g.getId(), label(g.getClient()),
          reason == null ? "The sign-in was revoked" : reason);
    }
    publisher.publishEvent(new PrincipalRevoked(Caller.Kind.OAUTH, g.getId(), g.getEndpointId(), slug));
    String id = g.getId();
    afterCommit(() -> {
      lastUsed.remove(id);
      synchronized (lastUseEvents) {
        lastUseEvents.remove(id);
      }
      changed(id, slug, "revoked", null, true);
    });
  }

  /** The endpoint is being deleted: its grants go with it (cascade); the rest of the core hears of each live one. */
  void endpointRemoving(String endpointId, String slug) {
    Instant now = clock.instant();
    for (OAuthGrantEntity g : grants.findByEndpointId(endpointId)) {
      if (live(g, now)) {
        publisher.publishEvent(new PrincipalRevoked(Caller.Kind.OAUTH, g.getId(), endpointId, slug));
      }
    }
  }

  /** A tunnel came up on {@code host}: sign-ins bound to another address can't be used any more. */
  int addressChanged(String host) {
    Integer n = writeTx.execute(s -> {
      int changed = 0;
      for (OAuthGrantEntity g : grants.findByStateOrderByCreatedAtDesc(OAuthGrantState.ACTIVE)) {
        if (g.getIngress() != Ingress.TUNNEL || g.getRevokedAt() != null) {
          continue;
        }
        String grantHost = URI.create(g.getResource()).getHost();
        if (grantHost != null && !grantHost.equalsIgnoreCase(host)) {
          g.setState(OAuthGrantState.ADDRESS_CHANGED);
          grants.save(g);
          changed++;
        }
      }
      return changed;
    });
    return n == null ? 0 : n;
  }

  // ── "Signed in" ─────────────────────────────────────────────────────────────────────────────────────────────

  List<Row> list() {
    Instant now = clock.instant();
    List<Row> out = new ArrayList<>();
    readTx.executeWithoutResult(s -> {
      for (OAuthGrantEntity g : grants.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))) {
        Instant used = lastUsed.getOrDefault(g.getId(), g.getLastUsedAt());
        String state = g.getRevokedAt() != null ? "revoked" : g.getState() == OAuthGrantState.ACTIVE
            && !g.getExpiresAt().isAfter(now) ? "expired"
            : g.getState().name().toLowerCase(Locale.ROOT).replace('_', '-');
        OAuthClientEntity c = g.getClient();
        out.add(new Row(g.getId(), c.getClientHost(), c.getClientName(), c.getKind().name().toLowerCase(Locale.ROOT),
            g.getEndpointId(), snapshots.get(g.getEndpointId()).map(EndpointSnapshot::slug).orElse(null),
            g.getIngress(), g.getResource(), g.getScope(), g.getCreatedAt(), g.getExpiresAt(), used,
            g.getRevokedAt(), g.getRevokeReason(), state));
      }
    });
    return out;
  }

  /** A call or refresh used the grant; written once a minute. */
  void used(String grantId, String endpoint) {
    Instant now = clock.instant();
    lastUsed.put(grantId, now);
    changed(grantId, endpoint, "active", now, false);
  }

  /** A burst of authenticated requests sends at most one usage event per second, with bounded bookkeeping. */
  private void changed(String id, String endpoint, String state, Instant usedAt, boolean force) {
    if ("active".equals(state)) {
      synchronized (lastUseEvents) {
        Instant previous = lastUseEvents.get(id);
        if (!force && previous != null && previous.plus(USE_EVENT_INTERVAL).isAfter(usedAt)) {
          return;
        }
        lastUseEvents.put(id, usedAt);
        while (lastUseEvents.size() > MAX_USE_EVENTS) {
          lastUseEvents.remove(lastUseEvents.keySet().iterator().next());
        }
      }
    }
    ObjectNode data = JsonNodeFactory.instance.objectNode().put("id", id).put("endpoint", endpoint)
        .put("state", state).put("lastUsedAt", usedAt == null ? null : usedAt.toString());
    events.publish("oauth.grant.changed", data);
  }

  private static void afterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          action.run();
        }
      });
    } else {
      action.run();
    }
  }

  void flushLastUsed() {
    if (lastUsed.isEmpty()) {
      return;
    }
    Map<String, Instant> batch = Map.copyOf(lastUsed);
    batch.forEach(lastUsed::remove);
    writeTx.executeWithoutResult(s -> batch.forEach((id, at) -> grants.findById(id).ifPresent(g -> {
      g.setLastUsedAt(at);
      grants.save(g);
    })));
  }

  private static String label(OAuthClientEntity c) {
    return c.getKind() == OAuthClientKind.CIMD || c.getClientName() == null ? c.getClientHost()
        : c.getClientName();
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
