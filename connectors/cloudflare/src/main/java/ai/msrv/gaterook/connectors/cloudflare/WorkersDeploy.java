package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_workers_deploy}: {@code POST …/workers/scripts/{name}/deployments} with the {@code percentage}
 * strategy and versions that already exist — a rollback or a promotion at 100 percent, or a split of two for a gradual
 * rollout. Nothing is uploaded. The approver sees the deployment that serves traffic now and each version's number and
 * message; the request runs only if that deployment is still the latest.
 */
final class WorkersDeploy extends Change {

  static final Set<String> ARGS = Set.of("connection", "script", "versions", "message");

  record Share(String version, BigDecimal percentage) {}

  private final String script;
  private final List<Share> versions;
  private final String message;

  private WorkersDeploy(String script, List<Share> versions, String message) {
    this.script = script;
    this.versions = versions;
    this.message = message;
  }

  static WorkersDeploy parse(ObjectNode args) throws Refusal {
    Args.only(args, CloudflareTools.WORKERS_DEPLOY, ARGS);
    String script = Args.required(args, "script", Args.SCRIPT, "a Worker name", "Pass the Worker's name.");
    JsonNode list = args.get("versions");
    if (list == null || !list.isArray() || list.isEmpty() || list.size() > 2) {
      throw Refusal.badInput("versions must list one or two versions.", "One at 100 rolls back or promotes it; two "
          + "split traffic.");
    }
    List<Share> shares = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (JsonNode v : list) {
      if (!(v instanceof ObjectNode o)) {
        throw Refusal.badInput("Each version must be {version, percentage}.", null);
      }
      Args.only(o, "a version", Set.of("version", "percentage"));
      String id = Args.required(o, "version", Args.UUID, "a version ID", "Pass a version ID from cloudflare_workers "
          + "view versions.").toLowerCase(Locale.ROOT);
      JsonNode p = o.get("percentage");
      if (p == null || !p.isNumber()) {
        throw Refusal.badInput("Version " + id + " needs its percentage.", null);
      }
      BigDecimal pct = p.decimalValue().stripTrailingZeros();
      if (pct.compareTo(new BigDecimal("0.01")) < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0
          || pct.scale() > 2) {
        throw Refusal.badInput("The percentage of version " + id + " must be from 0.01 to 100, in hundredths.",
            null);
      }
      for (Share s : shares) {
        if (s.version().equals(id)) {
          throw Refusal.badInput("Version " + id + " is listed twice.", null);
        }
      }
      shares.add(new Share(id, pct));
      total = total.add(pct);
    }
    if (total.compareTo(BigDecimal.valueOf(100)) != 0) {
      throw Refusal.badInput("The percentages add up to " + total.toPlainString() + "; they must add up to 100.",
          null);
    }
    String message = args.hasNonNull("message") ? Args.string(args, "message", 1_000) : null;
    return new WorkersDeploy(script, List.copyOf(shares), message);
  }

  @Override
  String tool() {
    return CloudflareTools.WORKERS_DEPLOY;
  }

  @Override
  String what() {
    return "Workers";
  }

  @Override
  Classification classification(String connection) {
    ObjectNode facts = Answers.F.objectNode();
    facts.put("cloudflare.script", script);
    facts.put("cloudflare.action", "workers.deploy");
    return Classification.mutation("Deploys " + split() + " of Worker " + script, facts);
  }

  private String split() {
    List<String> parts = new ArrayList<>();
    for (Share s : versions) {
      parts.add(s.version() + " at " + s.percentage().toPlainString() + "%");
    }
    return String.join(" and ", parts);
  }

  @Override
  Request request(Call c) {
    ObjectNode body = Answers.F.objectNode();
    body.put("strategy", "percentage");
    ArrayNode list = body.putArray("versions");
    for (Share s : versions) {
      ObjectNode v = list.addObject();
      v.put("version_id", s.version());
      if (s.percentage().scale() <= 0) {
        v.put("percentage", s.percentage().intValueExact());
      } else {
        v.put("percentage", s.percentage().doubleValue());
      }
    }
    if (message != null) {
      body.putObject("annotations").put("workers/message", message);
    }
    return new Request("POST", c.api().uri("accounts", c.settings().accountId(), "workers", "scripts", script,
        "deployments"), body);
  }

  @Override
  Context context(Call c, Request r) throws ConnectorException {
    JsonNode current = latest(c);
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("Worker", script));
    List<Field> impact = new ArrayList<>();
    impact.add(new Field("Before", current == null ? "no deployment yet" : describe(current.path("versions"))
        + " (deployment " + current.path("id").asString("?") + ", " + current.path("created_on").asString("")
        + ")"));
    List<String> after = new ArrayList<>();
    for (Share s : versions) {
      JsonNode v;
      try {
        v = c.api().get(c, c.api().uri("accounts", c.settings().accountId(), "workers", "scripts", script,
            "versions", s.version()), "Workers").result();
      } catch (CloudflareApi.CloudflareException e) {
        if (e.status == 404) {
          throw ConnectorException.badInput("Version " + s.version() + " isn't a version of Worker " + script
              + ". List them with cloudflare_workers view versions.");
        }
        throw e;
      }
      JsonNode meta = v.path("metadata");
      String what = "version " + (v.path("number").isNumber() ? "#" + v.path("number").asInt() + " " : "")
          + s.version() + " at " + s.percentage().toPlainString() + "%";
      String note = v.path("annotations").path("workers/message").asString("");
      if (meta.path("created_on").isString()) {
        what += " (created " + meta.path("created_on").asString() + (note.isBlank() ? "" : ": "
            + Args.abbreviate(note)) + ")";
      }
      after.add(what);
    }
    impact.add(new Field("After", String.join("; ", after)));
    List<String> warnings = new ArrayList<>();
    warnings.add("Changes which code serves the Worker's traffic within seconds.");
    if (versions.size() == 2) {
      warnings.add("Splits traffic between two versions: requests may see either one.");
    }
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("cloudflare.script", script);
    facets.put("cloudflare.action", "workers.deploy");
    ObjectNode state = Answers.F.objectNode();
    if (current == null) {
      state.putNull("deployment");
    } else {
      state.put("deployment", current.path("id").asString(""));
    }
    return new Context("Worker " + script, "Deploy " + split() + " of Worker " + script, fields, impact, warnings,
        facets, state);
  }

  /** The deployment that serves traffic now, or {@code null} for a Worker without one. */
  private JsonNode latest(Call c) throws ConnectorException {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("per_page", "1");
    JsonNode list = c.api().get(c, c.api().uri(List.of("accounts", c.settings().accountId(), "workers", "scripts",
        script, "deployments"), q), "Workers").result().path("deployments");
    return list.isArray() && !list.isEmpty() ? list.get(0) : null;
  }

  private static String describe(JsonNode versions) {
    List<String> parts = new ArrayList<>();
    for (JsonNode v : versions) {
      parts.add("version " + v.path("version_id").asString("?") + " at " + v.path("percentage").asString("?") + "%");
    }
    return parts.isEmpty() ? "no versions" : String.join("; ", parts);
  }

  @Override
  void stillCurrent(Call c, Request r, JsonNode state) throws ConnectorException {
    if (state == null || !state.has("deployment")) {
      throw ConnectorException.denied("GateRook has no record of what this deployment was approved against. Not "
          + "executed: call cloudflare_workers_deploy again.");
    }
    JsonNode current = latest(c);
    String now = current == null ? null : current.path("id").asString("");
    String then = state.path("deployment").isNull() ? null : state.path("deployment").asString("");
    if (now == null ? then != null : !now.equals(then)) {
      throw ConnectorException.denied("Worker " + script + " was deployed again after the approval (now "
          + (now == null ? "no deployment" : "deployment " + now) + "). Not executed: check view deployments and "
          + "ask for a new approval.");
    }
  }

  @Override
  ToolResult answer(Call c, Request r, CloudflareApi.Answer a) {
    return changed(c, "Worker " + script + " deployed · " + c.cfg().name(), "workers.deploy", r, a.result(), null,
        List.of("Cloudflare routes the Worker's traffic to the new deployment within seconds. Check it with "
            + "cloudflare_workers_logs."));
  }
}
