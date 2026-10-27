package ai.msrv.gaterook.core.connectors.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code plugin}. */
public interface PluginRepository extends JpaRepository<PluginEntity, String> {

  List<PluginEntity> findByState(PluginState state);
}
