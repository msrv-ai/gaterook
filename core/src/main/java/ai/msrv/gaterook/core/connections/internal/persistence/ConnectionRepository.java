package ai.msrv.gaterook.core.connections.internal.persistence;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Table {@code connection}. */
public interface ConnectionRepository extends JpaRepository<ConnectionEntity, String> {

  Optional<ConnectionEntity> findByName(String name);

  boolean existsByName(String name);

  List<ConnectionEntity> findByTypeOrderByNameAsc(String type);

  List<ConnectionEntity> findByEnvOrderByNameAsc(Env env);

  List<ConnectionEntity> findAllByOrderByNameAsc();

  long countByType(String type);

  /**
   * The last health check. A bulk update without {@code versioned}: health is not part of the row's MAC and must not
   * give the connection a new version (connector pools key on it). {@code health_since} stays while the state stays
   * (every expression of SET reads the row as it was before the update).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update ConnectionEntity c set c.healthSince = case when c.healthState = :state and c.healthSince is not null "
      + "then c.healthSince else :at end, c.healthState = :state, c.healthLatencyMs = :latencyMs, "
      + "c.healthMessage = :message, c.healthCheckedAt = :at where c.id = :id")
  int recordHealth(String id, HealthResult.State state, Long latencyMs, String message, Instant at);
}
