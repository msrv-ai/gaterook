package ai.msrv.gaterook.core.endpoints.internal.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code endpoint_tool}. */
public interface EndpointToolRepository extends JpaRepository<EndpointToolEntity, String> {

  List<EndpointToolEntity> findByEndpointId(String endpointId);

  List<EndpointToolEntity> findByEndpointIdAndConnectionId(String endpointId, String connectionId);

  Optional<EndpointToolEntity> findByEndpointIdAndConnectionIdAndToolKey(String endpointId, String connectionId,
      String toolKey);

  /** An endpoint-wide core tool ({@code connection_id IS NULL}). */
  Optional<EndpointToolEntity> findByEndpointIdAndConnectionIdIsNullAndToolKey(String endpointId, String toolKey);

  List<EndpointToolEntity> findByConnectionId(String connectionId);
}
