package ai.msrv.gaterook.core.oauth.internal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The HTTP face of both authorization servers (report 02 §7.1): metadata under {@code /.well-known/}, then
 * {@code /oauth/authorize}, {@code /oauth/authorize/status}, {@code /oauth/token}, {@code /oauth/register},
 * {@code /oauth/revoke}. The listener decides which server answers ({@link Issuers}); on the public port the tunnel's
 * ingress filter let only these paths of the published endpoint through. Nothing here is under {@code /api}: the
 * person's decision goes through the admin API only.
 *
 * <p>Every answer is {@code no-store}; the browser pages carry their CSP, {@code X-Frame-Options: DENY} and
 * {@code Referrer-Policy: no-referrer}. {@code ?access_token=} is never read anywhere.
 */
@RestController
class AuthorizationEndpoints {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final OAuthServer server;

  AuthorizationEndpoints(OAuthServer server) {
    this.server = server;
  }

  // ── Metadata ────────────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/.well-known/oauth-protected-resource/mcp/{slug}")
  ResponseEntity<byte[]> protectedResource(@PathVariable String slug, HttpServletRequest request) {
    return server.issuers().of(request).flatMap(s -> server.protectedResource(s, slug))
        .map(AuthorizationEndpoints::json).orElseGet(AuthorizationEndpoints::notFound);
  }

  /** The root form: the tunnel's one published endpoint (MCP 2026-07-28 tries it after the path form). */
  @GetMapping("/.well-known/oauth-protected-resource")
  ResponseEntity<byte[]> protectedResourceRoot(HttpServletRequest request) {
    return server.issuers().of(request).filter(Issuers.Server::tunnel)
        .flatMap(s -> server.protectedResource(s, s.publication().endpointSlug()))
        .map(AuthorizationEndpoints::json).orElseGet(AuthorizationEndpoints::notFound);
  }

  @GetMapping("/.well-known/oauth-authorization-server")
  ResponseEntity<byte[]> authorizationServer(HttpServletRequest request) {
    return server.issuers().of(request).map(server::authorizationServer).map(AuthorizationEndpoints::json)
        .orElseGet(AuthorizationEndpoints::notFound);
  }

  // ── Authorize ───────────────────────────────────────────────────────────────────────────────────────────────

  @GetMapping("/oauth/authorize")
  ResponseEntity<byte[]> authorize(HttpServletRequest request) throws InterruptedException {
    Optional<Issuers.Server> s = server.issuers().of(request);
    if (s.isEmpty()) {
      return notFound();
    }
    if (request.getParameter("request_uri") != null || request.getParameter("request") != null) {
      return page(400, ConsentPages.error("This sign-in can't continue", "Request objects aren't supported here."),
          null, s.get());
    }
    Map<String, String> params = new HashMap<>();
    for (String name : List.of("response_type", "client_id", "redirect_uri", "code_challenge",
        "code_challenge_method", "state", "scope", "resource")) {
      String[] values = request.getParameterValues(name);
      if (values != null && values.length > 1) {
        // A repeated parameter (two resources, two redirects) is ambiguous: refuse it like an unknown client.
        return page(400, ConsentPages.error("This sign-in can't continue", "The request repeats " + name + "."),
            null, s.get());
      }
      if (values != null) {
        params.put(name, values[0]);
      }
    }
    OAuthServer.Answer answer = server.authorize(s.get(), params, request.getRemoteAddr());
    if (answer.location() != null) {
      return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, answer.location())
          .cacheControl(CacheControl.noStore()).build();
    }
    return page(answer.status(), answer.html(), answer.cookie(), s.get());
  }

  @GetMapping("/oauth/authorize/status")
  ResponseEntity<byte[]> status(@RequestParam(name = "rid", required = false) String rid,
      HttpServletRequest request) {
    Optional<Issuers.Server> s = server.issuers().of(request);
    if (s.isEmpty()) {
      return notFound();
    }
    String cookie = null;
    if (request.getCookies() != null) {
      for (Cookie c : request.getCookies()) {
        if (OAuthServer.COOKIE.equals(c.getName())) {
          cookie = c.getValue();
        }
      }
    }
    return server.status(s.get(), rid, cookie).map(AuthorizationEndpoints::json)
        .orElseGet(AuthorizationEndpoints::notFound);
  }

  // ── Token, register, revoke ─────────────────────────────────────────────────────────────────────────────────

  @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  ResponseEntity<byte[]> token(@RequestParam MultiValueMap<String, String> form, HttpServletRequest request) {
    Optional<Issuers.Server> s = server.issuers().of(request);
    if (s.isEmpty()) {
      return notFound();
    }
    if (request.getQueryString() != null) {
      return inQuery();
    }
    Map<String, String> params = new HashMap<>();
    for (Map.Entry<String, List<String>> e : form.entrySet()) {
      if (e.getValue().size() > 1) {
        return json(400, JsonNodeFactory.instance.objectNode().put("error", "invalid_request")
            .put("error_description", "The request repeats " + e.getKey() + "."));
      }
      params.put(e.getKey(), e.getValue().getFirst());
    }
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    boolean basic = authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    OAuthServer.TokenAnswer answer = server.token(s.get(), params, basic);
    ResponseEntity.BodyBuilder b = ResponseEntity.status(answer.status()).cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache").contentType(MediaType.APPLICATION_JSON);
    if (answer.status() == 401) {
      b.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"gaterook\"");
    }
    return b.body(JSON.writeValueAsBytes(answer.body()));
  }

  @PostMapping(value = "/oauth/register")
  ResponseEntity<byte[]> register(HttpServletRequest request) throws java.io.IOException {
    Optional<Issuers.Server> s = server.issuers().of(request);
    if (s.isEmpty() || !server.dcrEnabled(s.get())) {
      return notFound();
    }
    String type = request.getContentType();
    if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
      return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).cacheControl(CacheControl.noStore()).build();
    }
    byte[] body = request.getInputStream().readNBytes(16 * 1024 + 1);
    JsonNode tree;
    try {
      tree = body.length > 16 * 1024 ? null : JSON.readTree(body);
    } catch (JacksonException e) {
      tree = null;
    }
    Clients.Registration r = server.register(s.get(), tree, request.getRemoteAddr());
    if (r == null) {
      return notFound();
    }
    return json(r.status(), r.body());
  }

  /** RFC 7009: 200 whatever the token was, so a caller learns nothing from it. */
  @PostMapping(value = "/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  ResponseEntity<byte[]> revoke(@RequestParam MultiValueMap<String, String> form, HttpServletRequest request) {
    Optional<Issuers.Server> s = server.issuers().of(request);
    if (s.isEmpty()) {
      return notFound();
    }
    if (request.getQueryString() != null) {
      return inQuery();
    }
    server.revoke(s.get(), form.getFirst("token"), form.getFirst("client_id"));
    return json(200, JsonNodeFactory.instance.objectNode());
  }

  /**
   * Codes, verifiers and tokens travel in the form body only (OAuth 2.1 §3.2): Spring would read them from the query
   * as well, and a URL ends up in the logs of every proxy and edge on the way.
   */
  private static ResponseEntity<byte[]> inQuery() {
    return json(400, JsonNodeFactory.instance.objectNode().put("error", "invalid_request")
        .put("error_description", "Send the parameters in the request body, not in the URL."));
  }

  // ── Answers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static ResponseEntity<byte[]> page(int status, String html, String cookie, Issuers.Server server) {
    ResponseEntity.BodyBuilder b = ResponseEntity.status(status).cacheControl(CacheControl.noStore())
        .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
        .header("Content-Security-Policy", ConsentPages.CSP).header("X-Frame-Options", "DENY")
        .header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff");
    if (cookie != null) {
      // The tunnel is https (Secure); the local server is plain loopback http, where browsers keep no Secure cookie.
      b.header(HttpHeaders.SET_COOKIE, ResponseCookie.from(OAuthServer.COOKIE, cookie).httpOnly(true)
          .secure(server.tunnel()).sameSite("Lax").path("/oauth/authorize")
          .maxAge(Consents.TIMEOUT.plusSeconds(30)).build().toString());
    }
    return b.body(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static ResponseEntity<byte[]> json(JsonNode body) {
    return json(200, body);
  }

  private static ResponseEntity<byte[]> json(int status, JsonNode body) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
        .body(JSON.writeValueAsBytes(body));
  }

  private static ResponseEntity<byte[]> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
  }
}
