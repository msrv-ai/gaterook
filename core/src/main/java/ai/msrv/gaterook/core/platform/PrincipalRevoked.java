package ai.msrv.gaterook.core.platform;

import java.util.Objects;

/**
 * A principal can't call any more (ARCHITECTURE.md §5 step 11): a named token or a grant was revoked, or the endpoint
 * it belonged to was deleted; an OAuth grant later too. Published by the module that owns the principal inside the
 * transaction that revokes it, so that {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listeners hear it
 * only once the revocation holds: {@code invocation} forgets the principal's rate limiters. Pending approval requests
 * are rejected inside that same transaction by the {@code endpoints} SPI {@code EndpointRemovalParticipant}, not by
 * this event: a request must never outlive its principal's revocation.
 *
 * <p>Declared in {@code platform} next to {@link Caller}: {@code invocation}, which keeps per-principal state, may not
 * depend on {@code endpoints}.
 *
 * @param principalId {@link Caller#principalId()}: the named token's or the grant's id
 */
public record PrincipalRevoked(Caller.Kind kind, String principalId, String endpointId, String endpointSlug) {

  public PrincipalRevoked {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(principalId, "principalId");
  }
}
