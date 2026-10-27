package ai.msrv.gaterook.core.platform.db;

import ai.msrv.gaterook.core.platform.DiskFullException;
import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.support.SQLExceptionTranslator;

/**
 * SQLite result codes → Spring's exception hierarchy, for what Hibernate's {@code SQLiteDialect} leaves generic: it
 * maps no constraint violation at all (a duplicate connection name, a CHECK of V1, an audit trigger would all surface
 * as {@code JpaSystemException}), and sqlite-jdbc reports extended codes ({@code SQLITE_CONSTRAINT_UNIQUE} = 2067),
 * whose low byte is the primary code. Services and the admin API rely on these types for 409/422 answers, and on
 * {@link DiskFullException} for 507 (Hibernate would report a full disk as a lost connection).
 */
final class SqliteExceptionTranslator implements SQLExceptionTranslator {

  private static final int SQLITE_BUSY = 5;
  private static final int SQLITE_LOCKED = 6;
  private static final int SQLITE_READONLY = 8;
  private static final int SQLITE_FULL = 13;
  private static final int SQLITE_CONSTRAINT = 19;
  private static final int SQLITE_CONSTRAINT_PRIMARYKEY = 1555;
  private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;

  @Override
  public DataAccessException translate(String task, String sql, SQLException ex) {
    int code = ex.getErrorCode();
    String message = task + ": " + ex.getMessage();
    return switch (code & 0xFF) {
      case SQLITE_CONSTRAINT -> code == SQLITE_CONSTRAINT_UNIQUE || code == SQLITE_CONSTRAINT_PRIMARYKEY
          ? new DuplicateKeyException(message, ex)
          : new DataIntegrityViolationException(message, ex);
      case SQLITE_BUSY, SQLITE_LOCKED -> new CannotAcquireLockException(message, ex);
      // A write on the read-only pool: a transaction marked read-only that writes is a bug of its caller.
      case SQLITE_READONLY -> new InvalidDataAccessApiUsageException(message, ex);
      case SQLITE_FULL -> new DiskFullException(message, ex);
      default -> null;
    };
  }
}
