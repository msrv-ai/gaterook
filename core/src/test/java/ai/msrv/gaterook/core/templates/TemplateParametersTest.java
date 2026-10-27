package ai.msrv.gaterook.core.templates;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The signature a template list row shows ("orders_by_status(status, since)"): the parameters its definition declares. */
class TemplateParametersTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void anArrayOfParametersIsKeptInItsOrder() {
    JsonNode definition = JSON.readTree("""
        {"name":"orders_by_status","sql":"SELECT 1 WHERE status = :status AND created_at > :since",
         "params":[{"name":"status","type":"enum","values":["pending","paid"]},{"name":"since","type":"timestamp",
         "required":false}]}""");
    JsonNode params = TemplateService.parameters(definition);
    assertThat(params.findValuesAsString("name")).containsExactly("status", "since");
    assertThat(params.get(0).path("values").size()).isEqualTo(2);
    assertThat(params.get(1).path("required").asBoolean(true)).isFalse();
  }

  @Test
  void aMapOfParametersBecomesNamedEntriesAndNoneIsEmpty() {
    JsonNode params = TemplateService.parameters(JSON.readTree("""
        {"name":"q","sql":"SELECT :id","params":{"id":{"type":"integer"}}}"""));
    assertThat(params.toString()).isEqualTo("[{\"name\":\"id\",\"type\":\"integer\"}]");
    assertThat(TemplateService.parameters(JSON.readTree("{\"name\":\"compose-ps\",\"command\":\"docker compose ps\"}"))
        .isEmpty()).isTrue();
  }
}
