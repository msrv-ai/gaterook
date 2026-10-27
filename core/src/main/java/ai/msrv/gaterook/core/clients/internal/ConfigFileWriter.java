package ai.msrv.gaterook.core.clients.internal;

import ai.msrv.gaterook.core.clients.ConfigEditException;
import ai.msrv.gaterook.core.clients.ConfigEditException.Reason;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes a client's config file the way report 03 §7.3 and ARCHITECTURE.md §7.2 ask: the file must still be what the
 * preview read (SHA-256, else {@link StaleFileException} → 409 and a new preview); a backup {@code <file>.bak-YYYY-MM-DD}
 * goes next to it with the original's permissions (0600 when it holds a token) and a copy into
 * {@code ~/.gaterook/backups/<client>/} (folder 0700, files 0600, the newest 20 kept); the new content goes to a
 * temporary file in the same folder, is synced and moved over the original atomically. A symlink is followed and the
 * real file written. A new file, and a file that now holds a token, is 0600; otherwise the original's permissions stay.
 * Files over 1 MiB and anything that isn't a regular file are refused.
 */
public final class ConfigFileWriter {

  public static final long MAX_BYTES = 1L << 20;
  public static final int KEEP_BACKUPS = 20;
  private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

  /** What the preview read: the real path, the content and its hash ({@code null} content: no file). */
  public record Snapshot(Path path, Path realPath, boolean exists, String content, String sha256,
      Set<PosixFilePermission> permissions) {}

  /** What was written. */
  public record Written(Path realPath, String sha256, Path backup, Path appBackup) {}

  /** The file changed between the preview and Confirm. */
  public static final class StaleFileException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StaleFileException(Path file) {
      super(file + " changed since the preview: check the change again");
    }
  }

  private final Path backupsRoot;
  private final Clock clock;

  /** @param backupsRoot {@code ~/.gaterook/backups} */
  public ConfigFileWriter(Path backupsRoot, Clock clock) {
    this.backupsRoot = Objects.requireNonNull(backupsRoot, "backupsRoot");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Reads {@code file} for a preview; a missing file is an empty snapshot. */
  public static Snapshot read(Path file) {
    try {
      Path real = realPath(file);
      if (!Files.exists(real, LinkOption.NOFOLLOW_LINKS)) {
        return new Snapshot(file, real, false, null, sha256((byte[]) null), null);
      }
      if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
        throw new ConfigEditException(Reason.UNSUPPORTED_FILE, real + " isn't a regular file, so GateRook "
            + "doesn't write it.");
      }
      if (Files.size(real) > MAX_BYTES) {
        throw new ConfigEditException(Reason.UNSUPPORTED_FILE, real + " is larger than 1 MiB, so GateRook "
            + "doesn't edit it.");
      }
      byte[] bytes = Files.readAllBytes(real);
      String content;
      try {
        content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException e) {
        throw new ConfigEditException(Reason.INVALID, real + " isn't UTF-8 text, so GateRook doesn't edit it.");
      }
      return new Snapshot(file, real, true, content, sha256(bytes), permissions(real));
    } catch (IOException e) {
      throw new ConfigEditException(Reason.UNSUPPORTED_FILE, "Can't read " + file + ": " + e.getMessage());
    }
  }

  /**
   * Replaces the file read as {@code before} with {@code content}.
   *
   * @param client      folder of the app's backup copies ({@code codex})
   * @param tokenInside the new content holds a token: the file (and its backups) become 0600
   * @throws StaleFileException the file isn't what {@code before} read any more
   */
  public Written write(Snapshot before, String content, String client, boolean tokenInside) throws IOException {
    Path real = realPath(before.path());
    Snapshot now = read(before.path());
    if (!now.sha256().equals(before.sha256()) || !real.equals(before.realPath())) {
      throw new StaleFileException(real);
    }
    Path backup = null;
    Path appBackup = null;
    if (now.exists()) {
      boolean secretBefore = holdsToken(now.content());
      backup = nextFree(real.resolveSibling(real.getFileName() + ".bak-" + LocalDate.now(clock.withZone(
          ZoneId.systemDefault()))));
      Files.copy(real, backup, StandardCopyOption.COPY_ATTRIBUTES);
      if (secretBefore) {
        ownerOnly(backup);
      }
      appBackup = appBackup(client, real, now.content());
    }
    Files.createDirectories(real.getParent());
    Path tmp = Files.createTempFile(real.getParent(), "." + real.getFileName(), ".mcpgw.tmp");
    try {
      try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer data = StandardCharsets.UTF_8.encode(content);
        while (data.hasRemaining()) {
          ch.write(data);
        }
        ch.force(true);
      }
      if (posix()) {
        Set<PosixFilePermission> mode = !now.exists() || tokenInside ? OWNER_FILE : now.permissions();
        Files.setPosixFilePermissions(tmp, mode);
      }
      move(tmp, real);
    } finally {
      Files.deleteIfExists(tmp);
    }
    syncDirectory(real.getParent());
    return new Written(real, sha256(content.getBytes(StandardCharsets.UTF_8)), backup, appBackup);
  }

  /** Copies the current content into {@code backups/<client>/<time>-<name>} (0600) and keeps the newest 20. */
  Path appBackup(String client, Path real, String content) throws IOException {
    Path dir = backupsRoot.resolve(client);
    GatewayPaths.ownerOnlyDirectory(backupsRoot);
    GatewayPaths.ownerOnlyDirectory(dir);
    String stamp = ZonedDateTime.now(clock.withZone(ZoneId.systemDefault())).format(STAMP);
    Path target = nextFree(dir.resolve(stamp + "-" + real.getFileName()));
    // Owner-only from its first byte: the copy may hold a token.
    if (posix()) {
      Files.createFile(target, PosixFilePermissions.asFileAttribute(OWNER_FILE));
    } else {
      Files.createFile(target);
    }
    GatewayPaths.ownerOnlyFile(target);
    Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    prune(dir);
    return target;
  }

  /** The app's copies of {@code client}, newest first. */
  public List<Path> backups(String client) {
    Path dir = backupsRoot.resolve(client);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::getFileName).reversed()).toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  public Path backupsOf(String client) {
    return backupsRoot.resolve(client);
  }

  private void prune(Path dir) throws IOException {
    List<Path> all;
    try (Stream<Path> s = Files.list(dir)) {
      all = new ArrayList<>(s.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::getFileName)).toList());
    }
    while (all.size() > KEEP_BACKUPS) {
      Files.deleteIfExists(all.removeFirst());
    }
  }

  /** {@code name}, else {@code name-2}, {@code name-3}, … */
  static Path nextFree(Path candidate) {
    if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      return candidate;
    }
    for (int i = 2; ; i++) {
      Path p = candidate.resolveSibling(candidate.getFileName() + "-" + i);
      if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
        return p;
      }
    }
  }

  /** A symlink's target (the file we really write); a missing file's own path. */
  static Path realPath(Path file) throws IOException {
    Path abs = file.toAbsolutePath().normalize();
    if (Files.isSymbolicLink(abs)) {
      Path target = Files.readSymbolicLink(abs);
      Path resolved = abs.resolveSibling(target).normalize();
      return Files.exists(resolved) ? resolved.toRealPath() : resolved;
    }
    if (Files.exists(abs)) {
      return abs.toRealPath();
    }
    Path parent = abs.getParent();
    return parent != null && Files.exists(parent) ? parent.toRealPath().resolve(abs.getFileName()) : abs;
  }

  private static void move(Path tmp, Path real) throws IOException {
    for (int attempt = 0; ; attempt++) {
      try {
        Files.move(tmp, real, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return;
      } catch (AccessDeniedException e) {
        // Windows: a reader (the client, an antivirus) may hold the file for a moment.
        if (attempt >= 2) {
          throw e;
        }
        try {
          Thread.sleep(100L * (attempt + 1));
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  private static void syncDirectory(Path dir) {
    if (!posix()) {
      return;
    }
    try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
      ch.force(true);
    } catch (IOException ignored) {
      // Not every file system lets a directory be synced; the rename itself is atomic.
    }
  }

  static void ownerOnly(Path file) throws IOException {
    GatewayPaths.ownerOnlyFile(file);
  }

  private static Set<PosixFilePermission> permissions(Path file) throws IOException {
    return posix() ? Files.getPosixFilePermissions(file) : null;
  }

  private static boolean posix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  /** Whether a config text holds a named token of ours. */
  public static boolean holdsToken(String content) {
    return content != null && content.contains("gwt_");
  }

  /** Hex SHA-256 of the bytes; of "no file" for {@code null}. */
  public static String sha256(byte[] bytes) {
    if (bytes == null) {
      return "absent";
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256(String text) {
    return text == null ? sha256((byte[]) null) : sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
