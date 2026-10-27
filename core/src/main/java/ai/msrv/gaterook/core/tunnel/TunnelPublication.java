package ai.msrv.gaterook.core.tunnel;

import ai.msrv.gaterook.core.platform.BuildFlags;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What the public listener ({@code 127.0.0.1:7466}) serves right now: the tunnel's hostname, the one published
 * endpoint (ARCHITECTURE.md §7.3, MVP: one endpoint) and the provider that carries the traffic (it decides which
 * header names the caller's address). Nothing while the tunnel is off, and then the listener answers 404 to
 * everything. {@link TunnelService} sets and clears it.
 *
 * <p>Tests of a non-release build can pretend a tunnel is on with {@code gateway.test.tunnel-host} and
 * {@code gateway.test.tunnel-endpoint} (the native smoke checks the listener's allowlist that way, M1.7): such a
 * publication has provider {@link TunnelProvider#TEST}, no process behind it and no caller-IP gate, since no edge
 * stands in front of the listener. A release ignores both, like the other test overrides of {@link BuildFlags}.
 */
@Component
public class TunnelPublication {

  private static final Logger log = LoggerFactory.getLogger(TunnelPublication.class);
  private static final Pattern HOST = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}"
      + "[a-z0-9])?)+$");
  private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

  /** The public hostname (lower case), the slug of the endpoint behind {@code /mcp/<slug>} and who carries it. */
  public record Publication(String host, String endpointSlug, TunnelProvider provider) {
    public Publication {
      host = host.toLowerCase(Locale.ROOT);
      if (!HOST.matcher(host).matches()) {
        throw new IllegalArgumentException("Not a public host name: " + host);
      }
      if (!SLUG.matcher(endpointSlug).matches()) {
        throw new IllegalArgumentException("Not an endpoint slug: " + endpointSlug);
      }
      Objects.requireNonNull(provider, "provider");
    }

    /** A publication of the test override (no process, no edge in front). */
    public Publication(String host, String endpointSlug) {
      this(host, endpointSlug, TunnelProvider.TEST);
    }

    /** {@code https://<host>}: the issuer of the tunnel's authorization server and the resource's origin. */
    public String origin() {
      return "https://" + host;
    }

    /** {@code https://<host>/mcp/<slug>}: the RFC 8707 resource OAuth tokens of the tunnel are bound to. */
    public String resource() {
      return origin() + "/mcp/" + endpointSlug;
    }
  }

  /** Whether {@code host} is a well-formed public DNS name as a publication accepts it. */
  public static boolean validHost(String host) {
    return host != null && HOST.matcher(host.toLowerCase(Locale.ROOT)).matches();
  }

  private volatile Publication current;

  public TunnelPublication(@Value("${gateway.test.tunnel-host:}") String testHost,
      @Value("${gateway.test.tunnel-endpoint:}") String testEndpoint) {
    if (!testHost.isBlank() && !testEndpoint.isBlank()) {
      if (BuildFlags.RELEASE) {
        log.warn("gateway.test.tunnel-host is ignored: this is a release build");
      } else {
        current = new Publication(testHost, testEndpoint);
        log.warn("Test override: the public listener serves /mcp/{} for {} as if a tunnel published it",
            testEndpoint, current.host());
      }
    }
  }

  public Optional<Publication> current() {
    return Optional.ofNullable(current);
  }

  /** Whether the test override (not a tunnel) publishes right now. */
  public boolean overridden() {
    Publication p = current;
    return p != null && p.provider() == TunnelProvider.TEST;
  }

  /** A tunnel came up with {@code publication}. */
  public void publish(Publication publication) {
    current = Objects.requireNonNull(publication, "publication");
  }

  /** The tunnel stopped: the public listener serves nothing. */
  public void clear() {
    current = null;
  }
}
