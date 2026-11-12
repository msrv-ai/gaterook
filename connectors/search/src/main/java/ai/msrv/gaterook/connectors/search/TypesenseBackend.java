package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typesense's REST API (docs/search-engines.md), Typesense Cloud's too: collections and their schemas, searches through
 * {@code /multi_search} (a read, so vectors fit in its body) with a {@code filter_by} GateRook writes from the filter
 * tree, documents fetched by id, JSONL imports ({@code upsert} replaces, {@code emplace} merges) and deletes by id. The
 * key goes in {@code X-TYPESENSE-API-KEY}; a key with documents:search and documents:get only is the engine's own
 * boundary.
 */
final class TypesenseBackend extends SearchBackend {

  static final String TYPE = "typesense";
  static final String API_KEY = "/apiKey";
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.\\-]{0,254}");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,254}");
  private static final long MAX_READ = 16L << 20;
  private static final long SCHEMA_TTL_NANOS = 5L * 60 * 1_000_000_000;

  private record Schema(List<String> text, List<String> vectors, JsonNode description, long atNanos) {}

  private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

  @Override
  String type() {
    return TYPE;
  }

  @Override
  String name() {
    return "Typesense";
  }

  @Override
  String description() {
    return "Collections and documents of Typesense and Typesense Cloud: typo-tolerant search with filters, facets "
        + "and sorting. Document writes and deletes wait for approval.";
  }

  @Override
  int localPort() {
    return 8108;
  }

  @Override
  String listNoun() {
    return "collection";
  }

  @Override
  String listNouns() {
    return "collections";
  }

  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException {
    return new ServiceHttp.Endpoint(ServiceHttp.base(cfg.string("/url"), "https://xyz.a1.typesense.net",
        "/collections"), ca(cfg));
  }

  @Override
  Map<String, List<String>> auth(Call c) throws ConnectorException {
    return ServiceHttp.headers("X-TYPESENSE-API-KEY", secret(c, API_KEY, "API key"));
  }

  @Override
  List<String> authShown(ConnectionConfig cfg) {
    return List.of("X-TYPESENSE-API-KEY: ‹secret›");
  }

  @Override
  String said(ServiceHttp.Response r) {
    try {
      JsonNode body = ServiceHttp.JSON.readTree(r.body());
      String m = body.path("message").asString(null);
      return m == null || m.isBlank() ? null : shorten(m, 500);
    } catch (RuntimeException e) {
      String text = r.text().strip();
      return text.isEmpty() ? null : shorten(text, 300);
    }
  }

  @Override
  String nameProblem(String name) {
    return NAME.matcher(name).matches() ? null : "A Typesense collection name has letters, digits, _, - and . here.";
  }

  @Override
  Pattern fieldPattern() {
    return FIELD;
  }

  @Override
  String fieldRule() {
    return "a Typesense field name has letters, digits, _, - and . into objects.";
  }

  @Override
  String idProblem(JsonNode id) {
    String text = idText(id);
    return text.contains("`") ? "A document id with a backtick can't be named in Typesense's filters." : null;
  }

  @Override
  String fixedIdField() {
    return "id";
  }

  @Override
  void checkFilter(Filter.Node filter) throws Filter.Invalid {
    super.checkFilter(filter);
    check(filter);
  }

  private static void check(Filter.Node node) throws Filter.Invalid {
    switch (node) {
      case Filter.And a -> {
        for (Filter.Node n : a.nodes()) {
          check(n);
        }
      }
      case Filter.Or o -> {
        for (Filter.Node n : o.nodes()) {
          check(n);
        }
      }
      case Filter.Exists e -> throw new Filter.Invalid("Typesense's filters have no $exists.");
      case Filter.Cmp cmp -> {
        if (cmp.op().range() && cmp.value() instanceof Filter.Text) {
          throw new Filter.Invalid("Typesense compares numbers with " + cmp.op().name + ", not text.");
        }
        text(cmp.value());
      }
      case Filter.In in -> {
        for (Filter.Value v : in.values()) {
          text(v);
        }
      }
    }
  }

  private static void text(Filter.Value v) throws Filter.Invalid {
    if (v instanceof Filter.Text t && t.value().contains("`")) {
      throw new Filter.Invalid("A text value of a Typesense filter can't contain a backtick.");
    }
  }

  @Override
  String queryProblem(Query q) {
    return q.sort().size() > 3 ? "Typesense sorts by at most 3 fields." : null;
  }

  private static String collection(String name) {
    return "/collections/" + ServiceHttp.seg(name);
  }

  private Schema schema(Call c, String collection, boolean required) throws ConnectorException {
    String key = c.cfg().id() + "|" + c.cfg().version() + "|" + collection;
    Schema known = schemas.get(key);
    if (known != null && System.nanoTime() - known.atNanos() < SCHEMA_TTL_NANOS) {
      return known;
    }
    ServiceHttp.Request r = ServiceHttp.Request.get(endpoint(c.cfg()).uri(collection(collection)), null,
        "Typesense's schema of " + collection);
    ServiceHttp.Response response = send(c, r, MAX_READ);
    if ((response.status() == 401 || response.status() == 403) && !required) {
      return null;
    }
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    JsonNode d = ServiceHttp.json(response, r.what());
    List<String> text = new ArrayList<>();
    List<String> vectors = new ArrayList<>();
    for (JsonNode f : d.path("fields")) {
      String name = f.path("name").asString("");
      String type = f.path("type").asString("");
      if ((type.equals("string") || type.equals("string[]")) && f.path("index").asBoolean(true)
          && FIELD.matcher(name).matches()) {
        text.add(name);
      }
      if (type.equals("float[]") && f.path("num_dim").asInt(0) > 0) {
        vectors.add(name);
      }
    }
    Schema s = new Schema(List.copyOf(text), List.copyOf(vectors), d, System.nanoTime());
    if (schemas.size() > 1_000) {
      schemas.clear();
    }
    schemas.put(key, s);
    return s;
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  List<ObjectNode> indexes(Call c) throws ConnectorException {
    ServiceHttp.Request r = ServiceHttp.Request.get(endpoint(c.cfg()).uri("/collections"), null,
        "Typesense's collection list");
    ServiceHttp.Response response = send(c, r, MAX_READ);
    if (response.status() == 401 || response.status() == 403) {
      return listed(c.cfg(), response, r);
    }
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode d : ServiceHttp.json(response, r.what())) {
      ObjectNode o = F.objectNode().put("name", d.path("name").asString(""));
      if (d.path("num_documents").isNumber()) {
        o.set("documents", d.get("num_documents"));
      }
      out.add(o);
    }
    return out;
  }

  @Override
  ObjectNode describe(Call c, String index) throws ConnectorException {
    Schema s = schema(c, index, true);
    JsonNode d = s.description();
    ObjectNode out = F.objectNode().put("name", index);
    out.set("documents", d.path("num_documents"));
    ArrayNode fields = out.putArray("fields");
    for (JsonNode f : d.path("fields")) {
      ObjectNode o = fields.addObject().put("name", f.path("name").asString("")).put("type", f.path("type")
          .asString(""));
      for (String flag : List.of("facet", "sort", "optional", "index")) {
        if (f.has(flag)) {
          o.set(flag, f.get(flag));
        }
      }
      if (f.path("num_dim").asInt(0) > 0) {
        o.set("dimension", f.get("num_dim"));
      }
    }
    if (d.path("default_sorting_field").isString() && !d.get("default_sorting_field").asString().isEmpty()) {
      out.put("defaultSortingField", d.get("default_sorting_field").asString());
    }
    out.put("search", "Searches the string fields (" + String.join(", ", s.text()) + ") unless searchFields names "
        + "others; filters any indexed field; facets the fields with facet.");
    return out;
  }

  private static String csv(List<String> values) {
    return String.join(",", values);
  }

  @Override
  Hits search(Call c, Query q) throws ConnectorException {
    ObjectNode one = F.objectNode().put("collection", q.index()).put("q", q.text() == null ? "*" : q.text());
    if (!q.searchFields().isEmpty()) {
      one.put("query_by", csv(q.searchFields()));
    } else if (q.text() != null) {
      Schema s = schema(c, q.index(), false);
      if (s == null || s.text().isEmpty()) {
        throw ConnectorException.badInput("Typesense needs the fields to search: name them with searchFields"
            + (s == null ? " (the key may not read the collection's schema)." : "."));
      }
      one.put("query_by", csv(s.text()));
    }
    if (q.filter() != null) {
      one.put("filter_by", filter(q.filter()));
    }
    if (!q.facets().isEmpty()) {
      one.put("facet_by", csv(q.facets()));
    }
    if (!q.sort().isEmpty()) {
      List<String> sort = new ArrayList<>();
      q.sort().forEach(s -> sort.add(s.field() + (s.descending() ? ":desc" : ":asc")));
      one.put("sort_by", csv(sort));
    }
    if (!q.fields().isEmpty()) {
      List<String> fields = new ArrayList<>(q.fields());
      if (!fields.contains("id")) {
        fields.add("id");
      }
      one.put("include_fields", csv(fields));
    }
    if (q.vector() != null) {
      String field = q.vectorField();
      if (field == null) {
        Schema s = schema(c, q.index(), false);
        if (s == null || s.vectors().size() != 1) {
          throw ConnectorException.badInput("Name the vector field with vectorField.");
        }
        field = s.vectors().getFirst();
      }
      StringBuilder v = new StringBuilder();
      for (Double d : q.vector()) {
        v.append(v.isEmpty() ? "" : ",").append(d);
      }
      one.put("vector_query", field + ":([" + v + "], k:" + (q.offset() + q.limit()) + ")");
    }
    one.put("offset", q.offset()).put("limit", q.limit());
    ObjectNode body = F.objectNode();
    body.putArray("searches").add(one);
    JsonNode answer = json(c, ServiceHttp.Request.json("POST", endpoint(c.cfg()).uri("/multi_search"), null, body,
        "Typesense's search of " + q.index(), false), MAX_READ);
    JsonNode result = answer.path("results").path(0);
    if (result.has("error")) {
      throw ConnectorException.badInput("Typesense refused the search of " + q.index() + " (" + result.path("code")
          .asInt(400) + "): " + ServiceHttp.sentence(shorten(result.path("error").asString(""), 500)));
    }
    List<ObjectNode> hits = new ArrayList<>();
    for (JsonNode h : result.path("hits")) {
      ObjectNode hit = F.objectNode();
      JsonNode doc = h.path("document");
      if (doc.has("id")) {
        hit.set("id", doc.get("id"));
      }
      if (h.has("vector_distance")) {
        hit.set("score", h.get("vector_distance"));
        hit.put("scoreKind", "distance");
      } else if (h.has("text_match")) {
        hit.set("score", h.get("text_match"));
      }
      hit.set("document", doc);
      hits.add(hit);
    }
    ObjectNode facets = F.objectNode();
    for (JsonNode f : result.path("facet_counts")) {
      ObjectNode counts = facets.putObject(f.path("field_name").asString(""));
      for (JsonNode v : f.path("counts")) {
        counts.set(v.path("value").asString(""), v.path("count"));
      }
    }
    return new Hits(hits, result.has("found") ? result.get("found").asLong() : null, true, facets);
  }

  /** The filter as Typesense's {@code filter_by}: text in backticks, which no value contains. */
  static String filter(Filter.Node node) {
    return switch (node) {
      case Filter.And a -> join(a.nodes(), " && ");
      case Filter.Or o -> join(o.nodes(), " || ");
      case Filter.Cmp cmp -> cmp.field() + ":" + switch (cmp.op()) {
        case EQ -> "=";
        case NE -> "!=";
        case GT -> ">";
        case GTE -> ">=";
        case LT -> "<";
        case LTE -> "<=";
      } + literal(cmp.value());
      case Filter.In in -> {
        StringBuilder out = new StringBuilder(in.field()).append(in.not() ? ":!=[" : ":=[");
        for (int i = 0; i < in.values().size(); i++) {
          out.append(i == 0 ? "" : ",").append(literal(in.values().get(i)));
        }
        yield out.append(']').toString();
      }
      case Filter.Exists e -> throw new IllegalStateException("checked before");
    };
  }

  private static String join(List<Filter.Node> nodes, String op) {
    StringBuilder out = new StringBuilder();
    for (Filter.Node n : nodes) {
      out.append(out.isEmpty() ? "" : op).append('(').append(filter(n)).append(')');
    }
    return out.toString();
  }

  private static String literal(Filter.Value v) {
    return switch (v) {
      case Filter.Text t -> "`" + t.value() + "`";
      case Filter.Bool b -> b.value() ? "true" : "false";
      default -> Filter.number(v);
    };
  }

  private static String idFilter(List<JsonNode> ids) {
    StringBuilder out = new StringBuilder("id:[");
    for (int i = 0; i < ids.size(); i++) {
      out.append(i == 0 ? "" : ",").append('`').append(idText(ids.get(i))).append('`');
    }
    return out.append(']').toString();
  }

  @Override
  Documents documents(Call c, String index, List<JsonNode> ids, List<String> fields) throws ConnectorException {
    ObjectNode one = F.objectNode().put("collection", index).put("q", "*").put("filter_by", idFilter(ids))
        .put("per_page", ids.size());
    if (!fields.isEmpty()) {
      List<String> f = new ArrayList<>(fields);
      if (!f.contains("id")) {
        f.add("id");
      }
      one.put("include_fields", csv(f));
    }
    ObjectNode body = F.objectNode();
    body.putArray("searches").add(one);
    JsonNode answer = json(c, ServiceHttp.Request.json("POST", endpoint(c.cfg()).uri("/multi_search"), null, body,
        "Typesense's documents of " + index, false), MAX_READ);
    JsonNode result = answer.path("results").path(0);
    if (result.has("error")) {
      throw ConnectorException.badInput("Typesense refused the lookup in " + index + ": " + ServiceHttp.sentence(
          shorten(result.path("error").asString(""), 500)));
    }
    List<ObjectNode> docs = new ArrayList<>();
    List<String> found = new ArrayList<>();
    for (JsonNode h : result.path("hits")) {
      docs.add((ObjectNode) h.path("document"));
      found.add(h.path("document").path("id").asString(""));
    }
    List<JsonNode> missing = new ArrayList<>();
    for (JsonNode id : ids) {
      if (!found.contains(idText(id))) {
        missing.add(id);
      }
    }
    return new Documents(docs, missing);
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    if ("delete".equals(change.op())) {
      List<JsonNode> ids = new ArrayList<>();
      change.ids().forEach(ids::add);
      return new ServiceHttp.Request("DELETE", endpoint(cfg).uri(collection(change.target()) + "/documents",
          "filter_by", idFilter(ids), "batch_size", Integer.toString(Math.max(1, ids.size()))), null, null,
          "Typesense's delete of documents from " + change.target(), true);
    }
    StringBuilder lines = new StringBuilder();
    for (JsonNode d : change.records()) {
      ObjectNode doc = (ObjectNode) d.deepCopy();
      JsonNode id = doc.get("id");
      if (id != null && id.isIntegralNumber()) {
        doc.put("id", Long.toString(id.asLong()));
      }
      lines.append(ServiceHttp.JSON.writeValueAsString(doc)).append('\n');
    }
    return new ServiceHttp.Request("POST", endpoint(cfg).uri(collection(change.target()) + "/documents/import",
        "action", "merge".equals(change.op()) ? "emplace" : "upsert"), ServiceHttp.headers("Content-Type",
        "text/plain"), lines.toString().getBytes(StandardCharsets.UTF_8), "Typesense's import of documents into "
        + change.target(), true);
  }

  @Override
  ObjectNode changed(Call c, Change change, ServiceHttp.Response r) throws ConnectorException {
    ObjectNode out = F.objectNode();
    if ("delete".equals(change.op())) {
      JsonNode answer = ServiceHttp.json(r, "Typesense's answer");
      out.put("deleted", answer.path("num_deleted").asInt(0));
      if (answer.path("num_deleted").asInt(0) < change.count()) {
        out.put("note", (change.count() - answer.path("num_deleted").asInt(0)) + " ids matched no document.");
      }
      out.put("applied", true);
      return out;
    }
    int ok = 0;
    ArrayNode failed = F.arrayNode();
    String[] lines = r.text().split("\n");
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].isBlank()) {
        continue;
      }
      JsonNode line;
      try {
        line = ServiceHttp.JSON.readTree(lines[i]);
      } catch (RuntimeException e) {
        continue;
      }
      if (line.path("success").asBoolean(false)) {
        ok++;
      } else {
        JsonNode doc = i < change.records().size() ? change.records().get(i) : F.objectNode();
        failed.addObject().put("id", doc.path("id").asString("")).put("error", shorten(line.path("error")
            .asString(""), 300));
      }
    }
    if (ok == 0 && !failed.isEmpty()) {
      throw ConnectorException.badInput("Typesense refused every document, so nothing changed: "
          + shorten(failed.get(0).path("error").asString(""), 300));
    }
    out.put("indexed", ok);
    out.put("applied", true);
    if (!failed.isEmpty()) {
      out.set("failed", failed);
    }
    return out;
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  TestResult test(Call c) {
    long started = System.nanoTime();
    try {
      JsonNode health = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/health"), null,
          "Typesense's health"), 64 * 1024);
      if (!health.path("ok").asBoolean(false)) {
        return TestResult.failure("typesense.unhealthy", "Typesense says it isn't healthy: " + shorten(health
            .toString(), 200) + ".", null);
      }
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null);
    }
    long latency = (System.nanoTime() - started) / 1_000_000;
    String server = "Typesense";
    boolean admin = false;
    try {
      ServiceHttp.Response debug = send(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/debug"), null,
          "Typesense's version"), 64 * 1024);
      if (debug.ok()) {
        server = "Typesense " + ServiceHttp.json(debug, "Typesense's version").path("version").asString("");
        admin = true;
      }
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null);
    }
    List<TestResult.Check> checks = new ArrayList<>();
    checks.add(new TestResult.Check("server", true, latency, server.strip()));
    List<ObjectNode> all;
    long listed = System.nanoTime();
    try {
      all = indexes(c);
      verifyListed(c, all);
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null).withChecks(checks);
    }
    long visible = all.stream().filter(o -> allows(c.cfg(), o.path("name").asString(""))).count();
    checks.add(new TestResult.Check("collections", true, (System.nanoTime() - listed) / 1_000_000,
        count(visible, "collection") + " agents may use"));
    TestResult.Permissions permissions = admin
        ? new TestResult.Permissions(TestResult.Level.ADMIN, "The key may read the server's debug information: an "
            + "admin key, which may also write, delete and drop collections.", null, List.of("admin key"), null)
        : new TestResult.Permissions(TestResult.Level.UNKNOWN, "A scoped key: its actions decide what it may do.",
            null, List.of(), null);
    TestResult result = TestResult.success(latency, server.strip(), "API key", permissions).withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", server.strip()));
    if (admin) {
      result = result.withHint(new Hint(c.cfg().env() == ai.msrv.gaterook.connector.api.Env.DEV ? Severity.INFO
          : Severity.WARNING, "typesense.admin-key", "/apiKey", "This is an admin key. Give agents a key with "
          + "documents:search, documents:get and collections:get only, unless they should write."));
    }
    List<Hint> plain = new ArrayList<>();
    QdrantBackend.plainHint(c.cfg(), plain);
    for (Hint h : plain) {
      result = result.withHint(h);
    }
    if (visible == 0) {
      result = result.withHint(new Hint(Severity.WARNING, "typesense.no-collections", "/indexes", "No collection "
          + "agents may use."));
    }
    return result;
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    return F.objectNode().put("ids", "The id field, as text")
        .put("search", "Typo-tolerant full-text on the string fields, filters, facets and up to 3 sort fields; "
            + "vector search on a float[] field");
  }

  @Override
  void forget(String connectionId) {
    schemas.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
  }
}
