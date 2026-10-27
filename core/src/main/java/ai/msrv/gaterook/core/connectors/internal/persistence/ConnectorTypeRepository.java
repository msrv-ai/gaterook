package ai.msrv.gaterook.core.connectors.internal.persistence;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code connector_type}. */
public interface ConnectorTypeRepository extends JpaRepository<ConnectorTypeEntity, String> {

  List<ConnectorTypeEntity> findByKind(ConnectorManifest.Kind kind);

  List<ConnectorTypeEntity> findByPluginId(String pluginId);
}
