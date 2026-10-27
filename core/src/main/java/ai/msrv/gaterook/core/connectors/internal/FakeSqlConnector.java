package ai.msrv.gaterook.core.connectors.internal;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.SecretUnavailableException;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.platform.CoreBuild;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A SQL-like connector that answers from memory: the core's end-to-end tests and the native smoke run the whole call
 * path with it (ARCHITECTURE.md §10, M1.7) while the built-in connectors are still skeletons. Never part of a release:
 * {@code ConnectorRegistry} adds it only to a non-release core started with {@code gateway.test.fake-connector=true}.
 *
 * <p>It publishes the tools of the first built-in type that has {@code sql_query} (PostgreSQL in the assembled core),
 * byte for byte, together with that type's configuration form and policy section: so the fake merges with that type
 * on an endpoint, its reads are checked against the real {@code outputSchema}, and {@code prod.sql.block_ddl} reads the
 * real policy. Classification is by the statement's first keyword: {@code SELECT}, {@code WITH}, {@code VALUES},
 * {@code TABLE}, {@code SHOW} and {@code EXPLAIN} read, anything else changes data, and DDL is denied where the policy
 * denies it. Each connection holds one table, {@code orders(id int4, status text)}, so an approved {@code UPDATE} is
 * visible to the next {@code SELECT}: the tests see that the stored statement ran, not the agent's arguments.
 *
 * <p>No I/O, no threads; secrets are only read to prove the resolver works ({@link #test}).
 */
public final class FakeSqlConnector implements Connector {

  /** The connector type id. */
  public static final String TYPE = "fake-sql";

  static final String QUERY = "sql_query";
  static final String EXECUTE = "sql_execute";
  private static final String PASSWORD = "/credentials/password";
  private static final Set<String> READ_KEYWORDS = Set.of("SELECT", "WITH", "VALUES", "TABLE", "SHOW", "EXPLAIN");
  private static final Set<String> DDL_KEYWORDS = Set.of("CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME");
  private static final Pattern FIRST_WORD = Pattern.compile("^\\s*([A-Za-z]+)");
  private static final Pattern PLACEHOLDER = Pattern.compile(":([a-z][a-z0-9_]{0,31})");
  private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
  private static final Pattern UPDATE = Pattern.compile(
      "(?is)^\\s*UPDATE\\s+orders\\s+SET\\s+status\\s*=\\s*'([^']*)'\\s+WHERE\\s+id\\s*=\\s*(\\d+)\\s*;?\\s*$");
  private static final Pattern INSERT = Pattern.compile(
      "(?is)^\\s*INSERT\\s+INTO\\s+orders\\s*\\(\\s*id\\s*,\\s*status\\s*\\)\\s*VALUES\\s*\\(\\s*(\\d+)\\s*,\\s*'([^']*)'"
          + "\\s*\\)\\s*;?\\s*$");
  private static final Pattern DELETE = Pattern.compile(
      "(?is)^\\s*DELETE\\s+FROM\\s+orders\\s+WHERE\\s+id\\s*=\\s*(\\d+)\\s*;?\\s*$");
  private static final Pattern WHERE_STATUS = Pattern.compile("(?is)\\bWHERE\\s+status\\s*=\\s*'([^']*)'");
  private static final Pattern WHERE_ID = Pattern.compile("(?is)\\bWHERE\\s+id\\s*=\\s*(\\d+)");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ConnectorManifest manifest;
  private final JsonNode readOutputSchema;
  private final Map<String, Map<Integer, String>> tables = new ConcurrentHashMap<>();

  private FakeSqlConnector(ConnectorManifest manifest, JsonNode readOutputSchema) {
    this.manifest = manifest;
    this.readOutputSchema = readOutputSchema;
  }

  /**
   * The fake shaped like {@code template} (a built-in SQL type: its {@code sql_query}, {@code sql_execute}, form and
   * policy), or with its own minimal tools when there is none.
   */
  public static FakeSqlConnector like(Optional<ConnectorManifest> template) {
    ConnectorManifest.Builder b = ConnectorManifest.builder(TYPE, CoreBuild.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Fake SQL").icon("database").group("Databases")
        .description("An in-memory SQL database for tests. Not part of a release.");
    List<ToolDefinition> tools = new ArrayList<>();
    if (template.isPresent()) {
      ConnectorManifest m = template.get();
      m.tools().stream().filter(t -> t.name().equals(QUERY) || t.name().equals(EXECUTE)).forEach(tools::add);
      b.configSchema(m.configSchema()).policySchema(m.policySchema());
    } else {
      tools.add(ToolDefinition.builder(QUERY).title("Run a read-only SQL query").description("Run one SELECT.")
          .inputSchema(input("One SELECT.")).outputSchema(read(TABLE_OUTPUT)).annotations(Annotations.read())
          .nature(Nature.READ).defaultMode(Mode.READ_ONLY, Mode.READ_ONLY).resultShape(ResultShape.TABLE).build());
      tools.add(ToolDefinition.builder(EXECUTE).title("Change data (needs approval)")
          .description("INSERT, UPDATE or DELETE. You approve the exact statement.").inputSchema(input("A change."))
          .annotations(Annotations.write(true)).nature(Nature.MUTATION).defaultMode(Mode.APPROVAL, Mode.APPROVAL)
          .resultShape(ResultShape.JSON).build());
      b.configSchema(read(CONFIG)).policySchema(read(POLICY));
    }
    ConnectorManifest manifest = b.tools(tools).build();
    JsonNode output = tools.stream().filter(t -> t.name().equals(QUERY)).findFirst().map(ToolDefinition::outputSchema)
        .orElse(null);
    return new FakeSqlConnector(manifest, output);
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public List<TemplateKind> templateKinds() {
    return List.of(new QueryTemplates());
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    String sql = statement(call);
    String keyword = firstKeyword(sql);
    if (DDL_KEYWORDS.contains(keyword) && policy != null && policy.flag("denyDdl", false)) {
      return Classification.deny(policy.rule("block_ddl"), "DDL is not allowed on " + policy.env().id(),
          "Ask a person to make schema changes outside GateRook.");
    }
    if (EXECUTE.equals(call.tool())) {
      return Classification.mutation(keyword.isEmpty() ? "Changes data" : keyword + " changes data");
    }
    return READ_KEYWORDS.contains(keyword) ? Classification.read()
        : Classification.mutation((keyword.isEmpty() ? "The statement" : keyword) + " isn't a read");
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String sql = statement(call);
    return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, cfg.name() + " · " + target(cfg),
            call.tool() + " on " + cfg.name())
        .withPayload(JsonNodeFactory.instance.objectNode().put("sql", sql))
        .withImpact(List.of(new ActionPreview.Field("Rows", estimate(cfg, sql) + " (estimate)")));
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      if (secrets.isSet(PASSWORD)) {
        // Resolving the secret is the point of a test: the value never leaves the resolver.
        try (SecretResolver.Secret ignored = secrets.secret(PASSWORD)) {
          // Read once, closed (zeroed) at once.
        }
      }
    } catch (SecretUnavailableException e) {
      return TestResult.failure("secret", e.getMessage(), null);
    }
    ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("serverVersion", "Fake SQL 1.0");
    return TestResult.success(1, "Fake SQL 1.0 (in memory)", cfg.string("/credentials/user"),
        new TestResult.Permissions(TestResult.Level.WRITE, "orders", "orders", List.of(), null)).withMetadata(metadata);
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return HealthResult.healthy(1);
  }

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    Classification c = classify(call, cfg, null);
    if (c.kind() != Classification.Kind.READ && !ctx.approved()) {
      throw ConnectorException.denied("Not approved: " + call.tool() + " changes data.");
    }
    // An approved call runs what the person saw, never the arguments again.
    String sql = ctx.approved() && ctx.approvedPayload().isPresent()
        ? ctx.approvedPayload().get().path("sql").asString(statement(call)) : statement(call);
    Map<Integer, String> table = table(cfg);
    if (READ_KEYWORDS.contains(firstKeyword(sql))) {
      return select(table, sql, Math.min(call.integer("limit", Integer.MAX_VALUE), ctx.limits().maxRows()));
    }
    return change(table, sql);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    tables.remove(connectionId);
  }

  // ── The table ───────────────────────────────────────────────────────────────────────────────────────────────

  /** One table per connection: an edited connection keeps its rows. */
  private Map<Integer, String> table(ConnectionConfig cfg) {
    return tables.computeIfAbsent(cfg.id(), k -> {
      Map<Integer, String> rows = new TreeMap<>();
      rows.put(48213, "pending");
      rows.put(48240, "pending");
      rows.put(48241, "shipped");
      return Collections.synchronizedMap(rows);
    });
  }

  private ToolResult select(Map<Integer, String> table, String sql, int limit) {
    ArrayNode rows = JsonNodeFactory.instance.arrayNode();
    boolean truncated = false;
    if (sql.toUpperCase(Locale.ROOT).contains("FROM ORDERS")) {
      Matcher status = WHERE_STATUS.matcher(sql);
      Matcher id = WHERE_ID.matcher(sql);
      String wantedStatus = status.find() ? status.group(1) : null;
      Integer wantedId = id.find() ? Integer.valueOf(id.group(1)) : null;
      synchronized (table) {
        for (Map.Entry<Integer, String> row : table.entrySet()) {
          if ((wantedStatus != null && !wantedStatus.equals(row.getValue()))
              || (wantedId != null && !wantedId.equals(row.getKey()))) {
            continue;
          }
          if (rows.size() == limit) {
            truncated = true;
            break;
          }
          rows.addArray().add(row.getKey()).add(row.getValue());
        }
      }
      ToolResult result = ToolResult.table(new ToolResult.Table(List.of(column("id", "int4"), column("status", "text")),
          rows, null), null);
      return truncated ? result.truncated("row_limit") : result;
    }
    rows.addArray().add(1);
    return ToolResult.table(new ToolResult.Table(List.of(column("?column?", "int4")), rows, null), null);
  }

  private static ToolResult change(Map<Integer, String> table, String sql) {
    int affected = 0;
    Matcher update = UPDATE.matcher(sql);
    Matcher insert = INSERT.matcher(sql);
    Matcher delete = DELETE.matcher(sql);
    synchronized (table) {
      if (update.matches()) {
        String status = update.group(1);
        affected = table.computeIfPresent(Integer.valueOf(update.group(2)), (k, v) -> status) == null ? 0 : 1;
      } else if (insert.matches()) {
        affected = table.putIfAbsent(Integer.valueOf(insert.group(1)), insert.group(2)) == null ? 1 : 0;
      } else if (delete.matches()) {
        affected = table.remove(Integer.valueOf(delete.group(1))) == null ? 0 : 1;
      }
    }
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("committed", true).put("rowsAffected", affected);
    result.putArray("statements").addObject().put("type", firstKeyword(sql)).put("rowsAffected", affected);
    return ToolResult.json(result, affected + (affected == 1 ? " row" : " rows") + " updated");
  }

  private String estimate(ConnectionConfig cfg, String sql) {
    Matcher id = WHERE_ID.matcher(sql);
    return id.find() && table(cfg).containsKey(Integer.valueOf(id.group(1))) ? "1 row" : "0 rows";
  }

  private static ToolResult.Column column(String name, String type) {
    return new ToolResult.Column(name, type, null, null, null);
  }

  // ── Statements ──────────────────────────────────────────────────────────────────────────────────────────────

  /** The SQL of the call: the argument, or the template's statement with its parameters as SQL literals. */
  static String statement(ToolCall call) {
    if (call.template() != null) {
      String sql = call.template().binding().path("sql").asString("");
      Matcher m = PLACEHOLDER.matcher(sql);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        JsonNode value = call.arguments().get(m.group(1));
        m.appendReplacement(out, Matcher.quoteReplacement(literal(value)));
      }
      m.appendTail(out);
      return out.toString();
    }
    String sql = call.string("sql");
    return sql == null ? "" : sql;
  }

  private static String literal(JsonNode value) {
    if (value == null || value.isNull()) {
      return "NULL";
    }
    if (value.isNumber() || value.isBoolean()) {
      return value.asString();
    }
    return "'" + value.asString().replace("'", "''") + "'";
  }

  static String firstKeyword(String sql) {
    Matcher m = FIRST_WORD.matcher(sql);
    return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "";
  }

  private static String target(ConnectionConfig cfg) {
    String host = cfg.string("/connection/host");
    String database = cfg.string("/connection/database");
    return (host == null ? "memory" : host) + (database == null ? "" : "/" + database);
  }

  // ── Templates ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code sql-query} of the fake: {@code {"name", "description", "sql", "params": {"<p>": {"type": "string"|"integer",
   * "enum": […]}}}} compiles to one tool without a {@code connection} parameter; {@code :p} in the statement is the
   * parameter, bound as a SQL literal. A statement that isn't a read compiles to a tool that needs approval.
   */
  private final class QueryTemplates implements TemplateKind {

    @Override
    public String kind() {
      return "sql-query";
    }

    @Override
    public List<String> connectionTypes() {
      return List.of(TYPE);
    }

    @Override
    public JsonNode definitionSchema() {
      return read(DEFINITION);
    }

    @Override
    public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
      List<String> errors = new ArrayList<>();
      String name = definition.path("name").asString("");
      String sql = definition.path("sql").asString("");
      if (!TOOL_NAME.matcher(name).matches()) {
        errors.add("name must match ^[a-z][a-z0-9_]{0,63}$");
      }
      if (sql.isBlank()) {
        errors.add("sql is missing");
      }
      ObjectNode input = JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false);
      ObjectNode properties = input.putObject("properties");
      ArrayNode required = input.putArray("required");
      JsonNode params = definition.path("params");
      for (Map.Entry<String, JsonNode> p : params.properties()) {
        String type = p.getValue().path("type").asString("string");
        if (!type.equals("string") && !type.equals("integer")) {
          errors.add("parameter " + p.getKey() + " must be a string or an integer");
          continue;
        }
        ObjectNode schema = properties.putObject(p.getKey()).put("type", type);
        if (p.getValue().path("enum").isArray()) {
          schema.set("enum", p.getValue().get("enum").deepCopy());
        }
        if (type.equals("string")) {
          schema.put("maxLength", 200);
        }
        required.add(p.getKey());
      }
      Matcher m = PLACEHOLDER.matcher(sql);
      while (m.find()) {
        if (!params.has(m.group(1))) {
          errors.add(":" + m.group(1) + " isn't a parameter");
        }
      }
      if (!errors.isEmpty()) {
        return new CompileResult(List.of(), List.of(), List.copyOf(errors), List.of(), null);
      }
      boolean reads = READ_KEYWORDS.contains(firstKeyword(sql));
      ToolDefinition.Builder tool = ToolDefinition.builder(name)
          .title(definition.path("title").asString(name))
          .description(definition.path("description").asString("Runs a saved statement on " + runsOn.name() + "."))
          .inputSchema(input)
          .annotations(reads ? Annotations.read() : Annotations.write(false))
          .nature(reads ? Nature.READ : Nature.MUTATION)
          .defaultMode(reads ? Mode.READ_ONLY : Mode.APPROVAL, reads ? Mode.READ_ONLY : Mode.APPROVAL)
          .resultShape(reads ? ResultShape.TABLE : ResultShape.JSON);
      if (reads && readOutputSchema != null) {
        tool.outputSchema(readOutputSchema);
      }
      ObjectNode binding = JsonNodeFactory.instance.objectNode().put("sql", sql);
      return new CompileResult(List.of(new CompiledTool("q", tool.build(), binding)), List.of(), List.of(), List.of(),
          null);
    }

    @Override
    public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
      ToolCall call = new ToolCall("tc_preview", definition.path("name").asString("template"), runsOn.name(),
          arguments, new ToolCall.TemplateBinding("preview", 0, "q",
              JsonNodeFactory.instance.objectNode().put("sql", definition.path("sql").asString(""))));
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", statement(call), runsOn.name() + " · " + target(runsOn),
          call.tool() + " on " + runsOn.name());
    }
  }

  // ── Schemas when no built-in SQL type is there to copy (the core's own tests) ─────────────────────────────

  private static ObjectNode input(String sqlDescription) {
    ObjectNode n = (ObjectNode) read("""
        {"type":"object","additionalProperties":false,"required":["connection","sql"],"properties":{
          "connection":{"type":"string"},"sql":{"type":"string","minLength":1,"maxLength":100000},
          "limit":{"type":"integer","minimum":1,"maximum":1000,"default":50}}}""");
    ((ObjectNode) n.path("properties").path("sql")).put("description", sqlDescription);
    return n;
  }

  private static JsonNode read(String json) {
    return JSON.readTree(json);
  }

  private static final String TABLE_OUTPUT = """
      {"type":"object","required":["columns","rows","rowCount","truncated"],"properties":{
       "columns":{"type":"array","items":{"type":"string"}},"types":{"type":"array","items":{"type":"string"}},
       "rows":{"type":"array","items":{"type":"array"}},"rowCount":{"type":"integer"},"truncated":{"type":"boolean"},
       "truncatedReason":{"type":["string","null"],"enum":["row_limit","size_limit","timeout",null]},
       "elapsedMs":{"type":"integer"},"masked":{"type":"array","items":{"type":"string"}}}}""";

  private static final String CONFIG = """
      {"type":"object","additionalProperties":false,"required":["connection","credentials"],"properties":{
       "connection":{"type":"object","title":"Connection","additionalProperties":false,"required":["host","database"],
         "properties":{"host":{"type":"string","minLength":1,"title":"Host"},
           "port":{"type":"integer","minimum":1,"maximum":65535,"default":5432,"title":"Port"},
           "database":{"type":"string","minLength":1,"title":"Database"}}},
       "credentials":{"type":"object","title":"Credentials","x-gateway-secret":"login","additionalProperties":false,
         "required":["user"],"properties":{"user":{"type":"string","title":"User"},
           "password":{"type":"string","title":"Password","writeOnly":true,"format":"password"}}}}}""";

  private static final String POLICY = """
      {"type":"object","x-policy-section":"sql","additionalProperties":false,"properties":{
       "rowLimit":{"type":"integer","minimum":1,"default":1000,"x-stricter":"min","x-env-default":{"prod":100}},
       "denyDdl":{"type":"boolean","default":false,"x-stricter":"or","x-env-default":{"stage":true,"prod":true}}}}""";

  private static final String DEFINITION = """
      {"type":"object","additionalProperties":false,"required":["name","sql"],"properties":{
       "name":{"type":"string","pattern":"^[a-z][a-z0-9_]{0,63}$"},"title":{"type":"string","maxLength":120},
       "description":{"type":"string","maxLength":1000},"sql":{"type":"string","minLength":1,"maxLength":100000},
       "params":{"type":"object","additionalProperties":{"type":"object","properties":{
         "type":{"enum":["string","integer"]},"enum":{"type":"array"}}}}}}""";
}
