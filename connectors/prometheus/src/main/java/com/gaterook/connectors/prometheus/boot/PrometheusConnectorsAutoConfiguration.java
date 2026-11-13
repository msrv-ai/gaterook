package com.gaterook.connectors.prometheus.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.prometheus.PrometheusConnector;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Prometheus connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched on
 * and off at run time by {@code connector_type.enabled}.
 */
@AutoConfiguration
@ImportRuntimeHints(PrometheusHints.class)
public class PrometheusConnectorsAutoConfiguration {

  @Bean
  Connector prometheusConnector() {
    return new PrometheusConnector(Clock.systemUTC());
  }
}
