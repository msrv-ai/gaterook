package ai.msrv.gaterook.core.admin.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.clients.ClientViews.Card;
import ai.msrv.gaterook.core.clients.ClientViews.Link;
import ai.msrv.gaterook.core.oauth.OAuthService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClientCallsTest {

  private static final Instant CONNECTED_AT = Instant.parse("2026-10-28T10:00:00Z");
  private static final Instant USED_AT = CONNECTED_AT.plusSeconds(5);

  private final AuditQueries audit = mock(AuditQueries.class);
  private final OAuthService oauth = mock(OAuthService.class);
  private final ClientCalls calls = new ClientCalls(audit, oauth);

  @Test
  void tokenExchangeOrAuthenticatedDiscoveryConnectsAManagedLinkWithoutInventingAToolCall() {
    when(oauth.localClientUses()).thenReturn(List.of(use("og_cursor", "cursor", "dev", USED_AT)));
    Card result = calls.withLastCalls(List.of(card("needs-restart", List.of(link("oauth", "needs-restart")))))
        .getFirst();

    assertThat(result.status()).isEqualTo("connected");
    assertThat(result.links()).singleElement().satisfies(l -> {
      assertThat(l.id()).isEqualTo("managed-cursor");
      assertThat(l.status()).isEqualTo("connected");
      assertThat(l.usedAt()).isEqualTo(USED_AT);
      assertThat(l.lastCallAt()).isNull();
      assertThat(l.revision()).isNull();
    });
  }

  @Test
  void oldUseAndAnotherClientOrEndpointDoNotProveTheManagedConnection() {
    when(oauth.localClientUses()).thenReturn(List.of(use("og_old", "cursor", "dev", CONNECTED_AT.minusSeconds(1)),
        use("og_other_client", "antigravity", "dev", USED_AT),
        use("og_other_endpoint", "cursor", "stage", USED_AT)));
    Link result = calls.withLastCalls(List.of(card("needs-restart", List.of(link("oauth", "needs-restart")))))
        .getFirst().links().getFirst();

    assertThat(result.status()).isEqualTo("needs-restart");
    assertThat(result.usedAt()).isNull();
    assertThat(result.lastCallAt()).isNull();
  }

  @Test
  void anOAuthSignInDoesNotRepairAConfigProblemOrProveATokenConnection() {
    when(oauth.localClientUses()).thenReturn(List.of(use("og_cursor", "cursor", "dev", USED_AT)));
    Card broken = calls.withLastCalls(List.of(card("error", List.of(link("oauth", "needs-repair")))))
        .getFirst();
    assertThat(broken.status()).isEqualTo("error");
    assertThat(broken.links().getFirst().status()).isEqualTo("needs-repair");
    Card token = calls.withLastCalls(List.of(card("needs-restart", List.of(link("token", "needs-restart")))))
        .getFirst();
    assertThat(token.status()).isEqualTo("needs-restart");
    assertThat(token.links()).hasSize(1);
  }

  @Test
  void historicalCallsStayVisibleButDoNotReviveAnEndedOAuthSignIn() {
    when(oauth.localClientUses()).thenReturn(List.of());
    when(audit.lastCall("dev", null, "cursor", CONNECTED_AT)).thenReturn(Optional.of(USED_AT));
    Card result = calls.withLastCalls(List.of(card("needs-restart", List.of(link("oauth", "needs-restart")))))
        .getFirst();

    assertThat(result.status()).isEqualTo("needs-restart");
    assertThat(result.links().getFirst().lastCallAt()).isEqualTo(USED_AT);
    assertThat(result.links().getFirst().usedAt()).isNull();
  }

  @Test
  void browserInstalledSignInsAreObservedWithoutClaimingConfigOwnershipAndDisappearWhenTheyEnd() {
    when(oauth.localClientUses()).thenReturn(List.of(use("og_old", "cursor", "dev", CONNECTED_AT),
        use("og_cursor", "cursor", "dev", USED_AT)));
    Card original = card("detected", List.of());
    Card result = calls.withCalls(List.of(original)).getFirst();

    assertThat(result.status()).isEqualTo("connected");
    assertThat(result.links()).singleElement().satisfies(l -> {
      assertThat(l.id()).isEqualTo("og_cursor");
      assertThat(l.method()).isEqualTo("oauth");
      assertThat(l.endpoint()).isEqualTo("dev");
      assertThat(l.configPath()).isNull();
      assertThat(l.configDisplay()).isNull();
      assertThat(l.entryName()).isNull();
      assertThat(l.lastCallAt()).isNull();
    });
    assertThat(original.links()).isEmpty();

    when(oauth.localClientUses()).thenReturn(List.of());
    assertThat(calls.withLastCalls(List.of(original)).getFirst()).isEqualTo(original);
  }

  private static OAuthService.ClientUse use(String id, String client, String endpoint, Instant usedAt) {
    return new OAuthService.ClientUse(id, client, endpoint, "dev", CONNECTED_AT.minusSeconds(5), usedAt);
  }

  private static Link link(String method, String status) {
    return new Link("managed-cursor", "cursor", "dev", "dev", method, "gaterook-dev", "/test/.cursor/mcp.json",
        "~/.cursor/mcp.json", null, null, method.equals("token") ? "cursor-laptop" : null, status, status,
        CONNECTED_AT, null, null, null, null);
  }

  private static Card card(String status, List<Link> links) {
    return new Card("cursor", "Cursor", status, status, "/test/.cursor/mcp.json", "~/.cursor/mcp.json", true,
        List.of(), null, List.of(), List.of(), links);
  }
}
