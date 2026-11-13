package com.gaterook.connectors.influxdb.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.influxdb.InfluxDbConnector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the InfluxDB connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched on
 * and off at run time by {@code connector_type.enabled}.
 */
@AutoConfiguration
@ImportRuntimeHints(InfluxDbHints.class)
public class InfluxDbConnectorsAutoConfiguration {

  @Bean
  Connector influxDbConnector() {
    return new InfluxDbConnector();
  }
}
