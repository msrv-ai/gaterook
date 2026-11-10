package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import java.util.List;

/**
 * The {@code k8s} section of the environment's policy, read for one connection (kit Policies › Kubernetes).
 *
 * <p>Lists follow the core's "only stricter wins" merge: an empty {@code verbs} list means "no restriction" and two
 * disjoint lists leave a value no name matches ({@link PolicyView#NOTHING}). The connector is closed by default where
 * an open reading would hand the agent more than the person chose, and the schema says so ({@code x-empty:
 * connector-default}):
 * <ul>
 *   <li>{@code namespaces} empty: only the connection's own namespace, not every namespace of the cluster;</li>
 *   <li>{@code resources}: Secrets and cluster-scoped resources (nodes, namespaces, …) only when named — an empty
 *       list opens the namespaced rest, never them ("secrets is not on the list, so agents can't read Kubernetes
 *       Secrets").</li>
 * </ul>
 * A list an endpoint or a tool override narrowed from such an empty list carries {@link PolicyView#WITHIN_DEFAULT}:
 * then a listed name counts only if the empty list allows it too, so an override never adds another namespace, a
 * Secret or a cluster-scoped resource the environment's empty list keeps closed.
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
  /** Whether the lists were narrowed from an empty one: their names count only inside what an empty list allows. */
  private final boolean resourcesWithinDefault;
  private final boolean namespacesWithinDefault;
  private final boolean unrestricted;

  K8sPolicy(PolicyView view, String connectionNamespace) {
    this(view, connectionNamespace, false);
  }

  private K8sPolicy(PolicyView view, String connectionNamespace, boolean unrestricted) {
    this.view = view;
    this.connectionNamespace = connectionNamespace;
    this.verbs = view.strings("verbs");
    List<String> resources = view.strings("resources");
    List<String> namespaces = view.strings("namespaces");
    this.resourcesWithinDefault = resources.removeIf(PolicyView.WITHIN_DEFAULT::equals);
    this.namespacesWithinDefault = namespaces.removeIf(PolicyView.WITHIN_DEFAULT::equals);
    this.resources = List.copyOf(resources);
    this.namespaces = List.copyOf(namespaces);
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

  /**
   * The namespaces this connection may reach: the policy's list, or the connection's own namespace. Empty when the
   * merged lists had no namespace in common: then none is allowed, never every one. A list narrowed from an empty one
   * keeps only the connection's own namespace, if it names it.
   */
  List<String> namespaces() {
    if (namespaces.isEmpty()) {
      return List.of(connectionNamespace);
    }
    return namespaces.stream().filter(n -> !n.startsWith("\u0000"))
        .filter(n -> !namespacesWithinDefault || n.equals(connectionNamespace)).toList();
  }

  boolean namespace(String ns) {
    return unrestricted || namespaces().contains(ns);
  }

  /**
   * Whether the policy names this resource: by bare plural for the built-in groups, else {@code plural.group}. In a
   * list narrowed from an empty one, a name counts only for what the empty list opens (namespaced, not Secrets).
   */
  boolean named(KindInfo k) {
    if (unrestricted) {
      return true;
    }
    boolean listed = resources.contains(k.rbacName()) || (k.plainGroup() && resources.contains(k.plural()));
    return listed && (!resourcesWithinDefault || openWhenEmpty(k));
  }

  /** What an empty {@code resources} list opens: namespaced resources but Secrets. */
  private static boolean openWhenEmpty(KindInfo k) {
    return k.namespaced() && !secret(k);
  }

  boolean resource(KindInfo k) {
    if (named(k)) {
      return true;
    }
    if (!resources.isEmpty()) {
      return false;
    }
    return openWhenEmpty(k);
  }

  static boolean secret(KindInfo k) {
    return k.group().isEmpty() && k.plural().equals("secrets");
  }

  /** "deployments, pods and events" for the agent: what it may ask for instead. */
  String resourcesText() {
    if (resources.isEmpty()) {
      return "any namespaced resource but secrets";
    }
    List<String> shown = resources.stream().filter(r -> !r.startsWith("\u0000")).toList();
    return String.join(", ", shown) + (resourcesWithinDefault ? " (namespaced, not secrets)" : "");
  }

  String namespacesText() {
    List<String> allowed = namespaces();
    return allowed.isEmpty() ? "none" : String.join(", ", allowed);
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
