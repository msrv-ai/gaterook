package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import java.net.URI;
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
 * Pinecone's REST APIs (docs/vector-databases.md): the control plane lists and describes the project's indexes, each
 * index's own host serves its records. The host comes from the control plane and is trusted only as
 * {@code <index>-<project>.svc.<region>.pinecone.io} over https (in tests, with a control plane on this computer, as
 * this computer). The project's API key goes in {@code Api-Key}; a key with the DataPlaneViewer role is the store's own
 * boundary, and ControlPlaneViewer lets it find the indexes.
 *
 * <p>Indexes with integrated embedding search and take records by text ({@code /records/namespaces/…}); the others by
 * vector or by a stored record's id.
 */
final class PineconeBackend extends VectorBackend implements VectorConnector.Recorded {

  static final String TYPE = "pinecone";
  static final String API_KEY = "/apiKey";
  static final String API_VERSION = "2025-10";
  static final URI CONTROL_PLANE = URI.create("https://api.pinecone.io");
  /** Pinecone's index names: lowercase letters, digits and hyphens, at most 45. */
  private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,43}[a-z0-9])?");
  private static final Pattern HOST = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}\\.svc\\.[a-z0-9][a-z0-9.-]{0,127}"
      + "\\.pinecone\\.io");
  private static final Pattern LOCAL_HOST = Pattern.compile("(localhost|127\\.0\\.0\\.1):\\d{1,5}");
  private static final Pattern KEY = Pattern.compile("[^$\\x00-\\x1f\\x7f][^\\x00-\\x1f\\x7f]{0,511}");
  private static final long MAX_READ = 16L << 20;
  /** Metadata of one record: Pinecone's own limit. */
  static final int MAX_METADATA = 40 * 1024;
  /** Records with text in one upsert of an index with integrated embedding: Pinecone's limit. */
  static final int MAX_TEXT_RECORDS = 96;
  private static final long HOST_TTL_NANOS = 10L * 60 * 1_000_000_000;

  /** An index as the control plane described it. */
  private record Index(String host, JsonNode description, long atNanos) {}

  private final Map<String, Index> indexes = new ConcurrentHashMap<>();
  private final URI controlPlane;

  PineconeBackend() {
    this(null);
  }

  /**
   * Tests: {@code testControlPlane} on this computer replaces api.pinecone.io, and index hosts are then this computer
   * too. Never a setting: a person can't send the key elsewhere.
   */
  PineconeBackend(URI testControlPlane) {
    if (testControlPlane != null && !ServiceHttp.loopbackHost(testControlPlane.getHost())) {
      throw new IllegalArgumentException("A test control plane is on this computer");
    }
    this.controlPlane = testControlPlane == null ? CONTROL_PLANE : testControlPlane;
  }

  @Override
  String type() {
    return TYPE;
  }

  @Override
  String name() {
    return "Pinecone";
  }

  @Override
  String description() {
    return "Indexes and records of Pinecone: metadata-filtered fetches, nearest-neighbour search by vector, record "
        + "or text with integrated embedding. Upserts and deletes wait for approval.";
  }

  @Override
  String listNoun() {
    return "index";
  }

  @Override
  String listNouns() {
    return "indexes";
  }

  @Override
  String signedIn(ConnectionConfig cfg) {
    return "Pinecone";
  }

  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) {
    return new ServiceHttp.Endpoint(controlPlane, List.of());
  }

  /** Whether the control plane is a test's on this computer. */
  private boolean local(ConnectionConfig cfg) {
    return controlPlane != CONTROL_PLANE;
  }

  @Override
  Map<String, List<String>> auth(Call c) throws ConnectorException {
    return ServiceHttp.headers("Api-Key", secret(c, API_KEY, "API key"), "X-Pinecone-API-Version", API_VERSION);
  }

  @Override
  List<String> authShown(ConnectionConfig cfg) {
    return List.of("Api-Key: ‹secret›", "X-Pinecone-API-Version: " + API_VERSION);
  }

  @Override
  String said(ServiceHttp.Response r) {
    try {
      JsonNode body = ServiceHttp.JSON.readTree(r.body());
      String m = body.path("error").path("message").asString(null);
      if (m == null || m.isBlank()) {
        m = body.path("message").asString(null);
      }
      if ((m == null || m.isBlank()) && body.path("error").path("code").isString()) {
        m = body.path("error").path("code").asString();
      }
      return m == null || m.isBlank() ? null : shorten(m, 500);
    } catch (RuntimeException e) {
      String text = r.text().strip();
      return text.isEmpty() ? null : shorten(text, 300);
    }
  }

  @Override
  String nameProblem(String name) {
    return NAME.matcher(name).matches() ? null : "A Pinecone index name has 1 to 45 lowercase letters, digits and "
        + "hyphens.";
  }

  @Override
  String namespaceWord() {
    return "namespace";
  }

  @Override
  String idProblem(JsonNode id) {
    return id.isString() ? null : "A Pinecone record id is text: \"" + id + "\".";
  }

  @Override
  void checkFilter(Filter.Node filter) throws Filter.Invalid {
    Filter.checkFields(filter, KEY, "a Pinecone metadata key doesn't start with $.");
  }

  @Override
  String queryProblem(Query q) {
    if (q.text() != null && q.mode() == TextMode.HYBRID) {
      return "Pinecone searches a text with the index's own model: leave textMode out.";
    }
    if (q.vectorName() != null) {
      return "A Pinecone index has one vector: leave vectorName out.";
    }
    return null;
  }

  @Override
  boolean documents() {
    return true;
  }

  @Override
  public String recordProblem(ObjectNode record) {
    JsonNode v = record.get("vector");
    if (v != null && v.isObject()) {
      return "A Pinecone record has one vector: give vector as a list of numbers.";
    }
    JsonNode m = record.get("metadata");
    if (m != null) {
      for (var e : m.properties()) {
        JsonNode value = e.getValue();
        boolean ok = value.isString() || value.isNumber() || value.isBoolean();
        if (value.isArray()) {
          ok = true;
          for (JsonNode item : value) {
            ok &= item.isString();
          }
        }
        if (!ok) {
          return "Pinecone metadata values are text, numbers, true or false, or lists of text: " + e.getKey()
              + " of " + record.get("id") + " isn't.";
        }
      }
      if (ServiceHttp.bytes(m).length > MAX_METADATA) {
        return "The metadata of " + record.get("id") + " is larger than Pinecone's 40 KB per record.";
      }
    }
    return null;
  }

  // ── The control plane and index hosts ──────────────────────────────────────────────────────────────────────

  private String key(ConnectionConfig cfg, String index) {
    return cfg.id() + "|" + cfg.version() + "|" + index;
  }

  /** The index as the control plane describes it now, its host checked; kept ten minutes. */
  private Index index(Call c, String name) throws ConnectorException {
    Index known = indexes.get(key(c.cfg(), name));
    if (known != null && System.nanoTime() - known.atNanos() < HOST_TTL_NANOS) {
      return known;
    }
    JsonNode d = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/indexes/" + ServiceHttp.seg(name)), null,
        "Pinecone's description of index " + name), 1 << 20);
    String host = checkedHost(c.cfg(), d.path("host").asString(""));
    if (indexes.size() > 1_000) {
      indexes.clear();
    }
    Index index = new Index(host, d, System.nanoTime());
    indexes.put(key(c.cfg(), name), index);
    return index;
  }

  /** The index host the control plane named, if GateRook may send the key there. */
  private String checkedHost(ConnectionConfig cfg, String host) throws ConnectorException {
    String h = host.toLowerCase(java.util.Locale.ROOT);
    if (h.startsWith("https://")) {
      h = h.substring("https://".length());
    }
    if (local(cfg) ? LOCAL_HOST.matcher(h).matches() : HOST.matcher(h).matches()) {
      return h;
    }
    throw ConnectorException.denied("Pinecone named the index host " + shorten(host, 120) + ", which isn't "
        + (local(cfg) ? "this computer" : "a host of pinecone.io") + ": GateRook doesn't send the key there.");
  }

  private ServiceHttp.Endpoint data(ConnectionConfig cfg, String host) throws ConnectorException {
    return new ServiceHttp.Endpoint(URI.create((local(cfg) ? "http://" : "https://") + host), List.of());
  }

  private ServiceHttp.Response sendData(Call c, String host, ServiceHttp.Request r, long max)
      throws ConnectorException {
    ServiceHttp.Response response = send(c, data(c.cfg(), host), r, max);
    if (!response.ok()) {
      throw failure(c.cfg(), r, response);
    }
    return response;
  }

  private JsonNode dataJson(Call c, String host, ServiceHttp.Request r) throws ConnectorException {
    return ServiceHttp.json(sendData(c, host, r, MAX_READ), r.what());
  }

  private ServiceHttp.Request dataPost(ConnectionConfig cfg, String host, String path, JsonNode body, String what,
      boolean change) throws ConnectorException {
    return ServiceHttp.Request.json("POST", data(cfg, host).uri(path), null, body, what, change);
  }

  private static String ns(String namespace) {
    return namespace == null ? "" : namespace;
  }

  /** The text field of an index with integrated embedding, or {@code null}. */
  private static String textField(JsonNode description) {
    JsonNode map = description.path("embed").path("field_map");
    String field = map.path("text").asString(null);
    if (field == null && map.isObject() && map.size() == 1) {
      field = map.properties().iterator().next().getValue().asString(null);
    }
    return field;
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  List<ObjectNode> collections(Call c) throws ConnectorException {
    JsonNode body = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/indexes"), null,
        "Pinecone's index list"), MAX_READ);
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode i : body.path("indexes")) {
      ObjectNode o = F.objectNode().put("name", i.path("name").asString(""));
      if (i.has("dimension") && !i.get("dimension").isNull()) {
        o.set("dimension", i.get("dimension"));
      }
      o.put("metric", i.path("metric").asString(""));
      if (i.path("vector_type").isString()) {
        o.put("vectorType", i.get("vector_type").asString());
      }
      o.put("ready", i.path("status").path("ready").asBoolean(false));
      if (i.path("embed").isObject()) {
        o.put("embedding", i.path("embed").path("model").asString(""));
      }
      out.add(o);
    }
    return out;
  }

  @Override
  ObjectNode describe(Call c, String collection) throws ConnectorException {
    Index index = index(c, collection);
    JsonNode d = index.description();
    ObjectNode out = F.objectNode().put("name", collection);
    if (d.has("dimension") && !d.get("dimension").isNull()) {
      out.set("dimension", d.get("dimension"));
    }
    out.put("metric", d.path("metric").asString(""));
    if (d.path("vector_type").isString()) {
      out.put("vectorType", d.get("vector_type").asString());
    }
    out.put("status", d.path("status").path("state").asString(""));
    if (d.path("spec").isObject()) {
      out.set("spec", d.get("spec"));
    }
    if (d.path("embed").isObject()) {
      ObjectNode embed = out.putObject("integratedEmbedding");
      embed.put("model", d.path("embed").path("model").asString(""));
      String field = textField(d);
      if (field != null) {
        embed.put("textField", field);
      }
      embed.put("note", "vector_query takes text, and vector_upsert a document instead of a vector.");
    }
    JsonNode stats = dataJson(c, index.host(), dataPost(c.cfg(), index.host(), "/describe_index_stats",
        F.objectNode(), "Pinecone's statistics of " + collection, false));
    if (stats.has("totalVectorCount")) {
      out.set("records", stats.get("totalVectorCount"));
    }
    ObjectNode namespaces = out.putObject("namespaces");
    int shown = 0;
    for (var e : stats.path("namespaces").properties()) {
      if (shown++ >= 100) {
        out.put("namespacesTruncated", true);
        break;
      }
      namespaces.set(e.getKey().isEmpty() ? "(default)" : e.getKey(), e.getValue().path("vectorCount"));
    }
    out.put("filters", "Metadata filters run on Pinecone; text, numbers, booleans and lists of text.");
    return out;
  }

  @Override
  Page get(Call c, Get g) throws ConnectorException {
    Index index = index(c, g.collection());
    if (!g.ids().isEmpty()) {
      return fetch(c, index, g.collection(), g.namespace(), g.ids(), g.vectors());
    }
    if (g.filter() != null) {
      ObjectNode body = F.objectNode().put("namespace", ns(g.namespace()));
      body.set("filter", mongo(g.filter()));
      body.put("limit", g.limit());
      if (g.cursor() != null) {
        body.put("paginationToken", g.cursor());
      }
      ServiceHttp.Request r = dataPost(c.cfg(), index.host(), "/vectors/fetch_by_metadata", body,
          "Pinecone's fetch by metadata of " + g.collection(), false);
      ServiceHttp.Response response = send(c, data(c.cfg(), index.host()), r, MAX_READ);
      if (response.status() == 404 || response.status() == 405 || (response.ok() && response.body().length == 0)) {
        throw ConnectorException.badInput("This Pinecone can't fetch records by metadata: use vector_query with "
            + "the filter.");
      }
      if (!response.ok()) {
        throw failure(c.cfg(), r, response);
      }
      JsonNode answer = ServiceHttp.json(response, r.what());
      List<ObjectNode> records = new ArrayList<>();
      for (var e : answer.path("vectors").properties()) {
        records.add(record(e.getValue(), null, g.vectors()));
      }
      String next = answer.path("pagination").path("next").asString(null);
      return new Page(records, next, List.of());
    }
    // No ids and no filter: a page of ids (serverless indexes), then their records.
    URI list = data(c.cfg(), index.host()).uri("/vectors/list", "namespace", ns(g.namespace()), "limit",
        Integer.toString(g.limit()), "paginationToken", g.cursor());
    JsonNode page = dataJson(c, index.host(), ServiceHttp.Request.get(list, null, "Pinecone's record ids of "
        + g.collection()));
    List<JsonNode> ids = new ArrayList<>();
    for (JsonNode v : page.path("vectors")) {
      ids.add(v.path("id"));
    }
    String next = page.path("pagination").path("next").asString(null);
    if (ids.isEmpty()) {
      return new Page(List.of(), next, List.of());
    }
    Page records = fetch(c, index, g.collection(), g.namespace(), ids, g.vectors());
    return new Page(records.records(), next, List.of());
  }

  private Page fetch(Call c, Index index, String collection, String namespace, List<JsonNode> ids, boolean vectors)
      throws ConnectorException {
    List<String> query = new ArrayList<>();
    for (JsonNode id : ids) {
      query.add("ids");
      query.add(id.asString());
    }
    query.add("namespace");
    query.add(ns(namespace));
    URI uri = data(c.cfg(), index.host()).uri("/vectors/fetch", query.toArray(String[]::new));
    JsonNode body = dataJson(c, index.host(), ServiceHttp.Request.get(uri, null, "Pinecone's records of "
        + collection));
    List<ObjectNode> records = new ArrayList<>();
    List<JsonNode> missing = new ArrayList<>();
    JsonNode found = body.path("vectors");
    for (JsonNode id : ids) {
      JsonNode v = found.get(id.asString());
      if (v == null) {
        missing.add(id);
      } else {
        records.add(record(v, null, vectors));
      }
    }
    return new Page(records, null, missing);
  }

  private static ObjectNode record(JsonNode v, JsonNode score, boolean vectors) {
    ObjectNode out = F.objectNode();
    out.set("id", v.path("id"));
    if (score != null) {
      out.set("score", score);
    }
    out.set("metadata", v.path("metadata").isObject() ? v.get("metadata") : F.objectNode());
    if (vectors && v.path("values").isArray() && !v.get("values").isEmpty()) {
      out.set("vector", v.get("values"));
    }
    if (vectors && v.path("sparseValues").isObject()) {
      out.set("sparseVector", v.get("sparseValues"));
    }
    return out;
  }

  @Override
  List<ObjectNode> query(Call c, Query q) throws ConnectorException {
    Index index = index(c, q.collection());
    List<ObjectNode> out = new ArrayList<>();
    if (q.text() != null) {
      String field = textField(index.description());
      if (field == null) {
        throw ConnectorException.badInput(q.collection() + " has no integrated embedding: search it with vector or "
            + "id.");
      }
      ObjectNode body = F.objectNode();
      ObjectNode query = body.putObject("query");
      query.putObject("inputs").put("text", q.text());
      query.put("top_k", q.topK());
      if (q.filter() != null) {
        query.set("filter", mongo(q.filter()));
      }
      String namespace = q.namespace() == null ? "__default__" : q.namespace();
      JsonNode answer = dataJson(c, index.host(), dataPost(c.cfg(), index.host(), "/records/namespaces/"
          + ServiceHttp.seg(namespace) + "/search", body, "Pinecone's search of " + q.collection(), false));
      for (JsonNode hit : answer.path("result").path("hits")) {
        ObjectNode r = F.objectNode();
        r.set("id", hit.path("_id"));
        r.set("score", hit.path("_score"));
        ObjectNode fields = hit.path("fields").isObject() ? (ObjectNode) hit.get("fields").deepCopy()
            : F.objectNode();
        JsonNode text = fields.remove(field);
        r.set("metadata", fields);
        if (text != null && text.isString()) {
          r.put("document", text.asString());
        }
        out.add(r);
      }
      return out;
    }
    ObjectNode body = F.objectNode().put("namespace", ns(q.namespace())).put("topK", q.topK());
    if (q.vector() != null) {
      ArrayNode v = body.putArray("vector");
      q.vector().forEach(v::add);
    } else {
      body.put("id", q.id().asString());
    }
    if (q.filter() != null) {
      body.set("filter", mongo(q.filter()));
    }
    body.put("includeValues", q.vectors()).put("includeMetadata", true);
    JsonNode answer = dataJson(c, index.host(), dataPost(c.cfg(), index.host(), "/query", body,
        "Pinecone's query of " + q.collection(), false));
    for (JsonNode m : answer.path("matches")) {
      out.add(record(m, m.path("score"), q.vectors()));
    }
    return out;
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  ObjectNode resolve(Call c, Change change) throws ConnectorException {
    Index index = index(c, change.target());
    ObjectNode out = F.objectNode().put("host", index.host());
    String field = textField(index.description());
    if (field != null) {
      out.put("textField", field);
    }
    return out;
  }

  @Override
  ObjectNode resolvedWithoutIo(ConnectionConfig cfg, Change change) {
    Index known = indexes.get(key(cfg, change.target()));
    ObjectNode out = F.objectNode().put("host", known == null ? change.target() + "-<project>.svc.<region>.pinecone.io"
        : known.host());
    String field = known == null ? null : textField(known.description());
    if (field != null) {
      out.put("textField", field);
    }
    return out;
  }

  private String resolvedHost(ConnectionConfig cfg, Change change) throws ConnectorException {
    return checkedHost(cfg, change.resolved().path("host").asString(""));
  }

  @Override
  ServiceHttp.Endpoint changeEndpoint(ConnectionConfig cfg, Change change) throws ConnectorException {
    return data(cfg, resolvedHost(cfg, change));
  }

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    String host = resolvedHost(cfg, change);
    if ("delete".equals(change.op())) {
      ObjectNode body = F.objectNode();
      body.set("ids", change.ids());
      body.put("namespace", ns(change.namespace()));
      return dataPost(cfg, host, "/vectors/delete", body, "Pinecone's delete of records from " + change.target(),
          true);
    }
    boolean anyVector = false;
    boolean anyText = false;
    for (JsonNode r : change.records()) {
      anyVector |= r.has("vector");
      anyText |= !r.has("vector");
    }
    String field = change.resolved().path("textField").asString(null);
    if (anyText) {
      if (field == null) {
        throw ConnectorException.badInput(change.target() + " has no integrated embedding: give every record a "
            + "vector.");
      }
      if (anyVector) {
        throw ConnectorException.badInput(change.target() + " embeds documents itself: leave the vectors out.");
      }
      if (change.records().size() > MAX_TEXT_RECORDS) {
        throw ConnectorException.badInput("Pinecone takes at most " + MAX_TEXT_RECORDS + " records with text per "
            + "upsert: split them.");
      }
      StringBuilder lines = new StringBuilder();
      for (JsonNode r : change.records()) {
        if (!r.has("document")) {
          throw ConnectorException.badInput("Record " + r.get("id") + " needs a document: " + change.target()
              + " embeds its " + field + ".");
        }
        ObjectNode line = F.objectNode();
        line.set("_id", r.get("id"));
        line.put(field, r.get("document").asString());
        if (r.has("metadata")) {
          for (var e : r.get("metadata").properties()) {
            if (!e.getKey().equals(field) && !e.getKey().equals("_id")) {
              line.set(e.getKey(), e.getValue());
            }
          }
        }
        lines.append(ServiceHttp.JSON.writeValueAsString(line)).append('\n');
      }
      String namespace = change.namespace() == null ? "__default__" : change.namespace();
      return new ServiceHttp.Request("POST", data(cfg, host).uri("/records/namespaces/" + ServiceHttp.seg(namespace)
          + "/upsert"), ServiceHttp.headers("Content-Type", "application/x-ndjson"),
          lines.toString().getBytes(StandardCharsets.UTF_8), "Pinecone's upsert of records into " + change.target(),
          true);
    }
    ObjectNode body = F.objectNode();
    ArrayNode vectors = body.putArray("vectors");
    for (JsonNode r : change.records()) {
      if (r.has("document")) {
        throw ConnectorException.badInput("Record " + r.get("id") + " has a document: " + change.target() + " stores "
            + "vectors; put the text in metadata.");
      }
      ObjectNode v = vectors.addObject();
      v.set("id", r.get("id"));
      v.set("values", r.get("vector"));
      if (r.has("metadata")) {
        v.set("metadata", r.get("metadata"));
      }
    }
    body.put("namespace", ns(change.namespace()));
    return dataPost(cfg, host, "/vectors/upsert", body, "Pinecone's upsert of records into " + change.target(),
        true);
  }

  @Override
  ObjectNode changed(Change change, ServiceHttp.Response r) throws ConnectorException {
    ObjectNode out = F.objectNode();
    if ("delete".equals(change.op())) {
      out.put("deleted", change.count());
      out.put("note", "Pinecone deletes ids that exist and ignores the others.");
      return out;
    }
    JsonNode body = r.body().length == 0 ? F.objectNode() : ServiceHttp.json(r, "Pinecone's answer");
    out.put("upserted", body.path("upsertedCount").asInt(change.count()));
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
    String server = "Pinecone";
    List<String> visible = all.stream().map(o -> o.path("name").asString("")).filter(n -> allows(c.cfg(), n))
        .toList();
    List<TestResult.Check> checks = new ArrayList<>();
    checks.add(new TestResult.Check("indexes", true, latency, count(visible.size(), "index", "indexes") + " agents may use" + (visible.isEmpty() ? "" : ": " + String.join(", ",
        visible.subList(0, Math.min(5, visible.size()))) + (visible.size() > 5 ? ", …" : ""))));
    TestResult result = TestResult.success(latency, server, "API key", new TestResult.Permissions(
        TestResult.Level.UNKNOWN, "Pinecone doesn't say what a key may do: a key with the DataPlaneViewer role "
            + "refuses writes itself.", null, List.of(), null)).withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", server).put("apiVersion", API_VERSION));
    result = result.withHint(new Hint(Severity.INFO, "pinecone.read-only-key", "/apiKey", "Give agents a key with "
        + "the ControlPlaneViewer and DataPlaneViewer roles unless they should upsert and delete."));
    if (visible.isEmpty()) {
      result = result.withHint(new Hint(Severity.WARNING, "pinecone.no-indexes", "/collections", "No index agents "
          + "may use."));
    }
    return result;
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    return F.objectNode().put("ids", "Text")
        .put("search", "By vector, by the id of a stored record, or by text on indexes with integrated embedding")
        .put("namespace", "Empty: the default namespace");
  }

  @Override
  void forget(String connectionId) {
    indexes.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
  }
}
