package ai.msrv.gaterook.connectors.cassandra.module;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorModule;
import ai.msrv.gaterook.connector.api.ModuleContext;
import ai.msrv.gaterook.connectors.cassandra.CassandraConnector;
import java.util.List;

/**
 * The Cassandra connector as a connector module (docs/connector-delivery.md): the same connector the development
 * build compiles in, behind the entry point a module host asks for its connectors.
 */
public final class CassandraModule implements ConnectorModule {

  @Override
  public List<Connector> connectors(ModuleContext context) {
    return List.of(new CassandraConnector());
  }
}
