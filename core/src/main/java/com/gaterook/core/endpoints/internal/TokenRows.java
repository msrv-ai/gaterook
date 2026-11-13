package com.gaterook.core.endpoints.internal;

import com.gaterook.core.endpoints.internal.persistence.ApiTokenEntity;
import com.gaterook.core.platform.IntegrityAlerts;
import com.gaterook.core.platform.RowMac;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MACs of {@code api_token} rows (ARCHITECTURE.md §4): a token's hash is what a request presents, so a row inserted or
 * edited outside the core (a hash of a value someone knows, another endpoint, a later expiry, a revocation undone)
 * must not authenticate anyone. The MAC covers what decides access: kind, endpoint, grant, name, hash, creation,
 * expiry and revocation. It is signed as version 0, since {@code last_used_at} moves the row's version and decides
 * nothing.
 *
 * <p>Every revocation signs the row again, whether it verified before or not: a revoked row can't authenticate, and the
 * MAC of the revoked row keeps the revocation from being undone by clearing {@code revoked_at}.
 */
@Component
public class TokenRows {

  public static final String TABLE = "api_token";

  private final RowMac rowMac;
  private final IntegrityAlerts alerts;

  public TokenRows(RowMac rowMac, IntegrityAlerts alerts) {
    this.rowMac = rowMac;
    this.alerts = alerts;
  }

  /** Signs the row as it is now; the caller saves it. */
  public void sign(ApiTokenEntity row) {
    row.setMac(rowMac.sign(TABLE, row.getId(), 0, fields(row)));
  }

  /** Whether the row is as the core wrote it; a row that isn't is reported once ("changed outside GateRook"). */
  public boolean verify(ApiTokenEntity row) {
    boolean ok = rowMac.verify(TABLE, row.getId(), 0, fields(row), row.getMac());
    if (!ok) {
      alerts.report("token", row.getId(), row.getName() == null ? "a client's token" : "token " + row.getName(),
          "access token row");
    }
    return ok;
  }

  private JsonNode fields(ApiTokenEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("kind", row.getKind().name());
    f.put("endpointId", row.getEndpoint().getId());
    f.put("grantId", row.getGrant() == null ? null : row.getGrant().getId());
    f.put("name", row.getName());
    f.put("hash", row.getHash());
    f.put("createdAt", row.getCreatedAt().toEpochMilli());
    f.put("expiresAt", row.getExpiresAt().toEpochMilli());
    f.put("revokedAt", row.getRevokedAt() == null ? null : row.getRevokedAt().toEpochMilli());
    return f;
  }
}
