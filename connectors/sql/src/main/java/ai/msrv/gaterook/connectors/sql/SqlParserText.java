package ai.msrv.gaterook.connectors.sql;

import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.CCJSqlParserTokenManager;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

/**
 * The text JSqlParser reads, built from the server's own tokens (ARCHITECTURE.md §9 "SQL reads cannot write").
 *
 * <p>The gateway's {@link SqlLexer} follows each server's rules for comments, strings and quoted names. JSqlParser's
 * lexer follows its own: it ends a block comment at a different place in MySQL and Oracle, reads {@code //} and a
 * double dash before any character as comments, reads the hash sign as an operator, ends {@code E'…'} and MySQL's
 * {@code "…"} at an escaped quote. Text the server executes can then look like a comment or a literal to the parser,
 * and the tables, clauses and subqueries in it would be missing from what the allowlist, the database selection and
 * the other rules are checked against.
 *
 * <p>So the parser reads this text instead of the statement as sent: comments become one space, and a string the
 * parser would not tokenize exactly as the lexer does becomes a neutral literal; only the tokens both read the same
 * way are passed on as they are. What cannot be rebuilt, any comment marker still in the code, and a start of a
 * literal that the parser's lexer would run past the server's (a {@code $$} outside PostgreSQL, a {@code q} before a
 * quote) make the text "not analyzable": the statement is treated like one the parser gave up on. The same goes for
 * text the parser's own lexer can't read to the end: the parser may then stop quietly where the error is, and every
 * table and clause after it would be missing. Execution always uses the original text.
 */
final class SqlParserText {

  /** What the parser reads for a string whose own tokens it could read differently from the server. */
  static final String LITERAL = "'literal'";

  /**
   * Comment openers and closers, and quotes, that JSqlParser acts on: after rebuilding none may be left in code. The
   * control character is the stand-in its lexer uses for a hash comment, and starts one wherever it appears.
   */
  private static final String[] MARKERS = {"--", "//", "/*", "*/", "'", "\"", "`", "\001"};

  /**
   * The parser's lexer reads {@code q'{…}'}, {@code q'(…)'}, {@code q'[…]'} and {@code q''…''} as one literal that
   * runs to the last closing pair in the text. Where the server reads a name and then a string, everything between the
   * two would be inside the literal for the parser.
   */
  private static final String ALTERNATIVE_QUOTE_DELIMITERS = "{(['";

  private SqlParserText() {}

  /**
   * The lexer settings of the classifier's parse. The text is scanned with them before it is parsed, so both read
   * it the same way.
   */
  static CCJSqlParser configure(CCJSqlParser parser, SqlSyntax syntax) {
    return parser.withBackslashEscapeCharacter(syntax == SqlSyntax.MYSQL)
        .withSquareBracketQuotation(syntax == SqlSyntax.SQLSERVER);
  }

  /**
   * The text for the parser, or {@code null} when it cannot be made to read what the server reads.
   *
   * @param scan a terminated scan without executable comments
   */
  static String of(SqlLexer.Scan scan, SqlSyntax syntax) {
    String sql = scan.sql();
    StringBuilder text = new StringBuilder(sql.length());
    for (SqlLexer.Segment segment : scan.segments()) {
      String value = sql.substring(segment.start(), segment.end());
      switch (segment.kind()) {
        case CODE -> {
          if (hasMarker(value, syntax)) {
            return null;
          }
          text.append(value);
        }
        case COMMENT -> {
          // A MySQL hash comment is an operator to the parser, and a quote in it would open a literal there.
          if (syntax == SqlSyntax.MYSQL && value.charAt(0) == '#') {
            return null;
          }
          text.append(' ');
        }
        case EXECUTABLE_COMMENT -> {
          return null;
        }
        case QUOTED_IDENTIFIER -> text.append(value);
        case STRING -> {
          String literal = alternativeQuote(text, value) ? null : literal(sql, segment, value, syntax);
          if (literal == null) {
            return null;
          }
          text.append(literal);
        }
      }
    }
    String rebuilt = text.toString();
    return readsToTheEnd(rebuilt, syntax) ? rebuilt : null;
  }

  /**
   * Whether the parser's own lexer reads all of {@code text}. Where it can't, the parser does not always fail: at some
   * places (after a table, where an alias may follow) its lookahead swallows the error and the statement ends there, so
   * the rest of the text would be neither read nor refused. A quoted name that holds a line break is one case; the
   * server accepts it, the lexer does not.
   */
  private static boolean readsToTheEnd(String text, SqlSyntax syntax) {
    if (text.isEmpty()) {
      return false;
    }
    try {
      CCJSqlParserTokenManager lexer = configure(CCJSqlParserUtil.newParser(text), syntax).token_source;
      while (lexer.getNextToken().kind != CCJSqlParserConstants.EOF) {
        // Only whether there is a next token matters.
      }
      return true;
    } catch (RuntimeException e) {
      // TokenMgrException: a character or a quoted name the lexer can't place.
      return false;
    }
  }

  /** The text for a string segment, or {@code null} if it can't be passed on. */
  private static String literal(String sql, SqlLexer.Segment segment, String value, SqlSyntax syntax) {
    char open = value.charAt(0);
    if (open == '\'') {
      if (plain(value)) {
        return value;
      }
      if (syntax == SqlSyntax.POSTGRES && SqlLexer.escapeString(sql, segment.start()) && escapesQuote(value)) {
        // The parser ends the literal at the escaped quote; the code after it is not what the server reads.
        return null;
      }
      return LITERAL;
    }
    if (open == '$' && !value.startsWith("$$")) {
      // The parser reads $$…$$ but not every tagged dollar quote.
      return null;
    }
    // MySQL's "…" (the parser reads an identifier), $$…$$, Oracle's q'…'.
    return LITERAL;
  }

  /** {@code '…'} with no quote or backslash inside: every lexer ends it at the same quote. */
  private static boolean plain(String literal) {
    int last = literal.length() - 1;
    return last >= 1 && literal.charAt(last) == '\'' && literal.indexOf('\'', 1) == last
        && literal.indexOf('\\') < 0;
  }

  /** Whether a backslash escape of an {@code E'…'} string takes a quote. */
  private static boolean escapesQuote(String literal) {
    for (int i = 1; i < literal.length() - 1; i++) {
      if (literal.charAt(i) == '\\') {
        if (literal.charAt(i + 1) == '\'') {
          return true;
        }
        i++;
      }
    }
    return false;
  }

  /**
   * Whether the string follows a {@code q} directly and opens with a delimiter of the parser's alternative quotes: the
   * server reads a name and a string there, the parser one literal that runs on.
   */
  private static boolean alternativeQuote(CharSequence before, String string) {
    int n = before.length();
    return n > 0 && (before.charAt(n - 1) == 'q' || before.charAt(n - 1) == 'Q') && string.length() > 1
        && string.charAt(0) == '\'' && ALTERNATIVE_QUOTE_DELIMITERS.indexOf(string.charAt(1)) >= 0;
  }

  private static boolean hasMarker(String code, SqlSyntax syntax) {
    for (String marker : MARKERS) {
      if (code.contains(marker)) {
        return true;
      }
    }
    // The parser's lexer reads a double dollar sign as the start of a string that runs to the next one. Only PostgreSQL
    // does too, and its strings never reach the code; elsewhere it is part of a name, or nothing the server accepts.
    return syntax != SqlSyntax.POSTGRES && code.contains("$$");
  }
}
