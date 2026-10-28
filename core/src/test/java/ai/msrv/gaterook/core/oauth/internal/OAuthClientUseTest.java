package ai.msrv.gaterook.core.oauth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientKind;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.settings.SettingsService;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

class OAuthClientUseTest {

  private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
  private final Grants grants = mock(Grants.class);
  private final Clients clients = mock(Clients.class);
  private final Issuers issuers = mock(Issuers.class);
  private final EndpointSnapshotService snapshots = mock(EndpointSnapshotService.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void onlyCurrentVerifiedLocalSignInsWithTokenExchangeCountAsClientUse() {
    List<Grants.Row> rows = List.of(
        row("cursor", "dev", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), resource("dev")),
        row("antigravity", "stage", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), resource("stage")),
        row("consent", "dev", Ingress.LOCAL, "active", null, NOW.plusSeconds(3600), resource("dev")),
        row("expired", "dev", Ingress.LOCAL, "active", NOW, NOW, resource("dev")),
        row("revoked", "dev", Ingress.LOCAL, "revoked", NOW, NOW.plusSeconds(3600), resource("dev")),
        row("tunnel", "dev", Ingress.TUNNEL, "active", NOW, NOW.plusSeconds(3600), resource("dev")),
        row("old-port", "dev", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), "http://127.0.0.1:7000/mcp/dev"),
        row("disabled", "disabled", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), resource("disabled")),
        row("oauth-off", "oauth-off", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), resource("oauth-off")),
        row("blocked", "blocked", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), resource("blocked")),
        row("prod", "prod", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), resource("prod")),
        row("missing", "missing", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), resource("missing")),
        row("unverified", "dev", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), resource("dev")),
        row("unknown", "dev", Ingress.LOCAL, "active", NOW, NOW.plusSeconds(3600), resource("dev")));
    when(grants.list()).thenReturn(rows);
    for (Grants.Row row : rows) {
      when(grants.live(row.id())).thenReturn(Optional.of(new Grants.Live(row.id(), "row-" + row.id(), row.id(),
          "localhost", "Cursor", row.endpointId(), row.ingress(), row.resource(), row.scope(), row.expiresAt())));
    }
    when(clients.forToken(any(), anyString())).thenAnswer(call -> {
      String id = call.getArgument(1);
      if (id.equals("unverified")) return Optional.empty();
      String name = id.equals("unknown") ? "Local app" : id.equals("antigravity") ? "Antigravity" : "Cursor";
      return Optional.of(new Clients.Client("row-" + id, id, OAuthClientKind.DCR, Ingress.LOCAL, "localhost", name,
          List.of("http://localhost:4321/callback")));
    });
    when(issuers.localResource(anyString())).thenAnswer(call -> resource(call.getArgument(0)));
    for (String slug : List.of("dev", "stage", "disabled", "oauth-off", "blocked", "prod")) {
      when(snapshots.get(slug)).thenReturn(Optional.of(endpoint(slug)));
    }

    assertThat(server().localClientUses()).satisfiesExactly(cursor -> {
      assertThat(cursor.client()).isEqualTo("cursor");
      assertThat(cursor.grantId()).isEqualTo("cursor");
      assertThat(cursor.endpoint()).isEqualTo("dev");
      assertThat(cursor.usedAt()).isEqualTo(NOW);
    }, antigravity -> {
      assertThat(antigravity.client()).isEqualTo("antigravity");
      assertThat(antigravity.endpoint()).isEqualTo("stage");
    });
  }

  @SuppressWarnings("unchecked")
  private OAuthServer server() {
    return new OAuthServer(issuers, clients, grants, mock(Consents.class), new AuthorizationCodes(clock), snapshots,
        mock(SettingsService.class), new TunnelPublication("", ""),
        (ObjectProvider<SetupChangeRecorder>) mock(ObjectProvider.class), clock,
        mock(PlatformTransactionManager.class));
  }

  private static Grants.Row row(String id, String endpoint, Ingress ingress, String state, Instant usedAt,
      Instant expiresAt, String resource) {
    return new Grants.Row(id, "localhost", "Cursor", "dcr", endpoint, endpoint, ingress, resource,
        "mcp offline_access", NOW.minusSeconds(60), expiresAt, usedAt, state.equals("revoked") ? NOW : null, null,
        state);
  }

  private static EndpointSnapshot endpoint(String slug) {
    return new EndpointSnapshot(slug, slug, null, slug.equals("prod") ? Env.PROD : Env.DEV, false,
        !slug.equals("disabled"), null, null, false, !slug.equals("oauth-off"), null, 1, List.of(), List.of(),
        List.of(), slug.equals("blocked") ? "Changed outside GateRook" : null);
  }

  private static String resource(String slug) {
    return "http://127.0.0.1:7465/mcp/" + slug;
  }
}
