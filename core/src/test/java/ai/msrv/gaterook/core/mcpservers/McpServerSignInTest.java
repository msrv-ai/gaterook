package ai.msrv.gaterook.core.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.ProblemException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The sign-in of an MCP server's config, as adding it and editing it in Connections both check it (ARCHITECTURE.md
 * §7.5, §7.6): over HTTP only, never beside its own Authorization header, cloud credentials for a cloud sign-in only.
 * The connection form sends nested objects with their defaults, so credentials holding nothing else go with another
 * sign-in instead of being refused.
 */
class McpServerSignInTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** As much of the type's schema as the checks read: the credentials of the HTTP branch's sign-in. */
  private static final JsonNode SCHEMA = JSON.readTree("""
      {"type": "object", "allOf": [
       {"if": {"properties": {"transport": {"const": "stdio"}}}, "then": {"properties": {"command": {"type": "array"}}}},
       {"if": {"properties": {"transport": {"const": "http"}}}, "then": {"properties": {
        "auth": {"type": "object", "required": ["type"], "properties": {"type": {"enum": ["oauth", "google", "aws"]},
          "credentials": {"type": "object", "properties": {
            "source": {"enum": ["adc", "serviceAccountKey", "profile", "accessKey"]}, "profile": {"type": "string"},
            "lifetimeSeconds": {"type": "integer", "default": 900}, "sessionSeconds": {"type": "integer", "default": 900}
          }}}}}}}]}""");

  private static ObjectNode http(String auth) {
    return (ObjectNode) JSON.readTree("{\"transport\": \"http\", \"url\": \"https://mcp.example.test/mcp\", \"auth\": "
        + auth + "}");
  }

  /** The keys the sign-in asks for, after it was checked. */
  private static List<String> keys(ObjectNode config) {
    List<String> keys = new ArrayList<>();
    McpServerService.signIn(config, McpServerService.credentialMembers(SCHEMA), keys::add);
    return keys;
  }

  @Test
  void theCredentialsAreFoundInTheBranchThatDeclaresThem() {
    assertThat(McpServerService.credentialMembers(SCHEMA).at("/lifetimeSeconds/default").asInt()).isEqualTo(900);
    assertThat(McpServerService.credentialMembers(JSON.readTree("{\"type\": \"object\"}")).isMissingNode()).isTrue();
  }

  @Test
  void credentialsHoldingTheirDefaultsAloneGoWithAnotherSignIn() {
    ObjectNode c = http("{\"type\": \"oauth\", \"callbackPath\": \"/callback\", "
        + "\"credentials\": {\"lifetimeSeconds\": 900, \"sessionSeconds\": 900}}");
    assertThat(keys(c)).isEmpty();
    assertThat(c.path("auth").has("credentials")).isFalse();
    assertThat(c.at("/auth/callbackPath").asString()).isEqualTo("/callback");
  }

  @Test
  void cloudSettingsAPersonGaveAnotherSignInAreRefused() {
    for (String auth : List.of("{\"type\": \"oauth\", \"credentials\": {\"profile\": \"default\"}}",
        "{\"type\": \"oauth\", \"credentials\": {\"lifetimeSeconds\": 600}}",
        "{\"type\": \"oauth\", \"region\": \"us-east-1\"}")) {
      assertThatThrownBy(() -> keys(http(auth))).as(auth).isInstanceOf(ProblemException.class)
          .hasMessageContaining("belong to a server that signs in with Google Cloud or AWS credentials");
    }
  }

  @Test
  void aCloudSignInKeepsItsCredentialsAndNamesTheKeyTheyNeed() {
    ObjectNode adc = http("{\"type\": \"google\", \"credentials\": {\"lifetimeSeconds\": 900}}");
    assertThat(keys(adc)).isEmpty();
    assertThat(adc.at("/auth/credentials/source").asString()).isEqualTo("adc");

    ObjectNode key = http("{\"type\": \"google\", \"credentials\": {\"source\": \"serviceAccountKey\", "
        + "\"serviceAccountKey\": \"typed into the config\"}}");
    assertThat(keys(key)).containsExactly(McpServerService.SERVICE_ACCOUNT_KEY);
    assertThat(key.at("/auth/credentials").has("serviceAccountKey")).isFalse();

    assertThatThrownBy(() -> keys(http("{\"type\": \"aws\"}"))).isInstanceOf(ProblemException.class)
        .hasMessageContaining("Choose where GateRook takes the AWS credentials from");
  }

  @Test
  void onlyAServerAtAUrlSignsInAndNeverBesideItsOwnAuthorizationHeader() {
    ObjectNode stdio = (ObjectNode) JSON.readTree("{\"transport\": \"stdio\", \"command\": [\"node\", \"server.js\"], "
        + "\"auth\": {\"type\": \"oauth\"}}");
    assertThatThrownBy(() -> keys(stdio)).hasMessageContaining("only a server at a URL signs in");

    ObjectNode both = http("{\"type\": \"oauth\"}");
    both.putArray("headers").addObject().put("name", "Authorization");
    assertThatThrownBy(() -> keys(both)).hasMessageContaining("The OAuth sign-in sends the Authorization header");

    ObjectNode none = (ObjectNode) JSON.readTree("{\"transport\": \"http\", \"url\": \"https://mcp.example.test/mcp\"}");
    assertThat(keys(none)).isEmpty();
    assertThat(none.has("auth")).isFalse();
  }
}
