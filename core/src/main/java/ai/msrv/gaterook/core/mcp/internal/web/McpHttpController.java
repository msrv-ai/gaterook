package ai.msrv.gaterook.core.mcp.internal.web;

import ai.msrv.gaterook.core.mcp.McpGateway;
import ai.msrv.gaterook.core.mcp.internal.JsonRpc;
import ai.msrv.gaterook.core.platform.Authenticator;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CallerAuthenticator;
import ai.msrv.gaterook.core.platform.Ingress;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * with {@code application/json} — no SSE, no sessions ({@code Mcp-Session-Id} and {@code Last-Event-ID} are ignored
 * and never issued). Before any JSON-RPC: only {@code application/json} (415, which also keeps cross-site forms out),
 * a body of at most 1 MiB (413), a bearer token of this endpoint and ingress (401 with
 * {@code WWW-Authenticate: Bearer realm="mcp-gateway"}), then one object (a batch is {@code -32600}). Other methods
 * are 405 with {@code Allow: POST}. Host and Origin were checked by {@code LocalGuardFilter} already.
 */
@RestController
class McpHttpController {

  static final int MAX_BODY = 1 << 20;

  private final McpGateway gateway;
  private final CallerAuthenticator authenticator;
  private final Bootstrap bootstrap;
  private final JsonMapper json;

  McpHttpController(McpGateway gateway, CallerAuthenticator authenticator, Bootstrap bootstrap, JsonMapper json) {
    this.gateway = gateway;
    this.authenticator = authenticator;
    this.bootstrap = bootstrap;
    this.json = json;
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
   * SSE streams, sessions and preflights don't exist here. Through the tunnel every method without a valid token is
   * 401 first, so that a client probing with GET or HEAD is sent to sign in as well (report 02 §3).
   */
  @RequestMapping(value = "/mcp/{slug}", method = {RequestMethod.GET, RequestMethod.DELETE, RequestMethod.OPTIONS,
      RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.HEAD})
  ResponseEntity<byte[]> notStreaming(@PathVariable String slug, HttpServletRequest http) {
    Ingress ingress = ingressOf(http);
    if (ingress == Ingress.TUNNEL && authenticate(ingress, slug, http).isEmpty()) {
      return unauthorized(ingress, slug, http);
    }
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
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
