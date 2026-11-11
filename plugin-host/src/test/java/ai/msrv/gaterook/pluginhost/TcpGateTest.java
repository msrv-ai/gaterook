package ai.msrv.gaterook.pluginhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.host.ScopedEgress;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The TCP gate with real sockets: what a plugin's own {@code java.net.Socket} reaches during a call, what it never
 * reaches, and that the bytes pass unchanged both ways.
 */
class TcpGateTest {

  private static final List<Integer> CORE_PORTS = List.of(7465, 7466);
  private static final Env DEV = Env.of("dev");

  private final Map<String, InetAddress[]> names = new ConcurrentHashMap<>();
  private final List<String> invalidated = new CopyOnWriteArrayList<>();
  private ProxySelector selectorBefore;
  private Authenticator authenticatorBefore;
  private TcpGate gate;
  private ServerSocket echo;

  @BeforeEach
  void start() throws IOException {
    selectorBefore = ProxySelector.getDefault();
    authenticatorBefore = Authenticator.getDefault();
    gate = new TcpGate(this::resolve, Clock.systemUTC());
    gate.ensureDefaults();
    gate.onGrantChanged(invalidated::add);
    echo = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
    Thread.ofVirtual().start(this::echoLoop);
  }

  @AfterEach
  void stop() throws IOException {
    gate.close();
    echo.close();
    ProxySelector.setDefault(selectorBefore);
    Authenticator.setDefault(authenticatorBefore);
  }

  private InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] a = names.get(host);
    if (a == null) {
      throw new UnknownHostException(host);
    }
    return a;
  }

  /** Answers each line with the line upper-cased, and says "bye" once the other side stops sending. */
  private void echoLoop() {
    while (!echo.isClosed()) {
      try {
        Socket s = echo.accept();
        Thread.ofVirtual().start(() -> {
          try (s) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            String line;
            while ((line = in.readLine()) != null) {
              out.write((line.toUpperCase() + "\n").getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
            out.write("bye\n".getBytes(StandardCharsets.UTF_8));
          } catch (IOException ignored) {
            // The test closed it.
          }
        });
      } catch (IOException e) {
        return;
      }
    }
  }

  private static ConnectionConfig connection(String id) {
    return new ConnectionConfig(id, "warehouse", "jdbc", DEV, 1, JsonNodeFactory.instance.objectNode(),
        java.util.Set.of(), null);
  }

  private GatedEgress call(String id, List<String> tcp, List<String> allowPrivate) {
    Messages.EgressGrant grant = new Messages.EgressGrant(List.of(), allowPrivate, CORE_PORTS, tcp);
    return new GatedEgress(null, gate, gate.register(grant, connection(id), Instant.now().plusSeconds(10)));
  }

  /** A socket to an unresolved {@code host}, which reaches the gate by name. */
  private static Socket byName(String host, int port) throws IOException {
    Socket s = new Socket();
    try {
      s.connect(InetSocketAddress.createUnresolved(host, port), 5_000);
      return s;
    } catch (IOException e) {
      s.close();
      throw e;
    }
  }

  private static String talk(Socket s, String line) throws IOException {
    s.setSoTimeout(5_000);
    OutputStream out = s.getOutputStream();
    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
    return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
  }

  @Test
  void aGrantedNameConnectsAndTheBytesPassBothWays() throws Exception {
    names.put("db.acme.internal", new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")});
    int port = echo.getLocalPort();
    try (GatedEgress e = call("c1", List.of("db.acme.internal:" + port), List.of("127.0.0.0/8"))) {
      String answer = e.run(() -> {
        try (Socket s = new Socket()) {
          // An unresolved address reaches the gate by name, as a driver's InetSocketAddress.createUnresolved does.
          s.connect(InetSocketAddress.createUnresolved("db.acme.internal", port), 5_000);
          String first = talk(s, "select 1");
          s.shutdownOutput();
          String last = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
              .readLine();
          return first + "|" + last;
        }
      });
      assertThat(answer).isEqualTo("SELECT 1|bye");
      assertThat(e.lastDenial()).isEmpty();
    }
  }

  @Test
  void anAddressTheDriverResolvedPassesAsTheGrantedNameItStandsFor() throws Exception {
    names.put("db.acme.internal", new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")});
    int port = echo.getLocalPort();
    try (GatedEgress e = call("c1", List.of("db.acme.internal:" + port), List.of("127.0.0.0/8"))) {
      String answer = e.run(() -> {
        // new InetSocketAddress(name, port) resolves first: the gate gets 127.0.0.1 and the name from the selector.
        try (Socket s = new Socket()) {
          s.connect(new InetSocketAddress(InetAddress.getByAddress("db.acme.internal",
              new byte[] {127, 0, 0, 1}), port), 5_000);
          return talk(s, "ping");
        }
      });
      assertThat(answer).isEqualTo("PING");
    }
  }

  @Test
  void anotherPortOrHostIsRefusedWithTheConnectionsDestinations() throws Exception {
    names.put("db.acme.internal", new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")});
    names.put("other.acme.internal", new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")});
    int port = echo.getLocalPort();
    try (GatedEgress e = call("c1", List.of("db.acme.internal:" + (port + 1)), List.of("127.0.0.0/8"))) {
      assertThatThrownBy(() -> e.run(() -> {
        try (Socket s = new Socket()) {
          s.connect(InetSocketAddress.createUnresolved("db.acme.internal", port), 5_000);
        }
        return null;
      })).isInstanceOf(SocketException.class).hasMessageContaining("not allowed by ruleset");
      assertThat(e.lastDenial()).get().satisfies(d -> {
        assertThat(d.reason()).isEqualTo(ScopedEgress.Reason.NOT_GRANTED);
        assertThat(d.text()).isEqualTo("This plugin may only connect to db.acme.internal:" + (port + 1)
            + " for this connection, not db.acme.internal:" + port + ".");
      });
      assertThatThrownBy(() -> e.run(() -> byName("other.acme.internal", port + 1)))
          .hasMessageContaining("not allowed by ruleset");
    }
  }

  @Test
  void aPrivateAddressNeedsTheEnvironmentsPolicyAndTheDenialSaysWhichRange() throws Exception {
    names.put("db.acme.internal", new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")});
    int port = echo.getLocalPort();
    try (GatedEgress e = call("c1", List.of("db.acme.internal:" + port), List.of())) {
      assertThatThrownBy(() -> e.run(() -> byName("db.acme.internal", port)))
          .hasMessageContaining("not allowed by ruleset");
      ScopedEgress.Denial d = e.lastDenial().orElseThrow();
      assertThat(d.reason()).isEqualTo(ScopedEgress.Reason.PRIVATE_ADDRESS);
      assertThat(d.suggestedCidr()).isEqualTo("127.0.0.1/32");
      assertThat(d.connection()).isEqualTo("warehouse");
      assertThat(d.text()).isEqualTo("db.acme.internal resolves to 127.0.0.1, a private address. Allow 127.0.0.1/32 "
          + "for warehouse on dev in the environment's policy (http.allowedPrivateRanges).");
    }
  }

  @Test
  void oneAddressThePolicyClosesRefusesTheName() throws Exception {
    names.put("db.acme.internal", new InetAddress[] {InetAddress.ofLiteral("127.0.0.1"),
        InetAddress.ofLiteral("10.9.9.9")});
    int port = echo.getLocalPort();
    try (GatedEgress e = call("c1", List.of("db.acme.internal:" + port), List.of("127.0.0.0/8"))) {
      assertThatThrownBy(() -> e.run(() -> {
        try (Socket s = new Socket()) {
          s.connect(InetSocketAddress.createUnresolved("db.acme.internal", port), 5_000);
        }
        return null;
      })).hasMessageContaining("not allowed by ruleset");
      assertThat(e.lastDenial().orElseThrow().text()).startsWith("db.acme.internal resolves to 10.9.9.9");
    }
  }

  @Test
  void metadataLinkLocalAndTheCoresPortsAreNeverReached() throws Exception {
    names.put("db.acme.internal", new InetAddress[] {InetAddress.ofLiteral("169.254.169.254")});
    try (GatedEgress e = call("c1", List.of("db.acme.internal:80", "metadata.google.internal:80",
        "127.0.0.1:7465", "[::ffff:169.254.169.254]:80"), List.of("0.0.0.0/0", "::/0", "127.0.0.0/8"))) {
      for (String host : List.of("db.acme.internal", "metadata.google.internal")) {
        assertThatThrownBy(() -> e.run(() -> {
          try (Socket s = new Socket()) {
            s.connect(InetSocketAddress.createUnresolved(host, 80), 5_000);
          }
          return null;
        })).as(host).hasMessageContaining("not allowed by ruleset");
        assertThat(e.lastDenial().orElseThrow().reason()).as(host).isEqualTo(ScopedEgress.Reason.ALWAYS_BLOCKED);
      }
      assertThatThrownBy(() -> e.run(() -> new Socket("127.0.0.1", 7465))).hasMessageContaining("ruleset");
      assertThat(e.lastDenial().orElseThrow().reason()).isEqualTo(ScopedEgress.Reason.CORE_PORT);
      assertThatThrownBy(() -> e.run(() -> new Socket("::ffff:169.254.169.254", 80))).hasMessageContaining("ruleset");
      assertThat(e.lastDenial().orElseThrow().reason()).isEqualTo(ScopedEgress.Reason.ALWAYS_BLOCKED);
      // The gate's own port is closed like the core's.
      assertThatThrownBy(() -> e.run(() -> new Socket("127.0.0.1", gate.port()))).hasMessageContaining("ruleset");
    }
  }

  @Test
  void aSocketOutsideACallIsRefused() throws Exception {
    int port = echo.getLocalPort();
    try (GatedEgress e = call("c1", List.of("127.0.0.1:" + port), List.of("127.0.0.0/8"))) {
      assertThat(e.run(() -> {
        try (Socket s = new Socket("127.0.0.1", port)) {
          return talk(s, "inside");
        }
      })).isEqualTo("INSIDE");
    }
    assertThatThrownBy(() -> new Socket("127.0.0.1", port)).isInstanceOf(SocketException.class)
        .hasMessageContaining("not allowed by ruleset");
  }

  @Test
  void aThreadTheCallStartedConnectsDuringTheCallButNotAfterIt() throws Exception {
    int port = echo.getLocalPort();
    AtomicReference<Thread> later = new AtomicReference<>();
    List<String> results = new CopyOnWriteArrayList<>();
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    GatedEgress e = call("c1", List.of("127.0.0.1:" + port), List.of("127.0.0.0/8"));
    e.run(() -> {
      Thread during = Thread.ofPlatform().start(() -> results.add(connect(port)));
      during.join();
      later.set(Thread.ofPlatform().start(() -> {
        try {
          go.await();
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
        results.add(connect(port));
      }));
      return null;
    });
    e.close();
    go.countDown();
    later.get().join();
    assertThat(results).containsExactly("UP", "refused");
    assertThat(e.lastDenial().orElseThrow().reason()).isEqualTo(ScopedEgress.Reason.OUTSIDE_CALL);
  }

  private static String connect(int port) {
    try (Socket s = new Socket("127.0.0.1", port)) {
      return talk(s, "up");
    } catch (IOException e) {
      return e.getMessage().contains("ruleset") ? "refused" : e.toString();
    }
  }

  @Test
  void aChangedGrantClosesTheConnectionsOpenedUnderTheOldOne() throws Exception {
    int port = echo.getLocalPort();
    Socket kept;
    try (GatedEgress e = call("c1", List.of("127.0.0.1:" + port), List.of("127.0.0.0/8"))) {
      kept = e.run(() -> new Socket("127.0.0.1", port));
      assertThat(talk(kept, "pooled")).isEqualTo("POOLED");
    }
    // The same grant again keeps the pooled connection.
    call("c1", List.of("127.0.0.1:" + port), List.of("127.0.0.0/8")).close();
    assertThat(talk(kept, "still")).isEqualTo("STILL");
    assertThat(invalidated).isEmpty();
    // The policy no longer allows loopback: the connection is cut and the plugin drops its pool.
    call("c1", List.of("127.0.0.1:" + port), List.of()).close();
    assertThat(invalidated).containsExactly("c1");
    kept.setSoTimeout(5_000);
    InputStream in = kept.getInputStream();
    assertThat(readOrEnd(in)).isEqualTo(-1);
    kept.close();
  }

  private static int readOrEnd(InputStream in) {
    try {
      return in.read();
    } catch (IOException e) {
      return -1;
    }
  }

  @Test
  void theSelectorRoutesOnlyPlainSocketsToTheGate() {
    ProxySelector selector = gate.selector();
    assertThat(selector.select(URI.create("http://db.acme.internal/"))).containsExactly(Proxy.NO_PROXY);
    assertThat(selector.select(URI.create("https://db.acme.internal/"))).containsExactly(Proxy.NO_PROXY);
    List<Proxy> socket = selector.select(URI.create("socket://db.acme.internal:5000"));
    assertThat(socket).hasSize(1);
    assertThat(socket.getFirst().type()).isEqualTo(Proxy.Type.SOCKS);
    assertThat(((InetSocketAddress) socket.getFirst().address()).getPort()).isEqualTo(gate.port());
    assertThat(TcpGate.hostOf(URI.create("socket://my_db.acme.internal:5000"))).isEqualTo("my_db.acme.internal");
    assertThat(TcpGate.hostOf(URI.create("socket://[::1]:5000"))).isEqualTo("::1");
  }

  @Test
  void aReplacedSelectorIsRestoredAroundACall() throws Exception {
    int port = echo.getLocalPort();
    try (GatedEgress e = call("c1", List.of("127.0.0.1:" + port), List.of("127.0.0.0/8"))) {
      e.run(() -> {
        ProxySelector.setDefault(new ProxySelector() {
          @Override
          public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
          }

          @Override
          public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {}
        });
        return null;
      });
      assertThat(ProxySelector.getDefault()).isSameAs(gate.selector());
      assertThat(Authenticator.getDefault()).isSameAs(gate.authenticator());
    }
  }

  @Test
  void literalDestinationsAndIpv6() throws Exception {
    List<String> entries = new ArrayList<>(List.of("[::1]:5000", "10.0.0.5:5000", "db.acme.internal:5000"));
    TcpGrant g = new TcpGrant("t", new Messages.EgressGrant(List.of(), List.of("10.0.0.0/8", "::1/128"),
        CORE_PORTS, entries), connection("c1"), java.util.Set.of(7465), Instant.now().plusSeconds(5));
    assertThat(g.judge(null, InetAddress.ofLiteral("::1"), "::1", 5000, this::resolve).addresses())
        .containsExactly(InetAddress.ofLiteral("::1"));
    assertThat(g.judge(null, InetAddress.ofLiteral("10.0.0.5"), null, 5000, this::resolve).addresses())
        .containsExactly(InetAddress.ofLiteral("10.0.0.5"));
    // An address of no granted name, whatever name the selector saw.
    assertThat(g.judge(null, InetAddress.ofLiteral("10.0.0.6"), "db.acme.internal", 5000, this::resolve).denial()
        .reason()).isEqualTo(ScopedEgress.Reason.NOT_GRANTED);
    assertThat(g.judge("DB.Acme.Internal.", null, null, 5000, this::resolve).unresolvable()).isTrue();
    assertThat(TcpGrant.Destination.parse("[::1]:5000")).get().extracting(TcpGrant.Destination::text)
        .isEqualTo("[::1]:5000");
    assertThat(TcpGrant.Destination.parse("db:0")).isEmpty();
    assertThat(TcpGrant.Destination.parse("::1:5000")).isEmpty();
    assertThat(TcpGrant.cidrOf(InetAddress.ofLiteral("::ffff:10.1.2.3"))).isEqualTo("10.1.2.3/32");
    assertThat(TcpGrant.cidrOf(InetAddress.ofLiteral("fd12::7"))).isEqualTo("fd12:0:0:0:0:0:0:7/128");
  }

  @Test
  void theSameGrantInAnyOrderHasTheSameFingerprint() {
    Messages.EgressGrant a = new Messages.EgressGrant(List.of(), List.of("10.0.0.0/8", "127.0.0.0/8"), CORE_PORTS,
        List.of("a:1", "b:2"));
    Messages.EgressGrant b = new Messages.EgressGrant(List.of(), List.of("127.0.0.0/8", "10.0.0.0/8"),
        List.of(7466, 7465), List.of("b:2", "a:1"));
    Messages.EgressGrant c = new Messages.EgressGrant(List.of(), List.of("10.0.0.0/8"), CORE_PORTS,
        List.of("a:1", "b:2"));
    assertThat(TcpGrant.fingerprint(a)).isEqualTo(TcpGrant.fingerprint(b)).isNotEqualTo(TcpGrant.fingerprint(c));
  }

  @Test
  void aClientThatOffersNoUserAndPasswordGetsNoMethod() throws Exception {
    // Another process on this computer, which knows no token, gets nothing either.
    try (Socket raw = new Socket(Proxy.NO_PROXY)) {
      raw.connect(new InetSocketAddress("127.0.0.1", gate.port()), 2_000);
      raw.setSoTimeout(5_000);
      OutputStream out = raw.getOutputStream();
      out.write(new byte[] {5, 1, 0});
      out.flush();
      byte[] answer = raw.getInputStream().readNBytes(2);
      assertThat(answer).containsExactly(5, (byte) 0xFF);
    }
  }
}
