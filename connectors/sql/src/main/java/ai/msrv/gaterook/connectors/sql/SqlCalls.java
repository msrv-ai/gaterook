package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.FunctionRules.FunctionRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The calls of one statement as the server's lexer sees them: every name, or qualified name, before an opening
 * parenthesis in its code (ARCHITECTURE.md §9 "SQL reads cannot write").
 *
 * <p>JSqlParser's walk doesn't reach every expression of the tree it builds, so a read's function rules must not rest
 * on that walk alone. Keywords, type names and the names the parser itself places before a column list (aliases and
 * CTEs) aren't calls; any other name before {@code (} is one, which fails closed: an unknown name is a function that
 * isn't built in.
 */
final class SqlCalls {

  /** Words that precede a parenthesis as syntax, never as a call of a function of that name. */
  private static final Set<String> KEYWORDS = Set.of("AGAINST", "ALL", "AND", "ANY", "APPLY", "ARRAY", "AS",
      "BETWEEN", "BY", "CASE", "CAST", "COLUMNS", "CONVERT", "CUBE", "CURSOR", "DISTINCT", "DIV", "ELSE", "ESCAPE",
      "EXCEPT", "EXISTS", "EXPLAIN", "EXTRACT", "FILTER", "FOR", "FROM", "GROUP", "GROUPING", "GROUP_CONCAT", "HAVING",
      "ILIKE", "IN", "INDEX", "INSERT", "INTERSECT", "INTERVAL", "IS", "JOIN", "JSON_ARRAY", "JSON_ARRAYAGG",
      "JSON_EXISTS", "JSON_OBJECT", "JSON_OBJECTAGG", "JSON_QUERY", "JSON_TABLE", "JSON_VALUE", "KEEP", "KEY",
      "LATERAL", "LIKE", "LIMIT", "MATCH", "MATCH_RECOGNIZE", "MATERIALIZED", "MEASURES", "MINUS", "MULTISET", "NOT",
      "OF", "OFFSET", "ON", "OPERATOR", "OPTION", "OR", "OVER", "OVERLAPS", "OVERLAY", "PARTITION", "PATH", "PATTERN",
      "PIVOT", "POSITION", "PRIOR", "RAW", "RECURSIVE", "REGEXP", "REPEATABLE", "RLIKE", "ROLLUP", "ROOT", "ROW",
      "RULES", "SAMPLE", "SCN", "SEED", "SELECT", "SET", "SETS", "SOME", "SUBPARTITION", "SUBSTRING", "TABLE",
      "TABLESAMPLE", "THEN", "TO", "TOP", "TREAT", "TRIM", "UNION", "UNPIVOT", "USING", "VALUES", "WHEN", "WHERE",
      "WITH", "WITHIN", "XMLATTRIBUTES", "XMLCONCAT", "XMLELEMENT", "XMLEXISTS", "XMLFOREST", "XMLNAMESPACES",
      "XMLPARSE", "XMLPI", "XMLROOT", "XMLSERIALIZE", "XMLTABLE", "XOR", "ZONE");

  /** {@code INTERVAL '1' DAY(2) TO SECOND(3)}: a field's precision after a literal, {@code INTERVAL} or {@code TO}. */
  private static final Set<String> INTERVAL_FIELDS = Set.of("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND");

  private static final Set<String> INTERVAL_STARTS = Set.of("INTERVAL", "TO");

  /** {@code TABLESAMPLE SYSTEM (10)}, {@code SAMPLE BLOCK (10)}: a sampling method. */
  private static final Set<String> SAMPLING = Set.of("TABLESAMPLE", "SAMPLE");

  private enum Type { NAME, QUOTED, DOT, OPEN, LITERAL, OTHER }

  private record Token(Type type, String text) {

    boolean named() {
      return type == Type.NAME || type == Type.QUOTED;
    }

    boolean word(Set<String> words) {
      return type == Type.NAME && words.contains(text.toUpperCase(Locale.ROOT));
    }
  }

  private SqlCalls() {}

  /**
   * The calls of a statement that the parser's walk may have missed.
   *
   * @param statement one statement, as the server receives it
   * @param notCalls  how often the parser placed each name before a column list or as a type: those parentheses
   *                  aren't calls (keys from {@link #key})
   */
  static List<FunctionRef> calls(String statement, SqlSyntax syntax, Map<String, Integer> notCalls) {
    List<Token> tokens = tokens(statement, syntax);
    Map<String, List<List<String>>> named = new LinkedHashMap<>();
    Map<String, Integer> counts = new HashMap<>();
    for (int i = 1; i < tokens.size(); i++) {
      if (tokens.get(i).type() != Type.OPEN || !tokens.get(i - 1).named()) {
        continue;
      }
      int first = i - 1;
      while (first >= 2 && tokens.get(first - 1).type() == Type.DOT && tokens.get(first - 2).named()) {
        first -= 2;
      }
      List<String> parts = new ArrayList<>();
      for (int k = first; k < i; k += 2) {
        parts.add(tokens.get(k).text());
      }
      if (syntactic(parts, first == 0 ? null : tokens.get(first - 1), syntax)) {
        continue;
      }
      String key = key(parts);
      named.computeIfAbsent(key, k -> new ArrayList<>()).add(List.copyOf(parts));
      counts.merge(key, 1, Integer::sum);
    }
    Set<FunctionRef> out = new LinkedHashSet<>();
    named.forEach((key, spellings) -> {
      // More of these than the parser placed before column lists: at least one is a call, and which one can't be told.
      if (counts.get(key) > notCalls.getOrDefault(key, 0)) {
        spellings.forEach(parts -> out.add(new FunctionRef(parts)));
      }
    });
    return List.copyOf(out);
  }

  /**
   * How {@link #calls} and the parser's names meet: each part lower case unless quoted, quoted parts as written,
   * joined with dots.
   */
  static String key(List<String> parts) {
    List<String> keys = new ArrayList<>(parts.size());
    for (String part : parts) {
      String p = part.strip();
      boolean quoted = !p.isEmpty() && (p.charAt(0) == '"' || p.charAt(0) == '`' || p.charAt(0) == '[');
      keys.add(quoted ? p : p.toLowerCase(Locale.ROOT));
    }
    return String.join(".", keys);
  }

  /** Whether a name before "(" is syntax: a keyword, a type, an interval field or a sampling method. */
  private static boolean syntactic(List<String> parts, Token before, SqlSyntax syntax) {
    if (parts.size() != 1) {
      return false;
    }
    String word = parts.getFirst();
    char c = word.charAt(0);
    if (c == '"' || c == '`' || c == '[') {
      return false;
    }
    String upper = word.toUpperCase(Locale.ROOT);
    if (KEYWORDS.contains(upper) || types(syntax).contains(word.toLowerCase(Locale.ROOT))) {
      return true;
    }
    if (before == null) {
      return false;
    }
    return before.word(SAMPLING) || INTERVAL_FIELDS.contains(upper)
        && (before.type() == Type.LITERAL || before.word(INTERVAL_STARTS));
  }

  /** Types that take a length, precision or scale in parentheses, and that no unqualified call can name. */
  private static Set<String> types(SqlSyntax syntax) {
    return switch (syntax) {
      case POSTGRES -> Set.of("bit", "char", "character", "dec", "decimal", "float", "nchar", "numeric", "time",
          "timestamp", "varchar", "varying");
      case MYSQL -> Set.of("bigint", "binary", "char", "character", "dec", "decimal", "double", "float", "int",
          "integer", "mediumint", "numeric", "real", "smallint", "time", "timestamp", "tinyint", "varbinary",
          "varchar", "varcharacter", "varying", "year");
      case SQLSERVER -> Set.of("binary", "char", "datetime2", "datetimeoffset", "decimal", "float", "nchar",
          "numeric", "nvarchar", "time", "varbinary", "varchar");
      case ORACLE -> Set.of("char", "decimal", "float", "nchar", "number", "numeric", "nvarchar2", "raw",
          "timestamp", "urowid", "varchar", "varchar2");
    };
  }

  /** The statement's code as tokens; comments are left out and each literal is one token. */
  private static List<Token> tokens(String statement, SqlSyntax syntax) {
    SqlLexer.Scan scan = SqlLexer.scan(statement, syntax);
    List<Token> tokens = new ArrayList<>();
    for (SqlLexer.Segment s : scan.segments()) {
      switch (s.kind()) {
        case COMMENT, EXECUTABLE_COMMENT -> { }
        case STRING -> tokens.add(new Token(Type.LITERAL, ""));
        case QUOTED_IDENTIFIER -> tokens.add(new Token(Type.QUOTED, statement.substring(s.start(), s.end())));
        case CODE -> code(statement, s.start(), s.end(), syntax, tokens);
      }
    }
    return tokens;
  }

  private static void code(String sql, int from, int to, SqlSyntax syntax, List<Token> tokens) {
    int i = from;
    while (i < to) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (namePart(c, syntax)) {
        int j = i;
        while (j < to && namePart(sql.charAt(j), syntax)) {
          j++;
        }
        tokens.add(new Token(Type.NAME, sql.substring(i, j)));
        i = j;
      } else {
        tokens.add(new Token(c == '.' ? Type.DOT : c == '(' ? Type.OPEN : Type.OTHER, String.valueOf(c)));
        i++;
      }
    }
  }

  /**
   * A character of an unquoted name. Every non-ASCII character that isn't white space counts: PostgreSQL and MySQL
   * take them into names, and where a server rejects one, reading too long a name only adds a call that fails closed.
   */
  private static boolean namePart(char c, SqlSyntax syntax) {
    return SqlLexer.identifierPart(c) || (c == '#' && syntax == SqlSyntax.ORACLE)
        || (c >= 0x80 && !Character.isWhitespace(c));
  }
}
