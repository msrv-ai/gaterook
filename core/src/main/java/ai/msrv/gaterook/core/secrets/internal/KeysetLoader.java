package ai.msrv.gaterook.core.secrets.internal;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.subtle.AesGcmJce;
import ai.msrv.gaterook.core.platform.BeforeMigrations;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.secrets.internal.crypto.KeysetFile;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code master-key} start-up step (ARCHITECTURE.md §7.1): before the database migrates, take the file key from
 * the bootstrap (once; the bootstrap forgets it) and read {@code master.keyset}. Whether the result is usable depends on
 * the database — does it hold secrets this keyset can't open? — so {@code MasterKeyService} decides after the
 * migrations: use it, create a first key, or run locked.
 */
public final class KeysetLoader implements BeforeMigrations {

  private static final Logger log = LoggerFactory.getLogger(KeysetLoader.class);

  private final KeysetFile file;
  private final Bootstrap bootstrap;
  private volatile Aead fileKey;
  private volatile KeysetFile.Loaded loaded;

  public KeysetLoader(GatewayPaths paths, Bootstrap bootstrap) {
    this.file = new KeysetFile(paths.home());
    this.bootstrap = bootstrap;
  }

  @Override
  public String step() {
    return "master-key";
  }

  @Override
  public void run() {
    if (loaded != null) {
      return;
    }
    bootstrap.takeFileKey().ifPresent(key -> {
      try {
        if (key.length != 32) {
          log.warn("The OS-protected file key has {} bytes, not 32: ignored", key.length);
        } else {
          fileKey = new AesGcmJce(key);
        }
      } catch (GeneralSecurityException e) {
        log.warn("The OS-protected file key can't be used: {}", e.getMessage());
      } finally {
        Arrays.fill(key, (byte) 0);
      }
    });
    loaded = file.load(fileKey);
    switch (loaded.state()) {
      case LOADED -> log.info("Master key loaded from {} ({})", file.path(), loaded.encrypted() ? "OS-protected"
          : "file 0400");
      case MISSING -> log.info("No master key at {}", file.path());
      case INVALID -> log.warn("Master key unusable: {}", loaded.problem());
    }
  }

  /** What {@link #run} found; runs it now if the migration strategy didn't (tests without Flyway hooks). */
  public KeysetFile.Loaded loaded() {
    if (loaded == null) {
      run();
    }
    return loaded;
  }

  public KeysetFile file() {
    return file;
  }

  /** The file key of OS protection, or {@code null}: the keyset is then a 0400 cleartext file. */
  public Aead fileKey() {
    return fileKey;
  }

  /** Switches OS protection on ({@code key} 32 bytes) or off ({@code null}); the caller rewrites the file. */
  public void fileKey(byte[] key) throws GeneralSecurityException {
    this.fileKey = key == null ? null : new AesGcmJce(key);
  }

  /**
   * {@code keychain}, {@code dpapi}, {@code libsecret}, {@code kwallet}, {@code off} or {@code unavailable}: what
   * protects the key file on this system (report 12 §6, Electron {@code getSelectedStorageBackend}).
   */
  public String osProtection() {
    String protection = bootstrap.protection().toLowerCase(Locale.ROOT);
    if (fileKey == null) {
      return protection.equals("basic_text") || protection.equals("unknown") || protection.equals("unavailable")
          ? "unavailable" : "off";
    }
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) {
      return "keychain";
    }
    if (os.contains("windows")) {
      return "dpapi";
    }
    if (protection.startsWith("kwallet")) {
      return "kwallet";
    }
    return "libsecret";
  }
}
