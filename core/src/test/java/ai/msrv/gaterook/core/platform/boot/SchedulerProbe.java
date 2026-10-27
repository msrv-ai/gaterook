package ai.msrv.gaterook.core.platform.boot;

import ai.msrv.gaterook.core.platform.db.GatewayDatabaseProperties;
import ai.msrv.gaterook.core.platform.db.SqlitePools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Child-JVM probe of {@code SqliteConcurrencyTest} (ARCHITECTURE.md M1.2): with every pooled SQLite connection busy in
 * native code (the writer waiting for a lock, three readers in a long query), do long polls — virtual threads parked
 * on a future, like {@code approval_status} — still wake up on time? Started with
 * {@code -Djdk.virtualThreadScheduler.parallelism=2}; with {@code launcher} as argument it first applies the
 * launcher's rule (pools + 2), without it the two carriers stay as asked. Prints one line:
 * {@code parallelism=<n> carriers=<n> maxWakeMs=<ms> maxGapMs=<ms>}, where {@code carriers} is what the launcher's
 * {@code VirtualThreadScheduler.carriersAtOnce} check saw.
 */
public final class SchedulerProbe {

  private SchedulerProbe() {}

  public static void main(String[] args) throws Exception {
    int parallelism = args.length > 0 && args[0].equals("launcher")
        ? VirtualThreadScheduler.ensureAtLeast(SqlitePools.MAX_CONNECTIONS + 2).effective()
        : Integer.getInteger("jdk.virtualThreadScheduler.parallelism", Runtime.getRuntime().availableProcessors());
    // The launcher's own check, as the first virtual threads of this JVM.
    int carriers = VirtualThreadScheduler.carriersAtOnce(SqlitePools.MAX_CONNECTIONS + 2, Duration.ofMillis(300));
    Path dir = Files.createTempDirectory(Path.of(args[1]), "probe-");
    Path database = dir.resolve("gateway.db");
    GatewayDatabaseProperties properties = new GatewayDatabaseProperties(3, Duration.ofSeconds(5),
        Duration.ofSeconds(30));
    try (SqlitePools pools = SqlitePools.open(database, properties)) {
      try (Connection c = pools.writer().getConnection(); Statement s = c.createStatement()) {
        s.execute("create table t (k integer primary key, v integer)");
      }
      String longQuery = calibratedQuery(pools, Duration.ofMillis(1500));

      // An outside connection holds the write lock for 2 s: the pool's writer will wait inside busy_timeout.
      CountDownLatch locked = new CountDownLatch(1);
      Thread holder = Thread.ofPlatform().start(() -> {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database); Statement s = c.createStatement()) {
          s.execute("begin exclusive");
          locked.countDown();
          Thread.sleep(2_000);
          s.execute("commit");
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      });
      locked.await();

      List<Thread> busy = new ArrayList<>();
      busy.add(Thread.ofVirtual().start(() -> run(pools.writer(), "insert into t (v) values (1)")));
      for (int i = 0; i < 3; i++) {
        busy.add(Thread.ofVirtual().start(() -> run(pools.reader().orElseThrow(), longQuery)));
      }
      Thread.sleep(200); // let all four connections enter native code

      AtomicLong maxGap = new AtomicLong();
      // Measured from now, on this thread: a heartbeat that can't even start (no free carrier) is the longest gap.
      AtomicLong lastTick = new AtomicLong(System.nanoTime());
      Runnable tick = () -> {
        long now = System.nanoTime();
        maxGap.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(now - lastTick.getAndSet(now)), Math::max);
      };
      Thread heartbeat = Thread.ofVirtual().start(() -> {
        while (!Thread.currentThread().isInterrupted()) {
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            return;
          }
          tick.run();
        }
      });

      // 50 long polls complete 300 ms from now, from a platform thread (as a decision would).
      List<CompletableFuture<Long>> decisions = new ArrayList<>();
      List<CompletableFuture<Long>> woke = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        CompletableFuture<Long> decision = new CompletableFuture<>();
        CompletableFuture<Long> wake = new CompletableFuture<>();
        decisions.add(decision);
        woke.add(wake);
        Thread.ofVirtual().start(() -> {
          try {
            long completedAt = decision.get(25, TimeUnit.SECONDS);
            wake.complete(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - completedAt));
          } catch (Exception e) {
            wake.completeExceptionally(e);
          }
        });
      }
      Thread.ofPlatform().start(() -> {
        try {
          Thread.sleep(300);
        } catch (InterruptedException ignored) {
          return;
        }
        decisions.forEach(d -> d.complete(System.nanoTime()));
      });
      long maxWake = 0;
      for (CompletableFuture<Long> wake : woke) {
        maxWake = Math.max(maxWake, wake.get(30, TimeUnit.SECONDS));
      }
      for (Thread t : busy) {
        t.join();
      }
      heartbeat.interrupt();
      heartbeat.join();
      tick.run();
      holder.join();
      System.out.println("parallelism=" + parallelism + " carriers=" + carriers + " maxWakeMs=" + maxWake
          + " maxGapMs=" + maxGap.get());
    }
  }

  /** A recursive CTE sized to keep a reader connection in native code for about {@code target}. */
  private static String calibratedQuery(SqlitePools pools, Duration target) throws Exception {
    long rows = 200_000;
    while (true) {
      String query = "with recursive n(x) as (select 1 union all select x + 1 from n where x < " + rows
          + ") select count(*) from n";
      long started = System.nanoTime();
      run(pools.reader().orElseThrow(), query);
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      if (ms >= target.toMillis() / 4) {
        return "with recursive n(x) as (select 1 union all select x + 1 from n where x < "
            + rows * target.toMillis() / Math.max(ms, 1) + ") select count(*) from n";
      }
      rows *= 4;
    }
  }

  private static void run(javax.sql.DataSource dataSource, String sql) {
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      if (s.execute(sql)) {
        try (ResultSet rows = s.getResultSet()) {
          rows.next();
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
