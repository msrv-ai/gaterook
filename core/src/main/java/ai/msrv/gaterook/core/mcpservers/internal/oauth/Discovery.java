package ai.msrv.gaterook.core.mcpservers.internal.oauth;

import ai.msrv.gaterook.connectors.support.BuiltIn;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * How an MCP server over HTTP wants clients to sign in (MCP authorization, 2025-06-18 and later): an unauthenticated
 * {@code initialize} that the server refuses with 401 and a {@code WWW-Authenticate} challenge, the protected-resource
 * metadata it names or the well-known one (RFC 9728), and the metadata of its authorization server (RFC 8414, OpenID
 * Connect Discovery as the fallback, with the issuer's path inserted and appended). A server of the 2025-03-26
 * revision without resource metadata is its own authorization server, with that revision's default endpoints when
 * it publishes no metadata either.
 */
public final class Discovery {

  /** The grant type of the device authorization grant (RFC 8628 §3.4). */
  public static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

  /** What the unauthenticated look at the server found. */
  public enum Kind {
    /** It answered {@code initialize} without a sign-in. */
    NONE,
    /** It answered without a sign-in, and offers one too (more access when signed in). */
    OPTIONAL,
    /** It wants an OAuth sign-in, and the metadata says where. */
    OAUTH,
    /** It refused (401/403) without saying how to sign in: a key in a header, probably. */
    REFUSED,
    /** It didn't answer as an MCP server over Streamable HTTP, or couldn't be reached. */
    UNKNOWN
  }

  /**
   * The protected-resource metadata of an MCP server.
   *
   * @param resource             its resource identifier, the {@code resource} of every authorization request
   * @param authorizationServers the issuers it trusts, the first one used
   */
  public record Resource(URI metadataUrl, String resource, List<String> authorizationServers, List<String> scopes,
      String name) {

    public Resource {
      authorizationServers = List.copyOf(authorizationServers);
      scopes = List.copyOf(scopes);
    }
  }

  /**
   * An authorization server as its metadata describes it.
   *
   * @param registration            the dynamic client registration endpoint (RFC 7591), or {@code null}
   * @param challengeMethods        {@code code_challenge_methods_supported}; empty when it doesn't say
   * @param issParameter            it adds {@code iss} to its redirects (RFC 9207)
   * @param clientMetadataDocuments it accepts an HTTPS URL as client ID (Client ID Metadata Documents)
   * @param defaults                no metadata was found: the 2025-03-26 default endpoints of the server's origin
   * @param deviceAuthorization     the device authorization endpoint (RFC 8628), or {@code null}
   * @param grantTypes              {@code grant_types_supported}; empty when it doesn't say
   */
  public record Server(String issuer, URI authorization, URI token, URI registration, URI revocation,
      List<String> scopes, List<String> challengeMethods, List<String> authMethods, boolean issParameter,
      boolean clientMetadataDocuments, boolean defaults, URI deviceAuthorization, List<String> grantTypes) {

    public Server {
      scopes = List.copyOf(scopes);
      challengeMethods = List.copyOf(challengeMethods);
      authMethods = List.copyOf(authMethods);
      grantTypes = List.copyOf(grantTypes);
    }

    /** A server without the device authorization grant. */
    public Server(String issuer, URI authorization, URI token, URI registration, URI revocation, List<String> scopes,
        List<String> challengeMethods, List<String> authMethods, boolean issParameter, boolean clientMetadataDocuments,
        boolean defaults) {
      this(issuer, authorization, token, registration, revocation, scopes, challengeMethods, authMethods, issParameter,
          clientMetadataDocuments, defaults, null, List.of());
    }

    /** It lets a client register itself, and the registration endpoint passes the URL rules. */
    public boolean dynamicRegistration() {
      return registration != null;
    }

    /**
     * It takes the device authorization grant: it names the endpoint, which passes the URL rules, and lists the grant
     * where it lists its grants.
     */
    public boolean deviceGrant() {
      return deviceAuthorization != null && (grantTypes.isEmpty() || grantTypes.contains(DEVICE_GRANT));
    }
  }

  /**
   * What discovery found.
   *
   * @param resource the {@code resource} parameter of the sign-in (RFC 8707): the metadata's resource identifier, or
   *                 the server's URL when it has none
   * @param scope    the scopes the 401 challenge asks for, or {@code null}
   * @param message  for {@link Kind#REFUSED} and {@link Kind#UNKNOWN}: what happened
   */
  public record Result(Kind kind, URI serverUrl, String resource, Resource metadata, Server server, String scope,
      String message) {}

  private Discovery() {}

  /** Looks at {@code serverUrl} without a sign-in and, when it wants one, finds where and how to sign in. */
  public static Result discover(OAuthHttp http, URI serverUrl) throws OAuthHttp.Failure {
    OAuthHttp.Answer probe;
    try {
      probe = http.probe(serverUrl, initialize(), Map.of("Accept", "application/json, text/event-stream"));
    } catch (OAuthHttp.Failure e) {
      return new Result(Kind.UNKNOWN, serverUrl, null, null, null, null, e.getMessage());
    }
    if (probe.ok()) {
      if (probe.sessionId() != null && !probe.sessionId().isBlank()) {
        http.forget(serverUrl, probe.sessionId());
      }
      Resource metadata = resourceMetadata(http, serverUrl, null);
      Server server = metadata == null || metadata.authorizationServers().isEmpty() ? null
          : authorizationServer(http, metadata.authorizationServers().getFirst());
      return server == null ? new Result(Kind.NONE, serverUrl, null, null, null, null, null)
          : new Result(Kind.OPTIONAL, serverUrl, metadata.resource(), metadata, server, null, null);
    }
    WwwAuthenticate challenge = WwwAuthenticate.parse(probe.challenges());
    if (probe.status() != 401 && probe.status() != 403) {
      String why = probe.status() == 404 || probe.status() == 405
          ? "It answered HTTP " + probe.status() + " to an MCP request over Streamable HTTP. Check the URL: it usually "
              + "ends in /mcp."
          : "It answered HTTP " + probe.status() + " to an MCP request.";
      return new Result(Kind.UNKNOWN, serverUrl, null, null, null, null, why);
    }
    Resource metadata = resourceMetadata(http, serverUrl, challenge.resourceMetadata());
    if (metadata == null && !challenge.bearer() && probe.status() == 403) {
      return new Result(Kind.REFUSED, serverUrl, null, null, null, null, "It refused the request (HTTP 403) and "
          + "doesn't say how to sign in.");
    }
    Server server;
    String resource;
    if (metadata != null) {
      if (metadata.authorizationServers().isEmpty()) {
        throw new OAuthHttp.Failure(OAuthHttp.host(serverUrl) + " publishes its resource metadata without an "
            + "authorization server, so there's nowhere to sign in.");
      }
      server = authorizationServer(http, metadata.authorizationServers().getFirst());
      if (server == null) {
        throw new OAuthHttp.Failure("The authorization server of " + OAuthHttp.host(serverUrl) + " ("
            + metadata.authorizationServers().getFirst() + ") publishes no metadata GateRook could read.");
      }
      resource = metadata.resource();
    } else {
      // 2025-03-26: the MCP server's origin is the authorization server.
      server = legacyServer(http, serverUrl);
      if (server == null) {
        return new Result(Kind.REFUSED, serverUrl, null, null, null, null, "It refused the request (HTTP "
            + probe.status() + ") and publishes no OAuth metadata: it probably wants a key in a header.");
      }
      resource = canonical(serverUrl);
    }
    return new Result(Kind.OAUTH, serverUrl, resource, metadata, server, challenge.scope(), null);
  }

  /**
   * The protected-resource metadata: at the URL the challenge names, else the well-known URI with the server's path
   * inserted, else at the root. {@code null} when there is none. Its resource must be the server's URL or a prefix of
   * it on the same origin, or a server could send a token for itself to another.
   */
  static Resource resourceMetadata(OAuthHttp http, URI serverUrl, String named) throws OAuthHttp.Failure {
    List<URI> candidates = new ArrayList<>();
    if (named != null && !named.isBlank()) {
      candidates.add(OAuthHttp.checked(named, "The resource metadata URL of " + OAuthHttp.host(serverUrl)));
    } else {
      String path = trimSlash(serverUrl.getRawPath());
      if (!path.isEmpty()) {
        candidates.add(origin(serverUrl).resolve("/.well-known/oauth-protected-resource" + path));
      }
      candidates.add(origin(serverUrl).resolve("/.well-known/oauth-protected-resource"));
    }
    for (URI url : candidates) {
      OAuthHttp.Answer a = http.get(url, "application/json");
      JsonNode j = a.ok() ? a.json() : null;
      if (j == null || !j.isObject() || !j.path("resource").isString()) {
        continue;
      }
      String resource = j.path("resource").asString();
      if (!resourceAllowed(serverUrl, resource)) {
        throw new OAuthHttp.Failure(OAuthHttp.host(serverUrl) + " publishes metadata for another resource ("
            + OAuthHttp.clean(resource) + "), so GateRook won't send it a sign-in.");
      }
      return new Resource(url, resource, OAuthHttp.strings(j.path("authorization_servers")),
          OAuthHttp.strings(j.path("scopes_supported")), j.path("resource_name").asString(null));
    }
    return null;
  }

  /** The metadata of the authorization server {@code issuer}, or {@code null} when none of its URIs answers. */
  public static Server authorizationServer(OAuthHttp http, String issuer) throws OAuthHttp.Failure {
    URI is = OAuthHttp.checked(issuer, "The authorization server");
    for (URI url : metadataUrls(is)) {
      OAuthHttp.Answer a = http.get(url, "application/json");
      JsonNode j = a.ok() ? a.json() : null;
      if (j == null || !j.isObject() || !j.path("issuer").isString()) {
        continue;
      }
      String declared = j.path("issuer").asString();
      if (!trimSlash(declared).equals(trimSlash(issuer))) {
        // RFC 8414 §3.3: metadata that names another issuer is not this server's.
        throw new OAuthHttp.Failure("The metadata at " + url + " names another issuer (" + OAuthHttp.clean(declared)
            + ") than " + issuer + ", so GateRook doesn't use it.");
      }
      return server(j, declared, false);
    }
    return null;
  }

  /** 2025-03-26: metadata at the origin of the MCP server, or the default {@code /authorize}, {@code /token}. */
  static Server legacyServer(OAuthHttp http, URI serverUrl) throws OAuthHttp.Failure {
    URI origin = origin(serverUrl);
    OAuthHttp.Answer a = http.get(origin.resolve("/.well-known/oauth-authorization-server"), "application/json");
    JsonNode j = a.ok() ? a.json() : null;
    if (j != null && j.isObject() && j.path("authorization_endpoint").isString()) {
      return server(j, j.path("issuer").asString(trimSlash(origin.toString())), false);
    }
    if (a.status() != 404) {
      return null;
    }
    ObjectNode defaults = OAuthHttp.object("issuer", trimSlash(origin.toString()),
        "authorization_endpoint", origin.resolve("/authorize").toString(),
        "token_endpoint", origin.resolve("/token").toString(),
        "registration_endpoint", origin.resolve("/register").toString());
    return server(defaults, defaults.path("issuer").asString(), true);
  }

  private static Server server(JsonNode j, String issuer, boolean defaults) throws OAuthHttp.Failure {
    if (!j.path("authorization_endpoint").isString() || !j.path("token_endpoint").isString()) {
      throw new OAuthHttp.Failure("The authorization server " + issuer + " doesn't name its authorization and token "
          + "endpoints, so there's no way to sign in.");
    }
    List<String> responseTypes = OAuthHttp.strings(j.path("response_types_supported"));
    if (!responseTypes.isEmpty() && !responseTypes.contains("code")) {
      throw new OAuthHttp.Failure("The authorization server " + issuer + " doesn't offer the authorization code "
          + "flow, the only one a desktop app can use safely.");
    }
    List<String> methods = OAuthHttp.strings(j.path("code_challenge_methods_supported"));
    // MCP authorization: without S256 in the metadata a client doesn't proceed. Only the 2025-03-26 defaults, which
    // have no metadata to say it in, are taken on trust.
    if (!defaults && !methods.contains("S256")) {
      throw new OAuthHttp.Failure("The authorization server " + issuer + " doesn't say it supports PKCE with S256, "
          + "which GateRook requires for a sign-in.");
    }
    URI registration = j.path("registration_endpoint").isString()
        ? OAuthHttp.checked(j.path("registration_endpoint").asString(), "The registration endpoint") : null;
    URI revocation = j.path("revocation_endpoint").isString()
        ? OAuthHttp.checked(j.path("revocation_endpoint").asString(), "The revocation endpoint") : null;
    URI device = null;
    if (j.path("device_authorization_endpoint").isString()) {
      try {
        device = OAuthHttp.checked(j.path("device_authorization_endpoint").asString(),
            "The device authorization endpoint");
      } catch (OAuthHttp.Failure e) {
        // Only GateRook's own apps use it (GateRookApps): every other sign-in at this server works without it.
        device = null;
      }
    }
    return new Server(issuer,
        OAuthHttp.checked(j.path("authorization_endpoint").asString(), "The authorization endpoint"),
        OAuthHttp.checked(j.path("token_endpoint").asString(), "The token endpoint"), registration, revocation,
        OAuthHttp.strings(j.path("scopes_supported")), methods,
        OAuthHttp.strings(j.path("token_endpoint_auth_methods_supported")),
        j.path("authorization_response_iss_parameter_supported").asBoolean(false),
        j.path("client_id_metadata_document_supported").asBoolean(false), defaults, device,
        OAuthHttp.strings(j.path("grant_types_supported")));
  }

  /**
   * RFC 8414 §3.1 with OpenID Connect Discovery as the fallback: for {@code https://as/tenant},
   * {@code /.well-known/oauth-authorization-server/tenant}, {@code /.well-known/openid-configuration/tenant} and
   * {@code /tenant/.well-known/openid-configuration}; without a path the two well-known URIs at the root.
   */
  static List<URI> metadataUrls(URI issuer) {
    URI origin = origin(issuer);
    String path = trimSlash(issuer.getRawPath());
    Set<URI> out = new LinkedHashSet<>();
    if (path.isEmpty()) {
      out.add(origin.resolve("/.well-known/oauth-authorization-server"));
      out.add(origin.resolve("/.well-known/openid-configuration"));
    } else {
      out.add(origin.resolve("/.well-known/oauth-authorization-server" + path));
      out.add(origin.resolve("/.well-known/openid-configuration" + path));
      out.add(origin.resolve(path + "/.well-known/openid-configuration"));
    }
    return List.copyOf(out);
  }

  /**
   * Whether metadata for {@code resource} may speak for {@code serverUrl}: the same origin, and a path that is the
   * server's or one of its ancestors ({@code https://mcp.example.com} for {@code https://mcp.example.com/mcp}).
   */
  static boolean resourceAllowed(URI serverUrl, String resource) {
    URI r;
    try {
      r = URI.create(resource);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (r.getScheme() == null || r.getHost() == null
        || !r.getScheme().equalsIgnoreCase(serverUrl.getScheme())
        || !r.getHost().equalsIgnoreCase(serverUrl.getHost()) || port(r) != port(serverUrl)) {
      return false;
    }
    String allowed = trimSlash(r.getRawPath());
    String requested = trimSlash(serverUrl.getRawPath());
    return allowed.isEmpty() || requested.equals(allowed) || requested.startsWith(allowed + "/");
  }

  /** The server's URL as a resource identifier: lower-case scheme and host, no default port, no fragment. */
  public static String canonical(URI url) {
    String scheme = url.getScheme().toLowerCase(Locale.ROOT);
    String host = url.getHost().toLowerCase(Locale.ROOT);
    int port = url.getPort();
    boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http")
        && port == 80);
    String path = url.getRawPath() == null ? "" : url.getRawPath();
    String query = url.getRawQuery() == null ? "" : "?" + url.getRawQuery();
    return scheme + "://" + (host.contains(":") ? "[" + host + "]" : host) + (defaultPort ? "" : ":" + port) + path
        + query;
  }

  static URI origin(URI url) {
    return URI.create(url.getScheme() + "://" + url.getRawAuthority() + "/");
  }

  private static int port(URI u) {
    if (u.getPort() != -1) {
      return u.getPort();
    }
    return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
  }

  public static String trimSlash(String s) {
    if (s == null) {
      return "";
    }
    String out = s;
    while (out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }

  private static ObjectNode initialize() {
    ObjectNode body = OAuthHttp.JSON.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.put("id", "gaterook-sign-in-probe");
    body.put("method", "initialize");
    ObjectNode params = body.putObject("params");
    params.put("protocolVersion", "2025-06-18");
    params.putObject("capabilities");
    ObjectNode info = params.putObject("clientInfo");
    info.put("name", "gaterook");
    info.put("version", BuiltIn.VERSION);
    return body;
  }
}
