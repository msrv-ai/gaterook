package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.Cidr;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.HostDeniedException;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.hc.client5.http.DnsResolver;

/**
 * Where an HTTP backend a person configured (Loki, OpenSearch, Elasticsearch) may be reached: a fixed
 * human-configured service client with its own allowlist (ARCHITECTURE.md §9). Such backends usually live on private
 * networks, so every private range stays allowed whatever the environment's HTTP policy says; link-local, cloud
 * metadata and reserved addresses and the core's own listeners on loopback never are, also when a name resolves
 * there. The core's ports and its resolver are those of the call's egress guard.
 */
final class BackendAddresses {

  /** Every private range of {@link AddressPolicy}. */
  private static final List<Cidr> PRIVATE = Stream.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
      "192.168.0.0/16", "100.64.0.0/10", "198.18.0.0/15", "fc00::/7", "fec0::/10", "64:ff9b:1::/48")
      .map(Cidr::parse).toList();

  private final AddressPolicy policy;
  private final HostResolver resolver;

  private BackendAddresses(AddressPolicy policy, HostResolver resolver) {
    this.policy = policy;
    this.resolver = resolver;
  }

  /** The rules of a call; without the core's egress guard (a test), no port of the core is known. */
  static BackendAddresses of(ExecContext ctx) {
    Optional<GuardedEgress> guard = GuardedEgress.of(ctx);
    Set<Integer> corePorts = guard.map(g -> g.policy().corePorts()).orElse(Set.of());
    return new BackendAddresses(AddressPolicy.builder().allowPrivate(PRIVATE).corePorts(corePorts).build(),
        guard.map(GuardedEgress::resolver).orElse(HostResolver.SYSTEM));
  }

  /**
   * Judges every address of {@code uri}'s host at its port, right before {@code java.net.http} sends a request there.
   * That client resolves the name again and offers no hook for the egress guard, so this judges the name's answer
   * rather than each connection; the JVM's address cache normally gives both the same answer. A name that doesn't
   * resolve is left to the client, which reports it as before.
   *
   * @throws ConnectorException {@code DENIED} with the reason
   */
  void check(URI uri) throws ConnectorException {
    String host = uri.getHost();
    int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    try {
      judged(host, port);
    } catch (HostDeniedException e) {
      throw ConnectorException.denied(e.getMessage());
    } catch (UnknownHostException e) {
      // The client fails to resolve it too.
    }
  }

  /**
   * Name resolution of the Apache HttpClient transport: every address judged at the request's port, so the client
   * connects only to addresses the rules allow. Refusals are {@link HostDeniedException}s.
   */
  DnsResolver dns() {
    return new DnsResolver() {
      @Override
      public InetAddress[] resolve(String host) throws UnknownHostException {
        return judged(host, -1).toArray(InetAddress[]::new);
      }

      @Override
      public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        return judged(host, port).stream().map(a -> new InetSocketAddress(a, port)).toList();
      }

      @Override
      public String resolveCanonicalHostname(String host) {
        return host;
      }
    };
  }

  /** Every address of {@code host}, each allowed at {@code port} ({@code -1}: any port); one refused refuses all. */
  private List<InetAddress> judged(String host, int port) throws UnknownHostException {
    Optional<AddressPolicy.Verdict> syntax = AddressPolicy.checkHostSyntax(host);
    if (syntax.isPresent()) {
      throw new HostDeniedException(host, syntax.get());
    }
    Optional<InetAddress> literal = AddressPolicy.canonicalIp(host);
    List<InetAddress> all = literal.isPresent() ? List.of(literal.get()) : resolver.resolve(host);
    if (all == null || all.isEmpty()) {
      throw new UnknownHostException(host + " doesn't resolve");
    }
    for (InetAddress address : all) {
      AddressPolicy.Verdict verdict = policy.check(host, address, port);
      if (!verdict.allowed()) {
        throw new HostDeniedException(host, verdict);
      }
    }
    return List.copyOf(all);
  }
}
