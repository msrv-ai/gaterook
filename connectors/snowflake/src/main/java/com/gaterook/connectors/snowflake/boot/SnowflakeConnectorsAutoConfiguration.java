package com.gaterook.connectors.snowflake.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.snowflake.SnowflakeConnector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Snowflake connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched on
 * and off at run time by {@code connector_type.enabled}. The connector needs nothing from the core: it reaches only the
 * address its connections' account identifiers name.
 */
@AutoConfiguration
@ImportRuntimeHints(SnowflakeHints.class)
public class SnowflakeConnectorsAutoConfiguration {

  @Bean
  Connector snowflakeConnector() {
    return new SnowflakeConnector();
  }
}
