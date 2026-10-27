package ai.msrv.gaterook.core.endpoints.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code client_grant}. */
public interface ClientGrantRepository extends JpaRepository<ClientGrantEntity, String> {

  List<ClientGrantEntity> findByEndpointId(String endpointId);

  List<ClientGrantEntity> findByClientAndRevokedAtIsNull(String client);
}
