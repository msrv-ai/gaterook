package ai.msrv.gaterook.connectors.support.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import okhttp3.Dns;

/**
 * The first layer of the egress guard (report 10 §1, §7.2): resolves a name, judges <em>every</em> address it
 * returns, and hands OkHttp exactly the judged list. A name that mixes allowed and private addresses is refused as a
 * whole, so the error is clear before any connection is tried.
 *
 * <p>Not enough on its own: OkHttp never asks {@code Dns} about IP literals ({@code http://2130706433/},
 * {@code [::ffff:7f00:2]}), and a resolver may answer differently later. {@link GuardSocketFactory} is the
 * authoritative check.
 */
public final class GuardDns implements Dns {

  private final HostResolver resolver;
  private final AddressPolicy policy;

  public GuardDns(HostResolver resolver, AddressPolicy policy) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  @Override
  public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    var syntax = AddressPolicy.checkHostSyntax(hostname);
    if (syntax.isPresent()) {
      throw new HostDeniedException(hostname, syntax.get());
    }
    List<InetAddress> all = resolver.resolve(hostname);
    if (all == null || all.isEmpty()) {
      throw new UnknownHostException(hostname + " doesn't resolve");
    }
    for (InetAddress address : all) {
      AddressPolicy.Verdict verdict = policy.check(hostname, address, -1);
      if (!verdict.allowed()) {
        throw new HostDeniedException(hostname, verdict);
      }
    }
    return List.copyOf(all);
  }
}
