package ai.msrv.gaterook.connectors.support.egress;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Objects;

/**
 * An IPv4 or IPv6 network, {@code 10.0.0.0/8} or {@code fd00:ec2::/32}. Our own matcher on the address bytes:
 * {@link InetAddress#isSiteLocalAddress()} knows neither {@code fc00::/7} nor CGNAT (report 10 §6.1).
 *
 * <p>Parsing accepts only canonical literals, the same rule hosts follow ({@link AddressPolicy#canonicalIp}): a
 * policy that says {@code 0177.0.0.1/32} would mean one thing to the person and another to Java.
 */
public final class Cidr {

  private final byte[] network;
  private final int prefix;

  private Cidr(byte[] network, int prefix) {
    this.network = network;
    this.prefix = prefix;
    // Zero the host bits so that equals() and toString() are canonical.
    for (int bit = prefix; bit < network.length * 8; bit++) {
      network[bit / 8] &= (byte) ~(0x80 >>> (bit % 8));
    }
  }

  /**
   * Parses {@code address/prefix} or a single address (a /32 or /128).
   *
   * @throws IllegalArgumentException if the text isn't a canonical IP literal with a prefix in range
   */
  public static Cidr parse(String text) {
    Objects.requireNonNull(text, "text");
    String trimmed = text.trim();
    int slash = trimmed.indexOf('/');
    String address = slash < 0 ? trimmed : trimmed.substring(0, slash);
    InetAddress ip = AddressPolicy.canonicalIp(address)
        .orElseThrow(() -> new IllegalArgumentException("Not an IP address: " + text));
    byte[] bytes = ip.getAddress();
    int max = bytes.length * 8;
    int prefix = max;
    if (slash >= 0) {
      String bits = trimmed.substring(slash + 1);
      if (!bits.matches("\\d{1,3}")) {
        throw new IllegalArgumentException("Bad prefix length in " + text);
      }
      prefix = Integer.parseInt(bits);
      if (prefix > max) {
        throw new IllegalArgumentException("Prefix /" + prefix + " is too long for " + address);
      }
    }
    return new Cidr(bytes, prefix);
  }

  /** The network of exactly {@code address}: what Test connection suggests to allow. */
  public static Cidr single(InetAddress address) {
    byte[] bytes = address.getAddress();
    return new Cidr(bytes, bytes.length * 8);
  }

  /** Whether {@code address} is inside this network; an IPv4 network never contains an IPv6 address. */
  public boolean contains(InetAddress address) {
    return contains(address.getAddress());
  }

  boolean contains(byte[] address) {
    if (address.length != network.length) {
      return false;
    }
    int full = prefix / 8;
    for (int i = 0; i < full; i++) {
      if (address[i] != network[i]) {
        return false;
      }
    }
    int rest = prefix % 8;
    if (rest == 0) {
      return true;
    }
    int mask = (0xFF << (8 - rest)) & 0xFF;
    return (address[full] & mask) == (network[full] & mask);
  }

  public boolean isIpv6() {
    return network.length == 16;
  }

  public int prefix() {
    return prefix;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Cidr other && prefix == other.prefix && Arrays.equals(network, other.network);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(network) + prefix;
  }

  @Override
  public String toString() {
    return IpText.format(network) + "/" + prefix;
  }
}
