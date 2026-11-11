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
 * {@code cloudflare_dns_records}: the records of a zone ({@code GET /zones/{id}/dns_records} with Cloudflare's own
 * filters), or one record ({@code GET /zones/{id}/dns_records/{record}}).
 */
final class DnsRead {

  static final Set<String> ARGS = Set.of("connection", "zone", "record", "type", "name", "nameContains", "content",
      "search", "proxied", "page", "perPage");
  static final Set<String> TYPES = Set.of("A", "AAAA", "CAA", "CERT", "CNAME", "DNSKEY", "DS", "HTTPS", "LOC", "MX",
      "NAPTR", "NS", "OPENPGPKEY", "PTR", "SMIMEA", "SRV", "SSHFP", "SVCB", "TLSA", "TXT", "URI");
  static final String[] FIELDS = {"id", "type", "name", "content", "data", "priority", "proxied", "proxiable", "ttl",
      "comment", "tags", "settings", "created_on", "modified_on"};

  private final Zones.Ref zone;
  private final String record;
  private final String type;
  private final String name;
  private final String nameContains;
  private final String content;
  private final String search;
  private final Boolean proxied;
  private final Integer page;
  private final int perPage;

  private DnsRead(Zones.Ref zone, String record, String type, String name, String nameContains, String content,
      String search, Boolean proxied, Integer page, int perPage) {
    this.zone = zone;
    this.record = record;
    this.type = type;
    this.name = name;
    this.nameContains = nameContains;
    this.content = content;
    this.search = search;
    this.proxied = proxied;
    this.page = page;
    this.perPage = perPage;
  }

  static DnsRead parse(ObjectNode args, CloudflareSettings s, String connection, JsonNode metadata) throws Refusal {
    Args.only(args, CloudflareTools.DNS_RECORDS, ARGS);
    Zones.Ref ref = Zones.Ref.parse(Args.string(args, "zone", 253));
    Zones.allowed(ref, s, connection, metadata);
    String record = Args.string(args, "record", Args.HEX32, "a DNS record ID (32 characters, 0-9 and a-f)");
    if (record != null) {
      Args.notFor(args, "record", "type", "name", "nameContains", "content", "search", "proxied", "page", "perPage");
    }
    String name = Args.string(args, "name", 253);
    if (name != null) {
      DnsNames.check(name);
    }
    Integer perPage = Args.integer(args, "perPage", 1, 500);
    return new DnsRead(ref, record, Args.choice(args, "type", TYPES, null), name,
        Args.string(args, "nameContains", 253), Args.string(args, "content", 4_096), Args.string(args, "search", 200),
        Args.bool(args, "proxied"), Args.integer(args, "page", 1, 10_000), perPage == null ? 100 : perPage);
  }

  ToolResult run(Call c) throws ConnectorException {
    Zones.Zone z = c.zones().resolve(c, zone);
    if (record != null) {
      JsonNode r = c.api().get(c, c.api().uri("zones", z.id(), "dns_records", record), "DNS").result();
      ObjectNode s = Answers.envelope(c, "record");
      s.set("zone", z.node());
      s.set("item", Answers.pick(r, FIELDS));
      return Answers.answer(c, "DNS record · " + z.name() + " · " + c.cfg().name(), s, List.of());
    }
    Map<String, String> q = new LinkedHashMap<>();
    q.put("type", type);
    try {
      q.put("name.exact", name == null ? null : DnsNames.absolute(name, z.name()));
    } catch (Refusal e) {
      throw ConnectorException.badInput(e.forAgent());
    }
    q.put("name.contains", nameContains);
    q.put("content.exact", content);
    q.put("search", search);
    q.put("proxied", proxied == null ? null : proxied.toString());
    q.put("page", page == null ? null : String.valueOf(page));
    q.put("per_page", String.valueOf(perPage));
    CloudflareApi.Answer a = c.api().get(c, c.api().uri(List.of("zones", z.id(), "dns_records"), q), "DNS");
    ArrayNode items = Answers.F.arrayNode();
    for (JsonNode r : a.result()) {
      items.add(Answers.pick(r, FIELDS));
    }
    ObjectNode s = Answers.envelope(c, "records");
    s.set("zone", z.node());
    s.set("items", items);
    Answers.paging(s, a.info());
    List<String> notes = new ArrayList<>();
    if (s.path("totalPages").asInt(1) > s.path("page").asInt(1)) {
      notes.add("More records: call again with page " + (s.path("page").asInt(1) + 1) + ".");
    }
    return Answers.answer(c, "DNS records · " + z.name() + " · " + c.cfg().name(), s, notes);
  }
}
