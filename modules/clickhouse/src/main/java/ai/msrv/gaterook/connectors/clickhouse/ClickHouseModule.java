package ai.msrv.gaterook.connectors.clickhouse;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorModule;
import ai.msrv.gaterook.connector.api.ModuleContext;
import java.util.List;

/**
 * The ClickHouse connector as a connector module (docs/connector-delivery.md): the entry point a module host asks
 * for its connectors. ClickHouse exists only as a module: its code runs in a module host, in that host's sandbox,
 * and is never compiled into the core.
 */
public final class ClickHouseModule implements ConnectorModule {

  @Override
  public List<Connector> connectors(ModuleContext context) {
    return List.of(new ClickHouseConnector());
  }
}
