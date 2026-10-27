package ai.msrv.gaterook.core.secrets;

import java.time.Instant;
import java.util.List;

/**
 * The master key card of Secrets (ARCHITECTURE.md §6 {@code GET /api/master-key}): where the key is, how it is
 * protected, what it protects and what is wrong with it. No key material.
 *
 * @param mode         {@code 0400}, or {@code null} without POSIX permissions
 * @param osProtection {@code keychain}, {@code dpapi}, {@code libsecret}, {@code kwallet}, {@code off} or
 *                     {@code unavailable} (Linux {@code basic_text})
 * @param protects     local secrets with a value encrypted under this key
 * @param problems     {@code perms}, {@code synced-folder}, {@code no-os-protection}, {@code key-missing},
 *                     {@code key-invalid}, {@code backup-needed}
 * @param warnings     facts worth a banner, e.g. that a new key was created because none was found
 * @param locked       {@code MASTER_KEY_MISSING} or {@code MASTER_KEY_INVALID} in locked mode
 * @param createdAt    when the gateway made the key in use ("Created Jul 14"), {@code null} for one made before it
 *                     kept the date
 * @param rotatedAt    when it last rotated it, {@code null}: never rotated
 */
public record MasterKeyStatus(String path, String mode, String owner, List<Key> keys, String osProtection,
    int protects, Instant lastBackupAt, List<String> problems, List<String> warnings, String locked,
    Rotation rotation, Instant createdAt, Instant rotatedAt) {

  /** One key of the keyset. */
  public record Key(long id, String status, boolean primary) {}

  /**
   * Progress of a rotation: "Re-wrapping data keys · 5 of 8".
   *
   * @param state {@code idle}, {@code running}, {@code done} or {@code failed}
   */
  public record Rotation(String state, int done, int total, Long targetKeyId, String error) {
    public static final Rotation IDLE = new Rotation("idle", 0, 0, null, null);
  }
}
