package com.gaterook.core.connectors;

/**
 * SPI: which connector types have connections. The {@code connections} module answers it; the connector modules ask
 * before they install a type that something merely looked up, so that an old audit row or a label doesn't make a
 * package arrive that no connection would use.
 */
public interface ConnectorTypesInUse {

  /** Whether a connection of {@code typeId} exists. */
  boolean inUse(String typeId);
}
