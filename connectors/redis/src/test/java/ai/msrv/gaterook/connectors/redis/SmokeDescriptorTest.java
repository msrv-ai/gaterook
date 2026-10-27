package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.io.InputStream;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native smoke's touches of this module ({@code META-INF/mcp-gateway/smoke/redis.json}, ARCHITECTURE.md §10 N1)
 * hold on the JVM: the ones without a fixture run here as the smoke would run them in the image; the fixture one runs
 * in {@code SmokeFixtureIT} against a real redis-server.
 */
class SmokeDescriptorTest {

  static JsonNode descriptor() throws Exception {
    try (InputStream in = SmokeDescriptorTest.class.getClassLoader()
        .getResourceAsStream("META-INF/mcp-gateway/smoke/redis.json")) {
      assertThat(in).isNotNull();
      return TestCtx.JSON.readTree(in);
    }
  }

  /**
   * A touch's connection as the smoke saves it: {@code ${fixture.*}} replaced by {@code fixture}; a placeholder that
   * is a whole JSON string and whose value is a whole number becomes that number (a port).
   */
  static ConnectionConfig connection(JsonNode touch, Map<String, String> fixture) {
    JsonNode c = touch.path("connection");
    String config = c.path("config").toString();
    for (Map.Entry<String, String> f : fixture.entrySet()) {
      String whole = "\"${fixture." + f.getKey() + "}\"";
      if (f.getValue().matches("\\d+")) {
        config = config.replace(whole, f.getValue());
      }
      config = config.replace("${fixture." + f.getKey() + "}", jsonEscaped(f.getValue()));
    }
    Set<String> secretFields = new HashSet<>();
    c.path("secrets").propertyNames().forEach(secretFields::add);
    return new ConnectionConfig("smoke-" + touch.path("name").asString(), "smoke-redis", c.path("type").asString(),
        Env.of(c.path("env").asString()), 1, TestCtx.JSON.readTree(config), secretFields, null);
  }

  static TestSecrets secrets(JsonNode touch, Map<String, String> fixture) {
    TestSecrets s = new TestSecrets();
    touch.at("/connection/secrets").properties().forEach(e -> {
      String v = e.getValue().asString();
      for (Map.Entry<String, String> f : fixture.entrySet()) {
        v = v.replace("${fixture." + f.getKey() + "}", f.getValue());
      }
      s.with(e.getKey(), v);
    });
    return s;
  }

  private static String jsonEscaped(String v) {
    String quoted = TestCtx.JSON.writeValueAsString(v);
    return quoted.substring(1, quoted.length() - 1);
  }

  @Test
  void touchesWithoutAServerAnswerAsTheDescriptorSays() throws Exception {
    JsonNode smoke = descriptor();
    assertThat(smoke.path("area").asString()).isEqualTo("redis");
    assertThat(smoke.path("module").asString()).isEqualTo("connectors/redis");
    assertThat(smoke.path("connectorTypes").toString()).isEqualTo("[\"redis\"]");
    int checked = 0;
    try (RedisConnector connector = new RedisConnector(Clock.systemUTC())) {
      for (JsonNode touch : smoke.path("touches")) {
        if (touch.has("fixture")) {
          continue;
        }
        ConnectionConfig cfg = connection(touch, Map.of());
        JsonNode expect = touch.path("expect");
        if (touch.path("test").asBoolean(false)) {
          TestResult r = connector.test(cfg, secrets(touch, Map.of()), TestCtx.of());
          assertThat(r.ok()).as(touch.path("name").asString()).isEqualTo(expect.path("ok").asBoolean());
          assertThat(r.error().code()).isEqualTo(expect.path("errorCode").asString());
        } else {
          ToolCall call = new ToolCall("tc_smoke", touch.at("/call/tool").asString(), cfg.name(),
              (ObjectNode) touch.at("/call/arguments"), null);
          Classification c = connector.classify(call, cfg, Configs.policy(cfg.env()));
          assertThat(c.kind()).as(touch.path("name").asString()).isEqualTo(Classification.Kind.DENY);
          assertThat("Denied by policy " + c.ruleId() + ": " + c.reason())
              .startsWith(expect.path("textStartsWith").asString());
        }
        checked++;
      }
    }
    assertThat(checked).isEqualTo(2);
  }

  @Test
  void theFixtureTouchNamesWhatItNeeds() throws Exception {
    JsonNode fixture = null;
    for (JsonNode touch : descriptor().path("touches")) {
      if (touch.has("fixture")) {
        fixture = touch;
      }
    }
    assertThat(fixture).isNotNull();
    assertThat(fixture.path("fixture").asString()).isEqualTo("redis");
    assertThat(fixture.path("connection").toString()).contains("${fixture.tlsPort}", "${fixture.caCertificate}",
        "${fixture.user}", "${fixture.password}");
  }
}
