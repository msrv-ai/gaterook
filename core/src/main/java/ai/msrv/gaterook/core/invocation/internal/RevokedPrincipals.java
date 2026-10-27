package ai.msrv.gaterook.core.invocation.internal;

import ai.msrv.gaterook.core.invocation.ToolGuard;
import ai.msrv.gaterook.core.platform.PrincipalRevoked;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A revoked principal calls no more: its rate limiters go, so that the registry doesn't keep every token that ever
 * called. Whatever it still had pending was rejected in the revoking transaction (ARCHITECTURE.md §5 step 11).
 */
@Component
class RevokedPrincipals {

  private final ToolGuard guard;

  RevokedPrincipals(ToolGuard guard) {
    this.guard = guard;
  }

  @Async
  @TransactionalEventListener
  public void onPrincipalRevoked(PrincipalRevoked event) {
    guard.forgetPrincipal(event.principalId());
  }
}
