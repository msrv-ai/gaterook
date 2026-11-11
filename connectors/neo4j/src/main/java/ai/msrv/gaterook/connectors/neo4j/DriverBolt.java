package ai.msrv.gaterook.connectors.neo4j;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;
import org.neo4j.driver.NotificationSeverity;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.summary.GqlNotification;
import org.neo4j.driver.summary.GqlStatusObject;
import org.neo4j.driver.summary.QueryType;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.summary.SummaryCounters;

/**
 * {@link Bolt} with the official Neo4j Java driver: one {@link Driver} per version of a connection and its sign-in,
 * closed once its calls end after the connection changes, and after ten idle minutes (checked when a call comes, so no
 * thread of its own). A driver has one event-loop thread and a pool of five connections; it never logs (queries and
 * parameters stay out of the gateway's log), sends no telemetry, and identifies itself as GateRook.
 *
 * <p>A private CA reaches the driver as a file: it is written to the connector's work folder, read by the driver while
 * it is built, and deleted right after. A certificate isn't a secret, but the file lives for that moment only.
 *
 * <p>Before a driver is built, the host must not resolve to an address no connection ever reaches (link-local and
 * cloud metadata, reserved ranges): the egress guard's fixed list. A {@code neo4j://} driver then follows the routing
 * table of the cluster, which names the servers of its members.
 */
final class DriverBolt implements Bolt.Source {

  static final Duration IDLE = Duration.ofMinutes(10);
  static final int POOL = 5;
  private static final AddressPolicy ALWAYS = AddressPolicy.builder().build();

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private volatile boolean closed;

  private static final class Entry {
    final long configVersion;
    final String authKey;
    final Driver driver;
    final AtomicInteger inFlight = new AtomicInteger();
    volatile long lastUsedNanos = System.nanoTime();
    volatile boolean retired;
    private boolean done;

    Entry(long configVersion, String authKey, Driver driver) {
      this.configVersion = configVersion;
      this.authKey = authKey;
      this.driver = driver;
    }

    synchronized void closeIfIdle() {
      if (retired && !done && inFlight.get() == 0) {
        done = true;
        driver.closeAsync();
      }
    }
  }

  @Override
  public Bolt.Lease lease(ConnectionConfig cfg, Neo4jSettings settings, Neo4jAuth auth, ExecContext ctx)
      throws ConnectorException {
    if (closed) {
      throw ConnectorException.unavailable("The Neo4j connector is shutting down.", null);
    }
    sweep();
    String authKey = auth.method() + "|" + auth.user() + "|" + auth.version();
    synchronized (this) {
      Entry current = entries.get(cfg.id());
      if (usable(current, cfg, authKey)) {
        current.inFlight.incrementAndGet();
        return lease(current);
      }
    }
    // Built outside the lock: resolving the host must not hold up the calls of other connections.
    Entry candidate = new Entry(cfg.version(), authKey, open(settings, auth, ctx));
    Entry stale = null;
    Entry use;
    boolean rejected;
    synchronized (this) {
      rejected = closed;
      Entry current = entries.get(cfg.id());
      if (!rejected && usable(current, cfg, authKey)) {
        use = current;
      } else {
        use = candidate;
        if (!rejected && (current == null || current.configVersion <= cfg.version())) {
          stale = entries.put(cfg.id(), candidate);
        } else {
          // A newer version or the shutdown won: this call may finish, but its driver isn't kept.
          candidate.retired = true;
        }
      }
      use.inFlight.incrementAndGet();
    }
    retire(stale);
    if (use != candidate) {
      retire(candidate);
    }
    return lease(use);
  }

  private static boolean usable(Entry e, ConnectionConfig cfg, String authKey) {
    return e != null && !e.retired && e.configVersion == cfg.version() && e.authKey.equals(authKey);
  }

  private static Bolt.Lease lease(Entry leased) {
    return new Bolt.Lease() {
      private boolean released;

      @Override
      public Bolt.Client client() {
        return new DriverClient(leased.driver);
      }

      @Override
      public void close() {
        if (!released) {
          released = true;
          leased.lastUsedNanos = System.nanoTime();
          leased.inFlight.decrementAndGet();
          leased.closeIfIdle();
        }
      }
    };
  }

  @Override
  public Bolt.Lease fresh(Neo4jSettings settings, Neo4jAuth auth, ExecContext ctx) throws ConnectorException {
    Driver driver = open(settings, auth, ctx);
    return new Bolt.Lease() {
      @Override
      public Bolt.Client client() {
        return new DriverClient(driver);
      }

      @Override
      public void close() {
        driver.closeAsync();
      }
    };
  }

  @Override
  public void evict(String connectionId) {
    Entry e;
    synchronized (this) {
      e = entries.remove(connectionId);
    }
    retire(e);
  }

  private void sweep() {
    long now = System.nanoTime();
    List<Entry> idle = new ArrayList<>();
    synchronized (this) {
      entries.entrySet().removeIf(e -> {
        boolean old = e.getValue().inFlight.get() == 0 && now - e.getValue().lastUsedNanos > IDLE.toNanos();
        if (old) {
          idle.add(e.getValue());
        }
        return old;
      });
    }
    idle.forEach(DriverBolt::retire);
  }

  private static void retire(Entry e) {
    if (e != null) {
      e.retired = true;
      e.closeIfIdle();
    }
  }

  @Override
  public void close() {
    List<Entry> all;
    synchronized (this) {
      closed = true;
      all = new ArrayList<>(entries.values());
      entries.clear();
    }
    for (Entry e : all) {
      e.retired = true;
      e.driver.close();
    }
  }

  // ── Building a driver ───────────────────────────────────────────────────────────────────────────────────────

  /**
   * A driver for the settings, after the host's addresses passed the egress guard's fixed list. {@code Logging} is
   * deprecated in driver 6 for {@code System.Logger}; {@code none()} keeps the driver's records of queries and
   * parameters out of the gateway's log whatever its levels are.
   */
  @SuppressWarnings("deprecation")
  static Driver open(Neo4jSettings s, Neo4jAuth auth, ExecContext ctx) throws ConnectorException {
    checkAddresses(s);
    Config.ConfigBuilder config = Config.builder()
        .withLogging(Logging.none())
        .withUserAgent("GateRook/" + BuiltIn.VERSION)
        .withTelemetryDisabled(true)
        .withEventLoopThreads(1)
        .withMaxConnectionPoolSize(POOL)
        .withConnectionAcquisitionTimeout(10, TimeUnit.SECONDS)
        .withConnectionTimeout(s.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
        .withConnectionLivenessCheckTimeout(30, TimeUnit.SECONDS)
        .withMaxConnectionLifetime(30, TimeUnit.MINUTES);
    String scheme = s.scheme();
    Path ca = null;
    try {
      if (s.caPem() != null) {
        // A +s scheme takes no trust settings: the plain scheme with encryption and the CA verifies the same way.
        ca = writeCa(ctx.workDir(), s.caPem());
        config.withEncryption().withTrustStrategy(Config.TrustStrategy.trustCustomCertificateSignedBy(ca.toFile())
            .withHostnameVerification());
        scheme = s.routing() ? "neo4j" : "bolt";
      }
      URI uri = new URI(scheme, null, s.host(), s.port(), null, null, null);
      return GraphDatabase.driver(uri, auth.token(), config.build());
    } catch (URISyntaxException | IllegalArgumentException e) {
      throw ConnectorException.badInput("The URI of the connection can't be used: write it as neo4j+s://host:7687.");
    } catch (RuntimeException e) {
      throw Neo4jErrors.connect(e, s);
    } finally {
      if (ca != null) {
        try {
          Files.deleteIfExists(ca);
        } catch (IOException e) {
          // A certificate isn't a secret; the next start of the folder's owner may remove it.
        }
      }
    }
  }

  private static void checkAddresses(Neo4jSettings s) throws ConnectorException {
    String host = s.host().startsWith("[") ? s.host().substring(1, s.host().length() - 1) : s.host();
    var syntax = AddressPolicy.checkHostSyntax(host);
    if (syntax.isPresent()) {
      throw ConnectorException.denied(syntax.get().text() + ".");
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw ConnectorException.unavailable("The host " + s.host() + " can't be found. Check the URI and this "
          + "computer's DNS or VPN.", null);
    }
    for (InetAddress a : addresses) {
      AddressPolicy.Verdict v = ALWAYS.check(host, a, s.port());
      if (v.reason() == AddressPolicy.Reason.ALWAYS_BLOCKED) {
        throw ConnectorException.denied(v.text());
      }
    }
  }

  private static Path writeCa(Path workDir, String pem) throws ConnectorException {
    try {
      Path dir = Objects.requireNonNull(workDir, "workDir").toAbsolutePath();
      Files.createDirectories(dir);
      Path file = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
          ? Files.createTempFile(dir, "neo4j-ca-", ".pem",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
          : Files.createTempFile(dir, "neo4j-ca-", ".pem");
      Files.writeString(file, pem);
      return file;
    } catch (IOException | RuntimeException e) {
      throw ConnectorException.unavailable("GateRook can't hand the CA certificate to the Neo4j driver: its work "
          + "folder isn't writable.", null);
    }
  }

  // ── The driver behind the seam ──────────────────────────────────────────────────────────────────────────────

  private record DriverClient(Driver driver) implements Bolt.Client {

    @Override
    public Bolt.Tx begin(AccessMode mode, String database, Duration timeout, Map<String, Object> metadata,
        int fetchSize) {
      Session session = driver.session(SessionConfig.builder().withDatabase(database).withDefaultAccessMode(mode)
          .withFetchSize(Math.max(1, fetchSize)).build());
      try {
        Transaction tx = session.beginTransaction(TransactionConfig.builder()
            .withTimeout(timeout.compareTo(Duration.ofMillis(100)) < 0 ? Duration.ofMillis(100) : timeout)
            .withMetadata(metadata).build());
        return new DriverTx(session, tx);
      } catch (RuntimeException e) {
        try {
          session.close();
        } catch (RuntimeException closing) {
          e.addSuppressed(closing);
        }
        throw e;
      }
    }
  }

  private record DriverTx(Session session, Transaction tx) implements Bolt.Tx {

    @Override
    public Bolt.Run run(String cypher, Map<String, Object> parameters) {
      return new DriverRun(tx.run(cypher, parameters));
    }

    @Override
    public void commit() {
      tx.commit();
    }

    @Override
    public void close() {
      try {
        tx.close();
      } catch (RuntimeException e) {
        // The connection is gone; the server rolls back what wasn't committed.
      } finally {
        try {
          session.close();
        } catch (RuntimeException e) {
          // As above.
        }
      }
    }
  }

  private record DriverRun(Result result) implements Bolt.Run {

    @Override
    public List<String> keys() {
      return result.keys();
    }

    @Override
    public boolean hasNext() {
      return result.hasNext();
    }

    @Override
    public Record next() {
      return result.next();
    }

    @Override
    public Bolt.Summary consume() {
      return summary(result.consume());
    }
  }

  static Bolt.Summary summary(ResultSummary s) {
    Map<String, Integer> counters = counters(s.counters());
    List<Bolt.Note> notes = new ArrayList<>();
    for (GqlStatusObject status : s.gqlStatusObjects()) {
      if (status instanceof GqlNotification n && notes.size() < 10) {
        String severity = n.rawSeverity().orElse(null);
        if (severity == null && n.severity().isPresent()) {
          severity = n.severity().get().equals(NotificationSeverity.WARNING) ? "WARNING" : "INFORMATION";
        }
        notes.add(new Bolt.Note(n.gqlStatus(), n.statusDescription(), n.statusDescription(), severity));
      }
    }
    String type = s.queryType() == null ? null : code(s.queryType());
    return new Bolt.Summary(type, counters, s.queryPlan().map(p -> plan(p, 0)).orElse(null), List.copyOf(notes),
        s.server() == null ? null : s.server().agent(), s.server() == null ? null : s.server().protocolVersion());
  }

  static String code(QueryType type) {
    return switch (type) {
      case READ_ONLY -> "r";
      case READ_WRITE -> "rw";
      case WRITE_ONLY -> "w";
      case SCHEMA_WRITE -> "s";
    };
  }

  private static Map<String, Integer> counters(SummaryCounters c) {
    Map<String, Integer> out = new LinkedHashMap<>();
    put(out, "nodesCreated", c.nodesCreated());
    put(out, "nodesDeleted", c.nodesDeleted());
    put(out, "relationshipsCreated", c.relationshipsCreated());
    put(out, "relationshipsDeleted", c.relationshipsDeleted());
    put(out, "propertiesSet", c.propertiesSet());
    put(out, "labelsAdded", c.labelsAdded());
    put(out, "labelsRemoved", c.labelsRemoved());
    put(out, "indexesAdded", c.indexesAdded());
    put(out, "indexesRemoved", c.indexesRemoved());
    put(out, "constraintsAdded", c.constraintsAdded());
    put(out, "constraintsRemoved", c.constraintsRemoved());
    put(out, "systemUpdates", c.systemUpdates());
    return out;
  }

  private static void put(Map<String, Integer> out, String key, int value) {
    if (value != 0) {
      out.put(key, value);
    }
  }

  private static Bolt.Plan plan(org.neo4j.driver.summary.Plan p, int depth) {
    String operator = p.operatorType();
    int at = operator.indexOf('@');
    if (at > 0) {
      operator = operator.substring(0, at);
    }
    Value rows = p.arguments().get("EstimatedRows");
    Value details = p.arguments().get("Details");
    List<Bolt.Plan> children = new ArrayList<>();
    if (depth < 64) {
      for (org.neo4j.driver.summary.Plan child : p.children()) {
        children.add(plan(child, depth + 1));
      }
    }
    return new Bolt.Plan(operator, rows == null || rows.isNull() ? null : rows.asDouble(),
        details == null || details.isNull() ? null : details.asString(), List.copyOf(children));
  }
}
