package ai.msrv.gaterook.connectors.sql;

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
 * way are passed on as they are. What cannot be rebuilt, and any comment marker still in the code, makes the text
 * "not analyzable": the statement is treated like one the parser gave up on. Execution always uses the original text.
 */
final class SqlParserText {

  /** What the parser reads for a string whose own tokens it could read differently from the server. */
  static final String LITERAL = "'literal'";

  /**
   * Comment openers and closers, and quotes, that JSqlParser acts on: after rebuilding none may be left in code. The
   * control character is the stand-in its lexer uses for a hash comment, and starts one wherever it appears.
   */
  private static final String[] MARKERS = {"--", "//", "/*", "*/", "'", "\"", "`", "\001"};

  private SqlParserText() {}

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
          if (hasMarker(value)) {
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
          String literal = literal(sql, segment, value, syntax);
          if (literal == null) {
            return null;
          }
          text.append(literal);
        }
      }
    }
    return text.toString();
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

  private static boolean hasMarker(String code) {
    for (String marker : MARKERS) {
      if (code.contains(marker)) {
        return true;
      }
    }
    return false;
  }
}
