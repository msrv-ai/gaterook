package ai.msrv.gaterook.pluginhost;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Every name resolution of the host process goes through here (JEP 418, registered in {@code META-INF/services}).
 * What a resolution may return is decided when the connection is made, not before: a name the egress client checked
 * can't rebind to cloud metadata, a private address or the core's ports between the check and the connect.
 *
 * <p>While requests through {@code ctx.egress()} use a name, it resolves only to what every one of them allows: one
 * host serves all the connections of its plugin, so a dev and a prod connection may use one name at once, and neither
 * lends the other its private ranges. Any other lookup — a plugin's own client — never gets metadata or link-local
 * addresses, but which private addresses it may reach can't be decided here: a lookup doesn't say which call it is for
 * (it runs on a library's thread, a pool refills between calls), address literals never reach a resolver, and the JDK
 * answers {@code localhost} itself when a resolver refuses it. The allowlist binds the client the SPI hands out
 * (report 16 §2). The core's own ports are closed to every socket of the host by the OS sandbox it starts in instead
 * ({@link SandboxExec}'s {@code --deny-tcp} on Linux, the core's {@code sandbox-exec} profile on macOS).
 */
public final class GuardedResolverProvider extends InetAddressResolverProvider {

  /** One request's claim on a name: its private ranges, and whether loopback is closed (it goes to a core port). */
  private record Grant(List<AddressRules.Cidr> allowedPrivate, boolean noLoopback) {

    boolean allows(InetAddress address) {
      return AddressRules.allowed(address, allowedPrivate) && !(noLoopback && AddressRules.loopback(address));
    }
  }

  /** The grants of the requests that use each name now (by identity: two calls may hold equal grants). */
  private static final Map<String, List<Grant>> GRANTS = new ConcurrentHashMap<>();

  /**
   * Lets {@code host} resolve to the private ranges of a request while it runs, never more than the other requests
   * on the name allow; close the returned handle when the request no longer needs the name.
   */
  static AutoCloseable grant(String host, List<AddressRules.Cidr> allowedPrivate) {
    return grant(host, allowedPrivate, false);
  }

  /** {@link #grant(String, List)}; {@code noLoopback} for a request to one of the core's ports. */
  static AutoCloseable grant(String host, List<AddressRules.Cidr> allowedPrivate, boolean noLoopback) {
    String key = key(host);
    Grant mine = new Grant(List.copyOf(allowedPrivate), noLoopback);
    GRANTS.compute(key, (k, active) -> {
      List<Grant> all = active == null ? new ArrayList<>() : new ArrayList<>(active);
      all.add(mine);
      return List.copyOf(all);
    });
    AtomicBoolean open = new AtomicBoolean(true);
    return () -> {
      if (open.compareAndSet(true, false)) {
        GRANTS.computeIfPresent(key, (k, active) -> {
          List<Grant> rest = new ArrayList<>(active);
          rest.removeIf(g -> g == mine);
          return rest.isEmpty() ? null : List.copyOf(rest);
        });
      }
    };
  }

  static boolean passes(String host, InetAddress address) {
    if (AddressRules.forbidden(address)) {
      return false;
    }
    List<Grant> active = host == null ? null : GRANTS.get(key(host));
    if (active == null) {
      return true;
    }
    for (Grant g : active) {
      if (!g.allows(address)) {
        return false;
      }
    }
    return true;
  }

  private static String key(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    while (h.endsWith(".")) {
      h = h.substring(0, h.length() - 1);
    }
    return h;
  }

  @Override
  public InetAddressResolver get(Configuration configuration) {
    return guard(configuration.builtinResolver());
  }

  /** {@code builtin} behind the guard. */
  static InetAddressResolver guard(InetAddressResolver builtin) {
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
