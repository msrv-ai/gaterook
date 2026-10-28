package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.Cidr;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Where a store may be reached (ARCHITECTURE.md §9 "SSRF": the person typed the address, so it is checked before any
 * resolution, not hop by hop like the egress guard). Vault usually lives on a private network, so private addresses
 * are fine; cloud metadata, link-local and the core's own ports never are, and a number in a non-standard form is
 * refused rather than interpreted. Plain {@code http://} to another computer requires explicit consent because
 * tokens and secrets cross the network unencrypted.
 */
public final class StoreAddress {

  private static final List<String> PRIVATE = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
      "192.168.0.0/16", "100.64.0.0/10", "198.18.0.0/15", "fc00::/7", "fec0::/10");

  private StoreAddress() {}

  /**
   * The address as the store client uses it: scheme, host and port only (a path prefix is kept, a query, fragment or
   * user info is refused).
   *
   * @param corePorts the core's listeners: a store on 127.0.0.1 may not be the core itself
   * @throws StoreException {@code INVALID} with what to change
   */
  public static URI check(String text, Set<Integer> corePorts) {
    return check(text, corePorts, false);
  }

  /**
   * Checks an address with the store's persisted transport consent.
   *
   * @param allowPlainHttp whether the user accepted sending tokens and secrets over unencrypted HTTP
   */
  public static URI check(String text, Set<Integer> corePorts, boolean allowPlainHttp) {
    URI uri;
    try {
      uri = URI.create(text == null ? "" : text.strip());
    } catch (IllegalArgumentException e) {
      throw StoreException.invalid("The address isn't a URL, like https://vault.acme.internal.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http")) {
      throw StoreException.invalid("The address starts with https:// or http://, like https://vault.acme.internal.");
    }
    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw StoreException.invalid("Write the address without a user, query or fragment.");
    }
    String host = uri.getHost();
    Optional<AddressPolicy.Verdict> syntax = AddressPolicy.checkHostSyntax(host);
    if (syntax.isPresent()) {
      throw StoreException.invalid(syntax.get().text());
    }
    int port = uri.getPort() > 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
    Optional<InetAddress> literal = AddressPolicy.canonicalIp(host);
    boolean loopback = literal.map(InetAddress::isLoopbackAddress).orElse(host.equalsIgnoreCase("localhost"));
    if (literal.isPresent()) {
      AddressPolicy policy = AddressPolicy.builder().allowPrivate(PRIVATE.stream().map(Cidr::parse).toList())
          .corePorts(corePorts).build();
      AddressPolicy.Verdict v = policy.check(host, literal.get(), port);
      if (!v.allowed()) {
        throw StoreException.invalid(v.text());
      }
    } else if (loopback && corePorts.contains(port)) {
      throw StoreException.invalid("Port " + port + " on this computer is GateRook itself.");
    }
    if (scheme.equals("http") && !loopback && !allowPlainHttp) {
      throw StoreException.invalid("Use https:// or confirm the risk of HTTP: tokens and secrets cross the network "
          + "unencrypted.");
    }
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    if (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (path.contains("..") || path.contains("%") || path.contains("//")) {
      throw StoreException.invalid("Write the address without escapes or dot segments.");
    }
    return URI.create(scheme + "://" + (host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host)
        + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + path);
  }
}
