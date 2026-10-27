package ai.msrv.gaterook.connectors.kubernetes;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One API resource as discovery names it: group, version, kind, plural and whether it lives in a namespace.
 *
 * @param group "" for the core group
 * @param plural the REST and RBAC name ({@code deployments})
 * @param singular {@code deployment}; derived from the kind when discovery doesn't say
 */
record KindInfo(String group, String version, String kind, String plural, String singular, boolean namespaced,
    List<String> shortNames) {

  /** Groups the policy names by bare plural ({@code deployments}); others need {@code <plural>.<group>}. */
  private static final Set<String> PLAIN_GROUPS = Set.of("", "apps", "batch", "autoscaling", "policy", "extensions");

  KindInfo {
    shortNames = shortNames == null ? List.of() : List.copyOf(shortNames);
    if (singular == null || singular.isEmpty()) {
      singular = kind.toLowerCase(Locale.ROOT);
    }
  }

  String apiVersion() {
    return group.isEmpty() ? version : group + "/" + version;
  }

  /** The name RBAC and the policy use: {@code deployments}, {@code certificates.cert-manager.io}. */
  String rbacName() {
    return group.isEmpty() ? plural : plural + "." + group;
  }

  /** Whether a policy may name this resource by its bare plural: the built-in API groups of Kubernetes. */
  boolean plainGroup() {
    return PLAIN_GROUPS.contains(group) || group.endsWith(".k8s.io");
  }

  /** {@code deployment/api}, the way kubectl names one object. */
  String display(String name) {
    return singular + "/" + name;
  }

  /**
   * The REST path of the collection ({@code name == null}) or of one object, relative to the API server's root; the
   * segments the agent gave are percent-encoded.
   */
  String path(String namespace, String name) {
    StringBuilder out = new StringBuilder(group.isEmpty() ? "api/" + version : "apis/" + group + "/" + version);
    if (namespaced && namespace != null) {
      out.append("/namespaces/").append(K8sApi.segment(namespace));
    }
    out.append('/').append(plural);
    if (name != null) {
      out.append('/').append(K8sApi.segment(name));
    }
    return out.toString();
  }
}
