package com.gaterook.core.oauth.internal;

import com.gaterook.core.oauth.internal.persistence.OAuthClientEntity;
import com.gaterook.core.oauth.internal.persistence.OAuthGrantEntity;
import com.gaterook.core.oauth.internal.persistence.OAuthGrantRepository;
import com.gaterook.core.oauth.internal.persistence.OAuthTokenEntity;
import com.gaterook.core.oauth.internal.persistence.OAuthTokenRepository;
import com.gaterook.core.platform.IntegrityAlerts;
import com.gaterook.core.platform.LegacyRows;
import com.gaterook.core.platform.RowMac;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Row MACs of the OAuth tables (ARCHITECTURE.md §4). A client's registration ({@code oauth_client}), a sign-in the
 * person allowed ({@code oauth_grant}) and its tokens ({@code oauth_token}, by hash) decide who reaches an endpoint,
 * locally and through the tunnel: a row inserted or edited outside the core (a token hash someone knows, a revocation
 * or a used refresh token undone, a later expiry, another endpoint or client) lets nobody in. Signed as version 0:
 * last-use times move the rows and decide nothing. A revocation, a used token and a changed address sign their row
 * again, whether it verified before or not, so what they ended can't be revived.
 *
 * <p>Sign-ins and tokens an older version stored are signed once by the upgrade (V86) as {@link LegacyRows}.
 */
class OAuthRows implements LegacyRows {

  private final RowMac rowMac;
  private final IntegrityAlerts alerts;
  private final OAuthGrantRepository grants;
  private final OAuthTokenRepository tokens;

  OAuthRows(RowMac rowMac, IntegrityAlerts alerts, OAuthGrantRepository grants, OAuthTokenRepository tokens) {
    this.rowMac = rowMac;
    this.alerts = alerts;
    this.grants = grants;
    this.tokens = tokens;
  }

  // ── oauth_client ────────────────────────────────────────────────────────────────────────────────────────────

  void sign(OAuthClientEntity row) {
    row.setMac(rowMac.sign("oauth_client", row.getId(), 0, fields(row)));
  }

  boolean verify(OAuthClientEntity row) {
    boolean ok = rowMac.verify("oauth_client", row.getId(), 0, fields(row), row.getMac());
    if (!ok) {
      alerts.report("oauth_client", row.getId(), row.getClientName() == null ? row.getClientHost()
          : row.getClientName(), "OAuth client row");
    }
    return ok;
  }

  private JsonNode fields(OAuthClientEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("kind", row.getKind().name());
    f.put("ingress", row.getIngress().name());
    f.put("clientId", row.getClientId());
    f.put("clientName", row.getClientName());
    f.put("clientHost", row.getClientHost());
    ArrayNode uris = f.putArray("redirectUris");
    row.getRedirectUris().forEach(uris::add);
    return f;
  }

  // ── oauth_grant ─────────────────────────────────────────────────────────────────────────────────────────────

  void sign(OAuthGrantEntity row) {
    row.setMac(rowMac.sign("oauth_grant", row.getId(), 0, fields(row)));
  }

  boolean verify(OAuthGrantEntity row) {
    boolean ok = rowMac.verify("oauth_grant", row.getId(), 0, fields(row), row.getMac());
    if (!ok) {
      alerts.report("oauth_grant", row.getId(), "a sign-in of " + row.getClient().getClientHost(),
          "OAuth sign-in row");
    }
    return ok;
  }

  private JsonNode fields(OAuthGrantEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("client", row.getClient().getId());
    f.put("endpointId", row.getEndpointId());
    f.put("ingress", row.getIngress().name());
    f.put("resource", row.getResource());
    f.put("scope", row.getScope());
    f.put("state", row.getState().name());
    f.put("createdAt", row.getCreatedAt().toEpochMilli());
    f.put("expiresAt", row.getExpiresAt().toEpochMilli());
    f.put("revokedAt", millis(row.getRevokedAt()));
    return f;
  }

  // ── oauth_token ─────────────────────────────────────────────────────────────────────────────────────────────

  void sign(OAuthTokenEntity row) {
    row.setMac(rowMac.sign("oauth_token", row.getHash(), 0, fields(row)));
  }

  boolean verify(OAuthTokenEntity row) {
    boolean ok = rowMac.verify("oauth_token", row.getHash(), 0, fields(row), row.getMac());
    if (!ok) {
      alerts.report("oauth_token", row.getHash(), "a token of a sign-in", "OAuth token row");
    }
    return ok;
  }

  private JsonNode fields(OAuthTokenEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("grant", row.getGrant().getId());
    f.put("kind", row.getKind().name());
    f.put("createdAt", row.getCreatedAt().toEpochMilli());
    f.put("expiresAt", row.getExpiresAt().toEpochMilli());
    f.put("consumedAt", millis(row.getConsumedAt()));
    f.put("replacedBy", row.getReplacedBy());
    return f;
  }

  private static Long millis(Instant at) {
    return at == null ? null : at.toEpochMilli();
  }

  // ── Upgrade ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public String migration() {
    return "86";
  }

  @Override
  public String signLegacyRows() {
    int signed = 0;
    for (OAuthGrantEntity g : grants.findByMacIsNull()) {
      sign(g);
      grants.save(g);
      signed++;
    }
    for (OAuthTokenEntity t : tokens.findByMacIsNull()) {
      sign(t);
      tokens.save(t);
    }
    return signed == 0 ? null : signed + (signed == 1 ? " OAuth sign-in" : " OAuth sign-ins");
  }
}
