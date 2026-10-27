package ai.msrv.gaterook.core.oauth;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.oauth.internal.OAuthServer;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * What the app does about OAuth (ARCHITECTURE.md §6 tunnel / OAuth, §7.3): the sign-ins waiting for the person here
 * and their decision, and "Signed in" with Revoke. The decision is made only through this service, which only the
 * admin API on the local port reaches — never the browser that asked, never Slack or Telegram.
 */
@Service
public class OAuthService {

  /** A sign-in waiting for an answer: what the consent dialog shows. */
  public record Consent(String id, String code, String clientHost, String clientName, String clientKind,
      String redirectHost, String endpoint, String env, Tools tools, String ip, Ingress ingress, Instant expiresAt) {}

  /** The endpoint's tools by mode, as the dialog counts them. */
  public record Tools(int read, int approval, int off, int bypass) {}

  /** One sign-in of "Signed in". */
  public record Grant(String id, String client, String clientName, String clientKind, String endpoint,
      Ingress ingress, String resource, String scope, Instant createdAt, Instant expiresAt, Instant lastUsedAt,
      Instant revokedAt, String revokeReason, String state) {}

  private final OAuthServer server;
  private final EndpointService endpoints;
  private final EndpointSnapshotService snapshots;

  public OAuthService(OAuthServer server, EndpointService endpoints, EndpointSnapshotService snapshots) {
    this.server = server;
    this.endpoints = endpoints;
    this.snapshots = snapshots;
  }

  /** {@code GET /api/oauth/consents}: oldest first. */
  public List<Consent> consents() {
    return server.waiting().stream().map(p -> {
      EndpointService.ToolCounts t = snapshots.get(p.endpointId()).map(EndpointSnapshot::slug)
          .map(slug -> endpoints.get(slug).tools()).orElse(new EndpointService.ToolCounts(0, 0, 0));
      String env = snapshots.get(p.endpointId()).map(EndpointSnapshot::env).map(Env::id).orElse(null);
      return new Consent(p.id(), p.code(), p.clientHost(), p.clientName(), p.clientKind(), p.redirectHost(),
          p.slug(), env, new Tools(t.read(), t.approval(), t.off(), t.bypass()), p.browserIp(), p.ingress(), p.expiresAt());
    }).toList();
  }

  /**
   * {@code POST /api/oauth/consents/{id} {decision}}.
   *
   * @throws ProblemException 404 for an unknown sign-in, 409 when it was answered or expired already
   */
  public void decide(String id, boolean allow, String reason) {
    server.decide(id, allow, reason);
  }

  /** {@code GET /api/oauth/grants}: newest first, revoked ones included with their state. */
  public List<Grant> grants() {
    return server.grantRows().stream().map(r -> new Grant(r.id(), r.clientHost(), r.clientName(), r.clientKind(),
        r.endpointSlug(), r.ingress(), r.resource(), r.scope(), r.createdAt(), r.expiresAt(), r.lastUsedAt(),
        r.revokedAt(), r.revokeReason(), r.state())).toList();
  }

  /** The demo world's claude.ai sign-in: who, to which endpoint and URL, allowed when. */
  public record DemoSignIn(String clientId, String clientHost, String clientName, List<String> redirectUris,
      String endpoint, String resource, Instant allowedAt) {}

  /** Seeds {@code d} as if the person had allowed it ({@code GW_DEMO} only); the grant's id. */
  public String seedDemo(DemoSignIn d) {
    String endpointId = snapshots.bySlug(d.endpoint()).orElseThrow(() -> new IllegalArgumentException(
        "No endpoint " + d.endpoint())).id();
    return server.seedDemoGrant(d.clientId(), d.clientHost(), d.clientName(), d.redirectUris(), endpointId,
        d.resource(), d.allowedAt(), "Demo world");
  }

  /** {@code DELETE /api/oauth/grants/{id}}: access and refresh tokens stop at once; 404 for an unknown one. */
  public void revoke(String id, String reason) {
    server.revokeGrant(id, reason);
  }
}
