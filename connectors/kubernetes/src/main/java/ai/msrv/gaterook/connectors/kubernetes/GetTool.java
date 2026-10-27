package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.kubernetes.K8sApi.StatusException;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Get;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code k8s_get} (report 08 §3.1): a list with the server's own columns (Table, as {@code kubectl get}), or one
 * object with its events and, for workloads, the rollout status of {@code kubectl rollout status}.
 *
 * <p>The objects of Table rows never reach the agent (their metadata carries {@code last-applied-configuration},
 * plain text on a Secret applied with kubectl); a listing across namespaces asks each allowed namespace on its own —
 * never cluster-wide, which RBAC would refuse and which would show other teams' objects. Paging is a sealed cursor
 * ({@code k8c.…}): the agent can't point it at another query or namespace.
 */
final class GetTool {

  static final String CURSOR_PREFIX = "k8c.";
  private static final String CURSOR_PURPOSE = "k8s-list";
  private static final Duration CURSOR_TTL = Duration.ofMinutes(15);
  private static final int MAX_EVENTS = 20;
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private GetTool() {}

  static ToolResult run(Get plan, K8sApi api, ConnectionConfig cfg, ExecContext ctx) throws ConnectorException {
    return plan.name() == null ? list(plan, api, cfg, ctx) : object(plan, api, cfg, ctx);
  }

  // ── list ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** The policy's {@code listMax} was checked by {@code classify}; the call's row limit cuts here. */
  private static ToolResult list(Get plan, K8sApi api, ConnectionConfig cfg, ExecContext ctx)
      throws ConnectorException {
    int limit = Math.max(1, Math.min(plan.limit(), Math.max(1, ctx.limits().maxRows())));
    List<String> namespaces = plan.namespaces().isEmpty() ? java.util.Collections.singletonList(null)
        : plan.namespaces();
    String query = queryKey(cfg, plan);
    int start = 0;
    String serverContinue = null;
    if (plan.cursor() != null) {
      JsonNode c = openCursor(plan.cursor(), ctx);
      if (!query.equals(c.path("q").asString())) {
        throw ConnectorException.badInput("This cursor belongs to another listing: repeat the same kind, namespace "
            + "and selectors, or start without cursor.");
      }
      start = c.path("i").asInt();
      serverContinue = c.path("c").asString(null);
      if (start < 0 || start >= namespaces.size()) {
        throw ConnectorException.badInput("The cursor is past the end of the listing.");
      }
    }
    List<String> columns = null;
    List<Integer> wanted = null;
    ArrayNode rows = NODES.arrayNode();
    List<List<String>> textRows = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    String nextContinue = null;
    int nextIndex = -1;
    Instant now = ctx.clock().instant();
    for (int i = start; i < namespaces.size(); i++) {
      String ns = namespaces.get(i);
      int left = limit - rows.size();
      if (left <= 0) {
        nextIndex = i;
        nextContinue = null;
        break;
      }
      K8sApi.Query q = K8sApi.query().put("limit", left).put("continue", i == start ? serverContinue : null)
          .put("labelSelector", plan.labelSelector()).put("fieldSelector", plan.fieldSelector())
          .put("includeObject", "Metadata");
      JsonNode answer;
      try {
        answer = api.get(plan.kind().path(ns, null), q, K8sApi.ACCEPT_TABLE, ctx);
      } catch (StatusException e) {
        if (plan.allNamespaces() && (e.code == 403 || e.code == 404)) {
          notes.add("Skipped " + ns + ": " + (e.code == 403 ? "forbidden by RBAC" : "not found") + ".");
          continue;
        }
        if (e.code == 410) {
          throw ConnectorException.badInput("The cursor expired (the cluster compacted its history). List again "
              + "without cursor.");
        }
        throw K8sErrors.of(e, plan.kind().plural() + (ns == null ? "" : " in " + ns));
      }
      boolean table = "Table".equals(answer.path("kind").asString());
      if (columns == null) {
        columns = new ArrayList<>();
        wanted = new ArrayList<>();
        if (plan.allNamespaces()) {
          columns.add("Namespace");
        }
        if (table) {
          JsonNode defs = answer.path("columnDefinitions");
          for (int c = 0; c < defs.size(); c++) {
            if (plan.wide() || defs.get(c).path("priority").asInt(0) == 0) {
              wanted.add(c);
              columns.add(defs.get(c).path("name").asString());
            }
          }
        } else {
          columns.addAll(List.of("Name", "Status", "Age"));
        }
      }
      JsonNode defs = answer.path("columnDefinitions");
      if (table) {
        for (JsonNode r : answer.path("rows")) {
          JsonNode meta = r.path("object").path("metadata");
          ObjectNode row = rows.addObject();
          String name = meta.path("name").asString(r.path("cells").path(0).asString(""));
          row.put("name", name);
          row.put("namespace", meta.path("namespace").asString(null));
          ArrayNode cells = row.putArray("cells");
          List<String> text = new ArrayList<>();
          if (plan.allNamespaces()) {
            text.add(String.valueOf(ns));
          }
          for (int c : wanted) {
            JsonNode cell = r.path("cells").path(c);
            if ("date".equals(defs.path(c).path("format").asString())) {
              String age = K8sText.ageOf(cell.asString(null), now);
              cells.add(age);
              text.add(age);
            } else {
              JsonNode clean = cell.isString() ? NODES.stringNode(OutputSanitizer.sanitize(cell.asString())) : cell;
              cells.add(clean);
              text.add(clean.isNull() ? "<none>" : clean.isString() ? clean.asString() : clean.toString());
            }
          }
          textRows.add(text);
        }
      } else {
        // The API server (or an aggregated API) didn't print a Table: a summary of our own.
        for (JsonNode item : answer.path("items")) {
          JsonNode meta = item.path("metadata");
          ObjectNode row = rows.addObject();
          String name = meta.path("name").asString("");
          row.put("name", name);
          row.put("namespace", meta.path("namespace").asString(null));
          String status = status(item);
          String age = K8sText.ageOf(meta.path("creationTimestamp").asString(null), now);
          row.putArray("cells").add(name).add(status).add(age);
          List<String> text = new ArrayList<>();
          if (plan.allNamespaces()) {
            text.add(String.valueOf(ns));
          }
          text.addAll(List.of(name, status, age));
          textRows.add(text);
        }
      }
      String cont = answer.path("metadata").path("continue").asString(null);
      if (cont != null && !cont.isEmpty()) {
        nextIndex = i;
        nextContinue = cont;
        break;
      }
    }
    if (columns == null) {
      columns = new ArrayList<>(List.of("Name"));
    }
    String cursor = nextIndex < 0 ? null : sealCursor(query, nextIndex, nextContinue, ctx);
    ObjectNode out = NODES.objectNode();
    out.put("mode", "list");
    out.put("connection", cfg.name());
    out.put("kind", plan.kind().plural());
    out.put("apiVersion", plan.kind().apiVersion());
    out.put("namespace", plan.allNamespaces() || plan.namespaces().isEmpty() ? null : plan.namespaces().get(0));
    ArrayNode cols = out.putArray("columns");
    columns.forEach(cols::add);
    out.set("rows", rows);
    out.put("cursor", cursor);
    out.put("truncated", cursor != null);
    if (!notes.isEmpty()) {
      ArrayNode n = out.putArray("notes");
      notes.forEach(n::add);
    }
    StringBuilder text = new StringBuilder();
    text.append(plan.kind().plural()).append(where(plan)).append(" on ").append(cfg.name()).append(" · ")
        .append(K8sText.plural(rows.size(), "object", "objects"));
    if (cursor != null) {
      text.append(" · more: call again with cursor ").append(cursor);
    }
    text.append('\n');
    if (rows.isEmpty()) {
      text.append("No ").append(plan.kind().plural()).append(" found.\n");
    } else {
      text.append(K8sText.table(columns, textRows));
    }
    notes.forEach(n -> text.append(n).append('\n'));
    ToolResult result = ToolResult.json(out, text.toString().stripTrailing());
    return cursor == null ? result : result.truncated("row_limit");
  }

  private static String where(Get plan) {
    if (plan.namespaces().isEmpty()) {
      return "";
    }
    return plan.allNamespaces() ? " in " + String.join(", ", plan.namespaces()) : " in " + plan.namespaces().get(0);
  }

  /** What a cursor may continue: the same connection, kind, namespaces and selectors. */
  private static String queryKey(ConnectionConfig cfg, Get plan) {
    ObjectNode q = NODES.objectNode();
    q.put("c", cfg.id()).put("k", plan.kind().rbacName()).put("l", plan.labelSelector()).put("f",
        plan.fieldSelector()).put("w", plan.wide());
    ArrayNode ns = q.putArray("n");
    plan.namespaces().forEach(ns::add);
    return K8sText.sha256(q).substring(0, 32);
  }

  private static String sealCursor(String query, int index, String serverContinue, ExecContext ctx) {
    ObjectNode p = NODES.objectNode();
    p.put("q", query).put("i", index);
    if (serverContinue != null) {
      p.put("c", serverContinue);
    }
    return CURSOR_PREFIX + ctx.handles().seal(CURSOR_PURPOSE, p, CURSOR_TTL);
  }

  private static JsonNode openCursor(String cursor, ExecContext ctx) throws ConnectorException {
    if (!cursor.startsWith(CURSOR_PREFIX)) {
      throw ConnectorException.badInput("cursor must be the value a previous k8s_get returned.");
    }
    Optional<JsonNode> opened = ctx.handles().open(CURSOR_PURPOSE, cursor.substring(CURSOR_PREFIX.length()));
    return opened.orElseThrow(() -> ConnectorException.badInput("The cursor is invalid or expired. List again "
        + "without cursor."));
  }

  /** Phase, condition or replicas: the one word kubectl would show when the server prints no Table. */
  private static String status(JsonNode item) {
    JsonNode s = item.path("status");
    if (s.path("phase").isString()) {
      return s.path("phase").asString();
    }
    for (JsonNode c : s.path("conditions")) {
      if ("Ready".equals(c.path("type").asString()) || "Available".equals(c.path("type").asString())) {
        return c.path("type").asString() + "=" + c.path("status").asString();
      }
    }
    if (s.has("replicas")) {
      return s.path("readyReplicas").asInt(0) + "/" + s.path("replicas").asInt(0);
    }
    return "<none>";
  }

  // ── one object ──────────────────────────────────────────────────────────────────────────────────────────────

  private static ToolResult object(Get plan, K8sApi api, ConnectionConfig cfg, ExecContext ctx)
      throws ConnectorException {
    String ns = plan.namespaces().isEmpty() ? null : plan.namespaces().get(0);
    KindInfo kind = plan.kind();
    String what = kind.display(plan.name()) + (ns == null ? "" : " in " + ns);
    JsonNode live;
    try {
      live = api.get(kind.path(ns, plan.name()), K8sApi.query(), K8sApi.ACCEPT_JSON, ctx);
    } catch (StatusException e) {
      throw K8sErrors.of(e, what);
    }
    ObjectNode out = NODES.objectNode();
    out.put("mode", "object");
    out.put("connection", cfg.name());
    out.put("kind", kind.plural());
    out.put("apiVersion", kind.apiVersion());
    out.put("namespace", ns);
    out.set("object", ObjectNormalizer.forAgent(live));
    Instant now = ctx.clock().instant();
    StringBuilder header = new StringBuilder(what).append(" on ").append(cfg.name()).append(" (")
        .append(cfg.env().id()).append(')');
    ObjectNode rollout = Rollouts.status(live);
    if (rollout != null) {
      out.set("rollout", rollout);
      header.append(" · rollout ").append(rollout.path("summary").asString());
    }
    if (kind.group().isEmpty() && kind.plural().equals("pods")) {
      ArrayNode containers = containers(live);
      out.set("containers", containers);
      header.append(" · ").append(podSummary(live, containers));
    }
    List<String> eventLines = new ArrayList<>();
    if (plan.events() && ns != null) {
      ArrayNode events = out.putArray("events");
      String uid = live.path("metadata").path("uid").asString(null);
      if (uid != null) {
        try {
          JsonNode list = api.get("api/v1/namespaces/" + K8sApi.segment(ns) + "/events",
              K8sApi.query().put("fieldSelector", "involvedObject.uid=" + uid), K8sApi.ACCEPT_JSON, ctx);
          for (JsonNode e : latest(list.path("items"))) {
            ObjectNode ev = events.addObject();
            String when = eventTime(e);
            String message = OutputSanitizer.sanitize(e.path("message").asString(""));
            ev.put("type", e.path("type").asString(""));
            ev.put("reason", e.path("reason").asString(""));
            ev.put("message", message);
            ev.put("count", e.path("count").asInt(1));
            ev.put("lastSeen", when);
            ev.put("age", K8sText.ageOf(when, now));
            eventLines.add("  " + e.path("type").asString("") + "  " + e.path("reason").asString("")
                + (e.path("count").asInt(1) > 1 ? " ×" + e.path("count").asInt() : "") + "  ("
                + K8sText.ageOf(when, now) + " ago)  " + message);
          }
        } catch (StatusException e) {
          eventLines.add("  (events: " + (e.code == 403 ? "forbidden by RBAC" : e.getMessage()) + ")");
        }
      }
    }
    StringBuilder text = new StringBuilder(header).append('\n').append(YamlText.write(out.get("object")));
    if (!eventLines.isEmpty() || plan.events()) {
      text.append("Events:\n");
      if (eventLines.isEmpty()) {
        text.append("  <none>\n");
      }
      eventLines.forEach(l -> text.append(l).append('\n'));
    }
    return ToolResult.json(out, text.toString().stripTrailing());
  }

  /** The latest events first, by {@code lastTimestamp} or else {@code eventTime} (events.k8s.io has only that). */
  private static List<JsonNode> latest(JsonNode items) {
    List<JsonNode> all = new ArrayList<>();
    items.forEach(all::add);
    all.sort(Comparator.comparing((JsonNode e) -> {
      Instant t = K8sText.instant(eventTime(e));
      return t == null ? Instant.EPOCH : t;
    }).reversed());
    return all.subList(0, Math.min(MAX_EVENTS, all.size()));
  }

  private static String eventTime(JsonNode e) {
    for (String f : new String[] {"lastTimestamp", "eventTime", "firstTimestamp"}) {
      String t = e.path(f).asString(null);
      if (t != null && !t.isEmpty()) {
        return t;
      }
    }
    return e.path("metadata").path("creationTimestamp").asString(null);
  }

  /** What describe shows first for a pod: per container its image, readiness, restarts and why it last stopped. */
  private static ArrayNode containers(JsonNode pod) {
    ArrayNode out = NODES.arrayNode();
    JsonNode statuses = pod.path("status").path("containerStatuses");
    for (JsonNode c : pod.path("spec").path("containers")) {
      String name = c.path("name").asString("");
      JsonNode st = NODES.objectNode();
      for (JsonNode s : statuses) {
        if (name.equals(s.path("name").asString())) {
          st = s;
        }
      }
      ObjectNode n = out.addObject();
      n.put("name", name);
      n.put("image", c.path("image").asString(null));
      n.put("ready", st.path("ready").asBoolean(false));
      n.put("restartCount", st.path("restartCount").asInt(0));
      n.put("state", state(st.path("state")));
      String last = st.path("lastState").path("terminated").path("reason").asString(null);
      if (last != null) {
        n.put("lastTerminated", last);
      }
    }
    return out;
  }

  private static String state(JsonNode state) {
    if (state.has("running")) {
      return "running";
    }
    if (state.has("waiting")) {
      return "waiting: " + state.path("waiting").path("reason").asString("");
    }
    if (state.has("terminated")) {
      return "terminated: " + state.path("terminated").path("reason").asString("");
    }
    return "unknown";
  }

  private static String podSummary(JsonNode pod, ArrayNode containers) {
    int ready = 0;
    int restarts = 0;
    for (JsonNode c : containers) {
      ready += c.path("ready").asBoolean() ? 1 : 0;
      restarts += c.path("restartCount").asInt();
    }
    return pod.path("status").path("phase").asString("Unknown") + " · " + ready + "/" + containers.size()
        + " ready · " + K8sText.plural(restarts, "restart", "restarts");
  }
}
