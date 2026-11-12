package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Milvus's RESTful API v2 (docs/vector-databases.md), also Zilliz Cloud's: collections of one database, entities got by
 * primary key or by a filter expression GateRook writes from the filter tree, vector and full-text search, upserts and
 * deletes by primary key. Milvus answers HTTP 200 with a {@code code} of its own; anything but 0 is an error. The
 * token ({@code user:password}, or a Zilliz Cloud API key) goes in {@code Authorization: Bearer}; a user whose role may
 * only read is the store's own boundary.
 */
final class MilvusBackend extends VectorBackend {

  static final String TYPE = "milvus";
  static final String TOKEN = "/auth/token";
  static final String PASSWORD = "/auth/password";
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,254}");
  private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,254}");
  private static final Set<String> VECTOR_TYPES = Set.of("FloatVector", "BinaryVector", "Float16Vector",
      "BFloat16Vector", "SparseFloatVector", "Int8Vector");
  private static final long MAX_READ = 16L << 20;
  private static final long SCHEMA_TTL_NANOS = 5L * 60 * 1_000_000_000;
  /** Milvus refuses a query whose offset and limit add up to more. */
  private static final int MAX_WINDOW = 16_384;

  /** A collection's fields as GateRook uses them. */
  record Schema(String primaryKey, String primaryType, List<String> vectorFields, List<String> scalarFields,
      Map<String, String> textFunctions, JsonNode description, long atNanos) {

    /** The output field of the function that turns text into the vectors of {@code mode}, or {@code null}. */
    String textField(TextMode mode) {
      return textFunctions.get(mode == TextMode.KEYWORD ? "BM25" : "TextEmbedding");
    }
  }

  private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

  @Override
  String type() {
    return TYPE;
  }

  @Override
  String name() {
    return "Milvus";
  }

  @Override
  String description() {
    return "Collections and entities of Milvus and Zilliz Cloud: filtered pages, vector and full-text search. "
        + "Upserts and deletes wait for approval.";
  }

  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException {
    return new ServiceHttp.Endpoint(ServiceHttp.base(cfg.string("/url"),
        "https://in03-0123456789abcdef.serverless.gcp-us-west1.cloud.zilliz.com", "/v2/vectordb", "/v1/vector"),
        ca(cfg));
  }

  private static String method(ConnectionConfig cfg) {
    String m = cfg.string("/auth/method");
    return m == null ? "token" : m;
  }

  private static String database(ConnectionConfig cfg) {
    String db = cfg.string("/database");
    return db == null || db.isBlank() ? "default" : db;
  }

  @Override
  Map<String, List<String>> auth(Call c) throws ConnectorException {
    return switch (method(c.cfg())) {
      case "none" -> Map.of();
      case "password" -> {
        String user = c.cfg().string("/auth/username");
        if (user == null || user.isBlank() || user.contains(":")) {
          throw ConnectorException.badInput("Enter the Milvus user, without a colon.");
        }
        String token = user + ":" + secret(c, PASSWORD, "password");
        c.ctx().taint(token);
        yield ServiceHttp.headers("Authorization", "Bearer " + token);
      }
      default -> ServiceHttp.headers("Authorization", "Bearer " + secret(c, TOKEN, "API key or token"));
    };
  }

  @Override
  List<String> authShown(ConnectionConfig cfg) {
    return "none".equals(method(cfg)) ? List.of() : List.of("Authorization: Bearer ‹secret›");
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
    return NAME.matcher(name).matches() ? null : "A Milvus collection name starts with a letter or _ and has letters, "
        + "digits and _ only.";
  }

  @Override
  String namespaceWord() {
    return "partition";
  }

  @Override
  String idProblem(JsonNode id) {
    return null;
  }

  @Override
  void checkFilter(Filter.Node filter) throws Filter.Invalid {
    Filter.checkFields(filter, FIELD, "a Milvus field name starts with a letter or _ and has letters, digits and _ "
        + "only.");
  }

  @Override
  String queryProblem(Query q) {
    return q.text() != null && q.mode() == TextMode.HYBRID ? "Milvus searches a text by one function: textMode is "
        + "semantic (a text embedding function) or keyword (BM25)." : null;
  }

  // ── Requests ────────────────────────────────────────────────────────────────────────────────────────────────

  private ServiceHttp.Request post(ConnectionConfig cfg, String path, ObjectNode body, String what, boolean change)
      throws ConnectorException {
    return ServiceHttp.Request.json("POST", endpoint(cfg).uri("/v2/vectordb/" + path), null, body, what, change);
  }

  private ObjectNode body(ConnectionConfig cfg, String collection) {
    ObjectNode b = F.objectNode().put("dbName", database(cfg));
    if (collection != null) {
      b.put("collectionName", collection);
    }
    return b;
  }

  /** Sends a request and returns {@code data} of an answer whose code is 0. */
  private JsonNode milvus(Call c, ServiceHttp.Request r) throws ConnectorException {
    JsonNode answer = json(c, r, MAX_READ);
    int code = answer.path("code").asInt(-1);
    if (code != 0 && code != 200) {
      throw error(c.cfg(), r, code, answer.path("message").asString(""));
    }
    return answer.path("data");
  }

  private ConnectorException error(ConnectionConfig cfg, ServiceHttp.Request r, int code, String message) {
    String text = "Milvus refused " + r.what() + " (code " + code + "): " + ServiceHttp.sentence(shorten(message,
        500));
    String lower = message.toLowerCase(java.util.Locale.ROOT);
    if (code == 1800 || lower.contains("auth") && (lower.contains("fail") || lower.contains("unauthenticated"))) {
      return ConnectorException.denied("Milvus refused the credentials of " + cfg.name() + ": " + shorten(message,
          300) + ". Check them under Credentials.");
    }
    if (lower.contains("permission") || lower.contains("privilege") || lower.contains("denied")) {
      return ConnectorException.denied(text + (r.change() ? " The credentials may not change data: nothing changed."
          : " The credentials may not do this."));
    }
    if (lower.contains("not loaded")) {
      return ConnectorException.badInput(text + " Load the collection in Milvus first (Attu, or load_collection).");
    }
    return ConnectorException.badInput(text);
  }

  private Schema schema(Call c, String collection) throws ConnectorException {
    String key = c.cfg().id() + "|" + c.cfg().version() + "|" + collection;
    Schema known = schemas.get(key);
    if (known != null && System.nanoTime() - known.atNanos() < SCHEMA_TTL_NANOS) {
      return known;
    }
    JsonNode d = milvus(c, post(c.cfg(), "collections/describe", body(c.cfg(), collection), "Milvus's description "
        + "of " + collection, false));
    String pk = null;
    String pkType = null;
    List<String> vectors = new ArrayList<>();
    List<String> scalars = new ArrayList<>();
    for (JsonNode f : d.path("fields")) {
      String name = f.path("name").asString("");
      String type = f.path("type").asString("");
      if (f.path("primaryKey").asBoolean(false)) {
        pk = name;
        pkType = type;
      }
      if (VECTOR_TYPES.contains(type)) {
        vectors.add(name);
      } else {
        scalars.add(name);
      }
    }
    if (pk == null) {
      throw ConnectorException.resourceError("Milvus described " + collection + " without a primary key.", null);
    }
    Map<String, String> functions = new java.util.LinkedHashMap<>();
    for (JsonNode fn : d.path("functions")) {
      String type = fn.path("type").asString("");
      JsonNode outputs = fn.path("outputFieldNames");
      if (outputs.isArray() && !outputs.isEmpty()) {
        functions.putIfAbsent(type.equals("BM25") ? "BM25" : type.equals("TextEmbedding") ? "TextEmbedding" : type,
            outputs.get(0).asString(""));
      }
    }
    Schema s = new Schema(pk, pkType, List.copyOf(vectors), List.copyOf(scalars), Map.copyOf(functions), d,
        System.nanoTime());
    if (schemas.size() > 1_000) {
      schemas.clear();
    }
    schemas.put(key, s);
    return s;
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  List<ObjectNode> collections(Call c) throws ConnectorException {
    JsonNode data = milvus(c, post(c.cfg(), "collections/list", body(c.cfg(), null), "Milvus's collection list",
        false));
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode n : data) {
      out.add(F.objectNode().put("name", n.asString("")));
    }
    return out;
  }

  @Override
  ObjectNode describe(Call c, String collection) throws ConnectorException {
    Schema s = schema(c, collection);
    JsonNode d = s.description();
    ObjectNode out = F.objectNode().put("name", collection).put("database", database(c.cfg()));
    if (d.path("description").isString() && !d.get("description").asString().isEmpty()) {
      out.put("description", d.get("description").asString());
    }
    out.put("primaryKey", s.primaryKey()).put("primaryKeyType", s.primaryType());
    ArrayNode fields = out.putArray("fields");
    for (JsonNode f : d.path("fields")) {
      ObjectNode o = fields.addObject().put("name", f.path("name").asString("")).put("type", f.path("type")
          .asString(""));
      for (JsonNode p : f.path("params")) {
        if (p.path("key").asString("").equals("dim")) {
          o.put("dimension", p.path("value").asInt());
        }
        if (p.path("key").asString("").equals("max_length")) {
          o.put("maxLength", p.path("value").asInt());
        }
      }
      if (f.path("nullable").asBoolean(false)) {
        o.put("nullable", true);
      }
    }
    if (d.path("enableDynamicField").asBoolean(false)) {
      out.put("dynamicFields", "Fields of no column are stored and filtered by name too.");
    }
    ArrayNode indexes = out.putArray("indexes");
    for (JsonNode i : d.path("indexes")) {
      indexes.addObject().put("field", i.path("fieldName").asString("")).put("metric", i.path("metricType")
          .asString(""));
    }
    if (!s.textFunctions().isEmpty()) {
      ObjectNode text = out.putObject("textSearch");
      s.textFunctions().forEach(text::put);
    }
    out.put("loaded", d.path("load").asString(""));
    ArrayNode partitions = out.putArray("partitions");
    try {
      for (JsonNode p : milvus(c, post(c.cfg(), "partitions/list", body(c.cfg(), collection), "Milvus's partitions "
          + "of " + collection, false))) {
        partitions.add(p.asString(""));
      }
    } catch (ConnectorException e) {
      out.remove("partitions");
    }
    try {
      ObjectNode count = body(c.cfg(), collection).put("filter", "");
      count.putArray("outputFields").add("count(*)");
      JsonNode rows = milvus(c, post(c.cfg(), "entities/query", count, "Milvus's count of " + collection, false));
      if (rows.isArray() && !rows.isEmpty()) {
        out.set("records", rows.get(0).path("count(*)"));
      }
    } catch (ConnectorException e) {
      out.put("records", "Unknown: " + shorten(e.getMessage(), 200));
    }
    out.put("score", "Milvus's distance by the index's metric: higher is nearer for COSINE and IP, lower for L2.");
    return out;
  }

  private static void partition(ObjectNode body, String namespace) {
    if (namespace != null) {
      body.putArray("partitionNames").add(namespace);
    }
  }

  /**
   * The output fields: every field, dynamic ones included, which only {@code *} names in every version; vectors not
   * asked for are dropped from the answer ({@link #record}).
   */
  private static ArrayNode output(Schema s, boolean vectors) {
    ArrayNode out = F.arrayNode();
    if (vectors || s.description().path("enableDynamicField").asBoolean(false)) {
      out.add("*");
    } else {
      s.scalarFields().forEach(out::add);
    }
    return out;
  }

  @Override
  Page get(Call c, Get g) throws ConnectorException {
    Schema s = schema(c, g.collection());
    if (!g.ids().isEmpty()) {
      ObjectNode body = body(c.cfg(), g.collection());
      body.put("filter", in(s, g.ids()));
      body.set("outputFields", output(s, g.vectors()));
      body.put("limit", g.ids().size());
      partition(body, g.namespace());
      JsonNode rows = milvus(c, post(c.cfg(), "entities/query", body, "Milvus's entities of " + g.collection(),
          false));
      List<ObjectNode> records = new ArrayList<>();
      List<String> found = new ArrayList<>();
      for (JsonNode row : rows) {
        ObjectNode r = record(s, row, null, g.vectors());
        records.add(r);
        found.add(r.get("id").asString());
      }
      List<JsonNode> missing = new ArrayList<>();
      for (JsonNode id : g.ids()) {
        if (!found.contains(id.asString())) {
          missing.add(id);
        }
      }
      return new Page(records, null, missing);
    }
    int offset = 0;
    if (g.cursor() != null) {
      if (!g.cursor().matches("\\d{1,5}")) {
        throw ConnectorException.badInput("cursor is nextCursor of the previous page.");
      }
      offset = Integer.parseInt(g.cursor());
    }
    if (offset + g.limit() > MAX_WINDOW) {
      throw ConnectorException.badInput("Milvus pages through at most " + MAX_WINDOW + " entities of a filter: "
          + "narrow the filter.");
    }
    ObjectNode body = body(c.cfg(), g.collection());
    body.put("filter", g.filter() == null ? "" : expression(g.filter()));
    body.set("outputFields", output(s, g.vectors()));
    body.put("limit", g.limit()).put("offset", offset);
    partition(body, g.namespace());
    JsonNode rows = milvus(c, post(c.cfg(), "entities/query", body, "Milvus's entities of " + g.collection(),
        false));
    List<ObjectNode> records = new ArrayList<>();
    for (JsonNode row : rows) {
      records.add(record(s, row, null, g.vectors()));
    }
    String next = records.size() == g.limit() && offset + 2L * g.limit() <= MAX_WINDOW
        ? Integer.toString(offset + g.limit()) : null;
    return new Page(records, next, List.of());
  }

  /** {@code pk in [...]}, the ids quoted for a VarChar key. */
  private static String in(Schema s, List<JsonNode> ids) throws ConnectorException {
    StringBuilder out = new StringBuilder(s.primaryKey()).append(" in [");
    boolean text = "VarChar".equalsIgnoreCase(s.primaryType());
    for (int i = 0; i < ids.size(); i++) {
      JsonNode id = ids.get(i);
      if (i > 0) {
        out.append(", ");
      }
      if (text) {
        out.append(quote(id.asString()));
      } else if (id.isIntegralNumber() || id.asString("").matches("-?\\d{1,19}")) {
        out.append(id.isIntegralNumber() ? Long.toString(id.asLong()) : id.asString());
      } else {
        throw ConnectorException.badInput("The primary key of this collection is an integer, not " + id + ".");
      }
    }
    return out.append(']').toString();
  }

  private static ObjectNode record(Schema s, JsonNode row, JsonNode score, boolean vectors) {
    ObjectNode out = F.objectNode();
    JsonNode id = row.has(s.primaryKey()) ? row.get(s.primaryKey()) : row.path("id");
    out.set("id", "VarChar".equalsIgnoreCase(s.primaryType()) ? F.stringNode(id.asString()) : id);
    if (score != null && !score.isMissingNode()) {
      out.set("score", score);
    }
    ObjectNode metadata = out.putObject("metadata");
    ObjectNode vectorValues = F.objectNode();
    for (var e : row.properties()) {
      String key = e.getKey();
      if (key.equals(s.primaryKey()) || key.equals("distance") || (key.equals("id") && score != null
          && !s.primaryKey().equals("id"))) {
        continue;
      }
      if (s.vectorFields().contains(key)) {
        vectorValues.set(key, e.getValue());
      } else if (key.equals("$meta") && e.getValue().isObject()) {
        e.getValue().properties().forEach(m -> metadata.set(m.getKey(), m.getValue()));
      } else {
        metadata.set(key, e.getValue());
      }
    }
    if (vectors && !vectorValues.isEmpty()) {
      out.set("vector", vectorValues.size() == 1 ? vectorValues.properties().iterator().next().getValue()
          : vectorValues);
    }
    return out;
  }

  @Override
  List<ObjectNode> query(Call c, Query q) throws ConnectorException {
    Schema s = schema(c, q.collection());
    ObjectNode body = body(c.cfg(), q.collection());
    ArrayNode data = body.putArray("data");
    String field;
    if (q.text() != null) {
      field = s.textField(q.mode());
      if (field == null) {
        throw ConnectorException.badInput(q.collection() + " has no " + (q.mode() == TextMode.KEYWORD ? "BM25"
            : "text embedding") + " function to search a text with: search it with vector or id.");
      }
      data.add(q.text());
    } else {
      field = vectorField(s, q.vectorName(), q.collection());
      if (q.vector() != null) {
        ArrayNode v = data.addArray();
        q.vector().forEach(v::add);
      } else {
        data.add(storedVector(c, s, q, field));
      }
    }
    body.put("annsField", field).put("limit", q.topK());
    if (q.filter() != null) {
      body.put("filter", expression(q.filter()));
    }
    body.set("outputFields", output(s, q.vectors()));
    partition(body, q.namespace());
    JsonNode hits = milvus(c, post(c.cfg(), "entities/search", body, "Milvus's search of " + q.collection(), false));
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode hit : hits) {
      out.add(record(s, hit, hit.path("distance"), q.vectors()));
    }
    return out;
  }

  private static String vectorField(Schema s, String vectorName, String collection) throws ConnectorException {
    if (vectorName != null) {
      if (!s.vectorFields().contains(vectorName)) {
        throw ConnectorException.badInput(collection + " has no vector field " + vectorName + ": its vector fields "
            + "are " + String.join(", ", s.vectorFields()) + ".");
      }
      return vectorName;
    }
    List<String> dense = s.vectorFields().stream().filter(f -> !s.textFunctions().containsValue(f)).toList();
    if (dense.size() == 1) {
      return dense.getFirst();
    }
    if (s.vectorFields().size() == 1) {
      return s.vectorFields().getFirst();
    }
    throw ConnectorException.badInput(collection + " has several vector fields (" + String.join(", ",
        s.vectorFields()) + "): name one with vectorName.");
  }

  /** The vector of a stored entity, to search near it. */
  private JsonNode storedVector(Call c, Schema s, Query q, String field) throws ConnectorException {
    ObjectNode body = body(c.cfg(), q.collection());
    body.put("filter", in(s, List.of(q.id())));
    body.putArray("outputFields").add(field);
    body.put("limit", 1);
    partition(body, q.namespace());
    JsonNode rows = milvus(c, post(c.cfg(), "entities/query", body, "Milvus's entity " + q.id() + " of "
        + q.collection(), false));
    if (!rows.isArray() || rows.isEmpty() || !rows.get(0).has(field)) {
      throw ConnectorException.badInput(q.collection() + " has no entity " + q.id() + ".");
    }
    return rows.get(0).get(field);
  }

  /** The filter as a Milvus boolean expression, every value written by GateRook. */
  static String expression(Filter.Node node) {
    return switch (node) {
      case Filter.And a -> join(a.nodes(), " and ");
      case Filter.Or o -> join(o.nodes(), " or ");
      case Filter.Cmp cmp -> cmp.field() + " " + switch (cmp.op()) {
        case EQ -> "==";
        case NE -> "!=";
        case GT -> ">";
        case GTE -> ">=";
        case LT -> "<";
        case LTE -> "<=";
      } + " " + literal(cmp.value());
      case Filter.In in -> {
        StringBuilder out = new StringBuilder(in.field()).append(in.not() ? " not in [" : " in [");
        for (int i = 0; i < in.values().size(); i++) {
          out.append(i == 0 ? "" : ", ").append(literal(in.values().get(i)));
        }
        yield out.append(']').toString();
      }
      case Filter.Exists e -> e.field() + (e.exists() ? " is not null" : " is null");
    };
  }

  private static String join(List<Filter.Node> nodes, String op) {
    StringBuilder out = new StringBuilder();
    for (Filter.Node n : nodes) {
      if (!out.isEmpty()) {
        out.append(op);
      }
      out.append('(').append(expression(n)).append(')');
    }
    return out.toString();
  }

  private static String literal(Filter.Value v) {
    return switch (v) {
      case Filter.Text t -> quote(t.value());
      case Filter.Bool b -> b.value() ? "true" : "false";
      default -> Filter.number(v);
    };
  }

  /** A string literal of Milvus's expressions: double quotes, backslash escapes. */
  static String quote(String s) {
    StringBuilder out = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      if (ch == '"' || ch == '\\') {
        out.append('\\');
      }
      out.append(ch);
    }
    return out.append('"').toString();
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  ObjectNode resolve(Call c, Change change) throws ConnectorException {
    Schema s = schema(c, change.target());
    ObjectNode out = F.objectNode().put("primaryKey", s.primaryKey()).put("primaryKeyType", s.primaryType());
    ArrayNode vectors = out.putArray("vectorFields");
    s.vectorFields().stream().filter(f -> !s.textFunctions().containsValue(f)).forEach(vectors::add);
    return out;
  }

  @Override
  ObjectNode resolvedWithoutIo(ConnectionConfig cfg, Change change) {
    Schema known = schemas.get(cfg.id() + "|" + cfg.version() + "|" + change.target());
    if (known == null) {
      ObjectNode out = F.objectNode().put("primaryKey", "id").put("primaryKeyType", "Int64");
      out.putArray("vectorFields").add("vector");
      return out;
    }
    ObjectNode out = F.objectNode().put("primaryKey", known.primaryKey()).put("primaryKeyType", known.primaryType());
    ArrayNode vectors = out.putArray("vectorFields");
    known.vectorFields().stream().filter(f -> !known.textFunctions().containsValue(f)).forEach(vectors::add);
    return out;
  }

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    String pk = change.resolved().path("primaryKey").asString("");
    String pkType = change.resolved().path("primaryKeyType").asString("Int64");
    if (!FIELD.matcher(pk).matches()) {
      throw ConnectorException.badInput("Milvus named no usable primary key of " + change.target() + ".");
    }
    Schema s = new Schema(pk, pkType, List.of(), List.of(), Map.of(), F.objectNode(), 0);
    ObjectNode body = body(cfg, change.target());
    if (change.namespace() != null) {
      body.put("partitionName", change.namespace());
    }
    if ("delete".equals(change.op())) {
      List<JsonNode> ids = new ArrayList<>();
      change.ids().forEach(ids::add);
      body.put("filter", in(s, ids));
      return post(cfg, "entities/delete", body, "Milvus's delete of entities from " + change.target(), true);
    }
    List<String> vectorFields = new ArrayList<>();
    change.resolved().path("vectorFields").forEach(f -> vectorFields.add(f.asString("")));
    ArrayNode rows = body.putArray("data");
    for (JsonNode r : change.records()) {
      ObjectNode row = rows.addObject();
      JsonNode id = r.get("id");
      if ("VarChar".equalsIgnoreCase(pkType)) {
        row.put(pk, id.asString());
      } else if (id.isIntegralNumber() || id.asString("").matches("-?\\d{1,19}")) {
        row.put(pk, id.isIntegralNumber() ? id.asLong() : Long.parseLong(id.asString()));
      } else {
        throw ConnectorException.badInput("The primary key of " + change.target() + " is an integer, not " + id + ".");
      }
      JsonNode vector = r.get("vector");
      if (vector != null && vector.isArray()) {
        if (vectorFields.size() != 1) {
          throw ConnectorException.badInput(change.target() + " has " + vectorFields.size() + " vector fields ("
              + String.join(", ", vectorFields) + "): give the vectors of " + id + " by name.");
        }
        row.set(vectorFields.getFirst(), vector);
      } else if (vector != null && vector.isObject()) {
        for (var e : vector.properties()) {
          if (!vectorFields.contains(e.getKey())) {
            throw ConnectorException.badInput(change.target() + " has no vector field " + e.getKey() + ".");
          }
          row.set(e.getKey(), e.getValue());
        }
      }
      if (r.has("metadata")) {
        for (var e : r.get("metadata").properties()) {
          if (e.getKey().equals(pk) || vectorFields.contains(e.getKey())) {
            throw ConnectorException.badInput("The metadata of " + id + " repeats the field " + e.getKey() + ".");
          }
          row.set(e.getKey(), e.getValue());
        }
      }
      if (r.has("document")) {
        throw ConnectorException.badInput("Record " + id + " has a document: put the text in a field of metadata.");
      }
    }
    return post(cfg, "entities/upsert", body, "Milvus's upsert of entities into " + change.target(), true);
  }

  @Override
  ObjectNode changed(Change change, ServiceHttp.Response r) throws ConnectorException {
    JsonNode answer = ServiceHttp.json(r, "Milvus's answer");
    int code = answer.path("code").asInt(-1);
    if (code != 0 && code != 200) {
      throw ConnectorException.badInput("Milvus refused the change (code " + code + "): " + ServiceHttp.sentence(
          shorten(answer.path("message").asString(""), 500)) + " Nothing changed.");
    }
    JsonNode data = answer.path("data");
    ObjectNode out = F.objectNode();
    if ("delete".equals(change.op())) {
      out.put("deleted", data.path("deleteCount").asInt(change.count()));
    } else {
      out.put("upserted", data.path("upsertCount").asInt(change.count()));
    }
    return out;
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  TestResult test(Call c) {
    long started = System.nanoTime();
    List<ObjectNode> all;
    try {
      all = collections(c);
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null);
    }
    long latency = (System.nanoTime() - started) / 1_000_000;
    String host = "";
    try {
      host = endpoint(c.cfg()).base().getHost();
    } catch (ConnectorException e) {
      // Shown as Milvus.
    }
    String server = host.endsWith("zillizcloud.com") || host.endsWith("cloud.zilliz.com") ? "Zilliz Cloud" : "Milvus";
    List<TestResult.Check> checks = new ArrayList<>();
    long visible = all.stream().filter(o -> allows(c.cfg(), o.path("name").asString(""))).count();
    checks.add(new TestResult.Check("collections", true, latency, count(visible, "collection") + " agents may use in "
        + database(c.cfg())));
    TestResult.Permissions permissions = permissions(c);
    String identity = switch (method(c.cfg())) {
      case "password" -> c.cfg().string("/auth/username");
      case "none" -> "no credentials";
      default -> "API key or token";
    };
    TestResult result = TestResult.success(latency, server, identity, permissions).withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", server));
    if ("none".equals(method(c.cfg()))) {
      result = result.withHint(new Hint(Severity.WARNING, "milvus.no-auth", "/auth/method", "The server checks no "
          + "credentials: anyone who reaches it may write and delete. Turn on authorization and give agents a user "
          + "whose role may only read."));
    } else if (permissions.level() == TestResult.Level.ADMIN) {
      result = result.withHint(new Hint(c.cfg().env() == ai.msrv.gaterook.connector.api.Env.DEV ? Severity.INFO
          : Severity.WARNING, "milvus.admin", "/auth/username", "These credentials may also write, delete and drop "
          + "collections. Give agents a user whose role may only read."));
    }
    List<Hint> plain = new ArrayList<>();
    QdrantBackend.plainHint(c.cfg(), plain);
    for (Hint h : plain) {
      result = result.withHint(h);
    }
    if (visible == 0) {
      result = result.withHint(new Hint(Severity.WARNING, "milvus.no-collections", "/collections", "No collection "
          + "agents may use in database " + database(c.cfg()) + "."));
    }
    return result;
  }

  /** The user's roles, when GateRook signs in with a user and Milvus says them. */
  private TestResult.Permissions permissions(Call c) {
    if ("none".equals(method(c.cfg()))) {
      return new TestResult.Permissions(TestResult.Level.ADMIN, "The server checks no credentials: anyone who "
          + "reaches it may read, write and delete.", null, List.of(), null);
    }
    String user = c.cfg().string("/auth/username");
    if (!"password".equals(method(c.cfg())) || user == null) {
      return new TestResult.Permissions(TestResult.Level.UNKNOWN, "Milvus doesn't say what a token may do: its "
          + "user's role decides.", null, List.of(), null);
    }
    if (user.equals("root")) {
      return new TestResult.Permissions(TestResult.Level.ADMIN, "root is Milvus's superuser: it may also write, "
          + "delete and drop collections.", null, List.of("root"), null);
    }
    try {
      ObjectNode body = F.objectNode().put("userName", user);
      JsonNode roles = milvus(c, post(c.cfg(), "users/describe", body, "Milvus's roles of " + user, false));
      List<String> names = new ArrayList<>();
      roles.forEach(r -> names.add(r.isString() ? r.asString() : r.path("roleName").asString("")));
      boolean admin = names.contains("admin");
      return new TestResult.Permissions(admin ? TestResult.Level.ADMIN : TestResult.Level.UNKNOWN, user
          + (names.isEmpty() ? " has no role." : " has the roles " + String.join(", ", names) + ": their privileges "
          + "decide."), null, admin ? List.of("admin") : List.of(), null);
    } catch (ConnectorException e) {
      return new TestResult.Permissions(TestResult.Level.UNKNOWN, user + "'s roles aren't readable with these "
          + "credentials: their privileges decide.", null, List.of(), null);
    }
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    return F.objectNode().put("database", database(cfg)).put("ids", "The primary key: an integer (Int64) or text "
            + "(VarChar)")
        .put("search", "By vector, by the id of a stored entity, or by text with a BM25 or text embedding function")
        .put("namespace", "A partition of the collection");
  }

  @Override
  void forget(String connectionId) {
    schemas.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
  }
}
