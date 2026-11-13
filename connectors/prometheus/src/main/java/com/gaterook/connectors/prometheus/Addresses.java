package com.gaterook.connectors.prometheus;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connectors.support.egress.AddressPolicy;
import com.gaterook.connectors.support.egress.Cidr;
import com.gaterook.connectors.support.egress.GuardedEgress;
import com.gaterook.connectors.support.egress.HostResolver;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where the server a person configured may be reached: a fixed human-configured service client with its own allowlist
 * (ARCHITECTURE.md §9), like the log backends. Metrics servers usually live on private networks, so every private
 * range stays allowed whatever the environment's HTTP policy says; link-local, cloud metadata and reserved addresses
 * and the core's own listeners on loopback never are, also when a name resolves there. Plain http goes only to
 * loopback, so a name that resolves elsewhere is refused before a request leaves in clear text.
 */
final class Addresses {

  /** Every private range of {@link AddressPolicy}. */
  private static final List<Cidr> PRIVATE = Stream.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
      "192.168.0.0/16", "100.64.0.0/10", "198.18.0.0/15", "fc00::/7", "fec0::/10", "64:ff9b:1::/48")
      .map(Cidr::parse).toList();

  private final AddressPolicy policy;
  private final HostResolver resolver;

  private Addresses(AddressPolicy policy, HostResolver resolver) {
    this.policy = policy;
    this.resolver = resolver;
  }

  /** The rules of a call; without the core's egress guard (a test), no port of the core is known. */
  static Addresses of(ExecContext ctx) {
    Optional<GuardedEgress> guard = GuardedEgress.of(ctx);
    Set<Integer> corePorts = guard.map(g -> g.policy().corePorts()).orElse(Set.of());
    return new Addresses(AddressPolicy.builder().allowPrivate(PRIVATE).corePorts(corePorts).build(),
        guard.map(GuardedEgress::resolver).orElse(HostResolver.SYSTEM));
  }

  /**
   * Judges every address of {@code uri}'s host at its port right before {@code java.net.http} sends a request there.
   * That client resolves the name again and offers no hook for the egress guard, so this judges the name's answer
   * rather than each connection; the JVM's address cache normally gives both the same answer. A name that doesn't
   * resolve is left to the client, which reports it.
   *
   * @throws ConnectorException {@code DENIED} with the reason
   */
  void check(URI uri) throws ConnectorException {
    String host = uri.getHost();
    boolean plain = "http".equalsIgnoreCase(uri.getScheme());
    int port = uri.getPort() >= 0 ? uri.getPort() : plain ? 80 : 443;
    Optional<AddressPolicy.Verdict> syntax = AddressPolicy.checkHostSyntax(host);
    if (syntax.isPresent()) {
      throw ConnectorException.denied(syntax.get().text());
    }
    List<InetAddress> all;
    try {
      Optional<InetAddress> literal = AddressPolicy.canonicalIp(host);
      all = literal.isPresent() ? List.of(literal.get()) : resolver.resolve(host);
    } catch (UnknownHostException e) {
      return;
    }
    if (all == null || all.isEmpty()) {
      return;
    }
    for (InetAddress address : all) {
      AddressPolicy.Verdict verdict = policy.check(host, address, port);
      if (!verdict.allowed()) {
        throw ConnectorException.denied(verdict.text());
      }
      if (plain && !address.isLoopbackAddress()) {
        throw ConnectorException.denied(host + " resolves to " + address.getHostAddress() + ", not to this computer: "
            + "GateRook sends plain http only to this computer. Use https://.");
      }
    }
  }
}
