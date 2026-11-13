package com.gaterook.connector.api;

import java.util.List;

/**
 * The entry point of a connector module: a package that adds one or more connector types and is delivered apart from
 * the application (connector API 1.2). The module host instantiates the class its manifest names as
 * {@code moduleClass} with a public no-argument constructor and asks it once for its connectors.
 *
 * <p>Each connector is one connector type: the ids of their manifests are the types the module's manifest declares,
 * no more and no fewer, and the tools each reports are the ones declared for its type. A connector lives as long as
 * the module is loaded and is closed with it.
 */
public interface ConnectorModule extends AutoCloseable {

  /** The connectors of this module, one per connector type, in the order of the module's manifest. */
  List<Connector> connectors(ModuleContext context);

  /** Called when the module is unloaded, after its connectors were closed. */
  @Override
  default void close() {}
}
