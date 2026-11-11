package ai.msrv.gaterook.connectors.prometheus;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connectors.support.PersonOnly;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The connection form and the tools' inputs take what the connector reads, and refuse the rest. */
class SchemasTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources SCHEMAS = SchemaResources.of(PrometheusConnector.class, "prometheus");
  private static final Schema CONFIG = REGISTRY.getSchema(SCHEMAS.load("config"));

  private static boolean valid(Schema schema, String json) {
    return schema.validate(JSON.readTree(json)).isEmpty();
  }

  @Test
  void theFormTakesEachSignInWithOnlyItsOwnCredentials() {
    for (String ok : List.of(
        "{\"url\": \"https://prometheus.example.com\"}",
        "{\"url\": \"https://prometheus.example.com\", \"auth\": {\"mode\": \"none\"}}",
        "{\"url\": \"https://mimir.example.com/prometheus\", \"tenant\": \"team-a|team-b\", \"auth\": {\"mode\": "
            + "\"basic\", \"username\": \"reader\", \"secret\": \"stored\"}}",
        "{\"url\": \"http://127.0.0.1:9090\", \"auth\": {\"mode\": \"bearer\", \"secret\": \"stored\"}, "
            + "\"advanced\": {\"maxSeries\": 200, \"maxPoints\": 5000, \"maxResponseMb\": 8}}",
        "{\"url\": \"https://prometheus.example.com\", \"caCertificate\": \"-----BEGIN CERTIFICATE-----\"}")) {
      assertThat(CONFIG.validate(JSON.readTree(ok))).as(ok).isEmpty();
    }
    for (String bad : List.of("{}",
        "{\"url\": \"https://prometheus.example.com/?x=1\"}",
        "{\"url\": \"https://reader:pw@prometheus.example.com\"}",
        "{\"url\": \"prometheus.example.com\"}",
        "{\"url\": \"https://prometheus.example.com\", \"tenant\": \"team a\"}",
        "{\"url\": \"https://prometheus.example.com\", \"auth\": {\"mode\": \"basic\", \"secret\": \"stored\"}}",
        "{\"url\": \"https://prometheus.example.com\", \"auth\": {\"mode\": \"basic\", \"username\": \"a:b\", "
            + "\"secret\": \"stored\"}}",
        "{\"url\": \"https://prometheus.example.com\", \"auth\": {\"mode\": \"bearer\"}}",
        "{\"url\": \"https://prometheus.example.com\", \"auth\": {\"mode\": \"oauth\"}}",
        "{\"url\": \"https://prometheus.example.com\", \"advanced\": {\"maxPoints\": 20000}}",
        "{\"url\": \"https://prometheus.example.com\", \"advanced\": {\"maxResponseMb\": 0}}",
        "{\"url\": \"https://prometheus.example.com\", \"metrics\": [\"up\"]}")) {
      assertThat(CONFIG.validate(JSON.readTree(bad))).as(bad).isNotEmpty();
    }
  }

  @Test
  void secretsAreWriteOnlyAndTheCaIsThePersonsOwnDecision() {
    JsonNode schema = SCHEMAS.load("config");
    for (JsonNode branch : schema.at("/properties/auth/allOf")) {
      JsonNode secret = branch.at("/then/properties/secret");
      assertThat(secret.path("writeOnly").asBoolean()).isTrue();
      assertThat(secret.path("x-gateway-secret").asString()).isEqualTo("value");
    }
    assertThat(PersonOnly.templates(schema)).containsExactly("/caCertificate");
    assertThat(schema.path("x-card").path("description").asString()).isEqualTo(new PrometheusConnector(
        Clock.systemUTC()).manifest().description());
  }

  @Test
  void theToolsTakeTheirDocumentedArgumentsAndNothingElse() {
    Map<String, String> ok = Map.of(
        "prometheus_query", "{\"connection\": \"p\", \"query\": \"up\", \"time\": \"-5m\", \"limit\": 10}",
        "prometheus_query_range", "{\"connection\": \"p\", \"query\": \"up\", \"start\": \"-1h\", \"end\": \"now\", "
            + "\"step\": \"30s\"}",
        "prometheus_series", "{\"connection\": \"p\", \"match\": [\"up\"], \"start\": \"-1h\"}",
        "prometheus_labels", "{\"connection\": \"p\"}",
        "prometheus_label_values", "{\"connection\": \"p\", \"label\": \"job\", \"match\": [\"up\"]}",
        "prometheus_metadata", "{\"connection\": \"p\", \"metric\": \"up\"}",
        "prometheus_targets", "{\"connection\": \"p\", \"state\": \"dropped\", \"scrapePool\": \"node\"}",
        "prometheus_rules", "{\"connection\": \"p\", \"type\": \"record\", \"names\": [\"a\"], \"groups\": [\"g\"]}",
        "prometheus_alerts", "{\"connection\": \"p\", \"state\": \"pending\"}");
    try (PrometheusConnector connector = new PrometheusConnector(Clock.systemUTC())) {
      for (ToolDefinition t : connector.manifest().tools()) {
        Schema input = REGISTRY.getSchema(t.inputSchema());
        assertThat(valid(input, ok.get(t.name()))).as(t.name()).isTrue();
        String extra = ok.get(t.name()).replaceFirst("\\{", "{\"admin\": true, ");
        assertThat(valid(input, extra)).as(t.name() + " with an unknown argument").isFalse();
      }
    }
    Schema range = REGISTRY.getSchema(SCHEMAS.load("prometheus_query_range.input"));
    assertThat(valid(range, "{\"connection\": \"p\"}")).isFalse();
    assertThat(valid(range, "{\"connection\": \"p\", \"query\": \"" + "x".repeat(8_193) + "\"}")).isFalse();
    Schema series = REGISTRY.getSchema(SCHEMAS.load("prometheus_series.input"));
    assertThat(valid(series, "{\"connection\": \"p\", \"match\": []}")).isFalse();
  }
}
