package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Analysis;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Kind;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code sql-query}: a saved SQL statement with typed {@code :name} parameters, published as one tool without a
 * {@code connection} argument that runs on its connection ("Runs on"; report 10 §3.6, §6.6, §7.5; kit Connectors ›
 * Templates).
 *
 * <p>Compiling classifies the statement on save: one statement the gateway can analyze — a read becomes a Read only
 * tool with the output of {@code sql_query}, a change a tool that always waits for approval; transaction control,
 * denied functions and anything the parser can't read are not saved. A read that calls a function that isn't built in
 * also needs approval (refused on prod). At call time the connector classifies it again under the call's policy
 * (DDL blocked, the allowlist).
 *
 * <p>The binding the core keeps with the compiled tool holds what runs: the SQL as saved, its JDBC form, the
 * placeholder order and the parameter types.
 */
final class SqlQueryTemplates implements TemplateKind {

  static final String KIND = "sql-query";
  static final int MAX_SQL = 20_000;
  private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

  private final SqlDialect dialect;
  private final JsonNode definitionSchema;
  private final JsonNode readOutputSchema;

  SqlQueryTemplates(SqlDialect dialect, JsonNode definitionSchema, JsonNode readOutputSchema) {
    this.dialect = dialect;
    this.definitionSchema = definitionSchema;
    this.readOutputSchema = readOutputSchema;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public List<String> connectionTypes() {
    return List.of(dialect.type());
  }

  @Override
  public JsonNode definitionSchema() {
    return definitionSchema;
  }

  /** What a definition compiles to, for the connector's own use at call time. */
  record Compiled(String name, String sql, NamedSql named, List<TemplateParams.Param> params, Analysis analysis,
      Integer maxRows, List<String> errors, List<String> warnings, boolean readsOnly) {}

  @Override
  public CompileResult compile(JsonNode definition, ConnectionConfig runsOn) {
    Compiled c = compileDefinition(definition, runsOn);
    if (!c.errors().isEmpty()) {
      return new CompileResult(List.of(), List.of(), c.errors(), c.warnings(), null);
    }
    String description = definition.path("description").asString("").strip();
    if (description.isEmpty()) {
      description = "Runs the saved query " + c.name() + ".";
    }
    String title = definition.path("title").asString("").strip();
    ToolDefinition.Builder tool = ToolDefinition.builder(c.name())
        .title(title.isEmpty() ? titleOf(c.name()) : title)
        .description(description)
        .inputSchema(TemplateParams.inputSchema(c.params()))
        .risk(new ToolDefinition.Risk(true, true, ToolDefinition.Egress.NONE));
    if (c.readsOnly()) {
      tool.annotations(Annotations.read()).nature(Nature.READ).defaultMode(Mode.READ_ONLY, Mode.READ_ONLY)
          .resultShape(ResultShape.TABLE).outputSchema(readOutputSchema);
    } else {
      boolean destructive = c.analysis().kind() == Kind.DDL || !"INSERT".equals(statementType(c));
      tool.annotations(Annotations.write(destructive)).nature(Nature.MUTATION)
          .defaultMode(Mode.APPROVAL, Mode.APPROVAL).resultShape(c.analysis().kind() == Kind.READ ? ResultShape.TABLE
              : ResultShape.JSON);
    }
    ObjectNode binding = JsonNodeFactory.instance.objectNode();
    binding.put("dialect", dialect.type());
    if (runsOn != null && (DatabaseSelection.configured(runsOn) || definition.hasNonNull("database"))) {
      try {
        binding.put("database", DatabaseSelection.resolve(runsOn, definition.path("database").asString(null)));
      } catch (ConnectorException e) {
        return new CompileResult(List.of(), List.of(), List.of(e.getMessage()), c.warnings(), null);
      }
    }
    binding.put("name", c.name());
    binding.put("sql", c.sql());
    binding.put("jdbc", c.named().jdbc());
    ArrayNode order = binding.putArray("order");
    c.named().order().forEach(order::add);
    binding.set("params", TemplateParams.toJson(c.params()));
    binding.put("kind", c.analysis().kind().name().toLowerCase(Locale.ROOT));
    binding.put("statement", statementType(c));
    if (c.maxRows() != null) {
      binding.put("maxRows", c.maxRows());
    }
    return new CompileResult(List.of(new CompiledTool("q", tool.build(), binding)), List.of(), List.of(),
        c.warnings(), null);
  }

  /** Validates and analyzes a definition; the errors say what keeps it from being saved. */
  Compiled compileDefinition(JsonNode definition, ConnectionConfig runsOn) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (runsOn != null) {
      try {
        DatabaseSelection.resolve(runsOn, definition.path("database").asString(null));
      } catch (ConnectorException e) {
        errors.add(e.getMessage());
      }
    }
    String name = definition.path("name").asString("");
    if (!TOOL_NAME.matcher(name).matches()) {
      errors.add("Tool name: use lowercase letters, digits and _, starting with a letter, at most 64 characters.");
    }
    String sql = definition.path("sql").asString("");
    if (sql.isBlank()) {
      errors.add("The SQL is empty.");
    } else if (sql.length() > MAX_SQL) {
      errors.add("The SQL is longer than " + String.format(Locale.US, "%,d", MAX_SQL) + " characters.");
    }
    Integer maxRows = null;
    if (definition.has("maxRows") && !definition.get("maxRows").isNull()) {
      maxRows = definition.get("maxRows").asInt(0);
      if (maxRows < 1 || maxRows > 100_000) {
        errors.add("Max rows is 1 to 100,000.");
      }
    }
    List<TemplateParams.Param> params = TemplateParams.parse(definition.path("params"), errors);
    Set<String> declared = new LinkedHashSet<>();
    params.forEach(p -> declared.add(p.name()));
    NamedSql named = NamedSql.compile(sql, dialect.syntax(), declared);
    errors.addAll(named.errors());
    for (String p : declared) {
      if (!named.used().contains(p)) {
        warnings.add("Parameter :" + p + " isn't used in the SQL.");
      }
    }
    Analysis analysis = SqlClassifier.analyze(sql.isBlank() ? "SELECT 1" : sql, dialect.syntax());
    boolean readsOnly = false;
    if (errors.isEmpty()) {
      if (analysis.tooComplex() || analysis.kind() == Kind.UNPARSED) {
        errors.add("The gateway couldn't analyze the SQL" + (analysis.problem() == null ? "" : ": "
            + analysis.problem()) + " A template must be plain SQL the gateway can read.");
      } else if (analysis.count() != 1) {
        errors.add("A template runs one statement; this SQL has " + analysis.count() + ".");
      } else if (analysis.kind() == Kind.FORBIDDEN) {
        String type = analysis.statements().getFirst().type();
        errors.add(Character.toUpperCase(type.charAt(0)) + type.substring(1) + " isn't allowed in a template: "
            + "GateRook runs every call in its own transaction and session.");
      } else {
        FunctionRules.Verdict v = FunctionRules.check(analysis.functions(), dialect.syntax(),
            FunctionRules.fromMetadata(runsOn == null ? null : runsOn.metadata()), List.of(),
            runsOn == null ? List.of() : ConnectionSettings.of(runsOn, dialect).allowedFunctions());
        if (!v.denied().isEmpty()) {
          errors.add(String.join(", ", v.denied()) + " isn't allowed through GateRook: it acts outside the "
              + "transaction.");
        } else if (analysis.kind() == Kind.READ && !v.notBuiltIn().isEmpty()) {
          warnings.add("Calls " + String.join(", ", v.notBuiltIn()) + ", which isn't a built-in function: the tool "
              + "needs approval, and on prod it is refused.");
        } else {
          readsOnly = analysis.kind() == Kind.READ;
        }
        if (analysis.kind() == Kind.DDL) {
          warnings.add("The template changes the schema: where the policy blocks DDL (stage and prod by default) "
              + "its calls are refused.");
        }
      }
    }
    return new Compiled(name, sql, named, params, analysis, maxRows, List.copyOf(errors), List.copyOf(warnings),
        readsOnly);
  }

  @Override
  public ActionPreview preview(JsonNode definition, ObjectNode arguments, ConnectionConfig runsOn) {
    Compiled c = compileDefinition(definition, runsOn);
    ConnectionSettings s = ConnectionSettings.of(runsOn, dialect);
    if (c.errors().isEmpty()) {
      try {
        s = s.withDatabase(DatabaseSelection.resolve(runsOn, definition.path("database").asString(null)), dialect.mysql());
      } catch (ConnectorException ignored) {
        // compileDefinition already reports selection errors in the preview.
      }
    }
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Template", c.name()));
    List<String> warnings = new ArrayList<>(c.errors());
    warnings.addAll(c.warnings());
    if (c.errors().isEmpty()) {
      try {
        List<TemplateParams.Bound> values = TemplateParams.resolve(c.params(), c.named().order(), arguments,
            Clock.systemUTC());
        fields.add(new ActionPreview.Field("Parameters", TemplateParams.describe(values)));
      } catch (ConnectorException e) {
        warnings.add(e.getMessage());
      }
    }
    fields.add(new ActionPreview.Field("Binding", "Values are bound by the driver, never pasted into the SQL"));
    return ActionPreview.of(ActionPreview.Kind.SQL, "sql", c.sql(), runsOn.name() + " · " + s.target(),
        c.name() + " · " + runsOn.name() + " (" + runsOn.env().id() + ")")
        .withFields(fields).withWarnings(warnings);
  }

  private static String statementType(Compiled c) {
    return c.analysis().statements().isEmpty() ? "SQL" : c.analysis().statements().getFirst().type();
  }

  /** {@code orders_by_status} → {@code Orders by status}. */
  static String titleOf(String name) {
    String words = name.replace('_', ' ').strip();
    return words.isEmpty() ? name : Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }
}
