package ai.msrv.gaterook.core.tunnel.internal;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Deny by default on the public listener (ARCHITECTURE.md §5 step 1, §7.3), before anything else of the core sees the
 * request. It passes only while a tunnel publishes an endpoint, only for that tunnel's {@code Host}, and only these
 * raw paths: {@code /mcp/<published>}, {@code /oauth/(authorize|authorize/status|token|register|revoke)},
 * {@code /.well-known/oauth-protected-resource[/mcp/<published>]} and
 * {@code /.well-known/oauth-authorization-server}. A raw path with {@code ;}, {@code %}, {@code ..}, {@code //}, a
 * trailing slash or another letter case is 404, as is every other path ({@code /api/**}, {@code /error},
 * Actuator). Bodies are at most 64 KiB (413, a chunked one too: it is cut off while read).
 *
 * <p>Then the caller gate (Settings › Tunnel › Callers, {@link CallerGate}): server calls from outside Anthropic's
 * ranges get an empty 403, the browser pages of the sign-in never do. The caller's address is taken from the
 * provider's header only ({@code CF-Connecting-IP} behind cloudflared, the last {@code X-Forwarded-For} hop behind
 * ngrok — the one its edge appended), never from what the client could send itself through the edge, and what passes
 * carries it as {@code getRemoteAddr()} (audit, consent). What passes is marked for the local guard, which refuses
 * everything unmarked on this port.
 *
 * <p>Requests on the local listener pass untouched: the local guard answers a tunnel hostname there with 421.
 */
public final class PublicIngressFilter extends OncePerRequestFilter {

  /** Request body limit of the tunnel ingress; {@code setMaxPostSize} only limits forms. */
  static final int MAX_BODY = 64 * 1024;

  private static final Pattern OAUTH = Pattern.compile("^/oauth/(authorize|authorize/status|token|register|revoke)$");
  private static final Pattern BROWSER = Pattern.compile("^/oauth/authorize(/status)?$");
  private static final Pattern AUTHORIZATION_SERVER = Pattern.compile("^/\\.well-known/oauth-authorization-server$");

  private final Bootstrap bootstrap;
  private final TunnelPublication tunnel;
  private final CallerGate gate;

  PublicIngressFilter(Bootstrap bootstrap, TunnelPublication tunnel, CallerGate gate) {
    this.bootstrap = bootstrap;
    this.tunnel = tunnel;
    this.gate = gate;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getLocalPort() != bootstrap.publicPort()) {
      chain.doFilter(request, response);
      return;
    }
    Optional<TunnelPublication.Publication> current = tunnel.current();
    String path = request.getRequestURI();
    if (current.isEmpty() || !allowed(current.get(), request.getHeader("Host"), path)) {
      reject(response, HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    TunnelPublication.Publication publication = current.get();
    if (request.getContentLengthLong() > MAX_BODY) {
      reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      return;
    }
    String caller = callerAddress(publication.provider(), request);
    boolean browser = BROWSER.matcher(path).matches();
    if (!browser && publication.provider() != TunnelProvider.TEST
        && !gate.admits(caller, request.getHeader(CallerGate.SELFTEST_HEADER))) {
      gate.refused(caller);
      reject(response, HttpServletResponse.SC_FORBIDDEN);
      return;
    }
    request.setAttribute(Ingress.TUNNEL_ACCEPTED_ATTRIBUTE, Boolean.TRUE);
    try {
      chain.doFilter(new TunnelRequest(request, caller), response);
    } catch (IOException | ServletException | RuntimeException e) {
      // A chunked body has no length to refuse up front: it is cut off while it is read, and answered here with the
      // same empty 413, before the container's error page could make a 500 of it.
      if (!tooLarge(e) || response.isCommitted()) {
        throw e;
      }
      response.reset();
      reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      return;
    }
    if (path.startsWith("/mcp/") && response.getStatus() < 400) {
      gate.served();
    }
  }

  /**
   * The address of whoever called through the edge: what the provider's edge says, or {@code null} when its header
   * is missing, ambiguous or not an IPv4 or IPv6 literal (then the gate refuses a server call). Every line of the
   * header counts, not only the first: a client's own line must never stand for the edge's. Cloudflare sets one
   * {@code CF-Connecting-IP} (two lines: whose is which is unknown, so nobody's); ngrok appends the caller to
   * {@code X-Forwarded-For}, so it is the last hop of the last line.
   */
  static String callerAddress(TunnelProvider provider, HttpServletRequest request) {
    String value = switch (provider) {
      case CLOUDFLARE_QUICK, CLOUDFLARE_NAMED -> only(request.getHeaders("CF-Connecting-IP"));
      case NGROK -> lastHop(last(request.getHeaders("X-Forwarded-For")));
      case TEST -> request.getHeader("CF-Connecting-IP") != null ? request.getHeader("CF-Connecting-IP")
          : request.getRemoteAddr();
    };
    return literal(value);
  }

  /** The header's single line, or {@code null} for none or several. */
  private static String only(Enumeration<String> lines) {
    if (lines == null || !lines.hasMoreElements()) {
      return null;
    }
    String first = lines.nextElement();
    return lines.hasMoreElements() ? null : first;
  }

  /** The header's last line, or {@code null}. */
  private static String last(Enumeration<String> lines) {
    String last = null;
    while (lines != null && lines.hasMoreElements()) {
      last = lines.nextElement();
    }
    return last;
  }

  private static String lastHop(String forwardedFor) {
    if (forwardedFor == null) {
      return null;
    }
    int comma = forwardedFor.lastIndexOf(',');
    return forwardedFor.substring(comma + 1).strip();
  }

  /** An IP literal as the header carried it, or {@code null}: names are never resolved here. */
  static String literal(String value) {
    if (value == null) {
      return null;
    }
    String s = value.strip();
    if (s.isEmpty() || s.length() > 45 || !s.matches("[0-9A-Fa-f:.]+")) {
      return null;
    }
    try {
      return java.net.InetAddress.ofLiteral(s).getHostAddress();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static boolean tooLarge(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof BodyTooLargeException) {
        return true;
      }
    }
    return false;
  }

  static boolean allowed(TunnelPublication.Publication publication, String host, String rawPath) {
    if (host == null || !publication.host().equals(hostName(host)) || rawPath == null) {
      return false;
    }
    if (rawPath.contains(";") || rawPath.contains("%") || rawPath.contains("..") || rawPath.contains("//")
        || rawPath.length() > 1 && rawPath.endsWith("/") || !rawPath.equals(rawPath.toLowerCase(Locale.ROOT))) {
      return false;
    }
    String mcp = "/mcp/" + publication.endpointSlug();
    return rawPath.equals(mcp) || OAUTH.matcher(rawPath).matches()
        || AUTHORIZATION_SERVER.matcher(rawPath).matches()
        || rawPath.equals("/.well-known/oauth-protected-resource")
        || rawPath.equals("/.well-known/oauth-protected-resource" + mcp);
  }

  /** {@code Host} without the port cloudflared or ngrok may add; any other form doesn't match. */
  private static String hostName(String host) {
    int colon = host.lastIndexOf(':');
    String name = colon > 0 && host.indexOf(']') < colon ? host.substring(0, colon) : host;
    return name.toLowerCase(Locale.ROOT);
  }

  private static void reject(HttpServletResponse response, int status) {
    response.setStatus(status);
    response.setContentLength(0);
  }

  /** What reading past {@link #MAX_BODY} throws; the filter answers it with 413. */
  static final class BodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    BodyTooLargeException() {
      super("The request body is larger than " + MAX_BODY + " bytes");
    }
  }

  /**
   * The request as the rest of the core sees it: the caller's address is the edge's ({@code getRemoteAddr()}, so
   * Activity and the consent dialog show who called, not cloudflared), and the body is cut off at
   * {@link #MAX_BODY} (a chunked body has no length to check up front).
   */
  private static final class TunnelRequest extends HttpServletRequestWrapper {

    private final String caller;

    TunnelRequest(HttpServletRequest request, String caller) {
      super(request);
      this.caller = caller;
    }

    @Override
    public String getRemoteAddr() {
      return caller != null ? caller : super.getRemoteAddr();
    }

    @Override
    public String getRemoteHost() {
      return getRemoteAddr();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      ServletInputStream in = super.getInputStream();
      return new ServletInputStream() {
        private long read;

        @Override
        public int read() throws IOException {
          int b = in.read();
          if (b >= 0) {
            count(1);
          }
          return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
          int n = in.read(buffer, offset, length);
          if (n > 0) {
            count(n);
          }
          return n;
        }

        private void count(int n) throws IOException {
          read += n;
          if (read > MAX_BODY) {
            throw new BodyTooLargeException();
          }
        }

        @Override
        public boolean isFinished() {
          return in.isFinished();
        }

        @Override
        public boolean isReady() {
          return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
          in.setReadListener(listener);
        }
      };
    }
  }
}
