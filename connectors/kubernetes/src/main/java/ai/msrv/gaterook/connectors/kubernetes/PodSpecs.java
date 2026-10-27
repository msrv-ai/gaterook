package ai.msrv.gaterook.connectors.kubernetes;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The pod specs inside a manifest object and what in them reaches beyond the pod (report 08 §3.5): privileged
 * containers, the host's network, PID or IPC namespace, hostPath volumes, added capabilities that amount to root on
 * the node — and a persistent volume of the node's own disk, which a pod reaches through a claim. On prod (or with
 * {@code denyPrivileged}) such an apply is refused; elsewhere the approver is warned.
 */
final class PodSpecs {

  private static final List<String> ROOT_CAPABILITIES = List.of("ALL", "SYS_ADMIN", "NET_ADMIN", "SYS_PTRACE",
      "SYS_MODULE", "DAC_READ_SEARCH", "SYS_RAWIO", "BPF");

  private PodSpecs() {}

  /** The pod spec of a Pod, of a workload's template, of a CronJob's job template; empty for other kinds. */
  static List<JsonNode> of(JsonNode object) {
    String kind = object.path("kind").asString("");
    JsonNode spec = object.path("spec");
    List<JsonNode> out = new ArrayList<>();
    switch (kind) {
      case "Pod" -> out.add(spec);
      case "Deployment", "ReplicaSet", "StatefulSet", "DaemonSet", "Job", "ReplicationController", "PodTemplate" ->
          out.add(kind.equals("PodTemplate") ? object.path("template").path("spec")
              : spec.path("template").path("spec"));
      case "CronJob" -> out.add(spec.path("jobTemplate").path("spec").path("template").path("spec"));
      default -> {
        // Other kinds start no pods themselves.
      }
    }
    return out.stream().filter(JsonNode::isObject).toList();
  }

  /** What in the object's pods reaches the node, in words; empty when nothing does. */
  static List<String> escapes(JsonNode object) {
    List<String> out = new ArrayList<>();
    // A volume of the node's own disk that any pod then mounts through a claim, no hostPath in its spec.
    if (object.path("kind").asString("").equals("PersistentVolume")) {
      for (String source : new String[] {"hostPath", "local"}) {
        if (object.path("spec").has(source)) {
          out.add(source + " persistent volume (" + object.path("spec").path(source).path("path").asString("?") + ")");
        }
      }
    }
    for (JsonNode spec : of(object)) {
      for (String host : new String[] {"hostNetwork", "hostPID", "hostIPC"}) {
        if (spec.path(host).asBoolean(false)) {
          out.add(host);
        }
      }
      for (JsonNode v : spec.path("volumes")) {
        if (v.has("hostPath")) {
          out.add("hostPath volume " + v.path("name").asString("?") + " ("
              + v.path("hostPath").path("path").asString("?") + ")");
        }
      }
      for (String list : new String[] {"initContainers", "containers", "ephemeralContainers"}) {
        for (JsonNode c : spec.path(list)) {
          JsonNode sc = c.path("securityContext");
          String name = c.path("name").asString("?");
          if (sc.path("privileged").asBoolean(false)) {
            out.add("privileged container " + name);
          }
          for (JsonNode cap : sc.path("capabilities").path("add")) {
            if (ROOT_CAPABILITIES.contains(cap.asString().toUpperCase(java.util.Locale.ROOT).replace("CAP_", ""))) {
              out.add("capability " + cap.asString() + " in container " + name);
            }
          }
        }
      }
    }
    return out;
  }

  /** Images of the object's containers: what a restart keeps and an apply may change. */
  static List<String> images(JsonNode object) {
    List<String> out = new ArrayList<>();
    for (JsonNode spec : of(object)) {
      for (String list : new String[] {"initContainers", "containers"}) {
        for (JsonNode c : spec.path(list)) {
          String image = c.path("image").asString(null);
          if (image != null) {
            out.add(c.path("name").asString("?") + "=" + image);
          }
        }
      }
    }
    return out;
  }
}
