package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.sql.SqlClassifier.Analysis;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Kind;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every table of a statement meets the allowlist and the database selection, wherever in the statement it is named:
 * the walk over the parse tree must reach the operand positions that JSqlParser's own table finder skips.
 */
class SqlTableWalkTest {

  private static final String HIDDEN = "hidden_table";

  /** A subquery naming {@value #HIDDEN} in a position the parser's table finder does not enter. */
  static Stream<Arguments> skippedOperandPositions() {
    return Stream.of(
        // The ordering and the value of a JSON aggregate.
        Arguments.of(SqlSyntax.POSTGRES, "SELECT JSON_ARRAYAGG(id ORDER BY (SELECT id FROM hidden_table)) FROM orders"),
        Arguments.of(SqlSyntax.POSTGRES, "SELECT JSON_OBJECTAGG(id : (SELECT id FROM hidden_table)) FROM orders"),
        Arguments.of(SqlSyntax.ORACLE, "SELECT JSON_ARRAYAGG(id ORDER BY (SELECT id FROM hidden_table)) FROM orders"),
        // The key of a JSON aggregate (a column, which can hold a subscript), and the PARTITION BY and ORDER BY of its
        // window.
        Arguments.of(SqlSyntax.POSTGRES, "SELECT JSON_OBJECTAGG(tags[(SELECT 1 FROM hidden_table)] : id) FROM orders"),
        Arguments.of(SqlSyntax.POSTGRES,
            "SELECT JSON_ARRAYAGG(id) OVER (PARTITION BY (SELECT id FROM hidden_table)) FROM orders"),
        Arguments.of(SqlSyntax.POSTGRES,
            "SELECT JSON_ARRAYAGG(id) OVER (ORDER BY (SELECT id FROM hidden_table)) FROM orders"),
        // The subscript of a column.
        Arguments.of(SqlSyntax.POSTGRES, "SELECT tags[(SELECT 1 FROM hidden_table)] FROM orders"),
        // The escape operand of LIKE.
        Arguments.of(SqlSyntax.POSTGRES,
            "SELECT id FROM orders WHERE note LIKE 'a%' ESCAPE (SELECT note FROM hidden_table LIMIT 1)"),
        Arguments.of(SqlSyntax.MYSQL,
            "SELECT id FROM orders WHERE note LIKE 'a%' ESCAPE (SELECT note FROM hidden_table LIMIT 1)"),
        // The TOP expression of SQL Server.
        Arguments.of(SqlSyntax.SQLSERVER, "SELECT TOP ((SELECT COUNT(*) FROM hidden_table)) id FROM orders"));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("skippedOperandPositions")
  void aSubqueryInEveryOperandPositionIsSeenByTheTableWalk(SqlSyntax syntax, String sql) {
    Analysis a = SqlClassifier.analyze(sql, syntax);

    assertThat(a.statements()).as("%s → %s", sql, a).hasSize(1);
    assertThat(a.statements().getFirst().tables()).as(sql).contains(HIDDEN);
  }

  @Test
  void selectKeywordsAreCountedInCodeOnly() {
    String sql = "SELECT 'select' /* select */ \"select\", selected, my_select FROM (SELECT 1) t -- select";

    assertThat(SqlClassifier.selectKeywords(sql, SqlSyntax.POSTGRES)).isEqualTo(2);
  }

  /**
   * Backstop for what the walk still does not enter: a statement with more SELECT keywords than SELECTs the walk
   * visited is not analyzed, as a call that only the text scan finds is still checked. The operand of
   * {@code FOR SYSTEM_TIME AS OF} is such a position; if the walk learns it, use another one the walk cannot enter.
   */
  @Test
  void aSelectTheWalkNeverReachedMakesTheStatementNotAnalyzed() {
    String sql = "SELECT id FROM orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM hidden_table)";
    Analysis a = SqlClassifier.analyze(sql, SqlSyntax.SQLSERVER);

    assertThat(a.kind()).isEqualTo(Kind.UNPARSED);
    assertThat(a.problem()).contains("every SELECT");
  }

  /**
   * The backstop holds for changes and DDL too: they run after approval, and the tables a person approves and the
   * allowlist checks must be all the tables the statement reads. The operand of {@code FOR SYSTEM_TIME AS OF} is the
   * position the walk does not enter, as above.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "INSERT INTO orders (id) SELECT id FROM orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM hidden_table)",
      "UPDATE orders SET id = 1 FROM orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM hidden_table)",
      "DELETE FROM orders WHERE id IN (SELECT id FROM orders FOR SYSTEM_TIME AS OF "
          + "(SELECT MAX(changed) FROM hidden_table))",
      "MERGE INTO orders o USING (SELECT id FROM orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM hidden_table)) s "
          + "ON o.id = s.id WHEN MATCHED THEN DELETE",
      "CREATE TABLE copied AS SELECT id FROM orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM hidden_table)",
      "CREATE VIEW recent AS SELECT id FROM orders FOR SYSTEM_TIME AS OF (SELECT MAX(changed) FROM hidden_table)"
  })
  void aSelectTheWalkNeverReachedMakesAChangeNotAnalyzed(String sql) {
    Analysis a = SqlClassifier.analyze(sql, SqlSyntax.SQLSERVER);

    assertThat(a.kind()).as(sql).isEqualTo(Kind.UNPARSED);
    assertThat(a.problem()).as(sql).contains("every SELECT");
  }

  /**
   * Reads that are not counted: a SHOW whose condition holds a subquery has no table list at all, so it meets a
   * restricted allowlist and the database selection as a statement whose tables can't be named, or it isn't parsed.
   */
  @Test
  void aShowWithASubqueryHasNoTableList() {
    Analysis tables = SqlClassifier.analyze("SHOW TABLES WHERE Tables_in_app IN (SELECT name FROM hidden_table)",
        SqlSyntax.MYSQL);
    assertThat(tables.statements()).hasSize(1);
    assertThat(tables.statements().getFirst().tables()).isNull();

    Analysis columns = SqlClassifier.analyze("SHOW COLUMNS FROM orders WHERE Field IN (SELECT name FROM hidden_table)",
        SqlSyntax.MYSQL);
    assertThat(columns.kind()).isEqualTo(Kind.UNPARSED);
  }

  @Test
  void everySelectOfAStatementTheWalkReachesIsCounted() {
    String sql = "WITH a AS (SELECT id FROM orders), b AS (SELECT id FROM a UNION SELECT id FROM items) "
        + "SELECT (SELECT max(id) FROM b) FROM a WHERE id IN (SELECT id FROM b)";
    Analysis a = SqlClassifier.analyze(sql, SqlSyntax.POSTGRES);

    assertThat(a.kind()).isEqualTo(Kind.READ);
    assertThat(SqlClassifier.selectKeywords(sql, SqlSyntax.POSTGRES)).isEqualTo(6);
  }
}
