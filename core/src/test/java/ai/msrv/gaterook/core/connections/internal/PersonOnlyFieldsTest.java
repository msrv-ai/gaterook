package ai.msrv.gaterook.core.connections.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
   * in array items and as the items themselves, behind a {@code "#/"} reference and behind {@code "#"} (the root),
   * where only a {@code oneOf} branch, an {@code anyOf} branch or an {@code else} branch defines one, and inside a
   * marked object ({@code /trust/pin}).
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
          "clientKey":{"type":"string","writeOnly":true},"pin":{"type":"string","x-person-only":true}}},
        "slots":{"type":"object","properties":{"7":{"type":"string","x-person-only":true}}},
        "proxy":{"oneOf":[{"type":"null"},{"type":"object","properties":{"host":{"type":"string"},
          "pin":{"type":"string","x-person-only":true}}}]},
        "account":{"anyOf":[{"type":"integer"},{"type":"string","x-person-only":true}]}},
       "allOf":[{"if":{"properties":{"host":{"const":"local"}}},"then":{"properties":{
         "allowExec":{"type":"boolean","default":false,"x-person-only":true}}}},
        {"if":{"properties":{"host":{"const":"local"}}},"then":{},"else":{"properties":{
         "remote":{"type":"object","properties":{"user":{"type":"string"},
           "key":{"type":"string","x-person-only":true}}}}}}],
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
         "trust":{"clientCert":"c","pin":"p"},"slots":{"7":"x"},
         "proxy":{"host":"proxy.example.test","pin":null},"account":7,"remote":{"user":"u","key":""},
         "unknown":{"trustedExec":"not a property of the schema"}}
        """);

    var held = PersonOnlyFields.held(config, JSON.readTree(SHARE_SCHEMA));

    assertThat(held).extracting(PersonOnlyFields.Held::pointer).containsExactly("/trustedExec", "/allowExec",
        "/tls/pins", "/replicas/0/fingerprint", "/replicas/1/fingerprint", "/trustedKeys/0", "/trustedKeys/1",
        "/jump/hostKey", "/next/trustedExec", "/next/next/trustedExec", "/trust", "/slots/7", "/proxy/pin",
        "/account", "/remote/key");
    assertThat(held).extracting(PersonOnlyFields.Held::template).containsExactly("/trustedExec", "/allowExec",
        "/tls/pins", "/replicas/*/fingerprint", "/replicas/*/fingerprint", "/trustedKeys/*", "/trustedKeys/*",
        "/jump/hostKey", "/next/trustedExec", "/next/next/trustedExec", "/trust", "/slots/7", "/proxy/pin",
        "/account", "/remote/key");
  }

  /**
   * Every place the list of templates names is one the walk of a config finds: the setting itself, or the marked
   * setting that holds it.
   */
  @Test
  void theWalkOfAConfigFindsEverySettingTheTemplatesName() {
    JsonNode schema = JSON.readTree(SHARE_SCHEMA);
    List<String> templates = PersonOnlyFields.templates(schema);
    for (String template : templates) {
      // Sorted, so the first one that is the template or contains it is the outermost.
      String outermost = templates.stream().filter(t -> template.equals(t) || template.startsWith(t + "/"))
          .findFirst().orElseThrow();
      assertThat(PersonOnlyFields.held(holding(template), schema)).as(template)
          .extracting(PersonOnlyFields.Held::template).containsExactly(outermost);
    }
    assertThat(templates).contains("/next/trustedExec", "/replicas/*/fingerprint", "/trustedKeys/*", "/jump/hostKey",
        "/allowExec", "/proxy/pin", "/account", "/remote/key", "/trust", "/trust/pin");
  }

  /** A marked value is not looked into: a mark inside it is part of that one decision, held and covered as it. */
  @Test
  void aMarkInsideAMarkedSettingIsPartOfIt() {
    JsonNode schema = JSON.readTree(SHARE_SCHEMA);

    assertThat(PersonOnlyFields.held(JSON.readTree("{\"trust\":{\"clientCert\":\"c\",\"pin\":\"p\"}}"), schema))
        .containsExactly(new PersonOnlyFields.Held("/trust", "/trust"));
    assertThat(PersonOnlyFields.held(JSON.readTree("{\"trust\":{\"pin\":null}}"), schema))
        .containsExactly(new PersonOnlyFields.Held("/trust", "/trust"));
    assertThat(PersonOnlyFields.covering(schema, "/trust/pin")).contains("/trust");
  }

  /**
   * A list given as an object is looked into as one: each member counts as an item, whatever its name, since whoever
   * iterates the value reads it so, and a schema without {@code "type": "array"} lets the object through.
   */
  @Test
  void anObjectWhereTheSchemaGivesArrayItemsIsLookedIntoAsAList() {
    JsonNode schema = JSON.readTree(SHARE_SCHEMA);
    JsonNode untyped = JSON.readTree("""
        {"type":"object","properties":{"keys":{"items":{"type":"string","x-person-only":true}},
          "hosts":{"items":{"type":"object","properties":{"name":{"type":"string"},
            "key":{"type":"string","x-person-only":true}}}}}}
        """);

    assertThat(PersonOnlyFields.held(JSON.readTree("""
        {"trustedKeys":{"0":"key-elsewhere","first":"other"},"replicas":{"r1":{"host":"h","fingerprint":"f"}}}
        """), schema)).containsExactly(new PersonOnlyFields.Held("/trustedKeys/0", "/trustedKeys/*"),
            new PersonOnlyFields.Held("/trustedKeys/first", "/trustedKeys/*"),
            new PersonOnlyFields.Held("/replicas/r1/fingerprint", "/replicas/*/fingerprint"));
    assertThat(PersonOnlyFields.held(JSON.readTree("""
        {"keys":{"0":"key-elsewhere"},"hosts":{"a":{"name":"n","key":"k"},"b":{"name":"m"}}}
        """), untyped)).containsExactly(new PersonOnlyFields.Held("/keys/0", "/keys/*"),
            new PersonOnlyFields.Held("/hosts/a/key", "/hosts/*/key"));
    // A member's own property keeps its name where the definitions give no items.
    assertThat(PersonOnlyFields.held(JSON.readTree("{\"slots\":{\"7\":\"x\",\"8\":\"y\"}}"), schema))
        .containsExactly(new PersonOnlyFields.Held("/slots/7", "/slots/7"));
  }

  /**
   * A definition that two places refer to is looked at once where a value meets both: here the root and its
   * {@code allOf} branch both define {@code next} as the root, and a config that nests it 30 deep is checked with a few
   * definitions at each level, not with twice as many as at the one above.
   */
  @Test
  void aDefinitionReachedTwiceIsLookedAtOnce() {
    JsonNode schema = JSON.readTree("""
        {"type":"object","properties":{"next":{"$ref":"#"},"pin":{"type":"string","x-person-only":true}},
         "allOf":[{"properties":{"next":{"$ref":"#"}}}]}
        """);
    ObjectNode config = JSON.createObjectNode();
    ObjectNode level = config;
    StringBuilder pointer = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      level = level.putObject("next");
      pointer.append("/next");
    }
    level.put("pin", "p");
    pointer.append("/pin");

    assertThat(PersonOnlyFields.held(config, schema))
        .containsExactly(new PersonOnlyFields.Held(pointer.toString(), pointer.toString()));
    assertThat(PersonOnlyFields.covering(schema, pointer.toString())).contains(pointer.toString());
  }

  /**
   * A mark that only one branch reaches counts like any other: a {@code oneOf} branch defines a marked property of an
   * object, an {@code anyOf} branch marks the value itself, and only an {@code else} branch defines its object.
   */
  @Test
  void aMarkThatOnlyAOneOfAnAnyOfOrAnElseBranchReachesIsHeldAndCovers() {
    JsonNode schema = JSON.readTree(SHARE_SCHEMA);

    assertThat(PersonOnlyFields.held(JSON.readTree("{\"proxy\":{\"host\":\"p\",\"pin\":\"x\"}}"), schema))
        .containsExactly(new PersonOnlyFields.Held("/proxy/pin", "/proxy/pin"));
    assertThat(PersonOnlyFields.held(JSON.readTree("{\"account\":123456789012}"), schema))
        .containsExactly(new PersonOnlyFields.Held("/account", "/account"));
    assertThat(PersonOnlyFields.held(JSON.readTree("{\"remote\":{\"user\":\"u\",\"key\":\"k\"}}"), schema))
        .containsExactly(new PersonOnlyFields.Held("/remote/key", "/remote/key"));
    assertThat(PersonOnlyFields.covering(schema, "/proxy/pin")).contains("/proxy/pin");
    assertThat(PersonOnlyFields.covering(schema, "/account")).contains("/account");
    assertThat(PersonOnlyFields.covering(schema, "/remote/key")).contains("/remote/key");
    assertThat(PersonOnlyFields.covering(schema, "/proxy/host")).isEmpty();
    assertThat(PersonOnlyFields.covering(schema, "/remote/user")).isEmpty();
  }

  /**
   * A schema the walk cannot check within its bounds is refused, never checked part way; one within them is checked to
   * its end. One value's definitions unfold at most 32 references or branches deep and into fewer than 20,000
   * definitions, and one walk looks at no more than a million.
   */
  @Test
  void aSchemaTooComplexToCheckIsRefusedAndOneWithinTheBoundsIsCheckedToItsEnd() {
    JsonNode deep = JSON.readTree("{\"deep\":\"x\"}");
    assertThat(PersonOnlyFields.held(deep, chained(30))).containsExactly(new PersonOnlyFields.Held("/deep", "/deep"));
    assertThat(PersonOnlyFields.covering(chained(30), "/deep")).contains("/deep");
    assertThatThrownBy(() -> PersonOnlyFields.held(deep, chained(40))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PersonOnlyFields.covering(chained(40), "/deep"))
        .isInstanceOf(IllegalArgumentException.class);

    JsonNode wide = JSON.readTree("{\"wide\":\"x\"}");
    assertThat(PersonOnlyFields.held(wide, branched(10_000)))
        .containsExactly(new PersonOnlyFields.Held("/wide", "/wide"));
    assertThat(PersonOnlyFields.covering(branched(10_000), "/wide")).contains("/wide");
    assertThatThrownBy(() -> PersonOnlyFields.held(wide, branched(20_001)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PersonOnlyFields.covering(branched(20_001), "/wide"))
        .isInstanceOf(IllegalArgumentException.class);

    // Each item has 10,001 definitions: within the bounds for one value, not for 120 of them.
    assertThat(PersonOnlyFields.held(listOf(90), itemsWith(10_000)))
        .containsExactly(new PersonOnlyFields.Held("/list/0/pin", "/list/*/pin"));
    assertThatThrownBy(() -> PersonOnlyFields.held(listOf(120), itemsWith(10_000)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** A schema whose marked {@code /deep} is defined behind a chain of {@code links} references. */
  private static JsonNode chained(int links) {
    ObjectNode schema = JSON.createObjectNode().put("type", "object");
    schema.putObject("properties").putObject("deep").put("$ref", "#/$defs/d0");
    ObjectNode defs = schema.putObject("$defs");
    for (int i = 0; i < links; i++) {
      defs.putObject("d" + i).put("$ref", "#/$defs/d" + (i + 1));
    }
    defs.putObject("d" + links).put("type", "string").put("x-person-only", true);
    return schema;
  }

  /** A schema whose {@code /wide} has {@code branches} {@code allOf} branches, the last one marked. */
  private static JsonNode branched(int branches) {
    ObjectNode schema = JSON.createObjectNode().put("type", "object");
    ArrayNode allOf = schema.putObject("properties").putObject("wide").putArray("allOf");
    for (int i = 1; i < branches; i++) {
      allOf.addObject();
    }
    allOf.addObject().put("x-person-only", true);
    return schema;
  }

  /** A schema of a {@code /list} whose items have {@code branches} empty {@code allOf} branches and a marked pin. */
  private static JsonNode itemsWith(int branches) {
    ObjectNode schema = JSON.createObjectNode().put("type", "object");
    ObjectNode items = schema.putObject("properties").putObject("list").put("type", "array").putObject("items");
    items.putObject("properties").putObject("pin").put("type", "string").put("x-person-only", true);
    ArrayNode allOf = items.putArray("allOf");
    for (int i = 0; i < branches; i++) {
      allOf.addObject();
    }
    return schema;
  }

  /** A config whose {@code /list} has {@code size} items, the first one holding a pin. */
  private static JsonNode listOf(int size) {
    ObjectNode config = JSON.createObjectNode();
    ArrayNode list = config.putArray("list");
    list.addObject().put("pin", "p");
    for (int i = 1; i < size; i++) {
      list.addObject();
    }
    return config;
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
        {"host":"local","tls":{"minVersion":"TLSv1.2"},"replicas":[{"host":"r"}],"jump":{"host":"b"},"next":{},
         "proxy":{"host":"p"},"remote":{"user":"u"}}
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
