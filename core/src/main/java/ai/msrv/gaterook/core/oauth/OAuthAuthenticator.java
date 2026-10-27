package ai.msrv.gaterook.core.oauth;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.oauth.internal.Issuers;
import ai.msrv.gaterook.core.oauth.internal.OAuthServer;
import ai.msrv.gaterook.core.platform.Authenticator;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.core.platform.PrincipalStatus;
import ai.msrv.gaterook.core.tunnel.TunnelPublication;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * OAuth access tokens ({@code gwo_}) on MCP calls (ARCHITECTURE.md §5 step 2, §7.3): through the tunnel the only
 * credential there is, locally beside the endpoint's own tokens for Cursor and Antigravity. A token works only on the
 * ingress of the server that issued it, for the endpoint it was granted for, at the URL it was granted for; every
 * check reads the database, so Revoke takes effect on the next call. The principal is the grant ({@code og_…}).
 *
 * <p>Its 401 names the protected resource metadata (RFC 9728) where a client can sign in: the tunnel's published
 * endpoint, or a local endpoint with OAuth on.
 */
@Component
@Order(20)
public class OAuthAuthenticator implements Authenticator, PrincipalStatus {

  private final OAuthServer access;
  private final Issuers issuers;
  private final TunnelPublication tunnel;
  private final EndpointSnapshotService snapshots;

  public OAuthAuthenticator(OAuthServer access, Issuers issuers, TunnelPublication tunnel,
      EndpointSnapshotService snapshots) {
    this.access = access;
    this.issuers = issuers;
    this.tunnel = tunnel;
    this.snapshots = snapshots;
  }

  @Override
  public boolean supports(Ingress ingress) {
    return ingress == Ingress.LOCAL || ingress == Ingress.TUNNEL;
  }

  @Override
  public Optional<Caller> authenticate(Request request) {
    if (!request.bearerToken().startsWith("gwo_")) {
      return Optional.empty();
    }
    return access.authenticate(request);
  }

  @Override
  public Optional<String> challenge(Ingress ingress, String endpointSlug, boolean tokenPresented) {
    String metadata;
    if (ingress == Ingress.TUNNEL) {
      Optional<TunnelPublication.Publication> p = tunnel.current();
      if (p.isEmpty() || !p.get().endpointSlug().equals(endpointSlug)) {
        return Optional.empty();
      }
      metadata = p.get().origin() + "/.well-known/oauth-protected-resource/mcp/" + endpointSlug;
    } else if (ingress == Ingress.LOCAL) {
      boolean signsIn = snapshots.bySlug(endpointSlug).filter(e -> e.oauthEnabled() && e.enabled()
          && e.env() != Env.PROD).isPresent();
      if (!signsIn) {
        return Optional.empty();
      }
      metadata = issuers.localResource(endpointSlug).replace("/mcp/", "/.well-known/oauth-protected-resource/mcp/");
    } else {
      return Optional.empty();
    }
    return Optional.of("Bearer " + (tokenPresented ? "error=\"invalid_token\", " : "") + "resource_metadata=\""
        + metadata + "\", scope=\"mcp\"");
  }

  @Override
  public boolean supports(Caller.Kind kind) {
    return kind == Caller.Kind.OAUTH;
  }

  /** Revoked or never existed; an expired or address-changed grant isn't revoked (the person's decisions stand). */
  @Override
  public boolean revoked(Caller.Kind kind, String principalId) {
    return kind == Caller.Kind.OAUTH && access.revoked(principalId);
  }
}
