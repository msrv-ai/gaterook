package ai.msrv.gaterook.core.oauth.internal;

import ai.msrv.gaterook.core.endpoints.EndpointRemovalParticipant;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthClientRepository;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthGrantRepository;
import ai.msrv.gaterook.core.oauth.internal.persistence.OAuthTokenRepository;
import ai.msrv.gaterook.core.platform.BuildFlags;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.RowMac;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.settings.SettingsService;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wiring of the authorization servers. {@code allowedClientHosts} is {@code claude.ai}: the tunnel's server takes a
 * metadata document from nowhere else. Tests of a non-release build may add hosts
 * ({@code gateway.test.oauth.client-hosts}) and a CA for a loopback HTTPS document server
 * ({@code gateway.test.oauth.cimd-ca}); a release ignores both.
 */
@Configuration(proxyBeanMethods = false)
class OAuthConfiguration {

  /** Whose Client ID Metadata Documents the tunnel's server reads (report 02 §4 {@code allowedClientHosts}). */
  static final List<String> ALLOWED_CLIENT_HOSTS = List.of("claude.ai");

  @Bean
  CimdResolver cimdResolver(@Value("${gateway.test.oauth.client-hosts:}") String testHosts,
      @Value("${gateway.test.oauth.cimd-ca:}") String testCa, Clock clock) {
    List<String> hosts = new ArrayList<>(ALLOWED_CLIENT_HOSTS);
    if (!testHosts.isBlank() && !BuildFlags.RELEASE) {
      for (String h : testHosts.split(",")) {
        if (!h.isBlank()) {
          hosts.add(h.strip().toLowerCase(Locale.ROOT));
        }
      }
    }
    return new CimdResolver(hosts, BuildFlags.RELEASE ? null : testCa, clock);
  }

  @Bean
  Grants oauthGrants(OAuthGrantRepository grants, OAuthTokenRepository tokens, OAuthClientRepository clients,
      EndpointSnapshotService snapshots, ObjectProvider<EndpointRemovalParticipant> participants,
      ObjectProvider<SetupChangeRecorder> setupChanges, ApplicationEventPublisher publisher, Clock clock,
      PlatformTransactionManager transactions) {
    return new Grants(grants, tokens, clients, snapshots, participants, setupChanges, publisher, clock, transactions);
  }

  @Bean
  Clients oauthClients(OAuthClientRepository clients, CimdResolver cimd, RowMac rowMac, IntegrityAlerts alerts,
      ObjectProvider<SetupChangeRecorder> setupChanges, Grants grants, Clock clock,
      PlatformTransactionManager transactions) {
    return new Clients(clients, cimd, rowMac, alerts, setupChanges, grants, clock, transactions);
  }

  @Bean(destroyMethod = "close")
  Consents oauthConsents(EventBus events, Clock clock) {
    return new Consents(events, clock);
  }

  @Bean
  AuthorizationCodes authorizationCodes(Clock clock) {
    return new AuthorizationCodes(clock);
  }

  @Bean
  OAuthServer oauthServer(Issuers issuers, Clients clients, Grants grants, Consents consents,
      AuthorizationCodes codes, EndpointSnapshotService snapshots, SettingsService settings,
      TunnelPublication tunnel, ObjectProvider<SetupChangeRecorder> setupChanges, Clock clock,
      PlatformTransactionManager transactions) {
    return new OAuthServer(issuers, clients, grants, consents, codes, snapshots, settings, tunnel, setupChanges,
        clock, transactions);
  }
}
