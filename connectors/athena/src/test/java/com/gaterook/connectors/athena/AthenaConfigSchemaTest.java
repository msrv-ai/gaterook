package com.gaterook.connectors.athena;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connectors.support.PersonOnly;
import com.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The connection form's schema: credentials as the DynamoDB connector's, a workgroup, databases and results. */
class AthenaConfigSchemaTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode TREE = SchemaResources.of(AthenaConnector.class, "athena").load("config");
  private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(TREE);

  @Test
  void aKeyWithARoleAndAProfileAreComplete() {
    assertThat(SCHEMA.validate(JSON.readTree("{\"region\": \"eu-central-1\", \"workgroup\": \"analytics\", "
        + "\"database\": \"sales\", \"databases\": [\"sales\", \"audit_*\", \"AwsDataCatalog.logs\"], "
        + "\"outputLocation\": \"s3://acme-athena-results/gaterook/\", \"credentials\": {\"source\": \"accessKey\", "
        + "\"accessKeyId\": \"AKIAIOSFODNN7EXAMPLE\", \"secretAccessKey\": \"stored\", \"roleArn\": "
        + "\"arn:aws:iam::412398765012:role/gw-athena\", \"externalId\": \"gw-ext-0123456789ab\"}}"))).isEmpty();
    assertThat(SCHEMA.validate(JSON.readTree("{\"region\": \"us-east-1\", \"credentials\": {\"source\": "
        + "\"profile\", \"profile\": \"analytics\"}}"))).isEmpty();
    for (String bad : List.of("{\"region\": \"eu-central-1\", \"credentials\": {\"source\": \"accessKey\", "
            + "\"accessKeyId\": \"AKIAIOSFODNN7EXAMPLE\"}}",
        "{\"region\": \"eu-central-1\", \"outputLocation\": \"https://acme.example.com/r\", \"credentials\": "
            + "{\"source\": \"profile\", \"profile\": \"p\"}}",
        "{\"region\": \"eu-central-1\", \"workgroup\": \"a b\", \"credentials\": {\"source\": \"profile\", "
            + "\"profile\": \"p\"}}",
        "{\"region\": \"eu-central-1\", \"databases\": [\"a b\"], \"credentials\": {\"source\": \"profile\", "
            + "\"profile\": \"p\"}}")) {
      assertThat(SCHEMA.validate(JSON.readTree(bad))).as(bad).isNotEmpty();
    }
  }

  @Test
  void theSecretIsWriteOnlyAndProdKeepsTheSessionPolicy() {
    assertThat(TREE.at("/properties/credentials/allOf/0/then/properties/secretAccessKey/writeOnly").asBoolean())
        .isTrue();
    assertThat(TREE.at("/properties/credentials/properties/sessionPolicy/x-forbidden-env-values/prod/0").asString())
        .isEqualTo("none");
    assertThat(PersonOnly.templates(TREE)).isEmpty();
    assertThat(TREE.at("/x-card/description").asString()).isEqualTo("Data catalogs, databases and SQL reads within "
        + "a workgroup's limits. Changes wait for approval.");
  }
}
