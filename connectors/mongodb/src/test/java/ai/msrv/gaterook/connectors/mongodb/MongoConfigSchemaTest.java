package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** The two address modes share credentials and preserve stored connection-string configurations. */
class MongoConfigSchemaTest {

  private static final JsonNode CONFIG = SchemaResources.of(MongoConnector.class, "mongodb").load("config");
  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(CONFIG);

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"host\":\"mongo.example.test\"}",
      "{\"mode\":\"host\",\"host\":\"mongo.example.test\",\"port\":27017,\"tls\":true}",
      "{\"mode\":\"host\",\"host\":\"::1\",\"port\":27018,\"tls\":false}",
      "{\"uri\":\"mongodb://mongo.example.test:27017/orders?replicaSet=rs0\"}",
      "{\"uri\":\"mongodb+srv://cluster.example.test/orders?retryWrites=false\"}",
      "{\"mode\":\"uri\",\"uri\":\"mongodb://one.example.test,two.example.test/orders\",\"tls\":true}"
  })
  void acceptsStructuredAndLegacyAddresses(String connection) {
    assertThat(SCHEMA.validate(Fixtures.json("{\"connection\":" + connection + "}"))).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{}",
      "{\"mode\":\"host\"}",
      "{\"mode\":\"host\",\"uri\":\"mongodb://mongo.example.test/orders\"}",
      "{\"mode\":\"host\",\"host\":\"\"}",
      "{\"mode\":\"host\",\"host\":42}",
      "{\"mode\":\"uri\"}",
      "{\"mode\":\"uri\",\"host\":\"mongo.example.test\"}",
      "{\"mode\":\"uri\",\"uri\":\"\"}",
      "{\"mode\":\"uri\",\"uri\":\"https://mongo.example.test\"}",
      "{\"mode\":\"uri\",\"uri\":42}",
      "{\"mode\":\"other\",\"host\":\"mongo.example.test\"}",
      "{\"mode\":\"host\",\"host\":\"mongo.example.test\",\"tls\":\"true\"}"
  })
  void requiresAnAddressForTheSelectedMode(String connection) {
    assertThat(SCHEMA.validate(Fixtures.json("{\"connection\":" + connection + "}"))).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"mode\":\"host\",\"host\":\"mongo.example.test\",\"uri\":\"mongodb://other.example.test\"}",
      "{\"mode\":\"host\",\"host\":\"mongo.example.test\",\"uri\":42}",
      "{\"mode\":\"host\",\"host\":\"mongo.example.test\",\"uri\":\"\"}",
      "{\"mode\":\"uri\",\"uri\":\"mongodb://mongo.example.test\",\"host\":\"other.example.test\"}",
      "{\"mode\":\"uri\",\"uri\":\"mongodb://mongo.example.test\",\"host\":42}",
      "{\"mode\":\"uri\",\"uri\":\"mongodb://mongo.example.test\",\"port\":27017}",
      "{\"mode\":\"uri\",\"uri\":\"mongodb://mongo.example.test\",\"port\":null}",
      "{\"uri\":\"mongodb://mongo.example.test\",\"host\":\"other.example.test\"}",
      "{\"uri\":\"mongodb://mongo.example.test\",\"port\":27017}"
  })
  void rejectsInactiveAddressFieldsInsteadOfSilentlyIgnoringThem(String connection) {
    assertThat(SCHEMA.validate(Fixtures.json("{\"connection\":" + connection + "}"))).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "65536", "27017.5", "\"27017\"", "null"})
  void rejectsInvalidStructuredPorts(String port) {
    assertThat(SCHEMA.validate(Fixtures.json("{\"connection\":{\"mode\":\"host\","
        + "\"host\":\"mongo.example.test\",\"port\":" + port + "}}"))).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 65535})
  void acceptsPortBoundaries(int port) {
    assertThat(SCHEMA.validate(Fixtures.json("{\"connection\":{\"mode\":\"host\","
        + "\"host\":\"mongo.example.test\",\"port\":" + port + "}}"))).isEmpty();
  }

  @Test
  void keepsTheCredentialPathsAndWriteOnlyPasswordInBothModes() {
    String credentials = ",\"credentials\":{\"user\":\"reader\",\"password\":\"test-only-value\","
        + "\"authSource\":\"admin\",\"mechanism\":\"SCRAM-SHA-256\"}}";
    assertThat(SCHEMA.validate(Fixtures.json("{\"connection\":{\"mode\":\"host\","
        + "\"host\":\"mongo.example.test\"}" + credentials))).isEmpty();
    assertThat(SCHEMA.validate(Fixtures.json("{\"connection\":{\"mode\":\"uri\","
        + "\"uri\":\"mongodb://mongo.example.test/orders\"}" + credentials))).isEmpty();
    assertThat(CONFIG.at("/properties/credentials/properties/password/writeOnly").asBoolean()).isTrue();
    assertThat(CONFIG.at("/properties/credentials/x-gateway-secret").asString()).isEqualTo("login");
    assertThat(CONFIG.at("/properties/credentials/properties/authSource/x-section").asString())
        .isEqualTo("/advanced");
    assertThat(CONFIG.at("/properties/credentials/properties/mechanism/x-section").asString())
        .isEqualTo("/advanced");
    assertThat(CONFIG.at("/properties/advanced/x-collapsed").asBoolean()).isTrue();
  }
}
