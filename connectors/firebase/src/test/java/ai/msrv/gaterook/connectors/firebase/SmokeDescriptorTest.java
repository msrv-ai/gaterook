package ai.msrv.gaterook.connectors.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
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
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/mcp-gateway/smoke/firebase.json")) {
      assertThat(in).isNotNull();
      smoke = JsonMapper.builder().build().readTree(in);
    }
    assertThat(smoke.path("area").asString()).isEqualTo("firebase");
    assertThat(smoke.path("connectorTypes").toString()).isEqualTo("[\"firestore\",\"firebase-rtdb\"]");
    int checked = 0;
    try (CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
        FirestoreConnector firestore = new FirestoreConnector(cloud);
        RtdbConnector rtdb = new RtdbConnector(cloud)) {
      Map<String, Connector> byType = Map.of("firestore", firestore, "firebase-rtdb", rtdb);
      for (JsonNode touch : smoke.path("touches")) {
        JsonNode c = touch.path("connection");
        Env env = Env.of(c.path("env").asString());
        String type = c.path("type").asString();
        assertThat(List.of("firestore", "firebase-rtdb")).contains(type);
        ConnectionConfig cfg = new ConnectionConfig("smoke-" + touch.path("name").asString(), "dev-firebase", type,
            env, 1, c.path("config"), Set.of(), null);
        ToolCall call = new ToolCall("tc_smoke", touch.at("/call/tool").asString(), "dev-firebase",
            (ObjectNode) touch.at("/call/arguments"), null);
        Classification cl = byType.get(type).classify(call, cfg, new PolicyView(env, env.id() + "." + type,
            JsonNodeFactory.instance.objectNode(), TestCtx.dev()));
        assertThat(cl.kind()).as(touch.path("name").asString()).isEqualTo(Classification.Kind.DENY);
        assertThat("Denied by policy " + cl.ruleId() + ": " + cl.reason())
            .startsWith(touch.at("/expect/textStartsWith").asString());
        checked++;
      }
    }
    assertThat(checked).isEqualTo(2);
  }
}
