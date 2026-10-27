package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.kubernetes.ManifestParser.ManifestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A tool call read into what it will do, without I/O: which kinds, names and namespaces it touches, under which
 * verb of the policy (report 08 §3.5). {@code classify} turns a refusal into DENY; {@code prepare} and
 * {@code execute} plan the same call again, so what is checked is what runs.
 */
final class K8sRequests {

  /**
   * Kinds {@code k8s_apply} never changes, whatever the policy (report 08 §3.5): they hand out credentials or access
   * (Secret, ServiceAccount, RBAC, certificate requests), or reshape the cluster (namespaces, nodes, CRDs, webhooks
   * and admission policies, API services, priority classes). Whole groups by name, single kinds as group/Kind.
   */
  private static final Set<String> FORBIDDEN_APPLY = Set.of("/Secret", "/ServiceAccount", "/Namespace", "/Node",
      "scheduling.k8s.io/PriorityClass", "certificates.k8s.io/CertificateSigningRequest");
  private static final Set<String> FORBIDDEN_APPLY_GROUPS = Set.of("rbac.authorization.k8s.io",
      "admissionregistration.k8s.io", "apiextensions.k8s.io", "apiregistration.k8s.io");
  /** Workloads {@code k8s_rollout_restart} and {@code deployment/<name>} of {@code k8s_logs} name. */
  static final Set<String> RESTARTABLE = Set.of("Deployment", "StatefulSet", "DaemonSet");
  private static final Set<String> LOG_OWNERS = Set.of("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job",
      "ReplicationController");

  private K8sRequests() {}

  /**
   * Why a call doesn't run. {@code ruleId} set: a DENY of the policy or of a fixed rule; {@code ruleId} null: the
   * arguments are wrong (the agent can fix them), told as an error, not as a policy.
   */
  record Problem(String ruleId, String reason, String hint) {
    boolean deny() {
      return ruleId != null;
    }
  }

  /** A refusal while planning. */
  static final class Refusal extends Exception {
    private static final long serialVersionUID = 1L;
    final transient Problem problem;

    Refusal(Problem problem) {
      super(problem.reason());
      this.problem = problem;
    }
  }

  sealed interface Plan permits Get, Logs, Restart, Apply {}

  /**
   * @param namespaces where to look: one namespace, every allowed one for {@code allNamespaces}, none for a
   *     cluster-scoped kind
   * @param events whether the events of the object are wanted and allowed ({@code describe})
   */
  record Get(KindInfo kind, String name, List<String> namespaces, boolean allNamespaces, String labelSelector,
      String fieldSelector, int limit, String cursor, boolean wide, boolean events) implements Plan {}

  /** {@code owner} is set for {@code deployment/api}: one of its pods is picked, like kubectl does. */
  record Logs(String namespace, String pod, KindInfo owner, String ownerName, String container, int tailLines,
      Integer sinceSeconds, boolean previous, boolean timestamps, int maxBytes) implements Plan {}

  record Restart(KindInfo kind, String name, String namespace) implements Plan {}

  record Apply(List<Item> items, boolean force) implements Plan {
    /** One object of the manifest, its namespace set explicitly (an object without one would land in default). */
    record Item(KindInfo kind, ObjectNode object, String namespace, String name) {
      String key() {
        return kind.apiVersion() + "/" + kind.kind() + "/" + (namespace == null ? "" : namespace) + "/" + name;
      }

      String display() {
        return kind.display(name);
      }
    }
  }

  static Plan plan(ToolCall call, K8sSettings s, Kinds kinds, K8sPolicy policy) throws Refusal {
    return switch (call.tool()) {
      case KubernetesTools.GET -> get(call, s, kinds, policy);
      case KubernetesTools.LOGS -> logs(call, s, kinds, policy);
      case KubernetesTools.RESTART -> restart(call, s, kinds, policy);
      case KubernetesTools.APPLY -> apply(call, s, kinds, policy);
      default -> throw new Refusal(new Problem(policy.rule("unknown-tool"), "Kubernetes has no tool " + call.tool()
          + ".", null));
    };
  }

  // ── k8s_get ─────────────────────────────────────────────────────────────────────────────────────────────────

  private static Get get(ToolCall call, K8sSettings s, Kinds kinds, K8sPolicy policy) throws Refusal {
    KindInfo kind = kind(call.string("kind"), kinds, policy);
    String name = call.string("name");
    if (name != null && !K8sNames.object(name)) {
      throw bad("name must be the name of one object, like api.");
    }
    boolean all = bool(call, "allNamespaces", false);
    String ns = call.string("namespace");
    if (all && name != null) {
      throw bad("allNamespaces lists objects; leave it out to get one object by name.");
    }
    if (all && ns != null) {
      throw bad("Give namespace or allNamespaces, not both.");
    }
    String label = call.string("labelSelector");
    String field = call.string("fieldSelector");
    if (!K8sNames.selector(label) || !K8sNames.selector(field)) {
      throw bad("Selectors are one line of at most 1,024 characters.");
    }
    String verb = name == null ? K8sPolicy.LIST : K8sPolicy.GET;
    verb(verb, policy);
    List<String> namespaces = namespaces(kind, ns, all, s, policy);
    String cursor = call.string("cursor");
    if (cursor != null && name != null) {
      throw bad("cursor continues a list; leave it out to get one object.");
    }
    // Events are the describe verb: refused rather than dropped quietly, so that execute (which has no policy) and
    // classify agree on what the call reads.
    boolean events = name != null && bool(call, "events", true);
    if (events && !policy.verb(K8sPolicy.DESCRIBE)) {
      throw new Refusal(new Problem(policy.rule("verb-not-allowed"), "describe (an object's events) isn't allowed "
          + "on " + policy.env().id() + ".", "Call again with events=false."));
    }
    if (events && !policy.resource(Kinds.builtIn().resolve("events").orElseThrow())) {
      throw new Refusal(new Problem(policy.rule("resource-not-allowed"), "events is not on the list of Allowed "
          + "resources of the " + policy.env().id() + " policy.", "Call again with events=false."));
    }
    int limit = call.integer("limit", 50);
    if (limit > policy.listMax()) {
      throw new Refusal(new Problem(policy.rule("list-max"), "limit " + limit + " is above the "
          + policy.env().id() + " policy's " + policy.listMax() + " objects per list.", "Ask for at most "
          + policy.listMax() + " and page with cursor."));
    }
    return new Get(kind, name, namespaces, all, label, field, limit, cursor, bool(call, "wide", false), events);
  }

  // ── k8s_logs ────────────────────────────────────────────────────────────────────────────────────────────────

  private static Logs logs(ToolCall call, K8sSettings s, Kinds kinds, K8sPolicy policy) throws Refusal {
    String podArg = call.string("pod");
    if (podArg == null || podArg.isBlank()) {
      throw bad("pod is the pod's name, or deployment/<name> to pick one of its pods.");
    }
    KindInfo pods = kinds.resolve("pods").orElseThrow();
    KindInfo owner = null;
    String ownerName = null;
    String pod = podArg;
    int slash = podArg.indexOf('/');
    if (slash >= 0) {
      String kindText = podArg.substring(0, slash);
      ownerName = podArg.substring(slash + 1);
      KindInfo k = kinds.resolve(kindText).orElseThrow(() -> new Refusal(new Problem(null, kindText
          + " isn't a kind whose pods have logs. Use a pod name, or deployment/, statefulset/, daemonset/, job/ "
          + "and a name.", null)));
      if (!k.group().isEmpty() || !k.plural().equals("pods")) {
        if (!LOG_OWNERS.contains(k.kind())) {
          throw bad(kindText + " has no pods of its own. Use deployment/, statefulset/, daemonset/, replicaset/ or "
              + "job/ and a name.");
        }
        owner = k;
        pod = null;
      } else {
        pod = ownerName;
        ownerName = null;
      }
      if (!K8sNames.object(owner == null ? pod : ownerName)) {
        throw bad("The name after the / must be the name of one object.");
      }
    } else if (!K8sNames.object(pod)) {
      throw bad("pod must be the name of one pod, like api-7d9c-xk2.");
    }
    verb(K8sPolicy.LOGS, policy);
    allowed(pods, policy);
    if (owner != null) {
      allowed(owner, policy);
    }
    String ns = namespaces(pods, call.string("namespace"), false, s, policy).get(0);
    String container = call.string("container");
    if (container != null && !K8sNames.container(container)) {
      throw bad("container is the name of one container of the pod.");
    }
    int tail = call.integer("tailLines", 200);
    if (tail < 1) {
      throw bad("tailLines is at least 1.");
    }
    if (tail > policy.logsTailMax()) {
      throw new Refusal(new Problem(policy.rule("logs-tail-max"), "tailLines " + tail + " is above the "
          + policy.env().id() + " policy's " + policy.logsTailMax() + " lines.", "Ask for at most "
          + policy.logsTailMax() + " lines, or narrow with sinceSeconds."));
    }
    JsonNode since = call.arguments().get("sinceSeconds");
    return new Logs(ns, pod, owner, ownerName, container, tail, since == null || since.isNull() ? null
        : since.asInt(), bool(call, "previous", false), bool(call, "timestamps", false),
        call.integer("maxBytes", 262_144));
  }

  // ── k8s_rollout_restart ─────────────────────────────────────────────────────────────────────────────────────

  private static Restart restart(ToolCall call, K8sSettings s, Kinds kinds, K8sPolicy policy) throws Refusal {
    String kindText = call.string("kind");
    KindInfo kind = kind(kindText, kinds, policy);
    if (!kind.group().equals("apps") || !RESTARTABLE.contains(kind.kind())) {
      throw bad("k8s_rollout_restart restarts a deployment, statefulset or daemonset.");
    }
    String name = call.string("name");
    if (!K8sNames.object(name)) {
      throw bad("name must be the name of the " + kind.singular() + ".");
    }
    verb(K8sPolicy.RESTART, policy);
    String ns = namespaces(kind, call.string("namespace"), false, s, policy).get(0);
    return new Restart(kind, name, ns);
  }

  // ── k8s_apply ───────────────────────────────────────────────────────────────────────────────────────────────

  private static Apply apply(ToolCall call, K8sSettings s, Kinds kinds, K8sPolicy policy) throws Refusal {
    List<ObjectNode> objects;
    try {
      objects = ManifestParser.parse(call.string("manifest"));
    } catch (ManifestException e) {
      throw bad(e.getMessage());
    }
    verb(K8sPolicy.APPLY, policy);
    if (objects.size() > policy.applyMaxObjects()) {
      throw new Refusal(new Problem(policy.rule("too-many-objects"), "The manifest has " + objects.size()
          + " objects; the " + policy.env().id() + " policy applies at most " + policy.applyMaxObjects()
          + " per call.", "Split the manifest."));
    }
    String nsArg = call.string("namespace");
    List<Apply.Item> items = new ArrayList<>();
    for (ObjectNode o : objects) {
      String apiVersion = o.path("apiVersion").asString();
      String kindName = o.path("kind").asString();
      String name = o.path("metadata").path("name").asString();
      KindInfo kind = kinds.byApiVersion(apiVersion, kindName).orElseThrow(() -> new Refusal(new Problem(
          policy.rule("kind-unknown"), "The cluster doesn't serve " + apiVersion + " " + kindName + ".",
          kinds.discovered() ? "Check apiVersion and kind."
              : "Test the connection so GateRook knows the cluster's custom resources.")));
      if (forbiddenForApply(kind)) {
        throw new Refusal(new Problem(policy.rule("kind-forbidden"), "k8s_apply can't change " + kind.kind()
            + ": it hands out access or reshapes the cluster.", "Use your GitOps pipeline."));
      }
      allowed(kind, policy);
      String ns = null;
      if (kind.namespaced()) {
        String own = o.path("metadata").path("namespace").asString(null);
        if (own != null && nsArg != null && !own.equals(nsArg)) {
          throw bad(kind.display(name) + " says namespace " + own + " but the call says " + nsArg
              + ". Leave namespace out or make them the same.");
        }
        ns = own != null ? own : nsArg != null ? nsArg : s.namespace();
        namespace(ns, policy);
        ((ObjectNode) o.get("metadata")).put("namespace", ns);
      } else if (o.path("metadata").has("namespace")) {
        throw bad(kind.display(name) + " is cluster-scoped: remove metadata.namespace.");
      }
      List<String> escapes = PodSpecs.escapes(o);
      if (!escapes.isEmpty() && policy.denyPrivileged()) {
        throw new Refusal(new Problem(policy.rule("privileged-deny"), kind.display(name) + " reaches the node ("
            + String.join(", ", escapes) + "), which the " + policy.env().id() + " policy doesn't allow.", null));
      }
      items.add(new Apply.Item(kind, o, ns, name));
    }
    return new Apply(List.copyOf(items), bool(call, "force", false));
  }

  static boolean forbiddenForApply(KindInfo kind) {
    return FORBIDDEN_APPLY.contains(kind.group() + "/" + kind.kind()) || FORBIDDEN_APPLY_GROUPS.contains(kind.group());
  }

  // ── shared checks ───────────────────────────────────────────────────────────────────────────────────────────

  private static KindInfo kind(String text, Kinds kinds, K8sPolicy policy) throws Refusal {
    if (text == null || text.isBlank()) {
      throw bad("kind is required, like deployments or pods.");
    }
    if (text.contains("/")) {
      throw new Refusal(new Problem(policy.rule("kind-unknown"), "Subresources (" + text + ") aren't reachable: "
          + (text.toLowerCase(Locale.ROOT).endsWith("/log") ? "use k8s_logs for logs." : "use the resource itself."),
          null));
    }
    KindInfo kind = kinds.resolve(text).orElseThrow(() -> new Refusal(new Problem(policy.rule("kind-unknown"),
        "No kind " + text + ". Known: " + kinds.known(24) + ".", kinds.discovered() ? null
            : "Test the connection so GateRook knows the cluster's custom resources.")));
    allowed(kind, policy);
    return kind;
  }

  private static void allowed(KindInfo kind, K8sPolicy policy) throws Refusal {
    if (!kind.namespaced() && !policy.named(kind)) {
      throw new Refusal(new Problem(policy.rule("cluster-scoped"), kind.plural() + " is cluster-scoped: agents "
          + "reach it only when Allowed resources names it.", null));
    }
    if (!policy.resource(kind)) {
      String name = kind.plainGroup() ? kind.plural() : kind.rbacName();
      String reason = K8sPolicy.secret(kind) ? "secrets is not on the list, so agents can't read Kubernetes Secrets."
          : name + " is not on the list of Allowed resources of the " + policy.env().id() + " policy: "
              + policy.resourcesText() + ".";
      throw new Refusal(new Problem(policy.rule("resource-not-allowed"), reason, null));
    }
  }

  private static void verb(String verb, K8sPolicy policy) throws Refusal {
    if (!policy.verb(verb)) {
      throw new Refusal(new Problem(policy.rule("verb-not-allowed"), verb + " isn't allowed on "
          + policy.env().id() + ".", null));
    }
  }

  private static List<String> namespaces(KindInfo kind, String ns, boolean all, K8sSettings s, K8sPolicy policy)
      throws Refusal {
    if (!kind.namespaced()) {
      if (ns != null || all) {
        throw bad(kind.plural() + " is cluster-scoped: leave namespace and allNamespaces out.");
      }
      return List.of();
    }
    if (all) {
      return policy.namespaces();
    }
    String n = ns != null ? ns : s.namespace();
    namespace(n, policy);
    return List.of(n);
  }

  private static void namespace(String ns, K8sPolicy policy) throws Refusal {
    if (!K8sNames.namespace(ns)) {
      throw bad("namespace must be a DNS label, like app-dev.");
    }
    if (!policy.namespace(ns)) {
      throw new Refusal(new Problem(policy.rule("namespace-not-allowed"), ns + " isn't allowed on "
          + policy.env().id() + ". Namespaces: " + policy.namespacesText() + ".", null));
    }
  }

  private static boolean bool(ToolCall call, String field, boolean dflt) {
    JsonNode n = call.arguments().get(field);
    return n == null || n.isNull() ? dflt : n.asBoolean(dflt);
  }

  private static Refusal bad(String message) {
    return new Refusal(new Problem(null, message, null));
  }
}
