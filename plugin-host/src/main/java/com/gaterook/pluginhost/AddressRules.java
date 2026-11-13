package com.gaterook.pluginhost;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which addresses a plugin may reach (ARCHITECTURE.md §9 SSRF, the host's side of it): never link-local and cloud
 * metadata (169.254.0.0/16, fe80::/10, fd00:ec2::/32, fd20:ce::254, 100.100.100.200, 192.0.0.0/24), "this network",
 * multicast, reserved, Teredo, documentation or discard-only ranges; private and loopback ranges only where the
 * environment's policy allows them for the connection. An IPv6 address that carries an IPv4 address (mapped,
 * compatible, NAT64 {@code 64:ff9b::/96}, 6to4 {@code 2002::/16}) is judged by the IPv4 address inside. The same rules
 * as the core's {@code AddressPolicy}, written again because the host sees none of the core's classes.
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

    boolean contains(byte[] a) {
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

  static final List<Cidr> NEVER = cidrs(
      "169.254.0.0/16",      // link-local: IMDS 169.254.169.254, ECS and EKS credentials
      "fe80::/10",           // IPv6 link-local
      "fd00:ec2::/32",       // AWS IMDS v6, EKS Pod Identity
      "fd20:ce::254/128",    // GCP metadata v6; inside fc00::/7, so no allowed range may open it
      "100.100.100.200/32",  // Alibaba Cloud metadata; inside 100.64.0.0/10
      "0.0.0.0/8",           // "this network"
      "::/128",              // unspecified
      "224.0.0.0/4",         // multicast
      "ff00::/8",            // IPv6 multicast
      "240.0.0.0/4",         // reserved, including 255.255.255.255
      "2001::/32",           // Teredo: tunnels to an IPv4 address that can't be judged here
      "192.0.0.0/24",        // IETF protocol assignments (Oracle Cloud's legacy metadata 192.0.0.192)
      "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "2001:db8::/32", // documentation
      "100::/64");           // discard-only

  static final List<Cidr> PRIVATE = cidrs(
      "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
      "100.64.0.0/10",   // CGNAT, Tailscale
      "198.18.0.0/15",   // benchmarking
      "fc00::/7",        // unique local
      "fec0::/10",       // deprecated site-local
      "64:ff9b:1::/48"); // local-use NAT64: may translate to private IPv4, so only its own range opens it

  static final List<Cidr> LOOPBACK = cidrs("127.0.0.0/8", "::1/128");

  /** Names that point at cloud metadata services whatever they resolve to. */
  static final Set<String> METADATA_NAMES = Set.of("metadata", "metadata.google.internal", "metadata.goog",
      "instance-data", "instance-data.ec2.internal", "metadata.azure.com");

  private AddressRules() {}

  /** Never reachable, whatever the policy. */
  static boolean forbidden(InetAddress address) {
    byte[] bytes = address.getAddress();
    byte[] judged = judged(bytes);
    return inAny(NEVER, bytes) || (judged != bytes && inAny(NEVER, judged));
  }

  static boolean privateAddress(InetAddress address) {
    return inAny(PRIVATE, judged(address.getAddress()));
  }

  /** This computer, in any form: where the core's own ports are. */
  static boolean loopback(InetAddress address) {
    return inAny(LOOPBACK, judged(address.getAddress()));
  }

  /** Whether {@code address} is reachable under {@code allowedPrivate}. */
  static boolean allowed(InetAddress address, List<Cidr> allowedPrivate) {
    if (forbidden(address)) {
      return false;
    }
    byte[] judged = judged(address.getAddress());
    return !inAny(PRIVATE, judged) || inAny(allowedPrivate, judged);
  }

  static boolean metadataName(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    while (h.endsWith(".")) {
      h = h.substring(0, h.length() - 1);
    }
    return METADATA_NAMES.contains(h);
  }

  /**
   * What an address is judged by: the IPv4 address an IPv6 address carries (mapped, compatible, the NAT64 well-known
   * prefix, 6to4), or else {@code address} itself, the same array.
   */
  static byte[] judged(byte[] address) {
    if (address.length != 16) {
      return address;
    }
    boolean firstTenZero = true;
    for (int i = 0; i < 10; i++) {
      firstTenZero &= address[i] == 0;
    }
    // ::ffff:a.b.c.d (mapped). Java turns a mapped literal into an Inet4Address; a resolver's answer may stay IPv6.
    if (firstTenZero && address[10] == (byte) 0xff && address[11] == (byte) 0xff) {
      return Arrays.copyOfRange(address, 12, 16);
    }
    // ::a.b.c.d (deprecated "compatible"), but not :: and ::1.
    if (firstTenZero && address[10] == 0 && address[11] == 0) {
      boolean zeroOrOne = address[12] == 0 && address[13] == 0 && address[14] == 0
          && (address[15] == 0 || address[15] == 1);
      return zeroOrOne ? address : Arrays.copyOfRange(address, 12, 16);
    }
    // 64:ff9b::/96, the NAT64 well-known prefix.
    if (address[0] == 0 && address[1] == 0x64 && address[2] == (byte) 0xff && address[3] == (byte) 0x9b) {
      boolean zero = true;
      for (int i = 4; i < 12; i++) {
        zero &= address[i] == 0;
      }
      if (zero) {
        return Arrays.copyOfRange(address, 12, 16);
      }
    }
    // 2002:AABB:CCDD::/48, 6to4.
    if (address[0] == 0x20 && address[1] == 0x02) {
      return Arrays.copyOfRange(address, 2, 6);
    }
    return address;
  }

  private static boolean inAny(List<Cidr> ranges, byte[] address) {
    for (Cidr c : ranges) {
      if (c.contains(address)) {
        return true;
      }
    }
    return false;
  }

  private static List<Cidr> cidrs(String... ranges) {
    return Arrays.stream(ranges).map(Cidr::parse).toList();
  }
}
