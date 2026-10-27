package ai.msrv.gaterook.core.endpoints.internal;

import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantRepository;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.RowMac;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MACs of {@code client_grant} rows (ARCHITECTURE.md §4): a grant decides which endpoint a client's bridge reaches and
 * under which trusted label its calls are audited and its approval requests shown. A row edited outside the core
 * (another endpoint, another client, a revocation undone) no longer verifies: no token is issued for it and its live
 * tokens stop on the next call, until the person connects the client again.
 *
 * <p>The MAC covers the security fields and the row's version, so every write signs again after the flush that settled
 * the version. A row that didn't verify is never signed again: a revocation of it is still written (fail closed),
 * unsigned.
 */
@Component
public class GrantRows {

  public static final String TABLE = "client_grant";

  private final RowMac rowMac;
  private final ClientGrantRepository grants;
  private final IntegrityAlerts alerts;

  public GrantRows(RowMac rowMac, ClientGrantRepository grants, IntegrityAlerts alerts) {
    this.rowMac = rowMac;
    this.grants = grants;
    this.alerts = alerts;
  }

  /** Saves and signs a row this transaction created or verified. */
  public ClientGrantEntity saveSigned(ClientGrantEntity row) {
    ClientGrantEntity saved = grants.saveAndFlush(row);
    saved.setMac(rowMac.sign(TABLE, saved.getId(), saved.getVersion(), fields(saved)));
    return grants.saveAndFlush(saved);
  }

  /** Whether the row is as the core wrote it; a row that isn't is reported once ("changed outside GateRook"). */
  public boolean verify(ClientGrantEntity row) {
    boolean ok = rowMac.verify(TABLE, row.getId(), row.getVersion(), fields(row), row.getMac());
    if (!ok) {
      alerts.report("client-grant", row.getId(), row.getLabel() + " (" + row.getClient() + ")", "client grant row");
    }
    return ok;
  }

  private JsonNode fields(ClientGrantEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("client", row.getClient());
    f.put("endpointId", row.getEndpoint().getId());
    f.put("label", row.getLabel());
    f.put("createdAt", row.getCreatedAt().toEpochMilli());
    f.put("revokedAt", row.getRevokedAt() == null ? null : row.getRevokedAt().toEpochMilli());
    return f;
  }
}
