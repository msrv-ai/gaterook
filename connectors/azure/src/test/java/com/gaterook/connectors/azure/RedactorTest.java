package com.gaterook.connectors.azure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** What the connector masks before the core's masker: Azure's settings maps, secret-named fields, connection strings. */
class RedactorTest {

  private static JsonNode redact(String json, int expected) {
    JsonNode n = TestCtx.JSON.readTree(json);
    assertThat(Redactor.redact(n)).as(n.toString()).isEqualTo(expected);
    return n;
  }

  @Test
  void settingsMapsAndListsLoseTheirValues() {
    JsonNode site = redact("{\"properties\":{\"appSettings\":{\"API_TOKEN\":\"t-1\",\"MODE\":\"fast\"},"
        + "\"connectionStrings\":{\"DB\":{\"value\":\"Server=x;Password=p\",\"type\":\"SQLAzure\"}}}}", 3);
    assertThat(site.at("/properties/appSettings/MODE").asString()).isEqualTo(Redactor.MASK);
    assertThat(site.at("/properties/connectionStrings/DB/type").asString()).isEqualTo("SQLAzure");

    JsonNode app = redact("{\"properties\":{\"template\":{\"containers\":[{\"name\":\"api\",\"env\":["
        + "{\"name\":\"LEVEL\",\"value\":\"debug\"},{\"name\":\"DB\",\"secretRef\":\"db-conn\"}]}]},"
        + "\"configuration\":{\"secrets\":[{\"name\":\"db-conn\",\"value\":\"s3cr3t\"},{\"name\":\"kv\","
        + "\"keyVaultUrl\":\"https://kv.vault.azure.net/secrets/x\"}]}}}", 2);
    assertThat(app.at("/properties/template/containers/0/env/1/secretRef").asString()).isEqualTo("db-conn");
    assertThat(app.at("/properties/configuration/secrets/1/keyVaultUrl").asString())
        .isEqualTo("https://kv.vault.azure.net/secrets/x");
  }

  @Test
  void secretNamedFieldsGoAndReferencesStay() {
    JsonNode vm = redact("{\"osProfile\":{\"adminUsername\":\"azure\",\"adminPassword\":\"P@ss\",\"customData\":"
        + "\"I2Nsb3VkLWNvbmZpZw==\",\"secrets\":[]},\"primaryKey\":\"k1\",\"keys\":[{\"keyName\":\"key1\","
        + "\"value\":\"v1\"}],\"passwordProfile\":\"basic\",\"secretName\":\"db\",\"tokenExpiry\":\"2026-12-01\","
        + "\"instrumentationKey\":\"00000000-0000-0000-0000-000000000000\"}", 4);
    assertThat(vm.at("/osProfile/adminUsername").asString()).isEqualTo("azure");
    assertThat(vm.path("passwordProfile").asString()).isEqualTo("basic");
    assertThat(vm.path("secretName").asString()).isEqualTo("db");
    assertThat(vm.path("tokenExpiry").asString()).isEqualTo("2026-12-01");
    assertThat(vm.path("instrumentationKey").asString()).isEqualTo("00000000-0000-0000-0000-000000000000");
  }

  @Test
  void connectionStringsAndSasUrlsAnywhere() {
    JsonNode n = redact("{\"description\":\"DefaultEndpointsProtocol=https;AccountName=a;AccountKey=abc==\","
        + "\"links\":[\"https://a.blob.core.windows.net/c/b?sv=2024-11-04&sig=AbCdEf1234567890%2B%3D\","
        + "\"https://example.test/docs\"],\"bus\":\"Endpoint=sb://n.servicebus.windows.net/;SharedAccessKeyName=r;"
        + "SharedAccessKey=xyz=\"}", 3);
    assertThat(n.at("/links/1").asString()).isEqualTo("https://example.test/docs");
  }
}
