package ai.msrv.gaterook.core.platform;

import java.util.List;

/**
 * SPI: whether a principal may still act (ARCHITECTURE.md §2.2, §5 step 11). The approval executor asks once more
 * before it runs an approved call, because a revocation between the decision and the execution finds no pending
 * request to reject. Each kind of principal is answered by the module that owns it: {@code endpoints.TokenService}
 * for named tokens and grants, {@code oauth} for OAuth grants (K3). Declared here next to {@link Caller}, so that
 * {@code approvals} needs no knowledge of who issues which principal.
 */
public interface PrincipalStatus {

  /** Whether this bean answers for principals of {@code kind}. */
  boolean supports(Caller.Kind kind);

  /**
   * Revoked, or never existed. A principal that merely expired is not revoked: the person's decision about a call it
   * made stands.
   */
  boolean revoked(Caller.Kind kind, String principalId);

  /**
   * The answer of all {@code statuses}: revoked when any bean that supports the kind says so, and when none does
   * (fail closed: a principal nobody vouches for doesn't act). The playground's principal belongs to the person at
   * this computer and is never revoked.
   */
  static boolean revoked(List<PrincipalStatus> statuses, Caller.Kind kind, String principalId) {
    if (kind == Caller.Kind.PLAYGROUND) {
      return false;
    }
    boolean answered = false;
    for (PrincipalStatus s : statuses) {
      if (s.supports(kind)) {
        answered = true;
        if (s.revoked(kind, principalId)) {
          return true;
        }
      }
    }
    return !answered;
  }
}
