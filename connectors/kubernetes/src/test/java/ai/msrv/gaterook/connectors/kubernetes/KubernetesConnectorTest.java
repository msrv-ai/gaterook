package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.call;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.ctx;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.policy;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.secrets;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.tokenCfg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

class KubernetesConnectorTest {

  private final KubernetesConnector connector = new KubernetesConnector();
  private final ConnectionConfig dev = tokenCfg(Env.DEV, "https://k8s.dev.internal:6443");
  private final ConnectionConfig prod = tokenCfg(Env.PROD, "https://k8s.prod.internal:6443");

  @Test
  void manifestIsTheCatalogCard() {
    ConnectorManifest m = connector.manifest();

    assertThat(m.id()).isEqualTo("kubernetes");
    assertThat(m.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
    assertThat(m.name()).isEqualTo("Kubernetes");
    assertThat(m.group()).isEqualTo("Kubernetes");
    assertThat(m.icon()).isEqualTo("ship-wheel");
    assertThat(m.configSchema().path("type").asString()).isEqualTo("object");
    assertThat(m.configSchema().at("/properties/insecureSkipTlsVerify/x-forbidden-env/0").asString())
        .isEqualTo("prod");
    // Each sign-in mode declares its own fields in its branch: the form shows them only for that mode.
    assertThat(declared(m.configSchema(), "pinnedCluster").path("readOnly").asBoolean()).isTrue();
    assertThat(declared(m.configSchema(), "trustedExec").path("readOnly").asBoolean()).isTrue();
    assertThat(m.configSchema().at("/allOf/0/then/properties/server/pattern").asString()).isEqualTo("^https://");
    assertThat(m.configSchema().at("/allOf/3/if/properties/auth/enum").toString())
        .isEqualTo("[\"kubeconfig\",\"kubeconfig-file\"]");
    assertThat(m.policySchema().path("x-policy-section").asString()).isEqualTo("k8s");
  }

  @Test
  void publishesTheToolsOfTheKit() {
    List<ToolDefinition> tools = connector.manifest().tools();

    assertThat(tools).extracting(ToolDefinition::name)
        .containsExactly("k8s_get", "k8s_logs", "k8s_rollout_restart", "k8s_apply");
    assertTool(tools.get(0), "k8s_get", Nature.READ, Mode.READ_ONLY, Mode.READ_ONLY, true);
    assertTool(tools.get(1), "k8s_logs", Nature.READ, Mode.OFF, Mode.OFF, false);
    assertTool(tools.get(2), "k8s_rollout_restart", Nature.MUTATION, Mode.APPROVAL, Mode.OFF, false);
    assertTool(tools.get(3), "k8s_apply", Nature.MUTATION, Mode.OFF, Mode.OFF, false);
    assertThat(tools.get(0).outputSchema().at("/properties/cursor/type").toString()).contains("null");
  }

  /** The definition of a config field in the {@code then} branch that declares it ({@code properties} names it true). */
  private static tools.jackson.databind.JsonNode declared(tools.jackson.databind.JsonNode schema, String field) {
    assertThat(schema.at("/properties/" + field).asBoolean(false)).as(field + " is named by the object").isTrue();
    for (tools.jackson.databind.JsonNode branch : schema.path("allOf")) {
      if (branch.at("/then/properties/" + field).isObject()) {
        return branch.at("/then/properties/" + field);
      }
    }
    throw new AssertionError(field + " is declared by no branch");
  }

  private static void assertTool(ToolDefinition t, String name, Nature nature, Mode dev, Mode prod, boolean output) {
    assertThat(t.name()).isEqualTo(name);
    assertThat(t.nature()).as(name).isEqualTo(nature);
    assertThat(t.defaultMode()).as(name + " dev/stage mode").isEqualTo(dev);
    assertThat(t.prodDefaultMode()).as(name + " prod mode").isEqualTo(prod);
    assertThat(t.outputSchema() != null).as(name + " outputSchema").isEqualTo(output);
    assertThat(t.inputSchema().at("/properties/connection/type").asString()).as(name).isEqualTo("string");
    assertThat(t.inputSchema().at("/required").toString()).as(name).contains("\"connection\"");
    assertThat(t.annotations().readOnlyHint()).as(name + " readOnlyHint").isEqualTo(nature == Nature.READ);
  }

  // ── classify: the policy without I/O ────────────────────────────────────────────────────────────────────────

  /** One row per call: env, policy section, tool, arguments, the expected kind and rule (k8s/classify.csv). */
  @ParameterizedTest(name = "[{index}] {0} {1} {2} {3} → {4} {5}")
  @CsvFileSource(resources = "/k8s/classify.csv", delimiter = '|', quoteCharacter = '`', numLinesToSkip = 1)
  void classifiesWithoutIo(String env, String section, String tool, String args, String kind, String rule) {
    Env e = Env.valueOf(env.toUpperCase(java.util.Locale.ROOT));
    ConnectionConfig cfg = e == Env.PROD ? prod : dev;
    Classification c = connector.classify(call(tool, args), cfg, policy(e, section));

    assertThat(c.kind()).as(c.reason()).isEqualTo(Classification.Kind.valueOf(kind));
    if (!rule.equals("-")) {
      assertThat(c.ruleId()).as(c.reason()).isEqualTo(rule);
    }
  }

  @Test
  void secretsAreRefusedInTheKitsWords() {
    Classification c = connector.classify(call("k8s_get", "{\"kind\":\"secret\",\"name\":\"db\"}"), dev,
        policy(Env.DEV, "{}"));

    assertThat(c.reason()).isEqualTo("secrets is not on the list, so agents can't read Kubernetes Secrets.");
  }

  @Test
  void aMutationNamesWhatItTouches() {
    Classification c = connector.classify(call("k8s_rollout_restart", "{\"kind\":\"deployment\",\"name\":\"api\"}"),
        dev, policy(Env.DEV, "{}"));

    assertThat(c.reason()).isEqualTo("Restarts deployment/api in app-dev");
    assertThat(c.facts().path("k8s.kind").asString()).isEqualTo("deployment");
    assertThat(c.facts().path("k8s.namespace").asString()).isEqualTo("app-dev");
  }

  @Test
  void anInvalidConnectionIsDeniedByItsOwnRule() {
    ConnectionConfig broken = K8sTestKit.cfg(Env.DEV, "{\"auth\":\"token\",\"server\":\"http://plain:8080\"}");

    Classification c = connector.classify(call("k8s_get", "{\"kind\":\"pods\"}"), broken, policy(Env.DEV, "{}"));

    assertThat(c.kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(c.ruleId()).isEqualTo("dev.k8s.config-invalid");
  }

  // ── the exact action and execute without approval ───────────────────────────────────────────────────────────

  @Test
  void theRestartActionIsTheCommandAPersonKnows() {
    ToolCall restart = call("k8s_rollout_restart", "{\"kind\":\"deployment\",\"name\":\"api\"}");

    ActionPreview p = connector.describeAction(restart, dev);

    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.COMMAND);
    assertThat(p.text()).isEqualTo("kubectl rollout restart deployment/api --namespace app-dev");
    assertThat(p.target()).isEqualTo("dev-cluster · k8s.dev.internal:6443 · ns app-dev");
    assertThat(p.payload().toString()).isEqualTo(
        "{\"apiVersion\":\"apps/v1\",\"kind\":\"Deployment\",\"namespace\":\"app-dev\",\"name\":\"api\"}");
  }

  @Test
  void theApplyActionWithoutIoIsTheManifestAsItWillBeSent() {
    ToolCall apply = call("k8s_apply", "{\"manifest\":\"kind: ConfigMap\\napiVersion: v1\\nmetadata: {name: cfg}"
        + "\\ndata: {enabled: 'true'}\"}");

    ActionPreview p = connector.describeAction(apply, dev);

    assertThat(p.text()).isEqualTo("""
        apiVersion: v1
        data:
          enabled: "true"
        kind: ConfigMap
        metadata:
          name: cfg
          namespace: app-dev
        """);
    assertThat(p.auditFacets()).containsEntry("k8s.kind", "configmap").containsEntry("k8s.namespace", "app-dev");
  }

  @Test
  void aChangeNeverRunsWithoutApproval() {
    ToolCall restart = call("k8s_rollout_restart", "{\"kind\":\"deployment\",\"name\":\"api\"}");

    assertThatThrownBy(() -> connector.execute(restart, dev, secrets(Map.of(K8sSettings.TOKEN, "t")), ctx(Env.DEV)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("runs only after a person approved it");
        });
    ToolCall apply = call("k8s_apply", "{\"manifest\":\"apiVersion: v1\\nkind: ConfigMap\\nmetadata: {name: c}\"}");
    assertThatThrownBy(() -> connector.execute(apply, dev, secrets(Map.of(K8sSettings.TOKEN, "t")), ctx(Env.DEV)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void anApprovalOfAnotherActionDoesNotRunThisOne() {
    ToolCall restart = call("k8s_rollout_restart", "{\"kind\":\"deployment\",\"name\":\"api\"}");
    ToolCall other = call("k8s_rollout_restart", "{\"kind\":\"deployment\",\"name\":\"web\"}");
    var approvedOther = connector.describeAction(other, dev).payload();

    assertThatThrownBy(() -> connector.execute(restart, dev, secrets(Map.of(K8sSettings.TOKEN, "t")),
        ctx(Env.DEV).approved(approvedOther, K8sTestKit.json("{\"uid\":\"u1\"}"))))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("doesn't match the action that was approved");
        });
  }

  @Test
  void prepareRefusesABrokenManifestWithoutARequest() throws Exception {
    ToolCall apply = call("k8s_apply", "{\"manifest\":\"a: &x 1\\nb: *x\"}");

    PreparedAction p = connector.prepare(apply, dev, secrets(Map.of()), ctx(Env.DEV));

    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(p.message()).contains("alias");

    PreparedAction hidden = connector.prepare(call("k8s_apply", "{\"manifest\":\"apiVersion: v1\\nkind: ConfigMap\\n"
        + "metadata:\\n  name: cfg\\n  annotations:\\n    kubectl.kubernetes.io/last-applied-configuration: '{}'\\n\"}"),
        dev, secrets(Map.of()), ctx(Env.DEV));
    assertThat(hidden.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(hidden.message()).contains("last-applied-configuration");
  }

  @Test
  void describeConnectionListsTheKindsWithoutIo() throws Exception {
    var out = connector.describe(dev, secrets(Map.of()), ctx(Env.DEV), null).orElseThrow();

    assertThat(out.path("server").asString()).isEqualTo("k8s.dev.internal:6443");
    assertThat(out.path("defaultNamespace").asString()).isEqualTo("app-dev");
    assertThat(out.path("kindsFrom").asString()).contains("built-in list");
    assertThat(out.path("kinds").toString()).contains("\"deployments\"", "\"pods\"");

    var one = connector.describe(dev, secrets(Map.of()), ctx(Env.DEV), "deploy").orElseThrow();
    assertThat(one.path("kind").toString())
        .isEqualTo("{\"kind\":\"Deployment\",\"apiVersion\":\"apps/v1\",\"resource\":\"deployments.apps\","
            + "\"namespaced\":true}");
  }

  @Test
  void theReadOfAnApprovalEndpointIsItsArguments() throws Exception {
    PreparedAction p = connector.prepare(call("k8s_get", "{\"kind\":\"pods\"}"), dev, secrets(Map.of()),
        ctx(Env.DEV));

    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(p.preview().kind()).isEqualTo(ActionPreview.Kind.JSON);
  }

  @Test
  void policyViewWithoutASectionStillClassifies() {
    Classification c = connector.classify(call("k8s_get", "{\"kind\":\"pods\"}"), dev,
        new PolicyView(Env.DEV, "dev.k8s", null, null));

    assertThat(c.kind()).isEqualTo(Classification.Kind.READ);
  }
}
