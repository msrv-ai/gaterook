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
 * {@code cloudflare_workers}: the account's Workers ({@code GET …/workers/scripts}), one Worker's settings and
 * bindings ({@code …/settings}), its versions and deployments, a zone's Worker routes and the account's custom
 * domains. Script content, secrets ({@code …/secrets}) and the subdomain are never read; binding values that hold
 * secrets are masked ({@link Redactor}).
 */
final class WorkersRead {

  static final Set<String> ARGS = Set.of("connection", "view", "script", "version", "zone", "page", "perPage");
  static final Set<String> VIEWS = Set.of("scripts", "settings", "versions", "version", "deployments", "routes",
      "domains");

  private final String view;
  private final String script;
  private final String version;
  private final Zones.Ref zone;
  private final Integer page;
  private final Integer perPage;

  private WorkersRead(String view, String script, String version, Zones.Ref zone, Integer page, Integer perPage) {
    this.view = view;
    this.script = script;
    this.version = version;
    this.zone = zone;
    this.page = page;
    this.perPage = perPage;
  }

  static WorkersRead parse(ObjectNode args, CloudflareSettings s, String connection, JsonNode metadata)
      throws Refusal {
    Args.only(args, CloudflareTools.WORKERS, ARGS);
    String view = Args.choice(args, "view", VIEWS, "scripts");
    String script = Args.string(args, "script", Args.SCRIPT, "a Worker name");
    String version = Args.string(args, "version", Args.UUID, "a version ID");
    String z = Args.string(args, "zone", 253);
    Zones.Ref zone = z == null ? null : Zones.Ref.parse(z);
    if (zone != null) {
      Zones.allowed(zone, s, connection, metadata);
    }
    switch (view) {
      case "scripts" -> Args.notFor(args, view, "script", "version", "zone", "page", "perPage");
      case "settings" -> {
        need(script, view);
        Args.notFor(args, view, "version", "zone", "page", "perPage");
      }
      case "versions", "deployments" -> {
        need(script, view);
        Args.notFor(args, view, "version", "zone");
      }
      case "version" -> {
        need(script, view);
        if (version == null) {
          throw Refusal.badInput("View version needs version.", "Pass a version ID from view versions.");
        }
        Args.notFor(args, view, "zone", "page", "perPage");
      }
      case "routes" -> {
        if (zone == null) {
          throw Refusal.badInput("View routes needs zone.", "Pass the zone whose Worker routes to list.");
        }
        Args.notFor(args, view, "version", "page", "perPage");
      }
      default -> Args.notFor(args, view, "version", "page", "perPage");
    }
    return new WorkersRead(view, script, version, zone, Args.integer(args, "page", 1, 1_000),
        Args.integer(args, "perPage", 1, 100));
  }

  private static void need(String script, String view) throws Refusal {
    if (script == null) {
      throw Refusal.badInput("View " + view + " needs script.", "Pass the Worker's name, as view scripts lists it.");
    }
  }

  ToolResult run(Call c) throws ConnectorException {
    String a = c.settings().accountId();
    CloudflareApi api = c.api();
    ObjectNode s = Answers.envelope(c, view);
    List<String> notes = new ArrayList<>();
    String title;
    switch (view) {
      case "scripts" -> {
        CloudflareApi.Answer r = api.get(c, api.uri("accounts", a, "workers", "scripts"), "Workers");
        ArrayNode items = Answers.F.arrayNode();
        for (JsonNode w : r.result()) {
          items.add(Answers.pick(w, "id", "created_on", "modified_on", "compatibility_date", "compatibility_flags",
              "handlers", "usage_model", "logpush", "placement_mode", "has_assets", "has_modules", "tail_consumers",
              "observability", "tag"));
        }
        s.set("items", items);
        title = "Workers · " + c.cfg().name();
      }
      case "settings" -> {
        JsonNode r = api.get(c, api.uri("accounts", a, "workers", "scripts", script, "settings"), "Workers").result();
        s.set("item", r.deepCopy());
        title = "Worker " + script + " settings · " + c.cfg().name();
      }
      case "versions" -> {
        CloudflareApi.Answer r = api.get(c, api.uri(List.of("accounts", a, "workers", "scripts", script, "versions"),
            page(page, perPage)), "Workers");
        s.set("items", r.result().path("items").isArray() ? r.result().path("items").deepCopy()
            : Answers.F.arrayNode());
        Answers.paging(s, r.info());
        title = "Worker " + script + " versions · " + c.cfg().name();
        notes.add("Deploy one of them with cloudflare_workers_deploy; view deployments shows what serves traffic.");
      }
      case "version" -> {
        JsonNode r = api.get(c, api.uri("accounts", a, "workers", "scripts", script, "versions", version), "Workers")
            .result();
        s.set("item", r.deepCopy());
        title = "Worker " + script + " version " + version + " · " + c.cfg().name();
      }
      case "deployments" -> {
        CloudflareApi.Answer r = api.get(c, api.uri(List.of("accounts", a, "workers", "scripts", script,
            "deployments"), page(page, perPage)), "Workers");
        s.set("items", r.result().path("deployments").isArray() ? r.result().path("deployments").deepCopy()
            : Answers.F.arrayNode());
        Answers.paging(s, r.info());
        title = "Worker " + script + " deployments · " + c.cfg().name();
        notes.add("The first deployment is the one that serves traffic now.");
      }
      case "routes" -> {
        Zones.Zone z = c.zones().resolve(c, zone);
        JsonNode r = api.get(c, api.uri("zones", z.id(), "workers", "routes"), "Workers").result();
        ArrayNode items = Answers.F.arrayNode();
        for (JsonNode route : r) {
          if (script == null || script.equals(route.path("script").asString(""))) {
            items.add(Answers.pick(route, "id", "pattern", "script"));
          }
        }
        s.set("zone", z.node());
        s.set("items", items);
        title = "Worker routes · " + z.name() + " · " + c.cfg().name();
      }
      default -> {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("service", script);
        Zones.Zone z = zone == null ? null : c.zones().resolve(c, zone);
        if (z != null) {
          q.put("zone_id", z.id());
          s.set("zone", z.node());
        }
        JsonNode r = api.get(c, api.uri(List.of("accounts", a, "workers", "domains"), q), "Workers").result();
        ArrayNode items = Answers.F.arrayNode();
        int hidden = 0;
        for (JsonNode d : r) {
          String zoneName = CloudflareSettings.normalize(d.path("zone_name").asString(""));
          if (zoneName != null && !c.settings().zoneAllowed(zoneName)) {
            hidden++;
            continue;
          }
          items.add(Answers.pick(d, "id", "hostname", "service", "environment", "zone_id", "zone_name", "enabled",
              "previews_enabled"));
        }
        if (hidden > 0) {
          s.put("hiddenByConnection", hidden);
          notes.add(hidden + " custom " + (hidden == 1 ? "domain is" : "domains are") + " in zones outside the "
              + "connection's list.");
        }
        s.set("items", items);
        title = "Worker custom domains · " + c.cfg().name();
      }
    }
    return Answers.answer(c, title, s, notes);
  }

  private static Map<String, String> page(Integer page, Integer perPage) {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("page", page == null ? null : String.valueOf(page));
    q.put("per_page", String.valueOf(perPage == null ? 20 : perPage));
    return q;
  }
}
