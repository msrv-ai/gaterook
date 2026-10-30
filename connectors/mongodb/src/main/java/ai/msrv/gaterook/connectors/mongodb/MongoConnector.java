package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.MongoException;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.PreparedAction;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.mongodb.Ejson.BadArgument;
import ai.msrv.gaterook.connectors.mongodb.PipelineValidator.Verdict;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.net.InetSocketAddress;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.RawBsonDocument;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MongoDB reads and updates over the synchronous driver.
 *
 * <p>No tool takes an arbitrary command. Find and aggregate remain read-only; updates classify as MUTATION and
 * default to Approval. {@link PipelineValidator} checks namespaces, update syntax and server-side JavaScript
 * before the network, in {@code classify}, {@code prepare} and {@code execute}, on the documents the driver sends.
 * The user's roles are the server-side boundary; Test connection shows them
 * ({@code connectionStatus {showPrivileges: true}}).
 *
 * <p>Calls: one cached client per connection version and login ({@link MongoClientCache}); documents read as
 * {@link RawBsonDocument} within the call's limits ({@code n + 1} documents, {@code maxTimeMS}, the response budget,
 * long values cut), relaxed Extended JSON out with {@code $numberLong} outside ±2^53, {@code comment} = the call id
 * (DBAs see it in {@code currentOp} and the profiler). {@code mongodb+srv://} resolves SRV and TXT with the gateway's
 * own DNS client ({@link SrvDns}).
 */
public final class MongoConnector implements Connector {

  public static final String TYPE = "mongodb";

  private static final List<String> TOOLS = List.of(MongoTools.FIND, MongoTools.AGGREGATE,
      MongoTools.UPDATE, MongoTools.UPDATE_MANY);

  private final ConnectorManifest manifest;
  private final SrvDns dns;
  private final MongoProbe probe;
  private final MongoClientCache clients;

  /** The connector with the system's DNS servers. */
  public MongoConnector() {
    this(Nameservers::system);
  }

  /** The connector asking one DNS server for SRV and TXT records (tests; the core's test override). */
  public MongoConnector(InetSocketAddress dnsServer) {
    this(() -> Nameservers.fixed(dnsServer));
  }

  MongoConnector(Supplier<Nameservers> nameservers) {
    SchemaResources schemas = SchemaResources.of(MongoConnector.class, "mongodb");
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("MongoDB")
        .icon("leaf")
        .group("Databases")
        .description("Find, aggregate and update documents. Updates require approval by default.")
        .configSchema(schemas.load("config"))
        .tools(MongoTools.definitions(schemas))
        .build();
    this.dns = new SrvDns(nameservers);
    this.probe = new MongoProbe(dns);
    this.clients = new MongoClientCache((s, login, readTimeout) -> MongoClients.create(ClientSettings.build(s,
        login, dns, Duration.ofMillis(s.serverSelectionTimeoutMs()), readTimeout)), Instant::now);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  /** The tools with the strictest limits of the endpoint's MongoDB connections in their descriptions. */
  @Override
  public List<ToolDefinition> tools(EndpointView endpoint) {
    if (endpoint == null || endpoint.connections().isEmpty()) {
      return manifest.tools();
    }
    int docs = MongoArgs.MAX_LIMIT;
    Duration time = null;
    for (ConnectionConfig c : endpoint.connections()) {
      Limits l = endpoint.limitsByConnection().get(c.id());
      if (l == null) {
        continue;
      }
      docs = Math.min(docs, Math.max(1, l.maxRows()));
      Duration t = timeLimit(MongoSettings.of(c), l);
      time = time == null || t.compareTo(time) < 0 ? t : time;
    }
    return time == null ? manifest.tools() : MongoTools.withLimits(manifest.tools(), docs, time);
  }

  // ── classify ────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    if (!TOOLS.contains(call.tool())) {
      return Classification.deny(policy.rule("unknown-tool"), "MongoDB has no tool " + call.tool() + ".", null);
    }
    MongoSettings s = MongoSettings.of(cfg);
    if (!s.usable()) {
      return Classification.deny(policy.rule("bad-config"), cfg.name() + " can't be used: " + s.problemText()
          + " Ask the user to fix the connection in GateRook.", null);
    }
    MongoArgs args;
    try {
      args = MongoArgs.parse(call.tool(), call.arguments(), s.defaultDatabase());
    } catch (BadArgument e) {
      return Classification.deny(policy.rule("bad-argument"), e.getMessage(), "Filters, updates, projections, sorts and "
          + "stages are Extended JSON: {\"_id\": {\"$oid\": \"…\"}}, {\"at\": {\"$gte\": {\"$date\": "
          + "\"2026-10-22T00:00:00Z\"}}}.");
    }
    Optional<Verdict> v = new PipelineValidator(new Namespaces(s.namespaceAllowlist(), s.databases())).check(call.tool(), args);
    if (v.isPresent()) {
      return Classification.deny(policy.rule(v.get().rule()), v.get().reason(), v.get().hint());
    }
    return args instanceof MongoArgs.Update u
        ? Classification.mutation("Update " + (u.many() ? "all matching documents" : "one matching document")
            + " in " + u.database() + "." + u.collection() + ".")
        : Classification.read();
  }

  // ── approval ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The command before the call's limits are known: {@link #prepare} adds them.
   */
  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    MongoSettings s = MongoSettings.of(cfg);
    MongoArgs args = MongoArgs.parse(call.tool(), call.arguments(), s.defaultDatabase());
    return preview(cfg, s, args, args.limit(), s.maxTimeMs() == null ? 0 : s.maxTimeMs());
  }

  /** The reviewed command with this call's limits; no I/O. */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    MongoSettings s = MongoSettings.of(cfg);
    if (!s.usable()) {
      return PreparedAction.reject(cfg.name() + " can't be used: " + s.problemText());
    }
    MongoArgs args;
    try {
      args = MongoArgs.parse(call.tool(), call.arguments(), s.defaultDatabase());
    } catch (BadArgument e) {
      return PreparedAction.reject(e.getMessage());
    }
    Optional<Verdict> verdict = new PipelineValidator(new Namespaces(s.namespaceAllowlist(), s.databases()))
        .check(call.tool(), args);
    if (verdict.isPresent()) {
      return PreparedAction.reject(verdict.get().reason());
    }
    int n = Math.min(args.limit(), Math.max(1, ctx.limits().maxRows()));
    int maxTime = (int) timeLimit(s, ctx.limits()).toMillis();
    return PreparedAction.approval(preview(cfg, s, args, n, maxTime), null);
  }

  private static ActionPreview preview(ConnectionConfig cfg, MongoSettings s, MongoArgs args, int n,
      int maxTimeMs) {
    if (args instanceof MongoArgs.Update u) {
      return updatePreview(cfg, s, u, maxTimeMs);
    }
    BsonDocument command = wireCommand(args, n, maxTimeMs);
    String verb = args instanceof MongoArgs.Find ? "find" : "aggregate";
    String ns = args.database() + "." + args.collection();
    ObjectNode payload = args.canonical();
    payload.put("limit", n);
    payload.put("maxTimeMS", maxTimeMs);
    NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.US);
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", Ejson.relaxed(command),
            cfg.name() + " · " + s.target(args.database()), verb + " " + ns + " · " + cfg.name() + " (" + cfg.env().id() + ")")
        .withFields(List.of(new ActionPreview.Field("Database", args.database()),
            new ActionPreview.Field("Collection", args.collection()),
            new ActionPreview.Field("Read preference", s.readPreference()),
            new ActionPreview.Field("Documents returned", "at most " + numbers.format(n)),
            new ActionPreview.Field("Time limit", maxTimeMs > 0 ? MongoTools.seconds(Duration.ofMillis(maxTimeMs))
                + " (maxTimeMS)" : "the environment's")))
        .withFacets(Map.of("mongo.namespace", ns, "mongo.command", verb))
        .withPayload(payload);
  }

  private static ActionPreview updatePreview(ConnectionConfig cfg, MongoSettings s, MongoArgs.Update args,
      int maxTimeMs) {
    String verb = args.many() ? "updateMany" : "update";
    String ns = args.database() + "." + args.collection();
    ObjectNode payload = args.canonical();
    payload.put("maxTimeMS", maxTimeMs);
    String target = (s.parsed() == null ? "?" : String.join(",", s.parsed().hosts()) + "/" + args.database())
        + " · primary";
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", Ejson.relaxed(wireCommand(args, 1, maxTimeMs)),
            cfg.name() + " · " + target, verb + " " + ns + " · " + cfg.name() + " (" + cfg.env().id() + ")")
        .withFields(List.of(new ActionPreview.Field("Database", args.database()),
            new ActionPreview.Field("Collection", args.collection()),
            new ActionPreview.Field("Documents affected", args.many() ? "all matching documents" : "at most one"),
            new ActionPreview.Field("Upsert", args.upsert() ? "insert if no match" : "disabled"),
            new ActionPreview.Field("Time limit", maxTimeMs > 0
                ? MongoTools.seconds(Duration.ofMillis(maxTimeMs)) : "the environment's")))
        .withFacets(Map.of("mongo.namespace", ns, "mongo.command", verb))
        .withPayload(payload);
  }

  /**
   * The reviewed command: reads fetch {@code n + 1} documents without disk spill; updates carry their exact filter,
   * changes, cardinality and upsert options. The execution deadline can reduce the displayed {@code maxTimeMS}.
   */
  static BsonDocument wireCommand(MongoArgs args, int n, int maxTimeMs) {
    return switch (args) {
      case MongoArgs.Update u -> {
        BsonDocument update = new BsonDocument("q", u.filter()).append("u", u.update())
            .append("multi", BsonBoolean.valueOf(u.many())).append("upsert", BsonBoolean.valueOf(u.upsert()));
        if (u.arrayFilters() != null) {
          update.append("arrayFilters", u.arrayFilters());
        }
        BsonDocument c = new BsonDocument("update", new BsonString(u.collection()))
            .append("updates", new BsonArray(List.of(update))).append("ordered", BsonBoolean.TRUE)
            .append("writeConcern", new BsonDocument("w", new BsonInt32(1)));
        if (maxTimeMs > 0) {
          c.append("maxTimeMS", new BsonInt32(maxTimeMs));
        }
        yield c;
      }
      case MongoArgs.Find f -> {
        BsonDocument c = new BsonDocument("find", new BsonString(f.collection())).append("filter", f.filter());
        if (f.projection() != null) {
          c.append("projection", f.projection());
        }
        if (f.sort() != null) {
          c.append("sort", f.sort());
        }
        c.append("skip", new BsonInt32(f.skip())).append("limit", new BsonInt32(n + 1));
        if (maxTimeMs > 0) {
          c.append("maxTimeMS", new BsonInt32(maxTimeMs));
        }
        c.append("allowDiskUse", BsonBoolean.FALSE);
        yield c;
      }
      case MongoArgs.Aggregate a -> {
        BsonArray stages = new BsonArray(new ArrayList<>(a.pipeline().getValues()));
        stages.add(new BsonDocument("$limit", new BsonInt32(n + 1)));
        BsonDocument c = new BsonDocument("aggregate", new BsonString(a.collection())).append("pipeline", stages);
        if (maxTimeMs > 0) {
          c.append("maxTimeMS", new BsonInt32(maxTimeMs));
        }
        c.append("allowDiskUse", BsonBoolean.FALSE);
        yield c;
      }
    };
  }

  // ── execute ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    if (!TOOLS.contains(call.tool())) {
      throw ConnectorException.badInput("MongoDB has no tool " + call.tool() + ".");
    }
    MongoSettings s = MongoSettings.of(cfg);
    if (!s.usable()) {
      throw ConnectorException.badInput(cfg.name() + " can't be used: " + s.problemText());
    }
    // An approved call runs what the person saw (its canonical arguments), never the agent's arguments again.
    Optional<JsonNode> approved = ctx.approved() ? ctx.approvedPayload() : Optional.empty();
    JsonNode raw = approved.isPresent() ? approved.get() : call.arguments();
    MongoArgs args;
    try {
      args = MongoArgs.parse(call.tool(), raw, s.defaultDatabase());
    } catch (BadArgument e) {
      throw ConnectorException.badInput(e.getMessage());
    }
    // Defence in depth: the same validation as classify, on the documents about to be sent.
    Optional<Verdict> v = new PipelineValidator(new Namespaces(s.namespaceAllowlist(), s.databases())).check(call.tool(), args);
    if (v.isPresent()) {
      throw ConnectorException.denied(v.get().reason());
    }
    if (args instanceof MongoArgs.Update && (!ctx.approved() || approved.isEmpty())) {
      throw ConnectorException.denied("MongoDB updates require a prepared payload authorized by approval or Bypass.");
    }
    Limits limits = ctx.limits();
    int n = Math.min(args.limit(), Math.max(1, limits.maxRows()));
    long maxTime = timeLimit(s, limits).toMillis();
    if (approved.isPresent()) {
      n = Math.min(n, Math.max(1, approved.get().path("limit").asInt(n)));
      int approvedTime = approved.get().path("maxTimeMS").asInt(0);
      if (approvedTime > 0) {
        maxTime = Math.min(maxTime, approvedTime);
      }
    }
    maxTime = Math.min(maxTime, ctx.remaining().toMillis() - 100);
    if (maxTime <= 0) {
      throw ConnectorException.timeout("No time was left for the query on " + cfg.name() + ".");
    }
    try (MongoLogin login = MongoLogin.resolve(s, secrets);
         MongoClientCache.Lease lease = clients.acquire(cfg, s, login, readTimeout(limits))) {
      maxTime = Math.min(maxTime, ctx.remaining().toMillis() - 100);
      if (maxTime <= 0) {
        throw ConnectorException.timeout("No time was left for the query on " + cfg.name() + ".");
      }
      MongoClient client = lease.client();
      try {
        return run(client, call.tool(), cfg, s, args, n, (int) maxTime, limits, ctx);
      } catch (MongoException e) {
        throw MongoErrors.call(e, cfg.name(), s, client);
      }
    } catch (MongoException | IllegalArgumentException e) {
      // The client couldn't be built: the connection string (its TXT lookup) or the TLS settings.
      throw MongoErrors.call(e instanceof MongoException me ? me : new MongoException(e.getMessage(), e),
          cfg.name(), s, null);
    }
  }

  private static ToolResult run(MongoClient client, String tool, ConnectionConfig cfg, MongoSettings s,
      MongoArgs args, int n, int maxTimeMs, Limits limits, ExecContext ctx) {
    MongoCollection<RawBsonDocument> col = client.getDatabase(args.database())
        .getCollection(args.collection(), RawBsonDocument.class);
    String comment = "gaterook " + ctx.callId();
    int batch = Math.min(n + 1, 101);
    long start = System.nanoTime();
    if (args instanceof MongoArgs.Update u) {
      return update(col, tool, cfg, u, maxTimeMs, comment, start);
    }
    MongoCursor<RawBsonDocument> cursor = switch (args) {
      case MongoArgs.Update ignored -> throw new IllegalStateException("Updates do not return a cursor.");
      case MongoArgs.Find f -> {
        var find = col.find(f.filter()).skip(f.skip()).limit(n + 1).batchSize(batch)
            .maxTime(maxTimeMs, TimeUnit.MILLISECONDS).allowDiskUse(false).comment(comment);
        if (f.projection() != null) {
          find.projection(f.projection());
        }
        if (f.sort() != null) {
          find.sort(f.sort());
        }
        yield find.cursor();
      }
      case MongoArgs.Aggregate a -> {
        List<BsonDocument> stages = new ArrayList<>();
        a.pipeline().forEach(st -> stages.add(st.asDocument()));
        stages.add(new BsonDocument("$limit", new BsonInt32(n + 1)));
        yield col.aggregate(stages).batchSize(batch).maxTime(maxTimeMs, TimeUnit.MILLISECONDS)
            .allowDiskUse(false).comment(comment).cursor();
      }
    };
    ctx.onCancel(cursor::close);
    long budget = Math.max(4_096, limits.maxResponseBytes());
    DocReader.Page page = DocReader.read(cursor, n, budget, Math.max(256, limits.maxCellChars()), start);
    List<String> warnings = new ArrayList<>();
    if (page.returned() == 0 && page.truncatedReason() == null && !exists(client, args)) {
      warnings.add("Collection " + args.database() + "." + args.collection() + " doesn't exist. Call "
          + "describe_connection to list collections.");
    }
    if (page.returned() == 0 && "size_limit".equals(page.truncatedReason())) {
      warnings.add("The first document is larger than the size limit. Project the fields you need.");
    }
    return DocReader.result(tool, cfg.name(), args.database(), args.collection(), page, s.readPreference(),
        warnings, n);
  }

  private static ToolResult update(MongoCollection<RawBsonDocument> collection, String tool, ConnectionConfig cfg,
      MongoArgs.Update args, int timeoutMs, String comment, long start) {
    // Writes always target the primary, acknowledge the result and obey the full operation deadline. The client
    // disables retryWrites so a non-idempotent update is never automatically replayed after an uncertain result.
    var col = collection.withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.ACKNOWLEDGED)
        .withTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    UpdateOptions options = new UpdateOptions().upsert(args.upsert()).comment(comment);
    if (args.arrayFilters() != null) {
      options.arrayFilters(args.arrayFilters().getValues().stream().map(v -> v.asDocument()).toList());
    }
    UpdateResult result;
    if (args.update() instanceof BsonArray pipeline) {
      List<BsonDocument> stages = pipeline.getValues().stream().map(v -> v.asDocument()).toList();
      result = args.many() ? col.updateMany(args.filter(), stages, options)
          : col.updateOne(args.filter(), stages, options);
    } else {
      result = args.many() ? col.updateMany(args.filter(), args.update().asDocument(), options)
          : col.updateOne(args.filter(), args.update().asDocument(), options);
    }
    ObjectNode out = Ejson.JSON.createObjectNode();
    out.put("tool", tool);
    out.put("connection", cfg.name());
    out.put("database", args.database());
    out.put("collection", args.collection());
    out.put("acknowledged", result.wasAcknowledged());
    out.put("matchedCount", result.getMatchedCount());
    out.put("modifiedCount", result.getModifiedCount());
    out.set("upsertedId", Ejson.tree(new BsonDocument("id",
        result.getUpsertedId() == null ? BsonNull.VALUE : result.getUpsertedId())).get("id"));
    out.put("elapsedMs", Duration.ofNanos(System.nanoTime() - start).toMillis());
    return ToolResult.json(out, "Matched " + result.getMatchedCount() + " and modified " + result.getModifiedCount()
        + " documents in " + args.database() + "." + args.collection() + " on " + cfg.name()
        + (result.getUpsertedId() == null ? "." : "; inserted one document (upsert)."));
  }

  /** MongoDB answers no documents for a collection that isn't there: one {@code listCollections} tells them apart. */
  private static boolean exists(MongoClient client, MongoArgs args) {
    try {
      BsonDocument r = client.getDatabase(args.database()).runCommand(new BsonDocument("listCollections",
          new BsonInt32(1)).append("filter", new BsonDocument("name", new BsonString(args.collection())))
          .append("nameOnly", BsonBoolean.TRUE).append("authorizedCollections", BsonBoolean.TRUE),
          BsonDocument.class);
      return !r.getDocument("cursor", new BsonDocument()).getArray("firstBatch", new BsonArray()).isEmpty();
    } catch (MongoException e) {
      return true;
    }
  }

  /** {@code maxTimeMS}: the environment's statement timeout, or the connection's own if stricter. */
  static Duration timeLimit(MongoSettings s, Limits limits) {
    long ms = Math.max(1, limits.statementTimeout().toMillis());
    if (s.maxTimeMs() != null && s.maxTimeMs() > 0) {
      ms = Math.min(ms, s.maxTimeMs());
    }
    return Duration.ofMillis(ms);
  }

  /** The socket waits longer than any {@code maxTimeMS} of the connection, so the server's limit answers first. */
  static Duration readTimeout(Limits limits) {
    return Duration.ofMillis(Math.max(5_000, limits.statementTimeout().toMillis() + 2_000));
  }

  // ── test, health, describe ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return probe.test(cfg, secrets, ctx);
  }

  @Override
  public TestResult discoverDatabases(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return probe.test(cfg, secrets, ctx);
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return probe.health(cfg, secrets, ctx, clients);
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object)
      throws ConnectorException {
    MongoSettings s = MongoSettings.of(cfg);
    if (!s.usable()) {
      throw ConnectorException.badInput(cfg.name() + " can't be used: " + s.problemText());
    }
    try (MongoLogin login = MongoLogin.resolve(s, secrets);
         MongoClientCache.Lease lease = clients.acquire(cfg, s, login, readTimeout(ctx.limits()))) {
      try {
        return Optional.of(MongoDescribe.describe(lease.client(), s, new Namespaces(s.namespaceAllowlist(), s.databases()), ctx,
            object));
      } catch (MongoDescribe.ObjectRefused e) {
        throw ConnectorException.denied(e.getMessage());
      } catch (MongoException e) {
        throw MongoErrors.call(e, cfg.name(), s, lease.client());
      }
    } catch (MongoException | IllegalArgumentException e) {
      throw MongoErrors.call(e instanceof MongoException me ? me : new MongoException(e.getMessage(), e),
          cfg.name(), s, null);
    }
  }

  // ── lifecycle ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public void onConnectionChanged(String connectionId) {
    clients.drop(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    clients.drop(connectionId);
  }

  @Override
  public void close() {
    clients.close();
  }

  /** How many clients are open now (tests of the cache's lifecycle). */
  int openClients() {
    return clients.size();
  }

  /** The DNS servers asked for {@code mongodb+srv://} now. */
  Nameservers nameservers() {
    return dns.current();
  }
}
