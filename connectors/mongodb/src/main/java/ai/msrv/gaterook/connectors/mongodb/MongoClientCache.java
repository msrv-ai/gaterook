package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.client.MongoClient;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * One {@link MongoClient} per connection (report 05 §7): a client belongs to one version of its connection
 * ({@code ConnectionConfig.version} changes with every edit, secret version and policy change) and to one login; the
 * driver fixes the credential when the client is built, so a new password or lease gets a new client, and the old
 * one is closed once its calls end. Each client has its own monitor threads (a heartbeat every 10 s to every host,
 * over the VPN), so a client idle for ten minutes is closed by a sweep on one shared daemon thread.
 */
final class MongoClientCache implements AutoCloseable {

  static final Duration IDLE = Duration.ofMinutes(10);
  /** A short-lived login is replaced this long before it expires. */
  static final Duration LEASE_MARGIN = Duration.ofSeconds(60);

  /** What the cache needs to build a client. */
  interface Factory {
    MongoClient create(MongoSettings settings, MongoLogin login, Duration readTimeout);
  }

  private final Factory factory;
  private final Supplier<Instant> clock;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private ScheduledThreadPoolExecutor sweeper;
  private boolean closed;

  MongoClientCache(Factory factory, Supplier<Instant> clock) {
    this.factory = factory;
    this.clock = clock;
  }

  private static final class Entry {
    final long configVersion;
    final MongoLogin login;
    final Duration readTimeout;
    final MongoClient client;
    final AtomicInteger inFlight = new AtomicInteger();
    volatile long lastUsedNanos = System.nanoTime();
    volatile boolean retired;
    private boolean closed;

    Entry(long configVersion, MongoLogin login, Duration readTimeout, MongoClient client) {
      this.configVersion = configVersion;
      this.login = login;
      this.readTimeout = readTimeout;
      this.client = client;
    }

    synchronized void closeIfIdle() {
      if (!closed && inFlight.get() == 0) {
        closed = true;
        try {
          client.close();
        } finally {
          login.close();
        }
      }
    }
  }

  /** One call's use of a client; closing it ends the call's claim. */
  final class Lease implements AutoCloseable {
    private final Entry entry;
    private final boolean use;
    private boolean released;

    private Lease(Entry entry, boolean use) {
      this.entry = entry;
      this.use = use;
    }

    MongoClient client() {
      return entry.client;
    }

    @Override
    public void close() {
      if (released) {
        return;
      }
      released = true;
      if (use) {
        entry.lastUsedNanos = System.nanoTime();
      }
      if (entry.inFlight.decrementAndGet() == 0 && entry.retired) {
        entry.closeIfIdle();
      }
    }
  }

  /**
   * The client of this version of the connection with this login and at least this socket read timeout, built if
   * needed. {@code login} stays the caller's.
   */
  Lease acquire(ConnectionConfig cfg, MongoSettings settings, MongoLogin login, Duration readTimeout) {
    Entry stale = null;
    Entry entry;
    synchronized (this) {
      if (closed) {
        throw new IllegalStateException("The MongoDB connector is closed");
      }
      Entry current = entries.get(cfg.id());
      if (current != null && current.configVersion == cfg.version() && current.login.equals(login)
          && current.readTimeout.compareTo(readTimeout) >= 0 && !expiring(current.login)) {
        current.inFlight.incrementAndGet();
        return new Lease(current, true);
      }
      MongoLogin owned = new MongoLogin(login.user(), login.password().clone(), login.version(), login.notAfter());
      MongoClient client;
      try {
        client = factory.create(settings, owned, readTimeout);
      } catch (RuntimeException e) {
        owned.close();
        throw e;
      }
      entry = new Entry(cfg.version(), owned, readTimeout, client);
      entry.inFlight.incrementAndGet();
      stale = entries.put(cfg.id(), entry);
      startSweeper();
    }
    retire(stale);
    return new Lease(entry, true);
  }

  /** The connection changed or went: its client goes once its calls end. */
  void drop(String connectionId) {
    Entry e;
    synchronized (this) {
      e = entries.remove(connectionId);
    }
    retire(e);
  }

  /** The client of a connection that is open now, for a health check that shouldn't build one. */
  Lease existing(ConnectionConfig cfg) {
    synchronized (this) {
      Entry current = entries.get(cfg.id());
      if (current == null || current.configVersion != cfg.version() || current.retired) {
        return null;
      }
      current.inFlight.incrementAndGet();
      // A health check isn't a use: the client still goes after ten idle minutes.
      return new Lease(current, false);
    }
  }

  int size() {
    return entries.size();
  }

  /** Closes clients idle for {@link #IDLE}. */
  void sweep() {
    long now = System.nanoTime();
    List<Entry> idle = new ArrayList<>();
    synchronized (this) {
      for (Map.Entry<String, Entry> e : entries.entrySet()) {
        Entry entry = e.getValue();
        if (entry.inFlight.get() == 0 && now - entry.lastUsedNanos > IDLE.toNanos()) {
          entries.remove(e.getKey());
          idle.add(entry);
        }
      }
    }
    idle.forEach(this::retire);
  }

  private boolean expiring(MongoLogin login) {
    return login.notAfter() != null && !clock.get().isBefore(login.notAfter().minus(LEASE_MARGIN));
  }

  private void retire(Entry e) {
    if (e != null) {
      e.retired = true;
      e.closeIfIdle();
    }
  }

  private void startSweeper() {
    if (sweeper == null) {
      sweeper = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "gw-mongodb-sweep");
        t.setDaemon(true);
        return t;
      });
      sweeper.setRemoveOnCancelPolicy(true);
      sweeper.scheduleWithFixedDelay(this::sweepQuietly, 1, 1, TimeUnit.MINUTES);
    }
  }

  private void sweepQuietly() {
    try {
      sweep();
    } catch (RuntimeException e) {
      System.getLogger(MongoClientCache.class.getName()).log(System.Logger.Level.WARNING,
          "Closing idle MongoDB clients failed", e);
    }
  }

  @Override
  public void close() {
    List<Entry> all;
    synchronized (this) {
      closed = true;
      all = new ArrayList<>(entries.values());
      entries.clear();
      if (sweeper != null) {
        sweeper.shutdownNow();
        sweeper = null;
      }
    }
    for (Entry e : all) {
      e.retired = true;
      e.inFlight.set(0);
      e.closeIfIdle();
    }
  }
}
