package ai.msrv.gaterook.connectors.access;

import java.sql.SQLException;
import java.util.Locale;

/**
 * What a UCanAccess error means for GateRook. UCanAccess wraps HSQLDB's and Jackcess's errors in its own
 * ({@code UCAExc:::5.3.0 …}): the message names the cause.
 */
final class AccessErrors {

  private AccessErrors() {}

  /** The message without UCanAccess's prefix and the statement it repeats. */
  static String message(Throwable e) {
    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    m = m.replaceAll("UCAExc:::[0-9.]+\\s*", "");
    int failed = m.indexOf("Failed to execute [");
    if (failed >= 0) {
      int close = m.indexOf("]: ", failed);
      if (close > 0) {
        m = m.substring(0, failed) + m.substring(close + 3);
      }
    }
    return m.strip();
  }

  private static String all(Throwable e) {
    StringBuilder b = new StringBuilder();
    for (Throwable t = e; t != null && b.length() < 4_000; t = t.getCause()) {
      b.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
      if (t.getCause() == t) {
        break;
      }
    }
    return b.toString().toLowerCase(Locale.ROOT);
  }

  /** HSQLDB refused a change in the read-only transaction (defence in depth: GateRook's check came first). */
  static boolean readOnly(Throwable e) {
    String a = all(e);
    return a.contains("read-only sql-transaction") || a.contains("read only") && a.contains("database");
  }

  /** The file is password-protected: Jackcess needs a codec GateRook doesn't bundle. */
  static boolean encrypted(Throwable e) {
    String a = all(e);
    return a.contains("codec") || a.contains("encrypt") || a.contains("password");
  }

  /** Another program holds the file exclusively. */
  static boolean locked(Throwable e) {
    String a = all(e);
    return a.contains("lock") && !a.contains("block");
  }

  /** The process may not write the file (the connector's sandbox, the file's permissions). */
  static boolean notPermitted(Throwable e) {
    String a = all(e);
    return a.contains("operation not permitted") || a.contains("permission denied") || a.contains("access is denied")
        || a.contains("read-only file system") || a.contains("readonly");
  }

  static boolean cancelled(SQLException e) {
    String a = all(e);
    return a.contains("cancel") || a.contains("timeout") || a.contains("statement execution aborted");
  }

  static boolean noSuchObject(Throwable e) {
    return all(e).contains("object not found");
  }
}
