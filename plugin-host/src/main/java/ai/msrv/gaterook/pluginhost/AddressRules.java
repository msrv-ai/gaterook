package ai.msrv.gaterook.pluginhost;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which addresses a plugin may reach (ARCHITECTURE.md §9 SSRF, the host's side of it): never link-local and cloud
 * metadata (169.254.0.0/16, fe80::/10, fd00:ec2::/32, fd20:ce::254), "this network" or multicast; private and
 * loopback ranges only where the environment's policy allows them for the connection. The same rules as the core's
 * {@code AddressPolicy}, written again because the host sees none of the core's classes.
 */
final class AddressRules {

  /** A CIDR block. */
  record Cidr(byte[] network, int prefix) {

    static Cidr parse(String text) {
      String s = text.strip();
      int slash = s.indexOf('/');
      try {
        InetAddress a = InetAddress.ofLiteral(slash < 0 ? s : s.substring(0, slash));
        byte[] bytes = a.getAddress();
        int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(s.substring(slash + 1));
        if (prefix < 0 || prefix > bytes.length * 8) {
          throw new IllegalArgumentException("Bad prefix in " + text);
        }
        return new Cidr(bytes, prefix);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Not a CIDR block: " + text, e);
      }
    }

    boolean contains(InetAddress address) {
      byte[] a = unmapped(address).getAddress();
      if (a.length != network.length) {
        return false;
      }
      int full = prefix / 8;
      for (int i = 0; i < full; i++) {
        if (a[i] != network[i]) {
          return false;
        }
      }
      int rest = prefix % 8;
      if (rest == 0) {
        return true;
      }
      int mask = (0xff << (8 - rest)) & 0xff;
      return (a[full] & mask) == (network[full] & mask);
    }
  }

  static final List<Cidr> NEVER = List.of(Cidr.parse("169.254.0.0/16"), Cidr.parse("0.0.0.0/8"),
      Cidr.parse("224.0.0.0/4"), Cidr.parse("240.0.0.0/4"), Cidr.parse("fe80::/10"), Cidr.parse("fd00:ec2::/32"),
      Cidr.parse("fd20:ce::254/128"), Cidr.parse("ff00::/8"), Cidr.parse("::/128"));

  static final List<Cidr> PRIVATE = List.of(Cidr.parse("127.0.0.0/8"), Cidr.parse("10.0.0.0/8"),
      Cidr.parse("172.16.0.0/12"), Cidr.parse("192.168.0.0/16"), Cidr.parse("100.64.0.0/10"),
      Cidr.parse("198.18.0.0/15"), Cidr.parse("::1/128"), Cidr.parse("fc00::/7"));

  /** Names that point at cloud metadata services whatever they resolve to. */
  static final Set<String> METADATA_NAMES = Set.of("metadata", "metadata.google.internal", "metadata.goog",
      "instance-data", "instance-data.ec2.internal", "metadata.azure.com");

  private AddressRules() {}

  /** Never reachable, whatever the policy. */
  static boolean forbidden(InetAddress address) {
    InetAddress a = unmapped(address);
    for (Cidr c : NEVER) {
      if (c.contains(a)) {
        return true;
      }
    }
    return false;
  }

  static boolean privateAddress(InetAddress address) {
    InetAddress a = unmapped(address);
    for (Cidr c : PRIVATE) {
      if (c.contains(a)) {
        return true;
      }
    }
    return a.isSiteLocalAddress() || a.isLoopbackAddress();
  }

  /** Whether {@code address} is reachable under {@code allowedPrivate}. */
  static boolean allowed(InetAddress address, List<Cidr> allowedPrivate) {
    if (forbidden(address)) {
      return false;
    }
    if (!privateAddress(address)) {
      return true;
    }
    InetAddress a = unmapped(address);
    for (Cidr c : allowedPrivate) {
      if (c.contains(a)) {
        return true;
      }
    }
    return false;
  }

  static boolean metadataName(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    while (h.endsWith(".")) {
      h = h.substring(0, h.length() - 1);
    }
    return METADATA_NAMES.contains(h);
  }

  /** An IPv4-mapped IPv6 address is judged as the IPv4 address it carries. */
  static InetAddress unmapped(InetAddress address) {
    if (address instanceof Inet6Address v6) {
      byte[] b = v6.getAddress();
      boolean mapped = true;
      for (int i = 0; i < 10; i++) {
        mapped &= b[i] == 0;
      }
      if (mapped && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
        try {
          return Inet4Address.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (UnknownHostException e) {
          return address;
        }
      }
    }
    return address;
  }
}
