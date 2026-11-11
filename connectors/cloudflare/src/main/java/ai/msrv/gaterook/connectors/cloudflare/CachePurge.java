package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.URI;
import java.net.URISyntaxException;
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
 * {@code cloudflare_cache_purge}: {@code POST /zones/{id}/purge_cache} with one kind of target — {@code files},
 * {@code tags}, {@code prefixes}, {@code hosts} — or {@code purge_everything}. Every URL, prefix and host must be in
 * the zone; the URLs are what Cloudflare purges, never anything GateRook requests.
 */
final class CachePurge extends Change {

  static final Set<String> ARGS = Set.of("connection", "zone", "files", "tags", "prefixes", "hosts", "everything");
  /** Cloudflare takes up to 30 targets per request on every plan (more on Enterprise). */
  static final int MAX = 30;
  private static final java.util.regex.Pattern HOST = java.util.regex.Pattern.compile(
      "(?=.{1,253}$)(?:[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  private final Zones.Ref zone;
  private final String kind;
  private final List<String> targets;

  private CachePurge(Zones.Ref zone, String kind, List<String> targets) {
    this.zone = zone;
    this.kind = kind;
    this.targets = targets;
  }

  static CachePurge parse(ObjectNode args, CloudflareSettings s, String connection, JsonNode metadata)
      throws Refusal {
    Args.only(args, CloudflareTools.CACHE_PURGE, ARGS);
    Zones.Ref ref = Zones.Ref.parse(Args.string(args, "zone", 253));
    Zones.allowed(ref, s, connection, metadata);
    List<String> kinds = new ArrayList<>();
    for (String k : List.of("files", "tags", "prefixes", "hosts", "everything")) {
      if (args.hasNonNull(k)) {
        kinds.add(k);
      }
    }
    if (kinds.size() != 1) {
      throw Refusal.badInput(kinds.isEmpty() ? "The call names nothing to purge."
          : "One kind of target per purge: " + String.join(" and ", kinds) + " were given.",
          "Pass files, tags, prefixes, hosts or everything.");
    }
    String kind = kinds.getFirst();
    if (kind.equals("everything")) {
      Boolean all = Args.bool(args, "everything");
      if (all == null || !all) {
        throw Refusal.badInput("everything must be true.", null);
      }
      return new CachePurge(ref, kind, List.of());
    }
    List<String> targets = Args.strings(args, kind, MAX, kind.equals("files") ? 2_048 : kind.equals("hosts") ? 253
        : 1_024);
    List<String> checked = new ArrayList<>();
    for (String t : targets) {
      switch (kind) {
        case "files" -> {
          URI u = url(t);
          checked.add(t);
          if (ref.name() != null) {
            inZone(u.getHost().toLowerCase(Locale.ROOT), ref.name(), t);
          }
        }
        case "prefixes" -> {
          if (t.contains("://") || t.startsWith("/")) {
            throw Refusal.badInput("Prefix \"" + Args.abbreviate(t) + "\" must be a host and a path without the "
                + "scheme, such as www.example.com/blog/.", null);
          }
          String host = t.contains("/") ? t.substring(0, t.indexOf('/')) : t;
          host(host, t);
          if (ref.name() != null) {
            inZone(host.toLowerCase(Locale.ROOT), ref.name(), t);
          }
          checked.add(t);
        }
        case "hosts" -> {
          host(t, t);
          if (ref.name() != null) {
            inZone(t.toLowerCase(Locale.ROOT), ref.name(), t);
          }
          checked.add(t.toLowerCase(Locale.ROOT));
        }
        default -> {
          if (t.contains(",")) {
            throw Refusal.badInput("Tag \"" + Args.abbreviate(t) + "\" has a comma: Cache-Tag values can't.", null);
          }
          checked.add(t);
        }
      }
    }
    return new CachePurge(ref, kind, List.copyOf(checked));
  }

  private static URI url(String t) throws Refusal {
    URI u;
    try {
      u = new URI(t);
    } catch (URISyntaxException e) {
      throw Refusal.badInput("\"" + Args.abbreviate(t) + "\" isn't a URL.", "Write the full URL, such as "
          + "https://www.example.com/css/site.css.");
    }
    if (!("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) || u.getHost() == null
        || u.getRawUserInfo() != null || u.getRawFragment() != null) {
      throw Refusal.badInput("\"" + Args.abbreviate(t) + "\" isn't an http or https URL of the zone.", "Write the "
          + "full URL, such as https://www.example.com/css/site.css.");
    }
    host(u.getHost(), t);
    return u;
  }

  private static void host(String host, String original) throws Refusal {
    if (!HOST.matcher(host.toLowerCase(Locale.ROOT)).matches()) {
      throw Refusal.badInput("\"" + Args.abbreviate(original) + "\" doesn't start with a host name.", null);
    }
  }

  private static void inZone(String host, String zone, String original) throws Refusal {
    if (!host.equals(zone) && !host.endsWith("." + zone)) {
      throw new Refusal("purge_outside_zone", "\"" + Args.abbreviate(original) + "\" isn't in zone " + zone + ".",
          "Purge it in its own zone.");
    }
  }

  @Override
  String tool() {
    return CloudflareTools.CACHE_PURGE;
  }

  @Override
  String what() {
    return "cache purge";
  }

  @Override
  Classification classification(String connection) {
    ObjectNode facts = Answers.F.objectNode();
    facts.put("cloudflare.zone", zone.text());
    facts.put("cloudflare.action", "cache.purge." + kind);
    return Classification.mutation(kind.equals("everything") ? "Purges everything cached for " + zone.text()
        : "Purges " + targets.size() + " " + kind + " from the cache of " + zone.text(), facts);
  }

  @Override
  Request request(Call c) throws ConnectorException {
    Zones.Zone z = c.zones().resolve(c, zone);
    ObjectNode body = Answers.F.objectNode();
    if (kind.equals("everything")) {
      body.put("purge_everything", true);
    } else {
      ArrayNode list = body.putArray(kind);
      for (String t : targets) {
        if (!kind.equals("tags")) {
          String host = switch (kind) {
            case "files" -> URI.create(t).getHost().toLowerCase(Locale.ROOT);
            case "prefixes" -> (t.contains("/") ? t.substring(0, t.indexOf('/')) : t).toLowerCase(Locale.ROOT);
            default -> t;
          };
          try {
            inZone(host, z.name(), t);
          } catch (Refusal e) {
            throw ConnectorException.denied(e.forAgent());
          }
        }
        list.add(t);
      }
    }
    return new Request("POST", c.api().uri("zones", z.id(), "purge_cache"), body);
  }

  @Override
  Context context(Call c, Request r) throws ConnectorException {
    Zones.Zone z = c.zones().resolve(c, zone);
    List<Field> fields = List.of(new Field("Zone", z.name() + " (" + z.id() + ")"),
        new Field("Purge", kind.equals("everything") ? "everything" : targets.size() + " " + kind));
    List<Field> impact = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (kind.equals("everything")) {
      impact.add(new Field("Cache", "every cached file of " + z.name() + " is dropped"));
      warnings.add("Purges everything cached for " + z.name() + ": until the cache fills again, every request goes "
          + "to the origin, which may slow it or take it down under load.");
    } else {
      impact.add(new Field("Cache", String.join(", ", targets.subList(0, Math.min(5, targets.size())))
          + (targets.size() > 5 ? " and " + (targets.size() - 5) + " more" : "")));
    }
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("cloudflare.zone", z.name());
    facets.put("cloudflare.action", "cache.purge." + kind);
    return new Context(z.name(), "Purge " + (kind.equals("everything") ? "everything" : targets.size() + " " + kind)
        + " from the cache of " + z.name(), fields, impact, warnings, facets, null);
  }

  @Override
  void stillCurrent(Call c, Request r, JsonNode state) {
    // A purge drops copies; nothing it was approved against can have changed.
  }

  @Override
  ToolResult answer(Call c, Request r, CloudflareApi.Answer a) throws ConnectorException {
    Zones.Zone z = c.zones().resolve(c, zone);
    return changed(c, "Cache purged · " + z.name() + " · " + c.cfg().name(), "cache.purge", r, a.result(), z.node(),
        List.of("Cloudflare purges within seconds across its network."));
  }
}
