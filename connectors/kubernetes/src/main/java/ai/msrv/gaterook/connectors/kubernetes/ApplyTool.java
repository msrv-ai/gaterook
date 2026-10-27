package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.kubernetes.K8sApi.StatusException;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Apply;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Apply.Item;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code k8s_apply} (report 08 §3.4): server-side apply with the field manager {@code mcp-gateway}, in three steps.
 *
 * <ol>
 *   <li>{@code prepare}: per object a dry run ({@code dryRun=All}) — what the API server would store, defaulting,
 *       mutating webhooks and the merge semantics of server-side apply included — and the unified diff of the live
 *       object against it. That diff is the exact action the person approves. Nothing changes: a conflict with
 *       another field manager, a validation error or "nothing to change" answers the agent without a request.</li>
 *   <li>The request keeps, per object, the {@code resourceVersion} the diff was built on and the SHA-256 of the
 *       dry run without the fields the server sets by itself ({@link ObjectNormalizer#forHash}).</li>
 *   <li>After approval the stored objects are applied one by one with that {@code resourceVersion} as a
 *       precondition. A 409 (the status moved: controllers write it all the time) gets a new dry run; only if it
 *       still yields the approved object is it applied once more with the current version. Otherwise the apply
 *       stops there: "changed in the cluster after approval".</li>
 * </ol>
 */
final class ApplyTool {

  static final String FIELD_MANAGER = "mcp-gateway";
  /** A diff longer than this can't be reviewed with care: the agent splits the manifest. */
  static final int MAX_DIFF_LINES = 2000;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private ApplyTool() {}

  /** Without I/O the exact action is the manifest as it will be sent (the diff needs the dry run of prepare). */
  static ActionPreview describe(Apply plan, ConnectionConfig cfg, String target) {
    StringBuilder text = new StringBuilder();
    for (Item item : plan.items()) {
      if (!text.isEmpty()) {
        text.append("---\n");
      }
      text.append(YamlText.write(item.object()));
    }
    return ActionPreview.of(ActionPreview.Kind.TEXT, "yaml", text.toString(), target(cfg, target, plan),
        summary(plan, cfg)).withPayload(payload(plan)).withFacets(facets(plan));
  }

  static PreparedAction prepare(Apply plan, K8sApi api, ConnectionConfig cfg, String target, ExecContext ctx)
      throws ConnectorException {
    List<String> diff = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<Field> impact = new ArrayList<>();
    ArrayNode objects = NODES.arrayNode();
    Set<String> takenOver = new LinkedHashSet<>();
    int configured = 0;
    int created = 0;
    int unchanged = 0;
    for (Item item : plan.items()) {
      JsonNode live = get(api, item, ctx);
      if (plan.force()) {
        // Which fields the force takes from whom: the approver sees it ("takes over .spec.replicas from kubectl").
        try {
          dryRun(api, item, false, ctx);
        } catch (StatusException e) {
          if (e.code != 409) {
            return reject(item, e);
          }
          for (String f : K8sErrors.conflicts(e)) {
            takenOver.add(item.display() + " " + f);
          }
        }
      }
      JsonNode dry;
      try {
        dry = dryRun(api, item, plan.force(), ctx);
      } catch (StatusException e) {
        if (e.code == 409) {
          return PreparedAction.reject("Apply would take over fields of " + item.display() + " that another field "
              + "manager owns: " + String.join(", ", K8sErrors.conflicts(e)) + ". Re-run with force=true; the "
              + "approver will see which.");
        }
        return reject(item, e);
      }
      String state;
      if (live == null) {
        state = "created";
        created++;
      } else if (!ObjectNormalizer.forHash(live).equals(ObjectNormalizer.forHash(dry))) {
        state = "configured";
        configured++;
      } else {
        // Only fields the server keeps for itself would move (a new resourceVersion for the ownership): no change.
        state = "unchanged";
        unchanged++;
      }
      if (!state.equals("unchanged")) {
        diff.addAll(YamlDiff.of(item.display(), live, dry).lines());
      }
      impact.add(new Field(item.display() + " (" + item.kind().apiVersion() + ")" + (item.namespace() == null ? ""
          : " in " + item.namespace()), state));
      warnings.addAll(changes(item, live, dry));
      ObjectNode o = objects.addObject();
      o.put("key", item.key());
      o.put("state", state);
      o.put("rv", live == null ? null : live.path("metadata").path("resourceVersion").asString(null));
      o.put("sha256", K8sText.sha256(ObjectNormalizer.forHash(dry)));
    }
    if (configured == 0 && created == 0) {
      return PreparedAction.noChange("unchanged: " + K8sText.plural(unchanged, "object already matches",
          "objects already match") + " the manifest on " + cfg.name() + ". Nothing to approve.");
    }
    if (diff.size() > MAX_DIFF_LINES) {
      return PreparedAction.reject("Denied by k8s.diff-too-large: the diff has " + diff.size() + " lines, more than "
          + MAX_DIFF_LINES + " can be reviewed. Split the manifest.");
    }
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("Objects", counts(configured, created, unchanged)));
    fields.add(new Field("Field manager", FIELD_MANAGER));
    if (plan.force()) {
      fields.add(new Field("Force", takenOver.isEmpty() ? "on; no field of another manager is taken over"
          : "takes over " + String.join(", ", takenOver)));
    }
    if (plan.items().size() > 1) {
      warnings.add("Applied one by one in this order, not atomically: " + String.join(", ", plan.items().stream()
          .map(Item::display).toList()) + ".");
    }
    ObjectNode state = NODES.objectNode();
    state.put("force", plan.force());
    state.set("objects", objects);
    ActionPreview preview = ActionPreview.of(ActionPreview.Kind.DIFF, "diff", String.join("\n", diff) + "\n",
        target(cfg, target, plan), summary(plan, cfg))
        .withFields(fields).withImpact(impact).withWarnings(List.copyOf(new LinkedHashSet<>(warnings)))
        .withFacets(facets(plan)).withPayload(payload(plan));
    return PreparedAction.approval(preview, state);
  }

  static ToolResult execute(Apply plan, K8sApi api, ConnectionConfig cfg, ExecContext ctx) throws ConnectorException {
    JsonNode state = ctx.preparedState().orElseThrow(() -> ConnectorException.denied("The approved request has no "
        + "dry run to hold the apply to. Not applied."));
    JsonNode prepared = state.path("objects");
    if (prepared.size() != plan.items().size()) {
      throw ConnectorException.denied("The approved request doesn't match its manifest. Not applied.");
    }
    ArrayNode results = NODES.arrayNode();
    List<String> summary = new ArrayList<>();
    String stop = null;
    for (int i = 0; i < plan.items().size(); i++) {
      Item item = plan.items().get(i);
      JsonNode p = prepared.get(i);
      ObjectNode r = results.addObject();
      r.put("object", item.display());
      r.put("namespace", item.namespace());
      if (!item.key().equals(p.path("key").asString())) {
        throw ConnectorException.denied("The approved request doesn't match its manifest. Not applied.");
      }
      if (stop != null) {
        r.put("result", "skipped");
        continue;
      }
      if ("unchanged".equals(p.path("state").asString())) {
        r.put("result", "unchanged");
        summary.add(item.display() + " unchanged");
        continue;
      }
      String rv = p.path("rv").asString(null);
      try {
        if (rv == null) {
          // Created at approval time: nobody may have created it since, or the apply would merge into theirs.
          if (!sameAsApproved(api, item, plan.force(), p, ctx)) {
            stop = item.display() + " changed in the cluster after approval.";
            r.put("result", "failed").put("message", stop);
            continue;
          }
          apply(api, item, null, plan.force(), ctx);
          r.put("result", "created");
          summary.add(item.display() + " created");
        } else {
          try {
            apply(api, item, rv, plan.force(), ctx);
          } catch (StatusException e) {
            if (e.code != 409) {
              throw e;
            }
            // The status moved under us, as controllers do: the same approved object, at the current version.
            JsonNode current = get(api, item, ctx);
            String currentRv = current == null ? null : current.path("metadata").path("resourceVersion").asString(null);
            if (currentRv == null || !sameAsApproved(api, item, plan.force(), p, ctx)) {
              stop = item.display() + " changed in the cluster after approval.";
              r.put("result", "failed").put("message", stop);
              continue;
            }
            apply(api, item, currentRv, plan.force(), ctx);
          }
          r.put("result", "configured");
          summary.add(item.display() + " configured");
        }
      } catch (StatusException e) {
        stop = item.display() + ": " + K8sErrors.of(e, item.display()).getMessage();
        r.put("result", "failed").put("message", stop);
      } catch (ConnectorException e) {
        if (e.kind() == ConnectorException.Kind.OUTCOME_UNKNOWN) {
          throw ConnectorException.outcomeUnknown(e.getMessage() + done(summary), e);
        }
        throw e;
      }
    }
    ObjectNode out = NODES.objectNode();
    out.put("status", stop == null ? "completed" : summary.isEmpty() ? "failed" : "partial");
    out.put("fieldManager", FIELD_MANAGER);
    out.set("objects", results);
    if (stop == null) {
      return ToolResult.json(out, "Applied on " + cfg.name() + ": " + String.join(", ", summary) + ".");
    }
    String text = stop + " Nothing after it was applied." + done(summary) + " Ask for a new approval.";
    return new ToolResult(List.of(ToolResult.Content.text(text)), out, true, null, false, null, List.of(), 0, 0,
        null);
  }

  private static String done(List<String> summary) {
    return summary.isEmpty() ? "" : " Before it: " + String.join(", ", summary) + ".";
  }

  private static boolean sameAsApproved(K8sApi api, Item item, boolean force, JsonNode p, ExecContext ctx)
      throws StatusException, ConnectorException {
    JsonNode dry = dryRun(api, item, force, ctx);
    return p.path("sha256").asString().equals(K8sText.sha256(ObjectNormalizer.forHash(dry)));
  }

  private static JsonNode get(K8sApi api, Item item, ExecContext ctx) throws ConnectorException {
    try {
      return api.get(item.kind().path(item.namespace(), item.name()), K8sApi.query(), K8sApi.ACCEPT_JSON, ctx);
    } catch (StatusException e) {
      if (e.code == 404) {
        return null;
      }
      throw K8sErrors.of(e, item.display() + (item.namespace() == null ? "" : " in " + item.namespace()));
    }
  }

  private static JsonNode dryRun(K8sApi api, Item item, boolean force, ExecContext ctx)
      throws StatusException, ConnectorException {
    return api.patch(item.kind().path(item.namespace(), item.name()), query(force).put("dryRun", "All"),
        K8sApi.APPLY_PATCH, K8sApi.JSON.writeValueAsString(item.object()), ctx);
  }

  private static JsonNode apply(K8sApi api, Item item, String resourceVersion, boolean force, ExecContext ctx)
      throws StatusException, ConnectorException {
    ObjectNode body = item.object().deepCopy();
    if (resourceVersion != null) {
      ((ObjectNode) body.get("metadata")).put("resourceVersion", resourceVersion);
    }
    return api.patch(item.kind().path(item.namespace(), item.name()), query(force), K8sApi.APPLY_PATCH,
        K8sApi.JSON.writeValueAsString(body), ctx);
  }

  /** Strict field validation, as {@code kubectl apply --server-side} sends: an unknown field is an error. */
  private static K8sApi.Query query(boolean force) {
    return K8sApi.query().put("fieldManager", FIELD_MANAGER).put("fieldValidation", "Strict")
        .put("force", force ? "true" : null);
  }

  private static PreparedAction reject(Item item, StatusException e) {
    String m = K8sErrors.of(e, item.display() + (item.namespace() == null ? "" : " in " + item.namespace()))
        .getMessage();
    if (m.contains("does not support dry run")) {
      return PreparedAction.reject("Can't preview " + item.display() + ": " + m + " Nothing applied.");
    }
    return PreparedAction.reject(item.display() + ": " + m + " Nothing applied.");
  }

  /** What the approver should notice beyond the diff: images, replicas, pods that reach the node, GitOps. */
  private static List<String> changes(Item item, JsonNode live, JsonNode dry) {
    List<String> out = new ArrayList<>();
    if (live != null) {
      List<String> before = PodSpecs.images(live);
      for (String after : PodSpecs.images(dry)) {
        String container = after.substring(0, after.indexOf('='));
        for (String b : before) {
          if (b.startsWith(container + "=") && !b.equals(after)) {
            out.add(item.display() + ": image of " + container + " " + b.substring(b.indexOf('=') + 1) + " → "
                + after.substring(after.indexOf('=') + 1));
          }
        }
      }
      JsonNode r0 = live.path("spec").path("replicas");
      JsonNode r1 = dry.path("spec").path("replicas");
      if (r0.isNumber() && r1.isNumber() && r0.asInt() != r1.asInt()) {
        out.add(item.display() + ": replicas " + r0.asInt() + " → " + r1.asInt());
      }
      Rollouts.gitOps(live).ifPresent(g -> out.add(item.display() + ": " + g));
    }
    List<String> escapes = PodSpecs.escapes(dry);
    if (!escapes.isEmpty()) {
      out.add(item.display() + " reaches the node: " + String.join(", ", escapes) + ".");
    }
    return out;
  }

  private static String counts(int configured, int created, int unchanged) {
    List<String> parts = new ArrayList<>();
    if (configured > 0) {
      parts.add(configured + " configured");
    }
    if (created > 0) {
      parts.add(created + " created");
    }
    if (unchanged > 0) {
      parts.add(unchanged + " unchanged");
    }
    return String.join(", ", parts);
  }

  private static ObjectNode payload(Apply plan) {
    ObjectNode p = NODES.objectNode();
    p.put("force", plan.force());
    ArrayNode objects = p.putArray("objects");
    plan.items().forEach(i -> objects.add(i.object()));
    return p;
  }

  private static String target(ConnectionConfig cfg, String target, Apply plan) {
    Set<String> ns = new LinkedHashSet<>();
    plan.items().stream().map(Item::namespace).filter(Objects::nonNull).forEach(ns::add);
    return cfg.name() + " · " + target + (ns.isEmpty() ? "" : " · ns " + String.join(", ", ns));
  }

  private static String summary(Apply plan, ConnectionConfig cfg) {
    String first = plan.items().get(0).display();
    return KubernetesTools.APPLY + " · " + first + (plan.items().size() > 1 ? " and " + (plan.items().size() - 1)
        + " more" : "") + " · " + cfg.name();
  }

  private static Map<String, String> facets(Apply plan) {
    Item first = plan.items().get(0);
    return first.namespace() == null ? Map.of("k8s.kind", first.kind().singular(), "k8s.name", first.name())
        : Map.of("k8s.kind", first.kind().singular(), "k8s.namespace", first.namespace(), "k8s.name", first.name());
  }
}
