package ai.msrv.gaterook.core.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time tokens for files a person picked in a native dialog (ARCHITECTURE.md §6): the core never accepts raw paths
 * from the UI. Electron's main process shows {@code showSaveDialog}/{@code showOpenDialog}, registers the chosen path
 * with {@code POST /internal/files/grant} and hands the renderer only the token; the API that writes or reads the file
 * ({@code /api/master-key/backup}, {@code /restore}, {@code /api/backup/db}) redeems it once, for its purpose.
 */
public final class FileGrants {

  /** How long a token is valid after the dialog closed. */
  public static final Duration TTL = Duration.ofMinutes(5);

  /** What a granted path may be used for. */
  public enum Purpose {
    MASTER_KEY_BACKUP(true), MASTER_KEY_RESTORE(false), DB_BACKUP(true), AUDIT_EXPORT(true), PLUGIN(false),
    OPENAPI(false);

    private final boolean save;

    Purpose(boolean save) {
      this.save = save;
    }

    /** A file to write ({@code saveToken}) rather than one to read ({@code openToken}). */
    public boolean save() {
      return save;
    }

    /** {@code master-key-backup} → {@link #MASTER_KEY_BACKUP}. */
    public static Purpose of(String id) {
      return valueOf(id.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
  }

  private record Grant(Path path, Purpose purpose, Instant expires) {}

  private final Map<String, Grant> grants = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final Clock clock;

  public FileGrants(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Registers a path chosen by the person.
   *
   * @throws IllegalArgumentException when the path isn't absolute, a file to open doesn't exist or isn't a regular
   *                                  file, or a file to save has no existing parent folder
   */
  public String grant(Path path, Purpose purpose) {
    Objects.requireNonNull(purpose, "purpose");
    if (path == null || !path.isAbsolute()) {
      throw new IllegalArgumentException("The path must be absolute");
    }
    Path normalized = path.normalize();
    if (purpose.save()) {
      Path parent = normalized.getParent();
      if (parent == null || !Files.isDirectory(parent)) {
        throw new IllegalArgumentException("The folder " + parent + " doesn't exist");
      }
      if (Files.isDirectory(normalized)) {
        throw new IllegalArgumentException(normalized + " is a folder");
      }
    } else if (!Files.isRegularFile(normalized)) {
      throw new IllegalArgumentException(normalized + " is not a file");
    }
    sweep();
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    String token = "ft_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    grants.put(token, new Grant(normalized, purpose, clock.instant().plus(TTL)));
    return token;
  }

  /**
   * Redeems a token once.
   *
   * @throws IllegalArgumentException when the token is unknown, used, expired or granted for another purpose
   */
  public Path take(String token, Purpose purpose) {
    Grant grant = token == null ? null : grants.remove(token);
    if (grant == null || grant.expires().isBefore(clock.instant())) {
      throw new IllegalArgumentException("The file choice expired. Choose the file again.");
    }
    if (grant.purpose() != purpose) {
      throw new IllegalArgumentException("This file was chosen for something else. Choose the file again.");
    }
    return grant.path();
  }

  private void sweep() {
    Instant now = clock.instant();
    grants.values().removeIf(g -> g.expires().isBefore(now));
  }
}
