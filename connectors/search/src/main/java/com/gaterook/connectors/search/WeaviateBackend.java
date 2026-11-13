package com.gaterook.connectors.search;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.TestResult.Hint;
import com.gaterook.connector.api.TestResult.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Weaviate's REST and GraphQL APIs (docs/vector-databases.md): the schema's collections (classes), objects read and
 * searched through GraphQL {@code Get} — which has no mutations — with a query GateRook writes itself from checked
 * names, the filter tree and quoted values; upserts through {@code /v1/batch/objects} and deletes by id through a
 * batch delete. The API key goes in {@code Authorization: Bearer}; a read-only user or role is the store's own
 * boundary.
 */
final class WeaviateBackend extends VectorBackend implements VectorConnector.Recorded {

  static final String TYPE = "weaviate";
  static final String API_KEY = "/auth/apiKey";
  /** A GraphQL name: a collection must also start with a capital letter. */
  private static final Pattern CLASS = Pattern.compile("[A-Z][_0-9A-Za-z]{0,254}");
  private static final Pattern PROPERTY = Pattern.compile("[_A-Za-z][_0-9A-Za-z]{0,230}");
  private static final Pattern UUID = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  /** Property types GraphQL returns without a selection of their own. */
  private static final Set<String> PLAIN = Set.of("text", "text[]", "string", "string[]", "int", "int[]", "number",
      "number[]", "boolean", "boolean[]", "date", "date[]", "uuid", "uuid[]");
  private static final long MAX_READ = 16L << 20;
  private static final long SCHEMA_TTL_NANOS = 5L * 60 * 1_000_000_000;
  /** Weaviate's QUERY_MAXIMUM_RESULTS by default: offset and limit together. */
  private static final int MAX_WINDOW = 10_000;

  /**
   * A collection as GateRook queries it.
   *
   * @param types       property → its first data type, of every property
   * @param plain       the properties GraphQL returns as values
   * @param named       the named vectors, empty for the one default vector
   * @param vectorizer  whether a vectorizer embeds text (nearText, hybrid)
   */
  record Schema(String name, Map<String, String> types, List<String> plain, List<String> named, boolean vectorizer,
      boolean multiTenant, JsonNode description, long atNanos) {}

  private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

  @Override
  String type() {
    return TYPE;
  }

  @Override
  String name() {
    return "Weaviate";
  }

  @Override
  String description() {
    return "Collections and objects of Weaviate and Weaviate Cloud: filtered pages, vector, BM25 and hybrid search "
        + "through GraphQL. Upserts and deletes wait for approval.";
  }

  @Override
  int localPort() {
    return 8080;
  }

  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException {
    return new ServiceHttp.Endpoint(ServiceHttp.base(cfg.string("/url"),
        "https://abc123.c0.europe-west3.gcp.weaviate.cloud", "/v1"), ca(cfg));
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
      JsonNode errors = body.path("error");
      if (errors.isArray() && !errors.isEmpty()) {
        return shorten(errors.get(0).path("message").asString(""), 500);
      }
      if (body.path("errors").isArray() && !body.get("errors").isEmpty()) {
        return shorten(body.get("errors").get(0).path("message").asString(""), 500);
      }
      String m = body.path("message").asString(null);
      return m == null || m.isBlank() ? null : shorten(m, 500);
    } catch (RuntimeException e) {
      String text = r.text().strip();
      return text.isEmpty() ? null : shorten(text, 300);
    }
  }

  @Override
  String nameProblem(String name) {
    return CLASS.matcher(name).matches() ? null : "A Weaviate collection name starts with a capital letter and has "
        + "letters, digits and _ only.";
  }

  @Override
  String namespaceWord() {
    return "tenant";
  }

  @Override
  String idProblem(JsonNode id) {
    return id.isString() && UUID.matcher(id.asString()).matches() ? null : "A Weaviate object id is a UUID, not "
        + shorten(id.toString(), 60) + ".";
  }

  @Override
  void checkFilter(Filter.Node filter) throws Filter.Invalid {
    Filter.checkFields(filter, PROPERTY, "a Weaviate property name has letters, digits and _ only.");
  }

  @Override
  String queryProblem(Query q) {
    return null;
  }

  @Override
  public String recordProblem(ObjectNode record) {
    JsonNode m = record.get("metadata");
    if (m != null) {
      for (String key : m.propertyNames()) {
        if (!PROPERTY.matcher(key).matches()) {
          return "A Weaviate property name has letters, digits and _ only: " + shorten(key, 60) + " of "
              + record.get("id") + " isn't one.";
        }
      }
    }
    if (record.has("document")) {
      return "A Weaviate object has properties, not a document: put the text in a property of metadata.";
    }
    return null;
  }

  // ── Schema ──────────────────────────────────────────────────────────────────────────────────────────────────

  private Schema schema(Call c, String collection) throws ConnectorException {
    String key = c.cfg().id() + "|" + c.cfg().version() + "|" + collection;
    Schema known = schemas.get(key);
    if (known != null && System.nanoTime() - known.atNanos() < SCHEMA_TTL_NANOS) {
      return known;
    }
    JsonNode d = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/v1/schema/" + ServiceHttp.seg(collection)),
        null, "Weaviate's schema of " + collection), MAX_READ);
    Schema s = schemaOf(collection, d);
    if (schemas.size() > 1_000) {
      schemas.clear();
    }
    schemas.put(key, s);
    return s;
  }

  static Schema schemaOf(String collection, JsonNode d) {
    Map<String, String> types = new LinkedHashMap<>();
    List<String> plain = new ArrayList<>();
    for (JsonNode p : d.path("properties")) {
      String name = p.path("name").asString("");
      String type = p.path("dataType").path(0).asString("");
      types.put(name, type);
      if (PLAIN.contains(type) && PROPERTY.matcher(name).matches()) {
        plain.add(name);
      }
    }
    List<String> named = new ArrayList<>();
    boolean vectorizer = !"none".equals(d.path("vectorizer").asString("none"));
    for (var e : d.path("vectorConfig").properties()) {
      if (PROPERTY.matcher(e.getKey()).matches()) {
        named.add(e.getKey());
      }
      JsonNode v = e.getValue().path("vectorizer");
      if (v.isObject() && !v.isEmpty() && !v.has("none")) {
        vectorizer = true;
      }
    }
    return new Schema(collection, Map.copyOf(types), List.copyOf(plain), List.copyOf(named), vectorizer,
        d.path("multiTenancyConfig").path("enabled").asBoolean(false), d, System.nanoTime());
  }

  // ── GraphQL ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** A GraphQL string literal: JSON's escapes are GraphQL's. */
  static String quote(String s) {
    return ServiceHttp.JSON.writeValueAsString(s);
  }

  /** The filter as a GraphQL {@code where} argument, each value typed by its property's data type. */
  static String where(Filter.Node node, Schema s) throws ConnectorException {
    return switch (node) {
      case Filter.And a -> operands("And", a.nodes(), s);
      case Filter.Or o -> operands("Or", o.nodes(), s);
      case Filter.Cmp cmp -> "{path: [" + quote(cmp.field()) + "], operator: " + switch (cmp.op()) {
        case EQ -> "Equal";
        case NE -> "NotEqual";
        case GT -> "GreaterThan";
        case GTE -> "GreaterThanEqual";
        case LT -> "LessThan";
        case LTE -> "LessThanEqual";
      } + ", " + value(cmp.field(), List.of(cmp.value()), s, false) + "}";
      case Filter.In in -> {
        String any = "{path: [" + quote(in.field()) + "], operator: ContainsAny, " + value(in.field(), in.values(), s,
            true) + "}";
        yield in.not() ? "{operator: Not, operands: [" + any + "]}" : any;
      }
      case Filter.Exists e -> "{path: [" + quote(e.field()) + "], operator: IsNull, valueBoolean: " + !e.exists()
          + "}";
    };
  }

  private static String operands(String op, List<Filter.Node> nodes, Schema s) throws ConnectorException {
    StringBuilder out = new StringBuilder("{operator: ").append(op).append(", operands: [");
    for (int i = 0; i < nodes.size(); i++) {
      out.append(i == 0 ? "" : ", ").append(where(nodes.get(i), s));
    }
    return out.append("]}").toString();
  }

  /** {@code valueText: "x"}, {@code valueNumber: [1.5, 2]}…, by the property's type. */
  private static String value(String field, List<Filter.Value> values, Schema s, boolean list)
      throws ConnectorException {
    String type = field.equals("id") ? "uuid" : s.types().getOrDefault(field, "").replace("[]", "");
    String key;
    List<String> rendered = new ArrayList<>();
    for (Filter.Value v : values) {
      switch (type) {
        case "int" -> {
          if (!(v instanceof Filter.Int)) {
            throw ConnectorException.badInput(field + " holds integers: compare it with an integer.");
          }
          rendered.add(Filter.number(v));
        }
        case "number" -> {
          if (!(v instanceof Filter.Int || v instanceof Filter.Num)) {
            throw ConnectorException.badInput(field + " holds numbers: compare it with a number.");
          }
          rendered.add(v instanceof Filter.Int i ? i.value() + ".0" : Filter.number(v));
        }
        case "boolean" -> {
          if (!(v instanceof Filter.Bool b)) {
            throw ConnectorException.badInput(field + " is true or false.");
          }
          rendered.add(Boolean.toString(b.value()));
        }
        default -> {
          if (!(v instanceof Filter.Text t)) {
            if (s.types().containsKey(field) || field.equals("id")) {
              throw ConnectorException.badInput(field + " holds text: compare it with text.");
            }
            rendered.add(v instanceof Filter.Bool b ? Boolean.toString(b.value()) : Filter.number(v));
          } else {
            rendered.add(quote(t.value()));
          }
        }
      }
    }
    key = switch (type) {
      case "int" -> "valueInt";
      case "number" -> "valueNumber";
      case "boolean" -> "valueBoolean";
      case "date" -> "valueDate";
      default -> {
        if (!s.types().containsKey(field) && !field.equals("id") && !(values.getFirst() instanceof Filter.Text)) {
          yield values.getFirst() instanceof Filter.Bool ? "valueBoolean" : values.getFirst() instanceof Filter.Int
              ? "valueInt" : "valueNumber";
        }
        yield "valueText";
      }
    };
    return key + ": " + (list ? "[" + String.join(", ", rendered) + "]" : rendered.getFirst());
  }

  /** The selection: plain properties, and {@code _additional} with the id, the score and the vectors when asked. */
  private static String selection(Schema s, boolean vectors, String scores) {
    StringBuilder out = new StringBuilder("{ ");
    s.plain().forEach(p -> out.append(p).append(' '));
    out.append("_additional { id ").append(scores);
    if (vectors) {
      if (s.named().isEmpty()) {
        out.append(" vector");
      } else {
        out.append(" vectors { ").append(String.join(" ", s.named())).append(" }");
      }
    }
    return out.append(" } }").toString();
  }

  private JsonNode graphql(Call c, Schema s, String args, String selection, String what) throws ConnectorException {
    String query = "{ Get { " + s.name() + (args.isEmpty() ? "" : "(" + args + ")") + " " + selection + " } }";
    ObjectNode body = F.objectNode().put("query", query);
    JsonNode answer = json(c, ServiceHttp.Request.json("POST", endpoint(c.cfg()).uri("/v1/graphql"), null, body,
        what, false), MAX_READ);
    JsonNode errors = answer.path("errors");
    if (errors.isArray() && !errors.isEmpty()) {
      throw ConnectorException.badInput("Weaviate refused " + what + ": " + ServiceHttp.sentence(shorten(
          errors.get(0).path("message").asString(""), 500)));
    }
    JsonNode objects = answer.path("data").path("Get").path(s.name());
    return objects.isArray() ? objects : F.arrayNode();
  }

  private static ObjectNode record(Schema s, JsonNode o, boolean vectors) {
    ObjectNode out = F.objectNode();
    JsonNode extra = o.path("_additional");
    out.set("id", extra.path("id"));
    for (String score : List.of("distance", "score", "certainty")) {
      JsonNode v = extra.get(score);
      if (v != null && !v.isNull()) {
        out.set("score", v.isString() && v.asString().matches("-?[0-9.eE+-]+") ? F.numberNode(Double.parseDouble(
            v.asString())) : v);
        out.put("scoreKind", score);
        break;
      }
    }
    ObjectNode metadata = out.putObject("metadata");
    for (var e : o.properties()) {
      if (!e.getKey().equals("_additional") && !e.getValue().isNull()) {
        metadata.set(e.getKey(), e.getValue());
      }
    }
    if (vectors) {
      if (extra.has("vector") && !extra.get("vector").isNull()) {
        out.set("vector", extra.get("vector"));
      } else if (extra.path("vectors").isObject()) {
        out.set("vector", extra.get("vectors"));
      }
    }
    return out;
  }

  private static String tenant(String namespace) {
    return namespace == null ? "" : "tenant: " + quote(namespace);
  }

  private static String args(String... parts) {
    List<String> kept = new ArrayList<>();
    for (String p : parts) {
      if (p != null && !p.isEmpty()) {
        kept.add(p);
      }
    }
    return String.join(", ", kept);
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  List<ObjectNode> collections(Call c) throws ConnectorException {
    JsonNode body = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/v1/schema"), null,
        "Weaviate's schema"), MAX_READ);
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode cls : body.path("classes")) {
      Schema s = schemaOf(cls.path("class").asString(""), cls);
      ObjectNode o = F.objectNode().put("name", s.name()).put("properties", s.types().size());
      if (!s.named().isEmpty()) {
        ArrayNode named = o.putArray("namedVectors");
        s.named().forEach(named::add);
      }
      o.put("vectorizer", s.vectorizer());
      if (s.multiTenant()) {
        o.put("multiTenant", true);
      }
      out.add(o);
    }
    return out;
  }

  @Override
  ObjectNode describe(Call c, String collection) throws ConnectorException {
    Schema s = schema(c, collection);
    JsonNode d = s.description();
    ObjectNode out = F.objectNode().put("name", collection);
    if (d.path("description").isString()) {
      out.put("description", d.get("description").asString());
    }
    ArrayNode props = out.putArray("properties");
    for (JsonNode p : d.path("properties")) {
      ObjectNode o = props.addObject().put("name", p.path("name").asString("")).put("type", p.path("dataType")
          .path(0).asString(""));
      if (!PLAIN.contains(o.get("type").asString())) {
        o.put("returned", false);
      }
    }
    out.put("vectorizer", d.path("vectorizer").asString(s.named().isEmpty() ? "none" : "named vectors"));
    if (!s.named().isEmpty()) {
      ObjectNode named = out.putObject("namedVectors");
      for (var e : d.path("vectorConfig").properties()) {
        ObjectNode v = named.putObject(e.getKey());
        v.put("distance", e.getValue().path("vectorIndexConfig").path("distance").asString(""));
        JsonNode vz = e.getValue().path("vectorizer");
        if (vz.isObject() && !vz.isEmpty()) {
          v.put("vectorizer", vz.propertyNames().iterator().next());
        }
      }
    } else {
      out.put("distance", d.path("vectorIndexConfig").path("distance").asString(""));
    }
    out.put("textSearch", s.vectorizer() ? "semantic (nearText), keyword (BM25) and hybrid" : "keyword (BM25) only: "
        + "no vectorizer embeds text");
    if (s.multiTenant()) {
      out.put("multiTenant", true);
      try {
        JsonNode tenants = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/v1/schema/"
            + ServiceHttp.seg(collection) + "/tenants"), null, "Weaviate's tenants of " + collection), MAX_READ);
        ArrayNode list = out.putArray("tenants");
        for (JsonNode t : tenants) {
          if (list.size() >= 100) {
            out.put("tenantsTruncated", true);
            break;
          }
          list.addObject().put("name", t.path("name").asString("")).put("status", t.path("activityStatus")
              .asString(""));
        }
      } catch (ConnectorException e) {
        out.put("tenants", "Not readable: " + shorten(e.getMessage(), 200));
      }
    } else {
      try {
        ObjectNode body = F.objectNode().put("query", "{ Aggregate { " + collection + " { meta { count } } } }");
        JsonNode answer = json(c, ServiceHttp.Request.json("POST", endpoint(c.cfg()).uri("/v1/graphql"), null, body,
            "Weaviate's count of " + collection, false), 1 << 20);
        JsonNode count = answer.path("data").path("Aggregate").path(collection).path(0).path("meta").path("count");
        if (count.isNumber()) {
          out.set("records", count);
        }
      } catch (ConnectorException e) {
        // The count is a nicety.
      }
    }
    return out;
  }

  @Override
  Page get(Call c, Get g) throws ConnectorException {
    Schema s = schema(c, g.collection());
    String sel = selection(s, g.vectors(), "");
    if (!g.ids().isEmpty()) {
      StringBuilder ids = new StringBuilder();
      for (JsonNode id : g.ids()) {
        ids.append(ids.isEmpty() ? "" : ", ").append(quote(id.asString().toLowerCase(java.util.Locale.ROOT)));
      }
      JsonNode objects = graphql(c, s, args("where: {path: [\"id\"], operator: ContainsAny, valueText: [" + ids
          + "]}", "limit: " + g.ids().size(), tenant(g.namespace())), sel, "Weaviate's objects of " + g.collection());
      List<ObjectNode> records = new ArrayList<>();
      List<String> found = new ArrayList<>();
      for (JsonNode o : objects) {
        ObjectNode r = record(s, o, g.vectors());
        records.add(r);
        found.add(r.path("id").asString("").toLowerCase(java.util.Locale.ROOT));
      }
      List<JsonNode> missing = new ArrayList<>();
      for (JsonNode id : g.ids()) {
        if (!found.contains(id.asString().toLowerCase(java.util.Locale.ROOT))) {
          missing.add(id);
        }
      }
      return new Page(records, null, missing);
    }
    String paging;
    int offset = 0;
    if (g.filter() == null) {
      if (g.cursor() != null && !UUID.matcher(g.cursor()).matches()) {
        throw ConnectorException.badInput("cursor is nextCursor of the previous page.");
      }
      paging = g.cursor() == null ? "" : "after: " + quote(g.cursor());
    } else {
      if (g.cursor() != null) {
        if (!g.cursor().matches("\\d{1,5}")) {
          throw ConnectorException.badInput("cursor is nextCursor of the previous page.");
        }
        offset = Integer.parseInt(g.cursor());
      }
      if (offset + g.limit() > MAX_WINDOW) {
        throw ConnectorException.badInput("Weaviate pages through at most " + MAX_WINDOW + " objects of a filter: "
            + "narrow the filter.");
      }
      paging = args("where: " + where(g.filter(), s), offset == 0 ? "" : "offset: " + offset);
    }
    JsonNode objects = graphql(c, s, args(paging, "limit: " + g.limit(), tenant(g.namespace())), sel,
        "Weaviate's objects of " + g.collection());
    List<ObjectNode> records = new ArrayList<>();
    for (JsonNode o : objects) {
      records.add(record(s, o, g.vectors()));
    }
    String next = null;
    if (records.size() == g.limit()) {
      next = g.filter() == null ? records.getLast().path("id").asString(null)
          : offset + 2L * g.limit() <= MAX_WINDOW ? Integer.toString(offset + g.limit()) : null;
    }
    return new Page(records, next, List.of());
  }

  @Override
  List<ObjectNode> query(Call c, Query q) throws ConnectorException {
    Schema s = schema(c, q.collection());
    String target = "";
    if (q.vectorName() != null) {
      if (!s.named().contains(q.vectorName())) {
        throw ConnectorException.badInput(q.collection() + " has no named vector " + q.vectorName()
            + (s.named().isEmpty() ? ": it has one vector." : ": it has " + String.join(", ", s.named()) + "."));
      }
      target = ", targetVectors: [" + quote(q.vectorName()) + "]";
    } else if (s.named().size() > 1 && (q.vector() != null || q.text() != null && q.mode() != TextMode.KEYWORD)) {
      throw ConnectorException.badInput(q.collection() + " has several named vectors (" + String.join(", ", s.named())
          + "): name one with vectorName.");
    }
    String near;
    String scores = "distance";
    if (q.vector() != null) {
      StringBuilder v = new StringBuilder();
      for (Double d : q.vector()) {
        v.append(v.isEmpty() ? "" : ", ").append(d);
      }
      near = "nearVector: {vector: [" + v + "]" + target + "}";
    } else if (q.id() != null) {
      near = "nearObject: {id: " + quote(q.id().asString()) + target + "}";
    } else {
      switch (q.mode()) {
        case KEYWORD -> {
          near = "bm25: {query: " + quote(q.text()) + "}";
          scores = "score";
        }
        case HYBRID -> {
          if (!s.vectorizer()) {
            throw ConnectorException.badInput(q.collection() + " has no vectorizer to embed the text: use textMode "
                + "keyword, or search with a vector.");
          }
          near = "hybrid: {query: " + quote(q.text()) + target + "}";
          scores = "score";
        }
        default -> {
          if (!s.vectorizer()) {
            throw ConnectorException.badInput(q.collection() + " has no vectorizer to embed the text: use textMode "
                + "keyword, or search with a vector.");
          }
          near = "nearText: {concepts: [" + quote(q.text()) + "]" + target + "}";
        }
      }
    }
    String filter = q.filter() == null ? "" : "where: " + where(q.filter(), s);
    JsonNode objects = graphql(c, s, args(near, filter, "limit: " + q.topK(), tenant(q.namespace())),
        selection(s, q.vectors(), scores), "Weaviate's search of " + q.collection());
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode o : objects) {
      out.add(record(s, o, q.vectors()));
    }
    return out;
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    if ("delete".equals(change.op())) {
      ObjectNode body = F.objectNode();
      ObjectNode match = body.putObject("match").put("class", change.target());
      ObjectNode where = match.putObject("where");
      where.putArray("path").add("id");
      where.put("operator", "ContainsAny");
      ArrayNode ids = where.putArray("valueTextArray");
      change.ids().forEach(id -> ids.add(id.asString().toLowerCase(java.util.Locale.ROOT)));
      body.put("output", "verbose").put("dryRun", false);
      return ServiceHttp.Request.json("DELETE", endpoint(cfg).uri("/v1/batch/objects", "tenant", change.namespace()),
          null, body, "Weaviate's delete of objects from " + change.target(), true);
    }
    ObjectNode body = F.objectNode();
    ArrayNode objects = body.putArray("objects");
    for (JsonNode r : change.records()) {
      ObjectNode o = objects.addObject().put("class", change.target());
      o.put("id", r.get("id").asString().toLowerCase(java.util.Locale.ROOT));
      o.set("properties", r.has("metadata") ? r.get("metadata") : F.objectNode());
      JsonNode v = r.get("vector");
      if (v != null && v.isArray()) {
        o.set("vector", v);
      } else if (v != null && v.isObject()) {
        o.set("vectors", v);
      }
      if (change.namespace() != null) {
        o.put("tenant", change.namespace());
      }
    }
    return ServiceHttp.Request.json("POST", endpoint(cfg).uri("/v1/batch/objects"), null, body,
        "Weaviate's upsert of objects into " + change.target(), true);
  }

  @Override
  ObjectNode changed(Change change, ServiceHttp.Response r) throws ConnectorException {
    JsonNode answer = ServiceHttp.json(r, "Weaviate's answer");
    ObjectNode out = F.objectNode();
    ArrayNode failed = F.arrayNode();
    if ("delete".equals(change.op())) {
      JsonNode results = answer.path("results");
      for (JsonNode o : results.path("objects")) {
        if (!"SUCCESS".equals(o.path("status").asString(""))) {
          failed.addObject().put("id", o.path("id").asString("")).put("error", shorten(o.path("errors").path("error")
              .path(0).path("message").asString(o.path("status").asString("")), 300));
        }
      }
      out.put("deleted", results.path("successful").asInt(0));
      out.put("matched", results.path("matches").asInt(0));
      if (results.path("matches").asInt(0) < change.count()) {
        out.put("note", (change.count() - results.path("matches").asInt(0)) + " ids matched no object.");
      }
    } else {
      int ok = 0;
      for (JsonNode o : answer) {
        JsonNode errors = o.path("result").path("errors").path("error");
        if (errors.isArray() && !errors.isEmpty()) {
          failed.addObject().put("id", o.path("id").asString("")).put("error", shorten(errors.get(0).path("message")
              .asString(""), 300));
        } else {
          ok++;
        }
      }
      if (ok == 0 && !failed.isEmpty()) {
        throw ConnectorException.badInput("Weaviate refused every object, so nothing changed: " + shorten(
            failed.get(0).path("error").asString(""), 300));
      }
      out.put("upserted", ok);
    }
    if (!failed.isEmpty()) {
      out.set("failed", failed);
    }
    return out;
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  TestResult test(Call c) {
    long started = System.nanoTime();
    JsonNode meta;
    try {
      meta = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/v1/meta"), null, "Weaviate's meta"), 4 << 20);
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null);
    }
    long latency = (System.nanoTime() - started) / 1_000_000;
    String version = meta.path("version").asString(null);
    if (version == null) {
      return TestResult.failure("weaviate.not-weaviate", "The server doesn't answer like Weaviate: check the URL.",
          null);
    }
    String server = "Weaviate " + version;
    List<TestResult.Check> checks = new ArrayList<>();
    checks.add(new TestResult.Check("server", true, latency, server));
    long listed = System.nanoTime();
    List<ObjectNode> all;
    try {
      all = collections(c);
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null).withChecks(checks);
    }
    long visible = all.stream().filter(o -> allows(c.cfg(), o.path("name").asString(""))).count();
    checks.add(new TestResult.Check("collections", true, (System.nanoTime() - listed) / 1_000_000,
        count(visible, "collection") + " agents may use"));
    String identity = keyed(c.cfg()) ? "API key" : "no credentials";
    TestResult.Permissions permissions = keyed(c.cfg())
        ? new TestResult.Permissions(TestResult.Level.UNKNOWN, "Weaviate's roles or admin list decide what the key "
            + "may do.", null, List.of(), null)
        : new TestResult.Permissions(TestResult.Level.ADMIN, "The server checks no key: anyone who reaches it may "
            + "read, write and delete.", null, List.of(), null);
    if (keyed(c.cfg())) {
      try {
        JsonNode own = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/v1/users/own-info"), null,
            "Weaviate's user"), 1 << 20);
        String user = own.path("username").asString(null);
        if (user != null && !user.isBlank()) {
          identity = user;
        }
      } catch (ConnectorException e) {
        // An older Weaviate: the key is all there is to show.
      }
    }
    TestResult result = TestResult.success(latency, server, identity, permissions).withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", server));
    if (!keyed(c.cfg())) {
      result = result.withHint(new Hint(Severity.WARNING, "weaviate.no-auth", "/auth/method", "The server checks no "
          + "key: anyone who reaches it may write and delete. Turn on API keys and give agents a read-only user."));
    } else {
      result = result.withHint(new Hint(Severity.INFO, "weaviate.read-only-key", "/auth/apiKey", "Give agents the "
          + "key of a read-only user or role unless they should upsert and delete."));
    }
    List<Hint> plain = new ArrayList<>();
    QdrantBackend.plainHint(c.cfg(), plain);
    for (Hint h : plain) {
      result = result.withHint(h);
    }
    if (visible == 0) {
      result = result.withHint(new Hint(Severity.WARNING, "weaviate.no-collections", "/collections", "No collection "
          + "agents may use."));
    }
    return result;
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    return F.objectNode().put("ids", "UUIDs")
        .put("search", "By vector (nearVector), by the id of a stored object (nearObject), or by text: semantic "
            + "with a vectorizer, keyword (BM25), hybrid")
        .put("namespace", "The tenant of a multi-tenant collection");
  }

  @Override
  void forget(String connectionId) {
    schemas.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
  }
}
