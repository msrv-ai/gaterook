package ai.msrv.gaterook.connectors.influxdb;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The connection form's schema: what each server's settings look like, and the tools' inputs. */
class InfluxConfigSchemaTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SchemaResources SCHEMAS = SchemaResources.of(InfluxDbConnector.class, "influxdb");
  private static final Schema CONFIG = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SCHEMAS.load("config"));

  private static JsonNode json(String text) {
    return JSON.readTree(text);
  }

  @Test
  void eachServersSettingsPass() {
    for (String config : List.of(
        "{\"url\":\"https://influxdb.example.com:8181\",\"version\":\"3\",\"auth\":{\"method\":\"token\","
            + "\"token\":\"stored\"},\"databases\":[\"telemetry\",\"iot_*\"]}",
        "{\"url\":\"https://influxdb.example.com:8086\",\"version\":\"2\",\"organization\":\"acme\",\"auth\":"
            + "{\"method\":\"token\",\"token\":\"stored\"}}",
        "{\"url\":\"https://influxdb.example.com:8086\",\"version\":\"1\",\"auth\":{\"method\":\"password\","
            + "\"username\":\"reader\",\"password\":\"stored\"},\"advanced\":{\"maxRows\":500,\"maxResponseKb\":256,"
            + "\"maxWritePoints\":100,\"caCertificate\":\"-----BEGIN CERTIFICATE-----\"}}",
        "{\"url\":\"http://localhost:8181\",\"version\":\"3\",\"auth\":{\"method\":\"none\"}}")) {
      assertThat(CONFIG.validate(json(config))).as(config).isEmpty();
    }
  }

  @Test
  void incompleteSettingsDont() {
    for (String config : List.of(
        "{\"version\":\"3\",\"auth\":{\"method\":\"token\",\"token\":\"stored\"}}",
        "{\"url\":\"influxdb.example.com\",\"version\":\"3\",\"auth\":{\"method\":\"token\",\"token\":\"stored\"}}",
        "{\"url\":\"https://influxdb.example.com\",\"version\":\"4\",\"auth\":{\"method\":\"token\",\"token\":\"s\"}}",
        "{\"url\":\"https://influxdb.example.com\",\"version\":\"3\",\"auth\":{\"method\":\"token\"}}",
        "{\"url\":\"https://influxdb.example.com\",\"version\":\"1\",\"auth\":{\"method\":\"password\","
            + "\"password\":\"stored\"}}",
        "{\"url\":\"https://influxdb.example.com\",\"version\":\"1\",\"auth\":{\"method\":\"password\","
            + "\"username\":\"a:b\",\"password\":\"stored\"}}",
        "{\"url\":\"https://influxdb.example.com\",\"version\":\"3\",\"auth\":{\"method\":\"token\",\"token\":\"s\"},"
            + "\"advanced\":{\"maxRows\":0}}",
        "{\"url\":\"https://influxdb.example.com\",\"version\":\"3\",\"auth\":{\"method\":\"token\",\"token\":\"s\"},"
            + "\"databases\":[\"a\\u0000b\"]}",
        "{\"url\":\"https://influxdb.example.com\",\"version\":\"3\",\"auth\":{\"method\":\"token\",\"token\":\"s\"},"
            + "\"bucket\":\"x\"}")) {
      assertThat(CONFIG.validate(json(config))).as(config).isNotEmpty();
    }
  }

  @Test
  void secretsAreWriteOnlyAndProdNeedsCredentials() {
    JsonNode schema = SCHEMAS.load("config");
    String json = schema.toString();
    assertThat(schema.at("/properties/auth/allOf/0/then/properties/token/writeOnly").asBoolean()).isTrue();
    assertThat(schema.at("/properties/auth/allOf/1/then/properties/password/writeOnly").asBoolean()).isTrue();
    assertThat(schema.at("/properties/auth/properties/method/x-forbidden-env-values/prod").toString())
        .isEqualTo("[\"none\"]");
    assertThat(json).doesNotContain("x-person-only");
  }

  @Test
  void everySchemaIsValidJsonSchema2020AndEveryToolKeepsTheCatalogsRules() {
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Schema meta = registry.getSchema(com.networknt.schema.SchemaLocation.of(
        "https://json-schema.org/draft/2020-12/schema"));
    try (InfluxDbConnector connector = new InfluxDbConnector()) {
      assertThat(meta.validate(connector.manifest().configSchema())).isEmpty();
      for (var tool : connector.manifest().tools()) {
        assertThat(meta.validate(tool.inputSchema())).as(tool.name()).isEmpty();
        assertThat(tool.name()).startsWith("influxdb_");
        assertThat(tool.title()).isNotBlank().isNotEqualTo(tool.name());
        assertThat(tool.inputSchema().path("additionalProperties").asBoolean(true)).as(tool.name()).isFalse();
        assertThat(tool.inputSchema().at("/properties/connection").toString()).isEqualTo("{\"type\":\"string\"}");
      }
      assertThat(connector.manifest().description()).endsWith(".");
    }
  }

  @Test
  void theWriteToolTakesANoteForTheApprover() {
    Schema write = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(SCHEMAS.load("influxdb_write.input"));
    assertThat(write.validate(json("{\"connection\":\"c\",\"database\":\"metrics\",\"lines\":\"m v=1\","
        + "\"precision\":\"s\",\"note\":\"backfill\"}"))).isEmpty();
    assertThat(write.validate(json("{\"connection\":\"c\",\"database\":\"metrics\",\"lines\":\"m v=1\","
        + "\"reason\":\"x\"}"))).isNotEmpty();
    Schema query = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(SCHEMAS.load("influxdb_query.input"));
    assertThat(query.validate(json("{\"connection\":\"c\",\"database\":\"metrics\",\"query\":\"SELECT 1\","
        + "\"parameters\":{\"host\":\"web-1\",\"n\":5,\"on\":true}}"))).isEmpty();
    assertThat(query.validate(json("{\"connection\":\"c\",\"query\":\"SELECT 1\",\"parameters\":{\"h\":{}}}")))
        .isNotEmpty();
  }
}
