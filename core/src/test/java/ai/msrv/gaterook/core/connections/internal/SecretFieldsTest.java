package ai.msrv.gaterook.core.connections.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Where secrets live in a config, and where a connection connects (the "new host" confirmation). */
class SecretFieldsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode j(String text) {
    return JSON.readTree(text);
  }

  @Test
  void dynamicLoginUsersAreScopedToTheReferencedArrayEntry() {
    JsonNode schema = j("""
        {"type":"object","properties":{"accounts":{"type":"array","items":{"$ref":"#/$defs/login"}},
          "other":{"type":"string","writeOnly":true}},
         "$defs":{"login":{"type":"object","x-gateway-secret":"login","required":["user"],
          "properties":{"user":{"type":"string"},"password":{"type":"string","writeOnly":true}}}}}
        """);
    ObjectNode config = (ObjectNode) j("{\"accounts\":[{},{}]}");
    ObjectNode probe = config.deepCopy();
    SecretFields.dynamicLoginUsers(schema, probe, java.util.Set.of("/accounts/0/password", "/other"));
    assertThat(probe.at("/accounts/0/user").asString()).isEqualTo("dynamic-login");
    assertThat(probe.at("/accounts/1/user").isMissingNode()).isTrue();
    assertThat(config.at("/accounts/0/user").isMissingNode()).isTrue();
    assertThat(new ai.msrv.gaterook.core.platform.SchemaValidator().validate(schema, probe))
        .extracting(ai.msrv.gaterook.core.platform.SchemaValidator.Violation::pointer)
        .containsExactly("/accounts/1/user");
    SecretFields.dynamicLoginUsers(schema, config, java.util.Set.of("/accounts/0/user", "/other"));
    assertThat(config.at("/accounts/0/user").isMissingNode()).isTrue();
  }

  @Test
  void writeOnlyFieldsAreFoundThroughObjectsArraysAndBranches() {
    JsonNode schema = j("""
        {"type":"object","properties":{
          "credentials":{"type":"object","properties":{"user":{"type":"string"},
            "password":{"type":"string","writeOnly":true}}},
          "secretHeaders":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},
            "value":{"type":"string","writeOnly":true}}}},
          "source":{"oneOf":[{"properties":{"token":{"type":"string","writeOnly":true}}},
            {"properties":{"a/b":{"type":"string","writeOnly":true}}}]},
          "auth":{"if":{"properties":{"kind":{"const":"key"}}},"then":{"properties":{
            "privateKey":{"type":"string","writeOnly":true}}}}}}""");
    assertThat(SecretFields.templates(schema)).containsExactly("/credentials/password", "/secretHeaders/*/value",
        "/source/token", "/source/a~1b", "/auth/privateKey");
    var templates = SecretFields.templates(schema);
    assertThat(SecretFields.matches(templates, "/secretHeaders/0/value")).isTrue();
    assertThat(SecretFields.matches(templates, "/secretHeaders/12/value")).isTrue();
    assertThat(SecretFields.matches(templates, "/secretHeaders/x/value")).isFalse();
    assertThat(SecretFields.matches(templates, "/secretHeaders/0/name")).isFalse();
    assertThat(SecretFields.matches(templates, "/credentials/user")).isFalse();
  }

  @Test
  void localReferencesAreFollowedOnceAlongAPath() {
    // The SSH bastion reuses the credentials of the host; a schema that refers to itself stops instead of looping.
    JsonNode schema = j("""
        {"type":"object","properties":{
          "credentials":{"type":"object","properties":{"password":{"type":"string","writeOnly":true}}},
          "bastion":{"type":"object","properties":{"credentials":{"$ref":"#/properties/credentials"}}},
          "loop":{"type":"object","properties":{"again":{"$ref":"#/properties/loop"},
            "token":{"type":"string","writeOnly":true}}},
          "elsewhere":{"$ref":"https://example.com/schema.json"}}}""");
    assertThat(SecretFields.templates(schema)).containsExactly("/credentials/password",
        "/bastion/credentials/password", "/loop/again/token", "/loop/token");
  }

  @Test
  void valuesAreFoundAtTemplatesThroughArrays() {
    JsonNode config = j("""
        {"auth":{"type":"apiKeyQuery"},"headers":[{"name":"a"},{"name":"b"},{"other":1}],"a/b":{"c":true}}""");
    assertThat(SchemaPointers.values(config, "/auth/type")).containsExactly(
        java.util.Map.entry("/auth/type", j("\"apiKeyQuery\"")));
    assertThat(SchemaPointers.values(config, "/headers/*/name")).containsOnlyKeys("/headers/0/name",
        "/headers/1/name");
    assertThat(SchemaPointers.values(config, "/a~1b/c")).containsOnlyKeys("/a~1b/c");
    assertThat(SchemaPointers.values(config, "/missing/field")).isEmpty();
  }

  @Test
  void environmentLocksRefuseWhatTheSchemaForbidsThere() {
    JsonNode schema = j("""
        {"type":"object","properties":{
          "auth":{"type":"object","properties":{"type":{"enum":["bearer","apiKeyQuery"],
            "x-forbidden-env-values":{"prod":["apiKeyQuery"]}}}},
          "tls":{"type":"object","properties":{"skipVerify":{"type":"boolean","title":"Skip TLS verification",
            "default":false,"x-forbidden-env":["prod"]}}},
          "bastion":{"type":"object","properties":{"auth":{"$ref":"#/properties/auth"}}}}}""");
    ai.msrv.gaterook.connector.api.Env prod = ai.msrv.gaterook.connector.api.Env.PROD;
    assertThat(EnvLocks.check(schema, j("{\"auth\":{\"type\":\"apiKeyQuery\"}}"), prod)).hasValueSatisfying(v -> {
      assertThat(v.pointer()).isEqualTo("/auth/type");
      assertThat(v.message()).isEqualTo("type can't be apiKeyQuery on prod.");
    });
    assertThat(EnvLocks.check(schema, j("{\"auth\":{\"type\":\"apiKeyQuery\"}}"),
        ai.msrv.gaterook.connector.api.Env.STAGE)).isEmpty();
    assertThat(EnvLocks.check(schema, j("{\"auth\":{\"type\":\"bearer\"}}"), prod)).isEmpty();
    assertThat(EnvLocks.check(schema, j("{\"tls\":{\"skipVerify\":true}}"), prod)).hasValueSatisfying(v ->
        assertThat(v.message()).isEqualTo("Skip TLS verification isn't allowed on prod."));
    assertThat(EnvLocks.check(schema, j("{\"tls\":{\"skipVerify\":false}}"), prod)).as("its default").isEmpty();
    assertThat(EnvLocks.check(schema, j("{\"tls\":{}}"), prod)).isEmpty();
    assertThat(EnvLocks.check(schema, j("{\"bastion\":{\"auth\":{\"type\":\"apiKeyQuery\"}}}"), prod))
        .map(EnvLocks.Violation::pointer).contains("/bastion/auth/type");
  }

  @Test
  void publicIdentifiersAreMarkedByTheirSchema() {
    JsonNode schema = j("""
        {"type":"object","properties":{"credentials":{"type":"object","properties":{
          "accessKeyId":{"type":"string","x-not-secret":true},
          "secretAccessKey":{"type":"string","writeOnly":true}}}}}""");
    assertThat(SecretFields.notSecretTemplates(schema)).containsExactly("/credentials/accessKeyId");
    assertThat(SecretFields.templates(schema)).containsExactly("/credentials/secretAccessKey");
  }

  @Test
  void placeholdersAreFoundStrippedAndNeverAcceptedFromOutside() {
    ObjectNode config = (ObjectNode) j("""
        {"connection":{"host":"db"},"credentials":{"user":"app","password":{"$secret":"sc_1"}},
         "secretHeaders":[{"name":"X-Key","value":{"$secret":"sc_2"}}]}""");
    assertThat(SecretFields.placeholders(config)).containsExactly(
        java.util.Map.entry("/credentials/password", "sc_1"), java.util.Map.entry("/secretHeaders/0/value", "sc_2"));
    JsonNode stripped = SecretFields.strip(config);
    assertThat(stripped.at("/credentials/password").isMissingNode()).isTrue();
    assertThat(stripped.at("/secretHeaders/0/name").asString()).isEqualTo("X-Key");
    assertThat(config.at("/credentials/password/$secret").asString()).as("the original is untouched")
        .isEqualTo("sc_1");
    assertThat(SecretFields.mentionsPlaceholder(j("{\"a\":[{\"$secret\":\"forged\"}]}"))).isTrue();
    assertThat(SecretFields.mentionsPlaceholder(j("{\"a\":[{\"secret\":\"x\"}]}"))).isFalse();
    assertThat(SecretFields.isPlaceholder(j("{\"$secret\":\"sc_1\",\"other\":1}"))).isFalse();
  }

  @Test
  void setCreatesObjectsButNotArrays() {
    ObjectNode root = JSON.createObjectNode();
    assertThat(SecretFields.set(root, "/credentials/password", SecretFields.placeholder("sc_1"))).isTrue();
    assertThat(root.at("/credentials/password/$secret").asString()).isEqualTo("sc_1");
    root.putArray("list").addObject();
    assertThat(SecretFields.set(root, "/list/3/value", j("1"))).as("no element to go through").isFalse();
    assertThat(SecretFields.set(root, "/list/0/value", j("2"))).isTrue();
    assertThat(SecretFields.set(root, "/list/1", j("3"))).as("append").isTrue();
    assertThat(SecretFields.set(root, "/list/5", j("4"))).isFalse();
    assertThat(SecretFields.parentExists(root, "/credentials/password")).isTrue();
    assertThat(SecretFields.parentExists(root, "/nope/password")).isFalse();
  }

  @Test
  void displayNamesReadLikeTheKit() {
    assertThat(SecretFields.displayName("/credentials/password")).isEqualTo("password");
    assertThat(SecretFields.displayName("/credentials/secretAccessKey")).isEqualTo("secret-access-key");
    assertThat(SecretFields.displayName("/secretHeaders/0/value")).isEqualTo("secret-headers-0-value");
    assertThat(SecretFields.displayName("/auth")).isEqualTo("secret");
  }

  @Test
  void targetsAreWhereAConnectionConnects() {
    JsonNode before = j("{\"connection\":{\"host\":\"db.dev\",\"port\":5432,\"database\":\"app\"},"
        + "\"credentials\":{\"user\":\"u\",\"password\":{\"$secret\":\"sc\"}}}");
    assertThat(ConnectionTargets.of(before)).containsExactly(java.util.Map.entry("/connection/host", "db.dev"));
    assertThat(ConnectionTargets.display(before)).isEqualTo("db.dev:5432/app");
    assertThat(ConnectionTargets.changedTarget(before, before)).isEmpty();
    JsonNode moved = j("{\"connection\":{\"host\":\"db.evil\",\"port\":5432}}");
    assertThat(ConnectionTargets.changedTarget(before, moved)).contains("db.evil");
    assertThat(ConnectionTargets.changedTarget(j("{\"baseUrls\":[\"https://a\"]}"),
        j("{\"baseUrls\":[\"https://a\",\"https://b\"]}"))).contains("https://b");
    assertThat(ConnectionTargets.display(j("{\"connection\":{\"uri\":\"mongodb://user:pw@mongo.dev:27017/app"
        + "?tls=true\"}}"))).as("no scheme, user info or query").isEqualTo("mongo.dev:27017/app");
    assertThat(ConnectionTargets.display(j("{\"regions\":[\"eu-central-1\"]}"))).isEqualTo("eu-central-1");
    assertThat(ConnectionTargets.display(j("{}"))).isEmpty();
  }

  @Test
  void theListShowsTargetsInTheKitsWords() {
    JsonNode sshSchema = j("{\"properties\":{\"connection\":{\"properties\":{\"port\":{\"type\":\"integer\","
        + "\"default\":22}}}}}");
    assertThat(ConnectionTargets.display(j("{\"connection\":{\"host\":\"10.20.0.14\",\"username\":\"gw\"}}"),
        sshSchema, null)).as("the form's default port").isEqualTo("10.20.0.14:22");
    assertThat(ConnectionTargets.display(j("{\"backend\":\"loki\",\"url\":\"http://loki.dev.internal:3100/\"}")))
        .isEqualTo("loki.dev.internal:3100");
    assertThat(ConnectionTargets.display(j("{\"server\":\"https://k8s.dev.internal:6443\",\"namespace\":\"app-dev\"}")))
        .isEqualTo("k8s.dev.internal:6443 · ns app-dev");
    assertThat(ConnectionTargets.display(j("{\"baseUrls\":[\"https://billing.stage.acme.dev\"]}")))
        .as("an HTTP API keeps its base URL").isEqualTo("https://billing.stage.acme.dev");
    assertThat(ConnectionTargets.display(j("{\"transport\":\"http\",\"url\":\"https://mcp.example.com/mcp\"}")))
        .isEqualTo("https://mcp.example.com/mcp");
    assertThat(ConnectionTargets.display(j("{\"regions\":[\"eu-central-1\"],\"accountAlias\":\"acme-stage\"}")))
        .isEqualTo("eu-central-1 · acme-stage");
    assertThat(ConnectionTargets.display(j("{\"regions\":[\"eu-central-1\"]}"), null,
        j("{\"account\":\"123456789012\"}"))).as("the account the last test signed in to")
        .isEqualTo("eu-central-1 · 123456789012");
    assertThat(ConnectionTargets.display(j("{\"backend\":\"gcp-logging\",\"resourceNames\":[\"projects/acme-prod\"]}")))
        .isEqualTo("projects/acme-prod");
    assertThat(ConnectionTargets.display(j("{\"backend\":\"cloudwatch\",\"region\":\"eu-west-1\"}")))
        .isEqualTo("eu-west-1");
  }

  /** Review API #5: never user info, a query or a fragment — however the URI was typed. */
  @Test
  void theListNeverShowsUserInfoOrAQuery() {
    assertThat(ConnectionTargets.display(j("{\"connection\":{\"uri\":\"mongodb://gw:p@ss@mongo.dev:27017/app\"}}")))
        .as("a password with an unescaped @").isEqualTo("mongo.dev:27017/app");
    assertThat(ConnectionTargets.display(j("{\"transport\":\"http\",\"url\":\"https://mcp.example.com/mcp"
        + "?apiKey=cnry-7f3a#top\"}"))).isEqualTo("https://mcp.example.com/mcp");
    assertThat(ConnectionTargets.display(j("{\"transport\":\"http\",\"url\":\"https://u:p@ss@mcp.example.com/mcp\"}")))
        .isEqualTo("https://mcp.example.com/mcp");
    assertThat(ConnectionTargets.display(j("{\"transport\":\"http\",\"url\":\"https://mcp.example.com/@acme/mcp\"}")))
        .as("an @ of the path is no user info").isEqualTo("https://mcp.example.com/@acme/mcp");
    assertThat(ConnectionTargets.display(j("{\"regions\":[\"eu-central-1\"]}"), null,
        j("{\"account\":\"412398765012\\n\\u202Eevil" + "9".repeat(200) + "\"}")))
        .as("what a test's STS answer said: one short line").startsWith("eu-central-1 · 412398765012 \\u202Eevil")
        .doesNotContain("\n").hasSizeLessThanOrEqualTo("eu-central-1 · ".length() + 64);
  }
}
