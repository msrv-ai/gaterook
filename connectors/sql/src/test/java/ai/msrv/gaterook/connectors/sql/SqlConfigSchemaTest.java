package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SqlConfigSchemaTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SchemaResources RESOURCES = SchemaResources.of(JdbcSqlConnector.class, "sql");
  private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  @ParameterizedTest
  @ValueSource(strings = {"postgres", "mysql"})
  void passwordlessConnectionsMatchTheSupportedRuntimeLogin(String type) throws Exception {
    var config = JSON.readTree("""
        {"connection":{"host":"db.example.test","port":5432,"databases":["app"]},
         "credentials":{"user":"reader"}}""");
    var schema = SCHEMAS.getSchema(RESOURCES.load(type + ".config"));
    assertThat(schema.validate(config)).isEmpty();
    var connection = new ConnectionConfig("conn-" + type, "dev-db", type, Env.DEV, 1, config, Set.of(), null);
    try (Credentials credentials = Credentials.resolve(connection, null)) {
      assertThat(credentials.user()).isEqualTo("reader");
      assertThat(credentials.password()).isEmpty();
    }
    ((ObjectNode) config.path("credentials")).put("password", "stored-password");
    assertThat(schema.validate(config)).as("Password authentication remains supported").isEmpty();
    ((ObjectNode) config.path("credentials")).remove("user");
    assertThat(schema.validate(config)).as("The connection still needs a user").isNotEmpty();
  }
}
