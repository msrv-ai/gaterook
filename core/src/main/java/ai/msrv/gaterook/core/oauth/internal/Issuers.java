package ai.msrv.gaterook.core.oauth.internal;

import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Which of the two authorization servers a request talks to (ARCHITECTURE.md §7.3), decided by the listener it came
 * in on, never by anything the client says:
 * <ul>
 *   <li>the public port: the tunnel's server, issuer {@code https://<tunnel host>} (no path), resource
 *       {@code https://<host>/mcp/<published>} — only while the tunnel publishes;</li>
 *   <li>the local port: the local server for Cursor and Antigravity, issuer {@code http://127.0.0.1:<port>} (or
 *       {@code localhost}, as the client addressed it: RFC 8414 clients compare the issuer with the URL they fetched),
 *       resource {@code http://127.0.0.1:<port>/mcp/<slug>} in canonical form whichever name the client used.</li>
 * </ul>
 */
@Component
public class Issuers {

  private static final Pattern LOCAL_RESOURCE = Pattern.compile(
      "^http://(?:127\\.0\\.0\\.1|localhost):(\\d{1,5})/mcp/([a-z0-9][a-z0-9-]{0,62})$");

  /** One server as a request sees it. */
  public record Server(Ingress ingress, String issuer, TunnelPublication.Publication publication) {

    public boolean tunnel() {
      return ingress == Ingress.TUNNEL;
    }
  }

  private final Bootstrap bootstrap;
  private final LocalListener listener;
  private final TunnelPublication tunnel;

  public Issuers(Bootstrap bootstrap, LocalListener listener, TunnelPublication tunnel) {
    this.bootstrap = bootstrap;
    this.listener = listener;
    this.tunnel = tunnel;
  }

  /** The server of {@code request}, or empty (the public port with no tunnel: the ingress filter said 404 anyway). */
  public Optional<Server> of(HttpServletRequest request) {
    if (request.getLocalPort() == bootstrap.publicPort()) {
      return tunnel.current().map(p -> new Server(Ingress.TUNNEL, p.origin(), p));
    }
    String host = request.getHeader("Host");
    if (host == null) {
      return Optional.empty();
    }
    String h = host.toLowerCase(Locale.ROOT);
    int port = listener.port();
    if (!h.equals("127.0.0.1:" + port) && !h.equals("localhost:" + port)) {
      return Optional.empty();
    }
    return Optional.of(new Server(Ingress.LOCAL, "http://" + h, null));
  }

  /** The canonical resource of a local endpoint: {@code http://127.0.0.1:<port>/mcp/<slug>}. */
  public String localResource(String slug) {
    return "http://127.0.0.1:" + listener.port() + "/mcp/" + slug;
  }

  /** The resource of {@code slug} as {@code server}'s clients name it in metadata. */
  public String resourceFor(Server server, String slug) {
    return server.tunnel() ? server.publication().origin() + "/mcp/" + slug : server.issuer() + "/mcp/" + slug;
  }

  /**
   * The endpoint a {@code resource} indicator names on {@code server}, after canonicalisation: on the tunnel only the
   * published endpoint's URL, locally {@code http://127.0.0.1|localhost:<this port>/mcp/<slug>}.
   */
  public Optional<String> slugOf(Server server, String resource) {
    Optional<String> canonical = OAuthUris.canonicalResource(resource);
    if (canonical.isEmpty()) {
      return Optional.empty();
    }
    if (server.tunnel()) {
      return server.publication().resource().equals(canonical.get())
          ? Optional.of(server.publication().endpointSlug()) : Optional.empty();
    }
    Matcher m = LOCAL_RESOURCE.matcher(canonical.get());
    if (!m.matches() || Integer.parseInt(m.group(1)) != listener.port()) {
      return Optional.empty();
    }
    return Optional.of(m.group(2));
  }

  /** The stored form of a grant's resource for {@code slug} on {@code server}. */
  public String storedResource(Server server, String slug) {
    return server.tunnel() ? server.publication().resource() : localResource(slug);
  }
}
