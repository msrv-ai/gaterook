package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rollouts of deployments, statefulsets and daemonsets: their status the way {@code kubectl rollout status} judges it
 * (report 08 §3.1, "polling the rollout of deployment/order-sync") and what a restart does to them (§3.6) — how many
 * pods, how many at a time, whether there is downtime.
 */
final class Rollouts {

  private Rollouts() {}

  /** The rollout of a workload, or {@code null} for other kinds. */
  static ObjectNode status(JsonNode o) {
    String kind = o.path("kind").asString("");
    if (!o.path("apiVersion").asString("").startsWith("apps/")
        || !K8sRequests.RESTARTABLE.contains(kind)) {
      return null;
    }
    JsonNode spec = o.path("spec");
    JsonNode st = o.path("status");
    long generation = o.path("metadata").path("generation").asLong(0);
    long observed = st.path("observedGeneration").asLong(0);
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("generation", generation);
    out.put("observedGeneration", observed);
    String message;
    boolean complete = false;
    switch (kind) {
      case "Deployment" -> {
        int replicas = spec.path("replicas").asInt(1);
        int updated = st.path("updatedReplicas").asInt(0);
        int current = st.path("replicas").asInt(0);
        int available = st.path("availableReplicas").asInt(0);
        out.put("replicas", replicas).put("updated", updated).put("ready", st.path("readyReplicas").asInt(0))
            .put("available", available);
        if (generation > observed) {
          message = "waiting for the spec update to be observed";
        } else if (deadlineExceeded(st)) {
          message = "exceeded its progress deadline";
        } else if (updated < replicas) {
          message = updated + " of " + replicas + " new replicas updated";
        } else if (current > updated) {
          message = (current - updated) + " old replicas pending termination";
        } else if (available < updated) {
          message = available + " of " + updated + " updated replicas available";
        } else {
          message = available + "/" + replicas + " complete";
          complete = true;
        }
      }
      case "StatefulSet" -> {
        int replicas = spec.path("replicas").asInt(1);
        int ready = st.path("readyReplicas").asInt(0);
        int updated = st.path("updatedReplicas").asInt(0);
        out.put("replicas", replicas).put("updated", updated).put("ready", ready)
            .put("available", st.path("availableReplicas").asInt(0));
        String strategy = spec.path("updateStrategy").path("type").asString("RollingUpdate");
        int partition = spec.path("updateStrategy").path("rollingUpdate").path("partition").asInt(0);
        if (!strategy.equals("RollingUpdate")) {
          message = "no rollout status for update strategy " + strategy;
        } else if (observed == 0 || generation > observed) {
          message = "waiting for the spec update to be observed";
        } else if (ready < replicas) {
          message = ready + " of " + replicas + " pods ready";
        } else if (partition > 0) {
          complete = updated >= replicas - partition;
          message = complete ? "partitioned rollout complete (" + updated + " new pods)"
              : updated + " of " + (replicas - partition) + " new pods updated (partitioned)";
        } else if (!st.path("updateRevision").asString("").equals(st.path("currentRevision").asString(""))) {
          message = updated + " of " + replicas + " pods at the new revision";
        } else {
          message = ready + "/" + replicas + " complete";
          complete = true;
        }
      }
      default -> {
        int desired = st.path("desiredNumberScheduled").asInt(0);
        int updated = st.path("updatedNumberScheduled").asInt(0);
        int available = st.path("numberAvailable").asInt(0);
        out.put("replicas", desired).put("updated", updated).put("ready", st.path("numberReady").asInt(0))
            .put("available", available);
        String strategy = spec.path("updateStrategy").path("type").asString("RollingUpdate");
        if (!strategy.equals("RollingUpdate")) {
          message = "no rollout status for update strategy " + strategy;
        } else if (generation > observed) {
          message = "waiting for the spec update to be observed";
        } else if (updated < desired) {
          message = updated + " of " + desired + " new pods updated";
        } else if (available < desired) {
          message = available + " of " + desired + " updated pods available";
        } else {
          message = available + "/" + desired + " complete";
          complete = true;
        }
      }
    }
    out.put("complete", complete);
    out.put("paused", spec.path("paused").asBoolean(false));
    out.put("summary", message);
    ArrayNode conditions = out.putArray("conditions");
    for (JsonNode c : st.path("conditions")) {
      ObjectNode n = conditions.addObject();
      n.put("type", c.path("type").asString("")).put("status", c.path("status").asString(""));
      if (c.has("reason")) {
        n.put("reason", c.path("reason").asString());
      }
      if (c.has("message")) {
        n.put("message", ai.msrv.gaterook.connectors.support.OutputSanitizer.sanitize(c.path("message").asString()));
      }
    }
    return out;
  }

  private static boolean deadlineExceeded(JsonNode status) {
    for (JsonNode c : status.path("conditions")) {
      if ("Progressing".equals(c.path("type").asString())
          && "ProgressDeadlineExceeded".equals(c.path("reason").asString())) {
        return true;
      }
    }
    return false;
  }

  /** What a restart does, from the live object: the approver's Impact block and warnings. */
  record Impact(List<Field> fields, List<String> warnings) {}

  static Impact restart(JsonNode o, String ns) {
    List<Field> fields = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String kind = o.path("kind").asString("");
    String name = o.path("metadata").path("name").asString("");
    JsonNode spec = o.path("spec");
    fields.add(new Field("Workload", kind.toLowerCase(java.util.Locale.ROOT) + "/" + name + " in " + ns));
    switch (kind) {
      case "Deployment" -> {
        int replicas = spec.path("replicas").asInt(1);
        String strategy = spec.path("strategy").path("type").asString("RollingUpdate");
        if (strategy.equals("Recreate")) {
          fields.add(new Field("Pods restarted", replicas + ", all at once"));
          fields.add(new Field("Expected downtime", "Yes (Recreate)"));
          warnings.add("All " + replicas + " pods stop before new ones start.");
        } else {
          JsonNode ru = spec.path("strategy").path("rollingUpdate");
          int surge = fencepost(ru.path("maxSurge"), "25%", replicas, true);
          int unavailable = fencepost(ru.path("maxUnavailable"), "25%", replicas, false);
          if (surge == 0 && unavailable == 0) {
            unavailable = 1;
          }
          fields.add(new Field("Pods restarted", replicas + ", up to " + surge + " new at a time, at most "
              + unavailable + " unavailable"));
          fields.add(new Field("Expected downtime", unavailable == 0 ? "None (rolling, max unavailable 0)"
              : unavailable >= replicas ? "Possible: every pod may be down at once" : "None if the others carry "
                  + "the load (rolling, max unavailable " + unavailable + ")"));
        }
        if (replicas == 0) {
          warnings.add(kind.toLowerCase(java.util.Locale.ROOT) + "/" + name + " has 0 replicas: nothing restarts.");
        }
      }
      case "StatefulSet" -> {
        int replicas = spec.path("replicas").asInt(1);
        String strategy = spec.path("updateStrategy").path("type").asString("RollingUpdate");
        if (strategy.equals("OnDelete")) {
          fields.add(new Field("Pods restarted", "None until pods are deleted"));
          warnings.add("updateStrategy OnDelete: restartedAt has no effect until pods are deleted.");
        } else {
          fields.add(new Field("Pods restarted", replicas + ", one at a time from the highest ordinal"));
        }
      }
      default -> {
        int desired = o.path("status").path("desiredNumberScheduled").asInt(0);
        String strategy = spec.path("updateStrategy").path("type").asString("RollingUpdate");
        if (strategy.equals("OnDelete")) {
          fields.add(new Field("Pods restarted", "None until pods are deleted"));
          warnings.add("updateStrategy OnDelete: restartedAt has no effect until pods are deleted.");
        } else {
          int unavailable = fencepost(spec.path("updateStrategy").path("rollingUpdate").path("maxUnavailable"), "1",
              Math.max(1, desired), false);
          fields.add(new Field("Pods restarted", desired + ", one node at a time (maxUnavailable "
              + Math.max(1, unavailable) + ")"));
        }
      }
    }
    List<String> images = PodSpecs.images(o);
    if (!images.isEmpty()) {
      fields.add(new Field("Image", String.join(", ", images.stream().map(i -> i.substring(i.indexOf('=') + 1))
          .toList()) + ", unchanged"));
    }
    gitOps(o).ifPresent(warnings::add);
    return new Impact(List.copyOf(fields), List.copyOf(warnings));
  }

  /**
   * {@code maxSurge}/{@code maxUnavailable} as the controller resolves them: a percentage of the replicas, surge
   * rounded up and unavailability down.
   */
  static int fencepost(JsonNode value, String dflt, int replicas, boolean roundUp) {
    String v = value.isMissingNode() || value.isNull() ? dflt : value.isNumber() ? value.asString() : value.asString();
    if (v.endsWith("%")) {
      double pct = Double.parseDouble(v.substring(0, v.length() - 1)) / 100.0;
      double n = pct * replicas;
      return (int) (roundUp ? Math.ceil(n) : Math.floor(n));
    }
    try {
      return Integer.parseInt(v.strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Objects Argo CD or Flux manage: they may put the old state back. */
  static java.util.Optional<String> gitOps(JsonNode o) {
    JsonNode meta = o.path("metadata");
    for (JsonNode map : List.of(meta.path("labels"), meta.path("annotations"))) {
      for (String key : map.propertyNames()) {
        if (key.startsWith("argocd.argoproj.io/") || key.equals("app.kubernetes.io/instance")
            && map.has("argocd.argoproj.io/instance")) {
          return java.util.Optional.of("Managed by Argo CD. It may revert the change.");
        }
        if (key.startsWith("kustomize.toolkit.fluxcd.io/") || key.startsWith("helm.toolkit.fluxcd.io/")) {
          return java.util.Optional.of("Managed by Flux. It may revert the change.");
        }
      }
    }
    return java.util.Optional.empty();
  }
}
