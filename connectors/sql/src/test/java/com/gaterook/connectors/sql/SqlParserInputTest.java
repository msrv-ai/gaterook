package com.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.Env;
import com.gaterook.connectors.sql.SqlClassifier.Analysis;
import com.gaterook.connectors.sql.SqlClassifier.Kind;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parser must read the text the server's lexer reads. Where the two tokenize a comment or a literal differently,
 * text the server executes can look like a comment or a literal to the parser, and the tables in it would skip the
 * allowlist and the database selection. The same happens where the parser's lexer fails, which ends the statement
 * quietly, or reads a literal further than the server does. Such text is analyzed as the server reads it, or not
 * analyzed at all.
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

  /**
   * Names and characters the servers accept that the parser's own lexer cannot read to the end, or reads as something
   * else. The parser does not always fail there: it may stop quietly, and the tables after that point would be neither
   * read nor refused.
   */
  static Stream<Arguments> textTheParsersLexerCannotRead() {
    return Stream.of(
        // A quoted name made of a line break (line feed, carriage return, both), used as a table alias.
        Arguments.of(SqlSyntax.POSTGRES, "SELECT s.note FROM orders \"\n\" JOIN hidden_table s ON 1 = 1"),
        Arguments.of(SqlSyntax.POSTGRES, "SELECT s.note FROM orders \"\r\" JOIN hidden_table s ON 1 = 1"),
        Arguments.of(SqlSyntax.POSTGRES, "SELECT s.note FROM orders \"\r\n\" JOIN hidden_table s ON 1 = 1"),
        Arguments.of(SqlSyntax.ORACLE, "SELECT s.note FROM orders \"\n\" JOIN hidden_table s ON 1 = 1"),
        Arguments.of(SqlSyntax.SQLSERVER, "SELECT s.note FROM orders \"\n\" JOIN hidden_table s ON 1 = 1"),
        Arguments.of(SqlSyntax.SQLSERVER, "SELECT s.note FROM orders [\n] JOIN hidden_table s ON 1 = 1"),
        Arguments.of(SqlSyntax.MYSQL, "SELECT s.note FROM orders `\n` JOIN hidden_table s ON 1 = 1"),
        // A character that is white space to the servers and unknown to the parser's lexer.
        Arguments.of(SqlSyntax.POSTGRES, "SELECT s.note FROM orders\fJOIN hidden_table s ON 1 = 1"),
        Arguments.of(SqlSyntax.MYSQL, "SELECT s.note FROM orders\fJOIN hidden_table s ON 1 = 1"),
        Arguments.of(SqlSyntax.SQLSERVER, "SELECT s.note FROM orders\fJOIN hidden_table s ON 1 = 1"),
        Arguments.of(SqlSyntax.ORACLE, "SELECT s.note FROM orders\fJOIN hidden_table s ON 1 = 1"));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("textTheParsersLexerCannotRead")
  void aTableAfterTextTheParsersLexerCannotReadIsSeenOrTheTextIsNotAnalyzed(SqlSyntax syntax, String sql) {
    Analysis a = SqlClassifier.analyze(sql, syntax);

    assertThat(a.kind() == Kind.UNPARSED || sees(a)).as("%s → %s", sql, a).isTrue();
  }

  /**
   * The parser's lexer reads these starts of a string past the place where the server's string ends or where there is
   * none: the server reads a name, and everything up to the next double dollar sign or closing pair is, to the parser,
   * inside a literal.
   */
  static Stream<Arguments> stringsTheParsersLexerRunsPast() {
    return Stream.of(
        Arguments.of(SqlSyntax.MYSQL, "SELECT 1 FROM orders $$o JOIN hidden_table h ON 1 = 1"),
        Arguments.of(SqlSyntax.MYSQL, "SELECT 1 FROM orders $$o JOIN hidden_table h ON 1 = 1 AND x = $$"),
        Arguments.of(SqlSyntax.MYSQL, "SELECT 1 FROM orders $$o JOIN hidden_table h ON 1 = 1 INTO OUTFILE '/tmp/o'"),
        Arguments.of(SqlSyntax.ORACLE, "SELECT 1 FROM orders $$o JOIN hidden_table h ON 1 = 1 AND x = $$"),
        Arguments.of(SqlSyntax.SQLSERVER, "SELECT 1 FROM orders $$o JOIN hidden_table h ON 1 = 1 AND x = $$"),
        Arguments.of(SqlSyntax.MYSQL, "SELECT q'{a' FROM orders JOIN hidden_table h ON '}' = ''"),
        Arguments.of(SqlSyntax.MYSQL, "SELECT q'(a' FROM orders JOIN hidden_table h ON ')' = ''"),
        Arguments.of(SqlSyntax.MYSQL, "SELECT q'[a' FROM orders JOIN hidden_table h ON ']' = ''"),
        Arguments.of(SqlSyntax.SQLSERVER, "SELECT q'{a' FROM orders JOIN hidden_table h ON '}' = ''"),
        Arguments.of(SqlSyntax.POSTGRES, "SELECT q'{a' FROM orders JOIN hidden_table h ON '}' = ''"));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("stringsTheParsersLexerRunsPast")
  void aTableInsideAStringOnlyTheParserOpensIsSeenOrTheTextIsNotAnalyzed(SqlSyntax syntax, String sql) {
    Analysis a = SqlClassifier.analyze(sql, syntax);

    assertThat(a.kind() == Kind.UNPARSED || sees(a)).as("%s → %s", sql, a).isTrue();
  }

  /** The whole route, from the call to the rule: a table the allowlist does not name is not reached by such text. */
  @Test
  void aTableAfterAQuotedLineBreakDoesNotPassAnAllowlist() {
    String join = " JOIN secrets s ON 1 = 1";

    assertThat(SqlClassifierTest.classify(false, "sql_query", Env.DEV, "SELECT s.note FROM orders \"\n\"" + join,
        "allowlist=public.orders").ruleId()).isEqualTo("dev.sql.unparsed");
    assertThat(SqlClassifierTest.classify(true, "sql_query", Env.DEV, "SELECT s.note FROM orders `\n`" + join,
        "allowlist=app.orders").ruleId()).isEqualTo("dev.sql.unparsed");
    assertThat(SqlClassifierTest.classify(true, "sql_query", Env.DEV, "SELECT s.id FROM orders $$o, secrets s",
        "allowlist=app.orders").ruleId()).isEqualTo("dev.sql.unparsed");
  }

  /** A file or lock clause after such text must still make the statement more than a read. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {" INTO OUTFILE '/tmp/o'", " INTO DUMPFILE '/tmp/o'", " FOR UPDATE", " FOR SHARE"})
  void aClauseAfterTextTheParsersLexerCannotReadStillMakesTheStatementMoreThanARead(String clause) {
    for (String name : new String[] {"`\n`", "$$o"}) {
      String sql = "SELECT 1 FROM orders " + name + clause;

      assertThat(SqlClassifier.analyze(sql, SqlSyntax.MYSQL).kind()).as(sql).isNotEqualTo(Kind.READ);
    }
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
  void namesAndStringsThatLookLikeTheParsersOwnQuotesStayAnalyzed() {
    // PostgreSQL reads $$ as a string or inside a name, as the parser does.
    Analysis dollars = SqlClassifier.analyze("SELECT a$$b, $$x$$ FROM orders WHERE id IN (SELECT id FROM hidden_table)",
        SqlSyntax.POSTGRES);
    assertThat(dollars.kind()).isEqualTo(Kind.READ);
    assertThat(sees(dollars)).isTrue();
    // A q before a string that does not open like an alternative quote, and a q that is not directly before one.
    Analysis plainQ = SqlClassifier.analyze("SELECT q 'a', q'b' FROM orders WHERE id IN (SELECT id FROM hidden_table)",
        SqlSyntax.MYSQL);
    assertThat(plainQ.kind()).isEqualTo(Kind.READ);
    assertThat(sees(plainQ)).isTrue();
    // A quoted name with a character that is not a line break.
    Analysis quoted = SqlClassifier.analyze("SELECT s.note FROM orders \"x y\" JOIN hidden_table s ON 1 = 1",
        SqlSyntax.POSTGRES);
    assertThat(quoted.kind()).isEqualTo(Kind.READ);
    assertThat(sees(quoted)).isTrue();
  }

  /** A doubled quote or backtick inside a quoted name is read the same way by both lexers: the name stays analyzed. */
  static Stream<Arguments> namesWithADoubledQuote() {
    return Stream.of(
        Arguments.of(SqlSyntax.POSTGRES, "SELECT \"a\"\"b\" FROM orders WHERE id IN (SELECT id FROM hidden_table)"),
        Arguments.of(SqlSyntax.ORACLE, "SELECT \"a\"\"b\" FROM orders WHERE id IN (SELECT id FROM hidden_table)"),
        Arguments.of(SqlSyntax.SQLSERVER, "SELECT \"a\"\"b\" FROM orders WHERE id IN (SELECT id FROM hidden_table)"),
        Arguments.of(SqlSyntax.MYSQL, "SELECT `a``b` FROM orders WHERE id IN (SELECT id FROM hidden_table)"));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("namesWithADoubledQuote")
  void aQuotedNameWithADoubledQuoteStaysAnalyzed(SqlSyntax syntax, String sql) {
    Analysis a = SqlClassifier.analyze(sql, syntax);

    assertThat(a.kind()).as("%s → %s", sql, a).isEqualTo(Kind.READ);
    assertThat(sees(a)).as(sql).isTrue();
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
