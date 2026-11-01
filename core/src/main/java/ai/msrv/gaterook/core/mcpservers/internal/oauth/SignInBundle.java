package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything of one MCP server's OAuth sign-in that a renewal needs, stored as one encrypted secret of the server
 * ({@code secret.owner_kind = 'mcp-server'}, field {@code /oauth}): where to renew (the token endpoint the sign-in
 * found, not one a database edit could put there — the secret's authenticated encryption covers it), as which client,
 * for which resource, and the tokens. JSON of this class's making, read without reflection.
 *
 * @param scope       the scopes asked for at the sign-in ({@code null}: the server's default)
 * @param needsSignIn why the person has to sign in again (the renewal was refused), or {@code null}
 */
public record SignInBundle(String serverUrl, String resource, String issuer, URI tokenEndpoint, URI revocation,
    OAuthClient.Client client, String scope, String accessToken, Instant expiresAt, String refreshToken,
    String grantedScope, Instant signedInAt, Instant renewedAt, String needsSignIn) {

  /** The layout of {@link #toJson}. */
  static final int FORMAT = 1;

  /** A new sign-in: the tokens of the code exchange. */
  public static SignInBundle signedIn(String serverUrl, String resource, Discovery.Server server,
      OAuthClient.Client client, String scope, OAuthClient.Tokens tokens, Instant now) {
    return new SignInBundle(serverUrl, resource, server.issuer(), server.token(), server.revocation(), client, scope,
        tokens.accessToken(), tokens.expiresAt(), tokens.refreshToken(), tokens.scope(), now, now, null);
  }

  /** The same sign-in with renewed tokens. */
  public SignInBundle renewed(OAuthClient.Tokens tokens, Instant now) {
    return new SignInBundle(serverUrl, resource, issuer, tokenEndpoint, revocation, client, scope,
        tokens.accessToken(), tokens.expiresAt(), tokens.refreshToken() != null ? tokens.refreshToken() : refreshToken,
        tokens.scope() != null ? tokens.scope() : grantedScope, signedInAt, now, null);
  }

  /** The sign-in can't be renewed: the tokens are dropped, the reason stays until the person signs in again. */
  public SignInBundle needing(String why) {
    return new SignInBundle(serverUrl, resource, issuer, tokenEndpoint, revocation, client, scope, null, null, null,
        grantedScope, signedInAt, renewedAt, why);
  }

  /** {@code Bearer <access token>}, or {@code null} without one. */
  public String header() {
    return accessToken == null ? null : "Bearer " + accessToken;
  }

  /** The access token expires within {@code margin} (a token without an expiry never does). */
  public boolean expiresWithin(Duration margin, Instant now) {
    return expiresAt != null && !now.plus(margin).isBefore(expiresAt);
  }

  public boolean renewable() {
    return refreshToken != null;
  }

  public char[] toJson() {
    ObjectNode o = OAuthHttp.JSON.createObjectNode();
    o.put("format", FORMAT);
    o.put("serverUrl", serverUrl);
    o.put("resource", resource);
    o.put("issuer", issuer);
    o.put("tokenEndpoint", tokenEndpoint.toString());
    if (revocation != null) {
      o.put("revocation", revocation.toString());
    }
    ObjectNode c = o.putObject("client");
    c.put("id", client.id());
    if (client.secret() != null) {
      c.put("secret", client.secret());
    }
    c.put("authMethod", client.authMethod());
    c.put("registered", client.registered());
    c.put("redirectUri", client.redirectUri());
    put(o, "scope", scope);
    put(o, "accessToken", accessToken);
    put(o, "expiresAt", expiresAt);
    put(o, "refreshToken", refreshToken);
    put(o, "grantedScope", grantedScope);
    put(o, "signedInAt", signedInAt);
    put(o, "renewedAt", renewedAt);
    put(o, "needsSignIn", needsSignIn);
    return OAuthHttp.JSON.writeValueAsString(o).toCharArray();
  }

  /**
   * The bundle in {@code json}.
   *
   * @throws IllegalArgumentException when it isn't one this version wrote
   */
  public static SignInBundle fromJson(String json) {
    JsonNode o = OAuthHttp.JSON.readTree(json);
    if (o == null || !o.isObject() || o.path("format").asInt(0) != FORMAT) {
      throw new IllegalArgumentException("Not a sign-in this version of GateRook wrote");
    }
    JsonNode c = o.path("client");
    OAuthClient.Client client = new OAuthClient.Client(c.path("id").asString(), c.path("secret").asString(null),
        c.path("authMethod").asString("none"), c.path("registered").asBoolean(false),
        c.path("redirectUri").asString(null));
    return new SignInBundle(o.path("serverUrl").asString(), o.path("resource").asString(null),
        o.path("issuer").asString(null), URI.create(o.path("tokenEndpoint").asString()),
        o.path("revocation").isString() ? URI.create(o.path("revocation").asString()) : null, client,
        o.path("scope").asString(null), o.path("accessToken").asString(null), instant(o.path("expiresAt")),
        o.path("refreshToken").asString(null), o.path("grantedScope").asString(null), instant(o.path("signedInAt")),
        instant(o.path("renewedAt")), o.path("needsSignIn").asString(null));
  }

  private static void put(ObjectNode o, String key, String value) {
    if (value != null) {
      o.put(key, value);
    }
  }

  private static void put(ObjectNode o, String key, Instant value) {
    if (value != null) {
      o.put(key, value.toString());
    }
  }

  private static Instant instant(JsonNode n) {
    return n.isString() ? Instant.parse(n.asString()) : null;
  }

  @Override
  public String toString() {
    return "SignInBundle[issuer=" + issuer + ", resource=" + resource + ", client=" + client + ", expiresAt="
        + expiresAt + ", renewable=" + renewable() + ", needsSignIn=" + needsSignIn + "]";
  }
}
