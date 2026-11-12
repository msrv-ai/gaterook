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
 * A search engine behind the search_* tools (docs/search-engines.md): one connector per type, its backend speaking the
 * engine's REST API. Like {@link VectorConnector}: {@link #classify} reads the call without I/O, reads run at once, a
 * write or a delete of documents is a MUTATION whose exact request {@link #prepare} shows with the ids that exist, and
 * {@link #execute} runs only the approved payload, never retried.
 */
final class SearchConnector implements Connector {

  static final int MAX_DOCUMENTS = 100;
  static final int MAX_DELETE = 1_000;
  static final int MAX_CHANGE_BYTES = 1 << 20;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final SearchBackend backend;
  private final ConnectorManifest manifest;

  SearchConnector(SearchBackend backend) {
    this.backend = Objects.requireNonNull(backend, "backend");
    SchemaResources schemas = SchemaResources.of(SearchConnector.class, "search");
    this.manifest = ConnectorManifest.builder(backend.type(), BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name(backend.name())
        .icon("search")
        .group("Databases")
        .description(backend.description())
        .configSchema(schemas.load(backend.type() + ".config"))
        .tools(SearchTools.definitions(schemas))
        .access(ConnectorManifest.RequiredAccess.none())
        .build();
  }

  SearchBackend backend() {
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
        case SearchTools.LIST -> {
          like(args);
          return Classification.read();
        }
        case SearchTools.DESCRIBE -> {
          index(args, cfg);
          return Classification.read();
        }
        case SearchTools.QUERY -> {
          query(args, cfg, Integer.MAX_VALUE);
          return Classification.read();
        }
        case SearchTools.GET -> {
          index(args, cfg);
          ids(args, MAX_DOCUMENTS);
          fields(args, "fields", 100);
          return Classification.read();
        }
        case SearchTools.INDEX, SearchTools.DELETE -> {
          Change change = change(call.tool(), args, cfg);
          ObjectNode facts = F.objectNode().put("index", change.target()).put("documents", change.count());
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

  private String index(JsonNode args, ConnectionConfig cfg) throws Refused {
    return Args.target(args, "index", backend, cfg, "index", SearchTools.LIST);
  }

  private List<JsonNode> ids(JsonNode args, int max) throws Refused {
    List<JsonNode> ids = Args.ids(args, "ids", max, backend::idProblem);
    if (ids.isEmpty()) {
      throw new Refused("bad-argument", "Name the ids of the documents.", null);
    }
    return ids;
  }

  private List<String> fields(JsonNode args, String name, int max) throws Refused {
    JsonNode list = args == null ? null : args.get(name);
    if (list == null || list.isNull()) {
      return List.of();
    }
    if (!list.isArray() || list.size() > max) {
      throw new Refused("bad-argument", name + " is a list of at most " + max + " field names.", null);
    }
    List<String> out = new ArrayList<>();
    for (JsonNode f : list) {
      String v = f.isString() ? f.asString() : null;
      if (v == null || !backend.fieldPattern().matcher(v).matches()) {
        throw new Refused("bad-argument", "The field " + (v == null ? f.toString() : v) + " of " + name + " can't be "
            + "used here: " + backend.fieldRule(), null);
      }
      if (!out.contains(v)) {
        out.add(v);
      }
    }
    return out;
  }

  private SearchBackend.Query query(JsonNode args, ConnectionConfig cfg, int maxRows) throws Refused {
    String index = index(args, cfg);
    String text = Args.text(args, "query");
    if (text != null && (text.length() > 1_000 || text.chars().anyMatch(c -> c < 0x20 && c != '\t'))) {
      throw new Refused("bad-argument", "query has at most 1,000 characters, without control characters.", null);
    }
    Filter.Node filter = Args.filter(args, backend::checkFilter);
    List<String> facets = fields(args, "facets", 20);
    List<SearchBackend.Sort> sort = new ArrayList<>();
    JsonNode sorts = args.get("sort");
    if (sorts != null && !sorts.isNull()) {
      if (!sorts.isArray() || sorts.size() > 5) {
        throw new Refused("bad-argument", "sort is a list of at most 5 field:asc or field:desc.", null);
      }
      for (JsonNode s : sorts) {
        String v = s.isString() ? s.asString() : "";
        boolean desc = v.endsWith(":desc");
        String f = v.endsWith(":desc") ? v.substring(0, v.length() - 5) : v.endsWith(":asc") ? v.substring(0,
            v.length() - 4) : v;
        if (!backend.fieldPattern().matcher(f).matches()) {
          throw new Refused("bad-argument", "sort takes field:asc or field:desc; " + backend.fieldRule(), null);
        }
        sort.add(new SearchBackend.Sort(f, desc));
      }
    }
    List<String> fields = fields(args, "fields", 100);
    List<String> searchFields = fields(args, "searchFields", 50);
    int limit = Math.max(1, Math.min(Args.number(args, "limit", 1, 100, 10), maxRows));
    int offset = Args.number(args, "offset", 0, 10_000, 0);
    List<Double> vector = null;
    JsonNode v = args.get("vector");
    if (v != null && !v.isNull()) {
      vector = Args.vector(v, "vector");
    }
    String vectorField = Args.text(args, "vectorField");
    if (vectorField != null && !backend.fieldPattern().matcher(vectorField).matches()) {
      throw new Refused("bad-argument", "vectorField names a field: " + backend.fieldRule(), null);
    }
    if (vectorField != null && vector == null) {
      throw new Refused("bad-argument", "vectorField goes with vector.", null);
    }
    SearchBackend.Query q = new SearchBackend.Query(index, text == null || text.isBlank() ? null : text, filter,
        facets, sort, fields, searchFields, limit, offset, vector, vectorField);
    String problem = backend.queryProblem(q);
    if (problem != null) {
      throw new Refused("unsupported", problem, null);
    }
    return q;
  }

  private Change change(String tool, JsonNode args, ConnectionConfig cfg) throws Refused {
    String index = index(args, cfg);
    if (SearchTools.DELETE.equals(tool)) {
      ArrayNode list = F.arrayNode();
      for (JsonNode id : ids(args, MAX_DELETE)) {
        list.add(id.isString() ? id : F.stringNode(SearchBackend.idText(id)));
      }
      return new Change("delete", index, null, null, list, null);
    }
    JsonNode docs = args.get("documents");
    if (docs == null || !docs.isArray() || docs.isEmpty()) {
      throw new Refused("bad-argument", "documents is a list of documents to write.", null);
    }
    if (docs.size() > MAX_DOCUMENTS) {
      throw new Refused("too-many", "One write holds at most " + MAX_DOCUMENTS + " documents; this one has "
          + docs.size() + ".", "Split them into several writes.");
    }
    String mode = Objects.requireNonNullElse(Args.text(args, "mode"), "replace");
    if (!mode.equals("replace") && !mode.equals("merge")) {
      throw new Refused("bad-argument", "mode is replace or merge.", null);
    }
    String idField = backend.fixedIdField();
    ArrayNode out = F.arrayNode();
    Set<String> seen = new HashSet<>();
    for (JsonNode d : docs) {
      if (!d.isObject() || d.isEmpty()) {
        throw new Refused("bad-argument", "Each document is an object of fields.", null);
      }
      if (idField != null) {
        JsonNode id = d.get(idField);
        String problem = Args.idText(id);
        if (problem == null) {
          problem = backend.idProblem(id);
        }
        if (problem != null) {
          throw new Refused("bad-argument", "Each document carries its id in " + idField + ": " + problem, null);
        }
        if (!seen.add(SearchBackend.idText(id))) {
          throw new Refused("bad-argument", "The id " + id + " is in documents twice.", null);
        }
      }
      out.add(d.deepCopy());
    }
    if (ServiceHttp.bytes(out).length > MAX_CHANGE_BYTES) {
      throw new Refused("too-large", "One write is at most 1 MiB of documents.", "Split them into several writes.");
    }
    return new Change(mode.equals("merge") ? "merge" : "upsert", index, null, out, null, null);
  }

  private String summary(Change change) {
    return switch (change.op()) {
      case "delete" -> "Deletes " + StoreBackend.count(change.count(), "document") + " from " + change.target();
      case "merge" -> "Merges " + StoreBackend.count(change.count(), "document") + " into " + change.target();
      default -> "Indexes " + StoreBackend.count(change.count(), "document") + " into " + change.target();
    };
  }

  /** Checks the documents of a write against the id field looked up, before the approval. */
  private static String idProblems(Change change) {
    if ("delete".equals(change.op())) {
      return null;
    }
    String field = SearchBackend.idFieldOf(change);
    Set<String> seen = new HashSet<>();
    for (JsonNode d : change.records()) {
      JsonNode id = d.get(field);
      if (Args.idText(id) != null) {
        return "Each document carries its id in " + field + ", the index's id field: one doesn't.";
      }
      if (!seen.add(SearchBackend.idText(id))) {
        return "The id " + id + " is in documents twice.";
      }
    }
    return null;
  }

  // ── Approval ────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    if (SearchTools.INDEX.equals(call.tool()) || SearchTools.DELETE.equals(call.tool())) {
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
    ObjectNode args = call.arguments().deepCopy();
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), target(cfg),
            StoreBackend.shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Documents, no change")));
  }

  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    if (!SearchTools.INDEX.equals(call.tool()) && !SearchTools.DELETE.equals(call.tool())) {
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
    String problem = idProblems(change);
    if (problem != null) {
      return PreparedAction.reject(problem);
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

  private ActionPreview preview(ConnectionConfig cfg, Change change, List<String> warningsSoFar,
      List<JsonNode> existing) throws ConnectorException {
    ServiceHttp.Request request = backend.request(cfg, change);
    ServiceHttp.Endpoint endpoint = backend.changeEndpoint(cfg, change);
    String text = VectorConnector.requestText(request, backend.authShown(cfg));
    boolean delete = "delete".equals(change.op());
    List<JsonNode> ids = SearchBackend.ids(change);
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("Index", change.target()));
    fields.add(new Field(delete ? "Ids" : "Documents", Integer.toString(change.count())));
    if (!delete) {
      fields.add(new Field("Mode", "merge".equals(change.op()) ? "Merge fields into existing documents (create "
          + "missing ones)" : "Replace whole documents (add new ones)"));
      fields.add(new Field("Id field", SearchBackend.idFieldOf(change)));
    }
    List<String> shown = new ArrayList<>();
    if (delete) {
      for (int i = 0; i < Math.min(ids.size(), 20); i++) {
        shown.add(SearchBackend.idText(ids.get(i)));
      }
      fields.add(new Field(ids.size() <= 20 ? "Each id" : "First 20 ids", String.join(", ", shown)));
    } else {
      for (int i = 0; i < Math.min(change.records().size(), 10); i++) {
        shown.add(StoreBackend.shorten(change.records().get(i).toString(), 500));
      }
      fields.add(new Field(change.records().size() <= 10 ? "Each document" : "First 10 documents",
          String.join("\n", shown)));
    }
    List<String> warnings = new ArrayList<>(warningsSoFar);
    if (existing != null) {
      int asked = Math.min(ids.size(), 100);
      if (delete) {
        int missing = asked - existing.size();
        if (missing > 0) {
          warnings.add(missing + " of " + (asked == ids.size() ? "the " + asked : "the first " + asked)
              + " ids don't exist: deleting them changes nothing.");
        }
      } else if (!existing.isEmpty()) {
        warnings.add(StoreBackend.count(existing.size(), "document") + " already exist" + (existing.size() == 1 ? "s"
            : "") + " and will be " + ("merge".equals(change.op()) ? "updated" : "replaced") + ": "
            + StoreBackend.shorten(existing.toString(), 300) + ".");
      }
    }
    if (text.length() > VectorConnector.CHANNEL_LIMIT) {
      warnings.add("The request is longer than 3,000 characters: Slack and Telegram show it cut and don't offer "
          + "Approve. Read it here.");
    }
    List<Field> impact = new ArrayList<>();
    impact.add(new Field(delete ? "Deletes" : "Writes", StoreBackend.count(change.count(), "document") + (delete
        ? " by id from " : " to ") + change.target() + " on " + endpoint.target()));
    impact.add(new Field("Undo", "None: the engine keeps no copy of what it replaces or deletes"));
    impact.add(new Field("Retried", "No: an answer lost after sending is reported as unknown"));
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("index", change.target());
    facets.put("documents", Integer.toString(change.count()));
    facets.put("op", change.op());
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text, target(cfg) + " · " + change.target(),
            StoreBackend.shorten((delete ? SearchTools.DELETE : SearchTools.INDEX) + " on " + cfg.name() + ": "
                + summary(change), 120))
        .withFields(fields)
        .withImpact(impact)
        .withWarnings(warnings)
        .withFacets(facets)
        .withPayload(change.payload());
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
        case SearchTools.LIST -> list(c, like(args));
        case SearchTools.DESCRIBE -> {
          String index = index(args, cfg);
          yield ToolResult.json(backend.describe(c, index), "Index " + index + " of " + cfg.name() + ".");
        }
        case SearchTools.QUERY -> search(c, query(args, cfg, maxRows));
        case SearchTools.GET -> {
          String index = index(args, cfg);
          List<JsonNode> ids = ids(args, Math.min(MAX_DOCUMENTS, Math.max(maxRows, 1)));
          SearchBackend.Documents docs = backend.documents(c, index, ids, fields(args, "fields", 100));
          ObjectNode out = F.objectNode().put("index", index);
          ArrayNode list = out.putArray("documents");
          docs.documents().forEach(list::add);
          if (!docs.missing().isEmpty()) {
            ArrayNode missing = out.putArray("missing");
            docs.missing().forEach(missing::add);
          }
          VectorConnector.fit(ctx, list, out);
          yield ToolResult.json(out, StoreBackend.count(list.size(), "document") + " of " + index + ".");
        }
        case SearchTools.INDEX, SearchTools.DELETE -> change(c, call.tool());
        default -> throw ConnectorException.badInput(backend.name() + " has no tool " + call.tool() + ".");
      };
    } catch (Refused r) {
      throw r.exception();
    }
  }

  private static JsonNode arguments(ToolCall call, ExecContext ctx) {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      return ctx.approvedPayload().get().get("arguments");
    }
    return call.arguments();
  }

  private ToolResult list(Call c, String like) throws ConnectorException {
    ArrayNode out = F.arrayNode();
    int hidden = 0;
    for (ObjectNode index : backend.indexes(c)) {
      String name = index.path("name").asString("");
      if (!backend.allows(c.cfg(), name)) {
        hidden++;
        continue;
      }
      if (like != null && !Glob.text(like).matches(name)) {
        continue;
      }
      out.add(index);
    }
    ObjectNode result = F.objectNode();
    result.set("indexes", out);
    if (hidden > 0) {
      result.put("notListed", hidden);
    }
    VectorConnector.fit(c.ctx(), out, result);
    return ToolResult.json(result, StoreBackend.count(out.size(), "index", "indexes") + " of " + c.cfg().name()
        + ".");
  }

  private ToolResult search(Call c, SearchBackend.Query q) throws ConnectorException {
    SearchBackend.Hits hits = backend.search(c, q);
    ObjectNode out = F.objectNode().put("index", q.index());
    if (hits.total() != null) {
      out.put("total", hits.total());
      if (!hits.exact()) {
        out.put("totalIsEstimate", true);
      }
    }
    ArrayNode list = out.putArray("hits");
    hits.hits().forEach(list::add);
    if (hits.facets() != null && !hits.facets().isEmpty()) {
      out.set("facets", hits.facets());
    }
    long next = (long) q.offset() + hits.hits().size();
    if (hits.hits().size() == q.limit() && (hits.total() == null || next < hits.total()) && next <= 10_000) {
      out.put("nextOffset", next);
    }
    VectorConnector.fit(c.ctx(), list, out);
    return ToolResult.json(out, list.size() + (list.size() == 1 ? " hit" : " hits") + " in " + q.index()
        + (hits.total() == null ? "" : " of " + (hits.exact() ? "" : "about ") + hits.total()) + ".");
  }

  private ToolResult change(Call c, String tool) throws ConnectorException {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: " + tool + " changes data, so a person approves it first.");
    }
    Change change = Change.of(ctx.approvedPayload().orElse(null));
    boolean delete = SearchTools.DELETE.equals(tool);
    if (delete != "delete".equals(change.op()) || (!delete && !Set.of("upsert", "merge").contains(change.op()))) {
      throw ConnectorException.denied("The approved action is another change than " + tool + ".");
    }
    if (backend.nameProblem(change.target()) != null || !backend.allows(c.cfg(), change.target())) {
      throw ConnectorException.denied(change.target() + " isn't one of the indexes of " + c.cfg().name() + ".");
    }
    if (change.count() == 0 || change.count() > (delete ? MAX_DELETE : MAX_DOCUMENTS)) {
      throw ConnectorException.denied("The approved change has no documents or too many.");
    }
    String problem = idProblems(change);
    if (problem != null) {
      throw ConnectorException.denied(problem);
    }
    ServiceHttp.Request request = backend.request(c.cfg(), change);
    ServiceHttp.Response response = backend.send(c, backend.changeEndpoint(c.cfg(), change), request,
        ServiceHttp.MAX_ERROR * 16);
    if (!response.ok()) {
      throw backend.failure(c.cfg(), request, response);
    }
    ObjectNode out = backend.changed(c, change, response);
    out.put("index", change.target());
    String verb = delete ? "Deleted " : "merge".equals(change.op()) ? "Merged " : "Indexed ";
    boolean applied = out.path("applied").asBoolean(true);
    return ToolResult.json(out, verb + StoreBackend.count(change.count(), "document") + (delete ? " from " : " into ")
        + change.target() + (applied ? "." : "; the engine hadn't applied it yet when GateRook stopped waiting."));
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

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode().put("engine", backend.name());
    JsonNode own = backend.describeConnection(cfg);
    if (own != null && own.isObject()) {
      own.properties().forEach(e -> out.set(e.getKey(), e.getValue()));
    }
    List<String> listed = cfg.strings("/indexes");
    if (listed.isEmpty()) {
      out.put("indexes", "Every index the credentials see");
    } else {
      ArrayNode a = out.putArray("indexes");
      listed.forEach(a::add);
    }
    out.put("filters", "MongoDB-style filters ($eq $ne $gt $gte $lt $lte $in $nin $exists $and $or) that GateRook "
        + "writes in " + backend.name() + "'s own syntax");
    out.put("changes", "search_index_documents and search_delete_documents (by id) after approval");
    if (object != null && !object.isBlank()) {
      out.put("index", "Call search_describe_index with index " + object + ".");
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
