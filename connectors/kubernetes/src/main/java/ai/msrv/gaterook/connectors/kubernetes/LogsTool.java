package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.kubernetes.K8sApi.StatusException;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Logs;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code k8s_logs} (report 08 §3.2): the tail of one container's log, read as a stream into a ring buffer, sanitized
 * (ANSI and control characters become visible escapes: log lines are untrusted content). {@code deployment/api}
 * picks one of its pods like {@code kubectl logs deployment/api}: a ready one, the newest.
 *
 * <p>The read stops a little before the call's deadline and answers with what arrived, so a chatty container gives
 * the agent its newest lines instead of a timeout.
 */
final class LogsTool {

  /** The server-side cap of one transfer; the ring buffer keeps the newest {@code maxBytes} of it. */
  static final long LIMIT_BYTES = 8L << 20;
  private static final Duration STOP_MARGIN = Duration.ofMillis(700);

  private LogsTool() {}

  /** The policy's {@code logsTailMax} was checked by {@code classify}; the call's line limit cuts here. */
  static ToolResult run(Logs plan, K8sApi api, ConnectionConfig cfg, ExecContext ctx) throws ConnectorException {
    String ns = plan.namespace();
    JsonNode pod = plan.owner() == null ? pod(api, ns, plan.pod(), ctx) : pick(plan, api, ns, ctx);
    String podName = pod.path("metadata").path("name").asString();
    String container = plan.container() != null ? plan.container() : onlyContainer(pod, podName);
    int tail = Math.min(plan.tailLines(), Math.max(1, ctx.limits().maxLines()));
    int maxBytes = (int) Math.min(plan.maxBytes(), Math.max(1024, ctx.limits().maxResponseBytes()));
    K8sApi.Query q = K8sApi.query().put("container", container).put("tailLines", tail)
        .put("sinceSeconds", plan.sinceSeconds()).put("previous", plan.previous() ? "true" : null)
        .put("timestamps", plan.timestamps() ? "true" : null).put("limitBytes", LIMIT_BYTES);
    String path = "api/v1/namespaces/" + K8sApi.segment(ns) + "/pods/" + K8sApi.segment(podName) + "/log";
    LogTail.Tail read;
    InputStream in;
    try {
      in = api.stream(path, q, ctx);
    } catch (StatusException e) {
      // 400 names the containers to choose from, or says the previous container doesn't exist: the agent's to read.
      throw K8sErrors.of(e, "pod/" + podName + " in " + ns);
    }
    AtomicBoolean stopped = new AtomicBoolean();
    Runnable stop = () -> {
      stopped.set(true);
      closeQuietly(in);
    };
    ctx.onCancel(stop);
    Duration budget = ctx.remaining().minus(STOP_MARGIN);
    Thread watchdog = Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(budget.isNegative() ? Duration.ZERO : budget);
        stop.run();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    try (in) {
      read = LogTail.read(in, maxBytes, stopped::get);
    } catch (IOException e) {
      throw ConnectorException.unavailable("The log stream of " + podName + " broke: " + e.getMessage(), e);
    } finally {
      watchdog.interrupt();
    }
    String text = OutputSanitizer.decode(read.bytes());
    long lines = text.isEmpty() ? 0 : text.lines().count();
    String reason = read.stopped() ? "timeout" : read.headCut() ? "head" : null;
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("pod", podName);
    out.put("container", container);
    out.put("namespace", ns);
    out.put("previous", plan.previous());
    out.put("lines", lines);
    out.put("bytes", read.bytes().length);
    out.put("truncated", reason != null);
    out.put("truncatedReason", reason);
    StringBuilder header = new StringBuilder("pod/").append(podName);
    if (container != null) {
      header.append(" (").append(container).append(')');
    }
    header.append(" in ").append(ns).append(" on ").append(cfg.name()).append(" · ")
        .append(K8sText.plural(lines, "line", "lines"));
    if (tail < plan.tailLines()) {
      header.append(" · tailLines limited to ").append(tail).append(" by the ").append(cfg.env().id())
          .append(" policy");
    }
    if (read.headCut()) {
      header.append(" · older lines cut at ").append(kb(maxBytes)).append(" of ").append(kb(read.total()))
          .append(". Use sinceSeconds or tailLines.");
    }
    if (read.stopped()) {
      header.append(" · stopped at the call's deadline: these are the lines that arrived.");
    }
    String shown = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    ToolResult result = ToolResult.json(out, header + "\n" + (shown.isEmpty() ? "(no log lines)" : shown));
    return reason == null ? result : result.truncated(reason);
  }

  private static JsonNode pod(K8sApi api, String ns, String name, ExecContext ctx) throws ConnectorException {
    try {
      return api.get("api/v1/namespaces/" + K8sApi.segment(ns) + "/pods/" + K8sApi.segment(name), K8sApi.query(),
          K8sApi.ACCEPT_JSON, ctx);
    } catch (StatusException e) {
      throw K8sErrors.of(e, "pod/" + name + " in " + ns);
    }
  }

  /** One pod of a workload, as kubectl picks it for logs: a ready one first, then the newest. */
  private static JsonNode pick(Logs plan, K8sApi api, String ns, ExecContext ctx) throws ConnectorException {
    String what = plan.owner().display(plan.ownerName()) + " in " + ns;
    JsonNode owner;
    try {
      owner = api.get(plan.owner().path(ns, plan.ownerName()), K8sApi.query(), K8sApi.ACCEPT_JSON, ctx);
    } catch (StatusException e) {
      throw K8sErrors.of(e, what);
    }
    String selector = selector(owner.path("spec").path("selector"));
    if (selector == null || selector.isEmpty()) {
      throw ConnectorException.resourceError(what + " has no pod selector.", null);
    }
    JsonNode pods;
    try {
      pods = api.get("api/v1/namespaces/" + K8sApi.segment(ns) + "/pods",
          K8sApi.query().put("labelSelector", selector).put("limit", 500), K8sApi.ACCEPT_JSON, ctx);
    } catch (StatusException e) {
      throw K8sErrors.of(e, "pods of " + what);
    }
    List<JsonNode> all = new ArrayList<>();
    pods.path("items").forEach(all::add);
    if (all.isEmpty()) {
      throw ConnectorException.resourceError(what + " has no pods.", null);
    }
    all.sort(Comparator.comparing((JsonNode p) -> ready(p) ? 0 : 1).thenComparing((JsonNode p) -> {
      Instant t = K8sText.instant(p.path("metadata").path("creationTimestamp").asString(null));
      return t == null ? Instant.EPOCH : t;
    }, Comparator.reverseOrder()));
    return all.get(0);
  }

  /** A label selector as the API wants it: {@code matchLabels} and {@code matchExpressions}, or a plain map (RC). */
  static String selector(JsonNode s) {
    List<String> parts = new ArrayList<>();
    JsonNode labels = s.has("matchLabels") || s.has("matchExpressions") ? s.path("matchLabels") : s;
    for (Map.Entry<String, JsonNode> e : labels.properties()) {
      if (e.getValue().isString()) {
        parts.add(e.getKey() + "=" + e.getValue().asString());
      }
    }
    for (JsonNode x : s.path("matchExpressions")) {
      String key = x.path("key").asString();
      List<String> values = new ArrayList<>();
      x.path("values").forEach(v -> values.add(v.asString()));
      switch (x.path("operator").asString("")) {
        case "In" -> parts.add(key + " in (" + String.join(",", values) + ")");
        case "NotIn" -> parts.add(key + " notin (" + String.join(",", values) + ")");
        case "Exists" -> parts.add(key);
        case "DoesNotExist" -> parts.add("!" + key);
        default -> {
          // An operator the API doesn't have: leave it out rather than guess.
        }
      }
    }
    return String.join(",", parts);
  }

  private static boolean ready(JsonNode pod) {
    if (!"Running".equals(pod.path("status").path("phase").asString())) {
      return false;
    }
    for (JsonNode c : pod.path("status").path("conditions")) {
      if ("Ready".equals(c.path("type").asString())) {
        return "True".equals(c.path("status").asString());
      }
    }
    return false;
  }

  private static String onlyContainer(JsonNode pod, String podName) throws ConnectorException {
    JsonNode containers = pod.path("spec").path("containers");
    if (containers.size() == 1) {
      return containers.get(0).path("name").asString(null);
    }
    List<String> names = new ArrayList<>();
    containers.forEach(c -> names.add(c.path("name").asString()));
    // kubectl takes the default-container annotation when there is one.
    String dflt = pod.path("metadata").path("annotations").path("kubectl.kubernetes.io/default-container")
        .asString(null);
    if (dflt != null && names.contains(dflt)) {
      return dflt;
    }
    if (names.isEmpty()) {
      return null;
    }
    throw ConnectorException.badInput("pod/" + podName + " has containers " + String.join(", ", names)
        + ": pick one with container.");
  }

  private static String kb(long bytes) {
    return bytes >= 1 << 20 ? String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        : (bytes + 1023) / 1024 + " KB";
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
      // Closing is how the read is stopped; a second close changes nothing.
    }
  }
}
