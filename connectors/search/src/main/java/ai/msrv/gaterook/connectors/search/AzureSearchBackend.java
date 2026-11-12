package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Azure AI Search's data plane REST API (docs/search-engines.md), {@code api-version} {@value #API_VERSION}: indexes,
 * their definitions and statistics, search with an OData {@code $filter} GateRook writes from the filter tree and
 * vector queries, documents by key, and batches of uploads, merges and deletes. The service's host is fixed by the
 * connection ({@code <service>.search.windows.net} or a sovereign cloud's). The key goes in {@code api-key}: a query key
 * may only search and read documents — Test connection tells it from an admin key — and listing or describing indexes
 * needs an admin key, so with a query key the connection names its indexes.
 */
final class AzureSearchBackend extends SearchBackend {

  static final String TYPE = "azure-ai-search";
  static final String API_KEY = "/apiKey";
  static final String API_VERSION = "2025-09-01";
  private static final Pattern ENDPOINT = Pattern.compile(
      "https://([a-z0-9][a-z0-9-]{0,58}[a-z0-9]?)\\.search\\.(windows\\.net|azure\\.us|azure\\.cn)/?");
  private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,126}[a-z0-9])?");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}(/[A-Za-z_][A-Za-z0-9_]{0,127}){0,9}");
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9\\-=][A-Za-z0-9_\\-=]{0,1023}");
  private static final long MAX_READ = 16L << 20;
  private static final long INDEX_TTL_NANOS = 5L * 60 * 1_000_000_000;

  /** An index definition as GateRook uses it: its key field and each field's type. */
  private record Definition(String key, Map<String, String> types, java.util.Set<String> vectors, JsonNode body,
      long atNanos) {

    /** Whether {@code field} is a collection of values (not a vector field): filtered through any/all. */
    boolean collection(String field) {
      String t = types.get(field);
      return t != null && t.startsWith("Collection(") && !vectors.contains(field);
    }
  }

  private final Map<String, Definition> definitions = new ConcurrentHashMap<>();
  private final URI testBase;

  AzureSearchBackend() {
    this(null);
  }

  /** Tests: {@code testBase} on this computer replaces the service's host. Never a setting. */
  AzureSearchBackend(URI testBase) {
    if (testBase != null && !ServiceHttp.loopbackHost(testBase.getHost())) {
      throw new IllegalArgumentException("A test base is on this computer");
    }
    this.testBase = testBase;
  }

  @Override
  String type() {
    return TYPE;
  }

  @Override
  String name() {
    return "Azure AI Search";
  }

  @Override
  String description() {
    return "Indexes and documents of an Azure AI Search service: full-text, filtered and vector search, documents by "
        + "key. Document writes and deletes wait for approval.";
  }

  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException {
    String endpoint = cfg.string("/endpoint");
    if (endpoint == null || !ENDPOINT.matcher(endpoint.strip().toLowerCase(java.util.Locale.ROOT)).matches()) {
      throw ConnectorException.badInput("Enter the service endpoint: https://<service>.search.windows.net (or "
          + ".search.azure.us, .search.azure.cn).");
    }
    String e = endpoint.strip().toLowerCase(java.util.Locale.ROOT);
    if (e.endsWith("/")) {
      e = e.substring(0, e.length() - 1);
    }
    return new ServiceHttp.Endpoint(testBase != null ? testBase : URI.create(e), List.of());
  }

  @Override
  Map<String, List<String>> auth(Call c) throws ConnectorException {
    return ServiceHttp.headers("api-key", secret(c, API_KEY, "API key"));
  }

  @Override
  List<String> authShown(ConnectionConfig cfg) {
    return List.of("api-key: ‹secret›");
  }

  @Override
  String said(ServiceHttp.Response r) {
    try {
      JsonNode body = ServiceHttp.JSON.readTree(r.body());
      String m = body.path("error").path("message").asString(null);
      return m == null || m.isBlank() ? null : shorten(m, 500);
    } catch (RuntimeException e) {
      String text = r.text().strip();
      return text.isEmpty() ? null : shorten(text, 300);
    }
  }

  @Override
  String nameProblem(String name) {
    return NAME.matcher(name).matches() && !name.contains("--") ? null : "An Azure AI Search index name has 1 to 128 "
        + "lowercase letters, digits and dashes, and starts and ends with a letter or digit.";
  }

  @Override
  Pattern fieldPattern() {
    return FIELD;
  }

  @Override
  String fieldRule() {
    return "an Azure AI Search field name starts with a letter or _, with / into complex fields.";
  }

  @Override
  String fixedIdField() {
    return null;
  }

  @Override
  String idProblem(JsonNode id) {
    return KEY.matcher(idText(id)).matches() ? null : "An Azure AI Search key has letters, digits, -, _ and = only, "
        + "doesn't start with _, and has at most 1,024 characters.";
  }

  @Override
  String queryProblem(Query q) {
    if (q.vector() != null && q.vectorField() == null) {
      return "Name the vector field of vector with vectorField: search_describe_index lists the index's fields.";
    }
    return q.sort().size() > 32 ? "Azure AI Search sorts by at most 32 fields." : null;
  }

  private static String index(String name) {
    return "/indexes/" + ServiceHttp.seg(name);
  }

  private ServiceHttp.Request get(ConnectionConfig cfg, String path, String what, String... query)
      throws ConnectorException {
    String[] all = new String[query.length + 2];
    all[0] = "api-version";
    all[1] = API_VERSION;
    System.arraycopy(query, 0, all, 2, query.length);
    return ServiceHttp.Request.get(endpoint(cfg).uri(path, all), null, what);
  }

  private ServiceHttp.Request post(ConnectionConfig cfg, String path, JsonNode body, String what, boolean change)
      throws ConnectorException {
    return ServiceHttp.Request.json("POST", endpoint(cfg).uri(path, "api-version", API_VERSION), null, body, what,
        change);
  }

  /** The index's definition, or {@code null} when the key may not read it (a query key). */
  private Definition definition(Call c, String index, boolean required) throws ConnectorException {
    String key = c.cfg().id() + "|" + c.cfg().version() + "|" + index;
    Definition known = definitions.get(key);
    if (known != null && System.nanoTime() - known.atNanos() < INDEX_TTL_NANOS) {
      return known;
    }
    ServiceHttp.Request r = get(c.cfg(), index(index), "Azure AI Search's definition of " + index);
    ServiceHttp.Response response = send(c, r, MAX_READ);
    if ((response.status() == 401 || response.status() == 403) && !required) {
      return null;
    }
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    JsonNode body = ServiceHttp.json(response, r.what());
    String keyField = null;
    Map<String, String> types = new LinkedHashMap<>();
    java.util.Set<String> vectors = new java.util.HashSet<>();
    for (JsonNode f : body.path("fields")) {
      String name = f.path("name").asString("");
      types.put(name, f.path("type").asString(""));
      if (f.path("dimensions").isNumber()) {
        vectors.add(name);
      }
      if (f.path("key").asBoolean(false)) {
        keyField = name;
      }
    }
    if (keyField == null) {
      throw ConnectorException.resourceError("Azure AI Search described " + index + " without a key field.", null);
    }
    Definition d = new Definition(keyField, Map.copyOf(types), java.util.Set.copyOf(vectors), body, System.nanoTime());
    if (definitions.size() > 1_000) {
      definitions.clear();
    }
    definitions.put(key, d);
    return d;
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  List<ObjectNode> indexes(Call c) throws ConnectorException {
    ServiceHttp.Request r = get(c.cfg(), "/indexes", "Azure AI Search's index list", "$select", "name");
    ServiceHttp.Response response = send(c, r, MAX_READ);
    if (response.status() == 401 || response.status() == 403) {
      return listed(c.cfg(), response, r);
    }
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode i : ServiceHttp.json(response, r.what()).path("value")) {
      out.add(F.objectNode().put("name", i.path("name").asString("")));
    }
    return out;
  }

  @Override
  ObjectNode describe(Call c, String index) throws ConnectorException {
    ObjectNode out = F.objectNode().put("name", index);
    Definition d = definition(c, index, false);
    if (d != null) {
      out.put("key", d.key());
      ArrayNode fields = out.putArray("fields");
      for (JsonNode f : d.body().path("fields")) {
        ObjectNode o = fields.addObject().put("name", f.path("name").asString("")).put("type", f.path("type")
            .asString(""));
        for (String flag : List.of("searchable", "filterable", "sortable", "facetable", "retrievable")) {
          if (f.path(flag).isBoolean()) {
            o.put(flag, f.get(flag).asBoolean());
          }
        }
        if (f.path("dimensions").isNumber()) {
          o.set("dimensions", f.get("dimensions"));
        }
      }
      if (d.body().path("semantic").isObject()) {
        out.put("semanticSearch", true);
      }
      try {
        JsonNode stats = json(c, get(c.cfg(), index(index) + "/stats", "Azure AI Search's statistics of " + index),
            1 << 20);
        out.set("documents", stats.path("documentCount"));
        out.set("storageSize", stats.path("storageSize"));
      } catch (ConnectorException e) {
        // Below: the count a query key may read.
      }
    } else {
      out.put("definition", "Not readable with a query key: it may only search and read documents.");
    }
    if (!out.has("documents")) {
      try {
        ServiceHttp.Response count = send(c, get(c.cfg(), index(index) + "/docs/$count", "Azure AI Search's count of "
            + index), 64 * 1024);
        if (count.ok() && count.text().strip().matches("\\d{1,19}")) {
          out.put("documents", Long.parseLong(count.text().strip()));
        }
      } catch (ConnectorException e) {
        // The count is a nicety.
      }
    }
    return out;
  }

  @Override
  Hits search(Call c, Query q) throws ConnectorException {
    Definition d = definition(c, q.index(), false);
    ObjectNode body = F.objectNode().put("search", q.text() == null ? "*" : q.text());
    if (q.filter() != null) {
      body.put("filter", odata(q.filter(), d));
    }
    if (!q.facets().isEmpty()) {
      ArrayNode facets = body.putArray("facets");
      q.facets().forEach(facets::add);
    }
    if (!q.sort().isEmpty()) {
      List<String> order = new ArrayList<>();
      q.sort().forEach(s -> order.add(s.field() + (s.descending() ? " desc" : " asc")));
      body.put("orderby", String.join(", ", order));
    }
    if (!q.fields().isEmpty()) {
      List<String> fields = new ArrayList<>(q.fields());
      if (d != null && !fields.contains(d.key())) {
        fields.add(d.key());
      }
      body.put("select", String.join(",", fields));
    }
    if (!q.searchFields().isEmpty()) {
      body.put("searchFields", String.join(",", q.searchFields()));
    }
    if (q.vector() != null) {
      ObjectNode vq = body.putArray("vectorQueries").addObject().put("kind", "vector");
      ArrayNode v = vq.putArray("vector");
      q.vector().forEach(v::add);
      vq.put("fields", q.vectorField()).put("k", q.offset() + q.limit());
    }
    body.put("top", q.limit()).put("skip", q.offset()).put("count", true);
    JsonNode answer = json(c, post(c.cfg(), index(q.index()) + "/docs/search", body, "Azure AI Search's search of "
        + q.index(), false), MAX_READ);
    List<ObjectNode> hits = new ArrayList<>();
    for (JsonNode h : answer.path("value")) {
      ObjectNode doc = (ObjectNode) h.deepCopy();
      JsonNode score = doc.remove("@search.score");
      JsonNode reranker = doc.remove("@search.rerankerScore");
      doc.remove("@search.highlights");
      doc.remove("@search.captions");
      ObjectNode hit = F.objectNode();
      if (d != null && doc.has(d.key())) {
        hit.set("id", doc.get(d.key()));
      }
      if (reranker != null && !reranker.isNull()) {
        hit.set("score", reranker);
      } else if (score != null) {
        hit.set("score", score);
      }
      hit.set("document", doc);
      hits.add(hit);
    }
    ObjectNode facets = F.objectNode();
    for (var e : answer.path("@search.facets").properties()) {
      ObjectNode counts = facets.putObject(e.getKey());
      for (JsonNode bucket : e.getValue()) {
        counts.set(bucket.path("value").asString(bucket.path("value").toString()), bucket.path("count"));
      }
    }
    return new Hits(hits, answer.has("@odata.count") ? answer.get("@odata.count").asLong() : null, false, facets);
  }

  /** The filter as an OData {@code $filter}: text single-quoted with quotes doubled, collections through any/all. */
  static String odata(Filter.Node node, Definition d) throws ConnectorException {
    return switch (node) {
      case Filter.And a -> join(a.nodes(), " and ", d);
      case Filter.Or o -> join(o.nodes(), " or ", d);
      case Filter.Cmp cmp -> {
        String op = switch (cmp.op()) {
          case EQ -> "eq";
          case NE -> "ne";
          case GT -> "gt";
          case GTE -> "ge";
          case LT -> "lt";
          case LTE -> "le";
        };
        if (d != null && d.collection(cmp.field())) {
          if (cmp.op() == Filter.Op.EQ) {
            yield cmp.field() + "/any(t: t eq " + literal(cmp.value()) + ")";
          }
          if (cmp.op() == Filter.Op.NE) {
            yield cmp.field() + "/all(t: t ne " + literal(cmp.value()) + ")";
          }
          throw ConnectorException.badInput(cmp.field() + " is a collection: compare it with $eq, $ne, $in or $nin.");
        }
        yield cmp.field() + " " + op + " " + literal(cmp.value());
      }
      case Filter.In in -> {
        boolean text = in.values().stream().allMatch(v -> v instanceof Filter.Text);
        boolean collection = d != null && d.collection(in.field());
        if (text) {
          String delimiter = delimiter(in.values());
          StringBuilder joined = new StringBuilder();
          for (Filter.Value v : in.values()) {
            joined.append(joined.isEmpty() ? "" : delimiter).append(((Filter.Text) v).value());
          }
          String call = "search.in(" + (collection ? "t" : in.field()) + ", " + quote(joined.toString()) + ", "
              + quote(delimiter) + ")";
          if (collection) {
            yield in.not() ? in.field() + "/all(t: not " + call + ")" : in.field() + "/any(t: " + call + ")";
          }
          yield in.not() ? "not " + call : call;
        }
        List<String> parts = new ArrayList<>();
        for (Filter.Value v : in.values()) {
          parts.add((collection ? "t" : in.field()) + (in.not() ? " ne " : " eq ") + literal(v));
        }
        String joined = String.join(in.not() ? " and " : " or ", parts);
        if (collection) {
          yield in.not() ? in.field() + "/all(t: " + joined + ")" : in.field() + "/any(t: " + joined + ")";
        }
        yield "(" + joined + ")";
      }
      case Filter.Exists e -> e.field() + (e.exists() ? " ne null" : " eq null");
    };
  }

  private static String join(List<Filter.Node> nodes, String op, Definition d) throws ConnectorException {
    StringBuilder out = new StringBuilder();
    for (Filter.Node n : nodes) {
      out.append(out.isEmpty() ? "" : op).append('(').append(odata(n, d)).append(')');
    }
    return out.toString();
  }

  /** A delimiter of search.in that none of the values contains. */
  private static String delimiter(List<Filter.Value> values) throws ConnectorException {
    for (String candidate : List.of("|", ",", ";", "~", "^", "`", "#")) {
      if (values.stream().noneMatch(v -> ((Filter.Text) v).value().contains(candidate))) {
        return candidate;
      }
    }
    throw ConnectorException.badInput("The values of $in contain every delimiter Azure AI Search's search.in could "
        + "use: split the filter into $or of $eq.");
  }

  private static String literal(Filter.Value v) {
    return switch (v) {
      case Filter.Text t -> quote(t.value());
      case Filter.Bool b -> b.value() ? "true" : "false";
      default -> Filter.number(v);
    };
  }

  /** An OData string literal: single quotes, a quote doubled. */
  static String quote(String s) {
    return "'" + s.replace("'", "''") + "'";
  }

  @Override
  Documents documents(Call c, String index, List<JsonNode> ids, List<String> fields) throws ConnectorException {
    Definition d = definition(c, index, false);
    List<ObjectNode> docs = new ArrayList<>();
    List<JsonNode> missing = new ArrayList<>();
    if (d != null) {
      List<Filter.Value> values = new ArrayList<>();
      ids.forEach(id -> values.add(new Filter.Text(idText(id))));
      String delimiter = delimiter(values);
      StringBuilder joined = new StringBuilder();
      ids.forEach(id -> joined.append(joined.isEmpty() ? "" : delimiter).append(idText(id)));
      ObjectNode body = F.objectNode().put("search", "*").put("filter", "search.in(" + d.key() + ", "
          + quote(joined.toString()) + ", " + quote(delimiter) + ")").put("top", ids.size());
      if (!fields.isEmpty()) {
        List<String> f = new ArrayList<>(fields);
        if (!f.contains(d.key())) {
          f.add(d.key());
        }
        body.put("select", String.join(",", f));
      }
      JsonNode answer = json(c, post(c.cfg(), index(index) + "/docs/search", body, "Azure AI Search's documents of "
          + index, false), MAX_READ);
      List<String> found = new ArrayList<>();
      for (JsonNode h : answer.path("value")) {
        ObjectNode doc = (ObjectNode) h.deepCopy();
        doc.remove("@search.score");
        docs.add(doc);
        found.add(doc.path(d.key()).asString(""));
      }
      for (JsonNode id : ids) {
        if (!found.contains(idText(id))) {
          missing.add(id);
        }
      }
      return new Documents(docs, missing);
    }
    if (ids.size() > 20) {
      throw ConnectorException.badInput("With a query key GateRook looks documents up one by one: name at most 20 "
          + "ids.");
    }
    for (JsonNode id : ids) {
      ServiceHttp.Request r = fields.isEmpty() ? get(c.cfg(), index(index) + "/docs/" + ServiceHttp.seg(idText(id)),
          "Azure AI Search's document " + idText(id)) : get(c.cfg(), index(index) + "/docs/"
          + ServiceHttp.seg(idText(id)), "Azure AI Search's document " + idText(id), "$select", String.join(",",
          fields));
      ServiceHttp.Response response = send(c, r, MAX_READ);
      if (response.status() == 404) {
        missing.add(id);
      } else if (!response.ok()) {
        throw failure(c.cfg(), r, response);
      } else {
        docs.add((ObjectNode) ServiceHttp.json(response, r.what()));
      }
    }
    return new Documents(docs, missing);
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  ObjectNode resolve(Call c, Change change) throws ConnectorException {
    Definition d = definition(c, change.target(), true);
    return F.objectNode().put("idField", d.key());
  }

  @Override
  ObjectNode resolvedWithoutIo(ConnectionConfig cfg, Change change) {
    Definition d = definitions.get(cfg.id() + "|" + cfg.version() + "|" + change.target());
    return F.objectNode().put("idField", d == null ? "id" : d.key());
  }

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    String key = idFieldOf(change);
    if (!FIELD.matcher(key).matches()) {
      throw ConnectorException.badInput("Azure AI Search named no usable key field of " + change.target() + ".");
    }
    ObjectNode body = F.objectNode();
    ArrayNode value = body.putArray("value");
    if ("delete".equals(change.op())) {
      change.ids().forEach(id -> value.addObject().put("@search.action", "delete").put(key, idText(id)));
    } else {
      String action = "merge".equals(change.op()) ? "mergeOrUpload" : "upload";
      for (JsonNode d : change.records()) {
        ObjectNode doc = value.addObject().put("@search.action", action);
        d.properties().forEach(e -> {
          if (!e.getKey().startsWith("@")) {
            doc.set(e.getKey(), e.getValue());
          }
        });
        doc.put(key, idText(d.get(key)));
      }
    }
    return post(cfg, index(change.target()) + "/docs/index", body, "Azure AI Search's batch on " + change.target(),
        true);
  }

  @Override
  ObjectNode changed(Call c, Change change, ServiceHttp.Response r) throws ConnectorException {
    JsonNode answer = ServiceHttp.json(r, "Azure AI Search's answer");
    int ok = 0;
    ArrayNode failed = F.arrayNode();
    for (JsonNode item : answer.path("value")) {
      if (item.path("status").asBoolean(false)) {
        ok++;
      } else {
        failed.addObject().put("id", item.path("key").asString("")).put("status", item.path("statusCode").asInt())
            .put("error", shorten(item.path("errorMessage").asString(""), 300));
      }
    }
    if (ok == 0 && !failed.isEmpty()) {
      throw ConnectorException.badInput("Azure AI Search refused every document, so nothing changed: "
          + shorten(failed.get(0).path("error").asString(""), 300));
    }
    ObjectNode out = F.objectNode().put("delete".equals(change.op()) ? "deleted" : "indexed", ok);
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
    boolean admin;
    List<ObjectNode> all;
    try {
      ServiceHttp.Request r = get(c.cfg(), "/indexes", "Azure AI Search's index list", "$select", "name");
      ServiceHttp.Response response = send(c, r, MAX_READ);
      admin = response.ok();
      all = indexes(c);
      verifyListed(c, all);
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null);
    }
    long latency = (System.nanoTime() - started) / 1_000_000;
    List<TestResult.Check> checks = new ArrayList<>();
    long visible = all.stream().filter(o -> allows(c.cfg(), o.path("name").asString(""))).count();
    checks.add(new TestResult.Check("indexes", true, latency, count(visible, "index", "indexes") + " agents may "
        + "use"));
    TestResult.Permissions permissions = admin
        ? new TestResult.Permissions(TestResult.Level.ADMIN, "An admin key: it may also write and delete documents "
            + "and change indexes.", null, List.of("admin key"), null)
        : new TestResult.Permissions(TestResult.Level.READ, "A query key: it may only search and read documents.",
            null, List.of(), null);
    TestResult result = TestResult.success(latency, "Azure AI Search", admin ? "admin key" : "query key", permissions)
        .withChecks(checks).withMetadata(F.objectNode().put("serverVersion", "Azure AI Search").put("apiVersion",
            API_VERSION));
    if (admin) {
      result = result.withHint(new Hint(c.cfg().env() == ai.msrv.gaterook.connector.api.Env.DEV ? Severity.INFO
          : Severity.WARNING, "azure-ai-search.admin-key", "/apiKey", "An admin key may also write documents and "
          + "change indexes. Give agents a query key, and list their indexes, unless they should write."));
    }
    if (visible == 0) {
      result = result.withHint(new Hint(Severity.WARNING, "azure-ai-search.no-indexes", "/indexes", "No index agents "
          + "may use."));
    }
    return result;
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    return F.objectNode().put("ids", "The index's key field: letters, digits, -, _ and =")
        .put("search", "Full-text (simple syntax) with OData filters, facets and sorting; vector queries on vector "
            + "fields")
        .put("apiVersion", API_VERSION);
  }

  @Override
  void forget(String connectionId) {
    definitions.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
  }
}
