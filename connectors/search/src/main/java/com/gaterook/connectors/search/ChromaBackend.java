package com.gaterook.connectors.search;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Suggestions;
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
 * Chroma's API v2 (docs/vector-databases.md), Chroma Cloud's too: the collections of one tenant and database, records
 * got by id or by a metadata filter in Chroma's own {@code where} (which is MongoDB's form), nearest-neighbour search
 * by embedding or by a stored record, upserts and deletes by id. A collection is named in the request by its id, which
 * GateRook looks up by name. Chroma Cloud's API key goes in {@code X-Chroma-Token}; a server of your own may take a
 * bearer token.
 */
final class ChromaBackend extends VectorBackend implements VectorConnector.Recorded {

  static final String TYPE = "chroma";
  static final String TOKEN = "/auth/token";
  /** Chroma's collection names: 3 to 512 characters of letters, digits, ., _ and -, starting and ending alike. */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,510}[A-Za-z0-9]");
  private static final Pattern UUID = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern KEY = Pattern.compile("[^$#\\x00-\\x1f\\x7f][^\\x00-\\x1f\\x7f]{0,254}");
  private static final long MAX_READ = 16L << 20;
  private static final long ID_TTL_NANOS = 5L * 60 * 1_000_000_000;

  private record Known(String id, JsonNode description, long atNanos) {}

  private final Map<String, Known> collections = new ConcurrentHashMap<>();

  @Override
  String type() {
    return TYPE;
  }

  @Override
  String name() {
    return "Chroma";
  }

  @Override
  String description() {
    return "Collections and records of Chroma and Chroma Cloud: metadata-filtered pages and nearest-neighbour search "
        + "by embedding or record. Upserts and deletes wait for approval.";
  }

  @Override
  int localPort() {
    return 8000;
  }

  @Override
  ServiceHttp.Endpoint endpoint(ConnectionConfig cfg) throws ConnectorException {
    return new ServiceHttp.Endpoint(ServiceHttp.base(cfg.string("/url"), "https://api.trychroma.com", "/api/v2",
        "/api/v1"), ca(cfg));
  }

  private static String method(ConnectionConfig cfg) {
    String m = cfg.string("/auth/method");
    return m == null ? "token" : m;
  }

  private static String tenant(ConnectionConfig cfg) {
    String t = cfg.string("/tenant");
    return t == null || t.isBlank() ? "default_tenant" : t;
  }

  private static String database(ConnectionConfig cfg) {
    String d = cfg.string("/database");
    return d == null || d.isBlank() ? "default_database" : d;
  }

  @Override
  Map<String, List<String>> auth(Call c) throws ConnectorException {
    return switch (method(c.cfg())) {
      case "none" -> Map.of();
      case "bearer" -> ServiceHttp.headers("Authorization", "Bearer " + secret(c, TOKEN, "token"));
      default -> ServiceHttp.headers("X-Chroma-Token", secret(c, TOKEN, "API key"));
    };
  }

  @Override
  List<String> authShown(ConnectionConfig cfg) {
    return switch (method(cfg)) {
      case "none" -> List.of();
      case "bearer" -> List.of("Authorization: Bearer ‹secret›");
      default -> List.of("X-Chroma-Token: ‹secret›");
    };
  }

  @Override
  String said(ServiceHttp.Response r) {
    try {
      JsonNode body = ServiceHttp.JSON.readTree(r.body());
      String m = body.path("message").asString(null);
      if (m == null || m.isBlank()) {
        m = body.path("error").asString(null);
      }
      return m == null || m.isBlank() ? null : shorten(m, 500);
    } catch (RuntimeException e) {
      String text = r.text().strip();
      return text.isEmpty() ? null : shorten(text, 300);
    }
  }

  @Override
  String nameProblem(String name) {
    return NAME.matcher(name).matches() && !name.contains("..") ? null : "A Chroma collection name has 3 to 512 "
        + "letters, digits, ., _ and -, and starts and ends with a letter or digit.";
  }

  @Override
  String namespaceWord() {
    return null;
  }

  @Override
  String idProblem(JsonNode id) {
    return id.isString() ? null : "A Chroma record id is text: \"" + id + "\".";
  }

  @Override
  void checkFilter(Filter.Node filter) throws Filter.Invalid {
    Filter.checkFields(filter, KEY, "a Chroma metadata key doesn't start with $ or #.");
    Filter.mongo(filter, false);
  }

  @Override
  String queryProblem(Query q) {
    if (q.text() != null) {
      return "Chroma's API searches by embedding: pass vector, or the id of a stored record.";
    }
    return q.vectorName() != null ? "A Chroma collection has one embedding: leave vectorName out." : null;
  }

  @Override
  boolean documents() {
    return true;
  }

  @Override
  public String recordProblem(ObjectNode record) {
    JsonNode v = record.get("vector");
    if (v != null && v.isObject()) {
      return "A Chroma record has one embedding: give vector as a list of numbers.";
    }
    JsonNode m = record.get("metadata");
    if (m != null) {
      if (m.isEmpty()) {
        return "Chroma refuses empty metadata: leave metadata out of " + record.get("id") + ".";
      }
      for (var e : m.properties()) {
        JsonNode value = e.getValue();
        if (!(value.isString() || value.isNumber() || value.isBoolean() || value.isNull())) {
          return "Chroma metadata values are text, numbers, true or false: " + e.getKey() + " of " + record.get("id")
              + " isn't.";
        }
        if (!KEY.matcher(e.getKey()).matches()) {
          return "A Chroma metadata key doesn't start with $ or #: " + shorten(e.getKey(), 60) + ".";
        }
      }
    }
    return null;
  }

  // ── Collections by name and id ──────────────────────────────────────────────────────────────────────────────

  private String base(ConnectionConfig cfg) {
    return "/api/v2/tenants/" + ServiceHttp.seg(tenant(cfg)) + "/databases/" + ServiceHttp.seg(database(cfg))
        + "/collections";
  }

  private Known known(Call c, String name) throws ConnectorException {
    String key = c.cfg().id() + "|" + c.cfg().version() + "|" + name;
    Known k = collections.get(key);
    if (k != null && System.nanoTime() - k.atNanos() < ID_TTL_NANOS) {
      return k;
    }
    JsonNode d = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri(base(c.cfg()) + "/" + ServiceHttp.seg(name)),
        null, "Chroma's collection " + name), 4 << 20);
    String id = d.path("id").asString("");
    if (!UUID.matcher(id).matches()) {
      throw ConnectorException.resourceError("Chroma described " + name + " without a collection id.", null);
    }
    if (collections.size() > 1_000) {
      collections.clear();
    }
    Known out = new Known(id, d, System.nanoTime());
    collections.put(key, out);
    return out;
  }

  private ServiceHttp.Request post(ConnectionConfig cfg, String id, String op, JsonNode body, String what,
      boolean change) throws ConnectorException {
    return ServiceHttp.Request.json("POST", endpoint(cfg).uri(base(cfg) + "/" + ServiceHttp.seg(id) + "/" + op), null,
        body, what, change);
  }

  private static ArrayNode include(boolean vectors, boolean distances) {
    ArrayNode out = F.arrayNode().add("metadatas").add("documents");
    if (vectors) {
      out.add("embeddings");
    }
    if (distances) {
      out.add("distances");
    }
    return out;
  }

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  private static String space(JsonNode d) {
    JsonNode config = d.path("configuration_json");
    String space = config.path("hnsw").path("space").asString(null);
    if (space == null) {
      space = config.path("spann").path("space").asString(null);
    }
    if (space == null) {
      space = d.path("metadata").path("hnsw:space").asString(null);
    }
    return space;
  }

  @Override
  List<ObjectNode> collections(Call c) throws ConnectorException {
    JsonNode list = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri(base(c.cfg()), "limit", "1000"), null,
        "Chroma's collection list"), MAX_READ);
    List<ObjectNode> out = new ArrayList<>();
    for (JsonNode d : list) {
      ObjectNode o = F.objectNode().put("name", d.path("name").asString(""));
      if (d.path("dimension").isNumber()) {
        o.set("dimension", d.get("dimension"));
      }
      String space = space(d);
      if (space != null) {
        o.put("metric", space);
      }
      out.add(o);
    }
    return out;
  }

  /**
   * Service suggestions: the tenant the credentials belong to (Chroma Cloud's tenant id), filled in; the databases
   * they may use, as the identity names them or else as the tenant lists them, the only one filled in; and the
   * collections of the database the connection would use.
   */
  @Override
  void service(Call c, Suggestions.Builder out) throws ConnectorException {
    JsonNode identity = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/api/v2/auth/identity"), null,
        "Chroma's identity"), 1 << 20);
    String tenant = identity.path("tenant").asString("").strip();
    if (tenant.isEmpty()) {
      tenant = tenant(c.cfg());
    } else {
      out.value("/tenant", name(), tenant, List.of(Suggestions.Option.of(tenant)));
    }
    List<String> databases = new ArrayList<>();
    boolean any = identity.path("databases").isEmpty();
    for (JsonNode d : identity.path("databases")) {
      String name = d.asString("").strip();
      if (name.equals("*")) {
        any = true;
      } else if (!name.isEmpty() && !databases.contains(name)) {
        databases.add(name);
      }
    }
    if (any) {
      try {
        JsonNode list = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/api/v2/tenants/" + ServiceHttp.seg(
            tenant) + "/databases", "limit", "200"), null, "Chroma's database list"), 1 << 20);
        for (JsonNode d : list) {
          String name = d.path("name").asString("").strip();
          if (!name.isEmpty() && !databases.contains(name)) {
            databases.add(name);
          }
        }
      } catch (ConnectorException e) {
        // The tenant's databases aren't listable here: the connection's database is all there is to go on.
      }
    }
    String database = database(c.cfg());
    if (!databases.isEmpty()) {
      out.value("/database", name(), databases.size() == 1 ? databases.getFirst() : null,
          databases.stream().map(Suggestions.Option::of).toList());
      if (!databases.contains(database)) {
        if (databases.size() != 1) {
          out.note("The credentials may use " + count(databases.size(), "database") + " of tenant " + tenant
              + ", not " + database + ": choose one.");
          return;
        }
        database = databases.getFirst();
      }
    }
    ConnectionConfig where = StoreSuggestions.with(StoreSuggestions.with(c.cfg(), "tenant", tenant), "database",
        database);
    StoreSuggestions.offerList(this, c.cfg(), collections(new Call(where, c.secrets(), c.ctx())), out);
  }

  @Override
  ObjectNode describe(Call c, String collection) throws ConnectorException {
    Known k = known(c, collection);
    JsonNode d = k.description();
    ObjectNode out = F.objectNode().put("name", collection).put("id", k.id()).put("tenant", tenant(c.cfg()))
        .put("database", database(c.cfg()));
    if (d.path("dimension").isNumber()) {
      out.set("dimension", d.get("dimension"));
    }
    String space = space(d);
    if (space != null) {
      out.put("metric", space);
    }
    if (d.path("metadata").isObject() && !d.get("metadata").isEmpty()) {
      out.set("metadata", d.get("metadata"));
    }
    JsonNode ef = d.path("configuration_json").path("embedding_function");
    if (ef.isObject() && !ef.isEmpty()) {
      out.put("embeddingFunction", ef.path("name").asString(ef.path("type").asString("")));
    }
    JsonNode count = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri(base(c.cfg()) + "/"
        + ServiceHttp.seg(k.id()) + "/count"), null, "Chroma's count of " + collection), 64 * 1024);
    if (count.isNumber()) {
      out.set("records", count);
    }
    out.put("filters", "Metadata filters run on Chroma: text, numbers and true or false; no $exists.");
    return out;
  }

  @Override
  Page get(Call c, Get g) throws ConnectorException {
    Known k = known(c, g.collection());
    ObjectNode body = F.objectNode();
    int offset = 0;
    if (!g.ids().isEmpty()) {
      ArrayNode ids = body.putArray("ids");
      g.ids().forEach(ids::add);
    } else {
      if (g.filter() != null) {
        body.set("where", mongo(g.filter()));
      }
      if (g.cursor() != null) {
        if (!g.cursor().matches("\\d{1,9}")) {
          throw ConnectorException.badInput("cursor is nextCursor of the previous page.");
        }
        offset = Integer.parseInt(g.cursor());
      }
      body.put("limit", g.limit()).put("offset", offset);
    }
    body.set("include", include(g.vectors(), false));
    JsonNode answer = json(c, post(c.cfg(), k.id(), "get", body, "Chroma's records of " + g.collection(), false),
        MAX_READ);
    List<ObjectNode> records = records(answer.path("ids"), answer.path("metadatas"), answer.path("documents"),
        answer.path("embeddings"), null, g.vectors());
    if (!g.ids().isEmpty()) {
      List<String> found = records.stream().map(r -> r.path("id").asString("")).toList();
      List<JsonNode> missing = new ArrayList<>();
      for (JsonNode id : g.ids()) {
        if (!found.contains(id.asString())) {
          missing.add(id);
        }
      }
      return new Page(records, null, missing);
    }
    return new Page(records, records.size() == g.limit() ? Integer.toString(offset + g.limit()) : null, List.of());
  }

  private static List<ObjectNode> records(JsonNode ids, JsonNode metadatas, JsonNode documents, JsonNode embeddings,
      JsonNode distances, boolean vectors) {
    List<ObjectNode> out = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      ObjectNode r = F.objectNode();
      r.set("id", ids.get(i));
      if (distances != null && distances.path(i).isNumber()) {
        r.set("score", distances.get(i));
      }
      JsonNode m = metadatas.path(i);
      r.set("metadata", m.isObject() ? m : F.objectNode());
      JsonNode doc = documents.path(i);
      if (doc.isString()) {
        r.put("document", doc.asString());
      }
      if (vectors && embeddings.path(i).isArray()) {
        r.set("vector", embeddings.get(i));
      }
      out.add(r);
    }
    return out;
  }

  @Override
  List<ObjectNode> query(Call c, Query q) throws ConnectorException {
    Known k = known(c, q.collection());
    ArrayNode vector;
    if (q.vector() != null) {
      vector = F.arrayNode();
      q.vector().forEach(vector::add);
    } else {
      ObjectNode get = F.objectNode();
      get.putArray("ids").add(q.id());
      get.putArray("include").add("embeddings");
      JsonNode answer = json(c, post(c.cfg(), k.id(), "get", get, "Chroma's record " + q.id(), false), 4 << 20);
      JsonNode e = answer.path("embeddings").path(0);
      if (!e.isArray()) {
        throw ConnectorException.badInput(q.collection() + " has no record " + q.id() + ".");
      }
      vector = (ArrayNode) e;
    }
    ObjectNode body = F.objectNode();
    body.putArray("query_embeddings").add(vector);
    body.put("n_results", q.topK());
    if (q.filter() != null) {
      body.set("where", mongo(q.filter()));
    }
    body.set("include", include(q.vectors(), true));
    JsonNode answer = json(c, post(c.cfg(), k.id(), "query", body, "Chroma's query of " + q.collection(), false),
        MAX_READ);
    return records(answer.path("ids").path(0), answer.path("metadatas").path(0), answer.path("documents").path(0),
        answer.path("embeddings").path(0), answer.path("distances").path(0), q.vectors());
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  ObjectNode resolve(Call c, Change change) throws ConnectorException {
    return F.objectNode().put("collectionId", known(c, change.target()).id());
  }

  @Override
  ObjectNode resolvedWithoutIo(ConnectionConfig cfg, Change change) {
    Known k = collections.get(cfg.id() + "|" + cfg.version() + "|" + change.target());
    return F.objectNode().put("collectionId", k == null ? "00000000-0000-0000-0000-000000000000" : k.id());
  }

  @Override
  ServiceHttp.Request request(ConnectionConfig cfg, Change change) throws ConnectorException {
    String id = change.resolved().path("collectionId").asString("");
    if (!UUID.matcher(id).matches()) {
      throw ConnectorException.badInput("Chroma named no id of " + change.target() + ".");
    }
    ObjectNode body = F.objectNode();
    if ("delete".equals(change.op())) {
      body.set("ids", change.ids());
      return post(cfg, id, "delete", body, "Chroma's delete of records from " + change.target(), true);
    }
    ArrayNode ids = body.putArray("ids");
    ArrayNode embeddings = F.arrayNode();
    ArrayNode metadatas = F.arrayNode();
    ArrayNode documents = F.arrayNode();
    int withVector = 0;
    boolean anyMetadata = false;
    boolean anyDocument = false;
    for (JsonNode r : change.records()) {
      ids.add(r.get("id"));
      if (r.has("vector")) {
        embeddings.add(r.get("vector"));
        withVector++;
      }
      metadatas.add(r.has("metadata") ? r.get("metadata") : F.nullNode());
      anyMetadata |= r.has("metadata");
      documents.add(r.has("document") ? F.stringNode(r.get("document").asString()) : F.nullNode());
      anyDocument |= r.has("document");
    }
    if (withVector != 0 && withVector != change.records().size()) {
      throw ConnectorException.badInput("Give every record an embedding, or none (where the collection embeds "
          + "documents itself).");
    }
    if (withVector > 0) {
      body.set("embeddings", embeddings);
    }
    if (anyMetadata) {
      body.set("metadatas", metadatas);
    }
    if (anyDocument) {
      body.set("documents", documents);
    }
    return post(cfg, id, "upsert", body, "Chroma's upsert of records into " + change.target(), true);
  }

  @Override
  ObjectNode changed(Change change, ServiceHttp.Response r) {
    ObjectNode out = F.objectNode();
    if ("delete".equals(change.op())) {
      out.put("deleted", change.count());
      out.put("note", "Chroma deletes the ids that exist and ignores the others.");
    } else {
      out.put("upserted", change.count());
    }
    return out;
  }

  // ── Test connection ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  TestResult test(Call c) {
    long started = System.nanoTime();
    JsonNode identity;
    try {
      json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/api/v2/heartbeat"), null, "Chroma's heartbeat"),
          64 * 1024);
      identity = json(c, ServiceHttp.Request.get(endpoint(c.cfg()).uri("/api/v2/auth/identity"), null,
          "Chroma's identity"), 1 << 20);
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null);
    }
    long latency = (System.nanoTime() - started) / 1_000_000;
    String host = "";
    try {
      host = endpoint(c.cfg()).base().getHost();
    } catch (ConnectorException e) {
      // Shown as Chroma.
    }
    String server = host.endsWith("trychroma.com") ? "Chroma Cloud" : "Chroma";
    List<TestResult.Check> checks = new ArrayList<>();
    checks.add(new TestResult.Check("server", true, latency, server));
    String tenant = identity.path("tenant").asString(null);
    List<String> databases = new ArrayList<>();
    identity.path("databases").forEach(d -> databases.add(d.asString("")));
    if (tenant != null && !tenant.isBlank() && !tenant.equals(tenant(c.cfg()))) {
      return TestResult.failure("chroma.tenant", "The credentials belong to tenant " + tenant + ", not "
          + tenant(c.cfg()) + ": set the connection's tenant to " + tenant + ".", null).withChecks(checks);
    }
    if (!databases.isEmpty() && !databases.contains("*") && !databases.contains(database(c.cfg()))) {
      return TestResult.failure("chroma.database", "The credentials may use the databases " + String.join(", ",
          databases) + ", not " + database(c.cfg()) + ".", null).withChecks(checks);
    }
    long listed = System.nanoTime();
    List<ObjectNode> all;
    try {
      all = collections(c);
    } catch (ConnectorException e) {
      return TestResult.failure(QdrantBackend.code(e), e.getMessage(), null).withChecks(checks);
    }
    long visible = all.stream().filter(o -> allows(c.cfg(), o.path("name").asString(""))).count();
    checks.add(new TestResult.Check("collections", true, (System.nanoTime() - listed) / 1_000_000,
        count(visible, "collection") + " agents may use in " + tenant(c.cfg()) + "/" + database(c.cfg())));
    boolean open = "none".equals(method(c.cfg()));
    String user = identity.path("user_id").asString("");
    TestResult result = TestResult.success(latency, server, open ? "no credentials" : user.isBlank() ? "token" : user,
        new TestResult.Permissions(open ? TestResult.Level.ADMIN : TestResult.Level.UNKNOWN, open ? "The server "
            + "checks no token: anyone who reaches it may read, write and delete." : "Chroma doesn't say what the "
            + "credentials may do.", null, List.of(), null)).withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", server));
    if (open) {
      result = result.withHint(new Hint(Severity.WARNING, "chroma.no-auth", "/auth/method", "The server checks no "
          + "token: anyone who reaches it may write and delete. Keep it on this computer, or put it behind "
          + "authentication."));
    }
    List<Hint> plain = new ArrayList<>();
    QdrantBackend.plainHint(c.cfg(), plain);
    for (Hint h : plain) {
      result = result.withHint(h);
    }
    if (visible == 0) {
      result = result.withHint(new Hint(Severity.WARNING, "chroma.no-collections", "/collections", "No collection "
          + "agents may use."));
    }
    return result;
  }

  @Override
  JsonNode describeConnection(ConnectionConfig cfg) {
    return F.objectNode().put("tenant", tenant(cfg)).put("database", database(cfg)).put("ids", "Text")
        .put("search", "By embedding (vector) or by the id of a stored record; records may carry a document");
  }

  @Override
  void forget(String connectionId) {
    collections.keySet().removeIf(k -> k.startsWith(connectionId + "|"));
  }
}
