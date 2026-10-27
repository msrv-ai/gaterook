package ai.msrv.gaterook.connectors.http.boot;

import ai.msrv.gaterook.connectors.http.HttpConnector;
import ai.msrv.gaterook.connectors.support.TemplateImporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the HTTP API connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own {@code
 * AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is switched
 * on and off at run time by {@code connector_type.enabled}. Its OpenAPI importer is a bean of its own, so the core's
 * {@code POST /api/templates/openapi/parse} finds it without knowing the connector's classes.
 */
@AutoConfiguration
@ImportRuntimeHints(HttpHints.class)
public class HttpConnectorsAutoConfiguration {

  @Bean
  HttpConnector httpConnector() {
    return new HttpConnector();
  }

  @Bean
  TemplateImporter openApiImporter(HttpConnector httpConnector) {
    return httpConnector.openApiImporter();
  }
}
