package ai.msrv.gaterook.connectors.cloudflare.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.cloudflare.CloudflareConnector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Cloudflare connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched on
 * and off at run time by {@code connector_type.enabled}. The connector needs nothing from the core: it reaches only
 * api.cloudflare.com.
 */
@AutoConfiguration
@ImportRuntimeHints(CloudflareHints.class)
public class CloudflareConnectorsAutoConfiguration {

  @Bean
  Connector cloudflareConnector() {
    return new CloudflareConnector();
  }
}
