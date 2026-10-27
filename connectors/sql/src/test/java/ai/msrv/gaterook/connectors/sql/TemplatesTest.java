package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The {@code sql-query} template kind without a database: parameters, compiling on save, preview (report 10 §6.6). */
class TemplatesTest {

  private static final Clock NOON = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC);
  private static final JdbcSqlConnector POSTGRES = new JdbcSqlConnector(new PostgresDialect());
  private static final JdbcSqlConnector MYSQL = new JdbcSqlConnector(new MySqlDialect());

  private static ConnectionConfig devDb() {
    return Fixtures.config("postgres", Env.DEV, "db.dev.internal", 5432, "app", "app_ro", null, null);
  }

  private static TemplateKind kind(JdbcSqlConnector c) {
    return c.templateKinds().getFirst();
  }

  private static List<TemplateParams.Param> params(String json) {
    List<String> errors = new ArrayList<>();
    List<TemplateParams.Param> p = TemplateParams.parse(Fixtures.JSON.readTree(json), errors);
    assertThat(errors).isEmpty();
    return p;
  }

  // ── Parameters ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void valuesAreTypedAndCheckedBeforeTheyAreBound() throws Exception {
    List<TemplateParams.Param> p = params("""
        [{"name": "status", "type": "enum", "values": ["pending", "paid"]},
         {"name": "id", "type": "integer", "minimum": 1, "maximum": 99999},
         {"name": "total", "type": "number", "required": false},
         {"name": "since", "type": "timestamp", "required": false, "default": "-PT24H"},
         {"name": "day", "type": "date", "required": false, "default": "-P1D"},
         {"name": "vip", "type": "boolean", "required": false, "default": false},
         {"name": "q", "type": "string", "maxLength": 5, "pattern": "[a-z]+", "required": false}]
        """);
    ObjectNode args = Fixtures.object().put("status", "paid").put("id", 48213).put("total", 12.5);

    List<TemplateParams.Bound> v = TemplateParams.resolve(p, List.of("status", "id", "total", "since", "day", "vip",
        "q"), args, NOON);

    assertThat(v).extracting(TemplateParams.Bound::value).containsExactly("paid", 48213L, new BigDecimal("12.5"),
        OffsetDateTime.parse("2026-10-21T12:00Z"), LocalDate.parse("2026-10-21"), false, null);
    assertThat(TemplateParams.describe(v)).isEqualTo(":status = 'paid' (enum), :id = 48213 (integer), "
        + ":total = 12.5 (number), :since = 2026-10-21T12:00Z (timestamp), :day = 2026-10-21 (date), "
        + ":vip = false (boolean), :q = NULL (string)");

    assertThatThrownBy(() -> TemplateParams.resolve(p, List.of("status"), Fixtures.object().put("status", "lost"),
        NOON)).hasMessage("status must be one of pending, paid.");
    assertThatThrownBy(() -> TemplateParams.resolve(p, List.of("id"), Fixtures.object().put("status", "paid")
        .put("id", 0), NOON)).hasMessage("id is at least 1.");
    assertThatThrownBy(() -> TemplateParams.resolve(p, List.of("id"), Fixtures.object().put("status", "paid"),
        NOON)).hasMessage("id is required.");
    assertThatThrownBy(() -> TemplateParams.resolve(p, List.of(), Fixtures.object().put("status", "paid")
        .put("id", 1).put("since", "yesterday"), NOON)).hasMessageContaining("ISO 8601 with an offset");
    assertThatThrownBy(() -> TemplateParams.resolve(p, List.of(), Fixtures.object().put("status", "paid")
        .put("id", 1).put("q", "abcdef"), NOON)).hasMessage("q is longer than 5 characters.");
    assertThatThrownBy(() -> TemplateParams.resolve(p, List.of(), Fixtures.object().put("status", "paid")
        .put("id", 1).put("q", "ABC"), NOON)).hasMessage("q doesn't match [a-z]+.");
  }

  @Test
  void theApprovedValuesComeBackWithTheirTypes() throws Exception {
    List<TemplateParams.Param> p = params("""
        [{"name": "id", "type": "integer"}, {"name": "since", "type": "timestamp", "default": "-PT1H"},
         {"name": "amount", "type": "number"}]
        """);
    List<String> order = List.of("id", "since", "amount", "id");
    List<TemplateParams.Bound> v = TemplateParams.resolve(p, order, Fixtures.object().put("id", 9007199254740993L)
        .put("amount", "10.10"), NOON);

    ObjectNode stored = TemplateParams.valuesJson(v);
    List<TemplateParams.Bound> back = TemplateParams.fromJson(p, order, stored);

    assertThat(stored.toString()).isEqualTo("{\"id\":9007199254740993,\"since\":\"2026-10-22T11:00Z\","
        + "\"amount\":\"10.10\"}");
    assertThat(back).extracting(TemplateParams.Bound::value).containsExactly(9007199254740993L,
        OffsetDateTime.parse("2026-10-22T11:00Z"), new BigDecimal("10.10"), 9007199254740993L);
  }

  @Test
  void aDefinitionThatIsntValidSaysWhy() {
    List<String> errors = new ArrayList<>();
    TemplateParams.parse(Fixtures.JSON.readTree("""
        [{"name": "Bad"}, {"name": "a", "type": "enum"}, {"name": "b", "type": "money"},
         {"name": "c", "type": "timestamp", "default": "soon"}, {"name": "d"}, {"name": "d"},
         {"name": "e", "pattern": "("}]
        """), errors);

    assertThat(errors).containsExactly(
        "Parameter name Bad: use lowercase letters, digits and _.",
        "Parameter :a is an enum without allowed values.",
        "Parameter :b: unknown type money.",
        "Parameter :c: the default \"soon\" isn't a timestamp or an ISO 8601 duration such as -PT24H.",
        "Parameter :d is defined twice.",
        "Parameter :e: the pattern isn't a regular expression.");
  }

  // ── Compiling on save ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void aReadCompilesToAReadOnlyToolWithTheQueryOutput() {
    TemplateKind.CompileResult r = kind(POSTGRES).compile(Fixtures.json(PostgresIT.ORDERS_BY_STATUS), devDb());

    assertThat(r.ok()).isTrue();
    assertThat(r.warnings()).isEmpty();
    TemplateKind.CompiledTool tool = r.tools().getFirst();
    assertThat(tool.opKey()).isEqualTo("q");
    ToolDefinition d = tool.definition();
    assertThat(d.title()).isEqualTo("Orders by status");
    assertThat(d.nature()).isEqualTo(ToolDefinition.Nature.READ);
    assertThat(d.resultShape()).isEqualTo(ToolDefinition.ResultShape.TABLE);
    assertThat(d.outputSchema()).isEqualTo(POSTGRES.manifest().tools().get(2).outputSchema());
    assertThat(d.inputSchema().has("connection")).isFalse();
    assertThat(tool.binding().path("kind").asString()).isEqualTo("read");
    assertThat(tool.binding().path("jdbc").asString()).contains("WHERE status = ?").contains("created_at >= ?");
    assertThat(tool.binding().path("order").toString()).isEqualTo("[\"status\",\"since\"]");
    assertThat(kind(POSTGRES).connectionTypes()).containsExactly("postgres");
    assertThat(kind(MYSQL).connectionTypes()).containsExactly("mysql");
    assertThat(kind(POSTGRES).kind()).isEqualTo("sql-query");
  }

  @Test
  void aChangeCompilesToAToolThatWaitsForApproval() {
    TemplateKind.CompileResult r = kind(POSTGRES).compile(Fixtures.json("""
        {"name": "cancel_order", "sql": "UPDATE orders SET status = 'cancelled' WHERE id = :id",
         "params": [{"name": "id", "type": "integer"}]}
        """), devDb());

    ToolDefinition d = r.tools().getFirst().definition();
    assertThat(d.nature()).isEqualTo(ToolDefinition.Nature.MUTATION);
    assertThat(d.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
    assertThat(d.annotations().destructiveHint()).isTrue();
    assertThat(d.outputSchema()).isNull();
  }

  @Test
  void aReadWithAFunctionThatIsntBuiltInIsSavedButWaitsForApproval() {
    TemplateKind.CompileResult r = kind(POSTGRES).compile(Fixtures.json("""
        {"name": "wrapped", "sql": "SELECT my_wrapper(:x)", "params": [{"name": "x", "type": "integer"}]}
        """), devDb());

    assertThat(r.ok()).isTrue();
    assertThat(r.warnings()).containsExactly("Calls my_wrapper, which isn't a built-in function: the tool needs "
        + "approval, and on prod it is refused.");
    assertThat(r.tools().getFirst().definition().defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
  }

  @Test
  void whatCantRunSafelyIsntSaved() {
    record Case(String sql, String error) {}
    for (Case c : List.of(
        new Case("SELECT 1; SELECT 2", "A template runs one statement; this SQL has 2."),
        new Case("BEGIN", "BEGIN isn't allowed in a template: GateRook runs every call in its own transaction and "
            + "session."),
        new Case("SELECT pg_terminate_backend(1)", "pg_terminate_backend isn't allowed through GateRook: it acts "
            + "outside the transaction."),
        new Case("DO $$ BEGIN END $$", "The gateway couldn't analyze the SQL: The gateway couldn't analyze this "
            + "statement. A template must be plain SQL the gateway can read."),
        new Case("SELECT :ghost", ":ghost isn't a parameter. Add it under Parameters, or write :: for a cast."),
        new Case("", "The SQL is empty."))) {
      TemplateKind.CompileResult r = kind(POSTGRES).compile(Fixtures.json("{\"name\": \"t\"}").put("sql", c.sql()),
          devDb());
      assertThat(r.ok()).as(c.sql()).isFalse();
      assertThat(r.errors()).as(c.sql()).contains(c.error());
      assertThat(r.tools()).isEmpty();
    }
    assertThat(kind(POSTGRES).compile(Fixtures.json("{\"name\": \"Bad-Name\", \"sql\": \"SELECT 1\"}"), devDb())
        .errors()).containsExactly("Tool name: use lowercase letters, digits and _, starting with a letter, at most 64 "
        + "characters.");
  }

  @Test
  void aSchemaChangeAndAnUnusedParameterAreWarnedAbout() {
    TemplateKind.CompileResult r = kind(POSTGRES).compile(Fixtures.json("""
        {"name": "add_column", "sql": "ALTER TABLE orders ADD COLUMN x int",
         "params": [{"name": "unused", "type": "string"}]}
        """), devDb());

    assertThat(r.ok()).isTrue();
    assertThat(r.warnings()).containsExactly("Parameter :unused isn't used in the SQL.",
        "The template changes the schema: where the policy blocks DDL (stage and prod by default) its calls are "
            + "refused.");
  }

  @Test
  void thePreviewShowsTheSqlAsSavedAndTheValuesApart() {
    ActionPreview p = kind(POSTGRES).preview(Fixtures.json(PostgresIT.ORDERS_BY_STATUS),
        Fixtures.object().put("status", "paid"), devDb());

    assertThat(p.text()).startsWith("SELECT id, status, total, created_at\nFROM orders\nWHERE status = :status");
    assertThat(p.target()).isEqualTo("dev-db · db.dev.internal:5432/app");
    assertThat(p.fields()).extracting(ActionPreview.Field::label).containsExactly("Template", "Parameters", "Binding");
    assertThat(p.fields().get(1).value()).startsWith(":status = 'paid' (enum), :since = ");
  }

  @Test
  void aStoredBindingThatDoesntMatchItsSqlDoesntRun() {
    TemplateKind.CompiledTool tool = kind(POSTGRES).compile(Fixtures.json(PostgresIT.ORDERS_BY_STATUS), devDb())
        .tools().getFirst();
    ObjectNode tampered = (ObjectNode) tool.binding().deepCopy();
    tampered.put("jdbc", "DELETE FROM orders");

    JdbcSqlConnector.TemplateCall t = JdbcSqlConnector.TemplateCall.of(tampered, false);
    assertThat(t.problem()).isEqualTo("The binding of orders_by_status doesn't match its SQL.");

    var call = new ai.msrv.gaterook.connector.api.ToolCall("tc_t", "orders_by_status", "dev-db",
        Fixtures.object().put("status", "paid"), new ai.msrv.gaterook.connector.api.ToolCall.TemplateBinding("tpl", 1,
        "q", tampered));
    assertThat(POSTGRES.classify(call, devDb(), Fixtures.policy(Env.DEV, null)).ruleId())
        .isEqualTo("dev.sql.template-invalid");
    assertThatThrownBy(() -> POSTGRES.execute(call, devDb(), Fixtures.Secrets.none(),
        Fixtures.Context.of(Env.DEV, null))).isInstanceOfSatisfying(ConnectorException.class,
        e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }
}
