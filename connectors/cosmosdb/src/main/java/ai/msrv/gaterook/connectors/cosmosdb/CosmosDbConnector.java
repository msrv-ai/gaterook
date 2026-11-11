package ai.msrv.gaterook.connectors.cosmosdb;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ActionPreview.Field;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Azure Cosmos DB for NoSQL (docs/cosmosdb.md): {@code cosmosdb_list_databases}, {@code cosmosdb_list_containers},
 * {@code cosmosdb_describe_container}, {@code cosmosdb_query} and {@code cosmosdb_read_item} read;
 * {@code cosmosdb_execute} runs one create, upsert, replace, patch or delete of one item that a person approved.
 *
 * <p>The route of a call comes from {@link #classify} without I/O: IDs read strictly, the database and container on
 * the connection's lists, partition key values that Cosmos DB stores exactly, a query that is one {@code SELECT}.
 * {@link #execute} gets no policy and checks the same again; a change runs only with the core's approval, runs the
 * approved request ({@link CosmosChange#approved}) once, and is never retried. With an account key, a read-only key is
 * Azure's own boundary for changes; with Microsoft Entra ID, the service principal's data-plane roles are.
 */
public final class CosmosDbConnector implements Connector {

  static final String TYPE = "cosmosdb";
  static final int DEFAULT_LIMIT = 25;
  static final int DEFAULT_LIST = 100;
  /** Databases Test connection lists. */
  static final int PROBE = 100;
  /** Databases of the connection's list that a listing refused at the account looks up one by one. */
  static final int MAX_LOOKUPS = 10;
  /**
   * SHA-256 of the emulator's well-known key as Microsoft publishes it (its Base64 text): Test connection tells it from
   * a key of a real account without the source holding something shaped like a key.
   */
  static final String EMULATOR_KEY_SHA256 = "aa6547439583c3a275d4542a0df6c1ad6a863971be9f2a2220f027cc02aef615";
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final boolean plainLoopback;
  private final CosmosHttp http;
  private final ConnectorManifest manifest;

  /** A release build's connector: HTTPS only, to Azure's Cosmos DB hosts and the emulator. */
  public CosmosDbConnector() {
    this(false);
  }

  /** @param plainLoopback a development build: plain HTTP to the emulator on this computer is allowed too */
  public CosmosDbConnector(boolean plainLoopback) {
    this(plainLoopback, null, null);
  }

  /**
   * Tests: {@code testBase} replaces the endpoint's address and {@code testLogin} Microsoft Entra ID's. Never a
   * setting: a person can't send a key or a token anywhere else.
   */
  CosmosDbConnector(boolean plainLoopback, URI testBase, URI testLogin) {
    this.plainLoopback = plainLoopback;
    this.http = new CosmosHttp(testBase, testLogin);
    SchemaResources schemas = SchemaResources.of(CosmosDbConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Azure Cosmos DB")
        .icon("database")
        .group("Databases")
        .description("Databases, containers, SQL queries and items of Azure Cosmos DB for NoSQL. Changes wait for "
            + "approval.")
        .configSchema(schemas.load("config"))
        .tools(CosmosTools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("*.documents.azure.com", "*.documents.azure.us",
            "*.documents.azure.cn", "login.microsoftonline.com", "login.microsoftonline.us",
            "login.chinacloudapi.cn"), List.of(), List.of()))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  private CosmosSettings settings(ConnectionConfig cfg) throws ConnectorException {
    return CosmosSettings.of(cfg, plainLoopback);
  }

  /** {@code cosmosdb_execute} isn't offered for a read-only key: Azure refuses every change made with one. */
  @Override
  public boolean supportsTool(String tool, ConnectionConfig cfg) {
    if (!CosmosTools.EXECUTE.equals(tool)) {
      return true;
    }
    try {
      return !settings(cfg).readOnlyKey();
    } catch (ConnectorException e) {
      return true;
    }
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    CosmosSettings s;
    try {
      s = settings(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    JsonNode args = call.arguments();
    try {
      return switch (call.tool()) {
        case CosmosTools.LIST_DATABASES -> Classification.read();
        case CosmosTools.LIST_CONTAINERS -> {
          CosmosTarget.database(args, s);
          yield Classification.read();
        }
        case CosmosTools.DESCRIBE_CONTAINER -> {
          CosmosTarget.of(args, s);
          yield Classification.read();
        }
        case CosmosTools.QUERY -> {
          parseQuery(args, s);
          yield Classification.read();
        }
        case CosmosTools.READ_ITEM -> {
          parseItem(args, s);
          yield Classification.read();
        }
        case CosmosTools.EXECUTE -> {
          if (s.readOnlyKey()) {
            throw new Refusal(Refusal.READ_ONLY_KEY, "This connection signs in with a read-only key, which can't "
                + "write: Azure refuses every change made with one.", "A person can switch the connection to a "
                + "read-write key or to Microsoft Entra ID with Cosmos DB Built-in Data Contributor.");
          }
          CosmosChange c = CosmosChange.of(args, s, policy.limits() == null ? 0 : policy.limits().maxRequestBytes());
          ObjectNode facts = F.objectNode().put("operation", c.operation()).put("database", c.database())
              .put("container", c.container());
          yield Classification.mutation(c.operation() + " of an item in " + c.database() + "/" + c.container()
              + " changes data", facts);
        }
        default -> Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool()
            + ".", null);
      };
    } catch (Refusal r) {
      return Classification.deny(policy.rule(r.rule), r.getMessage(), r.hint);
    }
  }

  /** A query's parts, checked: the container, the text, the parameters and the partition key. */
  record Query(CosmosTarget target, String text, ArrayNode parameters, PartitionKey partitionKey) {}

  static Query parseQuery(JsonNode args, CosmosSettings s) throws Refusal {
    CosmosTarget t = CosmosTarget.of(args, s);
    String text = args.path("query").isString() ? args.get("query").asString() : null;
    String problem = CosmosSql.problem(text);
    if (problem != null) {
      throw Refusal.bad(problem, null);
    }
    ArrayNode parameters = CosmosSql.parameters(args.get("parameters"));
    PartitionKey pk = args.has("partitionKey") ? PartitionKey.of(args.get("partitionKey")) : null;
    if (pk == null && !s.crossPartition()) {
      throw new Refusal(Refusal.CROSS_PARTITION, "This connection doesn't run queries across partitions.",
          "Give partitionKey: the partition key value to read.");
    }
    return new Query(t, text, parameters, pk);
  }

  /** A point read's parts, checked. */
  record Item(CosmosTarget target, String id, PartitionKey partitionKey) {}

  static Item parseItem(JsonNode args, CosmosSettings s) throws Refusal {
    CosmosTarget t = CosmosTarget.of(args, s);
    String id = args.path("id").isString() ? args.get("id").asString() : null;
    String problem = CosmosIds.itemProblem(id);
    if (problem != null) {
      throw Refusal.bad("The item id " + CosmosIds.shown(id) + " " + problem + ".", null);
    }
    return new Item(t, id, PartitionKey.of(args.get("partitionKey")));
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    if (CosmosTools.EXECUTE.equals(call.tool())) {
      try {
        CosmosSettings s = settings(cfg);
        return changePreview(cfg, s, CosmosChange.of(call.arguments(), s, 0));
      } catch (ConnectorException e) {
        return notRunnable(cfg, e.getMessage());
      } catch (Refusal r) {
        return notRunnable(cfg, r.getMessage());
      }
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    ObjectNode payload = F.objectNode().put("tool", call.tool());
    payload.set("arguments", args);
    String endpoint = Optional.ofNullable(cfg.string("/endpoint")).orElse("");
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), cfg.name() + " · " + endpoint,
            shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(payload)
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", CosmosTools.QUERY.equals(call.tool()) && !args.has("partitionKey")
            ? "Items of every partition, up to the limit; request units for each page" : "Items, no change")));
  }

  private static ActionPreview notRunnable(ConnectionConfig cfg, String why) {
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", "Not runnable: " + why, cfg.name(),
        shorten(CosmosTools.EXECUTE + " on " + cfg.name() + ": not runnable", 120)).withPayload(F.objectNode());
  }

  private static ActionPreview changePreview(ConnectionConfig cfg, CosmosSettings s, CosmosChange c) {
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("Operation", switch (c.operation()) {
      case "create" -> "Create the item; refused if an item with its id exists in the partition";
      case "upsert" -> "Create the item, or replace the whole item with its id in the partition";
      case "replace" -> "Replace the whole item; refused if it doesn't exist";
      case "patch" -> "Change the listed properties of the item; refused if it doesn't exist";
      default -> "Delete the item; refused if it doesn't exist";
    }));
    fields.add(new Field("Container", c.database() + "/" + c.container()));
    fields.add(new Field("Item id", c.id()));
    fields.add(new Field("Partition key", c.partitionKey().shown()));
    fields.add(new Field("If-Match", c.ifMatch() == null ? "None: applies to whatever version the item has"
        : c.ifMatch()));
    List<String> warnings = new ArrayList<>();
    if (c.inexactNumbers()) {
      warnings.add("Cosmos DB stores numbers as 64-bit floats: an integer beyond 2^53 in this body won't be stored "
          + "exactly.");
    }
    if (c.ifMatch() == null && (c.operation().equals("replace") || c.operation().equals("upsert"))) {
      warnings.add("No ifMatch: this replaces the whole item, whatever changed since it was read.");
    }
    if (s.auth() == CosmosSettings.Auth.KEY) {
      warnings.add("An account key isn't limited to this item: Azure lets it change anything in the account.");
    }
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", c.text(s.endpoint()),
            cfg.name() + " · " + s.label() + " · " + c.database() + "/" + c.container() + " · " + c.id(),
            shorten(CosmosTools.EXECUTE + " on " + cfg.name() + ": " + c.operation() + " " + c.id() + " in "
                + c.database() + "/" + c.container(), 120))
        .withFields(fields)
        .withImpact(List.of(new Field("Items", "One: " + c.id() + " in partition " + c.partitionKey().shown()),
            new Field("Retried", "No: a request whose answer is lost is reported as unknown")))
        .withWarnings(warnings)
        .withFacets(Map.of("operation", c.operation(), "database", c.database(), "container", c.container()))
        .withPayload(c.payload());
  }

  static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  /**
   * Before the approval exists: the container is read once, so that a change whose partition key doesn't match the
   * item's own value, or a patch of the partition key, is refused now rather than after a person approved it. A
   * principal that may not read the container's metadata skips the check; Cosmos DB refuses a mismatch anyway.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    if (!CosmosTools.EXECUTE.equals(call.tool())) {
      return PreparedAction.approval(describeAction(call, cfg), null);
    }
    CosmosSettings s = settings(cfg);
    CosmosChange c;
    try {
      c = CosmosChange.of(call.arguments(), s, ctx.limits().maxRequestBytes());
    } catch (Refusal r) {
      return PreparedAction.reject(r.hint == null ? r.getMessage() : r.getMessage() + " " + r.hint);
    }
    CosmosTarget t = new CosmosTarget(c.database(), c.container());
    CosmosHttp.Answer a = http.send(new CosmosHttp.Call(cfg, s, secrets, ctx), CosmosHttp.Request.get(t.path(),
        "colls", t.link(), "the container " + t.shown()), 403, 404);
    if (a.status() == 404) {
      return PreparedAction.reject("The container " + t.shown() + " doesn't exist. Call cosmosdb_list_containers to "
          + "see the containers.");
    }
    ObjectNode state = F.objectNode();
    if (a.status() / 100 == 2) {
      List<String> paths = new ArrayList<>();
      a.body().path("partitionKey").path("paths").forEach(p -> paths.add(p.asString("")));
      ArrayNode shown = state.putArray("partitionKeyPaths");
      paths.forEach(shown::add);
      String problem = partitionKeyProblem(c, paths);
      if (problem != null) {
        return PreparedAction.reject(problem);
      }
    }
    return PreparedAction.approval(describeAction(call, cfg), state);
  }

  /** Why the change's partition key doesn't fit the container's {@code paths}, or {@code null}. */
  static String partitionKeyProblem(CosmosChange c, List<String> paths) {
    if (paths.isEmpty()) {
      return null;
    }
    if (c.partitionKey().values().size() != paths.size()) {
      return "The container's partition key has " + paths.size() + (paths.size() == 1 ? " path (" : " paths (")
          + String.join(", ", paths) + "), so partitionKey holds " + (paths.size() == 1 ? "one value" : paths.size()
          + " values") + ".";
    }
    if (c.body() != null && !c.operation().equals("patch")) {
      try {
        PartitionKey own = PartitionKey.in(CosmosHttp.JSON.readTree(c.body()), paths);
        if (own != null && !own.sameAs(c.partitionKey())) {
          return "partitionKey " + c.partitionKey().shown() + " isn't the item's own value at "
              + String.join(", ", paths) + " (" + own.shown() + ").";
        }
        if (own == null && paths.stream().allMatch(p -> PartitionKey.segments(p) != null)) {
          return "The item has no value at the container's partition key path " + String.join(", ", paths)
              + ": add it to the item.";
        }
      } catch (JacksonException e) {
        return null;
      }
    }
    if (c.operation().equals("patch")) {
      try {
        for (JsonNode op : CosmosHttp.JSON.readTree(c.body()).path("operations")) {
          for (String field : List.of("path", "from")) {
            String path = op.path(field).asString(null);
            if (path == null) {
              continue;
            }
            for (String pk : paths) {
              if (path.equals(pk) || path.startsWith(pk + "/") || pk.startsWith(path + "/")) {
                return "A patch can't change the partition key (" + pk + "): delete the item and create it again "
                    + "in its new partition.";
              }
            }
          }
        }
      } catch (JacksonException e) {
        return null;
      }
    }
    return null;
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    CosmosSettings s = settings(cfg);
    CosmosHttp.Call c = new CosmosHttp.Call(cfg, s, secrets, ctx);
    try {
      return switch (call.tool()) {
        case CosmosTools.LIST_DATABASES -> listDatabases(c, arguments(call, ctx));
        case CosmosTools.LIST_CONTAINERS -> listContainers(c, arguments(call, ctx));
        case CosmosTools.DESCRIBE_CONTAINER -> describeContainer(c, arguments(call, ctx));
        case CosmosTools.QUERY -> query(c, arguments(call, ctx));
        case CosmosTools.READ_ITEM -> readItem(c, arguments(call, ctx));
        case CosmosTools.EXECUTE -> change(c);
        default -> throw ConnectorException.badInput(manifest.name() + " has no tool " + call.tool() + ".");
      };
    } catch (Refusal r) {
      throw r.toException();
    }
  }

  /** The arguments that run: the approved request's, or the call's. */
  private static JsonNode arguments(ToolCall call, ExecContext ctx) throws ConnectorException {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      JsonNode payload = ctx.approvedPayload().get();
      if (!call.tool().equals(payload.path("tool").asString(""))) {
        throw ConnectorException.badInput("The approved request is for another tool. Not executed.");
      }
      return payload.get("arguments");
    }
    return call.arguments();
  }

  private static int limit(JsonNode args, int dflt, int max) {
    JsonNode l = args.path("limit");
    int n = l.isIntegralNumber() && l.canConvertToInt() ? l.asInt() : dflt;
    return Math.max(1, Math.min(n, Math.max(1, max)));
  }

  /** Bytes of the answer's items, counted twice (structured content and text); room for the rest of it. */
  static long budget(ExecContext ctx) {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 10_240);
  }

  private static boolean timeLeft(ExecContext ctx) {
    return ctx.remaining().compareTo(Duration.ofSeconds(1)) >= 0;
  }

  private ToolResult listDatabases(CosmosHttp.Call c, JsonNode args) throws ConnectorException {
    ExecContext ctx = c.ctx();
    CosmosSettings s = c.settings();
    int limit = limit(args, DEFAULT_LIST, 1_000);
    String hash = Cursors.hash(F.objectNode().put("feed", "dbs"));
    String token = args.path("cursor").isString()
        ? Cursors.open(ctx, CosmosTools.LIST_DATABASES, args.get("cursor").asString(), c.cfg().id(), hash) : null;
    List<String> ids = new ArrayList<>();
    String next = null;
    boolean cut = false;
    double charge = 0;
    for (int page = 0; page < s.maxPages(); page++) {
      CosmosHttp.Request r = CosmosHttp.Request.get("/dbs", "dbs", "", "the databases")
          .with("x-ms-max-item-count", String.valueOf(limit - ids.size()));
      if (token != null) {
        r = r.with("x-ms-continuation", token);
      }
      CosmosHttp.Answer a = http.send(c, r, 403);
      if (a.status() == 403) {
        if (token == null && ids.isEmpty() && CosmosErrors.roleRefusal(a)) {
          return lookedUp(c, a);
        }
        throw CosmosErrors.of(a, r, c);
      }
      charge += a.charge();
      for (JsonNode db : a.body().path("Databases")) {
        String id = db.path("id").asString("");
        if (CosmosIds.nameProblem(id) == null && s.showsDatabase(id)) {
          if (ids.size() == limit) {
            cut = true;
            break;
          }
          ids.add(id);
        }
      }
      next = a.continuation();
      if (cut || next == null || ids.size() >= limit || !timeLeft(ctx)) {
        break;
      }
      token = next;
    }
    ObjectNode out = F.objectNode();
    ArrayNode list = out.putArray("databases");
    ids.forEach(list::add);
    String sealed = cut || next == null ? null : Cursors.seal(ctx, CosmosTools.LIST_DATABASES, c.cfg().id(), hash, next);
    if (sealed != null) {
      out.put("nextCursor", sealed);
    }
    out.put("requestCharge", round(charge));
    String text = ids.isEmpty() ? "No databases agents may use in " + s.label() + "."
        : ids.size() + (ids.size() == 1 ? " database: " : " databases: ") + String.join(", ", shownAll(ids))
            + (sealed == null ? "" : "\nMore databases: pass nextCursor.")
            + (cut ? "\nMore databases than the limit: ask for a larger limit." : "");
    ToolResult result = ToolResult.json(out, text);
    return cut ? result.truncated("row_limit") : result;
  }

  /**
   * A principal whose roles are scoped to databases may not list the account's: the connection's own databases, those
   * named without {@code *}, are looked up one by one instead.
   */
  private ToolResult lookedUp(CosmosHttp.Call c, CosmosHttp.Answer refused) throws ConnectorException {
    CosmosSettings s = c.settings();
    List<String> named = s.databases().stream().filter(d -> d.indexOf('*') < 0).limit(MAX_LOOKUPS).toList();
    if (named.isEmpty()) {
      throw CosmosErrors.of(refused, CosmosHttp.Request.get("/dbs", "dbs", "", "the databases"), c);
    }
    List<String> found = new ArrayList<>();
    for (String db : named) {
      if (!timeLeft(c.ctx())) {
        break;
      }
      CosmosHttp.Answer a = http.send(c, CosmosHttp.Request.get("/dbs/" + CosmosIds.encode(db), "dbs", "dbs/" + db,
          "the database " + CosmosIds.shown(db)), 403, 404);
      if (a.status() / 100 == 2 && s.showsDatabase(db)) {
        found.add(db);
      }
    }
    ObjectNode out = F.objectNode();
    ArrayNode list = out.putArray("databases");
    found.forEach(list::add);
    out.put("note", "Cosmos DB refused to list the account's databases (403); these are the connection's databases "
        + "the credentials can read.");
    return ToolResult.json(out, (found.isEmpty() ? "No databases of the connection's list can be read" : found.size()
        + (found.size() == 1 ? " database: " : " databases: ") + String.join(", ", shownAll(found)))
        + "\nCosmos DB refused to list the account's databases (403), so these are the connection's own, looked up "
        + "one by one.");
  }

  private ToolResult listContainers(CosmosHttp.Call c, JsonNode args) throws ConnectorException, Refusal {
    ExecContext ctx = c.ctx();
    CosmosSettings s = c.settings();
    String db = CosmosTarget.database(args, s);
    int limit = limit(args, DEFAULT_LIST, 1_000);
    String hash = Cursors.hash(F.objectNode().put("feed", "colls").put("database", db));
    String token = args.path("cursor").isString()
        ? Cursors.open(ctx, CosmosTools.LIST_CONTAINERS, args.get("cursor").asString(), c.cfg().id(), hash) : null;
    ArrayNode containers = F.arrayNode();
    List<String> lines = new ArrayList<>();
    String next = null;
    boolean cut = false;
    double charge = 0;
    for (int page = 0; page < s.maxPages(); page++) {
      CosmosHttp.Request r = CosmosHttp.Request.get("/dbs/" + CosmosIds.encode(db) + "/colls", "colls", "dbs/" + db,
          "the containers of " + CosmosIds.shown(db)).with("x-ms-max-item-count", String.valueOf(limit
          - containers.size()));
      if (token != null) {
        r = r.with("x-ms-continuation", token);
      }
      CosmosHttp.Answer a = http.send(c, r);
      charge += a.charge();
      for (JsonNode coll : a.body().path("DocumentCollections")) {
        String id = coll.path("id").asString("");
        if (CosmosIds.nameProblem(id) != null || !s.allowsContainer(db, id)) {
          continue;
        }
        if (containers.size() == limit) {
          cut = true;
          break;
        }
        ObjectNode o = containers.addObject().put("id", id);
        ArrayNode paths = o.putArray("partitionKeyPaths");
        List<String> p = new ArrayList<>();
        for (JsonNode path : coll.path("partitionKey").path("paths")) {
          paths.add(path.asString(""));
          p.add(OutputSanitizer.sanitize(path.asString("")));
        }
        String kind = coll.path("partitionKey").path("kind").asString(null);
        if (kind != null) {
          o.put("partitionKeyKind", kind);
        }
        lines.add(CosmosIds.shown(id) + (p.isEmpty() ? "" : " (" + String.join(", ", p) + ")"));
      }
      next = a.continuation();
      if (cut || next == null || containers.size() >= limit || !timeLeft(ctx)) {
        break;
      }
      token = next;
    }
    ObjectNode out = F.objectNode().put("database", db);
    out.set("containers", containers);
    String sealed = cut || next == null ? null : Cursors.seal(ctx, CosmosTools.LIST_CONTAINERS, c.cfg().id(), hash,
        next);
    if (sealed != null) {
      out.put("nextCursor", sealed);
    }
    out.put("requestCharge", round(charge));
    String text = containers.isEmpty() ? "No containers agents may use in " + CosmosIds.shown(db) + "."
        : containers.size() + (containers.size() == 1 ? " container in " : " containers in ") + CosmosIds.shown(db)
            + ": " + String.join(", ", lines) + (sealed == null ? "" : "\nMore containers: pass nextCursor.")
            + (cut ? "\nMore containers than the limit: ask for a larger limit." : "");
    ToolResult result = ToolResult.json(out, text);
    return cut ? result.truncated("row_limit") : result;
  }

  private ToolResult describeContainer(CosmosHttp.Call c, JsonNode args) throws ConnectorException, Refusal {
    CosmosTarget t = CosmosTarget.of(args, c.settings());
    CosmosHttp.Answer a = http.send(c, CosmosHttp.Request.get(t.path(), "colls", t.link(), "the container "
        + t.shown()));
    JsonNode coll = a.body();
    ObjectNode out = F.objectNode().put("database", t.database()).put("container", t.container());
    ObjectNode pk = out.putObject("partitionKey");
    ArrayNode pkPaths = pk.putArray("paths");
    coll.path("partitionKey").path("paths").forEach(p -> pkPaths.add(p.asString("")));
    copy(coll.path("partitionKey"), "kind", pk);
    copy(coll.path("partitionKey"), "version", pk);
    JsonNode ttl = coll.get("defaultTtl");
    out.put("defaultTtl", ttl == null || ttl.isNull() ? "off" : ttl.asLong() == -1 ? "on, no default"
        : ttl.asLong() + " s");
    if (coll.has("analyticalStorageTtl")) {
      out.set("analyticalStorageTtl", coll.get("analyticalStorageTtl"));
    }
    ArrayNode unique = out.putArray("uniqueKeys");
    for (JsonNode k : coll.path("uniqueKeyPolicy").path("uniqueKeys")) {
      ArrayNode paths = unique.addArray();
      k.path("paths").forEach(p -> paths.add(p.asString("")));
    }
    out.set("indexing", indexing(coll.path("indexingPolicy")));
    if (coll.path("conflictResolutionPolicy").has("mode")) {
      out.put("conflictResolution", coll.path("conflictResolutionPolicy").path("mode").asString());
    }
    if (coll.path("vectorEmbeddingPolicy").path("vectorEmbeddings").size() > 0) {
      out.put("vectorEmbeddings", coll.path("vectorEmbeddingPolicy").path("vectorEmbeddings").size());
    }
    if (coll.path("fullTextPolicy").path("fullTextPaths").size() > 0) {
      out.put("fullTextPaths", coll.path("fullTextPolicy").path("fullTextPaths").size());
    }
    double charge = a.charge();
    String rid = coll.path("_rid").asString(null);
    out.set("throughput", throughput(c, rid, t));
    out.put("requestCharge", round(charge));
    return ToolResult.json(out, describeText(out));
  }

  private static ObjectNode indexing(JsonNode p) {
    ObjectNode out = F.objectNode();
    copy(p, "indexingMode", out);
    copy(p, "automatic", out);
    out.set("includedPaths", paths(p.path("includedPaths"), 20));
    out.set("excludedPaths", paths(p.path("excludedPaths"), 20));
    ArrayNode composite = out.putArray("compositeIndexes");
    int n = 0;
    for (JsonNode index : p.path("compositeIndexes")) {
      if (n++ >= 10) {
        break;
      }
      List<String> parts = new ArrayList<>();
      index.forEach(part -> parts.add(part.path("path").asString("") + " " + part.path("order").asString("ascending")));
      composite.add(String.join(", ", parts));
    }
    if (p.path("compositeIndexes").size() > 10) {
      out.put("compositeIndexCount", p.path("compositeIndexes").size());
    }
    ArrayNode spatial = paths(p.path("spatialIndexes"), 20);
    if (!spatial.isEmpty()) {
      out.set("spatialIndexes", spatial);
    }
    ArrayNode vectors = F.arrayNode();
    for (JsonNode v : p.path("vectorIndexes")) {
      vectors.add(v.path("path").asString("") + " (" + v.path("type").asString("?") + ")");
    }
    if (!vectors.isEmpty()) {
      out.set("vectorIndexes", vectors);
    }
    ArrayNode fullText = paths(p.path("fullTextIndexes"), 20);
    if (!fullText.isEmpty()) {
      out.set("fullTextIndexes", fullText);
    }
    return out;
  }

  private static ArrayNode paths(JsonNode list, int max) {
    ArrayNode out = F.arrayNode();
    for (JsonNode e : list) {
      if (out.size() >= max) {
        out.add("… " + (list.size() - max) + " more");
        break;
      }
      out.add(e.path("path").asString(""));
    }
    return out;
  }

  private static void copy(JsonNode from, String field, ObjectNode to) {
    if (from.has(field) && !from.get(field).isNull()) {
      to.set(field, from.get(field));
    }
  }

  /**
   * The container's provisioned throughput, or its database's when they share it: Cosmos DB's offers, which an
   * account key may read. Microsoft Entra ID's data-plane roles don't cover them, and a serverless account has none:
   * then the answer says so.
   */
  private ObjectNode throughput(CosmosHttp.Call c, String containerRid, CosmosTarget t) {
    ObjectNode out = F.objectNode();
    if (containerRid == null || !timeLeft(c.ctx())) {
      return out.put("readable", false).put("note", "Not read: no time was left.");
    }
    try {
      JsonNode offer = offer(c, containerRid);
      if (offer == null && timeLeft(c.ctx())) {
        CosmosHttp.Answer db = http.send(c, CosmosHttp.Request.get("/dbs/" + CosmosIds.encode(t.database()), "dbs",
            "dbs/" + t.database(), "the database " + CosmosIds.shown(t.database())));
        String dbRid = db.body().path("_rid").asString(null);
        offer = dbRid == null ? null : offer(c, dbRid);
        if (offer != null) {
          out.put("sharedWithDatabase", true);
        }
      }
      if (offer == null) {
        return out.put("readable", true).put("note", "No provisioned throughput: a serverless account, or one "
            + "Cosmos DB doesn't report.");
      }
      JsonNode content = offer.path("content");
      out.put("readable", true);
      if (content.path("offerAutopilotSettings").path("maxThroughput").isNumber()) {
        out.put("autoscaleMaxRUs", content.path("offerAutopilotSettings").path("maxThroughput").asLong());
      } else if (content.path("offerThroughput").isNumber()) {
        out.put("manualRUs", content.path("offerThroughput").asLong());
      }
      return out;
    } catch (ConnectorException e) {
      return out.put("readable", false).put("note", c.settings().auth() == CosmosSettings.Auth.ENTRA
          ? "Not readable with Microsoft Entra ID: throughput is a control-plane setting." : "Not readable: "
          + e.getMessage());
    }
  }

  private JsonNode offer(CosmosHttp.Call c, String rid) throws ConnectorException {
    ObjectNode q = F.objectNode().put("query", "SELECT * FROM root WHERE root.offerResourceId = @rid");
    q.putArray("parameters").addObject().put("name", "@rid").put("value", rid);
    CosmosHttp.Request r = new CosmosHttp.Request("POST", "/offers", "offers", "", new LinkedHashMap<>(Map.of(
        "x-ms-documentdb-isquery", "True")), CosmosHttp.JSON.writeValueAsBytes(q), "application/query+json", false,
        "the throughput");
    JsonNode offers = http.send(c, r).body().path("Offers");
    return offers.isArray() && !offers.isEmpty() ? offers.get(0) : null;
  }

  /** {@code app/orders: partition key /customerId (Hash v2) · TTL off · unique keys /orderNo · 400 RU/s}. */
  static String describeText(ObjectNode d) {
    StringBuilder out = new StringBuilder(CosmosIds.shown(d.path("database").asString())).append('/')
        .append(CosmosIds.shown(d.path("container").asString())).append(": partition key ");
    List<String> paths = new ArrayList<>();
    d.path("partitionKey").path("paths").forEach(p -> paths.add(OutputSanitizer.sanitize(p.asString())));
    out.append(paths.isEmpty() ? "none" : String.join(", ", paths));
    if (d.path("partitionKey").has("kind")) {
      out.append(" (").append(d.path("partitionKey").path("kind").asString())
          .append(d.path("partitionKey").has("version") ? " v" + d.path("partitionKey").path("version").asInt() : "")
          .append(')');
    }
    out.append(" · TTL ").append(d.path("defaultTtl").asString());
    if (!d.path("uniqueKeys").isEmpty()) {
      List<String> keys = new ArrayList<>();
      d.path("uniqueKeys").forEach(k -> {
        List<String> p = new ArrayList<>();
        k.forEach(x -> p.add(OutputSanitizer.sanitize(x.asString())));
        keys.add(String.join(" + ", p));
      });
      out.append(" · unique keys ").append(String.join(", ", keys));
    }
    JsonNode ix = d.path("indexing");
    out.append(" · indexing ").append(ix.path("indexingMode").asString("?"));
    if (!ix.path("compositeIndexes").isEmpty()) {
      out.append(", ").append(ix.path("compositeIndexes").size()).append(" composite");
    }
    JsonNode tp = d.path("throughput");
    if (tp.has("manualRUs")) {
      out.append(" · ").append(tp.get("manualRUs").asLong()).append(" RU/s");
    } else if (tp.has("autoscaleMaxRUs")) {
      out.append(" · autoscale up to ").append(tp.get("autoscaleMaxRUs").asLong()).append(" RU/s");
    }
    if (tp.path("sharedWithDatabase").asBoolean(false)) {
      out.append(" (shared by the database)");
    }
    return out.toString();
  }

  private ToolResult query(CosmosHttp.Call c, JsonNode args) throws ConnectorException, Refusal {
    ExecContext ctx = c.ctx();
    CosmosSettings s = c.settings();
    Query q = parseQuery(args, s);
    int limit = limit(args, DEFAULT_LIMIT, ctx.limits().maxRows());
    CosmosSql.Shape shape = CosmosSql.shape(q.text());
    ObjectNode body = F.objectNode().put("query", q.text());
    body.set("parameters", q.parameters());
    ObjectNode request = F.objectNode().put("container", q.target().link());
    request.set("body", body);
    request.put("partitionKey", q.partitionKey() == null ? null : q.partitionKey().shown());
    request.put("consistency", s.readConsistency());
    String hash = Cursors.hash(request);
    String token = args.path("cursor").isString()
        ? Cursors.open(ctx, CosmosTools.QUERY, args.get("cursor").asString(), c.cfg().id(), hash) : null;
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-ms-documentdb-isquery", "True");
    if (q.partitionKey() != null) {
      headers.put("x-ms-documentdb-partitionkey", q.partitionKey().header());
    } else {
      headers.put("x-ms-documentdb-query-enablecrosspartition", "True");
    }
    if (s.readConsistency() != null) {
      headers.put("x-ms-consistency-level", s.readConsistency());
    }
    byte[] bytes = CosmosHttp.JSON.writeValueAsBytes(body);
    String what = "the query on " + q.target().shown();
    int cell = ctx.limits().maxCellChars();
    long budget = budget(ctx);
    ArrayNode items = F.arrayNode();
    List<String> lines = new ArrayList<>();
    long used = 0;
    double charge = 0;
    String pageStart = token;
    String next = null;
    String truncated = null;
    boolean resumable = true;
    boolean overLimit = false;
    int pages = shape.pageable() ? s.maxPages() : 1;
    for (int page = 0; page < pages; page++) {
      Map<String, String> h = new LinkedHashMap<>(headers);
      h.put("x-ms-max-item-count", String.valueOf(Math.min(1_000, limit - items.size())));
      if (pageStart != null) {
        h.put("x-ms-continuation", pageStart);
      }
      CosmosHttp.Answer a = http.send(c, new CosmosHttp.Request("POST", q.target().path() + "/docs", "docs",
          q.target().link(), h, bytes, "application/query+json", false, what));
      charge += a.charge();
      int before = items.size();
      int linesBefore = lines.size();
      long usedBefore = used;
      JsonNode documents = a.body().path("Documents");
      int consumed = 0;
      for (JsonNode d : documents) {
        if (items.size() >= limit) {
          overLimit = true;
          break;
        }
        JsonNode shown = CosmosValues.shown(d, cell);
        String line = shown.toString();
        long cost = 2L * line.getBytes(StandardCharsets.UTF_8).length + 2;
        if (used + cost > budget) {
          truncated = "size_limit";
          if (items.isEmpty()) {
            // The first item alone is too large: its properties that fit, so the answer still moves on.
            JsonNode cut = fit(shown, budget / 2);
            items.add(cut);
            lines.add(cut.toString());
            consumed++;
          }
          break;
        }
        used += cost;
        items.add(shown);
        lines.add(line);
        consumed++;
      }
      next = a.continuation();
      if (truncated != null || overLimit) {
        if (consumed == documents.size() && shape.pageable()) {
          // The page was read to its end, its last item cut: the cursor goes on after it.
          break;
        }
        if (page > 0 && shape.pageable()) {
          // Back to the start of this page: the next cursor reads it again, whole.
          while (items.size() > before) {
            items.remove(items.size() - 1);
          }
          while (lines.size() > linesBefore) {
            lines.removeLast();
          }
          used = usedBefore;
          next = pageStart;
        } else {
          resumable = false;
          if (truncated == null) {
            truncated = "row_limit";
          }
        }
        break;
      }
      if (!shape.pageable() || next == null || items.size() >= limit || !timeLeft(ctx)) {
        break;
      }
      pageStart = next;
    }
    String note = null;
    String cursor = null;
    if (next != null && !shape.pageable()) {
      if (shape.aggregates()) {
        throw ConnectorException.badInput("Cosmos DB answered this query in more than one page, and "
            + String.join(", ", shape.features()) + " " + (shape.features().size() == 1 ? "needs" : "need")
            + " the pages combined, which only an SDK's query pipeline does: a page alone isn't the answer. Narrow the "
            + "query (one partition key value, a tighter filter) so that it fits one page.");
      }
      note = "More items may follow, but Cosmos DB's gateway can't continue " + String.join(", ", shape.features())
          + " page by page: ask for a larger limit (up to " + ctx.limits().maxRows() + "), or narrow the query.";
      truncated = truncated == null ? "partial" : truncated;
    } else if (next != null && resumable) {
      cursor = Cursors.seal(ctx, CosmosTools.QUERY, c.cfg().id(), hash, next);
      if (cursor == null) {
        note = "Cosmos DB's continuation is too long for a cursor: narrow the query.";
        truncated = truncated == null ? "partial" : truncated;
      } else if (truncated != null) {
        note = "Cut at the response size: pass nextCursor for the rest, or ask for a smaller limit.";
      }
    } else if (!resumable) {
      note = truncated.equals("size_limit") ? "Cut at the response size: ask for a smaller limit, or project fewer "
          + "properties (SELECT c.id, c.status FROM c)." : "More items than the limit: ask for a larger limit.";
    }
    if (note == null && truncated != null) {
      note = "Cut at the response size: an item too large shows the properties that fit. Project fewer properties, "
          + "such as SELECT c.id, c.status FROM c.";
    }
    ObjectNode out = F.objectNode().put("database", q.target().database()).put("container", q.target().container());
    out.set("items", items);
    out.put("count", items.size());
    out.put("crossPartition", q.partitionKey() == null);
    out.put("requestCharge", round(charge));
    if (cursor != null) {
      out.put("nextCursor", cursor);
    }
    if (note != null) {
      out.put("note", note);
    }
    StringBuilder text = new StringBuilder();
    text.append(items.size()).append(items.size() == 1 ? " item" : " items").append(" from ")
        .append(q.target().shown())
        .append(q.partitionKey() == null ? " (all partitions)" : " (partition " + q.partitionKey().shown() + ")")
        .append(String.format(Locale.US, " · %.2f RU", round(charge)));
    for (String line : lines) {
      text.append('\n').append(line);
    }
    if (cursor != null) {
      text.append("\nMore items: pass nextCursor.");
    }
    if (note != null) {
      text.append('\n').append(note);
    }
    ToolResult result = ToolResult.json(out, text.toString());
    return truncated == null ? result : result.truncated(truncated);
  }

  /**
   * {@code item} within {@code budget} bytes: an object's properties in order while they fit, then how many were left
   * out ({@code ‹omittedProperties›}); any other value too large becomes a marker.
   */
  static JsonNode fit(JsonNode item, long budget) {
    if (item.toString().getBytes(StandardCharsets.UTF_8).length <= budget) {
      return item;
    }
    if (!item.isObject()) {
      return F.stringNode("‹a value of " + item.toString().getBytes(StandardCharsets.UTF_8).length + " bytes, "
          + "larger than the response size›");
    }
    ObjectNode out = F.objectNode();
    long used = 64;
    int left = 0;
    for (Map.Entry<String, JsonNode> e : item.properties()) {
      long cost = e.getKey().getBytes(StandardCharsets.UTF_8).length + e.getValue().toString()
          .getBytes(StandardCharsets.UTF_8).length + 4L;
      if (left > 0 || used + cost > budget) {
        left++;
        continue;
      }
      used += cost;
      out.set(e.getKey(), e.getValue());
    }
    out.put("‹omittedProperties›", left);
    return out;
  }

  private ToolResult readItem(CosmosHttp.Call c, JsonNode args) throws ConnectorException, Refusal {
    ExecContext ctx = c.ctx();
    Item item = parseItem(args, c.settings());
    CosmosTarget t = item.target();
    CosmosHttp.Request r = CosmosHttp.Request.get(t.path() + "/docs/" + CosmosIds.encode(item.id()), "docs",
        t.link() + "/docs/" + item.id(), "the item " + CosmosIds.shown(item.id()))
        .with("x-ms-documentdb-partitionkey", item.partitionKey().header());
    if (c.settings().readConsistency() != null) {
      r = r.with("x-ms-consistency-level", c.settings().readConsistency());
    }
    CosmosHttp.Answer a = http.send(c, r, 404);
    ObjectNode out = F.objectNode().put("database", t.database()).put("container", t.container())
        .put("id", item.id());
    out.set("partitionKey", item.partitionKey().values().deepCopy());
    if (a.status() == 404) {
      String said = CosmosErrors.message(a.body()).toLowerCase(Locale.ROOT);
      if (a.substatus() == 1003 || said.contains("collection") || said.contains("database")
          || said.contains("owner resource")) {
        throw CosmosErrors.of(a, r, c);
      }
      out.put("found", false);
      out.put("requestCharge", round(a.charge()));
      return ToolResult.json(out, "No item " + CosmosIds.shown(item.id()) + " with partition key "
          + item.partitionKey().shown() + " in " + t.shown() + ".");
    }
    JsonNode shown = CosmosValues.shown(a.body(), ctx.limits().maxCellChars());
    JsonNode fitted = fit(shown, budget(ctx) / 2);
    out.put("found", true);
    String etag = a.header("etag");
    if (etag == null) {
      etag = a.body().path("_etag").asString(null);
    }
    if (etag != null) {
      out.put("etag", etag);
    }
    out.put("requestCharge", round(a.charge()));
    out.set("item", fitted);
    String text = CosmosIds.shown(item.id()) + " in " + t.shown() + " (partition " + item.partitionKey().shown() + ")"
        + String.format(Locale.US, " · %.2f RU", round(a.charge())) + "\n" + fitted;
    ToolResult result = ToolResult.json(out, fitted == shown ? text : text + "\nCut at the response size: "
        + fitted.path("‹omittedProperties›").asInt() + " properties left out.");
    return fitted == shown ? result : result.truncated("size_limit");
  }

  private ToolResult change(CosmosHttp.Call c) throws ConnectorException {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: cosmosdb_execute changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no change."));
    CosmosChange change;
    try {
      change = CosmosChange.approved(payload, c.settings(), ctx.limits().maxRequestBytes());
    } catch (Refusal r) {
      throw ConnectorException.denied(r.getMessage());
    }
    if (c.settings().readOnlyKey()) {
      throw ConnectorException.denied("This key can't write: the connection signs in with a read-only key, and Azure "
          + "refuses every change made with one. Nothing changed.");
    }
    CosmosHttp.Answer a = http.send(c, new CosmosHttp.Request(change.verb(), change.path(), "docs",
        change.resourceLink(), change.headers(), change.bytes(), change.contentType(), true,
        "the " + change.operation() + " of " + CosmosIds.shown(change.id())));
    String where = change.database() + "/" + change.container();
    ObjectNode out = F.objectNode().put("committed", true).put("operation", change.operation())
        .put("database", change.database()).put("container", change.container()).put("id", change.id());
    out.set("partitionKey", change.partitionKey().values().deepCopy());
    out.put("status", a.status());
    String etag = a.header("etag");
    if (etag != null) {
      out.put("etag", etag);
    }
    out.put("requestCharge", round(a.charge()));
    if (!change.operation().equals("delete") && a.body().isObject() && !a.body().isEmpty()) {
      out.set("item", fit(CosmosValues.shown(a.body(), ctx.limits().maxCellChars()), budget(ctx) / 2));
    }
    String verb = switch (change.operation()) {
      case "create" -> "Created";
      case "upsert" -> a.status() == 201 ? "Created (upsert)" : "Replaced (upsert)";
      case "replace" -> "Replaced";
      case "patch" -> "Patched";
      default -> "Deleted";
    };
    return ToolResult.json(out, verb + " " + CosmosIds.shown(change.id()) + " in " + CosmosIds.shown(where)
        + " (partition " + change.partitionKey().shown() + ")" + String.format(Locale.US, " · %.2f RU",
        round(a.charge())) + ".");
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    CosmosSettings s;
    try {
      s = settings(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("cosmosdb.settings", e.getMessage(), null);
    }
    CosmosHttp.Call c = new CosmosHttp.Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    String principal = null;
    if (s.auth() == CosmosSettings.Auth.ENTRA) {
      long started = System.nanoTime();
      try {
        String token = http.auth().token(cfg, s, secrets, ctx);
        principal = principal(token, s);
        checks.add(new TestResult.Check("sign-in", true, ms(started), "Microsoft Entra ID (" + http.auth().loginHost(s)
            + "): " + principal));
      } catch (ConnectorException e) {
        checks.add(new TestResult.Check("sign-in", false, ms(started), e.getMessage()));
        return TestResult.failure("cosmosdb.sign-in", e.getMessage(), null).withChecks(checks);
      }
    }
    long started = System.nanoTime();
    CosmosHttp.Answer account;
    try {
      account = http.send(c, CosmosHttp.Request.get("/", "", "", "the account"));
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("account", false, ms(started), e.getMessage()));
      String code = e.kind() == ConnectorException.Kind.TIMEOUT ? "cosmosdb.unreachable"
          : e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE && e.getMessage().startsWith("Can't connect")
          ? "cosmosdb.unreachable" : "cosmosdb.refused";
      return TestResult.failure(code, e.getMessage(), null).withChecks(checks);
    }
    long latency = ms(started);
    JsonNode a = account.body();
    String id = OutputSanitizer.sanitize(a.path("id").asString(s.endpoint().account()));
    String consistency = a.path("userConsistencyPolicy").path("defaultConsistencyLevel").asString(null);
    List<String> writes = regions(a.path("writableLocations"));
    List<String> reads = regions(a.path("readableLocations"));
    List<String> where = new ArrayList<>();
    if (!writes.isEmpty()) {
      where.add("writes in " + String.join(", ", writes));
    }
    if (!reads.isEmpty()) {
      where.add("reads in " + String.join(", ", reads));
    }
    checks.add(new TestResult.Check("account", true, latency, id + (where.isEmpty() ? "" : " · "
        + String.join(", ", where)) + (consistency == null ? "" : " · default consistency "
        + OutputSanitizer.sanitize(consistency))));
    long listed = System.nanoTime();
    int visible = 0;
    List<String> first = new ArrayList<>();
    boolean listRefused = false;
    try {
      CosmosHttp.Request r = CosmosHttp.Request.get("/dbs", "dbs", "", "the databases")
          .with("x-ms-max-item-count", String.valueOf(PROBE));
      CosmosHttp.Answer dbs = http.send(c, r, 403);
      if (dbs.status() == 403 && !CosmosErrors.roleRefusal(dbs)) {
        throw CosmosErrors.of(dbs, r, c);
      }
      if (dbs.status() == 403) {
        listRefused = true;
        checks.add(new TestResult.Check("databases", false, ms(listed), CosmosErrors.message(dbs.body())));
      } else {
        for (JsonNode db : dbs.body().path("Databases")) {
          String name = db.path("id").asString("");
          if (CosmosIds.nameProblem(name) == null && s.showsDatabase(name)) {
            visible++;
            if (first.size() < 5) {
              first.add(CosmosIds.shown(name));
            }
          }
        }
        if (visible > 5 || dbs.continuation() != null) {
          first.add("…");
        }
        checks.add(new TestResult.Check("databases", true, ms(listed), (visible == 0 ? "No database"
            : visible + (dbs.continuation() != null ? "+" : "") + (visible == 1 ? " database" : " databases"))
            + " agents may use" + (first.isEmpty() ? "" : ": " + String.join(", ", first))));
      }
    } catch (ConnectorException e) {
      checks.add(new TestResult.Check("databases", false, ms(listed), e.getMessage()));
      return TestResult.failure("cosmosdb.list", e.getMessage(), null).withChecks(checks);
    }
    TestResult.Permissions permissions = permissions(s);
    String gateway = account.header("x-ms-gatewayversion");
    String version = "Azure Cosmos DB for NoSQL · " + id + (gateway == null ? ""
        : " · gateway " + OutputSanitizer.sanitize(gateway.replace("version=", "")));
    ObjectNode metadata = F.objectNode().put("serverVersion", version).put("account", id);
    if (consistency != null) {
      metadata.put("defaultConsistency", consistency);
    }
    ArrayNode w = metadata.putArray("writeRegions");
    writes.forEach(w::add);
    ArrayNode rr = metadata.putArray("readRegions");
    reads.forEach(rr::add);
    TestResult result = TestResult.success(latency, version, identity(s, id, principal), permissions)
        .withChecks(checks)
        .withMetadata(metadata);
    for (Hint h : hints(cfg.env(), s, consistency, visible, listRefused, principal, keyIsEmulators(s, secrets))) {
      result = result.withHint(h);
    }
    return result;
  }

  private static List<String> regions(JsonNode locations) {
    List<String> out = new ArrayList<>();
    for (JsonNode l : locations) {
      String name = l.path("name").asString("");
      if (!name.isBlank() && out.size() < 10) {
        out.add(OutputSanitizer.sanitize(name));
      }
    }
    return out;
  }

  private static String identity(CosmosSettings s, String account, String principal) {
    if (s.auth() == CosmosSettings.Auth.ENTRA) {
      return principal == null ? "Service principal " + s.clientId() : principal;
    }
    return (s.keyKind() == CosmosSettings.KeyKind.READ_ONLY ? "Read-only key" : "Read-write key") + " of " + account;
  }

  /** The service principal a token names: its application and object IDs, read from the token's claims. */
  static String principal(String token, CosmosSettings s) {
    String app = s.clientId();
    String oid = null;
    String[] parts = token.split("\\.");
    if (parts.length == 3) {
      try {
        JsonNode claims = CosmosHttp.JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        app = claims.path("appid").asString(claims.path("azp").asString(app));
        oid = claims.path("oid").asString(null);
      } catch (IllegalArgumentException | JacksonException e) {
        // An opaque token names no principal.
      }
    }
    String id = OutputSanitizer.sanitize(app);
    return "Service principal " + id + (oid == null ? "" : " (object ID " + OutputSanitizer.sanitize(oid) + ")");
  }

  private static TestResult.Permissions permissions(CosmosSettings s) {
    if (s.auth() == CosmosSettings.Auth.ENTRA) {
      return new TestResult.Permissions(TestResult.Level.UNKNOWN, "What the principal's data-plane roles allow",
          "Only changes a person approved, where a role allows them", List.of(), null);
    }
    if (s.keyKind() == CosmosSettings.KeyKind.READ_ONLY) {
      return new TestResult.Permissions(TestResult.Level.READ, "Every database and container of the account",
          "Nothing: Azure refuses changes made with a read-only key", List.of(), null);
    }
    return new TestResult.Permissions(TestResult.Level.ADMIN, "Every database and container of the account",
        "Everything; GateRook runs only changes a person approved", List.of("Create and delete databases and "
        + "containers", "Change throughput, indexing and stored procedures"), null);
  }

  private static boolean keyIsEmulators(CosmosSettings s, SecretResolver secrets) {
    if (s.auth() != CosmosSettings.Auth.KEY || !s.endpoint().emulator()) {
      return true;
    }
    try (SecretResolver.Secret key = secrets.secret(CosmosSettings.ACCOUNT_KEY)) {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.unsafeString().strip()
          .getBytes(StandardCharsets.UTF_8));
      return MessageDigest.isEqual(digest, HexFormat.of().parseHex(EMULATOR_KEY_SHA256));
    } catch (ConnectorException e) {
      return true;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static List<Hint> hints(Env env, CosmosSettings s, String accountConsistency, int visible, boolean listRefused,
      String principal, boolean emulatorKey) {
    List<Hint> out = new ArrayList<>();
    if (s.auth() == CosmosSettings.Auth.KEY && !s.endpoint().emulator()) {
      if (s.keyKind() == CosmosSettings.KeyKind.READ_WRITE) {
        out.add(new Hint(env == Env.DEV ? Severity.INFO : Severity.WARNING, "cosmosdb.read-write-key", "/auth/keyKind",
            "A primary or secondary key can change or delete anything in the account, databases included; GateRook "
                + "runs only changes a person approved. Use the read-only key, or Microsoft Entra ID with Cosmos DB "
                + "Built-in Data Reader or Data Contributor scoped to the containers agents use."));
      } else {
        out.add(new Hint(Severity.INFO, "cosmosdb.read-only-key", "/auth/keyKind", "Azure refuses every change made "
            + "with a read-only key, so cosmosdb_execute isn't offered. GateRook can't tell a read-only key from a "
            + "read-write one without writing: if this is a primary or secondary key, say so above."));
      }
    }
    if (s.auth() == CosmosSettings.Auth.ENTRA) {
      out.add(new Hint(Severity.INFO, "cosmosdb.rbac", "/auth/clientId", "The principal's data-plane roles are the "
          + "boundary: assign Cosmos DB Built-in Data Reader to read, or Data Contributor for approved changes, scoped "
          + "to the databases or containers agents use (az cosmosdb sql role assignment create --scope "
          + "/dbs/<database>/colls/<container>" + (principal != null && principal.contains("object ID ")
          ? " --principal-id " + principal.substring(principal.indexOf("object ID ") + 10).replace(")", "") : "")
          + "). Azure roles such as Contributor or Reader don't give access to data."));
    }
    if (s.endpoint().emulator()) {
      out.add(new Hint(env == Env.PROD ? Severity.WARNING : Severity.INFO, "cosmosdb.emulator", "/endpoint",
          "This is the emulator on this computer: it checks no key or token, and its data isn't an account's."));
      if (!emulatorKey) {
        out.add(new Hint(Severity.WARNING, "cosmosdb.emulator-key", "/auth/accountKey", "This key isn't the "
            + "emulator's well-known key. A key of a real account belongs to an endpoint of Azure, never to an "
            + "address on this computer."));
      }
    }
    if (s.readConsistency() != null && accountConsistency != null
        && strength(s.readConsistency()) > strength(accountConsistency)) {
      out.add(new Hint(Severity.WARNING, "cosmosdb.consistency", "/readConsistency", "Reads ask for "
          + s.readConsistency() + ", stronger than the account's default " + OutputSanitizer.sanitize(
          accountConsistency) + ": Cosmos DB refuses them. Choose the account's default or a weaker level."));
    }
    if (listRefused) {
      out.add(new Hint(Severity.INFO, "cosmosdb.no-account-listing", "/databases", "The credentials may not list the "
          + "account's databases: cosmosdb_list_databases looks up the databases named in the connection's list "
          + "instead. Name them there, without *."));
    } else if (visible == 0) {
      out.add(new Hint(Severity.WARNING, "cosmosdb.no-databases", "/databases", s.databases().isEmpty()
          && s.containers().isEmpty() ? "The credentials see no database in the account."
          : "No database of the account matches the connection's lists."));
    }
    return out;
  }

  /** Strength of a consistency level, strongest highest. */
  static int strength(String level) {
    return switch (level.toLowerCase(Locale.ROOT)) {
      case "strong" -> 5;
      case "boundedstaleness", "bounded" -> 4;
      case "session" -> 3;
      case "consistentprefix" -> 2;
      case "eventual" -> 1;
      default -> 0;
    };
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      CosmosSettings s = settings(cfg);
      long started = System.nanoTime();
      http.send(new CosmosHttp.Call(cfg, s, secrets, ctx), CosmosHttp.Request.get("/", "", "", "the account"));
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode();
    out.put("endpoint", cfg.string("/endpoint"));
    String method = cfg.string("/auth/method");
    out.put("auth", "entra".equals(method) ? "Microsoft Entra ID service principal: its data-plane roles apply"
        : "readWrite".equals(cfg.string("/auth/keyKind")) ? "Account key (read-write)" : "Account key (read-only)");
    List<String> databases = cfg.strings("/databases");
    if (databases.isEmpty()) {
      out.put("databases", "Every database the credentials may read");
    } else {
      ArrayNode a = out.putArray("databases");
      databases.forEach(a::add);
    }
    List<String> containers = cfg.strings("/containers");
    if (containers.isEmpty()) {
      out.put("containers", "Every container of the allowed databases");
    } else {
      ArrayNode a = out.putArray("containers");
      containers.forEach(a::add);
    }
    out.put("reads", "cosmosdb_query runs one Cosmos DB SQL SELECT with @name parameters; with partitionKey it reads "
        + "one partition, without it every partition, where the gateway refuses TOP, ORDER BY, OFFSET LIMIT, "
        + "DISTINCT, GROUP BY and aggregates. cosmosdb_read_item reads one item by id and partition key");
    out.put("changes", "One create, upsert, replace, patch or delete of one item per cosmosdb_execute, after approval"
        + ("readOnly".equals(cfg.string("/auth/keyKind")) && !"entra".equals(method)
        ? "; not offered with a read-only key" : ""));
    if (object != null && !object.isBlank()) {
      out.put("container", "Call cosmosdb_describe_container with the container " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    http.auth().evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    http.auth().evict(connectionId);
  }

  @Override
  public void close() {
    http.close();
  }

  private static List<String> shownAll(List<String> ids) {
    return ids.stream().map(CosmosIds::shown).toList();
  }

  static double round(double v) {
    return Math.round(v * 100.0) / 100.0;
  }

  static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
