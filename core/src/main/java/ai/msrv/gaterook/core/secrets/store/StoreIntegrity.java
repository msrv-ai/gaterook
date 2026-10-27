package ai.msrv.gaterook.core.secrets.store;

import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.secrets.internal.persistence.ExternalStoreEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretEntity;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * MACs over what decides where a secret is fetched and sent (ARCHITECTURE.md §4 "Integrity"): a store's address,
 * sign-in and settings, and what a reference points to. Neither {@code external_store} nor {@code secret} has a
 * {@code mac} column, and a step can't add one (migrations V1–V9 are M1's), so the MACs live in two settings:
 * {@code secrets.store-macs} and {@code secrets.reference-macs}, maps of row id → {@link RowMac} (version 0: the
 * rows' optimistic versions also move with their health and remote version, which decide nothing).
 *
 * <p>Why it matters: a process of the same account that edits {@code gateway.db} could otherwise point the Vault store
 * at its own host and receive the AppRole secret ID at the next sign-in, or turn a stage reference into a prod one. A
 * row that doesn't verify is not used ("Changed outside GateRook") until the person reviews it; a new master key
 * leaves every MAC unverified, like every other signed row (§7.1).
 */
public final class StoreIntegrity {

  static final String STORES = "secrets.store-macs";
  static final String REFERENCES = "secrets.reference-macs";

  private final RowMac rowMac;
  private final SettingStore settings;

  public StoreIntegrity(RowMac rowMac, SettingStore settings) {
    this.rowMac = rowMac;
    this.settings = settings;
  }

  /** Signs a store row; in the transaction that wrote it. */
  public void signStore(ExternalStoreEntity row) {
    put(STORES, row.getId(), rowMac.sign("external_store", row.getId(), 0, fields(row)));
  }

  public boolean verifyStore(ExternalStoreEntity row) {
    return rowMac.verify("external_store", row.getId(), 0, fields(row), get(STORES, row.getId()));
  }

  public void forgetStore(String id) {
    remove(STORES, id);
  }

  /** Signs where a reference (or dynamic secret) points; in the transaction that wrote it. */
  public void signReference(SecretEntity secret) {
    put(REFERENCES, secret.getId(), rowMac.sign("secret-reference", secret.getId(), 0, fields(secret)));
  }

  public boolean verifyReference(SecretEntity secret) {
    return rowMac.verify("secret-reference", secret.getId(), 0, fields(secret), get(REFERENCES, secret.getId()));
  }

  public void forgetReference(String secretId) {
    remove(REFERENCES, secretId);
  }

  private JsonNode fields(ExternalStoreEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("kind", row.getKind().name());
    f.put("name", row.getName());
    f.set("config", rowMac.tree(row.getConfig()));
    f.put("authSecretId", row.getAuthSecretId());
    return f;
  }

  private JsonNode fields(SecretEntity s) {
    ObjectNode f = rowMac.fields();
    f.put("kind", s.getKind().name());
    f.put("ownerKind", s.getOwnerKind().name());
    f.put("ownerId", s.getOwnerId());
    f.put("field", s.getField());
    f.put("ref", s.getRefUri());
    f.put("store", s.getStore() == null ? null : s.getStore().getId());
    return f;
  }

  private byte[] get(String key, String id) {
    JsonNode macs = settings.find(key).map(SettingStore.Setting::value).orElse(null);
    if (macs == null || !macs.path(id).isString()) {
      return null;
    }
    try {
      return Base64.getUrlDecoder().decode(macs.path(id).asString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private void put(String key, String id, byte[] mac) {
    ObjectNode macs = current(key);
    macs.put(id, Base64.getUrlEncoder().withoutPadding().encodeToString(mac));
    settings.put(key, macs, null);
  }

  private void remove(String key, String id) {
    ObjectNode macs = current(key);
    if (macs.remove(id) != null) {
      settings.put(key, macs, null);
    }
  }

  private ObjectNode current(String key) {
    JsonNode stored = settings.find(key).map(SettingStore.Setting::value).orElse(null);
    return stored instanceof ObjectNode o ? o.deepCopy() : JsonNodeFactory.instance.objectNode();
  }
}
