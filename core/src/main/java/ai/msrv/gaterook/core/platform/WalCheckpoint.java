package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.core.platform.db.SqlitePools;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code PRAGMA wal_checkpoint(TRUNCATE)} after crypto-shredding (report 12 §6): {@code secure_delete} zeroes freed
 * pages of the database file, but the old pages of a destroyed key may still sit in {@code gateway.db-wal} until the
 * next checkpoint. Best effort, outside any transaction: a busy reader only means a later checkpoint finishes it.
 */
@Component
public class WalCheckpoint {

  private static final Logger log = LoggerFactory.getLogger(WalCheckpoint.class);

  private final SqlitePools pools;

  WalCheckpoint(SqlitePools pools) {
    this.pools = pools;
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
}
