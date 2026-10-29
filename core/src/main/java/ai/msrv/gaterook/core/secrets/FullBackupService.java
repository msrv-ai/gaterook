package ai.msrv.gaterook.core.secrets;

import ai.msrv.gaterook.core.platform.GatewayDatabase;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.secrets.internal.crypto.BackupCodec;
import ai.msrv.gaterook.core.secrets.internal.crypto.FullBackupDatabase;
import com.google.crypto.tink.KeysetHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** Main-process-only preparation; the desktop owns encryption and the stopped-core restore transaction. */
@Service
public class FullBackupService {
  public record PluginFile(String path, String sha256) {}
  private static final int MAX_KEYSET_BYTES = 1 << 20;
  private final GatewayPaths paths;
  private final GatewayDatabase database;
  private final MasterKeyService masterKey;
  private final Clock clock;

  public FullBackupService(GatewayPaths paths, GatewayDatabase database, MasterKeyService masterKey, Clock clock) {
    this.paths = paths;
    this.database = database;
    this.masterKey = masterKey;
    this.clock = clock;
  }

  public synchronized byte[] snapshot(String directory) throws IOException, GeneralSecurityException {
    return masterKey.fullBackupSnapshot(database, directory(directory).resolve("gateway.db"));
  }

  public List<PluginFile> pluginFiles(String directory) throws IOException {
    return FullBackupDatabase.pluginFiles(directory(directory).resolve("gateway.db"), clock.instant()).stream()
        .map(file -> new PluginFile(file.path(), file.sha256())).toList();
  }

  public synchronized byte[] validate(String directory, byte[] keyset) throws IOException, GeneralSecurityException {
    Path source = directory(directory).resolve("gateway.db");
    if (keyset == null || keyset.length == 0 || keyset.length > MAX_KEYSET_BYTES) {
      throw new IOException("The backup contains an invalid master key.");
    }
    KeysetHandle restored;
    try {
      restored = BackupCodec.read(keyset, null);
    } catch (BackupCodec.BackupException e) {
      throw new IOException("The backup contains an invalid portable master key.", e);
    }
    FullBackupDatabase.prepare(source, database.file(), restored, clock.instant());
    return masterKey.protectFullBackupKey(restored);
  }

  private Path directory(String value) throws IOException {
    if (value == null) throw new IOException("The backup staging directory is missing.");
    Path directory = Path.of(value).toAbsolutePath().normalize();
    Path temporary = paths.tmp().toRealPath();
    if (!directory.getFileName().toString().matches("full-backup-[A-Za-z0-9-]{6,100}")
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || !directory.toRealPath().getParent().equals(temporary)) {
      throw new IOException("The backup staging directory is outside GateRook's temporary folder.");
    }
    GatewayPaths.ownerOnlyDirectory(directory);
    return directory;
  }
}
