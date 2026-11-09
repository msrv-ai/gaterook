package ai.msrv.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PersonOnlyTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The layouts of the real connectors: a conditional branch behind a placeholder, an object, an item, a shared $ref. */
  private static final JsonNode SCHEMA = JSON.readTree("""
      {"type":"object","properties":{
        "host":{"type":"string"},
        "trustedExec":true,
        "pinnedCluster":true,
        "hostKeys":{"type":"object","properties":{"pinned":{"type":"array","x-person-only":true,"items":{"type":"string"}},
          "label":{"type":"string"}}},
        "jump":{"type":"object","properties":{"hostKeys":{"$ref":"#/properties/hostKeys"}}},
        "replicas":{"type":"array","items":{"type":"object","properties":{"pin":{"type":"string","x-person-only":true},
          "host":{"type":"string"}}}},
        "shared":{"$ref":"#/$defs/shared"}},
       "allOf":[{"if":{"properties":{"host":{"const":"a"}}},"then":{"properties":{
         "trustedExec":{"type":"string","x-person-only":true},
         "pinnedCluster":{"type":"object","x-person-only":true,"properties":{"serverUrl":{"type":"string"}}}}}}],
       "$defs":{"shared":{"type":"object","properties":{"mark":{"$ref":"#/$defs/marked"}}},
                "marked":{"type":"string","x-person-only":true}}}
      """);

  @Test
  void aMarkedPropertyAndEverythingInsideItIsCovered() {
    assertThat(PersonOnly.covers(SCHEMA, "/trustedExec")).isTrue();
    assertThat(PersonOnly.covers(SCHEMA, "/pinnedCluster")).isTrue();
    assertThat(PersonOnly.covers(SCHEMA, "/pinnedCluster/serverUrl")).isTrue();
    assertThat(PersonOnly.covers(SCHEMA, "/hostKeys/pinned")).isTrue();
    assertThat(PersonOnly.covers(SCHEMA, "/hostKeys/pinned/0")).isTrue();
  }

  @Test
  void referencesAndArrayItemsAreFollowed() {
    assertThat(PersonOnly.covers(SCHEMA, "/jump/hostKeys/pinned")).isTrue();
    assertThat(PersonOnly.covers(SCHEMA, "/replicas/*/pin")).isTrue();
    assertThat(PersonOnly.covers(SCHEMA, "/replicas/3/pin")).isTrue();
    assertThat(PersonOnly.covers(SCHEMA, "/shared/mark")).isTrue();
  }

  @Test
  void whatIsNotMarkedIsNotCovered() {
    assertThat(PersonOnly.covers(SCHEMA, "/host")).isFalse();
    assertThat(PersonOnly.covers(SCHEMA, "/hostKeys")).isFalse();
    assertThat(PersonOnly.covers(SCHEMA, "/hostKeys/label")).isFalse();
    assertThat(PersonOnly.covers(SCHEMA, "/jump/hostKeys/label")).isFalse();
    assertThat(PersonOnly.covers(SCHEMA, "/replicas/*/host")).isFalse();
    assertThat(PersonOnly.covers(SCHEMA, "/absent")).isFalse();
    assertThat(PersonOnly.covers(SCHEMA, "")).isFalse();
    assertThat(PersonOnly.covers(null, "/host")).isFalse();
  }

  @Test
  void aValueThatReadsAsTrueMarksAPropertyAndNothingElseDoes() {
    assertThat(PersonOnly.isMarked(JSON.readTree("{\"x-person-only\":true}"))).isTrue();
    // A schema author who quotes the value still means it: an unmarked setting would be the unsafe misreading.
    assertThat(PersonOnly.isMarked(JSON.readTree("{\"x-person-only\":\"true\"}"))).isTrue();
    assertThat(PersonOnly.isMarked(JSON.readTree("{\"x-person-only\":false}"))).isFalse();
    assertThat(PersonOnly.isMarked(JSON.readTree("{\"x-person-only\":\"no\"}"))).isFalse();
    assertThat(PersonOnly.isMarked(JSON.readTree("{}"))).isFalse();
    assertThat(PersonOnly.isMarked(JSON.readTree("true"))).isFalse();
    assertThat(PersonOnly.isMarked(null)).isFalse();
  }
}
