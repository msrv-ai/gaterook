package ai.msrv.gaterook.core.oauth.internal;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Special-use addresses of RFC 6890 that a fetched client metadata document must never come from: this computer,
 * private networks, link-local and cloud metadata, CGNAT, documentation and benchmark ranges, multicast, IPv4-mapped
 * forms of all of these.
 */
final class SpecialUse {

  private SpecialUse() {}

  static boolean global(InetAddress a) {
    if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
        || a.isMulticastAddress()) {
      return false;
    }
    byte[] b = a.getAddress();
    if (a instanceof Inet4Address) {
      return global4(b);
    }
    if (a instanceof Inet6Address) {
      // ::ffff:a.b.c.d and ::a.b.c.d carry an IPv4 address: judge that.
      boolean mapped = true;
      for (int i = 0; i < 10; i++) {
        mapped &= b[i] == 0;
      }
      if (mapped && (b[10] == (byte) 0xff && b[11] == (byte) 0xff || b[10] == 0 && b[11] == 0)) {
        return global4(new byte[] {b[12], b[13], b[14], b[15]});
      }
      int first = b[0] & 0xff;
      if ((first & 0xfe) == 0xfc) {
        return false; // fc00::/7 unique local
      }
      if (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
        return false; // 2001:db8::/32 documentation
      }
      if (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x00 && (b[3] & 0xff) == 0x00) {
        return false; // 2001::/32 Teredo
      }
      if (first == 0x20 && (b[1] & 0xff) == 0x02) {
        return false; // 2002::/16 6to4
      }
      return first != 0x00 && first != 0xff;
    }
    return false;
  }

  private static boolean global4(byte[] b) {
    int a0 = b[0] & 0xff;
    int a1 = b[1] & 0xff;
    int a2 = b[2] & 0xff;
    return !(a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224
        || a0 == 100 && a1 >= 64 && a1 <= 127
        || a0 == 169 && a1 == 254
        || a0 == 172 && a1 >= 16 && a1 <= 31
        || a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)
        || a0 == 192 && a1 == 88 && a2 == 99
        || a0 == 192 && a1 == 168
        || a0 == 198 && (a1 == 18 || a1 == 19)
        || a0 == 198 && a1 == 51 && a2 == 100
        || a0 == 203 && a1 == 0 && a2 == 113);
  }
}
