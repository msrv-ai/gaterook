package com.gaterook.connectors.neo4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.support.PersonOnly;
import com.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The connection form and tool schemas: valid JSON Schema 2020-12, and what they accept is what the code accepts. */
class ConfigSchemaTest {

  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources SCHEMAS = SchemaResources.of(Neo4jConnector.class, "neo4j");

  /** The sample configuration of the UI's form validation. */
  static final String SAMPLE = "{\"uri\": \"neo4j+s://graph.example.com:7687\", \"database\": \"neo4j\", "
      + "\"authentication\": {\"method\": \"password\", \"user\": \"gw_reader\", \"password\": \"example-only\"}}";

  @Test
  void everySchemaIsValidJsonSchema2020() {
    Schema meta = REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    for (String name : List.of("config", "neo4j_schema.input", "neo4j_query.input", "neo4j_execute.input")) {
      JsonNode schema = SCHEMAS.load(name);
      assertThat(meta.validate(schema)).as(name).isEmpty();
      REGISTRY.getSchema(SchemaLocation.of("urn:neo4j:test:" + name), schema).initializeValidators();
    }
  }

  @Test
  void theFormAcceptsBothSignInsAndRefusesWhatTheCodeRefuses() {
    Schema config = REGISTRY.getSchema(SCHEMAS.load("config"));
    assertThat(config.validate(TestCtx.JSON.readTree(SAMPLE))).isEmpty();
    assertThat(config.validate(TestCtx.JSON.readTree("{\"uri\": \"bolt+s://10.0.0.5\", \"authentication\": "
        + "{\"method\": \"token\", \"token\": \"eyJ.example\"}, \"caCertificate\": \"-----BEGIN CERTIFICATE-----\", "
        + "\"advanced\": {\"maxRows\": 50, \"transactionTimeoutMs\": 2000}}"))).isEmpty();
    for (String bad : List.of(
        "{\"uri\": \"neo4j+ssc://graph.example.com\", \"authentication\": {\"method\": \"token\", \"token\": \"t\"}}",
        "{\"uri\": \"http://graph.example.com\", \"authentication\": {\"method\": \"token\", \"token\": \"t\"}}",
        "{\"uri\": \"neo4j+s://u@graph.example.com\", \"authentication\": {\"method\": \"token\", \"token\": \"t\"}}",
        "{\"uri\": \"neo4j+s://graph.example.com/db\", \"authentication\": {\"method\": \"token\", \"token\": \"t\"}}",
        "{\"uri\": \"neo4j+s://graph.example.com\", \"authentication\": {\"method\": \"password\", \"user\": \"u\"}}",
        "{\"uri\": \"neo4j+s://graph.example.com\", \"authentication\": {\"method\": \"token\", \"user\": \"u\", "
            + "\"token\": \"t\"}}",
        "{\"uri\": \"neo4j+s://graph.example.com\", \"database\": \"x\", \"authentication\": {\"method\": \"token\", "
            + "\"token\": \"t\"}}",
        "{\"uri\": \"neo4j+s://graph.example.com\", \"authentication\": {\"method\": \"kerberos\"}}",
        "{\"uri\": \"neo4j+s://graph.example.com\"}")) {
      assertThat(config.validate(TestCtx.JSON.readTree(bad))).as(bad).isNotEmpty();
    }
  }

  @Test
  void theSampleIsAConnectionTheCodeTakes() throws ConnectorException {
    JsonNode sample = TestCtx.JSON.readTree(SAMPLE);
    Neo4jSettings s = Neo4jSettings.of(new ConnectionConfig("c", "graph", "neo4j", Env.DEV, 1, sample,
        Set.of(Neo4jSettings.PASSWORD), null));
    assertThat(s.address()).isEqualTo("graph.example.com:7687");
    // No setting of this connector records a person's trust decision.
    assertThat(PersonOnly.templates(SCHEMAS.load("config"))).isEmpty();
  }

  @Test
  void theSecretsAreWriteOnly() {
    JsonNode config = SCHEMAS.load("config");
    JsonNode password = config.at("/properties/authentication/allOf/0/then/properties/password");
    JsonNode token = config.at("/properties/authentication/allOf/1/then/properties/token");
    assertThat(password.path("writeOnly").asBoolean()).isTrue();
    assertThat(token.path("writeOnly").asBoolean()).isTrue();
    assertThat(config.at("/x-card/description").asString()).startsWith("Cypher reads in read-only transactions");
  }
}
