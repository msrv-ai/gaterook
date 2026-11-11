package gaterook.plugins.duckdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DuckDB's SQL as GateRook reads it before a statement reaches DuckDB (docs/duckdb.md): the tokens of DuckDB's
 * PostgreSQL-derived scanner, then what one statement is. It fails closed: a statement it can't read is refused.
 *
 * <p>The lexer knows {@code '…'} strings with {@code ''}, the {@code E'…'}, {@code U&'…'}, {@code B'…'} and
 * {@code X'…'} forms, dollar quoting ({@code $tag$…$tag$}), {@code "…"} names with {@code ""}, line comments and
 * nested block comments. A backtick, a backslash outside a string or a control character is refused.
 *
 * <p>{@link #read} admits one SELECT, WITH … SELECT, FROM …, VALUES, TABLE, PIVOT, UNPIVOT, DESCRIBE, SHOW,
 * SUMMARIZE or EXPLAIN statement; {@link #change} one INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, TRUNCATE or
 * COMMENT ON. Both refuse ATTACH, DETACH, COPY, EXPORT and IMPORT DATABASE, INSTALL and LOAD, SET, RESET and PRAGMA,
 * CALL, CHECKPOINT, USE, transaction control, prepared statements, secrets, a statement that changes data inside
 * parentheses, functions that run SQL from text ({@code query}, {@code query_table}) or reach other systems, and file
 * reads ({@code read_csv}, {@code read_parquet}, {@code read_json}, {@code glob}, {@code read_text},
 * {@code read_blob}, a file name in a FROM clause, …) except of absolute paths written as text inside the
 * connection's data folders. DuckDB enforces the same boundary itself ({@link DuckDbEngine}); this class gives the
 * agent a reason before anything runs.
 */
final class DuckSql {

  enum T { WORD, QUOTED, STRING, NUMBER, PARAM, OP, LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, COMMA, SEMI,
    DOT }

  /**
   * One token.
   *
   * @param value a word in ASCII lower case, a name or a string as DuckDB reads it ({@code ''} undone); else the text
   * @param plain a {@code '…'} or dollar-quoted string, the only forms a file path is read from
   */
  record Token(T type, String value, int start, int end, boolean plain) {

    boolean word(String w) {
      return type == T.WORD && value.equals(w);
    }
  }

  /** A file a statement reads, as written, and whether it is a glob pattern. */
  record FileRef(String path, boolean glob) {}

  /**
   * What GateRook makes of one statement.
   *
   * @param problem why it is refused, or {@code null}
   * @param verb    its first keyword in upper case ({@code SELECT}, {@code INSERT}, {@code CREATE TABLE})
   * @param text    the statement as written, without a trailing {@code ;} or comment: what runs
   * @param rows    a read whose rows an outer {@code LIMIT} can bound (everything but EXPLAIN)
   * @param target  the table or object a change names, or {@code null}
   * @param files   the files it reads, every one inside a data folder
   */
  record Verdict(String problem, String verb, String text, boolean rows, String target, List<FileRef> files) {

    boolean ok() {
      return problem == null;
    }

    static Verdict refused(String problem) {
      return new Verdict(problem, null, null, false, null, List.of());
    }
  }

  /** Read-only statements by their first keyword. */
  static final Set<String> QUERIES = Set.of("select", "from", "values", "table", "pivot", "pivot_wider", "unpivot",
      "pivot_longer");

  /** Statements that change data as the first word inside parentheses: a data-modifying CTE or subquery. */
  static final Set<String> NESTED_CHANGES = Set.of("insert", "update", "delete", "merge");

  /** Table functions that run SQL from text the classifier can't read. */
  static final Set<String> SQL_FROM_TEXT = Set.of("query", "query_table", "json_execute_serialized_sql");

  /** Local file readers: allowed for absolute paths, written as text, inside a data folder. */
  static final Set<String> FILE_READERS = Set.of("read_csv", "read_csv_auto", "sniff_csv", "read_json",
      "read_json_auto", "read_json_objects", "read_json_objects_auto", "read_ndjson", "read_ndjson_auto",
      "read_ndjson_objects", "read_parquet", "parquet_scan", "parquet_metadata", "parquet_schema",
      "parquet_file_metadata", "parquet_kv_metadata", "parquet_bloom_probe", "read_text", "read_blob", "glob");

  /** Readers of other systems and other databases, and the functions of extensions GateRook never loads. */
  static final Set<String> OTHER_SYSTEMS = Set.of("read_duckdb", "read_xlsx", "read_avro", "read_gsheet",
      "iceberg_scan", "iceberg_metadata", "iceberg_snapshots", "delta_scan", "st_read", "st_read_meta", "st_readosm",
      "st_readshp", "sqlite_scan", "sqlite_attach", "sqlite_query", "postgres_scan", "postgres_scan_pushdown",
      "postgres_attach", "postgres_query", "postgres_execute", "mysql_scan", "mysql_attach", "mysql_query",
      "mysql_execute", "http_get", "http_post", "load_aws_credentials", "checkpoint", "force_checkpoint");

  /** Words that end a FROM clause at its own depth. */
  private static final Set<String> FROM_ENDS = Set.of("where", "group", "having", "qualify", "window", "order",
      "limit", "offset", "union", "except", "intersect", "select", "returning", "set", "values");

  /** Words after which a string or a quoted name is a table: a file read by DuckDB's replacement scans. */
  private static final Set<String> TABLE_POSITIONS = Set.of("from", "join", "describe", "desc", "summarize", "pivot",
      "pivot_wider", "unpivot", "pivot_longer", "table");

  /** A quoted table name that names a file rather than a table. */
  private static final Pattern FILE_LIKE = Pattern.compile("(?i).*([/\\\\*?~]|://|\\.(csv|tsv|txt|json|jsonl|ndjson"
      + "|parquet|arrow|feather|ipc|avro|orc|xlsx|xls|xml|gz|gzip|zst|zstd|bz2|xz|lz4|snappy|db|duckdb|ddb|sqlite"
      + "|sqlite3|gpkg|shp|geojson)$).*");

  private DuckSql() {}

  // ── Statements ──────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * One read-only statement, its file reads checked against {@code folders} ({@code /…/} prefixes; empty: no file may
   * be read).
   */
  static Verdict read(String sql, List<String> folders) {
    Statement s = statement(sql);
    if (s.problem() != null) {
      return Verdict.refused(s.problem());
    }
    List<Token> t = s.tokens();
    int i = 0;
    while (i < t.size() && t.get(i).type() == T.LPAREN) {
      i++;
    }
    if (i >= t.size() || t.get(i).type() != T.WORD) {
      return Verdict.refused("GateRook can't read this statement.");
    }
    String first = t.get(i).value();
    String kind = readKind(t, i, i > 0);
    if (kind.startsWith("!")) {
      return Verdict.refused(kind.substring(1));
    }
    String problem = nestedChange(t);
    if (problem == null) {
      problem = refusedFunctions(t);
    }
    List<FileRef> files = new ArrayList<>();
    if (problem == null) {
      problem = files(t, folders, files);
    }
    if (problem != null) {
      return Verdict.refused(problem);
    }
    return new Verdict(null, kind, s.text(), !"explain".equals(first), null, List.copyOf(files));
  }

  /** The kind of the read at {@code i}, or {@code !reason} when it isn't one. */
  private static String readKind(List<Token> t, int i, boolean parenthesized) {
    String w = t.get(i).value();
    if (QUERIES.contains(w)) {
      return w.toUpperCase(Locale.ROOT);
    }
    if (w.equals("with")) {
      int main = afterWith(t, i);
      if (main < 0) {
        return "!GateRook can't read this WITH clause.";
      }
      int j = main;
      while (j < t.size() && t.get(j).type() == T.LPAREN) {
        j++;
      }
      if (j < t.size() && t.get(j).type() == T.WORD && QUERIES.contains(t.get(j).value())) {
        return "WITH " + t.get(j).value().toUpperCase(Locale.ROOT);
      }
      String verb = j < t.size() && t.get(j).type() == T.WORD ? t.get(j).value() : "";
      return "!" + notARead(verb);
    }
    if (parenthesized) {
      return "!GateRook reads only a query inside parentheses.";
    }
    if (Set.of("describe", "desc", "show", "summarize").contains(w)) {
      return w.equals("desc") ? "DESCRIBE" : w.toUpperCase(Locale.ROOT);
    }
    if (w.equals("explain")) {
      int j = i + 1;
      if (j < t.size() && (t.get(j).word("analyze") || t.get(j).word("analyse"))) {
        j++;
      }
      if (j < t.size() && t.get(j).type() == T.LPAREN) {
        j = skip(t, j);
        if (j < 0) {
          return "!GateRook can't read this EXPLAIN.";
        }
      }
      int k = j;
      while (k < t.size() && t.get(k).type() == T.LPAREN) {
        k++;
      }
      if (k >= t.size() || t.get(k).type() != T.WORD || t.get(k).word("explain")) {
        return "!GateRook can't read this EXPLAIN.";
      }
      String inner = readKind(t, k, k > j);
      return inner.startsWith("!") ? inner : "EXPLAIN " + inner;
    }
    return "!" + notARead(w);
  }

  /** Why {@code verb} isn't a read. */
  private static String notARead(String verb) {
    String refused = refusedEverywhere(verb);
    if (refused != null) {
      return refused;
    }
    return switch (verb) {
      case "insert", "update", "delete", "merge", "create", "alter", "drop", "truncate", "comment" ->
          "This tool only reads, and " + verb.toUpperCase(Locale.ROOT) + " changes the database: use duckdb_execute, "
              + "which waits for a person's approval.";
      case "" -> "GateRook can't read this statement.";
      default -> verb.toUpperCase(Locale.ROOT) + " isn't a read GateRook runs: use SELECT, FROM, WITH, VALUES, TABLE, "
          + "PIVOT, UNPIVOT, DESCRIBE, SHOW, SUMMARIZE or EXPLAIN.";
    };
  }

  /** The reason for a statement no tool runs, or {@code null}. */
  private static String refusedEverywhere(String verb) {
    return switch (verb) {
      case "attach", "detach" -> verb.toUpperCase(Locale.ROOT) + " isn't allowed: this connection is one database "
          + "file.";
      case "copy" -> "COPY isn't allowed: GateRook never writes files, and reads them only with read_csv, "
          + "read_parquet or read_json from a data folder.";
      case "export", "import" -> "EXPORT DATABASE and IMPORT DATABASE write and read whole folders: refused.";
      case "install", "load" -> "Extensions aren't installed or loaded through GateRook.";
      case "force" -> "FORCE INSTALL and FORCE CHECKPOINT aren't allowed.";
      case "set", "reset", "pragma" -> "GateRook locks DuckDB's settings for every call: " + verb.toUpperCase(
          Locale.ROOT) + " isn't allowed.";
      case "call" -> "CALL isn't allowed: select from the table function instead.";
      case "checkpoint", "vacuum", "analyze" -> verb.toUpperCase(Locale.ROOT) + " isn't allowed: it rewrites the "
          + "database file.";
      case "use" -> "USE isn't allowed: name the schema in the statement instead.";
      case "begin", "start", "commit", "end", "rollback", "abort", "savepoint", "release" -> "Every call is its own "
          + "transaction: " + verb.toUpperCase(Locale.ROOT) + " isn't allowed.";
      case "prepare", "execute", "deallocate" -> "Prepared statements aren't run through GateRook: send the statement "
          + "itself.";
      default -> null;
    };
  }

  /**
   * One change: INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, TRUNCATE or COMMENT ON, its file reads checked as
   * {@link #read} checks them.
   */
  static Verdict change(String sql, List<String> folders) {
    Statement s = statement(sql);
    if (s.problem() != null) {
      return Verdict.refused(s.problem());
    }
    List<Token> t = s.tokens();
    if (t.getFirst().type() != T.WORD) {
      return Verdict.refused("GateRook can't read this statement.");
    }
    int i = 0;
    if (t.getFirst().word("with")) {
      i = afterWith(t, 0);
      if (i < 0 || i >= t.size() || t.get(i).type() != T.WORD) {
        return Verdict.refused("GateRook can't read this WITH clause.");
      }
      if (!Set.of("insert", "update", "delete").contains(t.get(i).value())) {
        return Verdict.refused(notAChange(t.get(i).value()));
      }
    }
    String[] described = describeChange(t, i);
    if (described[0] != null) {
      return Verdict.refused(described[0]);
    }
    String problem = nestedChange(t);
    if (problem == null) {
      problem = refusedFunctions(t);
    }
    List<FileRef> files = new ArrayList<>();
    if (problem == null) {
      problem = files(t, folders, files);
    }
    if (problem != null) {
      return Verdict.refused(problem);
    }
    return new Verdict(null, described[1], s.text(), false, described[2], List.copyOf(files));
  }

  /** {@code [problem, verb, target]} of the change at {@code i}. */
  private static String[] describeChange(List<Token> t, int i) {
    String w = t.get(i).value();
    int j = i + 1;
    switch (w) {
      case "insert" -> {
        if (word(t, j, "or") && (word(t, j + 1, "replace") || word(t, j + 1, "ignore"))) {
          j += 2;
        }
        if (!word(t, j, "into")) {
          return new String[] {"GateRook can't read this INSERT: it needs INSERT INTO a table.", null, null};
        }
        return new String[] {null, "INSERT", name(t, j + 1)};
      }
      case "update" -> {
        if (word(t, j, "extensions")) {
          return new String[] {refusedEverywhere("install"), null, null};
        }
        return new String[] {null, "UPDATE", name(t, j)};
      }
      case "delete" -> {
        if (!word(t, j, "from")) {
          return new String[] {"GateRook can't read this DELETE: it needs DELETE FROM a table.", null, null};
        }
        return new String[] {null, "DELETE", name(t, j + 1)};
      }
      case "truncate" -> {
        if (word(t, j, "table")) {
          j++;
        }
        return new String[] {null, "TRUNCATE", name(t, j)};
      }
      case "create" -> {
        if (word(t, j, "or") && word(t, j + 1, "replace")) {
          j += 2;
        }
        if (word(t, j, "temp") || word(t, j, "temporary")) {
          return new String[] {"Temporary objects end with the call's own DuckDB instance: create a table instead.",
              null, null};
        }
        if (word(t, j, "persistent") || word(t, j, "secret")) {
          return new String[] {"Secrets aren't created through GateRook.", null, null};
        }
        if (word(t, j, "unique")) {
          j++;
        }
        String object = j < t.size() && t.get(j).type() == T.WORD ? t.get(j).value() : "";
        if (!Set.of("table", "view", "index", "sequence", "schema", "type", "macro", "function").contains(object)) {
          return new String[] {"CREATE " + object.toUpperCase(Locale.ROOT) + " isn't a change GateRook runs: it "
              + "creates tables, views, indexes, sequences, schemas, types and macros.", null, null};
        }
        j++;
        if (word(t, j, "if") && word(t, j + 1, "not") && word(t, j + 2, "exists")) {
          j += 3;
        }
        String target = name(t, j);
        if (object.equals("index")) {
          int on = j;
          while (on < t.size() && !t.get(on).word("on")) {
            on++;
          }
          target = name(t, on + 1);
        }
        return new String[] {null, "CREATE " + object.toUpperCase(Locale.ROOT), target};
      }
      case "alter" -> {
        String object = j < t.size() && t.get(j).type() == T.WORD ? t.get(j).value() : "";
        if (!Set.of("table", "view", "sequence").contains(object)) {
          return new String[] {"ALTER " + object.toUpperCase(Locale.ROOT) + " isn't a change GateRook runs: it alters "
              + "tables, views and sequences.", null, null};
        }
        j++;
        if (word(t, j, "if") && word(t, j + 1, "exists")) {
          j += 2;
        }
        return new String[] {null, "ALTER " + object.toUpperCase(Locale.ROOT), name(t, j)};
      }
      case "drop" -> {
        String object = j < t.size() && t.get(j).type() == T.WORD ? t.get(j).value() : "";
        if (object.equals("secret") || object.equals("persistent") || object.equals("temporary")) {
          return new String[] {"Secrets aren't dropped through GateRook.", null, null};
        }
        if (!Set.of("table", "view", "index", "sequence", "schema", "type", "macro", "function").contains(object)) {
          return new String[] {"DROP " + object.toUpperCase(Locale.ROOT) + " isn't a change GateRook runs: it drops "
              + "tables, views, indexes, sequences, schemas, types and macros.", null, null};
        }
        j++;
        if (object.equals("macro") && word(t, j, "table")) {
          j++;
        }
        if (word(t, j, "if") && word(t, j + 1, "exists")) {
          j += 2;
        }
        return new String[] {null, "DROP " + object.toUpperCase(Locale.ROOT), name(t, j)};
      }
      case "comment" -> {
        if (!word(t, j, "on") || j + 1 >= t.size() || t.get(j + 1).type() != T.WORD) {
          return new String[] {"GateRook can't read this COMMENT: it needs COMMENT ON an object.", null, null};
        }
        return new String[] {null, "COMMENT ON " + t.get(j + 1).value().toUpperCase(Locale.ROOT), name(t, j + 2)};
      }
      default -> {
        return new String[] {notAChange(w), null, null};
      }
    }
  }

  /** Why {@code verb} isn't a change duckdb_execute runs. */
  private static String notAChange(String verb) {
    String refused = refusedEverywhere(verb);
    if (refused != null) {
      return refused;
    }
    if (QUERIES.contains(verb) || Set.of("with", "describe", "desc", "show", "summarize", "explain").contains(verb)) {
      return "duckdb_execute runs changes: send a read to duckdb_query.";
    }
    if (verb.equals("merge")) {
      return "duckdb_execute runs one INSERT, UPDATE, DELETE or DDL statement: write the MERGE as those.";
    }
    return (verb.isEmpty() ? "This statement" : verb.toUpperCase(Locale.ROOT)) + " isn't a change GateRook runs: "
        + "send one INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, TRUNCATE or COMMENT ON.";
  }

  /** A statement that changes data inside parentheses (a data-modifying CTE, a subquery): refused. */
  private static String nestedChange(List<Token> t) {
    for (int k = 0; k + 1 < t.size(); k++) {
      if (t.get(k).type() == T.LPAREN && t.get(k + 1).type() == T.WORD
          && NESTED_CHANGES.contains(t.get(k + 1).value())) {
        return "A statement inside parentheses may only read: " + t.get(k + 1).value().toUpperCase(Locale.ROOT)
            + " isn't allowed there.";
      }
    }
    return null;
  }

  /** Functions that run SQL from text or reach other systems, by name (the last part of a qualified one). */
  private static String refusedFunctions(List<Token> t) {
    for (int k = 0; k + 1 < t.size(); k++) {
      String name = functionName(t, k);
      if (name == null) {
        continue;
      }
      if (!name.chars().allMatch(c -> c < 0x80)) {
        return "GateRook can't read the function name " + t.get(k).value() + ".";
      }
      if (SQL_FROM_TEXT.contains(name)) {
        return name + "() runs SQL from text, which GateRook can't check: write the statement itself.";
      }
      if (OTHER_SYSTEMS.contains(name)) {
        return name + "() isn't allowed: queries read this database and the files of its data folders only.";
      }
    }
    return null;
  }

  /** The name of a function called at {@code k} ({@code name(}, {@code "name"(}), lower case, or {@code null}. */
  private static String functionName(List<Token> t, int k) {
    Token at = t.get(k);
    if (k + 1 >= t.size() || t.get(k + 1).type() != T.LPAREN) {
      return null;
    }
    if (at.type() == T.WORD) {
      return at.value();
    }
    if (at.type() == T.QUOTED) {
      return at.value().toLowerCase(Locale.ROOT);
    }
    return null;
  }

  /**
   * The files a statement reads: the path arguments of the local file readers and the strings (and file-like quoted
   * names) DuckDB scans as tables. Each must be an absolute path written as text inside a data folder.
   */
  private static String files(List<Token> t, List<String> folders, List<FileRef> out) {
    for (int k = 0; k + 1 < t.size(); k++) {
      String name = functionName(t, k);
      if (name == null || !FILE_READERS.contains(name)) {
        continue;
      }
      List<String> paths = new ArrayList<>();
      String problem = literalPaths(t, k + 2, name, paths);
      if (problem != null) {
        return problem;
      }
      for (String p : paths) {
        problem = inFolders(p, folders, out);
        if (problem != null) {
          return problem;
        }
      }
    }
    // A string (or a file-like quoted name) where a table goes is a file DuckDB scans: after FROM, a JOIN, DESCRIBE,
    // SUMMARIZE, PIVOT or TABLE, or after a comma of a FROM clause. Inside a function's parentheses (trim('x' FROM
    // s), extract(year FROM t)) FROM names no table; a subquery's parentheses start with a query keyword.
    boolean[] inFrom = new boolean[t.size() + 2];
    boolean[] call = new boolean[t.size() + 2];
    int depth = 0;
    for (int k = 0; k < t.size(); k++) {
      Token at = t.get(k);
      switch (at.type()) {
        case LPAREN, LBRACKET, LBRACE -> {
          depth++;
          inFrom[depth] = false;
          Token before = k == 0 ? null : t.get(k - 1);
          Token after = k + 1 < t.size() ? t.get(k + 1) : null;
          boolean query = after != null && after.type() == T.WORD && (QUERIES.contains(after.value())
              || after.value().equals("with"));
          call[depth] = at.type() != T.LPAREN || (!query && before != null
              && (before.type() == T.WORD || before.type() == T.QUOTED));
        }
        case RPAREN, RBRACKET, RBRACE -> depth = Math.max(0, depth - 1);
        case WORD -> {
          if (at.value().equals("from")) {
            inFrom[depth] = !call[depth];
          } else if (FROM_ENDS.contains(at.value())) {
            inFrom[depth] = false;
          }
        }
        case STRING, QUOTED -> {
          Token before = k == 0 ? null : t.get(k - 1);
          boolean table = !call[depth] && before != null && ((before.type() == T.WORD
              && (TABLE_POSITIONS.contains(before.value()) || before.value().endsWith("join")))
              || (before.type() == T.COMMA && inFrom[depth]));
          Token after = k + 1 < t.size() ? t.get(k + 1) : null;
          if (!table || (after != null && (after.type() == T.DOT || after.type() == T.LPAREN))) {
            continue;
          }
          if (at.type() == T.QUOTED && !FILE_LIKE.matcher(at.value()).matches()) {
            continue;
          }
          if (at.type() == T.STRING && (!at.plain() || (after != null && after.type() == T.STRING))) {
            return "GateRook reads a file name only when it is written as one plain string ('…').";
          }
          String problem = inFolders(at.value(), folders, out);
          if (problem != null) {
            return problem;
          }
        }
        default -> {
          // Other tokens don't change where a table is named.
        }
      }
    }
    return null;
  }

  /** The path or list of paths that starts at {@code j}, the first argument of {@code function}. */
  private static String literalPaths(List<Token> t, int j, String function, List<String> out) {
    String needs = function + "() reads files only by an absolute path written as text, or a list of them.";
    if (j >= t.size()) {
      return needs;
    }
    int next;
    if (t.get(j).type() == T.STRING) {
      if (!t.get(j).plain()) {
        return needs;
      }
      out.add(t.get(j).value());
      next = j + 1;
    } else if (t.get(j).type() == T.LBRACKET) {
      next = j + 1;
      while (true) {
        if (next >= t.size() || t.get(next).type() != T.STRING || !t.get(next).plain()) {
          return needs;
        }
        out.add(t.get(next).value());
        next++;
        if (next < t.size() && t.get(next).type() == T.COMMA) {
          next++;
          continue;
        }
        if (next < t.size() && t.get(next).type() == T.RBRACKET) {
          next++;
          break;
        }
        return needs;
      }
    } else {
      return needs;
    }
    if (next >= t.size() || (t.get(next).type() != T.COMMA && t.get(next).type() != T.RPAREN)) {
      return needs;
    }
    return null;
  }

  /** Whether {@code path} is an absolute local path inside one of {@code folders}; adds it to {@code out}. */
  static String inFolders(String path, List<String> folders, List<FileRef> out) {
    if (folders.isEmpty()) {
      return "Queries read files only from the connection's data folders, and this connection has none: "
          + "a person can add one in its settings.";
    }
    if (path.contains("://")) {
      return "Queries read local files only, not " + path + ".";
    }
    String p = path;
    if (DuckDbFiles.WINDOWS) {
      p = p.replace('\\', '/');
    } else if (p.indexOf('\\') >= 0) {
      return "A file path can't contain a backslash: DuckDB reads it as a folder separator.";
    }
    if (p.indexOf('~') >= 0) {
      return "Write the file path in full, without ~.";
    }
    boolean absolute = DuckDbFiles.WINDOWS ? p.matches("^[A-Za-z]:/.*") : p.startsWith("/");
    if (!absolute) {
      return "Write the file path in full, from the root (" + path + " isn't): DuckDB reads files only inside the "
          + "data folders.";
    }
    String rest = DuckDbFiles.WINDOWS ? p.substring(3) : p.substring(1);
    for (String segment : rest.split("/", -1)) {
      if (segment.equals("..") || segment.equals(".") || segment.isEmpty()) {
        return "A file path can't contain " + (segment.isEmpty() ? "an empty part" : segment) + ": " + path + ".";
      }
    }
    String compared = DuckDbFiles.WINDOWS ? p.toLowerCase(Locale.ROOT) : p;
    for (String folder : folders) {
      String prefix = DuckDbFiles.WINDOWS ? folder.toLowerCase(Locale.ROOT) : folder;
      if (compared.startsWith(prefix) && compared.length() > prefix.length()) {
        out.add(new FileRef(path, p.chars().anyMatch(c -> c == '*' || c == '?' || c == '[' || c == '{')));
        return null;
      }
    }
    return path + " isn't in a data folder of this connection: queries read files only from those (see "
        + "current_setting('allowed_directories')).";
  }

  /** Where the main statement of the WITH clause at {@code i} starts, or -1 when the clause can't be read. */
  static int afterWith(List<Token> t, int i) {
    int j = i + 1;
    if (word(t, j, "recursive")) {
      j++;
    }
    while (true) {
      if (j >= t.size() || (t.get(j).type() != T.WORD && t.get(j).type() != T.QUOTED)) {
        return -1;
      }
      j++;
      if (j < t.size() && t.get(j).type() == T.LPAREN) {
        j = skip(t, j);
        if (j < 0) {
          return -1;
        }
      }
      if (word(t, j, "using") && word(t, j + 1, "key") && j + 2 < t.size() && t.get(j + 2).type() == T.LPAREN) {
        j = skip(t, j + 2);
        if (j < 0) {
          return -1;
        }
      }
      if (!word(t, j, "as")) {
        return -1;
      }
      j++;
      if (word(t, j, "not") && word(t, j + 1, "materialized")) {
        j += 2;
      } else if (word(t, j, "materialized")) {
        j++;
      }
      if (j >= t.size() || t.get(j).type() != T.LPAREN) {
        return -1;
      }
      j = skip(t, j);
      if (j < 0) {
        return -1;
      }
      if (j < t.size() && t.get(j).type() == T.COMMA) {
        j++;
        continue;
      }
      return j;
    }
  }

  /** The index after the bracket that closes the one at {@code i}, or -1. */
  static int skip(List<Token> t, int i) {
    List<T> open = new ArrayList<>();
    for (int k = i; k < t.size(); k++) {
      T type = t.get(k).type();
      switch (type) {
        case LPAREN, LBRACKET, LBRACE -> open.add(type);
        case RPAREN, RBRACKET, RBRACE -> {
          if (open.isEmpty() || open.removeLast() != opening(type)) {
            return -1;
          }
          if (open.isEmpty()) {
            return k + 1;
          }
        }
        default -> {
          // Inside the brackets.
        }
      }
    }
    return -1;
  }

  private static T opening(T closing) {
    return switch (closing) {
      case RPAREN -> T.LPAREN;
      case RBRACKET -> T.LBRACKET;
      default -> T.LBRACE;
    };
  }

  /** The (qualified) name at {@code j} as written, or {@code null}. */
  private static String name(List<Token> t, int j) {
    StringBuilder b = new StringBuilder();
    while (j < t.size() && (t.get(j).type() == T.WORD || t.get(j).type() == T.QUOTED)) {
      Token n = t.get(j);
      b.append(n.type() == T.QUOTED ? "\"" + n.value().replace("\"", "\"\"") + "\"" : n.value());
      if (j + 1 < t.size() && t.get(j + 1).type() == T.DOT) {
        b.append('.');
        j += 2;
      } else {
        break;
      }
    }
    return b.isEmpty() ? null : b.toString();
  }

  private static boolean word(List<Token> t, int j, String w) {
    return j < t.size() && t.get(j).word(w);
  }

  // ── One statement ───────────────────────────────────────────────────────────────────────────────────────────

  /** The tokens and text of the one statement of {@code sql}, or why there isn't exactly one. */
  record Statement(List<Token> tokens, String text, String problem) {}

  static Statement statement(String sql) {
    if (sql == null || sql.isBlank()) {
      return new Statement(List.of(), null, "The statement is empty.");
    }
    Lexed lexed = lex(sql);
    if (lexed.problem() != null) {
      return new Statement(List.of(), null, lexed.problem());
    }
    List<List<Token>> statements = new ArrayList<>();
    List<Token> current = new ArrayList<>();
    for (Token token : lexed.tokens()) {
      if (token.type() == T.SEMI) {
        if (!current.isEmpty()) {
          statements.add(current);
          current = new ArrayList<>();
        }
      } else {
        current.add(token);
      }
    }
    if (!current.isEmpty()) {
      statements.add(current);
    }
    if (statements.isEmpty()) {
      return new Statement(List.of(), null, "The statement is empty.");
    }
    if (statements.size() > 1) {
      return new Statement(List.of(), null, "Send one statement per call: this has " + statements.size() + ".");
    }
    List<Token> tokens = List.copyOf(statements.getFirst());
    int depth = 0;
    for (Token token : tokens) {
      switch (token.type()) {
        case LPAREN, LBRACKET, LBRACE -> depth++;
        case RPAREN, RBRACKET, RBRACE -> depth--;
        default -> {
          // Not a bracket.
        }
      }
      if (depth < 0) {
        return new Statement(List.of(), null, "The statement's brackets don't match.");
      }
    }
    if (depth != 0) {
      return new Statement(List.of(), null, "The statement's brackets don't match.");
    }
    return new Statement(tokens, sql.substring(tokens.getFirst().start(), tokens.getLast().end()), null);
  }

  // ── Tokens ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** The tokens of {@code sql}, or the first thing that keeps GateRook from reading it. */
  record Lexed(List<Token> tokens, String problem) {}

  static Lexed lex(String sql) {
    List<Token> out = new ArrayList<>();
    int n = sql.length();
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B') {
        i++;
      } else if (c == '-' && next == '-') {
        while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
          i++;
        }
      } else if (c == '/' && next == '*') {
        int depth = 1;
        i += 2;
        while (i < n && depth > 0) {
          if (sql.startsWith("/*", i)) {
            depth++;
            i += 2;
          } else if (sql.startsWith("*/", i)) {
            depth--;
            i += 2;
          } else {
            i++;
          }
        }
        if (depth > 0) {
          return new Lexed(out, "A comment isn't closed.");
        }
      } else if (c == '\'') {
        int end = quoted(sql, i, '\'', false);
        if (end < 0) {
          return new Lexed(out, "A string isn't closed.");
        }
        out.add(new Token(T.STRING, unquote(sql.substring(i + 1, end - 1), '\''), i, end, true));
        i = end;
      } else if (c == '"') {
        int end = quoted(sql, i, '"', false);
        if (end < 0) {
          return new Lexed(out, "A quoted name isn't closed.");
        }
        out.add(new Token(T.QUOTED, unquote(sql.substring(i + 1, end - 1), '"'), i, end, true));
        i = end;
      } else if ((c == 'e' || c == 'E') && next == '\'') {
        int end = quoted(sql, i + 1, '\'', true);
        if (end < 0) {
          return new Lexed(out, "A string isn't closed.");
        }
        out.add(new Token(T.STRING, sql.substring(i + 2, end - 1), i, end, false));
        i = end;
      } else if ((c == 'b' || c == 'B' || c == 'x' || c == 'X' || c == 'n' || c == 'N') && next == '\'') {
        int end = quoted(sql, i + 1, '\'', false);
        if (end < 0) {
          return new Lexed(out, "A string isn't closed.");
        }
        out.add(new Token(T.STRING, sql.substring(i + 2, end - 1), i, end, false));
        i = end;
      } else if ((c == 'u' || c == 'U') && next == '&' && i + 2 < n
          && (sql.charAt(i + 2) == '\'' || sql.charAt(i + 2) == '"')) {
        char q = sql.charAt(i + 2);
        int end = quoted(sql, i + 2, q, false);
        if (end < 0) {
          return new Lexed(out, q == '"' ? "A quoted name isn't closed." : "A string isn't closed.");
        }
        out.add(new Token(q == '"' ? T.QUOTED : T.STRING, sql.substring(i + 3, end - 1), i, end, false));
        i = end;
      } else if (identStart(c)) {
        int end = i + 1;
        while (end < n && identPart(sql.charAt(end))) {
          end++;
        }
        out.add(new Token(T.WORD, ascii(sql.substring(i, end)), i, end, true));
        i = end;
      } else if (c == '$') {
        if (Character.isDigit(next)) {
          int end = i + 1;
          while (end < n && Character.isDigit(sql.charAt(end))) {
            end++;
          }
          out.add(new Token(T.PARAM, sql.substring(i, end), i, end, true));
          i = end;
          continue;
        }
        int tagEnd = i + 1;
        if (next != '$') {
          if (!identStart(next)) {
            return new Lexed(out, "GateRook can't read the $ at " + (i + 1) + ".");
          }
          while (tagEnd < n && (identPart(sql.charAt(tagEnd)) && sql.charAt(tagEnd) != '$')) {
            tagEnd++;
          }
          if (tagEnd >= n || sql.charAt(tagEnd) != '$') {
            out.add(new Token(T.PARAM, sql.substring(i, tagEnd), i, tagEnd, true));
            i = tagEnd;
            continue;
          }
        }
        String delimiter = sql.substring(i, tagEnd + 1);
        int close = sql.indexOf(delimiter, tagEnd + 1);
        if (close < 0) {
          return new Lexed(out, "A dollar-quoted string isn't closed.");
        }
        out.add(new Token(T.STRING, sql.substring(tagEnd + 1, close), i, close + delimiter.length(), true));
        i = close + delimiter.length();
      } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(next))) {
        int end = number(sql, i);
        out.add(new Token(T.NUMBER, sql.substring(i, end), i, end, true));
        i = end;
      } else if (c == '?') {
        out.add(new Token(T.PARAM, "?", i, i + 1, true));
        i++;
      } else if (c == '`') {
        return new Lexed(out, "DuckDB quotes names with \"…\", not backticks.");
      } else if (c == '\\') {
        return new Lexed(out, "GateRook can't read the backslash at " + (i + 1) + " outside a string.");
      } else if ("+-*/<>=~!@#%^&|:".indexOf(c) >= 0) {
        int end = i + 1;
        while (end < n && "+-*/<>=~!@#%^&|:".indexOf(sql.charAt(end)) >= 0
            && !sql.startsWith("--", end) && !sql.startsWith("/*", end)) {
          end++;
        }
        out.add(new Token(T.OP, sql.substring(i, end), i, end, true));
        i = end;
      } else {
        T type = switch (c) {
          case '(' -> T.LPAREN;
          case ')' -> T.RPAREN;
          case '[' -> T.LBRACKET;
          case ']' -> T.RBRACKET;
          case '{' -> T.LBRACE;
          case '}' -> T.RBRACE;
          case ',' -> T.COMMA;
          case ';' -> T.SEMI;
          case '.' -> T.DOT;
          default -> null;
        };
        if (type == null) {
          return new Lexed(out, "GateRook can't read the character U+" + String.format("%04X", (int) c) + " at "
              + (i + 1) + ".");
        }
        out.add(new Token(type, String.valueOf(c), i, i + 1, true));
        i++;
      }
    }
    return new Lexed(List.copyOf(out), null);
  }

  /** The index after the closing quote of the quoted text starting at {@code i}, or -1; doubled quotes stay inside. */
  private static int quoted(String sql, int i, char quote, boolean backslashEscapes) {
    int k = i + 1;
    while (k < sql.length()) {
      char c = sql.charAt(k);
      if (backslashEscapes && c == '\\') {
        k += 2;
      } else if (c == quote) {
        if (k + 1 < sql.length() && sql.charAt(k + 1) == quote) {
          k += 2;
        } else {
          return k + 1;
        }
      } else {
        k++;
      }
    }
    return -1;
  }

  private static String unquote(String inside, char quote) {
    String q = String.valueOf(quote);
    return inside.replace(q + q, q);
  }

  /** The end of the number at {@code i}: digits and underscores, a fraction, an exponent, or 0x/0b/0o forms. */
  private static int number(String sql, int i) {
    int n = sql.length();
    int k = i;
    if (sql.charAt(k) == '0' && k + 1 < n && "xXbBoO".indexOf(sql.charAt(k + 1)) >= 0) {
      k += 2;
      while (k < n && (Character.isLetterOrDigit(sql.charAt(k)) || sql.charAt(k) == '_')) {
        k++;
      }
      return k;
    }
    while (k < n && (Character.isDigit(sql.charAt(k)) || sql.charAt(k) == '_')) {
      k++;
    }
    if (k < n && sql.charAt(k) == '.' && !(k + 1 < n && sql.charAt(k + 1) == '.')) {
      k++;
      while (k < n && (Character.isDigit(sql.charAt(k)) || sql.charAt(k) == '_')) {
        k++;
      }
    }
    if (k < n && (sql.charAt(k) == 'e' || sql.charAt(k) == 'E')) {
      int e = k + 1;
      if (e < n && (sql.charAt(e) == '+' || sql.charAt(e) == '-')) {
        e++;
      }
      if (e < n && Character.isDigit(sql.charAt(e))) {
        k = e;
        while (k < n && Character.isDigit(sql.charAt(k))) {
          k++;
        }
      }
    }
    return k;
  }

  /** DuckDB's scanner: an ASCII letter or underscore, or any non-ASCII character, starts a name. */
  private static boolean identStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c >= 0x80;
  }

  private static boolean identPart(char c) {
    return identStart(c) || (c >= '0' && c <= '9') || c == '$';
  }

  /** ASCII lower case: what DuckDB compares unquoted names and keywords with. */
  private static String ascii(String word) {
    StringBuilder b = new StringBuilder(word.length());
    for (int k = 0; k < word.length(); k++) {
      char c = word.charAt(k);
      b.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
    }
    return b.toString();
  }
}
