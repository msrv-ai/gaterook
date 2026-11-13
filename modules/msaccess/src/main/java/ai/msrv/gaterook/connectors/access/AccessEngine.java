package ai.msrv.gaterook.connectors.access;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.ucanaccess.jdbc.UcanaccessDriver;

/**
 * Connections to an Access file through UCanAccess (docs/access.md), which copies the file into HSQLDB through
 * Jackcess and writes changes back to the file when they commit.
 *
 * <p>A read opens the file with {@link ReadOnlyOpener}: Jackcess can't write the file, and the HSQLDB session is
 * read-only, so HSQLDB refuses any change itself. UCanAccess keeps that copy for a while after the last connection
 * closes, and keeps one copy per path as {@code File.getAbsolutePath()} spells it. It loads the copy again only when the
 * file changed at least two seconds after it was loaded, so after an approved change of this process the next read
 * spells the path anew ({@code <folder>/./<file>}, then {@code <folder>/././<file>} …) and gets a fresh copy; the old one
 * goes when it has been idle for UCanAccess's inactivity timeout. A change opens {@code <folder>/../<name>/<file>},
 * which no read spells, so it gets a writable copy of its own, released when its connection closes. Files larger than
 * {@link #IN_MEMORY_BYTES} are copied into an HSQLDB database on disk in the connector's data folder instead of the heap.
 */
final class AccessEngine {

  /** Up to this size the copy lives in memory. */
  static final long IN_MEMORY_BYTES = 128L << 20;

  private AccessEngine() {}

  /** Changes this process made to each file, by real path: a read of a later generation gets a fresh copy. */
  private static final Map<String, AtomicInteger> GENERATIONS = new ConcurrentHashMap<>();

  /** A read-only connection: the file through a read-only Jackcess, an HSQLDB session that refuses changes. */
  static Connection read(Path file, Path workDir) throws SQLException {
    Properties p = common(file, workDir);
    p.setProperty("jackcessOpener", ReadOnlyOpener.class.getName());
    Connection c = new UcanaccessDriver().connect(url(readSpelling(file, generation(file))), p);
    try {
      c.setReadOnly(true);
      c.setAutoCommit(false);
      return c;
    } catch (SQLException | RuntimeException e) {
      c.close();
      throw e;
    }
  }

  /** A writable connection on a copy of its own, released when it closes; autocommit off. */
  static Connection write(Path file, Path workDir) throws SQLException {
    Properties p = common(file, workDir);
    p.setProperty("immediatelyReleaseResources", "true");
    Connection c = new UcanaccessDriver().connect(url(writeSpelling(file)), p);
    try {
      c.setAutoCommit(false);
      return c;
    } catch (SQLException | RuntimeException e) {
      c.close();
      throw e;
    }
  }

  private static Properties common(Path file, Path workDir) {
    Properties p = new Properties();
    // The file's own tables and queries; UCanAccess's system tables stay out of sight.
    p.setProperty("showSchema", "false");
    p.setProperty("sysSchema", "false");
    p.setProperty("allowRemoteLinks", "false");
    if (AccessFiles.size(file) > IN_MEMORY_BYTES && workDir != null) {
      p.setProperty("memory", "false");
      p.setProperty("mirrorFolder", workDir.resolve("tmp").toString());
    }
    return p;
  }

  /** The file may have changed through this process: later reads load it again. */
  static void changed(Path file) {
    GENERATIONS.computeIfAbsent(file.toString(), k -> new AtomicInteger()).incrementAndGet();
  }

  static int generation(Path file) {
    AtomicInteger g = GENERATIONS.get(file.toString());
    return g == null ? 0 : g.get();
  }

  /** {@code file} with {@code generation} {@code ./} segments before its name: one copy per generation. */
  static String readSpelling(Path file, int generation) {
    Path parent = file.getParent();
    if (parent == null || generation == 0) {
      return file.toString();
    }
    String sep = File.separator;
    return parent + sep + ("." + sep).repeat(generation) + file.getFileName();
  }

  /** {@code <folder>/../<folder's name>/<file>}: the same file under a spelling no read uses. */
  static String writeSpelling(Path file) {
    Path parent = file.getParent();
    if (parent == null || parent.getFileName() == null) {
      return file.toString();
    }
    String sep = File.separator;
    return parent + sep + ".." + sep + parent.getFileName() + sep + file.getFileName();
  }

  static String url(String path) {
    return "jdbc:ucanaccess://" + path;
  }
}
