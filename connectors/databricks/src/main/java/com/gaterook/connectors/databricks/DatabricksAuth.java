package com.gaterook.connectors.databricks;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.SecretResolver;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The bearer token of a call: the connection's personal access token, or an OAuth machine-to-machine token of its
 * service principal from the workspace's own token endpoint ({@code <workspace>/oidc/v1/token}, client credentials,
 * scope {@code all-apis}), kept in memory until shortly before it expires. A token is the connection's: the cache key
 * holds the connection, its config version and the version of the client secret, so a changed secret or setting gets
 * a new one. Every call hands its token to the call's taint, also one it found in the cache.
 */
final class DatabricksAuth {

  static final String TOKEN_FIELD = "/auth/token";
  static final String SECRET_FIELD = "/auth/clientSecret";
  /** A token is renewed this long before it expires. */
  static final Duration RENEW_BEFORE = Duration.ofSeconds(60);
  /** What a bearer token may hold: visible ASCII, no spaces, a bounded length. */
  private static final Pattern BEARER = Pattern.compile("[\\x21-\\x7E]{8,4096}");

  private final DatabricksApi api;
  private final Clock clock;
  private final Map<String, Token> cache = new ConcurrentHashMap<>();

  DatabricksAuth(DatabricksApi api, Clock clock) {
    this.api = api;
    this.clock = clock;
  }

  private record Token(String value, Instant expires) {}

  /** The bearer token for {@code call}, tainted. */
  String token(DatabricksApi.Call call) throws ConnectorException {
    DatabricksSettings s = call.settings();
    if (s.auth() == DatabricksSettings.Auth.TOKEN) {
      try (SecretResolver.Secret secret = call.secrets().secret(TOKEN_FIELD)) {
        String token = secret.unsafeString().strip();
        call.ctx().taint(token);
        if (!BEARER.matcher(token).matches()) {
          throw ConnectorException.badInput("The personal access token of " + call.cfg().name() + " has characters "
              + "an HTTP header can't carry. Paste it again.");
        }
        return token;
      }
    }
    String key = call.cfg().id() + "|" + call.cfg().version() + "|" + s.clientId() + "|"
        + call.secrets().meta(SECRET_FIELD).version();
    Token cached = cache.get(key);
    if (cached != null && cached.expires().isAfter(clock.instant().plus(RENEW_BEFORE))) {
      call.ctx().taint(cached.value());
      return cached.value();
    }
    Token fresh = fetch(call);
    cache.put(key, fresh);
    return fresh.value();
  }

  /** The service principal's client credentials for a token of the workspace. */
  private Token fetch(DatabricksApi.Call call) throws ConnectorException {
    DatabricksSettings s = call.settings();
    String basic;
    try (SecretResolver.Secret secret = call.secrets().secret(SECRET_FIELD)) {
      String clientSecret = secret.unsafeString().strip();
      call.ctx().taint(clientSecret);
      String pair = s.clientId() + ":" + clientSecret;
      basic = Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
      call.ctx().taint(basic);
    }
    URI uri = s.base().resolve("oidc/v1/token");
    JsonNode r = api.signIn(call, uri, "Basic " + basic, "grant_type=client_credentials&scope=all-apis");
    String token = r.path("access_token").asString("");
    if (!BEARER.matcher(token).matches()) {
      throw ConnectorException.unavailable("Databricks answered the sign-in of the service principal without a "
          + "usable token. Test the connection again.", null);
    }
    call.ctx().taint(token);
    long seconds = Math.max(60, Math.min(r.path("expires_in").asLong(3_600), 86_400));
    return new Token(token, clock.instant().plusSeconds(seconds));
  }

  /** Forgets the token of a connection Databricks refused it for, so the next call signs in again. */
  void refused(DatabricksApi.Call call) {
    String prefix = call.cfg().id() + "|";
    cache.keySet().removeIf(k -> k.startsWith(prefix));
  }

  /** Whether a token of {@code connectionId} is kept: the setup helpers drop only the ones they got themselves. */
  boolean has(String connectionId) {
    String prefix = connectionId + "|";
    return cache.keySet().stream().anyMatch(k -> k.startsWith(prefix));
  }

  /** Drops every token of {@code connectionId}: its config, secret or policy changed, or it was removed. */
  void evict(String connectionId) {
    String prefix = connectionId + "|";
    cache.keySet().removeIf(k -> k.startsWith(prefix));
  }
}
