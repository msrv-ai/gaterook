package ai.msrv.gaterook.core.platform;

import java.util.List;
import java.util.Optional;

/**
 * Step 2 of the call path (ARCHITECTURE.md §5): asks every {@link Authenticator} that serves the request's ingress, in
 * bean order, and stops at the first that recognizes the token. Empty means 401, whatever the reason: unknown, expired,
 * revoked, another endpoint's or another ingress's token all look the same to the client.
 */
public class CallerAuthenticator {

  private final List<Authenticator> authenticators;

  public CallerAuthenticator(List<Authenticator> authenticators) {
    this.authenticators = List.copyOf(authenticators);
  }

  public Optional<Caller> authenticate(Authenticator.Request request) {
    if (request.bearerToken().isBlank()) {
      return Optional.empty();
    }
    for (Authenticator authenticator : authenticators) {
      if (!authenticator.supports(request.ingress())) {
        continue;
      }
      Optional<Caller> caller = authenticator.authenticate(request);
      if (caller.isPresent()) {
        if (caller.get().ingress() != request.ingress()) {
          // An authenticator must never turn a token of one ingress into a caller of another.
          return Optional.empty();
        }
        return caller;
      }
    }
    return Optional.empty();
  }

  /** The plain challenge when no authenticator names a better one. */
  public static final String DEFAULT_CHALLENGE = "Bearer realm=\"gaterook\"";

  /**
   * The {@code WWW-Authenticate} of a 401 on {@code ingress}: the first authenticator of that ingress that can tell
   * the client where to sign in (OAuth resource metadata), else {@link #DEFAULT_CHALLENGE}.
   */
  public String challenge(Ingress ingress, String endpointSlug, boolean tokenPresented) {
    for (Authenticator authenticator : authenticators) {
      if (!authenticator.supports(ingress)) {
        continue;
      }
      Optional<String> challenge = authenticator.challenge(ingress, endpointSlug, tokenPresented);
      if (challenge.isPresent()) {
        return challenge.get();
      }
    }
    return DEFAULT_CHALLENGE;
  }
}
