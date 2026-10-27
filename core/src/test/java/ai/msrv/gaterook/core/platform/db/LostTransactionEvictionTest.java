package ai.msrv.gaterook.core.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.TestGateway;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ways other than a full disk ({@link DiskFullRecoveryTest}) in which sqlite-jdbc's {@code autoCommit=false} and
 * SQLite's transaction part ways on the pooled writer. After each, the next transaction on the pool is one again: a
 * rollback undoes its insert. Without {@link LostTransactionEviction} the insert commits on its own and the rollback
 * fails ("no transaction is active").
 */
class LostTransactionEvictionTest {

  private Path database;
  private SqlitePools pools;

  @BeforeEach
  void open() throws SQLException {
    database = TestGateway.newHome("eviction").resolve("gateway.db");
    pools = SqlitePools.open(database, new GatewayDatabaseProperties(0, Duration.ofMillis(100), Duration.ofSeconds(10)));
    try (Connection c = pools.writer().getConnection(); Statement s = c.createStatement()) {
      s.execute("create table t (v integer)");
    }
  }

  @AfterEach
  void close() {
    pools.close();
  }

  @Test
  void aWriterWhoseBeginTimedOutIsReplaced() throws Exception {
    // Another process holds the write lock past busy_timeout: BEGIN IMMEDIATE fails after the driver left autocommit.
    try (Connection other = DriverManager.getConnection("jdbc:sqlite:" + database);
        Statement s = other.createStatement()) {
      s.execute("begin exclusive");
      try (Connection c = pools.writer().getConnection()) {
        assertThatThrownBy(() -> c.setAutoCommit(false)).isInstanceOf(SQLException.class)
            .hasMessageContaining("SQLITE_BUSY");
      }
      s.execute("commit");
    }
    assertTheNextTransactionRollsBack();
  }

  @Test
  void aWriterThatHasNoTransactionAtCommitIsReplaced() throws Exception {
    try (Connection c = pools.writer().getConnection()) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("rollback");
      }
      assertThatThrownBy(c::commit).hasMessageContaining("no transaction is active");
    }
    assertTheNextTransactionRollsBack();
  }

  private void assertTheNextTransactionRollsBack() throws SQLException {
    try (Connection c = pools.writer().getConnection()) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("insert into t values (1)");
      }
      c.rollback();
    }
    try (Connection c = pools.writer().getConnection(); Statement s = c.createStatement();
        ResultSet rows = s.executeQuery("select count(*) from t")) {
      rows.next();
      assertThat(rows.getInt(1)).isZero();
    }
  }
}
