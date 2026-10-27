package ai.msrv.gaterook.core.clients.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code client_link}. */
public interface ClientLinkRepository extends JpaRepository<ClientLinkEntity, String> {

  List<ClientLinkEntity> findByClientAndRemovedAtIsNull(ClientKind client);

  List<ClientLinkEntity> findByEndpointIdAndRemovedAtIsNull(String endpointId);

  List<ClientLinkEntity> findByGrantId(String grantId);

  /** The entries GateRook still keeps in client configs (Clients page, drift). */
  List<ClientLinkEntity> findByRemovedAtIsNullOrderByCreatedAtAsc();

  List<ClientLinkEntity> findByTokenId(String tokenId);
}
