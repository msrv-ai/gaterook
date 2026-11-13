package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.sql.IdentifierRules.TableRef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

/** T-SQL lexical boundaries and policy checks without a database connection. */
class SqlServerSafetyTest {
  private static final SqlDialect DIALECT = new SqlServerDialect();

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT TOP (10) [Order Id], COUNT(*) OVER () AS total FROM [dbo].[Orders] ORDER BY [Order Id]",
      "WITH recent AS (SELECT id FROM dbo.orders) SELECT id FROM recent",
      "SELECT N'a;''b', ';EXEC dangerous', [semi;colon] FROM [dbo].[Orders]",
      "SELECT GETDATE(), DATEADD(day, 1, GETDATE()), ISNULL(NULL, 1), LEN(N'hello')",
      "SELECT CAST(1 AS varchar(10)), CONVERT(nvarchar(10), 1), TRY_CAST('1' AS int)",
      "SELECT * FROM OPENJSON(N'[1,2]')",
      ";WITH values_cte AS (SELECT 1 AS value) SELECT * FROM values_cte",
      "SELECT 1 /* outer /* nested */ still comment */",
      "SELECT @@VERSION",
      "SELECT COUNT(*) OVER (PARTITION BY LOWER(status) ORDER BY id) FROM dbo.orders",
      "SELECT * FROM (VALUES (1, N'a')) AS v(id, name)"
  })
  void ordinaryReadsAreRecognized(String sql) {
    assertThat(query(sql, null, null).kind()).as(sql).isEqualTo(Classification.Kind.READ);
  }

  /**
   * T-SQL needs no statement separator, and some of its reserved statement words are plain aliases to the parser:
   * text that is not exactly one query by the server's own tokens is not a read, whatever the parser makes of it.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT 1 DELETE FROM dbo.orders",
      "SELECT 1 FROM dbo.orders COMMIT",
      "SELECT 1 UNION SELECT 2 DELETE FROM dbo.orders",
      // A quoted name made of a line break, followed by a statement word that is not reserved.
      "SELECT 1 FROM dbo.orders \"\n\" RECEIVE TOP(1) * FROM dbo.queue",
      "SELECT 1 FROM dbo.orders [\n] RECEIVE TOP(1) * FROM dbo.queue",
      // A dot after a number is not a name separator.
      "SELECT 1. DELETE FROM dbo.orders",
      // A reserved statement word among the common table expressions is not a name there.
      "WITH a AS (SELECT 1 AS x), truncate AS (SELECT 2 AS y) SELECT x FROM a"
  })
  void aSecondStatementWithoutASeparatorIsNotARead(String sql) {
    assertThat(SqlClassifier.analyze(sql, DIALECT).kind()).as(sql).isEqualTo(SqlClassifier.Kind.UNPARSED);
    assertThat(query(sql, null, null).ruleId()).as(sql).isEqualTo("dev.sql.unparsed");
    assertThat(execute(sql, null, null).ruleId()).as(sql).isEqualTo("dev.sql.unparsed");
  }

  /** The clause words of one SELECT, and statement words that are only quoted names or text, stay reads. */
  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT id FROM dbo.orders ORDER BY id OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY",
      "SELECT TOP (5) WITH TIES id FROM dbo.orders ORDER BY id",
      "SELECT CASE WHEN id > 1 THEN N'a' ELSE N'b' END FROM dbo.orders",
      "SELECT id FROM dbo.orders WITH (NOLOCK) WHERE id IN (1, 2)",
      "SELECT id FROM dbo.orders UNION ALL SELECT id FROM dbo.customers",
      "SELECT id FROM dbo.orders o WHERE EXISTS (SELECT 1 FROM dbo.customers c WHERE c.id = o.customer_id)",
      "WITH a AS (SELECT 1 AS x), b AS (SELECT 2 AS y) SELECT * FROM a CROSS JOIN b",
      "SELECT o.id FROM dbo.orders AS o INNER JOIN dbo.customers AS c ON c.id = o.customer_id "
          + "LEFT JOIN dbo.items i ON i.order_id = o.id WHERE o.id BETWEEN 1 AND 5 AND c.name LIKE N'a%' "
          + "ORDER BY o.id DESC",
      "SELECT id FROM dbo.orders FOR JSON AUTO",
      "SELECT id FROM dbo.orders FOR BROWSE",
      "SELECT id FROM dbo.orders OPTION (RECOMPILE)",
      "SELECT (SELECT 1) AS x",
      "SELECT CURRENT_TIMESTAMP, COALESCE(NULL, 1), NULLIF(1, 2), IIF(1 = 1, 'a', 'b')",
      "SELECT 1 AS [commit], 'DELETE FROM dbo.orders' AS [text] FROM dbo.orders WHERE [update] IS NULL",
      "WITH r (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 5) SELECT n FROM r OPTION (MAXRECURSION 10)",
      "SELECT status, SUM(total) OVER (PARTITION BY status ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND "
          + "CURRENT ROW) FROM dbo.orders",
      "SELECT DISTINCT TOP 10 status, COUNT(*) AS c FROM dbo.orders WHERE created >= DATEADD(month, -1, GETDATE()) "
          + "GROUP BY status HAVING COUNT(*) > 5 ORDER BY c DESC",
      "SELECT o.id, c.name FROM dbo.orders o LEFT OUTER JOIN dbo.customers c ON c.id = o.customer_id "
          + "WHERE c.name IS NOT NULL AND o.id <> ALL (SELECT id FROM dbo.archived)",
      "SELECT 0x1F, 1e5, 1.5E-3, N'x', @@VERSION",
      // A reserved word after a dot is a column of a catalog view, not a statement word.
      "SELECT c.precision FROM sys.columns c",
      "SELECT c.name, c.max_length, c.precision, c.scale FROM sys.columns c WHERE c.object_id = OBJECT_ID('dbo.orders')"
  })
  void clauseWordsAndQuotedStatementWordsStayReads(String sql) {
    assertThat(query(sql, null, null).kind()).as(sql).isEqualTo(Classification.Kind.READ);
  }

  /** The parser's table walk skipped a TOP expression and a LIKE escape: their subqueries meet the table rules. */
  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT TOP ((SELECT COUNT(*) FROM dbo.Secrets)) id FROM dbo.Orders",
      "SELECT id FROM dbo.Orders WHERE name LIKE N'a%' ESCAPE (SELECT MAX(code) FROM dbo.Secrets)"
  })
  void subqueriesInSkippedOperandPositionsMeetTheTableAllowlist(String sql) {
    ObjectNode policy = Fixtures.object();
    policy.putArray("allowlist").add("dbo.Orders");
    assertThat(query(sql, policy, null).ruleId()).as(sql).isEqualTo("dev.sql.table-not-allowed");
  }

  /** Statements the parser reads as reads but that are not a SELECT, such as DESCRIBE, are not reads on SQL Server. */
  @ParameterizedTest
  @ValueSource(strings = {"DESCRIBE dbo.orders", "EXPLAIN SELECT 1"})
  void onlyASelectIsARead(String sql) {
    SqlClassifier.Analysis a = SqlClassifier.analyze(sql, DIALECT);

    assertThat(a.kind()).as(sql).isEqualTo(SqlClassifier.Kind.UNPARSED);
    assertThat(a.problem()).as(sql).contains("only a SELECT");
  }

  /**
   * A bracketed name with an escaped closing bracket is one name to T-SQL, while the parser's lexer ends it at the
   * first bracket: such text is not analyzed. Other bracketed names are.
   */
  @Test
  void aBracketedNameWithAnEscapedBracketIsNotAnalyzed() {
    assertThat(SqlClassifier.analyze("SELECT [a]]b] FROM dbo.orders", DIALECT).kind())
        .isEqualTo(SqlClassifier.Kind.UNPARSED);
    assertThat(query("SELECT [a]]b] FROM dbo.orders", null, null).ruleId()).isEqualTo("dev.sql.unparsed");
    assertThat(query("SELECT [a b] FROM [dbo].[orders]", null, null).kind()).isEqualTo(Classification.Kind.READ);
  }

  /**
   * A change whose SELECT the table walk never reached is not analyzed: with a restricted allowlist sql_execute refuses
   * it, where the tables the walk did list would pass.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "INSERT INTO dbo.Orders (id) SELECT id FROM dbo.Orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM dbo.Secrets)",
      "UPDATE dbo.Orders SET id = 1 FROM dbo.Orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM dbo.Secrets)",
      "DELETE FROM dbo.Orders WHERE id IN (SELECT id FROM dbo.Orders FOR SYSTEM_TIME AS OF "
          + "(SELECT MAX(changed) FROM dbo.Secrets))",
      "MERGE INTO dbo.Orders o USING (SELECT id FROM dbo.Orders FOR SYSTEM_TIME AS OF "
          + "(SELECT MAX(changed) FROM dbo.Secrets)) s ON o.id = s.id WHEN MATCHED THEN DELETE",
      "CREATE TABLE dbo.Copied AS SELECT id FROM dbo.Orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM dbo.Secrets)",
      "CREATE VIEW dbo.Recent AS SELECT id FROM dbo.Orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM dbo.Secrets)"
  })
  void aChangeWithASelectTheWalkNeverReachedIsNotAccepted(String sql) {
    ObjectNode policy = Fixtures.object();
    policy.putArray("allowlist").add("dbo.Orders").add("dbo.Copied").add("dbo.Recent");
    assertThat(execute(sql, policy, null).ruleId()).as(sql).isEqualTo("dev.sql.unparsed");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT NEXT VALUE FOR dbo.order_sequence",
      "SELECT NEXT /* hidden */ VALUE FOR dbo.order_sequence",
      "SELECT * INTO #copied FROM dbo.orders",
      "SELECT * INTO [#copied] FROM dbo.orders",
      "CREATE TABLE #scratch (id int)",
      "SELECT * FROM #scratch",
      "SELECT @value = id FROM dbo.orders",
      "SELECT * FROM OPENROWSET('provider', 'connection', 'query')",
      "SELECT * FROM OPENQUERY(remote_server, 'SELECT 1')",
      "EXEC sp_configure 'xp_cmdshell', 1",
      "EXECUTE ('SELECT 1')",
      "SET QUOTED_IDENTIFIER OFF",
      "USE another_database",
      "WAITFOR DELAY '00:00:01'",
      "CREATE PROCEDURE dbo.change_data AS DELETE FROM dbo.orders",
      "ALTER LOGIN app_user DISABLE",
      "SELECT * FROM dbo.orders WITH (UPDLOCK)",
      "SELECT 1 OPTION (QUERYTRACEON 9481)",
      "UPDATE dbo.orders SET status = 1 OUTPUT inserted.id INTO other.dbo.stolen",
      "INSERT INTO dbo.orders(id) OUTPUT INSERTED.id INTO other.dbo.stolen VALUES (1)",
      "UPDATE dbo.orders SET status = 1 OUTPUT dbo.custom_function()",
      "SELECT dbo.CustomType::Run()",
      "SELECT [dbo].[CustomType]::Run()",
      "SELECT CAST('value' AS dbo.CustomType)",
      "SELECT TRY_CAST('value' AS CustomType)",
      "SELECT CONVERT(dbo.CustomType, 'value')",
      "SELECT TRY_CONVERT(CustomType, 'value')",
      "SELECT [clrColumn].Run() FROM dbo.orders"
  })
  void sessionAndExternalEffectsAreRefusedForBothRoutes(String sql) {
    assertThat(query(sql, null, null).kind()).as(sql).isEqualTo(Classification.Kind.DENY);
    assertThat(execute(sql, null, null).kind()).as(sql).isEqualTo(Classification.Kind.DENY);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT * FROM other.dbo.orders",
      "SELECT * FROM [other].[dbo].[orders]",
      "SELECT * FROM linked.other.dbo.orders",
      "UPDATE other.dbo.orders SET status = 'done'",
      "DELETE FROM [other].[dbo].[orders]",
      "SELECT * INTO other.dbo.stolen FROM dbo.orders",
      "WITH x AS (SELECT * FROM other.dbo.orders) SELECT * FROM x",
      "SELECT * FROM [other]..[orders]"
  })
  void crossDatabaseNamesCannotBypassTheSelectedDatabase(String sql) {
    assertThat(query(sql, null, null).kind()).as(sql).isEqualTo(Classification.Kind.DENY);
    assertThat(execute(sql, null, null).kind()).as(sql).isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void ddlAndWritesKeepTheirUsualApprovalAndDdlRules() {
    assertThat(query("SELECT * INTO dbo.copied FROM dbo.orders", null, null).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(execute("SELECT * INTO dbo.copied FROM dbo.orders", null, null).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(execute("UPDATE dbo.orders SET status = 'done' WHERE id = 1", null, null).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(execute("CREATE TABLE dbo.example (id int)", Fixtures.object().put("denyDdl", true), null).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(execute("DELETE FROM dbo.orders; DELETE FROM dbo.customers", null, null).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(execute("SOMETHING THE PARSER DOES NOT KNOW", null, null).kind())
        .isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void unknownFunctionsCannotRunAsReadsEvenWhenListedInAllowedFunctions() {
    assertThat(query("SELECT dbo.custom_function(1)", null, null).kind()).isEqualTo(Classification.Kind.DENY);
    ObjectNode advanced = Fixtures.object();
    advanced.putArray("allowedFunctions").add("dbo.custom_function");
    assertThat(query("SELECT dbo.custom_function(1)", null, advanced).kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(query("SELECT pg_catalog.nextval('seq')", null, null).kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(query("SELECT [dbo].[xp_cmdshell]('command')", null, advanced).kind())
        .isEqualTo(Classification.Kind.DENY);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT COUNT(*) OVER (PARTITION BY dbo.custom_function(id)) FROM dbo.orders",
      "SELECT COUNT(*) OVER (PARTITION BY xp_cmdshell('dir')) FROM dbo.orders",
      "SELECT SUM(id) OVER (ORDER BY dbo.custom_function(id)) FROM dbo.orders"
  })
  void callsInEveryExpressionPositionMeetTheFunctionRules(String sql) {
    assertThat(query(sql, null, null).kind()).as(sql).isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void schemaAllowlistsPreserveCaseAndExcludeSystemSchemasFromWildcards() {
    ObjectNode policy = Fixtures.object();
    policy.putArray("allowlist").add("[dbo].[Orders]");
    assertThat(query("SELECT * FROM [dbo].[Orders]", policy, null).kind()).isEqualTo(Classification.Kind.READ);
    assertThat(query("SELECT * FROM [dbo].[orders]", policy, null).kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(query("SELECT * FROM [dbo].[Customers]", policy, null).kind()).isEqualTo(Classification.Kind.DENY);
    Allowlist all = Allowlist.of(List.of("*"), List.of(), DIALECT);
    assertThat(all.allows(new TableRef("sys", "objects"))).isFalse();
    assertThat(all.allows(new TableRef("INFORMATION_SCHEMA", "TABLES"))).isFalse();
    assertThat(all.allows(new TableRef("dbo", "Orders"))).isTrue();
  }

  @Test
  void customDefaultSchemasCannotHideDboFallbackBehindAnAllowlist() {
    ObjectNode policy = Fixtures.object();
    policy.putArray("allowlist").add("sales.*");
    ObjectNode advanced = Fixtures.object().put("defaultSchema", "sales");
    assertThat(query("SELECT * FROM orders", policy, advanced).kind()).isEqualTo(Classification.Kind.DENY);
    assertThat(query("SELECT * FROM sales.orders", policy, advanced).kind()).isEqualTo(Classification.Kind.READ);
  }

  @Test
  void bracketsAndStringsKeepTemplatePlaceholdersAndSeparatorsLiteral() {
    String sql = "SELECT N':literal;value', [x]]:name;y] FROM [dbo].[Orders] WHERE id = :id -- :ignored";
    NamedSql compiled = NamedSql.compile(sql, DIALECT, Set.of("id"));
    assertThat(compiled.errors()).isEmpty();
    assertThat(compiled.order()).containsExactly("id");
    assertThat(compiled.jdbc()).contains("N':literal;value'", "[x]]:name;y]", "id = ?");
    assertThat(SqlLexer.scan(sql, DIALECT).statements()).hasSize(1);
    assertThat(NamedSql.compile("SELECT ?", DIALECT, Set.of()).errors()).hasSize(1);
    assertThat(SqlLexer.scan("SELECT [unclosed", DIALECT).unterminated()).isTrue();
    assertThat(SqlLexer.scan("SELECT 'a\\'; SELECT 2", DIALECT).statements()).hasSize(2);
    assertThat(IdentifierRules.table("[My Schema].[a]]b]", DIALECT, "dbo"))
        .isEqualTo(new TableRef("My Schema", "a]b"));
    assertThat(IdentifierRules.quote("a]b", DIALECT)).isEqualTo("[a]]b]");
  }

  private Classification query(String sql, ObjectNode policy, ObjectNode advanced) {
    return SqlRules.query(SqlClassifier.analyze(sql, DIALECT), policy(policy, advanced));
  }

  private Classification execute(String sql, ObjectNode policy, ObjectNode advanced) {
    return SqlRules.execute(SqlClassifier.analyze(sql, DIALECT), policy(policy, advanced));
  }

  private SqlRules.SqlPolicy policy(ObjectNode section, ObjectNode advanced) {
    ConnectionConfig cfg = Fixtures.config("sqlserver", Env.DEV, "sql.example.test", 1433, "app", "app_user",
        advanced, null);
    return SqlRules.SqlPolicy.of(Fixtures.policy(Env.DEV, section), cfg, DIALECT);
  }
}
