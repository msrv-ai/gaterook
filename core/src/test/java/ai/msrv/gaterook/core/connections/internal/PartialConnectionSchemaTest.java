package ai.msrv.gaterook.core.connections.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.SchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PartialConnectionSchemaTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final SchemaValidator validator = new SchemaValidator();
  private final JsonNode schema = json.readTree("""
      {"type":"object","additionalProperties":false,"required":["host","auth","databases"],"properties":{
      "host":{"type":"string","minLength":3},"port":{"type":"integer","minimum":1,"maximum":65535},
      "databases":{"type":"array","minItems":1,"items":{"type":"string"}},
      "auth":{"type":"object","additionalProperties":false,"properties":{
        "type":{"enum":["token","appRole"]},"token":{"type":"string"},"roleId":{"type":"string"}},
        "oneOf":[{"properties":{"type":{"const":"token"}},"required":["type","token"]},
        {"properties":{"type":{"const":"appRole"}},"required":["type","roleId"]}]}}}
      """);

  @Test void missingPropertiesAndEmptySelectionsCanBeSavedOnlyAsPartialData() {
    JsonNode partial = PartialConnectionSchema.relax(schema);
    for (String value : new String[] {"{}", "{\"auth\":{}}", "{\"databases\":[]}", "{\"auth\":{\"type\":\"token\"}}"}) {
      JsonNode config = json.readTree(value);
      assertThat(validator.validate(partial, config)).isEmpty();
      assertThat(validator.validate(schema, config)).isNotEmpty();
    }
  }

  @Test void knownInvalidValuesAndUnknownPropertiesRemainInvalid() {
    JsonNode partial = PartialConnectionSchema.relax(schema);
    for (String value : new String[] {"{\"host\":42}", "{\"host\":\"x\"}", "{\"port\":0}",
        "{\"databases\":[42]}", "{\"auth\":{\"type\":\"invented\"}}", "{\"unknown\":true}"}) {
      assertThat(validator.validate(partial, json.readTree(value))).as(value).isNotEmpty();
    }
  }
}
