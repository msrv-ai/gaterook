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
  void aMarkBehindAReferenceOrABranchIsListedAndLeftOutOfTheCopyForAnAgent() {
    JsonNode original = JSON.readTree("""
        {"type":"object","required":["host","viaRef"],"properties":{"host":{"type":"string"},
          "viaRef":{"$ref":"#/$defs/pins"},
          "viaAllOfRef":{"allOf":[{"$ref":"#/$defs/pins"}]},
          "viaAnyOfBranch":{"anyOf":[{"type":"null"},{"type":"array","x-person-only":true}]},
          "viaOneOfBranch":{"oneOf":[{"type":"string"},{"$ref":"#/$defs/pins"}]}},
         "$defs":{"pins":{"type":"array","items":{"type":"string"},"x-person-only":true}}}
        """);

    assertThat(PersonOnlyFields.templates(original)).containsExactly("/viaAllOfRef", "/viaAnyOfBranch",
        "/viaOneOfBranch", "/viaRef");
    JsonNode agent = PersonOnlyFields.without(original);
    assertThat(agent.path("properties").propertyNames()).containsExactly("host");
    assertThat(agent.path("required")).isEqualTo(JSON.readTree("[\"host\"]"));
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

  /**
   * A share's layout of the marks: at the top level, a placeholder defined in a conditional branch, inside an object,
   * in array items and as the items themselves, behind a {@code "#/"} reference and behind {@code "#"} (the root).
   */
  private static final String SHARE_SCHEMA = """
      {"type":"object","properties":{
        "host":{"type":"string"},
        "trustedExec":{"type":"string","x-person-only":true},
        "allowExec":true,
        "tls":{"type":"object","properties":{"minVersion":{"type":"string"},
          "pins":{"type":"array","items":{"type":"string"},"x-person-only":true}}},
        "replicas":{"type":"array","items":{"type":"object","properties":{"host":{"type":"string"},
          "fingerprint":{"type":"string","x-person-only":true}}}},
        "trustedKeys":{"type":"array","items":{"type":"string","x-person-only":true}},
        "jump":{"type":"object","properties":{"host":{"type":"string"},"hostKey":{"$ref":"#/$defs/hostKey"}}},
        "next":{"$ref":"#"},
        "trust":{"type":"object","x-person-only":true,"properties":{"clientCert":{"type":"string"},
          "clientKey":{"type":"string","writeOnly":true}}},
        "slots":{"type":"object","properties":{"7":{"type":"string","x-person-only":true}}}},
       "allOf":[{"if":{"properties":{"host":{"const":"local"}}},"then":{"properties":{
         "allowExec":{"type":"boolean","default":false,"x-person-only":true}}}}],
       "$defs":{"hostKey":{"type":"string","x-person-only":true}}}
      """;

  @Test
  void aConfigHoldsEveryMarkedSettingWhereverTheSchemaDefinesItAndWhateverItHolds() {
    JsonNode config = JSON.readTree("""
        {"host":"db.example.test","trustedExec":null,"allowExec":false,
         "tls":{"minVersion":"TLSv1.3","pins":[]},
         "replicas":[{"host":"r1.example.test","fingerprint":""},{"host":"r2.example.test","fingerprint":"ab"}],
         "trustedKeys":["k1","k2"],
         "jump":{"host":"bastion.example.test","hostKey":{}},
         "next":{"trustedExec":"aa","next":{"host":"deep.example.test","trustedExec":"bb"}},
         "trust":{"clientCert":"c"},"slots":{"7":"x"},"unknown":{"trustedExec":"not a property of the schema"}}
        """);

    var held = PersonOnlyFields.held(config, JSON.readTree(SHARE_SCHEMA));

    assertThat(held).extracting(PersonOnlyFields.Held::pointer).containsExactly("/trustedExec", "/allowExec",
        "/tls/pins", "/replicas/0/fingerprint", "/replicas/1/fingerprint", "/trustedKeys/0", "/trustedKeys/1",
        "/jump/hostKey", "/next/trustedExec", "/next/next/trustedExec", "/trust", "/slots/7");
    assertThat(held).extracting(PersonOnlyFields.Held::template).containsExactly("/trustedExec", "/allowExec",
        "/tls/pins", "/replicas/*/fingerprint", "/replicas/*/fingerprint", "/trustedKeys/*", "/trustedKeys/*",
        "/jump/hostKey", "/next/trustedExec", "/next/next/trustedExec", "/trust", "/slots/7");
  }

  /** Every place the list of templates names is one the walk of a config finds. */
  @Test
  void theWalkOfAConfigFindsEverySettingTheTemplatesName() {
    JsonNode schema = JSON.readTree(SHARE_SCHEMA);
    for (String template : PersonOnlyFields.templates(schema)) {
      assertThat(PersonOnlyFields.held(holding(template), schema)).as(template)
          .extracting(PersonOnlyFields.Held::template).containsExactly(template);
    }
    assertThat(PersonOnlyFields.templates(schema)).contains("/next/trustedExec", "/replicas/*/fingerprint",
        "/trustedKeys/*", "/jump/hostKey", "/allowExec");
  }

  /** A template follows a reference to the root once; a config can nest it deeper, and the walk follows it there. */
  @Test
  void aSelfReferenceIsFollowedAsDeepAsTheConfigNests() {
    JsonNode schema = JSON.readTree("""
        {"type":"object","properties":{"pin":{"type":"string","x-person-only":true},"next":{"$ref":"#"},
          "list":{"type":"array","items":{"$ref":"#"}}}}
        """);
    JsonNode config = JSON.readTree("""
        {"next":{"next":{"next":{"pin":"p"}}},"list":[{"list":[{"pin":"q"}]}]}
        """);

    assertThat(PersonOnlyFields.templates(schema)).doesNotContain("/next/next/next/pin");
    assertThat(PersonOnlyFields.held(config, schema)).extracting(PersonOnlyFields.Held::pointer)
        .containsExactly("/next/next/next/pin", "/list/0/list/0/pin");
    assertThat(PersonOnlyFields.covering(schema, "/next/next/next/pin")).contains("/next/next/next/pin");
  }

  @Test
  void whatIsNotMarkedIsNotHeldAndAMarkedRootHoldsEverything() {
    JsonNode schema = JSON.readTree(SHARE_SCHEMA);

    assertThat(PersonOnlyFields.held(JSON.readTree("""
        {"host":"local","tls":{"minVersion":"TLSv1.2"},"replicas":[{"host":"r"}],"jump":{"host":"b"},"next":{}}
        """), schema)).isEmpty();
    assertThat(PersonOnlyFields.held(JSON.readTree("{\"trustedExec\":\"x\"}"), null)).isEmpty();
    assertThat(PersonOnlyFields.held(JSON.readTree("{\"a\":1}"), JSON.readTree("{\"x-person-only\":true}")))
        .containsExactly(new PersonOnlyFields.Held("", ""));
  }

  /** Where a share's credential of a field would go: inside a marked setting is a credential of that setting. */
  @Test
  void aCredentialFieldInsideAMarkedSettingIsCovered() {
    JsonNode schema = JSON.readTree(SHARE_SCHEMA);

    assertThat(PersonOnlyFields.covering(schema, "/trust/clientKey")).contains("/trust");
    assertThat(PersonOnlyFields.covering(schema, "/trust")).contains("/trust");
    assertThat(PersonOnlyFields.covering(schema, "/replicas/3/fingerprint")).contains("/replicas/*/fingerprint");
    assertThat(PersonOnlyFields.covering(schema, "/trustedKeys/12")).contains("/trustedKeys/*");
    assertThat(PersonOnlyFields.covering(schema, "/slots/7")).contains("/slots/7");
    assertThat(PersonOnlyFields.covering(schema, "/next/jump/hostKey")).contains("/next/jump/hostKey");
    assertThat(PersonOnlyFields.covering(schema, "/credentials/password")).isEmpty();
    assertThat(PersonOnlyFields.covering(schema, "/replicas/0/host")).isEmpty();
    assertThat(PersonOnlyFields.covering(schema, "/replicas/name/fingerprint")).isEmpty();
    assertThat(PersonOnlyFields.covering(schema, "credentials")).isEmpty();
  }

  /** A config that holds one value at a pointer template, every {@code *} standing for the first array element. */
  private static JsonNode holding(String template) {
    JsonNode value = JSON.readTree("\"0000\"");
    String[] parts = template.substring(1).split("/", -1);
    for (int i = parts.length - 1; i >= 0; i--) {
      if ("*".equals(parts[i])) {
        value = JSON.createArrayNode().add(value);
      } else {
        value = JSON.createObjectNode().set(SecretFields.unescape(parts[i]), value);
      }
    }
    return value;
  }
}
