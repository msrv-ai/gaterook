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
 * {@code cloudflare_tunnels}: the account's Cloudflare Tunnels with their status ({@code GET …/cfd_tunnel}), one tunnel
 * with its connectors ({@code …/cfd_tunnel/{id}} and {@code …/connections}), and a remotely managed tunnel's
 * configuration ({@code …/configurations}). The tunnel token ({@code …/token}) and management tokens are never
 * requested; a secret an answer carries anyway ({@code tunnel_secret}, {@code credentials_file}) is masked.
 */
final class TunnelsRead {

  static final Set<String> ARGS = Set.of("connection", "view", "tunnel", "status", "name", "includeDeleted", "page",
      "perPage");
  private static final String[] TUNNEL = {"id", "name", "status", "tun_type", "created_at", "deleted_at",
      "conns_active_at", "conns_inactive_at", "remote_config", "config_src", "metadata"};

  private final String view;
  private final String tunnel;
  private final String status;
  private final String name;
  private final boolean includeDeleted;
  private final Integer page;
  private final Integer perPage;

  private TunnelsRead(String view, String tunnel, String status, String name, boolean includeDeleted, Integer page,
      Integer perPage) {
    this.view = view;
    this.tunnel = tunnel;
    this.status = status;
    this.name = name;
    this.includeDeleted = includeDeleted;
    this.page = page;
    this.perPage = perPage;
  }

  static TunnelsRead parse(ObjectNode args) throws Refusal {
    Args.only(args, CloudflareTools.TUNNELS, ARGS);
    String view = Args.choice(args, "view", Set.of("tunnels", "tunnel", "configuration"), "tunnels");
    String tunnel = Args.string(args, "tunnel", Args.UUID, "a tunnel ID");
    if (view.equals("tunnels")) {
      Args.notFor(args, view, "tunnel");
    } else {
      if (tunnel == null) {
        throw Refusal.badInput("View " + view + " needs tunnel.", "Pass a tunnel ID from view tunnels.");
      }
      Args.notFor(args, view, "status", "name", "includeDeleted", "page", "perPage");
    }
    Boolean deleted = Args.bool(args, "includeDeleted");
    return new TunnelsRead(view, tunnel, Args.choice(args, "status", Set.of("inactive", "degraded", "healthy",
        "down"), null), Args.string(args, "name", 100), deleted != null && deleted,
        Args.integer(args, "page", 1, 1_000), Args.integer(args, "perPage", 1, 100));
  }

  ToolResult run(Call c) throws ConnectorException {
    String a = c.settings().accountId();
    CloudflareApi api = c.api();
    ObjectNode s = Answers.envelope(c, view);
    List<String> notes = new ArrayList<>();
    String title;
    switch (view) {
      case "tunnels" -> {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("is_deleted", includeDeleted ? null : "false");
        q.put("status", status);
        q.put("name", name);
        q.put("page", page == null ? null : String.valueOf(page));
        q.put("per_page", String.valueOf(perPage == null ? 50 : perPage));
        CloudflareApi.Answer r = api.get(c, api.uri(List.of("accounts", a, "cfd_tunnel"), q), "tunnels");
        ArrayNode items = Answers.F.arrayNode();
        for (JsonNode t : r.result()) {
          ObjectNode o = Answers.pick(t, TUNNEL);
          o.set("connections", connections(t.path("connections")));
          items.add(o);
        }
        s.set("items", items);
        Answers.paging(s, r.info());
        title = "Cloudflare Tunnels · " + c.cfg().name();
        notes.add("healthy: serving; degraded: some connections are down; down: no connection; inactive: never run.");
      }
      case "tunnel" -> {
        JsonNode t = api.get(c, api.uri("accounts", a, "cfd_tunnel", tunnel), "tunnels").result();
        ObjectNode item = Answers.pick(t, TUNNEL);
        JsonNode connectors = api.get(c, api.uri("accounts", a, "cfd_tunnel", tunnel, "connections"), "tunnels")
            .result();
        ArrayNode list = item.putArray("connectors");
        for (JsonNode k : connectors) {
          ObjectNode o = Answers.pick(k, "id", "version", "arch", "run_at", "config_version", "features");
          o.set("connections", connections(k.path("conns")));
          list.add(o);
        }
        s.set("item", item);
        title = "Cloudflare Tunnel " + t.path("name").asString(tunnel) + " · " + c.cfg().name();
      }
      default -> {
        JsonNode r = api.get(c, api.uri("accounts", a, "cfd_tunnel", tunnel, "configurations"), "tunnels").result();
        s.set("item", r.deepCopy());
        title = "Cloudflare Tunnel configuration · " + tunnel + " · " + c.cfg().name();
        notes.add("Only a remotely managed tunnel (config_src cloudflare) has a configuration here; a locally "
            + "managed one keeps it in cloudflared's config file.");
      }
    }
    return Answers.answer(c, title, s, notes);
  }

  /** A tunnel's connections: where each runs and since when; the origin address of a connector is left out. */
  private static ArrayNode connections(JsonNode list) {
    ArrayNode out = Answers.F.arrayNode();
    for (JsonNode k : list) {
      out.add(Answers.pick(k, "id", "colo_name", "client_id", "client_version", "opened_at",
          "is_pending_reconnect"));
    }
    return out;
  }
}
