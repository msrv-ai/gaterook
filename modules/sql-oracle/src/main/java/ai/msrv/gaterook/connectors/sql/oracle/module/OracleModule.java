package ai.msrv.gaterook.connectors.sql.oracle.module;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorModule;
import ai.msrv.gaterook.connector.api.ModuleContext;
import ai.msrv.gaterook.connectors.sql.JdbcSqlConnector;
import ai.msrv.gaterook.connectors.sql.OracleDialect;
import java.util.List;

/**
 * The Oracle Database connector type as a connector module (docs/connector-delivery.md): the SQL connector with the
 * dialect a build that compiles the type in registers, behind the entry point a module host asks for its connectors.
 */
public final class OracleModule implements ConnectorModule {

  @Override
  public List<Connector> connectors(ModuleContext context) {
    return List.of(new JdbcSqlConnector(new OracleDialect()));
  }
}
