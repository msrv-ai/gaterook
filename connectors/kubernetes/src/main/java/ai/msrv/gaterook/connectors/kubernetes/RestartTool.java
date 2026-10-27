package ai.msrv.gaterook.connectors.kubernetes;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.kubernetes.K8sApi.StatusException;
import ai.msrv.gaterook.connectors.kubernetes.K8sRequests.Restart;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code k8s_rollout_restart} (report 08 §3.3): what {@code kubectl rollout restart} does — a new
 * {@code kubectl.kubernetes.io/restartedAt} on the pod template — sent as a JSON merge patch with the field manager
 * {@code mcp-gateway-rollout}.
 *
 * <p>The patch carries the {@code metadata.uid} of the object the person approved: if it was deleted and created
 * again since, the API server refuses the patch (uid is immutable) and nothing restarts.
 */
final class RestartTool {

  static final String FIELD_MANAGER = "mcp-gateway-rollout";
  static final String RESTARTED_AT = "kubectl.kubernetes.io/restartedAt";

  private RestartTool() {}

  /** The exact action without I/O: the command a person knows and the request it becomes. */
  static ActionPreview describe(Restart plan, ConnectionConfig cfg, String target) {
    String text = "kubectl rollout restart " + plan.kind().display(plan.name()) + " --namespace " + plan.namespace();
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("apiVersion", plan.kind().apiVersion()).put("kind", plan.kind().kind())
        .put("namespace", plan.namespace()).put("name", plan.name());
    return ActionPreview.of(ActionPreview.Kind.COMMAND, "sh", text, cfg.name() + " · " + target + " · ns "
        + plan.namespace(), KubernetesTools.RESTART + " · " + plan.kind().display(plan.name()) + " · " + cfg.name())
        .withFields(List.of(
            new Field("Request", "PATCH " + plan.kind().path(plan.namespace(), plan.name()) + " · merge patch · field "
                + "manager " + FIELD_MANAGER),
            new Field("Body", "{\"metadata\":{\"uid\":\"<uid of the approved object>\"},\"spec\":{\"template\":"
                + "{\"metadata\":{\"annotations\":{\"" + RESTARTED_AT + "\":\"<time of run>\"}}}}}")))
        .withFacets(Map.of("k8s.kind", plan.kind().singular(), "k8s.namespace", plan.namespace(), "k8s.name",
            plan.name()))
        .withPayload(payload);
  }

  /** Before the request exists: the object is there, not paused; its uid and what a restart does to it. */
  static PreparedAction prepare(Restart plan, K8sApi api, ConnectionConfig cfg, String target, ExecContext ctx)
      throws ConnectorException {
    JsonNode live = live(plan, api, ctx);
    if (live == null) {
      return PreparedAction.reject(plan.kind().display(plan.name()) + " not found in " + plan.namespace() + ".");
    }
    if (live.path("spec").path("paused").asBoolean(false)) {
      return PreparedAction.reject(plan.kind().display(plan.name()) + " is paused. Resume it first.");
    }
    Rollouts.Impact impact = Rollouts.restart(live, plan.namespace());
    ObjectNode state = JsonNodeFactory.instance.objectNode();
    state.put("uid", live.path("metadata").path("uid").asString());
    ActionPreview preview = describe(plan, cfg, target).withImpact(impact.fields()).withWarnings(impact.warnings());
    return PreparedAction.approval(preview, state);
  }

  /** After approval: the approved object, by its uid, gets its new {@code restartedAt}. */
  static ToolResult execute(Restart plan, K8sApi api, ConnectionConfig cfg, ExecContext ctx)
      throws ConnectorException {
    String uid = ctx.preparedState().map(s -> s.path("uid").asString(null)).orElse(null);
    if (uid == null) {
      throw ConnectorException.denied("The approved request has no object uid. Not restarted.");
    }
    String what = plan.kind().display(plan.name()) + " in " + plan.namespace();
    JsonNode live = live(plan, api, ctx);
    if (live == null || !uid.equals(live.path("metadata").path("uid").asString())) {
      throw ConnectorException.resourceError(what + " was deleted or replaced after approval. Not restarted.", null);
    }
    if (live.path("spec").path("paused").asBoolean(false)) {
      throw ConnectorException.resourceError(what + " was paused after approval. Not restarted.", null);
    }
    String at = Instant.now(ctx.clock()).truncatedTo(ChronoUnit.SECONDS).toString();
    ObjectNode patch = JsonNodeFactory.instance.objectNode();
    patch.putObject("metadata").put("uid", uid);
    patch.putObject("spec").putObject("template").putObject("metadata").putObject("annotations")
        .put(RESTARTED_AT, at);
    JsonNode result;
    try {
      result = api.patch(plan.kind().path(plan.namespace(), plan.name()),
          K8sApi.query().put("fieldManager", FIELD_MANAGER), K8sApi.MERGE_PATCH, K8sApi.JSON.writeValueAsString(patch),
          ctx);
    } catch (StatusException e) {
      if (e.code == 422 || e.code == 409) {
        throw ConnectorException.resourceError(what + " was replaced after approval (" + K8sText.cut(e.getMessage(),
            200) + "). Not restarted.", null);
      }
      throw K8sErrors.of(e, what);
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("status", "completed");
    out.put("kind", plan.kind().singular());
    out.put("name", plan.name());
    out.put("namespace", plan.namespace());
    out.put("restartedAt", at);
    out.put("generation", result.path("metadata").path("generation").asLong());
    out.put("hint", "Track it with k8s_get(kind=" + plan.kind().singular() + ", name=" + plan.name() + ")");
    return ToolResult.json(out, "Restarted " + what + " on " + cfg.name() + " at " + at + " (generation "
        + result.path("metadata").path("generation").asLong() + "). Track the rollout with k8s_get(kind="
        + plan.kind().singular() + ", name=" + plan.name() + ").");
  }

  private static JsonNode live(Restart plan, K8sApi api, ExecContext ctx) throws ConnectorException {
    try {
      return api.get(plan.kind().path(plan.namespace(), plan.name()), K8sApi.query(), K8sApi.ACCEPT_JSON, ctx);
    } catch (StatusException e) {
      if (e.code == 404) {
        return null;
      }
      throw K8sErrors.of(e, plan.kind().display(plan.name()) + " in " + plan.namespace());
    }
  }
}
