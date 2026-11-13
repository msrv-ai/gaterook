package com.gaterook.pluginhost;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.host.ScopedEgress;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where one call's own TCP connections may go (ARCHITECTURE.md §7.5, the TCP gate): the connection's
 * {@code access.tcp} destinations as the core resolved them, under the environment's private ranges, never cloud
 * metadata, link-local or reserved addresses and never GateRook's own ports. {@link TcpGate} asks {@link #judge} for
 * every connection a socket of the plugin asks it for, and connects to the addresses the answer names, so a name
 * can't rebind between the check and the connect.
 *
 * <p>A request names a destination either by name (an unresolved socket address) or by the IP address the driver
 * resolved. A name must be a granted one, and every address it resolves to must pass, as for {@code ctx.egress()}. An
 * IP address must be an address of the granted name the driver asked for (the selector passes that name along), or
 * of any granted name on that port: the gate's lookup shares the JVM's address cache with the driver's, so both see
 * the same addresses.
 */
final class TcpGrant {

  /** One {@code host:port} the grant allows: a name (lower case, no trailing dot) or a literal address. */
  record Destination(String host, InetAddress literal, int port) {

    static Optional<Destination> parse(String entry) {
      String host;
      String port;
      if (entry.startsWith("[")) {
        int close = entry.indexOf(']');
        if (close < 0 || close + 1 >= entry.length() || entry.charAt(close + 1) != ':') {
          return Optional.empty();
        }
        host = entry.substring(1, close);
        port = entry.substring(close + 2);
      } else {
        int colon = entry.lastIndexOf(':');
        if (colon <= 0 || entry.indexOf(':') != colon) {
          return Optional.empty();
        }
        host = entry.substring(0, colon);
        port = entry.substring(colon + 1);
      }
      if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65_535) {
        return Optional.empty();
      }
      String h = normalize(host);
      if (h.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new Destination(h, TcpGrant.literal(h), Integer.parseInt(port)));
    }

    String text() {
      return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
  }

  /** The gate's answer: the addresses to connect to in order, or why not. */
  record Verdict(List<InetAddress> addresses, ScopedEgress.Denial denial, boolean unresolvable) {

    static Verdict connect(List<InetAddress> addresses) {
      return new Verdict(List.copyOf(addresses), null, false);
    }

    static Verdict refuse(ScopedEgress.Denial denial) {
      return new Verdict(List.of(), denial, false);
    }

    static Verdict unknownHost() {
      return new Verdict(List.of(), null, true);
    }
  }

  /** Name lookups; the JVM's resolver (through {@link GuardedResolverProvider}) in the host, a fake in tests. */
  @FunctionalInterface
  interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final String token;
  private final String connectionId;
  private final String connectionName;
  private final String env;
  private final List<Destination> destinations;
  private final List<AddressRules.Cidr> allowedPrivate;
  private final Set<Integer> closedPorts;
  private final String fingerprint;
  private final Instant deadline;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final AtomicReference<ScopedEgress.Denial> lastDenial = new AtomicReference<>();
  private volatile Instant closedAt;

  /**
   * @param closedPorts the core's ports and the gate's own: never reachable on this computer
   * @param deadline    the end of the call: the gate connects for no longer than that
   */
  TcpGrant(String token, Messages.EgressGrant grant, ConnectionConfig cfg, Set<Integer> closedPorts,
      Instant deadline) {
    this.token = token;
    this.connectionId = cfg == null ? null : cfg.id();
    this.connectionName = cfg == null ? null : cfg.name();
    this.env = cfg == null || cfg.env() == null ? null : cfg.env().id();
    List<Destination> d = new ArrayList<>();
    for (String entry : grant.tcp()) {
      Destination.parse(entry).ifPresent(d::add);
    }
    this.destinations = List.copyOf(d);
    List<AddressRules.Cidr> ranges = new ArrayList<>();
    for (String r : grant.allowPrivate()) {
      try {
        ranges.add(AddressRules.Cidr.parse(r));
      } catch (IllegalArgumentException e) {
        // An unreadable range allows nothing (§9).
      }
    }
    this.allowedPrivate = List.copyOf(ranges);
    this.closedPorts = Set.copyOf(closedPorts);
    this.fingerprint = fingerprint(grant);
    this.deadline = deadline;
  }

  String token() {
    return token;
  }

  String connectionId() {
    return connectionId;
  }

  String fingerprint() {
    return fingerprint;
  }

  Instant deadline() {
    return deadline;
  }

  List<Destination> destinations() {
    return destinations;
  }

  boolean open() {
    return open.get();
  }

  void close(Instant now) {
    if (open.compareAndSet(true, false)) {
      closedAt = now;
    }
  }

  Instant closedAt() {
    return closedAt;
  }

  Optional<ScopedEgress.Denial> lastDenial() {
    return Optional.ofNullable(lastDenial.get());
  }

  void record(ScopedEgress.Denial d) {
    lastDenial.set(d);
  }

  /**
   * What a socket of the plugin may do: connect to {@code requestedName} or {@code requestedIp} on {@code port}.
   * {@code askedName} is the name the driver opened the socket for, when the selector saw one.
   */
  Verdict judge(String requestedName, InetAddress requestedIp, String askedName, int port, Resolver resolver) {
    String shown = requestedName != null ? requestedName : requestedIp.getHostAddress();
    if (!open.get()) {
      return Verdict.refuse(denial(ScopedEgress.Reason.OUTSIDE_CALL, shown, port, "The " + HostVoice.noun()
          + " opened a connection to " + target(shown, port) + " after its call ended: its connections open only "
          + "during a call.", null));
    }
    if (requestedName != null) {
      return byName(normalize(requestedName), port, resolver);
    }
    return byAddress(requestedIp, askedName == null ? null : normalize(askedName), port, resolver);
  }

  private Verdict byName(String name, int port, Resolver resolver) {
    if (AddressRules.metadataName(name)) {
      return Verdict.refuse(denial(ScopedEgress.Reason.ALWAYS_BLOCKED, name, port, name + " is a cloud metadata "
          + "service, which a plugin never reaches.", null));
    }
    Optional<Destination> granted = find(name, port);
    if (granted.isEmpty()) {
      return Verdict.refuse(notGranted(name, port));
    }
    InetAddress[] addresses;
    if (granted.get().literal() != null) {
      addresses = new InetAddress[] {granted.get().literal()};
    } else {
      try {
        addresses = resolver.resolve(name);
      } catch (UnknownHostException e) {
        return Verdict.unknownHost();
      }
    }
    for (InetAddress a : addresses) {
      Optional<ScopedEgress.Denial> d = judgeAddress(name, a, port);
      if (d.isPresent()) {
        return Verdict.refuse(d.get());
      }
    }
    return Verdict.connect(List.of(addresses));
  }

  private Verdict byAddress(InetAddress ip, String askedName, int port, Resolver resolver) {
    List<String> candidates = new ArrayList<>();
    if (askedName != null && !askedName.isEmpty()) {
      candidates.add(askedName);
    } else {
      destinations.stream().filter(d -> d.port() == port).forEach(d -> candidates.add(d.host()));
    }
    String matched = null;
    for (String name : candidates) {
      Optional<Destination> granted = find(name, port);
      if (granted.isEmpty()) {
        continue;
      }
      if (granted.get().literal() != null) {
        if (sameAddress(granted.get().literal(), ip)) {
          matched = name;
          break;
        }
        continue;
      }
      try {
        for (InetAddress a : resolver.resolve(name)) {
          if (sameAddress(a, ip)) {
            matched = name;
            break;
          }
        }
      } catch (UnknownHostException e) {
        // That name doesn't stand for the address; try the next one.
      }
      if (matched != null) {
        break;
      }
    }
    if (matched == null) {
      String shown = askedName != null && !askedName.isEmpty() && !askedName.equals(ip.getHostAddress())
          ? askedName + " (" + ip.getHostAddress() + ")" : ip.getHostAddress();
      return Verdict.refuse(notGranted(shown, port));
    }
    Optional<ScopedEgress.Denial> d = judgeAddress(matched, ip, port);
    return d.map(Verdict::refuse).orElseGet(() -> Verdict.connect(List.of(ip)));
  }

  /** Why {@code address} of {@code name} is out of reach on {@code port}, if it is. */
  private Optional<ScopedEgress.Denial> judgeAddress(String name, InetAddress address, int port) {
    String ip = address.getHostAddress();
    boolean literal = name.equals(normalize(ip));
    if (AddressRules.forbidden(address)) {
      return Optional.of(denial(ScopedEgress.Reason.ALWAYS_BLOCKED, name, port, (literal ? ip + " is"
          : name + " resolves to " + ip + ", which is") + " cloud metadata, link-local or a reserved address: a "
          + "plugin never reaches it.", null));
    }
    if (AddressRules.loopback(address) && closedPorts.contains(port)) {
      return Optional.of(denial(ScopedEgress.Reason.CORE_PORT, name, port, "GateRook's own port " + port
          + " is never reachable from a plugin.", null));
    }
    if (AddressRules.privateAddress(address) && !AddressRules.allowed(address, allowedPrivate)) {
      String cidr = cidrOf(address);
      String where = connectionName == null ? "this connection" : connectionName + (env == null ? "" : " on " + env);
      String what = literal ? ip + " is a private address." : name + " resolves to " + ip + ", a private address.";
      return Optional.of(denial(ScopedEgress.Reason.PRIVATE_ADDRESS, name, port, what + " Allow " + cidr + " for "
          + where + " in the environment's policy (http.allowedPrivateRanges).", cidr));
    }
    return Optional.empty();
  }

  private ScopedEgress.Denial notGranted(String shown, int port) {
    if (destinations.isEmpty()) {
      return denial(ScopedEgress.Reason.NOT_GRANTED, shown, port, "This connection gives the "
          + HostVoice.noun() + " no host and port to connect to, so it may not connect to " + target(shown, port) + ".", null);
    }
    List<String> allowed = destinations.stream().map(Destination::text).toList();
    return denial(ScopedEgress.Reason.NOT_GRANTED, shown, port, "This " + HostVoice.noun()
        + " may only connect to " + String.join(", ", allowed) + " for this connection, not " + target(shown, port) + ".", null);
  }

  private ScopedEgress.Denial denial(ScopedEgress.Reason reason, String host, int port, String text, String cidr) {
    return new ScopedEgress.Denial(reason, target(host, port), text, cidr, connectionName);
  }

  private Optional<Destination> find(String name, int port) {
    InetAddress literal = literal(name);
    for (Destination d : destinations) {
      if (d.port() != port) {
        continue;
      }
      if (d.literal() != null && literal != null ? sameAddress(d.literal(), literal) : d.host().equals(name)) {
        return Optional.of(d);
      }
    }
    return Optional.empty();
  }

  private static String target(String host, int port) {
    String h = host.indexOf(':') >= 0 && !host.startsWith("[") && !host.contains(" ") ? "[" + host + "]" : host;
    return h + ":" + port;
  }

  /** The address as a range of one: /32 for IPv4 (also inside a mapped IPv6 address), /128 for IPv6. */
  static String cidrOf(InetAddress address) {
    byte[] judged = AddressRules.judged(address.getAddress());
    try {
      InetAddress a = InetAddress.getByAddress(judged);
      return a.getHostAddress() + (judged.length == 4 ? "/32" : "/128");
    } catch (UnknownHostException e) {
      return address.getHostAddress() + (judged.length == 4 ? "/32" : "/128");
    }
  }

  private static boolean sameAddress(InetAddress a, InetAddress b) {
    return java.util.Arrays.equals(AddressRules.judged(a.getAddress()), AddressRules.judged(b.getAddress()));
  }

  /** A host as the gate compares it: lower case, without brackets and trailing dots. */
  static String normalize(String host) {
    String h = host.strip().toLowerCase(Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]")) {
      h = h.substring(1, h.length() - 1);
    }
    while (h.endsWith(".")) {
      h = h.substring(0, h.length() - 1);
    }
    return h;
  }

  /** {@code host} as an address when it is a literal, else {@code null}. */
  static InetAddress literal(String host) {
    if (host.isEmpty() || !(host.indexOf(':') >= 0 || host.matches("[0-9.]+"))) {
      return null;
    }
    try {
      return InetAddress.ofLiteral(host);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The same grant, the same fingerprint: destinations, private ranges and closed ports, in any order. */
  static String fingerprint(Messages.EgressGrant grant) {
    StringBuilder b = new StringBuilder();
    new TreeSet<>(grant.tcp()).forEach(t -> b.append("tcp ").append(t).append('\n'));
    new TreeSet<>(grant.allowPrivate()).forEach(r -> b.append("private ").append(r).append('\n'));
    new TreeSet<>(grant.corePorts()).forEach(p -> b.append("core ").append(p).append('\n'));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
