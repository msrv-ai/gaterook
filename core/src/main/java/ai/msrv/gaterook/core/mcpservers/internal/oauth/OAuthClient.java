package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import ai.msrv.gaterook.connectors.support.BuiltIn;
import java.net.URI;
import java.time.Duration;
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
 * under its own name, else its own app registered at that server ({@link GateRookApps}, with the device authorization
 * grant of RFC 8628), or the client ID of an OAuth app the person created. It never presents itself as another
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

  /**
   * What a device authorization request issued (RFC 8628 §3.2).
   *
   * @param userCode                the code the person enters on the provider's page
   * @param verificationUri         that page
   * @param verificationUriComplete that page with the code in it, where the provider offers one; else {@code null}
   * @param interval                how long to wait between two polls of the token endpoint
   */
  public record DeviceCode(String deviceCode, String userCode, URI verificationUri, URI verificationUriComplete,
      Instant expiresAt, Duration interval) {

    @Override
    public String toString() {
      return "DeviceCode[‹redacted›, userCode=" + userCode + ", verificationUri=" + verificationUri + ", expiresAt="
          + expiresAt + ", interval=" + interval + "]";
    }
  }

  /**
   * One poll of the token endpoint for a device code: still waiting, with the interval to keep from now on, or the
   * tokens.
   */
  public record DevicePoll(Tokens tokens, Duration interval) {

    public boolean pending() {
      return tokens == null;
    }
  }

  /** Longest wait between two polls a server may ask for, and the longest life of a device code GateRook keeps. */
  static final Duration MAX_INTERVAL = Duration.ofSeconds(60);
  static final Duration MAX_DEVICE_LIFE = Duration.ofMinutes(30);

  /**
   * The shortest token GateRook takes: 16 characters, about 96 bits from the alphabet of a Bearer token (RFC 6819
   * §5.1.4.2.2 asks for 128). Every token GateRook is handed is masked wherever it shows up, in every connection's
   * results: an authorization server that issued an ordinary word as its token would have it masked everywhere.
   */
  public static final int MIN_TOKEN_LENGTH = 16;
  /** The longest: a token travels in a header, which servers cap at a few kilobytes; large JWTs stay below this. */
  public static final int MAX_TOKEN_LENGTH = 16_384;

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

  /**
   * GateRook's own app at an authorization server ({@link GateRookApps}): its public client ID, no secret, and no
   * redirect URI, because the device authorization grant has no redirect.
   */
  public static Client ownApp(String clientId) {
    return new Client(clientId, null, "none", false, null);
  }

  /**
   * Asks the server's device authorization endpoint for a code (RFC 8628 §3.1), with the scopes and the resource
   * (RFC 8707). The page the person enters the code on must be HTTPS on the authorization server's own host, or
   * HTTP on this computer, so that the answer can't send the person to another site.
   */
  public static DeviceCode deviceAuthorization(OAuthHttp http, Discovery.Server server, Client client, String scope,
      String resource, Instant now) throws OAuthHttp.Failure {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("scope", scope == null || scope.isBlank() ? null : scope);
    form.put("resource", resource);
    URI endpoint = server.deviceAuthorization();
    OAuthHttp.Answer a = post(http, endpoint, client, form);
    JsonNode j = a.json();
    if (!a.ok() || j == null || !j.isObject() || j.path("error").isString()) {
      throw OAuthHttp.failure("Starting the sign-in", endpoint, a);
    }
    String deviceCode = text(j, "device_code");
    String userCode = text(j, "user_code");
    // Google's older answers say verification_url.
    String page = text(j, "verification_uri") != null ? text(j, "verification_uri") : text(j, "verification_url");
    if (deviceCode == null || userCode == null || page == null) {
      throw new OAuthHttp.Failure("Starting the sign-in: " + OAuthHttp.host(endpoint) + " answered without a code "
          + "and the page to enter it on.");
    }
    if (userCode.length() > 64 || !userCode.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
      throw new OAuthHttp.Failure("Starting the sign-in: " + OAuthHttp.host(endpoint) + " answered with a code "
          + "GateRook can't show.");
    }
    URI verification = serversPage(OAuthHttp.checked(page, "The page to enter the code on"), server);
    URI complete = null;
    if (text(j, "verification_uri_complete") != null) {
      try {
        complete = serversPage(OAuthHttp.checked(text(j, "verification_uri_complete"), "The page with the code"),
            server);
      } catch (OAuthHttp.Failure e) {
        // The page without the code works too: the person types it.
        complete = null;
      }
    }
    long life = seconds(j.path("expires_in"), 900);
    long interval = seconds(j.path("interval"), 5);
    return new DeviceCode(deviceCode, userCode, verification, complete,
        now.plusSeconds(Math.clamp(life, 60, MAX_DEVICE_LIFE.toSeconds())),
        Duration.ofSeconds(Math.clamp(interval, 1, MAX_INTERVAL.toSeconds())));
  }

  /**
   * Polls the token endpoint for the tokens of {@code code} (RFC 8628 §3.4, §3.5): pending while the person hasn't
   * finished on the provider's page, at a slower pace after {@code slow_down}; the tokens once they allowed access.
   * GitHub answers these errors with HTTP 200, the RFC with 400: both are read.
   *
   * @throws OAuthHttp.Failure with {@code access_denied} when the person didn't allow access, {@code expired_token} when
   *                           the code expired, or another error after which the sign-in can't go on (no {@code error}
   *                           when the server couldn't be reached)
   */
  public static DevicePoll pollDevice(OAuthHttp http, URI tokenEndpoint, Client client, DeviceCode code,
      Duration interval, String resource, Instant now) throws OAuthHttp.Failure {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", Discovery.DEVICE_GRANT);
    form.put("device_code", code.deviceCode());
    form.put("resource", resource);
    OAuthHttp.Answer a = post(http, tokenEndpoint, client, form);
    JsonNode j = a.json();
    String error = j != null && j.path("error").isString() ? j.path("error").asString() : null;
    if (error == null) {
      return new DevicePoll(read("Signing in", tokenEndpoint, a, null, now), interval);
    }
    return switch (error) {
      case "authorization_pending" -> new DevicePoll(null, interval);
      case "slow_down" -> {
        // RFC 8628 §3.5: five seconds more for this and every later poll; GitHub also names the new interval.
        long named = seconds(j.path("interval"), 0);
        long next = Math.max(interval.toSeconds() + 5, named);
        yield new DevicePoll(null, Duration.ofSeconds(Math.min(next, MAX_INTERVAL.toSeconds())));
      }
      case "access_denied" -> throw new OAuthHttp.Failure("Access wasn't allowed on " + OAuthHttp.host(code
          .verificationUri()) + ", so the sign-in ended.", error, null);
      case "expired_token", "token_expired" -> throw new OAuthHttp.Failure("The code expired before it was entered. "
          + "Start the sign-in again.", "expired_token", null);
      default -> throw OAuthHttp.failure("Signing in", tokenEndpoint, a);
    };
  }

  /** The page the device authorization answer names, when it is on the authorization server's host. */
  private static URI serversPage(URI page, Discovery.Server server) throws OAuthHttp.Failure {
    String issuerHost = URI.create(server.issuer()).getHost();
    String authorizationHost = server.authorization().getHost();
    if (page.getHost() == null || !(page.getHost().equalsIgnoreCase(issuerHost)
        || page.getHost().equalsIgnoreCase(authorizationHost))) {
      throw new OAuthHttp.Failure("Starting the sign-in: " + OAuthHttp.host(server.deviceAuthorization())
          + " named a page on another host (" + OAuthHttp.clean(page.getHost()) + ") to enter the code on, so GateRook "
          + "doesn't open it.");
    }
    return page;
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
    return read(step, endpoint, post(http, endpoint, client, form), previousRefresh, now);
  }

  /**
   * The tokens of a token endpoint's answer. An answer with an {@code error} is a refusal even with HTTP 200, as GitHub
   * sends them ({@code bad_refresh_token}): its code is kept, so a refused renewal asks for a new sign-in.
   */
  private static Tokens read(String step, URI endpoint, OAuthHttp.Answer a, String previousRefresh, Instant now)
      throws OAuthHttp.Failure {
    JsonNode j = a.json();
    if (!a.ok() || j == null || !j.isObject() || j.path("error").isString()) {
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
    String access = j.path("access_token").asString();
    String issuedRefresh = j.path("refresh_token").isString() && !j.path("refresh_token").asString().isBlank()
        ? j.path("refresh_token").asString() : null;
    if (!credentialShaped(access) || (issuedRefresh != null && !credentialShaped(issuedRefresh))) {
      // The value isn't named: a token is a secret even when it is malformed.
      throw new OAuthHttp.Failure(step + ": " + OAuthHttp.host(endpoint) + " issued " + (credentialShaped(access)
          ? "a refresh token" : "an access token") + " that isn't shaped like one (" + MIN_TOKEN_LENGTH + " to "
          + MAX_TOKEN_LENGTH + " visible ASCII characters, no spaces), so GateRook doesn't use it.");
    }
    String refresh = issuedRefresh != null ? issuedRefresh : previousRefresh;
    return new Tokens(access, expires, refresh, j.path("scope").asString(null));
  }

  /**
   * Whether {@code token} has the shape of a credential: {@value #MIN_TOKEN_LENGTH} to {@value #MAX_TOKEN_LENGTH}
   * visible ASCII characters (RFC 6749's {@code VSCHAR} without the space), as Bearer tokens, JWTs and the opaque
   * tokens of common authorization servers are.
   */
  public static boolean credentialShaped(String token) {
    if (token == null || token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH) {
      return false;
    }
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c <= 0x20 || c >= 0x7f) {
        return false;
      }
    }
    return true;
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

  /** A whole number of seconds, as a number or a string, or {@code fallback} when it is neither or not positive. */
  private static long seconds(JsonNode n, long fallback) {
    long v = n.isNumber() ? n.asLong() : n.isString() ? parseLong(n.asString()) : -1;
    return v > 0 ? v : fallback;
  }

  /** A non-blank string member, or {@code null}. */
  private static String text(JsonNode j, String name) {
    JsonNode n = j.path(name);
    return n.isString() && !n.asString().isBlank() ? n.asString() : null;
  }
}
