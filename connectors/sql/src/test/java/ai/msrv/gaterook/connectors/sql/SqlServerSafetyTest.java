package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
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
      "SELECT @@VERSION"
  })
  void ordinaryReadsAreRecognized(String sql) {
    assertThat(query(sql, null, null).kind()).as(sql).isEqualTo(Classification.Kind.READ);
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
