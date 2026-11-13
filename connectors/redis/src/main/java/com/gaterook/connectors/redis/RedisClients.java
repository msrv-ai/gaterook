package com.gaterook.connectors.redis;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import redis.clients.jedis.ClientSetInfoConfig;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.exceptions.JedisException;

/**
 * One small connection pool per saved connection and database (report 05 §6, §7): at most {@code poolSize} physical connections
 * (2 by default), none until the first call, idle ones closed after a minute, the whole pool after ten idle minutes. A
 * pool belongs to one version of its connection and one version of its login: another version gets a new pool, and
 * the old one closes its idle sockets at once and each busy one when its call returns it.
 *
 * <p>Every call takes one connection for its whole duration — at most {@link #BORROW_WAIT} of waiting, then "busy" —
 * so a call's commands go one after the other on one socket and a cancelled call can cut exactly its own. A new
 * connection is opened on the caller's thread, inside the call's deadline. The pool is the connector's own, not
 * commons-pool's (which Jedis brings): that one opens a replacement synchronously whenever a broken connection is
 * returned, so a cancelled call against a silent server would hang in the next handshake.
 *
 * <p>RESP2 is requested explicitly ({@code HELLO 2 AUTH …}): replies have one shape whatever the server prefers, and
 * a server without {@code HELLO} (Redis &lt; 6.0) fails the handshake. Housekeeping runs on one shared virtual thread.
 * Connection type Auto is resolved before a pool is created, once per version of the connection ({@link AutoTls}).
 */
final class RedisClients implements AutoCloseable {

  static final Duration IDLE_POOL = Duration.ofMinutes(10);
  static final Duration IDLE_CONNECTION = Duration.ofSeconds(60);
  static final Duration BORROW_WAIT = Duration.ofSeconds(2);

  private final Clock clock;
  record PoolKey(String connectionId, int database) {}

  private final ConcurrentHashMap<PoolKey, Pool> pools = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Transport> transports = new ConcurrentHashMap<>();
  private ScheduledThreadPoolExecutor scheduler;
  private boolean closed;

  RedisClients(Clock clock) {
    this.clock = clock;
  }

  /** A connection taken from a pool: {@code reused} when it served a call before (it may have gone stale since). */
  record Borrowed(BoundedConnection connection, boolean reused) {}

  /** One connection's pool. */
  final class Pool {
    final String connectionId;
    final long configVersion;
    final RedisSettings settings;
    final Credentials credentials;
    private final JedisClientConfig client;
    private final GuardedSocketFactory sockets;
    private final Semaphore permits;
    private final Deque<Idle> idle = new ArrayDeque<>();
    private volatile boolean noTouchUnsupported;
    private volatile boolean retired;
    private volatile long lastUsedNanos = System.nanoTime();

    private record Idle(BoundedConnection connection, long sinceNanos) {}

    private Pool(String connectionId, long configVersion, RedisSettings settings, Credentials credentials) {
      this.connectionId = connectionId;
      this.configVersion = configVersion;
      this.settings = settings;
      this.credentials = credentials;
      this.sockets = new GuardedSocketFactory(settings);
      this.permits = new Semaphore(settings.poolSize(), true);
      this.client = DefaultJedisClientConfig.builder()
          .protocol(RedisProtocol.RESP2)
          .connectionTimeoutMillis(settings.connectTimeoutMs())
          .socketTimeoutMillis(settings.commandTimeoutMs())
          .database(settings.database())
          .clientName("gaterook")
          // lib-name "jedis(gaterook)": a DBA sees who reads in CLIENT LIST.
          .clientSetInfoConfig(ClientSetInfoConfig.withLibNameSuffix("gaterook"))
          .credentialsProvider(credentials::asRedisCredentials)
          .build();
    }

    /** A connection for one call: an idle one, or a new one opened within {@code deadline}. */
    Borrowed borrow(Instant deadline) throws ConnectorException {
      long leftMs = Duration.between(clock.instant(), deadline).toMillis();
      if (leftMs <= 0) {
        throw ConnectorException.timeout("The call reached its deadline before it could talk to Redis.");
      }
      try {
        if (!permits.tryAcquire(Math.min(BORROW_WAIT.toMillis(), leftMs), TimeUnit.MILLISECONDS)) {
          throw ConnectorException.resourceError("The connection is busy: " + settings.poolSize() + " call"
              + (settings.poolSize() == 1 ? " is" : "s are") + " running. Try again.", null);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw ConnectorException.timeout("The call was stopped while it waited for a connection.");
      }
      lastUsedNanos = System.nanoTime();
      try {
        if (retired) {
          throw ConnectorException.unavailable("The connection changed while the call started. Try again.", null);
        }
        BoundedConnection reuse = pollIdle();
        if (reuse != null) {
          return new Borrowed(reuse, true);
        }
        return new Borrowed(open(deadline), false);
      } catch (ConnectorException | RuntimeException e) {
        permits.release();
        throw e;
      }
    }

    /** Back to the idle ones, or closed if it broke or the pool was replaced. */
    void release(BoundedConnection c, boolean broken) {
      try {
        c.keep(-1);
        if (broken || retired || c.isBroken() || !c.isConnected()) {
          disconnect(c);
        } else {
          synchronized (idle) {
            idle.push(new Idle(c, System.nanoTime()));
          }
        }
      } finally {
        permits.release();
        lastUsedNanos = System.nanoTime();
        if (retired && active() == 0) {
          // Nothing can open a connection of this pool any more: its copy of the password goes.
          credentials.close();
        }
      }
    }

    int active() {
      return settings.poolSize() - permits.availablePermits();
    }

    int idle() {
      synchronized (idle) {
        return idle.size();
      }
    }

    boolean noTouchUnsupported() {
      return noTouchUnsupported;
    }

    void noTouchUnsupported(boolean value) {
      noTouchUnsupported = value;
    }

    private BoundedConnection open(Instant deadline) throws ConnectorException {
      try {
        return ScopedValue.where(GuardedSocketFactory.DEADLINE, deadline)
            .call(() -> new BoundedConnection(GuardedSocketFactory.oneSocket(sockets), client));
      } catch (JedisException e) {
        throw RedisErrors.connect(e, settings);
      }
    }

    private BoundedConnection pollIdle() {
      synchronized (idle) {
        return idle.isEmpty() ? null : idle.pop().connection();
      }
    }

    /** Closes connections idle for {@code olderThan}. */
    void evictIdle(Duration olderThan) {
      List<Connection> old = new ArrayList<>();
      long now = System.nanoTime();
      synchronized (idle) {
        idle.removeIf(i -> {
          if (now - i.sinceNanos() >= olderThan.toNanos()) {
            old.add(i.connection());
            return true;
          }
          return false;
        });
      }
      old.forEach(RedisClients::disconnect);
    }

    /** No new calls; idle sockets close now, busy ones when their calls return them. */
    private void retire() {
      retired = true;
      List<Connection> all;
      synchronized (idle) {
        all = new ArrayList<>();
        idle.forEach(i -> all.add(i.connection()));
        idle.clear();
      }
      all.forEach(RedisClients::disconnect);
      if (active() == 0) {
        credentials.close();
      }
    }
  }

  /**
   * A session on one connection of {@code cfg}'s pool for one call, the pool created if needed; takes ownership of
   * {@code login}.
   */
  RedisSession open(ConnectionConfig cfg, RedisSettings s, Credentials login, Instant deadline)
      throws ConnectorException {
    Pool p = pool(cfg, s, login, deadline);
    return new RedisSession(p, p.borrow(deadline), clock, deadline);
  }

  private Pool pool(ConnectionConfig cfg, RedisSettings s, Credentials login, Instant deadline)
      throws ConnectorException {
    PoolKey key = new PoolKey(cfg.id(), s.database());
    RedisSettings settings = s;
    if (s.autoTls()) {
      Pool current = pools.get(key);
      if (current == null || current.configVersion != cfg.version() || !current.credentials.equals(login)) {
        // Outside the lock: detecting the transport talks to the server, within the call's deadline.
        settings = transport(cfg, s, login, deadline);
      }
    }
    Pool stale;
    Pool result;
    synchronized (this) {
      if (closed) {
        login.close();
        throw new IllegalStateException("The Redis connector is closed");
      }
      Pool current = pools.get(key);
      if (current != null && current.configVersion == cfg.version() && current.credentials.equals(login)) {
        login.close();
        return current;
      }
      if (settings.autoTls()) {
        // A pool of this version existed a moment ago, so its transport is known unless the connection changed.
        Transport known = transports.get(cfg.id());
        if (known == null || known.configVersion() != cfg.version()) {
          login.close();
          throw ConnectorException.unavailable("The connection changed while the call started. Try again.", null);
        }
        settings = s.withTls(known.tls());
      }
      result = new Pool(cfg.id(), cfg.version(), settings, login);
      stale = pools.put(key, result);
      scheduler();
    }
    if (stale != null) {
      stale.retire();
    }
    return result;
  }

  /**
   * The transport Auto finds for this version of the connection: detected once ({@link AutoTls}), then shared by its
   * pools (one per database) until the connection changes or its pools close for being idle. A failure closes
   * {@code login}, which the pool would have owned.
   */
  private RedisSettings transport(ConnectionConfig cfg, RedisSettings s, Credentials login, Instant deadline)
      throws ConnectorException {
    Transport known = transports.get(cfg.id());
    if (known != null && known.configVersion() == cfg.version()) {
      return s.withTls(known.tls());
    }
    try {
      RedisSettings found = AutoTls.resolve(s, deadline);
      transports.put(cfg.id(), new Transport(cfg.version(), found.tls()));
      return found;
    } catch (JedisException e) {
      login.close();
      throw RedisErrors.connect(e, s);
    } catch (RuntimeException e) {
      login.close();
      throw e;
    }
  }

  /** The transport Auto found for one version of a connection. */
  private record Transport(long configVersion, boolean tls) {}

  /** The current pool of a connection, if any (tests). */
  Pool current(String connectionId) {
    return pools.values().stream().filter(p -> p.connectionId.equals(connectionId)).findFirst().orElse(null);
  }

  Pool current(String connectionId, int database) {
    return pools.get(new PoolKey(connectionId, database));
  }

  /** Pools by connection id and database (tests). */
  Map<PoolKey, Pool> pools() {
    return Map.copyOf(pools);
  }

  /** The connection changed or went away: its pool closes, busy sockets after their calls. */
  void evict(String connectionId) {
    transports.remove(connectionId);
    pools.forEach((key, pool) -> {
      if (key.connectionId().equals(connectionId) && pools.remove(key, pool)) {
        pool.retire();
      }
    });
  }

  /** Whether Auto found the transport of {@code connectionId} and its pools still use it (tests). */
  Boolean knownTls(String connectionId) {
    Transport t = transports.get(connectionId);
    return t == null ? null : t.tls();
  }

  /** Closes connections idle for a minute and pools nobody used for {@link #IDLE_POOL}. */
  void sweep() {
    sweep(IDLE_CONNECTION, IDLE_POOL);
  }

  void sweep(Duration idleConnection, Duration idlePool) {
    long now = System.nanoTime();
    for (Pool p : pools.values()) {
      if (now - p.lastUsedNanos >= idlePool.toNanos() && p.active() == 0) {
        if (pools.remove(new PoolKey(p.connectionId, p.settings.database()), p)) {
          p.retire();
          // The next call detects Auto's transport again: the server may have changed meanwhile.
          if (pools.keySet().stream().noneMatch(k -> k.connectionId().equals(p.connectionId))) {
            transports.remove(p.connectionId);
          }
        }
      } else {
        p.evictIdle(idleConnection);
      }
    }
  }

  @Override
  public void close() {
    List<Pool> all;
    ScheduledThreadPoolExecutor s;
    synchronized (this) {
      closed = true;
      all = new ArrayList<>(pools.values());
      pools.clear();
      transports.clear();
      s = scheduler;
      scheduler = null;
    }
    all.forEach(Pool::retire);
    if (s != null) {
      s.shutdownNow();
    }
  }

  private void scheduler() {
    if (scheduler == null) {
      scheduler = new ScheduledThreadPoolExecutor(1, Thread.ofVirtual().name("gw-redis-housekeeper-", 0).factory());
      scheduler.setRemoveOnCancelPolicy(true);
      scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
      scheduler.scheduleWithFixedDelay(this::sweep, 30, 30, TimeUnit.SECONDS);
    }
  }

  /** Closes the socket without a word to the server (QUIT could wait on a server that doesn't answer). */
  static void disconnect(Connection c) {
    try {
      c.forceDisconnect();
    } catch (java.io.IOException | RuntimeException ignored) {
      // The socket is gone either way.
    }
  }
}
