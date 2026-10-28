package ai.msrv.gaterook.core.clients;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.endpoints.ClientGrants;
import ai.msrv.gaterook.core.endpoints.ClientGrants.Grant;
import ai.msrv.gaterook.core.endpoints.ClientGrants.TokenAnswer;
import ai.msrv.gaterook.core.endpoints.ClientGrants.TokenRequest;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.platform.Authenticator;
import ai.msrv.gaterook.core.platform.CallerAuthenticator;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.node.ObjectNode;

/** Durable, scoped production access on the same installation across core restarts. */
class ClientGrantConfirmationIT {

  private TestCore core;
  private ClientGrants grants;
  private AdminMvc api;

  @BeforeEach
  void start() {
    core = TestCore.start("grant-confirmation");
    useCore();
    core.connection("prod-db", "pgfake", Env.PROD, "db.test");
    EndpointService endpoints = core.bean(EndpointService.class);
    EndpointConnections wiring = core.bean(EndpointConnections.class);
    for (String slug : new String[] {"incident-prod", "other-prod"}) {
      endpoints.create(slug, null, EndpointService.StartFrom.EMPTY, null);
      wiring.add(slug, "prod-db", null);
    }
  }

  @AfterEach
  void stop() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void aSavedAllowSurvivesRestartAndTokenReplacementWithoutApprovingOtherAccess() {
    Grant allowed = create("cursor", "incident-prod", "cursor-allowed");
    Grant unconfirmed = create("cursor", "incident-prod", "cursor-unconfirmed");
    Grant otherClient = create("codex", "incident-prod", "codex-unconfirmed");
    Grant otherEndpoint = create("cursor", "other-prod", "cursor-other");
    Grant denied = create("cursor", "incident-prod", "cursor-denied");
    Grant revoked = create("cursor", "incident-prod", "cursor-revoked");

    assertThat(issue(allowed, 101).error()).isEqualTo(ClientGrants.CONFIRMATION_REQUIRED);
    confirm(allowed, true);
    TokenAnswer first = issue(allowed, 101);
    assertThat(first.ok()).isTrue();
    assertThat(issue(allowed, 102).ok()).as("a restarted client can replace its token").isTrue();

    confirm(denied, true);
    confirm(denied, false);
    assertThat(issue(denied, 103).error()).isEqualTo(ClientGrants.CONFIRMATION_DENIED);
    confirm(revoked, true);
    TokenAnswer revokedToken = issue(revoked, 104);
    assertThat(revokedToken.ok()).isTrue();
    grants.revoke(revoked.id(), "Disconnected");
    String previousInstance = grants.instance();

    restart();

    assertThat(grants.instance()).isNotEqualTo(previousInstance);
    Grant saved = grants.find(allowed.id()).orElseThrow();
    assertThat(saved.active()).isTrue();
    assertThat(grants.awaitsConfirmation(saved)).isFalse();
    TokenAnswer replacement = issue(saved, 201);
    assertThat(replacement.ok()).as("the new core remembers the person's Allow").isTrue();
    assertThat(replacement.token()).isNotEqualTo(first.token());
    assertThat(replacement.instance()).isEqualTo(grants.instance());
    assertAuthenticates("incident-prod", replacement.token(), true);

    for (Grant untouched : new Grant[] {unconfirmed, otherClient, otherEndpoint, denied}) {
      Grant current = grants.find(untouched.id()).orElseThrow();
      assertThat(current.active()).isTrue();
      assertThat(grants.awaitsConfirmation(current)).as(current.label()).isTrue();
      assertThat(issue(current, 202).error()).as(current.label()).isEqualTo(ClientGrants.CONFIRMATION_REQUIRED);
    }
    assertThat(grants.issue(new TokenRequest("codex", "incident-prod", saved.id(), 203L, "0.4.0")).error())
        .isEqualTo(ClientGrants.GRANT_MISMATCH);
    assertThat(grants.issue(new TokenRequest("cursor", "other-prod", saved.id(), 204L, "0.4.0")).error())
        .isEqualTo(ClientGrants.GRANT_MISMATCH);
    assertThat(issue(revoked, 205).error()).isEqualTo(ClientGrants.GRANT_REVOKED);
    assertAuthenticates("incident-prod", revokedToken.token(), false);

    grants.revoke(saved.id(), "Disconnected after restart");
    assertThat(issue(saved, 206).error()).isEqualTo(ClientGrants.GRANT_REVOKED);
    assertAuthenticates("incident-prod", replacement.token(), false);
    Grant reconnected = create("cursor", "incident-prod", "cursor-reconnected");
    assertThat(issue(reconnected, 207).error()).isEqualTo(ClientGrants.CONFIRMATION_REQUIRED);
  }

  @Test
  void changingStoredConfirmationOutsideTheCoreNeverGrantsAccessOrKeepsLiveTokens() {
    Grant forged = create("cursor", "incident-prod", "cursor-forged");
    core.jdbc().update("update client_grant set confirmed_at = ? where id = ?",
        Instant.now().toEpochMilli(), forged.id());
    assertThat(issue(forged, 301).error()).isEqualTo(ClientGrants.GRANT_CHANGED);
    api.expect(409, HttpMethod.POST, "/internal/bridge/confirm", Map.of("grant", forged.id(), "decision", "allow"));

    for (boolean clear : new boolean[] {false, true}) {
      Grant allowed = create("cursor", "incident-prod", clear ? "cursor-cleared" : "cursor-changed");
      confirm(allowed, true);
      TokenAnswer token = issue(allowed, 302);
      assertThat(token.ok()).isTrue();
      assertAuthenticates("incident-prod", token.token(), true);
      if (clear) {
        core.jdbc().update("update client_grant set confirmed_at = null where id = ?", allowed.id());
      } else {
        core.jdbc().update("update client_grant set confirmed_at = confirmed_at + 1000 where id = ?", allowed.id());
      }
      assertThat(grants.find(allowed.id()).orElseThrow().verified()).isFalse();
      assertThat(issue(allowed, 303).error()).isEqualTo(ClientGrants.GRANT_CHANGED);
      assertAuthenticates("incident-prod", token.token(), false);
      api.expect(409, HttpMethod.POST, "/internal/bridge/confirm",
          Map.of("grant", allowed.id(), "decision", "allow"));
    }
  }

  @Test
  void anExistingGrantWithThePreviousMacFormatStillNeedsAnExplicitAllow() {
    Grant legacy = create("cursor", "incident-prod", "cursor-legacy");
    RowMac rowMac = core.bean(RowMac.class);
    // Reproduce the pre-confirmation MAC exactly, independently of GrantRows' current field selection.
    ObjectNode fields = rowMac.fields();
    fields.put("client", legacy.client());
    fields.put("endpointId", legacy.endpointId());
    fields.put("label", legacy.label());
    fields.put("createdAt", legacy.createdAt().toEpochMilli());
    fields.putNull("revokedAt");
    long version = core.jdbc().queryForObject("select version from client_grant where id = ?", Long.class,
        legacy.id());
    byte[] oldMac = rowMac.sign("client_grant", legacy.id(), version, fields);
    core.jdbc().update("update client_grant set mac = ?, last_token_at = ?, last_pid = ? where id = ?", oldMac,
        Instant.now().minusSeconds(30).toEpochMilli(), 401, legacy.id());

    restart();

    Grant existing = grants.find(legacy.id()).orElseThrow();
    assertThat(existing.active()).as("an existing MAC is not invalidated by the new optional field").isTrue();
    assertThat(existing.lastTokenAt()).isNotNull();
    assertThat(grants.awaitsConfirmation(existing)).isTrue();
    assertThat(issue(existing, 402).error()).as("previous token use is not a saved Allow")
        .isEqualTo(ClientGrants.CONFIRMATION_REQUIRED);
    confirm(existing, true);
    assertThat(issue(existing, 403).ok()).isTrue();

    restart();

    assertThat(issue(existing, 404).ok()).as("the first new Allow is durable for an existing grant").isTrue();
  }

  private Grant create(String client, String endpoint, String label) {
    return grants.create(null, client, endpoint, label, null);
  }

  private TokenAnswer issue(Grant grant, long pid) {
    return grants.issue(new TokenRequest(grant.client(), grant.endpointSlug(), grant.id(), pid, "0.4.0"));
  }

  private void confirm(Grant grant, boolean allow) {
    api.expect(204, HttpMethod.POST, "/internal/bridge/confirm",
        Map.of("grant", grant.id(), "decision", allow ? "allow" : "deny"));
  }

  private void assertAuthenticates(String endpoint, String token, boolean expected) {
    boolean present = core.bean(CallerAuthenticator.class)
        .authenticate(new Authenticator.Request(Ingress.LOCAL, endpoint, token, "127.0.0.1")).isPresent();
    assertThat(present).as("bridge token authentication").isEqualTo(expected);
  }

  private void restart() {
    Path home = core.home();
    core.close();
    core = null;
    core = TestCore.reopen(home);
    useCore();
  }

  private void useCore() {
    grants = core.bean(ClientGrants.class);
    api = new AdminMvc(core.context(), core.bean(LocalListener.class).port());
  }
}
