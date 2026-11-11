package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_pages_deployment}: {@code POST …/deployments/{id}/retry} builds and deploys a deployment again;
 * {@code POST …/deployments/{id}/rollback} makes an earlier, successful production deployment live. The approver sees
 * the deployment and, for a rollback, the one that is live now; a rollback runs only if production is still that one.
 */
final class PagesDeployment extends Change {

  static final Set<String> ARGS = Set.of("connection", "project", "deployment", "action");

  private final String project;
  private final String deployment;
  private final String action;

  private PagesDeployment(String project, String deployment, String action) {
    this.project = project;
    this.deployment = deployment;
    this.action = action;
  }

  static PagesDeployment parse(ObjectNode args) throws Refusal {
    Args.only(args, CloudflareTools.PAGES_DEPLOYMENT, ARGS);
    String project = Args.required(args, "project", Args.PROJECT, "a Pages project name", "Pass the project's name.");
    String deployment = Args.required(args, "deployment", Args.UUID, "a deployment ID", "Pass a deployment ID from "
        + "cloudflare_pages view deployments.").toLowerCase(java.util.Locale.ROOT);
    String action = Args.choice(args, "action", Set.of("retry", "rollback"), null);
    if (action == null) {
      throw Refusal.badInput("The call names no action.", "Pass retry or rollback.");
    }
    return new PagesDeployment(project, deployment, action);
  }

  @Override
  String tool() {
    return CloudflareTools.PAGES_DEPLOYMENT;
  }

  @Override
  String what() {
    return "Pages";
  }

  @Override
  Classification classification(String connection) {
    ObjectNode facts = Answers.F.objectNode();
    facts.put("cloudflare.project", project);
    facts.put("cloudflare.action", "pages." + action);
    return Classification.mutation((action.equals("retry") ? "Retries" : "Rolls production back to")
        + " deployment " + deployment + " of Pages project " + project, facts);
  }

  @Override
  Request request(Call c) {
    return new Request("POST", c.api().uri("accounts", c.settings().accountId(), "pages", "projects", project,
        "deployments", deployment, action), null);
  }

  @Override
  Context context(Call c, Request r) throws ConnectorException {
    JsonNode d;
    try {
      d = c.api().get(c, c.api().uri("accounts", c.settings().accountId(), "pages", "projects", project,
          "deployments", deployment), "Pages").result();
    } catch (CloudflareApi.CloudflareException e) {
      if (e.status == 404) {
        throw ConnectorException.badInput("Deployment " + deployment + " isn't a deployment of Pages project "
            + project + ". List them with cloudflare_pages view deployments.");
      }
      throw e;
    }
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("Project", project));
    fields.add(new Field("Deployment", describe(d)));
    List<Field> impact = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    ObjectNode state = null;
    String environment = d.path("environment").asString("");
    if (action.equals("rollback")) {
      if (!environment.equals("production")) {
        throw ConnectorException.badInput("Deployment " + deployment + " is a " + (environment.isEmpty() ? "non-"
            + "production" : environment) + " deployment: only production deployments can be rolled back to.");
      }
      if (!"success".equals(d.path("latest_stage").path("status").asString(""))) {
        throw ConnectorException.badInput("Deployment " + deployment + " didn't finish successfully ("
            + d.path("latest_stage").path("name").asString("?") + " " + d.path("latest_stage").path("status")
            .asString("?") + "): only a successful deployment can be rolled back to.");
      }
      JsonNode live = production(c);
      if (live != null && deployment.equals(live.path("id").asString(""))) {
        throw ConnectorException.badInput("Deployment " + deployment + " is already live in production.");
      }
      impact.add(new Field("Before", live == null ? "no production deployment" : "live: " + describe(live)));
      impact.add(new Field("After", "live: " + describe(d)));
      warnings.add("Production of " + project + " serves the older deployment within seconds; its build and "
          + "environment variables are those of that deployment.");
      state = Answers.F.objectNode();
      if (live == null) {
        state.putNull("production");
      } else {
        state.put("production", live.path("id").asString(""));
      }
    } else {
      impact.add(new Field("After", "a new build of " + describe(d)));
      if (environment.equals("production")) {
        warnings.add("Retries a production deployment: once it builds, it becomes live.");
      }
    }
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("cloudflare.project", project);
    facets.put("cloudflare.action", "pages." + action);
    return new Context("Pages project " + project, (action.equals("retry") ? "Retry " : "Roll back production to ")
        + "deployment " + d.path("short_id").asString(deployment) + " of " + project, fields, impact, warnings,
        facets, state);
  }

  /**
   * The production deployment that is live now: the project's {@code canonical_deployment}, which after a rollback
   * is older than the newest production deployment.
   */
  private JsonNode production(Call c) throws ConnectorException {
    JsonNode p = c.api().get(c, c.api().uri("accounts", c.settings().accountId(), "pages", "projects", project),
        "Pages").result();
    JsonNode live = p.path("canonical_deployment");
    return live.isObject() && live.path("id").isString() ? live : null;
  }

  private static String describe(JsonNode d) {
    JsonNode meta = d.path("deployment_trigger").path("metadata");
    StringBuilder b = new StringBuilder();
    b.append(d.path("short_id").asString(d.path("id").asString("?")));
    if (d.path("environment").isString()) {
      b.append(" · ").append(d.path("environment").asString());
    }
    if (meta.path("branch").isString()) {
      b.append(" · branch ").append(meta.path("branch").asString());
    }
    if (meta.path("commit_hash").isString()) {
      String h = meta.path("commit_hash").asString();
      b.append(" · commit ").append(h, 0, Math.min(12, h.length()));
    }
    if (meta.path("commit_message").isString()) {
      b.append(" \"").append(Args.abbreviate(meta.path("commit_message").asString())).append('"');
    }
    if (d.path("created_on").isString()) {
      b.append(" · ").append(d.path("created_on").asString());
    }
    return b.toString();
  }

  @Override
  void stillCurrent(Call c, Request r, JsonNode state) throws ConnectorException {
    if (!action.equals("rollback")) {
      return;
    }
    if (state == null || !state.has("production")) {
      throw ConnectorException.denied("GateRook has no record of what this rollback was approved against. Not "
          + "executed: call cloudflare_pages_deployment again.");
    }
    JsonNode live = production(c);
    String now = live == null ? null : live.path("id").asString("");
    String then = state.path("production").isNull() ? null : state.path("production").asString("");
    if (now == null ? then != null : !now.equals(then)) {
      throw ConnectorException.denied("Production of " + project + " changed after the approval (now "
          + (now == null ? "nothing" : "deployment " + now) + "). Not executed: check view deployments and ask for "
          + "a new approval.");
    }
  }

  @Override
  ToolResult answer(Call c, Request r, CloudflareApi.Answer a) {
    JsonNode d = a.result();
    return changed(c, "Pages deployment " + (action.equals("retry") ? "retried" : "rolled back") + " · " + project
        + " · " + c.cfg().name(), "pages." + action, r, d.isObject() ? Answers.pick(d, "id", "short_id",
        "environment", "url", "created_on", "latest_stage", "deployment_trigger") : d, null,
        List.of(action.equals("retry") ? "Follow the build with cloudflare_pages view logs." : "Production serves "
            + "this deployment now."));
  }
}
