package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.core.endpoints.internal.GrantRows;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.TokenKind;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.PrincipalRevoked;
import ai.msrv.gaterook.core.platform.PrincipalStatus;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Named endpoint tokens ({@code gwt_}, ARCHITECTURE.md A4, kit {@code token-issue}): 256 random bits, shown once,
 * stored only as {@code b64url(sha256)} and looked up on every request, so Revoke takes effect on the next call.
 * The default lifetime is 184 days (Settings › Authorization). People see a prefix ({@code gwt_K9xP…}), never the value.
 * Bridge tokens ({@code gwb_}, 15 minutes, per grant) are issued by the clients module; their rows show here as
 * "issued on the fly".
 */
@Service
public class TokenService implements PrincipalStatus {

  /** {@code gwt_}: what a named token starts with. */
  public static final String NAMED_PREFIX = "gwt_";

  /** {@code gwb_}: a bridge token. */
  public static final String BRIDGE_PREFIX = "gwb_";

  /** At least six calendar months, including the longest six-month interval. */
  public static final Duration DEFAULT_TTL = Duration.ofDays(184);
  public static final int MAX_TTL_DAYS = 184;

  private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The one response that carries a token's value. */
  public record Issued(String id, String name, String token, String prefix, Instant expiresAt) {

    /** The value stays out of logs and exception messages. */
    @Override
    public String toString() {
      return "Issued[id=" + id + ", name=" + name + ", prefix=" + prefix + "…, expiresAt=" + expiresAt + "]";
    }
  }

  /**
   * One row of Tokens.
   *
   * @param kind  {@code named} or {@code grant} ("issued on the fly")
   * @param state {@code active}, {@code expired} or {@code revoked}
   */
  public record TokenRow(String id, String kind, String name, String prefix, String client, Instant createdAt,
      Instant expiresAt, Instant lastUsedAt, Instant revokedAt, String state) {}

  private final ApiTokenRepository tokens;
  private final ClientGrantRepository grants;
  private final GrantRows grantRows;
  private final EndpointRepository endpoints;
  private final SettingStore settings;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ObjectProvider<EndpointRemovalParticipant> participants;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;
  /** Token id → last use, flushed to {@code last_used_at} in batches (ARCHITECTURE.md §4). */
  private final Map<String, Instant> lastUsed = new ConcurrentHashMap<>();

  public TokenService(ApiTokenRepository tokens, ClientGrantRepository grants, GrantRows grantRows,
      EndpointRepository endpoints, SettingStore settings, ObjectProvider<SetupChangeRecorder> setupChanges,
      ObjectProvider<EndpointRemovalParticipant> participants, ApplicationEventPublisher publisher, Clock clock,
      PlatformTransactionManager transactions) {
    this.tokens = tokens;
    this.grants = grants;
    this.grantRows = grantRows;
    this.endpoints = endpoints;
    this.settings = settings;
    this.setupChanges = setupChanges;
    this.participants = participants;
    this.publisher = publisher;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  /**
   * {@code POST /api/endpoints/{slug}/tokens {name, ttlDays}}: the value is in the result and nowhere else.
   *
   * @param ttlDays 1–184, or {@code null} for the default of Settings › Authorization (184 days)
   */
  public Issued issue(String slug, String name, Integer ttlDays, String reason) {
    return issue(slug, name, ttlDays, reason, null);
  }

  /**
   * As {@link #issue(String, String, Integer, String)}, counted as issued at {@code issuedAt}: the demo seed backdates
   * {@code ci-smoke} so that it has expired, as in the kit (design/BLUEPRINT.md §9). The admin API always issues now.
   * Backdating only shortens a token's life, so a time in the future is refused.
   *
   * @param issuedAt when the token counts as issued, or {@code null} for now
   */
  public Issued issue(String slug, String name, Integer ttlDays, String reason, Instant issuedAt) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw ProblemException.unprocessable("Invalid name", "Lowercase letters, digits and hyphens, like "
          + "inspector-debug.", "/name");
    }
    Duration ttl = Duration.ofDays(ttlDays == null ? defaultTtlDays() : ttlDays);
    if (ttl.toDays() < 1 || ttl.toDays() > MAX_TTL_DAYS) {
      throw ProblemException.unprocessable("Invalid lifetime", "A token lives 1 to " + MAX_TTL_DAYS + " days.", "/ttlDays");
    }
    byte[] random = new byte[32];
    RANDOM.nextBytes(random);
    String token = NAMED_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    java.util.Arrays.fill(random, (byte) 0);
    String prefix = token.substring(0, NAMED_PREFIX.length() + 4);
    return writeTx.execute(s -> {
      EndpointEntity e = endpoints.findBySlug(slug).orElseThrow(() -> EndpointService.notFound(slug));
      Instant now = clock.instant();
      if (issuedAt != null && issuedAt.isAfter(now)) {
        throw new IllegalArgumentException("A token can't be issued in the future");
      }
      Instant from = issuedAt == null ? now : issuedAt;
      ApiTokenEntity row = tokens.save(ApiTokenEntity.named(e, name, prefix, hash(token), from, from.plus(ttl)));
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.TOKEN,
          "Token " + name + " for " + slug, null, prefix + "… · expires " + row.getExpiresAt(), reason, e.getEnv(),
          slug, null, null));
      return new Issued(row.getId(), name, token, prefix, row.getExpiresAt());
    });
  }

  /**
   * {@code DELETE /api/tokens/{id}}: the next call with it gets 401, and what a named token's principal still waits
   * for is rejected in the same transaction (a bridge token is not a principal: its grant is).
   *
   * @return how many pending approval requests were rejected
   */
  public int revoke(String tokenId, String reason) {
    return writeTx.execute(s -> {
      ApiTokenEntity t = tokens.findById(tokenId).orElseThrow(() -> ProblemException.notFound("Token not found",
          "There is no token " + tokenId + "."));
      if (t.getRevokedAt() != null) {
        return 0;
      }
      t.revoke(clock.instant(), reason == null ? "revoked" : truncate(reason, 64));
      tokens.save(t);
      lastUsed.remove(tokenId);
      String name = t.getName() == null ? t.getId() : t.getName();
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.TOKEN,
          "Token " + name + " for " + t.getEndpoint().getSlug(), "Active", "Revoked", reason,
          t.getEndpoint().getEnv(), t.getEndpoint().getSlug(), null, null));
      return t.getKind() == TokenKind.NAMED
          ? principalRevoked(t.getEndpoint(), Caller.Kind.LOCAL_TOKEN, t.getId(), name, reason) : 0;
    });
  }

  /**
   * {@code DELETE /api/grants/{id}}: the helper or bridge of that client gets no new token, the tokens it holds stop
   * on the next call, and what the grant still waits for is rejected in the same transaction.
   *
   * @return how many pending approval requests were rejected
   */
  public int revokeGrant(String grantId, String reason) {
    return writeTx.execute(s -> {
      ClientGrantEntity g = grants.findById(grantId).orElseThrow(() -> ProblemException.notFound("Grant not found",
          "There is no grant " + grantId + "."));
      if (g.isRevoked()) {
        return 0;
      }
      Instant now = clock.instant();
      // Revoking is always safe; signing again only vouches for a row that was as the core wrote it.
      boolean trusted = grantRows.verify(g);
      g.revoke(now);
      if (trusted) {
        grantRows.saveSigned(g);
      } else {
        grants.save(g);
      }
      for (ApiTokenEntity t : tokens.findByGrantId(grantId)) {
        if (t.getRevokedAt() == null) {
          t.revoke(now, "grant revoked");
        }
      }
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.GRANT,
          "Grant " + g.getLabel() + " (" + g.getClient() + ") for " + g.getEndpoint().getSlug(), "Active", "Revoked",
          reason, g.getEndpoint().getEnv(), g.getEndpoint().getSlug(), null, null));
      return principalRevoked(g.getEndpoint(), Caller.Kind.BRIDGE, g.getId(), g.getLabel(), reason);
    });
  }

  /** Named tokens and grants are this module's principals; OAuth grants are {@code oauth}'s. */
  @Override
  public boolean supports(Caller.Kind kind) {
    return kind == Caller.Kind.LOCAL_TOKEN || kind == Caller.Kind.BRIDGE;
  }

  /**
   * Whether a named token or a grant was revoked (or never existed): what the approval executor checks once more
   * before it runs an approved call. A token that merely expired is not revoked: the person's decision stands.
   */
  @Override
  public boolean revoked(Caller.Kind kind, String principalId) {
    return Boolean.TRUE.equals(readTx.execute(s -> switch (kind) {
      case LOCAL_TOKEN -> tokens.findById(principalId).map(t -> t.getRevokedAt() != null).orElse(true);
      case BRIDGE -> grants.findById(principalId).map(ClientGrantEntity::isRevoked).orElse(true);
      case PLAYGROUND, OAUTH -> false;
    }));
  }

  /** Inside the revoking transaction: pending requests are rejected, the rest of the core hears of it after commit. */
  private int principalRevoked(EndpointEntity e, Caller.Kind kind, String principalId, String label, String reason) {
    int rejected = 0;
    for (EndpointRemovalParticipant p : participants.orderedStream().toList()) {
      rejected += p.onPrincipalRevoked(e.getId(), e.getSlug(), principalId, label, reason).rejectedApprovals();
    }
    publisher.publishEvent(new PrincipalRevoked(kind, principalId, e.getId(), e.getSlug()));
    return rejected;
  }

  /** {@code GET /api/endpoints/{slug}/tokens}: named tokens, then grants ("issued on the fly"). */
  public List<TokenRow> list(String slug) {
    return readTx.execute(s -> {
      EndpointEntity e = endpoints.findBySlug(slug).orElseThrow(() -> EndpointService.notFound(slug));
      Instant now = clock.instant();
      List<TokenRow> out = new ArrayList<>();
      for (ApiTokenEntity t : tokens.findByEndpointIdAndKindOrderByCreatedAtDesc(e.getId(), TokenKind.NAMED)) {
        String state = t.getRevokedAt() != null ? "revoked" : t.isLive(now) ? "active" : "expired";
        Instant used = lastUsed.getOrDefault(t.getId(), t.getLastUsedAt());
        out.add(new TokenRow(t.getId(), "named", t.getName(), t.getPrefix(), null, t.getCreatedAt(),
            t.getExpiresAt(), used, t.getRevokedAt(), state));
      }
      for (ClientGrantEntity g : grants.findByEndpointId(e.getId())) {
        out.add(new TokenRow(g.getId(), "grant", g.getLabel(), null, g.getClient(), g.getCreatedAt(), null,
            g.getLastTokenAt(), g.getRevokedAt(), g.isRevoked() ? "revoked" : "active"));
      }
      return out;
    });
  }

  /**
   * The id of the named token whose value is {@code token}, if the core issued it: the clients module finds the copies
   * of a config file that still hold a revoked token, to delete them (ARCHITECTURE.md §7.2).
   */
  public Optional<String> namedTokenIdOf(String token) {
    if (token == null || !token.startsWith(NAMED_PREFIX) || token.length() > 128) {
      return Optional.empty();
    }
    String h = hash(token);
    return readTx.execute(s -> tokens.findByHash(h).filter(t -> t.getKind() == TokenKind.NAMED)
        .map(ApiTokenEntity::getId));
  }

  /** Remembers a use; written to the database by {@link #flushLastUsed}. */
  void used(String tokenId, Instant at) {
    lastUsed.put(tokenId, at);
  }

  /** Writes the remembered uses (once a minute, and when the core stops). */
  public void flushLastUsed() {
    if (lastUsed.isEmpty()) {
      return;
    }
    Map<String, Instant> batch = Map.copyOf(lastUsed);
    writeTx.executeWithoutResult(s -> batch.forEach((id, at) -> tokens.findById(id).ifPresent(t -> {
      if (t.getLastUsedAt() == null || t.getLastUsedAt().isBefore(at)) {
        t.setLastUsedAt(at);
      }
    })));
    // Only the entries written above: a use recorded while this ran stays for the next flush.
    batch.forEach((id, at) -> lastUsed.remove(id, at));
  }

  /** The configured default for a named token, also used by client connection previews. */
  public int defaultTtlDays() {
    int fallback = Math.toIntExact(DEFAULT_TTL.toDays());
    return settings.find("authorization").map(v -> v.value().path("tokenTtlDays").asInt(fallback))
        .filter(days -> days >= 1 && days <= MAX_TTL_DAYS).orElse(fallback);
  }

  /** {@code b64url(sha256(token))}: the only form a token is stored in. */
  public static String hash(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
