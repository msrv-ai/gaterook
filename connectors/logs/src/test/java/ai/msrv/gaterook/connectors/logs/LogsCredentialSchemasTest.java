package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Authentication requires only credentials used by the selected backend and sign-in method. */
class LogsCredentialSchemasTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(SchemaResources.of(LogsConnector.class, "logs").load("config"));

  @Test
  void lokiCanUseNoAuthenticationOrOnlyTheSelectedMethodsCredentials() {
    for (String auth : new String[] {
        "{\"mode\":\"none\"}",
        "{\"mode\":\"bearer\",\"secret\":\"token\"}",
        "{\"mode\":\"basic\",\"username\":\"reader\",\"secret\":\"password\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"loki","url":"https://logs.example.test","auth":%s}""".formatted(auth))))
          .as(auth).isEmpty();
    }
    for (String auth : new String[] {
        "{\"mode\":\"bearer\"}",
        "{\"mode\":\"basic\",\"secret\":\"password\"}",
        "{\"mode\":\"basic\",\"username\":\"reader\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"loki","url":"https://logs.example.test","auth":%s}""".formatted(auth))))
          .as(auth).isNotEmpty();
    }
  }

  @Test
  void googleSignInNeedsAKeyOnlyForTheServiceAccountKeySource() {
    for (String credentials : new String[] {
        "{\"source\":\"adc\"}",
        "{\"source\":\"serviceAccountKey\",\"serviceAccountKey\":\"stored-key\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"gcp-logging","resourceNames":["projects/example-dev"],"credentials":%s}
          """.formatted(credentials)))).as(credentials).isEmpty();
    }
    assertThat(SCHEMA.validate(JSON.readTree("""
        {"backend":"gcp-logging","resourceNames":["projects/example-dev"],
         "credentials":{"source":"serviceAccountKey"}}"""))).isNotEmpty();
  }

  @Test
  void cloudWatchProfileDoesNotRequireAnAccessKey() {
    for (String credentials : new String[] {
        "{\"source\":\"profile\",\"profile\":\"dev-logs\"}",
        "{\"source\":\"accessKey\",\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\",\"secretAccessKey\":\"stored-key\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/app/api"],"credentials":%s}
          """.formatted(credentials)))).as(credentials).isEmpty();
    }
    for (String credentials : new String[] {
        "{\"source\":\"profile\"}",
        "{\"source\":\"accessKey\",\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\"}",
        "{\"source\":\"accessKey\",\"secretAccessKey\":\"stored-key\"}"}) {
      assertThat(SCHEMA.validate(JSON.readTree("""
          {"backend":"cloudwatch","region":"eu-central-1","logGroups":["/app/api"],"credentials":%s}
          """.formatted(credentials)))).as(credentials).isNotEmpty();
    }
  }
}
