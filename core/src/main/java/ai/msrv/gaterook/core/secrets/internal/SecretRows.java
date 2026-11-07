package ai.msrv.gaterook.core.secrets.internal;

import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.LegacyRows;
import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretEntity;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretKind;
import ai.msrv.gaterook.core.secrets.internal.persistence.SecretRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MACs of local {@code secret} rows (ARCHITECTURE.md §4): a value is found by its owner and field, and its encryption
 * binds it only to the secret's id and version. A row moved outside the core to another connection's field would hand
 * that connection another one's credentials, and a current version set back would bring an old value into use. The
 * MAC covers kind, owner, field and current version, signed as version 0 (remote checks move the row's version and
 * decide nothing). References and dynamic credentials have theirs in {@code StoreIntegrity}.
 *
 * <p>Local secrets an older version stored are signed once by the upgrade (V86) as {@link LegacyRows}.
 */
@Component
public class SecretRows implements LegacyRows {

  public static final String TABLE = "secret";

  private final RowMac rowMac;
  private final IntegrityAlerts alerts;
  private final SecretRepository secrets;

  SecretRows(RowMac rowMac, IntegrityAlerts alerts, SecretRepository secrets) {
    this.rowMac = rowMac;
    this.alerts = alerts;
    this.secrets = secrets;
  }

  /** Signs a local secret as it is now; in the transaction that wrote it. */
  public void sign(SecretEntity row) {
    if (row.getKind() == SecretKind.LOCAL) {
      row.setMac(rowMac.sign(TABLE, row.getId(), 0, fields(row)));
    }
  }

  /** Whether a local secret is as the core wrote it; one that isn't is reported once. */
  public boolean verify(SecretEntity row) {
    boolean ok = rowMac.verify(TABLE, row.getId(), 0, fields(row), row.getMac());
    if (!ok) {
      alerts.report("secret", row.getId(), row.getName(), "secret row");
    }
    return ok;
  }

  @Override
  public String migration() {
    return "86";
  }

  @Override
  public String signLegacyRows() {
    int signed = 0;
    for (SecretEntity s : secrets.findByKindAndMacIsNull(SecretKind.LOCAL)) {
      sign(s);
      secrets.save(s);
      signed++;
    }
    return signed == 0 ? null : signed + (signed == 1 ? " secret" : " secrets");
  }

  private JsonNode fields(SecretEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("kind", row.getKind().name());
    f.put("ownerKind", row.getOwnerKind().name());
    f.put("ownerId", row.getOwnerId());
    f.put("field", row.getField());
    f.put("currentVersion", row.getCurrentVersion());
    return f;
  }
}
