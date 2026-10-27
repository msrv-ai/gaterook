package ai.msrv.gaterook.core.oauth.internal;

import ai.msrv.gaterook.core.platform.Ingress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorization codes ({@code gwa_…}, report 02 §6.4): in memory by hash, valid 60 s, once. Each is bound to the
 * client, the redirect URI, the resource, the PKCE challenge and the server (ingress) of its authorization request.
 * A code presented again within its life revokes the grant it made (OAuth 2.1 §4.1.3).
 */
final class AuthorizationCodes {

  static final Duration TTL = Duration.ofSeconds(60);

  /** What a code was issued for. */
  record Code(String grantId, String clientId, String redirectUri, String resource, String challenge,
      Ingress ingress, Instant expiresAt) {}

  /** The outcome of presenting a code. */
  record Redeemed(Code code, boolean replayed) {}

  private static final class Entry {
    final Code code;
    boolean used;

    Entry(Code code) {
      this.code = code;
    }
  }

  private final Map<String, Entry> codes = new ConcurrentHashMap<>();
  private final Clock clock;

  AuthorizationCodes(Clock clock) {
    this.clock = clock;
  }

  /** A new code for {@code grantId}; the value goes to the browser's redirect only. */
  String issue(String grantId, String clientId, String redirectUri, String resource, String challenge,
      Ingress ingress) {
    Instant now = clock.instant();
    codes.entrySet().removeIf(e -> e.getValue().code.expiresAt().isBefore(now));
    String value = OAuthTokens.random(OAuthTokens.CODE);
    codes.put(OAuthTokens.hash(value), new Entry(new Code(grantId, clientId, redirectUri, resource, challenge,
        ingress, now.plus(TTL))));
    return value;
  }

  /** The code's binding, the first time within 60 s; a second presentation says {@code replayed}. */
  Optional<Redeemed> redeem(String value) {
    if (value == null || !value.startsWith(OAuthTokens.CODE) || value.length() > 128) {
      return Optional.empty();
    }
    Entry e = codes.get(OAuthTokens.hash(value));
    if (e == null || !e.code.expiresAt().isAfter(clock.instant())) {
      return Optional.empty();
    }
    synchronized (e) {
      boolean replayed = e.used;
      e.used = true;
      return Optional.of(new Redeemed(e.code, replayed));
    }
  }
}
