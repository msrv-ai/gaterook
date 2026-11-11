package ai.msrv.gaterook.connectors.athena.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.athena.AthenaConnector;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Athena connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched on
 * and off at run time by {@code connector_type.enabled}.
 *
 * <p>The core's {@link CloudCredentials} gives the AWS credentials (and, in development builds, the test endpoints
 * {@code aws.athena} and STS); a context without the core gets credentials for the real endpoints.
 */
@AutoConfiguration
@ImportRuntimeHints(AthenaHints.class)
public class AthenaConnectorsAutoConfiguration {

  @Bean
  Connector athenaConnector(ObjectProvider<CloudCredentials> cloud) {
    return new AthenaConnector(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }
}
