package com.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.core.platform.db.GatewayDatabaseProperties;
import com.gaterook.core.platform.db.SqlitePools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The checkpoint after crypto-shredding needs the writer pool's only connection. Asked for inside a transaction that
 * holds it, it must neither wait for that connection (every write stalled for the pool's timeout, and the checkpoint
 * then gave up) nor be lost: it runs once the transaction has committed.
 */
class WalCheckpointTest {

  /** Long enough that waiting for the connection inside the transaction shows, short enough for a unit test. */
  private static final Duration POOL_TIMEOUT = Duration.ofSeconds(2);

  @TempDir Path dir;
  private SqlitePools pools;
  private Path wal;
  private final List<Runnable> deferred = new CopyOnWriteArrayList<>();
  private WalCheckpoint checkpoint;
  private JdbcTemplate jdbc;
  private TransactionTemplate tx;

  @BeforeEach
  void open() {
    Path database = dir.resolve("gateway.db");
    pools = SqlitePools.open(database, new GatewayDatabaseProperties(0, Duration.ofMillis(500), POOL_TIMEOUT));
    wal = dir.resolve("gateway.db-wal");
    checkpoint = new WalCheckpoint(pools, deferred::add);
    jdbc = new JdbcTemplate(pools.writer());
    tx = new TransactionTemplate(new DataSourceTransactionManager(pools.writer()));
    jdbc.execute("CREATE TABLE shredded(value TEXT)");
  }

  @AfterEach
  void close() {
    pools.close();
  }

  @Test
  void insideATransactionTheCheckpointWaitsForTheCommitNotForTheConnection() throws Exception {
    long[] waited = new long[1];
    tx.executeWithoutResult(s -> {
      jdbc.update("INSERT INTO shredded VALUES ('old page')");
      long started = System.nanoTime();
      checkpoint.truncateWhenCommitted();
      checkpoint.truncateWhenCommitted();
      waited[0] = System.nanoTime() - started;
      assertThat(deferred).as("nothing runs before the commit").isEmpty();
    });

    assertThat(Duration.ofNanos(waited[0])).isLessThan(POOL_TIMEOUT.dividedBy(4));
    assertThat(deferred).as("one checkpoint per transaction").hasSize(1);
    assertThat(Files.size(wal)).as("the committed pages are in the WAL").isPositive();
    deferred.getFirst().run();
    assertThat(Files.size(wal)).as("the checkpoint after the commit truncated it").isZero();
  }

  @Test
  void aTransactionThatRollsBackShreddedNothingAndGetsNoCheckpoint() {
    tx.executeWithoutResult(s -> {
      jdbc.update("INSERT INTO shredded VALUES ('old page')");
      checkpoint.truncateWhenCommitted();
      s.setRollbackOnly();
    });

    assertThat(deferred).isEmpty();
  }

  @Test
  void withoutATransactionTheCheckpointRunsAtOnce() throws Exception {
    jdbc.update("INSERT INTO shredded VALUES ('old page')");
    assertThat(Files.size(wal)).isPositive();

    checkpoint.truncateWhenCommitted();

    assertThat(deferred).isEmpty();
    assertThat(Files.size(wal)).isZero();
  }

  /** As in production: on another thread, which gets the connection once the committing thread has released it. */
  @Test
  void theDeferredCheckpointGetsTheConnectionOnceTheTransactionGaveItBack() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    WalCheckpoint threaded = new WalCheckpoint(pools, task -> Thread.ofVirtual().start(() -> {
      task.run();
      done.countDown();
    }));
    long started = System.nanoTime();
    tx.executeWithoutResult(s -> {
      jdbc.update("INSERT INTO shredded VALUES ('old page')");
      threaded.truncateWhenCommitted();
    });

    assertThat(done.await(POOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(POOL_TIMEOUT);
    assertThat(Files.size(wal)).as("a complete checkpoint truncates the WAL").isZero();
  }
}
