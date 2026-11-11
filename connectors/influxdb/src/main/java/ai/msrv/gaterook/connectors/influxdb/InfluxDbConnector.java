package ai.msrv.gaterook.connectors.influxdb;

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
import ai.msrv.gaterook.connectors.influxdb.InfluxHttp.Call;
import ai.msrv.gaterook.connectors.influxdb.InfluxHttp.Request;
import ai.msrv.gaterook.connectors.influxdb.InfluxHttp.Response;
import ai.msrv.gaterook.connectors.influxdb.InfluxSettings.AuthMethod;
import ai.msrv.gaterook.connectors.influxdb.InfluxSettings.Family;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * InfluxDB (docs/influxdb.md): {@code influxdb_list_databases}, {@code influxdb_list_measurements},
 * {@code influxdb_describe} and {@code influxdb_query} read; {@code influxdb_write} writes line protocol a person
 * approved.
 *
 * <p>{@link #classify} decides without I/O: the databases of the connection's list, those the statement names itself,
 * and the statement read by GateRook's own lexer for its language ({@link InfluxSql} for InfluxDB 3's SQL,
 * {@link InfluxQl} for InfluxQL). Only what it reads as one read runs; Flux never does. A write is read point by point
 * ({@link LineProtocol}), shown to the approver as the exact request, and sent once from the approved payload. InfluxDB
 * has no read-only session: the token's permissions remain the server's boundary.
 */
public final class InfluxDbConnector implements Connector {

  static final String TYPE = "influxdb";
  static final int DEFAULT_LIMIT = 100;
  static final int MAX_PARAMETERS = 50;
  static final int MAX_DATABASES = 1_000;
  static final String MASKED = "‹masked›";
  /** Slack's limit for the text of a section: a longer action can't be approved from a channel. */
  static final int CHANNEL_LIMIT = 3_000;
  /** A point stamped before this most likely has its timestamp in a smaller unit than the write's precision. */
  static final Instant SUSPICIOUSLY_EARLY = Instant.parse("1980-01-01T00:00:00Z");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern PARAMETER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
  private static final Pattern MEASUREMENT = Pattern.compile("[^\\x00-\\x1F\\x7F\"\\\\]{1,255}");
  private static final Set<String> PRECISIONS = Set.of("ns", "us", "ms", "s");

  /** The query languages. */
  enum Language {
    SQL("sql"), INFLUXQL("influxql");

    final String id;

    Language(String id) {
      this.id = id;
    }
  }

  /** What GateRook's reading says a statement is, in either language. */
  record Reading(InfluxQl.Kind kind, String type, List<String> databases, boolean defaultDatabase, String problem) {

    boolean showDatabases() {
      return "SHOW DATABASES".equals(type);
    }
  }

  private final InfluxHttp http = new InfluxHttp();
  private final ConnectorManifest manifest;

  public InfluxDbConnector() {
    SchemaResources schemas = SchemaResources.of(InfluxDbConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("InfluxDB")
        .icon("activity")
        .group("Databases")
        .description("Databases, measurements and SQL or InfluxQL reads of InfluxDB 3, 2.x and 1.x. Writes wait "
            + "for approval.")
        .configSchema(schemas.load("config"))
        .tools(InfluxTools.definitions(schemas))
        .access(ConnectorManifest.RequiredAccess.none())
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    InfluxSettings s;
    try {
      s = InfluxSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    String env = policy.env() == null ? "this environment" : policy.env().id();
    switch (call.tool()) {
      case InfluxTools.LIST_DATABASES -> {
        return Classification.read();
      }
      case InfluxTools.LIST_MEASUREMENTS -> {
        Classification db = database(call.string("database"), s, policy, env);
        return db != null ? db : Classification.read();
      }
      case InfluxTools.DESCRIBE -> {
        Classification db = database(call.string("database"), s, policy, env);
        if (db != null) {
          return db;
        }
        String m = call.string("measurement");
        if (m == null || !MEASUREMENT.matcher(m).matches()) {
          return Classification.deny(policy.rule("bad-argument"), "Name a measurement without quotes, backslashes "
              + "or control characters.", "Call influxdb_list_measurements to see them, or read it with "
              + "influxdb_query.");
        }
        return Classification.read();
      }
      case InfluxTools.QUERY -> {
        return classifyQuery(call, s, policy, env);
      }
      case InfluxTools.WRITE -> {
        return classifyWrite(call, s, policy, env);
      }
      default -> {
        return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
            null);
      }
    }
  }

  /** Why {@code database} can't be used, or {@code null}. */
  private static Classification database(String database, InfluxSettings s, PolicyView policy, String env) {
    if (!validDatabase(database)) {
      return Classification.deny(policy.rule("bad-argument"), "Name a database (a bucket on InfluxDB 2.x) of at "
          + "most 255 characters.", "Call influxdb_list_databases to see the databases.");
    }
    if (!s.allows(database)) {
      return Classification.deny(policy.rule("database-not-allowed"), database + " isn't one of the databases "
          + "agents may use on " + env + ".", "Call influxdb_list_databases to see the databases you can use.");
    }
    return null;
  }

  static boolean validDatabase(String database) {
    return database != null && InfluxSettings.NAME.matcher(database).matches() && database.strip().equals(database);
  }

  private Classification classifyQuery(ToolCall call, InfluxSettings s, PolicyView policy, String env) {
    String asked = call.string("language");
    if (asked != null && !asked.equals("sql") && !asked.equals("influxql")) {
      return Classification.deny(policy.rule("bad-argument"), "language is sql or influxql; Flux isn't supported.",
          null);
    }
    Language language;
    try {
      language = language(asked, s);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("sql-not-supported"), e.getMessage(), "Write the query in InfluxQL.");
    }
    String problem = parameters(call.arguments().path("parameters"));
    if (problem != null) {
      return Classification.deny(policy.rule("bad-argument"), problem, null);
    }
    String query = call.string("query");
    Reading r = reading(language, query);
    if (r.kind() != InfluxQl.Kind.READ) {
      if (InfluxQl.looksLikeFlux(query)) {
        return Classification.deny(policy.rule("flux-not-supported"), "Flux isn't supported: a Flux script can "
            + "write and call other services (to(), http.post(), sql.to()), so GateRook doesn't run it.",
            s.family() == Family.V3 ? "Write the query in SQL or InfluxQL." : "Write the query in InfluxQL.");
      }
      return switch (r.kind()) {
        case WRITE -> Classification.deny(policy.rule("query-writes"), "influxdb_query only reads; " + r.problem(),
            "Write points with influxdb_write: a person approves them. Other changes stay with people.");
        case FORBIDDEN -> Classification.deny(policy.rule("forbidden"), r.problem(), null);
        default -> Classification.deny(policy.rule("unparsed"), r.problem(), "Write one plain "
            + (language == Language.SQL ? "SQL" : "InfluxQL") + " read.");
      };
    }
    String db = call.string("database");
    if (db != null) {
      Classification denied = database(db, s, policy, env);
      if (denied != null) {
        return denied;
      }
    }
    for (String named : r.databases()) {
      Classification denied = database(named, s, policy, env);
      if (denied != null) {
        return denied;
      }
    }
    if (db == null && r.defaultDatabase()) {
      return Classification.deny(policy.rule("bad-argument"), "Name the database the query reads.",
          "Call influxdb_list_databases to see the databases.");
    }
    return Classification.read();
  }

  private Classification classifyWrite(ToolCall call, InfluxSettings s, PolicyView policy, String env) {
    String db = call.string("database");
    Classification denied = database(db, s, policy, env);
    if (denied != null) {
      return denied;
    }
    if (db.startsWith("_")) {
      return Classification.deny(policy.rule("system-database"), db + " is one of InfluxDB's own databases: "
          + "GateRook doesn't write to it.", null);
    }
    String precision = Objects.requireNonNullElse(call.string("precision"), "ns");
    if (!PRECISIONS.contains(precision)) {
      return Classification.deny(policy.rule("bad-argument"), "precision is ns, us, ms or s.", null);
    }
    String rp = call.string("retentionPolicy");
    if (rp != null && (s.family() != Family.V1 || !InfluxSettings.NAME.matcher(rp).matches())) {
      return Classification.deny(policy.rule("bad-argument"), s.family() != Family.V1 ? "retentionPolicy is "
          + "for InfluxDB 1.x: leave it out." : "A retention policy is a name of at most 255 characters.", null);
    }
    String lines = Objects.requireNonNullElse(call.string("lines"), "");
    if (lines.getBytes(StandardCharsets.UTF_8).length > LineProtocol.MAX_BYTES) {
      return Classification.deny(policy.rule("write-too-large"), "One write is at most 1 MiB of line protocol.",
          "Split the points into several writes.");
    }
    LineProtocol.Batch batch = LineProtocol.read(lines, precision, s.family() != Family.V1);
    if (!batch.ok()) {
      return Classification.deny(policy.rule("bad-line-protocol"), batch.problem(), "Fix the line and send the "
          + "write again.");
    }
    if (batch.points().size() > s.maxWritePoints()) {
      return Classification.deny(policy.rule("too-many-points"), "One write holds at most " + s.maxWritePoints()
          + " points on this connection; this one has " + batch.points().size() + ".", "Split the points into "
          + "several writes.");
    }
    ObjectNode facts = F.objectNode().put("database", db).put("points", batch.points().size());
    ArrayNode measurements = facts.putArray("measurements");
    measurements(batch).keySet().forEach(measurements::add);
    return Classification.mutation("Writes " + count(batch.points().size(), "point") + " to " + db, facts);
  }

  static Language language(String language, InfluxSettings s) throws ConnectorException {
    if (language == null) {
      return s.family() == Family.V3 ? Language.SQL : Language.INFLUXQL;
    }
    Language l = switch (language) {
      case "sql" -> Language.SQL;
      case "influxql" -> Language.INFLUXQL;
      default -> throw ConnectorException.badInput("language is sql or influxql.");
    };
    if (l == Language.SQL && s.family() != Family.V3) {
      throw ConnectorException.badInput("SQL runs on InfluxDB 3 only: " + s.family().label + " reads InfluxQL "
          + "here, as Flux isn't supported.");
    }
    return l;
  }

  static Reading reading(Language language, String query) {
    if (language == Language.SQL) {
      InfluxSql.Statement st = InfluxSql.read(query);
      InfluxQl.Kind kind = switch (st.kind()) {
        case READ -> InfluxQl.Kind.READ;
        case WRITE -> InfluxQl.Kind.WRITE;
        case FORBIDDEN -> InfluxQl.Kind.FORBIDDEN;
        case UNREADABLE -> InfluxQl.Kind.UNREADABLE;
      };
      // SQL on InfluxDB 3 reads the request's database, and only that one.
      return new Reading(kind, st.type(), List.of(), true, st.problem());
    }
    InfluxQl.Statement st = InfluxQl.read(query);
    return new Reading(st.kind(), st.type(), st.databases(), st.defaultDatabase(), st.problem());
  }

  /** Why {@code parameters} aren't named scalar values, or {@code null}. */
  static String parameters(JsonNode parameters) {
    if (parameters == null || parameters.isMissingNode() || parameters.isNull()) {
      return null;
    }
    if (!parameters.isObject()) {
      return "parameters is an object such as {\"host\": \"web-1\"}.";
    }
    if (parameters.size() > MAX_PARAMETERS) {
      return "At most " + MAX_PARAMETERS + " parameters per query.";
    }
    for (Map.Entry<String, JsonNode> p : parameters.properties()) {
      if (!PARAMETER.matcher(p.getKey()).matches()) {
        return "A parameter's name has letters, digits and _, such as host (written $host in the query).";
      }
      JsonNode v = p.getValue();
      if (!(v.isString() || v.isNumber() || v.isBoolean())) {
        return "Parameter " + p.getKey() + " needs one string, number or boolean.";
      }
      if (v.isString() && v.asString().length() > 10_000) {
        return "Parameter " + p.getKey() + " is longer than 10,000 characters.";
      }
      if (v.isNumber() && !Double.isFinite(v.asDouble())) {
        return "Parameter " + p.getKey() + " isn't a finite number.";
      }
    }
    return null;
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    InfluxSettings s;
    try {
      s = InfluxSettings.of(cfg);
    } catch (ConnectorException e) {
      return ActionPreview.of(ActionPreview.Kind.TEXT, "text", e.getMessage(), cfg.name(), shorten(call.tool() + " on "
          + cfg.name() + ": settings incomplete", 120)).withPayload(F.objectNode().set("arguments",
          call.arguments().deepCopy()));
    }
    String where = cfg.name() + " · " + s.target();
    if (InfluxTools.WRITE.equals(call.tool())) {
      return describeWrite(call, cfg, s, where);
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    String db = call.string("database");
    String target = db == null ? where : where + " · " + db;
    if (InfluxTools.QUERY.equals(call.tool())) {
      Language language;
      try {
        language = language(call.string("language"), s);
      } catch (ConnectorException e) {
        language = Language.INFLUXQL;
      }
      return ActionPreview.of(language == Language.SQL ? ActionPreview.Kind.SQL : ActionPreview.Kind.TEXT,
              language == Language.SQL ? "sql" : "text", Objects.requireNonNullElse(call.string("query"), ""), target,
              shorten(call.tool() + " on " + cfg.name(), 120))
          .withFields(List.of(new Field("Language", language == Language.SQL ? "SQL" : "InfluxQL")))
          .withPayload(F.objectNode().set("arguments", args))
          .withFacets(Map.of("tool", call.tool()))
          .withImpact(List.of(new Field("Reads", "Rows, no change")));
    }
    return ActionPreview.of(ActionPreview.Kind.JSON, "json", args.toString(), target,
            shorten(call.tool() + " on " + cfg.name(), 120))
        .withPayload(F.objectNode().set("arguments", args))
        .withFacets(Map.of("tool", call.tool()))
        .withImpact(List.of(new Field("Reads", "Metadata, no change")));
  }

  private ActionPreview describeWrite(ToolCall call, ConnectionConfig cfg, InfluxSettings s, String where) {
    String db = Objects.requireNonNullElse(call.string("database"), "");
    String precision = Objects.requireNonNullElse(call.string("precision"), "ns");
    String rp = s.family() == Family.V1 ? call.string("retentionPolicy") : null;
    LineProtocol.Batch batch = LineProtocol.read(Objects.requireNonNullElse(call.string("lines"), ""), precision,
        s.family() != Family.V1);
    String body = batch.ok() ? batch.body() : Objects.requireNonNullElse(call.string("lines"), "");
    Request request = writeRequest(s, db, precision, rp, body);
    String text = requestText(s, request);

    List<Field> fields = new ArrayList<>();
    fields.add(new Field(s.family() == Family.V2 ? "Bucket" : "Database", db));
    if (s.family() == Family.V1) {
      fields.add(new Field("Retention policy", rp == null ? "The database's default" : rp));
    }
    if (s.family() == Family.V2 && s.organization() != null) {
      fields.add(new Field("Organization", s.organization()));
    }
    fields.add(new Field("Precision", precision + " (" + unit(precision) + ")"));
    fields.add(new Field("Also sent by the gateway", "Host, Content-Length: " + request.body().length
        + ", User-Agent: " + InfluxHttp.USER_AGENT + ", Accept: application/json"));
    List<String> warnings = new ArrayList<>();
    if (batch.ok()) {
      List<LineProtocol.Point> points = batch.points();
      fields.add(new Field("Points", Integer.toString(points.size())));
      List<String> perMeasurement = new ArrayList<>();
      measurements(batch).forEach((m, n) -> perMeasurement.add(m + " (" + n + ")"));
      fields.add(new Field("Measurements", shorten(String.join(", ", perMeasurement), 2_000)));
      fields.add(new Field("Time range", timeRange(points, precision)));
      List<String> shown = new ArrayList<>();
      for (LineProtocol.Point p : points.subList(0, Math.min(points.size(), 10))) {
        shown.add(shorten(LineProtocol.describe(p, precision), 500));
      }
      fields.add(new Field(points.size() <= 10 ? "Each point" : "First 10 points", String.join("\n", shown)));
      long untimed = points.stream().filter(p -> p.timestamp() == null).count();
      if (untimed > 0) {
        warnings.add(count(untimed, "point") + " without a timestamp: the server stamps "
            + (untimed == 1 ? "it" : "them") + " with its own time when it writes.");
      }
      for (LineProtocol.Point p : points) {
        if (p.timestamp() != null && LineProtocol.instant(p.timestamp(), precision).isBefore(SUSPICIOUSLY_EARLY)) {
          warnings.add("The point of line " + p.line() + " is stamped " + LineProtocol.instant(p.timestamp(),
              precision) + ": check that " + precision + " is the unit of its timestamp.");
          break;
        }
      }
    }
    if (text.length() > CHANNEL_LIMIT) {
      warnings.add("The write is longer than 3,000 characters: Slack and Telegram show it cut and don't offer "
          + "Approve. Read it here.");
    }
    List<Field> impact = new ArrayList<>();
    impact.add(new Field("Writes", (batch.ok() ? count(batch.points().size(), "point") : "Points") + " to " + db
        + " on " + s.target()));
    impact.add(new Field("Existing points", "A point with the same measurement, tags and timestamp as an existing "
        + "one replaces the fields it writes"));
    impact.add(new Field("New names", s.family() == Family.V3 ? "InfluxDB 3 creates the database, tables and columns "
        + "that don't exist yet" : "New measurements, tags and fields are created; the database must exist"));
    impact.add(new Field("All or nothing", s.family() == Family.V3 ? "Yes: every point or none (accept_partial=false)"
        : "No: InfluxDB 1.x and 2.x keep the points they accept when they refuse others"));
    impact.add(new Field("Retried", "No: an answer lost after sending is reported as unknown"));

    ObjectNode payload = F.objectNode().put("database", db).put("precision", precision).put("lines", body);
    if (rp != null) {
      payload.put("retentionPolicy", rp);
    }
    Map<String, String> facets = new LinkedHashMap<>();
    facets.put("database", db);
    facets.put("points", batch.ok() ? Integer.toString(batch.points().size()) : "?");
    facets.put("measurements", batch.ok() ? shorten(String.join(",", measurements(batch).keySet()), 200) : "");
    return ActionPreview.of(ActionPreview.Kind.HTTP, "http", text, where + " · " + db,
            shorten(InfluxTools.WRITE + " on " + cfg.name() + ": " + (batch.ok() ? count(batch.points().size(),
                "point") : "points") + " to " + db, 120))
        .withFields(fields)
        .withImpact(impact)
        .withWarnings(warnings)
        .withFacets(facets)
        .withPayload(payload);
  }

  /** The request of a write: each API's own path and parameters, the lines as the body. */
  static Request writeRequest(InfluxSettings s, String db, String precision, String rp, String body) {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String type = "text/plain; charset=utf-8";
    return switch (s.family()) {
      case V3 -> Request.post("/api/v3/write_lp", type, bytes, "InfluxDB write", true, "db", db, "precision",
          switch (precision) {
            case "us" -> "microsecond";
            case "ms" -> "millisecond";
            case "s" -> "second";
            default -> "nanosecond";
          }, "accept_partial", "false");
      case V2 -> Request.post("/api/v2/write", type, bytes, "InfluxDB write", true, "org", s.organization(),
          "bucket", db, "precision", precision);
      case V1 -> Request.post("/write", type, bytes, "InfluxDB write", true, "db", db, "rp", rp, "precision",
          precision.equals("us") ? "u" : precision);
    };
  }

  /** What the approver reads: the request line, the headers GateRook sets, a blank line, the body as it is sent. */
  static String requestText(InfluxSettings s, Request r) {
    StringBuilder text = new StringBuilder(r.method()).append(' ')
        .append(InfluxHttp.uri(s, r.path(), r.query())).append('\n');
    text.append("Content-Type: ").append(r.contentType()).append('\n');
    if (s.auth() != AuthMethod.NONE) {
      text.append("Authorization: ").append(MASKED).append('\n');
    }
    return text.append('\n').append(new String(r.body(), StandardCharsets.UTF_8)).toString();
  }

  private static String unit(String precision) {
    return switch (precision) {
      case "us" -> "microseconds";
      case "ms" -> "milliseconds";
      case "s" -> "seconds";
      default -> "nanoseconds";
    };
  }

  private static Map<String, Integer> measurements(LineProtocol.Batch batch) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (LineProtocol.Point p : batch.points()) {
      out.merge(p.measurement(), 1, Integer::sum);
    }
    return out;
  }

  private static String timeRange(List<LineProtocol.Point> points, String precision) {
    Long min = null;
    Long max = null;
    for (LineProtocol.Point p : points) {
      if (p.timestamp() != null) {
        min = min == null ? p.timestamp() : Math.min(min, p.timestamp());
        max = max == null ? p.timestamp() : Math.max(max, p.timestamp());
      }
    }
    if (min == null) {
      return "The server's time at the write";
    }
    String range = min.equals(max) ? LineProtocol.instant(min, precision).toString()
        : LineProtocol.instant(min, precision) + " … " + LineProtocol.instant(max, precision);
    long untimed = points.stream().filter(p -> p.timestamp() == null).count();
    return untimed == 0 ? range : range + " (and " + count(untimed, "point") + " at the server's time)";
  }

  private static String count(long n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  /**
   * Before a person is asked: InfluxDB 3 creates a database a write names, so the approval says when it doesn't exist
   * yet. A failed check adds nothing: the write's own answer will tell.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    ActionPreview preview = describeAction(call, cfg);
    if (!InfluxTools.WRITE.equals(call.tool())) {
      return PreparedAction.approval(preview, null);
    }
    try {
      InfluxSettings s = InfluxSettings.of(cfg);
      String db = call.string("database");
      if (s.family() == Family.V3 && db != null) {
        Databases known = databases(new Call(cfg, s, secrets, ctx));
        if (!known.names().contains(db)) {
          List<String> warnings = new ArrayList<>(preview.warnings());
          warnings.add("Database " + db + " doesn't exist yet: InfluxDB 3 creates it with this write. Check its "
              + "name.");
          preview = preview.withWarnings(warnings);
        }
      }
    } catch (ConnectorException e) {
      // The check only adds a warning.
    }
    return PreparedAction.approval(preview, null);
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    InfluxSettings s = InfluxSettings.of(cfg);
    Call c = new Call(cfg, s, secrets, ctx);
    return switch (call.tool()) {
      case InfluxTools.LIST_DATABASES -> listDatabases(c);
      case InfluxTools.LIST_MEASUREMENTS -> listMeasurements(c, arguments(call, ctx));
      case InfluxTools.DESCRIBE -> describeMeasurement(c, arguments(call, ctx));
      case InfluxTools.QUERY -> query(c, arguments(call, ctx));
      case InfluxTools.WRITE -> write(c);
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

  /** Defence in depth: {@link #execute} gets no policy, so it checks a database against the connection again. */
  private static String checked(String database, InfluxSettings s) throws ConnectorException {
    if (!validDatabase(database)) {
      throw ConnectorException.badInput("Name a database. Call influxdb_list_databases to see the databases.");
    }
    if (!s.allows(database)) {
      throw ConnectorException.denied(database + " isn't one of the databases of this connection.");
    }
    return database;
  }

  private static int rowLimit(JsonNode args, ExecContext ctx, InfluxSettings s, int dflt) {
    int cap = Math.min(ctx.limits().maxRows(), s.maxRows());
    return Math.max(1, Math.min(args.path("limit").asInt(dflt), cap));
  }

  /** Bytes of the answer's rows; leaves room for the rest of it. */
  private static long budget(ExecContext ctx, InfluxSettings s) {
    return Math.max(4_096, Math.min(ctx.limits().maxResponseBytes(), s.maxResponseBytes()) * 3 / 4 - 2_048);
  }

  /** Runs one statement and reads its rows within the limits. */
  private Results.Rows run(Call c, Language language, String db, String query, JsonNode parameters, int limit,
      long budget, String what) throws ConnectorException {
    Results.Rows rows = new Results.Rows(limit, budget, c.ctx().limits().maxCellChars(), c.ctx());
    boolean params = parameters != null && parameters.isObject() && !parameters.isEmpty();
    if (c.settings().family() == Family.V3) {
      ObjectNode body = F.objectNode();
      if (db != null) {
        body.put("db", db);
      }
      body.put("q", query).put("format", "jsonl");
      if (params) {
        body.set("params", parameters);
      }
      String path = language == Language.SQL ? "/api/v3/query_sql" : "/api/v3/query_influxql";
      return http.stream(c, Request.post(path, "application/json", InfluxHttp.JSON.writeValueAsBytes(body), what,
          false), in -> Results.jsonLines(in, rows, what));
    }
    byte[] form = InfluxHttp.form("db", db, "q", query, "chunked", "true", "chunk_size",
        Integer.toString(Math.min(limit + 1, 10_000)), "params", params ? parameters.toString() : null);
    return http.stream(c, Request.post("/query", "application/x-www-form-urlencoded", form, what, false),
        in -> Results.chunks(in, rows, what));
  }

  /** The databases a server lists, and whether it listed them through InfluxDB 3's configuration API. */
  record Databases(List<String> names, boolean configurationApi) {}

  private Databases databases(Call c) throws ConnectorException {
    long budget = 4L * 1024 * 1024;
    if (c.settings().family() == Family.V3) {
      Response r = http.send(c, Request.get("/api/v3/configure/database", "InfluxDB's database list", "format",
          "json"), budget);
      if (r.ok()) {
        List<String> names = new ArrayList<>();
        for (JsonNode d : InfluxHttp.json(r, "InfluxDB's database list")) {
          if (!d.path("deleted").asBoolean(false) && d.path("iox::database").isString()) {
            names.add(d.get("iox::database").asString());
          }
        }
        return new Databases(names, true);
      }
      if (r.status() != 401 && r.status() != 403) {
        throw Errors.of(r.status(), r.body(), Request.get("/api/v3/configure/database", "InfluxDB's database list"),
            c);
      }
      // A resource token of InfluxDB 3 Enterprise may not use the configuration API: SHOW DATABASES lists what it
      // reads.
    }
    Results.Rows rows = run(c, Language.INFLUXQL, null, "SHOW DATABASES", null, MAX_DATABASES, budget,
        "InfluxDB SHOW DATABASES");
    String column = rows.hasColumn("iox::database") ? "iox::database" : "name";
    List<String> names = new ArrayList<>();
    for (JsonNode n : rows.column(column)) {
      if (n.isString()) {
        names.add(n.asString());
      }
    }
    return new Databases(names, false);
  }

  private ToolResult listDatabases(Call c) throws ConnectorException {
    InfluxSettings s = c.settings();
    List<String> names = databases(c).names().stream().filter(s::allows).toList();
    ObjectNode out = F.objectNode();
    ArrayNode list = out.putArray("databases");
    names.forEach(list::add);
    String noun = s.family() == Family.V2 ? "bucket" : "database";
    return ToolResult.json(out, names.isEmpty() ? "No " + noun + "s you may use on " + c.cfg().name() + "."
        : count(names.size(), noun) + ": " + String.join(", ", names));
  }

  private ToolResult listMeasurements(Call c, JsonNode args) throws ConnectorException {
    InfluxSettings s = c.settings();
    String db = checked(args.path("database").asString(null), s);
    int limit = Math.max(1, Math.min(args.path("limit").asInt(200), 1_000));
    String hash = Cursors.hash(F.objectNode().put("database", db));
    long offset = 0;
    if (args.path("cursor").isString()) {
      offset = Math.max(0, Cursors.open(c.ctx(), InfluxTools.LIST_MEASUREMENTS, args.get("cursor").asString(),
          c.cfg().id(), hash).asLong(0));
    }
    Results.Rows rows;
    String column;
    if (s.family() == Family.V3) {
      rows = run(c, Language.SQL, db, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'iox' "
          + "ORDER BY table_name LIMIT " + (limit + 1) + " OFFSET " + offset, null, limit + 1, budget(c.ctx(), s),
          "InfluxDB tables");
      column = "table_name";
    } else {
      rows = run(c, Language.INFLUXQL, db, "SHOW MEASUREMENTS LIMIT " + (limit + 1) + " OFFSET " + offset, null,
          limit + 1, budget(c.ctx(), s), "InfluxDB SHOW MEASUREMENTS");
      column = "name";
    }
    List<String> names = new ArrayList<>();
    for (JsonNode n : rows.column(column)) {
      if (n.isString()) {
        names.add(n.asString());
      }
    }
    boolean more = names.size() > limit || "size_limit".equals(rows.truncated());
    if (names.size() > limit) {
      names = names.subList(0, limit);
    }
    ObjectNode out = F.objectNode().put("database", db);
    ArrayNode list = out.putArray("measurements");
    names.forEach(list::add);
    if (more && !names.isEmpty()) {
      out.put("nextCursor", Cursors.seal(c.ctx(), InfluxTools.LIST_MEASUREMENTS, c.cfg().id(), hash,
          F.numberNode(offset + names.size())));
    }
    return ToolResult.json(out, names.isEmpty() ? "No measurements in " + db + "."
        : count(names.size(), "measurement") + " in " + db + ": " + String.join(", ", names)
            + (more ? "\nMore measurements: pass nextCursor." : ""));
  }

  private ToolResult describeMeasurement(Call c, JsonNode args) throws ConnectorException {
    InfluxSettings s = c.settings();
    String db = checked(args.path("database").asString(null), s);
    String m = args.path("measurement").asString(null);
    if (m == null || !MEASUREMENT.matcher(m).matches()) {
      throw ConnectorException.badInput("Name a measurement without quotes, backslashes or control characters.");
    }
    long budget = 4L * 1024 * 1024;
    Results.Rows fieldRows = run(c, Language.INFLUXQL, db, "SHOW FIELD KEYS FROM " + InfluxQl.quote(m), null, 10_000,
        budget, "InfluxDB SHOW FIELD KEYS");
    Results.Rows tagRows = run(c, Language.INFLUXQL, db, "SHOW TAG KEYS FROM " + InfluxQl.quote(m), null, 10_000,
        budget, "InfluxDB SHOW TAG KEYS");
    ObjectNode out = F.objectNode().put("database", db).put("measurement", m).put("time", "timestamp (UTC)");
    ArrayNode tags = out.putArray("tags");
    List<String> tagNames = new ArrayList<>();
    for (JsonNode t : tagRows.column("tagKey")) {
      if (t.isString()) {
        tags.add(t.asString());
        tagNames.add(t.asString());
      }
    }
    ArrayNode fields = out.putArray("fields");
    List<String> fieldText = new ArrayList<>();
    List<JsonNode> keys = fieldRows.column("fieldKey");
    List<JsonNode> types = fieldRows.column("fieldType");
    for (int k = 0; k < keys.size(); k++) {
      String key = keys.get(k).asString("");
      String type = types.get(k).asString("");
      fields.addObject().put("name", key).put("type", type);
      fieldText.add(key + " " + type);
    }
    if (tagNames.isEmpty() && fieldText.isEmpty()) {
      return ToolResult.json(out, "No measurement " + m + " in " + db + ", or it has no fields. Call "
          + "influxdb_list_measurements to see them.");
    }
    return ToolResult.json(out, m + " in " + db + ": tags " + (tagNames.isEmpty() ? "none" : String.join(", ",
        tagNames)) + " · fields " + (fieldText.isEmpty() ? "none" : String.join(", ", fieldText)) + " · time");
  }

  private ToolResult query(Call c, JsonNode args) throws ConnectorException {
    InfluxSettings s = c.settings();
    ExecContext ctx = c.ctx();
    String query = args.path("query").asString("");
    Language language = language(args.path("language").asString(null), s);
    Reading r = reading(language, query);
    if (r.kind() != InfluxQl.Kind.READ) {
      throw ConnectorException.denied("influxdb_query runs one statement GateRook reads as a read: " + r.problem());
    }
    String db = args.path("database").isString() ? checked(args.get("database").asString(), s) : null;
    for (String named : r.databases()) {
      checked(named, s);
    }
    if (db == null && r.defaultDatabase()) {
      throw ConnectorException.badInput("Name the database the query reads. Call influxdb_list_databases to see "
          + "the databases.");
    }
    JsonNode parameters = args.path("parameters");
    String problem = parameters(parameters);
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    int limit = rowLimit(args, ctx, s, DEFAULT_LIMIT);
    Results.Rows rows = run(c, language, db, query, parameters, limit, budget(ctx, s), "InfluxDB query");
    if (r.showDatabases()) {
      rows.retain(rows.hasColumn("iox::database") ? "iox::database" : "name", s::allows);
    }
    ObjectNode facts = F.objectNode().put("language", language.id).put("statement", r.type());
    if (db != null) {
      facts.put("database", db);
    }
    facts.put("rows", rows.size());
    ToolResult result = ToolResult.table(rows.table(), facts);
    if (rows.truncated() == null) {
      return result;
    }
    facts.put("more", true);
    facts.put("hint", switch (rows.truncated()) {
      case "row_limit" -> "More rows than the limit of " + limit + ": narrow the time range or aggregate.";
      case "size_limit" -> "Cut at the response size: select fewer columns or rows.";
      default -> "Cut at the call's time: narrow the query.";
    });
    return result.truncated(rows.truncated());
  }

  private ToolResult write(Call c) throws ConnectorException {
    ExecContext ctx = c.ctx();
    InfluxSettings s = c.settings();
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: influxdb_write changes data, so a person approves it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no points."));
    String db = checked(payload.path("database").asString(null), s);
    if (db.startsWith("_")) {
      throw ConnectorException.denied(db + " is one of InfluxDB's own databases: GateRook doesn't write to it.");
    }
    String precision = payload.path("precision").asString("ns");
    if (!PRECISIONS.contains(precision)) {
      throw ConnectorException.badInput("precision is ns, us, ms or s.");
    }
    String rp = s.family() == Family.V1 ? payload.path("retentionPolicy").asString(null) : null;
    if (rp != null && !InfluxSettings.NAME.matcher(rp).matches()) {
      throw ConnectorException.badInput("A retention policy is a name of at most 255 characters.");
    }
    LineProtocol.Batch batch = LineProtocol.read(payload.path("lines").asString(""), precision,
        s.family() != Family.V1);
    if (!batch.ok()) {
      throw ConnectorException.badInput(batch.problem());
    }
    if (batch.points().size() > s.maxWritePoints()) {
      throw ConnectorException.denied("One write holds at most " + s.maxWritePoints() + " points on this "
          + "connection.");
    }
    Request request = writeRequest(s, db, precision, rp, batch.body());
    Response r = http.send(c, request, InfluxHttp.MAX_ERROR_BODY);
    if (!r.ok()) {
      throw Errors.of(r.status(), r.body(), request, c);
    }
    ObjectNode out = F.objectNode().put("written", true).put("database", db).put("points", batch.points().size());
    ArrayNode measurements = out.putArray("measurements");
    measurements(batch).keySet().forEach(measurements::add);
    return ToolResult.json(out, "Wrote " + count(batch.points().size(), "point") + " to " + db + ".");
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    InfluxSettings s;
    try {
      s = InfluxSettings.of(cfg);
    } catch (ConnectorException e) {
      return TestResult.failure("influxdb.settings", e.getMessage(), null);
    }
    Call c = new Call(cfg, s, secrets, ctx);
    List<TestResult.Check> checks = new ArrayList<>();
    long started = System.nanoTime();
    Response ping;
    try {
      ping = http.send(c, Request.get("/ping", "InfluxDB ping"), InfluxHttp.MAX_ERROR_BODY);
    } catch (ConnectorException e) {
      return TestResult.failure(code(e), e.getMessage(), null);
    }
    long latency = ms(started);
    // InfluxDB Cloud Dedicated may have no /ping: the database list below shows whether the server answers.
    if (!ping.ok() && ping.status() != 404) {
      String said = Errors.message(ping.body());
      if (ping.status() == 401 || ping.status() == 403) {
        return TestResult.failure("influxdb.credentials", "InfluxDB refused the credentials: " + said + ". Check "
            + (s.auth() == AuthMethod.PASSWORD ? "the username and password" : "the API token") + " under "
            + "Credentials.", null);
      }
      return TestResult.failure("influxdb.ping", "The server answered " + ping.status() + " to /ping: " + said
          + ". Check that the URL is InfluxDB's address.", null);
    }
    String version = ping.header("X-Influxdb-Version");
    String build = ping.header("X-Influxdb-Build");
    Family detected = detect(version, build);
    String server = serverName(version, build);
    if (detected != null && detected != s.family()) {
      return TestResult.failure("influxdb.version", "This server is " + server + ", not " + s.family().label
          + ": choose " + choice(detected) + " as the connection's server, then test again.", null);
    }
    checks.add(new TestResult.Check("server", true, latency, server));

    long listed = System.nanoTime();
    Databases all;
    try {
      all = databases(c);
    } catch (ConnectorException e) {
      return TestResult.failure(code(e), e.getMessage(), null).withChecks(checks);
    }
    List<String> visible = all.names().stream().filter(s::allows).toList();
    String noun = s.family() == Family.V2 ? "bucket" : "database";
    List<String> first = visible.subList(0, Math.min(visible.size(), 5));
    checks.add(new TestResult.Check("databases", true, ms(listed), count(visible.size(), noun) + " agents may use"
        + (first.isEmpty() ? "" : ": " + String.join(", ", first) + (visible.size() > first.size() ? ", …" : ""))));

    long probed = System.nanoTime();
    TestResult.Permissions permissions = permissions(c, build, all);
    checks.add(new TestResult.Check("permissions", true, ms(probed), permissions.read()));
    String identity = switch (s.auth()) {
      case TOKEN -> "API token";
      case PASSWORD -> s.username();
      case NONE -> "no credentials";
    };
    ObjectNode metadata = F.objectNode().put("serverVersion", server).put("family", s.family().id);
    if (version != null) {
      metadata.put("version", version);
    }
    if (build != null) {
      metadata.put("build", build);
    }
    TestResult result = TestResult.success(latency, server, identity, permissions).withChecks(checks)
        .withMetadata(metadata);
    for (Hint h : hints(cfg.env(), s, build, visible.size(), permissions.level())) {
      result = result.withHint(h);
    }
    return result;
  }

  private static String code(ConnectorException e) {
    return switch (e.kind()) {
      case RESOURCE_UNAVAILABLE -> "influxdb.unreachable";
      case DENIED -> "influxdb.address";
      case TIMEOUT -> "influxdb.timeout";
      default -> "influxdb.read";
    };
  }

  /** The API family a server's {@code /ping} headers name: 1.x, v2.x, 3.x (Core, Enterprise); {@code null}: none. */
  static Family detect(String version, String build) {
    String v = version == null ? "" : version.strip();
    if (v.startsWith("v")) {
      v = v.substring(1);
    }
    if (v.startsWith("1.")) {
      return Family.V1;
    }
    if (v.startsWith("2.")) {
      return Family.V2;
    }
    if (v.startsWith("3.") || "Core".equalsIgnoreCase(build) || "Enterprise".equalsIgnoreCase(build)) {
      return Family.V3;
    }
    return null;
  }

  private static String serverName(String version, String build) {
    if (version == null || version.isBlank()) {
      return "InfluxDB (version not reported)";
    }
    String v = version.strip();
    return "InfluxDB " + (v.startsWith("v") ? v.substring(1) : v) + (build == null || build.isBlank() ? ""
        : " " + build.strip());
  }

  private static String choice(Family family) {
    return switch (family) {
      case V3 -> "InfluxDB 3 Core or Enterprise";
      case V2 -> "InfluxDB 2.x or InfluxDB Cloud";
      case V1 -> "InfluxDB 1.x";
    };
  }

  /** What the credentials may do, as far as the server tells: never a write to find out. */
  private TestResult.Permissions permissions(Call c, String build, Databases all) {
    InfluxSettings s = c.settings();
    if (s.auth() == AuthMethod.NONE) {
      return new TestResult.Permissions(TestResult.Level.ADMIN, "The server checks no credentials: anyone who "
          + "reaches it may read, write and delete.", null, List.of(), null);
    }
    switch (s.family()) {
      case V3 -> {
        if ("Core".equalsIgnoreCase(build)) {
          return new TestResult.Permissions(TestResult.Level.ADMIN, "InfluxDB 3 Core has admin tokens only: this "
              + "token may also write and delete.", null, List.of("admin token"), null);
        }
        return all.configurationApi()
            ? new TestResult.Permissions(TestResult.Level.ADMIN, "The token may use the configuration API: an admin "
                + "token, which may also write and delete.", null, List.of("admin token"), null)
            : new TestResult.Permissions(TestResult.Level.UNKNOWN, "A resource token: its database permissions "
                + "decide what it may read and write.", null, List.of(), null);
      }
      case V2 -> {
        try {
          Response r = http.send(c, Request.get("/api/v2/users", "InfluxDB users", "limit", "1"), 1 << 20);
          if (r.ok() && InfluxHttp.json(r, "InfluxDB users").path("users").size() > 0) {
            return new TestResult.Permissions(TestResult.Level.ADMIN, "The token can read the organization's users: "
                + "an all-access or operator token, which may also write and delete.", null,
                List.of("all-access token"), null);
          }
        } catch (ConnectorException e) {
          // InfluxDB Cloud Dedicated has no users API: nothing more to tell.
        }
        return new TestResult.Permissions(TestResult.Level.UNKNOWN, "A scoped token: it can't read users. InfluxDB "
            + "doesn't say which buckets it may write.", null, List.of(), null);
      }
      default -> {
        try {
          run(c, Language.INFLUXQL, null, "SHOW USERS", null, 1, 64 * 1024, "InfluxDB SHOW USERS");
          return new TestResult.Permissions(TestResult.Level.ADMIN, s.username() + " is an admin user: it may also "
              + "write, delete and manage users.", null, List.of("admin user"), null);
        } catch (ConnectorException e) {
          return new TestResult.Permissions(TestResult.Level.UNKNOWN, s.username() + " isn't an admin user: its "
              + "database grants (READ, WRITE, ALL) decide.", null, List.of(), null);
        }
      }
    }
  }

  static List<Hint> hints(Env env, InfluxSettings s, String build, int visible, TestResult.Level level) {
    List<Hint> out = new ArrayList<>();
    out.add(new Hint(Severity.INFO, "influxdb.read-boundary", null, "InfluxDB has no read-only session: GateRook "
        + "runs only what its own reading of a statement calls a read, never Flux, and the token's permissions are "
        + "the server's boundary. Give agents credentials that may only read their databases."));
    if (s.auth() == AuthMethod.NONE) {
      out.add(new Hint(Severity.WARNING, "influxdb.no-auth", "/auth/method", "The server checks no credentials: "
          + "anyone who reaches it may write and delete. Turn on its authentication and use a read-only token or "
          + "user."));
    } else if (level == TestResult.Level.ADMIN) {
      out.add(new Hint(env == Env.DEV ? Severity.INFO : Severity.WARNING, "influxdb.admin-credentials",
          s.auth() == AuthMethod.PASSWORD ? "/auth/username" : "/auth/token", "These credentials may also write and "
          + "delete. Give agents ones that may only read: a resource token on InfluxDB 3 Enterprise, a read token "
          + "on 2.x, a user with READ grants on 1.x."));
    }
    if (s.plainHttp()) {
      out.add(new Hint(Severity.INFO, "influxdb.plain-http", "/url", "Plain http:// on this computer: the "
          + "credentials travel unencrypted over loopback."));
    }
    if (s.family() == Family.V2 && s.organization() == null && "OSS".equalsIgnoreCase(build)) {
      out.add(new Hint(Severity.WARNING, "influxdb.organization", "/organization", "InfluxDB OSS needs the "
          + "organization for writes: set it, or influxdb_write fails."));
    }
    if (visible == 0) {
      out.add(new Hint(Severity.WARNING, "influxdb.no-databases", "/databases", s.databases().isEmpty()
          ? "The credentials see no database." : "No database the credentials see matches the connection's list."));
    }
    return out;
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      InfluxSettings s = InfluxSettings.of(cfg);
      String path = s.family() == Family.V1 ? "/ping" : "/health";
      long started = System.nanoTime();
      Response r = http.send(new Call(cfg, s, secrets, ctx), Request.get(path, "InfluxDB health"),
          InfluxHttp.MAX_ERROR_BODY);
      if (!r.ok()) {
        return HealthResult.unreachable("InfluxDB answered " + r.status() + " to " + path + ": "
            + Errors.message(r.body()) + ".");
      }
      return HealthResult.healthy(ms(started));
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode();
    String version = Objects.requireNonNullElse(cfg.string("/version"), "3");
    out.put("server", switch (version) {
      case "1" -> "InfluxDB 1.x";
      case "2" -> "InfluxDB 2.x";
      default -> "InfluxDB 3";
    });
    List<String> databases = cfg.strings("/databases");
    if (databases.isEmpty()) {
      out.put("databases", "Every database the credentials may read");
    } else {
      ArrayNode a = out.putArray("databases");
      databases.forEach(a::add);
    }
    out.put("reads", version.equals("3")
        ? "One SQL (default) or InfluxQL read per influxdb_query, with the database named; Flux isn't supported"
        : "One InfluxQL SELECT or SHOW per influxdb_query, with the database named; Flux isn't supported");
    out.put("changes", "Line protocol points to one database per influxdb_write, after approval");
    if (object != null && !object.isBlank()) {
      out.put("measurement", "Call influxdb_describe with measurement " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    http.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    http.evict(connectionId);
  }

  @Override
  public void close() {
    http.close();
  }

  private static long ms(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
