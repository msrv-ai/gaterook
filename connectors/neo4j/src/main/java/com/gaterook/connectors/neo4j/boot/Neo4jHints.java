package com.gaterook.connectors.neo4j.boot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Native-image hints of the Neo4j connector; they live with the module, not in the core (ARCHITECTURE.md §2.3). The
 * driver ships its own native-image configuration and substitutions, and Netty its reachability metadata; what is left
 * are the connector's own schemas. A native image with the driver is not verified yet (docs/neo4j.md).
 */
class Neo4jHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    // Tool and connection schemas are read with getResourceAsStream: null in native without this.
    hints.resources().registerPattern("connectors/neo4j/*.schema.json");
  }
}
