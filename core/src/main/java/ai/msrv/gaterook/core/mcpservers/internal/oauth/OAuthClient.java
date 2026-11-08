package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import ai.msrv.gaterook.connectors.support.BuiltIn;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GateRook as an OAuth 2.1 client of an MCP server's authorization server: a public client on the person's computer
 * with PKCE S256, the {@code resource} of the MCP server in every authorization and token request (RFC 8707), and
 * as client ID its own Client ID Metadata Document where the server accepts one, else dynamic registration (RFC 7591)
 * under its own name, or the client ID of an OAuth app the person created. It never presents itself as another
 * application: no other client's ID, name or redirect URI.
 */
public final class OAuthClient {

  /** The name the authorization server shows on its consent page. */
  public static final String CLIENT_NAME = "GateRook";

  /**
   * GateRook's Client ID Metadata Document (draft-ietf-oauth-client-id-metadata-document), published on its website:
   * the client ID where an authorization server accepts one. The authorization server fetches it and finds the name
   * GateRook, a native public client ({@code none}) and {@link #METADATA_DOCUMENT_REDIRECTS}.
   */
  public static final String METADATA_DOCUMENT = "https://gaterook.com/oauth/gaterook-client.json";

  /**
   * The redirect URIs the document lists: loopback without a port, which authorization servers match with any port
   * (RFC 8252 §7.3), so each sign-in's listener can take a free one.
   */
  static final List<String> METADATA_DOCUMENT_REDIRECTS = List.of("http://localhost/callback",
      "http://127.0.0.1/callback");

  /**
   * The client GateRook signs in as.
   *
   * @param authMethod  how it authenticates at the token endpoint: {@code none}, {@code client_secret_basic} or
   *                    {@code client_secret_post}
   * @param registered  GateRook registered it itself (dynamic registration), rather than the person's OAuth app or
   *                    its metadata document
   * @param redirectUri the redirect URI it was registered with (or the one the person's app accepts, or the sign-in's
   *                    own with the metadata document)
   */
  public record Client(String id, String secret, String authMethod, boolean registered, String redirectUri) {

    @Override
    public String toString() {
      return "Client[id=" + id + ", secret=" + (secret == null ? "none" : "‹redacted›") + ", authMethod=" + authMethod
          + ", registered=" + registered + ", redirectUri=" + redirectUri + "]";
    }
  }

  /** What a token endpoint issued. {@code expiresAt} is {@code null} when it didn't say. */
  public record Tokens(String accessToken, Instant expiresAt, String refreshToken, String scope) {

    @Override
    public String toString() {
      return "Tokens[‹redacted›, expiresAt=" + expiresAt + ", refresh=" + (refreshToken != null) + ", scope=" + scope
          + "]";
    }
  }

  private OAuthClient() {}

  /**
   * Whether {@code server} takes GateRook's metadata document as client with {@code redirectUri}: it accepts an HTTPS
   * URL as client ID, it accepts public clients at its token endpoint (where it lists no methods, RFC 8414 means
   * {@code client_secret_basic} only), and the document lists the redirect URI.
   */
  public static boolean acceptsMetadataDocument(Discovery.Server server, URI redirectUri) {
    return server.clientMetadataDocuments() && server.authMethods().contains("none")
        && METADATA_DOCUMENT_REDIRECTS.contains(withoutPort(redirectUri));
  }

  /** GateRook by its metadata document: the document's URL as client ID, no secret, nothing registered. */
  public static Client metadataDocument(URI redirectUri) {
    return new Client(METADATA_DOCUMENT, null, "none", false, redirectUri.toString());
  }

  /**
   * Registers GateRook with {@code redirectUri} at the server's registration endpoint, as a public client that uses
   * the authorization code and refresh token grants.
   *
   * @throws OAuthHttp.Failure with the server's {@code error} ({@code invalid_redirect_uri}) when it refuses
   */
  public static Client register(OAuthHttp http, Discovery.Server server, URI redirectUri) throws OAuthHttp.Failure {
    ObjectNode body = OAuthHttp.JSON.createObjectNode();
    body.put("client_name", CLIENT_NAME);
    body.put("client_uri", "https://gaterook.com/");
    ArrayNode redirects = body.putArray("redirect_uris");
    redirects.add(redirectUri.toString());
    ArrayNode grants = body.putArray("grant_types");
    grants.add("authorization_code");
    grants.add("refresh_token");
    body.putArray("response_types").add("code");
    // A public client where the server takes one; otherwise the secret it issues (some only register confidential
    // clients), kept like the tokens.
    List<String> offered = server.authMethods();
    body.put("token_endpoint_auth_method", offered.isEmpty() || offered.contains("none") ? "none"
        : offered.contains("client_secret_basic") ? "client_secret_basic" : offered.contains("client_secret_post")
            ? "client_secret_post" : "none");
    body.put("application_type", "native");
    body.put("software_id", "ai.msrv.gaterook");
    body.put("software_version", BuiltIn.VERSION);
    OAuthHttp.Answer a = http.postJson(server.registration(), body, Map.of("Accept", "application/json"));
    JsonNode j = a.ok() ? a.json() : null;
    if (a.status() == 401 || a.status() == 403) {
      // Registration that only approved apps pass (by name, by redirect URI, or with an access token).
      throw new OAuthHttp.Failure(OAuthHttp.host(server.registration()) + " registers only the apps it approved "
          + "(HTTP " + a.status() + "), so GateRook can't register itself there. Create an OAuth app for GateRook "
          + "in its developer settings if it offers that and enter its client ID, or connect with an API key in a "
          + "header.", "access_denied", null);
    }
    if (j == null || !j.path("client_id").isString() || j.path("client_id").asString().isBlank()) {
      throw a.ok() ? new OAuthHttp.Failure("Registering GateRook: " + OAuthHttp.host(server.registration())
          + " answered without a client ID.") : OAuthHttp.failure("Registering GateRook", server.registration(), a);
    }
    String secret = j.path("client_secret").isString() && !j.path("client_secret").asString().isBlank()
        ? j.path("client_secret").asString() : null;
    String method = j.path("token_endpoint_auth_method").asString(secret == null ? "none" : "client_secret_basic");
    return new Client(j.path("client_id").asString(), secret, normalizeMethod(method, secret), true,
        redirectUri.toString());
  }

  /** The client of an OAuth app the person created: its ID, secret (or none) and the redirect URI it accepts. */
  public static Client preRegistered(Discovery.Server server, String id, String secret, URI redirectUri) {
    String method = "none";
    if (secret != null) {
      List<String> offered = server.authMethods();
      method = offered.isEmpty() || offered.contains("client_secret_basic") || !offered.contains("client_secret_post")
          ? "client_secret_basic" : "client_secret_post";
    }
    return new Client(id, secret, method, false, redirectUri.toString());
  }

  /** The URL the browser opens: the authorization request with PKCE, {@code state} and the resource. */
  public static URI authorizationUrl(Discovery.Server server, Client client, Pkce pkce, String state, String scope,
      String resource) {
    HttpUrl.Builder b = HttpUrl.get(server.authorization().toString()).newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", client.id())
        .addQueryParameter("redirect_uri", client.redirectUri())
        .addQueryParameter("code_challenge", pkce.challenge())
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", state);
    if (scope != null && !scope.isBlank()) {
      b.addQueryParameter("scope", scope);
    }
    if (resource != null) {
      b.addQueryParameter("resource", resource);
    }
    return b.build().uri();
  }

  /** Redeems the authorization code (RFC 6749 §4.1.3 with the PKCE verifier and the resource). */
  public static Tokens exchange(OAuthHttp http, URI tokenEndpoint, Client client, String code, String verifier,
      String resource, Instant now) throws OAuthHttp.Failure {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", client.redirectUri());
    form.put("code_verifier", verifier);
    form.put("resource", resource);
    return tokens("Signing in", http, tokenEndpoint, client, form, null, now);
  }

  /**
   * Renews the access token (RFC 6749 §6). A server that rotates refresh tokens sends a new one; one that doesn't
   * keeps the old one valid, which is kept then.
   */
  public static Tokens refresh(OAuthHttp http, URI tokenEndpoint, Client client, String refreshToken,
      String resource, Instant now) throws OAuthHttp.Failure {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refreshToken);
    form.put("resource", resource);
    return tokens("Renewing the sign-in", http, tokenEndpoint, client, form, refreshToken, now);
  }

  /** Revokes {@code token} (RFC 7009); best effort, failures are ignored. */
  public static void revoke(OAuthHttp http, URI revocation, Client client, String token, String hint) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("token", token);
    form.put("token_type_hint", hint);
    try {
      post(http, revocation, client, form);
    } catch (OAuthHttp.Failure e) {
      // The token stays valid until it expires; GateRook forgets it either way.
    }
  }

  private static Tokens tokens(String step, OAuthHttp http, URI endpoint, Client client, Map<String, String> form,
      String previousRefresh, Instant now) throws OAuthHttp.Failure {
    OAuthHttp.Answer a = post(http, endpoint, client, form);
    JsonNode j = a.json();
    if (!a.ok() || j == null || !j.isObject()) {
      throw OAuthHttp.failure(step, endpoint, a);
    }
    if (!j.path("access_token").isString() || j.path("access_token").asString().isBlank()) {
      throw new OAuthHttp.Failure(step + ": " + OAuthHttp.host(endpoint) + " answered without an access token.");
    }
    String type = j.path("token_type").asString("Bearer");
    if (!type.equalsIgnoreCase("bearer")) {
      throw new OAuthHttp.Failure(step + ": " + OAuthHttp.host(endpoint) + " issued a " + OAuthHttp.clean(type)
          + " token; GateRook sends Bearer tokens only.");
    }
    Instant expires = null;
    JsonNode in = j.path("expires_in");
    long seconds = in.isNumber() ? in.asLong() : in.isString() ? parseLong(in.asString()) : -1;
    if (seconds > 0) {
      expires = now.plusSeconds(Math.min(seconds, 10L * 365 * 24 * 3600));
    }
    String refresh = j.path("refresh_token").isString() && !j.path("refresh_token").asString().isBlank()
        ? j.path("refresh_token").asString() : previousRefresh;
    return new Tokens(j.path("access_token").asString(), expires, refresh, j.path("scope").asString(null));
  }

  private static OAuthHttp.Answer post(OAuthHttp http, URI endpoint, Client client, Map<String, String> form)
      throws OAuthHttp.Failure {
    Map<String, String> body = new LinkedHashMap<>(form);
    String[] basic = null;
    switch (client.authMethod()) {
      case "client_secret_basic" -> basic = new String[] {client.id(), client.secret()};
      case "client_secret_post" -> {
        body.put("client_id", client.id());
        body.put("client_secret", client.secret());
      }
      default -> body.put("client_id", client.id());
    }
    return http.postForm(endpoint, body, basic);
  }

  private static String normalizeMethod(String method, String secret) {
    String m = method == null ? "" : method.toLowerCase(Locale.ROOT);
    if (secret == null) {
      return "none";
    }
    return m.equals("client_secret_post") ? "client_secret_post" : "client_secret_basic";
  }

  private static String withoutPort(URI u) {
    return u.getScheme() + "://" + u.getHost() + (u.getRawPath() == null ? "" : u.getRawPath())
        + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
  }

  private static long parseLong(String s) {
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
