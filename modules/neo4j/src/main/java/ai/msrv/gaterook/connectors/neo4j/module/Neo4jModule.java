package ai.msrv.gaterook.connectors.neo4j.module;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorModule;
import ai.msrv.gaterook.connector.api.ModuleContext;
import ai.msrv.gaterook.connectors.neo4j.Neo4jConnector;
import java.util.List;

/**
 * The Neo4j connector as a connector module (docs/connector-delivery.md): the same connector the development build
 * compiles in, behind the entry point a module host asks for its connectors.
 */
public final class Neo4jModule implements ConnectorModule {

  @Override
  public List<Connector> connectors(ModuleContext context) {
    return List.of(new Neo4jConnector());
  }
}
