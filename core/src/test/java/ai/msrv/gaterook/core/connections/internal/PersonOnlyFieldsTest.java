package ai.msrv.gaterook.core.connections.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PersonOnlyFieldsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Kubernetes' layout (placeholders, definitions in a branch) plus SSH's (a shared object behind a $ref). */
  private static final String SCHEMA = """
      {"type":"object","required":["host","trustedExec"],"properties":{
        "host":{"type":"string"},
        "trustedExec":true,"allowExec":true,
        "hostKeys":{"type":"object","required":["pinned"],"properties":{
          "pinned":{"type":"array","x-person-only":true,"items":{"type":"string"}},
          "label":{"type":"string"}}},
        "jump":{"type":"object","properties":{"hostKeys":{"$ref":"#/properties/hostKeys"}}},
        "credentials":{"$ref":"#/$defs/credentials"},
        "backup":{"$ref":"#/$defs/credentials"}},
       "allOf":[{"if":{"properties":{"host":{"const":"a"}}},"then":{"properties":{
         "trustedExec":{"type":"string","x-person-only":true},
         "allowExec":{"type":"boolean","default":false,"x-person-only":true}}}}],
       "$defs":{"credentials":{"type":"object","properties":{"user":{"type":"string"},
         "keyPath":{"type":"string","x-person-only":true}}}}}
      """;

  @Test
  void listsEverySettingOnlyThePersonMakesWhereverItIsDefined() {
    assertThat(PersonOnlyFields.templates(JSON.readTree(SCHEMA))).containsExactly("/allowExec", "/backup/keyPath",
        "/credentials/keyPath", "/hostKeys/pinned", "/jump/hostKeys/pinned", "/trustedExec");
    assertThat(PersonOnlyFields.templates(JSON.readTree("{\"type\":\"object\"}"))).isEmpty();
    assertThat(PersonOnlyFields.templates(null)).isEmpty();
  }

  @Test
  void theCopyForAnAgentLeavesThemOutOfPropertiesPlaceholdersBranchesAndRequiredLists() {
    JsonNode original = JSON.readTree(SCHEMA);

    JsonNode agent = PersonOnlyFields.without(original);

    assertThat(agent.toString()).doesNotContain("x-person-only", "trustedExec", "allowExec", "pinned", "keyPath");
    assertThat(agent.at("/properties/hostKeys/properties/label/type").asString()).isEqualTo("string");
    assertThat(agent.at("/properties/jump/properties/hostKeys/$ref").asString()).isEqualTo("#/properties/hostKeys");
    assertThat(agent.at("/$defs/credentials/properties/user/type").asString()).isEqualTo("string");
    assertThat(agent.path("required")).isEqualTo(JSON.readTree("[\"host\"]"));
    assertThat(agent.at("/properties/hostKeys/required")).isEqualTo(JSON.readTree("[]"));
    // Nothing of the original changed: the form and the validation still read it.
    assertThat(original).isEqualTo(JSON.readTree(SCHEMA));
    assertThat(PersonOnlyFields.templates(agent)).isEmpty();
  }

  @Test
  void aSchemaWithoutThemIsCopiedAsItIs() {
    JsonNode plain = JSON.readTree("{\"type\":\"object\",\"properties\":{\"host\":{\"type\":\"string\"}}}");

    assertThat(PersonOnlyFields.without(plain)).isEqualTo(plain).isNotSameAs(plain);
    assertThat(PersonOnlyFields.without(null)).isNull();
  }

  @Test
  void aSchemaThatReferencesItselfEndsTheWalk() {
    JsonNode loop = JSON.readTree("""
        {"type":"object","properties":{"child":{"$ref":"#"},"pin":{"type":"string","x-person-only":true}}}
        """);

    assertThat(PersonOnlyFields.templates(loop)).contains("/pin");
    assertThat(PersonOnlyFields.without(loop).toString()).doesNotContain("x-person-only");
  }
}
