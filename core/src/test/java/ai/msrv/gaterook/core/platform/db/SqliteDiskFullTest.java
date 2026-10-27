package ai.msrv.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.SQLExceptionOverride.Override;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * The writer after a full disk (U6): {@code SQLITE_FULL} must not leave the single writer connection broken until
 * the core restarts. {@code PRAGMA max_page_count} makes a real {@code SQLITE_FULL} on one connection; the next
 * connection of the pool is a fresh one, without the limit, and writes.
 */
class SqliteDiskFullTest {

  @TempDir
  Path dir;

  @Test
  void aConnectionThatHitAFullDiskIsReplacedAndTheNextWriteSucceeds() throws Exception {
    try (SqlitePools pools = SqlitePools.open(dir.resolve("gateway.db"), properties())) {
      try (Connection c = pools.writer().getConnection(); Statement st = c.createStatement()) {
        st.execute("CREATE TABLE fill (v BLOB)");
        long pages = scalar(st, "PRAGMA page_count");
        st.execute("PRAGMA max_page_count = " + (pages + 2));
        assertThatThrownBy(() -> {
          for (int i = 0; i < 100; i++) {
            st.execute("INSERT INTO fill VALUES (zeroblob(4096))");
          }
        }).isInstanceOfSatisfying(SQLException.class,
            e -> assertThat(e.getErrorCode() & 0xFF).isEqualTo(SQLiteErrorCode.SQLITE_FULL.code));
      }

      try (Connection c = pools.writer().getConnection(); Statement st = c.createStatement()) {
        assertThat(scalar(st, "PRAGMA max_page_count")).as("a fresh connection").isGreaterThan(1_000_000);
        st.execute("INSERT INTO fill VALUES (zeroblob(4096))");
      }
    }
  }

  @Test
  void onlyTheErrorsThatBreakTheConnectionEvictIt() {
    LostTransactionEviction broken = new LostTransactionEviction();
    // BUSY too: a BEGIN IMMEDIATE that timed out never started its transaction (LostTransactionEvictionTest).
    for (SQLiteErrorCode code : new SQLiteErrorCode[] {SQLiteErrorCode.SQLITE_FULL, SQLiteErrorCode.SQLITE_IOERR,
        SQLiteErrorCode.SQLITE_IOERR_WRITE, SQLiteErrorCode.SQLITE_CORRUPT, SQLiteErrorCode.SQLITE_CANTOPEN,
        SQLiteErrorCode.SQLITE_NOTADB, SQLiteErrorCode.SQLITE_BUSY}) {
      assertThat(broken.adjudicate(new SQLiteException("x", code))).as(code.name())
          .isEqualTo(Override.MUST_EVICT);
    }
    for (SQLiteErrorCode code : new SQLiteErrorCode[] {SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE,
        SQLiteErrorCode.SQLITE_READONLY}) {
      assertThat(broken.adjudicate(new SQLiteException("x", code))).as(code.name())
          .isEqualTo(Override.CONTINUE_EVICT);
    }
  }

  private static long scalar(Statement st, String sql) throws SQLException {
    try (ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static GatewayDatabaseProperties properties() {
    return new GatewayDatabaseProperties(1, Duration.ofSeconds(5), Duration.ofSeconds(5));
  }
}
