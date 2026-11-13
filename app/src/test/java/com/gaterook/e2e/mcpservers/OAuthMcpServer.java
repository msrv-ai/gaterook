package com.gaterook.e2e.mcpservers;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A remote MCP server with its own OAuth 2.1 authorization server, as the official ones behave (2026-11-01): a 401 with
 * {@code resource_metadata}, protected-resource and authorization-server metadata, dynamic client registration and,
 * when asked, Client ID Metadata Documents beside it, an authorization endpoint that checks the client, redirect URI,
 * PKCE and resource and then redirects with {@code code}, {@code state} and {@code iss}, public clients only at the
 * token endpoint, rotating refresh tokens bound to their client (a reused one is refused) and revocation; it also
 * answers a request whose URL carries its key ({@link #URL_KEY}). The "person" always allows: the test plays the browser.
 *
 * <p>{@link #registeredAppsOnly} behaves as GitHub's authorization server does (2026-11-09): neither registration nor
 * documents, no authentication methods listed, the device authorization grant for the one app it knows
 * ({@link #APP_CLIENT}), its errors answered with HTTP 200. There the test plays the person who enters the code
 * ({@link #approve}, {@link #deny}). {@link #confidentialAppsOnly} admits only the OAuth app the person registered
 * there, which authenticates with its client secret. Its resource metadata can name another authorization server
 * ({@link #authorizationServer}), as a server can at any time, and its tokens can be short-lived ({@link #expiresIn}).
 */
final class OAuthMcpServer implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The URL of GateRook's Client ID Metadata Document. */
  static final String GATEROOK_CLIENT = "https://gaterook.com/oauth/gaterook-client.json";

  /** The client ID of GateRook's own app at a {@link #registeredAppsOnly} server: the only client it admits. */
  static final String APP_CLIENT = "gaterook-test-app";

  /** A key the server also takes in its URL ({@code /mcp?apiKey=…}), as some servers' docs give it. */
  static final String URL_KEY = "trk-url-key-5d1e7a";

  /** The client ID of the one OAuth app a {@link #confidentialAppsOnly} server knows: the person registered it there. */
  static final String CONFIDENTIAL_CLIENT = "chat-app-1234";
  /** That app's client secret (a test value). */
  static final String CONFIDENTIAL_SECRET = "chat-app-secret-5678-test";

  /**
   * The metadata documents this authorization server can read, by URL: GateRook's as its website publishes it. A real
   * one fetches the URL; the test has no network, so the documents are here.
   */
  private static final Map<String, String> PUBLISHED = Map.of(GATEROOK_CLIENT, """
      {
        "client_id": "https://gaterook.com/oauth/gaterook-client.json",
        "client_name": "GateRook",
        "client_uri": "https://gaterook.com/",
        "application_type": "native",
        "redirect_uris": ["http://localhost/callback", "http://127.0.0.1/callback"],
        "grant_types": ["authorization_code", "refresh_token"],
        "response_types": ["code"],
        "token_endpoint_auth_method": "none"
      }""");

  /** A client: registered dynamically, or its metadata document ({@code registration} is then the document). */
  record Client(String id, List<String> redirects, JsonNode registration) {

    /**
     * One of the client's redirect URIs exactly, or a loopback one whose port its list leaves out: authorization
     * servers that take the metadata documents of the AI apps' command-line clients match those with any port
     * (RFC 8252 §7.3).
     */
    String redirect(String requested) {
      String redirect = loopbackRedirect(requested);
      if (redirect == null) {
        return null;
      }
      if (redirects.contains(redirect)) {
        return redirect;
      }
      URI u = URI.create(redirect);
      return redirects.contains("http://" + u.getHost() + "/callback") ? redirect : null;
    }
  }

  /** The fixture only redirects to GateRook's loopback callback; rebuild its authority from validated components. */
  static String loopbackRedirect(String value) {
    if (value == null) {
      return null;
    }
    final URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (!"http".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawQuery() != null
        || uri.getRawFragment() != null || !"/callback".equals(uri.getRawPath())
        || uri.getPort() < 1 || uri.getPort() > 65535) {
      return null;
    }
    String host;
    if ("localhost".equals(uri.getHost())) {
      host = "localhost";
    } else if ("127.0.0.1".equals(uri.getHost())) {
      host = "127.0.0.1";
    } else {
      return null;
    }
    return "http://" + host + ":" + uri.getPort() + "/callback";
  }

  record Code(String client, String redirect, String challenge, String resource, String scope) {}

  /** A device code it issued, and the code the person enters for it. */
  record Device(String userCode, String client, String scope) {}

  final List<JsonNode> registrations = new CopyOnWriteArrayList<>();
  /** The metadata documents read, by URL, once per authorization request that named one. */
  final List<String> documentsRead = new CopyOnWriteArrayList<>();
  final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
  /** The {@code Authorization} header of every token request, as sent: an app's client secret, or {@code null}. */
  final List<String> tokenAuthorizations = new CopyOnWriteArrayList<>();
  /** How long the access tokens it issues last, in seconds. */
  volatile int expiresIn = 3600;
  /** The authorization server its resource metadata names: its own while {@code null}. */
  volatile String authorizationServer;
  final List<Map<String, String>> revocations = new CopyOnWriteArrayList<>();
  final List<String> authorizationsSeen = new CopyOnWriteArrayList<>();
  final List<String> bearersSeen = new CopyOnWriteArrayList<>();
  /** It also answers without a sign-in, with less access: the review finds the sign-in optional. */
  volatile boolean anonymous;
  final Map<String, Client> clients = new ConcurrentHashMap<>();
  final Map<String, Code> codes = new ConcurrentHashMap<>();
  final Set<String> access = ConcurrentHashMap.newKeySet();
  /** Every access token issued, in order (for a test's description of what happened). */
  final List<String> issued = new CopyOnWriteArrayList<>();
  final Set<String> refresh = ConcurrentHashMap.newKeySet();
  /** The client each refresh token was issued to: only that client may redeem it. */
  final Map<String, String> refreshClients = new ConcurrentHashMap<>();
  final Set<String> usedRefresh = ConcurrentHashMap.newKeySet();
  /** The device authorization requests, as sent. */
  final List<Map<String, String>> deviceRequests = new CopyOnWriteArrayList<>();
  final Map<String, Device> devices = new ConcurrentHashMap<>();
  /** The user code of every device code issued, kept after the device code is used up. */
  final Map<String, String> userCodes = new ConcurrentHashMap<>();
  /** The user codes the person entered and allowed, or cancelled, on the page. */
  final Set<String> approved = ConcurrentHashMap.newKeySet();
  final Set<String> denied = ConcurrentHashMap.newKeySet();
  private final boolean metadataDocuments;
  private final boolean registeredApps;
  private final boolean confidentialApps;
  private final Tomcat tomcat;
  private final McpSyncServer mcp;
  final int port;
  final String base;

  /** With dynamic client registration only. */
  OAuthMcpServer(Path work) throws Exception {
    this(work, false);
  }

  /** @param metadataDocuments it also accepts Client ID Metadata Documents and says so in its metadata */
  OAuthMcpServer(Path work, boolean metadataDocuments) throws Exception {
    this(work, metadataDocuments, false, false);
  }

  /** An authorization server like GitHub's: apps registered with it only, signing in with the device grant. */
  static OAuthMcpServer registeredAppsOnly(Path work) throws Exception {
    return new OAuthMcpServer(work, false, true, false);
  }

  /**
   * An authorization server like Slack's: no registration and no documents, only the OAuth app the person registered
   * there ({@link #CONFIDENTIAL_CLIENT}), which authenticates with its client secret at the token endpoint.
   */
  static OAuthMcpServer confidentialAppsOnly(Path work) throws Exception {
    return new OAuthMcpServer(work, false, false, true);
  }

  private OAuthMcpServer(Path work, boolean metadataDocuments, boolean registeredApps, boolean confidentialApps)
      throws Exception {
    this.metadataDocuments = metadataDocuments;
    this.registeredApps = registeredApps;
    this.confidentialApps = confidentialApps;
    if (confidentialApps) {
      // Registered with a loopback redirect: any port on this computer (RFC 8252 §7.3).
      clients.put(CONFIDENTIAL_CLIENT, new Client(CONFIDENTIAL_CLIENT, List.of("http://127.0.0.1/callback"), null));
    }
    HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(new JacksonMcpJsonMapper(JSON)).mcpEndpoint("/mcp").build();
    mcp = McpServer.sync(transport).serverInfo("tracker", "3.1.0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
        .tools(McpServerFeatures.SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder().name("list_issues").description("Lists open issues.")
                .inputSchema(Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string"))))
                .annotations(new McpSchema.ToolAnnotations("List issues", true, false, true, false, null)).build())
            .callHandler((ex, req) -> McpSchema.CallToolResult.builder()
                .addTextContent("issues matching " + req.arguments().get("q")).build())
            .build())
        .build();
    tomcat = new Tomcat();
    tomcat.setPort(0);
    tomcat.setBaseDir(work.toString());
    Context ctx = tomcat.addContext("", null);
    Tomcat.addServlet(ctx, "mcp", transport).setAsyncSupported(true);
    ctx.addServletMappingDecoded("/mcp", "mcp");
    servlet(ctx, "prm", "/.well-known/oauth-protected-resource/mcp", (req, res) -> json(res, 200, obj()
        .put("resource", base() + "/mcp").set("authorization_servers", JSON.createArrayNode()
            .add(authorizationServer != null ? authorizationServer : base()))));
    servlet(ctx, "as", "/.well-known/oauth-authorization-server", (req, res) -> {
      ObjectNode m = obj().put("issuer", base()).put("authorization_endpoint", base() + "/authorize")
          .put("token_endpoint", base() + "/token").put("authorization_response_iss_parameter_supported", true);
      m.putArray("response_types_supported").add("code");
      m.putArray("code_challenge_methods_supported").add("S256");
      if (registeredApps) {
        m.put("device_authorization_endpoint", base() + "/login/device/code");
        m.putArray("grant_types_supported").add("authorization_code").add("refresh_token")
            .add("urn:ietf:params:oauth:grant-type:device_code");
      } else if (confidentialApps) {
        m.put("revocation_endpoint", base() + "/revoke");
        m.putArray("token_endpoint_auth_methods_supported").add("client_secret_basic");
      } else {
        m.put("registration_endpoint", base() + "/register").put("revocation_endpoint", base() + "/revoke");
        m.putArray("token_endpoint_auth_methods_supported").add("none");
      }
      m.putArray("scopes_supported").add("issues:read").add("offline_access");
      if (metadataDocuments) {
        m.put("client_id_metadata_document_supported", true);
      }
      json(res, 200, m);
    });
    servlet(ctx, "register", "/register", this::register);
    servlet(ctx, "device", "/login/device/code", this::deviceCode);
    servlet(ctx, "authorize", "/authorize", this::authorize);
    servlet(ctx, "token", "/token", this::token);
    servlet(ctx, "revoke", "/revoke", (req, res) -> {
      Map<String, String> form = form(req);
      revocations.add(form);
      refresh.remove(form.get("token"));
      access.remove(form.get("token"));
      res.setStatus(200);
    });
    Filter guard = (request, response, chain) -> {
      HttpServletRequest r = (HttpServletRequest) request;
      HttpServletResponse w = (HttpServletResponse) response;
      if (("apiKey=" + URL_KEY).equals(r.getQueryString())) {
        // As servers that take their key in the URL do, beside a sign-in.
        chain.doFilter(request, response);
        return;
      }
      String header = r.getHeader("Authorization");
      bearersSeen.add(String.valueOf(header));
      if (header == null && anonymous) {
        chain.doFilter(request, response);
        return;
      }
      if (header == null || !header.startsWith("Bearer ") || !access.contains(header.substring(7))) {
        w.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", resource_metadata=\"" + base()
            + "/.well-known/oauth-protected-resource/mcp\", scope=\"issues:read\"");
        w.sendError(401);
        return;
      }
      chain.doFilter(request, response);
    };
    FilterDef def = new FilterDef();
    def.setFilterName("bearer");
    def.setFilter(guard);
    def.setAsyncSupported("true");
    ctx.addFilterDef(def);
    FilterMap map = new FilterMap();
    map.setFilterName("bearer");
    map.addURLPattern("/mcp");
    ctx.addFilterMap(map);
    tomcat.getConnector();
    tomcat.start();
    port = tomcat.getConnector().getLocalPort();
    base = "http://127.0.0.1:" + port;
  }

  private String base() {
    return "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
  }

  /** Every access token the server issued stops working (it expired early); refresh tokens still work. */
  void expireAccessTokens() {
    access.clear();
  }

  /** The person revoked the app at the provider: nothing it holds works any more. */
  void revokeEverything() {
    access.clear();
    refresh.clear();
  }

  /** The person entered {@code userCode} on the provider's page and allowed access. */
  void approve(String userCode) {
    approved.add(userCode);
  }

  /** The person entered {@code userCode} on the provider's page and cancelled. */
  void deny(String userCode) {
    denied.add(userCode);
  }

  /** How often the token endpoint was polled for the device code of {@code userCode}. */
  long polls(String userCode) {
    return tokenRequests.stream().filter(f -> userCode.equals(userCodes.get(String.valueOf(f.get("device_code")))))
        .count();
  }

  // ── The authorization server ────────────────────────────────────────────────────────────────────────────────

  /** GitHub's device authorization endpoint: its own app only, errors with HTTP 200. */
  private void deviceCode(HttpServletRequest req, HttpServletResponse res) throws IOException {
    Map<String, String> form = form(req);
    deviceRequests.add(form);
    if (!registeredApps || !APP_CLIENT.equals(form.get("client_id")) || form.containsKey("client_secret")
        || req.getHeader("Authorization") != null) {
      json(res, 200, obj().put("error", "incorrect_client_credentials").put("error_description", "The client_id "
          + "and/or client_secret passed are incorrect."));
      return;
    }
    String deviceCode = "trk-dc-" + random();
    String userCode = (random() + random()).replaceAll("[^A-Za-z]", "").toUpperCase(java.util.Locale.ROOT)
        .substring(0, 8);
    userCode = userCode.substring(0, 4) + "-" + userCode.substring(4);
    devices.put(deviceCode, new Device(userCode, form.get("client_id"), form.get("scope")));
    userCodes.put(deviceCode, userCode);
    json(res, 200, obj().put("device_code", deviceCode).put("user_code", userCode)
        .put("verification_uri", base() + "/login/device").put("expires_in", 900).put("interval", 1));
  }

  private void register(HttpServletRequest req, HttpServletResponse res) throws IOException {
    JsonNode body = JSON.readTree(req.getInputStream());
    registrations.add(body);
    List<String> redirects = new java.util.ArrayList<>();
    body.path("redirect_uris").forEach(r -> redirects.add(loopbackRedirect(r.asString())));
    if (redirects.isEmpty() || redirects.contains(null)) {
      json(res, 400, obj().put("error", "invalid_redirect_uri"));
      return;
    }
    String id = "client-" + random();
    clients.put(id, new Client(id, List.copyOf(redirects), body));
    json(res, 201, obj().put("client_id", id).put("token_endpoint_auth_method", "none")
        .set("redirect_uris", body.path("redirect_uris")));
  }

  /**
   * The client of a metadata document, read as the draft says: the document names its own URL as client ID, and it is
   * a public client. {@code null} where this server doesn't take documents, or doesn't know the URL.
   */
  private Client metadataDocument(String url) {
    if (!metadataDocuments || !PUBLISHED.containsKey(url)) {
      return null;
    }
    documentsRead.add(url);
    JsonNode doc = JSON.readTree(PUBLISHED.get(url));
    if (!url.equals(doc.path("client_id").asString()) || !"none".equals(doc.path("token_endpoint_auth_method")
        .asString())) {
      return null;
    }
    List<String> redirects = new ArrayList<>();
    doc.path("redirect_uris").forEach(r -> redirects.add(r.asString()));
    return new Client(url, List.copyOf(redirects), doc);
  }

  private void authorize(HttpServletRequest req, HttpServletResponse res) throws IOException {
    authorizationsSeen.add(req.getQueryString());
    String clientId = String.valueOf(req.getParameter("client_id"));
    Client c = clientId.startsWith("https://") ? metadataDocument(clientId) : clients.get(clientId);
    String redirect = c == null ? null : c.redirect(req.getParameter("redirect_uri"));
    if (redirect == null) {
      res.sendError(400, "Unknown client or redirect URI");
      return;
    }
    if (!"code".equals(req.getParameter("response_type")) || !"S256".equals(req.getParameter("code_challenge_method"))
        || req.getParameter("code_challenge") == null || !(base() + "/mcp").equals(req.getParameter("resource"))) {
      res.sendRedirect(redirect + "?error=invalid_request&state=" + enc(req.getParameter("state")));
      return;
    }
    String code = "trk-code-" + random();
    codes.put(code, new Code(c.id(), redirect, req.getParameter("code_challenge"), req.getParameter("resource"),
        req.getParameter("scope")));
    res.setStatus(302);
    res.setHeader("Location", redirect + "?code=" + enc(code) + "&state=" + enc(req.getParameter("state"))
        + "&iss=" + enc(base()));
  }

  private void token(HttpServletRequest req, HttpServletResponse res) throws IOException {
    Map<String, String> form = form(req);
    tokenRequests.add(form);
    tokenAuthorizations.add(req.getHeader("Authorization"));
    if (!(base() + "/mcp").equals(form.get("resource"))) {
      json(res, 400, obj().put("error", "invalid_target"));
      return;
    }
    String client;
    if (confidentialApps) {
      // Only its app, with its client secret (client_secret_basic).
      client = appClient(req.getHeader("Authorization"));
      if (client == null) {
        json(res, 401, obj().put("error", "invalid_client"));
        return;
      }
    } else if (req.getHeader("Authorization") != null || form.containsKey("client_secret")) {
      // Its clients are public (none): one that authenticates some other way isn't one of them.
      json(res, 401, obj().put("error", "invalid_client"));
      return;
    } else {
      client = form.get("client_id");
    }
    switch (String.valueOf(form.get("grant_type"))) {
      case "authorization_code" -> {
        Code code = codes.remove(String.valueOf(form.get("code")));
        if (code == null || !code.client().equals(client) || !code.redirect().equals(form.get(
            "redirect_uri")) || !code.challenge().equals(s256(form.get("code_verifier")))) {
          json(res, 400, obj().put("error", "invalid_grant"));
          return;
        }
        issue(res, code.client(), code.scope());
      }
      case "refresh_token" -> {
        String old = form.get("refresh_token");
        String issuedTo = old == null ? null : refreshClients.get(old);
        if (issuedTo == null || !issuedTo.equals(client) || !refresh.remove(old)) {
          json(res, 400, obj().put("error", "invalid_grant").put("error_description", old != null
              && usedRefresh.contains(old) ? "Refresh token reused" : "Refresh token revoked"));
          return;
        }
        usedRefresh.add(old);
        issue(res, client, "issues:read offline_access");
      }
      case "urn:ietf:params:oauth:grant-type:device_code" -> {
        // As GitHub answers: HTTP 200 with the error.
        String deviceCode = String.valueOf(form.get("device_code"));
        Device d = devices.get(deviceCode);
        if (!registeredApps || d == null || !d.client().equals(form.get("client_id"))) {
          json(res, 200, obj().put("error", "incorrect_device_code"));
        } else if (denied.contains(d.userCode())) {
          devices.remove(deviceCode);
          json(res, 200, obj().put("error", "access_denied"));
        } else if (!approved.contains(d.userCode())) {
          json(res, 200, obj().put("error", "authorization_pending"));
        } else {
          devices.remove(deviceCode);
          issue(res, d.client(), d.scope());
        }
      }
      default -> json(res, 400, obj().put("error", "unsupported_grant_type"));
    }
  }

  private void issue(HttpServletResponse res, String client, String scope) throws IOException {
    String at = "trk-at-" + random();
    String rt = "trk-rt-" + random();
    access.add(at);
    issued.add(at);
    refresh.add(rt);
    refreshClients.put(rt, client);
    res.setHeader("Cache-Control", "no-store");
    json(res, 200, obj().put("access_token", at).put("token_type", "Bearer").put("expires_in", expiresIn)
        .put("refresh_token", rt).put("scope", scope == null ? "issues:read" : scope));
  }

  /** The app a {@code client_secret_basic} header authenticates (RFC 6749 §2.3.1), or {@code null}. */
  private static String appClient(String authorization) {
    if (authorization == null || !authorization.startsWith("Basic ")) {
      return null;
    }
    String pair = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
    int colon = pair.indexOf(':');
    if (colon < 0) {
      return null;
    }
    String id = java.net.URLDecoder.decode(pair.substring(0, colon), StandardCharsets.UTF_8);
    String secret = java.net.URLDecoder.decode(pair.substring(colon + 1), StandardCharsets.UTF_8);
    return CONFIDENTIAL_CLIENT.equals(id) && CONFIDENTIAL_SECRET.equals(secret) ? id : null;
  }

  // ── Plumbing ────────────────────────────────────────────────────────────────────────────────────────────────

  private interface Handler {
    void handle(HttpServletRequest req, HttpServletResponse res) throws IOException;
  }

  private static void servlet(Context ctx, String name, String path, Handler h) {
    Tomcat.addServlet(ctx, name, new HttpServlet() {
      @Override
      protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
        h.handle(req, res);
      }
    });
    ctx.addServletMappingDecoded(path, name);
  }

  private static Map<String, String> form(HttpServletRequest req) {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    req.getParameterMap().forEach((k, v) -> out.put(k, v.length == 0 ? "" : v[0]));
    return out;
  }

  private static void json(HttpServletResponse res, int status, JsonNode body) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.getOutputStream().write(JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
  }

  private static ObjectNode obj() {
    return JSON.createObjectNode();
  }

  private static String enc(String s) {
    return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
  }

  private static String random() {
    byte[] b = new byte[12];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String s256(String verifier) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
          .digest(String.valueOf(verifier).getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() throws Exception {
    mcp.close();
    tomcat.stop();
    tomcat.destroy();
  }
}
