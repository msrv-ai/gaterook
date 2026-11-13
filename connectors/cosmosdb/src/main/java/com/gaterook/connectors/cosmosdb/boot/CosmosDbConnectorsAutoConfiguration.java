package com.gaterook.connectors.cosmosdb.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.cosmosdb.CosmosDbConnector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;

/**
 * Registers the Azure Cosmos DB connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own
 * {@code AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is
 * switched on and off at run time by {@code connector_type.enabled}.
 *
 * <p>Plain HTTP to the emulator on this computer is a development relaxation: the core says whether this is a release
 * in {@code gateway.release} (its {@code BuildFlags}, which this module can't see), and without that property the build
 * counts as a release, which reaches Cosmos DB over HTTPS only.
 */
@AutoConfiguration
@ImportRuntimeHints(CosmosDbHints.class)
public class CosmosDbConnectorsAutoConfiguration {

  @Bean
  Connector cosmosDbConnector(Environment environment) {
    return new CosmosDbConnector(plainLoopback(environment));
  }

  static boolean plainLoopback(Environment environment) {
    return !environment.getProperty("gateway.release", Boolean.class, true);
  }
}
