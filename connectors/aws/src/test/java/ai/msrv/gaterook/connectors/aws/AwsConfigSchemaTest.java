package ai.msrv.gaterook.connectors.aws;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AwsConfigSchemaTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SchemaResources.of(AwsConnector.class, "aws").load("config"));

  @Test
  void profilesNeedNoAccessKeyAndAccessKeysNeedNoProfile() {
    assertThat(SCHEMA.validate(config("{\"source\":\"profile\",\"profile\":\"reader\"}"))).isEmpty();
    assertThat(SCHEMA.validate(config("""
        {"source":"accessKey","accessKeyId":"AKIAEXAMPLE123456789","secretAccessKey":"stored-key"}""")))
        .isEmpty();
    for (String credentials : new String[] {
        "{\"source\":\"profile\"}", "{\"source\":\"profile\",\"profile\":\" \"}",
        "{\"source\":\"accessKey\",\"accessKeyId\":\"AKIAEXAMPLE123456789\"}",
        "{\"source\":\"accessKey\",\"secretAccessKey\":\"stored-key\"}"}) {
      assertThat(SCHEMA.validate(config(credentials))).as(credentials).isNotEmpty();
    }
  }

  @Test
  void inactiveSavedCredentialsDoNotBlockTheSelectedSource() {
    assertThat(SCHEMA.validate(config("""
        {"source":"profile","profile":"reader","accessKeyId":"old-incomplete-key","secretAccessKey":"old-key"}""")))
        .isEmpty();
    assertThat(SCHEMA.validate(config("""
        {"source":"accessKey","accessKeyId":"AKIAEXAMPLE123456789","secretAccessKey":"stored-key","profile":" "}""")))
        .isEmpty();
    assertThat(SCHEMA.validate(config("""
        {"source":"accessKey","accessKeyId":"bad-key","secretAccessKey":"stored-key"}"""))).isNotEmpty();
    assertThat(SCHEMA.validate(config("{\"source\":\"profile\",\"profile\":\"reader\",\"unknownCredential\":true}")))
        .isNotEmpty();
  }

  private static JsonNode config(String credentials) {
    return JSON.readTree("{\"regions\":[\"eu-central-1\"],\"credentials\":" + credentials + "}");
  }
}
