package com.gaterook.plugin.protocol.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.EndpointView;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Limits;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.TemplateKind;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connectors.fixturemodule.FixtureModule;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.JsonRpcPeer;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.RpcException;
import com.gaterook.plugin.protocol.SecretWire;
import com.gaterook.plugin.protocol.WireCodec;
import com.gaterook.plugin.protocol.testing.FakeHost;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A connector module in a host: one generation with two connector types. Each call reaches the connector of its
 * connection's type; the SPI calls a plugin never gets (prepare, describe, supportsTool, templates) are answered; and
 * the module's calls get the core's services (shared quotas, the masked view, progress) while a plugin's keep their
 * host's own.
 */
class ConnectorEndpointModuleTest {

  private static final String GENERATION = FixtureModule.ID + "@" + FixtureModule.VERSION;
  private static final Limits LIMITS = new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192,
      Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null);
  private static final Duration WAIT = Duration.ofSeconds(5);

  private final FixtureModule module = new FixtureModule();
  private final List<String> core = new CopyOnWriteArrayList<>();
  private FakeHost host;
  private JsonRpcPeer peer;

  static ConnectionConfig connection(String id, String type, String config) {
    return new ConnectionConfig(id, id, type, Env.DEV, 1, GppJson.MAPPER.readTree(config), Set.of("/password"), null);
  }

  static final ConnectionConfig ALPHA = connection("orders", "alpha", "{\"host\":\"db.example.com\"}");
  static final ConnectionConfig ALPHA_READ_ONLY = connection("reports", "alpha",
      "{\"host\":\"db.example.com\",\"readOnly\":true}");
  static final ConnectionConfig BETA = connection("lines", "beta", "{\"host\":\"logs.example.com\"}");

  @BeforeEach
  void start() throws Exception {
    host = FakeHost.startModules(load -> module.connectors(null));
    // The core's side: what a module's host may ask of it.
    peer = new JsonRpcPeer(host.stdout(), host.stdin(), new JsonRpcPeer.Handler() {
      @Override
      public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) throws RpcException {
        core.add(method);
        return switch (method) {
          case Gpp.SECRETS_GET -> SecretWire.text("s3cret-value".toCharArray(), "v1", null);
          case Gpp.QUOTA_ACQUIRE -> GppJson.object().put("granted", core.stream()
              .filter(Gpp.QUOTA_ACQUIRE::equals).count() == 1);
          case Gpp.MASK_PREVIEW -> {
            ObjectNode masked = (ObjectNode) params.path("tree").deepCopy();
            masked.put("email", "‹masked›");
            yield GppJson.object().set("tree", masked);
          }
          default -> throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
        };
      }

      @Override
      public void notification(String method, JsonNode params) {
        core.add(method + " " + params.path("message").asString(params.path("level").asString("")));
      }
    }, JsonRpcPeer.Options.defaults().name("core")).start();
    Messages.Loaded loaded = Messages.Loaded.read(peer.request(Gpp.PLUGIN_LOAD, new Messages.Load(GENERATION, 1,
        "/unused/module.jar", "0".repeat(64), "/tmp/fixture-module", new Messages.Sandbox(false, List.of(),
            List.of())).write()).get(WAIT));
    assertThat(loaded.types()).extracting(Messages.TypeTools::type).containsExactly("alpha", "beta");
    assertThat(loaded.type()).isEqualTo("alpha");
    assertThat(loaded.types().get(1).tools()).singleElement()
        .satisfies(t -> assertThat(t.name()).isEqualTo("beta_read"));
  }

  @AfterEach
  void stop() {
    peer.close();
    host.close();
  }

  private JsonNode execute(ConnectionConfig cfg, String tool, String arguments, ToolCall.TemplateBinding template,
      boolean wantsProgress) throws Exception {
    ToolCall call = new ToolCall("tc_1", tool, cfg.name(), (ObjectNode) GppJson.MAPPER.readTree(arguments), template);
    return peer.request(Gpp.CONNECTOR_EXECUTE, new Messages.Execute(GENERATION, "ct_token", call, cfg, 5_000, true,
        "ap_1", GppJson.object().put("rowVersion", 7), GppJson.object().put("sql", "UPDATE t"), LIMITS, null, null,
        Messages.EgressGrant.NONE, wantsProgress, wantsProgress ? "info" : null).write()).get(WAIT);
  }

  @Test
  void aCallReachesTheConnectorOfItsConnectionsTypeAndKeepsItsTemplateBinding() throws Exception {
    JsonNode alpha = execute(ALPHA, "alpha_read", "{\"table\":\"orders\"}",
        new ToolCall.TemplateBinding("tpl_9", 3, "q", GppJson.MAPPER.readTree("\"SELECT 1\"")), false);
    assertThat(alpha.at("/structuredContent/tool").asString()).isEqualTo("alpha_read");
    assertThat(alpha.at("/structuredContent/template").asString()).isEqualTo("tpl_9@3");
    assertThat(alpha.at("/structuredContent/preparedState/rowVersion").asInt()).isEqualTo(7);
    assertThat(alpha.at("/structuredContent/approvedPayload/sql").asString()).isEqualTo("UPDATE t");

    JsonNode beta = execute(BETA, "beta_read", "{}", null, false);
    assertThat(beta.at("/structuredContent/type").asString()).isEqualTo("beta");
    assertThat(module.asked).containsSubsequence("alpha.execute alpha_read", "beta.execute beta_read");

    ConnectionConfig unknown = connection("x", "gamma", "{}");
    assertThatThrownBy(() -> execute(unknown, "gamma_read", "{}", null, false)).isInstanceOf(RpcException.class)
        .hasMessageContaining("gamma isn't a connector type of " + GENERATION);
  }

  @Test
  void prepareRunsWithSecretsAndAnswersAnApprovalARejectionOrAConnectorError() throws Exception {
    ToolCall write = new ToolCall("tc_2", "alpha_write", "orders", (ObjectNode) GppJson.MAPPER.readTree("{\"id\":5}"),
        new ToolCall.TemplateBinding("tpl_1", 1, "q", null));
    PreparedAction approval = WireCodec.prepared(peer.request(Gpp.CONNECTOR_PREPARE, new Messages.Prepare(GENERATION,
        "ct_token", write, ALPHA, 5_000, LIMITS, "trace-1", null, Messages.EgressGrant.NONE).write()).get(WAIT));
    assertThat(approval.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(approval.preview().kind()).isEqualTo(ActionPreview.Kind.SQL);
    assertThat(approval.preview().text()).isEqualTo("UPDATE t SET x = 1 WHERE id = 5");
    assertThat(approval.preview().payload().path("sql").asString()).isEqualTo("UPDATE t SET x = 1 WHERE id = 5");
    assertThat(approval.preview().impact()).containsExactly(new ActionPreview.Field("Rows", "1"));
    assertThat(approval.preparedState().path("rowVersion").asInt()).isEqualTo(7);
    assertThat(approval.preparedState().path("passwordLength").asInt()).as("the secret came through the core")
        .isEqualTo("s3cret-value".length());
    assertThat(core).contains(Gpp.SECRETS_GET);
    assertThat(module.asked).contains("alpha.prepare alpha_write template tpl_1");

    ToolCall missing = new ToolCall("tc_3", "alpha_write", "orders",
        (ObjectNode) GppJson.MAPPER.readTree("{\"id\":-1}"), null);
    PreparedAction rejected = WireCodec.prepared(peer.request(Gpp.CONNECTOR_PREPARE, new Messages.Prepare(GENERATION,
        "ct_token", missing, ALPHA, 5_000, LIMITS, null, null, Messages.EgressGrant.NONE).write()).get(WAIT));
    assertThat(rejected.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(rejected.message()).isEqualTo("There is no row -1.");
    assertThat(rejected.preview()).isNull();

    ToolCall unreachable = new ToolCall("tc_4", "alpha_write", "orders",
        (ObjectNode) GppJson.MAPPER.readTree("{\"id\":404}"), null);
    assertThatThrownBy(() -> peer.request(Gpp.CONNECTOR_PREPARE, new Messages.Prepare(GENERATION, "ct_token",
        unreachable, ALPHA, 5_000, LIMITS, null, null, Messages.EgressGrant.NONE).write()).get(WAIT))
        .isInstanceOfSatisfying(RpcException.class, e -> {
          assertThat(e.code()).isEqualTo(Gpp.CONNECTOR_ERROR);
          assertThat(e.data().path("kind").asString()).isEqualTo("RESOURCE_UNAVAILABLE");
          assertThat(e.getMessage()).isEqualTo("alpha is unreachable");
        });
  }

  @Test
  void describeSupportsToolAndToolsAreAnsweredPerType() throws Exception {
    Messages.Test test = new Messages.Test(GENERATION, "tc_5", "ct_token", ALPHA, 2_000, LIMITS,
        Messages.EgressGrant.NONE);
    JsonNode described = peer.request(Gpp.CONNECTOR_DESCRIBE, new Messages.DescribeConnection(test, "orders").write())
        .get(WAIT);
    assertThat(described.at("/value/object").asString()).isEqualTo("orders");
    assertThat(described.at("/value/tables").toString()).isEqualTo("[\"orders\",\"customers\"]");
    JsonNode nothing = peer.request(Gpp.CONNECTOR_DESCRIBE, new Messages.DescribeConnection(test, "nothing").write())
        .get(WAIT);
    assertThat(nothing.has("value")).isFalse();
    JsonNode beta = peer.request(Gpp.CONNECTOR_DESCRIBE, new Messages.DescribeConnection(new Messages.Test(GENERATION,
        "tc_6", "ct_token", BETA, 2_000, LIMITS, Messages.EgressGrant.NONE), null).write()).get(WAIT);
    assertThat(beta.has("value")).as("beta has nothing of its own to say").isFalse();

    List<String> all = List.of("alpha_read", "alpha_run", "alpha_write");
    assertThat(Messages.SupportsTool.supported(peer.request(Gpp.CONNECTOR_SUPPORTS_TOOL,
        new Messages.SupportsTool(GENERATION, ALPHA, all).write()).get(WAIT))).containsExactlyElementsOf(all);
    assertThat(Messages.SupportsTool.supported(peer.request(Gpp.CONNECTOR_SUPPORTS_TOOL,
        new Messages.SupportsTool(GENERATION, ALPHA_READ_ONLY, all).write()).get(WAIT)))
        .containsExactly("alpha_read", "alpha_run");

    EndpointView view = new EndpointView("dev", Env.DEV, List.of(BETA), Map.of("lines", LIMITS), List.of());
    JsonNode tools = peer.request(Gpp.CONNECTOR_TOOLS, new Messages.Tools(GENERATION, "ct_token", view, "beta")
        .write()).get(WAIT);
    assertThat(WireCodec.tools(tools.path("tools"))).singleElement()
        .satisfies(t -> assertThat(t.name()).isEqualTo("beta_read"));
    assertThatThrownBy(() -> peer.request(Gpp.CONNECTOR_TOOLS, new Messages.Tools(GENERATION, "ct_token", view)
        .write()).get(WAIT)).as("a module's tools are asked per type").isInstanceOf(RpcException.class);
  }

  @Test
  void aTemplateKindCompilesAndPreviewsInTheHost() throws Exception {
    JsonNode definition = GppJson.MAPPER.readTree("{\"name\":\"orders_by_status\",\"sql\":\"SELECT 1\"}");
    TemplateKind.CompileResult compiled = WireCodec.compiled(peer.request(Gpp.TEMPLATE_COMPILE,
        new Messages.Template(GENERATION, "alpha-query", definition, null, ALPHA).write()).get(WAIT));
    assertThat(compiled.ok()).isTrue();
    assertThat(compiled.digest()).isEqualTo("sha256:fixture");
    assertThat(compiled.warnings()).containsExactly("Runs on orders.");
    assertThat(compiled.tools()).singleElement().satisfies(t -> {
      assertThat(t.opKey()).isEqualTo("q");
      assertThat(t.definition().name()).isEqualTo("orders_by_status");
      assertThat(t.binding().asString()).isEqualTo("SELECT 1");
    });
    TemplateKind.CompileResult failed = WireCodec.compiled(peer.request(Gpp.TEMPLATE_COMPILE,
        new Messages.Template(GENERATION, "alpha-query", GppJson.object(), null, ALPHA).write()).get(WAIT));
    assertThat(failed.errors()).containsExactly("A template needs a name.");

    ActionPreview preview = WireCodec.action(peer.request(Gpp.TEMPLATE_PREVIEW, new Messages.Template(GENERATION,
        "alpha-query", definition, (ObjectNode) GppJson.MAPPER.readTree("{\"status\":\"pending\"}"), ALPHA).write())
        .get(WAIT));
    assertThat(preview.text()).isEqualTo("SELECT 1");
    assertThat(preview.summary()).isEqualTo("A stored query with 1 arguments");

    assertThatThrownBy(() -> peer.request(Gpp.TEMPLATE_COMPILE, new Messages.Template(GENERATION, "ssh-runbook",
        definition, null, ALPHA).write()).get(WAIT)).isInstanceOf(RpcException.class)
        .hasMessageContaining("alpha has no template kind ssh-runbook");
    assertThatThrownBy(() -> peer.request(Gpp.TEMPLATE_COMPILE, new Messages.Template(GENERATION, "alpha-query",
        definition, null, BETA).write()).get(WAIT)).isInstanceOf(RpcException.class)
        .hasMessageContaining("beta has no template kind alpha-query");
  }

  @Test
  void aModulesCallAsksTheCoreForQuotasTheMaskedViewAndTellsItsProgress() throws Exception {
    JsonNode quota = execute(ALPHA, "alpha_read", "{\"table\":\"quota\"}", null, false);
    assertThat(quota.at("/structuredContent/first").asBoolean()).as("the core granted the first permit").isTrue();
    assertThat(quota.at("/structuredContent/second").asBoolean()).as("and refused the second").isFalse();

    JsonNode masked = execute(ALPHA, "alpha_read", "{\"table\":\"masked\"}", null, false);
    assertThat(masked.at("/structuredContent/masked/email").asString()).isEqualTo("‹masked›");

    JsonNode quiet = execute(ALPHA, "alpha_read", "{\"table\":\"progress\"}", null, false);
    assertThat(quiet.at("/structuredContent/wantsProgress").asBoolean()).isFalse();
    assertThat(quiet.at("/structuredContent/clientLogLevel").isNull()).isTrue();
    assertThat(core).noneMatch(m -> m.startsWith(Gpp.PROGRESS) || m.startsWith(Gpp.CLIENT_LOG));

    JsonNode loud = execute(ALPHA, "alpha_read", "{\"table\":\"progress\"}", null, true);
    assertThat(loud.at("/structuredContent/wantsProgress").asBoolean()).isTrue();
    assertThat(loud.at("/structuredContent/clientLogLevel").asString()).isEqualTo("info");
    // Notifications are ordered before the call's answer on the same stream.
    assertThat(core).contains(Gpp.PROGRESS + " Half way", Gpp.CLIENT_LOG + " info");
  }

  @Test
  void aPluginsCallKeepsItsHostsOwnQuotasAndGetsNoneOfTheCoresServices() throws Exception {
    peer.close();
    host.close();
    core.clear();
    FixtureModule asPlugin = new FixtureModule();
    host = FakeHost.start(load -> asPlugin.connectors(null).getFirst());
    peer = new JsonRpcPeer(host.stdout(), host.stdin(), new JsonRpcPeer.Handler() {
      @Override
      public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) throws RpcException {
        core.add(method);
        throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
      }

      @Override
      public void notification(String method, JsonNode params) {
        core.add(method);
      }
    }, JsonRpcPeer.Options.defaults().name("core")).start();
    peer.request(Gpp.PLUGIN_LOAD, new Messages.Load("alpha@1.0.0", 1, "/unused/plugin.jar", "0".repeat(64),
        "/tmp/fixture-plugin", new Messages.Sandbox(false, List.of(), List.of())).write()).get(WAIT);

    ToolCall call = new ToolCall("tc_1", "alpha_read", "orders", (ObjectNode) GppJson.MAPPER.readTree(
        "{\"table\":\"quota\"}"), new ToolCall.TemplateBinding("tpl_9", 3, "q", null));
    JsonNode quota = peer.request(Gpp.CONNECTOR_EXECUTE, new Messages.Execute("alpha@1.0.0", "ct_token", call, ALPHA,
        5_000, false, null, null, null, LIMITS, null, null, Messages.EgressGrant.NONE, true, "info").write())
        .get(WAIT);
    assertThat(quota.at("/structuredContent/first").asBoolean()).isTrue();
    assertThat(quota.at("/structuredContent/second").asBoolean()).as("the host's own quota of one a minute")
        .isFalse();
    assertThat(quota.at("/structuredContent/template").isNull()).as("a plugin gets no template binding").isTrue();
    assertThat(core).as("nothing was asked of the core").isEmpty();

    ToolCall progress = new ToolCall("tc_2", "alpha_read", "orders", (ObjectNode) GppJson.MAPPER.readTree(
        "{\"table\":\"progress\"}"), null);
    JsonNode quiet = peer.request(Gpp.CONNECTOR_EXECUTE, new Messages.Execute("alpha@1.0.0", "ct_token", progress,
        ALPHA, 5_000, false, null, null, null, LIMITS, null, null, Messages.EgressGrant.NONE, true, "info").write())
        .get(WAIT);
    assertThat(quiet.at("/structuredContent/wantsProgress").asBoolean()).isFalse();
    assertThat(core).isEmpty();
  }
}
