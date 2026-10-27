package gaterook.plugins.clickhouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The plugin's own check of a query before it goes to ClickHouse (step K6): one statement that only reads —
 * {@code SELECT}, {@code WITH … SELECT}, {@code SHOW}, {@code DESCRIBE}/{@code DESC}, {@code EXPLAIN} or
 * {@code EXISTS} — with no table function that reaches another system ({@code url}, {@code s3}, {@code remote},
 * {@code file}, {@code mysql}, …) and no {@code INTO OUTFILE}. ClickHouse enforces the same with {@code readonly=1}
 * and {@code allow_ddl=0}, which every query carries; this check says why sooner and in plain words, and holds even
 * where a server setting doesn't.
 *
 * <p>The scanner reads the text the way ClickHouse's lexer does where it matters: string literals in single quotes
 * with backslash escapes and doubled quotes, heredoc strings ({@code $tag$ … $tag$}), quoted identifiers in double
 * quotes and backticks, {@code --}, {@code #} and {@code /* … *\/} comments. Where it can't be sure (an unterminated
 * literal, a {@code $} that starts no heredoc), it refuses: whatever it reads as code, it checks.
 */
final class ReadOnlySql {

  /** The first word of a statement that only reads. */
  static final Set<String> READ_STARTS = Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "EXISTS");

  /** What {@code clickhouse_explain} may explain: a query, never a statement of another kind. */
  static final Set<String> QUERY_STARTS = Set.of("SELECT", "WITH");

  /**
   * Table functions that read from (or write to) another system, the server's files or a process: the agent could
   * make the ClickHouse server fetch from anywhere it reaches.
   */
  static final Set<String> OUTSIDE_FUNCTIONS = Set.of("url", "urlcluster", "s3", "s3cluster", "gcs", "oss", "cosn",
      "azureblobstorage", "azureblobstoragecluster", "hdfs", "hdfscluster", "file", "filecluster", "remote",
      "remotesecure", "cluster", "clusterallreplicas", "mysql", "postgresql", "mongodb", "redis", "sqlite", "jdbc",
      "odbc", "executable", "input", "iceberg", "icebergs3", "icebergazure", "iceberghdfs", "iceberglocal",
      "icebergcluster", "icebergs3cluster", "icebergazurecluster", "iceberghdfscluster", "deltalake", "deltalakes3",
      "deltalakeazure", "deltalakelocal", "deltalakecluster", "hudi", "hudicluster", "paimon", "paimons3",
      "paimonazure", "paimonhdfs", "paimonlocal", "hive", "fuzzjson", "fuzzquery", "dictionary", "mergetreeindex",
      "mergetreeprojection", "arrowflight", "ytsaurus", "prometheusquery", "prometheusqueryrange", "timeseriesdata",
      "timeseriesmetrics", "timeseriestags");

  /** A statement that may run, as ClickHouse will read it. */
  record Checked(String sql, String firstWord) {}

  private ReadOnlySql() {}

  /**
   * Checks {@code sql} for {@code clickhouse_query}.
   *
   * @return the statement without a trailing semicolon, or the reason it may not run
   */
  static Result check(String sql) {
    return check(sql, READ_STARTS);
  }

  /** Checks a query for {@code clickhouse_explain}: {@code SELECT} or {@code WITH … SELECT} only. */
  static Result checkQuery(String sql) {
    return check(sql, QUERY_STARTS);
  }

  /** Checks explicit table references when the connection is limited to selected databases. */
  static Optional<String> databaseProblem(String sql, List<String> databases) {
    if (databases.isEmpty()) return Optional.empty();
    List<Token> code = new ArrayList<>();
    String problem = scan(sql == null ? "" : sql, code);
    if (problem != null) return Optional.of(problem);
    if (code.isEmpty() || code.getFirst().kind != Kind.WORD
        || !Set.of("SELECT", "WITH", "EXPLAIN").contains(code.getFirst().text.toUpperCase(Locale.ROOT))) {
      return Optional.of("Use the database and table listing tools for metadata on a connection with selected databases.");
    }
    Set<Integer> from = new java.util.HashSet<>();
    int depth = 0;
    for (int i = 0; i < code.size(); i++) {
      Token token = code.get(i);
      String word = token.text.toUpperCase(Locale.ROOT);
      if (token.kind == Kind.OPEN) { depth++; continue; }
      if (punctuation(token, ")")) { from.remove(depth); depth--; continue; }
      // ClickHouse permits keyword aliases after AS; they do not start a clause.
      if (token.kind == Kind.WORD && word.equals("AS") && i + 1 < code.size() && identifier(code.get(i + 1))) {
        i++;
        continue;
      }
      boolean function = i + 1 < code.size() && code.get(i + 1).kind == Kind.OPEN;
      if (identifier(token) && function && (word.startsWith("DICT") || word.startsWith("JOINGET"))) {
        return Optional.of("Dictionary and join lookups cannot be limited to the selected databases.");
      }
      boolean keyword = token.kind == Kind.WORD;
      if (keyword && Set.of("WHERE", "PREWHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "UNION", "SETTINGS", "FORMAT",
          "QUALIFY", "WINDOW").contains(word)) from.remove(depth);
      boolean source = keyword && (word.equals("FROM") || word.equals("JOIN"))
          || punctuation(token, ",") && from.contains(depth);
      if (keyword && word.equals("FROM")) from.add(depth);
      // ClickHouse also accepts a table as the right side of IN, without a SELECT subquery.
      boolean inTable = keyword && word.equals("IN") && i + 1 < code.size() && identifier(code.get(i + 1));
      if (source || inTable) {
        String denied = tableProblem(code, i + 1, databases);
        if (denied != null) return Optional.of(denied);
      }
    }
    return Optional.empty();
  }

  private static String tableProblem(List<Token> code, int at, List<String> databases) {
    if (at >= code.size()) return "A table name is missing.";
    Token first = code.get(at);
    if (first.kind == Kind.OPEN) {
      return at + 1 < code.size() && code.get(at + 1).kind == Kind.WORD
          && Set.of("SELECT", "WITH").contains(code.get(at + 1).text.toUpperCase(Locale.ROOT))
          ? null : "Use a SELECT subquery instead of a dynamic table expression.";
    }
    if (!identifier(first)) return "This table expression cannot be limited to selected databases.";
    int end = at + 1;
    if (end < code.size() && punctuation(code.get(end), ".")) {
      if (!databases.contains(first.text)) return "Database " + first.text + " isn't selected for this connection.";
      end += 2;
      if (end > code.size() || !identifier(code.get(end - 1))) return "A table name is missing.";
    }
    if (end < code.size() && punctuation(code.get(end), ".")) {
      return "Use a database and table name without additional qualifiers.";
    }
    if (end < code.size() && code.get(end).kind == Kind.OPEN
        && (end != at + 1 || !Set.of("numbers", "numbers_mt", "zeros", "zeros_mt", "generateseries")
            .contains(first.text.toLowerCase(Locale.ROOT)))) {
      return "Table functions that choose a data source aren't allowed with selected databases.";
    }
    return null;
  }

  /** Either the checked statement or why it was refused. */
  record Result(Checked checked, String refusal) {

    boolean ok() {
      return checked != null;
    }

    Optional<String> problem() {
      return Optional.ofNullable(refusal);
    }
  }

  private static Result check(String sql, Set<String> starts) {
    if (sql == null || sql.isBlank()) {
      return refuse("The query is empty.");
    }
    List<Token> tokens = new ArrayList<>();
    int end = -1;
    String problem = scan(sql, tokens);
    if (problem != null) {
      return refuse(problem);
    }
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.kind == Kind.SEMICOLON) {
        if (i != tokens.size() - 1) {
          return refuse("One statement at a time: the query has more after a semicolon.");
        }
        end = t.start;
      }
    }
    List<Token> code = tokens.stream().filter(t -> t.kind != Kind.SEMICOLON).toList();
    if (code.isEmpty()) {
      return refuse("The query is empty.");
    }
    Token first = code.getFirst();
    String word = first.kind == Kind.WORD ? first.text.toUpperCase(Locale.ROOT) : first.text;
    if (first.kind != Kind.WORD || !starts.contains(word)) {
      String allowed = starts == QUERY_STARTS ? "SELECT or WITH … SELECT"
          : "SELECT, WITH … SELECT, SHOW, DESCRIBE, EXPLAIN or EXISTS";
      return refuse("Only " + allowed + " runs here: this plugin reads and never changes data (the query starts with "
          + cut(first.text) + ").");
    }
    for (int i = 0; i < code.size(); i++) {
      Token t = code.get(i);
      if (!identifier(t)) {
        continue;
      }
      String upper = t.text.toUpperCase(Locale.ROOT);
      if (upper.equals("OUTFILE")) {
        return refuse("INTO OUTFILE isn't allowed: results come back to the gateway only.");
      }
      boolean call = i + 1 < code.size() && code.get(i + 1).kind == Kind.OPEN;
      if (call && OUTSIDE_FUNCTIONS.contains(t.text.toLowerCase(Locale.ROOT))) {
        return refuse("The table function " + t.text + "() reaches outside this ClickHouse server, which this plugin "
            + "doesn't allow.");
      }
    }
    String statement = end < 0 ? sql.strip() : sql.substring(0, end).strip();
    return new Result(new Checked(statement, word), null);
  }

  private static Result refuse(String why) {
    return new Result(null, why);
  }

  private enum Kind { WORD, IDENTIFIER, OPEN, SEMICOLON, OTHER }

  private record Token(Kind kind, String text, int start) {}

  private static boolean identifier(Token token) {
    return token.kind == Kind.WORD || token.kind == Kind.IDENTIFIER;
  }

  private static boolean punctuation(Token token, String value) {
    return token.kind == Kind.OTHER && token.text.equals(value);
  }

  /** Splits {@code sql} into the tokens that matter outside literals and comments; a problem ends the scan. */
  private static String scan(String sql, List<Token> out) {
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-' || c == '#'
          || c == '/' && i + 1 < n && sql.charAt(i + 1) == '/') {
        while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
          i++;
        }
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int nesting = 1;
        i += 2;
        while (i < n && nesting > 0) {
          if (i + 1 < n && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
            nesting++;
            i += 2;
          } else if (i + 1 < n && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
            nesting--;
            i += 2;
          } else {
            i++;
          }
        }
        if (nesting != 0) {
          return "A /* comment isn't closed.";
        }
      } else if (c == '\'' || c == '"' || c == '`') {
        int close = literalEnd(sql, i, c);
        if (close < 0) {
          return "A quoted " + (c == '\'' ? "string" : "name") + " isn't closed.";
        }
        if (c != '\'') {
          // A quoted identifier is a name: a call of `url`(…) is still a call of url.
          out.add(new Token(Kind.IDENTIFIER, unquote(sql.substring(i + 1, close)), i));
        } else {
          out.add(new Token(Kind.OTHER, "'", i));
        }
        i = close + 1;
      } else if (c == '$') {
        // A heredoc string, $tag$ … $tag$ (the tag may be empty): ClickHouse reads what's inside as is, so a quote or
        // a comment marker in it starts nothing (review 2026-10-27: `$$'$$ FROM url(…) -- '` hid url() from this check).
        int tag = i + 1;
        while (tag < n && (isAsciiWordChar(sql.charAt(tag)))) {
          tag++;
        }
        if (tag >= n || sql.charAt(tag) != '$') {
          return "A $ outside a string isn't ClickHouse SQL.";
        }
        String delimiter = sql.substring(i, tag + 1);
        int close = sql.indexOf(delimiter, tag + 1);
        if (close < 0) {
          return "A " + delimiter + " string isn't closed.";
        }
        out.add(new Token(Kind.OTHER, "'", i));
        i = close + delimiter.length();
      } else if (c == ';') {
        out.add(new Token(Kind.SEMICOLON, ";", i));
        i++;
      } else if (c == '(') {
        out.add(new Token(Kind.OPEN, "(", i));
        i++;
      } else if (Character.isLetter(c) || c == '_') {
        int start = i;
        while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
          i++;
        }
        out.add(new Token(Kind.WORD, sql.substring(start, i), start));
      } else {
        out.add(new Token(Kind.OTHER, String.valueOf(c), i));
        i++;
      }
    }
    return null;
  }

  /** The index of the quote that closes the literal opened at {@code start}, or -1. */
  private static int literalEnd(String sql, int start, char quote) {
    int i = start + 1;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == quote) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
          i += 2;
        } else {
          return i;
        }
      } else {
        i++;
      }
    }
    return -1;
  }

  private static boolean isAsciiWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  private static String unquote(String name) {
    return name.replace("\\", "");
  }

  private static String cut(String s) {
    return s.length() <= 40 ? s : s.substring(0, 40) + "…";
  }
}
