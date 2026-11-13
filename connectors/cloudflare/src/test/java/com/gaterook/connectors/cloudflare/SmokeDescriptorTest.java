package com.gaterook.connectors.cloudflare;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolCall;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The native smoke's touches of this module hold on the JVM too: the same descriptor, the same answers. */
class SmokeDescriptorTest {

  @Test
  void everyTouchGivesItsExpectedAnswerWithoutARequest() throws Exception {
    JsonNode smoke;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(
        "META-INF/mcp-gateway/smoke/cloudflare.json")) {
      assertThat(in).isNotNull();
      smoke = JsonMapper.builder().build().readTree(in);
    }
    assertThat(smoke.path("area").asString()).isEqualTo("cloudflare");
    assertThat(smoke.path("connectorTypes").get(0).asString()).isEqualTo("cloudflare");
    int checked = 0;
    try (CloudflareConnector connector = new CloudflareConnector()) {
      for (JsonNode touch : smoke.path("touches")) {
        JsonNode c = touch.path("connection");
        Env env = Env.of(c.path("env").asString());
        ConnectionConfig cfg = new ConnectionConfig("smoke-" + touch.path("name").asString(), "dev-cf",
            c.path("type").asString(), env, 1, c.path("config"), Set.of(CloudflareSettings.TOKEN), null);
        ToolCall call = new ToolCall("tc_smoke", touch.at("/call/tool").asString(), "dev-cf",
            (ObjectNode) touch.at("/call/arguments"), null);
        Classification cl = connector.classify(call, cfg, CloudflareTestBase.policy(env));
        assertThat(cl.kind()).isEqualTo(Classification.Kind.DENY);
        assertThat("Denied by policy " + cl.ruleId() + ": " + cl.reason())
            .startsWith(touch.at("/expect/textStartsWith").asString());
        checked++;
      }
    }
    assertThat(checked).isEqualTo(2);
  }
}
