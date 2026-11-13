package com.gaterook.connectors.athena;

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
import com.gaterook.connectors.athena.AthenaApi.Call;
import com.gaterook.connectors.athena.AthenaSql.Kind;
import com.gaterook.connectors.athena.AthenaSql.Ref;
import com.gaterook.connectors.athena.AthenaSql.Role;
import com.gaterook.connectors.cloudauth.CloudCredentials;
import com.gaterook.connectors.cloudauth.CloudLocal;
import com.gaterook.connectors.support.BuiltIn;
import com.gaterook.connectors.support.OutputSanitizer;
import com.gaterook.connectors.support.SchemaResources;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Amazon Athena (docs/athena.md): data catalogs, databases, tables and SQL reads in one workgroup;
 * {@code athena_execute} runs one statement a person approved.
 *
 * <p>Athena has no read-only session. {@link #classify} reads the text by Athena's tokens ({@link AthenaSql}) and picks
 * the route without I/O. Before a query runs, Athena plans it: {@code EXPLAIN (TYPE IO, FORMAT JSON)}, which scans
 * nothing and costs nothing, must name no table it writes and only tables of the connection's databases
 * ({@link IoPlan}). {@code SHOW} and {@code DESCRIBE} run in Athena's Hive engine, which no plan covers: the names they
 * show are checked instead. A change is never planned itself, as Trino begins a write in its catalog while planning
 * it: a change Athena runs with Trino is planned by a read of what it reads, and writes the table its own name says.
 * With an IAM role, AWS enforces the rest: each call assumes the role with a session policy that allows reading and
 * writing query results to the workgroup's results location, and no other change ({@link AthenaSettings#readPolicy});
 * an approved change gets Glue changes in its own database. The workgroup's per-query data limit is the cost
 * boundary; GateRook stops a query at the call's deadline.
 */
public final class AthenaConnector implements Connector {

  static final String TYPE = "athena";
  static final int DEFAULT_LIMIT = 100;
  static final int LIST_LIMIT = 100;
  /** Time kept back from the deadline to stop the query and answer. */
  static final Duration CANCEL_RESERVE = Duration.ofMillis(1_000);
  static final Duration WORKGROUP_TTL = Duration.ofMinutes(10);
  /** Substatement types Athena reports for a statement that changed something: never the answer of a read. */
  private static final Pattern WRITE_SUBSTATEMENT = Pattern.compile("(?i)(INSERT|UPDATE|DELETE|MERGE|UNLOAD|OPTIMIZE"
      + "|VACUUM|CREATE|DROP|ALTER|MSCK|REPAIR).*");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final CloudCredentials cloud;
  private final AthenaApi api;
  private final ConnectorManifest manifest;
  private final Map<String, Cached> workgroups = new ConcurrentHashMap<>();
  /** What the setup helpers read of this computer: AWS profiles and regions. */
  private final CloudLocal local;

  public AthenaConnector(CloudCredentials cloud) {
    this(cloud, cloud == null ? null : new CloudLocal(cloud.endpoints()));
  }

  /** {@code local}: the profiles and regions the setup helpers read (tests: their own files and environment). */
  AthenaConnector(CloudCredentials cloud, CloudLocal local) {
    this.cloud = Objects.requireNonNull(cloud, "cloud");
    this.local = Objects.requireNonNull(local, "local");
    this.api = new AthenaApi(cloud);
    SchemaResources schemas = SchemaResources.of(AthenaConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Amazon Athena")
        .icon("database")
        .group("Databases")
        .description("Data catalogs, databases and SQL reads within a workgroup's limits. Changes wait for "
            + "approval.")
        .configSchema(schemas.load("config"))
        .tools(AthenaTools.definitions(schemas))
        .access(new ConnectorManifest.RequiredAccess(List.of("athena.*.amazonaws.com", "sts.*.amazonaws.com"),
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
    AthenaSettings s;
    try {
      s = AthenaSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    String env = policy.env() == null ? "this environment" : policy.env().id();
    switch (call.tool()) {
      case AthenaTools.LIST_CATALOGS -> {
        return Classification.read();
      }
      case AthenaTools.LIST_DATABASES -> {
        String catalog = Objects.requireNonNullElse(blank(call.string("catalog")), s.catalog());
        if (!AthenaSettings.CATALOG.matcher(catalog).matches()) {
          return Classification.deny(policy.rule("bad-argument"), catalog + " isn't a data catalog name.",
              "Call athena_list_catalogs to see the catalogs.");
        }
        return s.allowsCatalog(catalog) ? Classification.read() : notAllowed(catalog, env, policy);
      }
      case AthenaTools.LIST_TABLES -> {
        String catalog = Objects.requireNonNullElse(blank(call.string("catalog")), s.catalog());
        String database = Objects.requireNonNullElse(blank(call.string("database")), Objects.requireNonNullElse(
            s.database(), ""));
        if (!AthenaSettings.CATALOG.matcher(catalog).matches() || !AthenaSettings.DATABASE.matcher(database)
            .matches()) {
          return Classification.deny(policy.rule("bad-argument"), "Name a database (letters, digits, _ and -).",
              "Call athena_list_databases to see the databases.");
        }
        return s.allows(catalog, database) ? Classification.read() : notAllowed(catalog + "." + database, env,
            policy);
      }
      case AthenaTools.DESCRIBE_TABLE -> {
        String[] t = table(call.string("table"), s);
        if (t == null) {
          return Classification.deny(policy.rule("bad-argument"), "Name a table: database.table, or "
              + "catalog.database.table.", "Call athena_list_tables to see the tables.");
        }
        return s.allows(t[0], t[1]) ? Classification.read() : notAllowed(t[0] + "." + t[1], env, policy);
      }
      case AthenaTools.QUERY, AthenaTools.EXECUTE -> {
        AthenaSql.Statement st = AthenaSql.read(call.string("sql"));
        boolean read = AthenaTools.QUERY.equals(call.tool());
        return switch (st.kind()) {
          case UNREADABLE -> Classification.deny(policy.rule("unparsed"), st.problem() + " The gateway doesn't run "
              + "what it can't read.", "Rewrite it in plain Athena SQL.");
          case FORBIDDEN -> Classification.deny(policy.rule("forbidden"), st.problem(), null);
          case READ -> {
            if (!read) {
              yield Classification.deny(policy.rule("execute-read"), "This is a read.", "Use athena_query; it "
                  + "doesn't need approval.");
            }
            String problem = refs(st, s, env);
            yield problem == null ? Classification.read()
                : Classification.deny(policy.rule("database-not-allowed"), problem, "Call athena_list_databases to "
                    + "see the databases you can use.");
          }
          case CHANGE -> {
            if (read) {
              yield Classification.deny(policy.rule("query-writes"), "athena_query only reads; this is " + st.type()
                  + ".", "Use athena_execute: a person approves the exact statement.");
            }
            String problem = refs(st, s, env);
            if (problem != null) {
              yield Classification.deny(policy.rule("database-not-allowed"), problem, "Name the table as "
                  + "database.table in a database of the connection.");
            }
            ObjectNode facts = F.objectNode().put("statement", st.type()).put("target", st.target().name().text());
            yield Classification.mutation(st.type() + " on " + st.target().name().text() + " changes data", facts);
          }
        };
      }
      default -> {
        return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
            null);
      }
    }
  }

  private static Classification notAllowed(String what, String env, PolicyView policy) {
    return Classification.deny(policy.rule("database-not-allowed"), what + " isn't one of the databases agents may "
        + "use on " + env + ".", "Call athena_list_databases to see the databases you can use.");
  }

  /**
   * Why the statement's names leave the connection's databases, or {@code null}: its target, what a {@code SHOW} or
   * {@code DESCRIBE} names, the catalogs of qualified functions, and a listing of every database.
   */
  static String refs(AthenaSql.Statement st, AthenaSettings s, String env) {
    if (s.databases().isEmpty()) {
      return null;
    }
    if (st.type().equals("SHOW DATABASES") || st.type().equals("SHOW SCHEMAS")) {
      return st.type() + " lists every database, and on " + env + " only some are open to agents.";
    }
    List<Ref> all = new ArrayList<>(st.refs());
    if (st.target() != null) {
      all.add(st.target());
    }
    for (Ref ref : all) {
      String catalog = s.catalogOf(ref);
      if (ref.role() == Role.CATALOG || ref.role() == Role.FUNCTION) {
        if (!s.allowsCatalog(catalog)) {
          return (ref.role() == Role.FUNCTION ? "The function " + ref.name().text() + " is in catalog " : "")
              + catalog + ", which isn't one of the catalogs agents may use on " + env + ".";
        }
        continue;
      }
      String database = s.databaseOf(ref);
      if (database == null) {
        return "The gateway can't tell the database of " + ref.name().text() + ", and on " + env + " only some "
            + "databases are open to agents. Name it as database.table.";
      }
      if (!s.allows(catalog, database)) {
        return catalog + "." + database + " isn't one of the databases agents may use on " + env + ".";
      }
    }
    return null;
  }

  /** {@code [catalog, database, table]} of a table name an agent gave, with the defaults; or {@code null}. */
  static String[] table(String written, AthenaSettings s) {
    AthenaSql.Name name = AthenaSql.qualified(written);
    if (name == null) {
      return null;
    }
    List<String> p = name.parts();
    String catalog = p.size() == 3 ? p.get(0) : s.catalog();
    String database = p.size() >= 2 ? p.get(p.size() - 2) : s.database();
    if (database == null || !AthenaSettings.DATABASE.matcher(database).matches()) {
      return null;
    }
    return new String[] {catalog, database.toLowerCase(Locale.ROOT), p.getLast()};
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String where = cfg.name() + " · " + cfg.string("/region") + " · "
        + Objects.requireNonNullElse(cfg.string("/workgroup"), "primary")
        + (cfg.string("/database") == null ? "" : " · " + Objects.requireNonNullElse(cfg.string("/catalog"),
            AthenaSettings.DEFAULT_CATALOG) + "." + cfg.string("/database"));
    if (AthenaTools.EXECUTE.equals(call.tool())) {
      AthenaSql.Statement st = AthenaSql.read(call.string("sql"));
      String sql = st.sql() != null ? st.sql() : Objects.requireNonNullElse(call.string("sql"), "");
      String target = st.target() == null ? null : st.target().name().text();
      List<String> warnings = new ArrayList<>();
      if (cfg.string("/credentials/roleArn") == null || "none".equals(cfg.string("/credentials/sessionPolicy"))) {
        warnings.add("AWS doesn't limit this change to " + (target == null ? "its database" : target) + ": the "
            + "credentials' own permissions apply.");
      }
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, where, shorten(AthenaTools.EXECUTE + " on "
              + cfg.name() + ": " + st.type() + (target == null ? "" : " " + target), 120))
          .withImpact(List.of(new Field("Target", target == null ? "Unknown: not analyzed" : target),
              new Field("Atomicity", "Iceberg tables take a change whole or not at all; a failed INSERT or CREATE "
                  + "TABLE AS on a Hive table can leave data files"),
              new Field("Retried", "No: started once with a request token of this approval")))
          .withWarnings(warnings)
          .withFacets(Map.of("statement", st.type(), "target", Objects.requireNonNullElse(target, "")))
          .withPayload(F.objectNode().put("sql", sql));
    }
    ObjectNode args = call.arguments().deepCopy();
    if (AthenaTools.QUERY.equals(call.tool())) {
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", Objects.requireNonNullElse(call.string("sql"), ""),
              where, shorten(call.tool() + " on " + cfg.name(), 120))
          .withPayload(F.objectNode().set("arguments", args))
          .withFacets(Map.of("tool", call.tool()))
          .withImpact(List.of(new Field("Reads", "Rows, no change; Athena charges for the data scanned")));
    }
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), where,
            shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Metadata, no change")));
  }

  /**
   * Athena's plan before a person is asked. A change Athena runs with Trino is never planned itself: Trino would begin
   * the write in its catalog while planning it. GateRook plans the read of what the change reads ({@link AthenaSql})
   * and shows the tables it reads and the table the statement's own name says it writes. A statement whose read Athena
   * refuses, a read sent as a change, or one that reaches past the connection's databases is never put to a person.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ActionPreview preview = describeAction(call, cfg);
    boolean change = AthenaTools.EXECUTE.equals(call.tool());
    if (!change && !AthenaTools.QUERY.equals(call.tool())) {
      return PreparedAction.approval(preview, null);
    }
    AthenaSettings s = AthenaSettings.of(cfg);
    Call c = new Call(cfg, s, secrets, ctx);
    AthenaSql.Statement st = AthenaSql.read(call.string("sql"));
    List<Field> impact = new ArrayList<>();
    try {
      if (change) {
        if (!st.change()) {
          return PreparedAction.reject(st.read() ? "This is a read. Use athena_query; it doesn't need approval."
              : Objects.requireNonNullElse(st.problem(), "athena_execute runs one analyzed change."));
        }
        String problem = refs(st, s, ctx.env() == null ? "this environment" : ctx.env().id());
        if (problem != null) {
          return PreparedAction.reject(problem);
        }
        impact.add(new Field("Statement", st.type()));
        if (st.planned() != null) {
          IoPlan plan = plan(c, st.planned(), readPolicy(c));
          checkChange(plan, s);
          impact.add(new Field("Writes", written(st.target(), s)));
          impact.add(new Field("Reads", plan.inputs().isEmpty() ? "No table, by Athena's plan"
              : shorten(String.join(", ", plan.inputNames()), 2_000)));
        } else {
          impact.add(new Field("Athena's plan", "None: Athena checks " + st.type() + " when it runs it"));
        }
      } else {
        if (!st.read()) {
          return PreparedAction.reject(Objects.requireNonNullElse(st.problem(), "athena_query only reads."));
        }
        if (st.planned() != null) {
          IoPlan plan = plan(c, st.planned(), readPolicy(c));
          checkRead(plan, s);
          impact.add(new Field("Reads", plan.inputs().isEmpty() ? "No table, by Athena's plan"
              : shorten(String.join(", ", plan.inputNames()), 2_000)));
        }
      }
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.BAD_INPUT || e.kind() == ConnectorException.Kind.DENIED
          || e.kind() == ConnectorException.Kind.RESOURCE_ERROR) {
        return PreparedAction.reject(e.getMessage());
      }
      throw e;
    }
    Workgroup wg = workgroupOrNull(c);
    if (wg != null) {
      impact.add(new Field("Data limit", wg.cutoff() == null ? "None: the workgroup sets no per-query limit"
          : gib(wg.cutoff()) + " per query (workgroup " + s.workgroup() + ")"));
    }
    impact.addAll(preview.impact());
    return PreparedAction.approval(preview.withImpact(impact), null);
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── The workgroup ───────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The workgroup's own settings, as {@code GetWorkGroup} reads them.
   *
   * @param outputLocation where results go, or {@code null}
   * @param enforced       whether its settings override a client's
   * @param cutoff         its per-query data limit in bytes, or {@code null}
   * @param managed        whether Athena keeps results itself
   */
  record Workgroup(String name, String state, String outputLocation, boolean enforced, Long cutoff, boolean managed,
      String engine) {

    static Workgroup of(JsonNode r) {
      JsonNode wg = r.path("WorkGroup");
      JsonNode cfg = wg.path("Configuration");
      JsonNode cutoff = cfg.path("BytesScannedCutoffPerQuery");
      return new Workgroup(wg.path("Name").asString(""), wg.path("State").asString(""),
          cfg.path("ResultConfiguration").path("OutputLocation").asString(null),
          cfg.path("EnforceWorkGroupConfiguration").asBoolean(false),
          cutoff.canConvertToLong() ? cutoff.asLong() : null,
          cfg.path("ManagedQueryResultsConfiguration").path("Enabled").asBoolean(false),
          cfg.path("EngineVersion").path("EffectiveEngineVersion").asString(
              cfg.path("EngineVersion").path("SelectedEngineVersion").asString("")));
    }

    /** Where Athena writes this connection's results: {@code null} when Athena keeps them itself. */
    String results(AthenaSettings s) {
      if (managed && (enforced || s.outputLocation() == null)) {
        return null;
      }
      if (enforced) {
        return outputLocation;
      }
      return s.outputLocation() != null ? s.outputLocation() : outputLocation;
    }
  }

  private record Cached(long version, Workgroup workgroup, Instant at) {}

  /** The workgroup's settings, read with a session policy that allows only {@code athena:GetWorkGroup}. */
  private Workgroup workgroup(Call c, boolean fresh) throws ConnectorException {
    Cached cached = workgroups.get(c.cfg().id());
    Instant now = c.ctx().clock().instant();
    if (!fresh && cached != null && cached.version() == c.cfg().version()
        && cached.at().plus(WORKGROUP_TTL).isAfter(now)) {
      return cached.workgroup();
    }
    Workgroup wg = Workgroup.of(api.call(c, "GetWorkGroup", F.objectNode().put("WorkGroup",
        c.settings().workgroup()), c.settings().inspectPolicy(), false));
    workgroups.put(c.cfg().id(), new Cached(c.cfg().version(), wg, now));
    return wg;
  }

  /** The workgroup for an approval's facts; none when it can't be read. */
  private Workgroup workgroupOrNull(Call c) {
    try {
      return workgroup(c, false);
    } catch (ConnectorException e) {
      return null;
    }
  }

  /** The session policy of reads: with a generated one, it needs the workgroup's results location. */
  private String readPolicy(Call c) throws ConnectorException {
    AthenaSettings s = c.settings();
    if (!s.generatedPolicy() || s.credentials().roleArn() == null) {
      return null;
    }
    return s.readPolicy(workgroup(c, false).results(s));
  }

  private String writePolicy(Call c, String database) throws ConnectorException {
    AthenaSettings s = c.settings();
    if (!s.generatedPolicy() || s.credentials().roleArn() == null) {
      return null;
    }
    return s.writePolicy(database, workgroup(c, false).results(s));
  }

  // ── Queries ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code StartQueryExecution} in the connection's workgroup, catalog and database; its query execution id. */
  private String start(Call c, String sql, String policy, String token, boolean change) throws ConnectorException {
    AthenaSettings s = c.settings();
    ObjectNode body = F.objectNode().put("QueryString", sql).put("ClientRequestToken", token)
        .put("WorkGroup", s.workgroup());
    ObjectNode context = body.putObject("QueryExecutionContext").put("Catalog", s.catalog());
    if (s.database() != null) {
      context.put("Database", s.database());
    }
    if (s.outputLocation() != null) {
      body.putObject("ResultConfiguration").put("OutputLocation", s.outputLocation());
    }
    JsonNode r = api.call(c, "StartQueryExecution", body, policy, change);
    String id = r.path("QueryExecutionId").asString(null);
    if (id == null || id.isBlank()) {
      if (change) {
        throw ConnectorException.outcomeUnknown("Athena named no query for the change, so whether it started is not "
            + "known. Check the workgroup's recent queries before retrying.", null);
      }
      throw ConnectorException.resourceError("Athena named no query for the statement.", null);
    }
    return id;
  }

  /**
   * Polls {@code GetQueryExecution} until the query ends, with pauses from 100 ms to 1 s; at the call's deadline it
   * stops the query. A change whose end can't be learned has an unknown outcome.
   */
  private JsonNode await(Call c, String id, String policy, boolean change) throws ConnectorException {
    ExecContext ctx = c.ctx();
    long pause = 100;
    while (true) {
      JsonNode exec;
      try {
        exec = api.call(c, "GetQueryExecution", F.objectNode().put("QueryExecutionId", id), policy, false)
            .path("QueryExecution");
      } catch (ConnectorException e) {
        stop(c, id, policy);
        if (change) {
          throw ConnectorException.outcomeUnknown("GateRook lost track of change query " + id + " (" + e.getMessage()
              + "), so whether it ran is not known. Check the query in the Athena console before retrying.", e);
        }
        throw e;
      }
      String state = exec.path("Status").path("State").asString("");
      switch (state) {
        case "SUCCEEDED" -> {
          return exec;
        }
        case "FAILED" -> throw failure(exec, id, change);
        case "CANCELLED" -> throw change
            ? ConnectorException.outcomeUnknown("Athena cancelled change query " + id + " before it finished, so what "
                + "it wrote is not known. Check the table before retrying.", null)
            : ConnectorException.timeout("Athena cancelled query " + id + " before it finished.");
        default -> {
          // QUEUED or RUNNING.
        }
      }
      if (ctx.remaining().toMillis() - pause <= CANCEL_RESERVE.toMillis()) {
        stop(c, id, policy);
        if (change) {
          throw ConnectorException.outcomeUnknown("Athena didn't finish the change within the call's time; GateRook "
              + "stopped query " + id + ", so whether it ran is not known. Check the table before retrying.", null);
        }
        throw ConnectorException.timeout("Athena didn't finish the query within the call's time, so GateRook stopped "
            + "it (query " + id + "). Narrow it, or filter on a partition column.");
      }
      try {
        Thread.sleep(pause);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        stop(c, id, policy);
        if (change) {
          throw ConnectorException.outcomeUnknown("The call was cancelled while Athena ran change query " + id
              + ", so whether it ran is not known.", e);
        }
        throw ConnectorException.timeout("The call was cancelled; GateRook stopped query " + id + ".");
      }
      pause = Math.min(pause * 2, 1_000);
    }
  }

  /** {@code StopQueryExecution}, sent even at the deadline; what Athena answers doesn't change the call. */
  private void stop(Call c, String id, String policy) {
    try {
      api.call(c, "StopQueryExecution", F.objectNode().put("QueryExecutionId", id), policy, false,
          Duration.ofSeconds(2));
    } catch (ConnectorException e) {
      // The workgroup's limits end the query.
    }
  }

  /** Athena's failure of a query, with what to do; a change says what it may have left behind. */
  static ConnectorException failure(JsonNode exec, String id, boolean change) {
    JsonNode status = exec.path("Status");
    String message = OutputSanitizer.sanitize(AthenaApi.firstLine(status.path("AthenaError").path("ErrorMessage")
        .asString(status.path("StateChangeReason").asString(""))));
    if (message.length() > 1_000) {
      message = message.substring(0, 999) + "…";
    }
    int category = status.path("AthenaError").path("ErrorCategory").asInt(0);
    if (message.toLowerCase(Locale.ROOT).contains("bytes scanned limit")) {
      return ConnectorException.resourceError("The workgroup's data limit stopped query " + id + ": " + message
          + ". Select fewer columns or filter on a partition column.", null);
    }
    if (change) {
      return ConnectorException.resourceError("Athena stopped the change (query " + id + "): " + message + ". Iceberg "
          + "tables take a change whole or not at all; a failed INSERT or CREATE TABLE AS on a Hive table can leave "
          + "data files in its location, so check the table before retrying.", null);
    }
    return category == 2 ? ConnectorException.badInput("Athena refused the statement: " + message)
        : ConnectorException.resourceError("Athena failed query " + id + ": " + message + ".", null);
  }

  /** One page of {@code GetQueryResults}. */
  private JsonNode page(Call c, String id, String token, int max, String policy) throws ConnectorException {
    ObjectNode body = F.objectNode().put("QueryExecutionId", id).put("MaxResults", Math.max(1, Math.min(max, 1_000)));
    if (token != null) {
      body.put("NextToken", token);
    }
    return api.call(c, "GetQueryResults", body, policy, false);
  }

  /** Whether a row holds the column labels: the first row of a query's first page. */
  static boolean header(JsonNode row, JsonNode columnInfo) {
    JsonNode data = row.path("Data");
    if (data.size() != columnInfo.size() || data.isEmpty()) {
      return false;
    }
    for (int i = 0; i < data.size(); i++) {
      String label = columnInfo.get(i).path("Label").asString(columnInfo.get(i).path("Name").asString(""));
      if (!label.equals(data.get(i).path("VarCharValue").asString(null))) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@code EXPLAIN (TYPE IO, FORMAT JSON) <read>}: what Athena says a read reads. Only a query GateRook reads as a read
   * that Athena plans with Trino is sent, a change's read of what it reads included; never a write, which Trino plans
   * through its catalog's write hooks.
   */
  IoPlan plan(Call c, String read, String policy) throws ConnectorException {
    AthenaSql.Statement st = AthenaSql.read(read);
    if (!st.read() || st.hive()) {
      throw ConnectorException.denied("GateRook asks Athena to plan queries only.");
    }
    String id = start(c, "EXPLAIN (TYPE IO, FORMAT JSON) " + read, policy, UUID.randomUUID().toString(), false);
    await(c, id, policy, false);
    StringBuilder text = new StringBuilder();
    String token = null;
    for (int pages = 0; pages < 10; pages++) {
      JsonNode r = page(c, id, token, 1_000, policy);
      JsonNode info = r.path("ResultSet").path("ResultSetMetadata").path("ColumnInfo");
      JsonNode rows = r.path("ResultSet").path("Rows");
      for (int i = 0; i < rows.size(); i++) {
        if (token == null && i == 0 && header(rows.get(0), info)) {
          continue;
        }
        if (!text.isEmpty()) {
          text.append('\n');
        }
        text.append(rows.get(i).path("Data").path(0).path("VarCharValue").asString(""));
      }
      token = r.path("NextToken").asString(null);
      if (token == null) {
        break;
      }
    }
    IoPlan plan = IoPlan.parse(text.toString());
    if (plan == null) {
      throw ConnectorException.denied("Athena's IO plan of the statement can't be read, and GateRook doesn't run "
          + "what it can't check.");
    }
    return plan;
  }

  /** A read: Athena plans it as a query that writes no table and reads only databases of the connection. */
  static void checkRead(IoPlan plan, AthenaSettings s) throws ConnectorException {
    if (!plan.planned()) {
      throw ConnectorException.denied("Athena plans this as a data definition statement, not a query: athena_query "
          + "doesn't run it.");
    }
    if (plan.output() != null) {
      throw ConnectorException.denied("Athena's plan writes to " + plan.output().text() + ": athena_query only "
          + "reads. Use athena_execute, which a person approves.");
    }
    checkInputs(plan, s);
  }

  /**
   * A change, by Athena's plan of the read of what it reads: a plan of a query that writes no table, whose tables are
   * all in databases of the connection. The table the change writes is its own target, which {@link #refs} checks.
   */
  static void checkChange(IoPlan plan, AthenaSettings s) throws ConnectorException {
    if (!plan.planned() || plan.output() != null) {
      throw ConnectorException.denied("Athena's plan of what the change reads isn't the plan of a read, and GateRook "
          + "doesn't run what it can't check.");
    }
    checkInputs(plan, s);
  }

  /** The table a change writes, as its own name says, with the connection's catalog and default database. */
  static String written(Ref target, AthenaSettings s) {
    String database = s.databaseOf(target);
    if (database == null) {
      return target.name().text();
    }
    return s.catalogOf(target) + "." + database + "." + target.name().parts().getLast().toLowerCase(Locale.ROOT);
  }

  private static void checkInputs(IoPlan plan, AthenaSettings s) throws ConnectorException {
    for (IoPlan.Table t : plan.inputs()) {
      if (!s.allows(t.catalog(), t.schema())) {
        throw ConnectorException.denied("The statement reads " + t.text() + ", which isn't in a database of this "
            + "connection.");
      }
    }
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    AthenaSettings s = AthenaSettings.of(cfg);
    Call c = new Call(cfg, s, secrets, ctx);
    return switch (call.tool()) {
      case AthenaTools.LIST_CATALOGS -> listCatalogs(c, arguments(call, ctx));
      case AthenaTools.LIST_DATABASES -> listDatabases(c, arguments(call, ctx));
      case AthenaTools.LIST_TABLES -> listTables(c, arguments(call, ctx));
      case AthenaTools.DESCRIBE_TABLE -> describeTable(c, arguments(call, ctx));
      case AthenaTools.QUERY -> query(c, arguments(call, ctx));
      case AthenaTools.EXECUTE -> change(c);
      default -> throw ConnectorException.badInput(manifest.name() + " has no tool " + call.tool() + ".");
    };
  }

  private static JsonNode arguments(ToolCall call, ExecContext ctx) {
    if (ctx.approved() && ctx.approvedPayload().isPresent() && ctx.approvedPayload().get().has("arguments")) {
      return ctx.approvedPayload().get().get("arguments");
    }
    return call.arguments();
  }

  private static int limit(JsonNode args, int dflt, int max) {
    return Math.max(1, Math.min(args.path("limit").asInt(dflt), max));
  }

  private ToolResult listCatalogs(Call c, JsonNode args) throws ConnectorException {
    int limit = limit(args, LIST_LIMIT, 500);
    ObjectNode out = F.objectNode();
    ArrayNode catalogs = out.putArray("catalogs");
    List<String> names = new ArrayList<>();
    String token = null;
    boolean more = false;
    String policy = readPolicy(c);
    for (int pages = 0; pages < 10; pages++) {
      ObjectNode req = F.objectNode().put("MaxResults", 50).put("WorkGroup", c.settings().workgroup());
      if (token != null) {
        req.put("NextToken", token);
      }
      JsonNode r = api.call(c, "ListDataCatalogs", req, policy, false);
      for (JsonNode d : r.path("DataCatalogsSummary")) {
        String name = d.path("CatalogName").asString("");
        if (c.settings().allowsCatalog(name)) {
          if (names.size() >= limit) {
            more = true;
            break;
          }
          catalogs.addObject().put("catalog", name).put("type", d.path("Type").asString(""));
          names.add(name);
        }
      }
      token = r.path("NextToken").asString(null);
      if (more || token == null || c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        more = more || token != null;
        break;
      }
    }
    ToolResult r = ToolResult.json(out, names.isEmpty() ? "No data catalogs you may use in "
        + c.settings().region() + "." : names.size() + (names.size() == 1 ? " catalog: " : " catalogs: ")
        + String.join(", ", names) + (more ? "\nMore catalogs: raise limit." : ""));
    return more ? r.truncated("row_limit") : r;
  }

  private ToolResult listDatabases(Call c, JsonNode args) throws ConnectorException {
    AthenaSettings s = c.settings();
    String catalog = Objects.requireNonNullElse(blank(args.path("catalog").asString(null)), s.catalog());
    if (!AthenaSettings.CATALOG.matcher(catalog).matches() || !s.allowsCatalog(catalog)) {
      throw ConnectorException.denied(catalog + " isn't a catalog of this connection.");
    }
    int limit = limit(args, LIST_LIMIT, 500);
    String hash = Cursors.hash(F.objectNode().put("databases", catalog.toLowerCase(Locale.ROOT)));
    String token = args.path("cursor").isString() ? Cursors.open(c.ctx(), "list-databases",
        AthenaTools.LIST_DATABASES, args.get("cursor").asString(), c.cfg().id(), hash).asString(null) : null;
    ObjectNode out = F.objectNode().put("catalog", catalog);
    ArrayNode databases = out.putArray("databases");
    List<String> names = new ArrayList<>();
    String policy = readPolicy(c);
    for (int pages = 0; pages < 10 && names.size() < limit; pages++) {
      ObjectNode req = F.objectNode().put("CatalogName", catalog).put("MaxResults", 50)
          .put("WorkGroup", s.workgroup());
      if (token != null) {
        req.put("NextToken", token);
      }
      JsonNode r = api.call(c, "ListDatabases", req, policy, false);
      for (JsonNode d : r.path("DatabaseList")) {
        String name = d.path("Name").asString("");
        if (s.allows(catalog, name)) {
          ObjectNode e = databases.addObject().put("database", name);
          if (d.path("Description").isString() && !d.get("Description").asString().isBlank()) {
            e.set("description", Cells.text(d.get("Description").asString(), 500));
          }
          names.add(name);
        }
      }
      token = r.path("NextToken").asString(null);
      if (token == null || c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
    }
    if (token != null) {
      out.put("nextCursor", Cursors.seal(c.ctx(), "list-databases", c.cfg().id(), hash, F.stringNode(token)));
    }
    return ToolResult.json(out, names.isEmpty() ? "No databases you may use in " + catalog + "." : catalog + ": "
        + String.join(", ", names) + (token == null ? "" : "\nMore databases: pass nextCursor."));
  }

  private ToolResult listTables(Call c, JsonNode args) throws ConnectorException {
    AthenaSettings s = c.settings();
    String catalog = Objects.requireNonNullElse(blank(args.path("catalog").asString(null)), s.catalog());
    String database = Objects.requireNonNullElse(blank(args.path("database").asString(null)),
        Objects.requireNonNullElse(s.database(), ""));
    if (!AthenaSettings.CATALOG.matcher(catalog).matches() || !AthenaSettings.DATABASE.matcher(database).matches()) {
      throw ConnectorException.badInput("Name a database. Call athena_list_databases to see the databases.");
    }
    if (!s.allows(catalog, database)) {
      throw ConnectorException.denied(catalog + "." + database + " isn't a database of this connection.");
    }
    int limit = limit(args, LIST_LIMIT, 500);
    String hash = Cursors.hash(F.objectNode().put("tables", (catalog + "." + database).toLowerCase(Locale.ROOT)));
    String token = args.path("cursor").isString() ? Cursors.open(c.ctx(), "list-tables", AthenaTools.LIST_TABLES,
        args.get("cursor").asString(), c.cfg().id(), hash).asString(null) : null;
    ObjectNode out = F.objectNode().put("catalog", catalog).put("database", database);
    ArrayNode tables = out.putArray("tables");
    List<String> names = new ArrayList<>();
    String policy = readPolicy(c);
    for (int pages = 0; pages < 10 && names.size() < limit; pages++) {
      ObjectNode req = F.objectNode().put("CatalogName", catalog).put("DatabaseName", database)
          .put("MaxResults", Math.min(50, limit - names.size())).put("WorkGroup", s.workgroup());
      if (token != null) {
        req.put("NextToken", token);
      }
      JsonNode r = api.call(c, "ListTableMetadata", req, policy, false);
      for (JsonNode t : r.path("TableMetadataList")) {
        String name = t.path("Name").asString("");
        String kind = kind(t.path("TableType").asString(""));
        tables.addObject().put("table", name).put("kind", kind);
        names.add(name + ("table".equals(kind) ? "" : " (" + kind + ")"));
      }
      token = r.path("NextToken").asString(null);
      if (token == null || c.ctx().remaining().compareTo(Duration.ofSeconds(1)) < 0) {
        break;
      }
    }
    if (token != null) {
      out.put("nextCursor", Cursors.seal(c.ctx(), "list-tables", c.cfg().id(), hash, F.stringNode(token)));
    }
    return ToolResult.json(out, names.isEmpty() ? "No tables in " + database + "." : database + ": "
        + String.join(", ", names) + (token == null ? "" : "\nMore tables: pass nextCursor."));
  }

  /** Glue's table types in words: {@code EXTERNAL_TABLE} is a table, {@code VIRTUAL_VIEW} a view. */
  private static String kind(String tableType) {
    return switch (tableType) {
      case "VIRTUAL_VIEW" -> "view";
      case "", "EXTERNAL_TABLE", "MANAGED_TABLE" -> "table";
      default -> tableType.toLowerCase(Locale.ROOT).replace('_', ' ');
    };
  }

  private ToolResult describeTable(Call c, JsonNode args) throws ConnectorException {
    AthenaSettings s = c.settings();
    String[] t = table(args.path("table").asString(null), s);
    if (t == null) {
      throw ConnectorException.badInput("Name a table: database.table or catalog.database.table.");
    }
    if (!s.allows(t[0], t[1])) {
      throw ConnectorException.denied(t[0] + "." + t[1] + " isn't a database of this connection.");
    }
    JsonNode m = api.call(c, "GetTableMetadata", F.objectNode().put("CatalogName", t[0]).put("DatabaseName", t[1])
        .put("TableName", t[2]).put("WorkGroup", s.workgroup()), readPolicy(c), false).path("TableMetadata");
    ObjectNode out = F.objectNode().put("table", t[0] + "." + t[1] + "." + m.path("Name").asString(t[2]))
        .put("kind", kind(m.path("TableType").asString("")));
    out.set("columns", columns(m.path("Columns")));
    if (m.path("PartitionKeys").isArray() && !m.get("PartitionKeys").isEmpty()) {
      out.set("partitionKeys", columns(m.get("PartitionKeys")));
    }
    JsonNode p = m.path("Parameters");
    for (String key : List.of("location", "table_type", "classification", "comment")) {
      if (p.path(key).isString()) {
        out.set(key.equals("table_type") ? "format" : key, Cells.text(p.get(key).asString(), 1_000));
      }
    }
    List<String> cols = new ArrayList<>();
    out.path("columns").forEach(col -> cols.add(col.path("name").asString() + " " + col.path("type").asString()));
    StringBuilder text = new StringBuilder(out.path("table").asString()).append(" (")
        .append(out.path("kind").asString()).append("): ").append(String.join(", ", cols));
    if (out.has("partitionKeys")) {
      List<String> keys = new ArrayList<>();
      out.path("partitionKeys").forEach(k -> keys.add(k.path("name").asString()));
      text.append(" · partitioned by ").append(String.join(", ", keys)).append(": filter on them to scan less");
    }
    return ToolResult.json(out, text.toString());
  }

  private static ArrayNode columns(JsonNode list) {
    ArrayNode out = F.arrayNode();
    for (JsonNode col : list) {
      ObjectNode o = out.addObject().put("name", col.path("Name").asString(""))
          .put("type", col.path("Type").asString(""));
      if (col.path("Comment").isString() && !col.get("Comment").asString().isBlank()) {
        o.set("comment", Cells.text(col.get("Comment").asString(), 500));
      }
    }
    return out;
  }

  private ToolResult query(Call c, JsonNode args) throws ConnectorException {
    ExecContext ctx = c.ctx();
    AthenaSettings s = c.settings();
    String sql = args.path("sql").asString("");
    AthenaSql.Statement st = AthenaSql.read(sql);
    if (st.kind() != Kind.READ) {
      throw ConnectorException.denied("athena_query only runs one statement the gateway reads as a read.");
    }
    String problem = refs(st, s, ctx.env() == null ? "this environment" : ctx.env().id());
    if (problem != null) {
      throw ConnectorException.denied(problem);
    }
    int limit = limit(args, DEFAULT_LIMIT, ctx.limits().maxRows());
    String hash = Cursors.hash(F.objectNode().put("sql", sql));
    String policy = readPolicy(c);
    ObjectNode facts = F.objectNode();
    String id;
    String token = null;
    int skip = 0;
    boolean dml;
    if (args.path("cursor").isString()) {
      JsonNode at = Cursors.open(ctx, "query", AthenaTools.QUERY, args.get("cursor").asString(), c.cfg().id(), hash);
      id = at.path("q").asString("");
      token = at.path("t").asString(null);
      skip = Math.max(0, at.path("s").asInt(0));
      dml = at.path("d").asBoolean(false);
      if (id.isBlank()) {
        throw ConnectorException.badInput("The cursor names no query. Start again without cursor.");
      }
    } else {
      IoPlan plan = null;
      if (st.planned() != null) {
        plan = plan(c, st.planned(), policy);
        checkRead(plan, s);
      }
      id = start(c, st.sql(), policy, UUID.randomUUID().toString(), false);
      JsonNode exec = await(c, id, policy, false);
      // A tripwire after the plan: what Athena says it ran. A read it reports as a change returns nothing.
      String type = exec.path("StatementType").asString("");
      String sub = exec.path("SubstatementType").asString("");
      if ((type.equals("DDL") && !st.hive()) || WRITE_SUBSTATEMENT.matcher(sub).matches()) {
        throw ConnectorException.denied("Athena reports query " + id + " ran as " + type + (sub.isEmpty() ? "" : " "
            + sub) + ", not as a read: GateRook returns no result of it. Check the workgroup's recent queries.");
      }
      // Queries Athena runs with Trino answer with the column labels first; Hive's SHOW and DESCRIBE don't.
      dml = type.equals("DML") || !st.hive();
      JsonNode stats = exec.path("Statistics");
      if (stats.path("DataScannedInBytes").canConvertToLong()) {
        facts.put("dataScannedBytes", stats.get("DataScannedInBytes").asLong());
      }
      if (stats.path("EngineExecutionTimeInMillis").canConvertToLong()) {
        facts.put("engineMs", stats.get("EngineExecutionTimeInMillis").asLong());
      }
      if (stats.path("ResultReuseInformation").path("ReusedPreviousResult").asBoolean(false)) {
        facts.put("reusedResult", true);
      }
      facts.put("statementType", type + (sub.isEmpty() ? "" : " " + sub));
      if (plan != null && !plan.inputs().isEmpty()) {
        ArrayNode read = facts.putArray("tablesRead");
        plan.inputNames().stream().limit(50).forEach(read::add);
      }
    }
    facts.put("queryExecutionId", id);
    return rows(c, id, dml, token, skip, limit, hash, facts, policy);
  }

  /**
   * Rows of a finished query from {@code token} (the first page: {@code null}), the first {@code skip} rows of that
   * page left out, within the row limit and the response budget. The cursor names the page and the rows of it shown,
   * so a page cut at the response size goes on exactly where it stopped.
   */
  private ToolResult rows(Call c, String id, boolean dml, String token, int skip, int limit, String hash,
      ObjectNode facts, String policy) throws ConnectorException {
    ExecContext ctx = c.ctx();
    long budget = Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
    int cell = ctx.limits().maxCellChars();
    ArrayNode rows = F.arrayNode();
    JsonNode columns = null;
    long used = 0;
    ObjectNode next = null;
    String truncated = null;
    String pageToken = token;
    int skipRows = skip;
    for (int pages = 0; pages < 20; pages++) {
      boolean firstPage = pageToken == null && skipRows == 0;
      int max = (limit - rows.size()) + skipRows + (firstPage && dml ? 1 : 0);
      JsonNode r = page(c, id, pageToken, max, policy);
      JsonNode info = r.path("ResultSet").path("ResultSetMetadata").path("ColumnInfo");
      if (columns == null) {
        columns = info;
      }
      JsonNode list = r.path("ResultSet").path("Rows");
      int start = skipRows;
      if (firstPage && dml && !list.isEmpty() && header(list.get(0), info)) {
        start = 1;
      }
      String after = r.path("NextToken").asString(null);
      boolean stop = false;
      for (int i = start; i < list.size(); i++) {
        if (rows.size() >= limit) {
          next = position(id, pageToken, i, dml);
          stop = true;
          break;
        }
        ArrayNode cells = Cells.row(list.get(i), info, cell);
        long size = cells.toString().length();
        if (used + size > budget && !rows.isEmpty()) {
          next = position(id, pageToken, i, dml);
          truncated = "size_limit";
          stop = true;
          break;
        }
        used += size;
        rows.add(cells);
      }
      if (stop || after == null) {
        break;
      }
      if (rows.size() >= limit || ctx.remaining().compareTo(Duration.ofMillis(1_500)) < 0) {
        next = position(id, after, 0, dml);
        break;
      }
      pageToken = after;
      skipRows = 0;
    }
    String cursor = next == null ? null : Cursors.seal(ctx, "query", c.cfg().id(), hash, next);
    ToolResult.Table table = new ToolResult.Table(Cells.columns(columns == null ? F.arrayNode() : columns), rows,
        cursor);
    String scanned = facts.path("dataScannedBytes").canConvertToLong()
        ? gib(facts.get("dataScannedBytes").asLong()) + " scanned" : null;
    ToolResult result = new ToolResult(scanned == null ? List.of() : List.of(ToolResult.Content.text(scanned)),
        facts, false, table, false, null, List.of(), 0, 0, null);
    return truncated == null ? result : result.truncated(truncated);
  }

  private static ObjectNode position(String id, String token, int skip, boolean dml) {
    ObjectNode at = F.objectNode().put("q", id).put("s", skip).put("d", dml);
    if (token != null) {
      at.put("t", token);
    }
    return at;
  }

  private ToolResult change(Call c) throws ConnectorException {
    ExecContext ctx = c.ctx();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: athena_execute changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    AthenaSql.Statement st = AthenaSql.read(payload.path("sql").asString(""));
    if (st.kind() != Kind.CHANGE) {
      throw ConnectorException.denied("athena_execute runs one analyzed change.");
    }
    AthenaSettings s = c.settings();
    String problem = refs(st, s, ctx.env() == null ? "this environment" : ctx.env().id());
    if (problem != null) {
      throw ConnectorException.denied(problem);
    }
    String database = s.databaseOf(st.target());
    // Checked again right before it runs: the read of what it reads, never the write itself.
    if (st.planned() != null) {
      checkChange(plan(c, st.planned(), readPolicy(c)), s);
    }
    String policy = writePolicy(c, database);
    // Athena runs one request token once: the same approval can't start its statement twice.
    String id = start(c, st.sql(), policy, requestToken(ctx), true);
    JsonNode exec = await(c, id, policy, true);
    ObjectNode out = F.objectNode().put("committed", true).put("statement", st.type())
        .put("target", st.target().name().text()).put("queryExecutionId", id);
    JsonNode stats = exec.path("Statistics");
    if (stats.path("DataScannedInBytes").canConvertToLong()) {
      out.put("dataScannedBytes", stats.get("DataScannedInBytes").asLong());
    }
    Long count = null;
    try {
      JsonNode r = page(c, id, null, 1, policy);
      if (r.path("UpdateCount").canConvertToLong()) {
        count = r.get("UpdateCount").asLong();
      }
    } catch (ConnectorException e) {
      // The count is extra: the change finished, as Athena said.
    }
    String text;
    if (count != null && count > 0) {
      out.put("rowsAffected", count);
      text = count + (count == 1 ? " row" : " rows") + " written by " + st.type() + " on " + st.target().name().text()
          + ".";
    } else {
      text = st.type() + " on " + st.target().name().text() + " done.";
    }
    if (out.has("dataScannedBytes")) {
      text += " " + gib(out.get("dataScannedBytes").asLong()) + " scanned.";
    }
    return ToolResult.json(out, text);
  }

  /** Athena's request token of an approved change: a UUID of the approval's id (32 to 128 characters). */
  static String requestToken(ExecContext ctx) {
    String id = ctx.approvalId().orElse(ctx.callId());
    return UUID.nameUUIDFromBytes(("gaterook-athena|" + id).getBytes(StandardCharsets.UTF_8)).toString();
  }

  static String gib(long bytes) {
    if (bytes >= 1L << 30) {
      return String.format(Locale.US, "%.1f GiB", bytes / (double) (1L << 30));
    }
    if (bytes >= 1L << 20) {
      return String.format(Locale.US, "%.1f MiB", bytes / (double) (1L << 20));
    }
    return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    long started = System.nanoTime();
    AthenaSettings s;
    try {
      s = AthenaSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("athena.settings", e.getMessage(), null);
    }
    Call c = new Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    CloudCredentials.CallerIdentity identity;
    try {
      AwsCredentialsProvider provider = CloudCredentials.tainting(cloud.aws(new CloudCredentials.Owner(cfg.id(),
          cfg.version(), cfg.name()), s.credentials(), s.inspectPolicy(), secrets), ctx::taint);
      identity = cloud.callerIdentity(provider, s.region());
    } catch (ConnectorException e) {
      return TestResult.failure("athena.credentials", e.getMessage(), null);
    } catch (SdkException e) {
      return TestResult.failure("athena.credentials", "The AWS credentials couldn't be loaded: "
          + AthenaApi.firstLine(e.getMessage()) + ".", null);
    }
    checks.add(new TestResult.Check("identity", true, ms(started), identity.arn()));
    long read = System.nanoTime();
    Workgroup wg;
    try {
      wg = workgroup(c, true);
    } catch (ConnectorException e) {
      return TestResult.failure("athena.workgroup", "The workgroup " + s.workgroup() + " can't be read: "
          + e.getMessage(), null).withChecks(checks);
    }
    if (!"ENABLED".equals(wg.state())) {
      return TestResult.failure("athena.workgroup", "The workgroup " + s.workgroup() + " is " + wg.state()
          .toLowerCase(Locale.ROOT) + ": enable it or choose another.", null).withChecks(checks);
    }
    long latency = ms(read);
    checks.add(new TestResult.Check("workgroup", true, latency, s.workgroup() + " · " + wg.engine()
        + " · data limit " + (wg.cutoff() == null ? "none" : gib(wg.cutoff()) + " per query")));
    List<String> visible = new ArrayList<>();
    long listed = System.nanoTime();
    try {
      JsonNode r = api.call(c, "ListDatabases", F.objectNode().put("CatalogName", s.catalog()).put("MaxResults", 50)
          .put("WorkGroup", s.workgroup()), readPolicy(c), false);
      for (JsonNode d : r.path("DatabaseList")) {
        if (s.allows(s.catalog(), d.path("Name").asString(""))) {
          visible.add(d.path("Name").asString());
        }
      }
      if (r.path("NextToken").isString()) {
        visible.add("…");
      }
    } catch (ConnectorException e) {
      return TestResult.failure("athena.databases", "The credentials can read the workgroup but not the databases "
          + "of " + s.catalog() + ": " + e.getMessage(), null).withChecks(checks);
    }
    long count = visible.stream().filter(v -> !v.equals("…")).count();
    checks.add(new TestResult.Check("databases", true, ms(listed), count + (count == 1 ? " database" : " databases")
        + " agents may use" + (visible.isEmpty() ? "" : ": " + String.join(", ", visible.subList(0,
        Math.min(6, visible.size()))))));
    long queried = System.nanoTime();
    try {
      // EXPLAIN scans nothing and costs nothing: it proves queries start and their results can be read.
      String policy = readPolicy(c);
      String id = start(c, "EXPLAIN SELECT 1", policy, UUID.randomUUID().toString(), false);
      await(c, id, policy, false);
      page(c, id, null, 1, policy);
      checks.add(new TestResult.Check("query", true, ms(queried), "Runs queries in " + s.workgroup() + " and reads "
          + "their results (EXPLAIN)"));
    } catch (ConnectorException e) {
      return TestResult.failure("athena.query", "The credentials can list databases but not run a query in "
          + s.workgroup() + ": " + e.getMessage(), null).withChecks(checks);
    }
    ObjectNode details = F.objectNode().put("sessionPolicy", s.generatedPolicy() && s.credentials().roleArn() != null
        ? "generated per call" : "none");
    if (wg.cutoff() != null) {
      details.put("scanCutoffBytes", wg.cutoff());
    }
    details.put("workgroupEnforced", wg.enforced());
    TestResult.Permissions permissions = new TestResult.Permissions(TestResult.Level.UNKNOWN,
        "SELECT, SHOW, DESCRIBE and EXPLAIN; queries checked by Athena's IO plan", null, List.of(), details);
    TestResult result = TestResult.success(latency, "Amazon Athena · " + wg.engine() + " · " + s.region(),
            identity.arn(), permissions)
        .withChecks(checks)
        .withMetadata(F.objectNode().put("serverVersion", "Athena " + wg.engine()).put("account", identity.account())
            .put("region", s.region()).put("workgroup", s.workgroup()));
    for (Hint h : hints(cfg.env(), s, wg, visible)) {
      result = result.withHint(h);
    }
    return result;
  }

  /**
   * Profiles and regions of this computer; signed in, the workgroups, the workgroup's results location, the data
   * catalogs and the databases ({@link AthenaSuggestions}).
   */
  @Override
  public Suggestions suggest(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Suggestions.Scope scope) {
    return AthenaSuggestions.suggest(cfg, secrets, ctx, scope, cloud, local, api);
  }

  static List<Hint> hints(Env env, AthenaSettings s, Workgroup wg, List<String> visible) {
    List<Hint> out = new ArrayList<>();
    if (wg.cutoff() == null) {
      out.add(new Hint(Severity.WARNING, "athena.no-scan-cutoff", "/workgroup", "The workgroup " + s.workgroup()
          + " has no per-query data limit, so a query may scan, and cost, without bound. Set a data usage control "
          + "(BytesScannedCutoffPerQuery) on it."));
    }
    if (!wg.enforced()) {
      out.add(new Hint(Severity.WARNING, "athena.not-enforced", "/workgroup", "The workgroup " + s.workgroup()
          + " doesn't override client-side settings: turn on Override client-side settings so its results "
          + "location and encryption hold for every query."));
    }
    if (!wg.engine().isEmpty() && !wg.engine().contains("3")) {
      out.add(new Hint(Severity.WARNING, "athena.engine-version", "/workgroup", "The workgroup runs " + wg.engine()
          + ": GateRook's checks follow Athena engine version 3."));
    }
    if (s.outputLocation() != null && wg.managed() && !wg.enforced()) {
      out.add(new Hint(Severity.WARNING, "athena.managed-results", "/outputLocation", "The workgroup keeps results "
          + "itself: leave Query results location empty."));
    }
    if (s.credentials().roleArn() == null) {
      out.add(new Hint(env == Env.DEV ? Severity.INFO : Severity.WARNING, "athena.no-role", "/credentials/roleArn",
          "Without a role, each call has every permission of the credentials. With a role, GateRook limits each call "
              + "to what it needs, enforced by AWS."));
    } else if (!s.generatedPolicy()) {
      out.add(new Hint(Severity.WARNING, "athena.no-session-policy", "/credentials/sessionPolicy",
          "Calls use the role's permissions as they are: AWS doesn't keep a read from writing."));
    }
    out.add(new Hint(Severity.INFO, "athena.read-only-boundary", null, "Athena has no read-only session: GateRook "
        + "runs only what Athena's IO plan calls a read of the listed databases, and the workgroup's data limit is "
        + "the cost boundary."));
    if (visible.isEmpty()) {
      out.add(new Hint(Severity.WARNING, "athena.no-databases", "/databases", "The credentials see no database "
          + (s.databases().isEmpty() ? "" : "matching the connection's list ") + "in " + s.catalog() + "."));
    }
    return out;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      AthenaSettings s = AthenaSettings.of(cfg);
      long started = System.nanoTime();
      Workgroup wg = workgroup(new Call(cfg, s, secrets, ctx), true);
      if (!"ENABLED".equals(wg.state())) {
        return HealthResult.unreachable("The workgroup " + s.workgroup() + " is " + wg.state()
            .toLowerCase(Locale.ROOT) + ".");
      }
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode().put("region", cfg.string("/region"))
        .put("workgroup", Objects.requireNonNullElse(cfg.string("/workgroup"), "primary"))
        .put("catalog", Objects.requireNonNullElse(cfg.string("/catalog"), AthenaSettings.DEFAULT_CATALOG));
    if (cfg.string("/database") != null) {
      out.put("defaultDatabase", cfg.string("/database"));
    }
    List<String> databases = cfg.strings("/databases");
    if (databases.isEmpty()) {
      out.put("databases", "Every database the credentials may read");
    } else {
      ArrayNode a = out.putArray("databases");
      databases.forEach(a::add);
    }
    out.put("reads", "One SELECT, WITH, VALUES, SHOW, DESCRIBE or EXPLAIN per athena_query; queries are checked by "
        + "Athena's IO plan, name tables as database.table and filter on partition columns to scan less. Athena "
        + "charges for the data scanned");
    out.put("changes", "One INSERT, UPDATE, DELETE, MERGE, CREATE, ALTER, DROP, MSCK REPAIR TABLE, OPTIMIZE or VACUUM "
        + "per athena_execute, after approval");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call athena_describe_table with table " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    workgroups.remove(connectionId);
    cloud.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    workgroups.remove(connectionId);
    cloud.evict(connectionId);
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
