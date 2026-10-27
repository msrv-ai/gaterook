package ai.msrv.gaterook.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * {@code ~/.mcp-gateway/logs/bridge.log} (0600, one copy kept at 1 MiB): Codex throws a helper's stderr away, so the
 * reason a helper failed is written here, and the client card shows it ("Codex helper failed at 14:29: grant
 * revoked"). Lines carry a grant's prefix only and never a token: anything shaped like one is cut out first.
 */
final class BridgeLog {

  private static final long ROTATE_AT = 1L << 20;
  private static final Pattern TOKEN = Pattern.compile("gw[tbor]_[A-Za-z0-9_-]{8,}");
  private static final Pattern GRANT = Pattern.compile("(gr_[a-z0-9]{4})[a-z0-9]{8}");

  private final Path file;

  BridgeLog(Path home) {
    this.file = home.resolve("logs").resolve("bridge.log");
  }

  Path file() {
    return file;
  }

  /** One line; never fails the bridge. */
  synchronized void write(String command, String line) {
    String clean = GRANT.matcher(TOKEN.matcher(line).replaceAll("‹token›")).replaceAll("$1…").replace('\n', ' ');
    String entry = Instant.now() + " " + command + " pid " + ProcessHandle.current().pid() + ": " + clean + "\n";
    try {
      boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
      // The core makes logs/ owner-only; when a bridge runs first, it does the same.
      if (!Files.isDirectory(file.getParent()) && posix) {
        Files.createDirectories(file.getParent(), PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
      } else {
        Files.createDirectories(file.getParent());
      }
      if (Files.exists(file) && Files.size(file) > ROTATE_AT) {
        Files.move(file, file.resolveSibling("bridge.log.1"), StandardCopyOption.REPLACE_EXISTING);
      }
      if (!Files.exists(file) && posix) {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      }
      Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException | RuntimeException ignored) {
      // A full disk or a read-only home must not turn a working helper into a failing one.
    }
  }
}
