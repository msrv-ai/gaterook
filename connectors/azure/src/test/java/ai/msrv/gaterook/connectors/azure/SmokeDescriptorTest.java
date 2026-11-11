package ai.msrv.gaterook.connectors.azure;

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
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native smoke's touches of this module ({@code META-INF/mcp-gateway/smoke/azure.json}) hold on the JVM, as the
 * smoke would run them in the image: none needs a server.
 */
class SmokeDescriptorTest {

  static JsonNode descriptor() throws Exception {
    try (InputStream in = SmokeDescriptorTest.class.getClassLoader()
        .getResourceAsStream("META-INF/mcp-gateway/smoke/azure.json")) {
      assertThat(in).isNotNull();
      return TestCtx.JSON.readTree(in);
    }
  }

  static ConnectionConfig connection(JsonNode touch) {
    JsonNode c = touch.path("connection");
    Set<String> secretFields = new HashSet<>();
    c.path("secrets").propertyNames().forEach(secretFields::add);
    return new ConnectionConfig("smoke-" + touch.path("name").asString(), "smoke-azure", c.path("type").asString(),
        Env.of(c.path("env").asString()), 1, c.path("config"), secretFields, null);
  }

  @Test
  void touchesAnswerAsTheDescriptorSays() throws Exception {
    JsonNode smoke = descriptor();
    assertThat(smoke.path("area").asString()).isEqualTo("azure");
    assertThat(smoke.path("connectorTypes").toString()).isEqualTo("[\"azure\"]");
    int checked = 0;
    try (CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
        AzureConnector connector = new AzureConnector(cloud, Clock.systemUTC())) {
      for (JsonNode touch : smoke.path("touches")) {
        assertThat(touch.has("fixture")).as("no Azure fixture exists").isFalse();
        ConnectionConfig cfg = connection(touch);
        JsonNode expect = touch.path("expect");
        if (touch.path("test").asBoolean(false)) {
          TestResult r = connector.test(cfg, new TestSecrets(), TestCtx.of());
          assertThat(r.ok()).as(touch.path("name").asString()).isEqualTo(expect.path("ok").asBoolean());
          // Where the Azure CLI is installed the test asks to run it; elsewhere it says the CLI is missing.
          if (r.needsConfirmation() != null) {
            assertThat(r.needsConfirmation().kind()).isEqualTo("azure-cli");
          } else {
            assertThat(r.error().code()).isEqualTo("azure.unreachable");
          }
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
    assertThat(checked).isEqualTo(5);
  }
}
