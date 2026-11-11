package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_zones}: the account's zones, one page of 50 ({@code GET /zones?account.id=…}), the ones outside
 * the connection's list hidden and counted; or one zone ({@code GET /zones/{id}}) with its DNSSEC status
 * ({@code GET /zones/{id}/dnssec}).
 */
final class ZonesRead {

  static final Set<String> ARGS = Set.of("connection", "zone", "status", "page");
  static final Set<String> STATUSES = Set.of("active", "pending", "initializing", "moved");
  static final int PER_PAGE = 50;

  private final Zones.Ref zone;
  private final String status;
  private final Integer page;

  private ZonesRead(Zones.Ref zone, String status, Integer page) {
    this.zone = zone;
    this.status = status;
    this.page = page;
  }

  static ZonesRead parse(ObjectNode args, CloudflareSettings s, String connection, JsonNode metadata)
      throws Refusal {
    Args.only(args, CloudflareTools.ZONES, ARGS);
    String z = Args.string(args, "zone", 253);
    Zones.Ref ref = null;
    if (z != null) {
      ref = Zones.Ref.parse(z);
      Zones.allowed(ref, s, connection, metadata);
      Args.notFor(args, "zone", "status", "page");
    }
    return new ZonesRead(ref, Args.choice(args, "status", STATUSES, null), Args.integer(args, "page", 1, 1_000));
  }

  ToolResult run(Call c) throws ConnectorException {
    if (zone != null) {
      return one(c);
    }
    Map<String, String> q = new LinkedHashMap<>();
    q.put("account.id", c.settings().accountId());
    q.put("status", status);
    q.put("order", "name");
    q.put("page", page == null ? null : String.valueOf(page));
    q.put("per_page", String.valueOf(PER_PAGE));
    CloudflareApi.Answer a = c.api().get(c, c.api().uri(List.of("zones"), q), "zones");
    ArrayNode items = Answers.F.arrayNode();
    int hidden = 0;
    for (JsonNode z : a.result()) {
      String name = CloudflareSettings.normalize(z.path("name").asString(""));
      if (name == null || !c.settings().zoneAllowed(name)
          || !c.settings().accountId().equals(z.path("account").path("id").asString(""))) {
        hidden++;
        continue;
      }
      items.add(summary(z));
    }
    ObjectNode s = Answers.envelope(c, "zones");
    s.set("items", items);
    Answers.paging(s, a.info());
    List<String> notes = new ArrayList<>();
    if (hidden > 0) {
      s.put("hiddenByConnection", hidden);
      notes.add(hidden + (hidden == 1 ? " zone is" : " zones are") + " not among the zones of " + c.cfg().name()
          + ".");
    }
    if (s.path("totalPages").asInt(1) > s.path("page").asInt(1)) {
      notes.add("More zones: call again with page " + (s.path("page").asInt(1) + 1) + ".");
    }
    return Answers.answer(c, "Zones · " + c.cfg().name(), s, notes);
  }

  private ToolResult one(Call c) throws ConnectorException {
    Zones.Zone z = c.zones().resolve(c, zone);
    JsonNode full = c.api().get(c, c.api().uri("zones", z.id()), "zones").result();
    ObjectNode item = Answers.pick(full, "id", "name", "status", "paused", "type", "development_mode",
        "name_servers", "original_name_servers", "original_registrar", "original_dnshost", "vanity_name_servers",
        "account", "created_on", "modified_on", "activated_on", "verification_key", "cname_suffix");
    if (full.path("plan").path("name").isString()) {
      item.put("plan", full.path("plan").path("name").asString());
    }
    List<String> notes = new ArrayList<>();
    try {
      JsonNode dnssec = c.api().get(c, c.api().uri("zones", z.id(), "dnssec"), "DNS").result();
      item.set("dnssec", Answers.pick(dnssec, "status", "algorithm", "digest_type", "key_tag", "ds", "modified_on",
          "dnssec_multi_signer", "dnssec_presigned"));
    } catch (CloudflareApi.CloudflareException e) {
      if (e.status != 403) {
        throw e;
      }
      notes.add("DNSSEC status is hidden from this token: it needs Zone · DNS · Read.");
    }
    ObjectNode s = Answers.envelope(c, "zone");
    s.set("zone", z.node());
    s.set("item", item);
    return Answers.answer(c, "Zone " + z.name() + " · " + c.cfg().name(), s, notes);
  }

  static ObjectNode summary(JsonNode z) {
    ObjectNode o = Answers.pick(z, "id", "name", "status", "paused", "type", "development_mode", "name_servers",
        "created_on", "modified_on", "activated_on");
    if (z.path("plan").path("name").isString()) {
      o.put("plan", z.path("plan").path("name").asString());
    }
    return o;
  }
}
