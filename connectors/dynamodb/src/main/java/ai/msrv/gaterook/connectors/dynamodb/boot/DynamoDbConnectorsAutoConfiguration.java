package ai.msrv.gaterook.connectors.dynamodb.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.dynamodb.DynamoDbConnector;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the DynamoDB connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched on
 * and off at run time by {@code connector_type.enabled}.
 *
 * <p>The core's {@link CloudCredentials} gives the AWS credentials (and, in development builds, the test endpoints
 * {@code aws.dynamodb} and STS); a context without the core gets credentials for the real endpoints.
 */
@AutoConfiguration
@ImportRuntimeHints(DynamoDbHints.class)
public class DynamoDbConnectorsAutoConfiguration {

  @Bean
  Connector dynamoDbConnector(ObjectProvider<CloudCredentials> cloud) {
    return new DynamoDbConnector(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }
}
