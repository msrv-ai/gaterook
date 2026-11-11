package ai.msrv.gaterook.connectors.influxdb;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.Cidr;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where the InfluxDB server a person configured may be reached: a fixed human-configured service client
 * (ARCHITECTURE.md §9). Such servers usually live on private networks, so every private range stays allowed;
 * link-local, cloud metadata and reserved addresses and the core's own listeners on loopback never are, also when a
 * name resolves there. Plain http goes only to this computer: every address the name resolves to must be loopback.
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
   * Judges every address of {@code uri}'s host at its port, right before {@code java.net.http} sends a request there.
   * That client resolves the name again and offers no hook for the guard, so this judges the name's answer rather than
   * each connection; the JVM's address cache normally gives both the same answer. A name that doesn't resolve is left
   * to the client, which reports it.
   *
   * @throws ConnectorException {@code DENIED} with the reason
   */
  void check(URI uri) throws ConnectorException {
    String host = uri.getHost();
    boolean plain = "http".equalsIgnoreCase(uri.getScheme());
    int port = uri.getPort() >= 0 ? uri.getPort() : plain ? 80 : 443;
    Optional<AddressPolicy.Verdict> syntax = AddressPolicy.checkHostSyntax(host);
    if (syntax.isPresent()) {
      throw ConnectorException.denied(sentence(syntax.get().text()));
    }
    Optional<InetAddress> literal = AddressPolicy.canonicalIp(host);
    List<InetAddress> all;
    try {
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
        throw ConnectorException.denied(sentence(verdict.text()));
      }
      if (plain && !address.isLoopbackAddress()) {
        throw ConnectorException.denied(host + " resolves to " + address.getHostAddress() + ", which isn't this "
            + "computer: plain http:// goes only to this computer. Use https://.");
      }
    }
  }

  /** {@code text} ending in a full stop. */
  static String sentence(String text) {
    return text.endsWith(".") ? text : text + ".";
  }
}
