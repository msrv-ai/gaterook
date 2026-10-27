package ai.msrv.gaterook.connectors.support.egress;

import java.net.InetAddress;

/** Text of an address as people read it: dotted IPv4, RFC 5952 IPv6 (lower case, longest zero run as {@code ::}). */
final class IpText {

  private IpText() {}

  static String format(InetAddress address) {
    return format(address.getAddress());
  }

  static String format(byte[] bytes) {
    if (bytes.length == 4) {
      return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
    }
    int[] groups = new int[8];
    for (int i = 0; i < 8; i++) {
      groups[i] = ((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF);
    }
    // The longest run of at least two zero groups becomes "::"; the first one wins a tie (RFC 5952 §4.2.3).
    int bestStart = -1;
    int bestLength = 1;
    for (int i = 0; i < 8; ) {
      if (groups[i] != 0) {
        i++;
        continue;
      }
      int j = i;
      while (j < 8 && groups[j] == 0) {
        j++;
      }
      if (j - i > bestLength) {
        bestStart = i;
        bestLength = j - i;
      }
      i = j;
    }
    StringBuilder out = new StringBuilder(39);
    for (int i = 0; i < 8; i++) {
      if (i == bestStart) {
        out.append("::");
        i += bestLength - 1;
        continue;
      }
      if (out.length() > 0 && out.charAt(out.length() - 1) != ':') {
        out.append(':');
      }
      out.append(Integer.toHexString(groups[i]));
    }
    return out.toString();
  }
}
