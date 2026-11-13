package com.gaterook.connectors.snowflake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.connectors.support.PersonOnly;
import com.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The connection form's schema and the settings read from it: where GateRook connects, and as whom. */
class SnowflakeSettingsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private static final JsonNode CONFIG_SCHEMA = SchemaResources.of(SnowflakeConnector.class, "snowflake")
      .load("config");
  private static final Schema SCHEMA = REGISTRY.getSchema(SchemaLocation.of("urn:mcp-gateway:snowflake:config"),
      CONFIG_SCHEMA);

  private static ObjectNode config(String account, String authentication) {
    ObjectNode c = JsonNodeFactory.instance.objectNode().put("account", account).put("user", "gw_reader");
    c.set("authentication", JSON.readTree(authentication));
    return c;
  }

  private static SnowflakeSettings settings(ObjectNode c) throws ConnectorException {
    return SnowflakeSettings.of(new ConnectionConfig("c", "dev-sf", "snowflake", Env.DEV, 1, c, Set.of(), null));
  }

  @Test
  void theFormTakesAKeyPairOrATokenAndNothingElse() {
    assertThat(SCHEMA.validate(config("myorg-myaccount", "{\"method\": \"keyPair\", \"privateKey\": \"pem\"}")))
        .isEmpty();
    assertThat(SCHEMA.validate(config("xy12345.eu-central-1", "{\"method\": \"token\", \"token\": \"t\"}"))).isEmpty();
    assertThat(SCHEMA.validate(config("myorg-myaccount", "{\"method\": \"keyPair\"}"))).as("the key").isNotEmpty();
    assertThat(SCHEMA.validate(config("myorg-myaccount", "{\"method\": \"token\"}"))).as("the token").isNotEmpty();
    assertThat(SCHEMA.validate(config("myorg-myaccount", "{\"method\": \"password\", \"password\": \"p\"}")))
        .isNotEmpty();
    assertThat(SCHEMA.validate(config("myorg-myaccount", "{\"method\": \"token\", \"token\": \"t\", \"other\": 1}")))
        .as("unknown keys").isNotEmpty();
    ObjectNode extra = config("myorg-myaccount", "{\"method\": \"token\", \"token\": \"t\"}");
    extra.put("url", "https://evil.example");
    assertThat(SCHEMA.validate(extra)).as("no URL of its own").isNotEmpty();
    for (String account : List.of("https://myorg-myaccount.snowflakecomputing.com", "myorg-myaccount.snowflakecomputing"
        + ".com", "my org", "a.b.c.d", "-x", "")) {
      assertThat(SCHEMA.validate(config(account, "{\"method\": \"token\", \"token\": \"t\"}"))).as(account)
          .isNotEmpty();
    }
    ObjectNode tables = config("myorg-myaccount", "{\"method\": \"token\", \"token\": \"t\"}");
    tables.putArray("tables").add("ANALYTICS.PUBLIC.*").add("analytics.*.orders");
    assertThat(SCHEMA.validate(tables)).isEmpty();
    tables.putArray("tables").add("PUBLIC.ORDERS");
    assertThat(SCHEMA.validate(tables)).as("three parts").isNotEmpty();
    ObjectNode names = config("myorg-myaccount", "{\"method\": \"token\", \"token\": \"t\"}");
    names.put("warehouse", "compute_wh").put("role", "\"Read Only\"").put("database", "ANALYTICS");
    assertThat(SCHEMA.validate(names)).isEmpty();
    names.put("schema", "public; DROP");
    assertThat(SCHEMA.validate(names)).isNotEmpty();
    // A schema needs its database, as the connector says: the form requires Database with one.
    ObjectNode schemaOnly = config("myorg-myaccount", "{\"method\": \"token\", \"token\": \"t\"}");
    schemaOnly.put("schema", "PUBLIC");
    assertThat(SCHEMA.validate(schemaOnly)).anySatisfy(e -> assertThat(e.getProperty()).isEqualTo("database"));
    assertThat(CONFIG_SCHEMA.at("/properties/database/x-suggest").asBoolean()).isTrue();
    assertThat(CONFIG_SCHEMA.at("/properties/warehouse/x-advanced").asBoolean(true)).isFalse();
  }

  @Test
  void secretsAreWriteOnlyAndNothingIsForThePersonAlone() {
    JsonNode then = CONFIG_SCHEMA.at("/properties/authentication/allOf");
    assertThat(then.get(0).at("/then/properties/privateKey/writeOnly").asBoolean()).isTrue();
    assertThat(then.get(0).at("/then/properties/privateKey/x-gateway-secret").asString()).isEqualTo("pem");
    assertThat(then.get(1).at("/then/properties/token/writeOnly").asBoolean()).isTrue();
    assertThat(then.get(1).at("/then/properties/token/x-gateway-secret").asString()).isEqualTo("value");
    assertThat(PersonOnly.templates(CONFIG_SCHEMA)).isEmpty();
  }

  @Test
  void everySchemaIsValidJsonSchema2020AndToolInputsAreClosed() {
    Schema meta = REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    SnowflakeConnector connector = new SnowflakeConnector();
    assertThat(meta.validate(connector.manifest().configSchema())).isEmpty();
    for (ToolDefinition t : connector.manifest().tools()) {
      assertThat(meta.validate(t.inputSchema())).as(t.name()).isEmpty();
      if (t.outputSchema() != null) {
        assertThat(meta.validate(t.outputSchema())).as(t.name()).isEmpty();
      }
      assertThat(t.name()).matches("^snowflake_[a-z_]{1,54}$");
    }
    connector.close();
  }

  @Test
  void theAddressComesFromTheAccountIdentifierAlone() throws ConnectorException {
    SnowflakeSettings org = settings(config("MyOrg-My_Account", "{\"method\": \"keyPair\"}"));
    assertThat(org.host()).isEqualTo("myorg-my-account.snowflakecomputing.com");
    assertThat(org.base().toString()).isEqualTo("https://myorg-my-account.snowflakecomputing.com");
    assertThat(org.jwtAccount()).isEqualTo("MYORG-MY_ACCOUNT");
    SnowflakeSettings locator = settings(config("XY12345.us-east-2.aws", "{\"method\": \"keyPair\"}"));
    assertThat(locator.host()).isEqualTo("xy12345.us-east-2.aws.snowflakecomputing.com");
    assertThat(locator.jwtAccount()).isEqualTo("XY12345");
    ObjectNode privateLink = config("myorg-myaccount", "{\"method\": \"token\"}");
    privateLink.put("privateLink", true);
    assertThat(settings(privateLink).host()).isEqualTo("myorg-myaccount.privatelink.snowflakecomputing.com");
    for (String account : List.of("xy12345.privatelink", "myorg-myaccount.snowflakecomputing.com", "evil.com",
        "https://x", "a b")) {
      assertThatThrownBy(() -> settings(config(account, "{\"method\": \"token\"}"))).as(account)
          .isInstanceOfSatisfying(ConnectorException.class, e -> assertThat(e.kind())
              .isEqualTo(ConnectorException.Kind.BAD_INPUT));
    }
  }

  @Test
  void namesAreReadAsSnowflakeStoresThem() throws ConnectorException {
    ObjectNode c = config("myorg-myaccount", "{\"method\": \"keyPair\"}");
    c.put("warehouse", "compute_wh").put("role", "\"Read Only\"").put("database", "analytics").put("schema", "public");
    SnowflakeSettings s = settings(c);
    assertThat(s.warehouse()).isEqualTo("COMPUTE_WH");
    assertThat(s.role()).isEqualTo("Read Only");
    assertThat(s.namespace()).isEqualTo("ANALYTICS.PUBLIC");
    c.put("schema", "\"x\"\"y\"");
    assertThat(settings(c).schema()).isEqualTo("x\"y");
    ObjectNode noDatabase = config("myorg-myaccount", "{\"method\": \"keyPair\"}");
    noDatabase.put("schema", "public");
    assertThatThrownBy(() -> settings(noDatabase)).hasMessageContaining("needs its database");
    c.put("warehouse", "two words");
    assertThatThrownBy(() -> settings(c)).hasMessageContaining("warehouse");
  }

  @Test
  void theStatementTimeoutIsTheStricterOne() throws ConnectorException {
    ObjectNode c = config("myorg-myaccount", "{\"method\": \"keyPair\"}");
    assertThat(settings(c).statementTimeout(Duration.ofMillis(5_000))).isEqualTo(5);
    assertThat(settings(c).statementTimeout(Duration.ofMillis(1_500))).isEqualTo(2);
    c.putObject("advanced").put("statementTimeoutSeconds", 3);
    assertThat(settings(c).statementTimeout(Duration.ofSeconds(30))).isEqualTo(3);
    assertThat(settings(c).statementTimeout(Duration.ofSeconds(1))).isEqualTo(1);
  }

  @Test
  void aTableListMatchesWholeNamesIgnoringCase() {
    TableList list = TableList.of(List.of("ANALYTICS.PUBLIC.ORD*", "analytics.*.customers"));
    assertThat(list.restricted()).isTrue();
    assertThat(list.allows("ANALYTICS", "PUBLIC", "ORDERS")).isTrue();
    assertThat(list.allows("analytics", "public", "orders_2026")).isTrue();
    assertThat(list.allows("ANALYTICS", "SALES", "CUSTOMERS")).isTrue();
    assertThat(list.allows("ANALYTICS", "PUBLIC", "SECRETS")).isFalse();
    assertThat(list.allows("HR", "PUBLIC", "ORDERS")).isFalse();
    assertThat(list.allows(new ObjectName(List.of("ORDERS")))).as("unresolved").isFalse();
    assertThat(list.mayAllowDatabase("HR")).isFalse();
    assertThat(list.mayAllowSchema("ANALYTICS", "SALES")).isTrue();
    assertThat(list.likePatterns("ANALYTICS")).extracting(p -> p[0] + "|" + p[1]).containsExactly("PUBLIC|ORD%",
        "%|CUSTOMERS");
    assertThat(TableList.like("A_B%C!*")).isEqualTo("A!_B!%C!!%");
    assertThat(TableList.of(List.of()).allows("ANY", "THING", "AT_ALL")).isTrue();
    assertThatThrownBy(() -> TableList.of(List.of("ORDERS"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void objectNamesFollowSnowflakesIdentifierRules() {
    assertThat(ObjectName.parse("orders").parts()).containsExactly("ORDERS");
    assertThat(ObjectName.parse("Analytics.\"Mixed Case\".t").parts()).containsExactly("ANALYTICS", "Mixed Case", "T");
    assertThat(ObjectName.parse("a.b.c").resolve(null, null).display()).isEqualTo("A.B.C");
    assertThat(ObjectName.parse("t").resolve("DB", null)).isNull();
    assertThat(ObjectName.parse("s.t").resolve("DB", null).display()).isEqualTo("DB.S.T");
    for (String bad : List.of("", "a b", "a..b", "a.", "\"\"", "IDENTIFIER('t')", "$x", "a;b", "'t'")) {
      assertThat(ObjectName.parse(bad)).as(bad).isNull();
    }
    assertThat(ObjectName.identifier("\"x\"\"y\"")).isEqualTo("x\"y");
    assertThat(ObjectName.quote("x\"y")).isEqualTo("\"x\"\"y\"");
  }
}
