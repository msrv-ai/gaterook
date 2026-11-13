package com.gaterook.connectors.search.boot;

import com.gaterook.connector.api.Connector;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudEndpoints;
import com.gaterook.connectors.search.Connectors;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the vector database and search engine connectors (ARCHITECTURE.md §2.3, A13): explicit beans listed in
 * this module's own {@code AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}:
 * a type is switched on and off at run time by {@code connector_type.enabled}.
 *
 * <p>The core's {@link CloudCredentials} gives Azure AI Search its Entra ID tokens (and, in development builds, the
 * test sign-in and search endpoints); a context without the core gets credentials for the real endpoints.
 */
@AutoConfiguration
@ImportRuntimeHints(SearchHints.class)
public class SearchConnectorsAutoConfiguration {

  @Bean
  Connector pineconeConnector() {
    return Connectors.pinecone();
  }

  @Bean
  Connector qdrantConnector() {
    return Connectors.qdrant();
  }

  @Bean
  Connector milvusConnector() {
    return Connectors.milvus();
  }

  @Bean
  Connector weaviateConnector() {
    return Connectors.weaviate();
  }

  @Bean
  Connector chromaConnector() {
    return Connectors.chroma();
  }

  @Bean
  Connector algoliaConnector() {
    return Connectors.algolia();
  }

  @Bean
  Connector meilisearchConnector() {
    return Connectors.meilisearch();
  }

  @Bean
  Connector typesenseConnector() {
    return Connectors.typesense();
  }

  @Bean
  Connector azureAiSearchConnector(ObjectProvider<CloudCredentials> cloud) {
    return Connectors.azureAiSearch(cloud.getIfAvailable(() -> new CloudCredentials(CloudEndpoints.PRODUCTION,
        Clock.systemUTC())));
  }
}
