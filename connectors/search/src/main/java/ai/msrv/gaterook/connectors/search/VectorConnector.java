package ai.msrv.gaterook.connectors.search;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.search.Args.Refused;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.Glob;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A vector database behind the vector_* tools (docs/vector-databases.md): one connector per type, its backend speaking
 * the store's REST API.
 *
 * <p>{@link #classify} decides without I/O: the collection against the connection's list and the store's name rules,
 * the ids, the filter as GateRook reads it ({@link Filter}), the sizes. Reads run at once. A change — an upsert or a
 * delete by id — is a MUTATION: {@link #prepare} looks up what its request needs (an index host, a collection id) and
 * which of its ids exist, and shows the exact request; {@link #execute} runs only when approved, from the approved
 * payload, and never retries. The store's own credentials remain its boundary: give agents a key that may only read
 * where the store has one.
 */
final class VectorConnector implements Connector {

  static final int MAX_RECORDS = 100;
  static final int MAX_DELETE = 1_000;
  /** One upsert's records as JSON. */
  static final int MAX_CHANGE_BYTES = 1 << 20;
  /** Slack's limit for the text of a section: a longer action can't be approved from a channel. */
  static final int CHANNEL_LIMIT = 3_000;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final VectorBackend backend;
  private final ConnectorManifest manifest;

  VectorConnector(VectorBackend backend) {
    this.backend = Objects.requireNonNull(backend, "backend");
    SchemaResources schemas = SchemaResources.of(VectorConnector.class, "search");
    this.manifest = ConnectorManifest.builder(backend.type(), BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name(backend.name())
        .icon("scan-search")
        .group("Databases")
        .description(backend.description())
        .configSchema(schemas.load(backend.type() + ".config"))
        .tools(VectorTools.definitions(schemas))
        .access(ConnectorManifest.RequiredAccess.none())
        .build();
  }

  VectorBackend backend() {
    return backend;
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    try {
      backend.endpoint(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    JsonNode args = call.arguments();
    try {
      switch (call.tool()) {
        case VectorTools.LIST -> {
          like(args);
          return Classification.read();
        }
        case VectorTools.DESCRIBE -> {
          collection(args, cfg);
          return Classification.read();
        }
        case VectorTools.GET -> {
          get(args, cfg, Integer.MAX_VALUE);
          return Classification.read();
        }
        case VectorTools.QUERY -> {
          query(args, cfg, Integer.MAX_VALUE);
          return Classification.read();
        }
        case VectorTools.UPSERT, VectorTools.DELETE -> {
          Change change = change(call.tool(), args, cfg);
          ObjectNode facts = F.objectNode().put("collection", change.target()).put("records", change.count());
          if (change.namespace() != null) {
            facts.put(backend.namespaceWord(), change.namespace());
          }
          return Classification.mutation(summary(change), facts);
        }
        default -> {
          return Classification.deny(policy.rule("unknown-tool"), backend.name() + " has no tool " + call.tool()
              + ".", null);
        }
      }
    } catch (Refused r) {
      return r.denial(policy);
    }
  }

  private static String like(JsonNode args) throws Refused {
    String like = Args.text(args, "like");
    if (like != null && (like.isBlank() || like.length() > 255)) {
      throw new Refused("bad-argument", "like is a name with * for any characters.", null);
    }
    return like;
  }

  private String collection(JsonNode args, ConnectionConfig cfg) throws Refused {
    return Args.target(args, "collection", backend, cfg, "collection", VectorTools.LIST);
  }

  private String namespace(JsonNode args) throws Refused {
    String ns = Args.text(args, "namespace");
    if (ns == null) {
      if (args != null && args.has("namespace") && !args.get("namespace").isNull()) {
        throw new Refused("bad-argument", "namespace is text.", null);
      }
      return null;
    }
    if (backend.namespaceWord() == null) {
      throw new Refused("bad-argument", backend.name() + " has no namespaces: leave namespace out.", null);
    }
    if (ns.isEmpty() || ns.length() > 512 || ns.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new Refused("bad-argument", "A " + backend.namespaceWord() + " has 1 to 512 characters, without control "
          + "characters.", null);
    }
    return ns;
  }

  private VectorBackend.Get get(JsonNode args, ConnectionConfig cfg, int maxRows) throws Refused {
    String collection = collection(args, cfg);
    String ns = namespace(args);
    List<JsonNode> ids = Args.ids(args, "ids", MAX_RECORDS, backend::idProblem);
    Filter.Node filter = Args.filter(args, backend::checkFilter);
    if (!ids.isEmpty() && filter != null) {
      throw new Refused("bad-argument", "Give ids or a filter, not both.", null);
    }
    String cursor = Args.text(args, "cursor");
    if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048)) {
      throw new Refused("bad-argument", "cursor is nextCursor of the previous page.", null);
    }
    if (cursor != null && !ids.isEmpty()) {
      throw new Refused("bad-argument", "A cursor pages through a filter's records, not through ids.", null);
    }
    int limit = Math.min(Args.number(args, "limit", 1, 100, 10), maxRows);
    return new VectorBackend.Get(collection, ns, ids, filter, Math.max(1, limit), cursor,
        Args.flag(args, "includeVectors"));
  }

  private VectorBackend.Query query(JsonNode args, ConnectionConfig cfg, int maxRows) throws Refused {
    String collection = collection(args, cfg);
    String ns = namespace(args);
    JsonNode vectorArg = args.get("vector");
    JsonNode idArg = args.get("id");
    String text = Args.text(args, "text");
    int given = (vectorArg == null || vectorArg.isNull() ? 0 : 1) + (idArg == null || idArg.isNull() ? 0 : 1)
        + (text == null ? 0 : 1);
    if (given != 1) {
      throw new Refused("bad-argument", "Search near exactly one of vector, id or text.", null);
    }
    List<Double> vector = null;
    JsonNode id = null;
    if (vectorArg != null && !vectorArg.isNull()) {
      vector = Args.vector(vectorArg, "vector");
    } else if (idArg != null && !idArg.isNull()) {
      String problem = Args.idText(idArg);
      if (problem == null) {
        problem = backend.idProblem(idArg);
      }
      if (problem != null) {
        throw new Refused("bad-argument", problem, null);
      }
      id = idArg;
    } else if (text.isBlank() || text.length() > 4_000) {
      throw new Refused("bad-argument", "text has 1 to 4,000 characters.", null);
    }
    VectorBackend.TextMode mode = VectorBackend.TextMode.SEMANTIC;
    String m = Args.text(args, "textMode");
    if (m != null) {
      try {
        mode = VectorBackend.TextMode.valueOf(m.toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new Refused("bad-argument", "textMode is semantic, keyword or hybrid.", null);
      }
    }
    Filter.Node filter = Args.filter(args, backend::checkFilter);
    String vectorName = Args.text(args, "vectorName");
    if (vectorName != null && (vectorName.isBlank() || vectorName.length() > 255)) {
      throw new Refused("bad-argument", "vectorName names one of the collection's vectors.", null);
    }
    int topK = Math.min(Args.number(args, "topK", 1, 100, 10), maxRows);
    VectorBackend.Query q = new VectorBackend.Query(collection, ns, vector, id, text, mode, filter, Math.max(1, topK),
        vectorName, Args.flag(args, "includeVectors"));
    String problem = backend.queryProblem(q);
    if (problem != null) {
      throw new Refused("unsupported", problem, null);
    }
    return q;
  }

  /** The change an upsert or a delete asks for, checked; nothing looked up yet. */
  private Change change(String tool, JsonNode args, ConnectionConfig cfg) throws Refused {
    String collection = collection(args, cfg);
    String ns = namespace(args);
    if (VectorTools.DELETE.equals(tool)) {
      List<JsonNode> ids = Args.ids(args, "ids", MAX_DELETE, backend::idProblem);
      if (ids.isEmpty()) {
        throw new Refused("bad-argument", "Name the ids to delete.", null);
      }
      ArrayNode list = F.arrayNode();
      ids.forEach(list::add);
      return new Change("delete", collection, ns, null, list, null);
    }
    JsonNode records = args.get("records");
    if (records == null || !records.isArray() || records.isEmpty()) {
      throw new Refused("bad-argument", "records is a list of records to write.", null);
    }
    if (records.size() > MAX_RECORDS) {
      throw new Refused("too-many", "One upsert writes at most " + MAX_RECORDS + " records; this one has "
          + records.size() + ".", "Split them into several upserts.");
    }
    ArrayNode out = F.arrayNode();
    Set<String> seen = new HashSet<>();
    for (JsonNode r : records) {
      out.add(record(r, seen));
    }
    if (ServiceHttp.bytes(out).length > MAX_CHANGE_BYTES) {
      throw new Refused("too-large", "One upsert is at most 1 MiB of records.", "Split them into several upserts.");
    }
    return new Change("upsert", collection, ns, out, null, null);
  }

  /** One record of an upsert, normalized: {@code {id, vector?, metadata?, document?}}. */
  private ObjectNode record(JsonNode r, Set<String> seen) throws Refused {
    if (!r.isObject()) {
      throw new Refused("bad-argument", "Each record is an object with an id.", null);
    }
    for (String key : r.propertyNames()) {
      if (!Set.of("id", "vector", "metadata", "document").contains(key)) {
        throw new Refused("bad-argument", "A record has id, vector, metadata and document; not " + key + ".", null);
      }
    }
    JsonNode id = r.get("id");
    String problem = Args.idText(id);
    if (problem == null) {
      problem = backend.idProblem(id);
    }
    if (problem != null) {
      throw new Refused("bad-argument", problem, null);
    }
    if (!seen.add(id.toString())) {
      throw new Refused("bad-argument", "The id " + id + " is in records twice.", null);
    }
    ObjectNode out = F.objectNode();
    out.set("id", id);
    JsonNode vector = r.get("vector");
    if (vector != null && !vector.isNull()) {
      if (vector.isArray()) {
        ArrayNode values = F.arrayNode();
        Args.vector(vector, "The vector of " + id).forEach(values::add);
        out.set("vector", values);
      } else if (vector.isObject() && !vector.isEmpty() && vector.size() <= 16) {
        ObjectNode named = F.objectNode();
        for (var e : vector.properties()) {
          ArrayNode values = F.arrayNode();
          Args.vector(e.getValue(), "The vector " + e.getKey() + " of " + id).forEach(values::add);
          named.set(e.getKey(), values);
        }
        out.set("vector", named);
      } else {
        throw new Refused("bad-argument", "The vector of " + id + " is a list of numbers, or named lists.", null);
      }
    }
    JsonNode metadata = r.get("metadata");
    if (metadata != null && !metadata.isNull()) {
      if (!metadata.isObject()) {
        throw new Refused("bad-argument", "The metadata of " + id + " is an object.", null);
      }
      out.set("metadata", metadata.deepCopy());
    }
    JsonNode document = r.get("document");
    if (document != null && !document.isNull()) {
      if (!document.isString()) {
        throw new Refused("bad-argument", "The document of " + id + " is text.", null);
      }
      out.put("document", document.asString());
    }
    String rule = backend instanceof Recorded rec ? rec.recordProblem(out) : null;
    if (rule != null) {
      throw new Refused("bad-record", rule, null);
    }
    return out;
  }

  /** A backend's own rules for a record of an upsert, without I/O. */
  interface Recorded {
    /** Why {@code record} can't be written here, or {@code null}. */
    String recordProblem(ObjectNode record);
  }

  private String summary(Change change) {
    String where = change.target() + (change.namespace() == null ? "" : " (" + backend.namespaceWord() + " "
        + change.namespace() + ")");
    return "delete".equals(change.op())
        ? "Deletes " + StoreBackend.count(change.count(), "record") + " from " + where
        : "Upserts " + StoreBackend.count(change.count(), "record") + " into " + where;
  }

  // ── Approval ────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    if (VectorTools.UPSERT.equals(call.tool()) || VectorTools.DELETE.equals(call.tool())) {
      try {
        Change change = change(call.tool(), call.arguments(), cfg);
        change = change.withResolved(backend.resolvedWithoutIo(cfg, change));
        return preview(cfg, change, List.of(), null);
      } catch (Refused | ConnectorException e) {
        return ActionPreview.of(ActionPreview.Kind.TEXT, "text", e.getMessage(), cfg.name(),
            StoreBackend.shorten(call.tool() + " on " + cfg.name() + ": not runnable", 120))
            .withPayload(F.objectNode().set("arguments", call.arguments().deepCopy()));
      }
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), target(cfg),
            StoreBackend.shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Records, no change")));
  }

  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    if (!VectorTools.UPSERT.equals(call.tool()) && !VectorTools.DELETE.equals(call.tool())) {
      return PreparedAction.approval(describeAction(call, cfg), null);
    }
    Change change;
    try {
      change = change(call.tool(), call.arguments(), cfg);
    } catch (Refused r) {
      return PreparedAction.reject(r.getMessage());
    }
    Call c = new Call(cfg, secrets, ctx);
    try {
      change = change.withResolved(backend.resolve(c, change));
    } catch (ConnectorException e) {
      return PreparedAction.reject(e.getMessage());
    }
    List<String> warnings = new ArrayList<>();
    List<JsonNode> existing = null;
    try {
      existing = backend.existing(c, change);
    } catch (ConnectorException e) {
      warnings.add("GateRook couldn't check which ids exist: " + e.getMessage());
    }
    try {
      return PreparedAction.approval(preview(cfg, change, warnings, existing), null);
    } catch (ConnectorException e) {
      return PreparedAction.reject(e.getMessage());
    }
  }

  /** The approver's view: the exact request, what it changes, and which ids exist. */
  private ActionPreview preview(ConnectionConfig cfg, Change change, List<String> warningsSoFar,
      List<JsonNode> existing) throws ConnectorException {
    ServiceHttp.Request request = backend.request(cfg, change);
    ServiceHttp.Endpoint endpoint = backend.changeEndpoint(cfg, change);
    String text = requestText(request, backend.authShown(cfg));
    boolean delete = "delete".equals(change.op());
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("Collection", change.target()));
    if (change.namespace() != null) {
      fields.add(new Field(capitalized(backend.namespaceWord()), change.namespace()));
    }
    fields.add(new Field(delete ? "Ids" : "Records", Integer.toString(change.count())));
    List<String> shown = new ArrayList<>();
    if (delete) {
      for (int i = 0; i < Math.min(change.ids().size(), 20); i++) {
        shown.add(change.ids().get(i).toString());
      }
      fields.add(new Field(change.ids().size() <= 20 ? "Each id" : "First 20 ids", String.join(", ", shown)));
    } else {
      for (int i = 0; i < Math.min(change.records().size(), 10); i++) {
        shown.add(StoreBackend.shorten(describeRecord(change.records().get(i)), 500));
      }
      fields.add(new Field(change.records().size() <= 10 ? "Each record" : "First 10 records",
          String.join("\n", shown)));
    }
    List<String> warnings = new ArrayList<>(warningsSoFar);
    if (existing != null) {
      int asked = Math.min(change.count(), 100);
      if (delete) {
        int missing = asked - existing.size();
        if (missing > 0) {
          warnings.add(missing + " of " + (asked == change.count() ? "the " + asked : "the first " + asked)
              + " ids don't exist: deleting them changes nothing.");
        }
      } else if (!existing.isEmpty()) {
        warnings.add(StoreBackend.count(existing.size(), "record") + " already exist" + (existing.size() == 1 ? "s"
            : "") + " and will be replaced: " + StoreBackend.shorten(existing.toString(), 300) + ".");
      }
    }
    if (text.length() > CHANNEL_LIMIT) {
      warnings.add("The request is longer than 3,000 characters: Slack and Telegram show it cut and don't offer "
          + "Approve. Read it here.");
    }
    List<Field> impact = new ArrayList<>();
    impact.add(new Field(delete ? "Deletes" : "Writes", StoreBackend.count(change.count(), "record") + (delete
        ? " by id from " : " to ") + change.target() + " on " + endpoint.target()));
    if (!delete) {
      impact.add(new Field("Existing records", "A record with the same id is replaced whole: its vector and "
          + "metadata"));
    }
    impact.add(new Field("Undo", delete ? "None: the store keeps no copy" : "None: the records replaced aren't kept"));
    impact.add(new Field("Retried", "No: an answer lost after sending is reported as unknown"));
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("collection", change.target());
    facets.put("records", Integer.toString(change.count()));
    facets.put("op", change.op());
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text, target(cfg) + " · " + change.target(),
            StoreBackend.shorten((delete ? VectorTools.DELETE : VectorTools.UPSERT) + " on " + cfg.name() + ": "
                + summary(change), 120))
        .withFields(fields)
        .withImpact(impact)
        .withWarnings(warnings)
        .withFacets(facets)
        .withPayload(change.payload());
  }

  private static String describeRecord(JsonNode r) {
    StringBuilder out = new StringBuilder(r.get("id").toString());
    JsonNode v = r.get("vector");
    if (v != null && v.isArray()) {
      out.append(" · vector of ").append(v.size());
    } else if (v != null && v.isObject()) {
      List<String> names = new ArrayList<>();
      v.properties().forEach(e -> names.add(e.getKey() + " (" + e.getValue().size() + ")"));
      out.append(" · vectors ").append(String.join(", ", names));
    }
    if (r.has("metadata")) {
      out.append(" · ").append(r.get("metadata"));
    }
    if (r.has("document")) {
      out.append(" · document \"").append(StoreBackend.shorten(r.get("document").asString(), 120)).append('"');
    }
    return out.toString();
  }

  /** What the approver reads: the request line, the headers GateRook sets, a blank line, the body as it is sent. */
  static String requestText(ServiceHttp.Request r, List<String> auth) {
    StringBuilder text = new StringBuilder(r.method()).append(' ').append(r.uri()).append('\n');
    if (r.headers() != null) {
      r.headers().forEach((k, v) -> v.forEach(value -> text.append(k).append(": ").append(value).append('\n')));
    }
    auth.forEach(h -> text.append(h).append('\n'));
    if (r.body() != null) {
      text.append('\n').append(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8));
    }
    return text.toString();
  }

  private static String capitalized(String word) {
    return word == null || word.isEmpty() ? "" : Character.toUpperCase(word.charAt(0)) + word.substring(1);
  }

  private String target(ConnectionConfig cfg) {
    try {
      return cfg.name() + " · " + backend.endpoint(cfg).target();
    } catch (ConnectorException e) {
      return cfg.name();
    }
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    Call c = new Call(cfg, secrets, ctx);
    JsonNode args = arguments(call, ctx);
    int maxRows = Math.max(1, ctx.limits().maxRows());
    try {
      return switch (call.tool()) {
        case VectorTools.LIST -> list(c, like(args));
        case VectorTools.DESCRIBE -> {
          String collection = collection(args, cfg);
          ObjectNode out = backend.describe(c, collection);
          yield ToolResult.json(out, "Collection " + collection + " of " + cfg.name() + ".");
        }
        case VectorTools.GET -> get(c, get(args, cfg, maxRows));
        case VectorTools.QUERY -> query(c, query(args, cfg, maxRows));
        case VectorTools.UPSERT, VectorTools.DELETE -> change(c, call.tool());
        default -> throw ConnectorException.badInput(backend.name() + " has no tool " + call.tool() + ".");
      };
    } catch (Refused r) {
      throw r.exception();
    }
  }

  /** The arguments that run: the approved request's, or the call's. */
  private static JsonNode arguments(ToolCall call, ExecContext ctx) {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      return ctx.approvedPayload().get().get("arguments");
    }
    return call.arguments();
  }

  private ToolResult list(Call c, String like) throws ConnectorException {
    List<ObjectNode> all = backend.collections(c);
    ArrayNode out = F.arrayNode();
    int hidden = 0;
    for (ObjectNode coll : all) {
      String name = coll.path("name").asString("");
      if (!backend.allows(c.cfg(), name)) {
        hidden++;
        continue;
      }
      if (like != null && !Glob.text(like).matches(name)) {
        continue;
      }
      out.add(coll);
    }
    ObjectNode result = F.objectNode();
    result.set("collections", out);
    if (hidden > 0) {
      result.put("notListed", hidden);
    }
    fit(c.ctx(), out, result);
    return ToolResult.json(result, StoreBackend.count(out.size(), "collection") + " of " + c.cfg().name() + ".");
  }

  private ToolResult get(Call c, VectorBackend.Get g) throws ConnectorException {
    VectorBackend.Page page = backend.get(c, g);
    ObjectNode out = F.objectNode().put("collection", g.collection());
    if (g.namespace() != null) {
      out.put(backend.namespaceWord(), g.namespace());
    }
    ArrayNode records = out.putArray("records");
    page.records().forEach(records::add);
    if (page.missing() != null && !page.missing().isEmpty()) {
      ArrayNode missing = out.putArray("missing");
      page.missing().forEach(missing::add);
    }
    if (page.nextCursor() != null) {
      out.put("nextCursor", page.nextCursor());
    }
    fit(c.ctx(), records, out);
    return ToolResult.json(out, StoreBackend.count(records.size(), "record") + " of " + g.collection() + ".");
  }

  private ToolResult query(Call c, VectorBackend.Query q) throws ConnectorException {
    List<ObjectNode> matches = backend.query(c, q);
    ObjectNode out = F.objectNode().put("collection", q.collection());
    if (q.namespace() != null) {
      out.put(backend.namespaceWord(), q.namespace());
    }
    ArrayNode list = out.putArray("matches");
    matches.forEach(list::add);
    fit(c.ctx(), list, out);
    return ToolResult.json(out, list.size() + (list.size() == 1 ? " match" : " matches") + " in " + q.collection()
        + ".");
  }

  /**
   * Keeps the answer within the call's size: records are dropped from the end, and {@code truncated} says how many,
   * so an answer never ends in the middle of a record.
   */
  static void fit(ExecContext ctx, ArrayNode items, ObjectNode out) {
    long budget = Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
    long size = ServiceHttp.bytes(out).length;
    int dropped = 0;
    while (size > budget && !items.isEmpty()) {
      JsonNode last = items.remove(items.size() - 1);
      size -= ServiceHttp.bytes(last).length + 1;
      dropped++;
    }
    if (dropped > 0) {
      out.put("truncated", dropped);
      out.put("truncatedReason", "The answer reached the call's size limit: ask for fewer records, or leave the "
          + "vectors out.");
    }
  }

  private ToolResult change(Call c, String tool) throws ConnectorException {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: " + tool + " changes data, so a person approves it first.");
    }
    Change change = Change.of(ctx.approvedPayload().orElse(null));
    // Defence in depth: execute gets no policy, so the change is checked against the connection again.
    if (!(VectorTools.DELETE.equals(tool) ? "delete" : "upsert").equals(change.op())) {
      throw ConnectorException.denied("The approved action is another change than " + tool + ".");
    }
    if (backend.nameProblem(change.target()) != null || !backend.allows(c.cfg(), change.target())) {
      throw ConnectorException.denied(change.target() + " isn't one of the collections of " + c.cfg().name() + ".");
    }
    if (change.count() == 0 || change.count() > ("delete".equals(change.op()) ? MAX_DELETE : MAX_RECORDS)) {
      throw ConnectorException.denied("The approved change has no records or too many.");
    }
    ServiceHttp.Request request = backend.request(c.cfg(), change);
    ServiceHttp.Response response = backend.send(c, backend.changeEndpoint(c.cfg(), change), request,
        ServiceHttp.MAX_ERROR * 16);
    if (!response.ok()) {
      throw backend.failure(c.cfg(), request, response);
    }
    ObjectNode out = backend.changed(change, response);
    out.put("collection", change.target());
    if (change.namespace() != null) {
      out.put(backend.namespaceWord(), change.namespace());
    }
    return ToolResult.json(out, ("delete".equals(change.op()) ? "Deleted " : "Upserted ")
        + StoreBackend.count(change.count(), "record") + ("delete".equals(change.op()) ? " from " : " into ")
        + change.target() + ".");
  }

  // ── Test connection, describe ───────────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      backend.endpoint(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure(backend.type() + ".settings", e.getMessage(), null);
    }
    return backend.test(new Call(cfg, secrets, ctx));
  }

  /**
   * Setup helpers: a store answering on its usual port of this computer, offered as its address; signed in, the
   * collections the credentials see (Milvus's databases and Chroma's tenant and databases too).
   */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return StoreSuggestions.suggest(backend, cfg, secrets, ctx, scope);
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode().put("store", backend.name());
    JsonNode own = backend.describeConnection(cfg);
    if (own != null && own.isObject()) {
      own.properties().forEach(e -> out.set(e.getKey(), e.getValue()));
    }
    List<String> listed = cfg.strings("/collections");
    if (listed.isEmpty()) {
      out.put("collections", "Every collection the credentials see");
    } else {
      ArrayNode a = out.putArray("collections");
      listed.forEach(a::add);
    }
    out.put("filters", "MongoDB-style metadata filters ($eq $ne $gt $gte $lt $lte $in $nin $exists $and $or), "
        + "evaluated by " + backend.name());
    if (backend.namespaceWord() != null) {
      out.put("namespace", "vector_*'s namespace is a " + backend.namespaceWord() + " here");
    }
    out.put("changes", "vector_upsert and vector_delete (by id) after approval");
    if (object != null && !object.isBlank()) {
      out.put("collection", "Call vector_describe_collection with collection " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    backend.forget(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    backend.forget(connectionId);
  }

  @Override
  public void close() {
    backend.close();
  }
}
