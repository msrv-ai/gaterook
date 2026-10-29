package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.sql.FunctionRules.FunctionRef;
import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Kind;
import ai.msrv.gaterook.connectors.sql.SqlRules.SqlPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OracleSqlSecurityTest {

  private static final SqlSyntax ORACLE = SqlSyntax.ORACLE;

  @Test
  void identifiersUseOracleCaseAndRejectDatabaseLinksAndThreePartNames() {
    assertThat(IdentifierRules.table("app.orders", ORACLE, "REPORTING"))
        .isEqualTo(new TableRef("APP", "ORDERS"));
    assertThat(IdentifierRules.table("\"app\".\"Orders\"", ORACLE, "REPORTING"))
        .isEqualTo(new TableRef("app", "Orders"));
    assertThat(IdentifierRules.table("pg_orders", ORACLE, "APP"))
        .isEqualTo(new TableRef("APP", "PG_ORDERS"));
    assertThat(IdentifierRules.table("orders@remote", ORACLE, "APP")).isNull();
    assertThat(IdentifierRules.table("db.app.orders", ORACLE, "APP")).isNull();
    assertThat(IdentifierRules.display(new TableRef("APP", "Orders"), ORACLE)).isEqualTo("APP.\"Orders\"");
  }

  @Test
  void allowlistsFoldUnquotedNamesWithoutOpeningSystemSchemasOrShadowedDual() {
    Allowlist allow = Allowlist.of(List.of("app.orders", "\"app\".\"Orders\""), List.of(), ORACLE);
    assertThat(allow.allows(new TableRef("APP", "ORDERS"))).isTrue();
    assertThat(allow.allows(new TableRef("app", "Orders"))).isTrue();
    assertThat(allow.allows(new TableRef("APP", "Orders"))).isFalse();
    assertThat(allow.allows(new TableRef("APP", "DUAL"))).isFalse();
    assertThat(allow.allows(new TableRef("SYS", "DUAL"))).isFalse();
    Allowlist wildcard = Allowlist.of(List.of("*"), List.of(), ORACLE);
    assertThat(wildcard.allows(new TableRef("APP", "ORDERS"))).isTrue();
    assertThat(wildcard.allows(new TableRef("SYS", "USER$"))).isFalse();
    assertThat(wildcard.mayAllowSchema("SYSTEM")).isFalse();
  }

  @Test
  void onlyReadsOfTheRealDualCanBypassApplicationSchemaRestrictions() {
    SqlPolicy restricted = new SqlPolicy(Fixtures.policy(Env.DEV, null), ORACLE, false,
        Allowlist.of(List.of("app.orders"), List.of(), ORACLE), List.of(), List.of(), Set.of(), "APP");
    assertThat(SqlRules.query(SqlClassifier.analyze("SELECT 1 FROM SYS.DUAL", ORACLE), restricted).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(SqlRules.execute(SqlClassifier.analyze("DELETE FROM SYS.DUAL", ORACLE), restricted).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(SqlRules.query(SqlClassifier.analyze("SELECT * FROM DUAL", ORACLE), restricted).kind())
        .isEqualTo(Classification.Kind.DENY);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "q'[it\'s ; :hidden @remote NEXTVAL]'", "Q'{it\'s ; :hidden}'", "q'(it\'s ; :hidden)'",
      "q'<it\'s ; :hidden>'", "q'!it\'s ; :hidden!'", "nq'[it\'s ; :hidden]'"
  })
  void alternativeQuotesKeepEmbeddedStatementsAndParametersLiteral(String literal) {
    String sql = "SELECT " + literal + ", :id FROM SYS.DUAL";
    NamedSql named = NamedSql.compile(sql, ORACLE, Set.of("id"));
    assertThat(named.errors()).isEmpty();
    assertThat(named.order()).containsExactly("id");
    assertThat(named.jdbc()).isEqualTo("SELECT " + literal + ", ? FROM SYS.DUAL");
    assertThat(SqlLexer.scan(sql, ORACLE).statements()).containsExactly(sql);
    assertThat(SqlClassifier.analyze(sql, ORACLE).kind()).isEqualTo(Kind.READ);
  }

  @Test
  void oracleDoesNotUsePostgresDollarQuotesOrNestedCommentsOrMysqlBackslashEscapes() {
    assertThat(SqlLexer.scan("SELECT 'a\\'; SELECT 2 FROM SYS.DUAL", ORACLE).statements()).hasSize(2);
    assertThat(SqlLexer.scan("SELECT $$;$$ FROM SYS.DUAL", ORACLE).statements()).hasSize(2);
    assertThat(SqlLexer.scan("SELECT 1 /* outer /* inner */; SELECT 2 FROM SYS.DUAL", ORACLE).statements())
        .hasSize(2);
    assertThat(SqlLexer.scan("SELECT q'[open' FROM SYS.DUAL", ORACLE).unterminated()).isTrue();
    assertThat(NamedSql.compile("SELECT ? FROM SYS.DUAL", ORACLE, Set.of()).errors()).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT orders_seq.NEXTVAL FROM SYS.DUAL", "SELECT \"ORDERS_SEQ\".\"NEXTVAL\" FROM SYS.DUAL",
      "SELECT orders_seq.CURRVAL FROM SYS.DUAL", "SELECT * FROM orders@remote",
      "SELECT DBMS_RANDOM.VALUE FROM SYS.DUAL", "SELECT \"SYS\".\"UTL_HTTP\".\"REQUEST\"('http://example.invalid') FROM SYS.DUAL",
      "CALL app.proc()", "BEGIN app.proc(); END;", "DECLARE x NUMBER; BEGIN NULL; END;",
      "ALTER SESSION SET CURRENT_SCHEMA = OTHER", "SET TRANSACTION READ WRITE", "COMMIT",
      "EXPLAIN PLAN FOR SELECT * FROM orders", "WITH FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END; SELECT f() FROM SYS.DUAL"
  })
  void sessionStateProceduralSqlLinksAndSequenceStateAreDenied(String sql) {
    assertThat(SqlRules.execute(SqlClassifier.analyze(sql, ORACLE), policy()).kind()).as(sql)
        .isEqualTo(Classification.Kind.DENY);
    assertThat(SqlRules.query(SqlClassifier.analyze(sql, ORACLE), policy()).kind()).as(sql)
        .isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void unknownFunctionsCannotBeEnabledByApprovalAllowlistOrServerMetadata() {
    FunctionRules.Verdict result = FunctionRules.check(List.of(new FunctionRef(List.of("custom_fn")),
        new FunctionRef(List.of("APP", "ABS")), new FunctionRef(List.of("\"ABS\""))), ORACLE,
        Set.of("custom_fn"), List.of(), List.of("custom_fn", "APP.ABS", "ABS"));
    assertThat(result.denied()).containsExactly("custom_fn", "APP.ABS", "\"ABS\"");
    assertThat(FunctionRules.check(List.of(new FunctionRef(List.of("NVL")), new FunctionRef(List.of("COUNT"))),
        ORACLE, Set.of(), List.of(), List.of()).clean()).isTrue();
    assertThat(SqlRules.query(SqlClassifier.analyze("SELECT custom_fn(1) FROM SYS.DUAL", ORACLE), policy()).kind())
        .isEqualTo(Classification.Kind.DENY);
  }

  @Test
  void ordinaryQueriesAndSingleApprovedChangesUseExistingPolicyRoutes() {
    assertThat(SqlRules.query(SqlClassifier.analyze("SELECT NVL(name, 'unknown') FROM orders FETCH FIRST 10 ROWS ONLY", ORACLE), policy()).kind())
        .isEqualTo(Classification.Kind.READ);
    assertThat(SqlRules.execute(SqlClassifier.analyze("UPDATE orders SET status = 'done' WHERE id = 1", ORACLE), policy()).kind())
        .isEqualTo(Classification.Kind.MUTATION);
    assertThat(SqlRules.query(SqlClassifier.analyze("SELECT * FROM orders FOR UPDATE", ORACLE), policy()).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(SqlRules.execute(SqlClassifier.analyze("DELETE FROM orders; DELETE FROM items", ORACLE), policy()).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(SqlRules.execute(SqlClassifier.analyze("some unsupported command", ORACLE), policy()).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(SqlRules.query(SqlClassifier.analyze("SELECT /*+ parallel(4) */ * FROM orders", ORACLE), policy()).kind())
        .isEqualTo(Classification.Kind.DENY);
    assertThat(SqlRules.query(SqlClassifier.analyze("SELECT --+ parallel(4)\n * FROM orders", ORACLE), policy()).kind())
        .isEqualTo(Classification.Kind.DENY);
  }

  private static SqlPolicy policy() {
    return new SqlPolicy(Fixtures.policy(Env.DEV, null), ORACLE, false,
        Allowlist.of(List.of(), List.of(), ORACLE), List.of(), List.of(), Set.of(), "APP");
  }
}
