package ai.msrv.gaterook.core.platform.web;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.LaunchSecret;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Deny by default on the local listener {@code 127.0.0.1:7465} (ARCHITECTURE.md §5 step 1, A21, §9 "DNS rebinding").
 * In order:
 * <ol>
 *   <li>{@code Host} must be {@code 127.0.0.1:<port>} or {@code localhost:<port>}, else 421 (DNS rebinding; the
 *       tunnel's hostname never reaches this listener legitimately).</li>
 *   <li>An {@code Origin}, when present, must be allowed (none by default), else 403, with a JSON-RPC error without
 *       {@code id} on {@code /mcp/*}.</li>
 *   <li>Only these routes exist: {@code /mcp/<slug>}, {@code GET /approvals/ap_<12>}, {@code /oauth/**} and
 *       {@code /.well-known/oauth-*} of the local authorization server, {@code /api/**} and {@code /internal/**}.
 *       Everything else, {@code /error} and Actuator included, is 404, as is any raw path with encoded dots or
 *       slashes, empty or dot segments, {@code ;} or a trailing slash.</li>
 *   <li>{@code /api/**} and {@code /internal/**}: no cross-site or same-site browser request (403), a valid
 *       single-use {@link LaunchSecret} signature (401) and a JSON body when there is one (415, multipart only for a
 *       plugin upload). Answers are {@code no-store}, {@code nosniff}, and carry {@code X-Gateway-Instance}.</li>
 * </ol>
 * Requests on the public port pass only when the tunnel's {@code PublicIngressFilter} (which runs first) accepted them;
 * any other port is refused. No CORS headers are ever sent, so a browser's preflight never succeeds.
 */
public final class LocalGuardFilter extends OncePerRequestFilter {

  /** Request attribute the public ingress filter sets on the requests it lets through on the public port. */
  public static final String PUBLIC_INGRESS_ACCEPTED = Ingress.TUNNEL_ACCEPTED_ATTRIBUTE;

  /** Response header with the launch's instance nonce, for callers who proved they are the desktop app. */
  public static final String INSTANCE_HEADER = "X-Gateway-Instance";

  private static final Logger log = LoggerFactory.getLogger(LocalGuardFilter.class);

  private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
  private static final Pattern APPROVAL_ID = Pattern.compile("ap_[a-z0-9]{12}");
  private static final Pattern FORBIDDEN_ESCAPE = Pattern.compile("%(2e|2f|5c|00|25|3b)", Pattern.CASE_INSENSITIVE);
  private static final String PLUGIN_UPLOAD = "/api/plugins/check";
  private static final byte[] ORIGIN_REJECTED =
      "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Origin not allowed\"}}"
          .getBytes(StandardCharsets.UTF_8);

  /** The kinds of requests the local listener knows. */
  enum Route { MCP, APPROVAL_PAGE, OAUTH, API, INTERNAL, NONE }

  private final Bootstrap bootstrap;
  private final AllowedOrigins origins;
  private final ReplayGuard replays;
  private final Clock clock;

  LocalGuardFilter(Bootstrap bootstrap, AllowedOrigins origins, ReplayGuard replays, Clock clock) {
    this.bootstrap = bootstrap;
    this.origins = origins;
    this.replays = replays;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    int port = request.getLocalPort();
    if (port == bootstrap.publicPort()) {
      if (Boolean.TRUE.equals(request.getAttribute(PUBLIC_INGRESS_ACCEPTED))) {
        chain.doFilter(request, response);
      } else {
        reject(response, HttpServletResponse.SC_NOT_FOUND);
      }
      return;
    }
    if (bootstrap.port() != 0 && port != bootstrap.port()) {
      // A connector nobody configured: refuse rather than guess its rules.
      reject(response, HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    if (!allowedHost(request.getHeader("Host"), port)) {
      reject(response, 421);
      return;
    }
    String uri = request.getRequestURI();
    Route route = route(request.getMethod(), uri);
    String origin = request.getHeader("Origin");
    if (origin != null && !origins.allows(origin)) {
      if (route == Route.MCP) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setContentLength(ORIGIN_REJECTED.length);
        response.getOutputStream().write(ORIGIN_REJECTED);
      } else {
        reject(response, HttpServletResponse.SC_FORBIDDEN);
      }
      return;
    }
    switch (route) {
      case NONE -> reject(response, HttpServletResponse.SC_NOT_FOUND);
      case MCP, APPROVAL_PAGE, OAUTH -> chain.doFilter(request, response);
      case API, INTERNAL -> guardAdmin(request, response, chain, uri);
    }
  }

  private void guardAdmin(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String uri)
      throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    String site = request.getHeader("Sec-Fetch-Site");
    if ("cross-site".equalsIgnoreCase(site) || "same-site".equalsIgnoreCase(site)) {
      reject(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    String target = request.getQueryString() == null ? uri : uri + "?" + request.getQueryString();
    LaunchSecret.Verification verification = bootstrap.launchSecret().verify(request.getMethod(), target,
        request.getHeader(LaunchSecret.HEADER), clock.instant());
    if (!verification.valid() || !replays.firstUse(verification.nonce(), verification.timestamp())) {
      log.debug("Refused {} {}: {}", request.getMethod(), uri,
          verification.valid() ? "nonce replayed" : verification.verdict());
      reject(response, HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    if (!acceptableBody(request, uri)) {
      reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
      return;
    }
    response.setHeader(INSTANCE_HEADER, bootstrap.instanceNonce());
    chain.doFilter(request, response);
  }

  /** Only JSON bodies (multipart for a plugin upload); requests without a body need no content type. */
  private static boolean acceptableBody(HttpServletRequest request, String uri) {
    boolean hasBody = request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    String type = request.getContentType();
    if (!hasBody) {
      return type == null || isJson(type);
    }
    if (type == null) {
      return false;
    }
    return isJson(type) || (PLUGIN_UPLOAD.equals(uri) && "POST".equals(request.getMethod())
        && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"));
  }

  private static boolean isJson(String contentType) {
    String type = contentType.toLowerCase(Locale.ROOT).strip();
    return type.equals("application/json") || type.startsWith("application/json;");
  }

  static boolean allowedHost(String host, int port) {
    if (host == null) {
      return false;
    }
    String suffix = ":" + port;
    return host.equals("127.0.0.1" + suffix) || host.equalsIgnoreCase("localhost" + suffix);
  }

  /** Classifies the raw request path; anything unexpected is {@link Route#NONE}. */
  static Route route(String method, String uri) {
    if (!wellFormed(uri)) {
      return Route.NONE;
    }
    if (uri.startsWith("/mcp/")) {
      return SLUG.matcher(uri.substring(5)).matches() ? Route.MCP : Route.NONE;
    }
    if (uri.startsWith("/approvals/")) {
      return "GET".equals(method) && APPROVAL_ID.matcher(uri.substring(11)).matches() ? Route.APPROVAL_PAGE
          : Route.NONE;
    }
    if (uri.startsWith("/oauth/") || uri.startsWith("/.well-known/oauth-")) {
      return Route.OAUTH;
    }
    if (uri.startsWith("/api/")) {
      return Route.API;
    }
    if (uri.startsWith("/internal/")) {
      return Route.INTERNAL;
    }
    return Route.NONE;
  }

  /**
   * A raw path the routes can trust: absolute, no empty, {@code .} or {@code ..} segments, no trailing slash, no
   * {@code ;} (path parameters), no backslash and no escapes of dots, slashes, backslashes, NUL, {@code ;} or
   * {@code %} itself (double encoding).
   */
  static boolean wellFormed(String uri) {
    if (uri == null || uri.length() < 2 || uri.charAt(0) != '/' || uri.endsWith("/") || uri.indexOf(';') >= 0
        || uri.indexOf('\\') >= 0 || FORBIDDEN_ESCAPE.matcher(uri).find()) {
      return false;
    }
    for (String segment : uri.substring(1).split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  private static void reject(HttpServletResponse response, int status) {
    // setStatus, not sendError: no error-page dispatch, no body that could tell an attacker more.
    response.setStatus(status);
    response.setContentLength(0);
  }
}
