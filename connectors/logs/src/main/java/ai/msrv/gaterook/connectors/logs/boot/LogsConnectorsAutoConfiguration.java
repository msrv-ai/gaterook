package ai.msrv.gaterook.connectors.logs.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.logs.LogsConnector;
import ai.msrv.gaterook.connectors.logs.BackendConnector;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Logs connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched
 * on and off at run time by {@code connector_type.enabled}.
 *
 * <p>The core's {@link CloudCredentials} gives GCP and AWS credentials (and, in development builds, the test
 * endpoints of their APIs); a context without the core — the catalog's tests — gets credentials for the real
 * endpoints.
 */
@AutoConfiguration
@ImportRuntimeHints(LogsHints.class)
public class LogsConnectorsAutoConfiguration {

  @Bean
  Connector logsConnector(ObjectProvider<CloudCredentials> cloud) {
    return new LogsConnector(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }

  @Bean
  Connector gcpLoggingConnector(ObjectProvider<CloudCredentials> cloud) {
    return backend("gcp-logging", cloud);
  }

  @Bean
  Connector lokiConnector(ObjectProvider<CloudCredentials> cloud) {
    return backend("loki", cloud);
  }

  @Bean
  Connector cloudWatchLogsConnector(ObjectProvider<CloudCredentials> cloud) {
    return backend("cloudwatch", cloud);
  }

  @Bean
  Connector openSearchConnector(ObjectProvider<CloudCredentials> cloud) {
    return backend("opensearch", cloud);
  }

  private static Connector backend(String type, ObjectProvider<CloudCredentials> cloud) {
    return new BackendConnector(type, cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }
}
