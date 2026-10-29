package ai.msrv.gaterook.core.secrets;

import ai.msrv.gaterook.core.secrets.store.SecretRef;
import java.time.Instant;
import java.util.List;

/**
 * One row of Secrets (ARCHITECTURE.md §6 {@code GET /api/secrets}). Never a value, its length, hash or prefix.
 *
 * @param source  {@code local} (encrypted here), {@code vault}, {@code gcp-sm}, {@code aws-sm}
 * @param version the version in use, or {@code null} when the secret needs a value
 * @param state   {@code stored}, {@code needs-value}, {@code testing} (a pending version); for a reference
 *                {@code reference}, {@code short-lived}, {@code not-connected} or {@code changed-outside}
 * @param ref     where a reference points ({@code vault://kv/stage/db#password}), {@code null} for a local secret
 * @param store   the connected store a reference reads through, or {@code null}
 */
public record SecretInfo(String id, String name, SecretOwner owner, String field, String ownerLabel,
    List<String> usedBy, String source, Integer version, String remoteVersion, Instant rotatedAt, String state,
    boolean canRotate, String rotateReason, String ref, String store) {

  /** Whether this reference supplies a leased login, independent of the store's current connection state. */
  public boolean dynamicReference() {
    if (ref == null) return false;
    try {
      return SecretRef.parse(ref) instanceof SecretRef.Vault vault && vault.credsPath();
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
