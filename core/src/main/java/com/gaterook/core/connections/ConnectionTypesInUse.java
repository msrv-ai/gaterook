package com.gaterook.core.connections;

import com.gaterook.core.connectors.ConnectorTypesInUse;
import org.springframework.stereotype.Component;

/** The connector types that connections exist of, for the connector modules ({@link ConnectorTypesInUse}). */
@Component
class ConnectionTypesInUse implements ConnectorTypesInUse {

  private final ConnectionConfigs connections;

  ConnectionTypesInUse(ConnectionConfigs connections) {
    this.connections = connections;
  }

  @Override
  public boolean inUse(String typeId) {
    return connections.all().stream().anyMatch(s -> s.config().type().equals(typeId));
  }
}
