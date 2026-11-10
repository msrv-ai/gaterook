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
 * The parser must read the text the server's lexer reads. Where the two tokenize a comment or a literal differently,
 * text the server executes can look like a comment or a literal to the parser, and the tables in it would skip the
 * allowlist and the database selection. Such text is analyzed as the server reads it, or not analyzed at all.
 */
class SqlParserInputTest {

  private static final String HIDDEN = "hidden_table";

  /** One minimal form per kind of difference; each names {@value #HIDDEN}, which the server reads as code. */
  static Stream<Arguments> differentlyTokenizedForms() {
    return Stream.of(
        // A line comment that only the parser knows.
        Arguments.of(SqlSyntax.POSTGRES, "SELECT 1 // x FROM hidden_table"),
        Arguments.of(SqlSyntax.MYSQL, "SELECT 1 // x FROM hidden_table"),
        Arguments.of(SqlSyntax.ORACLE, "SELECT 1 // x FROM hidden_table"),
        Arguments.of(SqlSyntax.SQLSERVER, "SELECT 1 // x FROM hidden_table"),
        // MySQL starts a double-dash comment only before white space.
        Arguments.of(SqlSyntax.MYSQL, "SELECT 1--1 FROM hidden_table"),
        // Block comments do not nest in MySQL and Oracle.
        Arguments.of(SqlSyntax.MYSQL, "SELECT 1 /* a /* b */ , (SELECT id FROM hidden_table) -- */"),
        Arguments.of(SqlSyntax.ORACLE, "SELECT 1 /* a /* b */ , (SELECT id FROM hidden_table) -- */ FROM dual"),
        // The MySQL hash comment can hold a quote.
        Arguments.of(SqlSyntax.MYSQL, "SELECT 1 # '\n, (SELECT id FROM hidden_table) -- '\n"),
        // A MySQL double-quoted string takes backslash escapes.
        Arguments.of(SqlSyntax.MYSQL, "SELECT \"\\\"\" FROM hidden_table"),
        // A PostgreSQL E string takes backslash escapes.
        Arguments.of(SqlSyntax.POSTGRES,
            "SELECT id FROM orders WHERE note = E'\\' --' OR id IN (SELECT id FROM hidden_table)"));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("differentlyTokenizedForms")
  void aTableTheServerReadsIsSeenOrTheTextIsNotAnalyzed(SqlSyntax syntax, String sql) {
    Analysis a = SqlClassifier.analyze(sql, syntax);

    assertThat(a.kind() == Kind.UNPARSED || sees(a)).as("%s → %s", sql, a).isTrue();
  }

  @Test
  void literalsAndCommentsThatBothReadTheSameWayStayAnalyzed() {
    Analysis mysql = SqlClassifier.analyze("SELECT \"a;b\", 'it''s', 'a\\'b' /* c */ FROM orders -- d\n"
        + "WHERE id IN (SELECT id FROM hidden_table)", SqlSyntax.MYSQL);
    assertThat(mysql.kind()).isEqualTo(Kind.READ);
    assertThat(sees(mysql)).isTrue();

    Analysis postgres = SqlClassifier.analyze("SELECT 'it''s', E'a\\\\b', $$x'y$$ FROM orders /* c /* nested */ */ "
        + "WHERE id IN (SELECT id FROM hidden_table)", SqlSyntax.POSTGRES);
    assertThat(postgres.kind()).isEqualTo(Kind.READ);
    assertThat(sees(postgres)).isTrue();
  }

  @Test
  void aCommentOrALiteralNeverChangesWhereStatementsEnd() {
    assertThat(SqlClassifier.analyze("SELECT 1 /* ; */ ; SELECT ';'", SqlSyntax.MYSQL).count()).isEqualTo(2);
    assertThat(SqlClassifier.analyze("SELECT 'a\\'; SELECT 2'", SqlSyntax.MYSQL).count()).isEqualTo(1);
    assertThat(SqlClassifier.analyze("SELECT 'a\\'; SELECT 2", SqlSyntax.POSTGRES).count()).isEqualTo(2);
  }

  private static boolean sees(Analysis a) {
    return a.statements().stream().anyMatch(s -> s.tables() != null && s.tables().contains(HIDDEN));
  }
}
