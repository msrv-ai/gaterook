package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native smoke's touches of this module ({@code META-INF/mcp-gateway/smoke/mongodb.json}, ARCHITECTURE.md §10
 * N1) hold on the JVM: the ones without a fixture run here as the smoke would run them in the image.
 */
class SmokeDescriptorTest {

  @Test
  void touchesWithoutAServerAnswerAsTheDescriptorSays() throws Exception {
    JsonNode smoke;
    try (InputStream in = getClass().getClassLoader()
        .getResourceAsStream("META-INF/mcp-gateway/smoke/mongodb.json")) {
      assertThat(in).isNotNull();
      smoke = Fixtures.JSON.readTree(in);
    }
    assertThat(smoke.path("area").asString()).isEqualTo("mongodb");
    assertThat(smoke.path("connectorTypes").toString()).isEqualTo("[\"" + MongoConnector.TYPE + "\"]");
    int checked = 0;
    try (MongoConnector connector = new MongoConnector()) {
      for (JsonNode touch : smoke.path("touches")) {
        if (touch.has("fixture")) {
          assertThat(touch.path("fixture").asString()).isEqualTo("mongod-srv");
          continue;
        }
        JsonNode c = touch.path("connection");
        Env env = Env.of(c.path("env").asString());
        ConnectionConfig cfg = new ConnectionConfig("smoke-" + touch.path("name").asString(), "orders-mongo",
            c.path("type").asString(), env, 1, c.path("config"), Set.of(MongoSettings.PASSWORD), null);
        JsonNode password = c.at("/secrets/~1credentials~1password");
        Fixtures.Secrets secrets = password.isMissingNode() ? Fixtures.Secrets.none()
            : Fixtures.Secrets.password(password.asString());
        JsonNode expect = touch.path("expect");
        if (touch.path("test").asBoolean(false)) {
          TestResult r = connector.test(cfg, secrets, Fixtures.Context.of(env));
          assertThat(r.ok()).isEqualTo(expect.path("ok").asBoolean());
          assertThat(r.error().code()).isEqualTo(expect.path("errorCode").asString());
        } else {
          ToolCall call = new ToolCall("tc_smoke", touch.at("/call/tool").asString(), "orders-mongo",
              (ObjectNode) touch.at("/call/arguments"), null);
          Classification cl = connector.classify(call, cfg, Fixtures.policy(env));
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
