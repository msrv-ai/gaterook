package ai.msrv.gaterook.connectors.search.boot;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.search.Connectors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the vector database and search engine connectors (ARCHITECTURE.md §2.3, A13): explicit beans listed in
 * this module's own {@code AutoConfiguration.imports}, resolved by Spring AOT at build time. No {@code @Conditional*}:
 * a type is switched on and off at run time by {@code connector_type.enabled}.
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
  Connector azureAiSearchConnector() {
    return Connectors.azureAiSearch();
  }
}
