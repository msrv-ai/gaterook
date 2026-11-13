package com.gaterook.pluginhost;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.host.ScopedEgress;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The TCP gate of a plugin that declares {@code access.tcp} (ARCHITECTURE.md §7.5): every {@code java.net.Socket} of
 * the process connects through a SOCKS5 listener on loopback inside the host, which lets through only what the grant
 * of the call that opens the socket allows ({@link TcpGrant}). A JDBC driver needs no socket factory for it: the
 * JVM-wide {@link ProxySelector} sends every {@code socket://} connection here, and the JVM-wide {@link Authenticator}
 * answers the gate's user/password request with the token of the grant bound to the connecting thread, on that
 * thread. Redirects a server sends the driver (client reroute, cluster nodes) are judged like the first connection.
 *
 * <p>A grant is bound to a call ({@link GatedEgress#run}): to the call's thread and to the threads it starts while it
 * runs. A socket opened by any other thread, or after the call ended, is refused. A connection the gate let through
 * stays open after the call — a pooled JDBC connection — until the plugin closes it, or the connection's grant
 * changes ({@link #register}), or the host ends.
 *
 * <p>What it doesn't judge: a socket made with an explicit proxy ({@code new Socket(Proxy.NO_PROXY)}), NIO channels
 * ({@code SocketChannel}, which the JDK's HTTP client uses), datagrams and native code. A plugin can go past the gate
 * with those, as it can past {@code ctx.egress()}: the gate binds the drivers a plugin bundles, it is no boundary
 * against a plugin's own code. The OS sandbox still closes GateRook's own ports to every socket of the host.
 */
final class TcpGate {

  static final int VERSION = 5;
  static final int USER_PASSWORD = 2;
  static final int NO_METHOD = 0xFF;
  static final int CONNECT = 1;
  static final int IPV4 = 1;
  static final int DOMAIN = 3;
  static final int IPV6 = 4;
  static final int OK = 0;
  static final int GENERAL_FAILURE = 1;
  static final int NOT_ALLOWED = 2;
  static final int HOST_UNREACHABLE = 4;
  static final int CONNECTION_REFUSED = 5;
  static final int TTL_EXPIRED = 6;
  static final int COMMAND_NOT_SUPPORTED = 7;
  static final int ADDRESS_NOT_SUPPORTED = 8;

  /** Connections the gate keeps open at once. */
  static final int MAX_TUNNELS = 256;
  /** How long a socket may take to say where it wants to go. */
  static final Duration HANDSHAKE = Duration.ofSeconds(10);
  /** The longest connect, whatever the call has left. */
  static final Duration MAX_CONNECT = Duration.ofSeconds(30);
  /** How long a closed grant is remembered, to explain a late socket. */
  static final Duration REMEMBER_CLOSED = Duration.ofMinutes(2);

  /** Set once the gate is the JVM's proxy selector: what a plugin that needs it checks. */
  static final String PROPERTY = "gaterook.tcpGate";

  /** The user name the authenticator sends from a thread with no grant. */
  static final String NO_GRANT = "-";

  static final ScopedValue<TcpGrant> CURRENT = ScopedValue.newInstance();
  static final InheritableThreadLocal<TcpGrant> INHERITED = new InheritableThreadLocal<>();
  /** The name the selector saw for the socket this thread is connecting, until the authenticator sends it. */
  private static final ThreadLocal<String> ASKED = new ThreadLocal<>();

  private static TcpGate installed;

  private final ServerSocket listener;
  private final Proxy proxy;
  private final Selector selector = new Selector();
  private final Credentials credentials;
  private final Map<String, TcpGrant> grants = new ConcurrentHashMap<>();
  private final Map<String, String> fingerprints = new ConcurrentHashMap<>();
  private final Set<Tunnel> tunnels = ConcurrentHashMap.newKeySet();
  private final AtomicInteger tunnelCount = new AtomicInteger();
  private final SecureRandom random = new SecureRandom();
  private final TcpGrant.Resolver resolver;
  private final Clock clock;
  private volatile Consumer<String> grantChanged = id -> {};
  private volatile boolean closed;

  TcpGate(TcpGrant.Resolver resolver, Clock clock) throws IOException {
    this.resolver = resolver;
    this.clock = clock;
    InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    this.listener = new ServerSocket(0, 64, loopback);
    this.proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(loopback, listener.getLocalPort()));
    this.credentials = new Credentials(Authenticator.getDefault());
    Thread.ofVirtual().name("tcp-gate").start(this::acceptLoop);
  }

  /**
   * The process's gate, started and made the JVM's proxy selector and authenticator the first time a plugin that
   * declares TCP destinations loads.
   */
  static synchronized TcpGate install() throws IOException {
    if (installed == null) {
      installed = new TcpGate(InetAddress::getAllByName, Clock.systemUTC());
    }
    installed.ensureDefaults();
    // A plugin can tell that its sockets are gated before it opens one (a gateway without the gate would let them go).
    System.setProperty(PROPERTY, "socks5");
    return installed;
  }

  /** The gate of this process, or {@code null} when its plugin declares no TCP destinations. */
  static synchronized TcpGate installed() {
    return installed;
  }

  int port() {
    return listener.getLocalPort();
  }

  ProxySelector selector() {
    return selector;
  }

  Authenticator authenticator() {
    return credentials;
  }

  /** What to do when a connection's grant changed: its plugin drops what it pooled for it. */
  void onGrantChanged(Consumer<String> action) {
    grantChanged = action == null ? id -> {} : action;
  }

  /**
   * Makes the gate the JVM's proxy selector and authenticator again if a plugin replaced either; true when they were
   * in place. A replaced selector lets the plugin's sockets past the gate until this runs, around every call.
   */
  boolean ensureDefaults() {
    boolean ok = ProxySelector.getDefault() == selector && Authenticator.getDefault() == credentials;
    if (!ok) {
      ProxySelector.setDefault(selector);
      Authenticator.setDefault(credentials);
    }
    return ok;
  }

  /**
   * A grant for one call of {@code cfg} until {@code deadline}. When the connection's grant differs from the one its
   * last call had (a policy or configuration change), the connections opened under the old one are closed and the
   * plugin drops its pools for the connection first.
   */
  TcpGrant register(Messages.EgressGrant grant, ConnectionConfig cfg, Instant deadline) {
    purge();
    Set<Integer> closedPorts = new HashSet<>(grant.corePorts());
    closedPorts.add(port());
    TcpGrant g = new TcpGrant(token(), grant, cfg, closedPorts, deadline);
    if (g.connectionId() != null) {
      String before = fingerprints.put(g.connectionId(), g.fingerprint());
      if (before != null && !before.equals(g.fingerprint())) {
        closeTunnels(g.connectionId(), g.fingerprint());
        grantChanged.accept(g.connectionId());
      }
    }
    grants.put(g.token(), g);
    return g;
  }

  /** The grant of the calling thread: the call's, or the one a thread inherited from the call that started it. */
  static TcpGrant current() {
    return CURRENT.isBound() ? CURRENT.get() : INHERITED.get();
  }

  private String token() {
    byte[] b = new byte[16];
    random.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  private void purge() {
    Instant now = clock.instant();
    grants.values().removeIf(g -> !g.open() && g.closedAt() != null
        && g.closedAt().plus(REMEMBER_CLOSED).isBefore(now));
  }

  void ended(TcpGrant g) {
    g.close(clock.instant());
  }

  int openTunnels() {
    return tunnelCount.get();
  }

  /** Closes the connections of {@code connectionId} opened under a grant other than {@code keep}. */
  void closeTunnels(String connectionId, String keep) {
    for (Tunnel t : tunnels) {
      if (connectionId.equals(t.connectionId) && !t.fingerprint.equals(keep)) {
        t.close();
      }
    }
  }

  void close() {
    closed = true;
    try {
      listener.close();
    } catch (IOException ignored) {
      // Closing.
    }
    tunnels.forEach(Tunnel::close);
  }

  // ── SOCKS5 ──────────────────────────────────────────────────────────────────────────────────────────────────

  private void acceptLoop() {
    while (!closed) {
      Socket client;
      try {
        client = listener.accept();
      } catch (IOException e) {
        if (!closed) {
          HostLog.line("tcp-gate", System.Logger.Level.WARNING, "The TCP gate stopped: " + e.getMessage());
        }
        return;
      }
      Thread.ofVirtual().name("tcp-gate-handshake").start(() -> serve(client));
    }
  }

  /** One socket of the plugin: greeting, user/password, CONNECT, then the bytes both ways. */
  void serve(Socket client) {
    Socket upstream = null;
    try {
      client.setSoTimeout((int) HANDSHAKE.toMillis());
      client.setTcpNoDelay(true);
      DataInputStream in = new DataInputStream(client.getInputStream());
      OutputStream out = client.getOutputStream();
      if (in.readUnsignedByte() != VERSION) {
        client.close();
        return;
      }
      byte[] methods = exactly(in, in.readUnsignedByte());
      boolean userPassword = false;
      for (byte m : methods) {
        userPassword |= m == USER_PASSWORD;
      }
      out.write(new byte[] {VERSION, (byte) (userPassword ? USER_PASSWORD : NO_METHOD)});
      out.flush();
      if (!userPassword) {
        client.close();
        return;
      }
      int authVersion = in.readUnsignedByte();
      String user = new String(exactly(in, in.readUnsignedByte()), StandardCharsets.UTF_8);
      String asked = new String(exactly(in, in.readUnsignedByte()), StandardCharsets.UTF_8);
      // Any user passes this step: a refusal comes with CONNECT, so the driver says "not allowed by ruleset".
      out.write(new byte[] {1, (byte) (authVersion == 1 ? 0 : 1)});
      out.flush();
      if (authVersion != 1) {
        client.close();
        return;
      }
      if (in.readUnsignedByte() != VERSION) {
        client.close();
        return;
      }
      int command = in.readUnsignedByte();
      in.readUnsignedByte();
      int type = in.readUnsignedByte();
      String name = null;
      InetAddress ip = null;
      switch (type) {
        case IPV4 -> ip = InetAddress.getByAddress(exactly(in, 4));
        case IPV6 -> ip = InetAddress.getByAddress(exactly(in, 16));
        case DOMAIN -> name = new String(exactly(in, in.readUnsignedByte()), StandardCharsets.US_ASCII);
        default -> {
          reply(out, ADDRESS_NOT_SUPPORTED);
          client.close();
          return;
        }
      }
      int port = in.readUnsignedShort();
      if (command != CONNECT) {
        reply(out, COMMAND_NOT_SUPPORTED);
        client.close();
        return;
      }
      TcpGrant grant = NO_GRANT.equals(user) ? null : grants.get(user);
      if (grant == null) {
        String where = (name != null ? name : ip.getHostAddress()) + ":" + port;
        HostLog.line("tcp-gate", System.Logger.Level.WARNING, "Refused a connection to " + where + " that no call "
            + "of the plugin opened (a thread GateRook didn't start for a call, or a replaced authenticator)");
        reply(out, NOT_ALLOWED);
        client.close();
        return;
      }
      if (tunnelCount.get() >= MAX_TUNNELS) {
        refuse(grant, new ScopedEgress.Denial(ScopedEgress.Reason.LIMIT, (name != null ? name : ip.getHostAddress())
            + ":" + port, "The " + HostVoice.noun() + " has " + MAX_TUNNELS + " connections open, the most it may.", null, null), out);
        client.close();
        return;
      }
      TcpGrant.Verdict v = grant.judge(name, ip, asked.isEmpty() ? null : asked, port, resolver);
      if (v.denial() != null) {
        refuse(grant, v.denial(), out);
        client.close();
        return;
      }
      if (v.unresolvable()) {
        reply(out, HOST_UNREACHABLE);
        client.close();
        return;
      }
      int code = GENERAL_FAILURE;
      Duration timeout = connectTimeout(grant);
      for (InetAddress a : v.addresses()) {
        Socket s = new Socket(Proxy.NO_PROXY);
        try {
          s.setTcpNoDelay(true);
          s.setKeepAlive(true);
          s.connect(new InetSocketAddress(a, port), (int) Math.max(1, timeout.toMillis()));
          upstream = s;
          break;
        } catch (SocketTimeoutException e) {
          code = TTL_EXPIRED;
        } catch (ConnectException e) {
          code = CONNECTION_REFUSED;
        } catch (NoRouteToHostException e) {
          code = HOST_UNREACHABLE;
        } catch (IOException e) {
          code = GENERAL_FAILURE;
        }
        closeQuietly(s);
      }
      if (upstream == null) {
        reply(out, code);
        client.close();
        return;
      }
      reply(out, OK);
      client.setSoTimeout(0);
      Tunnel t = new Tunnel(client, upstream, grant.connectionId(), grant.fingerprint());
      tunnels.add(t);
      tunnelCount.incrementAndGet();
      t.start();
      upstream = null;
    } catch (IOException | RuntimeException e) {
      closeQuietly(client);
    } finally {
      closeQuietly(upstream);
    }
  }

  private void refuse(TcpGrant grant, ScopedEgress.Denial d, OutputStream out) throws IOException {
    grant.record(d);
    HostLog.line("tcp-gate", System.Logger.Level.WARNING, "Refused a connection to " + d.destination() + ": "
        + d.text());
    reply(out, NOT_ALLOWED);
  }

  private Duration connectTimeout(TcpGrant grant) {
    Duration left = grant.deadline() == null ? MAX_CONNECT : Duration.between(clock.instant(), grant.deadline());
    if (left.isNegative() || left.isZero()) {
      return Duration.ofMillis(1);
    }
    return left.compareTo(MAX_CONNECT) > 0 ? MAX_CONNECT : left;
  }

  /** A reply without a bound address: 0.0.0.0:0 says nothing of the gate's own side. */
  private static void reply(OutputStream out, int code) throws IOException {
    out.write(new byte[] {VERSION, (byte) code, 0, IPV4, 0, 0, 0, 0, 0, 0});
    out.flush();
  }

  private static byte[] exactly(DataInputStream in, int n) throws IOException {
    byte[] b = in.readNBytes(n);
    if (b.length != n) {
      throw new EOFException();
    }
    return b;
  }

  private static void closeQuietly(Socket s) {
    if (s != null) {
      try {
        s.close();
      } catch (IOException ignored) {
        // Closing.
      }
    }
  }

  /** The bytes of one connection both ways; an end of either direction is passed on, an error closes both. */
  private final class Tunnel {
    private final Socket client;
    private final Socket upstream;
    private final String connectionId;
    private final String fingerprint;
    private final AtomicInteger running = new AtomicInteger(2);
    private volatile boolean done;

    Tunnel(Socket client, Socket upstream, String connectionId, String fingerprint) {
      this.client = client;
      this.upstream = upstream;
      this.connectionId = connectionId;
      this.fingerprint = fingerprint == null ? "" : fingerprint;
    }

    void start() {
      Thread.ofVirtual().name("tcp-gate-out").start(() -> pump(client, upstream));
      Thread.ofVirtual().name("tcp-gate-in").start(() -> pump(upstream, client));
    }

    /** Copies until {@code from} ends, then ends {@code to}'s output; closing a socket's stream would close both. */
    private void pump(Socket from, Socket to) {
      try {
        InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) >= 0) {
          out.write(buffer, 0, n);
          out.flush();
        }
        if (!to.isClosed()) {
          to.shutdownOutput();
        }
      } catch (IOException e) {
        close();
      } finally {
        if (running.decrementAndGet() == 0) {
          close();
        }
      }
    }

    void close() {
      if (done) {
        return;
      }
      done = true;
      closeQuietly(client);
      closeQuietly(upstream);
      if (tunnels.remove(this)) {
        tunnelCount.decrementAndGet();
      }
    }
  }

  // ── The JVM's side ──────────────────────────────────────────────────────────────────────────────────────────

  /** Sends every {@code socket://} connection to the gate and passes the name it was opened for to the authenticator. */
  private final class Selector extends ProxySelector {

    /**
     * A {@code null} URI goes to the gate too, whose judgement needs no name: the JDK's SOCKS client passes one when
     * it can't write the address as a URI (an IPv4-mapped literal, {@code ::ffff:10.0.0.5}).
     */
    @Override
    public List<Proxy> select(URI uri) {
      if (uri == null) {
        ASKED.remove();
        return List.of(proxy);
      }
      if (!"socket".equalsIgnoreCase(uri.getScheme())) {
        return List.of(Proxy.NO_PROXY);
      }
      ASKED.set(hostOf(uri));
      return List.of(proxy);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
      // The gate is the only route: nothing to try instead.
    }
  }

  /** The host of {@code socket://host:port}, also where {@link URI#getHost} gives none (a name with {@code _}). */
  static String hostOf(URI uri) {
    String host = uri.getHost();
    if (host == null) {
      String authority = uri.getRawAuthority();
      if (authority == null) {
        return "";
      }
      int colon = authority.lastIndexOf(':');
      host = colon > 0 && !authority.endsWith("]") ? authority.substring(0, colon) : authority;
    }
    return TcpGrant.normalize(host);
  }

  /**
   * Answers the gate's SOCKS5 request with the token of the calling thread's grant and the name the socket was opened
   * for; anything else goes to the authenticator that was there before.
   */
  private final class Credentials extends Authenticator {
    private final Authenticator before;

    Credentials(Authenticator before) {
      this.before = before;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
      InetAddress site = getRequestingSite();
      if ("SOCKS5".equals(getRequestingProtocol()) && getRequestingPort() == port() && site != null
          && site.isLoopbackAddress()) {
        TcpGrant g = current();
        String asked = ASKED.get();
        ASKED.remove();
        String name = asked == null ? "" : asked;
        if (name.length() > 255) {
          name = "";
        }
        return new PasswordAuthentication(g == null ? NO_GRANT : g.token(), name.toCharArray());
      }
      if (before == null) {
        return null;
      }
      return before.requestPasswordAuthenticationInstance(getRequestingHost(), site, getRequestingPort(),
          getRequestingProtocol(), getRequestingPrompt(), getRequestingScheme(), getRequestingURL(),
          getRequestorType());
    }
  }
}
