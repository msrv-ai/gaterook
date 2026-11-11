package gaterook.plugins.duckdb;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The files of a DuckDB connection (docs/duckdb.md): the database file and the data folders, both chosen by the person
 * on this computer, and nothing else. The rules mirror the built-in SQLite connector's:
 * <ul>
 *   <li>an absolute path or {@code ~/…}, without control characters, {@code ?}, {@code ;} (the DuckDB driver reads
 *       what follows a semicolon in its URL as settings) or, outside Windows, {@code \} (DuckDB reads it as a folder
 *       separator, so it would open another file than the one checked), and not a network or device path;</li>
 *   <li>the database: an existing regular file, links followed, that starts like a DuckDB database (its main header's
 *       magic {@code DUCK} at byte 8); an empty file isn't one, and DuckDB never creates a file here;</li>
 *   <li>never in GateRook's own folder ({@code gateway.db} holds the approvals, the audit trail and the encrypted
 *       secrets), which a plugin finds above its work folder {@code plugin-data/<id>/};</li>
 *   <li>a data folder: an existing folder that is neither in GateRook's folder nor above it, nor the person's home or
 *       a folder above it, nor a folder that holds the database file: DuckDB lets a statement write the files of its
 *       allowed folders, so the database stays outside them.</li>
 * </ul>
 * Everything is checked again whenever a connection opens, not only at Test connection: a link changed afterwards is
 * caught when the next call opens the file. DuckDB is given the real path that was checked.
 */
final class DuckDbFiles {

  /** DuckDB's main header: an 8-byte checksum, then the magic bytes. */
  static final byte[] MAGIC = "DUCK".getBytes(StandardCharsets.US_ASCII);
  static final int MAGIC_OFFSET = 8;

  static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

  /** A data folder: as configured (absolute, normalized) and as the file system resolves it. */
  record Folder(Path configured, Path real) {}

  private DuckDbFiles() {}

  /** The configured path as a path on this computer, without I/O; or why it isn't one. */
  static Path path(String configured, String what) throws ConnectorException {
    if (configured == null || configured.isBlank()) {
      throw ConnectorException.badInput("Choose the " + what + " of the connection.");
    }
    String text = configured.strip();
    if (text.length() > 4_096) {
      throw ConnectorException.badInput("The " + what + " path is too long.");
    }
    String problem = characters(text);
    if (problem != null) {
      throw ConnectorException.badInput("A " + what + " path can't contain " + problem + ".");
    }
    if (text.startsWith("\\\\") || text.startsWith("//")) {
      throw ConnectorException.badInput("Choose a " + what + " on this computer, not on a network share.");
    }
    if (text.equals("~") || text.startsWith("~/") || (WINDOWS && text.startsWith("~\\"))) {
      text = System.getProperty("user.home") + text.substring(1);
    }
    Path p;
    try {
      p = Path.of(text);
    } catch (InvalidPathException e) {
      throw ConnectorException.badInput("The " + what + " path isn't valid: " + e.getReason() + ".");
    }
    if (!p.isAbsolute()) {
      throw ConnectorException.badInput("Use an absolute path or ~/… for the " + what + ".");
    }
    return p.normalize();
  }

  /** What in {@code text} DuckDB or its driver would read otherwise than as a file name, or {@code null}. */
  static String characters(String text) {
    if (text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      return "control characters";
    }
    if (text.indexOf('?') >= 0) {
      return "a question mark";
    }
    if (text.indexOf(';') >= 0) {
      return "a semicolon (the DuckDB driver reads what follows it as settings)";
    }
    if (!WINDOWS && text.indexOf('\\') >= 0) {
      return "a backslash (DuckDB reads it as a folder separator)";
    }
    return null;
  }

  /**
   * The real database file behind {@code configured}, checked: it exists, is a regular file that starts like a DuckDB
   * database, its real path is one DuckDB takes as it is, and it is outside {@code gaterookHome} (when known).
   */
  static Path database(String configured, Path gaterookHome) throws ConnectorException {
    Path p = path(configured, "database file");
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
    String problem = characters(real.toString());
    if (problem != null) {
      throw ConnectorException.badInput(p + " leads to a path with " + problem + ".");
    }
    if (gaterookHome != null && gaterooks(real, gaterookHome)) {
      throw ConnectorException.denied("GateRook's own files can't be the database of a connection.");
    }
    byte[] head = new byte[MAGIC_OFFSET + MAGIC.length];
    int read;
    try (InputStream in = Files.newInputStream(real)) {
      read = in.readNBytes(head, 0, head.length);
    } catch (IOException | SecurityException e) {
      throw ConnectorException.badInput("GateRook may not read the database file " + p + ".");
    }
    if (read == 0) {
      throw ConnectorException.badInput(p + " is empty: it isn't a DuckDB database.");
    }
    if (read < head.length || !Arrays.equals(head, MAGIC_OFFSET, head.length, MAGIC, 0, MAGIC.length)) {
      throw ConnectorException.badInput(p + " isn't a DuckDB database.");
    }
    return real;
  }

  /**
   * The data folders, checked against the rules of the class: each exists and is a folder, and none is GateRook's,
   * holds GateRook's folder or the person's home, or holds {@code database} (the real database file).
   */
  static List<Folder> folders(List<String> configured, Path gaterookHome, Path database) throws ConnectorException {
    List<Folder> out = new ArrayList<>();
    Path home = gaterookHome == null ? null : real(gaterookHome);
    Path user = real(Path.of(System.getProperty("user.home")));
    for (String text : configured) {
      Path p = path(text, "data folder");
      if (p.toString().indexOf('*') >= 0) {
        throw ConnectorException.badInput("A data folder path can't contain *: name the folder itself.");
      }
      Path real;
      try {
        real = p.toRealPath();
      } catch (NoSuchFileException e) {
        throw ConnectorException.badInput("The data folder " + p + " doesn't exist.");
      } catch (AccessDeniedException e) {
        throw ConnectorException.badInput("GateRook may not read the data folder " + p + ".");
      } catch (IOException | SecurityException e) {
        throw ConnectorException.badInput("The data folder " + p + " can't be read: " + e.getMessage() + ".");
      }
      if (!Files.isDirectory(real)) {
        throw ConnectorException.badInput(p + " isn't a folder. Choose the folder your data files are in.");
      }
      String problem = characters(real.toString());
      if (problem != null) {
        throw ConnectorException.badInput("The data folder " + p + " leads to a path with " + problem + ".");
      }
      if (real.getParent() == null) {
        throw ConnectorException.denied("The root of a disk can't be a data folder: choose the folder your data "
            + "files are in.");
      }
      if (home != null && (gaterooks(real, home) || home.startsWith(real))) {
        throw ConnectorException.denied("A data folder can't be GateRook's own folder, lie in it or hold it.");
      }
      if (user != null && user.startsWith(real)) {
        throw ConnectorException.denied("Your home folder, or a folder above it, can't be a data folder: choose the "
            + "folder your data files are in.");
      }
      if (database != null && database.startsWith(real)) {
        throw ConnectorException.denied("The data folder " + p + " holds the database file. DuckDB lets a statement "
            + "write files in its data folders, so keep the database file outside them, or choose a subfolder.");
      }
      out.add(new Folder(p, real));
    }
    return List.copyOf(out);
  }

  /**
   * The folders as DuckDB's {@code allowed_directories} and the classifier compare them: each folder as configured
   * and as resolved, once, with {@code /} separators and a trailing {@code /}.
   */
  static List<String> prefixes(List<Path> folders) {
    Set<String> out = new LinkedHashSet<>();
    for (Path p : folders) {
      out.add(prefix(p));
    }
    return List.copyOf(out);
  }

  /** {@code /Users/ann/data/} for {@code /Users/ann/data}. */
  static String prefix(Path p) {
    String text = WINDOWS ? p.toString().replace('\\', '/') : p.toString();
    return text.endsWith("/") ? text : text + "/";
  }

  /** The configured folders as paths, without I/O (classification); a folder that isn't a path is left out. */
  static List<Path> configuredFolders(List<String> configured) {
    List<Path> out = new ArrayList<>();
    for (String text : configured) {
      try {
        out.add(path(text, "data folder"));
      } catch (ConnectorException e) {
        // Refused when the call runs: no prefix for it.
      }
    }
    return out;
  }

  /**
   * Whether the real file is GateRook's: in its home folder (compared as files, so another spelling of the folder on a
   * case-insensitive disk is the same one), or another name of its database or the database's journal files.
   */
  static boolean gaterooks(Path real, Path gaterookHome) {
    Path home = real(gaterookHome);
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

  /**
   * GateRook's home for a plugin's work folder ({@code <home>/plugin-data/<id>}), or the work folder itself when it
   * isn't laid out that way.
   */
  static Path home(Path workDir) {
    if (workDir == null) {
      return null;
    }
    Path dir = workDir.toAbsolutePath().normalize();
    Path parent = dir.getParent();
    return parent != null && parent.getFileName() != null && "plugin-data".equals(parent.getFileName().toString())
        && parent.getParent() != null ? parent.getParent() : dir;
  }

  /** Whether this process may write the file and create DuckDB's write-ahead log next to it. */
  static boolean writable(Path real) {
    try {
      Path folder = real.getParent();
      return Files.isWritable(real) && folder != null && Files.isWritable(folder);
    } catch (SecurityException e) {
      return false;
    }
  }

  private static boolean same(Path a, Path b) {
    try {
      return Files.exists(b) && Files.isSameFile(a, b);
    } catch (IOException | SecurityException e) {
      return false;
    }
  }

  private static Path real(Path p) {
    try {
      return p.toRealPath();
    } catch (IOException | SecurityException e) {
      return p.toAbsolutePath().normalize();
    }
  }
}
