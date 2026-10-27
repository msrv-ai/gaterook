package ai.msrv.gaterook.connectors.aws.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.aws.AwsConnector;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the AWS connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched
 * on and off at run time by {@code connector_type.enabled}.
 *
 * <p>The core's {@link CloudCredentials} gives the AWS credentials (and, in development builds, the test endpoints of
 * STS, Cloud Control and CloudWatch); a context without the core — the catalog's tests — gets credentials for the
 * real endpoints.
 */
@AutoConfiguration
@ImportRuntimeHints(AwsHints.class)
public class AwsConnectorsAutoConfiguration {

  @Bean
  Connector awsConnector(ObjectProvider<CloudCredentials> cloud) {
    return new AwsConnector(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }
}
