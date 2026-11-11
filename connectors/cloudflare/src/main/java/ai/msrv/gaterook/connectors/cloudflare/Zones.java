package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Zones as agents name them — {@code example.com} or a 32-character ID — and the zone Cloudflare says that is. Every
 * zone a call reaches is checked here before any request about it: it must belong to the connection's account and be
 * one of its zones. Resolutions are kept ten minutes per connection version, so an approved change resolves to the
 * same zone it was approved for, or is refused.
 */
final class Zones {

  static final Duration TTL = Duration.ofMinutes(10);
  static final int MAX = 4_096;

  /** A zone as agents name it: by ID or by (normalized) name, exactly one of them. */
  record Ref(String id, String name) {

    static Ref parse(String text) throws Refusal {
      if (text == null) {
        throw Refusal.badInput("The call names no zone.", "Pass zone: a zone name such as example.com, or its ID.");
      }
      String t = text.strip();
      if (Args.HEX32.matcher(t).matches()) {
        return new Ref(t, null);
      }
      String name = CloudflareSettings.normalize(t);
      if (name == null) {
        throw Refusal.badInput("\"" + Args.abbreviate(text) + "\" isn't a zone name or a zone ID.",
            "Pass a name such as example.com, or the 32-character ID cloudflare_zones gives.");
      }
      return new Ref(null, name);
    }

    String text() {
      return id != null ? id : name;
    }
  }

  /** The zone Cloudflare answered for a reference. */
  record Zone(String id, String name, String accountId, String status) {

    ObjectNode node() {
      return JsonNodeFactory.instance.objectNode().put("id", id).put("name", name);
    }
  }

  private record Entry(Zone zone, long atNanos) {}

  private final Map<String, Entry> cache = new ConcurrentHashMap<>();

  /**
   * Refuses, without I/O, a zone that the connection's list of zones doesn't name. A zone given by ID is checked when
   * it is resolved, unless the last Test connection recorded its name.
   */
  static void allowed(Ref ref, CloudflareSettings s, String connection, JsonNode metadata) throws Refusal {
    String name = ref.name();
    if (name == null && metadata != null) {
      for (JsonNode z : metadata.path("zones")) {
        if (ref.id().equals(z.path("id").asString(""))) {
          name = z.path("name").asString(null);
        }
      }
    }
    if (name != null && !s.zoneAllowed(name)) {
      throw new Refusal("zone_not_allowed", name + " isn't one of the zones of " + connection + ".",
          "Its zones: " + s.zonesText() + ".");
    }
  }

  /** The zone of a reference, checked against the connection's account and zones; looked up when not known. */
  Zone resolve(Call c, Ref ref) throws ConnectorException {
    String key = c.cfg().id() + "|" + c.cfg().version() + "|" + (ref.id() != null ? "id:" + ref.id()
        : "name:" + ref.name());
    Entry e = cache.get(key);
    Zone zone = e != null && System.nanoTime() - e.atNanos() <= TTL.toNanos() ? e.zone() : null;
    if (zone == null) {
      zone = ref.id() != null ? byId(c, ref.id()) : byName(c, ref.name());
      if (cache.size() >= MAX) {
        cache.clear();
      }
      cache.put(key, new Entry(zone, System.nanoTime()));
    }
    if (!c.settings().accountId().equals(zone.accountId())) {
      throw ConnectorException.denied("Zone " + zone.name() + " belongs to another Cloudflare account, not "
          + c.settings().accountId() + " of " + c.cfg().name() + ".");
    }
    if (!c.settings().zoneAllowed(zone.name())) {
      throw ConnectorException.denied(zone.name() + " isn't one of the zones of " + c.cfg().name() + ". Its zones: "
          + c.settings().zonesText() + ".");
    }
    return zone;
  }

  private static Zone byId(Call c, String id) throws ConnectorException {
    JsonNode z = c.api().get(c, c.api().uri("zones", id), "zones").result();
    return zone(z, id);
  }

  private static Zone byName(Call c, String name) throws ConnectorException {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("name", name);
    q.put("account.id", c.settings().accountId());
    q.put("per_page", "5");
    URI uri = c.api().uri(List.of("zones"), q);
    JsonNode list = c.api().get(c, uri, "zones").result();
    for (JsonNode z : list) {
      if (name.equalsIgnoreCase(z.path("name").asString(""))) {
        return zone(z, z.path("id").asString(""));
      }
    }
    throw ConnectorException.resourceError("Cloudflare shows no zone " + name + " in account "
        + c.settings().accountId() + " to this token. Check the name, or give the token Zone · Zone · Read on it.",
        null);
  }

  private static Zone zone(JsonNode z, String id) throws ConnectorException {
    String zid = z.path("id").asString("");
    String name = z.path("name").asString("");
    if (!Args.HEX32.matcher(zid).matches() || !zid.equals(id) || CloudflareSettings.normalize(name) == null) {
      throw ConnectorException.resourceError("Cloudflare answered with a zone GateRook can't read.", null);
    }
    return new Zone(zid, CloudflareSettings.normalize(name), z.path("account").path("id").asString(""),
        z.path("status").asString(""));
  }

  void forget(String connectionId) {
    cache.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
  }
}
