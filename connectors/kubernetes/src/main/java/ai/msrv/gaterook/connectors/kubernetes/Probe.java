package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Check;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Level;
import ai.msrv.gaterook.connector.api.TestResult.Permissions;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connectors.kubernetes.K8sApi.StatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test connection for Kubernetes (report 08 §5): the version (the second, warm request's latency, as the kit shows
 * "Healthy · 38 ms"), who the credentials are ({@code SelfSubjectReview}), what they may do in the connection's
 * namespace ({@code SelfSubjectRulesReview}, or {@code SelfSubjectAccessReview} when the rules are incomplete, as
 * with the IAM authorizers of GKE and EKS), whether the namespace exists, and the discovery snapshot that
 * {@code classify} resolves kinds with. Hints in the kit's tone say what an identity can do that it shouldn't.
 */
final class Probe {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** What the tools need and what nobody should hand an agent, asked of the cluster one by one. */
  private record Ask(String verb, String group, String resource, String subresource, String label) {}

  private static final List<Ask> ASKS = List.of(
      new Ask("list", "", "pods", null, "list pods"),
      new Ask("get", "", "pods", "log", "read pod logs"),
      new Ask("list", "apps", "deployments", null, "list deployments"),
      new Ask("patch", "apps", "deployments", null, "patch deployments"),
      new Ask("get", "", "secrets", null, "read Secrets"),
      new Ask("delete", "", "pods", null, "delete pods"),
      new Ask("create", "", "pods", "exec", "exec into pods"),
      new Ask("*", "*", "*", null, "do anything"));

  private Probe() {}

  static TestResult test(ConnectionConfig cfg, K8sSettings s, ClusterAccess access, K8sApi api, ExecContext ctx) {
    List<Check> checks = new ArrayList<>();
    long started = System.nanoTime();
    JsonNode version;
    long latency;
    try {
      api.get("version", K8sApi.query(), K8sApi.ACCEPT_JSON, ctx);
      long warm = System.nanoTime();
      version = api.get("version", K8sApi.query(), K8sApi.ACCEPT_JSON, ctx);
      latency = Math.max(1, (System.nanoTime() - warm) / 1_000_000);
    } catch (StatusException e) {
      return failure("k8s.version", e.code == 401 ? "The API server rejected the credentials (401). Check the token "
          + "or the kubeconfig user." : "The API server answered /version with " + e.code + ": " + e.getMessage(),
          checks, started);
    } catch (ConnectorException e) {
      return failure("k8s.unreachable", e.getMessage(), checks, started);
    }
    String gitVersion = version.path("gitVersion").asString("unknown");
    checks.add(new Check("version", true, latency, gitVersion + " · " + version.path("platform").asString("")));
    List<Hint> hints = new ArrayList<>();
    ObjectNode details = NODES.objectNode();
    details.put("server", access.target());
    details.put("platform", version.path("platform").asString(null));
    details.put("namespace", s.namespace());

    // Who am I.
    String identity = null;
    List<String> groups = new ArrayList<>();
    long t = System.nanoTime();
    try {
      JsonNode review = api.post("apis/authentication.k8s.io/v1/selfsubjectreviews", NODES.objectNode()
          .put("apiVersion", "authentication.k8s.io/v1").put("kind", "SelfSubjectReview"), ctx);
      JsonNode user = review.path("status").path("userInfo");
      identity = user.path("username").asString(null);
      user.path("groups").forEach(g -> groups.add(g.asString()));
      checks.add(new Check("identity", true, ms(t), identity));
    } catch (StatusException e) {
      checks.add(new Check("identity", false, ms(t), e.code == 404 ? "SelfSubjectReview isn't served (Kubernetes "
          + "< 1.28)" : e.getMessage()));
    } catch (ConnectorException e) {
      checks.add(new Check("identity", false, ms(t), e.getMessage()));
    }
    ArrayNode groupList = details.putArray("groups");
    groups.forEach(groupList::add);
    tokenHints(access, ctx.clock().instant(), hints);
    if (access.token() == null && access.clientCertPem() == null && access.exec() == null) {
      hints.add(new Hint(Severity.WARNING, "k8s.anonymous", null, "No credentials: requests are anonymous."));
    }
    if (access.insecure()) {
      hints.add(new Hint(Severity.WARNING, "k8s.insecure", "/insecureSkipTlsVerify", "TLS verification is off: "
          + "anyone on the network path can read the token. Paste the cluster's CA instead."));
    }
    if (access.tlsServerName() != null) {
      hints.add(new Hint(Severity.WARNING, "k8s.tls-server-name", null, "The kubeconfig sets tls-server-name, which "
          + "the HTTP client can't use: the API server's certificate must name " + access.server().getHost() + "."));
    }

    // What the cluster serves.
    ObjectNode metadata = NODES.objectNode();
    metadata.put("serverVersion", "Kubernetes " + gitVersion);
    t = System.nanoTime();
    try {
      List<KindInfo> kinds = discovery(api, ctx);
      metadata.set("discovery", Kinds.snapshot(kinds));
      checks.add(new Check("discovery", true, ms(t), kinds.size() + " resources"));
    } catch (StatusException | ConnectorException e) {
      checks.add(new Check("discovery", false, ms(t), e.getMessage()));
    }
    if (!access.contexts().isEmpty()) {
      metadata.set("kubeconfigContexts", Kubeconfig.contextsTree(access.contexts()));
    }

    // The namespace.
    t = System.nanoTime();
    try {
      api.get("api/v1/namespaces/" + K8sApi.segment(s.namespace()), K8sApi.query(), K8sApi.ACCEPT_JSON, ctx);
      checks.add(new Check("namespace", true, ms(t), s.namespace()));
    } catch (StatusException e) {
      if (e.code == 404) {
        checks.add(new Check("namespace", false, ms(t), "not found"));
        hints.add(new Hint(Severity.ERROR, "k8s.namespace-missing", "/namespace", "Namespace " + s.namespace()
            + " doesn't exist on this cluster."));
      } else {
        checks.add(new Check("namespace", true, ms(t), "not checked (" + e.code + ")"));
      }
    } catch (ConnectorException e) {
      checks.add(new Check("namespace", false, ms(t), e.getMessage()));
    }

    // What this identity may do there.
    t = System.nanoTime();
    Map<String, Boolean> can = new LinkedHashMap<>();
    try {
      can = permissions(api, s.namespace(), ctx);
      checks.add(new Check("permissions", true, ms(t), allowedText(can)));
    } catch (StatusException | ConnectorException e) {
      checks.add(new Check("permissions", false, ms(t), e.getMessage()));
    }
    boolean admin = Boolean.TRUE.equals(can.get("do anything")) || groups.contains("system:masters");
    Severity risky = cfg.env() == Env.PROD ? Severity.DANGER : Severity.WARNING;
    if (admin) {
      hints.add(new Hint(risky, "k8s.admin", null, "Full access" + (groups.contains("system:masters")
          ? " (system:masters)" : "") + ". Use a ServiceAccount with a Role limited to " + s.namespace() + "."));
    } else if (Boolean.TRUE.equals(can.get("delete pods")) || Boolean.TRUE.equals(can.get("read Secrets"))
        || Boolean.TRUE.equals(can.get("exec into pods"))) {
      List<String> risk = new ArrayList<>();
      for (String k : List.of("delete pods", "read Secrets", "exec into pods")) {
        if (Boolean.TRUE.equals(can.get(k))) {
          risk.add(k);
        }
      }
      hints.add(new Hint(risky, "k8s.broad-rights", null, "This identity can " + join(risk) + " in " + s.namespace()
          + ". Use a read-only ServiceAccount for prod."));
    }
    if (Boolean.FALSE.equals(can.get("patch deployments"))) {
      hints.add(new Hint(Severity.INFO, "k8s.restart-not-granted", null, "k8s_rollout_restart needs patch on "
          + "deployments in " + s.namespace() + ": not granted."));
    }
    if (Boolean.FALSE.equals(can.get("list pods"))) {
      hints.add(new Hint(Severity.WARNING, "k8s.list-not-granted", null, "Can't list pods in " + s.namespace()
          + ": k8s_get will fail there."));
    }
    if (admin || Boolean.TRUE.equals(can.get("read Secrets")) || Boolean.TRUE.equals(can.get("delete pods"))) {
      hints.add(new Hint(Severity.INFO, "k8s.rbac-read-only", null, "Copy RBAC for a read-only ServiceAccount."));
      details.putObject("snippets").putObject("k8s.rbac-read-only").put("language", "yaml")
          .put("text", readOnlyRbac(s.namespace()));
    }
    Level level = admin ? Level.ADMIN : can.isEmpty() ? Level.UNKNOWN
        : Boolean.TRUE.equals(can.get("patch deployments")) || Boolean.TRUE.equals(can.get("delete pods"))
            ? Level.WRITE : Level.READ;
    List<String> reads = new ArrayList<>();
    List<String> writes = new ArrayList<>();
    for (Map.Entry<String, Boolean> e : can.entrySet()) {
      if (Boolean.TRUE.equals(e.getValue())) {
        (e.getKey().startsWith("list") || e.getKey().startsWith("read") ? reads : writes).add(e.getKey());
      }
    }
    ObjectNode permDetails = NODES.objectNode();
    can.forEach(permDetails::put);
    Permissions permissions = new Permissions(level, reads.isEmpty() ? null : join(reads) + " in " + s.namespace(),
        writes.isEmpty() ? null : join(writes) + " in " + s.namespace(), admin ? List.of("*") : List.of(),
        permDetails);
    details.set("permissions", permDetails);
    TestResult result = TestResult.success(latency, gitVersion, identity, permissions).withChecks(checks)
        .withMetadata(metadata).withDetails(details);
    for (Hint h : hints) {
      result = result.withHint(h);
    }
    return result;
  }

  /** {@code /version} only: cheap enough for every few minutes. */
  static long health(K8sApi api, ExecContext ctx) throws StatusException, ConnectorException {
    long t = System.nanoTime();
    api.get("version", K8sApi.query(), K8sApi.ACCEPT_JSON, ctx);
    return Math.max(1, (System.nanoTime() - t) / 1_000_000);
  }

  /** Aggregated discovery in two requests; one request per group on clusters older than 1.27. */
  static List<KindInfo> discovery(K8sApi api, ExecContext ctx) throws StatusException, ConnectorException {
    JsonNode core = api.get("api", K8sApi.query(), K8sApi.ACCEPT_DISCOVERY, ctx);
    JsonNode groups = api.get("apis", K8sApi.query(), K8sApi.ACCEPT_DISCOVERY, ctx);
    if ("APIGroupDiscoveryList".equals(core.path("kind").asString())
        && "APIGroupDiscoveryList".equals(groups.path("kind").asString())) {
      List<KindInfo> out = new ArrayList<>(Kinds.fromAggregated(core));
      out.addAll(Kinds.fromAggregated(groups));
      return out;
    }
    List<KindInfo> out = new ArrayList<>(Kinds.fromResourceList(api.get("api/v1", K8sApi.query(),
        K8sApi.ACCEPT_JSON, ctx)));
    for (JsonNode g : groups.path("groups")) {
      String gv = g.path("preferredVersion").path("groupVersion").asString(null);
      if (gv != null) {
        try {
          out.addAll(Kinds.fromResourceList(api.get("apis/" + gv, K8sApi.query(), K8sApi.ACCEPT_JSON, ctx)));
        } catch (StatusException e) {
          // An aggregated API that is down (metrics-server): its kinds are unknown until it answers.
        }
      }
    }
    return out;
  }

  /** The asks above, from the rules review, with access reviews for whatever the rules can't tell. */
  private static Map<String, Boolean> permissions(K8sApi api, String ns, ExecContext ctx)
      throws StatusException, ConnectorException {
    JsonNode review = api.post("apis/authorization.k8s.io/v1/selfsubjectrulesreviews", NODES.objectNode()
        .put("apiVersion", "authorization.k8s.io/v1").put("kind", "SelfSubjectRulesReview")
        .set("spec", NODES.objectNode().put("namespace", ns)), ctx);
    JsonNode status = review.path("status");
    boolean incomplete = status.path("incomplete").asBoolean(false);
    Map<String, Boolean> out = new LinkedHashMap<>();
    for (Ask a : ASKS) {
      boolean allowed = allowedByRules(status.path("resourceRules"), a);
      if (!allowed && incomplete) {
        allowed = accessReview(api, ns, a, ctx);
      }
      out.put(a.label(), allowed);
    }
    return out;
  }

  private static boolean allowedByRules(JsonNode rules, Ask a) {
    String resource = a.subresource() == null ? a.resource() : a.resource() + "/" + a.subresource();
    for (JsonNode r : rules) {
      if (r.path("resourceNames").size() > 0) {
        continue;
      }
      if (has(r.path("verbs"), a.verb()) && has(r.path("apiGroups"), a.group())
          && (has(r.path("resources"), resource) || a.subresource() == null && has(r.path("resources"), "*")
          || a.subresource() != null && has(r.path("resources"), a.resource() + "/*"))) {
        return true;
      }
    }
    return false;
  }

  private static boolean has(JsonNode list, String value) {
    for (JsonNode v : list) {
      if (v.asString().equals(value) || v.asString().equals("*")) {
        return true;
      }
    }
    return false;
  }

  private static boolean accessReview(K8sApi api, String ns, Ask a, ExecContext ctx)
      throws StatusException, ConnectorException {
    ObjectNode attrs = NODES.objectNode().put("namespace", ns).put("verb", a.verb()).put("group", a.group())
        .put("resource", a.resource());
    if (a.subresource() != null) {
      attrs.put("subresource", a.subresource());
    }
    JsonNode r = api.post("apis/authorization.k8s.io/v1/selfsubjectaccessreviews", NODES.objectNode()
        .put("apiVersion", "authorization.k8s.io/v1").put("kind", "SelfSubjectAccessReview")
        .set("spec", NODES.objectNode().set("resourceAttributes", attrs)), ctx);
    return r.path("status").path("allowed").asBoolean(false);
  }

  /** A token that is a JWT says when it expires; a legacy service-account token never does. */
  private static void tokenHints(ClusterAccess access, Instant now, List<Hint> hints) {
    if (access.token() == null) {
      return;
    }
    String[] parts = access.token().split("\\.");
    if (parts.length != 3) {
      return;
    }
    JsonNode claims;
    try {
      claims = K8sApi.JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    } catch (RuntimeException e) {
      return;
    }
    if (!claims.has("exp")) {
      hints.add(new Hint(Severity.WARNING, "k8s.token-never-expires", "/token", "Never expires. Use a bound token "
          + "(kubectl create token) or a short-lived source."));
      return;
    }
    Duration left = Duration.between(now, Instant.ofEpochSecond(claims.path("exp").asLong()));
    if (left.isNegative()) {
      hints.add(new Hint(Severity.ERROR, "k8s.token-expired", "/token", "The token expired " + K8sText.age(left
          .negated()) + " ago."));
    } else {
      hints.add(new Hint(Severity.INFO, "k8s.token-expiry", "/token", "Token expires in " + K8sText.age(left) + "."));
    }
  }

  private static String readOnlyRbac(String ns) {
    return """
        apiVersion: v1
        kind: ServiceAccount
        metadata:
          name: gw-reader
          namespace: %1$s
        ---
        apiVersion: rbac.authorization.k8s.io/v1
        kind: Role
        metadata:
          name: gw-reader
          namespace: %1$s
        rules:
        - apiGroups: [""]
          resources: [pods, pods/log, services, configmaps, events]
          verbs: [get, list, watch]
        - apiGroups: [apps, batch]
          resources: [deployments, statefulsets, daemonsets, replicasets, jobs, cronjobs]
          verbs: [get, list, watch]
        ---
        apiVersion: rbac.authorization.k8s.io/v1
        kind: RoleBinding
        metadata:
          name: gw-reader
          namespace: %1$s
        roleRef: {apiGroup: rbac.authorization.k8s.io, kind: Role, name: gw-reader}
        subjects:
        - {kind: ServiceAccount, name: gw-reader, namespace: %1$s}
        # Token: kubectl create token gw-reader -n %1$s --duration=24h
        """.formatted(ns);
  }

  private static String allowedText(Map<String, Boolean> can) {
    List<String> yes = new ArrayList<>();
    can.forEach((k, v) -> {
      if (v) {
        yes.add(k);
      }
    });
    return yes.isEmpty() ? "nothing asked is allowed" : "can " + join(yes);
  }

  private static String join(List<String> items) {
    if (items.size() <= 1) {
      return String.join("", items);
    }
    return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
  }

  private static long ms(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  private static TestResult failure(String code, String message, List<Check> checks, long started) {
    return TestResult.failure(code, message, null).withChecks(checks);
  }
}
