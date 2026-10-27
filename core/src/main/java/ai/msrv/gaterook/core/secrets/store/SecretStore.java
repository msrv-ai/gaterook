package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.core.secrets.SecretStores;
import ai.msrv.gaterook.core.secrets.internal.persistence.StoreKind;
import java.util.List;

/**
 * One connected external store as the core uses it (report 12 §1 item 3): Vault/OpenBao on {@code java.net.http},
 * GCP Secret Manager over REST, AWS Secrets Manager through the SDK. Built from the store's row and sign-in secrets,
 * replaced whenever either changes; blocking and virtual-thread friendly; holds its credentials in memory only.
 */
public interface SecretStore extends AutoCloseable {

  StoreKind kind();

  /** How long a value read from this store may be reused ({@code cacheTtlSeconds}; prod caps it at 15 s). */
  java.time.Duration cacheTtl();

  /**
   * Reads {@code ref} now (the cache is the caller's), handing every token it used to {@code call.taint()}.
   *
   * @throws StoreException with what happened and what to do
   */
  RemoteValue read(SecretRef ref, StoreCall call);

  /**
   * Test connection of Secrets › External stores: reachability, sign-in, identity, and what the identity may do with
   * {@code refs} (the references of this store), with hints when it may do more than read.
   */
  SecretStores.TestResult test(List<SecretRef> refs, StoreCall call);

  /** Forgets the credentials (tokens zeroed, clients closed). */
  @Override
  void close();

  /** The sign-in secrets of the store, resolved for one use; the caller zeroes what it gets. */
  @FunctionalInterface
  interface SignIn {
    /**
     * @throws StoreException {@code UNAVAILABLE} when the secret isn't stored (or can't be decrypted)
     */
    char[] secret(String field);
  }
}
