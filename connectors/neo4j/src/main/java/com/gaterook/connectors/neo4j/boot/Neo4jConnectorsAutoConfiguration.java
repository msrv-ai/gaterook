package com.gaterook.connectors.neo4j.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.neo4j.Neo4jConnector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Neo4j connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched on
 * and off at run time by {@code connector_type.enabled}.
 */
@AutoConfiguration
@ImportRuntimeHints(Neo4jHints.class)
public class Neo4jConnectorsAutoConfiguration {

  @Bean
  Connector neo4jConnector() {
    return new Neo4jConnector();
  }
}
