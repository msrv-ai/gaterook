package ai.msrv.gaterook.core.endpoints;

import java.util.List;

/**
 * SPI: a module that keeps something of an endpoint takes part when the endpoint is deleted, a connection leaves it or
 * one of its principals is revoked, inside that transaction: {@code approvals} rejects the pending requests,
 * {@code clients} reports the client links that stop working. Throwing vetoes the change. It also says what access to
 * the endpoint it holds ({@link #liveAccess}).
 */
public interface EndpointRemovalParticipant {

  /** What one participant did, for the summary of {@code DELETE /api/endpoints/{slug}}. */
  record Removal(List<String> clients, int rejectedApprovals) {

    public static Removal none() {
      return new Removal(List.of(), 0);
    }
  }

  /** The endpoint is being deleted. */
  Removal onEndpointRemoving(String endpointId, String slug, String reason);

  /** A connection leaves the endpoint (Disconnect, split); its pending requests there can't run any more. */
  default Removal onConnectionUnwiring(String endpointId, String slug, String connectionId, String connectionName,
      String reason) {
    return Removal.none();
  }

  /**
   * A principal of the endpoint was revoked (a named token, a grant; ARCHITECTURE.md §5 step 11): what it asked for
   * and still waits can't run any more. The owner of the principal also publishes {@code platform.PrincipalRevoked}.
   *
   * @param principalId what approvals and rate limits key on ({@code platform.Caller.principalId})
   * @param label       the token's name or the grant's label, for the reason the agent and the person read
   */
  default Removal onPrincipalRevoked(String endpointId, String slug, String principalId, String label,
      String reason) {
    return Removal.none();
  }

  /**
   * Access to the endpoint this module still holds ("1 OAuth grant"), empty when none. An endpoint without connections
   * takes the env of the first one it gets, and must not cross the prod line holding access made under the other
   * side's rules: the core refuses that connection while anything is listed here (ARCHITECTURE.md §9 "Stricter production rules").
   * Named tokens and client grants are the endpoints module's own; OAuth grants belong to {@code oauth} (K3).
   */
  default List<String> liveAccess(String endpointId) {
    return List.of();
  }
}
