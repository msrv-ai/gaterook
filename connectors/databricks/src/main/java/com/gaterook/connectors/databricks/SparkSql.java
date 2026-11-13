package com.gaterook.connectors.databricks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What one Databricks SQL statement is, without I/O: the gate in front of the warehouse. The text is read by the tokens
 * of Spark and Databricks SQL: {@code '…'} and {@code "…"} strings with backslash escapes, raw {@code r'…'} strings,
 * {@code `…`} names with doubled backticks, {@code --} line comments, block comments and {@code /*+ … *}{@code /}
 * hints, so a semicolon or a keyword inside a string, a name or a comment is neither. Whatever this can't read with
 * certainty is {@link Kind#UNREADABLE}, and a call refuses it: an open quote or comment, a comment inside a comment, a
 * backslash in a raw or double-quoted string (readers disagree on what it does there), a {@code $} outside a string
 * (bodies of Python functions), {@code ${…}} (Spark replaces it before it reads the statement) and a control character.
 *
 * <p>Only {@code SELECT}, {@code WITH}, {@code VALUES}, {@code TABLE}, {@code FROM}, {@code SHOW}, {@code DESCRIBE} and
 * {@code EXPLAIN} of a read are reads. Access control, files, data movement, procedures, session settings, scripting,
 * objects that reach outside the catalog and functions that call other services, read files or secrets, or run Java
 * are {@link Kind#FORBIDDEN}. The names a statement uses ({@link Statement#names}) let the connector check the
 * catalogs and schemas of the connection.
 */
final class SparkSql {

  /** The route of a statement, in increasing order of what it may do. */
  enum Kind { READ, CHANGE, FORBIDDEN, UNREADABLE }

  /** What a name in a statement names. */
  enum Ref { TABLE, SCHEMA, CATALOG, FUNCTION }

  /**
   * A name the statement uses: its parts without backticks, lower case, as Unity Catalog compares them.
   *
   * @param ref what it names: a table or view, a schema, a catalog, or a function a call names with its schema
   */
  record Name(Ref ref, List<String> parts) {

    String text() {
      return String.join(".", parts);
    }
  }

  /**
   * One call's text.
   *
   * @param type       what it is, upper case: {@code SELECT}, {@code MERGE}, {@code CREATE TABLE}, {@code SHOW
   *                   SCHEMAS}; a description when it can't be read
   * @param target     for a change, the first table or schema it names, or {@code null}
   * @param problem    why it is FORBIDDEN or UNREADABLE, for the agent; {@code null} otherwise
   * @param parameters the {@code :name} markers of the code
   * @param names      the tables, views, schemas, catalogs and qualified functions it names, in order
   */
  record Statement(Kind kind, String type, String target, String problem, Set<String> parameters, List<Name> names) {

    static Statement refused(Kind kind, String type, String problem) {
      return new Statement(kind, type, null, problem, Set.of(), List.of());
    }

    /** INSERT, UPDATE, DELETE or MERGE: Delta applies it in one transaction, whole or not at all. */
    boolean dml() {
      return Set.of("INSERT", "UPDATE", "DELETE", "MERGE").contains(type);
    }
  }

  /** The Statement Execution API takes 16 MiB; the tools take this many characters. */
  static final int MAX_LENGTH = 100_000;

  private static final Set<String> READS = Set.of("SELECT", "WITH", "VALUES", "TABLE", "FROM", "SHOW", "DESCRIBE",
      "DESC", "EXPLAIN");
  private static final Set<String> QUERIES = Set.of("SELECT", "WITH", "VALUES", "TABLE", "FROM");
  private static final Set<String> CHANGES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "CREATE",
      "ALTER", "DROP", "OPTIMIZE", "VACUUM", "ANALYZE", "REFRESH", "RESTORE", "COMMENT", "MSCK", "FSCK", "REPAIR",
      "UNDROP", "REORG", "CONVERT");
  /** Statements that never go through GateRook, with why. */
  private static final Map<String, String> FORBIDDEN = forbidden();
  /**
   * Objects of CREATE, ALTER and DROP that stay with people: catalogs, Delta Sharing (shares, recipients, providers:
   * data leaves the workspace), connections, credentials and external locations (they reach storage and other
   * databases), clean rooms, foreign catalogs and tables, functions and procedures (code), principals.
   */
  private static final Set<String> FORBIDDEN_OBJECTS = Set.of("CATALOG", "SHARE", "RECIPIENT", "PROVIDER",
      "CONNECTION", "CREDENTIAL", "STORAGE", "SERVICE", "EXTERNAL", "LOCATION", "CLEAN", "FOREIGN", "PROCEDURE",
      "FUNCTION", "ROLE", "USER", "GROUP", "SERVER");
  /** Words between CREATE, ALTER or DROP and the kind of object. */
  private static final Set<String> MODIFIERS = Set.of("OR", "REPLACE", "TEMPORARY", "TEMP", "GLOBAL", "IF", "NOT",
      "EXISTS");
  /** Words a read never holds. */
  private static final Set<String> WRITES_IN_READ = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "INTO",
      "OVERWRITE");
  /**
   * Functions that reach outside the warehouse's tables: files and streams, HTTP, models and AI services, vector
   * search, other databases, secrets, Java methods, Python code. {@code ai_} names every AI function.
   */
  private static final Set<String> OUTSIDE = Set.of("read_files", "http_request", "read_kafka", "read_kinesis",
      "read_pubsub", "read_pulsar", "read_eventhubs", "read_statestore", "read_state_metadata", "cloud_files",
      "cloud_files_state", "secret", "try_secret", "list_secrets", "java_method", "reflect", "try_reflect",
      "remote_query", "vector_search", "python_exec");
  /** Words between a name's keyword and the name. */
  private static final Set<String> SKIP = Set.of("IF", "NOT", "EXISTS", "TABLE", "ONLY", "EXTENDED", "FORMATTED",
      "DETAIL", "HISTORY", "LATERAL", "TEMPORARY", "TEMP", "MATERIALIZED", "STREAMING", "OR", "REPLACE", "SHALLOW",
      "DEEP", "GLOBAL");
  /** Words that end a FROM clause at their depth: after them, a comma there separates no tables. */
  private static final Set<String> FROM_ENDS = Set.of("WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET",
      "UNION", "EXCEPT", "MINUS", "INTERSECT", "WINDOW", "QUALIFY", "SELECT", "CLUSTER", "DISTRIBUTE", "SORT",
      "INSERT", "WHEN", "SET");
  /** What DESCRIBE describes when it isn't a table. */
  private static final Set<String> DESCRIBED = Set.of("SCHEMA", "DATABASE", "CATALOG", "FUNCTION", "QUERY",
      "VOLUME", "CONNECTION", "CREDENTIAL", "EXTERNAL", "STORAGE", "SHARE", "RECIPIENT", "PROVIDER");

  private static Map<String, String> forbidden() {
    String access = "sets access, which stays with people: Unity Catalog's grants are the boundary GateRook relies on.";
    String files = "moves files between the warehouse and storage outside the catalog.";
    String session = "changes the session, whose settings belong to the gateway.";
    String script = "starts a transaction or a script, which runs more than one statement.";
    return Map.ofEntries(Map.entry("GRANT", access), Map.entry("REVOKE", access), Map.entry("DENY", access),
        Map.entry("COPY", files), Map.entry("LIST", files), Map.entry("PUT", files), Map.entry("GET", files),
        Map.entry("REMOVE", files), Map.entry("LOAD", files), Map.entry("ADD", files),
        Map.entry("CALL", "runs a procedure."), Map.entry("SET", session), Map.entry("RESET", session),
        Map.entry("USE", session), Map.entry("CACHE", session), Map.entry("UNCACHE", session),
        Map.entry("CLEAR", session), Map.entry("BEGIN", script), Map.entry("START", script),
        Map.entry("COMMIT", script), Map.entry("ROLLBACK", script), Map.entry("DECLARE", script),
        Map.entry("EXECUTE", script), Map.entry("IF", script), Map.entry("CASE", script), Map.entry("LOOP", script),
        Map.entry("WHILE", script), Map.entry("REPEAT", script), Map.entry("FOR", script),
        Map.entry("LEAVE", script), Map.entry("ITERATE", script), Map.entry("SIGNAL", script),
        Map.entry("RESIGNAL", script), Map.entry("RETURN", script),
        Map.entry("SYNC", "upgrades tables to Unity Catalog, which stays with people."),
        Map.entry("GENERATE", "writes files next to a table."),
        Map.entry("KILL", "stops other work."));
  }

  private SparkSql() {}

  /** What a token is. */
  enum T { WORD, QUOTED, STRING, NUMBER, SYMBOL }

  /**
   * One token of the code.
   *
   * @param text  a word as written, a quoted name without its backticks, a symbol; {@code ''} for a string
   * @param upper a word upper-cased, for keyword checks; {@code text} otherwise
   */
  record Token(T type, String text, String upper) {

    boolean name() {
      return type == T.WORD || type == T.QUOTED;
    }

    boolean is(String symbolOrWord) {
      return upper.equals(symbolOrWord);
    }
  }

  /** The tokens of the code, the count of top-level statements, the {@code :name} markers, or why it can't be read. */
  record Scan(List<Token> tokens, int statements, Set<String> parameters, String problem) {}

  static Statement read(String sql) {
    if (sql == null || sql.isBlank()) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement is empty.");
    }
    if (sql.length() > MAX_LENGTH) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement is longer than " + MAX_LENGTH
          + " characters.");
    }
    Scan scan = scan(sql);
    if (scan.problem() != null) {
      return Statement.refused(Kind.UNREADABLE, "statement", scan.problem());
    }
    if (scan.statements() > 1) {
      return Statement.refused(Kind.FORBIDDEN, "script", "One statement per call: this text has "
          + scan.statements() + ". Send them one by one.");
    }
    List<Token> tokens = scan.tokens();
    List<String> words = new ArrayList<>();
    for (Token t : tokens) {
      if (t.type() == T.WORD) {
        words.add(t.upper());
      }
    }
    String first = firstKeyword(tokens);
    if (first == null) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement has no keyword.");
    }
    String reach = reachesOutside(tokens);
    if (reach != null) {
      return Statement.refused(Kind.FORBIDDEN, first, reach);
    }
    if (FORBIDDEN.containsKey(first)) {
      return Statement.refused(Kind.FORBIDDEN, first, first + " isn't allowed through GateRook: it "
          + FORBIDDEN.get(first));
    }
    if (READS.contains(first)) {
      return read(first, tokens, words, scan);
    }
    if (CHANGES.contains(first)) {
      return change(first, tokens, words, scan);
    }
    return Statement.refused(Kind.UNREADABLE, first, first + " isn't a statement GateRook runs on Databricks.");
  }

  private static Statement read(String first, List<Token> tokens, List<String> words, Scan scan) {
    String type = first;
    if (first.equals("EXPLAIN")) {
      int k = 1;
      while (k < words.size() && Set.of("EXTENDED", "CODEGEN", "COST", "FORMATTED", "LOGICAL").contains(words.get(k))) {
        k++;
      }
      String explained = k < words.size() ? words.get(k) : "";
      if (explained.equals("ANALYZE")) {
        return Statement.refused(Kind.FORBIDDEN, type, "ANALYZE writes a table's statistics: GateRook only explains "
            + "reads.");
      }
      if (!QUERIES.contains(explained)) {
        return Statement.refused(Kind.FORBIDDEN, type, "GateRook explains only queries (SELECT, WITH, VALUES, TABLE, "
            + "FROM); databricks_execute shows a change in its approval.");
      }
    }
    if (first.equals("SHOW") && words.size() > 1) {
      type = "SHOW " + words.get(1);
    }
    if (!first.equals("SHOW") && !first.equals("DESCRIBE") && !first.equals("DESC")) {
      for (String w : words) {
        if (WRITES_IN_READ.contains(w)) {
          return Statement.refused(Kind.FORBIDDEN, type, "This statement holds " + w + ", so it isn't only a read. "
              + "Send a change on its own, starting with its verb, to databricks_execute.");
        }
      }
    }
    return new Statement(Kind.READ, type, null, null, scan.parameters(), names(first, tokens));
  }

  private static Statement change(String first, List<Token> tokens, List<String> words, Scan scan) {
    String type = first;
    if (first.equals("INSERT")) {
      int overwrite = words.indexOf("OVERWRITE");
      if (overwrite > 0 && (words.subList(overwrite, Math.min(words.size(), overwrite + 3)).contains("DIRECTORY"))) {
        return Statement.refused(Kind.FORBIDDEN, "INSERT OVERWRITE DIRECTORY", "INSERT OVERWRITE DIRECTORY writes "
            + "files outside the catalog: GateRook doesn't run it.");
      }
    }
    if (first.equals("CREATE") || first.equals("ALTER") || first.equals("DROP")) {
      int k = 1;
      while (k < words.size() && MODIFIERS.contains(words.get(k))) {
        k++;
      }
      String object = k < words.size() ? words.get(k) : "";
      if ((object.equals("MATERIALIZED") || object.equals("STREAMING")) && k + 1 < words.size()) {
        object = object + " " + words.get(k + 1);
      }
      if (FORBIDDEN_OBJECTS.contains(object)) {
        return Statement.refused(Kind.FORBIDDEN, first + " " + object, first + " " + object + " isn't allowed "
            + "through GateRook: catalogs, shares, recipients, providers, connections, credentials, external "
            + "locations, foreign catalogs, clean rooms, functions and procedures stay with people.");
      }
      type = object.isEmpty() ? first : first + " " + object;
      String clause = first.equals("DROP") ? null : forbiddenClause(tokens);
      if (clause != null) {
        return Statement.refused(Kind.FORBIDDEN, type, clause + " in " + first + " isn't allowed through GateRook: "
            + "storage paths, data source options and credentials reach outside the catalog, and ownership, row "
            + "filters and column masks set access, which stays with people.");
      }
    }
    List<Name> names = names(first, tokens);
    String target = null;
    for (Name n : names) {
      if (n.ref() == Ref.TABLE || n.ref() == Ref.SCHEMA) {
        target = n.text();
        break;
      }
    }
    return new Statement(Kind.CHANGE, type, target, null, scan.parameters(), names);
  }

  /**
   * The clause of a CREATE or ALTER that reaches outside the catalog or sets access, or {@code null}: a storage
   * {@code LOCATION '…'}, data source {@code OPTIONS (…)} (a path, a JDBC URL), a {@code CREDENTIAL}, {@code OWNER TO},
   * a row filter ({@code ROW FILTER}) or a column mask ({@code MASK}). A column named {@code location} is none of
   * these.
   */
  private static String forbiddenClause(List<Token> tokens) {
    for (int j = 0; j < tokens.size(); j++) {
      Token t = tokens.get(j);
      if (t.type() != T.WORD) {
        continue;
      }
      String next = j + 1 < tokens.size() ? tokens.get(j + 1).upper() : "";
      switch (t.upper()) {
        case "CREDENTIAL", "MASK" -> {
          return t.upper();
        }
        case "LOCATION" -> {
          // A path: a string, or a parameter marker that stands for one.
          if (next.equals("''") || next.equals(":")) {
            return "LOCATION";
          }
        }
        case "OPTIONS" -> {
          if (next.equals("(")) {
            return "OPTIONS";
          }
        }
        case "OWNER" -> {
          if (next.equals("TO")) {
            return "OWNER TO";
          }
        }
        case "ROW" -> {
          if (next.equals("FILTER")) {
            return "ROW FILTER";
          }
        }
        default -> {
          // Not a clause that reaches outside.
        }
      }
    }
    return null;
  }

  /** The first keyword after opening parentheses, upper case; {@code null} without one. */
  private static String firstKeyword(List<Token> tokens) {
    for (Token t : tokens) {
      if (t.type() == T.WORD) {
        return t.upper();
      }
      if (!t.is("(")) {
        return null;
      }
    }
    return null;
  }

  /**
   * Why the code reaches outside the warehouse's tables, or {@code null}: it calls a function that does (bare,
   * qualified or in backticks), names an object at run time ({@code IDENTIFIER(…)}) or queries files by their path
   * ({@code delta.`/Volumes/…`}).
   */
  private static String reachesOutside(List<Token> tokens) {
    for (int k = 0; k < tokens.size(); k++) {
      Token t = tokens.get(k);
      if (t.type() == T.QUOTED && t.text().indexOf('/') >= 0) {
        return "A name with a / is a storage path: querying files by their path reaches storage outside the "
            + "catalog. Query a table.";
      }
      if (!t.name() || k + 1 >= tokens.size() || !tokens.get(k + 1).is("(")) {
        continue;
      }
      String name = t.text().toLowerCase(Locale.ROOT);
      if (name.equals("identifier")) {
        return "IDENTIFIER() names an object only when the statement runs: name tables in the statement itself.";
      }
      if (OUTSIDE.contains(name) || name.startsWith("ai_")) {
        return name + " reaches outside the warehouse's tables (files, streams, HTTP, models, other databases, "
            + "secrets, Java or Python): GateRook doesn't run it.";
      }
    }
    return null;
  }

  /**
   * The names the statement uses, in order and each once: tables after FROM, after each comma of a FROM clause at its
   * own depth of parentheses (also after a JOIN's ON condition), and after JOIN, INTO, UPDATE, TABLE, VIEW, USING,
   * CLONE, OVERWRITE, OPTIMIZE, VACUUM, DESCRIBE, LIKE and RENAME TO; schemas after SCHEMA and DATABASE; catalogs
   * after CATALOG; for SHOW, what IN or FROM names; and every function called with its schema ({@code s.f(…)}).
   */
  static List<Name> names(String first, List<Token> tokens) {
    Set<Name> out = new LinkedHashSet<>();
    boolean show = first.equals("SHOW");
    String shown = null;
    boolean columnsTable = false;
    int depth = 0;
    // The depths of parentheses at which a FROM clause is open: a comma there separates its tables.
    Set<Integer> from = new java.util.HashSet<>();
    for (int k = 0; k < tokens.size(); k++) {
      Token t = tokens.get(k);
      if (t.is("(")) {
        depth++;
        continue;
      }
      if (t.is(")")) {
        from.remove(depth);
        depth = Math.max(0, depth - 1);
        continue;
      }
      if (t.is(",") && from.contains(depth) && !show) {
        table(out, tokens, k + 1);
        continue;
      }
      if (t.name() && (k == 0 || !tokens.get(k - 1).is("."))) {
        Parsed call = dotted(tokens, k);
        if (call != null && call.parts().size() >= 2 && call.end() < tokens.size()
            && tokens.get(call.end()).is("(")) {
          out.add(new Name(Ref.FUNCTION, call.parts()));
        }
      }
      if (t.type() != T.WORD) {
        continue;
      }
      String w = t.upper();
      String previous = k > 0 ? tokens.get(k - 1).upper() : "";
      if (show) {
        if (shown == null && k > 0) {
          shown = w;
          continue;
        }
        if (w.equals("IN") || w.equals("FROM")) {
          Ref ref = "SCHEMAS".equals(shown) || "DATABASES".equals(shown) ? Ref.CATALOG
              : "COLUMNS".equals(shown) && !columnsTable ? Ref.TABLE : Ref.SCHEMA;
          columnsTable = columnsTable || ref == Ref.TABLE;
          add(out, ref, tokens, k + 1);
        } else if (w.equals("TBLPROPERTIES") || w.equals("PARTITIONS") || (w.equals("TABLE")
            && (previous.equals("CREATE") || previous.equals("ON")))) {
          add(out, Ref.TABLE, tokens, k + 1);
        } else if ((w.equals("SCHEMA") || w.equals("DATABASE")) && previous.equals("ON")) {
          add(out, Ref.SCHEMA, tokens, k + 1);
        } else if (w.equals("CATALOG") && previous.equals("ON")) {
          add(out, Ref.CATALOG, tokens, k + 1);
        }
        continue;
      }
      if (FROM_ENDS.contains(w)) {
        from.remove(depth);
      }
      switch (w) {
        case "FROM" -> {
          from.add(depth);
          table(out, tokens, k + 1);
        }
        case "JOIN" -> table(out, tokens, k + 1);
        case "INTO", "UPDATE", "TABLE", "VIEW", "USING", "CLONE", "OVERWRITE", "OPTIMIZE", "VACUUM" ->
            add(out, Ref.TABLE, tokens, k + 1);
        case "DESCRIBE", "DESC" -> {
          if (k + 1 < tokens.size() && !DESCRIBED.contains(tokens.get(k + 1).upper())) {
            add(out, Ref.TABLE, tokens, k + 1);
          }
        }
        case "LIKE" -> {
          if (k + 1 < tokens.size() && tokens.get(k + 1).name()) {
            add(out, Ref.TABLE, tokens, k + 1);
          }
        }
        case "TO" -> {
          if (previous.equals("RENAME")) {
            add(out, Ref.TABLE, tokens, k + 1);
          }
        }
        case "SCHEMA", "DATABASE" -> add(out, Ref.SCHEMA, tokens, k + 1);
        case "CATALOG" -> add(out, Ref.CATALOG, tokens, k + 1);
        default -> {
          // Not a word that names an object.
        }
      }
    }
    return List.copyOf(out);
  }

  /** A dotted name and the index after it. */
  private record Parsed(List<String> parts, int end) {}

  /** The name at {@code j} after the words of {@link #SKIP}; {@code null} when none starts there. */
  private static Parsed parse(List<Token> tokens, int j) {
    while (j < tokens.size() && tokens.get(j).type() == T.WORD && SKIP.contains(tokens.get(j).upper())) {
      j++;
    }
    return dotted(tokens, j);
  }

  /** The dotted name that starts at {@code j}, {@code a.`b`.c}; {@code null} when none does. */
  private static Parsed dotted(List<Token> tokens, int j) {
    if (j >= tokens.size() || !tokens.get(j).name()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    parts.add(tokens.get(j).text().toLowerCase(Locale.ROOT));
    int k = j + 1;
    while (k + 1 < tokens.size() && tokens.get(k).is(".") && tokens.get(k + 1).name()) {
      parts.add(tokens.get(k + 1).text().toLowerCase(Locale.ROOT));
      k += 2;
    }
    return new Parsed(List.copyOf(parts), k);
  }

  private static void add(Set<Name> out, Ref ref, List<Token> tokens, int j) {
    Parsed p = parse(tokens, j);
    if (p != null) {
      out.add(new Name(ref, p.parts()));
    }
  }

  /** The table of a FROM item or a JOIN; a table function ({@code range(10)}) is none, a subquery neither. */
  private static void table(Set<Name> out, List<Token> tokens, int j) {
    Parsed p = parse(tokens, j);
    if (p != null && !(p.end() < tokens.size() && tokens.get(p.end()).is("("))) {
      out.add(new Name(Ref.TABLE, p.parts()));
    }
  }

  /** Reads the text by Spark's tokens. */
  static Scan scan(String sql) {
    List<Token> tokens = new ArrayList<>();
    Set<String> parameters = new LinkedHashSet<>();
    if (sql.contains("${")) {
      return new Scan(tokens, 0, parameters, "${…} is replaced before the statement is read, so GateRook can't read "
          + "what would run. Write the value itself, or pass it as a :name parameter.");
    }
    int n = sql.length();
    int statements = 0;
    boolean pending = false;
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F) {
        return new Scan(tokens, statements, parameters, "A control character isn't analyzed.");
      }
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
          i++;
        }
        if (i < n && sql.charAt(i - 1) == '\\') {
          // Spark goes on with the comment on the next line after a backslash: what follows isn't code there.
          return new Scan(tokens, statements, parameters, "A comment that ends in a backslash isn't analyzed.");
        }
        continue;
      }
      if (c == '/' && i + 2 < n && sql.charAt(i + 1) == '*' && sql.charAt(i + 2) == '+') {
        // A hint: its contents are tokens, its */ two symbols.
        i += 3;
        continue;
      }
      if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int close = sql.indexOf("*/", i + 2);
        if (close < 0) {
          return new Scan(tokens, statements, parameters, "A comment isn't closed.");
        }
        if (sql.substring(i + 2, close).contains("/*")) {
          return new Scan(tokens, statements, parameters, "A comment inside a comment isn't analyzed.");
        }
        i = close + 2;
        continue;
      }
      if (c == '\'' || c == '"') {
        boolean raw = !tokens.isEmpty() && tokens.getLast().type() == T.WORD && tokens.getLast().is("R")
            && i > 0 && (sql.charAt(i - 1) == 'r' || sql.charAt(i - 1) == 'R');
        if (raw) {
          tokens.removeLast();
        }
        int j = i + 1;
        int end = -1;
        while (j < n) {
          char d = sql.charAt(j);
          if (d == '\\') {
            if (raw) {
              return new Scan(tokens, statements, parameters, "A backslash in a raw string isn't analyzed: use a "
                  + "plain string.");
            }
            if (c == '"') {
              return new Scan(tokens, statements, parameters, "A backslash in a double-quoted string isn't "
                  + "analyzed: use single quotes.");
            }
            if (j + 1 < n && sql.charAt(j + 1) == '\'') {
              // Where a warehouse keeps backslashes as they are, this quote would end the string.
              return new Scan(tokens, statements, parameters, "A backslash before a quote in a string isn't "
                  + "analyzed: write a string with a ' in double quotes, such as \"it's\".");
            }
            j += 2;
            continue;
          }
          if (d == c) {
            end = j + 1;
            break;
          }
          j++;
        }
        if (end < 0) {
          return new Scan(tokens, statements, parameters, "A string isn't closed.");
        }
        tokens.add(new Token(T.STRING, "''", "''"));
        pending = true;
        i = end;
        continue;
      }
      if (c == '`') {
        StringBuilder name = new StringBuilder();
        int j = i + 1;
        int end = -1;
        while (j < n) {
          if (sql.charAt(j) == '`') {
            if (j + 1 < n && sql.charAt(j + 1) == '`') {
              name.append('`');
              j += 2;
              continue;
            }
            end = j + 1;
            break;
          }
          name.append(sql.charAt(j));
          j++;
        }
        if (end < 0) {
          return new Scan(tokens, statements, parameters, "A quoted name isn't closed.");
        }
        tokens.add(new Token(T.QUOTED, name.toString(), name.toString()));
        pending = true;
        i = end;
        continue;
      }
      if (c == '$') {
        return new Scan(tokens, statements, parameters, "A $ outside a string isn't analyzed: function bodies stay "
            + "with people.");
      }
      if (c == ';') {
        if (pending) {
          statements++;
          pending = false;
        }
        i++;
        continue;
      }
      pending = true;
      if (Character.isLetter(c) || c == '_') {
        int j = i;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
          j++;
        }
        String word = sql.substring(i, j);
        tokens.add(new Token(T.WORD, word, word.toUpperCase(Locale.ROOT)));
        i = j;
        continue;
      }
      if (Character.isDigit(c)) {
        int j = i;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_' || sql.charAt(j) == '.')) {
          j++;
        }
        tokens.add(new Token(T.NUMBER, sql.substring(i, j), sql.substring(i, j)));
        i = j;
        continue;
      }
      if (c == ':' && i + 1 < n && marker(sql, i)) {
        int j = i + 1;
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
          j++;
        }
        parameters.add(sql.substring(i + 1, j));
      }
      tokens.add(new Token(T.SYMBOL, String.valueOf(c), String.valueOf(c)));
      i++;
    }
    if (pending) {
      statements++;
    }
    return new Scan(List.copyOf(tokens), statements, parameters, null);
  }

  /**
   * Whether the {@code :} at {@code i} starts a named parameter marker: a name follows, and neither a cast
   * ({@code ::}) nor a JSON path of a column ({@code raw:owner}, a name right before it) is meant.
   */
  private static boolean marker(String sql, int i) {
    char next = sql.charAt(i + 1);
    if (!(Character.isLetter(next) || next == '_')) {
      return false;
    }
    if (i == 0) {
      return true;
    }
    char before = sql.charAt(i - 1);
    return !(Character.isLetterOrDigit(before) || before == '_' || before == '`' || before == ')' || before == ']'
        || before == ':');
  }
}
