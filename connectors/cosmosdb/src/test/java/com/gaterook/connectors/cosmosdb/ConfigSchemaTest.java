package com.gaterook.connectors.cosmosdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The connection form and the tool schemas: valid JSON Schema 2020-12, and what they accept is what the code accepts. */
class ConfigSchemaTest {

  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources SCHEMAS = SchemaResources.of(CosmosDbConnector.class, "cosmosdb");

  @Test
  void everySchemaIsValidJsonSchema2020() {
    Schema meta = REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    for (String name : List.of("config", "cosmosdb_list_databases.input", "cosmosdb_list_containers.input",
        "cosmosdb_describe_container.input", "cosmosdb_query.input", "cosmosdb_read_item.input",
        "cosmosdb_execute.input")) {
      JsonNode schema = SCHEMAS.load(name);
      assertThat(meta.validate(schema)).as(name).isEmpty();
      REGISTRY.getSchema(SchemaLocation.of("urn:cosmosdb:test:" + name), schema).initializeValidators();
    }
  }

  @Test
  void theEndpointPatternAcceptsWhatTheConnectionAccepts() {
    Schema config = REGISTRY.getSchema(SCHEMAS.load("config"));
    for (String endpoint : List.of("https://acme.documents.azure.com", "https://acme.documents.azure.com/",
        "https://acme-westeurope.documents.azure.com:443", "https://acme.documents.azure.us",
        "https://acme.documents.azure.cn", "http://127.0.0.1:8081", "https://localhost:8081", "http://[::1]:8081",
        "http://acme.documents.azure.com", "https://acme.example.com", "https://acme.documents.azure.com/dbs",
        "https://acme.documents.azure.com:8443", "https://ab.documents.azure.com", "https://-acme.documents.azure.com",
        "https://acme.documents.azure.com.example.com", "http://10.0.0.5:8081", "http://localhost",
        "https://acme.documents.azure.com?x", "AccountEndpoint=https://acme.documents.azure.com/;AccountKey=x")) {
      boolean schema = config.validate(TestCtx.JSON.readTree("{\"endpoint\": \"" + endpoint + "\", \"auth\": "
          + "{\"method\": \"key\", \"keyKind\": \"readOnly\", \"accountKey\": \"k\"}}")).isEmpty();
      boolean code;
      try {
        CosmosEndpoint.parse(endpoint, true);
        code = true;
      } catch (CosmosEndpoint.Invalid e) {
        code = false;
      }
      assertThat(schema).as(endpoint).isEqualTo(code);
    }
  }

  @Test
  void theListsAndTheSignInAreWhatTheCodeReads() throws Exception {
    Schema config = REGISTRY.getSchema(SCHEMAS.load("config"));
    for (String db : List.of("app", "audit-*", "a b", "Zürich")) {
      assertThat(config.validate(with("\"databases\": [\"" + db + "\"]"))).as(db).isEmpty();
      CosmosSettings.of(cfg(with("\"databases\": [\"" + db + "\"]")), false);
    }
    for (String db : List.of("a/b", "a#b", "a?b", "a\\\\b", "")) {
      assertThat(config.validate(with("\"databases\": [\"" + db + "\"]"))).as(db).isNotEmpty();
    }
    for (String c : List.of("orders", "app/orders", "app/*", "*/events")) {
      assertThat(config.validate(with("\"containers\": [\"" + c + "\"]"))).as(c).isEmpty();
      CosmosSettings.of(cfg(with("\"containers\": [\"" + c + "\"]")), false);
    }
    for (String c : List.of("a/b/c", "app/", "/orders", "a#b")) {
      assertThat(config.validate(with("\"containers\": [\"" + c + "\"]"))).as(c).isNotEmpty();
    }
    assertThat(config.validate(TestCtx.JSON.readTree("{\"endpoint\": \"https://acme.documents.azure.com\", \"auth\": "
        + "{\"method\": \"key\", \"keyKind\": \"readOnly\"}}"))).as("a key needs its secret").isNotEmpty();
    assertThat(config.validate(TestCtx.JSON.readTree("{\"endpoint\": \"https://acme.documents.azure.com\", \"auth\": "
        + "{\"method\": \"entra\", \"tenantId\": \"acme.onmicrosoft.com\", \"clientId\": \"" + FakeCosmos.CLIENT
        + "\", \"clientSecret\": \"s\"}}"))).isEmpty();
    for (String tenant : List.of("acme.onmicrosoft.com/../x", "acme", "a b.com")) {
      assertThat(config.validate(TestCtx.JSON.readTree("{\"endpoint\": \"https://acme.documents.azure.com\", "
          + "\"auth\": {\"method\": \"entra\", \"tenantId\": \"" + tenant + "\", \"clientId\": \"" + FakeCosmos.CLIENT
          + "\", \"clientSecret\": \"s\"}}"))).as(tenant).isNotEmpty();
      ObjectNode c = (ObjectNode) TestCtx.JSON.readTree("{\"endpoint\": \"https://acme.documents.azure.com\", "
          + "\"auth\": {\"method\": \"entra\", \"clientId\": \"" + FakeCosmos.CLIENT + "\"}}");
      ((ObjectNode) c.get("auth")).put("tenantId", tenant);
      try {
        CosmosSettings.of(cfg(c), false);
        throw new AssertionError(tenant + " was accepted");
      } catch (ConnectorException e) {
        assertThat(e.getMessage()).contains("tenant");
      }
    }
  }

  @Test
  void theSmokeConnectionsAreValidConnections() throws Exception {
    Schema config = REGISTRY.getSchema(SCHEMAS.load("config"));
    JsonNode smoke;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/mcp-gateway/smoke/cosmosdb.json")) {
      smoke = TestCtx.JSON.readTree(in);
    }
    for (JsonNode touch : smoke.path("touches")) {
      // Stored connections keep their secrets apart: the form is checked with one.
      ObjectNode c = (ObjectNode) touch.at("/connection/config").deepCopy();
      ((ObjectNode) c.get("auth")).put("accountKey", "k");
      assertThat(config.validate(c)).as(touch.path("name").asString()).isEmpty();
    }
  }

  private static JsonNode with(String more) {
    return TestCtx.JSON.readTree("{\"endpoint\": \"https://acme.documents.azure.com\", \"auth\": {\"method\": \"key\", "
        + "\"keyKind\": \"readOnly\", \"accountKey\": \"k\"}, " + more + "}");
  }

  private static ConnectionConfig cfg(JsonNode c) {
    return new ConnectionConfig("c", "cosmos", "cosmosdb", Env.DEV, 1, c, Set.of(), null);
  }
}
