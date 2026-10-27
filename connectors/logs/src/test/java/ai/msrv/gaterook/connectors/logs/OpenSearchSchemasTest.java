package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The forms and MCP schemas default to the full API while preserving explicit scoped connections. */
class OpenSearchSchemasTest {

  private static final SchemaResources RESOURCES = SchemaResources.of(LogsConnector.class, "logs");
  private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void fullApiDefaultsCanOmitLogIndicesWhileExplicitScopedConfigurationRequiresThem() {
    var schema = SCHEMAS.getSchema(RESOURCES.load("config"));
    assertThat(schema.validate(JSON.readTree("""
        {"backend":"opensearch","url":"https://search.example.test:9200","apiAccess":"full",
         "auth":{"mode":"none"}}"""))).isEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"backend":"opensearch","url":"https://search.example.test:9200","auth":{"mode":"none"}}""")))
        .as("Omitted API access defaults to full").isEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"backend":"opensearch","url":"https://search.example.test:9200","apiAccess":"logs",
         "auth":{"mode":"none"}}"""))).as("Explicit scoped access requires index patterns").isNotEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"backend":"opensearch","url":"https://search.example.test:9200","apiAccess":"logs","indices":["logs-*"],
         "auth":{"mode":"none"}}"""))).isEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"backend":"opensearch","url":"https://search.example.test:9200","apiAccess":"logs","indices":["*"],
         "auth":{"mode":"none"}}"""))).as("Scoped access cannot select every index").isNotEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"backend":"opensearch","url":"https://search.example.test:9200","apiAccess":"full","indices":["*"],
         "auth":{"mode":"none"}}"""))).isEmpty();
    assertThat(schema.validate(JSON.readTree("""
        {"backend":"opensearch","url":"https://search.example.test:9200","indices":["*"],
         "auth":{"mode":"none"}}"""))).as("Default full access accepts all indices").isEmpty();
  }

  @Test
  void restSchemasAcceptDslNdjsonAndTypedQueryParameters() {
    for (String tool : new String[] {"opensearch_query", "opensearch_request"}) {
      var schema = SCHEMAS.getSchema(RESOURCES.load(tool + ".input"));
      assertThat(schema.validate(JSON.readTree("""
          {"connection":"search","method":"POST","path":"/products/_search",
           "query":{"pretty":true,"size":10,"filter_path":["hits","aggregations"]},
           "body":{"query":{"match_all":{}},"aggs":{"categories":{"terms":{"field":"category"}}}}}""")))
          .as(tool + " JSON DSL").isEmpty();
      assertThat(schema.validate(JSON.readTree("""
          {"connection":"search","method":"POST","path":"/_msearch",
           "contentType":"application/x-ndjson","body":"{}\\n{\\"query\\":{\\"match_all\\":{}}}\\n"}""")))
          .as(tool + " NDJSON").isEmpty();
      assertThat(schema.validate(JSON.readTree("""
          {"connection":"search","path":"https://elsewhere.example.test/_search"}""")))
          .as("Only local absolute paths").isNotEmpty();
    }
    var mutation = JSON.readTree("""
        {"connection":"search","method":"PUT","path":"/products/_settings",
         "body":{"index":{"number_of_replicas":0}}}""");
    assertThat(SCHEMAS.getSchema(RESOURCES.load("opensearch_query.input")).validate(mutation)).isNotEmpty();
    assertThat(SCHEMAS.getSchema(RESOURCES.load("opensearch_request.input")).validate(mutation)).isEmpty();
  }
}
