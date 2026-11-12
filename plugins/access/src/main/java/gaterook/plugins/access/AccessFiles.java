package gaterook.plugins.access;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The file of an Access connection (docs/access.md), chosen by the person on this computer, and nothing else. The rules
 * are the DuckDB plugin's:
 * <ul>
 *   <li>an absolute path or {@code ~/…}, without control characters, {@code ?}, {@code ;} (UCanAccess reads what
 *       follows a semicolon in its URL as settings) or, outside Windows, {@code \}, and not a network path;</li>
 *   <li>an existing regular file, links followed, that starts like an Access database: "Standard Jet DB" (an
 *       {@code .mdb}) or "Standard ACE DB" (an {@code .accdb}) at byte 4; UCanAccess never creates one here;</li>
 *   <li>never in GateRook's own folder, which a plugin finds above its work folder {@code plugin-data/<id>/};</li>
 *   <li>at most {@link #MAX_BYTES}, Access's own limit being 2 GB.</li>
 * </ul>
 * The file is checked again whenever a call opens it, and UCanAccess is given the real path that was checked.
 */
final class AccessFiles {

  static final int MAGIC_OFFSET = 4;
  static final byte[] JET = "Standard Jet DB".getBytes(StandardCharsets.US_ASCII);
  static final byte[] ACE = "Standard ACE DB".getBytes(StandardCharsets.US_ASCII);
  /** Larger files aren't opened: UCanAccess copies the whole file into HSQLDB. */
  static final long MAX_BYTES = 1L << 30;

  static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");

  private AccessFiles() {}

  /** The configured path as a path on this computer, without I/O; or why it isn't one. */
  static Path path(String configured) throws ConnectorException {
    if (configured == null || configured.isBlank()) {
      throw ConnectorException.badInput("Choose the Access database file of the connection.");
    }
    String text = configured.strip();
    if (text.length() > 4_096) {
      throw ConnectorException.badInput("The database file path is too long.");
    }
    String problem = characters(text);
    if (problem != null) {
      throw ConnectorException.badInput("A database file path can't contain " + problem + ".");
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

  /** What in {@code text} UCanAccess would read otherwise than as a file name, or {@code null}. */
  static String characters(String text) {
    if (text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      return "control characters";
    }
    if (text.indexOf('?') >= 0) {
      return "a question mark";
    }
    if (text.indexOf(';') >= 0) {
      return "a semicolon (UCanAccess reads what follows it as settings)";
    }
    if (!WINDOWS && text.indexOf('\\') >= 0) {
      return "a backslash";
    }
    return null;
  }

  /**
   * The real database file behind {@code configured}, checked: it exists, is a regular Access file of at most
   * {@link #MAX_BYTES}, and is outside {@code gaterookHome} (when known).
   */
  static Path database(String configured, Path gaterookHome) throws ConnectorException {
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
      throw ConnectorException.badInput(p + " isn't a file. Choose the .accdb or .mdb file itself.");
    }
    String problem = characters(real.toString());
    if (problem != null) {
      throw ConnectorException.badInput(p + " leads to a path with " + problem + ".");
    }
    if (gaterookHome != null && gaterooks(real, gaterookHome)) {
      throw ConnectorException.denied("GateRook's own files can't be the database of a connection.");
    }
    byte[] head = new byte[MAGIC_OFFSET + JET.length];
    int read;
    try (InputStream in = Files.newInputStream(real)) {
      read = in.readNBytes(head, 0, head.length);
    } catch (IOException | SecurityException e) {
      throw ConnectorException.badInput("GateRook may not read the database file " + p + ".");
    }
    if (read == 0) {
      throw ConnectorException.badInput(p + " is empty: it isn't an Access database.");
    }
    if (read < head.length || !(starts(head, JET) || starts(head, ACE))) {
      throw ConnectorException.badInput(p + " isn't an Access database (.accdb or .mdb).");
    }
    long size = size(real);
    if (size > MAX_BYTES) {
      throw ConnectorException.badInput(p + " is larger than 1 GB: GateRook copies an Access file whole to read it.");
    }
    return real;
  }

  private static boolean starts(byte[] head, byte[] magic) {
    for (int i = 0; i < magic.length; i++) {
      if (head[MAGIC_OFFSET + i] != magic[i]) {
        return false;
      }
    }
    return true;
  }

  /** Whether {@code real} is GateRook's folder, lies in it, or is its database. */
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

  /** GateRook's home for a plugin's work folder ({@code <home>/plugin-data/<id>}), or the work folder itself. */
  static Path home(Path workDir) {
    if (workDir == null) {
      return null;
    }
    Path dir = workDir.toAbsolutePath().normalize();
    Path parent = dir.getParent();
    return parent != null && parent.getFileName() != null && "plugin-data".equals(parent.getFileName().toString())
        && parent.getParent() != null ? parent.getParent() : dir;
  }

  /** Whether this process may write the file: what an approved change needs. */
  static boolean writable(Path real) {
    try {
      return Files.isWritable(real);
    } catch (SecurityException e) {
      return false;
    }
  }

  /**
   * Whether the file opens for writing here, tried without writing a byte: Jackcess would otherwise open a file it may
   * not write read-only, and a change would fail only when it commits. A sandbox that closes writes outside the
   * plugin's folder refuses this open even where the file's permissions allow it.
   */
  static boolean opensForWriting(Path real) {
    try (java.nio.channels.FileChannel ignored = java.nio.channels.FileChannel.open(real,
        java.nio.file.StandardOpenOption.WRITE)) {
      return true;
    } catch (IOException | SecurityException | UnsupportedOperationException e) {
      return false;
    }
  }

  static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException | SecurityException e) {
      return 0;
    }
  }

  /** Jet ({@code .mdb}) or ACE ({@code .accdb}), by the header. */
  static String format(Path real) {
    byte[] head = new byte[MAGIC_OFFSET + JET.length];
    try (InputStream in = Files.newInputStream(real)) {
      if (in.readNBytes(head, 0, head.length) == head.length) {
        return starts(head, ACE) ? "ACE (.accdb)" : starts(head, JET) ? "Jet (.mdb)" : "unknown";
      }
    } catch (IOException | SecurityException e) {
      // Shown as unknown.
    }
    return "unknown";
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
