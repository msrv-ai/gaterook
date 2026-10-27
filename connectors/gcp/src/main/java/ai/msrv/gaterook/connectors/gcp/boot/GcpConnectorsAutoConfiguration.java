package ai.msrv.gaterook.connectors.gcp.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.gcp.GcpConnector;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the GCP connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched
 * on and off at run time by {@code connector_type.enabled}.
 *
 * <p>The core's {@link CloudCredentials} gives the Google token (and, in development builds, the test endpoints of
 * Google's APIs); a context without the core — the catalog's tests — gets credentials for the real endpoints.
 */
@AutoConfiguration
@ImportRuntimeHints(GcpHints.class)
public class GcpConnectorsAutoConfiguration {

  @Bean
  Connector gcpConnector(ObjectProvider<CloudCredentials> cloud) {
    return new GcpConnector(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }
}
