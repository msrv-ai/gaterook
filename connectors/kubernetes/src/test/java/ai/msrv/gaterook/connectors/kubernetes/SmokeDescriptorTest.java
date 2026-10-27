package ai.msrv.gaterook.connectors.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native smoke's touches of this module ({@code META-INF/mcp-gateway/smoke/kubernetes.json}, ARCHITECTURE.md
 * §10 N1) hold on the JVM: the ones without a fixture run here as the smoke would run them in the image; the
 * kube-apiserver one runs in {@code KubeApiServerIT} against the real binaries.
 */
class SmokeDescriptorTest {

  static JsonNode descriptor() throws Exception {
    try (InputStream in = SmokeDescriptorTest.class.getClassLoader()
        .getResourceAsStream("META-INF/mcp-gateway/smoke/kubernetes.json")) {
      assertThat(in).isNotNull();
      return K8sApi.JSON.readTree(in);
    }
  }

  /** A touch's connection as the smoke saves it, {@code ${fixture.*}} replaced by {@code fixture}. */
  static ConnectionConfig connection(JsonNode touch, Map<String, String> fixture) {
    JsonNode c = touch.path("connection");
    String config = c.path("config").toString();
    for (Map.Entry<String, String> f : fixture.entrySet()) {
      config = config.replace("${fixture." + f.getKey() + "}", K8sApi.JSON.writeValueAsString(f.getValue())
          .replaceAll("^\"|\"$", ""));
    }
    Set<String> secretFields = new HashSet<>();
    c.path("secrets").propertyNames().forEach(secretFields::add);
    return new ConnectionConfig("smoke-" + touch.path("name").asString(), "smoke-k8s", c.path("type").asString(),
        Env.valueOf(c.path("env").asString().toUpperCase(java.util.Locale.ROOT)), 1, K8sApi.JSON.readTree(config),
        secretFields, null);
  }

  static K8sTestKit.Secrets secrets(JsonNode touch, Map<String, String> fixture) {
    Map<String, String> values = new HashMap<>();
    touch.at("/connection/secrets").properties().forEach(e -> {
      String v = e.getValue().asString();
      for (Map.Entry<String, String> f : fixture.entrySet()) {
        v = v.replace("${fixture." + f.getKey() + "}", f.getValue());
      }
      values.put(e.getKey(), v);
    });
    return K8sTestKit.secrets(values);
  }

  static ToolCall call(JsonNode touch, ConnectionConfig cfg) {
    return new ToolCall("tc_smoke", touch.at("/call/tool").asString(), cfg.name(),
        ((ObjectNode) touch.at("/call/arguments")).deepCopy(), null);
  }

  @Test
  void touchesWithoutAServerAnswerAsTheDescriptorSays() throws Exception {
    JsonNode smoke = descriptor();
    assertThat(smoke.path("area").asString()).isEqualTo("kubernetes");
    assertThat(smoke.path("connectorTypes").toString()).isEqualTo("[\"kubernetes\"]");
    int checked = 0;
    try (KubernetesConnector connector = new KubernetesConnector()) {
      for (JsonNode touch : smoke.path("touches")) {
        if (touch.has("fixture")) {
          continue;
        }
        ConnectionConfig cfg = connection(touch, Map.of());
        JsonNode expect = touch.path("expect");
        if (touch.path("test").asBoolean(false)) {
          TestResult r = connector.test(cfg, secrets(touch, Map.of()), K8sTestKit.ctx(cfg.env()));
          assertThat(r.ok()).as(touch.path("name").asString()).isEqualTo(expect.path("ok").asBoolean());
          assertThat(r.error().code()).isEqualTo(expect.path("errorCode").asString());
        } else {
          Classification c = connector.classify(call(touch, cfg), cfg, K8sTestKit.policy(cfg.env(), "{}"));
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
