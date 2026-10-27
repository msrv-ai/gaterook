package ai.msrv.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ai.msrv.gaterook.core.platform.TestGateway;
import ai.msrv.gaterook.core.platform.boot.SchedulerProbe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Report 14 §9.1–9.2 and the scheduler rule of ARCHITECTURE.md M1.2, run in a JVM with two carriers
 * ({@code -Djdk.virtualThreadScheduler.parallelism=2}, a surefire execution of its own):
 * <ul>
 *   <li>DEFERRED transactions that read, then write, fail with {@code SQLITE_BUSY}; the IMMEDIATE single writer of
 *       {@link SqlitePools} never does;</li>
 *   <li>two writers waiting in {@code busy_timeout} pin both carriers and freeze every virtual thread; with the single
 *       writer, waiting writers park in Hikari and the rest of the application keeps running;</li>
 *   <li>with the launcher's parallelism (pools + 2) long polls wake on time even while every pooled connection is in
 *       native code; with two carriers they don't (child JVMs, {@link SchedulerProbe}).</li>
 * </ul>
 */
class SqliteConcurrencyTest {

  private Path home;

  @BeforeEach
  void home() {
    home = TestGateway.newHome("concurrency");
  }

  @Test
  void runsWithTwoCarriers() {
    assertThat(System.getProperty("jdk.virtualThreadScheduler.parallelism")).isEqualTo("2");
  }

  @Test
  void deferredReadThenWriteFailsButTheImmediateWriterNeverDoes() throws Exception {
    Path database = home.resolve("deferred.db");
    String url = "jdbc:sqlite:" + database + "?journal_mode=WAL&busy_timeout=1000";
    try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
      s.execute("create table t (k integer primary key, v integer)");
      s.execute("insert into t values (0, 0)");
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setMaximumPoolSize(4);
    int deferredFailures;
    try (HikariDataSource deferred = new HikariDataSource(config)) {
      deferredFailures = readThenWrite(deferred, 200);
    }
    assertThat(deferredFailures).as("DEFERRED: SQLITE_BUSY / BUSY_SNAPSHOT on lock upgrade").isPositive();

    try (SqlitePools pools = SqlitePools.open(home.resolve("gateway.db"), properties())) {
      try (Connection c = pools.writer().getConnection(); Statement s = c.createStatement()) {
        s.execute("create table t (k integer primary key, v integer)");
        s.execute("insert into t values (0, 0)");
      }
      assertThat(readThenWrite(pools.writer(), 200)).as("IMMEDIATE single writer").isZero();
      try (Connection c = pools.writer().getConnection(); Statement s = c.createStatement();
          ResultSet rows = s.executeQuery("select v from t where k = 0")) {
        rows.next();
        assertThat(rows.getInt(1)).isEqualTo(200);
      }
    }
  }

  @Test
  void twoWaitingWritersFreezeVirtualThreadsTheSingleWriterDoesNot() throws Exception {
    Path database = home.resolve("gateway.db");
    try (SqlitePools pools = SqlitePools.open(database, properties())) {
      try (Connection c = pools.writer().getConnection(); Statement s = c.createStatement()) {
        s.execute("create table t (k integer primary key, v integer)");
      }
      String raw = "jdbc:sqlite:" + database + "?journal_mode=WAL&busy_timeout=5000";

      long twoWriters = maxHeartbeatGapWhileLocked(database, () -> {
        List<Thread> writers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
          writers.add(Thread.ofVirtual().start(() -> {
            try (Connection c = DriverManager.getConnection(raw); Statement s = c.createStatement()) {
              s.execute("insert into t (v) values (1)");
            } catch (SQLException e) {
              throw new IllegalStateException(e);
            }
          }));
        }
        return writers;
      });
      long singleWriter = maxHeartbeatGapWhileLocked(database, () -> {
        List<Thread> writers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
          writers.add(Thread.ofVirtual().start(() -> {
            try (Connection c = pools.writer().getConnection(); Statement s = c.createStatement()) {
              s.execute("insert into t (v) values (1)");
            } catch (SQLException e) {
              throw new IllegalStateException(e);
            }
          }));
        }
        return writers;
      });

      assertThat(twoWriters).as("both carriers pinned in busy_timeout").isGreaterThan(800);
      assertThat(singleWriter).as("one carrier pinned, the rest park in Hikari").isLessThan(300);
    }
  }

  @Test
  void longPollsWakeOnTimeWithTheLaunchersParallelism() throws Exception {
    Result withRule = probe(true);
    assertThat(withRule.parallelism()).isEqualTo(SqlitePools.MAX_CONNECTIONS + 2);
    assertThat(withRule.carriers()).as("the launcher's carrier check").isEqualTo(SqlitePools.MAX_CONNECTIONS + 2);
    assertThat(withRule.maxWakeMs()).as("long polls wake while 4 connections are in native code").isLessThan(250);
    assertThat(withRule.maxGapMs()).isLessThan(300);

    Result twoCarriers = probe(false);
    assertThat(twoCarriers.parallelism()).isEqualTo(2);
    assertThat(twoCarriers.carriers()).as("the check notices a scheduler with too few carriers").isEqualTo(2);
    assertThat(twoCarriers.maxWakeMs()).as("the hazard the rule prevents").isGreaterThan(700);
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

  private static GatewayDatabaseProperties properties() {
    return new GatewayDatabaseProperties(3, Duration.ofSeconds(5), Duration.ofSeconds(30));
  }

  /** {@code n} transactions "read v, write v + 1" on virtual threads; returns how many failed. */
  private static int readThenWrite(DataSource dataSource, int n) throws Exception {
    AtomicInteger failures = new AtomicInteger();
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(threads.submit(() -> {
          try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
              int v;
              try (ResultSet rows = s.executeQuery("select v from t where k = 0")) {
                rows.next();
                v = rows.getInt(1);
              }
              Thread.sleep(1);
              s.executeUpdate("update t set v = " + (v + 1) + " where k = 0");
              c.commit();
            } catch (SQLException e) {
              failures.incrementAndGet();
              c.rollback();
            }
          } catch (SQLException | InterruptedException e) {
            failures.incrementAndGet();
          }
          return null;
        }));
      }
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    }
    return failures.get();
  }

  private interface Writers {
    List<Thread> start();
  }

  /** Holds an exclusive lock for 1.5 s from a platform thread and measures how late a 10 ms virtual-thread tick gets. */
  private static long maxHeartbeatGapWhileLocked(Path database, Writers writers) throws Exception {
    CountDownLatch locked = new CountDownLatch(1);
    Thread holder = Thread.ofPlatform().start(() -> {
      try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database); Statement s = c.createStatement()) {
        s.execute("begin exclusive");
        locked.countDown();
        Thread.sleep(1_500);
        s.execute("commit");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    locked.await();
    List<Thread> started = writers.start();
    Thread.sleep(100);
    Heartbeat heartbeat = Heartbeat.start();
    holder.join();
    for (Thread writer : started) {
      writer.join();
    }
    return heartbeat.stop();
  }

  /**
   * A virtual thread that ticks every 10 ms and records the longest gap between ticks. Measured from its start on the
   * caller's clock: a heartbeat that can't even get a carrier until the end still reports the whole freeze.
   */
  private static final class Heartbeat {

    private final AtomicLong maxGapMs = new AtomicLong();
    private final AtomicLong lastTick = new AtomicLong(System.nanoTime());
    private final Thread thread;

    private Heartbeat() {
      thread = Thread.ofVirtual().unstarted(() -> {
        while (!Thread.currentThread().isInterrupted()) {
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            return;
          }
          tick();
        }
      });
    }

    static Heartbeat start() {
      Heartbeat heartbeat = new Heartbeat();
      heartbeat.thread.start();
      return heartbeat;
    }

    private void tick() {
      long now = System.nanoTime();
      long last = lastTick.getAndSet(now);
      maxGapMs.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(now - last), Math::max);
    }

    /** Stops the heartbeat; the time since its last tick counts as a gap too. */
    long stop() throws InterruptedException {
      thread.interrupt();
      thread.join();
      tick();
      return maxGapMs.get();
    }
  }

  private record Result(int parallelism, int carriers, long maxWakeMs, long maxGapMs) {}

  private static final Pattern RESULT =
      Pattern.compile("parallelism=(\\d+) carriers=(\\d+) maxWakeMs=(\\d+) maxGapMs=(\\d+)");

  /** Runs {@link SchedulerProbe} in a child JVM with two carriers requested. */
  private Result probe(boolean launcherRule) throws IOException, InterruptedException {
    String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--enable-native-access=ALL-UNNAMED", "-Djdk.virtualThreadScheduler.parallelism=2", "-cp", classpath,
        SchedulerProbe.class.getName(), launcherRule ? "launcher" : "plain", home.toString())
        .redirectError(ProcessBuilder.Redirect.INHERIT).start();
    String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as(out).isZero();
    Matcher m = RESULT.matcher(out);
    assertThat(m.find()).as(out).isTrue();
    return new Result(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Long.parseLong(m.group(3)),
        Long.parseLong(m.group(4)));
  }
}
