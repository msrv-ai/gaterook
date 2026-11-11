package ai.msrv.gaterook.connectors.dynamodb;

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
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Hint;
import ai.msrv.gaterook.connector.api.TestResult.Severity;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Amazon DynamoDB (docs/dynamodb.md): {@code dynamodb_list_tables}, {@code dynamodb_describe_table},
 * {@code dynamodb_query}, {@code dynamodb_scan} and {@code dynamodb_partiql} read; {@code dynamodb_execute} runs one
 * PartiQL INSERT, UPDATE or DELETE that a person approved.
 *
 * <p>The route of a call comes from {@link #classify} without I/O: the tables of the connection's list, typed values
 * that are values, and a PartiQL statement read by its own tokens ({@link PartiQl}). A read calls only read APIs, and
 * PartiQL only with a SELECT. With an IAM role, AWS enforces the same: each call assumes the role with a session policy
 * that allows the read actions on the listed tables, or for an approved change the PartiQL writes on that one table
 * ({@link DynamoSettings#readPolicy}, {@link DynamoSettings#writePolicy}). Without a role, the key's own permissions
 * are the boundary.
 */
public final class DynamoDbConnector implements Connector {

  static final String TYPE = "dynamodb";
  static final int DEFAULT_LIMIT = 25;
  /** Pages one call reads at most to fill its limit, as a filter may keep few items of each. */
  static final int MAX_PAGES = 5;
  /** Table names of one listing request, DynamoDB's own maximum. */
  static final int LIST_PAGE = 100;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final CloudCredentials cloud;
  private final DynamoApi api;
  private final ConnectorManifest manifest;

  public DynamoDbConnector(CloudCredentials cloud) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.api = new DynamoApi(cloud);
    SchemaResources schemas = SchemaResources.of(DynamoDbConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Amazon DynamoDB")
        .icon("database")
        .group("Databases")
        .description("Tables, Query, Scan and PartiQL on Amazon DynamoDB. Changes wait for approval.")
        .configSchema(schemas.load("config"))
        .tools(DynamoTools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("dynamodb.*.amazonaws.com", "sts.*.amazonaws.com"),
            List.of(), List.of("~/.aws/config", "~/.aws/credentials")))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    DynamoSettings s;
    try {
      s = DynamoSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    String tool = call.tool();
    if (DynamoTools.LIST_TABLES.equals(tool)) {
      return Classification.read();
    }
    if (DynamoTools.DESCRIBE_TABLE.equals(tool) || DynamoTools.QUERY.equals(tool) || DynamoTools.SCAN.equals(tool)) {
      String table = call.string("table");
      Classification t = table(table, s, policy);
      if (t != null) {
        return t;
      }
      String problem = values(call.arguments().path("values"));
      return problem == null ? Classification.read() : Classification.deny(policy.rule("bad-argument"), problem,
          "Write each value as a DynamoDB typed value, such as {\"S\": \"text\"}.");
    }
    if (DynamoTools.PARTIQL.equals(tool) || DynamoTools.EXECUTE.equals(tool)) {
      PartiQl.Statement st = PartiQl.read(call.string("statement"));
      if (!st.analyzed()) {
        return Classification.deny(policy.rule("unparsed"), st.problem(), "Write one plain PartiQL statement.");
      }
      boolean read = DynamoTools.PARTIQL.equals(tool);
      if (read && st.kind() != PartiQl.Kind.SELECT) {
        return Classification.deny(policy.rule("query-writes"), "dynamodb_partiql only reads; this is " + st.kind()
            + ".", "Use dynamodb_execute: a person approves the exact statement.");
      }
      if (!read && st.kind() == PartiQl.Kind.SELECT) {
        return Classification.deny(policy.rule("execute-read"), "This is a read.", "Use dynamodb_partiql; it "
            + "doesn't need approval.");
      }
      Classification t = table(st.table(), s, policy);
      if (t != null) {
        return t;
      }
      String problem = parameters(call.arguments().path("parameters"));
      if (problem != null) {
        return Classification.deny(policy.rule("bad-argument"), problem, "Write each parameter as a DynamoDB typed "
            + "value, such as {\"S\": \"text\"}.");
      }
      if (read) {
        return Classification.read();
      }
      ObjectNode facts = F.objectNode().put("statement", st.kind().name()).put("table", st.table());
      return Classification.mutation(st.kind() + " on " + st.table() + " changes data", facts);
    }
    return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + tool + ".", null);
  }

  private static Classification table(String table, DynamoSettings s, PolicyView policy) {
    if (table == null || !PartiQl.TABLE.matcher(table).matches()) {
      return Classification.deny(policy.rule("bad-argument"), (table == null ? "The call names no table"
          : table + " isn't a DynamoDB table name") + ".", "Call dynamodb_list_tables to see the tables.");
    }
    if (!s.allows(table)) {
      return Classification.deny(policy.rule("table-not-allowed"), table + " isn't one of the tables agents may use "
          + "on " + (policy.env() == null ? "this environment" : policy.env().id()) + ".",
          "Call dynamodb_list_tables to see the tables you can use.");
    }
    return null;
  }

  /** Why {@code values} isn't a map of typed values, or {@code null}. */
  static String values(JsonNode values) {
    if (values == null || values.isMissingNode() || values.isNull()) {
      return null;
    }
    if (!values.isObject()) {
      return "values must be an object of placeholders such as {\":id\": {\"S\": \"c-42\"}}.";
    }
    for (Map.Entry<String, JsonNode> e : values.properties()) {
      String problem = Items.invalid(e.getValue());
      if (problem != null) {
        return "The value of " + e.getKey() + " " + problem + ".";
      }
    }
    return null;
  }

  /** Why {@code parameters} isn't an array of typed values, or {@code null}. */
  static String parameters(JsonNode parameters) {
    if (parameters == null || parameters.isMissingNode() || parameters.isNull()) {
      return null;
    }
    if (!parameters.isArray()) {
      return "parameters must be an array of typed values such as [{\"S\": \"c-42\"}].";
    }
    for (int i = 0; i < parameters.size(); i++) {
      String problem = Items.invalid(parameters.get(i));
      if (problem != null) {
        return "Parameter " + (i + 1) + " " + problem + ".";
      }
    }
    return null;
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String region = cfg.string("/region");
    if (DynamoTools.EXECUTE.equals(call.tool())) {
      String statement = Objects.requireNonNullElse(call.string("statement"), "");
      PartiQl.Statement st = PartiQl.read(statement);
      JsonNode parameters = call.arguments().path("parameters");
      ObjectNode payload = F.objectNode().put("statement", statement);
      if (parameters.isArray()) {
        payload.set("parameters", parameters.deepCopy());
      }
      List<Field> fields = new ArrayList<>();
      if (parameters.isArray() && !parameters.isEmpty()) {
        fields.add(new Field("Parameters", shorten(parameters.toString(), 2_000)));
      }
      List<String> warnings = new ArrayList<>();
      if (cfg.string("/credentials/roleArn") == null || "none".equals(cfg.string("/credentials/sessionPolicy"))) {
        warnings.add("AWS doesn't limit this change to " + (st.table() == null ? "its table" : st.table())
            + ": the credentials' own permissions apply.");
      }
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", statement, cfg.name() + " · " + region + " · "
              + (st.table() == null ? "?" : st.table()),
              shorten(DynamoTools.EXECUTE + " on " + cfg.name() + ": " + st.kind() + " " + Objects.requireNonNullElse(
                  st.table(), ""), 120))
          .withFields(fields)
          .withImpact(List.of(new Field("Table", st.table() == null ? "Unknown: not analyzed" : st.table()),
              new Field("Items", st.kind() == PartiQl.Kind.INSERT ? "One new item"
                  : "One item, named by its full primary key"),
              new Field("Retried", "No: a request whose answer is lost is reported as unknown")))
          .withWarnings(warnings)
          .withFacets(Map.of("statement", st.kind().name(), "table", Objects.requireNonNullElse(st.table(), "")))
          .withPayload(payload);
    }
    // A read tool an endpoint put in Approval (a Scan on prod): its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    String table = call.string("table");
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), cfg.name() + " · " + region
            + (table == null ? "" : " · " + table), shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", DynamoTools.SCAN.equals(call.tool())
            ? "Every item of the table or index, up to the limit; read capacity for each" : "Items, no change")));
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    DynamoSettings s = DynamoSettings.of(cfg);
    DynamoApi.Call c = new DynamoApi.Call(cfg, s, secrets, ctx);
    JsonNode args = arguments(call, ctx);
    return switch (call.tool()) {
      case DynamoTools.LIST_TABLES -> listTables(c, args);
      case DynamoTools.DESCRIBE_TABLE -> describeTable(c, checkedTable(args.path("table").asString(null), s));
      case DynamoTools.QUERY -> read(c, call.tool(), args, true);
      case DynamoTools.SCAN -> read(c, call.tool(), args, false);
      case DynamoTools.PARTIQL -> partiql(c, args);
      case DynamoTools.EXECUTE -> change(c, ctx);
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

  /** Defence in depth: {@link #execute} gets no policy, so it checks the table against the connection again. */
  private static String checkedTable(String table, DynamoSettings s) throws ConnectorException {
    if (table == null || !PartiQl.TABLE.matcher(table).matches()) {
      throw ConnectorException.badInput("Name a DynamoDB table. Call dynamodb_list_tables to see the tables.");
    }
    if (!s.allows(table)) {
      throw ConnectorException.denied(table + " isn't one of the tables of this connection.");
    }
    return table;
  }

  private static int limit(JsonNode args, ExecContext ctx, int dflt) {
    return Math.max(1, Math.min(args.path("limit").asInt(dflt), ctx.limits().maxRows()));
  }

  /** Bytes of the answer's items; leaves room for the rest of it. */
  private static long budget(ExecContext ctx) {
    return Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
  }

  private ToolResult listTables(DynamoApi.Call c, JsonNode args) throws ConnectorException {
    ExecContext ctx = c.ctx();
    String prefix = args.path("prefix").asString("");
    int limit = Math.max(1, Math.min(args.path("limit").asInt(100), 1_000));
    String hash = Cursors.hash(F.objectNode().put("prefix", prefix));
    String start = null;
    if (args.path("cursor").isString()) {
      start = Cursors.open(ctx, DynamoTools.LIST_TABLES, args.get("cursor").asString(), c.cfg().id(), hash)
          .asString(null);
    }
    List<String> tables = new ArrayList<>();
    String next = null;
    for (int page = 0; page < 10; page++) {
      ObjectNode req = F.objectNode().put("Limit", LIST_PAGE);
      if (start != null) {
        req.put("ExclusiveStartTableName", start);
      }
      JsonNode r = api.call(c, "ListTables", req, c.settings().readPolicy(), false);
      JsonNode names = r.path("TableNames");
      boolean restOfPage = false;
      for (int i = 0; i < names.size(); i++) {
        String name = names.get(i).asString("");
        if (name.startsWith(prefix) && c.settings().allows(name)) {
          tables.add(name);
          if (tables.size() == limit) {
            restOfPage = i < names.size() - 1;
            break;
          }
        }
      }
      String evaluated = r.path("LastEvaluatedTableName").asString(null);
      if (tables.size() == limit) {
        // The rest of this page and the pages after it go on after the last table shown.
        next = restOfPage || evaluated != null ? tables.getLast() : null;
        break;
      }
      if (evaluated == null || ctx.remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        next = evaluated;
        break;
      }
      start = evaluated;
    }
    ObjectNode out = F.objectNode();
    ArrayNode names = out.putArray("tables");
    tables.forEach(names::add);
    if (next != null) {
      out.put("nextCursor", Cursors.seal(ctx, DynamoTools.LIST_TABLES, c.cfg().id(), hash, F.stringNode(next)));
    }
    String text = tables.isEmpty() ? "No tables you may use in " + c.settings().region() + "."
        : tables.size() + (tables.size() == 1 ? " table: " : " tables: ") + String.join(", ", tables)
            + (next == null ? "" : "\nMore tables: pass nextCursor.");
    return ToolResult.json(out, text);
  }

  private ToolResult describeTable(DynamoApi.Call c, String table) throws ConnectorException {
    JsonNode t = api.call(c, "DescribeTable", F.objectNode().put("TableName", table), c.settings().readPolicy(),
        false).path("Table");
    ObjectNode out = F.objectNode();
    out.put("table", t.path("TableName").asString(table));
    out.put("status", t.path("TableStatus").asString(null));
    Map<String, String> types = new LinkedHashMap<>();
    t.path("AttributeDefinitions").forEach(a -> types.put(a.path("AttributeName").asString(""),
        a.path("AttributeType").asString("")));
    out.set("key", keys(t.path("KeySchema"), types));
    ArrayNode indexes = out.putArray("indexes");
    for (String kind : List.of("GlobalSecondaryIndexes", "LocalSecondaryIndexes")) {
      for (JsonNode i : t.path(kind)) {
        ObjectNode index = indexes.addObject().put("name", i.path("IndexName").asString(""))
            .put("kind", kind.startsWith("Global") ? "global" : "local");
        index.set("key", keys(i.path("KeySchema"), types));
        index.put("projection", i.path("Projection").path("ProjectionType").asString(null));
        if (i.has("IndexStatus")) {
          index.put("status", i.path("IndexStatus").asString());
        }
      }
    }
    if (t.path("ItemCount").isNumber()) {
      out.put("itemCount", t.get("ItemCount").asLong());
    }
    if (t.path("TableSizeBytes").isNumber()) {
      out.put("sizeBytes", t.get("TableSizeBytes").asLong());
    }
    out.put("billingMode", t.path("BillingModeSummary").path("BillingMode").asString(
        t.path("ProvisionedThroughput").isObject() ? "PROVISIONED" : null));
    if (t.path("StreamSpecification").path("StreamEnabled").asBoolean(false)) {
      out.put("stream", t.path("StreamSpecification").path("StreamViewType").asString("enabled"));
    }
    if (t.has("DeletionProtectionEnabled")) {
      out.put("deletionProtection", t.get("DeletionProtectionEnabled").asBoolean());
    }
    try {
      JsonNode ttl = api.call(c, "DescribeTimeToLive", F.objectNode().put("TableName", table),
          c.settings().readPolicy(), false).path("TimeToLiveDescription");
      if ("ENABLED".equals(ttl.path("TimeToLiveStatus").asString(""))) {
        out.put("ttlAttribute", ttl.path("AttributeName").asString(null));
      }
    } catch (ConnectorException e) {
      // The time to live is extra: a role without DescribeTimeToLive still describes the table.
    }
    return ToolResult.json(out, describeText(out));
  }

  private static ArrayNode keys(JsonNode schema, Map<String, String> types) {
    ArrayNode out = F.arrayNode();
    for (JsonNode k : schema) {
      String name = k.path("AttributeName").asString("");
      out.addObject().put("name", name).put("role", "HASH".equals(k.path("KeyType").asString("")) ? "partition"
          : "sort").put("type", types.getOrDefault(name, "?"));
    }
    return out;
  }

  /** {@code orders (ACTIVE, ~1,204 items): customer_id S partition, created S sort · 2 indexes}. */
  static String describeText(ObjectNode d) {
    StringBuilder out = new StringBuilder(d.path("table").asString()).append(" (")
        .append(d.path("status").asString("?"));
    if (d.path("itemCount").isNumber()) {
      out.append(", ~").append(String.format(Locale.US, "%,d", d.get("itemCount").asLong())).append(" items");
    }
    out.append("): ");
    List<String> key = new ArrayList<>();
    d.path("key").forEach(k -> key.add(k.path("name").asString() + " " + k.path("type").asString() + " "
        + k.path("role").asString()));
    out.append(String.join(", ", key));
    int indexes = d.path("indexes").size();
    if (indexes > 0) {
      List<String> names = new ArrayList<>();
      d.path("indexes").forEach(i -> names.add(i.path("name").asString() + " (" + i.path("kind").asString() + ")"));
      out.append(" · indexes: ").append(String.join(", ", names));
    }
    if (d.path("ttlAttribute").isString()) {
      out.append(" · TTL on ").append(d.get("ttlAttribute").asString());
    }
    return out.toString();
  }

  /** Query ({@code keyed}) or Scan, page after page until the limit, the budget or {@link #MAX_PAGES}. */
  private ToolResult read(DynamoApi.Call c, String tool, JsonNode args, boolean keyed) throws ConnectorException {
    ExecContext ctx = c.ctx();
    String table = checkedTable(args.path("table").asString(null), c.settings());
    String problem = values(args.path("values"));
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    ObjectNode req = F.objectNode().put("TableName", table);
    copy(args, "index", req, "IndexName");
    if (keyed) {
      copy(args, "keyCondition", req, "KeyConditionExpression");
      if (!req.has("KeyConditionExpression")) {
        throw ConnectorException.badInput("dynamodb_query needs keyCondition, such as \"customer_id = :id\".");
      }
      req.put("ScanIndexForward", args.path("ascending").asBoolean(true));
    }
    copy(args, "filter", req, "FilterExpression");
    copy(args, "projection", req, "ProjectionExpression");
    if (args.path("names").isObject() && !args.get("names").isEmpty()) {
      req.set("ExpressionAttributeNames", args.get("names").deepCopy());
    }
    if (args.path("values").isObject() && !args.get("values").isEmpty()) {
      req.set("ExpressionAttributeValues", args.get("values").deepCopy());
    }
    if (args.path("consistent").isBoolean()) {
      req.put("ConsistentRead", args.get("consistent").asBoolean());
    } else if (c.settings().consistentReads() && !req.has("IndexName")) {
      req.put("ConsistentRead", true);
    }
    req.put("ReturnConsumedCapacity", "TOTAL");
    String hash = Cursors.hash(req);
    if (args.path("cursor").isString()) {
      req.set("ExclusiveStartKey", Cursors.open(ctx, tool, args.get("cursor").asString(), c.cfg().id(), hash));
    }
    return pages(c, tool, keyed ? "Query" : "Scan", req, hash, limit(args, ctx, DEFAULT_LIMIT), "ExclusiveStartKey",
        "LastEvaluatedKey", table);
  }

  private ToolResult partiql(DynamoApi.Call c, JsonNode args) throws ConnectorException {
    String statement = args.path("statement").asString("");
    PartiQl.Statement st = PartiQl.read(statement);
    if (!st.analyzed() || st.kind() != PartiQl.Kind.SELECT) {
      throw ConnectorException.denied("dynamodb_partiql only runs one PartiQL SELECT the gateway reads as a read.");
    }
    checkedTable(st.table(), c.settings());
    String problem = parameters(args.path("parameters"));
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    ObjectNode req = F.objectNode().put("Statement", statement);
    if (args.path("parameters").isArray() && !args.get("parameters").isEmpty()) {
      req.set("Parameters", args.get("parameters").deepCopy());
    }
    if (c.settings().consistentReads()) {
      req.put("ConsistentRead", true);
    }
    req.put("ReturnConsumedCapacity", "TOTAL");
    String hash = Cursors.hash(req);
    if (args.path("cursor").isString()) {
      req.set("NextToken", Cursors.open(c.ctx(), DynamoTools.PARTIQL, args.get("cursor").asString(), c.cfg().id(),
          hash));
    }
    return pages(c, DynamoTools.PARTIQL, "ExecuteStatement", req, hash, limit(args, c.ctx(), DEFAULT_LIMIT),
        "NextToken", "NextToken", st.table());
  }

  /**
   * The pages of a read: DynamoDB's {@code Limit} counts the items it evaluates, before a filter, so a page may hold
   * fewer than asked and the call reads on, up to {@link #MAX_PAGES}. The cursor starts where DynamoDB stopped; an
   * answer cut at the response size ends without one, and says so.
   */
  private ToolResult pages(DynamoApi.Call c, String tool, String operation, ObjectNode req, String hash, int limit,
      String startField, String nextField, String table) throws ConnectorException {
    ExecContext ctx = c.ctx();
    int cell = ctx.limits().maxCellChars();
    long budget = budget(ctx);
    ArrayNode items = F.arrayNode();
    long bytes = 0;
    long scanned = 0;
    double capacity = 0;
    JsonNode next = null;
    String truncated = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      req.put("Limit", limit - items.size());
      JsonNode r = api.call(c, operation, req, c.settings().readPolicy(), false);
      for (JsonNode item : r.path("Items")) {
        ObjectNode plain = Items.plainItem(item, cell);
        bytes += plain.toString().length();
        if (bytes > budget) {
          truncated = "size_limit";
          break;
        }
        items.add(plain);
      }
      scanned += r.path("ScannedCount").asLong(r.path("Items").size());
      capacity += r.path("ConsumedCapacity").path("CapacityUnits").asDouble(0);
      next = r.has(nextField) && !r.get(nextField).isNull() ? r.get(nextField) : null;
      if (truncated != null) {
        next = null;
        break;
      }
      if (next == null || items.size() >= limit || ctx.remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
      req.set(startField, next);
    }
    ObjectNode out = F.objectNode();
    out.set("items", items);
    out.put("count", items.size());
    if (!DynamoTools.PARTIQL.equals(tool)) {
      out.put("scannedCount", scanned);
    }
    out.put("consumedCapacity", capacity);
    if (next != null) {
      out.put("nextCursor", Cursors.seal(ctx, tool, c.cfg().id(), hash, next));
    }
    StringBuilder text = new StringBuilder();
    text.append(items.size()).append(items.size() == 1 ? " item" : " items").append(" from ").append(table);
    if (!DynamoTools.PARTIQL.equals(tool) && scanned != items.size()) {
      text.append(" (").append(scanned).append(" read)");
    }
    text.append(String.format(Locale.US, " · %.1f read capacity units", capacity));
    for (JsonNode item : items) {
      text.append('\n').append(item);
    }
    if (next != null) {
      text.append("\nMore items: pass nextCursor.");
    }
    ToolResult result = ToolResult.json(out, text.toString());
    if (truncated != null) {
      text.append("\nCut at the response size: narrow it with projection, filter or a smaller limit.");
      return ToolResult.json(out, text.toString()).truncated(truncated);
    }
    return result;
  }

  private static void copy(JsonNode args, String from, ObjectNode req, String to) {
    String v = args.path(from).asString(null);
    if (v != null && !v.isBlank()) {
      req.put(to, v);
    }
  }

  private ToolResult change(DynamoApi.Call c, ExecContext ctx) throws ConnectorException {
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: dynamodb_execute changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    String statement = payload.path("statement").asString("");
    PartiQl.Statement st = PartiQl.read(statement);
    if (!st.analyzed() || st.kind() == PartiQl.Kind.SELECT || st.kind() == PartiQl.Kind.OTHER) {
      throw ConnectorException.denied("dynamodb_execute runs one analyzed PartiQL INSERT, UPDATE or DELETE.");
    }
    checkedTable(st.table(), c.settings());
    String problem = parameters(payload.path("parameters"));
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    ObjectNode req = F.objectNode().put("Statement", statement).put("ReturnConsumedCapacity", "TOTAL");
    if (payload.path("parameters").isArray() && !payload.get("parameters").isEmpty()) {
      req.set("Parameters", payload.get("parameters").deepCopy());
    }
    JsonNode r = api.call(c, "ExecuteStatement", req, c.settings().writePolicy(st.table()), true);
    ObjectNode out = F.objectNode().put("committed", true).put("statement", st.kind().name())
        .put("table", st.table())
        .put("consumedCapacity", r.path("ConsumedCapacity").path("CapacityUnits").asDouble(0));
    String verb = switch (st.kind()) {
      case INSERT -> "inserted into";
      case UPDATE -> "updated in";
      case DELETE -> "deleted from";
      default -> "changed in";
    };
    return ToolResult.json(out, "1 item " + verb + " " + st.table() + ".");
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    long started = System.nanoTime();
    DynamoSettings s;
    try {
      s = DynamoSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("dynamodb.settings", e.getMessage(), null);
    }
    List<TestResult.Check> checks = new ArrayList<>();
    CloudCredentials.CallerIdentity identity;
    try {
      AwsCredentialsProvider provider = CloudCredentials.tainting(cloud.aws(new CloudCredentials.Owner(cfg.id(),
          cfg.version(), cfg.name()), s.credentials(), s.readPolicy(), secrets), ctx::taint);
      identity = cloud.callerIdentity(provider, s.region());
    } catch (ConnectorException e) {
      return TestResult.failure("dynamodb.credentials", e.getMessage(), null);
    } catch (SdkException e) {
      return TestResult.failure("dynamodb.credentials", "The AWS credentials couldn't be loaded: "
          + DynamoApi.firstLine(e.getMessage()) + ".", null);
    }
    checks.add(new TestResult.Check("identity", true, ms(started), identity.arn()));
    long listed = System.nanoTime();
    int visible;
    List<String> first = new ArrayList<>();
    try {
      JsonNode r = api.call(new DynamoApi.Call(cfg, s, secrets, ctx), "ListTables", F.objectNode().put("Limit",
          LIST_PAGE), s.readPolicy(), false);
      visible = 0;
      for (JsonNode n : r.path("TableNames")) {
        if (s.allows(n.asString(""))) {
          visible++;
          if (first.size() < 5) {
            first.add(n.asString());
          }
        }
      }
      if (r.has("LastEvaluatedTableName")) {
        first.add("…");
      }
    } catch (ConnectorException e) {
      return TestResult.failure(e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE ? "dynamodb.unreachable"
          : "dynamodb.list", e.getMessage(), null).withChecks(checks);
    }
    long latency = ms(listed);
    checks.add(new TestResult.Check("tables", true, latency, visible + (visible == 1 ? " table" : " tables")
        + " agents may use" + (first.isEmpty() ? "" : ": " + String.join(", ", first))));
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        "Query, Scan and PartiQL SELECT", null, List.of(), F.objectNode().put("sessionPolicy",
        s.readPolicy() == null ? "none" : "generated per call"));
    TestResult result = TestResult.success(latency, "Amazon DynamoDB · " + s.region(), identity.arn(), permissions)
        .withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", "DynamoDB " + s.region()).put("account",
            identity.account()).put("region", s.region()));
    for (Hint h : hints(cfg.env(), s, visible)) {
      result = result.withHint(h);
    }
    return result;
  }

  static List<Hint> hints(Env env, DynamoSettings s, int visible) {
    List<Hint> out = new ArrayList<>();
    if (s.credentials().roleArn() == null) {
      boolean dev = env == Env.DEV;
      out.add(new Hint(dev ? Severity.INFO : Severity.WARNING, "dynamodb.no-role", "/credentials/roleArn",
          "Without a role, each call has every permission of the credentials. With a role, GateRook limits each call "
              + "to what it needs, enforced by AWS."));
    } else if (!s.generatedPolicy()) {
      out.add(new Hint(Severity.WARNING, "dynamodb.no-session-policy", "/credentials/sessionPolicy",
          "Calls use the role's permissions as they are: AWS doesn't keep a read from writing."));
    }
    if (visible == 0) {
      out.add(new Hint(Severity.WARNING, "dynamodb.no-tables", "/tables", s.tables().isEmpty()
          ? "The credentials see no table in " + s.region() + "."
          : "No table in " + s.region() + " matches the connection's list."));
    }
    return out;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      DynamoSettings s = DynamoSettings.of(cfg);
      long started = System.nanoTime();
      api.call(new DynamoApi.Call(cfg, s, secrets, ctx), "ListTables", F.objectNode().put("Limit", 1),
          s.readPolicy(), false);
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode();
    out.put("region", cfg.string("/region"));
    List<String> tables = cfg.strings("/tables");
    if (tables.isEmpty()) {
      out.put("tables", "Every table the credentials may read");
    } else {
      ArrayNode a = out.putArray("tables");
      tables.forEach(a::add);
    }
    out.put("reads", "dynamodb_query and dynamodb_scan with DynamoDB expressions, dynamodb_partiql with one SELECT; "
        + "values are DynamoDB typed values such as {\"S\": \"text\"}");
    out.put("changes", "One PartiQL INSERT, UPDATE or DELETE per dynamodb_execute, after approval");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call dynamodb_describe_table with table " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    cloud.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    cloud.evict(connectionId);
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
