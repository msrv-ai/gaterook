package com.gaterook.connectors.snowflake;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.ToolCall;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The native smoke's touches of this module hold on the JVM too: the same descriptor, the same answers. */
class SmokeDescriptorTest {

  @Test
  void everyTouchWithoutAFixtureGivesItsExpectedAnswer() throws Exception {
    JsonNode smoke;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(
        "META-INF/mcp-gateway/smoke/snowflake.json")) {
      assertThat(in).isNotNull();
      smoke = JsonMapper.builder().build().readTree(in);
    }
    assertThat(smoke.path("area").asString()).isEqualTo("snowflake");
    assertThat(smoke.path("connectorTypes").get(0).asString()).isEqualTo("snowflake");
    int checked = 0;
    try (SnowflakeConnector connector = new SnowflakeConnector()) {
      for (JsonNode touch : smoke.path("touches")) {
        JsonNode c = touch.path("connection");
        Env env = Env.of(c.path("env").asString());
        ConnectionConfig cfg = new ConnectionConfig("smoke-" + touch.path("name").asString(), "dev-sf",
            c.path("type").asString(), env, 1, c.path("config"), Set.of(SnowflakeSettings.PRIVATE_KEY,
            SnowflakeSettings.TOKEN), null);
        TestSecrets secrets = new TestSecrets();
        for (Map.Entry<String, JsonNode> s : c.path("secrets").properties()) {
          secrets.with(s.getKey(), s.getValue().asString());
        }
        JsonNode expect = touch.path("expect");
        if (touch.path("test").asBoolean(false)) {
          TestResult r = connector.test(cfg, secrets, TestCtx.of());
          assertThat(r.ok()).isEqualTo(expect.path("ok").asBoolean());
          assertThat(r.error().code()).isEqualTo(expect.path("errorCode").asString());
        } else {
          ToolCall call = new ToolCall("tc_smoke", touch.at("/call/tool").asString(), "dev-sf",
              (ObjectNode) touch.at("/call/arguments"), null);
          Classification cl = connector.classify(call, cfg, new PolicyView(env, env.id() + ".snowflake",
              JsonNodeFactory.instance.objectNode(), TestCtx.dev()));
          assertThat(cl.kind()).isEqualTo(Classification.Kind.DENY);
          assertThat("Denied by policy " + cl.ruleId() + ": " + cl.reason())
              .startsWith(expect.path("textStartsWith").asString());
        }
        checked++;
      }
    }
    assertThat(checked).isEqualTo(2);
  }
}
