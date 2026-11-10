package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.core.platform.db.SqlitePools;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code PRAGMA wal_checkpoint(TRUNCATE)} after crypto-shredding (report 12 §6): {@code secure_delete} zeroes freed
 * pages of the database file, but the old pages of a destroyed key may still sit in {@code gateway.db-wal} until the
 * next checkpoint. Best effort, outside any transaction: a busy reader only means a later checkpoint finishes it.
 *
 * <p>The checkpoint needs the writer pool's only connection. A caller that shreds inside its own transaction (Settings ›
 * Tunnel or a store's sign-in replacing a secret) holds that connection until it commits, so it asks for
 * {@link #truncateWhenCommitted()}: the checkpoint runs once that transaction has committed and given the connection
 * back, instead of waiting for it inside the transaction (which stalled every write for the pool's timeout and then
 * gave up, with the shredded pages still in the WAL).
 */
@Component
public class WalCheckpoint {

  private static final Logger log = LoggerFactory.getLogger(WalCheckpoint.class);

  private final SqlitePools pools;
  private final Executor background;

  @Autowired
  WalCheckpoint(SqlitePools pools) {
    this(pools, task -> Thread.ofVirtual().name("wal-checkpoint").start(task));
  }

  /** {@code background} runs the checkpoints deferred to after a commit (tests run them when they choose). */
  WalCheckpoint(SqlitePools pools, Executor background) {
    this.pools = pools;
    this.background = background;
  }

  /** Copies the WAL into the database and truncates it; returns whether SQLite reported the checkpoint complete. */
  public boolean truncate() {
    try (Connection connection = pools.writer().getConnection(); Statement statement = connection.createStatement()) {
      try (var result = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
        boolean complete = result.next() && result.getInt(1) == 0;
        if (!complete) {
          log.debug("WAL checkpoint was busy; the next one completes it");
        }
        return complete;
      }
    } catch (SQLException e) {
      log.warn("WAL checkpoint failed: {}", e.getMessage());
      return false;
    }
  }

  /**
   * {@link #truncate()} now when no transaction is in progress on this thread, else once the transaction has
   * committed: then on another thread, which gets the writer's connection as soon as the committing thread releases
   * it (a transaction still holds it while its completion callbacks run). A transaction that rolls back shredded
   * nothing, so it gets no checkpoint; several requests in one transaction get one.
   */
  public void truncateWhenCommitted() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      truncate();
      return;
    }
    if (TransactionSynchronizationManager.getSynchronizations().stream().anyMatch(AfterCommit.class::isInstance)) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new AfterCommit());
  }

  /** The checkpoint a transaction asked for, handed to {@link #background} when it has committed. */
  private final class AfterCommit implements TransactionSynchronization {
    @Override
    public void afterCompletion(int status) {
      if (status == STATUS_COMMITTED) {
        background.execute(WalCheckpoint.this::truncate);
      }
    }
  }
}
