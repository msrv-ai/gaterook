package ai.msrv.gaterook.connectors.kubernetes.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.kubernetes.KubernetesConnector;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the Kubernetes connector (ARCHITECTURE.md §2.3, A13): an explicit bean listed in this module's own
 * {@code AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}: the type is
 * switched on and off at run time by {@code connector_type.enabled}.
 */
@AutoConfiguration
@ImportRuntimeHints(KubernetesHints.class)
public class KubernetesConnectorsAutoConfiguration {

  @Bean
  Connector kubernetesConnector() {
    return new KubernetesConnector();
  }
}
