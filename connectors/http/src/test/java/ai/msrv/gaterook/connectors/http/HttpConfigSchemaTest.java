package ai.msrv.gaterook.connectors.http;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpConfigSchemaTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SchemaResources.of(HttpConnector.class, "http").load("config"));

  @Test
  void eachAuthenticationMethodRequiresOnlyItsOwnSecret() {
    for (String auth : new String[] {
        "{\"type\":\"none\"}",
        "{\"type\":\"bearer\",\"token\":\"stored-token\"}",
        "{\"type\":\"apiKeyHeader\",\"apiKey\":\"stored-key\"}",
        "{\"type\":\"apiKeyQuery\",\"apiKey\":\"stored-key\"}",
        "{\"type\":\"basic\",\"password\":\"stored-password\"}"}) {
      assertThat(SCHEMA.validate(config(auth))).as(auth).isEmpty();
    }
    for (String type : new String[] {"bearer", "apiKeyHeader", "apiKeyQuery", "basic"}) {
      assertThat(SCHEMA.validate(config("{\"type\":\"" + type + "\"}"))).as(type).isNotEmpty();
    }
  }

  @Test
  void inactiveSavedFieldsDoNotBlockAnotherAuthenticationMethod() {
    assertThat(SCHEMA.validate(config("""
        {"type":"bearer","token":"stored-token","headerName":"not a header","queryName":"old query name",
         "username":"old-user","password":"old-password","apiKey":"old-key"}"""))).isEmpty();
    assertThat(SCHEMA.validate(config("""
        {"type":"apiKeyHeader","apiKey":"stored-key","headerName":"not a header"}"""))).isNotEmpty();
    assertThat(SCHEMA.validate(config("{\"type\":\"none\",\"unknownCredential\":true}"))).isNotEmpty();
  }

  private static JsonNode config(String auth) {
    return JSON.readTree("{\"baseUrls\":[\"https://api.example.test\"],\"auth\":" + auth + "}");
  }
}
