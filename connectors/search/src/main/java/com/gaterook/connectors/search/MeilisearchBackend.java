package com.gaterook.connectors.search;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.TestResult.Hint;
import com.gaterook.connector.api.TestResult.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Meilisearch's REST API (docs/search-engines.md), Meilisearch Cloud's too: indexes, their settings and statistics,
 * search with a {@code filter} GateRook writes from the filter tree, documents fetched by id, and document additions,
 * updates and deletions, which Meilisearch applies as tasks: GateRook waits a few seconds for each and says whether it
 * ran. The key goes in {@code Authorization: Bearer}; the Default Search API Key, or a key with search and
 * documents.get only, is the engine's own boundary.
 */
final class MeilisearchBackend extends SearchBackend {

  static final String TYPE = "meilisearch";
  static final String API_KEY = "/auth/apiKey";
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,400}");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,254}");
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,511}");
  private static final long MAX_READ = 16L << 20;
  private static final long PK_TTL_NANOS = 5L * 60 * 1_000_000_000;
  /** How long a change waits for its task: Meilisearch applies most batches in well under a second. */
  private static final long TASK_WAIT_MILLIS = 4_000;

  private record Known(String primaryKey, long atNanos) {}

  private final Map<String, Known> primaryKeys = new ConcurrentHashMap<>();

  @Override
  String type() {
    return TYPE;
  }

  @Override
  String name() {
    return "Meilisearch";
  }

  @Override
  String description() {
    return "Indexes and documents of Meilisearch and Meilisearch Cloud: full-text search with filters, facets and "
        + "sorting. Document writes and deletes wait for approval.";
  }

  @Override
  int localPort() {
    return 7700;
  }

  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException {
    return new ServiceHttp.Endpoint(ServiceHttp.base(cfg.string("/url"),
        "https://ms-0123456789ab-1234.lon.meilisearch.io", "/indexes"), ca(cfg));
  }

  private static boolean keyed(ConnectionConfig cfg) {
    return !"none".equals(cfg.string("/auth/method"));
  }

  @Override
  Map<String, List<String>> auth(Call c) throws ConnectorException {
    return keyed(c.cfg()) ? ServiceHttp.headers("Authorization", "Bearer " + secret(c, API_KEY, "API key"))
        : Map.of();
  }

  @Override
  List<String> authShown(ConnectionConfig cfg) {
    return keyed(cfg) ? List.of("Authorization: Bearer ‹secret›") : List.of();
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
    return NAME.matcher(name).matches() ? null : "A Meilisearch index uid has letters, digits, - and _ only.";
  }

  @Override
  Pattern fieldPattern() {
    return FIELD;
  }

  @Override
  String fieldRule() {
    return "a Meilisearch field name has letters, digits, _, - and . into objects.";
  }

  @Override
  String idProblem(JsonNode id) {
    if (id.isIntegralNumber()) {
      return id.asLong() < 0 ? "A Meilisearch document id is a non-negative integer or text." : null;
    }
    return ID.matcher(id.asString()).matches() ? null : "A Meilisearch document id has letters, digits, - and _ "
        + "only, at most 511.";
  }

  @Override
  String fixedIdField() {
    return null;
  }

  @Override
  String queryProblem(Query q) {
    return null;
  }

  private ServiceHttp.Request get(ConnectionConfig cfg, String path, String what, String... query)
      throws ConnectorException {
    return ServiceHttp.Request.get(endpoint(cfg).uri(path, query), null, what);
  }

  private static String index(String uid) {
    return "/indexes/" + ServiceHttp.seg(uid);
  }

  /** The index's primary key, or {@code null} when the key may not read it. */
  private String primaryKey(Call c, String uid, boolean required) throws ConnectorException {
    String key = c.cfg().id() + "|" + c.cfg().version() + "|" + uid;
    Known known = primaryKeys.get(key);
    if (known != null && System.nanoTime() - known.atNanos() < PK_TTL_NANOS) {
      return known.primaryKey();
    }
    ServiceHttp.Request r = get(c.cfg(), index(uid), "Meilisearch's index " + uid);
    ServiceHttp.Response response = send(c, r, 1 << 20);
    if ((response.status() == 401 || response.status() == 403) && !required) {
      return null;
    }
    if (response.status() == 404 && required) {
      throw ConnectorException.badInput("Index " + uid + " doesn't exist: GateRook writes only to an index that "
          + "exists. Create it in Meilisearch first.");
    }
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    String pk = ServiceHttp.json(response, r.what()).path("primaryKey").asString(null);
    if (pk == null && required) {
      throw ConnectorException.badInput("Index " + uid + " has no primary key yet: set one in Meilisearch first.");
    }
    if (pk != null) {
      if (primaryKeys.size() > 1_000) {
        primaryKeys.clear();
      }
      primaryKeys.put(key, new Known(pk, System.nanoTime()));
    }
    return pk;
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  List<ObjectNode> indexes(Call c) throws ConnectorException {
    ServiceHttp.Request r = get(c.cfg(), "/indexes", "Meilisearch's index list", "limit", "1000");
    ServiceHttp.Response response = send(c, r, MAX_READ);
    if (response.status() == 401 || response.status() == 403) {
      return listed(c.cfg(), response, r);
    }
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    List<ObjectNode> out = new ArrayList<>();
    JsonNode counts = F.objectNode();
    try {
      counts = json(c, get(c.cfg(), "/stats", "Meilisearch's statistics"), MAX_READ).path("indexes");
    } catch (ConnectorException e) {
      // A key without stats.get: the list without sizes.
    }
    for (JsonNode i : ServiceHttp.json(response, r.what()).path("results")) {
      String uid = i.path("uid").asString("");
      ObjectNode o = F.objectNode().put("name", uid);
      if (i.path("primaryKey").isString()) {
        o.put("primaryKey", i.get("primaryKey").asString());
      }
      JsonNode n = counts.path(uid).path("numberOfDocuments");
      if (n.isNumber()) {
        o.set("documents", n);
      }
      if (i.path("updatedAt").isString()) {
        o.put("updatedAt", i.get("updatedAt").asString());
      }
      out.add(o);
    }
    return out;
  }

  @Override
  ObjectNode describe(Call c, String index) throws ConnectorException {
    ObjectNode out = F.objectNode().put("name", index);
    ArrayNode unreadable = F.arrayNode();
    String pk = primaryKey(c, index, false);
    if (pk != null) {
      out.put("primaryKey", pk);
    } else {
      unreadable.add("the primary key");
    }
    ServiceHttp.Request settings = get(c.cfg(), index(index) + "/settings", "Meilisearch's settings of " + index);
    ServiceHttp.Response s = send(c, settings, MAX_READ);
    if (s.ok()) {
      JsonNode body = ServiceHttp.json(s, settings.what());
      for (String key : List.of("searchableAttributes", "filterableAttributes", "sortableAttributes",
          "displayedAttributes", "rankingRules", "distinctAttribute")) {
        if (body.has(key) && !body.get(key).isNull()) {
          out.set(key, body.get(key));
        }
      }
      if (body.path("embedders").isObject() && !body.get("embedders").isEmpty()) {
        ArrayNode embedders = out.putArray("embedders");
        body.get("embedders").propertyNames().forEach(embedders::add);
      }
    } else if (s.status() == 401 || s.status() == 403) {
      unreadable.add("the settings");
    } else {
      throw failure(c.cfg(), settings, s);
    }
    ServiceHttp.Request stats = get(c.cfg(), index(index) + "/stats", "Meilisearch's statistics of " + index);
    ServiceHttp.Response st = send(c, stats, MAX_READ);
    if (st.ok()) {
      JsonNode body = ServiceHttp.json(st, stats.what());
      out.set("documents", body.path("numberOfDocuments"));
      out.put("indexing", body.path("isIndexing").asBoolean(false));
      if (body.path("fieldDistribution").isObject()) {
        out.set("fields", body.get("fieldDistribution"));
      }
    } else if (st.status() == 401 || st.status() == 403) {
      unreadable.add("the statistics");
    } else {
      throw failure(c.cfg(), stats, st);
    }
    if (!unreadable.isEmpty()) {
      out.set("notReadableWithThisKey", unreadable);
    }
    out.put("filters", "Fields listed in filterableAttributes; sort on sortableAttributes.");
    return out;
  }

  @Override
  Hits search(Call c, Query q) throws ConnectorException {
    ObjectNode body = F.objectNode();
    if (q.text() != null) {
      body.put("q", q.text());
    }
    if (q.filter() != null) {
      body.put("filter", filter(q.filter()));
    }
    if (!q.facets().isEmpty()) {
      ArrayNode facets = body.putArray("facets");
      q.facets().forEach(facets::add);
    }
    if (!q.sort().isEmpty()) {
      ArrayNode sort = body.putArray("sort");
      q.sort().forEach(s -> sort.add(s.field() + (s.descending() ? ":desc" : ":asc")));
    }
    if (!q.fields().isEmpty()) {
      ArrayNode fields = body.putArray("attributesToRetrieve");
      q.fields().forEach(fields::add);
    }
    if (!q.searchFields().isEmpty()) {
      ArrayNode on = body.putArray("attributesToSearchOn");
      q.searchFields().forEach(on::add);
    }
    if (q.vector() != null) {
      ArrayNode v = body.putArray("vector");
      q.vector().forEach(v::add);
      if (q.vectorField() == null) {
        throw ConnectorException.badInput("Name the embedder of the vector with vectorField: search_describe_index "
            + "lists the index's embedders.");
      }
      body.putObject("hybrid").put("embedder", q.vectorField()).put("semanticRatio", q.text() == null ? 1.0 : 0.5);
    }
    body.put("limit", q.limit()).put("offset", q.offset()).put("showRankingScore", true);
    JsonNode answer = json(c, ServiceHttp.Request.json("POST", endpoint(c.cfg()).uri(index(q.index()) + "/search"),
        null, body, "Meilisearch's search of " + q.index(), false), MAX_READ);
    String pk = primaryKey(c, q.index(), false);
    List<ObjectNode> hits = new ArrayList<>();
    for (JsonNode h : answer.path("hits")) {
      ObjectNode hit = F.objectNode();
      ObjectNode doc = (ObjectNode) h.deepCopy();
      JsonNode score = doc.remove("_rankingScore");
      doc.remove("_formatted");
      doc.remove("_matchesPosition");
      doc.remove("_semanticScore");
      if (pk != null && doc.has(pk)) {
        hit.set("id", doc.get(pk));
      }
      if (score != null) {
        hit.set("score", score);
      }
      hit.set("document", doc);
      hits.add(hit);
    }
    ObjectNode facets = null;
    if (answer.path("facetDistribution").isObject()) {
      facets = (ObjectNode) answer.get("facetDistribution");
    }
    Long total = answer.has("estimatedTotalHits") ? answer.get("estimatedTotalHits").asLong()
        : answer.has("totalHits") ? answer.get("totalHits").asLong() : null;
    return new Hits(hits, total, !answer.has("estimatedTotalHits"), facets);
  }

  /** The filter as Meilisearch's filter expression, every value quoted by GateRook. */
  static String filter(Filter.Node node) {
    return switch (node) {
      case Filter.And a -> join(a.nodes(), " AND ");
      case Filter.Or o -> join(o.nodes(), " OR ");
      case Filter.Cmp cmp -> cmp.field() + " " + switch (cmp.op()) {
        case EQ -> "=";
        case NE -> "!=";
        case GT -> ">";
        case GTE -> ">=";
        case LT -> "<";
        case LTE -> "<=";
      } + " " + literal(cmp.value());
      case Filter.In in -> {
        StringBuilder out = new StringBuilder(in.field()).append(in.not() ? " NOT IN [" : " IN [");
        for (int i = 0; i < in.values().size(); i++) {
          out.append(i == 0 ? "" : ", ").append(literal(in.values().get(i)));
        }
        yield out.append(']').toString();
      }
      case Filter.Exists e -> e.field() + (e.exists() ? " EXISTS" : " NOT EXISTS");
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
      case Filter.Text t -> MilvusBackend.quote(t.value());
      case Filter.Bool b -> b.value() ? "true" : "false";
      default -> Filter.number(v);
    };
  }

  @Override
  Documents documents(Call c, String index, List<JsonNode> ids, List<String> fields) throws ConnectorException {
    String pk = primaryKey(c, index, false);
    ObjectNode body = F.objectNode();
    ArrayNode list = body.putArray("ids");
    ids.forEach(id -> list.add(idText(id)));
    if (!fields.isEmpty()) {
      ArrayNode f = body.putArray("fields");
      fields.forEach(f::add);
      if (pk != null && !fields.contains(pk)) {
        f.add(pk);
      }
    }
    body.put("limit", ids.size());
    JsonNode answer = json(c, ServiceHttp.Request.json("POST", endpoint(c.cfg()).uri(index(index)
        + "/documents/fetch"), null, body, "Meilisearch's documents of " + index, false), MAX_READ);
    List<ObjectNode> docs = new ArrayList<>();
    List<String> found = new ArrayList<>();
    for (JsonNode d : answer.path("results")) {
      docs.add((ObjectNode) d);
      if (pk != null && d.has(pk)) {
        found.add(idText(d.get(pk)));
      }
    }
    List<JsonNode> missing = new ArrayList<>();
    if (pk != null) {
      for (JsonNode id : ids) {
        if (!found.contains(idText(id))) {
          missing.add(id);
        }
      }
    }
    return new Documents(docs, missing);
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  ObjectNode resolve(Call c, Change change) throws ConnectorException {
    return F.objectNode().put("idField", primaryKey(c, change.target(), true));
  }

  @Override
  ObjectNode resolvedWithoutIo(ConnectionConfig cfg, Change change) {
    Known known = primaryKeys.get(cfg.id() + "|" + cfg.version() + "|" + change.target());
    return F.objectNode().put("idField", known == null ? "id" : known.primaryKey());
  }

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    if ("delete".equals(change.op())) {
      ArrayNode ids = F.arrayNode();
      change.ids().forEach(id -> ids.add(idText(id)));
      return ServiceHttp.Request.json("POST", endpoint(cfg).uri(index(change.target()) + "/documents/delete-batch"),
          null, ids, "Meilisearch's deletion of documents from " + change.target(), true);
    }
    return ServiceHttp.Request.json("merge".equals(change.op()) ? "PUT" : "POST", endpoint(cfg).uri(
        index(change.target()) + "/documents"), null, change.records(), "Meilisearch's "
        + ("merge".equals(change.op()) ? "update" : "addition") + " of documents to " + change.target(), true);
  }

  @Override
  ObjectNode changed(Call c, Change change, ServiceHttp.Response r) throws ConnectorException {
    JsonNode task = ServiceHttp.json(r, "Meilisearch's answer");
    long uid = task.path("taskUid").asLong(-1);
    ObjectNode out = F.objectNode().put("delete".equals(change.op()) ? "deleted" : "indexed", change.count());
    out.put("task", uid);
    if (uid < 0) {
      out.put("applied", false);
      return out;
    }
    long until = System.currentTimeMillis() + Math.min(TASK_WAIT_MILLIS, Math.max(0,
        c.ctx().remaining().toMillis() - 1_000));
    String status = task.path("status").asString("enqueued");
    JsonNode last = task;
    while (!status.equals("succeeded") && !status.equals("failed") && !status.equals("canceled")
        && System.currentTimeMillis() < until) {
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      ServiceHttp.Request poll = get(c.cfg(), "/tasks/" + uid, "Meilisearch's task " + uid);
      ServiceHttp.Response p = send(c, poll, 1 << 20);
      if (p.status() == 401 || p.status() == 403) {
        out.put("applied", false);
        out.put("note", "The key may not read tasks: Meilisearch accepted the change as task " + uid + ".");
        return out;
      }
      if (!p.ok()) {
        break;
      }
      last = ServiceHttp.json(p, poll.what());
      status = last.path("status").asString(status);
    }
    out.put("status", status);
    if (status.equals("failed")) {
      throw ConnectorException.badInput("Meilisearch's task " + uid + " failed, so nothing changed: "
          + ServiceHttp.sentence(shorten(last.path("error").path("message").asString(""), 500)));
    }
    out.put("applied", status.equals("succeeded"));
    JsonNode details = last.path("details");
    if (details.isObject() && !details.isEmpty()) {
      out.set("details", details);
    }
    return out;
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  TestResult test(Call c) {
    long started = System.nanoTime();
    try {
      JsonNode health = json(c, get(c.cfg(), "/health", "Meilisearch's health"), 64 * 1024);
      if (!"available".equals(health.path("status").asString(""))) {
        return TestResult.failure("meilisearch.not-meilisearch", "The server doesn't answer like Meilisearch: check "
            + "the URL.", null);
      }
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null);
    }
    long latency = (System.nanoTime() - started) / 1_000_000;
    String server = "Meilisearch";
    try {
      ServiceHttp.Response v = send(c, get(c.cfg(), "/version", "Meilisearch's version"), 64 * 1024);
      if (v.ok()) {
        server = "Meilisearch " + ServiceHttp.json(v, "Meilisearch's version").path("pkgVersion").asString("");
      } else if (v.status() == 401) {
        return TestResult.failure("search.denied", "Meilisearch refused the API key of " + c.cfg().name() + ": check "
            + "it under Credentials.", null);
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
    checks.add(new TestResult.Check("indexes", true, (System.nanoTime() - listed) / 1_000_000,
        count(visible, "index", "indexes") + " agents may use"));
    TestResult result = TestResult.success(latency, server.strip(), keyed(c.cfg()) ? "API key" : "no credentials",
        keyed(c.cfg()) ? new TestResult.Permissions(TestResult.Level.UNKNOWN, "Meilisearch doesn't say what a key may "
            + "do without reading every key.", null, List.of(), null)
            : new TestResult.Permissions(TestResult.Level.ADMIN, "The server checks no key: anyone who reaches it may "
                + "read, write and delete.", null, List.of(), null)).withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", server.strip()));
    result = result.withHint(keyed(c.cfg())
        ? new Hint(Severity.INFO, "meilisearch.search-key", "/auth/apiKey", "Give agents the Default Search API Key, "
            + "or a key with search and documents.get, unless they should write documents.")
        : new Hint(Severity.WARNING, "meilisearch.no-auth", "/auth/method", "The server has no master key: anyone who "
            + "reaches it may write and delete. Start it with a master key and give agents a search key."));
    List<Hint> plain = new ArrayList<>();
    QdrantBackend.plainHint(c.cfg(), plain);
    for (Hint h : plain) {
      result = result.withHint(h);
    }
    if (visible == 0) {
      result = result.withHint(new Hint(Severity.WARNING, "meilisearch.no-indexes", "/indexes", "No index agents "
          + "may use."));
    }
    return result;
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    return F.objectNode().put("ids", "The index's primary key: text of letters, digits, - and _, or an integer")
        .put("search", "Full-text with filters, facets and sorting; vector and hybrid with an embedder")
        .put("changes", "Applied as tasks: GateRook waits a few seconds and says whether each ran");
  }

  @Override
  void forget(String connectionId) {
    primaryKeys.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
  }
}
