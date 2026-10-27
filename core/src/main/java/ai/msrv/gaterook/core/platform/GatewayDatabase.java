package ai.msrv.gaterook.core.platform;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * The gateway database as a file (ARCHITECTURE.md §4, §6 settings › Backup, {@code GET /api/health}): what it is
 * (path, SQLite version, journal mode, schema version, size) and consistent copies of it.
 *
 * <p>A copy is {@code VACUUM INTO} on a connection of its own: the reader pool can't take it ({@code query_only}
 * refuses it with {@code SQLITE_READONLY}) and the single writer must not be held for the seconds a large audit takes.
 * In WAL mode the copy is a read transaction, so calls keep writing while it runs; the copy is one self-contained file
 * in rollback-journal mode, owner-only from its first byte. It holds secrets only encrypted, and never the master key.
 */
public final class GatewayDatabase {

  /**
   * What {@code /api/health} reports of the database.
   *
   * @param schema    the Flyway version applied last ({@code "3"}), or {@code null} before any
   * @param sizeBytes the database file and its WAL together
   */
  public record Info(String path, String sqlite, String journal, String schema, long sizeBytes) {}

  private final Path file;
  private final String url;
  private final DataSource reads;

  /**
   * @param file  {@code ~/.gaterook/gateway.db}
   * @param url   the JDBC URL the pools share (pragmas of the file)
   * @param reads a pool for short reads (the reader, or the writer when there is none)
   */
  public GatewayDatabase(Path file, String url, DataSource reads) {
    this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
    this.url = Objects.requireNonNull(url, "url");
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  public Path file() {
    return file;
  }

  public Info info() {
    String sqlite = null;
    String journal = null;
    String schema = null;
    try (Connection c = reads.getConnection(); Statement s = c.createStatement()) {
      sqlite = scalar(s, "select sqlite_version()");
      journal = scalar(s, "pragma journal_mode");
      schema = scalar(s, "select version from flyway_schema_history where success = 1 and version is not null "
          + "order by installed_rank desc limit 1");
    } catch (SQLException e) {
      // Health still answers: the self-check proved the database at start, a passing hiccup isn't a status.
    }
    return new Info(file.toString(), sqlite, journal, schema, size(file) + size(file.resolveSibling(
        file.getFileName() + "-wal")));
  }

  /**
   * Writes a consistent copy to {@code target}, which must not exist yet; on failure nothing is left behind.
   *
   * @throws IOException when the file can't be created or SQLite can't write the copy
   */
  public void backupInto(Path target) throws IOException {
    Path absolute = target.toAbsolutePath();
    // VACUUM INTO accepts an existing empty file: create it owner-only first, so the copy is never readable by
    // others, not even for a moment.
    Files.createFile(absolute);
    boolean done = false;
    try {
      GatewayPaths.ownerOnlyFile(absolute);
      try (Connection c = org.sqlite.JDBC.createConnection(url, new Properties());
          Statement s = c.createStatement()) {
        s.execute("VACUUM INTO '" + absolute.toString().replace("'", "''") + "'");
      }
      done = true;
    } catch (SQLException e) {
      throw new IOException("SQLite couldn't write the copy: " + e.getMessage(), e);
    } finally {
      if (!done) {
        Files.deleteIfExists(absolute);
      }
    }
  }

  /**
   * Writes a consistent copy over {@code target} (a file the person chose and agreed to replace): the copy is made
   * next to it and moved in at once, so an interrupted backup never leaves half a database under the chosen name.
   */
  public void backupOver(Path target) throws IOException {
    Path absolute = target.toAbsolutePath();
    Path temp = absolute.resolveSibling("." + absolute.getFileName() + "." + Ids.uuid() + ".tmp");
    backupInto(temp);
    try {
      try {
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static String scalar(Statement s, String sql) throws SQLException {
    try (ResultSet rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static long size(Path path) {
    try {
      return Files.exists(path) ? Files.size(path) : 0;
    } catch (IOException e) {
      // The WAL comes and goes with checkpoints; a file that vanished between the two calls counts as empty.
      return 0;
    }
  }
}
