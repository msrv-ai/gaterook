package ai.msrv.gaterook.connectors.support.egress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which addresses outbound connections of the gateway may reach (report 10 §6.1, ARCHITECTURE.md §9 "SSRF").
 *
 * <ul>
 *   <li><b>Always blocked</b>, on every environment, whatever the policy says: link-local (cloud metadata
 *       {@code 169.254.169.254}, ECS/EKS credentials), the IPv6 metadata addresses of AWS and GCP, Alibaba's metadata,
 *       "this network", multicast, reserved, broadcast, Teredo, documentation and IETF protocol ranges, the metadata
 *       host names, and the core's own loopback ports (7465, 7466, the tunnel's).</li>
 *   <li><b>Private</b> ({@code 127/8}, {@code 10/8}, {@code 172.16/12}, {@code 192.168/16}, CGNAT, benchmarking,
 *       {@code fc00::/7}, {@code fec0::/10}, {@code ::1}, local-use NAT64 {@code 64:ff9b:1::/48}): blocked until the
 *       environment's {@code allowedPrivateRanges} names them.</li>
 *   <li><b>Embedded IPv4</b> ({@code ::ffff:a.b.c.d}, {@code ::a.b.c.d}, NAT64 {@code 64:ff9b::/96}, 6to4
 *       {@code 2002::/16}): judged by the IPv4 address inside.</li>
 * </ul>
 *
 * <p>The policy judges addresses, not names: {@link GuardDns} applies it to what a name resolves to and
 * {@link GuardSocketFactory} to the address each TCP connection really goes to, which closes DNS rebinding,
 * IP literals that bypass DNS, every redirect hop and HTTP/2 connection coalescing. Immutable and thread-safe.
 */
public final class AddressPolicy {

  /** Why an address is (not) allowed. */
  public enum Reason {
    /** A public address. */
    PUBLIC(true),
    /** A private address inside one of {@code allowedPrivateRanges}. */
    ALLOWED_PRIVATE(true),
    /** Link-local, metadata, multicast, reserved: never, on no environment. */
    ALWAYS_BLOCKED(false),
    /** The core's own listener on loopback. */
    CORE_PORT(false),
    /** A private address the environment's policy doesn't allow. */
    PRIVATE(false),
    /** Loopback, for a client that never goes there (downloads protect the core's admin API). */
    LOOPBACK(false),
    /** A host name reserved for cloud metadata. */
    BLOCKED_NAME(false),
    /** A numeric host that isn't a canonical IP literal ({@code 2130706433}, {@code 0177.0.0.1}). */
    NON_CANONICAL(false),
    /** The name doesn't resolve. */
    UNRESOLVED(false);

    private final boolean allowed;

    Reason(boolean allowed) {
      this.allowed = allowed;
    }

    public boolean allowed() {
      return allowed;
    }
  }

  /**
   * The judgment of one address.
   *
   * @param address the address as connected to (an embedded IPv4 address is judged, but this stays the original)
   * @param text    what happened and what to do, in the tone of the app; never contains secrets
   */
  public record Verdict(Reason reason, InetAddress address, String text) {

    public boolean allowed() {
      return reason.allowed();
    }

    /** For {@link Reason#PRIVATE}: the range Test connection offers to allow ({@code 10.30.4.12/32}). */
    public Optional<Cidr> suggestedRange() {
      return reason == Reason.PRIVATE && address != null ? Optional.of(Cidr.single(address)) : Optional.empty();
    }
  }

  // Declared before the range lists: their initializers parse literals with these patterns.
  private static final Pattern DOTTED_QUAD =
      Pattern.compile("(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})");
  /** The last label of a host that browsers and curl read as an IPv4 number (WHATWG URL "ends in a number"). */
  private static final Pattern NUMERIC_LABEL = Pattern.compile("\\d+|0[xX][0-9a-fA-F]*");

  private static final List<Cidr> ALWAYS_BLOCKED = cidrs(
      "169.254.0.0/16",      // link-local: IMDS 169.254.169.254, ECS 169.254.170.2, EKS 169.254.170.23
      "fe80::/10",           // IPv6 link-local
      "fd00:ec2::/32",       // AWS IMDS v6 fd00:ec2::254, EKS Pod Identity fd00:ec2::23
      "fd20:ce::254/128",    // GCP metadata v6; inside fc00::/7, so allowedPrivateRanges must never open it
      "100.100.100.200/32",  // Alibaba Cloud metadata
      "0.0.0.0/8",           // "this network"
      "::/128",              // unspecified
      "224.0.0.0/4",         // multicast
      "ff00::/8",            // IPv6 multicast
      "240.0.0.0/4",         // reserved, including 255.255.255.255
      "2001::/32",           // Teredo: tunnels to an IPv4 address we can't see
      "192.0.0.0/24",        // IETF protocol assignments (Oracle Cloud's legacy metadata 192.0.0.192)
      "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "2001:db8::/32", // documentation
      "100::/64");           // discard-only

  private static final List<Cidr> PRIVATE = cidrs(
      "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
      "100.64.0.0/10",  // CGNAT, Tailscale
      "198.18.0.0/15",  // benchmarking
      "fc00::/7",       // unique local
      "fec0::/10",      // deprecated site-local
      "64:ff9b:1::/48"); // local-use NAT64 (RFC 8215): may translate to private IPv4, so only its own range opens it

  private static final List<Cidr> LOOPBACK = cidrs("127.0.0.0/8", "::1/128");

  /** Names that resolve to metadata services inside clouds; the addresses are blocked too, this says it earlier. */
  private static final Set<String> BLOCKED_NAMES = Set.of(
      "metadata.google.internal", "metadata.goog", "instance-data.ec2.internal", "instance-data");

  private final List<Cidr> allowedPrivate;
  private final Set<Integer> corePorts;
  private final boolean loopbackNeverAllowed;

  private AddressPolicy(List<Cidr> allowedPrivate, Set<Integer> corePorts, boolean loopbackNeverAllowed) {
    this.allowedPrivate = List.copyOf(allowedPrivate);
    this.corePorts = Set.copyOf(corePorts);
    this.loopbackNeverAllowed = loopbackNeverAllowed;
  }

  /** Public addresses only: no private range is allowed. */
  public static AddressPolicy publicOnly(Collection<Integer> corePorts) {
    return builder().corePorts(corePorts).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** A copy of this policy that also allows {@code more} private ranges. */
  public AddressPolicy allowing(Collection<Cidr> more) {
    List<Cidr> all = new ArrayList<>(allowedPrivate);
    all.addAll(more);
    return new AddressPolicy(all, corePorts, loopbackNeverAllowed);
  }

  /** A copy of this policy that also blocks {@code ports} on loopback (the core's own listeners). */
  public AddressPolicy withCorePorts(Collection<Integer> ports) {
    if (corePorts.containsAll(ports)) {
      return this;
    }
    Set<Integer> all = new HashSet<>(corePorts);
    all.addAll(ports);
    return new AddressPolicy(allowedPrivate, all, loopbackNeverAllowed);
  }

  public List<Cidr> allowedPrivateRanges() {
    return allowedPrivate;
  }

  public Set<Integer> corePorts() {
    return corePorts;
  }

  /**
   * Judges a connection to {@code address}.
   *
   * @param port the TCP port, or {@code -1} when only the address is known (name resolution)
   */
  public Verdict check(InetAddress address, int port) {
    return check(null, address, port);
  }

  /**
   * Judges a connection to {@code address}, which {@code host} resolved to; the verdict's text names both.
   *
   * @param host the name as written, or {@code null} for a literal
   * @param port the TCP port, or {@code -1} when only the address is known (name resolution)
   */
  public Verdict check(String host, InetAddress address, int port) {
    Objects.requireNonNull(address, "address");
    byte[] bytes = address.getAddress();
    String ip = IpText.format(bytes);
    String subject = host == null || host.equals(ip) || unbracket(host).equalsIgnoreCase(ip)
        ? ip + " is" : host + " resolves to " + ip + ",";
    if (inAny(ALWAYS_BLOCKED, bytes)) {
      return new Verdict(Reason.ALWAYS_BLOCKED, address, subject
          + " a link-local, cloud metadata or reserved address. MCP Gateway never connects there.");
    }
    byte[] judged = embeddedIpv4(bytes).orElse(bytes);
    if (judged != bytes && inAny(ALWAYS_BLOCKED, judged)) {
      return new Verdict(Reason.ALWAYS_BLOCKED, address, subject + " an address that carries "
          + IpText.format(judged) + " (link-local, cloud metadata or reserved). MCP Gateway never connects there.");
    }
    boolean loopback = inAny(LOOPBACK, judged);
    if (loopback && port >= 0 && corePorts.contains(port)) {
      return new Verdict(Reason.CORE_PORT, address, IpText.format(judged) + ":" + port
          + " is MCP Gateway itself. Connections to it are never allowed.");
    }
    if (loopback && loopbackNeverAllowed) {
      return new Verdict(Reason.LOOPBACK, address, subject + " this computer. Downloads never go there.");
    }
    if (inAny(PRIVATE, judged)) {
      for (Cidr range : allowedPrivate) {
        if (range.contains(judged)) {
          return new Verdict(Reason.ALLOWED_PRIVATE, address, subject + " a private address allowed by " + range);
        }
      }
      return new Verdict(Reason.PRIVATE, address, subject + " a private address. Allow " + Cidr.single(address)
          + " in this environment's policy to reach it.");
    }
    return new Verdict(Reason.PUBLIC, address, subject + " a public address");
  }

  public boolean allows(InetAddress address, int port) {
    return check(address, port).allowed();
  }

  /** Whether {@code host} is a name reserved for cloud metadata (any case, with or without the trailing dot). */
  public static boolean blockedName(String host) {
    return host != null && BLOCKED_NAMES.contains(normalizeName(host));
  }

  /**
   * The judgment of a host as written in a URL, before any resolution: a blocked name, or a numeric host that isn't a
   * canonical literal. Java reads {@code 0177.0.0.1} as 177.0.0.1 while browsers and curl read 127.0.0.1, so the
   * approver would see one address and the connection would go to another: such hosts are refused, not interpreted.
   *
   * @return the refusal, or empty when the host may be resolved
   */
  public static Optional<Verdict> checkHostSyntax(String host) {
    if (host == null || host.isBlank()) {
      return Optional.of(new Verdict(Reason.NON_CANONICAL, null, "The URL has no host"));
    }
    if (blockedName(host)) {
      return Optional.of(new Verdict(Reason.BLOCKED_NAME, null, normalizeName(host)
          + " is a cloud metadata name. MCP Gateway never connects there."));
    }
    String bare = unbracket(host);
    if (bare.indexOf(':') >= 0) {
      return canonicalIp(bare).isPresent() ? Optional.empty()
          : Optional.of(new Verdict(Reason.NON_CANONICAL, null, host + " is not a valid IPv6 address"));
    }
    String name = normalizeName(bare);
    String[] labels = name.split("\\.", -1);
    if (NUMERIC_LABEL.matcher(labels[labels.length - 1]).matches() && !DOTTED_QUAD.matcher(name).matches()) {
      return Optional.of(new Verdict(Reason.NON_CANONICAL, null, host
          + " is a numeric host in a non-standard form. Write the address as four decimal numbers, like 10.1.2.3."));
    }
    if (DOTTED_QUAD.matcher(name).matches() && canonicalIp(name).isEmpty()) {
      return Optional.of(new Verdict(Reason.NON_CANONICAL, null, host + " is not a valid IPv4 address"));
    }
    return Optional.empty();
  }

  /**
   * Parses a canonical IP literal: dotted decimal IPv4 without leading zeros, or IPv6 (optionally in brackets, with a
   * zone id). Never touches DNS.
   */
  public static Optional<InetAddress> canonicalIp(String text) {
    if (text == null || text.isEmpty()) {
      return Optional.empty();
    }
    String bare = unbracket(text);
    if (bare.indexOf(':') < 0) {
      var m = DOTTED_QUAD.matcher(bare);
      if (!m.matches()) {
        return Optional.empty();
      }
      byte[] bytes = new byte[4];
      for (int i = 0; i < 4; i++) {
        int octet = Integer.parseInt(m.group(i + 1));
        if (octet > 255) {
          return Optional.empty();
        }
        bytes[i] = (byte) octet;
      }
      return Optional.of(byAddress(bytes));
    }
    // Only hexadecimal groups, colons, an optional embedded dotted quad and a zone: InetAddress then parses a literal
    // and never resolves a name.
    if (!bare.matches("[0-9a-fA-F:.]+(%[0-9A-Za-z._-]+)?")) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.getByName(bare));
    } catch (UnknownHostException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** The IPv4 address an IPv6 address carries: mapped, compatible, NAT64 well-known prefix, 6to4. */
  static Optional<byte[]> embeddedIpv4(byte[] address) {
    if (address.length != 16) {
      return Optional.empty();
    }
    boolean firstTenZero = true;
    for (int i = 0; i < 10; i++) {
      firstTenZero &= address[i] == 0;
    }
    // ::ffff:a.b.c.d (mapped). Java already turns most of them into Inet4Address; bytes from elsewhere may not be.
    if (firstTenZero && address[10] == (byte) 0xFF && address[11] == (byte) 0xFF) {
      return Optional.of(Arrays.copyOfRange(address, 12, 16));
    }
    // ::a.b.c.d (deprecated "compatible"), but not :: and ::1.
    if (firstTenZero && address[10] == 0 && address[11] == 0) {
      byte[] v4 = Arrays.copyOfRange(address, 12, 16);
      boolean zeroOrOne = v4[0] == 0 && v4[1] == 0 && v4[2] == 0 && (v4[3] == 0 || v4[3] == 1);
      return zeroOrOne ? Optional.empty() : Optional.of(v4);
    }
    // 64:ff9b::/96, the NAT64 well-known prefix.
    if (address[0] == 0 && address[1] == 0x64 && address[2] == (byte) 0xFF && address[3] == (byte) 0x9B) {
      boolean zero = true;
      for (int i = 4; i < 12; i++) {
        zero &= address[i] == 0;
      }
      if (zero) {
        return Optional.of(Arrays.copyOfRange(address, 12, 16));
      }
    }
    // 2002:AABB:CCDD::/48, 6to4.
    if (address[0] == 0x20 && address[1] == 0x02) {
      return Optional.of(Arrays.copyOfRange(address, 2, 6));
    }
    return Optional.empty();
  }

  private static boolean inAny(List<Cidr> ranges, byte[] address) {
    for (Cidr range : ranges) {
      if (range.contains(address)) {
        return true;
      }
    }
    return false;
  }

  private static String unbracket(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  private static String normalizeName(String host) {
    String lower = host.toLowerCase(Locale.ROOT);
    return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
  }

  private static InetAddress byAddress(byte[] bytes) {
    try {
      return InetAddress.getByAddress(bytes);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Cidr> cidrs(String... ranges) {
    return Arrays.stream(ranges).map(Cidr::parse).toList();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof AddressPolicy other && loopbackNeverAllowed == other.loopbackNeverAllowed
        && allowedPrivate.equals(other.allowedPrivate) && corePorts.equals(other.corePorts);
  }

  @Override
  public int hashCode() {
    return Objects.hash(allowedPrivate, corePorts, loopbackNeverAllowed);
  }

  @Override
  public String toString() {
    return "AddressPolicy[allowedPrivate=" + allowedPrivate + ", corePorts=" + corePorts
        + (loopbackNeverAllowed ? ", no loopback" : "") + "]";
  }

  /** Builds a policy. */
  public static final class Builder {
    private final List<Cidr> allowedPrivate = new ArrayList<>();
    private Set<Integer> corePorts = Set.of();
    private boolean loopbackNeverAllowed;

    private Builder() {}

    /** Private ranges the environment's policy allows ({@code allowedPrivateRanges}). */
    public Builder allowPrivate(Collection<Cidr> ranges) {
      allowedPrivate.addAll(ranges);
      return this;
    }

    public Builder allowPrivate(String... ranges) {
      for (String range : ranges) {
        allowedPrivate.add(Cidr.parse(range));
      }
      return this;
    }

    /** The core's own ports, blocked on loopback even when loopback is allowed. */
    public Builder corePorts(Collection<Integer> ports) {
      corePorts = Set.copyOf(ports);
      return this;
    }

    /**
     * Loopback is refused whatever the private ranges say: for downloads by URL (OpenAPI, plugins, catalog), which
     * must never reach the core's admin API (report 10 §6.5).
     */
    public Builder neverLoopback() {
      loopbackNeverAllowed = true;
      return this;
    }

    public AddressPolicy build() {
      return new AddressPolicy(allowedPrivate, corePorts, loopbackNeverAllowed);
    }
  }
}
