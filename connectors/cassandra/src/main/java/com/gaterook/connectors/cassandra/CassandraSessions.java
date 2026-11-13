package com.gaterook.connectors.cassandra;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.auth.PlainTextAuthProviderBase;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.OptionsMap;
import com.datastax.oss.driver.api.core.config.TypedDriverOption;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.loadbalancing.NodeDistance;
import com.datastax.oss.driver.api.core.loadbalancing.NodeDistanceEvaluator;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.session.ProgrammaticArguments;
import com.datastax.oss.driver.api.core.session.Session;
import com.datastax.oss.driver.api.core.ssl.SslEngineFactory;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.datastax.oss.driver.internal.core.context.DefaultDriverContext;
import com.datastax.oss.driver.internal.core.context.StartupOptionsBuilder;
import com.datastax.oss.driver.internal.core.loadbalancing.DcInferringLoadBalancingPolicy;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connectors.support.BuiltIn;
import com.gaterook.connectors.support.TrustStores;
import com.gaterook.connectors.support.egress.AddressPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * One driver session per saved connection, opened on its first call and shared by its calls; another version of the
 * connection or of its login gets a new session, and the old one closes. A session idle for {@link #IDLE} closes on
 * the next call of any connection. Each session has its own small Netty groups (two I/O threads, one admin thread,
 * daemon), which the driver starts and stops: the connector starts no threads of its own.
 *
 * <p>What the driver is told, in code and nothing else ({@link DriverConfigLoader#fromMap}: no {@code application.conf},
 * no system properties): the local data center, plain-text sign-in from the connection's secret, TLS with the
 * connection's CA or the platform's and host name checks, server-side timestamps (a desktop clock never decides which
 * write wins), no metrics, no schema metadata of its own, no logging of server warnings (they go to the answer), and
 * an execution profile {@value #CHANGE} for approved changes: consistency {@code LOCAL_QUORUM}, {@link NeverRetry}, no
 * speculative executions.
 *
 * <p>Contact points are resolved here, once, and every address is judged like the egress guard's: link-local, cloud
 * metadata and reserved addresses are refused. Nodes the cluster announces later are judged by the same rule
 * ({@link #guard}): such a node is ignored, never connected to.
 */
final class CassandraSessions implements AutoCloseable {

  static final Duration IDLE = Duration.ofMinutes(10);
  static final String CHANGE = "change";
  private static final AddressPolicy ALWAYS = AddressPolicy.builder().build();

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  // ── Login ───────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The login of one call: the form's user and the stored password, or both from a store (a short-lived login, or a
   * user read from a store with its password). The password is a copy that {@link #close()} zeroes; every password
   * also joins the call's taint, so an echo of it is masked.
   *
   * @param password {@code null}: the cluster takes no sign-in
   */
  record Login(String user, char[] password, String version, Instant notAfter) implements AutoCloseable {

    @Override
    public void close() {
      if (password != null) {
        Arrays.fill(password, '\0');
      }
    }

    @Override
    public String toString() {
      return "Login[user=" + user + ", version=" + version + ", password=‹secret›]";
    }
  }

  static Login login(CassandraSettings s, SecretResolver secrets, ExecContext ctx) throws ConnectorException {
    String field = CassandraSettings.PASSWORD;
    if (secrets == null || !secrets.isSet(field)) {
      return new Login(s.user(), null, "none", null);
    }
    SecretResolver.Meta meta = secrets.meta(field);
    Login login;
    if ("dynamic".equals(meta.source()) || secrets.isSet(CassandraSettings.USER)) {
      try (SecretResolver.Login l = secrets.login(field)) {
        login = new Login(l.username(), l.password().chars().clone(), l.version(), l.notAfter());
      }
    } else {
      try (SecretResolver.Secret secret = secrets.secret(field)) {
        login = new Login(s.user(), secret.chars().clone(), secret.version(), secret.notAfter().orElse(null));
      }
    }
    if (login.user() == null || login.user().isBlank()) {
      login.close();
      throw ConnectorException.badInput("The connection has a password but no user: enter the role GateRook signs "
          + "in as.");
    }
    ctx.taint(new String(login.password()));
    return login;
  }

  // ── Sessions of saved connections ───────────────────────────────────────────────────────────────────────────

  private record Key(long configVersion, String loginVersion) {}

  private static final class Entry {
    final Key key;
    final Instant notAfter;
    volatile CompletableFuture<CqlSession> future;
    volatile long lastUsedNanos = System.nanoTime();

    Entry(Key key, Instant notAfter) {
      this.key = key;
      this.notAfter = notAfter;
    }

    boolean usable(Key wanted) {
      CompletableFuture<CqlSession> f = future;
      return key.equals(wanted) && f != null && !f.isCompletedExceptionally() && !f.isCancelled()
          && (notAfter == null || Instant.now().plusSeconds(30).isBefore(notAfter));
    }

    void close() {
      CompletableFuture<CqlSession> f = future;
      if (f != null) {
        f.whenComplete((session, error) -> {
          if (session != null) {
            session.closeAsync();
          }
        });
      }
    }
  }

  /** The time Auto may take to find the transport: two connect timeouts, never past the call's deadline. */
  static Duration autoBudget(CassandraSettings s, ExecContext ctx) {
    Duration budget = s.connectTimeout().multipliedBy(2);
    Duration left = ctx.remaining();
    return left.compareTo(budget) < 0 ? left : budget;
  }

  /** The session of a saved connection, opened within the call's deadline when there is none yet. */
  CqlSession session(ConnectionConfig cfg, CassandraSettings s, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    sweep();
    try (Login login = login(s, secrets, ctx)) {
      Key key = new Key(cfg.version(), login.version());
      Entry entry = entries.get(cfg.id());
      if (entry == null || !entry.usable(key)) {
        List<InetSocketAddress> points = contactPoints(s);
        // Connection type Auto: the transport is found once per session, before anything signs in.
        CassandraSettings resolved = AutoTls.resolve(s, points, autoBudget(s, ctx));
        Entry fresh = new Entry(key, login.notAfter());
        entry = entries.compute(cfg.id(), (id, old) -> {
          if (old != null && old.usable(key)) {
            return old;
          }
          if (old != null) {
            old.close();
          }
          fresh.future = open(cfg.id(), resolved, login, points);
          return fresh;
        });
      }
      entry.lastUsedNanos = System.nanoTime();
      return await(cfg.id(), entry, ctx);
    }
  }

  private CqlSession await(String id, Entry entry, ExecContext ctx) throws ConnectorException {
    long left = Math.max(1, ctx.remaining().toMillis());
    try {
      return entry.future.get(left, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw ConnectorException.timeout("The cluster didn't let GateRook connect within the call's time. Try again: "
          + "the connection goes on opening.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was stopped while GateRook connected to the cluster.");
    } catch (ExecutionException e) {
      entries.remove(id, entry);
      throw opening(e.getCause());
    }
  }

  /** Why a session didn't open, as the connection's problem. */
  static ConnectorException opening(Throwable error) {
    Throwable t = CassandraErrors.unwrap(error);
    if (t instanceof ConnectorException ce) {
      return ce;
    }
    if (t instanceof com.datastax.oss.driver.api.core.AllNodesFailedException all) {
      return CassandraErrors.connect(all);
    }
    if (t instanceof com.datastax.oss.driver.api.core.InvalidKeyspaceException) {
      return ConnectorException.resourceError("The connection's keyspace doesn't exist on the cluster: "
          + CassandraErrors.server(t) + ".", t);
    }
    return CassandraErrors.read(t, "Opening the connection");
  }

  /** Closes the session of a connection that changed or went away; its calls in flight end with it. */
  void evict(String connectionId) {
    Entry e = entries.remove(connectionId);
    if (e != null) {
      e.close();
    }
  }

  private void sweep() {
    long now = System.nanoTime();
    for (Map.Entry<String, Entry> e : entries.entrySet()) {
      if (now - e.getValue().lastUsedNanos > IDLE.toNanos() && entries.remove(e.getKey(), e.getValue())) {
        e.getValue().close();
      }
    }
  }

  @Override
  public void close() {
    for (String id : List.copyOf(entries.keySet())) {
      evict(id);
    }
  }

  // ── Opening ─────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The contact points' addresses: each name resolved once, here, and every address judged. A name with any address
   * that GateRook never connects to is refused as a whole, as the egress guard's DNS refuses it.
   */
  static List<InetSocketAddress> contactPoints(CassandraSettings s) throws ConnectorException {
    List<InetSocketAddress> out = new ArrayList<>();
    for (CassandraSettings.ContactPoint p : s.contactPoints()) {
      InetAddress[] addresses;
      try {
        addresses = InetAddress.getAllByName(p.host());
      } catch (UnknownHostException e) {
        throw ConnectorException.unavailable("The contact point " + p.host() + " doesn't resolve. Check its name and "
            + "your network or VPN.", null);
      }
      for (InetAddress a : addresses) {
        AddressPolicy.Verdict v = ALWAYS.check(p.host(), a, p.port());
        if (v.reason() == AddressPolicy.Reason.ALWAYS_BLOCKED) {
          throw ConnectorException.denied(v.text());
        }
      }
      for (InetAddress a : addresses) {
        out.add(new InetSocketAddress(a, p.port()));
      }
    }
    return out;
  }

  /**
   * Opens a session, without waiting for it: the driver connects on its own threads. Settings the driver refuses
   * fail the future, never the caller.
   *
   * @param login the call's login; the session keeps its own copy of the password
   */
  static CompletableFuture<CqlSession> open(String connectionId, CassandraSettings s, Login login,
      List<InetSocketAddress> points) {
    try {
      if (s.autoTls()) {
        throw new IllegalStateException("Connection type Auto must be resolved before a session opens");
      }
      CqlSessionBuilder b = builder()
          .withConfigLoader(DriverConfigLoader.fromMap(options(connectionId, s)))
          .withClassLoader(CassandraSessions.class.getClassLoader())
          .addContactPoints(points)
          .withNodeDistanceEvaluator(guard())
          .withApplicationName("GateRook")
          .withApplicationVersion(BuiltIn.VERSION);
      if (s.localDataCenter() != null) {
        b.withLocalDatacenter(s.localDataCenter());
      }
      if (login.password() != null) {
        b.withAuthProvider(new PasswordAuth(login.user(), login.password().clone()));
      }
      if (s.tls()) {
        b.withSslEngineFactory(new TlsEngines(TrustStores.sslContext(s.caCertificates().isEmpty()
            ? TrustStores.platform() : TrustStores.of(s.caCertificates(), false)), s.verifyHostname(),
            s.tlsServerName()));
      }
      if (s.keyspace() != null) {
        b.withKeyspace(CqlIdentifier.fromInternal(s.keyspace()));
      }
      return b.buildAsync().toCompletableFuture();
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * A session builder whose driver context sends its own STARTUP options. The driver's {@code StartupOptionsBuilder}
   * needs Jackson 2 for the "driver baggage" it adds there, and Jackson 2's databind isn't on GateRook's classpath
   * (ARCHITECTURE.md A9): {@link GatewayContext} sends what it sends but the baggage, and that class never loads.
   */
  static CqlSessionBuilder builder() {
    return new CqlSessionBuilder() {
      @Override
      protected DriverContext buildContext(DriverConfigLoader configLoader, ProgrammaticArguments arguments) {
        return new GatewayContext(configLoader, arguments);
      }
    };
  }

  /** The driver's own context, with the STARTUP options of {@link #startupOptions}. */
  static final class GatewayContext extends DefaultDriverContext {
    private final Map<String, String> startup;

    GatewayContext(DriverConfigLoader configLoader, ProgrammaticArguments arguments) {
      super(configLoader, arguments);
      this.startup = startupOptions();
    }

    @Override
    public Map<String, String> getStartupOptions() {
      return startup;
    }

    @Override
    protected StartupOptionsBuilder buildStartupOptionsFactory() {
      throw new UnsupportedOperationException("GateRook sends its own STARTUP options");
    }
  }

  /**
   * What the driver says about itself in STARTUP, as its {@code StartupOptionsBuilder} would without compression and
   * baggage: its name and version, a client id, and the application (GateRook and its version), which Cassandra 4 and
   * later show in {@code system_views.clients}. The protocol layer adds {@code CQL_VERSION}.
   */
  static Map<String, String> startupOptions() {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    out.put("DRIVER_NAME", Session.OSS_DRIVER_COORDINATES.getName());
    out.put("DRIVER_VERSION", Session.OSS_DRIVER_COORDINATES.getVersion().toString());
    out.put("CLIENT_ID", Uuids.random().toString());
    out.put("APPLICATION_NAME", "GateRook");
    out.put("APPLICATION_VERSION", BuiltIn.VERSION);
    return java.util.Collections.unmodifiableMap(out);
  }

  /** The driver's options for one connection: its defaults, then what GateRook decides. */
  static OptionsMap options(String connectionId, CassandraSettings s) {
    OptionsMap o = OptionsMap.driverDefaults();
    o.put(TypedDriverOption.SESSION_NAME, "gaterook-" + Integer.toHexString(connectionId.hashCode()));
    o.put(TypedDriverOption.CONFIG_RELOAD_INTERVAL, Duration.ZERO);
    o.put(TypedDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(10));
    o.put(TypedDriverOption.REQUEST_CONSISTENCY, s.readConsistency());
    o.put(TypedDriverOption.REQUEST_SERIAL_CONSISTENCY, "LOCAL_SERIAL");
    o.put(TypedDriverOption.REQUEST_PAGE_SIZE, 100);
    o.put(TypedDriverOption.REQUEST_DEFAULT_IDEMPOTENCE, false);
    o.put(TypedDriverOption.REQUEST_LOG_WARNINGS, false);
    if (s.localDataCenter() != null) {
      o.put(TypedDriverOption.LOAD_BALANCING_LOCAL_DATACENTER, s.localDataCenter());
    } else {
      // Only a draft's own session (setup suggestions): the contact points' data center, if they share one.
      o.put(TypedDriverOption.LOAD_BALANCING_POLICY_CLASS, DcInferringLoadBalancingPolicy.class.getName());
    }
    o.put(TypedDriverOption.CONNECTION_CONNECT_TIMEOUT, s.connectTimeout());
    o.put(TypedDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, s.connectTimeout());
    o.put(TypedDriverOption.CONNECTION_SET_KEYSPACE_TIMEOUT, s.connectTimeout());
    o.put(TypedDriverOption.CONTROL_CONNECTION_TIMEOUT, s.connectTimeout());
    o.put(TypedDriverOption.CONTROL_CONNECTION_AGREEMENT_TIMEOUT, Duration.ofSeconds(5));
    o.put(TypedDriverOption.CONNECTION_POOL_LOCAL_SIZE, s.connectionsPerNode());
    o.put(TypedDriverOption.RECONNECT_ON_INIT, false);
    o.put(TypedDriverOption.SESSION_LEAK_THRESHOLD, 0);
    o.put(TypedDriverOption.METRICS_FACTORY_CLASS, "NoopMetricsFactory");
    o.put(TypedDriverOption.MONITOR_REPORTING_ENABLED, false);
    o.put(TypedDriverOption.METADATA_SCHEMA_ENABLED, false);
    o.put(TypedDriverOption.METADATA_TOKEN_MAP_ENABLED, false);
    o.put(TypedDriverOption.TIMESTAMP_GENERATOR_CLASS, "ServerSideTimestampGenerator");
    o.put(TypedDriverOption.PROTOCOL_MAX_FRAME_LENGTH, 64L << 20);
    o.put(TypedDriverOption.NETTY_DAEMON, true);
    o.put(TypedDriverOption.NETTY_IO_SIZE, 2);
    o.put(TypedDriverOption.NETTY_ADMIN_SIZE, 1);
    o.put(TypedDriverOption.NETTY_IO_SHUTDOWN_QUIET_PERIOD, 0);
    o.put(TypedDriverOption.NETTY_IO_SHUTDOWN_TIMEOUT, 5);
    o.put(TypedDriverOption.NETTY_ADMIN_SHUTDOWN_QUIET_PERIOD, 0);
    o.put(TypedDriverOption.NETTY_ADMIN_SHUTDOWN_TIMEOUT, 5);
    o.put(TypedDriverOption.APPLICATION_NAME, "GateRook");
    o.put(TypedDriverOption.APPLICATION_VERSION, BuiltIn.VERSION);
    // Approved changes: one node, once.
    o.put(CHANGE, TypedDriverOption.REQUEST_CONSISTENCY, "LOCAL_QUORUM");
    o.put(CHANGE, TypedDriverOption.REQUEST_DEFAULT_IDEMPOTENCE, false);
    o.put(CHANGE, TypedDriverOption.RETRY_POLICY_CLASS, NeverRetry.class.getName());
    o.put(CHANGE, TypedDriverOption.SPECULATIVE_EXECUTION_POLICY_CLASS, "NoSpeculativeExecutionPolicy");
    return o;
  }

  /** Ignores a node at an address GateRook never connects to (link-local, metadata, reserved); the rest as usual. */
  static NodeDistanceEvaluator guard() {
    return (node, localDc) -> {
      SocketAddress a = node.getEndPoint().resolve();
      if (a instanceof InetSocketAddress isa && isa.getAddress() != null
          && ALWAYS.check(isa.getAddress(), isa.getPort()).reason() == AddressPolicy.Reason.ALWAYS_BLOCKED) {
        return NodeDistance.IGNORED;
      }
      return null;
    };
  }

  /** Plain-text sign-in with a copy of the password, zeroed when the session closes. */
  static final class PasswordAuth extends PlainTextAuthProviderBase {
    private final String user;
    private final char[] password;

    PasswordAuth(String user, char[] password) {
      super("gaterook");
      this.user = user;
      this.password = password;
    }

    @Override
    protected Credentials getCredentials(EndPoint endPoint, String serverAuthenticator) {
      // The driver clears what it gets after sending it: a fresh copy each time.
      return new Credentials(user.toCharArray(), password.clone());
    }

    @Override
    public void close() {
      Arrays.fill(password, '\0');
    }

    @Override
    public String toString() {
      return "PasswordAuth[user=" + user + ", password=‹secret›]";
    }
  }

  /**
   * TLS engines of one connection: the trust of {@code context}; with {@code verifyHostname} the certificate must
   * carry {@code serverName}, or else the node's own address as written (a contact point's name, a peer's IP — never
   * a reverse DNS lookup). {@code serverName} is also sent as SNI.
   */
  static final class TlsEngines implements SslEngineFactory {
    private final SSLContext context;
    private final boolean verifyHostname;
    private final String serverName;

    TlsEngines(SSLContext context, boolean verifyHostname, String serverName) {
      this.context = context;
      this.verifyHostname = verifyHostname;
      this.serverName = serverName;
    }

    @Override
    public SSLEngine newSslEngine(EndPoint endPoint) {
      SocketAddress address = endPoint.resolve();
      SSLEngine engine;
      if (address instanceof InetSocketAddress isa) {
        engine = context.createSSLEngine(serverName != null ? serverName : isa.getHostString(), isa.getPort());
      } else {
        engine = context.createSSLEngine();
      }
      engine.setUseClientMode(true);
      SSLParameters p = engine.getSSLParameters();
      if (verifyHostname) {
        p.setEndpointIdentificationAlgorithm("HTTPS");
      }
      if (serverName != null) {
        p.setServerNames(List.of(new SNIHostName(serverName)));
      }
      engine.setSSLParameters(p);
      return engine;
    }

    @Override
    public void close() {
      // The context holds no resources.
    }
  }
}
