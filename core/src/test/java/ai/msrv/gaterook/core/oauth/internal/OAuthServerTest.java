package ai.msrv.gaterook.core.oauth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.platform.Authenticator;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.settings.SettingsService;
import ai.msrv.gaterook.core.tunnel.TunnelProvider;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/** The tunnel's resource server where the rest of the core can't be relied on (ARCHITECTURE.md §9 "Stricter production rules"). */
class OAuthServerTest {

  private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
  private static final String HOST = "gw-smoke-7f3k.trycloudflare.com";

  @Test
  @SuppressWarnings("unchecked")
  void aPublishedEndpointThatTurnedProdTakesNoMoreCallsThroughTheTunnel() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    TunnelPublication tunnel = new TunnelPublication("", "");
    tunnel.publish(new TunnelPublication.Publication(HOST, "stage-readonly", TunnelProvider.CLOUDFLARE_QUICK));
    Grants grants = mock(Grants.class);
    when(grants.access("gwo_token")).thenReturn(Optional.of(new Grants.Live("og_000000000001", "row-1",
        "https://claude.ai/oauth/mcp-oauth-client-metadata", "claude.ai", "Claude", "endpoint-1", Ingress.TUNNEL,
        "https://" + HOST + "/mcp/stage-readonly", "mcp offline_access", NOW.plus(Duration.ofDays(7)))));
    AtomicReference<Env> env = new AtomicReference<>(Env.STAGE);
    EndpointSnapshotService snapshots = mock(EndpointSnapshotService.class);
    when(snapshots.get("endpoint-1")).thenAnswer(i -> Optional.of(new EndpointSnapshot("endpoint-1", "stage-readonly",
        null, env.get(), true, true, null, null, true, false, null, 1, List.of(), List.of(), List.of(), null)));
    OAuthServer server = new OAuthServer(mock(Issuers.class), mock(Clients.class), grants, mock(Consents.class),
        new AuthorizationCodes(clock), snapshots, mock(SettingsService.class), tunnel,
        (ObjectProvider<SetupChangeRecorder>) mock(ObjectProvider.class), clock,
        mock(PlatformTransactionManager.class));
    Authenticator.Request call = new Authenticator.Request(Ingress.TUNNEL, "stage-readonly", "gwo_token",
        "160.79.104.10");

    assertThat(server.authenticate(call)).isPresent();
    // The tunnel stops it shortly after (an endpoint listener after the commit); its calls stop at once.
    env.set(Env.PROD);
    assertThat(server.authenticate(call)).isEmpty();
  }
}
