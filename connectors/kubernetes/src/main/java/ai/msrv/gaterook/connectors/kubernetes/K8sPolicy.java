package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import java.util.List;

/**
 * The {@code k8s} section of the environment's policy, read for one connection (kit Policies › Kubernetes).
 *
 * <p>Lists follow the core's "only stricter wins" merge, where an empty list means "no restriction" and two disjoint
 * lists leave a value no name matches. On top of that the connector is closed by default where an open reading would
 * hand the agent more than the person chose:
 * <ul>
 *   <li>{@code namespaces} empty: only the connection's own namespace, not every namespace of the cluster;</li>
 *   <li>{@code resources}: Secrets and cluster-scoped resources (nodes, namespaces, …) only when named — an empty
 *       list opens the namespaced rest, never them ("secrets is not on the list, so agents can't read Kubernetes
 *       Secrets").</li>
 * </ul>
 */
final class K8sPolicy {

  static final String GET = "get";
  static final String LIST = "list";
  static final String DESCRIBE = "describe";
  static final String LOGS = "logs";
  static final String RESTART = "rollout-restart";
  static final String APPLY = "apply";

  private final PolicyView view;
  private final String connectionNamespace;
  private final List<String> verbs;
  private final List<String> resources;
  private final List<String> namespaces;
  private final boolean unrestricted;

  K8sPolicy(PolicyView view, String connectionNamespace) {
    this(view, connectionNamespace, false);
  }

  private K8sPolicy(PolicyView view, String connectionNamespace, boolean unrestricted) {
    this.view = view;
    this.connectionNamespace = connectionNamespace;
    this.verbs = view.strings("verbs");
    this.resources = view.strings("resources");
    this.namespaces = view.strings("namespaces");
    this.unrestricted = unrestricted;
  }

  /**
   * A policy that allows everything, for planning a call {@code classify} already checked against the real one
   * ({@code prepare} and {@code execute} get no policy view; the core runs {@code classify} again before an approved
   * call executes).
   */
  static K8sPolicy unrestricted(Env env, String connectionNamespace) {
    return new K8sPolicy(new PolicyView(env, env.id() + ".k8s", null, null), connectionNamespace, true);
  }

  Env env() {
    return view.env();
  }

  String rule(String id) {
    return view.rule(id);
  }

  boolean verb(String verb) {
    return unrestricted || verbs.isEmpty() || verbs.contains(verb);
  }

  /** The namespaces this connection may reach: the policy's list, or the connection's own namespace. */
  List<String> namespaces() {
    return namespaces.isEmpty() ? List.of(connectionNamespace) : namespaces.stream()
        .filter(n -> !n.startsWith("\u0000")).toList();
  }

  boolean namespace(String ns) {
    return unrestricted || namespaces().contains(ns);
  }

  /** Whether the policy names this resource: by bare plural for the built-in groups, else {@code plural.group}. */
  boolean named(KindInfo k) {
    return unrestricted || resources.contains(k.rbacName()) || (k.plainGroup() && resources.contains(k.plural()));
  }

  boolean resource(KindInfo k) {
    if (named(k)) {
      return true;
    }
    if (!resources.isEmpty()) {
      return false;
    }
    return k.namespaced() && !secret(k);
  }

  static boolean secret(KindInfo k) {
    return k.group().isEmpty() && k.plural().equals("secrets");
  }

  /** "deployments, pods and events" for the agent: what it may ask for instead. */
  String resourcesText() {
    if (resources.isEmpty()) {
      return "any namespaced resource but secrets";
    }
    return String.join(", ", resources.stream().filter(r -> !r.startsWith("\u0000")).toList());
  }

  String namespacesText() {
    return String.join(", ", namespaces());
  }

  int applyMaxObjects() {
    return unrestricted ? ManifestParser.MAX_OBJECTS
        : Math.min(ManifestParser.MAX_OBJECTS, Math.max(1, view.integer("applyMaxObjects", 20)));
  }

  int logsTailMax() {
    return unrestricted ? Integer.MAX_VALUE : Math.max(1, view.integer("logsTailMax", 5000));
  }

  int listMax() {
    return unrestricted ? Integer.MAX_VALUE : Math.max(1, view.integer("listMax", 500));
  }

  /**
   * Privileged pods, host namespaces and hostPath are refused (prod's default); otherwise they are a warning to the
   * approver.
   */
  boolean denyPrivileged() {
    return !unrestricted && view.flag("denyPrivileged", view.env() == Env.PROD);
  }
}
