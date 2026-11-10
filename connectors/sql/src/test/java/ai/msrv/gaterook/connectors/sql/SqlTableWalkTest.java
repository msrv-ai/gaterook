package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.sql.SqlClassifier.Analysis;
import ai.msrv.gaterook.connectors.sql.SqlClassifier.Kind;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

  @Test
  void everySelectOfAStatementTheWalkReachesIsCounted() {
    String sql = "WITH a AS (SELECT id FROM orders), b AS (SELECT id FROM a UNION SELECT id FROM items) "
        + "SELECT (SELECT max(id) FROM b) FROM a WHERE id IN (SELECT id FROM b)";
    Analysis a = SqlClassifier.analyze(sql, SqlSyntax.POSTGRES);

    assertThat(a.kind()).isEqualTo(Kind.READ);
    assertThat(SqlClassifier.selectKeywords(sql, SqlSyntax.POSTGRES)).isEqualTo(6);
  }
}
