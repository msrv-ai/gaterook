package ai.msrv.gaterook.connectors.cloudflare;

import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code cloudflare_dns_change}: creates ({@code POST …/dns_records}), updates only the given fields of
 * ({@code PATCH …/dns_records/{id}}) or deletes ({@code DELETE …/dns_records/{id}}) one record. The approver sees the
 * record before and after; an update or delete runs only if the record's {@code modified_on} is still the one shown.
 */
final class DnsChange extends Change {

  static final Set<String> ARGS = Set.of("connection", "zone", "action", "record", "type", "name", "content", "data",
      "ttl", "proxied", "priority", "comment", "tags");
  /** Types whose value is {@code content}; the others take {@code data}. */
  static final Set<String> CONTENT_TYPES = Set.of("A", "AAAA", "CNAME", "MX", "NS", "PTR", "TXT", "OPENPGPKEY");
  static final Set<String> HOST_TYPES = Set.of("CNAME", "MX", "NS", "PTR");
  static final Set<String> PROXIABLE = Set.of("A", "AAAA", "CNAME");
  /** The fields of {@code data} each type takes, from Cloudflare's schema. */
  static final Map<String, Set<String>> DATA_FIELDS = Map.ofEntries(
      Map.entry("CAA", Set.of("flags", "tag", "value")),
      Map.entry("CERT", Set.of("algorithm", "certificate", "key_tag", "type")),
      Map.entry("DNSKEY", Set.of("algorithm", "flags", "protocol", "public_key")),
      Map.entry("DS", Set.of("algorithm", "digest", "digest_type", "key_tag")),
      Map.entry("HTTPS", Set.of("priority", "target", "value")),
      Map.entry("LOC", Set.of("altitude", "lat_degrees", "lat_direction", "lat_minutes", "lat_seconds",
          "long_degrees", "long_direction", "long_minutes", "long_seconds", "precision_horz", "precision_vert",
          "size")),
      Map.entry("NAPTR", Set.of("flags", "order", "preference", "regex", "replacement", "service")),
      Map.entry("SMIMEA", Set.of("certificate", "matching_type", "selector", "usage")),
      Map.entry("SRV", Set.of("port", "priority", "target", "weight")),
      Map.entry("SSHFP", Set.of("algorithm", "fingerprint", "type")),
      Map.entry("SVCB", Set.of("priority", "target", "value")),
      Map.entry("TLSA", Set.of("certificate", "matching_type", "selector", "usage")),
      Map.entry("URI", Set.of("target", "weight")));
  private static final java.util.regex.Pattern HOST = java.util.regex.Pattern.compile(
      "(?=.{1,253}$)(?:[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.?");
  private static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("[^:\\s]{1,100}(:\\S.{0,98})?");

  private final Zones.Ref zone;
  private final String action;
  private final String record;
  private final String type;
  private final String name;
  private final String content;
  private final ObjectNode data;
  private final Integer ttl;
  private final Boolean proxied;
  private final Integer priority;
  private final String comment;
  private final List<String> tags;

  private DnsChange(Zones.Ref zone, String action, String record, String type, String name, String content,
      ObjectNode data, Integer ttl, Boolean proxied, Integer priority, String comment, List<String> tags) {
    this.zone = zone;
    this.action = action;
    this.record = record;
    this.type = type;
    this.name = name;
    this.content = content;
    this.data = data;
    this.ttl = ttl;
    this.proxied = proxied;
    this.priority = priority;
    this.comment = comment;
    this.tags = tags;
  }

  static DnsChange parse(ObjectNode args, CloudflareSettings s, String connection, JsonNode metadata)
      throws Refusal {
    Args.only(args, CloudflareTools.DNS_CHANGE, ARGS);
    Zones.Ref ref = Zones.Ref.parse(Args.string(args, "zone", 253));
    Zones.allowed(ref, s, connection, metadata);
    String action = Args.choice(args, "action", Set.of("create", "update", "delete"), null);
    if (action == null) {
      throw Refusal.badInput("The call names no action.", "Pass create, update or delete.");
    }
    String record = Args.string(args, "record", Args.HEX32, "a DNS record ID (32 characters, 0-9 and a-f)");
    String type = Args.choice(args, "type", DnsRead.TYPES, null);
    String name = Args.string(args, "name", 253);
    if (name != null) {
      DnsNames.check(name);
    }
    String content = Args.string(args, "content", 4_096);
    ObjectNode data = data(args.get("data"), type);
    Integer ttl = Args.integer(args, "ttl", 1, 86_400);
    if (ttl != null && ttl != 1 && ttl < 30) {
      throw Refusal.badInput("ttl must be 1 (automatic) or 30 to 86400 seconds.", null);
    }
    Boolean proxied = Args.bool(args, "proxied");
    Integer priority = Args.integer(args, "priority", 0, 65_535);
    String comment = args.hasNonNull("comment") ? Args.string(args, "comment", 500) : null;
    List<String> tags = Args.strings(args, "tags", 20, 200);
    if (tags != null) {
      for (String t : tags) {
        if (!TAG.matcher(t).matches()) {
          throw Refusal.badInput("Tag \"" + Args.abbreviate(t) + "\" isn't name:value.", null);
        }
      }
    }
    boolean anyField = type != null || name != null || content != null || data != null || ttl != null
        || proxied != null || priority != null || comment != null || tags != null;
    switch (action) {
      case "create" -> {
        if (record != null) {
          throw Refusal.badInput("create makes a new record and takes no record ID.", "Use update to change one.");
        }
        if (type == null || name == null) {
          throw Refusal.badInput("create needs type and name.", null);
        }
        if (CONTENT_TYPES.contains(type) ? content == null : data == null) {
          throw Refusal.badInput("A " + type + " record needs " + (CONTENT_TYPES.contains(type) ? "content" : "data")
              + ".", CONTENT_TYPES.contains(type) ? null : "Its fields: " + String.join(", ",
              DATA_FIELDS.get(type).stream().sorted().toList()) + ".");
        }
      }
      case "update" -> {
        if (record == null) {
          throw Refusal.badInput("update needs record: the ID of the record to change.", "Find it with "
              + "cloudflare_dns_records.");
        }
        if (!anyField) {
          throw Refusal.badInput("update names nothing to change.", "Pass the fields to change, such as content "
              + "or ttl.");
        }
      }
      default -> {
        if (record == null) {
          throw Refusal.badInput("delete needs record: the ID of the record to delete.", "Find it with "
              + "cloudflare_dns_records.");
        }
        if (anyField) {
          throw Refusal.badInput("delete takes only zone and record.", null);
        }
      }
    }
    if (type != null) {
      if (CONTENT_TYPES.contains(type) && data != null) {
        throw Refusal.badInput("A " + type + " record takes content, not data.", null);
      }
      if (!CONTENT_TYPES.contains(type) && content != null) {
        throw Refusal.badInput("A " + type + " record takes data, not content.", "Its fields: " + String.join(", ",
            DATA_FIELDS.get(type).stream().sorted().toList()) + ".");
      }
      if (proxied != null && proxied && !PROXIABLE.contains(type)) {
        throw Refusal.badInput("Only A, AAAA and CNAME records can be proxied.", null);
      }
      if (priority != null && !type.equals("MX") && !type.equals("URI")) {
        throw Refusal.badInput("priority applies to MX and URI records; " + type + " keeps it in data.", null);
      }
      if (content != null) {
        content(type, content);
      }
    }
    return new DnsChange(ref, action, record, type, name, content, data, ttl, proxied, priority, comment, tags);
  }

  private static ObjectNode data(JsonNode n, String type) throws Refusal {
    if (n == null || n.isNull()) {
      return null;
    }
    if (!(n instanceof ObjectNode o) || o.isEmpty() || o.size() > 12) {
      throw Refusal.badInput("data must be an object of the record's fields.", null);
    }
    Set<String> allowed = type == null ? null : DATA_FIELDS.get(type);
    ObjectNode out = Answers.F.objectNode();
    for (String k : new java.util.TreeSet<>(o.propertyNames())) {
      JsonNode v = o.get(k);
      if (allowed != null && !allowed.contains(k)) {
        throw Refusal.badInput("data." + Args.abbreviate(k) + " isn't a field of a " + type + " record.", "Its fields: "
            + String.join(", ", allowed.stream().sorted().toList()) + ".");
      }
      if (!k.matches("[a-z_]{1,32}") || !(v.isString() || v.isNumber()) || v.isString() && (v.asString().length()
          > 4_096 || !ai.msrv.gaterook.connectors.support.OutputSanitizer.isClean(v.asString()))) {
        throw Refusal.badInput("data." + Args.abbreviate(k) + " must be text or a number.", null);
      }
      out.set(k, v.deepCopy());
    }
    return out;
  }

  /** The content of A, AAAA and host-name types, checked as Cloudflare would before anything is sent. */
  private static void content(String type, String content) throws Refusal {
    switch (type) {
      case "A" -> {
        try {
          Inet4Address.ofLiteral(content);
        } catch (IllegalArgumentException e) {
          throw Refusal.badInput("\"" + Args.abbreviate(content) + "\" isn't an IPv4 address.", null);
        }
      }
      case "AAAA" -> {
        try {
          Inet6Address.ofLiteral(content);
        } catch (IllegalArgumentException e) {
          throw Refusal.badInput("\"" + Args.abbreviate(content) + "\" isn't an IPv6 address.", null);
        }
      }
      default -> {
        if (HOST_TYPES.contains(type) && !HOST.matcher(content).matches()) {
          throw Refusal.badInput("\"" + Args.abbreviate(content) + "\" isn't a host name, which a " + type
              + " record points to.", null);
        }
      }
    }
  }

  @Override
  String tool() {
    return CloudflareTools.DNS_CHANGE;
  }

  @Override
  String what() {
    return "DNS";
  }

  @Override
  Classification classification(String connection) {
    ObjectNode facts = Answers.F.objectNode();
    facts.put("cloudflare.zone", zone.text());
    facts.put("cloudflare.action", "dns." + action);
    if (record != null) {
      facts.put("cloudflare.record", record);
    }
    String what = switch (action) {
      case "create" -> "Creates DNS record " + name + " (" + type + ")";
      case "update" -> "Changes DNS record " + record;
      default -> "Deletes DNS record " + record;
    };
    return Classification.mutation(what + " in " + zone.text(), facts);
  }

  @Override
  Request request(Call c) throws ConnectorException {
    Zones.Zone z = c.zones().resolve(c, zone);
    CloudflareApi api = c.api();
    if (action.equals("delete")) {
      return new Request("DELETE", api.uri("zones", z.id(), "dns_records", record), null);
    }
    ObjectNode body = Answers.F.objectNode();
    if (type != null) {
      body.put("type", type);
    }
    if (name != null) {
      try {
        body.put("name", DnsNames.absolute(name, z.name()));
      } catch (Refusal e) {
        throw ConnectorException.badInput(e.forAgent());
      }
    }
    if (content != null) {
      body.put("content", content);
    }
    if (data != null) {
      body.set("data", data.deepCopy());
    }
    if (ttl != null) {
      body.put("ttl", ttl);
    }
    if (proxied != null) {
      body.put("proxied", proxied);
    }
    if (priority != null) {
      body.put("priority", priority);
    }
    if (comment != null) {
      body.put("comment", comment);
    }
    if (tags != null) {
      ArrayNode t = body.putArray("tags");
      tags.forEach(t::add);
    }
    return action.equals("create")
        ? new Request("POST", api.uri("zones", z.id(), "dns_records"), body)
        : new Request("PATCH", api.uri("zones", z.id(), "dns_records", record), body);
  }

  @Override
  Context context(Call c, Request r) throws ConnectorException {
    Zones.Zone z = c.zones().resolve(c, zone);
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("Zone", z.name() + " (" + z.id() + ")"));
    List<Field> impact = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("cloudflare.zone", z.name());
    facets.put("cloudflare.action", "dns." + action);
    JsonNode state = null;
    String summary;
    if (action.equals("create")) {
      String full = r.body().path("name").asString();
      fields.add(new Field("Record", type + " " + full));
      impact.add(new Field("After", line(r.body())));
      Map<String, String> q = new LinkedHashMap<>();
      q.put("name.exact", full);
      q.put("per_page", "20");
      JsonNode existing = c.api().get(c, c.api().uri(List.of("zones", z.id(), "dns_records"), q), "DNS").result();
      List<String> lines = new ArrayList<>();
      String conflict = null;
      for (JsonNode e : existing) {
        lines.add(line(e));
        String t = e.path("type").asString("");
        if (conflict == null && (t.equals("CNAME") || type.equals("CNAME"))) {
          conflict = t;
        }
      }
      if (conflict != null) {
        warnings.add("A CNAME can't share its name with other records, and " + full + " already has a " + conflict
            + " record: Cloudflare will refuse the request.");
      }
      impact.add(new Field("Existing at this name", lines.isEmpty() ? "none" : String.join("; ", lines)));
      summary = "Create " + type + " " + full + " in " + z.name();
    } else {
      JsonNode before = c.api().get(c, c.api().uri("zones", z.id(), "dns_records", record), "DNS").result();
      fields.add(new Field("Record", before.path("type").asString("") + " " + before.path("name").asString("")
          + " (" + record + ")"));
      impact.add(new Field("Before", line(before)));
      if (action.equals("update")) {
        ObjectNode after = Answers.pick(before, DnsRead.FIELDS);
        r.body().properties().forEach(e -> after.set(e.getKey(), e.getValue().deepCopy()));
        impact.add(new Field("After", line(after)));
        String t = after.path("type").asString("");
        if (r.body().has("content") && !t.isEmpty() && !CONTENT_TYPES.contains(t)) {
          throw ConnectorException.badInput("A " + t + " record takes data, not content.");
        }
        if (r.body().has("data") && CONTENT_TYPES.contains(t)) {
          throw ConnectorException.badInput("A " + t + " record takes content, not data.");
        }
        summary = "Change " + t + " " + before.path("name").asString("") + " in " + z.name();
      } else {
        impact.add(new Field("After", "deleted"));
        warnings.add("Deletes " + before.path("type").asString("") + " " + before.path("name").asString("")
            + ": resolvers keep the old answer for up to its TTL.");
        summary = "Delete " + before.path("type").asString("") + " " + before.path("name").asString("") + " in "
            + z.name();
      }
      if (before.path("proxied").asBoolean(false) && r.body() != null && r.body().has("proxied")
          && !r.body().path("proxied").asBoolean()) {
        warnings.add("Turns off Cloudflare's proxy: visitors reach the origin's address directly, and it becomes "
            + "public.");
      }
      state = Answers.F.objectNode().put("record", record).put("modified_on",
          before.path("modified_on").asString(""));
    }
    return new Context(z.name(), summary, fields, impact, warnings, facets, state);
  }

  @Override
  void stillCurrent(Call c, Request r, JsonNode state) throws ConnectorException {
    if (action.equals("create")) {
      return;
    }
    if (state == null || !state.path("modified_on").isString()) {
      throw ConnectorException.denied("GateRook has no record of what this change was approved against. Not "
          + "executed: call cloudflare_dns_change again.");
    }
    Zones.Zone z = c.zones().resolve(c, zone);
    JsonNode now = c.api().get(c, c.api().uri("zones", z.id(), "dns_records", record), "DNS").result();
    if (!state.path("modified_on").asString().equals(now.path("modified_on").asString(""))) {
      throw ConnectorException.denied("DNS record " + record + " changed after the approval (now modified "
          + now.path("modified_on").asString("?") + "). Not executed: read it again and ask for a new approval.");
    }
  }

  @Override
  ToolResult answer(Call c, Request r, CloudflareApi.Answer a) throws ConnectorException {
    Zones.Zone z = c.zones().resolve(c, zone);
    JsonNode result = a.result();
    return changed(c, "DNS record " + action + "d · " + z.name() + " · " + c.cfg().name(), "dns." + action, r,
        result.isObject() ? Answers.pick(result, DnsRead.FIELDS) : result, z.node(),
        List.of("Resolvers may keep the previous answer for up to the record's old TTL."));
  }

  /** One record in a line: {@code A www.example.com → 192.0.2.1 · proxied · TTL auto}. */
  static String line(JsonNode r) {
    StringBuilder b = new StringBuilder();
    b.append(r.path("type").asString("?")).append(' ').append(r.path("name").asString("?")).append(" → ");
    if (r.path("data").isObject()) {
      b.append(r.path("data").toString());
    } else {
      b.append(r.path("content").asString(""));
    }
    if (r.path("priority").isNumber()) {
      b.append(" · priority ").append(r.path("priority").asInt());
    }
    if (r.path("proxied").isBoolean()) {
      b.append(r.path("proxied").asBoolean() ? " · proxied" : " · DNS only");
    }
    if (r.path("ttl").isNumber()) {
      b.append(" · TTL ").append(r.path("ttl").asInt() == 1 ? "auto" : r.path("ttl").asInt() + " s");
    }
    if (r.path("comment").isString() && !r.path("comment").asString().isEmpty()) {
      b.append(" · ").append(Args.abbreviate(r.path("comment").asString()));
    }
    return b.toString();
  }
}
