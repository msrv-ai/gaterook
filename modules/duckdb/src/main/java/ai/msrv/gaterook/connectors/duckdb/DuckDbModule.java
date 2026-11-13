package ai.msrv.gaterook.connectors.duckdb;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorModule;
import ai.msrv.gaterook.connector.api.ModuleContext;
import java.util.List;

/**
 * The DuckDB connector as a connector module (docs/connector-delivery.md): the entry point a module host asks
 * for its connectors. DuckDB exists only as a module: its code runs in a module host, in that host's sandbox,
 * and is never compiled into the core.
 */
public final class DuckDbModule implements ConnectorModule {

  @Override
  public List<Connector> connectors(ModuleContext context) {
    return List.of(new DuckDbConnector());
  }
}
