package com.gaterook.connectors.databricks.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.databricks.DatabricksConnector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Databricks connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched on
 * and off at run time by {@code connector_type.enabled}. The connector needs nothing of the core: its workspace is the
 * connection's own, reached over TLS with the JDK's trusted roots.
 */
@AutoConfiguration
@ImportRuntimeHints(DatabricksHints.class)
public class DatabricksConnectorsAutoConfiguration {

  @Bean
  Connector databricksConnector() {
    return new DatabricksConnector();
  }
}
