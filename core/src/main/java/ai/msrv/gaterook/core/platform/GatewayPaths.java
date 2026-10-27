package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.core.platform.boot.GatewayStartupException;
import ai.msrv.gaterook.core.platform.boot.StartupReason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The gateway home ({@code ~/.mcp-gateway}) and its layout. Opening it creates the directories owner-only (0700),
 * prepares {@code gateway.db} as 0600 before SQLite creates it (SQLite gives {@code -wal} and {@code -shm} the
 * database's permissions) and takes {@code core.lock}: one core per home, a second one fails with {@code DB_LOCKED}
 * instead of sharing the database with it.
 *
 * <p>On file systems without POSIX permissions (Windows) the permissions are left to the ACLs of the user profile
 * (owner-only ACLs: UNVERIFIED, report 12 §6).
 */
public final class GatewayPaths implements AutoCloseable {

  private static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");

  private final Path home;
  private final boolean databaseExisted;
  private final FileChannel lockChannel;
  private final FileLock lock;

  private GatewayPaths(Path home, boolean databaseExisted, FileChannel lockChannel, FileLock lock) {
    this.home = home;
    this.databaseExisted = databaseExisted;
    this.lockChannel = lockChannel;
    this.lock = lock;
  }

  /**
   * Opens {@code home}: creates the layout and takes the home lock.
   *
   * @throws GatewayStartupException {@code DB_LOCKED} when another core holds the home
   */
  public static GatewayPaths open(Path home) {
    Path root = home.toAbsolutePath().normalize();
    try {
      for (Path dir : new Path[] {root, root.resolve("logs"), root.resolve("backups"), root.resolve("tmp"),
          root.resolve("run")}) {
        ownerOnlyDirectory(dir);
      }
      Path lockFile = root.resolve("core.lock");
      FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock lock;
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        lock = null;
      }
      if (lock == null) {
        channel.close();
        throw new GatewayStartupException(StartupReason.DB_LOCKED,
            "Another MCP Gateway core is using " + root + " (" + lockFile.getFileName() + " is locked)");
      }
      channel.truncate(0);
      channel.write(ByteBuffer.wrap((ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.US_ASCII)));
      ownerOnlyFile(lockFile);

      Path database = root.resolve("gateway.db");
      boolean existed = Files.exists(database) && Files.size(database) > 0;
      if (!Files.exists(database)) {
        Files.createFile(database);
      }
      ownerOnlyFile(database);
      return new GatewayPaths(root, existed, channel, lock);
    } catch (IOException e) {
      throw new UncheckedIOException("Can't prepare the gateway home " + root, e);
    }
  }

  public Path home() {
    return home;
  }

  /** {@code gateway.db}, the SQLite database. */
  public Path database() {
    return home.resolve("gateway.db");
  }

  /** Whether {@code gateway.db} had content before this start: no means first run. */
  public boolean databaseExisted() {
    return databaseExisted;
  }

  public Path logs() {
    return home.resolve("logs");
  }

  /** Database backups, daily and pre-migration (never the master key). */
  public Path backups() {
    return home.resolve("backups");
  }

  /** {@code java.io.tmpdir} of the core and its children. */
  public Path tmp() {
    return home.resolve("tmp");
  }

  /** Runtime files: broker socket names, pid files of child processes. */
  public Path run() {
    return home.resolve("run");
  }

  /** Releases the home lock. */
  @Override
  public void close() {
    try {
      if (lock.isValid()) {
        lock.release();
      }
      lockChannel.close();
    } catch (IOException e) {
      // The lock goes away with the process anyway.
    }
  }

  /** Creates {@code dir} if needed and makes it 0700. */
  public static void ownerOnlyDirectory(Path dir) throws IOException {
    if (posix()) {
      if (!Files.isDirectory(dir)) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_DIR));
      }
      Files.setPosixFilePermissions(dir, OWNER_DIR);
    } else {
      Files.createDirectories(dir);
    }
  }

  /** Makes an existing file 0600. */
  public static void ownerOnlyFile(Path file) throws IOException {
    if (posix()) {
      Files.setPosixFilePermissions(file, OWNER_FILE);
    }
  }

  private static boolean posix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  @Override
  public String toString() {
    return "GatewayPaths[" + home + "]";
  }
}
