package ai.msrv.gaterook.connectors.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The connection forms and tool schemas: valid JSON Schema 2020-12, and what they accept is what the code accepts. */
class ConfigSchemaTest {

  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final SchemaResources FIRESTORE = SchemaResources.of(FirestoreConnector.class, "firestore");
  private static final SchemaResources RTDB = SchemaResources.of(RtdbConnector.class, "firebase-rtdb");

  @Test
  void everySchemaIsValidJsonSchema2020() {
    Schema meta = REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    Map<String, JsonNode> schemas = new LinkedHashMap<>();
    for (String name : List.of("config", "firestore_list_collections.input", "firestore_get_document.input",
        "firestore_query.input", "firestore_write.input")) {
      schemas.put("firestore " + name, FIRESTORE.load(name));
    }
    for (String name : List.of("config", "rtdb_get.input", "rtdb_write.input")) {
      schemas.put("firebase-rtdb " + name, RTDB.load(name));
    }
    schemas.forEach((name, schema) -> {
      assertThat(meta.validate(schema)).as(name).isEmpty();
      Schema compiled = REGISTRY.getSchema(SchemaLocation.of("urn:firebase:test:" + name.replace(' ', ':')), schema);
      compiled.initializeValidators();
    });
  }

  @Test
  void theDatabaseUrlPatternAcceptsWhatTheConnectionAccepts() {
    Schema config = REGISTRY.getSchema(RTDB.load("config"));
    for (String url : List.of("https://acme-default-rtdb.firebaseio.com", "https://acme.firebaseio.com/",
        "https://acme-eu.europe-west1.firebasedatabase.app", "http://acme.firebaseio.com", "https://acme.web.app",
        "https://acme.firebaseapp.com", "https://a.b.firebaseio.com", "https://acme.firebaseio.com/users",
        "https://acme.firebaseio.com:443", "https://acme.firebasedatabase.app", "https://firebaseio.com")) {
      boolean schema = config.validate(TestCtx.JSON.readTree("{\"databaseUrl\": \"" + url + "\", \"credentials\": "
          + "{\"source\": \"adc\"}}")).isEmpty();
      boolean code = RtdbSettings.database(url) != null && !url.contains(":443");
      assertThat(schema).as(url).isEqualTo(code);
    }
    assertThat(config.validate(TestCtx.JSON.readTree("{\"databaseUrl\": \"https://acme.firebaseio.com\", "
        + "\"credentials\": {\"source\": \"adc\", \"quotaProject\": \"x\"}}"))).as("no billed project for a database")
        .isNotEmpty();
    assertThat(config.validate(TestCtx.JSON.readTree("{\"databaseUrl\": \"https://acme.firebaseio.com\", "
        + "\"credentials\": {\"source\": \"serviceAccountKey\"}}"))).as("a key needs its secret").isNotEmpty();
    for (String path : List.of("users", "public-*", "a?")) {
      assertThat(config.validate(TestCtx.JSON.readTree("{\"databaseUrl\": \"https://acme.firebaseio.com\", "
          + "\"paths\": [\"" + path + "\"], \"credentials\": {\"source\": \"adc\"}}"))).as(path).isEmpty();
    }
    for (String path : List.of("users/alice", "a.b", "$x", "a#", "a[0]", "%2e")) {
      assertThat(config.validate(TestCtx.JSON.readTree("{\"databaseUrl\": \"https://acme.firebaseio.com\", "
          + "\"paths\": [\"" + path + "\"], \"credentials\": {\"source\": \"adc\"}}"))).as(path).isNotEmpty();
    }
  }

  @Test
  void theCollectionsAndTheProjectAreWhatFirestoreNames() {
    Schema config = REGISTRY.getSchema(FIRESTORE.load("config"));
    for (String entry : List.of("orders", "audit-*", "/orders", "users/*/orders", "tenants/acme/**")) {
      assertThat(config.validate(firestore("acme-stage", entry))).as(entry).isEmpty();
    }
    for (String entry : List.of("users//orders", "users/", "a#b", "a%2Fb", "a\\\\b", "/")) {
      assertThat(config.validate(firestore("acme-stage", entry))).as(entry).isNotEmpty();
    }
    for (String project : List.of("ACME", "a", "acme_stage", "-acme")) {
      assertThat(config.validate(firestore(project, "orders"))).as(project).isNotEmpty();
    }
    ObjectNode named = (ObjectNode) firestore("acme-stage", "orders");
    named.put("database", "audit-db");
    assertThat(config.validate(named)).isEmpty();
    named.put("database", "Audit");
    assertThat(config.validate(named)).isNotEmpty();
  }

  @Test
  void theSmokeConnectionsAreValidConnections() throws Exception {
    JsonNode smoke;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/mcp-gateway/smoke/firebase.json")) {
      smoke = TestCtx.JSON.readTree(in);
    }
    for (JsonNode touch : smoke.path("touches")) {
      String type = touch.at("/connection/type").asString();
      Schema config = REGISTRY.getSchema((type.equals("firestore") ? FIRESTORE : RTDB).load("config"));
      assertThat(config.validate(touch.at("/connection/config"))).as(touch.path("name").asString()).isEmpty();
    }
  }

  private static JsonNode firestore(String project, String collection) {
    return TestCtx.JSON.readTree("{\"project\": \"" + project + "\", \"collections\": [\"" + collection + "\"], "
        + "\"credentials\": {\"source\": \"adc\"}}");
  }
}
