package ai.msrv.gaterook.core.secrets.internal.crypto;

import com.google.crypto.tink.KeyStatus;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Operations on the master keyset: a new one, a new primary, disabling and deleting old keys (report 12 §7.3). */
public final class Keysets {

  private Keysets() {}

  /** One key of the keyset, for {@code GET /api/master-key}. */
  public record KeyInfo(long id, String status, boolean primary) {}

  public static KeysetHandle generate() throws GeneralSecurityException {
    return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, Envelope.CFG);
  }

  /**
   * Step 1 of a rotation: a new {@code AES256_GCM} key becomes primary; old keys stay enabled, so data keys wrapped by
   * them keep opening until they are re-wrapped. {@code setConfiguration} matters: without it {@code build()} uses the
   * global registry and fails with "no key creator for this class was registered" (report 12 §9.1).
   */
  public static KeysetHandle withNewPrimary(KeysetHandle current) throws GeneralSecurityException {
    return KeysetHandle.newBuilder(current).setConfiguration(Envelope.CFG)
        .addEntry(KeysetHandle.generateEntryFromParameters(PredefinedAeadParameters.AES256_GCM).withRandomId()
            .makePrimary())
        .build();
  }

  /** Step 3: every key but the primary is disabled; data keys still wrapped by them no longer open. */
  public static KeysetHandle disableAllButPrimary(KeysetHandle current) throws GeneralSecurityException {
    KeysetHandle.Builder builder = KeysetHandle.newBuilder(current).setConfiguration(Envelope.CFG);
    for (int i = 0; i < builder.size(); i++) {
      if (!builder.getAt(i).isPrimary()) {
        builder.getAt(i).setStatus(KeyStatus.DISABLED);
      }
    }
    return builder.build();
  }

  /** Step 4, after a backup of the new key: disabled keys go away. */
  public static KeysetHandle deleteDisabled(KeysetHandle current) throws GeneralSecurityException {
    KeysetHandle.Builder builder = KeysetHandle.newBuilder(current).setConfiguration(Envelope.CFG);
    for (int i = builder.size() - 1; i >= 0; i--) {
      if (builder.getAt(i).getStatus() == KeyStatus.DISABLED) {
        builder.deleteAt(i);
      }
    }
    return builder.build();
  }

  /** Ids of the keys that can still decrypt (enabled). */
  public static Set<Long> enabledKeyIds(KeysetHandle handle) {
    Set<Long> ids = new HashSet<>();
    for (int i = 0; i < handle.size(); i++) {
      if (handle.getAt(i).getStatus() == KeyStatus.ENABLED) {
        ids.add(Integer.toUnsignedLong(handle.getAt(i).getId()));
      }
    }
    return ids;
  }

  /** Whether the keyset can open data keys wrapped by every id of {@code required}. */
  public static boolean covers(KeysetHandle handle, Collection<Long> required) {
    return enabledKeyIds(handle).containsAll(required);
  }

  public static List<KeyInfo> describe(KeysetHandle handle) {
    List<KeyInfo> keys = new ArrayList<>();
    for (int i = 0; i < handle.size(); i++) {
      var entry = handle.getAt(i);
      keys.add(new KeyInfo(Integer.toUnsignedLong(entry.getId()), entry.getStatus().toString().toLowerCase(
          java.util.Locale.ROOT), entry.isPrimary()));
    }
    return keys;
  }

  public static boolean hasDisabled(KeysetHandle handle) {
    for (int i = 0; i < handle.size(); i++) {
      if (handle.getAt(i).getStatus() == KeyStatus.DISABLED) {
        return true;
      }
    }
    return false;
  }
}
