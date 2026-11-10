package ai.msrv.gaterook.connectors.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL Server: whether the code of one statement is exactly one query by T-SQL's own tokens (ARCHITECTURE.md §9
 * "SQL reads cannot write").
 *
 * <p>T-SQL needs no separator between statements: {@code SELECT 1 DELETE FROM t} is two statements to the server.
 * JSqlParser takes {@code DELETE} for an alias and reads one SELECT, and the gateway's lexer splits only at
 * semicolons, so the statement count of the two agree. A reserved word can never be a name on the server, so the
 * check is on the words: outside parentheses (nothing starts a statement inside them) the code of a read may hold
 * only the reserved words a single SELECT uses, a query starts only at the beginning, after a set operator or after
 * the common table expressions of a {@code WITH}, and parentheses and {@code CASE … END} must balance. Literals,
 * quoted names and comments are not words, and neither is a word after the dot of a qualified name
 * ({@code c.precision}). Anything else is not a read: the call is treated as not analyzed.
 */
final class SqlServerBatch {

  /** Transact-SQL reserved keywords: never an alias or a name unless quoted. */
  private static final Set<String> RESERVED = Set.of("ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC",
      "AUTHORIZATION", "BACKUP", "BEGIN", "BETWEEN", "BREAK", "BROWSE", "BULK", "BY", "CASCADE", "CASE", "CHECK",
      "CHECKPOINT", "CLOSE", "CLUSTERED", "COALESCE", "COLLATE", "COLUMN", "COMMIT", "COMPUTE", "CONSTRAINT",
      "CONTAINS", "CONTAINSTABLE", "CONTINUE", "CONVERT", "CREATE", "CROSS", "CURRENT", "CURRENT_DATE",
      "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "CURSOR", "DATABASE", "DBCC", "DEALLOCATE", "DECLARE",
      "DEFAULT", "DELETE", "DENY", "DESC", "DISK", "DISTINCT", "DISTRIBUTED", "DOUBLE", "DROP", "DUMP", "ELSE",
      "END", "ERRLVL", "ESCAPE", "EXCEPT", "EXEC", "EXECUTE", "EXISTS", "EXIT", "EXTERNAL", "FETCH", "FILE",
      "FILLFACTOR", "FOR", "FOREIGN", "FREETEXT", "FREETEXTTABLE", "FROM", "FULL", "FUNCTION", "GOTO", "GRANT",
      "GROUP", "HAVING", "HOLDLOCK", "IDENTITY", "IDENTITY_INSERT", "IDENTITYCOL", "IF", "IN", "INDEX", "INNER",
      "INSERT", "INTERSECT", "INTO", "IS", "JOIN", "KEY", "KILL", "LEFT", "LIKE", "LINENO", "LOAD", "MERGE",
      "NATIONAL", "NOCHECK", "NONCLUSTERED", "NOT", "NULL", "NULLIF", "OF", "OFF", "OFFSETS", "ON", "OPEN",
      "OPENDATASOURCE", "OPENQUERY", "OPENROWSET", "OPENXML", "OPTION", "OR", "ORDER", "OUTER", "OVER", "PERCENT",
      "PIVOT", "PLAN", "PRECISION", "PRIMARY", "PRINT", "PROC", "PROCEDURE", "PUBLIC", "RAISERROR", "READ",
      "READTEXT", "RECONFIGURE", "REFERENCES", "REPLICATION", "RESTORE", "RESTRICT", "RETURN", "REVERT", "REVOKE",
      "RIGHT", "ROLLBACK", "ROWCOUNT", "ROWGUIDCOL", "RULE", "SAVE", "SCHEMA", "SECURITYAUDIT", "SELECT",
      "SEMANTICKEYPHRASETABLE", "SEMANTICSIMILARITYDETAILSTABLE", "SEMANTICSIMILARITYTABLE", "SESSION_USER", "SET",
      "SETUSER", "SHUTDOWN", "SOME", "STATISTICS", "SYSTEM_USER", "TABLE", "TABLESAMPLE", "TEXTSIZE", "THEN", "TO",
      "TOP", "TRAN", "TRANSACTION", "TRIGGER", "TRUNCATE", "TRY_CONVERT", "TSEQUAL", "UNION", "UNIQUE", "UNPIVOT",
      "UPDATE", "UPDATETEXT", "USE", "USER", "VALUES", "VARYING", "VIEW", "WAITFOR", "WHEN", "WHERE", "WHILE", "WITH",
      "WRITETEXT");

  /**
   * The reserved words a single SELECT uses outside parentheses. {@code SELECT}, {@code WITH}, {@code FETCH},
   * {@code CASE} and {@code END} are in the list and checked further where they stand.
   */
  private static final Set<String> QUERY_WORDS = Set.of("ALL", "AND", "ANY", "AS", "ASC", "BETWEEN", "BY", "CASE",
      "COALESCE", "COLLATE", "CONTAINS", "CONTAINSTABLE", "CONVERT", "CROSS", "CURRENT_DATE", "CURRENT_TIME",
      "CURRENT_TIMESTAMP", "CURRENT_USER", "DESC", "DISTINCT", "ELSE", "END", "ESCAPE", "EXCEPT", "EXISTS", "FETCH",
      "FOR", "FREETEXT", "FREETEXTTABLE", "FROM", "FULL", "GROUP", "HAVING", "IDENTITYCOL", "IN", "INNER",
      "INTERSECT", "IS", "JOIN", "LEFT", "LIKE", "NOT", "NULL", "NULLIF", "OF", "ON", "OPTION", "OR", "ORDER", "OUTER",
      "OVER", "PERCENT", "PIVOT", "RIGHT", "ROWGUIDCOL", "SELECT", "SESSION_USER", "SOME", "SYSTEM_USER",
      "TABLESAMPLE", "THEN", "TO", "TOP", "TRY_CONVERT", "UNION", "UNPIVOT", "USER", "WHEN", "WHERE", "WITH");

  private static final Set<String> SET_OPERATORS = Set.of("UNION", "INTERSECT", "EXCEPT");

  /**
   * {@code NAME}: a quoted name. {@code DOT}: a dot, which separates the parts of a qualified name or sits in a number.
   * {@code BAD}: letters stuck to a number, which the server may split into a number and a word.
   */
  private enum Type { WORD, NAME, DOT, OPEN, CLOSE, OTHER, BAD }

  /** A number the server reads whole: digits, {@code 0x} and hex digits, or an exponent's {@code E} and digits. */
  private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:[eE][0-9]*)?|0[xX][0-9A-Fa-f]*");

  private record Token(Type type, String word) {

    boolean is(Type t) {
      return type == t;
    }

    boolean word(String upper) {
      return type == Type.WORD && word.equals(upper);
    }
  }

  private SqlServerBatch() {}

  /**
   * Whether {@code statement} (one piece between semicolons, as sent) is one query: {@code SELECT}, {@code WITH … SELECT}
   * or a parenthesized query, with set operators between queries.
   */
  static boolean oneQuery(String statement) {
    List<Token> tokens = tokens(statement);
    if (tokens.isEmpty()) {
      return false;
    }
    Token first = tokens.getFirst();
    if (!first.is(Type.OPEN) && !first.word("SELECT") && !first.word("WITH")) {
      return false;
    }
    boolean[] nameParts = nameParts(tokens);
    int depth = 0;
    int cases = 0;
    boolean ctes = false;       // inside the common table expressions after WITH
    boolean started = false;    // the query has started at depth 0: a SELECT, or a parenthesis
    boolean pending = false;    // a set operator wants its query next
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.is(Type.BAD)) {
        return false;
      }
      if (t.is(Type.OPEN)) {
        if (depth == 0) {
          started |= !ctes;
          pending = false;
        }
        depth++;
        continue;
      }
      if (t.is(Type.CLOSE)) {
        if (--depth < 0) {
          return false;
        }
        continue;
      }
      if (!t.is(Type.WORD) || nameParts[i]) {
        if (depth == 0) {
          pending = false;
        }
        continue;
      }
      String w = t.word();
      if (w.equals("CASE")) {
        cases++;
      } else if (w.equals("END") && --cases < 0) {
        return false;
      }
      if (depth > 0) {
        continue;
      }
      if (ctes) {
        // Names, column lists and bodies of the common table expressions, then the query.
        if (w.equals("SELECT")) {
          if (!tokens.get(i - 1).is(Type.CLOSE)) {
            return false;
          }
          ctes = false;
          started = true;
        } else if (!w.equals("AS") && RESERVED.contains(w)) {
          return false;
        }
        continue;
      }
      if (!started) {
        // Only the first word gets here: WITH, or SELECT.
        ctes = w.equals("WITH");
        started = !ctes;
        continue;
      }
      if (w.equals("SELECT")) {
        if (!pending) {
          return false;
        }
        pending = false;
        continue;
      }
      if (SET_OPERATORS.contains(w)) {
        pending = true;
        continue;
      }
      if (w.equals("ALL") && pending) {
        continue;
      }
      pending = false;
      if (w.equals("WITH") && !tableHintOrModifier(tokens, i)) {
        return false;
      }
      if (w.equals("FETCH") && !pageClause(tokens, i)) {
        return false;
      }
      if (RESERVED.contains(w) && !QUERY_WORDS.contains(w)) {
        return false;
      }
    }
    return depth == 0 && cases == 0 && started && !ctes && !pending;
  }

  /**
   * The words that follow a dot in a qualified name ({@code c.precision}, {@code db..t.user}): a part of a name, never a
   * statement word, whatever it is spelled. The dot must follow a name: a quoted name, a word that is not reserved, or
   * another part of a name. After a number, a string, a parenthesis or a reserved word it separates nothing, and the
   * word after it is checked like any other ({@code SELECT 1. DELETE …} ends the number and starts a statement).
   */
  private static boolean[] nameParts(List<Token> tokens) {
    boolean[] parts = new boolean[tokens.size()];
    boolean name = false;       // the previous token is a name that a dot can qualify
    boolean qualified = false;  // the previous token is a dot after such a name
    for (int i = 0; i < parts.length; i++) {
      Token t = tokens.get(i);
      switch (t.type()) {
        case NAME -> {
          name = true;
          qualified = false;
        }
        case DOT -> qualified = name;     // a second dot, as in db..t, follows a name as well
        case WORD -> {
          parts[i] = qualified;
          name = qualified || !RESERVED.contains(t.word());
          qualified = false;
        }
        default -> {
          name = false;
          qualified = false;
        }
      }
    }
    return parts;
  }

  /** {@code WITH (NOLOCK)}, {@code TOP … WITH TIES}, {@code GROUP BY … WITH ROLLUP}: not a common table expression. */
  private static boolean tableHintOrModifier(List<Token> tokens, int i) {
    if (i + 1 >= tokens.size()) {
      return false;
    }
    Token next = tokens.get(i + 1);
    return next.is(Type.OPEN) || next.word("TIES") || next.word("ROLLUP") || next.word("CUBE");
  }

  /** {@code ROWS FETCH NEXT 5 ROWS ONLY}, not a cursor's {@code FETCH NEXT FROM}. */
  private static boolean pageClause(List<Token> tokens, int i) {
    if (i == 0 || i + 2 >= tokens.size()) {
      return false;
    }
    Token before = tokens.get(i - 1);
    Token kind = tokens.get(i + 1);
    Token after = tokens.get(i + 2);
    return (before.word("ROW") || before.word("ROWS")) && (kind.word("NEXT") || kind.word("FIRST"))
        && !after.word("FROM") && !after.word("GLOBAL");
  }

  /**
   * The words and parentheses of the code. A word is a run of ASCII letters, digits and {@code _ @ # $} that starts
   * with a letter or {@code _ @ #}; any other character, white space or not, ends a word, so the check never joins
   * what the server separates ({@code $} starts no name, and a run that starts with a digit must be a number whole).
   * Strings and quoted names are not words; a comment separates like white space.
   */
  private static List<Token> tokens(String statement) {
    SqlLexer.Scan scan = SqlLexer.scan(statement, SqlSyntax.SQLSERVER);
    List<Token> tokens = new ArrayList<>();
    for (SqlLexer.Segment s : scan.segments()) {
      switch (s.kind()) {
        case COMMENT, EXECUTABLE_COMMENT -> { }
        case STRING -> tokens.add(new Token(Type.OTHER, ""));
        case QUOTED_IDENTIFIER -> tokens.add(new Token(Type.NAME, ""));
        case CODE -> code(statement, s.start(), s.end(), tokens);
      }
    }
    return tokens;
  }

  private static void code(String sql, int from, int to, List<Token> tokens) {
    int i = from;
    while (i < to) {
      char c = sql.charAt(i);
      boolean digit = c >= '0' && c <= '9';
      if (isWordStart(c) || digit) {
        int j = i + 1;
        while (j < to && isWordPart(sql.charAt(j))) {
          j++;
        }
        String run = sql.substring(i, j);
        if (!digit) {
          tokens.add(new Token(Type.WORD, run.toUpperCase(Locale.ROOT)));
        } else {
          tokens.add(new Token(NUMBER.matcher(run).matches() ? Type.OTHER : Type.BAD, ""));
        }
        i = j;
      } else {
        if (!Character.isWhitespace(c)) {
          tokens.add(new Token(c == '(' ? Type.OPEN : c == ')' ? Type.CLOSE : c == '.' ? Type.DOT : Type.OTHER, ""));
        }
        i++;
      }
    }
  }

  private static boolean isWordStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '@' || c == '#';
  }

  private static boolean isWordPart(char c) {
    return isWordStart(c) || (c >= '0' && c <= '9') || c == '$';
  }
}
