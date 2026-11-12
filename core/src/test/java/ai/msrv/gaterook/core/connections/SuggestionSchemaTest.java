package ai.msrv.gaterook.core.connections;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A suggestion request may leave the fields the connector lists (x-suggest) empty: only those are relaxed. */
class SuggestionSchemaTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void aListedFieldLeavesEveryRequiredOnTheWayAndKeepsItsNeighbours() {
    JsonNode schema = JSON.readTree("""
        {"type":"object","required":["region","target","logGroups"],"properties":{
          "region":{"type":"string","minLength":1},
          "logGroups":{"type":"array","minItems":1,"x-suggest":true},
          "target":{"type":"object","required":["kind"],"properties":{"kind":{"enum":["cluster","serverless"]}},
            "allOf":[
              {"if":{"properties":{"kind":{"const":"cluster"}}},
               "then":{"required":["cluster"],"properties":{"cluster":{"type":"string","pattern":"^[a-z]","x-suggest":true}}}},
              {"if":{"properties":{"kind":{"const":"serverless"}}},
               "then":{"required":["workgroup"],"properties":{"workgroup":{"type":"string"}}}}]}}}""");

    ConnectionService.relax(schema, List.of("logGroups"));
    ConnectionService.relax(schema, List.of("target", "cluster"));

    assertThat(schema.path("required").toString()).isEqualTo("[\"region\",\"target\"]");
    assertThat(schema.at("/properties/logGroups").isEmpty()).isTrue();
    assertThat(schema.at("/properties/region/minLength").asInt()).isEqualTo(1);
    JsonNode target = schema.at("/properties/target");
    assertThat(target.path("required").toString()).isEqualTo("[\"kind\"]");
    assertThat(target.at("/allOf/0/then/required").toString()).isEqualTo("[]");
    assertThat(target.at("/allOf/0/then/properties/cluster").isEmpty()).isTrue();
    // The other branch keeps its own requirement.
    assertThat(target.at("/allOf/1/then/required").toString()).isEqualTo("[\"workgroup\"]");
  }
}
