package com.gaterook.core.secrets.store;

import com.gaterook.core.platform.ProblemException;
import com.gaterook.core.secrets.SecretOwner;
import com.gaterook.core.secrets.internal.persistence.ExternalStoreEntity;
import com.gaterook.core.secrets.internal.persistence.ExternalStoreRepository;
import com.gaterook.core.secrets.internal.persistence.SecretEntity;
import com.gaterook.core.secrets.internal.persistence.SecretKind;
import java.util.List;
import java.util.Objects;

/**
 * Which connected store resolves a reference (report 12 §4.2: "every reference is resolved by the connected store of
 * its kind — the Vault address, the GCP project, the AWS region or account — and its {@code store_id} is kept next to
 * it"). A reference names no store, so: the store the person chose; else the one store of its kind whose project
 * (GCP) or region (AWS) matches; else the only store of its kind; none — "not connected" until one is; several
 * without a match — the first, by name, that has it ({@link #candidates}: asked in turn before the reference is
 * stored, so it is bound to the store that answered). A Key Vault reference names its vault, and a Key Vault store
 * reads one vault: only the stores of that vault are of its kind here, so the reference waits, "not connected", until
 * its vault is. Everything here runs in the caller's transaction.
 */
public final class StoreBinding {

  private final ExternalStoreRepository stores;
  private final StoreIntegrity integrity;

  public StoreBinding(ExternalStoreRepository stores, StoreIntegrity integrity) {
    this.stores = stores;
    this.integrity = integrity;
  }

  /**
   * Parses a reference the person typed.
   *
   * @param pointer JSON pointer of the request field, for the 422
   * @throws ProblemException 422 with what is wrong
   */
  public static SecretRef parse(String uri, String pointer) {
    try {
      return SecretRef.parse(uri);
    } catch (IllegalArgumentException e) {
      throw ProblemException.unprocessable("Check the reference", e.getMessage(), pointer);
    }
  }

  /**
   * The store for {@code ref}, or {@code null} when none of its kind is connected.
   *
   * @param storeName the store the person chose, or {@code null}
   * @throws ProblemException 422 when the chosen store doesn't fit, or several fit and none was chosen
   */
  public ExternalStoreEntity storeFor(SecretRef ref, String storeName, String pointer) {
    if (storeName != null && !storeName.isBlank()) {
      ExternalStoreEntity chosen = stores.findByName(storeName.strip()).or(() -> stores.findById(storeName.strip()))
          .orElseThrow(() -> ProblemException.unprocessable("No such store", "There is no external store "
              + storeName + ".", pointer));
      if (chosen.getKind() != ref.kind()) {
        throw ProblemException.unprocessable("Another kind of store", storeName + " is "
            + StoreResolution.title(chosen.getKind()) + "; " + ref.uri() + " needs " + StoreResolution.title(
            ref.kind()) + ".", pointer);
      }
      if (ref instanceof SecretRef.AzureKv kv && !matches(chosen, ref)) {
        throw ProblemException.unprocessable("Another key vault", storeName + " reads key vault "
            + chosen.getConfig().get("vault") + "; " + ref.uri() + " is in " + kv.vault() + ". Choose the store of "
            + kv.vault() + ", or connect it first.", pointer);
      }
      return chosen;
    }
    List<ExternalStoreEntity> candidates = ofKind(ref);
    if (candidates.isEmpty()) {
      return null;
    }
    List<ExternalStoreEntity> matching = candidates.stream().filter(s -> matches(s, ref)).toList();
    if (matching.size() == 1) {
      return matching.getFirst();
    }
    if (candidates.size() == 1) {
      return candidates.getFirst();
    }
    throw ProblemException.unprocessable("Choose the store", "Several " + StoreResolution.title(ref.kind())
        + " stores could resolve " + ref.uri() + ": choose one of " + String.join(", ", candidates.stream()
        .map(ExternalStoreEntity::getName).sorted().toList()) + ".", pointer);
  }

  /**
   * The stores that may hold {@code ref}, in the order they are asked: the one the person chose; else the natural
   * one (GCP project, AWS region, key vault) or the only one of its kind; else every connected store of its kind, by
   * name, each asked in turn until one has it. Empty when none of its kind (for Key Vault: of its vault) is connected.
   *
   * @throws ProblemException 422 when the chosen store doesn't exist, is of another kind or reads another key vault
   */
  public List<ExternalStoreEntity> candidates(SecretRef ref, String storeName, String pointer) {
    if (storeName != null && !storeName.isBlank()) {
      return List.of(storeFor(ref, storeName, pointer));
    }
    List<ExternalStoreEntity> all = ofKind(ref).stream()
        .sorted(java.util.Comparator.comparing(ExternalStoreEntity::getName)).toList();
    List<ExternalStoreEntity> matching = all.stream().filter(s -> matches(s, ref)).toList();
    return matching.size() == 1 ? matching : all;
  }

  /** The connected stores of {@code ref}'s kind; for Key Vault, those that read its vault. */
  private List<ExternalStoreEntity> ofKind(SecretRef ref) {
    return stores.findAll().stream().filter(s -> s.getKind() == ref.kind())
        .filter(s -> !(ref instanceof SecretRef.AzureKv) || matches(s, ref)).toList();
  }

  /** Whether {@code store} is the natural one for {@code ref}: same GCP project, AWS region or key vault. */
  static boolean matches(ExternalStoreEntity store, SecretRef ref) {
    Object project = store.getConfig().get("project");
    Object region = store.getConfig().get("region");
    Object vault = store.getConfig().get("vault");
    return switch (ref) {
      case SecretRef.GcpSm g -> Objects.equals(project, g.project());
      case SecretRef.AwsSm a -> Objects.equals(region, a.region());
      case SecretRef.AzureKv kv -> Objects.equals(vault, kv.vault());
      case SecretRef.Vault _ -> false;
    };
  }

  /** Points {@code secret} to {@code ref} in {@code store} (or to no store yet) and signs where it points. */
  public void point(SecretEntity secret, boolean dynamic, SecretRef ref, ExternalStoreEntity store) {
    secret.pointTo(dynamic ? SecretKind.DYNAMIC : SecretKind.REFERENCE, store, ref.uri());
    integrity.signReference(secret);
  }

  /** The secret's pointer as resolution needs it; checked against its MAC. */
  public StoreResolution.Pointer pointer(SecretEntity s) {
    return new StoreResolution.Pointer(s.getId(), s.getName(), new SecretOwner(SecretOwner.Kind.valueOf(
        s.getOwnerKind().name()), s.getOwnerId()), s.getField(), s.getKind() == SecretKind.DYNAMIC, s.getRefUri(),
        s.getStore() == null ? null : s.getStore().getId(), s.getRemoteVersion(), integrity.verifyReference(s));
  }

  public StoreIntegrity integrity() {
    return integrity;
  }
}
