package com.gaterook.connectors.cloudflare;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_pages}: the account's Pages projects, one project, its deployments, one deployment and the build
 * log of a deployment ({@code …/history/logs}, its last lines within the environment's line limit). Upload tokens are
 * never read; secret environment variables and the Web Analytics token are masked ({@link Redactor}).
 */
final class PagesRead {

  static final Set<String> ARGS = Set.of("connection", "view", "project", "deployment", "environment", "page",
      "perPage");
  static final Set<String> VIEWS = Set.of("projects", "project", "deployments", "deployment", "logs");
  private static final String[] DEPLOYMENT = {"id", "short_id", "environment", "url", "aliases", "created_on",
      "modified_on", "latest_stage", "stages", "deployment_trigger", "is_skipped", "production_branch", "source"};

  private final String view;
  private final String project;
  private final String deployment;
  private final String environment;
  private final Integer page;
  private final Integer perPage;

  private PagesRead(String view, String project, String deployment, String environment, Integer page,
      Integer perPage) {
    this.view = view;
    this.project = project;
    this.deployment = deployment;
    this.environment = environment;
    this.page = page;
    this.perPage = perPage;
  }

  static PagesRead parse(ObjectNode args) throws Refusal {
    Args.only(args, CloudflareTools.PAGES, ARGS);
    String view = Args.choice(args, "view", VIEWS, "projects");
    String project = Args.string(args, "project", Args.PROJECT, "a Pages project name");
    String deployment = Args.string(args, "deployment", Args.UUID, "a deployment ID");
    if (!view.equals("projects") && project == null) {
      throw Refusal.badInput("View " + view + " needs project.", "Pass the project's name, as view projects lists "
          + "it.");
    }
    if ((view.equals("deployment") || view.equals("logs")) && deployment == null) {
      throw Refusal.badInput("View " + view + " needs deployment.", "Pass a deployment ID from view deployments.");
    }
    switch (view) {
      case "projects" -> Args.notFor(args, view, "project", "deployment", "environment");
      case "project" -> Args.notFor(args, view, "deployment", "environment", "page", "perPage");
      case "deployments" -> Args.notFor(args, view, "deployment");
      default -> Args.notFor(args, view, "environment", "page", "perPage");
    }
    return new PagesRead(view, project, deployment, Args.choice(args, "environment", Set.of("production", "preview"),
        null), Args.integer(args, "page", 1, 1_000), Args.integer(args, "perPage", 1, 25));
  }

  ToolResult run(Call c) throws ConnectorException {
    String a = c.settings().accountId();
    CloudflareApi api = c.api();
    ObjectNode s = Answers.envelope(c, view);
    List<String> notes = new ArrayList<>();
    String title;
    switch (view) {
      case "projects" -> {
        CloudflareApi.Answer r = api.get(c, api.uri(List.of("accounts", a, "pages", "projects"), page()), "Pages");
        ArrayNode items = Answers.F.arrayNode();
        for (JsonNode p : r.result()) {
          ObjectNode o = Answers.pick(p, "id", "name", "subdomain", "domains", "production_branch", "created_on");
          if (p.path("source").isObject()) {
            o.set("source", Answers.pick(p.path("source"), "type"));
            JsonNode cfg = p.path("source").path("config");
            if (cfg.isObject()) {
              ((ObjectNode) o.get("source")).set("config", Answers.pick(cfg, "owner", "repo_name",
                  "production_branch", "production_deployments_enabled"));
            }
          }
          if (p.path("latest_deployment").isObject()) {
            o.set("latest_deployment", Answers.pick(p.path("latest_deployment"), "id", "environment", "url",
                "created_on", "latest_stage"));
          }
          if (p.path("canonical_deployment").isObject()) {
            o.set("canonical_deployment", Answers.pick(p.path("canonical_deployment"), "id", "environment", "url",
                "created_on", "latest_stage"));
          }
          items.add(o);
        }
        s.set("items", items);
        Answers.paging(s, r.info());
        title = "Pages projects · " + c.cfg().name();
      }
      case "project" -> {
        JsonNode r = api.get(c, api.uri("accounts", a, "pages", "projects", project), "Pages").result();
        s.set("item", r.deepCopy());
        title = "Pages project " + project + " · " + c.cfg().name();
      }
      case "deployments" -> {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("env", environment);
        q.putAll(page());
        CloudflareApi.Answer r = api.get(c, api.uri(List.of("accounts", a, "pages", "projects", project,
            "deployments"), q), "Pages");
        ArrayNode items = Answers.F.arrayNode();
        for (JsonNode d : r.result()) {
          items.add(Answers.pick(d, DEPLOYMENT));
        }
        s.set("items", items);
        Answers.paging(s, r.info());
        title = "Pages deployments · " + project + " · " + c.cfg().name();
        notes.add("Newest first. Retry one, or roll production back to one, with cloudflare_pages_deployment.");
      }
      case "deployment" -> {
        JsonNode r = api.get(c, api.uri("accounts", a, "pages", "projects", project, "deployments", deployment),
            "Pages").result();
        s.set("item", r.deepCopy());
        title = "Pages deployment " + deployment + " · " + c.cfg().name();
      }
      default -> {
        JsonNode r = api.get(c, api.uri("accounts", a, "pages", "projects", project, "deployments", deployment,
            "history", "logs"), "Pages").result();
        JsonNode lines = r.path("data");
        int keep = Math.max(1, c.ctx().limits().maxLines());
        ArrayNode items = Answers.F.arrayNode();
        int from = Math.max(0, lines.size() - keep);
        for (int i = from; i < lines.size(); i++) {
          items.add(Answers.pick(lines.get(i), "ts", "line"));
        }
        if (from > 0) {
          notes.add("The log has " + lines.size() + " lines; the last " + items.size() + " are shown.");
        }
        s.put("totalCount", lines.size());
        s.set("items", items);
        title = "Pages build log · " + deployment + " · " + c.cfg().name();
      }
    }
    return Answers.answer(c, title, s, notes);
  }

  private Map<String, String> page() {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("page", page == null ? null : String.valueOf(page));
    q.put("per_page", String.valueOf(perPage == null ? 10 : perPage));
    return q;
  }
}
