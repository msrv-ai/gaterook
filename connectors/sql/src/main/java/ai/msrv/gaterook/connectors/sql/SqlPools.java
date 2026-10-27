package ai.msrv.gaterook.connectors.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One HikariCP pool per connection (report 04 §1, §7.3): created lazily with no physical connection, at most
 * {@code poolSize} connections, idle ones closed after a minute, the whole pool after ten idle minutes. A pool belongs
 * to one version of its connection ({@code ConnectionConfig.version} changes with every edit, secret version and
 * policy change): another version gets a new pool and the old one is closed in the background once its calls end. A
 * new value of the same login (Rotate at the source) evicts the pool's connections softly; a new short-lived login
 * (a Vault lease) gets a new pool whose connections live shorter than the lease.
 *
 * <p>Hikari's housekeeping for every pool runs on one shared scheduler thread, and its connection threads are virtual.
 */
final class SqlPools implements AutoCloseable {

  static final Duration IDLE_POOL = Duration.ofMinutes(10);
  static final Duration MAX_LIFETIME = Duration.ofMinutes(10);
  static final Duration MIN_LIFETIME = Duration.ofSeconds(30);
  static final Duration LEASE_MARGIN = Duration.ofSeconds(60);
  static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);
  /** The socket's read timeout: statements end earlier, at their own timeouts (at most 600 s by the schema). */
  static final int SOCKET_TIMEOUT_MS = 610_000;

  private final SqlDialect dialect;
  private final ConcurrentHashMap<String, Pool> pools = new ConcurrentHashMap<>();
  private ScheduledThreadPoolExecutor scheduler;
  private boolean closed;
  private Duration idleTimeout = Duration.ofSeconds(60);

  SqlPools(SqlDialect dialect) {
    this.dialect = dialect;
  }

  /** One connection's pool. */
  final class Pool {
    final String connectionId;
    final long configVersion;
    final HikariDataSource hikari;
    final CredentialAwareDataSource source;
    private volatile ServerInfo server;
    private volatile long lastUsedNanos = System.nanoTime();

    private Pool(String connectionId, long configVersion, HikariDataSource hikari, CredentialAwareDataSource source) {
      this.connectionId = connectionId;
      this.configVersion = configVersion;
      this.hikari = hikari;
      this.source = source;
    }

    /** A connection of the pool; {@code autoCommit} is off. */
    Connection borrow() throws SQLException {
      lastUsedNanos = System.nanoTime();
      Connection c = hikari.getConnection();
      if (server == null) {
        server = dialect.identify(c);
      }
      return c;
    }

    ServerInfo server() {
      return server;
    }

    int active() {
      return hikari.getHikariPoolMXBean() == null ? 0 : hikari.getHikariPoolMXBean().getActiveConnections();
    }

    int total() {
      return hikari.getHikariPoolMXBean() == null ? 0 : hikari.getHikariPoolMXBean().getTotalConnections();
    }

    private void close() {
      try {
        hikari.close();
      } finally {
        source.close();
      }
    }
  }

  /**
   * The pool for this version of the connection with this login, created if needed. Takes ownership of
   * {@code login}.
   */
  Pool pool(ConnectionConfig cfg, ConnectionSettings s, Credentials login, Path certificates) throws IOException {
    Pool stale = null;
    Pool result;
    synchronized (this) {
      if (closed) {
        login.close();
        throw new IllegalStateException("The SQL connector is closed");
      }
      Pool current = pools.get(cfg.id());
      if (current != null && current.configVersion == cfg.version()
          && (login.notAfter() == null || login.equals(current.source.current()))) {
        if (current.source.update(login)) {
          // A new value of the same login: connections opened with the old one go as soon as they are idle.
          current.hikari.getHikariPoolMXBean().softEvictConnections();
        }
        current.lastUsedNanos = System.nanoTime();
        return current;
      }
      result = create(cfg, s, login, certificates);
      stale = pools.put(cfg.id(), result);
    }
    if (stale != null) {
      closeLater(stale);
    }
    return result;
  }

  private Pool create(ConnectionConfig cfg, ConnectionSettings s, Credentials login, Path certificates)
      throws IOException {
    CredentialAwareDataSource source;
    try {
      source = new CredentialAwareDataSource(dialect.driver(), dialect.url(s),
          dialect.properties(s, certificates, SOCKET_TIMEOUT_MS), login);
    } catch (IOException | RuntimeException e) {
      login.close();
      throw e;
    }
    HikariConfig hc = new HikariConfig();
    // The pool's name shows up in Hikari's log lines: neither host nor user.
    hc.setPoolName("gw-sql-" + shortId(cfg.id()));
    hc.setDataSource(source);
    hc.setMaximumPoolSize(s.poolSize());
    hc.setMinimumIdle(0);
    hc.setIdleTimeout(idleTimeout.toMillis());
    hc.setMaxLifetime(lifetime(login.notAfter()).toMillis());
    hc.setKeepaliveTime(0);
    hc.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
    hc.setValidationTimeout(2_000);
    // No physical connection until the first call: a pool that can't connect yet is still created.
    hc.setInitializationFailTimeout(-1);
    hc.setAutoCommit(false);
    hc.setRegisterMbeans(false);
    hc.setThreadFactory(Thread.ofVirtual().name("gw-sql-" + shortId(cfg.id()) + "-", 0).factory());
    hc.setScheduledExecutor(scheduler());
    return new Pool(cfg.id(), cfg.version(), new HikariDataSource(hc), source);
  }

  /** {@code min(10 min, notAfter − now − 60 s)}, not below Hikari's 30 s (it would silently use 30 min instead). */
  static Duration lifetime(Instant notAfter) {
    if (notAfter == null) {
      return MAX_LIFETIME;
    }
    Duration left = Duration.between(Instant.now(), notAfter).minus(LEASE_MARGIN);
    if (left.compareTo(MAX_LIFETIME) > 0) {
      return MAX_LIFETIME;
    }
    return left.compareTo(MIN_LIFETIME) < 0 ? MIN_LIFETIME : left;
  }

  /** The current pool of a connection, if any (tests, health). */
  Pool current(String connectionId) {
    return pools.get(connectionId);
  }

  /** The connection changed or went away: its pool closes after its calls. */
  void drop(String connectionId) {
    Pool p = pools.remove(connectionId);
    if (p != null) {
      closeLater(p);
    }
  }

  /** Closes pools nobody used for {@link #IDLE_POOL}. */
  void sweep() {
    sweep(IDLE_POOL);
  }

  /** Closes pools nobody used for {@code idleFor} and that have no call running. */
  void sweep(Duration idleFor) {
    long now = System.nanoTime();
    List<Pool> idle = new ArrayList<>();
    for (Pool p : pools.values()) {
      if (now - p.lastUsedNanos >= idleFor.toNanos() && p.active() == 0) {
        idle.add(p);
      }
    }
    for (Pool p : idle) {
      if (pools.remove(p.connectionId, p)) {
        closeLater(p);
      }
    }
  }

  /** Tests make idle connections go sooner; Hikari's floor is 10 s. */
  void idleTimeout(Duration value) {
    this.idleTimeout = value;
  }

  @Override
  public void close() {
    List<Pool> all;
    ScheduledThreadPoolExecutor s;
    synchronized (this) {
      closed = true;
      all = new ArrayList<>(pools.values());
      pools.clear();
      s = scheduler;
      scheduler = null;
    }
    all.forEach(Pool::close);
    if (s != null) {
      s.shutdownNow();
    }
  }

  private synchronized ScheduledThreadPoolExecutor scheduler() {
    if (scheduler == null) {
      scheduler = new ScheduledThreadPoolExecutor(1, Thread.ofVirtual().name("gw-sql-housekeeper-", 0).factory());
      scheduler.setRemoveOnCancelPolicy(true);
      scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
      scheduler.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.MINUTES);
    }
    return scheduler;
  }

  /**
   * Closes a pool no call can borrow from any more once its last call is done. Hikari's own shutdown aborts
   * connections still in use, which could cut a change off during its COMMIT; so the pool waits for its calls first,
   * as long as the longest statement may take (the schema allows 600 s), then closes anyway.
   */
  private static void closeLater(Pool p) {
    Thread.ofVirtual().name("gw-sql-close-" + shortId(p.connectionId)).start(() -> {
      long until = System.nanoTime() + DRAIN.toNanos();
      try {
        while (p.active() > 0 && System.nanoTime() < until) {
          Thread.sleep(200);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        p.close();
      }
    });
  }

  /** How long a replaced pool waits for its calls: the longest statement timeout and the socket's margin. */
  static final Duration DRAIN = Duration.ofMillis(SOCKET_TIMEOUT_MS);

  private static String shortId(String id) {
    String clean = id.replaceAll("[^A-Za-z0-9]", "");
    return clean.length() <= 8 ? clean : clean.substring(0, 8);
  }
}
