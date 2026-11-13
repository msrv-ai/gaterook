package com.gaterook.connectors.neo4j;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.ActionPreview.Field;
import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Connector;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.connector.api.HealthResult;
import com.gaterook.connector.api.PolicyView;
import com.gaterook.connector.api.PreparedAction;
import com.gaterook.connector.api.SecretResolver;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.TestResult;
import com.gaterook.connector.api.TestResult.Hint;
import com.gaterook.connector.api.TestResult.Severity;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.connector.api.ToolResult;
import com.gaterook.connectors.support.BuiltIn;
import com.gaterook.connectors.support.LocalPorts;
import com.gaterook.connectors.support.OutputSanitizer;
import com.gaterook.connectors.support.SchemaResources;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Neo4j (docs/neo4j.md) over Bolt with the official Java driver: the schema, one Cypher read per {@code neo4j_query}
 * and one approved Cypher write per {@code neo4j_execute}.
 *
 * <p>Reads are held back twice. {@link #classify} reads the text by Cypher's own tokens ({@link Cypher}) without I/O
 * and refuses what GateRook never runs; then each read runs in an explicit transaction with access mode READ, in which
 * Neo4j itself refuses writes, and only after Neo4j's {@code EXPLAIN} of it, in that transaction, calls it read only
 * ({@code r}). A change shows the server's plan in its approval, runs only the approved statement in one explicit WRITE
 * transaction, once, never through the driver's retrying transactions, and reports the server's counters.
 */
public final class Neo4jConnector implements Connector {

  static final String TYPE = "neo4j";
  static final int DEFAULT_LIMIT = 100;
  static final int MAX_LIMIT = 10_000;
  /** Plans are made in READ transactions: nothing an EXPLAIN holds can write there. */
  static final AccessMode PLAN_MODE = AccessMode.READ;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Set<String> WRITER_ROLES = Set.of("editor", "publisher", "architect", "admin");
  /** How long a local suggestion waits for a port of this computer to answer a TLS hello or a Bolt handshake. */
  private static final Duration LOCAL_PROBE = Duration.ofMillis(1500);

  private final Bolt.Source bolts;
  private final ConnectorManifest manifest;

  public Neo4jConnector() {
    this(new DriverBolt());
  }

  Neo4jConnector(Bolt.Source bolts) {
    this.bolts = Objects.requireNonNull(bolts, "bolts");
    SchemaResources schemas = SchemaResources.of(Neo4jConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Neo4j")
        .icon("waypoints")
        .group("Databases")
        .description("Cypher reads in read-only transactions, the graph's schema and its nodes and paths. Writes "
            + "wait for approval.")
        .configSchema(schemas.load("config"))
        .tools(Neo4jTools.definitions(schemas))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    try {
      Neo4jSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    switch (call.tool()) {
      case Neo4jTools.SCHEMA -> {
        return Classification.read();
      }
      case Neo4jTools.QUERY, Neo4jTools.EXECUTE -> {
        boolean read = Neo4jTools.QUERY.equals(call.tool());
        Cypher.Statement st = Cypher.read(call.string("cypher"));
        if (st.kind() == Cypher.Kind.UNREADABLE) {
          return Classification.deny(policy.rule("unparsed"), st.problem() + " GateRook doesn't run what it can't "
              + "read with certainty.", st.hint());
        }
        if (st.kind() == Cypher.Kind.FORBIDDEN) {
          return Classification.deny(policy.rule(st.rule()), st.problem(), st.hint());
        }
        if (read && st.kind() != Cypher.Kind.READ) {
          return Classification.deny(policy.rule("query-writes"), "neo4j_query only reads; this changes "
              + st.changes() + ".", "Use neo4j_execute: a person approves the exact statement. If a word here is a "
              + "name, write it in backticks.");
        }
        if (!read && st.kind() == Cypher.Kind.READ) {
          return Classification.deny(policy.rule("execute-read"), "This is a read.", "Use neo4j_query; it doesn't "
              + "need approval.");
        }
        String problem = Parameters.problem(call.arguments().get("parameters"));
        if (problem != null) {
          return Classification.deny(policy.rule("bad-argument"), problem, null);
        }
        if (read) {
          return Classification.read();
        }
        ObjectNode facts = F.objectNode().put("changes", st.changes());
        if (st.kind() == Cypher.Kind.SCHEMA) {
          facts.put("schema", true);
        }
        return Classification.mutation("Cypher " + st.changes() + " changes the graph", facts);
      }
      default -> {
        return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
            null);
      }
    }
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String database = Objects.requireNonNullElse(cfg.string("/database"), Neo4jSettings.DEFAULT_DATABASE);
    String where = cfg.name() + " · " + target(cfg.string("/uri")) + " · " + database;
    JsonNode parameters = call.arguments().get("parameters");
    List<Field> fields = new ArrayList<>();
    if (parameters != null && parameters.isObject() && !parameters.isEmpty()) {
      fields.add(new Field("Parameters", shorten(parameters.toString(), 2_000)));
    }
    if (Neo4jTools.EXECUTE.equals(call.tool())) {
      String cypher = Objects.requireNonNullElse(call.string("cypher"), "");
      Cypher.Statement st = Cypher.read(cypher);
      ObjectNode payload = F.objectNode().put("cypher", cypher);
      if (parameters != null && parameters.isObject()) {
        payload.set("parameters", parameters.deepCopy());
      }
      return ActionPreview.of(ActionPreview.Kind.COMMAND, "cypher", cypher, where,
              shorten(Neo4jTools.EXECUTE + " on " + cfg.name() + ": " + st.changes(), 120))
          .withFields(fields)
          .withImpact(List.of(new Field("Changes", st.refused() ? "Unknown: not analyzed" : st.changes()),
              new Field("Database", database),
              new Field("Transaction", "One explicit WRITE transaction, committed once"),
              new Field("Retried", "No: an answer lost after the commit was sent is reported as unknown")))
          .withFacets(Map.of("changes", st.changes(), "database", database))
          .withPayload(payload);
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    if (Neo4jTools.QUERY.equals(call.tool())) {
      return ActionPreview.of(ActionPreview.Kind.COMMAND, "cypher", Objects.requireNonNullElse(call.string("cypher"),
              ""), where, shorten(call.tool() + " on " + cfg.name(), 120))
          .withFields(fields)
          .withPayload(F.objectNode().set("arguments", args))
          .withFacets(Map.of("tool", call.tool(), "database", database))
          .withImpact(List.of(new Field("Reads", "Rows in a READ transaction, no change")));
    }
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), where,
            shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool(), "database", database))
        .withImpact(List.of(new Field("Reads", "The schema, no change")));
  }

  /** {@code graph.example.com:7687} of a URI, without its scheme; the text itself when it isn't one. */
  private static String target(String uri) {
    if (uri == null) {
      return "?";
    }
    int at = uri.indexOf("://");
    String rest = at < 0 ? uri : uri.substring(at + 3);
    return rest.endsWith("/") ? rest.substring(0, rest.length() - 1) : rest;
  }

  /**
   * Neo4j's plan before a person is asked: {@code EXPLAIN} in a READ transaction, which plans and runs nothing. A
   * statement Neo4j can't plan, a read sent as a change and a change sent as a read are never put to a person.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ActionPreview preview = describeAction(call, cfg);
    boolean change = Neo4jTools.EXECUTE.equals(call.tool());
    if (!change && !Neo4jTools.QUERY.equals(call.tool())) {
      return PreparedAction.approval(preview, null);
    }
    Neo4jSettings s = Neo4jSettings.of(cfg);
    String cypher = Objects.requireNonNullElse(call.string("cypher"), "");
    Cypher.Statement st = Cypher.read(cypher);
    if (st.refused()) {
      return PreparedAction.reject(st.problem());
    }
    String problem = Parameters.problem(call.arguments().get("parameters"));
    if (problem != null) {
      return PreparedAction.reject(problem);
    }
    Bolt.Summary plan;
    Duration timeout = timeout(ctx, s);
    try (Bolt.Lease lease = bolts.lease(cfg, s, Neo4jAuth.resolve(s, secrets, ctx), ctx);
        Bolt.Tx tx = lease.client().begin(PLAN_MODE, s.database(), timeout, metadata(ctx, "plan"), 1)) {
      plan = tx.run("EXPLAIN " + cypher, Parameters.of(call.arguments().get("parameters"))).consume();
    } catch (RuntimeException e) {
      ConnectorException c = Neo4jErrors.read(e, s, timeout);
      if (c.kind() == ConnectorException.Kind.BAD_INPUT || c.kind() == ConnectorException.Kind.DENIED
          || c.kind() == ConnectorException.Kind.RESOURCE_ERROR) {
        return PreparedAction.reject(c.getMessage());
      }
      throw c;
    }
    String type = plan.queryType();
    if (change && type == null) {
      return PreparedAction.reject("Neo4j's plan doesn't say what the statement does, so GateRook doesn't put it to "
          + "a person.");
    }
    if (change && "r".equals(type)) {
      return PreparedAction.reject("Neo4j plans this as read only. Use neo4j_query; it doesn't need approval.");
    }
    if (!change && !"r".equals(type)) {
      return PreparedAction.reject("Neo4j plans this as " + typeName(type) + ": neo4j_query only runs what Neo4j "
          + "calls read only.");
    }
    List<Field> impact = new ArrayList<>();
    impact.add(new Field("Query type", typeName(type) + " (" + type + ")"));
    if (plan.plan() != null) {
      impact.add(new Field("Plan", shorten(operators(plan.plan()), 2_000)));
      if (plan.plan().estimatedRows() != null) {
        impact.add(new Field("Estimated rows", String.format(Locale.ROOT, "%,.0f", plan.plan().estimatedRows())));
      }
    }
    impact.addAll(preview.impact());
    List<String> warnings = new ArrayList<>(preview.warnings());
    for (Bolt.Note note : plan.notifications()) {
      if (note.severity() == null || note.severity().equalsIgnoreCase("WARNING")) {
        warnings.add(shorten(OutputSanitizer.sanitize(note.description() == null ? note.code()
            : note.description()), 500));
      }
    }
    return PreparedAction.approval(preview.withImpact(impact).withWarnings(warnings), null);
  }

  /** What a query type means, in words. */
  static String typeName(String type) {
    return switch (type == null ? "" : type) {
      case "r" -> "read only";
      case "rw" -> "read and write";
      case "w" -> "write only";
      case "s" -> "a schema change";
      default -> "unknown";
    };
  }

  /** The operators of a plan from the top, each child below its parent: {@code ProduceResults ← Create ← Argument}. */
  static String operators(Bolt.Plan plan) {
    StringBuilder out = new StringBuilder();
    operators(plan, out, 0);
    return out.toString();
  }

  private static void operators(Bolt.Plan plan, StringBuilder out, int depth) {
    if (depth > 0) {
      out.append(" ← ");
    }
    out.append(plan.operator());
    if (plan.details() != null && !plan.details().isBlank()) {
      out.append(" (").append(shorten(OutputSanitizer.sanitize(plan.details()), 120)).append(')');
    }
    if (plan.children().size() == 1) {
      operators(plan.children().getFirst(), out, depth + 1);
    } else if (!plan.children().isEmpty()) {
      out.append(" ← [");
      for (int i = 0; i < plan.children().size(); i++) {
        if (i > 0) {
          out.append(" | ");
        }
        StringBuilder branch = new StringBuilder();
        operators(plan.children().get(i), branch, 0);
        out.append(branch);
      }
      out.append(']');
    }
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    Neo4jSettings s = Neo4jSettings.of(cfg);
    return switch (call.tool()) {
      case Neo4jTools.SCHEMA -> schema(cfg, s, secrets, ctx, arguments(call, ctx));
      case Neo4jTools.QUERY -> query(cfg, s, secrets, ctx, arguments(call, ctx));
      case Neo4jTools.EXECUTE -> change(cfg, s, secrets, ctx);
      default -> throw ConnectorException.badInput(manifest.name() + " has no tool " + call.tool() + ".");
    };
  }

  /** The arguments that run: the approved request's, or the call's. */
  private static JsonNode arguments(ToolCall call, ExecContext ctx) {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      return ctx.approvedPayload().get().get("arguments");
    }
    return call.arguments();
  }

  /**
   * The transaction timeout: the connection's, the policy's statement timeout and what is left of the call less a
   * second for the answer, whichever is shortest.
   */
  static Duration timeout(ExecContext ctx, Neo4jSettings s) throws ConnectorException {
    Duration left = ctx.remaining().minus(Duration.ofSeconds(1));
    if (left.compareTo(Duration.ofMillis(100)) < 0) {
      throw ConnectorException.timeout("The call has no time left for Neo4j.");
    }
    Duration t = left;
    Duration statement = ctx.limits() == null ? null : ctx.limits().statementTimeout();
    if (statement != null && !statement.isZero() && !statement.isNegative() && statement.compareTo(t) < 0) {
      t = statement;
    }
    if (s.transactionTimeout() != null && s.transactionTimeout().compareTo(t) < 0) {
      t = s.transactionTimeout();
    }
    return t;
  }

  /** What {@code SHOW TRANSACTIONS} and the query log show of a GateRook transaction. */
  static Map<String, Object> metadata(ExecContext ctx, String purpose) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("app", "GateRook");
    m.put("purpose", purpose);
    if (ctx.callId() != null) {
      m.put("call", ctx.callId());
    }
    return m;
  }

  private static int limit(JsonNode args, ExecContext ctx, Neo4jSettings s) {
    int limit = Math.max(1, Math.min(args.path("limit").asInt(DEFAULT_LIMIT), MAX_LIMIT));
    limit = Math.min(limit, Math.max(1, ctx.limits().maxRows()));
    return s.maxRows() == null ? limit : Math.min(limit, s.maxRows());
  }

  /** Bytes of the answer's rows; leaves room for the rest of it. */
  private static long budget(ExecContext ctx) {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
  }

  private ToolResult query(ConnectionConfig cfg, Neo4jSettings s, SecretResolver secrets, ExecContext ctx,
      JsonNode args) throws ConnectorException {
    String cypher = args.path("cypher").asString("");
    if (Cypher.read(cypher).kind() != Cypher.Kind.READ) {
      throw ConnectorException.denied("neo4j_query only runs one statement GateRook reads as a read.");
    }
    String problem = Parameters.problem(args.get("parameters"));
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    int limit = limit(args, ctx, s);
    Duration timeout = timeout(ctx, s);
    try (Bolt.Lease lease = bolts.lease(cfg, s, Neo4jAuth.resolve(s, secrets, ctx), ctx);
        Bolt.Tx tx = lease.client().begin(AccessMode.READ, s.database(), timeout, metadata(ctx, "read"),
            Math.min(limit + 1, 1_000))) {
      return read(tx, cypher, Parameters.of(args.get("parameters")), limit, ctx, s.database(), true);
    } catch (RuntimeException e) {
      throw Neo4jErrors.read(e, s, timeout);
    }
  }

  /**
   * One read in its READ transaction: Neo4j's {@code EXPLAIN} first, which must call it read only ({@code planCheck};
   * only an integration test turns it off, to show that the READ transaction refuses a write by itself), then up to
   * {@code limit} rows within the response budget. The transaction is never committed.
   */
  static ToolResult read(Bolt.Tx tx, String cypher, Map<String, Object> parameters, int limit, ExecContext ctx,
      String database, boolean planCheck) throws ConnectorException {
    if (planCheck) {
      Bolt.Summary plan = tx.run("EXPLAIN " + cypher, parameters).consume();
      if (!"r".equals(plan.queryType())) {
        throw ConnectorException.denied("Neo4j plans this as " + typeName(plan.queryType()) + ", not read only: "
            + "neo4j_query doesn't run it. Use neo4j_execute for a change.");
      }
    }
    Bolt.Run run = tx.run(cypher, parameters);
    List<String> keys = run.keys();
    String[] types = new String[keys.size()];
    ArrayNode rows = F.arrayNode();
    long budget = budget(ctx);
    Neo4jJson.Budget cells = new Neo4jJson.Budget(budget);
    String truncated = null;
    boolean cut = false;
    while (run.hasNext()) {
      if (rows.size() >= limit) {
        truncated = "row_limit";
        break;
      }
      if (cells.spent() && !rows.isEmpty()) {
        truncated = "size_limit";
        break;
      }
      Record r = run.next();
      ArrayNode row = F.arrayNode();
      for (int i = 0; i < keys.size(); i++) {
        Value v = r.get(i);
        row.add(Neo4jJson.value(v, ctx.limits().maxCellChars(), cells));
        if (!v.isNull()) {
          String t = Neo4jJson.type(v);
          types[i] = types[i] == null || types[i].equals(t) ? t : "ANY";
        }
      }
      rows.add(row);
    }
    cut = cells.cut();
    Bolt.Summary summary = run.consume();
    if (summary.updates()) {
      throw ConnectorException.denied("Neo4j reported changes in a read transaction; GateRook rolled it back and "
          + "returns nothing.");
    }
    List<ToolResult.Column> columns = new ArrayList<>();
    for (int i = 0; i < keys.size(); i++) {
      columns.add(new ToolResult.Column(keys.get(i), types[i] == null ? "NULL" : types[i], null, null, null));
    }
    ObjectNode facts = F.objectNode().put("database", database);
    if (summary.queryType() != null) {
      facts.put("queryType", summary.queryType());
    }
    if (cut) {
      facts.put("valuesCut", true);
    }
    ArrayNode notes = F.arrayNode();
    for (Bolt.Note note : summary.notifications()) {
      if (note.severity() == null || note.severity().equalsIgnoreCase("WARNING")) {
        notes.add(shorten(OutputSanitizer.sanitize(Objects.requireNonNullElse(note.description(), note.code())),
            300));
      }
    }
    if (!notes.isEmpty()) {
      facts.set("warnings", notes);
    }
    ToolResult result = ToolResult.table(new ToolResult.Table(List.copyOf(columns), rows, null), facts);
    return truncated == null ? result : result.truncated(truncated);
  }

  private ToolResult change(ConnectionConfig cfg, Neo4jSettings s, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: neo4j_execute changes the graph, so a person approves it "
          + "first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    String cypher = payload.path("cypher").asString("");
    Cypher.Statement st = Cypher.read(cypher);
    if (st.kind() != Cypher.Kind.WRITE && st.kind() != Cypher.Kind.SCHEMA) {
      throw ConnectorException.denied("neo4j_execute runs one Cypher write GateRook has read.");
    }
    String problem = Parameters.problem(payload.get("parameters"));
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    Map<String, Object> parameters = Parameters.of(payload.get("parameters"));
    int limit = Math.max(1, Math.min(ctx.limits().maxRows(), s.maxRows() == null ? MAX_LIMIT : s.maxRows()));
    Duration timeout = timeout(ctx, s);
    boolean committing = false;
    try (Bolt.Lease lease = bolts.lease(cfg, s, Neo4jAuth.resolve(s, secrets, ctx), ctx);
        Bolt.Tx tx = lease.client().begin(AccessMode.WRITE, s.database(), timeout, metadata(ctx, "change"),
            Math.min(limit + 1, 1_000))) {
      Bolt.Run run = tx.run(cypher, parameters);
      List<String> keys = run.keys();
      ArrayNode rows = F.arrayNode();
      Neo4jJson.Budget cells = new Neo4jJson.Budget(budget(ctx) / 2);
      boolean more = false;
      while (run.hasNext()) {
        if (rows.size() >= limit || cells.spent()) {
          more = true;
          break;
        }
        Record r = run.next();
        ArrayNode row = F.arrayNode();
        for (int i = 0; i < keys.size(); i++) {
          row.add(Neo4jJson.value(r.get(i), ctx.limits().maxCellChars(), cells));
        }
        rows.add(row);
      }
      Bolt.Summary summary = run.consume();
      committing = true;
      try {
        tx.commit();
      } catch (RuntimeException e) {
        throw Neo4jErrors.commit(e, s);
      }
      ObjectNode out = F.objectNode().put("committed", true).put("database", s.database());
      if (summary.queryType() != null) {
        out.put("queryType", summary.queryType());
      }
      ObjectNode counters = out.putObject("counters");
      summary.counters().forEach(counters::put);
      if (!keys.isEmpty()) {
        ArrayNode columns = out.putArray("columns");
        keys.forEach(columns::add);
        out.set("rows", rows);
        if (more) {
          out.put("rowsCut", true);
        }
      }
      return ToolResult.json(out, "Committed: " + counted(summary.counters()) + ".");
    } catch (ConnectorException e) {
      throw e;
    } catch (RuntimeException e) {
      throw committing ? Neo4jErrors.commit(e, s) : Neo4jErrors.beforeCommit(e, s, timeout);
    }
  }

  /** The counters in words: {@code 1 node created, 2 properties set}; {@code nothing changed} without any. */
  static String counted(Map<String, Integer> counters) {
    if (counters.isEmpty()) {
      return "nothing changed";
    }
    List<String> parts = new ArrayList<>();
    counters.forEach((key, n) -> parts.add(n + " " + words(key, n)));
    return String.join(", ", parts);
  }

  private static String words(String key, int n) {
    String[] w = switch (key) {
      case "nodesCreated" -> new String[] {"node", "created"};
      case "nodesDeleted" -> new String[] {"node", "deleted"};
      case "relationshipsCreated" -> new String[] {"relationship", "created"};
      case "relationshipsDeleted" -> new String[] {"relationship", "deleted"};
      case "propertiesSet" -> new String[] {"property", "set"};
      case "labelsAdded" -> new String[] {"label", "added"};
      case "labelsRemoved" -> new String[] {"label", "removed"};
      case "indexesAdded" -> new String[] {"index", "added"};
      case "indexesRemoved" -> new String[] {"index", "removed"};
      case "constraintsAdded" -> new String[] {"constraint", "added"};
      case "constraintsRemoved" -> new String[] {"constraint", "removed"};
      default -> new String[] {"system update", "made"};
    };
    String noun = n == 1 ? w[0] : switch (w[0]) {
      case "property" -> "properties";
      case "index" -> "indexes";
      default -> w[0] + "s";
    };
    return noun + " " + w[1];
  }

  // ── Schema ──────────────────────────────────────────────────────────────────────────────────────────────────

  private static final String LABELS = "CALL db.labels() YIELD label RETURN label ORDER BY label LIMIT $limit";
  private static final String TYPES = "CALL db.relationshipTypes() YIELD relationshipType "
      + "RETURN relationshipType ORDER BY relationshipType LIMIT $limit";
  private static final String KEYS = "CALL db.propertyKeys() YIELD propertyKey RETURN propertyKey "
      + "ORDER BY propertyKey LIMIT $limit";
  private static final String INDEXES = "SHOW INDEXES YIELD name, type, entityType, labelsOrTypes, properties, state, "
      + "owningConstraint RETURN name, type, entityType, labelsOrTypes, properties, state, owningConstraint "
      + "ORDER BY name LIMIT $limit";
  private static final String CONSTRAINTS = "SHOW CONSTRAINTS YIELD name, type, entityType, labelsOrTypes, "
      + "properties, ownedIndex RETURN name, type, entityType, labelsOrTypes, properties, ownedIndex ORDER BY name "
      + "LIMIT $limit";
  private static final String VISUALIZATION = "CALL db.schema.visualization() YIELD nodes, relationships "
      + "RETURN nodes, relationships";

  private ToolResult schema(ConnectionConfig cfg, Neo4jSettings s, SecretResolver secrets, ExecContext ctx,
      JsonNode args) throws ConnectorException {
    int limit = Math.max(1, Math.min(args.path("limit").asInt(200), 1_000));
    Map<String, Object> p = Map.of("limit", (long) limit + 1);
    Duration timeout = timeout(ctx, s);
    ObjectNode out = F.objectNode().put("database", s.database());
    ArrayNode cut = F.arrayNode();
    int cell = ctx.limits().maxCellChars();
    try (Bolt.Lease lease = bolts.lease(cfg, s, Neo4jAuth.resolve(s, secrets, ctx), ctx)) {
      Bolt.Client client = lease.client();
      try (Bolt.Tx tx = client.begin(AccessMode.READ, s.database(), timeout, metadata(ctx, "schema"), limit + 1)) {
        out.set("labels", names(tx.run(LABELS, p), limit, cell, "labels", cut));
        out.set("relationshipTypes", names(tx.run(TYPES, p), limit, cell, "relationshipTypes", cut));
        out.set("propertyKeys", names(tx.run(KEYS, p), limit, cell, "propertyKeys", cut));
      }
      // SHOW commands run in transactions of their own.
      try (Bolt.Tx tx = client.begin(AccessMode.READ, s.database(), timeout, metadata(ctx, "schema"), limit + 1)) {
        out.set("indexes", objects(tx.run(INDEXES, p), limit, cell, "indexes", cut));
      }
      try (Bolt.Tx tx = client.begin(AccessMode.READ, s.database(), timeout, metadata(ctx, "schema"), limit + 1)) {
        out.set("constraints", objects(tx.run(CONSTRAINTS, p), limit, cell, "constraints", cut));
      }
      if (args.path("visualization").asBoolean(false)) {
        try (Bolt.Tx tx = client.begin(AccessMode.READ, s.database(), timeout, metadata(ctx, "schema"), 1)) {
          out.set("graph", visualization(tx.run(VISUALIZATION, Map.of()), limit, cell, cut));
        }
      }
    } catch (RuntimeException e) {
      throw Neo4jErrors.read(e, s, timeout);
    }
    if (!cut.isEmpty()) {
      out.set("truncated", cut);
    }
    StringBuilder text = new StringBuilder(s.database()).append(": ");
    text.append(count(out.path("labels"), "label")).append(" (").append(first(out.path("labels"))).append("), ");
    text.append(count(out.path("relationshipTypes"), "relationship type")).append(" (")
        .append(first(out.path("relationshipTypes"))).append("), ");
    text.append(count(out.path("propertyKeys"), "property key")).append(", ");
    text.append(count(out.path("indexes"), "index")).append(", ");
    text.append(count(out.path("constraints"), "constraint"));
    if (!cut.isEmpty()) {
      text.append(". More than ").append(limit).append(" ").append(String.join(", ", strings(cut)))
          .append(": raise limit.");
    }
    return ToolResult.json(out, text.toString());
  }

  private static ArrayNode names(Bolt.Run run, int limit, int cell, String what, ArrayNode cut) {
    ArrayNode out = F.arrayNode();
    while (run.hasNext()) {
      if (out.size() >= limit) {
        cut.add(what);
        break;
      }
      Value v = run.next().get(0);
      out.add(v.isNull() ? F.nullNode() : Neo4jJson.text(v.asString(), cell));
    }
    run.consume();
    return out;
  }

  private static ArrayNode objects(Bolt.Run run, int limit, int cell, String what, ArrayNode cut) {
    ArrayNode out = F.arrayNode();
    List<String> keys = run.keys();
    Neo4jJson.Budget budget = new Neo4jJson.Budget(Long.MAX_VALUE);
    while (run.hasNext()) {
      if (out.size() >= limit) {
        cut.add(what);
        break;
      }
      Record r = run.next();
      ObjectNode o = out.addObject();
      for (int i = 0; i < keys.size(); i++) {
        if (!r.get(i).isNull()) {
          o.set(keys.get(i), Neo4jJson.value(r.get(i), cell, budget));
        }
      }
    }
    run.consume();
    return out;
  }

  /** The schema graph: each label with the types that leave it for other labels. */
  private static ObjectNode visualization(Bolt.Run run, int limit, int cell, ArrayNode cut) {
    ObjectNode out = F.objectNode();
    ArrayNode nodes = out.putArray("nodes");
    ArrayNode relationships = out.putArray("relationships");
    if (run.hasNext()) {
      Record r = run.next();
      Map<String, List<String>> labels = new LinkedHashMap<>();
      for (Value n : r.get("nodes").values()) {
        List<String> l = new ArrayList<>();
        n.asNode().labels().forEach(l::add);
        labels.put(n.asNode().elementId(), l);
        if (nodes.size() < limit) {
          ArrayNode a = nodes.addArray();
          l.forEach(label -> a.add(Neo4jJson.text(label, cell)));
        } else if (!cut.toString().contains("\"graph\"")) {
          cut.add("graph");
        }
      }
      for (Value v : r.get("relationships").values()) {
        if (relationships.size() >= limit) {
          if (!cut.toString().contains("\"graph\"")) {
            cut.add("graph");
          }
          break;
        }
        var rel = v.asRelationship();
        ObjectNode o = relationships.addObject().put("type", rel.type());
        ArrayNode from = o.putArray("from");
        labels.getOrDefault(rel.startNodeElementId(), List.of()).forEach(from::add);
        ArrayNode to = o.putArray("to");
        labels.getOrDefault(rel.endNodeElementId(), List.of()).forEach(to::add);
      }
    }
    run.consume();
    return out;
  }

  private static String count(JsonNode list, String noun) {
    int n = list.size();
    return n + " " + (n == 1 ? noun : noun.equals("index") ? "indexes" : noun + "s");
  }

  private static String first(JsonNode list) {
    List<String> names = new ArrayList<>();
    for (JsonNode n : list) {
      if (names.size() == 5) {
        names.add("…");
        break;
      }
      names.add(n.asString(""));
    }
    return names.isEmpty() ? "none" : String.join(", ", names);
  }

  private static List<String> strings(ArrayNode a) {
    List<String> out = new ArrayList<>();
    a.forEach(n -> out.add(n.asString()));
    return out;
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    Neo4jSettings s;
    Neo4jAuth auth;
    try {
      s = Neo4jSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("neo4j.settings", e.getMessage(), null);
    }
    try {
      auth = Neo4jAuth.resolve(s, secrets, ctx);
    } catch (ConnectorException e) {
      return TestResult.failure("neo4j.credentials", e.getMessage(), null);
    }
    List<TestResult.Check> checks = new ArrayList<>();
    ObjectNode details = F.objectNode();
    Duration timeout = Duration.ofSeconds(5);
    String agent;
    String edition = null;
    List<String> roles = null;
    String user = auth.user();
    String dbStatus = null;
    boolean passwordChange = false;
    long latency;
    try (Bolt.Lease lease = bolts.fresh(s, auth, ctx)) {
      Bolt.Client client = lease.client();
      long started = System.nanoTime();
      Bolt.Summary summary;
      try (Bolt.Tx tx = client.begin(AccessMode.READ, s.database(), timeout, metadata(ctx, "test"), 1)) {
        Bolt.Run run = tx.run("RETURN 1 AS ok", Map.of());
        while (run.hasNext()) {
          run.next();
        }
        summary = run.consume();
      }
      latency = ms(started);
      agent = summary.agent();
      details.put("agent", agent);
      if (summary.protocol() != null) {
        details.put("bolt", summary.protocol());
      }
      details.put("tls", s.tls());
      checks.add(new TestResult.Check("connect", true, latency, (agent == null ? "Neo4j" : agent)
          + (summary.protocol() == null ? "" : " over Bolt " + summary.protocol()) + (s.tls() ? " with TLS"
          : " without TLS (this computer only)")));
      // Optional facts: a server that refuses one of these commands still works for the tools.
      try (Bolt.Tx tx = client.begin(AccessMode.READ, s.database(), timeout, metadata(ctx, "test"), 10)) {
        Bolt.Run run = tx.run("CALL dbms.components() YIELD name, versions, edition RETURN name, versions, edition",
            Map.of());
        if (run.hasNext()) {
          Record r = run.next();
          edition = r.get("edition").isNull() ? null : r.get("edition").asString();
          details.put("edition", edition);
          if (!r.get("versions").isNull() && !r.get("versions").isEmpty()) {
            details.put("version", r.get("versions").get(0).asString());
          }
        }
        run.consume();
      } catch (RuntimeException e) {
        details.put("edition", "unknown");
      }
      long t = System.nanoTime();
      try (Bolt.Tx tx = client.begin(AccessMode.READ, "system", timeout, metadata(ctx, "test"), 10)) {
        Bolt.Run run = tx.run("SHOW DATABASE $name YIELD name, currentStatus, requestedStatus, access "
            + "RETURN name, currentStatus, requestedStatus, access", Map.of("name", s.database()));
        if (run.hasNext()) {
          Record r = run.next();
          dbStatus = text(r.get("currentStatus"));
          ObjectNode db = details.putObject("database").put("name", s.database()).put("status", dbStatus)
              .put("access", text(r.get("access")));
          checks.add(new TestResult.Check("database", "online".equals(dbStatus), ms(t), s.database() + " "
              + dbStatus + ", " + db.path("access").asString("")));
        } else {
          checks.add(new TestResult.Check("database", false, ms(t), "Neo4j doesn't show " + s.database()
              + " to this user"));
        }
        run.consume();
      } catch (RuntimeException e) {
        checks.add(new TestResult.Check("database", true, ms(t), s.database() + " answers; its state isn't shown: "
            + Neo4jErrors.said(e)));
      }
      t = System.nanoTime();
      try (Bolt.Tx tx = client.begin(AccessMode.READ, "system", timeout, metadata(ctx, "test"), 10)) {
        Bolt.Run run = tx.run("SHOW CURRENT USER YIELD user, roles, passwordChangeRequired "
            + "RETURN user, roles, passwordChangeRequired", Map.of());
        if (run.hasNext()) {
          Record r = run.next();
          user = text(r.get("user"));
          if (!r.get("roles").isNull()) {
            roles = new ArrayList<>();
            for (Value role : r.get("roles").values()) {
              roles.add(role.asString());
            }
          }
          passwordChange = !r.get("passwordChangeRequired").isNull() && r.get("passwordChangeRequired").asBoolean();
          ObjectNode u = details.putObject("user").put("name", user);
          if (roles != null) {
            ArrayNode a = u.putArray("roles");
            roles.forEach(a::add);
          }
          checks.add(new TestResult.Check("user", true, ms(t), user + (roles == null ? " (no roles: "
              + (edition == null ? "this edition" : edition + " edition") + ")" : " · roles: "
              + (roles.isEmpty() ? "none" : String.join(", ", roles)))));
        }
        run.consume();
      } catch (RuntimeException e) {
        checks.add(new TestResult.Check("user", true, ms(t), "Neo4j doesn't show the user's roles: "
            + Neo4jErrors.said(e)));
      }
    } catch (ConnectorException e) {
      return TestResult.failure(code(e), e.getMessage(), null).withChecks(checks);
    } catch (RuntimeException e) {
      ConnectorException c = Neo4jErrors.read(e, s, timeout);
      return TestResult.failure(code(c), c.getMessage(), null).withChecks(checks);
    }
    boolean community = "community".equalsIgnoreCase(edition);
    TestResult.Level level;
    if (community) {
      level = TestResult.Level.ADMIN;
    } else if (roles == null) {
      level = TestResult.Level.UNKNOWN;
    } else if (roles.contains("admin")) {
      level = TestResult.Level.ADMIN;
    } else if (roles.stream().anyMatch(WRITER_ROLES::contains)) {
      level = TestResult.Level.WRITE;
    } else if (roles.stream().allMatch(r -> r.equals("reader") || r.equals("PUBLIC"))) {
      level = TestResult.Level.READ;
    } else {
      level = TestResult.Level.UNKNOWN;
    }
    String version = details.path("version").asString(agent == null ? "" : agent.replace("Neo4j/", ""));
    // Memgraph names a Neo4j version it is compatible with: its agent says what it is.
    String serverVersion = agent != null && agent.toLowerCase(Locale.ROOT).contains("memgraph") ? agent
        : "Neo4j " + version + (edition == null ? "" : " " + edition);
    TestResult.Permissions permissions = new TestResult.Permissions(level, "Cypher reads in READ transactions on "
        + s.database(), level == TestResult.Level.READ ? null : "With approval, one Cypher write per change",
        roles == null ? List.of() : roles.stream().filter(WRITER_ROLES::contains).toList(), null);
    TestResult result = TestResult.success(latency, serverVersion, user, permissions).withChecks(checks)
        .withDetails(details).withMetadata(F.objectNode().put("serverVersion", serverVersion)
            .put("edition", edition == null ? "unknown" : edition));
    for (Hint h : hints(cfg.env(), s, agent, edition, roles, dbStatus, passwordChange)) {
      result = result.withHint(h);
    }
    return result;
  }

  static List<Hint> hints(Env env, Neo4jSettings s, String agent, String edition, List<String> roles,
      String dbStatus, boolean passwordChange) {
    List<Hint> out = new ArrayList<>();
    boolean dev = env == Env.DEV;
    if (agent != null && agent.toLowerCase(Locale.ROOT).contains("memgraph")) {
      out.add(new Hint(Severity.WARNING, "neo4j.memgraph", "/uri", "This server is Memgraph. It reports every "
          + "statement as read and write, so neo4j_query refuses its reads, and it lacks the schema procedures of "
          + "neo4j_schema. GateRook supports Neo4j."));
    }
    if ("community".equalsIgnoreCase(edition)) {
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "neo4j.no-roles", "/authentication/user",
          "Community Edition has no roles: every user may read, write and manage users. GateRook runs reads in READ "
              + "transactions after EXPLAIN calls them read only, and changes wait for approval."));
    } else if (roles != null && roles.contains("admin")) {
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "neo4j.admin", "/authentication/user", "The user has "
          + "the admin role" + (dev ? "." : ". On " + env.id() + ", sign in as a user with the reader role.")));
    } else if (roles != null && roles.stream().anyMatch(WRITER_ROLES::contains)) {
      out.add(new Hint(Severity.INFO, "neo4j.can-write", "/authentication/user", "The user can write ("
          + String.join(", ", roles.stream().filter(WRITER_ROLES::contains).toList()) + "). Reads still run in READ "
          + "transactions; a user with the reader role keeps changes with people."));
    }
    if (dbStatus != null && !"online".equals(dbStatus)) {
      out.add(new Hint(Severity.WARNING, "neo4j.database-offline", "/database", s.database() + " is " + dbStatus
          + ": calls fail until it is online."));
    }
    if (passwordChange) {
      out.add(new Hint(Severity.WARNING, "neo4j.password-change", "/authentication/password", "Neo4j asks this user "
          + "to change the password before it runs anything. Change it in Neo4j, then here."));
    }
    if (!s.tls()) {
      out.add(new Hint(Severity.INFO, "neo4j.no-tls", "/uri", "Without TLS: GateRook allows this only to this "
          + "computer."));
    }
    return out;
  }

  private static String code(ConnectorException e) {
    return switch (e.kind()) {
      case RESOURCE_UNAVAILABLE, TIMEOUT -> e.getMessage().startsWith("TLS") ? "neo4j.tls"
          : e.getMessage().startsWith("Neo4j refused the sign-in") || e.getMessage().contains("token has expired")
          ? "neo4j.auth" : "neo4j.connect";
      case BAD_INPUT -> "neo4j.database";
      // The host resolves to an address no connection reaches (link-local, cloud metadata, reserved).
      case DENIED -> "neo4j.address";
      default -> "neo4j.error";
    };
  }

  private static String text(Value v) {
    return v == null || v.isNull() ? "" : v.asString();
  }

  /** The scheme the server needs and, signed in, its databases ({@link Neo4jSuggestions}). */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return Neo4jSuggestions.suggest(cfg, secrets, ctx, scope, bolts, LocalPorts::listening,
        (host, port) -> LoopbackBolt.probe(host, port, LOCAL_PROBE));
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      Neo4jSettings s = Neo4jSettings.of(cfg);
      long started = System.nanoTime();
      Duration timeout = Duration.ofSeconds(5);
      try (Bolt.Lease lease = bolts.lease(cfg, s, Neo4jAuth.resolve(s, secrets, ctx), ctx);
          Bolt.Tx tx = lease.client().begin(AccessMode.READ, s.database(), timeout, metadata(ctx, "health"), 1)) {
        Bolt.Run run = tx.run("RETURN 1 AS ok", Map.of());
        while (run.hasNext()) {
          run.next();
        }
        run.consume();
      } catch (RuntimeException e) {
        throw Neo4jErrors.read(e, s, timeout);
      }
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode().put("server", target(cfg.string("/uri")))
        .put("database", Objects.requireNonNullElse(cfg.string("/database"), Neo4jSettings.DEFAULT_DATABASE));
    out.put("reads", "One Cypher read per neo4j_query with $parameters, in a READ transaction after Neo4j's EXPLAIN "
        + "calls it read only; neo4j_schema shows labels, relationship types, property keys, indexes and "
        + "constraints");
    out.put("changes", "One Cypher write per neo4j_execute, after approval");
    out.put("refused", "USE, LOAD CSV, administration and SHOW commands, CALL { … } IN TRANSACTIONS, procedures "
        + "other than db.labels, db.relationshipTypes, db.propertyKeys and db.schema.*");
    if (object != null && !object.isBlank()) {
      out.put("label", "MATCH (n:`" + object.replace("`", "``") + "`) RETURN n LIMIT 10 shows nodes of " + object
          + ".");
    }
    return Optional.of(out);
  }

  @Override
  public int maxConcurrency() {
    return 4;
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    bolts.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    bolts.evict(connectionId);
  }

  @Override
  public void close() {
    bolts.close();
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
