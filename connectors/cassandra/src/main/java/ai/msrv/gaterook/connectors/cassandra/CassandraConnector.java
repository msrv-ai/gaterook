package ai.msrv.gaterook.connectors.cassandra;

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
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.BuiltIn;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Apache Cassandra and CQL-compatible services (docs/cassandra.md): {@code cassandra_list_keyspaces},
 * {@code cassandra_describe_table} and {@code cassandra_query} read; {@code cassandra_execute} runs one INSERT, UPDATE,
 * DELETE, TRUNCATE or table, index, type or view DDL statement that a person approved.
 *
 * <p>Cassandra has no read-only session. What keeps a read from writing is {@link #classify}, which reads each
 * statement by CQL's own tokens ({@link Cql}) and routes only a {@code SELECT} of an allowed keyspace to
 * {@code cassandra_query}, and the grants of the role GateRook signs in as: Test connection reads them where the
 * cluster allows and advises a role with {@code SELECT} only. A change runs only the approved payload, once: the
 * statement is non-idempotent, its profile never retries and has no speculative executions, and an answer lost after
 * it was sent is an unknown outcome.
 */
public final class CassandraConnector implements Connector {

  static final String TYPE = "cassandra";
  static final int DEFAULT_LIMIT = 100;
  /** Rows of one page fetch: an answer cut at the response size resumes in the same page. */
  static final int MAX_FETCH = 20_000;
  /** Keyspaces, tables and views one listing reads from system_schema. */
  static final int MAX_LISTED = 20_000;
  static final Duration MARGIN = Duration.ofMillis(250);
  static final Duration SCHEMA_AGREEMENT = Duration.ofSeconds(5);
  /** Characters of an approval's values its field shows; a warning says when there are more. */
  static final int SHOWN_VALUES = 2_000;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final CassandraSessions sessions = new CassandraSessions();
  private final ConnectorManifest manifest;

  public CassandraConnector() {
    SchemaResources schemas = SchemaResources.of(CassandraConnector.class, TYPE);
    this.manifest = ConnectorManifest.builder(TYPE, BuiltIn.VERSION, ConnectorManifest.Kind.BUILT_IN)
        .name("Apache Cassandra")
        .icon("database")
        .group("Databases")
        .description("Keyspaces, tables and CQL reads on Apache Cassandra and CQL-compatible services. Changes wait "
            + "for approval.")
        .configSchema(schemas.load("config"))
        .tools(CassandraTools.definitions(schemas))
        .build();
  }

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  // ── Classification ──────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    CassandraSettings s;
    try {
      s = CassandraSettings.of(cfg);
    } catch (ConnectorException e) {
      return Classification.deny(policy.rule("bad-config"), e.getMessage(), "Complete the connection's settings.");
    }
    Env env = policy.env() == null ? cfg.env() : policy.env();
    switch (call.tool()) {
      case CassandraTools.LIST_KEYSPACES -> {
        String keyspace = call.string("keyspace");
        if (keyspace == null) {
          return Classification.read();
        }
        if (!CassandraSettings.KEYSPACE.matcher(keyspace).matches()) {
          return deny(policy, new CassandraRules.Refusal("bad-argument", keyspace + " isn't a keyspace name.",
              "Leave keyspace out to list them all."));
        }
        CassandraRules.Refusal r = CassandraRules.keyspace(new Cql.Name(keyspace, null), s, false, env);
        return r == null ? Classification.read() : deny(policy, r);
      }
      case CassandraTools.DESCRIBE_TABLE -> {
        Cql.Name name = Cql.tableName(call.string("table"));
        if (name == null) {
          return deny(policy, new CassandraRules.Refusal("bad-argument", "Name a table: keyspace.table, or table "
              + "in the connection's keyspace.", "Call cassandra_list_keyspaces to see the tables."));
        }
        CassandraRules.Refusal r = CassandraRules.keyspace(name, s, false, env);
        return r == null ? Classification.read() : deny(policy, r);
      }
      case CassandraTools.QUERY, CassandraTools.EXECUTE -> {
        boolean read = CassandraTools.QUERY.equals(call.tool());
        CassandraRules.Refusal values = CassandraRules.values(call.arguments().get("values"));
        if (values != null) {
          return deny(policy, values);
        }
        Cql.Statement st = Cql.read(call.string("statement"));
        CassandraRules.Refusal r = CassandraRules.statement(st, s, read, env);
        if (r != null) {
          return deny(policy, r);
        }
        if (read) {
          return Classification.read();
        }
        ObjectNode facts = F.objectNode().put("statement", st.verb()).put("target", target(st, s));
        if (st.conditional()) {
          facts.put("conditional", true);
        }
        return Classification.mutation(st.verb() + " on " + target(st, s) + " changes "
            + (st.kind().ddl() ? "the schema" : "data"), facts);
      }
      default -> {
        return Classification.deny(policy.rule("unknown-tool"), manifest.name() + " has no tool " + call.tool() + ".",
            null);
      }
    }
  }

  private static Classification deny(PolicyView policy, CassandraRules.Refusal r) {
    return Classification.deny(policy.rule(r.rule()), r.reason(), r.hint());
  }

  /** {@code ks.table} of a statement, the connection's keyspace filled in where the statement names none. */
  static String target(Cql.Statement st, CassandraSettings s) {
    if (st.target() == null) {
      return "";
    }
    Cql.Name n = st.target();
    return n.qualified() || s.keyspace() == null ? n.text() : new Cql.Name(s.keyspace(), n.name()).text();
  }

  // ── The exact action ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    String keyspace = cfg.string("/keyspace");
    String where = cfg.name() + " · " + Objects.requireNonNullElse(cfg.string("/localDataCenter"), "?");
    JsonNode values = call.arguments().get("values");
    List<Field> fields = new ArrayList<>();
    if (values != null && !values.isNull() && !values.isEmpty()) {
      fields.add(new Field("Values", shorten(values.toString(), SHOWN_VALUES)));
    }
    if (CassandraTools.EXECUTE.equals(call.tool())) {
      String statement = Objects.requireNonNullElse(call.string("statement"), "");
      Cql.Statement st = Cql.read(statement);
      String target = st.target() == null ? "" : st.target().qualified() || keyspace == null ? st.target().text()
          : new Cql.Name(keyspace, st.target().name()).text();
      ObjectNode payload = F.objectNode().put("statement", statement);
      if (values != null && !values.isNull()) {
        payload.set("values", values.deepCopy());
      }
      if (keyspace == null) {
        payload.putNull("keyspace");
      } else {
        payload.put("keyspace", keyspace);
      }
      if (st.target() != null && !st.target().qualified() && keyspace != null) {
        fields.add(new Field("Keyspace", keyspace + " (the connection's, for names without one)"));
      }
      fields.add(new Field("Consistency", "LOCAL_QUORUM" + (st.conditional() ? ", serial LOCAL_SERIAL" : "")));
      List<Field> impact = new ArrayList<>();
      impact.add(new Field("Target", target.isEmpty() ? "Unknown: not analyzed" : target));
      if (st.conditional()) {
        impact.add(new Field("Condition", "A lightweight transaction: it applies only if its IF holds, and the "
            + "answer says [applied]"));
      }
      if (st.kind().ddl()) {
        impact.add(new Field("Schema", "Every node applies the change; GateRook waits up to "
            + SCHEMA_AGREEMENT.toSeconds() + " s for them to agree"));
      }
      impact.add(new Field("Retried", "No: sent to one node once; an answer lost after that is reported as unknown"));
      List<String> warnings = warnings(st, target);
      if (values != null && values.toString().length() > SHOWN_VALUES) {
        warnings.add("Values shows the first " + SHOWN_VALUES + " of " + values.toString().length() + " characters; "
            + "the change binds all of them.");
      }
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", statement, where + (target.isEmpty() ? "" : " · "
              + target), shorten(CassandraTools.EXECUTE + " on " + cfg.name() + ": " + st.verb() + " " + target, 120))
          .withFields(fields)
          .withImpact(impact)
          .withWarnings(warnings)
          .withFacets(Map.of("statement", st.verb(), "target", target))
          .withPayload(payload);
    }
    // A read tool an endpoint put in Approval: its arguments are the whole action.
    ObjectNode args = call.arguments().deepCopy();
    if (CassandraTools.QUERY.equals(call.tool())) {
      return ActionPreview.of(ActionPreview.Kind.SQL, "sql", Objects.requireNonNullElse(call.string("statement"), ""),
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
        .withImpact(List.of(new Field("Reads", "Schema, no change")));
  }

  private static List<String> warnings(Cql.Statement st, String target) {
    List<String> out = new ArrayList<>();
    switch (st.kind()) {
      case TRUNCATE -> out.add("TRUNCATE removes every row of " + target + " on every node. It can't be undone.");
      case DROP_TABLE, DROP_VIEW -> out.add(st.verb() + " removes " + target + " and all of its data. It can't be "
          + "undone.");
      case DROP_TYPE -> out.add("DROP TYPE removes the type " + target + "; tables that use it must not exist.");
      default -> {
        // No warning of its own.
      }
    }
    if (st.usingTimestamp()) {
      out.add("USING TIMESTAMP gives the write its own timestamp: one in the future keeps later writes from "
          + "replacing it, one in the past may make it lose to existing data.");
    }
    return out;
  }

  /**
   * Cassandra's own check before a person is asked: an INSERT, UPDATE or DELETE is prepared on the cluster, which
   * reads it as Cassandra would and names the types of its markers, and its values are bound to them. A statement the
   * cluster refuses, or values that don't fit, are never put to a person. Nothing runs.
   */
  @Override
  public PreparedAction prepare(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    ActionPreview preview = describeAction(call, cfg);
    if (!CassandraTools.EXECUTE.equals(call.tool())) {
      return PreparedAction.approval(preview, null);
    }
    Cql.Statement st = Cql.read(call.string("statement"));
    if (!st.kind().dml()) {
      return PreparedAction.approval(preview, null);
    }
    CassandraSettings s = CassandraSettings.of(cfg);
    try {
      CqlSession session = sessions.session(cfg, s, secrets, ctx);
      bind(session, call.string("statement"), call.arguments().get("values"), ctx, true);
    } catch (ConnectorException e) {
      if (e.kind() == ConnectorException.Kind.BAD_INPUT || e.kind() == ConnectorException.Kind.DENIED
          || e.kind() == ConnectorException.Kind.RESOURCE_ERROR) {
        return PreparedAction.reject(e.getMessage());
      }
      throw e;
    }
    List<Field> impact = new ArrayList<>(preview.impact());
    impact.addFirst(new Field("Checked", "Cassandra prepared the statement, and its values fit the columns' types"));
    return PreparedAction.approval(preview.withImpact(impact), null);
  }

  private static String shorten(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // ── Execution ───────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public ToolResult execute(ToolCall call, ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    CassandraSettings s = CassandraSettings.of(cfg);
    return switch (call.tool()) {
      case CassandraTools.LIST_KEYSPACES -> listKeyspaces(cfg, s, secrets, ctx, arguments(call, ctx));
      case CassandraTools.DESCRIBE_TABLE -> describeTable(cfg, s, secrets, ctx, arguments(call, ctx));
      case CassandraTools.QUERY -> query(cfg, s, secrets, ctx, arguments(call, ctx));
      case CassandraTools.EXECUTE -> change(cfg, s, secrets, ctx);
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

  /** Defence in depth: {@link #execute} gets no policy, so the connection's rules are checked again. */
  private static void check(CassandraRules.Refusal r) throws ConnectorException {
    if (r != null) {
      throw ConnectorException.denied(r.reason() + (r.hint() == null ? "" : " " + r.hint()));
    }
  }

  /** How long a read may run: the stricter of the connection's and the policy's statement timeout, within the call. */
  private static Duration readTimeout(CassandraSettings s, ExecContext ctx) {
    Duration t = ctx.remaining().minus(MARGIN);
    Duration policy = ctx.limits().statementTimeout();
    if (policy != null && !policy.isZero() && policy.compareTo(t) < 0) {
      t = policy;
    }
    if (s.statementTimeout() != null && s.statementTimeout().compareTo(t) < 0) {
      t = s.statementTimeout();
    }
    return t.compareTo(Duration.ofMillis(100)) < 0 ? Duration.ofMillis(100) : t;
  }

  /** Runs {@code statement} and waits for its first page within the call; cancelling the call cancels it. */
  private static AsyncResultSet run(CqlSession session, Statement<?> statement, ExecContext ctx, String what)
      throws ConnectorException {
    CompletableFuture<AsyncResultSet> f = session.executeAsync(statement).toCompletableFuture();
    return await(f, ctx, what, false);
  }

  private static <T> T await(CompletableFuture<T> f, ExecContext ctx, String what, boolean change)
      throws ConnectorException {
    ctx.onCancel(() -> f.cancel(true));
    try {
      return f.get(Math.max(1, ctx.remaining().toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      f.cancel(true);
      if (change) {
        throw ConnectorException.outcomeUnknown("The outcome is unknown: " + what + " got no answer within the "
            + "call's time. Check the data before you try again; GateRook doesn't retry a change.", e);
      }
      throw ConnectorException.timeout(what + " didn't answer within the call's time. Narrow it with a partition "
          + "key or a smaller limit.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      f.cancel(true);
      if (change) {
        throw ConnectorException.outcomeUnknown("The outcome is unknown: the call was stopped while " + what
            + " was on its way. Check the data before you try again.", e);
      }
      throw ConnectorException.timeout("The call was stopped while " + what + " ran.");
    } catch (ExecutionException e) {
      throw change ? CassandraErrors.change(e.getCause(), what) : CassandraErrors.read(e.getCause(), what);
    } catch (java.util.concurrent.CancellationException e) {
      throw change ? CassandraErrors.change(e, what) : ConnectorException.timeout("The call was stopped while "
          + what + " ran.");
    }
  }

  /**
   * {@code statement} with {@code values} bound to the types its markers have on the cluster (the driver prepares it
   * once per session); without values, the plain statement, prepared too when {@code check}. Nothing is sent but the
   * preparation.
   */
  private static Statement<?> bind(CqlSession session, String statement, JsonNode values, ExecContext ctx,
      boolean check) throws ConnectorException {
    if ((values == null || values.isNull() || values.isEmpty()) && !check) {
      return SimpleStatement.newInstance(statement);
    }
    if (values == null || values.isNull()) {
      values = F.arrayNode();
    }
    PreparedStatement prepared = await(session.prepareAsync(statement).toCompletableFuture(), ctx,
        "Preparing the statement", false);
    ColumnDefinitions variables = prepared.getVariableDefinitions();
    CodecRegistry codecs = session.getContext().getCodecRegistry();
    BoundStatementBuilder b = prepared.boundStatementBuilder();
    try {
      if (values.isArray()) {
        if (values.size() != variables.size()) {
          throw ConnectorException.badInput("The statement has " + variables.size() + " marker"
              + (variables.size() == 1 ? "" : "s") + " and the call " + values.size() + " value"
              + (values.size() == 1 ? "" : "s") + ".");
        }
        for (int i = 0; i < variables.size(); i++) {
          b = set(b, i, variables.get(i).getType(), Values.java(variables.get(i).getType(), values.get(i),
              "values[" + i + "]", codecs, 0), codecs);
        }
      } else {
        List<String> used = new ArrayList<>();
        for (int i = 0; i < variables.size(); i++) {
          String name = variables.get(i).getName().asInternal();
          if (!values.has(name)) {
            throw ConnectorException.badInput("values has no " + name + ": a named marker :" + name + " needs it. "
                + "Use an array for ? markers.");
          }
          used.add(name);
          b = set(b, i, variables.get(i).getType(), Values.java(variables.get(i).getType(), values.get(name),
              "values." + name, codecs, 0), codecs);
        }
        for (Map.Entry<String, JsonNode> e : values.properties()) {
          if (!used.contains(e.getKey())) {
            throw ConnectorException.badInput("values." + e.getKey() + " isn't a marker of the statement.");
          }
        }
      }
    } catch (Values.BadValue e) {
      throw ConnectorException.badInput(e.getMessage());
    } catch (IllegalArgumentException | ClassCastException e) {
      throw ConnectorException.badInput("A value doesn't fit its marker: " + OutputSanitizer.sanitize(
          CassandraErrors.cut(String.valueOf(e.getMessage()), 300)) + ".");
    }
    return b.build();
  }

  private static BoundStatementBuilder set(BoundStatementBuilder b, int i, DataType type, Object v,
      CodecRegistry codecs) {
    TypeCodec<Object> codec = codecs.codecFor(type);
    return v == null ? b.setToNull(i) : b.set(i, v, codec);
  }

  private ToolResult query(ConnectionConfig cfg, CassandraSettings s, SecretResolver secrets, ExecContext ctx,
      JsonNode args) throws ConnectorException {
    String statement = args.path("statement").asString("");
    Cql.Statement st = Cql.read(statement);
    check(CassandraRules.statement(st, s, true, ctx.env()));
    JsonNode values = args.get("values");
    check(CassandraRules.values(values));
    int limit = Math.max(1, Math.min(args.path("limit").asInt(DEFAULT_LIMIT), ctx.limits().maxRows()));
    ObjectNode request = F.objectNode().put("statement", statement).put("consistency", s.readConsistency());
    request.set("values", values == null ? F.nullNode() : values);
    request.put("keyspace", s.keyspace());
    String hash = Cursors.hash(request);
    ByteBuffer state = null;
    int skip = 0;
    if (args.path("cursor").isString()) {
      JsonNode at = Cursors.open(ctx, CassandraTools.QUERY, args.get("cursor").asString(), cfg.id(), hash);
      if (at.path("s").isString()) {
        try {
          state = ByteBuffer.wrap(Base64.getDecoder().decode(at.get("s").asString()));
        } catch (IllegalArgumentException e) {
          throw ConnectorException.badInput("The cursor is invalid. Start again without cursor.");
        }
      }
      skip = Math.max(0, at.path("k").asInt(0));
    }
    CqlSession session = sessions.session(cfg, s, secrets, ctx);
    Statement<?> stmt = bind(session, statement, values, ctx, false)
        .setPageSize(Math.min(MAX_FETCH, skip + limit))
        .setPagingState(state)
        .setConsistencyLevel(DefaultConsistencyLevel.valueOf(s.readConsistency()))
        .setIdempotent(true)
        .setTimeout(readTimeout(s, ctx));
    AsyncResultSet rs = run(session, stmt, ctx, "The query");
    ColumnDefinitions defs = rs.getColumnDefinitions();
    List<ToolResult.Column> columns = new ArrayList<>();
    for (ColumnDefinition d : defs) {
      columns.add(new ToolResult.Column(d.getName().asInternal(), Values.cql(d.getType()),
          d.getKeyspace().asInternal(), d.getTable().asInternal(), d.getName().asInternal()));
    }
    int cell = ctx.limits().maxCellChars();
    long budget = Math.max(4_096, ctx.limits().maxResponseBytes() * 3 / 4 - 2_048);
    long used = 0;
    ArrayNode rows = F.arrayNode();
    int seen = 0;
    boolean cut = false;
    for (Row row : rs.currentPage()) {
      if (seen++ < skip) {
        continue;
      }
      if (rows.size() >= limit) {
        break;
      }
      ArrayNode cells = F.arrayNode();
      for (int i = 0; i < defs.size(); i++) {
        cells.add(Values.cell(row, i, defs.get(i).getType(), cell));
      }
      used += cells.toString().length();
      if (used > budget && !rows.isEmpty()) {
        cut = true;
        break;
      }
      rows.add(cells);
    }
    String next = null;
    if (cut) {
      ObjectNode at = F.objectNode().put("k", skip + rows.size());
      at.put("s", state == null ? null : Base64.getEncoder().encodeToString(bytes(state)));
      next = Cursors.seal(ctx, CassandraTools.QUERY, cfg.id(), hash, at);
    } else if (rs.hasMorePages() && rs.getExecutionInfo().getPagingState() != null) {
      // The next page; rows still to skip (a page shorter than the skip) carry over into it.
      ObjectNode at = F.objectNode().put("k", Math.max(0, skip - seen));
      at.put("s", Base64.getEncoder().encodeToString(bytes(rs.getExecutionInfo().getPagingState())));
      next = Cursors.seal(ctx, CassandraTools.QUERY, cfg.id(), hash, at);
    }
    ObjectNode facts = F.objectNode().put("consistency", s.readConsistency()).put("rows", rows.size());
    List<String> warnings = rs.getExecutionInfo().getWarnings();
    if (!warnings.isEmpty()) {
      ArrayNode w = facts.putArray("warnings");
      warnings.stream().limit(5).forEach(x -> w.add(OutputSanitizer.sanitize(CassandraErrors.cut(x, 500))));
    }
    ToolResult result = ToolResult.table(new ToolResult.Table(List.copyOf(columns), rows, next), facts);
    return cut ? result.truncated("size_limit") : result;
  }

  private static byte[] bytes(ByteBuffer b) {
    ByteBuffer d = b.duplicate();
    byte[] out = new byte[d.remaining()];
    d.get(out);
    return out;
  }

  private ToolResult change(ConnectionConfig cfg, CassandraSettings s, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    if (!ctx.approved()) {
      throw ConnectorException.denied("Not approved: cassandra_execute changes data or schema, so a person approves "
          + "it first.");
    }
    JsonNode payload = ctx.approvedPayload().orElseThrow(() -> ConnectorException.denied("The approved action "
        + "carries no statement."));
    String statement = payload.path("statement").asString("");
    Cql.Statement st = Cql.read(statement);
    check(CassandraRules.statement(st, s, false, ctx.env()));
    if (!st.kind().change()) {
      throw ConnectorException.denied("cassandra_execute runs one analyzed INSERT, UPDATE, DELETE, TRUNCATE or DDL "
          + "statement.");
    }
    boolean unqualified = (st.target() != null && !st.target().qualified())
        || (st.base() != null && !st.base().qualified());
    String approvedKeyspace = payload.path("keyspace").isString() ? payload.get("keyspace").asString() : null;
    if (unqualified && !Objects.equals(approvedKeyspace, s.keyspace())) {
      throw ConnectorException.denied("The connection's keyspace changed since the change was approved, and the "
          + "statement doesn't name its keyspace: ask for approval again.");
    }
    JsonNode values = payload.get("values");
    check(CassandraRules.values(values));
    CqlSession session = sessions.session(cfg, s, secrets, ctx);
    // Preparing and binding send nothing that changes data: a failure there changed nothing.
    Statement<?> stmt = bind(session, statement, values, ctx, false);
    Duration left = ctx.remaining().minus(MARGIN);
    if (st.kind().ddl() && left.compareTo(SCHEMA_AGREEMENT.plusSeconds(2)) > 0) {
      left = left.minus(SCHEMA_AGREEMENT);
    }
    stmt = stmt.setExecutionProfileName(CassandraSessions.CHANGE)
        .setIdempotent(false)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
        .setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL)
        .setTimeout(left.compareTo(Duration.ofMillis(100)) < 0 ? Duration.ofMillis(100) : left);
    String target = target(st, s);
    String what = st.verb() + " on " + target;
    AsyncResultSet rs = await(session.executeAsync(stmt).toCompletableFuture(), ctx, what, true);
    ObjectNode out = F.objectNode().put("statement", st.verb()).put("target", target);
    StringBuilder text = new StringBuilder();
    if (st.conditional()) {
      boolean applied = rs.wasApplied();
      out.put("applied", applied);
      if (!applied) {
        Row row = rs.one();
        if (row != null) {
          ObjectNode current = out.putObject("current");
          ColumnDefinitions defs = row.getColumnDefinitions();
          for (int i = 0; i < defs.size(); i++) {
            String name = defs.get(i).getName().asInternal();
            if (!name.equals("[applied]")) {
              current.set(name, Values.cell(row, i, defs.get(i).getType(), ctx.limits().maxCellChars()));
            }
          }
        }
      }
      text.append(applied ? "Applied: " + what + "." : "Not applied: the condition of " + what + " didn't hold"
          + (out.has("current") ? "; current values: " + out.get("current") : "") + ".");
    } else {
      out.put("committed", true);
      text.append(st.verb()).append(" on ").append(target).append(" done.");
    }
    if (st.kind().ddl()) {
      boolean agreed = rs.getExecutionInfo().isSchemaInAgreement();
      out.put("schemaInAgreement", agreed);
      if (!agreed) {
        text.append(" The nodes didn't agree on the schema within ").append(SCHEMA_AGREEMENT.toSeconds())
            .append(" s yet; they will shortly.");
      }
    }
    List<String> warnings = rs.getExecutionInfo().getWarnings();
    if (!warnings.isEmpty()) {
      ArrayNode w = out.putArray("warnings");
      warnings.stream().limit(5).forEach(x -> w.add(OutputSanitizer.sanitize(CassandraErrors.cut(x, 500))));
    }
    return ToolResult.json(out, text.toString());
  }

  // ── Schema ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The rows of one of the gateway's own statements, page after page up to {@code max}, at {@code LOCAL_ONE} and
   * within the call (or the statement's own timeout).
   */
  static List<Row> rows(CqlSession session, SimpleStatement statement, int max, ExecContext ctx, String what)
      throws ConnectorException {
    Duration timeout = ctx.remaining().minus(MARGIN);
    if (statement.getTimeout() != null && statement.getTimeout().compareTo(timeout) < 0) {
      timeout = statement.getTimeout();
    }
    statement = statement.setPageSize(Math.min(max, 5_000)).setIdempotent(true)
        .setConsistencyLevel(ConsistencyLevel.LOCAL_ONE)
        .setTimeout(timeout.compareTo(Duration.ofMillis(100)) < 0 ? Duration.ofMillis(100) : timeout);
    List<Row> out = new ArrayList<>();
    AsyncResultSet rs = run(session, statement, ctx, what);
    while (true) {
      for (Row r : rs.currentPage()) {
        if (out.size() >= max) {
          return out;
        }
        out.add(r);
      }
      if (!rs.hasMorePages() || out.size() >= max) {
        return out;
      }
      rs = await(rs.fetchNextPage().toCompletableFuture(), ctx, what, false);
    }
  }

  private ToolResult listKeyspaces(ConnectionConfig cfg, CassandraSettings s, SecretResolver secrets,
      ExecContext ctx, JsonNode args) throws ConnectorException {
    String only = args.path("keyspace").asString(null);
    if (only != null) {
      check(CassandraRules.keyspace(new Cql.Name(only, null), s, false, ctx.env()));
    }
    CqlSession session = sessions.session(cfg, s, secrets, ctx);
    Map<String, ObjectNode> keyspaces = new LinkedHashMap<>();
    SimpleStatement ks = only == null
        ? SimpleStatement.newInstance("SELECT keyspace_name, replication FROM system_schema.keyspaces")
        : SimpleStatement.newInstance("SELECT keyspace_name, replication FROM system_schema.keyspaces WHERE "
            + "keyspace_name = ?", only);
    for (Row r : rows(session, ks, MAX_LISTED, ctx, "Listing keyspaces")) {
      String name = r.getString("keyspace_name");
      if (name == null || !s.allows(name)) {
        continue;
      }
      ObjectNode k = F.objectNode().put("keyspace", name);
      Map<String, String> replication = r.isNull("replication") ? Map.of()
          : r.getMap("replication", String.class, String.class);
      ObjectNode rep = k.putObject("replication");
      replication.forEach((key, value) -> rep.put(key, key.equals("class") ? shortClass(value) : value));
      k.putArray("tables");
      keyspaces.put(name, k);
    }
    SimpleStatement tables = only == null
        ? SimpleStatement.newInstance("SELECT keyspace_name, table_name FROM system_schema.tables")
        : SimpleStatement.newInstance("SELECT keyspace_name, table_name FROM system_schema.tables WHERE "
            + "keyspace_name = ?", only);
    int listed = 0;
    boolean more = false;
    for (Row r : rows(session, tables, MAX_LISTED, ctx, "Listing tables")) {
      ObjectNode k = keyspaces.get(r.getString("keyspace_name"));
      if (k == null) {
        continue;
      }
      if (listed++ >= 2_000) {
        more = true;
        continue;
      }
      ((ArrayNode) k.get("tables")).add(r.getString("table_name"));
    }
    try {
      SimpleStatement views = only == null
          ? SimpleStatement.newInstance("SELECT keyspace_name, view_name, base_table_name FROM system_schema.views")
          : SimpleStatement.newInstance("SELECT keyspace_name, view_name, base_table_name FROM system_schema.views "
              + "WHERE keyspace_name = ?", only);
      for (Row r : rows(session, views, MAX_LISTED, ctx, "Listing views")) {
        ObjectNode k = keyspaces.get(r.getString("keyspace_name"));
        if (k != null) {
          (k.has("views") ? (ArrayNode) k.get("views") : k.putArray("views")).addObject()
              .put("view", r.getString("view_name")).put("table", r.getString("base_table_name"));
        }
      }
    } catch (ConnectorException e) {
      // Some services keep no views table; the keyspaces and tables are the answer.
    }
    ObjectNode out = F.objectNode();
    if (s.keyspace() != null) {
      out.put("defaultKeyspace", s.keyspace());
    }
    ArrayNode list = out.putArray("keyspaces");
    keyspaces.values().forEach(list::add);
    if (more) {
      out.put("truncated", "Only the first 2,000 tables are listed: pass keyspace to list one keyspace.");
    }
    StringBuilder text = new StringBuilder();
    if (keyspaces.isEmpty()) {
      text.append(only == null ? "No keyspaces agents may use." : "No keyspace " + only + ".");
    }
    for (ObjectNode k : keyspaces.values()) {
      if (!text.isEmpty()) {
        text.append('\n');
      }
      List<String> names = new ArrayList<>();
      k.get("tables").forEach(t -> names.add(t.asString()));
      text.append(k.get("keyspace").asString()).append(" (").append(k.path("replication").path("class").asString("?"))
          .append("): ").append(names.isEmpty() ? "no tables" : String.join(", ", names));
    }
    return ToolResult.json(out, text.toString());
  }

  private static String shortClass(String c) {
    int dot = c.lastIndexOf('.');
    return dot < 0 ? c : c.substring(dot + 1);
  }

  private ToolResult describeTable(ConnectionConfig cfg, CassandraSettings s, SecretResolver secrets,
      ExecContext ctx, JsonNode args) throws ConnectorException {
    Cql.Name name = Cql.tableName(args.path("table").asString(null));
    if (name == null) {
      throw ConnectorException.badInput("Name a table: keyspace.table, or table in the connection's keyspace.");
    }
    check(CassandraRules.keyspace(name, s, false, ctx.env()));
    String ks = name.qualified() ? name.keyspace() : s.keyspace();
    String table = name.name();
    CqlSession session = sessions.session(cfg, s, secrets, ctx);
    List<Row> found = rows(session, SimpleStatement.newInstance("SELECT * FROM system_schema.tables WHERE "
        + "keyspace_name = ? AND table_name = ?", ks, table), 1, ctx, "Describing the table");
    String kind = "table";
    String base = null;
    if (found.isEmpty()) {
      try {
        found = rows(session, SimpleStatement.newInstance("SELECT * FROM system_schema.views WHERE keyspace_name = ? "
            + "AND view_name = ?", ks, table), 1, ctx, "Describing the view");
      } catch (ConnectorException e) {
        found = List.of();
      }
      if (found.isEmpty()) {
        throw ConnectorException.badInput("No table or view " + new Cql.Name(ks, table).text() + ". Call "
            + "cassandra_list_keyspaces to see the tables.");
      }
      kind = "materialized view";
      base = found.getFirst().getString("base_table_name");
    }
    Row t = found.getFirst();
    ObjectNode out = F.objectNode().put("keyspace", ks).put("table", table).put("kind", kind);
    if (base != null) {
      out.put("baseTable", base);
    }
    List<Row> columns = rows(session, SimpleStatement.newInstance("SELECT column_name, kind, position, type, "
        + "clustering_order FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?", ks, table), 5_000,
        ctx, "Describing the columns");
    List<ObjectNode> partition = new ArrayList<>();
    List<ObjectNode> clustering = new ArrayList<>();
    List<ObjectNode> statics = new ArrayList<>();
    List<ObjectNode> regular = new ArrayList<>();
    for (Row c : columns) {
      String k = Objects.requireNonNullElse(c.getString("kind"), "regular").toLowerCase(Locale.ROOT);
      ObjectNode col = F.objectNode().put("name", c.getString("column_name")).put("type", c.getString("type"))
          .put("kind", k);
      int position = c.isNull("position") ? -1 : c.getInt("position");
      if (k.equals("clustering")) {
        col.put("order", Objects.requireNonNullElse(c.getString("clustering_order"), "asc").toLowerCase(Locale.ROOT));
      }
      col.put("position", position);
      switch (k) {
        case "partition_key" -> partition.add(col);
        case "clustering" -> clustering.add(col);
        case "static" -> statics.add(col);
        default -> regular.add(col);
      }
    }
    partition.sort((a, b) -> Integer.compare(a.get("position").asInt(), b.get("position").asInt()));
    clustering.sort((a, b) -> Integer.compare(a.get("position").asInt(), b.get("position").asInt()));
    statics.sort((a, b) -> a.get("name").asString().compareTo(b.get("name").asString()));
    regular.sort((a, b) -> a.get("name").asString().compareTo(b.get("name").asString()));
    ArrayNode cols = out.putArray("columns");
    for (List<ObjectNode> group : List.of(partition, clustering, statics, regular)) {
      for (ObjectNode col : group) {
        if (!col.get("kind").asString().equals("partition_key") && !col.get("kind").asString().equals("clustering")) {
          col.remove("position");
        }
        cols.add(col);
      }
    }
    ArrayNode pk = out.putArray("partitionKey");
    partition.forEach(c -> pk.add(c.get("name").asString()));
    ArrayNode ck = out.putArray("clustering");
    clustering.forEach(c -> ck.addObject().put("name", c.get("name").asString()).put("order",
        c.get("order").asString()));
    ObjectNode options = out.putObject("options");
    ColumnDefinitions defs = t.getColumnDefinitions();
    if (defs.contains("default_time_to_live") && !t.isNull("default_time_to_live")) {
      options.put("defaultTimeToLive", t.getInt("default_time_to_live"));
    }
    if (defs.contains("gc_grace_seconds") && !t.isNull("gc_grace_seconds")) {
      options.put("gcGraceSeconds", t.getInt("gc_grace_seconds"));
    }
    if (defs.contains("compaction") && !t.isNull("compaction")) {
      ObjectNode compaction = options.putObject("compaction");
      t.getMap("compaction", String.class, String.class).forEach((k, v) -> compaction.put(k, k.equals("class")
          ? shortClass(v) : v));
    }
    if (defs.contains("comment") && !t.isNull("comment") && !t.getString("comment").isEmpty()) {
      options.set("comment", Values.cut(t.getString("comment"), 500));
    }
    if (defs.contains("where_clause") && !t.isNull("where_clause")) {
      options.set("whereClause", Values.cut(t.getString("where_clause"), 2_000));
    }
    if (kind.equals("table")) {
      try {
        ArrayNode indexes = out.putArray("indexes");
        for (Row i : rows(session, SimpleStatement.newInstance("SELECT index_name, kind, options FROM "
            + "system_schema.indexes WHERE keyspace_name = ? AND table_name = ?", ks, table), 500, ctx,
            "Listing indexes")) {
          ObjectNode index = indexes.addObject().put("name", i.getString("index_name"))
              .put("kind", Objects.requireNonNullElse(i.getString("kind"), "").toLowerCase(Locale.ROOT));
          Map<String, String> o = i.isNull("options") ? Map.of() : i.getMap("options", String.class, String.class);
          if (o.containsKey("target")) {
            index.put("target", o.get("target"));
          }
          if (o.containsKey("class_name")) {
            index.put("class", shortClass(o.get("class_name")));
          }
        }
        ArrayNode views = out.putArray("views");
        for (Row v : rows(session, SimpleStatement.newInstance("SELECT view_name, base_table_name FROM "
            + "system_schema.views WHERE keyspace_name = ?", ks), 5_000, ctx, "Listing views")) {
          if (table.equals(v.getString("base_table_name"))) {
            views.add(v.getString("view_name"));
          }
        }
      } catch (ConnectorException e) {
        // Indexes and views are extra: a service without those tables still describes the table.
      }
    }
    return ToolResult.json(out, describeText(out));
  }

  /** {@code shop.orders (table): customer_id uuid partition, placed timestamp clustering desc, total decimal · …}. */
  static String describeText(ObjectNode d) {
    StringBuilder out = new StringBuilder(new Cql.Name(d.path("keyspace").asString(), d.path("table").asString())
        .text()).append(" (").append(d.path("kind").asString()).append("): ");
    List<String> cols = new ArrayList<>();
    for (JsonNode c : d.path("columns")) {
      String k = c.path("kind").asString();
      cols.add(c.path("name").asString() + " " + c.path("type").asString() + switch (k) {
        case "partition_key" -> " partition key";
        case "clustering" -> " clustering " + c.path("order").asString();
        case "static" -> " static";
        default -> "";
      });
    }
    out.append(String.join(", ", cols));
    if (d.path("indexes").size() > 0) {
      List<String> names = new ArrayList<>();
      d.path("indexes").forEach(i -> names.add(i.path("name").asString() + " on " + i.path("target").asString("?")));
      out.append(" · indexes: ").append(String.join(", ", names));
    }
    if (d.path("views").size() > 0) {
      List<String> names = new ArrayList<>();
      d.path("views").forEach(v -> names.add(v.asString()));
      out.append(" · views: ").append(String.join(", ", names));
    }
    int ttl = d.path("options").path("defaultTimeToLive").asInt(0);
    if (ttl > 0) {
      out.append(" · default TTL ").append(ttl).append(" s");
    }
    if (d.path("options").path("compaction").path("class").isString()) {
      out.append(" · ").append(d.path("options").path("compaction").path("class").asString());
    }
    return out.toString();
  }

  // ── Test connection, health, describe ───────────────────────────────────────────────────────────────────────

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return CassandraProbe.test(cfg, secrets, ctx);
  }

  @Override
  public HealthResult health(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    try {
      CassandraSettings s = CassandraSettings.of(cfg);
      long started = System.nanoTime();
      CqlSession session = sessions.session(cfg, s, secrets, ctx);
      Duration left = ctx.remaining().minus(MARGIN);
      run(session, SimpleStatement.newInstance("SELECT release_version FROM system.local")
          .setTimeout(left.compareTo(Duration.ofSeconds(2)) < 0 ? left.plus(MARGIN.dividedBy(2))
              : Duration.ofSeconds(2)), ctx, "The health check");
      return HealthResult.healthy((System.nanoTime() - started) / 1_000_000);
    } catch (ConnectorException e) {
      return HealthResult.unreachable(e.getMessage());
    }
  }

  @Override
  public Optional<JsonNode> describe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, String object) {
    ObjectNode out = F.objectNode();
    out.put("localDataCenter", cfg.string("/localDataCenter"));
    if (cfg.string("/keyspace") != null) {
      out.put("keyspace", cfg.string("/keyspace"));
    }
    List<String> keyspaces = cfg.strings("/keyspaces");
    if (keyspaces.isEmpty()) {
      out.put("keyspaces", "Every keyspace but Cassandra's own system keyspaces");
    } else {
      ArrayNode a = out.putArray("keyspaces");
      keyspaces.forEach(a::add);
    }
    out.put("consistency", Objects.requireNonNullElse(cfg.string("/consistency"), "LOCAL_ONE") + " for reads, "
        + "LOCAL_QUORUM for changes");
    out.put("reads", "One CQL SELECT per cassandra_query: name tables as keyspace.table"
        + (cfg.string("/keyspace") == null ? "" : " (or table, in " + cfg.string("/keyspace") + ")")
        + ", query by partition key, pass values for ? or :name markers; ALLOW FILTERING is refused");
    out.put("changes", "One INSERT, UPDATE, DELETE, TRUNCATE or table, index, type or view DDL per "
        + "cassandra_execute, after approval; BATCH, keyspaces, roles, permissions and functions stay with people");
    if (object != null && !object.isBlank()) {
      out.put("table", "Call cassandra_describe_table with table " + object + ".");
    }
    return Optional.of(out);
  }

  @Override
  public void onConnectionChanged(String connectionId) {
    sessions.evict(connectionId);
  }

  @Override
  public void onConnectionRemoved(String connectionId) {
    sessions.evict(connectionId);
  }

  @Override
  public void close() {
    sessions.close();
  }
}
