package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import java.util.List;
import java.util.Objects;

/** The connectors of this module, one per type: what the {@code .boot} configuration registers. */
public final class Connectors {

  private Connectors() {}

  public static Connector pinecone() {
    return new VectorConnector(new PineconeBackend());
  }

  public static Connector qdrant() {
    return new VectorConnector(new QdrantBackend());
  }

  public static Connector milvus() {
    return new VectorConnector(new MilvusBackend());
  }

  public static Connector weaviate() {
    return new VectorConnector(new WeaviateBackend());
  }

  public static Connector chroma() {
    return new VectorConnector(new ChromaBackend());
  }

  public static Connector algolia() {
    return new SearchConnector(new AlgoliaBackend());
  }

  public static Connector meilisearch() {
    return new SearchConnector(new MeilisearchBackend());
  }

  public static Connector typesense() {
    return new SearchConnector(new TypesenseBackend());
  }

  /** Azure AI Search without the core (tests, tools): Entra ID tokens of the real endpoints, made on first use. */
  public static Connector azureAiSearch() {
    return new SearchConnector(new AzureSearchBackend());
  }

  /** Azure AI Search with the core's {@link CloudCredentials} for its Entra ID sign-ins. */
  public static Connector azureAiSearch(CloudCredentials cloud) {
    return new SearchConnector(new AzureSearchBackend(Objects.requireNonNull(cloud, "cloud")));
  }

  /** Every search engine type, for tests and tools. */
  public static List<Connector> searchEngines() {
    return List.of(algolia(), meilisearch(), typesense(), azureAiSearch());
  }

  /** Every vector database type, for tests and tools. */
  public static List<Connector> vectorDatabases() {
    return List.of(pinecone(), qdrant(), milvus(), weaviate(), chroma());
  }
}
