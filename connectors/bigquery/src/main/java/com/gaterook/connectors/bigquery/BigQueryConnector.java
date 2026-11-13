package com.gaterook.connectors.bigquery;

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
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudLocal;
import com.gaterook.connectors.cloudauth.GcpSpec;
import com.gaterook.connectors.support.BuiltIn;
import com.gaterook.connectors.support.OutputSanitizer;
import com.gaterook.connectors.support.SchemaResources;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google BigQuery (docs/bigquery.md): datasets, tables and GoogleSQL reads; {@code bigquery_execute} runs one DML or
 * DDL statement a person approved.
 *
 * <p>{@link #classify} reads the text by GoogleSQL's tokens ({@link GoogleSql}) and picks the route without I/O. The
 * guarantee comes from BigQuery itself before anything runs: a dry run, which is free and runs nothing, names the
 * statement type, the tables it reads and the bytes it would process. A read runs only when BigQuery calls it a
 * {@code SELECT} of datasets on the connection's list within its byte limit, and runs with that limit as
 * {@code maximumBytesBilled}. A change shows the same facts in its approval, runs once with a request id derived from
 * the approval, and is never retried.
 */
public final class BigQueryConnector implements Connector {

  static final String TYPE = "bigquery";
  static final int DEFAULT_LIMIT = 100;
  static final int MAX_PARAMETERS = 100;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern PARAMETER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
  private static final Set<String> PARAMETER_TYPES = Set.of("STRING", "INT64", "FLOAT64", "NUMERIC", "BIGNUMERIC",
      "BOOL", "DATE", "DATETIME", "TIME", "TIMESTAMP");
  /** Statement types of a dry run that never run as a change: data movement, procedures, access, reservations. */
  private static final List<String> NEVER_CHANGES = List.of("SCRIPT", "EXPORT", "LOAD", "CALL", "ROW_ACCESS_POLIC",
      "CAPACITY", "RESERVATION", "ASSIGNMENT");

  private final CloudCredentials cloud;
  private final CloudLocal local;
  private final BigQueryApi api;
  private final ConnectorManifest manifest;

  public BigQueryConnector(CloudCredentials cloud) {
    this(cloud, new CloudLocal(Objects.requireNonNull(cloud, "cloud").endpoints()));
  }

  /** @param local what this computer's gcloud knows, for the form's suggestions (tests: their own files) */
  BigQueryConnector(CloudCredentials cloud, CloudLocal local) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.local = Objects.requireNonNull(local, "local");
    this.api = new BigQueryApi(cloud);
    SchemaResources schemas = SchemaResources.of(BigQueryConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Google BigQuery")
        .icon("database")
        .group("Databases")
        .description("Datasets, tables and GoogleSQL queries with a cost limit. Changes wait for approval.")
        .configSchema(schemas.load("config"))
        .tools(BigQueryTools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("bigquery.googleapis.com", "oauth2.googleapis.com",
            "iamcredentials.googleapis.com"), List.of(),
            List.of("~/.config/gcloud/application_default_credentials.json")))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    BigQuerySettings s;
    try {
      s = BigQuerySettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    String env = policy.env() == null ? "this environment" : policy.env().id();
    switch (call.tool()) {
      case BigQueryTools.LIST_DATASETS -> {
        return Classification.read();
      }
      case BigQueryTools.LIST_TABLES -> {
        String[] d = s.dataset(call.string("dataset"));
        if (d == null || !BigQuerySettings.DATASET.matcher(d[1]).matches()) {
          return Classification.deny(policy.rule("bad-argument"), "Name a dataset: dataset or project.dataset.",
              "Call bigquery_list_datasets to see the datasets.");
        }
        return s.allows(d[0], d[1]) ? Classification.read() : notAllowed(d[0] + "." + d[1], env, policy);
      }
      case BigQueryTools.DESCRIBE_TABLE -> {
        String[] t = s.table(call.string("table"));
        if (t == null || !BigQuerySettings.DATASET.matcher(t[1]).matches() || t[2].isEmpty()) {
          return Classification.deny(policy.rule("bad-argument"), "Name a table: dataset.table or "
              + "project.dataset.table.", "Call bigquery_list_tables to see the tables.");
        }
        return s.allows(t[0], t[1]) ? Classification.read() : notAllowed(t[0] + "." + t[1], env, policy);
      }
      case BigQueryTools.QUERY, BigQueryTools.EXECUTE -> {
        String problem = parameters(call.arguments().path("parameters"));
        if (problem != null) {
          return Classification.deny(policy.rule("bad-argument"), problem, null);
        }
        GoogleSql.Statement st = GoogleSql.read(call.string("sql"));
        boolean read = BigQueryTools.QUERY.equals(call.tool());
        return switch (st.kind()) {
          case UNREADABLE -> Classification.deny(policy.rule("unparsed"), st.problem() + " The gateway doesn't run "
              + "what it can't read.", "Rewrite it in plain GoogleSQL.");
          case FORBIDDEN -> Classification.deny(policy.rule("forbidden"), st.problem(), null);
          case READ -> read ? Classification.read() : Classification.deny(policy.rule("execute-read"), "This is a "
              + "read.", "Use bigquery_query; it doesn't need approval.");
          case DML, DDL -> {
            if (read) {
              yield Classification.deny(policy.rule("query-writes"), "bigquery_query only reads; this is "
                  + st.type() + ".", "Use bigquery_execute: a person approves the exact statement.");
            }
            if (!s.datasets().isEmpty()) {
              String[] target = target(st, s);
              if (target == null) {
                yield Classification.deny(policy.rule("table-not-allowed"), "The gateway can't tell what "
                    + st.type() + " changes, and on " + env + " only some datasets are open to agents.",
                    "Name the table as dataset.table.");
              }
              if (!s.allows(target[0], target[1])) {
                yield notAllowed(target[0] + "." + target[1], env, policy);
              }
            }
            ObjectNode facts = F.objectNode().put("statement", st.type());
            if (st.target() != null) {
              facts.put("target", st.target());
            }
            yield Classification.mutation(st.type() + (st.target() == null ? "" : " on " + st.target())
                + " changes data", facts);
          }
        };
      }
      default -> {
        return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
            null);
      }
    }
  }

  /** The {@code [project, dataset, …]} a change names: a dataset for {@code … SCHEMA}, a table otherwise. */
  private static String[] target(GoogleSql.Statement st, BigQuerySettings s) {
    if (st.target() == null) {
      return null;
    }
    return st.schema() ? s.dataset(st.target()) : s.table(st.target());
  }

  private static Classification notAllowed(String dataset, String env, PolicyView policy) {
    return Classification.deny(policy.rule("table-not-allowed"), dataset + " isn't one of the datasets agents may "
        + "use on " + env + ".", "Call bigquery_list_datasets to see the datasets you can use.");
  }

  /** Why {@code parameters} aren't BigQuery's named scalar parameters, or {@code null}. */
  static String parameters(JsonNode parameters) {
    if (parameters == null || parameters.isMissingNode() || parameters.isNull()) {
      return null;
    }
    if (!parameters.isArray()) {
      return "parameters must be an array of {name, type, value}.";
    }
    if (parameters.size() > MAX_PARAMETERS) {
      return "At most " + MAX_PARAMETERS + " parameters per statement.";
    }
    for (JsonNode p : parameters) {
      if (!PARAMETER.matcher(p.path("name").asString("")).matches()) {
        return "A parameter needs a name such as since (written @since in the SQL).";
      }
      if (!PARAMETER_TYPES.contains(p.path("type").asString(""))) {
        return "Parameter " + p.path("name").asString() + " has a type BigQuery parameters don't take here.";
      }
      JsonNode v = p.path("value");
      if (!(v.isString() || v.isNumber() || v.isBoolean() || v.isNull())) {
        return "Parameter " + p.path("name").asString() + " needs a single value.";
      }
    }
    return null;
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String where = cfg.name() + " · " + cfg.string("/project")
        + (cfg.string("/location") == null ? "" : " · " + cfg.string("/location"));
    JsonNode parameters = call.arguments().path("parameters");
    List<Field> fields = new ArrayList<>();
    if (parameters.isArray() && !parameters.isEmpty()) {
      fields.add(new Field("Parameters", shorten(parameters.toString(), 2_000)));
    }
    if (BigQueryTools.EXECUTE.equals(call.tool())) {
      String sql = Objects.requireNonNullElse(call.string("sql"), "");
      GoogleSql.Statement st = GoogleSql.read(sql);
      ObjectNode payload = F.objectNode().put("sql", sql);
      if (parameters.isArray()) {
        payload.set("parameters", parameters.deepCopy());
      }
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, where, shorten(BigQueryTools.EXECUTE + " on "
              + cfg.name() + ": " + st.type() + (st.target() == null ? "" : " " + st.target()), 120))
          .withFields(fields)
          .withImpact(List.of(new Field("Target", st.target() == null ? "Unknown: not analyzed" : st.target()),
              new Field("Transaction", "One statement; BigQuery applies it whole or not at all"),
              new Field("Retried", "No: a request whose answer is lost is reported as unknown")))
          .withFacets(Map.of("statement", st.type(), "target", Objects.requireNonNullElse(st.target(), "")))
          .withPayload(payload);
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    if (BigQueryTools.QUERY.equals(call.tool())) {
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", Objects.requireNonNullElse(call.string("sql"), ""),
              where, shorten(call.tool() + " on " + cfg.name(), 120))
          .withFields(fields)
          .withPayload(F.objectNode().set("arguments", args))
          .withFacets(Map.of("tool", call.tool()))
          .withImpact(List.of(new Field("Reads", "Rows, no change")));
    }
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), where,
            shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Metadata, no change")));
  }

  /**
   * BigQuery's facts before a person is asked: the dry run's statement type, tables and bytes. A statement BigQuery
   * refuses, a read sent as a change, a script, a dataset off the list or more bytes than the limit is never put to a
   * person.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ActionPreview preview = describeAction(call, cfg);
    boolean change = BigQueryTools.EXECUTE.equals(call.tool());
    boolean query = BigQueryTools.QUERY.equals(call.tool()) && !call.arguments().path("cursor").isString();
    if (!change && !query) {
      return PreparedAction.approval(preview, null);
    }
    BigQuerySettings s = BigQuerySettings.of(cfg);
    DryRun dry;
    try {
      dry = dryRun(new BigQueryApi.Call(cfg, s, secrets, ctx), Objects.requireNonNullElse(call.string("sql"), ""),
          call.arguments().path("parameters"));
      if (change) {
        dry.checkChange(s);
      } else {
        dry.checkRead(s);
      }
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.BAD_INPUT || e.kind() == ConnectorException.Kind.DENIED) {
        return PreparedAction.reject(e.getMessage());
      }
      throw e;
    }
    List<Field> impact = new ArrayList<>();
    impact.add(new Field("Statement type", dry.statementType()));
    impact.add(new Field("Bytes processed", BigQueryApi.gib(dry.bytes()) + " (dry run estimate; limit "
        + BigQueryApi.gib(s.maxBytesBilled()) + ")"));
    if (!dry.tables().isEmpty()) {
      impact.add(new Field("Tables read", shorten(String.join(", ", dry.tables()), 2_000)));
    }
    impact.addAll(preview.impact());
    return PreparedAction.approval(preview.withImpact(impact), null);
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    BigQuerySettings s = BigQuerySettings.of(cfg);
    BigQueryApi.Call c = new BigQueryApi.Call(cfg, s, secrets, ctx);
    return switch (call.tool()) {
      case BigQueryTools.LIST_DATASETS -> listDatasets(c, arguments(call, ctx));
      case BigQueryTools.LIST_TABLES -> listTables(c, arguments(call, ctx));
      case BigQueryTools.DESCRIBE_TABLE -> describeTable(c, arguments(call, ctx));
      case BigQueryTools.QUERY -> query(c, arguments(call, ctx));
      case BigQueryTools.EXECUTE -> change(c);
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

  private ToolResult listDatasets(BigQueryApi.Call c, JsonNode args) throws ConnectorException {
    BigQuerySettings s = c.settings();
    int limit = Math.max(1, Math.min(args.path("limit").asInt(100), 1_000));
    String hash = Cursors.hash(F.objectNode().put("list", "datasets"));
    String token = args.path("cursor").isString() ? Cursors.open(c.ctx(), BigQueryTools.LIST_DATASETS,
        args.get("cursor").asString(), c.cfg().id(), hash).asString(null) : null;
    JsonNode r = api.get(c, BigQueryApi.path("projects", s.project(), "datasets") + BigQueryApi.query("maxResults",
        Integer.toString(limit), "pageToken", token), "BigQuery datasets.list");
    ObjectNode out = F.objectNode().put("project", s.project());
    ArrayNode datasets = out.putArray("datasets");
    List<String> names = new ArrayList<>();
    for (JsonNode d : r.path("datasets")) {
      String project = d.path("datasetReference").path("projectId").asString(s.project());
      String id = d.path("datasetReference").path("datasetId").asString("");
      if (s.allows(project, id)) {
        ObjectNode e = datasets.addObject().put("dataset", id);
        if (d.path("location").isString()) {
          e.put("location", d.get("location").asString());
        }
        names.add(id);
      }
    }
    String next = r.path("nextPageToken").asString(null);
    if (next != null) {
      out.put("nextCursor", Cursors.seal(c.ctx(), BigQueryTools.LIST_DATASETS, c.cfg().id(), hash,
          F.stringNode(next)));
    }
    return ToolResult.json(out, names.isEmpty() ? "No datasets you may use in " + s.project() + "."
        : names.size() + (names.size() == 1 ? " dataset: " : " datasets: ") + String.join(", ", names)
            + (next == null ? "" : "\nMore datasets: pass nextCursor."));
  }

  private ToolResult listTables(BigQueryApi.Call c, JsonNode args) throws ConnectorException {
    BigQuerySettings s = c.settings();
    String[] d = s.dataset(args.path("dataset").asString(null));
    if (d == null || !BigQuerySettings.DATASET.matcher(d[1]).matches()) {
      throw ConnectorException.badInput("Name a dataset: dataset or project.dataset.");
    }
    if (!s.allows(d[0], d[1])) {
      throw ConnectorException.denied(d[0] + "." + d[1] + " isn't one of the datasets of this connection.");
    }
    int limit = Math.max(1, Math.min(args.path("limit").asInt(100), 1_000));
    String hash = Cursors.hash(F.objectNode().put("dataset", d[0] + "." + d[1]));
    String token = args.path("cursor").isString() ? Cursors.open(c.ctx(), BigQueryTools.LIST_TABLES,
        args.get("cursor").asString(), c.cfg().id(), hash).asString(null) : null;
    JsonNode r = api.get(c, BigQueryApi.path("projects", d[0], "datasets", d[1], "tables")
        + BigQueryApi.query("maxResults", Integer.toString(limit), "pageToken", token), "BigQuery tables.list");
    ObjectNode out = F.objectNode().put("dataset", d[0] + "." + d[1]);
    ArrayNode tables = out.putArray("tables");
    List<String> names = new ArrayList<>();
    for (JsonNode t : r.path("tables")) {
      String id = t.path("tableReference").path("tableId").asString("");
      String kind = kind(t.path("type").asString("TABLE"));
      tables.addObject().put("table", id).put("kind", kind);
      names.add(id + ("table".equals(kind) ? "" : " (" + kind + ")"));
    }
    String next = r.path("nextPageToken").asString(null);
    if (next != null) {
      out.put("nextCursor", Cursors.seal(c.ctx(), BigQueryTools.LIST_TABLES, c.cfg().id(), hash, F.stringNode(next)));
    }
    return ToolResult.json(out, names.isEmpty() ? "No tables in " + d[1] + "." : d[1] + ": " + String.join(", ", names)
        + (next == null ? "" : "\nMore tables: pass nextCursor."));
  }

  private static String kind(String type) {
    return type.toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private ToolResult describeTable(BigQueryApi.Call c, JsonNode args) throws ConnectorException {
    BigQuerySettings s = c.settings();
    String[] t = s.table(args.path("table").asString(null));
    if (t == null || !BigQuerySettings.DATASET.matcher(t[1]).matches() || t[2].isEmpty()) {
      throw ConnectorException.badInput("Name a table: dataset.table or project.dataset.table.");
    }
    if (!s.allows(t[0], t[1])) {
      throw ConnectorException.denied(t[0] + "." + t[1] + " isn't one of the datasets of this connection.");
    }
    JsonNode r = api.get(c, BigQueryApi.path("projects", t[0], "datasets", t[1], "tables", t[2]),
        "BigQuery tables.get");
    ObjectNode out = F.objectNode().put("table", t[0] + "." + t[1] + "." + t[2])
        .put("kind", kind(r.path("type").asString("TABLE")));
    if (r.path("description").isString()) {
      out.put("description", Rows.text(r.get("description").asString(), 2_000).asString());
    }
    if (r.has("numRows")) {
      out.set("rows", Rows.integer(r.get("numRows").asString()));
    }
    if (r.has("numBytes")) {
      out.set("bytes", Rows.integer(r.get("numBytes").asString()));
    }
    out.set("columns", fields(r.path("schema").path("fields"), 0));
    if (r.has("timePartitioning")) {
      out.put("partitioning", r.path("timePartitioning").path("type").asString("") + " on "
          + r.path("timePartitioning").path("field").asString("_PARTITIONTIME"));
    } else if (r.has("rangePartitioning")) {
      out.put("partitioning", "RANGE on " + r.path("rangePartitioning").path("field").asString(""));
    }
    if (r.path("requirePartitionFilter").asBoolean(false)
        || r.path("timePartitioning").path("requirePartitionFilter").asBoolean(false)) {
      out.put("requiresPartitionFilter", true);
    }
    if (r.path("clustering").path("fields").isArray()) {
      ArrayNode clustering = out.putArray("clustering");
      r.path("clustering").path("fields").forEach(clustering::add);
    }
    JsonNode view = r.path("view").path("query").isString() ? r.path("view").path("query")
        : r.path("materializedView").path("query");
    if (view.isString()) {
      out.put("viewQuery", Rows.text(view.asString(), 8_000).asString());
    }
    StringBuilder text = new StringBuilder(out.path("table").asString()).append(" (")
        .append(out.path("kind").asString());
    if (out.path("rows").isNumber()) {
      text.append(", ").append(String.format(Locale.US, "%,d", out.get("rows").asLong())).append(" rows");
    }
    text.append("): ");
    List<String> cols = new ArrayList<>();
    out.path("columns").forEach(col -> cols.add(col.path("name").asString() + " " + col.path("type").asString()));
    text.append(String.join(", ", cols));
    if (out.has("partitioning")) {
      text.append(" · partitioned by ").append(out.get("partitioning").asString());
    }
    if (out.has("requiresPartitionFilter")) {
      text.append(" · queries must filter on the partitioning column");
    }
    return ToolResult.json(out, text.toString());
  }

  private static ArrayNode fields(JsonNode fields, int depth) {
    ArrayNode out = F.arrayNode();
    for (JsonNode f : fields) {
      String type = f.path("type").asString("");
      String mode = f.path("mode").asString("NULLABLE");
      ObjectNode col = out.addObject().put("name", f.path("name").asString(""))
          .put("type", "REPEATED".equals(mode) ? "ARRAY<" + type + ">" : type)
          .put("nullable", !"REQUIRED".equals(mode));
      if (f.path("description").isString()) {
        col.put("description", Rows.text(f.get("description").asString(), 500).asString());
      }
      if (f.path("fields").isArray() && depth < Rows.MAX_DEPTH) {
        col.set("fields", fields(f.get("fields"), depth + 1));
      }
    }
    return out;
  }

  /** What BigQuery's dry run said about a statement. */
  record DryRun(String statementType, long bytes, List<String> tables, List<String[]> refs) {

    /** A read: BigQuery calls it a SELECT, of datasets on the list, within the byte limit. */
    void checkRead(BigQuerySettings s) throws ConnectorException {
      if (!"SELECT".equals(statementType)) {
        throw ConnectorException.denied("BigQuery reads this as " + statementType + ", not a SELECT: bigquery_query "
            + "doesn't run it.");
      }
      checkTables(s);
      checkBytes(s);
    }

    /** A change: not a read, a script, data movement or access control; datasets on the list; within the limit. */
    void checkChange(BigQuerySettings s) throws ConnectorException {
      if ("SELECT".equals(statementType)) {
        throw ConnectorException.badInput("This is a read. Use bigquery_query; it doesn't need approval.");
      }
      for (String never : NEVER_CHANGES) {
        if (statementType.contains(never)) {
          throw ConnectorException.denied("BigQuery reads this as " + statementType + ", which GateRook doesn't "
              + "run: scripts, exports, loads, procedures, access policies and reservations stay with people.");
        }
      }
      checkTables(s);
      checkBytes(s);
    }

    private void checkTables(BigQuerySettings s) throws ConnectorException {
      for (String[] ref : refs) {
        if (!s.allows(ref[0], ref[1])) {
          throw ConnectorException.denied("The statement uses " + ref[0] + "." + ref[1] + ", which isn't one of the "
              + "datasets of this connection.");
        }
      }
    }

    private void checkBytes(BigQuerySettings s) throws ConnectorException {
      if (bytes > s.maxBytesBilled()) {
        throw ConnectorException.denied("The statement would process " + BigQueryApi.gib(bytes) + ", more than the "
            + "connection's limit of " + BigQueryApi.gib(s.maxBytesBilled()) + ". Select fewer columns, filter on the "
            + "partitioning column, or ask a person to raise the limit.");
      }
    }
  }

  /** A dry run of {@code sql}: free, runs nothing. */
  private DryRun dryRun(BigQueryApi.Call c, String sql, JsonNode parameters) throws ConnectorException {
    ObjectNode job = F.objectNode();
    ObjectNode configuration = job.putObject("configuration").put("dryRun", true);
    configuration.set("query", queryConfig(sql, parameters));
    ObjectNode reference = job.putObject("jobReference").put("projectId", c.settings().project());
    if (c.settings().location() != null) {
      reference.put("location", c.settings().location());
    }
    JsonNode r = api.post(c, BigQueryApi.path("projects", c.settings().project(), "jobs"), job,
        "BigQuery dry run", false);
    JsonNode q = r.path("statistics").path("query");
    List<String> tables = new ArrayList<>();
    List<String[]> refs = new ArrayList<>();
    for (JsonNode t : q.path("referencedTables")) {
      String[] ref = {t.path("projectId").asString(""), t.path("datasetId").asString(""),
          t.path("tableId").asString("")};
      refs.add(ref);
      tables.add(ref[0] + "." + ref[1] + "." + ref[2]);
    }
    for (String target : List.of("ddlTargetTable", "ddlTargetDataset")) {
      JsonNode t = q.path(target);
      if (t.isObject()) {
        refs.add(new String[] {t.path("projectId").asString(""), t.path("datasetId").asString(""),
            t.path("tableId").asString("")});
      }
    }
    long bytes = Rows.count(q.has("totalBytesProcessed") ? q.get("totalBytesProcessed")
        : r.path("statistics").path("totalBytesProcessed"));
    return new DryRun(q.path("statementType").asString("UNKNOWN"), bytes, List.copyOf(tables), List.copyOf(refs));
  }

  private static ObjectNode queryConfig(String sql, JsonNode parameters) {
    ObjectNode query = F.objectNode().put("query", sql).put("useLegacySql", false);
    if (parameters != null && parameters.isArray() && !parameters.isEmpty()) {
      query.put("parameterMode", "NAMED");
      ArrayNode out = query.putArray("queryParameters");
      for (JsonNode p : parameters) {
        ObjectNode q = out.addObject().put("name", p.path("name").asString());
        q.putObject("parameterType").put("type", p.path("type").asString());
        JsonNode v = p.path("value");
        ObjectNode value = q.putObject("parameterValue");
        // A NULL parameter is a value without "value".
        if (!v.isNull() && !v.isMissingNode()) {
          value.put("value", v.isString() ? v.asString() : v.toString());
        }
      }
    }
    return query;
  }

  private ToolResult query(BigQueryApi.Call c, JsonNode args) throws ConnectorException {
    ExecContext ctx = c.ctx();
    BigQuerySettings s = c.settings();
    String sql = args.path("sql").asString("");
    GoogleSql.Statement st = GoogleSql.read(sql);
    if (st.kind() != GoogleSql.Kind.READ) {
      throw ConnectorException.denied("bigquery_query only runs one statement the gateway reads as a read.");
    }
    String problem = parameters(args.path("parameters"));
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    int limit = Math.max(1, Math.min(args.path("limit").asInt(DEFAULT_LIMIT), ctx.limits().maxRows()));
    ObjectNode request = F.objectNode().put("sql", sql);
    request.set("parameters", args.path("parameters").isArray() ? args.get("parameters") : F.arrayNode());
    String hash = Cursors.hash(request);
    JsonNode page;
    String jobId;
    String location;
    long start = 0;
    if (args.path("cursor").isString()) {
      JsonNode at = Cursors.open(ctx, BigQueryTools.QUERY, args.get("cursor").asString(), c.cfg().id(), hash);
      jobId = at.path("job").asString("");
      location = at.path("location").asString(null);
      start = Math.max(0, at.path("index").asLong(0));
      page = results(c, jobId, location, start, limit);
    } else {
      DryRun dry = dryRun(c, sql, args.path("parameters"));
      dry.checkRead(s);
      ObjectNode body = queryConfig(sql, args.path("parameters"));
      body.put("maxResults", limit).put("timeoutMs", waitMs(ctx)).put("maximumBytesBilled",
          Long.toString(s.maxBytesBilled())).put("useQueryCache", true);
      body.putObject("formatOptions").put("useInt64Timestamp", true);
      body.putObject("labels").put("gaterook", "read");
      if (s.location() != null) {
        body.put("location", s.location());
      }
      page = api.post(c, BigQueryApi.path("projects", s.project(), "queries"), body, "BigQuery query", false);
      jobId = page.path("jobReference").path("jobId").asString("");
      location = page.path("jobReference").path("location").asString(s.location());
    }
    if (!page.path("jobComplete").asBoolean(false)) {
      page = complete(c, jobId, location, start, limit);
    }
    ObjectNode facts = F.objectNode().put("job", jobId);
    if (page.has("totalBytesProcessed")) {
      facts.put("bytesProcessed", Rows.count(page.get("totalBytesProcessed")));
    }
    if (page.has("cacheHit")) {
      facts.put("cacheHit", page.get("cacheHit").asBoolean(false));
    }
    facts.put("totalRows", Rows.count(page.path("totalRows")));
    return table(c, page, limit, hash, jobId, location, start, facts);
  }

  /** How long one request waits for a query: what the call has left, at most 20 s, leaving time to answer. */
  private static long waitMs(ExecContext ctx) {
    long left = ctx.remaining().minus(Duration.ofSeconds(1)).toMillis();
    return Math.max(1_000, Math.min(20_000, left));
  }

  /** {@code jobs.getQueryResults}: rows of a finished job from {@code start}, or a wait for a running one. */
  private JsonNode results(BigQueryApi.Call c, String jobId, String location, long start, int limit)
      throws ConnectorException {
    if (jobId == null || jobId.isBlank()) {
      throw ConnectorException.badInput("The cursor names no query job. Start again without cursor.");
    }
    return api.get(c, BigQueryApi.path("projects", c.settings().project(), "queries", jobId) + BigQueryApi.query(
        "maxResults", Integer.toString(limit), "startIndex", Long.toString(start), "location", location, "timeoutMs",
        Long.toString(waitMs(c.ctx())), "formatOptions.useInt64Timestamp", "true"), "BigQuery getQueryResults");
  }

  /** Waits for a running query until the call's deadline, then cancels its job. */
  private JsonNode complete(BigQueryApi.Call c, String jobId, String location, long start, int limit)
      throws ConnectorException {
    while (c.ctx().remaining().compareTo(Duration.ofSeconds(2)) > 0) {
      JsonNode page = results(c, jobId, location, start, limit);
      if (page.path("jobComplete").asBoolean(false)) {
        return page;
      }
    }
    try {
      api.post(c, BigQueryApi.path("projects", c.settings().project(), "jobs", jobId, "cancel")
          + BigQueryApi.query("location", location), F.objectNode(), "BigQuery jobs.cancel", false);
    } catch (ConnectorException e) {
      // The job stops at its own limits; the call ends either way.
    }
    throw ConnectorException.timeout("The query didn't finish within the call's time, so GateRook cancelled job "
        + jobId + ". Narrow it, or filter on the partitioning column.");
  }

  /**
   * One page of rows within the row limit and the response budget. The cursor names the job and the next row's
   * index, so a page cut at the response size goes on exactly where it stopped.
   */
  private static ToolResult table(BigQueryApi.Call c, JsonNode page, int limit, String hash, String jobId,
      String location, long start, ObjectNode facts) {
    ExecContext ctx = c.ctx();
    JsonNode schema = page.path("schema");
    List<ToolResult.Column> columns = Rows.columns(schema);
    ArrayNode rows = F.arrayNode();
    long budget = Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
    long used = 0;
    String truncated = null;
    for (JsonNode row : page.path("rows")) {
      if (rows.size() >= limit) {
        break;
      }
      ArrayNode cells = Rows.row(row, schema, ctx.limits().maxCellChars());
      used += cells.toString().length();
      if (used > budget && !rows.isEmpty()) {
        truncated = "size_limit";
        break;
      }
      rows.add(cells);
    }
    long shown = start + rows.size();
    String next = null;
    if (shown < Rows.count(page.path("totalRows")) && !jobId.isBlank()) {
      ObjectNode at = F.objectNode().put("job", jobId).put("index", shown);
      if (location != null) {
        at.put("location", location);
      }
      next = Cursors.seal(ctx, BigQueryTools.QUERY, c.cfg().id(), hash, at);
    }
    ToolResult result = ToolResult.table(new ToolResult.Table(List.copyOf(columns), rows, next), facts);
    return truncated == null ? result : result.truncated(truncated);
  }

  private ToolResult change(BigQueryApi.Call c) throws ConnectorException {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: bigquery_execute changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    String sql = payload.path("sql").asString("");
    GoogleSql.Statement st = GoogleSql.read(sql);
    if (st.kind() != GoogleSql.Kind.DML && st.kind() != GoogleSql.Kind.DDL) {
      throw ConnectorException.denied("bigquery_execute runs one analyzed DML or DDL statement.");
    }
    BigQuerySettings s = c.settings();
    if (!s.datasets().isEmpty()) {
      String[] target = target(st, s);
      if (target == null || !s.allows(target[0], target[1])) {
        throw ConnectorException.denied("The statement changes a dataset that isn't one of this connection's.");
      }
    }
    String problem = parameters(payload.path("parameters"));
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    dryRun(c, sql, payload.path("parameters")).checkChange(s);
    ObjectNode body = queryConfig(sql, payload.path("parameters"));
    body.put("timeoutMs", waitMs(ctx)).put("maximumBytesBilled", Long.toString(s.maxBytesBilled()));
    body.putObject("labels").put("gaterook", "change");
    if (s.location() != null) {
      body.put("location", s.location());
    }
    // BigQuery runs one request id once: the same approval can't run its statement twice.
    body.put("requestId", requestId(ctx));
    JsonNode r = api.post(c, BigQueryApi.path("projects", s.project(), "queries"), body, "BigQuery change", true);
    String jobId = r.path("jobReference").path("jobId").asString("");
    String location = r.path("jobReference").path("location").asString(s.location());
    String affected = r.path("numDmlAffectedRows").asString(null);
    if (!r.path("jobComplete").asBoolean(false)) {
      affected = awaitChange(c, jobId, location);
    }
    ObjectNode out = F.objectNode().put("committed", true).put("statement", st.type()).put("job", jobId);
    if (affected != null) {
      out.set("rowsAffected", Rows.integer(affected));
    }
    String text = affected == null ? st.type() + " done." : affected + (affected.equals("1") ? " row" : " rows")
        + " affected by " + st.type() + ".";
    return ToolResult.json(out, text);
  }

  /** BigQuery's request id for an approved change: a UUID of the approval's id (at most 36 ASCII characters). */
  static String requestId(ExecContext ctx) {
    String id = ctx.approvalId().orElse(ctx.callId());
    return UUID.nameUUIDFromBytes(("gaterook-bigquery|" + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * Waits for a change job to finish: the rows it changed, or {@code null} when BigQuery reports none. A failed job
   * changed nothing, as BigQuery applies a statement whole or not at all; a job whose state can't be learned leaves
   * the outcome unknown.
   */
  private String awaitChange(BigQueryApi.Call c, String jobId, String location) throws ConnectorException {
    ExecContext ctx = c.ctx();
    try {
      while (ctx.remaining().compareTo(Duration.ofSeconds(2)) > 0) {
        JsonNode r = results(c, jobId, location, 0, 1);
        if (r.path("jobComplete").asBoolean(false)) {
          return r.path("numDmlAffectedRows").asString(null);
        }
      }
    } catch (ConnectorException e) {
      // getQueryResults answers a failed job with its error: ask the job itself what happened.
    }
    JsonNode job;
    try {
      job = api.get(c, BigQueryApi.path("projects", c.settings().project(), "jobs", jobId)
          + BigQueryApi.query("location", location), "BigQuery jobs.get");
    } catch (ConnectorException e) {
      throw ConnectorException.outcomeUnknown("GateRook lost track of change job " + jobId + ", so whether it ran "
          + "is not known. Check the job in the BigQuery console before retrying.", e);
    }
    if (!"DONE".equals(job.path("status").path("state").asString(""))) {
      throw ConnectorException.outcomeUnknown("BigQuery is still running the change as job " + jobId + ". Check the "
          + "job in the BigQuery console before retrying.", null);
    }
    JsonNode error = job.path("status").path("errorResult");
    if (error.isObject()) {
      throw ConnectorException.resourceError("BigQuery stopped the change (job " + jobId + "): " + OutputSanitizer
          .sanitize(BigQueryApi.firstLine(error.path("message").asString(""))) + ". Nothing changed.", null);
    }
    return job.path("statistics").path("query").path("numDmlAffectedRows").asString(null);
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    BigQuerySettings s;
    try {
      s = BigQuerySettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("bigquery.settings", e.getMessage(), null);
    }
    BigQueryApi.Call c = new BigQueryApi.Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    int visible = 0;
    boolean more;
    List<String> first = new ArrayList<>();
    long started = System.nanoTime();
    try {
      JsonNode r = api.get(c, BigQueryApi.path("projects", s.project(), "datasets") + BigQueryApi.query("maxResults",
          "200"), "BigQuery datasets.list");
      for (JsonNode d : r.path("datasets")) {
        String id = d.path("datasetReference").path("datasetId").asString("");
        if (s.allows(d.path("datasetReference").path("projectId").asString(s.project()), id)) {
          visible++;
          if (first.size() < 5) {
            first.add(id);
          }
        }
      }
      more = r.path("nextPageToken").isString();
    } catch (ConnectorException e) {
      return TestResult.failure(e.kind() == ConnectorException.Kind.RESOURCE_UNAVAILABLE ? "bigquery.credentials"
          : "bigquery.datasets", e.getMessage(), null);
    }
    long latency = ms(started);
    checks.add(new TestResult.Check("datasets", true, latency, visible + (more ? "+" : "")
        + (visible == 1 ? " dataset" : " datasets") + " agents may use"
        + (first.isEmpty() ? "" : ": " + String.join(", ", first) + (visible > first.size() || more ? ", …" : ""))));
    long dryStarted = System.nanoTime();
    try {
      dryRun(c, "SELECT 1", null);
      checks.add(new TestResult.Check("jobs", true, ms(dryStarted), "Can run query jobs in " + s.project()
          + " (dry run)"));
    } catch (ConnectorException e) {
      return TestResult.failure("bigquery.jobs", "The credentials can list datasets but not run queries in "
          + s.project() + ": " + e.getMessage(), null).withChecks(checks);
    }
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        "GoogleSQL SELECT of " + (s.datasets().isEmpty() ? "every dataset the credentials may read"
            : "the listed datasets"), null, List.of(), F.objectNode().put("maxBytesBilled", s.maxBytesBilled()));
    TestResult result = TestResult.success(latency, "Google BigQuery · " + s.project(), s.credentials().identity(),
        permissions).withChecks(checks).withMetadata(F.objectNode().put("serverVersion", "BigQuery")
        .put("project", s.project()));
    for (Hint h : hints(cfg.env(), s, visible)) {
      result = result.withHint(h);
    }
    return result;
  }

  /** The project from this computer or the key; signed in, the datasets, their locations and the projects. */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return BigQuerySuggestions.suggest(cfg, secrets, ctx, scope, api, cloud, local);
  }

  static List<Hint> hints(Env env, BigQuerySettings s, int visible) {
    List<Hint> out = new ArrayList<>();
    if (env != Env.DEV && s.credentials().source() == GcpSpec.Source.ADC && s.credentials().impersonate() == null) {
      out.add(new Hint(Severity.WARNING, "bigquery.personal-credentials", "/credentials/impersonate", "Calls use "
          + "your own Google sign-in. On " + env.id() + ", impersonate a service account with "
          + "roles/bigquery.dataViewer on the datasets and roles/bigquery.jobUser on the project."));
    }
    out.add(new Hint(Severity.INFO, "bigquery.read-only-boundary", null, "BigQuery has no read-only session: GateRook "
        + "runs only what BigQuery's dry run calls a SELECT, and the credentials' roles are the boundary. Readers "
        + "need roles/bigquery.dataViewer, not dataEditor."));
    if (visible == 0) {
      out.add(new Hint(Severity.WARNING, "bigquery.no-datasets", "/datasets", "The credentials see no dataset "
          + (s.datasets().isEmpty() ? "" : "matching the connection's list ") + "in " + s.project() + "."));
    }
    return out;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      BigQuerySettings s = BigQuerySettings.of(cfg);
      long started = System.nanoTime();
      api.get(new BigQueryApi.Call(cfg, s, secrets, ctx), BigQueryApi.path("projects", s.project(), "datasets")
          + BigQueryApi.query("maxResults", "1"), "BigQuery datasets.list");
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode().put("project", cfg.string("/project"));
    if (cfg.string("/location") != null) {
      out.put("location", cfg.string("/location"));
    }
    List<String> datasets = cfg.strings("/datasets");
    if (datasets.isEmpty()) {
      out.put("datasets", "Every dataset the credentials may read");
    } else {
      ArrayNode a = out.putArray("datasets");
      datasets.forEach(a::add);
    }
    out.put("bytesPerStatement", BigQueryApi.gib(BigQuerySettings.maxBytes(cfg)));
    out.put("reads", "One GoogleSQL SELECT per bigquery_query, checked by a dry run; name tables as dataset.table "
        + "and pass values as named parameters (@name)");
    out.put("changes", "One DML or DDL statement per bigquery_execute, after approval");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call bigquery_describe_table with table " + object + ".");
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
