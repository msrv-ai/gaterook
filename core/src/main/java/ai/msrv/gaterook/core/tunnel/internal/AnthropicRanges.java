package ai.msrv.gaterook.core.tunnel.internal;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

/**
 * The addresses claude.ai's connectors call from (Settings › Tunnel › Callers, report 02 §6.2): Anthropic's published
 * outbound IPv4 range {@code 160.79.104.0/21}, as listed on {@code platform.claude.com/docs/en/api/ip-addresses} on
 * {@link #VERIFIED}. The inbound ranges of Anthropic's API ({@code 160.79.104.0/23}, {@code 2607:6bc0::/48}) and the
 * retired {@code 34.162.*}/32 addresses are not in it. Built into the release, so it changes with app updates only.
 *
 * <p>Connectors reach servers over IPv4 only, so an IPv6 caller is never in range.
 */
final class AnthropicRanges {

  /** The day the list was checked against Anthropic's documentation. */
  static final String VERIFIED = "2026-10-23";

  /** CIDR blocks: network and prefix length. */
  static final List<String> RANGES = List.of("160.79.104.0/21");

  private static final int[][] BLOCKS = blocks();

  private AnthropicRanges() {}

  /** Whether {@code ip} (an IPv4 literal; never a name, never resolved) is one of Anthropic's addresses. */
  static boolean contains(String ip) {
    Integer address = ipv4(ip);
    if (address == null) {
      return false;
    }
    for (int[] block : BLOCKS) {
      int mask = block[1] == 0 ? 0 : -1 << (32 - block[1]);
      if ((address & mask) == (block[0] & mask)) {
        return true;
      }
    }
    return false;
  }

  /** An IPv4 literal as an int, or {@code null} for anything else (names, IPv6, octal or short forms). */
  static Integer ipv4(String ip) {
    if (ip == null) {
      return null;
    }
    String s = ip.strip();
    // Four decimal octets only: InetAddress.ofLiteral would also take forms a header has no business carrying.
    if (!s.matches("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}")) {
      return null;
    }
    InetAddress a = InetAddress.ofLiteral(s);
    if (!(a instanceof Inet4Address)) {
      return null;
    }
    byte[] b = a.getAddress();
    return (b[0] & 0xff) << 24 | (b[1] & 0xff) << 16 | (b[2] & 0xff) << 8 | (b[3] & 0xff);
  }

  private static int[][] blocks() {
    int[][] out = new int[RANGES.size()][];
    for (int i = 0; i < RANGES.size(); i++) {
      String[] parts = RANGES.get(i).split("/");
      out[i] = new int[] {ipv4(parts[0]), Integer.parseInt(parts[1])};
    }
    return out;
  }
}
