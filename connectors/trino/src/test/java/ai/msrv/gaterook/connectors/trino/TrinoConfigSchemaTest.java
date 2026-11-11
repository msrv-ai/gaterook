package ai.msrv.gaterook.connectors.trino;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.PersonOnly;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The connection form's schema: what a person can save, and which settings are secrets. */
class TrinoConfigSchemaTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode TREE = SchemaResources.of(TrinoConnector.class, "trino").load("config");
  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(TREE);

  @Test
  void aPasswordAJwtAndNoAuthenticationEachNeedTheirOwnSettings() {
    assertThat(SCHEMA.validate(JSON.readTree("{\"url\": \"https://trino.example.com:8443\", \"catalog\": \"hive\", "
        + "\"schema\": \"sales\", \"catalogs\": [\"hive\", \"iceberg_*\"], \"auth\": {\"mode\": \"basic\", \"user\": "
        + "\"gaterook_reader\", \"password\": \"stored\"}}"))).isEmpty();
    assertThat(SCHEMA.validate(JSON.readTree("{\"url\": \"https://trino.example.com:8443\", \"auth\": {\"mode\": "
        + "\"jwt\", \"token\": \"stored\"}}"))).isEmpty();
    assertThat(SCHEMA.validate(JSON.readTree("{\"url\": \"http://127.0.0.1:8080\", \"engine\": \"presto\", "
        + "\"auth\": {\"mode\": \"none\", \"user\": \"dev\"}}"))).isEmpty();
    for (String bad : List.of("{\"url\": \"https://trino.example.com:8443\", \"auth\": {\"mode\": \"basic\", "
            + "\"user\": \"a\"}}",
        "{\"url\": \"https://trino.example.com:8443\", \"auth\": {\"mode\": \"none\"}}",
        "{\"url\": \"https://trino.example.com:8443/v1\", \"auth\": {\"mode\": \"jwt\", \"token\": \"t\"}}",
        "{\"url\": \"ftp://trino.example.com\", \"auth\": {\"mode\": \"jwt\", \"token\": \"t\"}}",
        "{\"url\": \"https://trino.example.com\", \"auth\": {\"mode\": \"jwt\", \"token\": \"t\"}, \"catalogs\": "
            + "[\"a b\"]}",
        "{\"url\": \"https://trino.example.com\", \"auth\": {\"mode\": \"jwt\", \"token\": \"t\", \"extra\": 1}}")) {
      assertThat(SCHEMA.validate(JSON.readTree(bad))).as(bad).isNotEmpty();
    }
  }

  @Test
  void theSecretsAreWriteOnlyAndNothingIsPersonOnly() {
    assertThat(TREE.at("/properties/auth/allOf/0/then/properties/password/writeOnly").asBoolean()).isTrue();
    assertThat(TREE.at("/properties/auth/allOf/1/then/properties/token/writeOnly").asBoolean()).isTrue();
    assertThat(PersonOnly.templates(TREE)).isEmpty();
    assertThat(TREE.at("/x-card/description").asString()).isEqualTo("Catalogs, schemas and SQL reads checked by "
        + "Trino's own plan. Changes wait for approval.");
  }
}
