package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.PolicyView;
import java.util.List;

/**
 * The {@code k8s} section of the environment's policy, read for one connection (kit Policies › Kubernetes).
 *
 * <p>Lists follow the core's "only stricter wins" merge: an empty {@code verbs} list means "no restriction" and two
 * disjoint lists leave a value no name matches ({@link PolicyView#NOTHING}). By default agents get what a deep look at
 * the cluster needs:
 * <ul>
 *   <li>{@code namespaces} empty: every namespace of the cluster ({@code x-empty: all}); a list keeps them to its
 *       namespaces;</li>
 *   <li>{@code resources} empty: every resource, cluster-scoped ones (nodes, namespaces, …) included, but Kubernetes
 *       Secrets, which hold credentials and need to be named ({@code x-empty: connector-default}: "secrets is not on
 *       the list, so agents can't read Kubernetes Secrets"); a list keeps them to its resources.</li>
 * </ul>
 * A resources list an endpoint or a tool override narrowed from the empty one carries {@link PolicyView#WITHIN_DEFAULT}:
 * then a listed name counts only if the empty list allows it too, so an override never adds a Secret the environment's
 * empty list keeps closed.
 */
final class K8sPolicy {

  static final String GET = "get";
  static final String LIST = "list";
  static final String DESCRIBE = "describe";
  static final String LOGS = "logs";
  static final String RESTART = "rollout-restart";
  static final String APPLY = "apply";

  /**
   * What {@link #namespaces()} holds when every namespace is allowed: a listing across namespaces asks the cluster for
   * the whole collection. Never a namespace's name, which is a DNS label.
   */
  static final String ALL = "*";

  private final PolicyView view;
  private final List<String> verbs;
  private final List<String> resources;
  private final List<String> namespaces;
  /** Whether the resources list was narrowed from an empty one: its names count only inside what that list allows. */
  private final boolean resourcesWithinDefault;
  private final boolean unrestricted;

  K8sPolicy(PolicyView view) {
    this(view, false);
  }

  private K8sPolicy(PolicyView view, boolean unrestricted) {
    this.view = view;
    this.verbs = view.strings("verbs");
    List<String> resources = view.strings("resources");
    List<String> namespaces = view.strings("namespaces");
    this.resourcesWithinDefault = resources.removeIf(PolicyView.WITHIN_DEFAULT::equals);
    // Every name lies inside what an empty namespaces list allows, so the marker narrows nothing there.
    namespaces.removeIf(PolicyView.WITHIN_DEFAULT::equals);
    this.resources = List.copyOf(resources);
    this.namespaces = List.copyOf(namespaces);
    this.unrestricted = unrestricted;
  }

  /**
   * A policy that allows everything, for planning a call {@code classify} already checked against the real one
   * ({@code prepare} and {@code execute} get no policy view; the core runs {@code classify} again before an approved
   * call executes).
   */
  static K8sPolicy unrestricted(Env env) {
    return new K8sPolicy(new PolicyView(env, env.id() + ".k8s", null, null), true);
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

  /** Whether every namespace of the cluster is allowed: the policy names none. */
  boolean anyNamespace() {
    return unrestricted || namespaces.isEmpty();
  }

  /**
   * The namespaces a listing across namespaces asks for: {@link #ALL} when every one is allowed, else the policy's
   * list. Empty when the merged lists had no namespace in common: then none is allowed, never every one.
   */
  List<String> namespaces() {
    if (anyNamespace()) {
      return List.of(ALL);
    }
    return namespaces.stream().filter(n -> !n.startsWith("\u0000")).toList();
  }

  boolean namespace(String ns) {
    return anyNamespace() || namespaces().contains(ns);
  }

  /**
   * Whether the policy names this resource: by bare plural for the built-in groups, else {@code plural.group}. In a
   * list narrowed from an empty one, a name counts only for what the empty list opens (everything but Secrets).
   */
  boolean named(KindInfo k) {
    if (unrestricted) {
      return true;
    }
    boolean listed = resources.contains(k.rbacName()) || (k.plainGroup() && resources.contains(k.plural()));
    return listed && (!resourcesWithinDefault || openWhenEmpty(k));
  }

  /** What an empty {@code resources} list opens: every resource but Secrets. */
  private static boolean openWhenEmpty(KindInfo k) {
    return !secret(k);
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
      return "any resource but secrets";
    }
    List<String> shown = resources.stream().filter(r -> !r.startsWith("\u0000")).toList();
    return String.join(", ", shown) + (resourcesWithinDefault ? " (not secrets)" : "");
  }

  String namespacesText() {
    if (anyNamespace()) {
      return "any";
    }
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
   * Privileged pods, host namespaces and hostPath are refused when the policy says so; otherwise (the default) they are
   * a warning to the approver.
   */
  boolean denyPrivileged() {
    return !unrestricted && view.flag("denyPrivileged", false);
  }
}
