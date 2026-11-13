package com.gaterook.connectors.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.ToolCall;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The native smoke's touches of this module hold on the JVM too: the same descriptor, the same answers. */
class SmokeDescriptorTest {

  @Test
  void everyTouchGivesItsExpectedAnswerWithoutIo() throws Exception {
    JsonNode smoke;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/mcp-gateway/smoke/search.json")) {
      assertThat(in).isNotNull();
      smoke = JsonMapper.builder().build().readTree(in);
    }
    assertThat(smoke.path("area").asString()).isEqualTo("search");
    List<Connector> all = new ArrayList<>(Connectors.vectorDatabases());
    all.addAll(Connectors.searchEngines());
    int checked = 0;
    for (JsonNode touch : smoke.path("touches")) {
      JsonNode c = touch.path("connection");
      Env env = Env.of(c.path("env").asString());
      String type = c.path("type").asString();
      Connector connector = all.stream().filter(x -> x.manifest().id().equals(type)).findFirst().orElseThrow();
      ConnectionConfig cfg = new ConnectionConfig("smoke-" + touch.path("name").asString(), "dev-" + type, type, env,
          1, c.path("config"), Set.copyOf(c.path("secrets").propertyNames()), null);
      ToolCall call = new ToolCall("tc_smoke", touch.at("/call/tool").asString(), "dev-" + type,
          (ObjectNode) touch.at("/call/arguments"), null);
      Classification cl = connector.classify(call, cfg, new PolicyView(env, env.id() + "." + type,
          JsonNodeFactory.instance.objectNode(), TestCtx.dev()));
      assertThat(cl.kind()).as(touch.path("name").asString()).isEqualTo(Classification.Kind.DENY);
      assertThat("Denied by policy " + cl.ruleId() + ": " + cl.reason()).as(touch.path("name").asString())
          .startsWith(touch.at("/expect/textStartsWith").asString());
      checked++;
    }
    assertThat(checked).isEqualTo(7);
    assertThat(smoke.path("connectorTypes")).hasSize(9);
  }
}
