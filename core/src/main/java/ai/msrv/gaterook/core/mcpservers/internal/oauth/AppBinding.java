package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where the client secret of the person's own OAuth app may go: the authorization server it was entered for or signed
 * in with first, by its issuer and the token endpoint the secret is sent to. A sign-in whose discovery names another
 * one doesn't send the secret: an MCP server names its authorization server itself, and could otherwise collect the
 * secret of an app the person registered elsewhere. The binding holds for one client: the app's client ID and one
 * version of the server's stored client secret ({@code secret}: the secret's id and version), so that entering the
 * client secret again, with the same value or another one, is how the person trusts a new authorization server.
 *
 * <p>Kept as an encrypted record of the server ({@code secret.owner_kind = 'mcp-server'}, field {@code /oauth-app})
 * that signing out keeps and removing the server deletes. JSON of this class's making, read without reflection.
 *
 * @param clientId the app's client ID ({@code auth.clientId})
 * @param secret   which stored client secret: {@code <secret id>@<version>}
 * @param at       when the binding was made
 */
public record AppBinding(String clientId, String secret, String issuer, URI tokenEndpoint, Instant at) {

  /** The layout of {@link #toJson}. */
  static final int FORMAT = 1;

  public AppBinding {
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(secret, "secret");
    Objects.requireNonNull(issuer, "issuer");
    Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
  }

  /**
   * An authorization server as a review showed it, which a sign-in of a server not yet added is held to.
   *
   * @param tokenEndpoint where the client secret goes
   */
  public record Server(String issuer, String tokenEndpoint) {

    /** Whether discovery found the same authorization server: the same issuer and the same token endpoint. */
    public boolean matches(Discovery.Server server) {
      return issuer != null && tokenEndpoint != null && sameIssuer(issuer, server.issuer())
          && sameEndpoint(tokenEndpoint, server.token());
    }
  }

  /** The binding of a client to the authorization server a sign-in just used. */
  public static AppBinding of(String clientId, String secret, String issuer, URI tokenEndpoint, Instant now) {
    return new AppBinding(clientId, secret, issuer, tokenEndpoint, now);
  }

  /**
   * The binding a sign-in of an earlier version of GateRook left implicit: its bundle, when it was made with this app's
   * client ID and the client secret it holds now ({@code secretValue}), binds that secret ({@code secret}) to the
   * authorization server it signed in with. {@code null} when the bundle is of another client.
   */
  public static AppBinding fromBundle(SignInBundle b, String clientId, String secretValue, String secret,
      Instant now) {
    if (b == null || b.client() == null || b.issuer() == null || b.tokenEndpoint() == null || secretValue == null) {
      return null;
    }
    OAuthClient.Client c = b.client();
    if (c.registered() || c.secret() == null || !c.id().equals(clientId)
        || !MessageDigest.isEqual(c.secret().getBytes(StandardCharsets.UTF_8),
            secretValue.getBytes(StandardCharsets.UTF_8))) {
      return null;
    }
    return new AppBinding(clientId, secret, b.issuer(), b.tokenEndpoint(), now);
  }

  /** Whether this binding speaks for the client {@code clientId} with the stored client secret {@code secret}. */
  public boolean covers(String clientId, String secret) {
    return this.clientId.equals(clientId) && this.secret.equals(secret);
  }

  /** Whether a sign-in may send the client secret to {@code server}: the same issuer and the same token endpoint. */
  public boolean allows(Discovery.Server server) {
    return sameIssuer(issuer, server.issuer()) && sameEndpoint(tokenEndpoint.toString(), server.token());
  }

  static boolean sameIssuer(String a, String b) {
    return a != null && b != null && Discovery.trimSlash(a).equals(Discovery.trimSlash(b));
  }

  static boolean sameEndpoint(String a, URI b) {
    if (a == null || b == null) {
      return false;
    }
    try {
      return Discovery.canonical(URI.create(a)).equals(Discovery.canonical(b));
    } catch (RuntimeException e) {
      return false;
    }
  }

  public char[] toJson() {
    ObjectNode o = OAuthHttp.JSON.createObjectNode();
    o.put("format", FORMAT);
    o.put("clientId", clientId);
    o.put("secret", secret);
    o.put("issuer", issuer);
    o.put("tokenEndpoint", tokenEndpoint.toString());
    if (at != null) {
      o.put("at", at.toString());
    }
    return OAuthHttp.JSON.writeValueAsString(o).toCharArray();
  }

  /**
   * The binding in {@code json}.
   *
   * @throws IllegalArgumentException when it isn't one this version wrote
   */
  public static AppBinding fromJson(String json) {
    JsonNode o = OAuthHttp.JSON.readTree(json);
    if (o == null || !o.isObject() || o.path("format").asInt(0) != FORMAT || !o.path("clientId").isString()
        || !o.path("secret").isString() || !o.path("issuer").isString() || !o.path("tokenEndpoint").isString()) {
      throw new IllegalArgumentException("Not an app binding this version of GateRook wrote");
    }
    return new AppBinding(o.path("clientId").asString(), o.path("secret").asString(), o.path("issuer").asString(),
        URI.create(o.path("tokenEndpoint").asString()),
        o.path("at").isString() ? Instant.parse(o.path("at").asString()) : null);
  }
}
