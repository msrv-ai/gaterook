package ai.msrv.gaterook.core.platform;

import java.util.Objects;
import java.util.Optional;

/**
 * SPI: turns the bearer token of an MCP request into a {@link Caller} (ARCHITECTURE.md §5 step 2).
 *
 * <p>{@code CallerAuthenticator} (this module) asks every bean that {@linkplain #supports supports} the request's
 * ingress, in order, and answers 401 when none recognizes the token. Implementations live in the modules that own the
 * credentials: {@code endpoints.TokenAuthenticator} ({@code gwt_} named and {@code gwb_} bridge tokens, LOCAL) and
 * {@code oauth.OAuthAuthenticator} (tokens of the local authorization server on LOCAL, of the tunnel's on TUNNEL).
 * Keeping the interface here lets {@code platform} depend on no module.
 *
 * <p>Tokens are opaque; only their SHA-256 is stored and it is looked up on every request, so a revocation takes
 * effect on the next call (A4).
 */
public interface Authenticator {

  /** Whether this authenticator serves requests that arrived through {@code ingress}. */
  boolean supports(Ingress ingress);

  /**
   * The caller behind the token, or empty when the token is not one of this authenticator's or is unknown, expired,
   * revoked, issued for another endpoint or for another ingress. Never throws for a bad token: the response to the
   * client is the same 401 whatever the reason.
   */
  Optional<Caller> authenticate(Request request);

  /**
   * The {@code WWW-Authenticate} value of a 401 for {@code endpointSlug} on {@code ingress}, when this authenticator
   * can tell the client where to get a token: an OAuth resource server names its metadata
   * ({@code Bearer resource_metadata="…", scope="mcp"}, RFC 9728). Empty leaves the plain
   * {@code Bearer realm="gaterook"}. It must not tell more about the endpoint or the token than RFC 6750 allows
   * ({@code error="invalid_token"} when one was presented).
   *
   * @param tokenPresented whether the request carried a bearer token at all
   */
  default Optional<String> challenge(Ingress ingress, String endpointSlug, boolean tokenPresented) {
    return Optional.empty();
  }

  /**
   * One authentication attempt.
   *
   * @param endpointSlug  the endpoint of the URL ({@code /mcp/<slug>}): a token is valid for one endpoint only
   * @param bearerToken   the value after {@code Bearer }; never logged, see {@link #toString()}
   * @param remoteAddress the listener's view of the peer address
   */
  record Request(Ingress ingress, String endpointSlug, String bearerToken, String remoteAddress) {

    public Request {
      Objects.requireNonNull(ingress, "ingress");
      Objects.requireNonNull(endpointSlug, "endpointSlug");
      Objects.requireNonNull(bearerToken, "bearerToken");
    }

    /** Leaves the token out: requests end up in logs and exception messages. */
    @Override
    public String toString() {
      return "Request[ingress=" + ingress + ", endpointSlug=" + endpointSlug + ", bearerToken=‹redacted›"
          + ", remoteAddress=" + remoteAddress + "]";
    }
  }
}
