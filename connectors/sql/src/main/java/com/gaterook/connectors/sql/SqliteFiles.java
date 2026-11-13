package com.gaterook.connectors.sql;

import com.gaterook.connector.api.ConnectorException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The database file of a SQLite connection (docs/sqlite.md). The person chooses it on this computer, and the
 * connector opens nothing else:
 * <ul>
 *   <li>an absolute path or {@code ~/…}, without {@code ?} (sqlite-jdbc reads what follows it as settings), control
 *       characters, or a network or device path;</li>
 *   <li>an existing regular file that starts with SQLite's header, or is empty, links followed;</li>
 *   <li>never in GateRook's own folder: {@code gateway.db} holds the approvals, the audit trail and the encrypted
 *       secrets.</li>
 * </ul>
 * The file is checked whenever a connection to it opens, not only at Test connection: a link changed afterwards is
 * caught when the next connection opens.
 */
final class SqliteFiles {

  static final String URL_PREFIX = "jdbc:sqlite:";
  private static final byte[] HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
  private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

  private SqliteFiles() {}

  /** The configured path as a file name on this computer, without I/O; or why it isn't one. */
  static Path path(String configured) throws ConnectorException {
    if (configured == null || configured.isBlank()) {
      throw ConnectorException.badInput("Choose the database file of the connection.");
    }
    String text = configured.strip();
    if (text.length() > 4_096 || text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw ConnectorException.badInput("The database file path isn't valid.");
    }
    if (text.indexOf('?') >= 0) {
      throw ConnectorException.badInput("A database file path can't contain a question mark.");
    }
    if (text.startsWith("\\\\") || text.startsWith("//")) {
      throw ConnectorException.badInput("Choose a database file on this computer, not on a network share.");
    }
    if (text.equals("~") || text.startsWith("~/") || (WINDOWS && text.startsWith("~\\"))) {
      text = System.getProperty("user.home") + text.substring(1);
    }
    Path p;
    try {
      p = Path.of(text);
    } catch (InvalidPathException e) {
      throw ConnectorException.badInput("The database file path isn't valid: " + e.getReason() + ".");
    }
    if (!p.isAbsolute()) {
      throw ConnectorException.badInput("Use an absolute path or ~/… for the database file.");
    }
    return p.normalize();
  }

  /** The URL of the configured file, as {@link SqliteDialect#url} builds it; the driver checks it again. */
  static String url(String configured) {
    try {
      return URL_PREFIX + path(configured);
    } catch (ConnectorException e) {
      return URL_PREFIX + (configured == null ? "" : configured.strip());
    }
  }

  /**
   * The real file behind {@code configured}, checked: it exists, is a regular file that starts with SQLite's header
   * or is empty, and is outside {@code gaterookHome} (when known).
   */
  static Path check(String configured, Path gaterookHome) throws ConnectorException {
    Path p = path(configured);
    Path real;
    try {
      real = p.toRealPath();
    } catch (NoSuchFileException e) {
      throw ConnectorException.badInput("The database file " + p + " doesn't exist.");
    } catch (AccessDeniedException e) {
      throw ConnectorException.badInput("GateRook may not read the database file " + p + ".");
    } catch (IOException | SecurityException e) {
      throw ConnectorException.badInput("The database file " + p + " can't be read: " + e.getMessage() + ".");
    }
    if (!Files.isRegularFile(real)) {
      throw ConnectorException.badInput(p + " isn't a file. Choose the database file itself.");
    }
    if (gaterookHome != null && gaterooks(real, gaterookHome)) {
      throw ConnectorException.denied("GateRook's own files can't be the database of a connection.");
    }
    byte[] head = new byte[HEADER.length];
    int read;
    try (InputStream in = Files.newInputStream(real)) {
      read = in.readNBytes(head, 0, head.length);
    } catch (IOException | SecurityException e) {
      throw ConnectorException.badInput("GateRook may not read the database file " + p + ".");
    }
    if (read != 0 && (read != HEADER.length || !Arrays.equals(head, HEADER))) {
      throw ConnectorException.badInput(p + " isn't a SQLite database.");
    }
    return real;
  }

  /**
   * Whether the real file is GateRook's: in its home folder (compared as files, so another spelling of the folder on a
   * case-insensitive disk is the same one), or another name of its database or the database's journal files.
   */
  static boolean gaterooks(Path real, Path gaterookHome) {
    Path home;
    try {
      home = gaterookHome.toRealPath();
    } catch (IOException | SecurityException e) {
      home = gaterookHome.toAbsolutePath().normalize();
    }
    if (real.startsWith(home)) {
      return true;
    }
    for (Path dir = real.getParent(); dir != null; dir = dir.getParent()) {
      if (same(dir, home)) {
        return true;
      }
    }
    for (String name : List.of("gateway.db", "gateway.db-wal", "gateway.db-shm", "gateway.db-journal")) {
      if (same(real, home.resolve(name))) {
        return true;
      }
    }
    return false;
  }

  private static boolean same(Path a, Path b) {
    try {
      return Files.exists(b) && Files.isSameFile(a, b);
    } catch (IOException | SecurityException e) {
      return false;
    }
  }

  /**
   * GateRook's home for a built-in connector's work folder ({@code <home>/connectors/<type>}), or the work folder
   * itself when it isn't laid out that way.
   */
  static Path home(Path workDir) {
    if (workDir == null) {
      return null;
    }
    Path parent = workDir.toAbsolutePath().normalize().getParent();
    return parent != null && parent.getFileName() != null && "connectors".equals(parent.getFileName().toString())
        && parent.getParent() != null ? parent.getParent() : workDir;
  }
}
