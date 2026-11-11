package gaterook.plugins.h2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * H2's SQL as GateRook reads it before a statement reaches the server (docs/h2.md): the tokens of H2's own tokenizer,
 * then what one statement is. It fails closed: what it can't read with certainty is refused.
 *
 * <p>H2 has no read-only transaction, so a read must be one query that calls only H2's built-in functions that don't
 * reach outside the database ({@link #READ_FUNCTIONS}): a function the database defines (an alias, Java code) or one
 * that reads or writes the server's files, links other databases or ends sessions is refused, as are data change
 * delta tables ({@code FINAL TABLE (INSERT …)}), {@code NEXT VALUE FOR}, row locks ({@code FOR UPDATE}) and JDBC
 * escapes (<code>{call …}</code>, which H2 turns into {@code CALL}). The read then runs in a transaction that is always
 * rolled back. A change is one INSERT, UPDATE, DELETE, MERGE, TRUNCATE, COMMENT or DDL statement on tables, views,
 * indexes, sequences, schemas, domains, constants or synonyms; Java code ({@code CREATE ALIAS}, triggers, aggregates),
 * linked tables, users, roles, grants, scripts, backups, settings, {@code CALL}, {@code EXECUTE IMMEDIATE},
 * {@code SHUTDOWN} and transaction control are refused everywhere.
 *
 * <p>The lexer knows {@code '…'} strings with {@code ''} (also {@code N'…'}, {@code X'…'}, {@code U&'…'}),
 * {@code $$…$$} strings, {@code "…"} names with {@code ""}, {@code --} and {@code //} line comments and block comments.
 * A nested block comment, a brace, a backtick, {@code #}, {@code @} or a character outside the language is refused.
 */
final class H2Sql {

  enum T { WORD, QUOTED, STRING, NUMBER, PARAM, OP, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, SEMI, DOT }

  /**
   * One token.
   *
   * @param value a word in upper case (H2 folds unquoted names to upper case), a name as written, or the text
   */
  record Token(T type, String value, int start, int end) {

    boolean word(String w) {
      return type == T.WORD && value.equals(w);
    }
  }

  enum Kind { READ, CHANGE, REFUSED }

  /**
   * What GateRook makes of one statement.
   *
   * @param problem why it is refused, or {@code null}
   * @param verb    its statement words in upper case ({@code SELECT}, {@code INSERT}, {@code CREATE TABLE})
   * @param text    the statement as written, without a trailing {@code ;}: what runs
   * @param target  the table or object a change names, or {@code null}
   */
  record Verdict(Kind kind, String problem, String verb, String text, String target) {

    boolean ok() {
      return problem == null;
    }

    static Verdict refused(String problem) {
      return new Verdict(Kind.REFUSED, problem, null, null, null);
    }
  }

  /** The longest statement read. */
  static final int MAX_LENGTH = 100_000;

  /**
   * H2's built-in functions a read may call (H2 2.5's parser: its built-in and older function names, aggregates,
   * window and table functions), without those that reach outside the query: {@code FILE_READ}, {@code FILE_WRITE},
   * {@code CSVREAD}, {@code CSVWRITE}, {@code LINK_SCHEMA}, {@code CANCEL_SESSION}, {@code ABORT_SESSION} and
   * {@code NEXTVAL}. The functions only a compatibility mode adds aren't here: GateRook connects in H2's regular mode.
   */
  static final Set<String> READ_FUNCTIONS = Set.of(
      // The parser's built-in functions (Parser.readBuiltinFunctionIf).
      "ABS", "ACOS", "ARRAY_CONTAINS", "ARRAY_MAX_CARDINALITY", "ARRAY_SLICE", "ASCII", "ASIN", "ATAN", "ATAN2",
      "AUTOCOMMIT", "BITAND", "BITCOUNT", "BITGET", "BITNAND", "BITNOR", "BITNOT", "BITOR", "BITXNOR", "BITXOR",
      "BIT_LENGTH", "BTRIM", "CARDINALITY", "CEIL", "CEILING", "CHAR", "CHARACTER_LENGTH", "CHAR_LENGTH", "CHR",
      "COALESCE", "COMPRESS", "CONCAT", "CONCAT_WS", "COS", "COSH", "COT", "DATABASE_PATH", "DATA_TYPE_SQL",
      "DATEADD", "DATEDIFF", "DATE_TRUNC", "DAYNAME", "DB_OBJECT_APPROXIMATE_SIZE", "DB_OBJECT_APPROXIMATE_TOTAL_SIZE",
      "DB_OBJECT_ID", "DB_OBJECT_SIZE", "DB_OBJECT_SQL", "DB_OBJECT_TOTAL_SIZE", "DECRYPT", "DEGREES", "DIFFERENCE",
      "DISK_SPACE_USED", "ENCRYPT", "ESTIMATED_ENVELOPE", "EXP", "EXPAND", "EXTRACT", "FLOOR", "FORMATDATETIME",
      "GCD", "GREATEST", "H2VERSION", "HASH", "HEXTORAW", "INSERT", "JSON_ARRAY", "JSON_OBJECT", "LAST_DAY", "LCM",
      "LEAST", "LENGTH", "LN", "LOCATE", "LOCK_MODE", "LOCK_TIMEOUT", "LOG", "LOG10", "LOWER", "LPAD", "LSHIFT",
      "LTRIM", "MEMORY_FREE", "MEMORY_USED", "MOD", "MONTHNAME", "NULLIF", "OCTET_LENGTH", "ORA_HASH",
      "PARSEDATETIME", "PI", "POWER", "QUOTE_IDENT", "RADIANS", "RAND", "RANDOM", "RANDOM_UUID", "RAWTOHEX",
      "READONLY", "REGEXP_LIKE", "REGEXP_REPLACE", "REGEXP_SUBSTR", "REPEAT", "REPLACE", "ROTATELEFT", "ROTATERIGHT",
      "ROUND", "ROUNDMAGIC", "RPAD", "RSHIFT", "RTRIM", "SECURE_RAND", "SESSION_ID", "SIGN", "SIGNAL", "SIN", "SINH",
      "SOUNDEX", "SPACE", "SQRT", "STRINGDECODE", "STRINGENCODE", "STRINGTOUTF8", "SUBSTRING", "TAN", "TANH",
      "TIMESTAMPADD", "TIMESTAMPDIFF", "TO_CHAR", "TRANSACTION_ID", "TRANSLATE", "TRIM", "TRIM_ARRAY", "TRUNC",
      "TRUNCATE", "TRUNCATE_VALUE", "ULSHIFT", "UPPER", "URSHIFT", "UTF8TOSTRING", "UUID", "XMLATTR", "XMLCDATA",
      "XMLCOMMENT", "XMLNODE", "XMLSTARTDOC", "XMLTEXT", "ZERO",
      // The older names it reads in every mode (Parser.readCompatibilityFunction), without NEXTVAL.
      "ARRAY_APPEND", "ARRAY_CAT", "ARRAY_GET", "ARRAY_LENGTH", "CASEWHEN", "CONVERT", "CURDATE", "CURRVAL",
      "CURTIME", "DATABASE", "DAY", "DAYOFMONTH", "DAYOFWEEK", "DAYOFYEAR", "DAY_OF_MONTH", "DAY_OF_WEEK",
      "DAY_OF_YEAR", "DECODE", "HOUR", "IFNULL", "INSTR", "ISO_DAY_OF_WEEK", "ISO_WEEK", "ISO_YEAR", "LCASE",
      "MINUTE", "MONTH", "NOW", "NVL", "NVL2", "POSITION", "QUARTER", "SCHEMA", "SECOND", "SUBSTR", "SYSDATE",
      "SYSTIMESTAMP", "TODAY", "UCASE", "WEEK", "YEAR",
      // Forms the parser reads itself.
      "CAST", "LEFT", "RIGHT", "CURRENT_TIMESTAMP", "CURRENT_TIME", "LOCALTIME", "LOCALTIMESTAMP", "ARRAY", "ROW",
      // Table functions.
      "TABLE", "TABLE_DISTINCT", "UNNEST", "SYSTEM_RANGE", "GENERATE_SERIES",
      // Aggregates and the names H2 also reads for them (Aggregate).
      "COUNT", "SUM", "MIN", "MAX", "AVG", "STDDEV_POP", "STDDEV_SAMP", "STDDEV", "STDDEVP", "VAR_POP", "VAR_SAMP",
      "VAR", "VARIANCE", "VARP", "ANY_VALUE", "ANY", "SOME", "EVERY", "BOOL_AND", "BOOL_OR", "BIT_AND", "BIT_OR",
      "BIT_AND_AGG", "BIT_OR_AGG", "BIT_XOR_AGG", "BIT_NAND_AGG", "BIT_NOR_AGG", "BIT_XNOR_AGG", "HISTOGRAM",
      "COVAR_POP", "COVAR_SAMP", "CORR", "REGR_SLOPE", "REGR_INTERCEPT", "REGR_COUNT", "REGR_R2", "REGR_AVGX",
      "REGR_AVGY", "REGR_SXX", "REGR_SYY", "REGR_SXY", "PERCENTILE_CONT", "PERCENTILE_DISC", "MEDIAN", "LISTAGG",
      "GROUP_CONCAT", "STRING_AGG", "ARRAY_AGG", "MODE", "STATS_MODE", "ENVELOPE", "JSON_OBJECTAGG", "JSON_ARRAYAGG",
      "GCD_AGG", "LCM_AGG",
      // Window functions.
      "ROW_NUMBER", "RANK", "DENSE_RANK", "PERCENT_RANK", "CUME_DIST", "NTILE", "LEAD", "LAG", "FIRST_VALUE",
      "LAST_VALUE", "NTH_VALUE", "RATIO_TO_REPORT");

  /** Functions that reach outside the database or other sessions: refused in a change too. */
  static final Set<String> OUTSIDE = Set.of("FILE_READ", "FILE_WRITE", "CSVREAD", "CSVWRITE", "LINK_SCHEMA",
      "CANCEL_SESSION", "ABORT_SESSION");

  /** Words before a {@code (} that open a list or a subquery, not a function call. */
  private static final Set<String> NOT_CALLS = Set.of("SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "ON", "IN",
      "EXISTS", "UNIQUE", "VALUES", "OVER", "FILTER", "GROUP", "AS", "BY", "HAVING", "WHEN", "THEN", "ELSE", "CASE",
      "JOIN", "USING", "UNION", "INTERSECT", "EXCEPT", "MINUS", "LIMIT", "OFFSET", "FETCH", "TOP", "DISTINCT", "LIKE",
      "ILIKE", "REGEXP", "BETWEEN", "IS", "WITH", "RECURSIVE", "LATERAL", "WINDOW", "QUALIFY", "ESCAPE", "ALL",
      "INTO", "SET", "RETURN", "KEY", "PRIMARY", "REFERENCES", "CHECK", "CONSTRAINT", "INDEX", "PARTITION", "WITHIN",
      "DEFAULT", "UPDATE", "ROWS", "RANGE", "GROUPS", "PRECEDING", "FOLLOWING", "OF", "FOR", "MATCHED", "EXCLUDE",
      "NULLS", "TIES", "ONLY", "CROSS", "NATURAL", "INNER", "OUTER", "FULL");

  /** First words of a read. */
  private static final Set<String> READS = Set.of("SELECT", "WITH", "VALUES", "TABLE", "EXPLAIN", "SHOW");

  /** Objects a change may create, alter or drop. */
  private static final Set<String> CHANGE_OBJECTS = Set.of("TABLE", "VIEW", "INDEX", "SEQUENCE", "SCHEMA", "DOMAIN",
      "CONSTANT", "SYNONYM", "MATERIALIZED");

  private H2Sql() {}

  // ── Reads ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** One read: SELECT, WITH … SELECT, VALUES, TABLE, a parenthesized query, EXPLAIN of one, or SHOW. */
  static Verdict read(String sql) {
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
      return Verdict.refused("GateRook can't read this statement: it reads SELECT, WITH … SELECT, VALUES, TABLE, "
          + "EXPLAIN and SHOW.");
    }
    String first = t.get(i).value();
    if (first.equals("EXPLAIN")) {
      if (word(t, i + 1, "ANALYZE")) {
        return Verdict.refused("EXPLAIN ANALYZE runs the statement: use EXPLAIN alone.");
      }
      int j = i + 1;
      if (word(t, j, "PLAN") && word(t, j + 1, "FOR")) {
        j += 2;
      }
      if (j >= t.size() || !(t.get(j).type() == T.LPAREN || t.get(j).type() == T.WORD
          && Set.of("SELECT", "WITH", "VALUES", "TABLE").contains(t.get(j).value()))) {
        return Verdict.refused("EXPLAIN here explains a query only: a change goes through h2_execute.");
      }
    } else if (!READS.contains(first)) {
      String why = refusedEverywhere(t, i);
      return Verdict.refused(why != null ? why : first + " changes the database: h2_query only reads. A change goes "
          + "through h2_execute, which waits for a person's approval.");
    }
    if (first.equals("WITH")) {
      String main = afterWith(t, i);
      if (main == null || !Set.of("SELECT", "VALUES", "TABLE", "(").contains(main)) {
        return Verdict.refused("A WITH here must end in a query: a change goes through h2_execute.");
      }
    }
    String problem = readProblems(t);
    if (problem != null) {
      return Verdict.refused(problem);
    }
    return new Verdict(Kind.READ, null, first, s.text(), null);
  }

  /** What makes a query more than a read: changes inside it, sequences, locks, functions other than H2's own. */
  private static String readProblems(List<Token> t) {
    Set<Integer> notCalls = notCalls(t);
    for (int k = 0; k < t.size(); k++) {
      Token tok = t.get(k);
      if (tok.type() == T.WORD) {
        if (tok.value().equals("TABLE") && k > 0 && t.get(k - 1).type() == T.WORD
            && Set.of("FINAL", "NEW", "OLD").contains(t.get(k - 1).value()) && k + 1 < t.size()
            && t.get(k + 1).type() == T.LPAREN) {
          return t.get(k - 1).value() + " TABLE runs a change inside the query: changes go through h2_execute.";
        }
        if (tok.value().equals("NEXT") && word(t, k + 1, "VALUE") && word(t, k + 2, "FOR")) {
          return "NEXT VALUE FOR advances a sequence, which a rollback doesn't undo: use CURRENT VALUE FOR to read "
              + "it.";
        }
        if (tok.value().equals("FOR") && (word(t, k + 1, "UPDATE") || word(t, k + 1, "SHARE"))
            && (k == 0 || t.get(k - 1).type() != T.WORD || !t.get(k - 1).value().equals("VALUE"))) {
          return "FOR " + t.get(k + 1).value() + " locks rows: a read here doesn't lock.";
        }
      }
      if (k + 1 < t.size() && t.get(k + 1).type() == T.LPAREN && !notCalls.contains(k)) {
        String call = call(t, k);
        if (call != null) {
          return call;
        }
      }
    }
    return null;
  }

  /**
   * Why the call at {@code k} isn't one a read may make, or {@code null}: an H2 built-in that stays inside the query,
   * written unqualified and unquoted.
   */
  private static String call(List<Token> t, int k) {
    Token tok = t.get(k);
    if (tok.type() == T.QUOTED) {
      boolean afterDot = k > 0 && t.get(k - 1).type() == T.DOT;
      if (afterDot || k == 0 || !aliasPosition(t, k)) {
        return "A function written in quotes (" + tok.value() + ") is the database's own: reads may call H2's "
            + "built-in functions only.";
      }
      return null;
    }
    if (tok.type() != T.WORD) {
      return null;
    }
    String name = tok.value();
    if (k > 0 && t.get(k - 1).type() == T.DOT) {
      return "A function named with its schema (" + text(t, k) + ") is the database's own: reads may call H2's "
          + "built-in functions only.";
    }
    if (NOT_CALLS.contains(name)) {
      return null;
    }
    if (OUTSIDE.contains(name)) {
      return name + " reaches outside the database (the server's files, other databases or sessions): GateRook "
          + "never runs it.";
    }
    if (name.equals("NEXTVAL")) {
      return "NEXTVAL advances a sequence, which a rollback doesn't undo: use CURRVAL or CURRENT VALUE FOR.";
    }
    if (READ_FUNCTIONS.contains(name)) {
      return null;
    }
    return name + " isn't one of H2's built-in functions: a function the database defines can run Java code and "
        + "change data, so reads may call H2's built-in functions only.";
  }

  /** {@code "name"(} after AS or a {@code )}: a derived column list, not a call. */
  private static boolean aliasPosition(List<Token> t, int k) {
    Token before = t.get(k - 1);
    return before.word("AS") || before.type() == T.RPAREN;
  }

  /**
   * Positions of names followed by {@code (} that aren't calls: a CTE's column list ({@code WITH t(a, b) AS}), a
   * derived column list ({@code AS t(a, b)}, {@code ) t(a, b)}) and a type's precision after {@code AS} or
   * {@code ::} ({@code CAST(x AS DECIMAL(10, 2))}).
   */
  private static Set<Integer> notCalls(List<Token> t) {
    Set<Integer> out = new HashSet<>();
    for (int k = 1; k + 1 < t.size(); k++) {
      if (t.get(k + 1).type() != T.LPAREN || !(t.get(k).type() == T.WORD || t.get(k).type() == T.QUOTED)) {
        continue;
      }
      Token before = t.get(k - 1);
      if (before.word("AS") || before.type() == T.RPAREN || before.type() == T.OP && before.value().equals("::")) {
        out.add(k);
        continue;
      }
      // A type of two words: CAST(x AS CHARACTER VARYING(10)), DOUBLE PRECISION.
      if (before.type() == T.WORD && k >= 2 && t.get(k - 2).word("AS") && Set.of("CHARACTER", "CHAR", "NATIONAL",
          "BINARY", "DOUBLE", "TIMESTAMP", "TIME", "INTERVAL", "BIT").contains(before.value())) {
        out.add(k);
        continue;
      }
      if (before.word("WITH") || before.word("RECURSIVE") || before.type() == T.COMMA && cteColumns(t, k)) {
        out.add(k);
      }
    }
    return out;
  }

  /** {@code , name(cols) AS (}: the next CTE of a WITH list. */
  private static boolean cteColumns(List<Token> t, int k) {
    int close = skip(t, k + 1);
    return close > 0 && close < t.size() && t.get(close).word("AS") && close + 1 < t.size()
        && t.get(close + 1).type() == T.LPAREN;
  }

  /** The first word after a WITH list (or {@code "("}), or {@code null} when the list doesn't end well. */
  static String afterWith(List<Token> t, int i) {
    int j = i + 1;
    if (word(t, j, "RECURSIVE")) {
      j++;
    }
    while (true) {
      if (j >= t.size() || !(t.get(j).type() == T.WORD || t.get(j).type() == T.QUOTED)) {
        return null;
      }
      j++;
      if (j < t.size() && t.get(j).type() == T.LPAREN) {
        j = skip(t, j);
        if (j < 0) {
          return null;
        }
      }
      if (!word(t, j, "AS")) {
        return null;
      }
      j++;
      if (j >= t.size() || t.get(j).type() != T.LPAREN) {
        return null;
      }
      j = skip(t, j);
      if (j < 0 || j >= t.size()) {
        return null;
      }
      if (t.get(j).type() == T.COMMA) {
        j++;
        continue;
      }
      return t.get(j).type() == T.LPAREN ? "(" : t.get(j).type() == T.WORD ? t.get(j).value() : null;
    }
  }

  // ── Changes ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** One INSERT, UPDATE, DELETE, MERGE, TRUNCATE, COMMENT ON or DDL statement on data objects. */
  static Verdict change(String sql) {
    Statement s = statement(sql);
    if (s.problem() != null) {
      return Verdict.refused(s.problem());
    }
    List<Token> t = s.tokens();
    if (t.getFirst().type() != T.WORD) {
      return Verdict.refused("GateRook can't read this statement: a change starts with INSERT, UPDATE, DELETE, "
          + "MERGE, TRUNCATE, COMMENT, CREATE, ALTER or DROP.");
    }
    String why = refusedEverywhere(t, 0);
    if (why != null) {
      return Verdict.refused(why);
    }
    for (int k = 0; k + 1 < t.size(); k++) {
      if (t.get(k).type() == T.WORD && t.get(k + 1).type() == T.LPAREN && OUTSIDE.contains(t.get(k).value())
          && (k == 0 || t.get(k - 1).type() != T.DOT)) {
        return Verdict.refused(t.get(k).value() + " reaches outside the database (the server's files, other "
            + "databases or sessions): GateRook never runs it.");
      }
    }
    String first = t.getFirst().value();
    return switch (first) {
      case "INSERT" -> target(s, "INSERT", t, word(t, 1, "INTO") ? 2 : 1);
      case "UPDATE" -> target(s, "UPDATE", t, 1);
      case "DELETE" -> target(s, "DELETE", t, word(t, 1, "FROM") ? 2 : 1);
      case "MERGE" -> target(s, "MERGE", t, word(t, 1, "INTO") ? 2 : 1);
      case "TRUNCATE" -> target(s, "TRUNCATE TABLE", t, word(t, 1, "TABLE") ? 2 : 1);
      case "COMMENT" -> word(t, 1, "ON") && t.size() > 3 && t.get(2).type() == T.WORD
          ? target(s, "COMMENT ON " + t.get(2).value(), t, 3)
          : Verdict.refused("COMMENT is COMMENT ON <object> <name> IS '…'.");
      case "CREATE", "ALTER", "DROP" -> ddl(s, t, first);
      case "SELECT", "WITH", "VALUES", "TABLE", "EXPLAIN", "SHOW" -> Verdict.refused(first + " only reads: run it "
          + "with h2_query.");
      default -> Verdict.refused(first + " isn't a change GateRook runs: h2_execute runs INSERT, UPDATE, DELETE, "
          + "MERGE, TRUNCATE, COMMENT ON and CREATE, ALTER or DROP of tables, views, indexes, sequences, schemas, "
          + "domains, constants and synonyms.");
    };
  }

  private static Verdict ddl(Statement s, List<Token> t, String first) {
    int i = 1;
    // CREATE [OR REPLACE] [FORCE] [UNIQUE|SPATIAL|HASH] [[GLOBAL|LOCAL] TEMPORARY|MEMORY|CACHED] object
    while (i < t.size() && t.get(i).type() == T.WORD && Set.of("OR", "REPLACE", "FORCE", "UNIQUE", "SPATIAL",
        "HASH", "NULLS", "DISTINCT", "GLOBAL", "LOCAL", "TEMPORARY", "TEMP", "MEMORY", "CACHED", "NOT", "PERSISTENT")
        .contains(t.get(i).value())) {
      i++;
    }
    if (i >= t.size() || t.get(i).type() != T.WORD) {
      return Verdict.refused("GateRook can't tell what " + first + " changes here.");
    }
    String object = t.get(i).value();
    if (object.equals("LINKED")) {
      return Verdict.refused("A linked table reaches another database: GateRook never creates one.");
    }
    if (!CHANGE_OBJECTS.contains(object)) {
      return Verdict.refused(first + " " + object + " isn't a change GateRook runs: h2_execute changes tables, "
          + "views, indexes, sequences, schemas, domains, constants and synonyms.");
    }
    for (Token tok : t) {
      if (tok.word("ENGINE")) {
        return Verdict.refused("A table engine is Java code H2 loads on the server: GateRook never names one.");
      }
    }
    if (object.equals("MATERIALIZED")) {
      if (!word(t, i + 1, "VIEW")) {
        return Verdict.refused("GateRook can't tell what " + first + " MATERIALIZED changes here.");
      }
      object = "MATERIALIZED VIEW";
      i++;
    }
    int j = i + 1;
    while (j < t.size() && t.get(j).type() == T.WORD && Set.of("IF", "NOT", "EXISTS").contains(t.get(j).value())) {
      j++;
    }
    String verb = first + " " + object;
    if (object.equals("INDEX") && first.equals("CREATE")) {
      // CREATE INDEX [name] ON table: the table is what it changes.
      int on = j;
      while (on < t.size() && !t.get(on).word("ON")) {
        on++;
      }
      if (on < t.size()) {
        return target(s, verb, t, on + 1);
      }
    }
    return target(s, verb, t, j);
  }

  /** {@code verb} on the possibly qualified name that starts at {@code j}. */
  private static Verdict target(Statement s, String verb, List<Token> t, int j) {
    String name = name(t, j);
    return new Verdict(Kind.CHANGE, null, verb, s.text(), name);
  }

  /**
   * Why the statement at {@code i} is refused for reads and changes alike, or {@code null}: what reaches outside the
   * database, runs Java code, changes who may do what, or controls the session or transaction.
   */
  private static String refusedEverywhere(List<Token> t, int i) {
    String first = t.get(i).value();
    String second = i + 1 < t.size() && t.get(i + 1).type() == T.WORD ? t.get(i + 1).value() : "";
    return switch (first) {
      case "CALL" -> "CALL runs a function or procedure GateRook can't see into: select the expression instead.";
      case "SET" -> "SET changes the session or the database's settings: GateRook never runs it.";
      case "SCRIPT", "RUNSCRIPT", "BACKUP" -> first + " reads or writes files on the server: GateRook never runs it.";
      case "SHUTDOWN" -> "SHUTDOWN closes the database for everyone: GateRook never runs it.";
      case "CHECKPOINT", "ANALYZE" -> first + " is the database's maintenance: GateRook never runs it.";
      case "COMMIT", "ROLLBACK", "BEGIN", "START", "SAVEPOINT", "RELEASE", "PREPARE" -> "Transactions are "
          + "GateRook's: each call runs in its own.";
      case "GRANT", "REVOKE" -> "Rights are the person's to change: GateRook never runs " + first + ".";
      case "EXECUTE" -> "EXECUTE IMMEDIATE runs SQL from text GateRook can't read: write the statement itself.";
      case "HELP" -> "HELP isn't a query: use h2_list_tables and h2_describe_table.";
      case "CREATE", "ALTER", "DROP" -> switch (second) {
        case "USER", "ROLE" -> "Users and roles are the person's to change: GateRook never runs " + first + " "
            + second + ".";
        case "ALIAS", "TRIGGER", "AGGREGATE" -> first.equals("DROP") ? null : second + " runs Java code on the "
            + "server: GateRook never creates one.";
        default -> null;
      };
      default -> null;
    };
  }

  // ── Statements and tokens ───────────────────────────────────────────────────────────────────────────────────

  /** One statement: its tokens, its text without a trailing {@code ;}, or why it can't be read. */
  record Statement(List<Token> tokens, String text, String problem) {}

  static Statement statement(String sql) {
    if (sql == null || sql.isBlank()) {
      return new Statement(List.of(), "", "The statement is empty.");
    }
    if (sql.length() > MAX_LENGTH) {
      return new Statement(List.of(), "", "The statement is longer than " + MAX_LENGTH + " characters.");
    }
    Lexed lexed = lex(sql);
    if (lexed.problem() != null) {
      return new Statement(List.of(), "", lexed.problem());
    }
    List<Token> all = lexed.tokens();
    int semis = 0;
    int last = all.size();
    for (int k = 0; k < all.size(); k++) {
      if (all.get(k).type() == T.SEMI) {
        semis++;
        if (k != all.size() - 1) {
          return new Statement(List.of(), "", "One statement per call: this text holds more than one.");
        }
        last = k;
      }
    }
    List<Token> tokens = List.copyOf(all.subList(0, last));
    if (tokens.isEmpty()) {
      return new Statement(List.of(), "", "The statement is empty.");
    }
    if (semis > 1) {
      return new Statement(List.of(), "", "One statement per call: this text holds more than one.");
    }
    int depth = 0;
    for (Token tok : tokens) {
      if (tok.type() == T.LPAREN || tok.type() == T.LBRACKET) {
        depth++;
      } else if (tok.type() == T.RPAREN || tok.type() == T.RBRACKET) {
        depth--;
        if (depth < 0) {
          return new Statement(List.of(), "", "The parentheses of this statement don't balance.");
        }
      }
    }
    if (depth != 0) {
      return new Statement(List.of(), "", "The parentheses of this statement don't balance.");
    }
    String text = sql.substring(0, tokens.getLast().end()).strip();
    int lead = sql.indexOf(text);
    return new Statement(tokens, lead >= 0 ? text : sql.strip(), null);
  }

  record Lexed(List<Token> tokens, String problem) {}

  static Lexed lex(String sql) {
    List<Token> out = new ArrayList<>();
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-' || c == '/' && i + 1 < n && sql.charAt(i + 1) == '/') {
        int end = sql.indexOf('\n', i);
        i = end < 0 ? n : end + 1;
        continue;
      }
      if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) {
          return new Lexed(List.of(), "A comment of this statement isn't closed.");
        }
        if (sql.substring(i + 2, end).contains("/*")) {
          return new Lexed(List.of(), "GateRook can't read a comment inside a comment.");
        }
        i = end + 2;
        continue;
      }
      if (c == '\'') {
        int end = quoted(sql, i, '\'');
        if (end < 0) {
          return new Lexed(List.of(), "A string of this statement isn't closed.");
        }
        out.add(new Token(T.STRING, sql.substring(i + 1, end - 1).replace("''", "'"), i, end));
        i = end;
        continue;
      }
      if (c == '"') {
        int end = quoted(sql, i, '"');
        if (end < 0) {
          return new Lexed(List.of(), "A quoted name of this statement isn't closed.");
        }
        out.add(new Token(T.QUOTED, sql.substring(i + 1, end - 1).replace("\"\"", "\""), i, end));
        i = end;
        continue;
      }
      if (c == '$' && i + 1 < n && sql.charAt(i + 1) == '$') {
        int end = sql.indexOf("$$", i + 2);
        if (end < 0) {
          return new Lexed(List.of(), "A $$ string of this statement isn't closed.");
        }
        out.add(new Token(T.STRING, sql.substring(i + 2, end), i, end + 2));
        i = end + 2;
        continue;
      }
      if ((c == 'N' || c == 'n' || c == 'X' || c == 'x') && i + 1 < n && sql.charAt(i + 1) == '\'') {
        int end = quoted(sql, i + 1, '\'');
        if (end < 0) {
          return new Lexed(List.of(), "A string of this statement isn't closed.");
        }
        out.add(new Token(T.STRING, sql.substring(i + 2, end - 1).replace("''", "'"), i, end));
        i = end;
        continue;
      }
      if ((c == 'U' || c == 'u') && i + 2 < n && sql.charAt(i + 1) == '&' && (sql.charAt(i + 2) == '\''
          || sql.charAt(i + 2) == '"')) {
        char q = sql.charAt(i + 2);
        int end = quoted(sql, i + 2, q);
        if (end < 0) {
          return new Lexed(List.of(), "A string of this statement isn't closed.");
        }
        out.add(new Token(q == '\'' ? T.STRING : T.QUOTED, sql.substring(i + 3, end - 1), i, end));
        i = end;
        continue;
      }
      if (identStart(c)) {
        int j = i + 1;
        while (j < n && identPart(sql.charAt(j))) {
          j++;
        }
        out.add(new Token(T.WORD, sql.substring(i, j).toUpperCase(Locale.ROOT), i, j));
        i = j;
        continue;
      }
      if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
        int j = number(sql, i);
        if (j < n && identStart(sql.charAt(j))) {
          return new Lexed(List.of(), "GateRook can't read a number with letters stuck to it.");
        }
        out.add(new Token(T.NUMBER, sql.substring(i, j), i, j));
        i = j;
        continue;
      }
      if (c == '?') {
        int j = i + 1;
        while (j < n && Character.isDigit(sql.charAt(j))) {
          j++;
        }
        out.add(new Token(T.PARAM, sql.substring(i, j), i, j));
        i = j;
        continue;
      }
      switch (c) {
        case '(' -> out.add(new Token(T.LPAREN, "(", i, i + 1));
        case ')' -> out.add(new Token(T.RPAREN, ")", i, i + 1));
        case '[' -> out.add(new Token(T.LBRACKET, "[", i, i + 1));
        case ']' -> out.add(new Token(T.RBRACKET, "]", i, i + 1));
        case ',' -> out.add(new Token(T.COMMA, ",", i, i + 1));
        case ';' -> out.add(new Token(T.SEMI, ";", i, i + 1));
        case '.' -> out.add(new Token(T.DOT, ".", i, i + 1));
        default -> {
          String op = operator(sql, i);
          if (op == null) {
            return new Lexed(List.of(), (c == '{' || c == '}' ? "GateRook doesn't run JDBC escapes ({…}): write "
                : "GateRook can't read the character " + printable(c) + " here: write ") + "the statement in H2's SQL.");
          }
          out.add(new Token(T.OP, op, i, i + op.length()));
          i += op.length();
          continue;
        }
      }
      i++;
    }
    return new Lexed(List.copyOf(out), null);
  }

  private static final List<String> OPERATORS = List.of("::", "||", "<=", ">=", "<>", "!=", "&&", "=", "<", ">",
      "+", "-", "*", "/", "%", "~", "&", "|", "^", ":");

  private static String operator(String sql, int i) {
    for (String op : OPERATORS) {
      if (sql.startsWith(op, i)) {
        // ':' alone only after a word (JSON_OBJECT(KEY 'a' : 1) style): := and named parameters aren't read here.
        if (op.equals(":") && i + 1 < sql.length() && sql.charAt(i + 1) == '=') {
          return null;
        }
        return op;
      }
    }
    return null;
  }

  private static String printable(char c) {
    return Character.isISOControl(c) ? String.format(Locale.ROOT, "U+%04X", (int) c) : "'" + c + "'";
  }

  /** The end (exclusive) of the quoted text that starts at {@code i}, doubled quotes inside, or -1. */
  private static int quoted(String sql, int i, char quote) {
    int j = i + 1;
    while (j < sql.length()) {
      if (sql.charAt(j) == quote) {
        if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    return -1;
  }

  private static int number(String sql, int i) {
    int j = i;
    int n = sql.length();
    if (sql.startsWith("0x", j) || sql.startsWith("0X", j)) {
      j += 2;
      while (j < n && Character.digit(sql.charAt(j), 16) >= 0) {
        j++;
      }
      return j;
    }
    while (j < n && (Character.isDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
      j++;
    }
    if (j < n && sql.charAt(j) == '.') {
      j++;
      while (j < n && Character.isDigit(sql.charAt(j))) {
        j++;
      }
    }
    if (j < n && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
      int k = j + 1;
      if (k < n && (sql.charAt(k) == '+' || sql.charAt(k) == '-')) {
        k++;
      }
      if (k < n && Character.isDigit(sql.charAt(k))) {
        j = k;
        while (j < n && Character.isDigit(sql.charAt(j))) {
          j++;
        }
      }
    }
    return j;
  }

  private static boolean identStart(char c) {
    return c == '_' || Character.isLetter(c);
  }

  private static boolean identPart(char c) {
    return c == '_' || c == '$' || Character.isLetterOrDigit(c);
  }

  /** The index after the bracket that closes the one at {@code open}, or -1. */
  static int skip(List<Token> t, int open) {
    int depth = 0;
    for (int k = open; k < t.size(); k++) {
      T type = t.get(k).type();
      if (type == T.LPAREN || type == T.LBRACKET) {
        depth++;
      } else if (type == T.RPAREN || type == T.RBRACKET) {
        depth--;
        if (depth == 0) {
          return k + 1;
        }
      }
    }
    return -1;
  }

  /** A possibly qualified name ({@code schema.table}) starting at {@code j}, as written, or {@code null}. */
  static String name(List<Token> t, int j) {
    StringBuilder b = new StringBuilder();
    int k = j;
    while (k < t.size() && (t.get(k).type() == T.WORD || t.get(k).type() == T.QUOTED)) {
      Token tok = t.get(k);
      b.append(tok.type() == T.QUOTED ? "\"" + tok.value().replace("\"", "\"\"") + "\"" : tok.value());
      if (k + 1 < t.size() && t.get(k + 1).type() == T.DOT) {
        b.append('.');
        k += 2;
      } else {
        break;
      }
    }
    return b.isEmpty() ? null : b.toString();
  }

  private static String text(List<Token> t, int k) {
    String n = name(t, Math.max(0, k - 2));
    return n == null ? t.get(k).value() : n;
  }

  private static boolean word(List<Token> t, int j, String w) {
    return j >= 0 && j < t.size() && t.get(j).word(w);
  }
}
