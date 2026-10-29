package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Locale;
import javax.net.ssl.SSLException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Errors of the database in words for the agent (report 04 §6.6): the SQLState and the first line of the server's
 * message, PostgreSQL's hint and position, and for the common cases what to do. The core masks the text like a
 * result: "Key (email)=(a@b.c) already exists" carries data.
 *
 * <p>Only an unreachable server counts for the connection's circuit breaker; a statement that ran into its own timeout
 * is the statement's problem ({@code RESOURCE_ERROR}), and after a failed change the transaction was rolled back, so
 * its outcome is known.
 */
final class SqlErrors {

  private SqlErrors() {}

  /** The failure of a statement on {@code connection} (a name the agent knows) as a connector error. */
  static ConnectorException of(SQLException e, boolean mysql, String connection, String env, Duration timeout) {
    return of(e, mysql ? SqlSyntax.MYSQL : SqlSyntax.POSTGRES, connection, env, timeout);
  }

  static ConnectorException of(SQLException e, SqlSyntax syntax, String connection, String env, Duration timeout) {
    boolean mysql = syntax == SqlSyntax.MYSQL;
    boolean oracle = syntax == SqlSyntax.ORACLE;
    String state = state(e);
    int code = e.getErrorCode();
    if (e instanceof SQLTransientConnectionException && !(e.getCause() instanceof SQLException)) {
      return ConnectorException.resourceError(connection + " is busy: every connection of its pool is in use. Try "
          + "again.", e);
    }
    if (unreachable(e, state, syntax)) {
      return ConnectorException.unavailable(reach(e, connection), e);
    }
    if (timedOut(state, code, mysql) || (oracle && (code == 1013 || code == 30006))) {
      return ConnectorException.resourceError("Statement timeout (" + seconds(timeout) + " on " + env
          + ") exceeded. Narrow the WHERE clause or "
          + (oracle ? "add FETCH FIRST n ROWS ONLY." : "add LIMIT."), e);
    }
    if ((oracle && code == 1456) || "25006".equals(state) || (mysql && (code == 1792 || "S1009".equals(state)))) {
      return ConnectorException.resourceError("This statement writes, and the read-only transaction refused it: "
          + describe(e) + ". Use sql_execute for changes; a person approves them.", e);
    }
    if ((oracle && code == 1031) || "42501".equals(state) || (mysql && (code == 1142 || code == 1143 || code == 1044 || code == 1370))) {
      return ConnectorException.resourceError("The database user of " + connection + " may not do this: "
          + describe(e) + ".", e);
    }
    if ("40001".equals(state) && syntax == SqlSyntax.POSTGRES) {
      return ConnectorException.resourceError(describe(e) + ". Canceled by a conflict (a replica replaying changes, "
          + "or a concurrent transaction): retry.", e);
    }
    return ConnectorException.resourceError(describe(e), e);
  }

  /**
   * PostgreSQL: {@code ERROR 42P01: relation "ordrs" does not exist (position 15). Hint: …}; MySQL as its own client
   * prints it: {@code ERROR 1146 (42S02): Table 'app.ordrs' doesn't exist}.
   */
  static String describe(SQLException e) {
    StringBuilder out = new StringBuilder("ERROR");
    String state = state(e);
    if (!(e instanceof PSQLException) && e.getErrorCode() > 0) {
      out.append(' ').append(e.getErrorCode());
      if (state != null) {
        out.append(" (").append(state).append(')');
      }
    } else if (state != null) {
      out.append(' ').append(state);
    }
    String message = null;
    String hint = null;
    int position = 0;
    if (e instanceof PSQLException p && p.getServerErrorMessage() != null) {
      ServerErrorMessage m = p.getServerErrorMessage();
      message = m.getMessage();
      hint = m.getHint();
      position = m.getPosition();
    }
    if (message == null) {
      message = firstLine(e.getMessage());
    }
    out.append(": ").append(message == null ? "the database refused the statement" : message);
    if (position > 0) {
      out.append(" (position ").append(position).append(')');
    }
    if (hint != null && !hint.isBlank()) {
      out.append(". Hint: ").append(firstLine(hint));
    }
    return out.toString();
  }

  /** The connection is gone or never came up: class 08, authentication, shutdown, a network exception below. */
  static boolean unreachable(SQLException e, String state) {
    return unreachable(e, state, SqlSyntax.MYSQL);
  }

  static boolean unreachable(SQLException e, String state, SqlSyntax syntax) {
    if (state != null && (state.startsWith("08") || state.equals("28P01") || state.equals("28000")
        || state.equals("57P01") || state.equals("57P02") || state.equals("57P03") || state.equals("3D000"))) {
      return true;
    }
    int code = e.getErrorCode();
    if (code == 1045 || code == 1049 || code == 2002 || code == 2003 || code == 2006 || code == 2013) {
      return true;
    }
    if (syntax == SqlSyntax.ORACLE && (code == 1017 || code == 28000 || code == 28001 || code == 1033
        || code == 1034 || code == 1089 || code == 1090 || code == 3113 || code == 3114 || code == 3135
        || code == 12154 || code == 12514 || code == 12541 || code == 17002 || code == 17008 || code == 17410)) {
      return true;
    }
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof UnknownHostException || t instanceof ConnectException || t instanceof SSLException
          || (t instanceof SocketTimeoutException && !(e instanceof SQLTransientConnectionException))) {
        return true;
      }
    }
    return false;
  }

  private static boolean timedOut(String state, int code, boolean mysql) {
    if ("57014".equals(state)) {
      return true;
    }
    return mysql && (code == 3024 || code == 1969 || code == 1317 || "70100".equals(state));
  }

  /** pgjdbc's verify-full refusal: the certificate is for another name (no SSLException below it). */
  static boolean hostNameMismatch(SQLException e) {
    String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    return m.contains("hostname") && m.contains("could not be verified");
  }

  /** What went wrong on the way to the server, in the kit's tone. */
  static String reach(SQLException e, String connection) {
    if (hostNameMismatch(e)) {
      return "TLS to " + connection + " failed: the certificate doesn't match the host.";
    }
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof UnknownHostException) {
        return "Can't resolve the host of " + connection + ". Check the VPN or the host name.";
      }
      if (t instanceof ConnectException) {
        return "Connection refused by " + connection + ". Is the database running?";
      }
      if (t instanceof SSLException) {
        return "TLS to " + connection + " failed: " + firstLine(t.getMessage()) + ".";
      }
      if (t instanceof SocketTimeoutException) {
        return "Can't reach " + connection + ": it timed out. Check the VPN or the host, then try again.";
      }
    }
    String state = state(e);
    if ("28P01".equals(state) || "28000".equals(state) || e.getErrorCode() == 1045) {
      return "The database refused the login of " + connection + ": " + firstLine(e.getMessage()) + ".";
    }
    return connection + " isn't reachable: " + describe(e) + ".";
  }

  static String state(SQLException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException s && s.getSQLState() != null) {
        return s.getSQLState();
      }
    }
    return null;
  }

  static String firstLine(String s) {
    if (s == null) {
      return null;
    }
    int nl = s.indexOf('\n');
    String line = (nl < 0 ? s : s.substring(0, nl)).strip();
    return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
  }

  static String seconds(Duration d) {
    long ms = d.toMillis();
    return ms % 1000 == 0 ? (ms / 1000) + " s" : String.format(Locale.US, "%.1f s", ms / 1000.0);
  }
}
