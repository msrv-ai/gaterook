package ai.msrv.gaterook.connectors.search;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The connection forms' schemas: what each store's settings look like, and the two tool families' inputs. */
class ConfigSchemaTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SchemaResources SCHEMAS = SchemaResources.of(VectorConnector.class, "search");
  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private static Schema config(String type) {
    return REGISTRY.getSchema(SCHEMAS.load(type + ".config"));
  }

  private static final Map<String, List<String>> GOOD = Map.of(
      "qdrant", List.of("{\"url\":\"https://xyz.cloud.qdrant.io:6333\",\"auth\":{\"method\":\"apiKey\",\"apiKey\":\"s\"},"
          + "\"collections\":[\"docs\",\"kb_*\"]}", "{\"url\":\"http://localhost:6333\",\"auth\":{\"method\":\"none\"},"
          + "\"advanced\":{\"caCertificate\":\"-----BEGIN CERTIFICATE-----\"}}"),
      "weaviate", List.of("{\"url\":\"https://abc.weaviate.cloud\",\"auth\":{\"method\":\"apiKey\",\"apiKey\":\"s\"}}"),
      "chroma", List.of("{\"url\":\"https://api.trychroma.com\",\"tenant\":\"t-1\",\"database\":\"prod\",\"auth\":"
          + "{\"method\":\"token\",\"token\":\"s\"}}", "{\"url\":\"http://localhost:8000\",\"auth\":{\"method\":\"none\"}}"),
      "milvus", List.of("{\"url\":\"https://in03-x.cloud.zilliz.com\",\"auth\":{\"method\":\"token\",\"token\":\"s\"}}",
          "{\"url\":\"http://localhost:19530\",\"database\":\"kb\",\"auth\":{\"method\":\"password\",\"username\":"
              + "\"reader\",\"password\":\"s\"}}"),
      "pinecone", List.of("{\"apiKey\":\"s\",\"collections\":[\"docs\"]}"),
      "meilisearch", List.of("{\"url\":\"https://ms-1.meilisearch.io\",\"auth\":{\"method\":\"apiKey\",\"apiKey\":\"s\"},"
          + "\"indexes\":[\"movies\"]}"),
      "typesense", List.of("{\"url\":\"https://xyz.a1.typesense.net\",\"apiKey\":\"s\"}"),
      "algolia", List.of("{\"applicationId\":\"ABCD1234EF\",\"apiKey\":\"s\",\"indexes\":[\"products\"]}"),
      "azure-ai-search", List.of("{\"endpoint\":\"https://contoso.search.windows.net\",\"apiKey\":\"s\"}",
          "{\"endpoint\":\"https://gov-search.search.azure.us/\",\"apiKey\":\"s\",\"indexes\":[\"hotels\"]}",
          "{\"endpoint\":\"https://contoso.search.windows.net\",\"auth\":\"apiKey\",\"apiKey\":\"s\"}",
          "{\"endpoint\":\"https://contoso.search.windows.net\",\"auth\":\"entraId\",\"credentials\":"
              + "{\"source\":\"cli\"},\"trustedCli\":\"" + "ab".repeat(32) + "\"}",
          "{\"endpoint\":\"https://cn-search.search.azure.cn\",\"auth\":\"entraId\",\"tenantId\":"
              + "\"contoso.partner.onmschina.cn\",\"credentials\":{\"source\":\"clientSecret\",\"clientId\":"
              + "\"0a1b2c3d-1111-4222-8333-444455556666\",\"clientSecret\":\"s\"},\"indexes\":[\"hotels\"]}",
          "{\"endpoint\":\"https://contoso.search.windows.net\",\"auth\":\"entraId\",\"credentials\":"
              + "{\"source\":\"deviceCode\"},\"apiKey\":\"left-from-the-key\"}"));

  private static final Map<String, List<String>> BAD = Map.of(
      "qdrant", List.of("{\"auth\":{\"method\":\"none\"}}", "{\"url\":\"qdrant:6333\",\"auth\":{\"method\":\"none\"}}",
          "{\"url\":\"https://q.example.com\",\"auth\":{\"method\":\"apiKey\"}}",
          "{\"url\":\"https://q.example.com\",\"auth\":{\"method\":\"none\"},\"x\":1}"),
      "chroma", List.of("{\"url\":\"https://c.example.com\",\"tenant\":\"a/b\",\"auth\":{\"method\":\"none\"}}"),
      "milvus", List.of("{\"url\":\"https://m.example.com\",\"auth\":{\"method\":\"password\",\"password\":\"s\"}}",
          "{\"url\":\"https://m.example.com\",\"database\":\"a-b\",\"auth\":{\"method\":\"none\"}}"),
      "pinecone", List.of("{}", "{\"apiKey\":\"s\",\"advanced\":{\"controlPlane\":\"http://localhost:5080\"}}"),
      "typesense", List.of("{\"url\":\"https://t.example.com\"}"),
      "algolia", List.of("{\"applicationId\":\"AB-CD\",\"apiKey\":\"s\"}"),
      "azure-ai-search", List.of("{\"endpoint\":\"https://contoso.example.com\",\"apiKey\":\"s\"}",
          "{\"endpoint\":\"http://contoso.search.windows.net\",\"apiKey\":\"s\"}",
          "{\"endpoint\":\"https://contoso.search.windows.net\"}",
          "{\"endpoint\":\"https://contoso.search.windows.net\",\"auth\":\"entraId\"}",
          "{\"endpoint\":\"https://contoso.search.windows.net\",\"auth\":\"entraId\",\"credentials\":"
              + "{\"source\":\"clientSecret\",\"clientSecret\":\"s\"}}",
          "{\"endpoint\":\"https://contoso.search.windows.net\",\"auth\":\"managedIdentity\",\"apiKey\":\"s\"}",
          "{\"endpoint\":\"https://contoso.search.windows.net\",\"apiKey\":\"s\",\"cloud\":\"AzureUSGovernment\"}"));

  @Test
  void eachStoresSettingsPassAndIncompleteOnesDont() {
    GOOD.forEach((type, configs) -> configs.forEach(c -> assertThat(config(type).validate(JSON.readTree(c)))
        .as(type + " " + c).isEmpty()));
    BAD.forEach((type, configs) -> configs.forEach(c -> assertThat(config(type).validate(JSON.readTree(c)))
        .as(type + " " + c).isNotEmpty()));
  }

  @Test
  void secretsAreWriteOnlyAndProdNeedsCredentials() {
    List<String> pointers = new ArrayList<>();
    for (String type : GOOD.keySet()) {
      JsonNode schema = SCHEMAS.load(type + ".config");
      collect(schema, "", pointers);
      String text = schema.toString();
      assertThat(text).as(type).doesNotContain("Pinecone Local").doesNotContain("emulator");
      JsonNode method = schema.at("/properties/auth/properties/method");
      if (!method.isMissingNode() && method.path("enum").toString().contains("none")) {
        assertThat(method.at("/x-forbidden-env-values/prod").toString()).as(type).isEqualTo("[\"none\"]");
      }
    }
    assertThat(pointers).isNotEmpty().allSatisfy(p -> assertThat(p).endsWith("writeOnly"));
  }

  /** Every property marked as a gateway secret, by the pointer of its writeOnly flag. */
  private static void collect(JsonNode node, String path, List<String> out) {
    if (node.isObject()) {
      if (node.has("x-gateway-secret")) {
        assertThat(node.path("writeOnly").asBoolean()).as(path).isTrue();
        out.add(path + "/writeOnly");
      }
      node.properties().forEach(e -> collect(e.getValue(), path + "/" + e.getKey(), out));
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        collect(node.get(i), path + "/" + i, out);
      }
    }
  }

  @Test
  void everySchemaIsValidAndEveryToolKeepsTheCatalogsRules() throws Exception {
    Schema meta = REGISTRY.getSchema(com.networknt.schema.SchemaLocation.of(
        "https://json-schema.org/draft/2020-12/schema"));
    List<Connector> all = new ArrayList<>(Connectors.vectorDatabases());
    all.addAll(Connectors.searchEngines());
    assertThat(all).extracting(c -> c.manifest().id()).containsExactly("pinecone", "qdrant", "milvus", "weaviate",
        "chroma", "algolia", "meilisearch", "typesense", "azure-ai-search");
    for (Connector connector : all) {
      try (connector) {
        assertThat(meta.validate(connector.manifest().configSchema())).as(connector.manifest().id()).isEmpty();
        assertThat(connector.manifest().description()).endsWith(".");
        String prefix = connector instanceof VectorConnector ? "vector_" : "search_";
        for (var tool : connector.manifest().tools()) {
          assertThat(meta.validate(tool.inputSchema())).as(tool.name()).isEmpty();
          assertThat(tool.name()).startsWith(prefix);
          assertThat(tool.title()).isNotBlank().isNotEqualTo(tool.name());
          assertThat(tool.inputSchema().path("additionalProperties").asBoolean(true)).as(tool.name()).isFalse();
          assertThat(tool.inputSchema().at("/properties/connection").toString()).isEqualTo("{\"type\":\"string\"}");
        }
      }
    }
  }

  @Test
  void theChangeToolsTakeANoteForTheApprover() {
    Schema upsert = REGISTRY.getSchema(SCHEMAS.load("vector_upsert.input"));
    assertThat(upsert.validate(JSON.readTree("{\"connection\":\"c\",\"collection\":\"docs\",\"records\":[{\"id\":1,"
        + "\"vector\":[0.1,0.2],\"metadata\":{\"a\":1}},{\"id\":\"b\",\"vector\":{\"text\":[0.5]}}],\"note\":\"x\"}")))
        .isEmpty();
    assertThat(upsert.validate(JSON.readTree("{\"connection\":\"c\",\"collection\":\"docs\",\"records\":[{\"vector\":"
        + "[1]}]}"))).isNotEmpty();
    Schema index = REGISTRY.getSchema(SCHEMAS.load("search_index_documents.input"));
    assertThat(index.validate(JSON.readTree("{\"connection\":\"c\",\"index\":\"movies\",\"documents\":[{\"id\":1}],"
        + "\"mode\":\"merge\",\"note\":\"fix\"}"))).isEmpty();
    assertThat(index.validate(JSON.readTree("{\"connection\":\"c\",\"index\":\"movies\",\"documents\":[{}]}")))
        .isNotEmpty();
  }
}
