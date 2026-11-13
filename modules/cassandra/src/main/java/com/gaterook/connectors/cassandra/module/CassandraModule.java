package com.gaterook.connectors.cassandra.module;

import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorModule;
import com.gaterook.connector.api.ModuleContext;
import com.gaterook.connectors.cassandra.CassandraConnector;
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
