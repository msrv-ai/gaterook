package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Qdrant's REST API (docs/vector-databases.md): collections, points scrolled, retrieved and searched with the universal
 * query API ({@code /points/query}, Qdrant 1.10 and later), upserts and deletes by id with {@code wait=true}. The API
 * key goes in {@code api-key}; a read-only key (Qdrant's {@code read_only_api_key}, or a JWT without write access) is
 * the store's own boundary.
 */
final class QdrantBackend extends VectorBackend {

  static final String TYPE = "qdrant";
  static final String API_KEY = "/auth/apiKey";
  /** A collection name: Qdrant refuses {@code /}, and GateRook control characters and dot names. */
  private static final Pattern NAME = Pattern.compile("[^/\\\\\\x00-\\x1f\\x7f]{1,255}");
  private static final Pattern UUID = Pattern.compile(
      "[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");
  /** A payload key: dotted paths into objects, {@code []} into lists. */
  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_\\-]+(\\[\\])?(\\.[A-Za-z0-9_\\-]+(\\[\\])?)*");
  private static final long MAX_READ = 16L << 20;

  @Override
  String type() {
    return TYPE;
  }

  @Override
  String name() {
    return "Qdrant";
  }

  @Override
  String description() {
    return "Collections and points of Qdrant and Qdrant Cloud: filtered pages, nearest-neighbour search by vector or "
        + "point. Upserts and deletes wait for approval.";
  }

  @Override
  int localPort() {
    return 6333;
  }

  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException {
    return new ServiceHttp.Endpoint(ServiceHttp.base(cfg.string("/url"), "https://xyz.cloud.qdrant.io:6333",
        "/collections"), ca(cfg));
  }

  private static boolean keyed(ConnectionConfig cfg) {
    return !"none".equals(cfg.string("/auth/method"));
  }

  @Override
  Map<String, List<String>> auth(Call c) throws ConnectorException {
    return keyed(c.cfg()) ? ServiceHttp.headers("api-key", secret(c, API_KEY, "API key")) : Map.of();
  }

  @Override
  List<String> authShown(ConnectionConfig cfg) {
    return keyed(cfg) ? List.of("api-key: ‹secret›") : List.of();
  }

  @Override
  String said(ServiceHttp.Response r) {
    try {
      JsonNode body = ServiceHttp.JSON.readTree(r.body());
      String error = body.path("status").path("error").asString(null);
      return error == null ? null : shorten(error, 500);
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  String nameProblem(String name) {
    if (!NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
      return "A Qdrant collection name has 1 to 255 characters, without / or control characters.";
    }
    return null;
  }

  @Override
  String namespaceWord() {
    return null;
  }

  @Override
  String idProblem(JsonNode id) {
    if (id.isIntegralNumber()) {
      return id.asLong() < 0 ? "A Qdrant point id is an unsigned integer or a UUID." : null;
    }
    return UUID.matcher(id.asString()).matches() ? null : "A Qdrant point id is an unsigned integer or a UUID, not "
        + shorten(id.asString(), 60) + ".";
  }

  @Override
  void checkFilter(Filter.Node filter) throws Filter.Invalid {
    Filter.checkFields(filter, KEY, "a Qdrant payload key is letters, digits, _ and -, with . into objects and [] "
        + "into lists.");
  }

  @Override
  String queryProblem(Query q) {
    return q.text() != null ? "Qdrant searches by vector or by point: pass vector, or the id of a stored point." : null;
  }

  private ServiceHttp.Request post(ConnectionConfig cfg, String path, JsonNode body, String what, boolean change,
      String... query) throws ConnectorException {
    return ServiceHttp.Request.json("POST", endpoint(cfg).uri(path, query), null, body, what, change);
  }

  private static String points(String collection) {
    return "/collections/" + ServiceHttp.seg(collection) + "/points";
  }

  @Override
  List<ObjectNode> collections(Call c) throws ConnectorException {
    JsonNode body = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/collections"), null,
        "Qdrant's collection list"), MAX_READ);
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode coll : body.path("result").path("collections")) {
      out.add(F.objectNode().put("name", coll.path("name").asString("")));
    }
    return out;
  }

  @Override
  ObjectNode describe(Call c, String collection) throws ConnectorException {
    JsonNode result = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/collections/"
        + ServiceHttp.seg(collection)), null, "Qdrant's collection " + collection), MAX_READ).path("result");
    ObjectNode out = F.objectNode().put("name", collection).put("status", result.path("status").asString(""));
    if (result.has("points_count")) {
      out.set("records", result.get("points_count"));
    }
    JsonNode params = result.path("config").path("params");
    JsonNode vectors = params.path("vectors");
    if (vectors.has("size")) {
      out.putObject("vector").put("dimension", vectors.path("size").asInt()).put("metric", vectors.path("distance")
          .asString(""));
    } else {
      ObjectNode shown = out.putObject("vectors");
      for (var e : vectors.properties()) {
        shown.putObject(e.getKey()).put("dimension", e.getValue().path("size").asInt()).put("metric",
            e.getValue().path("distance").asString(""));
      }
    }
    if (params.path("sparse_vectors").isObject() && !params.path("sparse_vectors").isEmpty()) {
      ArrayNode sparse = out.putArray("sparseVectors");
      params.path("sparse_vectors").propertyNames().forEach(sparse::add);
    }
    ObjectNode indexed = out.putObject("indexedPayload");
    for (var e : result.path("payload_schema").properties()) {
      indexed.put(e.getKey(), e.getValue().path("data_type").asString(""));
    }
    out.put("filters", "Any payload key; Qdrant filters indexed keys fast and others by scanning.");
    return out;
  }

  @Override
  Page get(Call c, Get g) throws ConnectorException {
    if (!g.ids().isEmpty()) {
      ObjectNode body = F.objectNode();
      ArrayNode ids = body.putArray("ids");
      g.ids().forEach(ids::add);
      body.put("with_payload", true).put("with_vector", g.vectors());
      JsonNode result = json(c, post(c.cfg(), points(g.collection()), body, "Qdrant's points of " + g.collection(),
          false), MAX_READ).path("result");
      List<ObjectNode> records = new ArrayList<>();
      List<String> found = new ArrayList<>();
      for (JsonNode p : result) {
        records.add(record(p, g.vectors()));
        found.add(p.path("id").toString());
      }
      List<JsonNode> missing = new ArrayList<>();
      for (JsonNode id : g.ids()) {
        if (!found.contains(id.toString()) && !found.contains(normalizedUuid(id))) {
          missing.add(id);
        }
      }
      return new Page(records, null, missing);
    }
    ObjectNode body = F.objectNode().put("limit", g.limit()).put("with_payload", true).put("with_vector",
        g.vectors());
    if (g.cursor() != null) {
      body.set("offset", cursor(g.cursor()));
    }
    if (g.filter() != null) {
      body.set("filter", filter(g.filter()));
    }
    JsonNode result = json(c, post(c.cfg(), points(g.collection()) + "/scroll", body, "Qdrant's points of "
        + g.collection(), false), MAX_READ).path("result");
    List<ObjectNode> records = new ArrayList<>();
    for (JsonNode p : result.path("points")) {
      records.add(record(p, g.vectors()));
    }
    JsonNode next = result.get("next_page_offset");
    return new Page(records, next == null || next.isNull() ? null : next.isString() ? next.asString()
        : next.toString(), List.of());
  }

  /** A cursor as Qdrant reads an offset: the point id where the next page starts. */
  private static JsonNode cursor(String cursor) throws ConnectorException {
    if (cursor.matches("\\d{1,19}")) {
      return F.numberNode(Long.parseLong(cursor));
    }
    if (UUID.matcher(cursor).matches()) {
      return F.stringNode(cursor);
    }
    throw ConnectorException.badInput("cursor is nextCursor of the previous page.");
  }

  private static String normalizedUuid(JsonNode id) {
    if (!id.isString()) {
      return id.toString();
    }
    String s = id.asString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
    if (s.length() != 32) {
      return id.toString();
    }
    return "\"" + s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20)
        + "-" + s.substring(20) + "\"";
  }

  private static ObjectNode record(JsonNode p, boolean vectors) {
    ObjectNode out = F.objectNode();
    out.set("id", p.get("id"));
    if (p.has("score")) {
      out.set("score", p.get("score"));
    }
    out.set("metadata", p.path("payload").isObject() ? p.get("payload") : F.objectNode());
    if (vectors && p.has("vector") && !p.get("vector").isNull()) {
      out.set("vector", p.get("vector"));
    }
    return out;
  }

  @Override
  List<ObjectNode> query(Call c, Query q) throws ConnectorException {
    ObjectNode body = F.objectNode();
    if (q.vector() != null) {
      ArrayNode v = body.putArray("query");
      q.vector().forEach(v::add);
    } else {
      body.set("query", q.id());
    }
    if (q.vectorName() != null) {
      body.put("using", q.vectorName());
    }
    if (q.filter() != null) {
      body.set("filter", filter(q.filter()));
    }
    body.put("limit", q.topK()).put("with_payload", true).put("with_vector", q.vectors());
    JsonNode result = json(c, post(c.cfg(), points(q.collection()) + "/query", body, "Qdrant's search of "
        + q.collection(), false), MAX_READ).path("result");
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode p : result.path("points")) {
      out.add(record(p, q.vectors()));
    }
    return out;
  }

  /** The filter as Qdrant's {@code must}, {@code should} and {@code must_not}. */
  static ObjectNode filter(Filter.Node node) {
    ObjectNode out = F.objectNode();
    switch (node) {
      case Filter.And a -> {
        ArrayNode must = out.putArray("must");
        for (Filter.Node n : a.nodes()) {
          must.add(clause(n));
        }
      }
      case Filter.Or o -> {
        ArrayNode should = out.putArray("should");
        for (Filter.Node n : o.nodes()) {
          should.add(clause(n));
        }
      }
      default -> out.putArray("must").add(clause(node));
    }
    return out;
  }

  /** One condition, or a nested filter for $and/$or and the negated ones. */
  private static ObjectNode clause(Filter.Node node) {
    return switch (node) {
      case Filter.And a -> filter(a);
      case Filter.Or o -> filter(o);
      case Filter.Cmp cmp -> switch (cmp.op()) {
        case EQ -> match(cmp.field(), cmp.value());
        case NE -> {
          ObjectNode not = F.objectNode();
          not.putArray("must_not").add(match(cmp.field(), cmp.value()));
          yield not;
        }
        default -> {
          ObjectNode range = F.objectNode();
          String bound = switch (cmp.op()) {
            case GT -> "gt";
            case GTE -> "gte";
            case LT -> "lt";
            default -> "lte";
          };
          range.set(bound, cmp.value().json());
          ObjectNode out = F.objectNode().put("key", cmp.field());
          out.set("range", range);
          yield out;
        }
      };
      case Filter.In in -> {
        ObjectNode out = F.objectNode().put("key", in.field());
        ArrayNode values = F.arrayNode();
        in.values().forEach(v -> values.add(v.json()));
        out.putObject("match").set(in.not() ? "except" : "any", values);
        yield out;
      }
      case Filter.Exists e -> {
        ObjectNode empty = F.objectNode();
        empty.putObject("is_empty").put("key", e.field());
        if (!e.exists()) {
          yield empty;
        }
        ObjectNode not = F.objectNode();
        not.putArray("must_not").add(empty);
        yield not;
      }
    };
  }

  private static ObjectNode match(String field, Filter.Value value) {
    ObjectNode out = F.objectNode().put("key", field);
    if (value instanceof Filter.Num n) {
      // Qdrant matches keywords, integers and booleans exactly; a number with a fraction is a range of one value.
      ObjectNode range = out.putObject("range");
      range.put("gte", n.value()).put("lte", n.value());
      return out;
    }
    out.putObject("match").set("value", value.json());
    return out;
  }

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    if ("delete".equals(change.op())) {
      ObjectNode body = F.objectNode();
      body.set("points", change.ids());
      return post(cfg, points(change.target()) + "/delete", body, "Qdrant's delete of points from "
          + change.target(), true, "wait", "true");
    }
    ObjectNode body = F.objectNode();
    ArrayNode points = body.putArray("points");
    for (JsonNode r : change.records()) {
      ObjectNode p = points.addObject();
      p.set("id", r.get("id"));
      if (r.has("vector")) {
        p.set("vector", r.get("vector"));
      }
      p.set("payload", r.has("metadata") ? r.get("metadata") : F.objectNode());
    }
    return ServiceHttp.Request.json("PUT", endpoint(cfg).uri(points(change.target()), "wait", "true"), null, body,
        "Qdrant's upsert of points into " + change.target(), true);
  }

  @Override
  ObjectNode changed(Change change, ServiceHttp.Response r) throws ConnectorException {
    JsonNode body = ServiceHttp.json(r, "Qdrant's answer");
    String status = body.path("result").path("status").asString("");
    ObjectNode out = F.objectNode();
    out.put("delete".equals(change.op()) ? "deleted" : "upserted", change.count());
    out.put("status", status);
    if (body.path("result").has("operation_id")) {
      out.set("operation", body.path("result").get("operation_id"));
    }
    if (!"completed".equals(status)) {
      out.put("note", "Qdrant accepted the change but hadn't applied it when it answered (" + status + ").");
    }
    return out;
  }

  @Override
  ai.msrv.gaterook.connector.api.TestResult test(Call c) {
    long started = System.nanoTime();
    List<TestResult.Check> checks = new ArrayList<>();
    JsonNode root;
    try {
      root = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/"), null, "Qdrant's version"), 64 * 1024);
    } catch (ConnectorException e) {
      return TestResult.failure(code(e), e.getMessage(), null);
    }
    long latency = (System.nanoTime() - started) / 1_000_000;
    String version = root.path("version").asString(null);
    if (version == null || !root.path("title").asString("").toLowerCase(java.util.Locale.ROOT).contains("qdrant")) {
      return TestResult.failure("qdrant.not-qdrant", "The server at " + targetOf(c.cfg()) + " doesn't answer like "
          + "Qdrant. Check the URL: Qdrant's REST port is 6333.", null);
    }
    String server = "Qdrant " + version;
    checks.add(new TestResult.Check("server", true, latency, server));
    List<ObjectNode> all;
    long listed = System.nanoTime();
    try {
      all = collections(c);
    } catch (ConnectorException e) {
      return TestResult.failure(code(e), e.getMessage(), null).withChecks(checks);
    }
    long visible = all.stream().filter(o -> allows(c.cfg(), o.path("name").asString(""))).count();
    checks.add(new TestResult.Check("collections", true, (System.nanoTime() - listed) / 1_000_000,
        count(visible, "collection") + " agents may use"));
    TestResult.Permissions permissions = keyed(c.cfg())
        ? new TestResult.Permissions(TestResult.Level.UNKNOWN, "Qdrant doesn't say what a key may do: a read-only "
            + "key refuses writes itself.", null, List.of(), null)
        : new TestResult.Permissions(TestResult.Level.ADMIN, "The server checks no key: anyone who reaches it may "
            + "read, write and delete.", null, List.of(), null);
    TestResult result = TestResult.success(latency, server, keyed(c.cfg()) ? "API key" : "no credentials",
        permissions).withChecks(checks).withMetadata(F.objectNode().put("serverVersion", server));
    for (Hint h : hints(c.cfg(), visible)) {
      result = result.withHint(h);
    }
    return result;
  }

  private List<Hint> hints(ConnectionConfig cfg, long visible) {
    List<Hint> out = new ArrayList<>();
    if (!keyed(cfg)) {
      out.add(new Hint(Severity.WARNING, "qdrant.no-auth", "/auth/method", "The server checks no API key: anyone "
          + "who reaches it may write and delete. Set service.api_key and a read_only_api_key for agents."));
    } else {
      out.add(new Hint(Severity.INFO, "qdrant.read-only-key", "/auth/apiKey", "Give agents Qdrant's read-only API "
          + "key (or a JWT without write access) unless they should upsert and delete."));
    }
    plainHint(cfg, out);
    if (visible == 0) {
      out.add(new Hint(Severity.WARNING, "qdrant.no-collections", "/collections", "No collection agents may use."));
    }
    return out;
  }

  static void plainHint(ConnectionConfig cfg, List<Hint> out) {
    String url = cfg.string("/url");
    if (url != null && url.strip().toLowerCase(java.util.Locale.ROOT).startsWith("http://")) {
      out.add(new Hint(Severity.INFO, "search.plain-http", "/url", "Plain http:// on this computer: the key travels "
          + "unencrypted over loopback."));
    }
  }

  private String targetOf(ConnectionConfig cfg) {
    try {
      return endpoint(cfg).target();
    } catch (ConnectorException e) {
      return cfg.name();
    }
  }

  static String code(ConnectorException e) {
    return switch (e.kind()) {
      case RESOURCE_UNAVAILABLE -> "search.unreachable";
      case DENIED -> "search.denied";
      case TIMEOUT -> "search.timeout";
      default -> "search.read";
    };
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    return F.objectNode().put("ids", "Unsigned integers or UUIDs")
        .put("search", "By vector or by the id of a stored point; vectorName picks a named vector");
  }
}
