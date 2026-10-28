package ai.msrv.gaterook.core.mcp.internal.web;

import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.mcp.McpGateway;
import ai.msrv.gaterook.core.mcp.internal.Era;
import ai.msrv.gaterook.core.mcp.internal.EraResolver;
import ai.msrv.gaterook.core.mcp.internal.JsonRpc;
import ai.msrv.gaterook.core.mcp.internal.McpToolNotifications;
import ai.msrv.gaterook.core.platform.Authenticator;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CallerAuthenticator;
import ai.msrv.gaterook.core.platform.Ingress;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /mcp/{slug}} (ARCHITECTURE.md §5 steps 1–2, report 01 §3.1): one JSON-RPC object per request, answered
 * with {@code application/json}. Legacy clients can open an authenticated GET SSE stream for tool-list changes.
 * No session IDs or replay IDs are issued. Before any JSON-RPC: only {@code application/json} (415, which also keeps
 * cross-site forms out), a body of at most 1 MiB (413), a bearer token of this endpoint and ingress (401 with
 * {@code WWW-Authenticate: Bearer realm="gaterook"}), then one object (a batch is {@code -32600}). Other methods
 * are 405 with {@code Allow: GET, POST}. Host and Origin were checked by {@code LocalGuardFilter} already.
 */
@RestController
class McpHttpController {

  static final int MAX_BODY = 1 << 20;

  private final McpGateway gateway;
  private final CallerAuthenticator authenticator;
  private final Bootstrap bootstrap;
  private final JsonMapper json;
  private final EndpointSnapshotService snapshots;
  private final ToolCatalog catalogs;
  private final McpToolNotifications notifications;
  private final Duration heartbeat;

  McpHttpController(McpGateway gateway, CallerAuthenticator authenticator, Bootstrap bootstrap, JsonMapper json,
      EndpointSnapshotService snapshots, ToolCatalog catalogs, McpToolNotifications notifications,
      @Value("${gateway.mcp.heartbeat:15s}") Duration heartbeat) {
    this.gateway = gateway;
    this.authenticator = authenticator;
    this.bootstrap = bootstrap;
    this.json = json;
    this.snapshots = snapshots;
    this.catalogs = catalogs;
    this.notifications = notifications;
    this.heartbeat = heartbeat;
  }

  @PostMapping("/mcp/{slug}")
  ResponseEntity<byte[]> post(@PathVariable String slug, HttpServletRequest http) throws IOException {
    Ingress ingress = ingressOf(http);
    // Through the tunnel nothing is answered before the token (report 02 §3): claude.ai starts its sign-in on a 401
    // only, and an anonymous caller learns nothing about the endpoint, not even which bodies it takes.
    Optional<Caller> caller = ingress == Ingress.TUNNEL ? authenticate(ingress, slug, http) : Optional.empty();
    if (ingress == Ingress.TUNNEL && caller.isEmpty()) {
      return unauthorized(ingress, slug, http);
    }
    String type = http.getContentType();
    if (type == null || !isJson(type)) {
      return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }
    if (http.getContentLengthLong() > MAX_BODY) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }
    byte[] body;
    try (InputStream in = http.getInputStream()) {
      body = in.readNBytes(MAX_BODY + 1);
    }
    if (body.length > MAX_BODY) {
      return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
    }
    if (ingress != Ingress.TUNNEL) {
      caller = authenticate(ingress, slug, http);
      if (caller.isEmpty()) {
        return unauthorized(ingress, slug, http);
      }
    }
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (JacksonException e) {
      return write(400, McpGateway.error(JsonRpc.PARSE_ERROR, "Parse error"));
    }
    if (root == null || root.isArray()) {
      return write(400, McpGateway.error(JsonRpc.INVALID_REQUEST, "Batch requests are not supported"));
    }
    if (!root.isObject()) {
      return write(400, McpGateway.error(JsonRpc.INVALID_REQUEST, "A request is one JSON object"));
    }
    McpGateway.Response response = gateway.handle(slug, caller.get(), (ObjectNode) root, http::getHeader);
    if (response.body() == null) {
      return ResponseEntity.status(response.status()).build();
    }
    return write(response.status(), response.body());
  }

  /**
   * An independent Streamable HTTP notification stream, written on Tomcat's virtual request thread. Revalidate
   * access before each notification and heartbeat, so expired credentials and disabled endpoints close the stream.
   */
  @GetMapping("/mcp/{slug}")
  ResponseEntity<byte[]> get(@PathVariable String slug, HttpServletRequest http, HttpServletResponse response)
      throws IOException {
    Ingress ingress = ingressOf(http);
    Optional<Caller> caller = authenticate(ingress, slug, http);
    if (caller.isEmpty()) {
      return unauthorized(ingress, slug, http);
    }
    String version = Optional.ofNullable(http.getHeader("MCP-Protocol-Version")).orElse("2025-03-26");
    if (Era.MODERN_VERSION.equals(version)) {
      return methodNotAllowed();
    }
    if (!EraResolver.LEGACY.contains(version)) {
      return write(400, McpGateway.error(JsonRpc.INVALID_REQUEST, "Unsupported MCP-Protocol-Version " + version));
    }
    if (!acceptsEvents(http.getHeader(HttpHeaders.ACCEPT))) {
      return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }
    Optional<EndpointSnapshot> initial = snapshots.bySlug(slug).filter(EndpointSnapshot::enabled);
    if (initial.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    if (!legacyEnabled(initial.get())) {
      return methodNotAllowed();
    }
    // The client may have cached a list before connecting, or missed changes while disconnected. Invalidate that
    // unknown view once on every new stream, then notify only when the published catalog actually changes.
    String listHash = null;
    try (McpToolNotifications.Subscription subscription = notifications.subscribe(initial.get().id())) {
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
      response.setHeader("X-Accel-Buffering", "no");
      ServletOutputStream out = response.getOutputStream();
      writeFrame(out, ": connected\n\n");
      while (!subscription.isClosed()) {
        boolean changed = subscription.awaitChange(heartbeat);
        if (subscription.isClosed() || authenticate(ingress, slug, http).isEmpty()) {
          break;
        }
        Optional<EndpointSnapshot> current = snapshots.get(initial.get().id())
            .filter(s -> s.enabled() && s.slug().equals(slug) && legacyEnabled(s));
        if (current.isEmpty()) {
          break;
        }
        if (changed) {
          String nextHash = catalogs.forEndpoint(current.get()).listHash();
          if (!nextHash.equals(listHash)) {
            writeFrame(out, "event: message\ndata: {\"jsonrpc\":\"2.0\","
                + "\"method\":\"notifications/tools/list_changed\"}\n\n");
            listHash = nextHash;
          }
        } else {
          writeFrame(out, ": keepalive\n\n");
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      // A disconnected client ends this subscription without affecting other clients or configuration changes.
    }
    return null;
  }

  private static boolean legacyEnabled(EndpointSnapshot snapshot) {
    JsonNode revisions = snapshot.settings().path("protocol").path("revisions");
    if (revisions.isEmpty()) {
      return true;
    }
    for (JsonNode revision : revisions) {
      if (Era.LEGACY_VERSION.equals(revision.asString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean acceptsEvents(String accept) {
    try {
      return accept != null && MediaType.parseMediaTypes(accept).stream()
          .anyMatch(t -> t.getQualityValue() > 0 && t.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static void writeFrame(ServletOutputStream out, String frame) throws IOException {
    out.write(frame.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /**
   * Sessions and preflights don't exist here. Through the tunnel every method without a valid token is
   * 401 first, so that a client probing with GET or HEAD is sent to sign in as well (report 02 §3).
   */
  @RequestMapping(value = "/mcp/{slug}", method = {RequestMethod.DELETE, RequestMethod.OPTIONS,
      RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.HEAD})
  ResponseEntity<byte[]> notStreaming(@PathVariable String slug, HttpServletRequest http) {
    Ingress ingress = ingressOf(http);
    if (ingress == Ingress.TUNNEL && authenticate(ingress, slug, http).isEmpty()) {
      return unauthorized(ingress, slug, http);
    }
    return methodNotAllowed();
  }

  private static ResponseEntity<byte[]> methodNotAllowed() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "GET, POST").build();
  }

  private Ingress ingressOf(HttpServletRequest http) {
    return http.getLocalPort() == bootstrap.publicPort() ? Ingress.TUNNEL : Ingress.LOCAL;
  }

  private Optional<Caller> authenticate(Ingress ingress, String slug, HttpServletRequest http) {
    return bearer(http.getHeader(HttpHeaders.AUTHORIZATION)).flatMap(token -> authenticator.authenticate(
        new Authenticator.Request(ingress, slug, token, http.getRemoteAddr())));
  }

  /**
   * 401 with the challenge of the ingress: through the tunnel (and for a local endpoint that signs in with OAuth) it
   * names the protected resource metadata, so the client knows where to sign in.
   */
  private ResponseEntity<byte[]> unauthorized(Ingress ingress, String slug, HttpServletRequest http) {
    boolean presented = bearer(http.getHeader(HttpHeaders.AUTHORIZATION)).isPresent();
    ResponseEntity.BodyBuilder answer = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, authenticator.challenge(ingress, slug, presented))
        .header(HttpHeaders.CACHE_CONTROL, "no-store");
    if (ingress != Ingress.TUNNEL || "HEAD".equals(http.getMethod())) {
      return answer.build();
    }
    ObjectNode error = json.createObjectNode().put("error", "invalid_token")
        .put("error_description", presented ? "The access token is not valid for this endpoint"
            : "Sign in to use this endpoint");
    return answer.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsBytes(error));
  }

  private ResponseEntity<byte[]> write(int status, ObjectNode body) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CACHE_CONTROL, "no-store").body(json.writeValueAsBytes(body));
  }

  /** {@code Bearer <token>}; {@code ?access_token=} is never read. */
  static Optional<String> bearer(String authorization) {
    if (authorization == null || authorization.length() < 8
        || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return Optional.empty();
    }
    String token = authorization.substring(7).strip();
    return token.isEmpty() ? Optional.empty() : Optional.of(token);
  }

  private static boolean isJson(String contentType) {
    String t = contentType.toLowerCase(Locale.ROOT).strip();
    return t.equals("application/json") || t.startsWith("application/json;");
  }
}
