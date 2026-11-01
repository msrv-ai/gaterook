package ai.msrv.gaterook.e2e.mcpservers;

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
 */
final class OAuthMcpServer implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The URL of GateRook's Client ID Metadata Document. */
  static final String GATEROOK_CLIENT = "https://msrv.dev/oauth/gaterook-client.json";

  /** A key the server also takes in its URL ({@code /mcp?apiKey=…}), as some servers' docs give it. */
  static final String URL_KEY = "trk-url-key-5d1e7a";

  /**
   * The metadata documents this authorization server can read, by URL: GateRook's as its website publishes it. A real
   * one fetches the URL; the test has no network, so the documents are here.
   */
  private static final Map<String, String> PUBLISHED = Map.of(GATEROOK_CLIENT, """
      {
        "client_id": "https://msrv.dev/oauth/gaterook-client.json",
        "client_name": "GateRook",
        "client_uri": "https://msrv.dev/",
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
    boolean accepts(String redirect) {
      if (redirect == null) {
        return false;
      }
      if (redirects.contains(redirect)) {
        return true;
      }
      URI u = URI.create(redirect);
      return "http".equals(u.getScheme()) && List.of("localhost", "127.0.0.1").contains(u.getHost())
          && redirects.contains("http://" + u.getHost() + u.getRawPath());
    }
  }

  record Code(String client, String redirect, String challenge, String resource, String scope) {}

  final List<JsonNode> registrations = new CopyOnWriteArrayList<>();
  /** The metadata documents read, by URL, once per authorization request that named one. */
  final List<String> documentsRead = new CopyOnWriteArrayList<>();
  final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
  final List<Map<String, String>> revocations = new CopyOnWriteArrayList<>();
  final List<String> authorizationsSeen = new CopyOnWriteArrayList<>();
  final List<String> bearersSeen = new CopyOnWriteArrayList<>();
  final Map<String, Client> clients = new ConcurrentHashMap<>();
  final Map<String, Code> codes = new ConcurrentHashMap<>();
  final Set<String> access = ConcurrentHashMap.newKeySet();
  /** Every access token issued, in order (for a test's description of what happened). */
  final List<String> issued = new CopyOnWriteArrayList<>();
  final Set<String> refresh = ConcurrentHashMap.newKeySet();
  /** The client each refresh token was issued to: only that client may redeem it. */
  final Map<String, String> refreshClients = new ConcurrentHashMap<>();
  final Set<String> usedRefresh = ConcurrentHashMap.newKeySet();
  private final boolean metadataDocuments;
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
    this.metadataDocuments = metadataDocuments;
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
        .put("resource", base() + "/mcp").set("authorization_servers", JSON.createArrayNode().add(base()))));
    servlet(ctx, "as", "/.well-known/oauth-authorization-server", (req, res) -> {
      ObjectNode m = obj().put("issuer", base()).put("authorization_endpoint", base() + "/authorize")
          .put("token_endpoint", base() + "/token").put("registration_endpoint", base() + "/register")
          .put("revocation_endpoint", base() + "/revoke").put("authorization_response_iss_parameter_supported", true);
      m.putArray("response_types_supported").add("code");
      m.putArray("code_challenge_methods_supported").add("S256");
      m.putArray("token_endpoint_auth_methods_supported").add("none");
      m.putArray("scopes_supported").add("issues:read").add("offline_access");
      if (metadataDocuments) {
        m.put("client_id_metadata_document_supported", true);
      }
      json(res, 200, m);
    });
    servlet(ctx, "register", "/register", this::register);
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

  // ── The authorization server ────────────────────────────────────────────────────────────────────────────────

  private void register(HttpServletRequest req, HttpServletResponse res) throws IOException {
    JsonNode body = JSON.readTree(req.getInputStream());
    registrations.add(body);
    List<String> redirects = new java.util.ArrayList<>();
    body.path("redirect_uris").forEach(r -> redirects.add(r.asString()));
    if (redirects.isEmpty() || redirects.stream().anyMatch(r -> !r.startsWith("http://localhost:")
        && !r.startsWith("http://127.0.0.1:"))) {
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
    String redirect = req.getParameter("redirect_uri");
    if (c == null || !c.accepts(redirect)) {
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
    if (!(base() + "/mcp").equals(form.get("resource"))) {
      json(res, 400, obj().put("error", "invalid_target"));
      return;
    }
    if (req.getHeader("Authorization") != null || form.containsKey("client_secret")) {
      // Its clients are public (none): one that authenticates some other way isn't one of them.
      json(res, 401, obj().put("error", "invalid_client"));
      return;
    }
    switch (String.valueOf(form.get("grant_type"))) {
      case "authorization_code" -> {
        Code code = codes.remove(String.valueOf(form.get("code")));
        if (code == null || !code.client().equals(form.get("client_id")) || !code.redirect().equals(form.get(
            "redirect_uri")) || !code.challenge().equals(s256(form.get("code_verifier")))) {
          json(res, 400, obj().put("error", "invalid_grant"));
          return;
        }
        issue(res, code.client(), code.scope());
      }
      case "refresh_token" -> {
        String old = form.get("refresh_token");
        String client = old == null ? null : refreshClients.get(old);
        if (client == null || !client.equals(form.get("client_id")) || !refresh.remove(old)) {
          json(res, 400, obj().put("error", "invalid_grant").put("error_description", old != null
              && usedRefresh.contains(old) ? "Refresh token reused" : "Refresh token revoked"));
          return;
        }
        usedRefresh.add(old);
        issue(res, client, "issues:read offline_access");
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
    json(res, 200, obj().put("access_token", at).put("token_type", "Bearer").put("expires_in", 3600)
        .put("refresh_token", rt).put("scope", scope == null ? "issues:read" : scope));
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
