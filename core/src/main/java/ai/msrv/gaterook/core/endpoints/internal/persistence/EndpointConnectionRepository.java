package ai.msrv.gaterook.core.endpoints.internal.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Table {@code endpoint_connection}. */
public interface EndpointConnectionRepository extends JpaRepository<EndpointConnectionEntity, String> {

  /**
   * The wiring of an endpoint in the order its connections were added: the order of its pairs, so that the published
   * tool list (and its hash) doesn't change between two reads.
   */
  List<EndpointConnectionEntity> findByEndpointIdOrderByAddedAtAscIdAsc(String endpointId);

  List<EndpointConnectionEntity> findByConnectionId(String connectionId);

  Optional<EndpointConnectionEntity> findByEndpointIdAndConnectionId(String endpointId, String connectionId);

  /** "Used by" of a connection: the slugs of the endpoints it is wired into. */
  @Query("select ec.endpoint.slug from EndpointConnectionEntity ec where ec.connectionId = :connectionId "
      + "order by ec.endpoint.slug")
  List<String> findEndpointSlugsByConnectionId(String connectionId);
}
