package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ConnectorManifest.RequiredAccess;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.kubernetes.K8sApi.StatusException;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Apply;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Get;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Logs;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Plan;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Refusal;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Restart;
import ai.msrv.gaterook.connectors.kubernetes.K8sSettings.Auth;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Kubernetes connector (report 08, ARCHITECTURE.md §3): {@code k8s_get} and {@code k8s_logs} read;
 * {@code k8s_rollout_restart} and {@code k8s_apply} change the cluster and always wait for a person. There is no
 * delete, scale, exec, port-forward or proxy: those requests can't be built.
 *
 * <p>What makes it safe: the connection's own credentials only ({@code Config.empty()}, no kubeconfig of the
 * process, no environment); namespaces, resources and verbs checked by {@code classify} against the environment's
 * policy without I/O; Secrets and the {@code last-applied-configuration} that copies them never shown; the approver
 * reads the command or the API server's own dry-run diff, and what runs after approval is that object — held to it by
 * the uid (restart) or the {@code resourceVersion} and hash of the dry run (apply).
 */
public final class KubernetesConnector implements Connector {

  public static final String TYPE = "kubernetes";

  private final ConnectorManifest manifest;
  private final K8sClients clients;
  private final ClassifiedScopes scopes = new ClassifiedScopes();

  public KubernetesConnector() {
    this(Clock.systemUTC());
  }

  KubernetesConnector(Clock clock) {
    SchemaResources schemas = SchemaResources.of(KubernetesConnector.class, "kubernetes");
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Kubernetes")
        .icon("ship-wheel")
        .group("Kubernetes")
        .description("Read resources, events and pod logs. Rollouts and apply wait for approval.")
        .configSchema(schemas.load("config"))
        .policySchema(schemas.load("policy"))
        .tools(KubernetesTools.definitions(schemas))
        // Files this connector may read outside its own config ("Needs access to" on the catalog card).
        .access(new RequiredAccess(List.of(), List.of(), List.of("~/.kube/config")))
        .build();
    this.clients = new K8sClients(BuiltIn.VERSION, clock);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── classify ────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    K8sSettings s;
    try {
      s = K8sSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("config-invalid"), e.getMessage(), "Fix the connection in GateRook.");
    }
    Plan plan;
    try {
      plan = K8sRequests.plan(call, s, Kinds.of(cfg.metadata()), new K8sPolicy(policy, s.namespace()));
    } catch (Refusal r) {
      if (r.problem.deny()) {
        return Classification.deny(r.problem.ruleId(), r.problem.reason(), r.problem.hint());
      }
      // Wrong arguments: a read answers with the error, a change is refused by prepare before any request.
      return mutating(call.tool()) ? Classification.mutation(r.problem.reason()) : Classification.read();
    }
    return switch (plan) {
      case Get g -> {
        if (g.allNamespaces()) {
          scopes.put(call.callId(), g.namespaces());
        }
        yield Classification.read();
      }
      case Logs l -> Classification.read();
      case Restart r -> Classification.mutation("Restarts " + r.kind().display(r.name()) + " in " + r.namespace(),
          facts(r.kind(), r.namespace(), r.name()));
      case Apply a -> Classification.mutation("Applies " + K8sText.plural(a.items().size(), "object", "objects")
          + (a.force() ? " with force" : ""), facts(a.items().get(0).kind(), a.items().get(0).namespace(),
              a.items().get(0).name()));
    };
  }

  private static boolean mutating(String tool) {
    return tool.equals(KubernetesTools.RESTART) || tool.equals(KubernetesTools.APPLY);
  }

  private static JsonNode facts(KindInfo kind, String ns, String name) {
    ObjectNode f = JsonNodeFactory.instance.objectNode();
    f.put("k8s.kind", kind.singular()).put("k8s.name", name);
    if (ns != null) {
      f.put("k8s.namespace", ns);
    }
    return f;
  }

  // ── the exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    try {
      K8sSettings s = K8sSettings.of(cfg);
      Plan plan = planUnchecked(call, s, cfg);
      return switch (plan) {
        case Restart r -> RestartTool.describe(r, cfg, configuredTarget(s));
        case Apply a -> ApplyTool.describe(a, cfg, configuredTarget(s));
        default -> arguments(call, cfg);
      };
    } catch (ConnectorException | Refusal e) {
      return arguments(call, cfg);
    }
  }

  private static ActionPreview arguments(ToolCall call, ConnectionConfig cfg) {
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", call.arguments().toString(), cfg.name(),
        call.tool() + " on " + cfg.name());
  }

  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    K8sSettings s = K8sSettings.of(cfg);
    Plan plan;
    try {
      plan = planUnchecked(call, s, cfg);
    } catch (Refusal r) {
      return PreparedAction.reject(r.problem.reason());
    }
    if (!(plan instanceof Restart) && !(plan instanceof Apply)) {
      // A read the endpoint sends through approval (mode Approval): its arguments are the action.
      return PreparedAction.approval(arguments(call, cfg), null);
    }
    K8sClients.Client client = client(cfg, s, secrets, ctx);
    try {
      return switch (plan) {
        case Restart r -> RestartTool.prepare(r, client.api(), cfg, client.access().target(), ctx);
        case Apply a -> ApplyTool.prepare(a, client.api(), cfg, client.access().target(), ctx);
        default -> throw new IllegalStateException("not a change");
      };
    } finally {
      release(cfg, client, ctx);
    }
  }

  // ── execute ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    K8sSettings s = K8sSettings.of(cfg);
    Plan plan;
    try {
      plan = planUnchecked(call, s, cfg);
    } catch (Refusal r) {
      throw ConnectorException.badInput(r.problem.reason());
    }
    if ((plan instanceof Restart || plan instanceof Apply) && !ctx.approved()) {
      // Defence in depth: the policy sends every change to a person first; without that approval nothing changes.
      throw ConnectorException.denied(call.tool() + " changes the cluster and runs only after a person approved it.");
    }
    // What runs is what was approved: the plan of the stored call must give the stored payload, byte for byte.
    if (plan instanceof Restart r && !samePayload(RestartTool.describe(r, cfg, "").payload(), ctx)
        || plan instanceof Apply a && !samePayload(ApplyTool.describe(a, cfg, "").payload(), ctx)) {
      throw ConnectorException.denied("The call doesn't match the action that was approved. Not executed.");
    }
    if (plan instanceof Get g && g.allNamespaces()) {
      plan = new Get(g.kind(), g.name(), scopes.take(call.callId(), s.namespace()), true, g.labelSelector(),
          g.fieldSelector(), g.limit(), g.cursor(), g.wide(), g.events());
    }
    K8sClients.Client client = client(cfg, s, secrets, ctx);
    try {
      return switch (plan) {
        case Get g -> GetTool.run(g, client.api(), cfg, ctx);
        case Logs l -> LogsTool.run(l, client.api(), cfg, ctx);
        case Restart r -> RestartTool.execute(r, client.api(), cfg, ctx);
        case Apply a -> ApplyTool.execute(a, client.api(), cfg, ctx);
      };
    } finally {
      release(cfg, client, ctx);
    }
  }

  private static boolean samePayload(JsonNode expected, ExecContext ctx) {
    return ctx.approvedPayload().map(p -> p.equals(expected)).orElse(false);
  }

  /**
   * The plan of a call {@code classify} already let through: the checks against the environment's policy ran there
   * (and run again before an approved call executes); here only the arguments and the connection count.
   */
  private static Plan planUnchecked(ToolCall call, K8sSettings s, ConnectionConfig cfg) throws Refusal {
    return K8sRequests.plan(call, s, Kinds.of(cfg.metadata()), K8sPolicy.unrestricted(cfg.env(), s.namespace()));
  }

  // ── test, health, describe ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    K8sSettings s;
    ClusterAccess access;
    try {
      s = K8sSettings.of(cfg);
      access = ClusterAccess.resolve(s, secrets, cfg.env(), ctx::taint);
    } catch (ConnectorException e) {
      return TestResult.failure("k8s.config", e.getMessage(), null);
    }
    JsonNode contexts = access.contexts().isEmpty() ? null : JsonNodeFactory.instance.objectNode()
        .set("kubeconfigContexts", Kubeconfig.contextsTree(access.contexts()));
    if (s.auth() == Auth.KUBECONFIG_FILE && !access.matchesPin(s)) {
      return TestResult.confirm(pinConfirmation(s, access), List.of()).withMetadata(contexts);
    }
    if (access.exec() != null) {
      if (!s.allowExecPlugin()) {
        return TestResult.failure("k8s.exec-not-allowed", execOff(access), null).withMetadata(contexts);
      }
      String fp = ExecCredentials.fingerprint(access.exec());
      if (!fp.equals(s.trustedExec())) {
        return TestResult.confirm(execConfirmation(cfg, access, fp), List.of()).withMetadata(contexts);
      }
    }
    K8sClients.Client client;
    try {
      client = clients.client(cfg.id(), cfg.version(), access, s.requestTimeoutMs());
    } catch (ConnectorException e) {
      return TestResult.failure("k8s.config", e.getMessage(), null).withMetadata(contexts);
    }
    try {
      TestResult r = Probe.test(cfg, s, access, client.api(), ctx);
      return r.ok() || contexts == null ? r : r.withMetadata(contexts);
    } finally {
      release(cfg, client, ctx);
    }
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      K8sSettings s = K8sSettings.of(cfg);
      K8sClients.Client client = client(cfg, s, secrets, ctx);
      try {
        return HealthResult.healthy(Probe.health(client.api(), ctx));
      } finally {
        release(cfg, client, ctx);
      }
    } catch (StatusException e) {
      return HealthResult.unreachable(e.code == 401 ? "Credentials rejected (401)" : "HTTP " + e.code);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(K8sText.cut(e.getMessage(), 200));
    }
  }

  /**
   * What {@code describe_connection} adds, without I/O: where the connection points, its default namespace and the
   * kinds it knows; with {@code object}, how that kind resolves.
   */
  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    K8sSettings s = K8sSettings.of(cfg);
    Kinds kinds = Kinds.of(cfg.metadata());
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("server", configuredTarget(s));
    out.put("defaultNamespace", s.namespace());
    if (cfg.metadata() != null && cfg.metadata().has("serverVersion")) {
      out.put("version", cfg.metadata().path("serverVersion").asString());
    }
    if (object != null && !object.isBlank()) {
      KindInfo k = kinds.resolve(object).orElseThrow(() -> ConnectorException.badInput("No kind " + object
          + ". Known: " + kinds.known(24) + "."));
      out.putObject("kind").put("kind", k.kind()).put("apiVersion", k.apiVersion()).put("resource", k.rbacName())
          .put("namespaced", k.namespaced());
      return Optional.of(out);
    }
    out.put("kindsFrom", kinds.discovered() ? "the cluster's discovery (last Test connection)"
        : "the built-in list (test the connection to load custom resources)");
    ArrayNode names = out.putArray("kinds");
    kinds.all().stream().map(k -> k.plainGroup() ? k.plural() : k.rbacName()).distinct().limit(200)
        .forEach(names::add);
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    clients.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    clients.evict(connectionId);
  }

  @Override
  public void close() {
    clients.close();
  }

  // ── access ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The client of this call: credentials resolved and tainted, a kubeconfig file still pointing at the cluster the
   * person trusted, its credential command still the one they allowed.
   */
  private K8sClients.Client client(ConnectionConfig cfg, K8sSettings s, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ClusterAccess access = ClusterAccess.resolve(s, secrets, cfg.env(), ctx::taint);
    if (s.auth() == Auth.KUBECONFIG_FILE && !access.matchesPin(s)) {
      throw ConnectorException.denied(s.pinnedServer() == null
          ? "Test " + cfg.name() + " once: GateRook pins the cluster its kubeconfig file points to."
          : "Kubeconfig changed: " + cfg.name() + " now points to " + access.server() + ". Test the connection again.");
    }
    if (access.exec() != null) {
      if (!s.allowExecPlugin()) {
        throw ConnectorException.denied(execOff(access));
      }
      if (!ExecCredentials.fingerprint(access.exec()).equals(s.trustedExec())) {
        throw ConnectorException.denied("The credential command of " + cfg.name() + "'s kubeconfig isn't the one "
            + "that was allowed. Test the connection to review it.");
      }
    }
    return clients.client(cfg.id(), cfg.version(), access, s.requestTimeoutMs());
  }

  /**
   * After a call: a token the credential command printed is tainted (it may have been refreshed during the call); a
   * draft's client is closed, a saved connection's is kept for the next call.
   */
  private static void release(ConnectionConfig cfg, K8sClients.Client client, ExecContext ctx) {
    if (client.exec() != null && client.exec().current() != null) {
      ctx.taint(client.exec().current());
    }
    if (!K8sClients.cached(cfg.id())) {
      client.close();
    }
  }

  private static String execOff(ClusterAccess access) {
    return "This kubeconfig signs in with the command " + access.exec().command().getFileName() + ". Turn on \"Run "
        + "the credential command from kubeconfig\" to use it.";
  }

  private static TestResult.Confirmation pinConfirmation(K8sSettings s, ClusterAccess access) {
    String ca = access.caSha256();
    String text = (s.pinnedServer() == null ? "Context " + access.contextName() + " of " + s.kubeconfigPath()
        + " points to " : "Kubeconfig changed: context " + access.contextName() + " now points to ")
        + access.server() + (ca.isEmpty() ? " (the OS trust store)" : " (CA SHA-256 " + ca.substring(0, 16) + "…)")
        + ". Use this cluster for the connection?";
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("file", s.kubeconfigPath().toString()).put("context", access.contextName())
        .put("server", access.server().toString()).put("caSha256", ca);
    if (s.pinnedServer() != null) {
      data.put("previousServer", s.pinnedServer());
    }
    ObjectNode patch = JsonNodeFactory.instance.objectNode();
    patch.putObject("pinnedCluster").put("serverUrl", access.server().toString()).put("caSha256", ca);
    return new TestResult.Confirmation("kubeconfig-cluster", text, data, patch);
  }

  private static TestResult.Confirmation execConfirmation(ConnectionConfig cfg, ClusterAccess access, String fp) {
    ExecCredentials.Spec spec = access.exec();
    String text = "Run this command on this computer, with your user rights, whenever " + cfg.name() + " needs a "
        + "new token: " + spec.commandLine();
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("command", spec.command().toString());
    ArrayNode args = data.putArray("args");
    spec.args().forEach(args::add);
    ArrayNode env = data.putArray("env");
    spec.env().keySet().stream().sorted().forEach(env::add);
    data.put("fingerprint", fp);
    ObjectNode patch = JsonNodeFactory.instance.objectNode().put("trustedExec", fp);
    return new TestResult.Confirmation("exec-plugin", text, data, patch);
  }

  /** Where the form says the connection points, for texts written without I/O. */
  private static String configuredTarget(K8sSettings s) {
    return switch (s.auth()) {
      case TOKEN -> K8sSettings.hostPort(s.server());
      case KUBECONFIG -> "kubeconfig" + (s.context() == null ? "" : " context " + s.context());
      case KUBECONFIG_FILE -> s.pinnedServer() != null ? hostOf(s.pinnedServer())
          : "kubeconfig " + s.kubeconfigPath();
    };
  }

  private static String hostOf(String url) {
    try {
      return K8sSettings.hostPort(java.net.URI.create(url));
    } catch (IllegalArgumentException e) {
      return url;
    }
  }
}
