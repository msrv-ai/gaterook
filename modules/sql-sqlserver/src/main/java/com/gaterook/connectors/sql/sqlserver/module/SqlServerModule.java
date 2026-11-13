package com.gaterook.connectors.sql.sqlserver.module;

import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorModule;
import com.gaterook.connector.api.ModuleContext;
import com.gaterook.connectors.sql.JdbcSqlConnector;
import com.gaterook.connectors.sql.SqlServerDialect;
import java.util.List;

/**
 * The SQL Server connector type as a connector module (docs/connector-delivery.md): the SQL connector with the
 * dialect a build that compiles the type in registers, behind the entry point a module host asks for its connectors.
 */
public final class SqlServerModule implements ConnectorModule {

  @Override
  public List<Connector> connectors(ModuleContext context) {
    return List.of(new JdbcSqlConnector(new SqlServerDialect()));
  }
}
