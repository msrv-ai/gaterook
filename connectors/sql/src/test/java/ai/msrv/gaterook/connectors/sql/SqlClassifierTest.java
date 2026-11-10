package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Analysis;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Kind;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The routes of report 04 §3.5 and §6.1, row by row ({@code classifier-cases.psv}): what the classifier reads and the
 * rules make of it, for both dialects, every tool and environment. Bypasses of the lexer and the parser are rows too:
 * comments and dollar quotes with {@code ;}, MySQL's executable comments and backslash escapes, homoglyphs and quoted
 * names against the allowlist, a lock or a DELETE nested in a read.
 */
class SqlClassifierTest {

  private static final JdbcSqlConnector POSTGRES = new JdbcSqlConnector(new PostgresDialect());
  private static final JdbcSqlConnector MYSQL = new JdbcSqlConnector(new MySqlDialect());

  @ParameterizedTest(name = "{index}: {0} {1} {2} · {3} → {4}")
  @CsvFileSource(resources = "/ai/msrv/gaterook/connectors/sql/classifier-cases.psv", delimiterString = " | ",
      quoteCharacter = '\u0001', maxCharsPerColumn = 10_000)
  void routes(ArgumentsAccessor row) {
    boolean mysql = "my".equals(row.getString(0));
    String tool = "sql_" + row.getString(1);
    Env env = Env.of(row.getString(2));
    String sql = row.getString(3);
    String expected = row.getString(4);
    String options = row.size() > 5 ? row.getString(5) : "";

    Classification c = classify(mysql, tool, env, sql, options);

    assertThat(describe(c)).as(sql).isEqualTo(expected.startsWith("DENY ")
        ? "DENY " + env.id() + ".sql." + expected.substring(5) : expected);
    if (c.kind() == Classification.Kind.DENY) {
      assertThat(c.reason()).as("a denial says why").isNotBlank();
    }
  }

  static Classification classify(boolean mysql, String tool, Env env, String sql, String options) {
    ObjectNode section = Fixtures.object();
    ObjectNode advanced = Fixtures.object();
    boolean noDatabase = false;
    for (String option : options == null || options.isBlank() ? new String[0] : options.split(";")) {
      String[] kv = option.strip().split("=", 2);
      switch (kv[0]) {
        case "allowlist" -> list(section.putArray("allowlist"), kv[1]);
        case "denied" -> list(section.putArray("deniedFunctions"), kv[1]);
        case "ddl" -> section.put("denyDdl", "deny".equals(kv[1]));
        case "allowed" -> list(advanced.putArray("allowedFunctions"), kv[1]);
        case "schemas" -> list(advanced.putArray("schemaAllowlist"), kv[1]);
        case "nodb" -> noDatabase = true;
        default -> throw new IllegalArgumentException("Unknown option " + option);
      }
    }
    ConnectionConfig cfg = Fixtures.config(mysql ? "mysql" : "postgres", env, "db.dev.internal", mysql ? 3306 : 5432,
        noDatabase ? null : "app", "app_ro", advanced, null);
    ToolCall call = Fixtures.sql(tool, sql);
    return (mysql ? MYSQL : POSTGRES).classify(call, cfg, Fixtures.policy(env, section));
  }

  private static void list(ArrayNode array, String values) {
    for (String v : values.split(",")) {
      array.add(v.strip());
    }
  }

  private static String describe(Classification c) {
    return switch (c.kind()) {
      case READ -> "READ";
      case MUTATION -> "MUTATION";
      case DENY -> "DENY " + c.ruleId();
    };
  }

  @Test
  void postgresRunsUpToTwentyStatementsInOneTransaction() {
    StringJoiner twenty = new StringJoiner("; ");
    for (int i = 1; i <= 20; i++) {
      twenty.add("DELETE FROM order_items WHERE id = " + i);
    }
    assertThat(classify(false, "sql_execute", Env.DEV, twenty.toString(), "").kind())
        .isEqualTo(Classification.Kind.MUTATION);

    Classification more = classify(false, "sql_execute", Env.DEV, twenty + "; DELETE FROM orders WHERE id = 21", "");
    assertThat(more.ruleId()).isEqualTo("dev.sql.too-many-statements");
    assertThat(more.reason()).contains("at most 20").contains("21");
  }

  @Test
  void theCallTakesTheHighestClassOfItsStatements() {
    Analysis a = SqlClassifier.analyze("SELECT 1; UPDATE orders SET status = 'x'; CREATE TABLE t (id int)", false);

    assertThat(a.statements()).extracting(SqlClassifier.StatementFacts::kind)
        .containsExactly(Kind.READ, Kind.DML, Kind.DDL);
    assertThat(a.kind()).isEqualTo(Kind.DDL);
    assertThat(a.statements()).extracting(SqlClassifier.StatementFacts::type)
        .containsExactly("SELECT", "UPDATE", "CREATE TABLE");
  }

  @Test
  void theParserAndTheServerMustAgreeWhereStatementsEnd() {
    // A string the PostgreSQL lexer reads as closed and MySQL's as escaped: one statement each, as the server does.
    assertThat(SqlClassifier.analyze("SELECT 'a\\'; SELECT 2", false).count()).isEqualTo(2);
    assertThat(SqlClassifier.analyze("SELECT 'a\\'; SELECT 2'", true).count()).isEqualTo(1);
  }

  @Test
  void tablesAndTargetsAreNamedForTheApproverAndTheAllowlist() {
    Analysis a = SqlClassifier.analyze("UPDATE orders o SET status = 'x' FROM customers c WHERE c.id = o.customer_id",
        false);

    assertThat(a.statements().getFirst().tables()).containsExactlyInAnyOrder("orders", "customers");
    assertThat(SqlRules.targets(a, false, "public")).containsExactly("orders");
  }

  @Test
  void tooLongOrTooDeepIsRefusedBeforeParsing() {
    String deep = "SELECT " + "(".repeat(100) + "1" + ")".repeat(100);
    Classification c = classify(false, "sql_query", Env.DEV, deep, "");
    assertThat(c.ruleId()).isEqualTo("dev.sql.too-complex");
    assertThat(c.reason()).contains("64 levels");

    String huge = "SELECT '" + "x".repeat(SqlClassifier.MAX_LENGTH) + "'";
    assertThat(classify(false, "sql_query", Env.DEV, huge, "").ruleId()).isEqualTo("dev.sql.too-complex");
  }

  @Test
  void whatIsRefusedEverywhereStaysRefusedWhenTheParserCountsTheStatementsDifferently() {
    // The parser takes three line breaks for the end of a statement; the server reads one statement.
    String sessionControl = "SET statement_timeout = 0\n\n\nUPDATE orders SET a = 1";
    Analysis a = SqlClassifier.analyze(sessionControl, false);
    assertThat(a.kind()).isEqualTo(Kind.FORBIDDEN);
    assertThat(classify(false, "sql_execute", Env.DEV, sessionControl, "").ruleId()).isEqualTo("dev.sql.forbidden");

    String deniedCall = "UPDATE orders SET note = 'a'\n\n\nSELECT pg_cancel_backend(1)";
    assertThat(SqlClassifier.analyze(deniedCall, false).problem()).contains("where the database would read 1");
    assertThat(classify(false, "sql_execute", Env.DEV, deniedCall, "").ruleId())
        .isEqualTo("dev.sql.function-denied");

    // Nothing in it is refused everywhere: still a statement a person may approve on dev, with the reason it wasn't read.
    String plain = "UPDATE orders SET a = 1\n\n\nUPDATE orders SET b = 2";
    Classification c = classify(false, "sql_execute", Env.DEV, plain, "");
    assertThat(c.kind()).isEqualTo(Classification.Kind.MUTATION);
    assertThat(SqlClassifier.analyze(plain, false).problem()).contains("statement(s) where the database would read 1");
  }

  @Test
  void theCommentsMySqlRunsAreReadAsCodeOnlyForWhatIsRefusedEverywhere() {
    assertThat(SqlClassifier.serverCode("/*!50000 SET a = 1 */ SELECT 1", SqlSyntax.MYSQL))
        .isEqualTo("         SET a = 1    SELECT 1");
    assertThat(SqlClassifier.serverCode("SELECT 1 /*M!100100 , 2 */", SqlSyntax.MYSQL))
        .isEqualTo("SELECT 1            , 2   ");
    // An optimizer hint holds no statement, and other dialects have no such comments.
    assertThat(SqlClassifier.serverCode("SELECT /*+ SET_VAR(a = 1) */ 1", SqlSyntax.MYSQL))
        .isEqualTo("SELECT /*+ SET_VAR(a = 1) */ 1");
    assertThat(SqlClassifier.serverCode("SELECT /*!1 */ 1", SqlSyntax.POSTGRES)).isEqualTo("SELECT /*!1 */ 1");
  }

  @Test
  void aFunctionThatRunsAQueryGivenAsTextIsDeniedEvenWhereTheCatalogListsItAsBuiltIn() {
    // Test connection stores every pg_catalog name as built in, ts_stat and ts_rewrite among them.
    for (String name : List.of("ts_stat", "ts_rewrite", "query_to_xml", "query_to_xmlschema")) {
      FunctionRules.Verdict v = FunctionRules.check(List.of(new FunctionRules.FunctionRef(List.of("pg_catalog", name))),
          SqlSyntax.POSTGRES, Set.of(name), List.of(), List.of(name));
      assertThat(v.denied()).as(name).containsExactly("pg_catalog." + name);
    }
  }

  @Test
  void ruleIdsCarryTheEnvironment() {
    for (Env env : Env.values()) {
      Classification c = classify(true, "sql_query", env, "SELECT GET_LOCK('a', 1)", "");
      assertThat(c.ruleId()).isEqualTo(env.id().toLowerCase(Locale.ROOT) + ".sql.function-denied");
    }
  }
}
