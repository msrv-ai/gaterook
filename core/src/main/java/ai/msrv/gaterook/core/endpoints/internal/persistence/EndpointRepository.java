package ai.msrv.gaterook.core.endpoints.internal.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code endpoint}. */
public interface EndpointRepository extends JpaRepository<EndpointEntity, String> {

  Optional<EndpointEntity> findBySlug(String slug);

  boolean existsBySlug(String slug);

  List<EndpointEntity> findAllByOrderBySlugAsc();
}
