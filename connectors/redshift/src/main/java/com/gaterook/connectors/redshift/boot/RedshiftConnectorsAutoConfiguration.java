package com.gaterook.connectors.redshift.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.redshift.RedshiftConnector;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Redshift connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched on
 * and off at run time by {@code connector_type.enabled}.
 *
 * <p>The core's {@link CloudCredentials} gives the AWS credentials (and, in development builds, the test endpoints
 * {@code aws.redshift-data} and STS); a context without the core gets credentials for the real endpoints.
 */
@AutoConfiguration
@ImportRuntimeHints(RedshiftHints.class)
public class RedshiftConnectorsAutoConfiguration {

  @Bean
  Connector redshiftConnector(ObjectProvider<CloudCredentials> cloud) {
    return new RedshiftConnector(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }
}
