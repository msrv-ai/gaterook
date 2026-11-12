package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Algolia's Search REST API v1 (docs/search-engines.md): indices, their settings, search with {@code filters} GateRook
 * writes from the filter tree, records by objectID, and batches of record writes and deletes, which Algolia applies as
 * tasks: GateRook waits a few seconds for each. Reads go to {@code <APP>-dsn.algolia.net}, writes to
 * {@code <APP>.algolia.net}: fixed hosts of the application, never one a person or an agent types. The key goes in
 * {@code X-Algolia-API-Key}; a Search API key is the engine's own boundary, and Test connection reads the key's own
 * ACL to say so.
 */
final class AlgoliaBackend extends SearchBackend {

  static final String TYPE = "algolia";
  static final String API_KEY = "/apiKey";
  private static final Pattern APP = Pattern.compile("[A-Za-z0-9]{6,32}");
  /** An index name: Algolia takes almost anything; GateRook refuses control characters, / and *. */
  private static final Pattern NAME = Pattern.compile("[^\\x00-\\x1f\\x7f/*]{1,256}");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,254}");
  private static final long MAX_READ = 16L << 20;
  private static final long TASK_WAIT_MILLIS = 4_000;
  /** ACLs that change data. */
  private static final Set<String> WRITE_ACL = Set.of("addObject", "deleteObject", "deleteIndex", "editSettings");

  private final URI testBase;

  AlgoliaBackend() {
    this(null);
  }

  /** Tests: {@code testBase} on this computer replaces both of Algolia's hosts. Never a setting. */
  AlgoliaBackend(URI testBase) {
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
    return "Algolia";
  }

  @Override
  String description() {
    return "Indices and records of Algolia: search with filters and facets, records by objectID. Record writes and "
        + "deletes wait for approval.";
  }

  private static String app(ConnectionConfig cfg) throws ConnectorException {
    String app = cfg.string("/applicationId");
    if (app == null || !APP.matcher(app.strip()).matches()) {
      throw ConnectorException.badInput("Enter the Algolia application ID: letters and digits, such as ABCD1234EF.");
    }
    return app.strip();
  }

  /** The read host: the nearest of Algolia's distributed search network. */
  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException {
    String app = app(cfg);
    return new ServiceHttp.Endpoint(testBase != null ? testBase : URI.create("https://" + app.toLowerCase(Locale.ROOT)
        + "-dsn.algolia.net"), List.of());
  }

  /** The write host: the application's main cluster. */
  private ServiceHttp.Endpoint writes(ConnectionConfig cfg) throws ConnectorException {
    String app = app(cfg);
    return new ServiceHttp.Endpoint(testBase != null ? testBase : URI.create("https://" + app.toLowerCase(Locale.ROOT)
        + ".algolia.net"), List.of());
  }

  @Override
  ServiceHttp.Endpoint changeEndpoint(ConnectionConfig cfg, Change change) throws ConnectorException {
    return writes(cfg);
  }

  @Override
  Map<String, List<String>> auth(Call c) throws ConnectorException {
    return ServiceHttp.headers("X-Algolia-Application-Id", app(c.cfg()), "X-Algolia-API-Key",
        secret(c, API_KEY, "API key"));
  }

  @Override
  List<String> authShown(ConnectionConfig cfg) {
    String app;
    try {
      app = app(cfg);
    } catch (ConnectorException e) {
      app = "?";
    }
    return List.of("X-Algolia-Application-Id: " + app, "X-Algolia-API-Key: ‹secret›");
  }

  @Override
  String said(ServiceHttp.Response r) {
    try {
      String m = ServiceHttp.JSON.readTree(r.body()).path("message").asString(null);
      return m == null || m.isBlank() ? null : shorten(m, 500);
    } catch (RuntimeException e) {
      String text = r.text().strip();
      return text.isEmpty() ? null : shorten(text, 300);
    }
  }

  @Override
  String nameProblem(String name) {
    return NAME.matcher(name).matches() && !name.equals(".") && !name.equals("..") ? null : "An Algolia index name "
        + "has 1 to 256 characters, without /, * or control characters, and isn't . or ..";
  }

  @Override
  Pattern fieldPattern() {
    return FIELD;
  }

  @Override
  String fieldRule() {
    return "an Algolia attribute name has letters, digits, _, - and . into objects.";
  }

  @Override
  String fixedIdField() {
    return "objectID";
  }

  @Override
  String idProblem(JsonNode id) {
    return idText(id).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512 ? "An objectID has at most 512 "
        + "bytes." : null;
  }

  @Override
  void checkFilter(Filter.Node filter) throws Filter.Invalid {
    super.checkFilter(filter);
    check(filter, false);
  }

  /** Algolia's {@code filters} can't put an AND inside an OR, have no $exists, and compare numbers only. */
  private static void check(Filter.Node node, boolean inOr) throws Filter.Invalid {
    switch (node) {
      case Filter.And a -> {
        if (inOr) {
          throw new Filter.Invalid("Algolia's filters can't put an $and inside an $or: write the filter as an "
              + "$and of $or lists.");
        }
        for (Filter.Node n : a.nodes()) {
          check(n, false);
        }
      }
      case Filter.Or o -> {
        for (Filter.Node n : o.nodes()) {
          check(n, true);
        }
      }
      case Filter.Exists e -> throw new Filter.Invalid("Algolia's filters can't test whether an attribute exists.");
      case Filter.Cmp cmp -> {
        if (cmp.op().range() && !(cmp.value() instanceof Filter.Int || cmp.value() instanceof Filter.Num)) {
          throw new Filter.Invalid("Algolia compares numbers with " + cmp.op().name + ", not text.");
        }
      }
      case Filter.In in -> {
        if (inOr && in.not()) {
          throw new Filter.Invalid("Algolia's filters can't put a $nin inside an $or.");
        }
      }
    }
  }

  @Override
  String queryProblem(Query q) {
    if (!q.sort().isEmpty()) {
      return "Algolia sorts by its replica indices, not per query: search the replica that sorts this way.";
    }
    if (q.vector() != null) {
      return "Algolia's API takes no vectors: search with words and filters.";
    }
    return null;
  }

  private static String index(String name) {
    return "/1/indexes/" + ServiceHttp.seg(name);
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  List<ObjectNode> indexes(Call c) throws ConnectorException {
    ServiceHttp.Request r = ServiceHttp.Request.get(endpoint(c.cfg()).uri("/1/indexes", "hitsPerPage", "1000",
        "page", "0"), null, "Algolia's index list");
    ServiceHttp.Response response = send(c, r, MAX_READ);
    if (response.status() == 403 && !c.cfg().strings("/indexes").isEmpty()) {
      return listed(c.cfg(), response, r);
    }
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode i : ServiceHttp.json(response, r.what()).path("items")) {
      ObjectNode o = F.objectNode().put("name", i.path("name").asString(""));
      if (i.path("entries").isNumber()) {
        o.set("documents", i.get("entries"));
      }
      if (i.path("primary").isString()) {
        o.put("replicaOf", i.get("primary").asString());
      }
      if (i.path("replicas").isArray() && !i.get("replicas").isEmpty()) {
        o.set("replicas", i.get("replicas"));
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
    ServiceHttp.Request settings = ServiceHttp.Request.get(endpoint(c.cfg()).uri(index(index) + "/settings",
        "getVersion", "2"), null, "Algolia's settings of " + index);
    ServiceHttp.Response s = send(c, settings, MAX_READ);
    if (s.ok()) {
      JsonNode body = ServiceHttp.json(s, settings.what());
      for (String key : List.of("searchableAttributes", "attributesForFaceting", "customRanking", "ranking",
          "replicas", "primary", "attributesToRetrieve", "unretrievableAttributes", "numericAttributesForFiltering",
          "attributeForDistinct", "paginationLimitedTo")) {
        if (body.has(key) && !body.get(key).isNull()) {
          out.set(key, body.get(key));
        }
      }
    } else if (s.status() == 403) {
      out.put("settings", "Not readable with this key (it lacks the settings ACL).");
    } else {
      throw failure(c.cfg(), settings, s);
    }
    try {
      ObjectNode body = F.objectNode().put("query", "").put("hitsPerPage", 0);
      JsonNode answer = json(c, ServiceHttp.Request.json("POST", endpoint(c.cfg()).uri(index(index) + "/query"), null,
          body, "Algolia's count of " + index, false), 1 << 20);
      out.set("documents", answer.path("nbHits"));
    } catch (ConnectorException e) {
      // The count is a nicety.
    }
    out.put("filters", "Facet filters on attributesForFaceting; numeric comparisons on numeric attributes.");
    return out;
  }

  @Override
  Hits search(Call c, Query q) throws ConnectorException {
    ObjectNode body = F.objectNode().put("query", q.text() == null ? "" : q.text());
    if (q.filter() != null) {
      body.put("filters", filters(q.filter()));
    }
    if (!q.facets().isEmpty()) {
      ArrayNode facets = body.putArray("facets");
      q.facets().forEach(facets::add);
    }
    if (!q.fields().isEmpty()) {
      ArrayNode fields = body.putArray("attributesToRetrieve");
      q.fields().forEach(fields::add);
    }
    if (!q.searchFields().isEmpty()) {
      ArrayNode on = body.putArray("restrictSearchableAttributes");
      q.searchFields().forEach(on::add);
    }
    body.put("offset", q.offset()).put("length", q.limit());
    body.putArray("attributesToHighlight");
    body.putArray("attributesToSnippet");
    JsonNode answer = json(c, ServiceHttp.Request.json("POST", endpoint(c.cfg()).uri(index(q.index()) + "/query"),
        null, body, "Algolia's search of " + q.index(), false), MAX_READ);
    List<ObjectNode> hits = new ArrayList<>();
    for (JsonNode h : answer.path("hits")) {
      ObjectNode doc = (ObjectNode) h.deepCopy();
      doc.remove("_highlightResult");
      doc.remove("_snippetResult");
      doc.remove("_rankingInfo");
      ObjectNode hit = F.objectNode();
      if (doc.has("objectID")) {
        hit.set("id", doc.get("objectID"));
      }
      hit.set("document", doc);
      hits.add(hit);
    }
    boolean exact = answer.path("exhaustive").path("nbHits").asBoolean(answer.path("exhaustiveNbHits")
        .asBoolean(true));
    ObjectNode facets = answer.path("facets").isObject() ? (ObjectNode) answer.get("facets") : null;
    return new Hits(hits, answer.has("nbHits") ? answer.get("nbHits").asLong() : null, exact, facets);
  }

  /** The filter as Algolia's {@code filters}: text facet values in double quotes, numbers compared. */
  static String filters(Filter.Node node) {
    return switch (node) {
      case Filter.And a -> join(a.nodes(), " AND ");
      case Filter.Or o -> "(" + join(o.nodes(), " OR ") + ")";
      case Filter.Cmp cmp -> {
        boolean number = cmp.value() instanceof Filter.Int || cmp.value() instanceof Filter.Num;
        if (number) {
          yield cmp.field() + " " + switch (cmp.op()) {
            case EQ -> "=";
            case NE -> "!=";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
          } + " " + Filter.number(cmp.value());
        }
        String facet = cmp.field() + ":" + literal(cmp.value());
        yield cmp.op() == Filter.Op.NE ? "NOT " + facet : facet;
      }
      case Filter.In in -> {
        List<String> parts = new ArrayList<>();
        for (Filter.Value v : in.values()) {
          boolean number = v instanceof Filter.Int || v instanceof Filter.Num;
          String one = number ? in.field() + (in.not() ? " != " : " = ") + Filter.number(v)
              : (in.not() ? "NOT " : "") + in.field() + ":" + literal(v);
          parts.add(one);
        }
        yield in.not() ? String.join(" AND ", parts) : "(" + String.join(" OR ", parts) + ")";
      }
      case Filter.Exists e -> throw new IllegalStateException("checked before");
    };
  }

  private static String join(List<Filter.Node> nodes, String op) {
    StringBuilder out = new StringBuilder();
    for (Filter.Node n : nodes) {
      out.append(out.isEmpty() ? "" : op).append(filters(n));
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
    ObjectNode body = F.objectNode();
    ArrayNode requests = body.putArray("requests");
    for (JsonNode id : ids) {
      ObjectNode r = requests.addObject().put("indexName", index).put("objectID", idText(id));
      if (!fields.isEmpty()) {
        ArrayNode f = r.putArray("attributesToRetrieve");
        fields.forEach(f::add);
        if (!fields.contains("objectID")) {
          f.add("objectID");
        }
      }
    }
    JsonNode answer = json(c, ServiceHttp.Request.json("POST", endpoint(c.cfg()).uri("/1/indexes/*/objects"), null,
        body, "Algolia's records of " + index, false), MAX_READ);
    List<ObjectNode> docs = new ArrayList<>();
    List<JsonNode> missing = new ArrayList<>();
    JsonNode results = answer.path("results");
    for (int i = 0; i < ids.size(); i++) {
      JsonNode doc = results.path(i);
      if (doc.isObject()) {
        docs.add((ObjectNode) doc);
      } else {
        missing.add(ids.get(i));
      }
    }
    return new Documents(docs, missing);
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    ObjectNode body = F.objectNode();
    ArrayNode requests = body.putArray("requests");
    if ("delete".equals(change.op())) {
      change.ids().forEach(id -> requests.addObject().put("action", "deleteObject").putObject("body")
          .put("objectID", idText(id)));
    } else {
      String action = "merge".equals(change.op()) ? "partialUpdateObject" : "updateObject";
      for (JsonNode d : change.records()) {
        ObjectNode doc = (ObjectNode) d.deepCopy();
        doc.put("objectID", idText(d.get("objectID")));
        ObjectNode r = requests.addObject().put("action", action);
        r.set("body", doc);
      }
    }
    return ServiceHttp.Request.json("POST", writes(cfg).uri(index(change.target()) + "/batch"), null, body,
        "Algolia's batch on " + change.target(), true);
  }

  @Override
  ObjectNode changed(Call c, Change change, ServiceHttp.Response r) throws ConnectorException {
    JsonNode answer = ServiceHttp.json(r, "Algolia's answer");
    ObjectNode out = F.objectNode().put("delete".equals(change.op()) ? "deleted" : "indexed", change.count());
    long task = answer.path("taskID").asLong(-1);
    out.put("task", task);
    if (task < 0) {
      out.put("applied", false);
      return out;
    }
    long until = System.currentTimeMillis() + Math.min(TASK_WAIT_MILLIS, Math.max(0,
        c.ctx().remaining().toMillis() - 1_000));
    String status = "notPublished";
    while (System.currentTimeMillis() < until) {
      ServiceHttp.Request poll = ServiceHttp.Request.get(writes(c.cfg()).uri(index(change.target()) + "/task/"
          + task), null, "Algolia's task " + task);
      ServiceHttp.Response p = send(c, writes(c.cfg()), poll, 64 * 1024);
      if (!p.ok()) {
        break;
      }
      status = ServiceHttp.json(p, poll.what()).path("status").asString(status);
      if (status.equals("published")) {
        break;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    out.put("applied", status.equals("published"));
    out.put("status", status);
    return out;
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  TestResult test(Call c) {
    long started = System.nanoTime();
    List<ObjectNode> all;
    try {
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
    TestResult.Permissions permissions = permissions(c);
    TestResult result = TestResult.success(latency, "Algolia", "API key", permissions).withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", "Algolia"));
    if (permissions.level() != TestResult.Level.READ) {
      result = result.withHint(new Hint(permissions.level() == TestResult.Level.UNKNOWN
          || c.cfg().env() == ai.msrv.gaterook.connector.api.Env.DEV ? Severity.INFO : Severity.WARNING,
          "algolia.search-key", "/apiKey", "Give agents the Search API key, or a key with search, browse and "
          + "listIndexes only, unless they should write records."));
    }
    if (visible == 0) {
      result = result.withHint(new Hint(Severity.WARNING, "algolia.no-indexes", "/indexes", "No index agents may "
          + "use."));
    }
    return result;
  }

  /**
   * The key's own ACL: Algolia lets any key read its own permissions ({@code GET /1/keys/{key}}). The key is in that
   * path, sent only to the application's own host over TLS like its header, and tainted.
   */
  private TestResult.Permissions permissions(Call c) {
    try {
      String key = secret(c, API_KEY, "API key");
      JsonNode answer = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/1/keys/" + ServiceHttp.seg(key)), null,
          "Algolia's permissions of the key"), 64 * 1024);
      List<String> acl = new ArrayList<>();
      answer.path("acl").forEach(a -> acl.add(a.asString("")));
      List<String> writes = acl.stream().filter(WRITE_ACL::contains).toList();
      if (acl.isEmpty()) {
        return new TestResult.Permissions(TestResult.Level.UNKNOWN, "Algolia didn't list the key's permissions.",
            null, List.of(), null);
      }
      return writes.isEmpty()
          ? new TestResult.Permissions(TestResult.Level.READ, "The key may " + String.join(", ", acl) + ": it can't "
              + "change records.", null, List.of(), null)
          : new TestResult.Permissions(TestResult.Level.WRITE, "The key may " + String.join(", ", acl) + ".",
              "It may change data: " + String.join(", ", writes) + ".", List.of(), null);
    } catch (ConnectorException e) {
      return new TestResult.Permissions(TestResult.Level.UNKNOWN, "The key's permissions aren't readable: "
          + shorten(e.getMessage(), 200), null, List.of(), null);
    }
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    return F.objectNode().put("ids", "objectID")
        .put("search", "Words with filters and facets; sorting comes from replica indices")
        .put("changes", "Applied as tasks: GateRook waits a few seconds and says whether each ran");
  }
}
