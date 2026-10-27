package ai.msrv.gaterook.connectors.gcp;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
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
 * The native smoke's touches of this module ({@code META-INF/mcp-gateway/smoke/gcp.json}, ARCHITECTURE.md §10 N1)
 * hold on the JVM: the ones without a fixture as the smoke would run them in the image, the {@code google} one against
 * {@link FakeGoogle}.
 */
class SmokeDescriptorTest {

  static JsonNode descriptor() throws Exception {
    try (InputStream in = SmokeDescriptorTest.class.getClassLoader()
        .getResourceAsStream("META-INF/mcp-gateway/smoke/gcp.json")) {
      assertThat(in).isNotNull();
      return TestCtx.JSON.readTree(in);
    }
  }

  static ConnectionConfig connection(JsonNode touch) {
    JsonNode c = touch.path("connection");
    Set<String> secretFields = new HashSet<>();
    c.path("secrets").propertyNames().forEach(secretFields::add);
    return new ConnectionConfig("smoke-" + touch.path("name").asString(), "smoke-gcp", c.path("type").asString(),
        Env.of(c.path("env").asString()), 1, c.path("config"), secretFields, null);
  }

  static TestSecrets secrets(JsonNode touch) {
    TestSecrets s = new TestSecrets();
    touch.at("/connection/secrets").properties().forEach(e -> s.with(e.getKey(), e.getValue().asString()));
    return s;
  }

  @Test
  void touchesWithoutAServerAnswerAsTheDescriptorSays() throws Exception {
    JsonNode smoke = descriptor();
    assertThat(smoke.path("area").asString()).isEqualTo("gcp");
    assertThat(smoke.path("connectorTypes").toString()).isEqualTo("[\"gcp\"]");
    int checked = 0;
    try (CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
        GcpConnector connector = new GcpConnector(cloud, Clock.systemUTC())) {
      for (JsonNode touch : smoke.path("touches")) {
        if (touch.has("fixture")) {
          continue;
        }
        ConnectionConfig cfg = connection(touch);
        JsonNode expect = touch.path("expect");
        if (touch.path("test").asBoolean(false)) {
          TestResult r = connector.test(cfg, secrets(touch), TestCtx.of());
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
    assertThat(checked).isEqualTo(3);
  }

  @Test
  void theFixtureTouchSearchesThroughImpersonation() throws Exception {
    JsonNode touch = null;
    for (JsonNode t : descriptor().path("touches")) {
      if ("google".equals(t.path("fixture").asString(null))) {
        touch = t;
      }
    }
    assertThat(touch).isNotNull();
    try (FakeGoogle google = new FakeGoogle()) {
      google.on("/v1/", r -> FakeGoogle.json(200, "{\"results\":[{\"name\":\"//run.googleapis.com/projects/"
          + "acme-dev/locations/europe-west1/services/api\",\"assetType\":\"run.googleapis.com/Service\",\"location\":"
          + "\"europe-west1\"}]}"));
      try (GcpConnector connector = new GcpConnector(google.cloud, Clock.systemUTC())) {
        ConnectionConfig cfg = connection(touch);
        ToolCall call = new ToolCall("tc_smoke_fixture", touch.at("/call/tool").asString(), cfg.name(),
            (ObjectNode) touch.at("/call/arguments"), null);
        assertThat(connector.classify(call, cfg, Configs.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.READ);

        ToolResult r = connector.execute(call, cfg, secrets(touch), TestCtx.of());

        assertThat(r.isError()).isEqualTo(touch.at("/expect/isError").asBoolean());
        for (Map.Entry<String, JsonNode> e : touch.at("/expect/structuredContent").properties()) {
          assertThat(r.structuredContent().path(e.getKey())).as(e.getKey()).isEqualTo(e.getValue());
        }
        assertThat(google.to("/v1/projects/acme-dev:searchAllResources").getFirst().getHeaders().get("Authorization"))
            .isEqualTo("Bearer " + google.token);
      }
    }
  }
}
