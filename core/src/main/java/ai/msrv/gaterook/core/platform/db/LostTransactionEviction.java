package ai.msrv.gaterook.core.platform.db;

import com.zaxxer.hikari.SQLExceptionOverride;
import java.sql.SQLException;

/**
 * Hikari throws away a connection on which SQLite may have ended the transaction by itself. After {@code SQLITE_FULL},
 * {@code SQLITE_IOERR}, {@code SQLITE_BUSY}, {@code SQLITE_NOMEM} or {@code SQLITE_INTERRUPT} SQLite may roll back the
 * whole transaction ("Response To Errors Within A Transaction", sqlite.org/lang_transaction.html), and an IMMEDIATE
 * {@code BEGIN} that times out with {@code SQLITE_BUSY} never starts one. sqlite-jdbc notices neither and keeps
 * {@code autoCommit=false}: its {@code rollback()} fails ("cannot rollback - no transaction is active"), Hikari's reset
 * on return is skipped, and the pooled connection stays out of step for good. The next transaction sends no
 * {@code BEGIN}, each of its statements commits on its own and its commit fails ("cannot commit - no transaction is
 * active"); after a few minutes of a full disk every write failed until a restart (2026-10-25).
 *
 * <p>An evicted connection is closed, which rolls back whatever SQLite still had open, and the pool opens a new one: the
 * error stays with the transaction that met it, and writes work again as soon as the disk has space. A commit or
 * rollback that SQLite answers with "no transaction is active" evicts too, whatever brought the connection there. So
 * do {@code SQLITE_CORRUPT}, {@code SQLITE_CANTOPEN} and {@code SQLITE_NOTADB}: the file under the connection is
 * gone or unreadable, and only a fresh connection sees it as it is now (U6, 2026-10-26).
 */
final class LostTransactionEviction implements SQLExceptionOverride {

  private static final int SQLITE_BUSY = 5;
  private static final int SQLITE_NOMEM = 7;
  private static final int SQLITE_INTERRUPT = 9;
  private static final int SQLITE_IOERR = 10;
  private static final int SQLITE_CORRUPT = 11;
  private static final int SQLITE_FULL = 13;
  private static final int SQLITE_CANTOPEN = 14;
  private static final int SQLITE_NOTADB = 26;

  @java.lang.Override
  public Override adjudicate(SQLException e) {
    // sqlite-jdbc may report extended codes (SQLITE_IOERR_WRITE = 778): the low byte is the primary one.
    return switch (e.getErrorCode() & 0xFF) {
      case SQLITE_BUSY, SQLITE_NOMEM, SQLITE_INTERRUPT, SQLITE_IOERR, SQLITE_CORRUPT, SQLITE_FULL, SQLITE_CANTOPEN,
          SQLITE_NOTADB -> Override.MUST_EVICT;
      default -> e.getMessage() != null && e.getMessage().contains("no transaction is active") ? Override.MUST_EVICT
          : Override.CONTINUE_EVICT;
    };
  }
}
