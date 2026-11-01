package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BridgeClientTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void readsTheCapabilitiesAndClientInfoTheBridgeRepeats() {
    BridgeClient declared = parse(encode("{\"capabilities\":{\"extensions\":{\"io.modelcontextprotocol/ui\":"
        + "{\"mimeTypes\":[\"text/html;profile=mcp-app\"]}}},\"clientInfo\":{\"name\":\"claude-ai\","
        + "\"version\":\"0.1.0\"}}")).orElseThrow();
    assertThat(declared.capabilities().at("/extensions/io.modelcontextprotocol~1ui/mimeTypes/0").asString())
        .isEqualTo("text/html;profile=mcp-app");
    assertThat(declared.clientInfo().path("name").asString()).isEqualTo("claude-ai");

    BridgeClient bare = parse(encode("{\"capabilities\":{}}")).orElseThrow();
    assertThat(bare.capabilities().isEmpty()).isTrue();
    assertThat(bare.clientInfo()).isNull();
  }

  @Test
  void anythingElseIsNoDeclaration() {
    assertThat(parse(null)).isEmpty();
    assertThat(parse("")).isEmpty();
    assertThat(parse("not base64url!")).isEmpty();
    String standard = Base64.getEncoder().encodeToString("{\"capabilities\":{\"a??\":{}}}"
        .getBytes(StandardCharsets.UTF_8));
    assertThat(standard).contains("/");
    assertThat(parse(standard)).as("standard base64").isEmpty();
    assertThat(parse(encode("not json"))).isEmpty();
    assertThat(parse(encode("[]"))).isEmpty();
    assertThat(parse(encode("{}"))).as("capabilities are required").isEmpty();
    assertThat(parse(encode("{\"capabilities\":[]}"))).isEmpty();
    assertThat(parse(encode("{\"capabilities\":{},\"clientInfo\":\"claude\"}"))).isEmpty();
    String padded = "{\"capabilities\":{\"experimental\":{\"x\":\"" + "y".repeat(3_100) + "\"}}}";
    assertThat(encode(padded).length()).isGreaterThan(BridgeClient.MAX_LENGTH);
    assertThat(parse(encode(padded))).isEmpty();
  }

  private static Optional<BridgeClient> parse(String value) {
    return BridgeClient.parse(value, JSON);
  }

  private static String encode(String json) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
