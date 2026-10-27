package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.InputStream;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native smoke's touches of this module ({@code META-INF/mcp-gateway/smoke/logs.json}, ARCHITECTURE.md §10 N1)
 * hold on the JVM: the ones without a fixture run here as the smoke would run them in the image; the Loki one runs
 * in {@code LokiIT} against the real binary.
 */
class SmokeDescriptorTest {

  static JsonNode descriptor() throws Exception {
    try (InputStream in = SmokeDescriptorTest.class.getClassLoader()
        .getResourceAsStream("META-INF/mcp-gateway/smoke/logs.json")) {
      assertThat(in).isNotNull();
      return TestCtx.JSON.readTree(in);
    }
  }

  /** A touch's connection as the smoke saves it, {@code ${fixture.*}} replaced by {@code fixture}. */
  static ConnectionConfig connection(JsonNode touch, Map<String, String> fixture) {
    JsonNode c = touch.path("connection");
    String config = c.path("config").toString();
    for (Map.Entry<String, String> f : fixture.entrySet()) {
      config = config.replace("${fixture." + f.getKey() + "}", f.getValue());
    }
    Set<String> secretFields = new HashSet<>();
    c.path("secrets").propertyNames().forEach(secretFields::add);
    return new ConnectionConfig("smoke-" + touch.path("name").asString(), "smoke-logs", c.path("type").asString(),
        Env.of(c.path("env").asString()), 1, TestCtx.JSON.readTree(config), secretFields, null);
  }

  static TestSecrets secrets(JsonNode touch) {
    TestSecrets s = new TestSecrets();
    touch.at("/connection/secrets").properties().forEach(e -> s.with(e.getKey(), e.getValue().asString()));
    return s;
  }

  @Test
  void touchesWithoutAServerAnswerAsTheDescriptorSays() throws Exception {
    JsonNode smoke = descriptor();
    assertThat(smoke.path("area").asString()).isEqualTo("logs");
    assertThat(smoke.path("connectorTypes").toString()).isEqualTo("[\"logs\"]");
    int checked = 0;
    try (CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
        LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC())) {
      for (JsonNode touch : smoke.path("touches")) {
        if (touch.has("fixture")) {
          continue;
        }
        ConnectionConfig cfg = connection(touch, Map.of());
        JsonNode expect = touch.path("expect");
        if (touch.path("test").asBoolean(false)) {
          TestResult r = connector.test(cfg, secrets(touch), TestCtx.of());
          assertThat(r.ok()).as(touch.path("name").asString()).isEqualTo(expect.path("ok").asBoolean());
          assertThat(r.error().code()).isEqualTo(expect.path("errorCode").asString());
        } else {
          ToolCall call = new ToolCall("tc_smoke", touch.at("/call/tool").asString(), cfg.name(),
              (ObjectNode) touch.at("/call/arguments"), null);
          Classification c = connector.classify(call, cfg, Configs.policy(cfg.env(), TestCtx.dev()));
          assertThat(c.kind()).as(touch.path("name").asString()).isEqualTo(Classification.Kind.DENY);
          assertThat("Denied by policy " + c.ruleId() + ": " + c.reason())
              .startsWith(expect.path("textStartsWith").asString());
        }
        checked++;
      }
    }
    assertThat(checked).isEqualTo(3);
  }
}
