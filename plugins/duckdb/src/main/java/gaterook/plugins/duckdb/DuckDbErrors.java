package gaterook.plugins.duckdb;

import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * DuckDB's errors as GateRook passes them on. DuckDB names the kind first ({@code Catalog Error: …},
 * {@code Out of Memory Error: …}); a lock held by another program names that program, its process id and its user,
 * which stay out of what the agent reads.
 */
final class DuckDbErrors {

  static final int MAX_MESSAGE = 2_000;

  private static final Pattern LOCK_HOLDER = Pattern.compile("Conflicting lock is held in .*?\\(PID \\d+\\)"
      + "(?: by user [^.\\s]+)?\\.?", Pattern.DOTALL);

  private DuckDbErrors() {}

  /** DuckDB's message, without who holds a lock, at most {@link #MAX_MESSAGE} characters. */
  static String message(SQLException e) {
    String m = e == null || e.getMessage() == null ? "DuckDB failed." : e.getMessage().strip();
    m = LOCK_HOLDER.matcher(m).replaceAll("Another program holds a lock on it.");
    return m.length() <= MAX_MESSAGE ? m : m.substring(0, MAX_MESSAGE - 1) + "…";
  }

  /** Another program has the file open for writing: DuckDB lets nobody else in then. */
  static boolean lockConflict(SQLException e) {
    String m = text(e);
    return m.contains("Could not set lock on file") || m.contains("Conflicting lock");
  }

  /** Another instance of this process has the file open in a way that excludes this one. */
  static boolean handleConflict(SQLException e) {
    return text(e).contains("Unique file handle conflict");
  }

  static boolean outOfMemory(SQLException e) {
    return text(e).startsWith("Out of Memory Error");
  }

  /** The file system refused: the account's permissions, or the sandbox the plugin runs in. */
  static boolean notPermitted(SQLException e) {
    String m = text(e);
    return m.contains("Operation not permitted") || m.contains("Permission denied") || m.contains("Access is denied")
        || m.contains("Read-only file system");
  }

  /** DuckDB's own file boundary: a file outside the data folders. */
  static boolean outsideFolders(SQLException e) {
    return text(e).startsWith("Permission Error");
  }

  private static String text(SQLException e) {
    return e == null || e.getMessage() == null ? "" : e.getMessage();
  }
}
