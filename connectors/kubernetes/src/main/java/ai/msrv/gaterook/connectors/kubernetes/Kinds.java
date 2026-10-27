package ai.msrv.gaterook.connectors.kubernetes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The kinds one connection knows, and how {@code kind} arguments resolve to them the way kubectl does: plural,
 * singular, Kind and short names without regard to case, optionally qualified as {@code <name>.<group>} or
 * {@code <name>.<version>.<group>}. The core group wins ambiguities, then the order of discovery.
 *
 * <p>The source is the discovery snapshot Test connection stores in the connection's metadata ({@code classify} has no
 * I/O); before the first test, a built-in table of the standard API groups stands in, so a new connection works for
 * everything but custom resources.
 */
final class Kinds {

  /** The standard resources of Kubernetes 1.37 that the tools can address (no subresources). */
  static final List<KindInfo> BUILT_IN = List.of(
      core("Pod", "pods", true, "po"),
      core("Service", "services", true, "svc"),
      core("ConfigMap", "configmaps", true, "cm"),
      core("Secret", "secrets", true),
      core("Event", "events", true, "ev"),
      core("Endpoints", "endpoints", true, "ep"),
      core("PersistentVolumeClaim", "persistentvolumeclaims", true, "pvc"),
      core("ServiceAccount", "serviceaccounts", true, "sa"),
      core("ResourceQuota", "resourcequotas", true, "quota"),
      core("LimitRange", "limitranges", true, "limits"),
      core("ReplicationController", "replicationcontrollers", true, "rc"),
      core("PodTemplate", "podtemplates", true),
      core("Namespace", "namespaces", false, "ns"),
      core("Node", "nodes", false, "no"),
      core("PersistentVolume", "persistentvolumes", false, "pv"),
      of("apps", "v1", "Deployment", "deployments", true, "deploy"),
      of("apps", "v1", "StatefulSet", "statefulsets", true, "sts"),
      of("apps", "v1", "DaemonSet", "daemonsets", true, "ds"),
      of("apps", "v1", "ReplicaSet", "replicasets", true, "rs"),
      of("apps", "v1", "ControllerRevision", "controllerrevisions", true),
      of("batch", "v1", "Job", "jobs", true),
      of("batch", "v1", "CronJob", "cronjobs", true, "cj"),
      of("autoscaling", "v2", "HorizontalPodAutoscaler", "horizontalpodautoscalers", true, "hpa"),
      of("policy", "v1", "PodDisruptionBudget", "poddisruptionbudgets", true, "pdb"),
      of("networking.k8s.io", "v1", "Ingress", "ingresses", true, "ing"),
      of("networking.k8s.io", "v1", "NetworkPolicy", "networkpolicies", true, "netpol"),
      of("networking.k8s.io", "v1", "IngressClass", "ingressclasses", false),
      of("discovery.k8s.io", "v1", "EndpointSlice", "endpointslices", true),
      of("events.k8s.io", "v1", "Event", "events", true, "ev"),
      of("coordination.k8s.io", "v1", "Lease", "leases", true),
      of("rbac.authorization.k8s.io", "v1", "Role", "roles", true),
      of("rbac.authorization.k8s.io", "v1", "RoleBinding", "rolebindings", true),
      of("rbac.authorization.k8s.io", "v1", "ClusterRole", "clusterroles", false),
      of("rbac.authorization.k8s.io", "v1", "ClusterRoleBinding", "clusterrolebindings", false),
      of("storage.k8s.io", "v1", "StorageClass", "storageclasses", false, "sc"),
      of("scheduling.k8s.io", "v1", "PriorityClass", "priorityclasses", false, "pc"),
      of("apiextensions.k8s.io", "v1", "CustomResourceDefinition", "customresourcedefinitions", false, "crd", "crds"),
      of("admissionregistration.k8s.io", "v1", "ValidatingWebhookConfiguration",
          "validatingwebhookconfigurations", false),
      of("admissionregistration.k8s.io", "v1", "MutatingWebhookConfiguration", "mutatingwebhookconfigurations",
          false),
      of("admissionregistration.k8s.io", "v1", "ValidatingAdmissionPolicy", "validatingadmissionpolicies", false),
      of("admissionregistration.k8s.io", "v1", "ValidatingAdmissionPolicyBinding",
          "validatingadmissionpolicybindings", false),
      of("apiregistration.k8s.io", "v1", "APIService", "apiservices", false),
      of("certificates.k8s.io", "v1", "CertificateSigningRequest", "certificatesigningrequests", false, "csr"));

  private final List<KindInfo> kinds;
  private final boolean discovered;

  private Kinds(List<KindInfo> kinds, boolean discovered) {
    this.kinds = List.copyOf(kinds);
    this.discovered = discovered;
  }

  /** The kinds of a connection: its discovery snapshot, or the built-in table when it has none yet. */
  static Kinds of(JsonNode metadata) {
    JsonNode snapshot = metadata == null ? null : metadata.path("discovery").path("resources");
    if (snapshot == null || !snapshot.isArray() || snapshot.isEmpty()) {
      return new Kinds(BUILT_IN, false);
    }
    List<KindInfo> out = new ArrayList<>();
    for (JsonNode r : snapshot) {
      List<String> shortNames = new ArrayList<>();
      r.path("sn").forEach(s -> shortNames.add(s.asString()));
      out.add(new KindInfo(r.path("g").asString(""), r.path("v").asString(), r.path("k").asString(),
          r.path("p").asString(), r.path("s").asString(""), r.path("n").asBoolean(true), shortNames));
    }
    return new Kinds(out, true);
  }

  static Kinds builtIn() {
    return new Kinds(BUILT_IN, false);
  }

  /** Whether the kinds come from the cluster (custom resources included) or from the built-in table. */
  boolean discovered() {
    return discovered;
  }

  List<KindInfo> all() {
    return kinds;
  }

  /** The kind a {@code kind} argument names, or empty: {@code pods}, {@code po}, {@code Deployment}, {@code cert}. */
  Optional<KindInfo> resolve(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String t = text.strip().toLowerCase(Locale.ROOT);
    Optional<KindInfo> whole = find(t, null, null);
    if (whole.isPresent()) {
      return whole;
    }
    int dot = t.indexOf('.');
    if (dot <= 0) {
      return Optional.empty();
    }
    String name = t.substring(0, dot);
    String rest = t.substring(dot + 1);
    // <name>.<version>.<group> (deployments.v1.apps) before <name>.<group> (deployments.apps).
    int second = rest.indexOf('.');
    if (second > 0) {
      Optional<KindInfo> versioned = find(name, rest.substring(0, second), rest.substring(second + 1));
      if (versioned.isPresent()) {
        return versioned;
      }
    }
    return find(name, null, rest);
  }

  /** The kind of a manifest object, by its {@code apiVersion} and {@code kind}. */
  Optional<KindInfo> byApiVersion(String apiVersion, String kind) {
    if (apiVersion == null || kind == null) {
      return Optional.empty();
    }
    int slash = apiVersion.indexOf('/');
    String group = slash < 0 ? "" : apiVersion.substring(0, slash);
    String version = slash < 0 ? apiVersion : apiVersion.substring(slash + 1);
    KindInfo sameGroup = null;
    for (KindInfo k : kinds) {
      if (k.group().equals(group) && k.kind().equals(kind)) {
        if (k.version().equals(version)) {
          return Optional.of(k);
        }
        if (sameGroup == null) {
          sameGroup = k;
        }
      }
    }
    // A snapshot lists every version the cluster serves, so an unlisted one isn't served; the built-in table knows
    // only the preferred version, and the API server judges the one the manifest names.
    if (sameGroup == null || discovered) {
      return Optional.empty();
    }
    KindInfo k = sameGroup;
    return Optional.of(new KindInfo(k.group(), version, k.kind(), k.plural(), k.singular(), k.namespaced(),
        k.shortNames()));
  }

  /** Names a person reads in "Known kinds: …" (built-in plurals first, then the cluster's own). */
  String known(int max) {
    List<String> names = new ArrayList<>();
    for (KindInfo k : kinds) {
      String n = k.plainGroup() ? k.plural() : k.rbacName();
      if (!names.contains(n)) {
        names.add(n);
      }
      if (names.size() >= max) {
        break;
      }
    }
    return String.join(", ", names) + (kinds.size() > names.size() ? ", …" : "");
  }

  private Optional<KindInfo> find(String name, String version, String group) {
    KindInfo found = null;
    for (KindInfo k : kinds) {
      if (group != null && !k.group().equals(group)) {
        continue;
      }
      if (version != null && !k.version().equals(version)) {
        continue;
      }
      if (matches(k, name)) {
        if (k.group().isEmpty()) {
          return Optional.of(k);
        }
        if (found == null) {
          found = k;
        }
      }
    }
    return Optional.ofNullable(found);
  }

  private static boolean matches(KindInfo k, String name) {
    if (k.plural().equals(name) || k.singular().equals(name) || k.kind().toLowerCase(Locale.ROOT).equals(name)) {
      return true;
    }
    for (String s : k.shortNames()) {
      if (s.equalsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
  }

  // ── Discovery ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The snapshot stored in the connection's metadata: compact, in discovery order (core first), no timestamps, so
   * the same cluster gives the same bytes and a test doesn't make a new connection version.
   */
  static ObjectNode snapshot(List<KindInfo> kinds) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("v", 1);
    ArrayNode resources = out.putArray("resources");
    for (KindInfo k : kinds) {
      ObjectNode r = resources.addObject();
      r.put("g", k.group()).put("v", k.version()).put("k", k.kind()).put("p", k.plural()).put("s", k.singular())
          .put("n", k.namespaced());
      if (!k.shortNames().isEmpty()) {
        ArrayNode sn = r.putArray("sn");
        k.shortNames().forEach(sn::add);
      }
    }
    return out;
  }

  /**
   * Resources of an aggregated discovery document ({@code apidiscovery.k8s.io/v2 APIGroupDiscoveryList}, from
   * {@code /api} and {@code /apis}): every version of every group, the preferred first, without subresources and
   * without what can't be read ({@code get} missing from the verbs).
   */
  static List<KindInfo> fromAggregated(JsonNode list) {
    Map<String, KindInfo> out = new LinkedHashMap<>();
    for (JsonNode group : list.path("items")) {
      String name = group.path("metadata").path("name").asString("");
      for (JsonNode version : group.path("versions")) {
        String v = version.path("version").asString();
        for (JsonNode r : version.path("resources")) {
          // Subresources come nested under their resource in v2; a name with a slash is never addressable here.
          if (!readable(r.path("verbs")) || r.path("resource").asString("").contains("/")) {
            continue;
          }
          JsonNode kind = r.path("responseKind");
          List<String> shortNames = new ArrayList<>();
          r.path("shortNames").forEach(s -> shortNames.add(s.asString()));
          KindInfo k = new KindInfo(name, v, kind.path("kind").asString(), r.path("resource").asString(),
              r.path("singularResource").asString(""), "Namespaced".equals(r.path("scope").asString()), shortNames);
          out.putIfAbsent(name + "/" + v + "/" + k.plural(), k);
        }
      }
    }
    return List.copyOf(out.values());
  }

  /** Resources of one legacy {@code APIResourceList} ({@code /api/v1}, {@code /apis/apps/v1}). */
  static List<KindInfo> fromResourceList(JsonNode list) {
    String groupVersion = list.path("groupVersion").asString("");
    int slash = groupVersion.indexOf('/');
    String group = slash < 0 ? "" : groupVersion.substring(0, slash);
    String version = slash < 0 ? groupVersion : groupVersion.substring(slash + 1);
    List<KindInfo> out = new ArrayList<>();
    for (JsonNode r : list.path("resources")) {
      String name = r.path("name").asString("");
      if (name.contains("/") || !readable(r.path("verbs"))) {
        continue;
      }
      List<String> shortNames = new ArrayList<>();
      r.path("shortNames").forEach(s -> shortNames.add(s.asString()));
      out.add(new KindInfo(group, version, r.path("kind").asString(), name, r.path("singularName").asString(""),
          r.path("namespaced").asBoolean(false), shortNames));
    }
    return out;
  }

  private static boolean readable(JsonNode verbs) {
    if (!verbs.isArray() || verbs.isEmpty()) {
      return false;
    }
    for (JsonNode v : verbs) {
      if ("get".equals(v.asString())) {
        return true;
      }
    }
    return false;
  }

  private static KindInfo core(String kind, String plural, boolean namespaced, String... shortNames) {
    return new KindInfo("", "v1", kind, plural, null, namespaced, List.of(shortNames));
  }

  private static KindInfo of(String group, String version, String kind, String plural, boolean namespaced,
      String... shortNames) {
    return new KindInfo(group, version, kind, plural, null, namespaced, List.of(shortNames));
  }
}
