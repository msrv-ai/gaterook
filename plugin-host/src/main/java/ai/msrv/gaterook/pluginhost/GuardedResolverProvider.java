package ai.msrv.gaterook.pluginhost;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Every name resolution of the host process goes through here (JEP 418, registered in {@code META-INF/services}).
 * What a resolution may return is decided when the connection is made, not before: a name the egress client checked
 * can't rebind to cloud metadata or a private address between the check and the connect. Names a call is using
 * through {@code ctx.egress()} carry that call's private ranges; every other lookup — a plugin's own HTTP client
 * included — still never gets metadata or link-local addresses.
 */
public final class GuardedResolverProvider extends InetAddressResolverProvider {

  private record Grant(List<AddressRules.Cidr> allowedPrivate, AtomicInteger uses) {}

  private static final Map<String, Grant> GRANTS = new ConcurrentHashMap<>();

  /**
   * Lets {@code host} resolve to the private ranges of a call while it runs; close the returned handle when the call
   * no longer needs the name. Grants of concurrent calls on the same name must agree (one connection, one policy).
   */
  static AutoCloseable grant(String host, List<AddressRules.Cidr> allowedPrivate) {
    String key = host.toLowerCase(Locale.ROOT);
    Grant g = GRANTS.compute(key, (k, existing) -> {
      if (existing == null) {
        return new Grant(List.copyOf(allowedPrivate), new AtomicInteger(1));
      }
      existing.uses().incrementAndGet();
      return existing;
    });
    return () -> GRANTS.computeIfPresent(key, (k, existing) -> existing.uses().decrementAndGet() <= 0 ? null : existing);
  }

  static boolean passes(String host, InetAddress address) {
    if (AddressRules.forbidden(address)) {
      return false;
    }
    Grant g = host == null ? null : GRANTS.get(host.toLowerCase(Locale.ROOT));
    return g == null || AddressRules.allowed(address, g.allowedPrivate());
  }

  @Override
  public InetAddressResolver get(Configuration configuration) {
    InetAddressResolver builtin = configuration.builtinResolver();
    return new InetAddressResolver() {
      @Override
      public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
        if (AddressRules.metadataName(host)) {
          throw new UnknownHostException(host + ": cloud metadata is never reachable from a plugin");
        }
        List<InetAddress> found = builtin.lookupByName(host, policy).filter(a -> passes(host, a)).toList();
        if (found.isEmpty()) {
          throw new UnknownHostException(host + ": no address this plugin may reach");
        }
        return found.stream();
      }

      @Override
      public String lookupByAddress(byte[] addr) throws UnknownHostException {
        return builtin.lookupByAddress(addr);
      }
    };
  }

  @Override
  public String name() {
    return "gaterook-plugin-guard";
  }
}
