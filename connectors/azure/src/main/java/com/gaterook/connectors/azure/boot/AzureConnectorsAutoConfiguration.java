package com.gaterook.connectors.azure.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.azure.AzureConnector;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Azure connector (ARCHITECTURE.md §2.3): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched
 * on and off at run time by {@code connector_type.enabled}.
 *
 * <p>The core's {@link CloudCredentials} gives the Entra ID tokens (and, in development builds, the test endpoints of
 * the sign-in, Resource Manager and Log Analytics); a context without the core — the catalog's tests — gets
 * credentials for the real endpoints.
 */
@AutoConfiguration
@ImportRuntimeHints(AzureHints.class)
public class AzureConnectorsAutoConfiguration {

  @Bean
  Connector azureConnector(ObjectProvider<CloudCredentials> cloud) {
    return new AzureConnector(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }
}
