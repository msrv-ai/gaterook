package ai.msrv.gaterook.core.clients.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Saved apps that the person configures by hand. */
public interface ManualClientRepository extends JpaRepository<ManualClientEntity, String> {

  List<ManualClientEntity> findAllByOrderByCreatedAtAsc();

  List<ManualClientEntity> findByEndpointId(String endpointId);
}
