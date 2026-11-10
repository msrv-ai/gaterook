package ai.msrv.gaterook.connectors.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The text the parser reads is built from the server's tokens (see {@link SqlParserText}). */
class SqlParserTextTest {

  private static String text(String sql, SqlSyntax syntax) {
    return SqlParserText.of(SqlLexer.scan(sql, syntax), syntax);
  }

  private static String words(String text) {
    return text == null ? null : text.replaceAll("\\s+", " ").strip();
  }

  @Test
  void commentsBecomeWhiteSpace() {
    assertThat(words(text("SELECT 1 /* a */ , 2 -- b\n, 3", SqlSyntax.POSTGRES))).isEqualTo("SELECT 1 , 2 , 3");
    // Block comments nest in PostgreSQL and SQL Server only: what follows the first close is code elsewhere.
    assertThat(words(text("SELECT 1 /* a /* b */ , 2 */ 3", SqlSyntax.POSTGRES))).isEqualTo("SELECT 1 3");
    assertThat(text("SELECT 1 /* a /* b */ , 2", SqlSyntax.MYSQL)).isEqualTo("SELECT 1   , 2");
    assertThat(text("SELECT 1 /* a /* b */ , 2", SqlSyntax.ORACLE)).isEqualTo("SELECT 1   , 2");
    assertThat(words(text("SELECT 1 -- a\n, 2", SqlSyntax.MYSQL))).isEqualTo("SELECT 1 , 2");
  }

  @Test
  void aStringTheParserReadsAsTheLexerDoesIsPassedOn() {
    assertThat(text("SELECT 'a;--b', X'41', N'x'", SqlSyntax.MYSQL)).isEqualTo("SELECT 'a;--b', X'41', N'x'");
    assertThat(text("SELECT DATE '2020-01-01'", SqlSyntax.POSTGRES)).isEqualTo("SELECT DATE '2020-01-01'");
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(delimiter = '|', quoteCharacter = '\u0001', textBlock = """
      POSTGRES | SELECT 'it''s'       | SELECT 'literal'
      POSTGRES | SELECT 'a\\b'        | SELECT 'literal'
      POSTGRES | SELECT E'a\\nb'      | SELECT E'literal'
      POSTGRES | SELECT $$a'b$$       | SELECT 'literal'
      MYSQL    | SELECT 'a\\'b'       | SELECT 'literal'
      MYSQL    | SELECT "a;b"         | SELECT 'literal'
      MYSQL    | SELECT "a\\"b"       | SELECT 'literal'
      ORACLE   | SELECT q'[it's]'     | SELECT 'literal'
      ORACLE   | SELECT nq'{a}'       | SELECT 'literal'
      SQLSERVER | SELECT N'it''s'     | SELECT N'literal'
      """)
  void aStringWithAnEscapeOrAQuoteOfItsOwnBecomesANeutralLiteral(SqlSyntax syntax, String sql, String expected) {
    assertThat(text(sql.strip(), syntax)).isEqualTo(expected.strip());
  }

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(delimiter = '|', quoteCharacter = '\u0001', textBlock = """
      POSTGRES  | SELECT 1 // x
      MYSQL     | SELECT 1 // x
      ORACLE    | SELECT 1 // x
      SQLSERVER | SELECT 1 // x
      MYSQL     | SELECT 1--1
      MYSQL     | SELECT 1 # note
      POSTGRES  | SELECT $tag$x$tag$
      POSTGRES  | SELECT E'\\''
      POSTGRES  | SELECT E'a\\\\\\''
      POSTGRES  | SELECT `a`
      """)
  void textThatTheParserWouldReadDifferentlyIsNotRebuilt(SqlSyntax syntax, String sql) {
    assertThat(text(sql.strip(), syntax)).isNull();
  }

  @Test
  void aControlCharacterThatStartsAParserCommentIsNotRebuilt() {
    assertThat(text("SELECT 1 \u0001 FROM t", SqlSyntax.POSTGRES)).isNull();
    assertThat(text("SELECT 'a\u0001b' FROM t", SqlSyntax.POSTGRES)).isNotNull();
  }

  @Test
  void anEscapedBackslashIsNotAnEscapedQuote() {
    assertThat(text("SELECT E'a\\\\'", SqlSyntax.POSTGRES)).isEqualTo("SELECT E'literal'");
  }
}
