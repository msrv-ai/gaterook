package gaterook.plugins.h2;

import java.sql.SQLException;
import java.util.Locale;

/**
 * What an H2 error means for GateRook, by H2's error codes ({@code org.h2.api.ErrorCode}): the server refused the
 * login, has no such database, broke the connection, cancelled a statement, or refused the statement itself, which is
 * an answer the agent reads.
 */
final class H2Errors {

  static final int WRONG_USER_OR_PASSWORD = 28000;
  static final int DATABASE_NOT_FOUND_WITH_IF_EXISTS = 90146;
  static final int REMOTE_DATABASE_NOT_FOUND = 90149;
  static final int DATABASE_NOT_FOUND = 90013;
  static final int CONNECTION_BROKEN = 90067;
  static final int STATEMENT_WAS_CANCELED = 57014;
  static final int NOT_ENOUGH_RIGHTS = 90096;
  static final int DRIVER_VERSION_ERROR = 90047;
  static final int DATABASE_ALREADY_OPEN = 90020;

  private H2Errors() {}

  /** H2's message without the statement it repeats and the code in brackets. */
  static String message(SQLException e) {
    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    int statement = m.indexOf("; SQL statement:");
    if (statement > 0) {
      m = m.substring(0, statement);
    }
    int code = m.lastIndexOf(" [");
    if (code > 0 && m.endsWith("]")) {
      m = m.substring(0, code);
    }
    return m.strip();
  }

  static boolean login(SQLException e) {
    return e.getErrorCode() == WRONG_USER_OR_PASSWORD;
  }

  static boolean noDatabase(SQLException e) {
    int c = e.getErrorCode();
    return c == DATABASE_NOT_FOUND_WITH_IF_EXISTS || c == REMOTE_DATABASE_NOT_FOUND || c == DATABASE_NOT_FOUND;
  }

  /** The socket couldn't connect or broke: the server is unreachable, or the TCP gate refused it. */
  static boolean connection(SQLException e) {
    return e.getErrorCode() == CONNECTION_BROKEN || refusedByGate(e);
  }

  static boolean cancelled(SQLException e) {
    return e.getErrorCode() == STATEMENT_WAS_CANCELED;
  }

  static boolean rights(SQLException e) {
    return e.getErrorCode() == NOT_ENOUGH_RIGHTS;
  }

  static boolean versionMismatch(SQLException e) {
    return e.getErrorCode() == DRIVER_VERSION_ERROR;
  }

  /** The host's TCP gate refused the socket: the JDK's SOCKS client says so, and H2 quotes it. */
  static boolean refusedByGate(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String m = t.getMessage();
      if (m != null && m.toLowerCase(Locale.ROOT).contains("not allowed by ruleset")) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }
}
