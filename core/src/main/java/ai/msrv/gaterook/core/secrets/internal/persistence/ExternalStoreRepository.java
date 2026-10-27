package ai.msrv.gaterook.core.secrets.internal.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code external_store}. */
public interface ExternalStoreRepository extends JpaRepository<ExternalStoreEntity, String> {

  Optional<ExternalStoreEntity> findByName(String name);
}
