package ai.msrv.gaterook.core.admin.settings;

import ai.msrv.gaterook.core.admin.ApiRequestException;
import ai.msrv.gaterook.core.secrets.FullBackupService;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The renderer cannot proxy internal routes or obtain portable master-key material. */
@RestController
@RegisterReflectionForBinding({FullBackupController.Snapshot.class, FullBackupController.Validate.class,
    FullBackupController.Prepared.class, FullBackupController.SnapshotPrepared.class, FullBackupService.PluginFile.class})
class FullBackupController {
  record Snapshot(String directory) {}
  record Validate(String directory, byte[] keyset) {
    @Override public String toString() { return "Validate[keyset=<redacted>]"; }
  }
  record Prepared(byte[] keyset) {
    @Override public String toString() { return "Prepared[keyset=<redacted>]"; }
  }
  record SnapshotPrepared(byte[] keyset, List<FullBackupService.PluginFile> pluginFiles) {
    @Override public String toString() { return "SnapshotPrepared[keyset=<redacted>]"; }
  }

  private final FullBackupService backups;
  FullBackupController(FullBackupService backups) { this.backups = backups; }

  @PostMapping("/internal/full-backup/snapshot")
  SnapshotPrepared snapshot(@RequestBody Snapshot request) {
    byte[] keyset = null;
    try {
      keyset = backups.snapshot(request.directory());
      return new SnapshotPrepared(keyset, backups.pluginFiles(request.directory()));
    } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
      if (keyset != null) Arrays.fill(keyset, (byte) 0);
      throw ApiRequestException.unprocessable("Full backup failed", e.getMessage(), null);
    }
  }

  @PostMapping("/internal/full-backup/validate")
  Prepared validate(@RequestBody Validate request) {
    try {
      return new Prepared(backups.validate(request.directory(), request.keyset()));
    } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
      throw ApiRequestException.unprocessable("Backup validation failed", e.getMessage(), null);
    } finally {
      if (request.keyset() != null) Arrays.fill(request.keyset(), (byte) 0);
    }
  }
}
