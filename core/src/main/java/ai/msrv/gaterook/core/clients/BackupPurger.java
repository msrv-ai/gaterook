package ai.msrv.gaterook.core.clients;

import ai.msrv.gaterook.core.clients.internal.persistence.ClientLinkEntity;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.PrincipalRevoked;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A revoked named token takes the copies of config files that still hold it with it (ARCHITECTURE.md §7.2), wherever
 * it was revoked: Disconnect, the endpoint's Tokens tab, the endpoint's deletion.
 */
@Component
class BackupPurger {

  private final ClientService clients;

  BackupPurger(ClientService clients) {
    this.clients = clients;
  }

  /** After the revocation committed, off the request's thread. */
  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPrincipalRevoked(PrincipalRevoked revoked) {
    if (revoked.kind() != Caller.Kind.LOCAL_TOKEN) {
      return;
    }
    for (ClientLinkEntity l : clients.linksWithToken(revoked.principalId())) {
      clients.purgeBackupsHolding(l.getClient(), revoked.principalId());
    }
  }
}
