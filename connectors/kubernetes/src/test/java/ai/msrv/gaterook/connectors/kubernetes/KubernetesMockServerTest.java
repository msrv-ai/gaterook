package ai.msrv.gaterook.connectors.kubernetes;

import static ai.msrv.gaterook.connectors.kubernetes.FakeApiServer.ok;
import static ai.msrv.gaterook.connectors.kubernetes.FakeApiServer.status;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.call;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.ctx;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.json;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.policy;
import static ai.msrv.gaterook.connectors.kubernetes.K8sTestKit.secrets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.mockwebserver.http.MockResponse;
import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools against an API server stand-in over verified TLS ({@code kubernetes-server-mock}'s web server): the
 * requests they send, what reaches the agent and the approver, and what is never sent.
 */
class KubernetesMockServerTest {

  private static final String TOKEN = "cnry-sa-token-7f3k";
  private static final String PODS_TABLE = """
      {"kind":"Table","apiVersion":"meta.k8s.io/v1","metadata":{"resourceVersion":"100","continue":"%s"},
       "columnDefinitions":[
         {"name":"Name","type":"string","format":"name","priority":0},
         {"name":"Ready","type":"string","priority":0},
         {"name":"Status","type":"string","priority":0},
         {"name":"Restarts","type":"string","priority":0},
         {"name":"Age","type":"string","format":"date","priority":0},
         {"name":"IP","type":"string","priority":1},
         {"name":"Node","type":"string","priority":1}],
       "rows":[
         {"cells":["api-7d9c-xk2","1/1","Running","0","2026-10-25T10:00:00Z","10.0.0.7","node-a"],
          "object":{"kind":"PartialObjectMetadata","metadata":{"name":"api-7d9c-xk2","namespace":"%s",
            "annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\\"cnry\\":\\"copy\\"}"}}}},
         {"cells":["worker-1","0/1","CrashLoopBackOff","12 (2m ago)","2026-10-24T12:00:00Z","10.0.0.8","node-b"],
          "object":{"kind":"PartialObjectMetadata","metadata":{"name":"worker-1","namespace":"%s"}}}]}
      """;

  private FakeApiServer api;
  private KubernetesConnector connector;
  private ConnectionConfig cfg;
  private final Map<String, String> token = Map.of(K8sSettings.TOKEN, TOKEN);

  @TempDir
  Path dir;

  @BeforeEach
  void start() throws IOException {
    api = new FakeApiServer();
    connector = new KubernetesConnector();
    cfg = K8sTestKit.cfg("c1", Env.DEV, api.tokenConfig("app-dev"), null);
  }

  @AfterEach
  void stop() {
    connector.close();
    api.close();
  }

  private ToolResult run(ToolCall call, K8sTestKit.Ctx ctx) throws ConnectorException {
    return connector.execute(call, cfg, secrets(token), ctx);
  }

  // ── k8s_get ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void listsWithTheServersColumnsAndPagesWithASealedCursor() throws Exception {
    api.on("GET", "/api/v1/namespaces/app-dev/pods", r -> ok(PODS_TABLE.formatted(
        r.query().containsKey("continue") ? "" : "server-continue-token", "app-dev", "app-dev")));
    K8sTestKit.Ctx ctx = ctx(Env.DEV);
    ctx.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-25T10:41:00Z"), java.time.ZoneOffset.UTC);

    ToolResult page = run(call("k8s_get", "{\"kind\":\"pods\",\"limit\":2}"), ctx);

    FakeApiServer.Request req = api.requests.get(0);
    assertThat(req.header("Accept")).startsWith("application/json;as=Table;v=v1;g=meta.k8s.io");
    assertThat(req.header("Authorization")).isEqualTo("Bearer " + TOKEN);
    assertThat(req.header("User-Agent")).startsWith("mcp-gateway/");
    assertThat(req.query()).containsEntry("limit", "2").containsEntry("includeObject", "Metadata");
    JsonNode out = page.structuredContent();
    assertThat(out.path("columns").toString()).isEqualTo("[\"Name\",\"Ready\",\"Status\",\"Restarts\",\"Age\"]");
    assertThat(out.at("/rows/0/cells").toString()).isEqualTo("[\"api-7d9c-xk2\",\"1/1\",\"Running\",\"0\",\"41m\"]");
    assertThat(out.toString()).doesNotContain("last-applied", "cnry", "server-continue-token");
    String cursor = out.path("cursor").asString();
    assertThat(cursor).startsWith("k8c.");
    assertThat(page.truncated()).isTrue();
    assertThat(page.content().get(0).text()).startsWith("pods in app-dev on dev-cluster · 2 objects · more: call "
        + "again with cursor k8c.").contains("NAME           READY   STATUS             RESTARTS      AGE");

    ToolResult next = run(call("k8s_get", "{\"kind\":\"pods\",\"limit\":2,\"cursor\":\"" + cursor + "\"}"), ctx);

    assertThat(api.requests.get(1).query()).containsEntry("continue", "server-continue-token");
    assertThat(next.structuredContent().path("cursor").isNull()).isTrue();
    // A cursor is bound to its listing: another kind or namespace can't continue it.
    assertThatThrownBy(() -> run(call("k8s_get", "{\"kind\":\"services\",\"cursor\":\"" + cursor + "\"}"), ctx))
        .hasMessageContaining("belongs to another listing");
    assertThatThrownBy(() -> run(call("k8s_get", "{\"kind\":\"pods\",\"cursor\":\"k8c.forged\"}"), ctx))
        .hasMessageContaining("invalid or expired");
  }

  @Test
  void wideAddsThePriorityColumns() throws Exception {
    api.json("GET", "/api/v1/namespaces/app-dev/pods", PODS_TABLE.formatted("", "app-dev", "app-dev"));

    ToolResult r = run(call("k8s_get", "{\"kind\":\"pods\",\"wide\":true}"), ctx(Env.DEV));

    assertThat(r.structuredContent().path("columns").toString()).contains("\"IP\"", "\"Node\"");
  }

  @Test
  void allNamespacesAsksEachAllowedNamespaceOnItsOwn() throws Exception {
    api.json("GET", "/api/v1/namespaces/app-dev/pods", PODS_TABLE.formatted("", "app-dev", "app-dev"));
    api.on("GET", "/api/v1/namespaces/web/pods", r -> status(403, "Forbidden", "pods is forbidden: User "
        + "\"system:serviceaccount:app-dev:gw\" cannot list resource \"pods\" in the namespace \"web\""));
    ToolCall all = call("tc_all", "k8s_get", "{\"kind\":\"pods\",\"allNamespaces\":true}");

    Classification c = connector.classify(all, cfg, policy(Env.DEV, "{\"namespaces\":[\"app-dev\",\"web\"]}"));
    ToolResult r = run(all, ctx(Env.DEV));

    assertThat(c.kind()).isEqualTo(Classification.Kind.READ);
    assertThat(api.requests).extracting(FakeApiServer.Request::path).containsExactly(
        "/api/v1/namespaces/app-dev/pods", "/api/v1/namespaces/web/pods");
    assertThat(api.requests).noneMatch(q -> q.path().equals("/api/v1/pods"));
    assertThat(r.structuredContent().path("columns").get(0).asString()).isEqualTo("Namespace");
    assertThat(r.structuredContent().path("notes").toString()).contains("Skipped web: forbidden by RBAC.");
  }

  @Test
  void anExecuteWithoutItsClassificationListsTheConnectionsNamespaceOnly() throws Exception {
    api.json("GET", "/api/v1/namespaces/app-dev/pods", PODS_TABLE.formatted("", "app-dev", "app-dev"));

    run(call("k8s_get", "{\"kind\":\"pods\",\"allNamespaces\":true}"), ctx(Env.DEV));

    assertThat(api.requests).extracting(FakeApiServer.Request::path).containsExactly(
        "/api/v1/namespaces/app-dev/pods");
  }

  @Test
  void oneObjectComesWithItsEventsAndRollout() throws Exception {
    api.json("GET", "/apis/apps/v1/namespaces/app-dev/deployments/api", """
        {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"api","namespace":"app-dev","uid":"u-42",
          "generation":7,"managedFields":[{"manager":"kubectl"}],
          "annotations":{"kubectl.kubernetes.io/last-applied-configuration":"{\\"secret\\":\\"cnry-la\\"}"}},
         "spec":{"replicas":3},
         "status":{"observedGeneration":7,"replicas":3,"updatedReplicas":3,"readyReplicas":3,"availableReplicas":3}}
        """);
    api.on("GET", "/api/v1/namespaces/app-dev/events", r -> ok("""
        {"kind":"EventList","items":[
          {"type":"Normal","reason":"ScalingReplicaSet","message":"Scaled up replica set api-7d9c to 3",
           "count":1,"lastTimestamp":"2026-10-25T10:30:00Z"},
          {"type":"Warning","reason":"FailedCreate","message":"\\u001b[31mquota exceeded\\u001b[0m","count":4,
           "lastTimestamp":"2026-10-25T10:40:00Z"}]}
        """));
    K8sTestKit.Ctx ctx = ctx(Env.DEV);
    ctx.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-25T10:41:00Z"), java.time.ZoneOffset.UTC);

    ToolResult r = run(call("k8s_get", "{\"kind\":\"deploy\",\"name\":\"api\"}"), ctx);

    assertThat(api.requests("GET", "/api/v1/namespaces/app-dev/events").get(0).query())
        .containsEntry("fieldSelector", "involvedObject.uid=u-42");
    JsonNode out = r.structuredContent();
    assertThat(out.toString()).doesNotContain("cnry-la", "managedFields");
    assertThat(out.at("/events/0/message").asString()).isEqualTo("\\e[31mquota exceeded\\e[0m");
    assertThat(out.at("/rollout/summary").asString()).isEqualTo("3/3 complete");
    assertThat(out.at("/events/0/reason").asString()).isEqualTo("FailedCreate");
    String text = r.content().get(0).text();
    assertThat(text).startsWith("deployment/api in app-dev on dev-cluster (dev) · rollout 3/3 complete\n")
        .contains("Events:\n  Warning  FailedCreate ×4  (60s ago)");
  }

  @Test
  void aSecretIsShownWithoutItsValues() throws Exception {
    api.json("GET", "/api/v1/namespaces/app-dev/secrets/db", """
        {"apiVersion":"v1","kind":"Secret","metadata":{"name":"db","namespace":"app-dev","uid":"s1"},
         "data":{"password":"Y25yeS1zZWNyZXQ="}}
        """);
    ToolCall get = call("k8s_get", "{\"kind\":\"secrets\",\"name\":\"db\",\"events\":false}");

    assertThat(connector.classify(get, cfg, policy(Env.DEV, "{\"resources\":[\"secrets\"]}")).kind())
        .isEqualTo(Classification.Kind.READ);
    ToolResult r = run(get, ctx(Env.DEV));

    assertThat(r.structuredContent().at("/object/data/password").asString()).isEqualTo("‹masked›");
    assertThat(r.content().get(0).text()).contains("password: ‹masked›").doesNotContain("Y25ye");
  }

  @Test
  void aServerWithoutTablesStillLists() throws Exception {
    api.json("GET", "/apis/metrics.k8s.io/v1beta1/namespaces/app-dev/pods", """
        {"kind":"PodMetricsList","items":[{"metadata":{"name":"api-1","creationTimestamp":"2026-10-25T10:00:00Z"}}]}
        """);
    ObjectNode metadata = K8sTestKit.NODES.objectNode();
    metadata.set("discovery", Kinds.snapshot(List.of(new KindInfo("metrics.k8s.io", "v1beta1", "PodMetrics", "pods",
        "pod", true, List.of()))));
    cfg = K8sTestKit.cfg("c1", Env.DEV, api.tokenConfig("app-dev"), metadata);

    ToolResult r = run(call("k8s_get", "{\"kind\":\"pods.metrics.k8s.io\"}"), ctx(Env.DEV));

    assertThat(r.structuredContent().path("columns").toString()).isEqualTo("[\"Name\",\"Status\",\"Age\"]");
    assertThat(r.structuredContent().at("/rows/0/name").asString()).isEqualTo("api-1");
  }

  @Test
  void errorsOfTheApiServerReadAsWhatToDo() throws Exception {
    api.on("GET", "/api/v1/namespaces/app-dev/pods/gone", r -> status(404, "NotFound", "pods \"gone\" not found"));
    api.on("GET", "/api/v1/namespaces/app-dev/pods/denied", r -> status(403, "Forbidden",
        "pods \"denied\" is forbidden: User \"gw\" cannot get resource \"pods\""));
    api.on("GET", "/api/v1/namespaces/app-dev/pods/old", r -> status(401, "Unauthorized", "Unauthorized"));
    api.on("GET", "/api/v1/namespaces/app-dev/pods/proxy", r -> new MockResponse().setResponseCode(502)
        .setBody("<html>Bad gateway</html>"));

    assertThatThrownBy(() -> run(call("k8s_get", "{\"kind\":\"pods\",\"name\":\"gone\",\"events\":false}"),
        ctx(Env.DEV))).hasMessage("pod/gone in app-dev not found.");
    assertThatThrownBy(() -> run(call("k8s_get", "{\"kind\":\"pods\",\"name\":\"denied\",\"events\":false}"),
        ctx(Env.DEV))).hasMessageStartingWith("Forbidden by the cluster's RBAC: pods \"denied\" is forbidden");
    assertThatThrownBy(() -> run(call("k8s_get", "{\"kind\":\"pods\",\"name\":\"old\",\"events\":false}"),
        ctx(Env.DEV))).hasMessageContaining("rejected the credentials").hasMessageNotContaining(TOKEN);
    assertThatThrownBy(() -> run(call("k8s_get", "{\"kind\":\"pods\",\"name\":\"proxy\",\"events\":false}"),
        ctx(Env.DEV))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE));
  }

  // ── k8s_logs ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void logsAreTheTailOfOneContainer() throws Exception {
    api.json("GET", "/api/v1/namespaces/app-dev/pods/api-1", """
        {"kind":"Pod","metadata":{"name":"api-1"},"spec":{"containers":[{"name":"api"},{"name":"sidecar"}]}}
        """);
    StringBuilder log = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      log.append("2026-10-25T10:00:00Z line ").append(i).append(i == 299 ? " \u001b[2Jdone" : "").append('\n');
    }
    api.on("GET", "/api/v1/namespaces/app-dev/pods/api-1/log", r -> new MockResponse().setResponseCode(200)
        .setBody(log.toString()));

    ToolResult r = run(call("k8s_logs", "{\"pod\":\"api-1\",\"container\":\"api\",\"tailLines\":300,"
        + "\"maxBytes\":1024}"), ctx(Env.DEV));

    FakeApiServer.Request req = api.requests("GET", "/api/v1/namespaces/app-dev/pods/api-1/log").get(0);
    assertThat(req.query()).containsEntry("container", "api").containsEntry("tailLines", "300")
        .containsEntry("limitBytes", String.valueOf(LogsTool.LIMIT_BYTES));
    String text = r.content().get(0).text();
    assertThat(text).startsWith("pod/api-1 (api) in app-dev on dev-cluster · ").contains("older lines cut at 1 KB");
    assertThat(text).doesNotContain("\u001b").endsWith("done");
    assertThat(r.truncatedReason()).isEqualTo("head");
    assertThatThrownBy(() -> run(call("k8s_logs", "{\"pod\":\"api-1\"}"), ctx(Env.DEV)))
        .hasMessageContaining("has containers api, sidecar: pick one with container");
  }

  @Test
  void aDeploymentsLogsComeFromItsReadyNewestPod() throws Exception {
    api.json("GET", "/apis/apps/v1/namespaces/app-dev/deployments/api", """
        {"kind":"Deployment","spec":{"selector":{"matchLabels":{"app":"api"}}}}
        """);
    api.json("GET", "/api/v1/namespaces/app-dev/pods", """
        {"kind":"PodList","items":[
          {"metadata":{"name":"api-old","creationTimestamp":"2026-10-24T00:00:00Z"},
           "spec":{"containers":[{"name":"api"}]},
           "status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}]}},
          {"metadata":{"name":"api-new","creationTimestamp":"2026-10-25T00:00:00Z"},
           "spec":{"containers":[{"name":"api"}]},
           "status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}]}},
          {"metadata":{"name":"api-starting","creationTimestamp":"2026-10-25T09:00:00Z"},
           "spec":{"containers":[{"name":"api"}]},"status":{"phase":"Pending"}}]}
        """);
    api.on("GET", "/api/v1/namespaces/app-dev/pods/api-new/log", r -> new MockResponse().setResponseCode(200)
        .setBody("hello\n"));

    ToolResult r = run(call("k8s_logs", "{\"pod\":\"deployment/api\"}"), ctx(Env.DEV));

    assertThat(api.requests("GET", "/api/v1/namespaces/app-dev/pods").get(0).query())
        .containsEntry("labelSelector", "app=api");
    assertThat(r.structuredContent().path("pod").asString()).isEqualTo("api-new");
    assertThat(r.content().get(0).text()).endsWith("hello");
  }

  // ── k8s_rollout_restart ─────────────────────────────────────────────────────────────────────────────────────

  private static final String DEPLOYMENT = """
      {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"api","namespace":"app-dev","uid":"%s",
        "generation":4},
       "spec":{"replicas":3,"paused":%s,"template":{"spec":{"containers":[{"name":"api","image":"api:1.4.2"}]}}}}
      """;

  @Test
  void aRestartIsHeldToTheApprovedObject() throws Exception {
    AtomicInteger patches = new AtomicInteger();
    api.on("GET", "/apis/apps/v1/namespaces/app-dev/deployments/api", r -> ok(DEPLOYMENT.formatted("u-1", false)));
    api.on("PATCH", "/apis/apps/v1/namespaces/app-dev/deployments/api", r -> {
      patches.incrementAndGet();
      return ok("{\"metadata\":{\"generation\":5}}");
    });
    ToolCall restart = call("k8s_rollout_restart", "{\"kind\":\"deployment\",\"name\":\"api\"}");

    PreparedAction prepared = connector.prepare(restart, cfg, secrets(token), ctx(Env.DEV));

    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(prepared.preparedState().path("uid").asString()).isEqualTo("u-1");
    assertThat(prepared.preview().impact()).extracting(ActionPreview.Field::value)
        .contains("3, up to 1 new at a time, at most 0 unavailable");
    assertThat(patches).hasValue(0);

    K8sTestKit.Ctx approved = ctx(Env.DEV).approved(prepared.preview().payload(), prepared.preparedState());
    approved.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-25T11:00:00.123Z"),
        java.time.ZoneOffset.UTC);
    ToolResult r = run(restart, approved);

    FakeApiServer.Request patch = api.requests("PATCH", "/apis/apps/v1/namespaces/app-dev/deployments/api").get(0);
    assertThat(patch.header("Content-Type")).startsWith("application/merge-patch+json");
    assertThat(patch.query()).containsEntry("fieldManager", "mcp-gateway-rollout");
    assertThat(json(patch.body()).toString()).isEqualTo("{\"metadata\":{\"uid\":\"u-1\"},\"spec\":{\"template\":"
        + "{\"metadata\":{\"annotations\":{\"kubectl.kubernetes.io/restartedAt\":\"2026-10-25T11:00:00Z\"}}}}}");
    assertThat(r.content().get(0).text()).startsWith("Restarted deployment/api in app-dev on dev-cluster");
  }

  @Test
  void aReplacedOrPausedObjectIsNotRestarted() throws Exception {
    api.on("GET", "/apis/apps/v1/namespaces/app-dev/deployments/api", r -> ok(DEPLOYMENT.formatted("u-2", false)));
    ToolCall restart = call("k8s_rollout_restart", "{\"kind\":\"deployment\",\"name\":\"api\"}");
    JsonNode payload = connector.describeAction(restart, cfg).payload();

    assertThatThrownBy(() -> run(restart, ctx(Env.DEV).approved(payload, json("{\"uid\":\"u-1\"}"))))
        .hasMessageContaining("deleted or replaced after approval");
    assertThat(api.requests("PATCH", "/apis/apps/v1/namespaces/app-dev/deployments/api")).isEmpty();

    api.on("GET", "/apis/apps/v1/namespaces/app-dev/deployments/api", r -> ok(DEPLOYMENT.formatted("u-2", true)));
    assertThat(connector.prepare(restart, cfg, secrets(token), ctx(Env.DEV)).message()).contains("is paused");
  }

  // ── k8s_apply ───────────────────────────────────────────────────────────────────────────────────────────────

  private static final String CONFIGMAP = """
      {"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"cfg","namespace":"app-dev","uid":"cm-1",
        "resourceVersion":"%s","creationTimestamp":"2026-10-01T00:00:00Z"},"data":{"mode":"%s"}}
      """;

  @Test
  void applyShowsTheDryRunDiffAndAppliesAtTheApprovedVersion() throws Exception {
    api.on("GET", "/api/v1/namespaces/app-dev/configmaps/cfg", r -> ok(CONFIGMAP.formatted("50", "slow")));
    api.on("PATCH", "/api/v1/namespaces/app-dev/configmaps/cfg", r -> ok(CONFIGMAP.formatted(
        "All".equals(r.query().get("dryRun")) ? "50" : "51", "fast")));
    ToolCall apply = call("k8s_apply", "{\"manifest\":\"apiVersion: v1\\nkind: ConfigMap\\nmetadata:\\n  name: cfg\\n"
        + "data:\\n  mode: fast\\n\"}");

    PreparedAction prepared = connector.prepare(apply, cfg, secrets(token), ctx(Env.DEV));

    FakeApiServer.Request dry = api.requests("PATCH", "/api/v1/namespaces/app-dev/configmaps/cfg").get(0);
    assertThat(dry.header("Content-Type")).startsWith("application/apply-patch+yaml");
    assertThat(dry.query()).containsEntry("dryRun", "All").containsEntry("fieldManager", "mcp-gateway")
        .containsEntry("fieldValidation", "Strict").doesNotContainKey("force");
    assertThat(prepared.outcome()).isEqualTo(PreparedAction.Outcome.APPROVAL);
    assertThat(prepared.preview().kind()).isEqualTo(ActionPreview.Kind.DIFF);
    assertThat(prepared.preview().text()).contains("-  mode: slow\n+  mode: fast").startsWith("--- configmap/cfg");
    assertThat(prepared.preparedState().at("/objects/0/rv").asString()).isEqualTo("50");
    assertThat(prepared.preview().fields()).extracting(ActionPreview.Field::value).contains("1 configured");

    ToolResult r = run(apply, ctx(Env.DEV).approved(prepared.preview().payload(), prepared.preparedState()));

    List<FakeApiServer.Request> patches = api.requests("PATCH", "/api/v1/namespaces/app-dev/configmaps/cfg");
    FakeApiServer.Request real = patches.get(patches.size() - 1);
    assertThat(real.query()).doesNotContainKey("dryRun").containsEntry("fieldManager", "mcp-gateway");
    assertThat(json(real.body()).at("/metadata/resourceVersion").asString()).isEqualTo("50");
    assertThat(r.structuredContent().path("status").asString()).isEqualTo("completed");
    assertThat(r.content().get(0).text()).isEqualTo("Applied on dev-cluster: configmap/cfg configured.");
  }

  @Test
  void aConflictAfterApprovalAppliesOnlyIfTheDryRunIsStillTheApprovedOne() throws Exception {
    String[] liveMode = {"slow"};
    String[] rv = {"50"};
    api.on("GET", "/api/v1/namespaces/app-dev/configmaps/cfg", r -> ok(CONFIGMAP.formatted(rv[0], liveMode[0])));
    api.on("PATCH", "/api/v1/namespaces/app-dev/configmaps/cfg", r -> {
      if ("All".equals(r.query().get("dryRun"))) {
        return ok(CONFIGMAP.formatted(rv[0], liveMode[0].equals("other") ? "fast-but-other" : "fast"));
      }
      String precondition = json(r.body()).at("/metadata/resourceVersion").asString();
      if (!precondition.equals(rv[0])) {
        return status(409, "Conflict", "Operation cannot be fulfilled: the object has been modified");
      }
      return ok(CONFIGMAP.formatted("99", "fast"));
    });
    ToolCall apply = call("k8s_apply", "{\"manifest\":\"apiVersion: v1\\nkind: ConfigMap\\nmetadata:\\n  name: cfg\\n"
        + "data:\\n  mode: fast\\n\"}");
    PreparedAction prepared = connector.prepare(apply, cfg, secrets(token), ctx(Env.DEV));

    // Only the version moved (a controller wrote the status): the same approved object goes in at the new version.
    rv[0] = "60";
    ToolResult ok = run(apply, ctx(Env.DEV).approved(prepared.preview().payload(), prepared.preparedState()));
    assertThat(ok.structuredContent().path("status").asString()).isEqualTo("completed");

    // Someone changed the object itself: the dry run differs from what was approved, nothing is applied.
    rv[0] = "70";
    liveMode[0] = "other";
    int before = api.requests.size();
    ToolResult stopped = run(apply, ctx(Env.DEV).approved(prepared.preview().payload(), prepared.preparedState()));
    assertThat(stopped.isError()).isTrue();
    assertThat(stopped.content().get(0).text()).startsWith("configmap/cfg changed in the cluster after approval.");
    assertThat(api.requests.subList(before, api.requests.size())).noneMatch(q -> q.method().equals("PATCH")
        && !"All".equals(q.query().get("dryRun")) && json(q.body()).at("/metadata/resourceVersion").asString()
        .equals("70"));
  }

  @Test
  void aFieldOwnedByAnotherManagerStopsTheApplyBeforeApproval() throws Exception {
    api.on("GET", "/api/v1/namespaces/app-dev/configmaps/cfg", r -> ok(CONFIGMAP.formatted("50", "slow")));
    api.on("PATCH", "/api/v1/namespaces/app-dev/configmaps/cfg", r -> new MockResponse().setResponseCode(409)
        .setBody("""
            {"kind":"Status","status":"Failure","reason":"Conflict","code":409,
             "message":"Apply failed with 1 conflict: conflict with \\"kubectl-client-side-apply\\": .data.mode",
             "details":{"causes":[{"reason":"FieldManagerConflict","field":".data.mode",
               "message":"conflict with \\"kubectl-client-side-apply\\" using v1"}]}}
            """));
    ToolCall apply = call("k8s_apply", "{\"manifest\":\"apiVersion: v1\\nkind: ConfigMap\\nmetadata: {name: cfg}\\n"
        + "data: {mode: fast}\"}");

    PreparedAction p = connector.prepare(apply, cfg, secrets(token), ctx(Env.DEV));

    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.REJECT);
    assertThat(p.message()).contains(".data.mode (owned by kubectl-client-side-apply)").contains("force=true");
  }

  @Test
  void anUnchangedObjectNeedsNoApproval() throws Exception {
    api.on("GET", "/api/v1/namespaces/app-dev/configmaps/cfg", r -> ok(CONFIGMAP.formatted("50", "fast")));
    api.on("PATCH", "/api/v1/namespaces/app-dev/configmaps/cfg", r -> ok(CONFIGMAP.formatted("50", "fast")));
    ToolCall apply = call("k8s_apply", "{\"manifest\":\"apiVersion: v1\\nkind: ConfigMap\\nmetadata: {name: cfg}\\n"
        + "data: {mode: fast}\"}");

    PreparedAction p = connector.prepare(apply, cfg, secrets(token), ctx(Env.DEV));

    assertThat(p.outcome()).isEqualTo(PreparedAction.Outcome.NO_CHANGE);
    assertThat(p.message()).startsWith("unchanged: 1 object already matches");
  }

  // ── Test connection and health ──────────────────────────────────────────────────────────────────────────────

  @Test
  void testConnectionSaysWhoTheCredentialsAreAndWhatTheyMayDo() throws Exception {
    String jwt = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"exp\":1700000000}".getBytes()) + ".sig";
    cfg = K8sTestKit.cfg("c1", Env.PROD, api.tokenConfig("app-prod"), null);
    api.json("GET", "/version", "{\"gitVersion\":\"v1.37.0\",\"platform\":\"linux/amd64\"}");
    api.json("POST", "/apis/authentication.k8s.io/v1/selfsubjectreviews",
        "{\"status\":{\"userInfo\":{\"username\":\"system:serviceaccount:app-prod:gw\",\"groups\":[\"system:"
            + "serviceaccounts\"]}}}");
    api.json("GET", "/api", "{\"kind\":\"APIGroupDiscoveryList\",\"items\":[{\"metadata\":{\"name\":\"\"},"
        + "\"versions\":[{\"version\":\"v1\",\"resources\":[{\"resource\":\"pods\",\"responseKind\":{\"kind\":\"Pod\"},"
        + "\"scope\":\"Namespaced\",\"verbs\":[\"get\",\"list\"]}]}]}]}");
    api.json("GET", "/apis", "{\"kind\":\"APIGroupDiscoveryList\",\"items\":[]}");
    api.json("GET", "/api/v1/namespaces/app-prod", "{\"kind\":\"Namespace\"}");
    api.json("POST", "/apis/authorization.k8s.io/v1/selfsubjectrulesreviews", """
        {"status":{"incomplete":false,"resourceRules":[
          {"verbs":["get","list","watch","delete"],"apiGroups":[""],"resources":["pods","pods/log"]},
          {"verbs":["get"],"apiGroups":[""],"resources":["secrets"]}]}}
        """);

    TestResult t = connector.test(cfg, secrets(Map.of(K8sSettings.TOKEN, jwt)), ctx(Env.PROD));

    assertThat(t.ok()).as(String.valueOf(t.error())).isTrue();
    assertThat(t.serverVersion()).isEqualTo("v1.37.0");
    assertThat(t.identity()).isEqualTo("system:serviceaccount:app-prod:gw");
    assertThat(t.permissions().level()).isEqualTo(TestResult.Level.WRITE);
    assertThat(t.hints()).extracting(TestResult.Hint::code).contains("k8s.broad-rights", "k8s.token-expired",
        "k8s.rbac-read-only", "k8s.restart-not-granted");
    assertThat(t.hints()).filteredOn(h -> h.code().equals("k8s.broad-rights")).singleElement()
        .satisfies(h -> {
          assertThat(h.severity()).isEqualTo(TestResult.Severity.DANGER);
          assertThat(h.text()).isEqualTo("This identity can delete pods and read Secrets in app-prod. Use a "
              + "read-only ServiceAccount for prod.");
        });
    assertThat(t.details().at("/snippets/k8s.rbac-read-only/text").asString()).contains("name: gw-reader");
    assertThat(t.metadataUpdate().at("/discovery/resources/0/p").asString()).isEqualTo("pods");
    assertThat(t.metadataUpdate().path("serverVersion").asString()).isEqualTo("Kubernetes v1.37.0");
    assertThat(K8sApi.JSON.writeValueAsString(t)).doesNotContain(jwt);
  }

  @Test
  void healthIsOneVersionRequest() {
    api.json("GET", "/version", "{\"gitVersion\":\"v1.37.0\"}");

    HealthResult h = connector.health(cfg, secrets(token), ctx(Env.DEV));

    assertThat(h.state()).isEqualTo(HealthResult.State.HEALTHY);
    assertThat(api.requests).hasSize(1);
  }

  @Test
  void aWrongCaFailsTlsInsteadOfTrustingEveryone() throws Exception {
    try (FakeApiServer other = new FakeApiServer()) {
      String config = api.tokenConfig("app-dev").replace(K8sApi.JSON.writeValueAsString(api.caPem),
          K8sApi.JSON.writeValueAsString(other.caPem));
      ConnectionConfig wrongCa = K8sTestKit.cfg("c2", Env.DEV, config, null);
      api.json("GET", "/version", "{\"gitVersion\":\"v1.37.0\"}");

      TestResult t = connector.test(wrongCa, secrets(token), ctx(Env.DEV));

      assertThat(t.ok()).isFalse();
      assertThat(t.error().message()).contains("TLS with the API server failed");
      assertThat(api.requests).isEmpty();
    }
  }

  // ── what is never sent or followed ──────────────────────────────────────────────────────────────────────────

  @Test
  void aRedirectIsNotFollowedWithTheToken() throws Exception {
    try (FakeApiServer elsewhere = new FakeApiServer()) {
      elsewhere.json("GET", "/api/v1/namespaces/app-dev/pods/x", "{}");
      api.on("GET", "/api/v1/namespaces/app-dev/pods/x", r -> new MockResponse().setResponseCode(302)
          .setHeader("Location", elsewhere.url() + "/api/v1/namespaces/app-dev/pods/x"));

      assertThatThrownBy(() -> run(call("k8s_get", "{\"kind\":\"pods\",\"name\":\"x\",\"events\":false}"),
          ctx(Env.DEV))).isInstanceOf(ConnectorException.class);
      assertThat(elsewhere.requests).isEmpty();
    }
  }

  @Test
  void theProcessesProxyAndKubeconfigAreNeverUsed() throws Exception {
    Path kubeconfig = dir.resolve("config");
    Files.writeString(kubeconfig, """
        apiVersion: v1
        kind: Config
        current-context: x
        clusters: [{name: x, cluster: {server: "https://127.0.0.1:1"}}]
        contexts: [{name: x, context: {cluster: x, user: x}}]
        users: [{name: x, user: {token: cnry-ambient-token}}]
        """);
    ProxySelector previous = ProxySelector.getDefault();
    AtomicInteger asked = new AtomicInteger();
    String[] props = {"kubeconfig", "kubernetes.master", "kubernetes.auth.token", "kubernetes.trust.certificates"};
    String[] old = new String[props.length];
    for (int i = 0; i < props.length; i++) {
      old[i] = System.getProperty(props[i]);
    }
    try {
      System.setProperty("kubeconfig", kubeconfig.toString());
      System.setProperty("kubernetes.master", "https://127.0.0.1:1");
      System.setProperty("kubernetes.auth.token", "cnry-ambient-token");
      System.setProperty("kubernetes.trust.certificates", "true");
      ProxySelector.setDefault(new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
          asked.incrementAndGet();
          return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
      });
      api.json("GET", "/version", "{\"gitVersion\":\"v1.37.0\"}");
      // A fresh connection id: a client built now, under these properties.
      ConnectionConfig fresh = K8sTestKit.cfg("c-ambient", Env.DEV, api.tokenConfig("app-dev"), null);

      assertThat(connector.health(fresh, secrets(token), ctx(Env.DEV)).state()).isEqualTo(HealthResult.State.HEALTHY);

      assertThat(api.requests.get(0).header("Authorization")).isEqualTo("Bearer " + TOKEN);
      assertThat(asked).hasValue(0);
    } finally {
      ProxySelector.setDefault(previous);
      for (int i = 0; i < props.length; i++) {
        if (old[i] == null) {
          System.clearProperty(props[i]);
        } else {
          System.setProperty(props[i], old[i]);
        }
      }
    }
  }

  @Test
  void aServerThatStallsIsCutOffAtTheDeadline() throws Exception {
    api.on("GET", "/api/v1/namespaces/app-dev/pods/slow", r -> new MockResponse().setResponseCode(200)
        .setBody("{}").setBodyDelay(Duration.ofSeconds(5)));
    api.on("PATCH", "/apis/apps/v1/namespaces/app-dev/deployments/api", r -> new MockResponse().setResponseCode(200)
        .setBody("{}").setBodyDelay(Duration.ofSeconds(5)));
    api.on("GET", "/apis/apps/v1/namespaces/app-dev/deployments/api", r -> ok(DEPLOYMENT.formatted("u-1", false)));

    long started = System.nanoTime();
    assertThatThrownBy(() -> run(call("k8s_get", "{\"kind\":\"pods\",\"name\":\"slow\",\"events\":false}"),
        ctx(Env.DEV).deadlineIn(Duration.ofMillis(1200)))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));

    // A change sent and not answered may have happened: outcome unknown, never retried.
    ToolCall restart = call("k8s_rollout_restart", "{\"kind\":\"deployment\",\"name\":\"api\"}");
    JsonNode payload = connector.describeAction(restart, cfg).payload();
    assertThatThrownBy(() -> run(restart, ctx(Env.DEV).deadlineIn(Duration.ofMillis(1500))
        .approved(payload, json("{\"uid\":\"u-1\"}")))).isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.OUTCOME_UNKNOWN));
    assertThat(api.requests("PATCH", "/apis/apps/v1/namespaces/app-dev/deployments/api")).hasSize(1);
  }

  // ── credential commands ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aCredentialCommandsTokenIsUsedTaintedAndRefreshedOnce401() throws Exception {
    Path plugin = dir.resolve("auth-plugin");
    Path counter = dir.resolve("runs");
    Files.writeString(plugin, """
        #!/bin/sh
        echo run >> "%s"
        n=$(wc -l < "%s" | tr -d ' ')
        printf '{"apiVersion":"client.authentication.k8s.io/v1","kind":"ExecCredential",'
        printf '"status":{"token":"cnry-exec-%%s"}}\\n' "$n"
        """.formatted(counter, counter));
    plugin.toFile().setExecutable(true);
    String kubeconfig = """
        apiVersion: v1
        kind: Config
        current-context: dev
        clusters: [{name: dev, cluster: {server: "%s", certificate-authority-data: %s}}]
        contexts: [{name: dev, context: {cluster: dev, user: dev}}]
        users:
        - name: dev
          user:
            exec: {apiVersion: client.authentication.k8s.io/v1, command: "%s"}
        """.formatted(api.url(), Base64.getEncoder().encodeToString(api.caPem.getBytes()), plugin);
    Map<String, String> secret = Map.of(K8sSettings.KUBECONFIG, kubeconfig);
    ConnectionConfig draft = K8sTestKit.cfg("draft-1", Env.DEV, "{\"auth\":\"kubeconfig\",\"namespace\":\"app-dev\","
        + "\"allowExecPlugin\":true}", null);
    String fingerprint = connector.test(draft, secrets(secret), ctx(Env.DEV)).needsConfirmation().configPatch()
        .path("trustedExec").asString();
    ConnectionConfig trusted = K8sTestKit.cfg("c-exec", Env.DEV, "{\"auth\":\"kubeconfig\",\"namespace\":"
        + "\"app-dev\",\"allowExecPlugin\":true,\"trustedExec\":\"" + fingerprint + "\"}", null);
    AtomicInteger calls = new AtomicInteger();
    api.on("GET", "/api/v1/namespaces/app-dev/pods", r -> calls.incrementAndGet() == 1
        ? status(401, "Unauthorized", "Unauthorized") : ok(PODS_TABLE.formatted("", "app-dev", "app-dev")));
    K8sTestKit.Ctx ctx = ctx(Env.DEV);

    ToolResult r = connector.execute(call("k8s_get", "{\"kind\":\"pods\"}"), trusted, secrets(secret), ctx);

    assertThat(r.isError()).isFalse();
    List<String> auth = api.requests("GET", "/api/v1/namespaces/app-dev/pods").stream()
        .map(q -> q.header("Authorization")).toList();
    assertThat(auth.get(0)).isEqualTo("Bearer cnry-exec-1");
    assertThat(auth.get(auth.size() - 1)).isEqualTo("Bearer cnry-exec-2");
    assertThat(ctx.tainted).contains("cnry-exec-1", "cnry-exec-2");
  }
}
