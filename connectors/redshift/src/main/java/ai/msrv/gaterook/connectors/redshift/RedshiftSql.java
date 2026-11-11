package ai.msrv.gaterook.connectors.redshift;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one Redshift statement is, without I/O: the gate before Redshift's own answer. The text is read by the tokens of
 * Redshift's PostgreSQL-derived SQL: {@code '…'} strings with doubled quotes, {@code "…"} names with doubled quotes,
 * {@code --} line comments and block comments, so a semicolon or a keyword inside a string, a name or a comment is
 * neither. Whatever this can't read with certainty is {@link Kind#UNREADABLE}, and a call refuses it: an open quote or
 * comment, a comment inside a comment (readers disagree on nesting), a backslash in a string (Redshift may read it as
 * an escape, which would move where the string ends), a dollar-quoted body and a control character.
 *
 * <p>Only {@code SELECT}, {@code WITH}, {@code SHOW} and {@code EXPLAIN} of a read are reads. Access control, data
 * movement to and from S3, procedures, transaction and session control, external and shared objects and functions that
 * stop other sessions or change settings are {@link Kind#FORBIDDEN}. This only picks the route: a read then runs in a
 * READ ONLY transaction that Redshift enforces ({@link RedshiftConnector}).
 */
final class RedshiftSql {

  /** The route of a statement, in increasing order of what it may do. */
  enum Kind { READ, CHANGE, FORBIDDEN, UNREADABLE }

  /**
   * One call's text.
   *
   * @param type        what it is, upper case: {@code SELECT}, {@code UPDATE}, {@code CREATE TABLE}; a description
   *                    when it can't be read
   * @param target      for a change, the object it names after its keywords, as written; or {@code null}
   * @param explainable whether Redshift's {@code EXPLAIN} plans it ({@code INSERT}, {@code UPDATE}, {@code DELETE},
   *                    {@code CREATE TABLE … AS})
   * @param problem     why it is FORBIDDEN or UNREADABLE, for the agent; {@code null} otherwise
   * @param parameters  the {@code :name} placeholders of the code, in order of first use
   */
  record Statement(Kind kind, String type, String target, boolean explainable, String problem,
      Set<String> parameters) {

    static Statement refused(Kind kind, String type, String problem) {
      return new Statement(kind, type, null, false, problem, Set.of());
    }
  }

  /** The Data API takes statements of up to 200 KB; the tools take up to this many characters. */
  static final int MAX_LENGTH = 100_000;

  private static final Set<String> READS = Set.of("SELECT", "WITH", "SHOW", "EXPLAIN");
  private static final Set<String> CHANGES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "CREATE",
      "ALTER", "DROP", "COMMENT", "VACUUM", "ANALYZE", "REFRESH");
  /** Statements that move data to or from S3, set access, run procedures or control the transaction or session. */
  private static final Set<String> FORBIDDEN = Set.of("GRANT", "REVOKE", "COPY", "UNLOAD", "CALL", "BEGIN", "START",
      "COMMIT", "END", "ROLLBACK", "ABORT", "SAVEPOINT", "RELEASE", "PREPARE", "EXECUTE", "DEALLOCATE", "DECLARE",
      "FETCH", "CLOSE", "LOCK", "SET", "RESET", "USE", "CANCEL", "ATTACH", "DETACH");
  /**
   * Objects of CREATE, ALTER and DROP that stay with people: users, roles and groups, databases, datashares (data
   * leaves the account), external objects and models (S3, Lambda, other databases, SageMaker), libraries, identity
   * providers, row-level security and masking policies, default privileges, functions and procedures.
   */
  private static final Set<String> FORBIDDEN_OBJECTS = Set.of("USER", "ROLE", "GROUP", "DATABASE", "DATASHARE",
      "EXTERNAL", "MODEL", "LIBRARY", "IDENTITY", "PROVIDER", "RLS", "MASKING", "POLICY", "DEFAULT", "SYSTEM",
      "FUNCTION", "PROCEDURE", "TEMPLATE", "INTEGRATION");
  /** Words a read never holds: SELECT … INTO creates a table, the rest are writes. */
  private static final Set<String> WRITES_IN_READ = Set.of("INTO", "INSERT", "UPDATE", "DELETE", "MERGE");
  /** Functions that stop other sessions, change their priority or change settings: never through GateRook. */
  private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of("pg_terminate_backend", "pg_cancel_backend",
      "change_query_priority", "change_session_priority", "change_user_priority", "set_config");
  /** Words between CREATE, ALTER or DROP and the kind of object. */
  private static final Set<String> MODIFIERS = Set.of("OR", "REPLACE", "TEMP", "TEMPORARY", "LOCAL");
  /** Words between a change's keyword and the object it acts on. */
  private static final Set<String> NOISE = Set.of("INTO", "FROM", "TABLE", "VIEW", "MATERIALIZED", "SCHEMA", "OR",
      "REPLACE", "IF", "NOT", "EXISTS", "TEMP", "TEMPORARY", "LOCAL", "ONLY", "ON", "COLUMN", "CONSTRAINT", "FULL",
      "SORT", "DELETE", "REINDEX", "RECLUSTER", "BOOST", "VERBOSE", "COMPRESSION", "PREDICATE", "COLUMNS");

  private RedshiftSql() {}

  /**
   * One token of the code: a word, a quoted name or one punctuation character.
   *
   * @param text  as written
   * @param upper a word upper-cased, for keyword checks; {@code text} otherwise
   */
  record Token(String text, String upper, boolean word, boolean quoted) {}

  /**
   * The code: literals and comments as spaces, quoted names kept as written; the count of top-level statements; the
   * {@code :name} placeholders; or why the text can't be read.
   */
  record Scan(String code, int statements, Set<String> parameters, String problem) {}

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
    List<Token> tokens = tokens(scan.code());
    List<String> words = new ArrayList<>();
    for (Token t : tokens) {
      if (t.word()) {
        words.add(t.upper());
      }
    }
    String first = firstKeyword(tokens);
    if (first == null) {
      return Statement.refused(Kind.UNREADABLE, "statement", "The statement has no keyword.");
    }
    String function = forbiddenFunction(tokens);
    if (function != null) {
      return Statement.refused(Kind.FORBIDDEN, first, function + " stops other sessions, changes their priority or "
          + "changes settings: GateRook doesn't run it.");
    }
    if (READS.contains(first)) {
      return readStatement(first, words, scan);
    }
    if (FORBIDDEN.contains(first)) {
      return Statement.refused(Kind.FORBIDDEN, first, first + " isn't allowed through GateRook: access control, "
          + "data movement to and from S3 (COPY, UNLOAD), procedures (CALL), transaction and session control stay "
          + "with people.");
    }
    if (CHANGES.contains(first)) {
      return change(first, words, tokens, scan);
    }
    return Statement.refused(Kind.UNREADABLE, first, first + " isn't a statement GateRook runs on Redshift.");
  }

  private static Statement readStatement(String first, List<String> words, Scan scan) {
    if (first.equals("EXPLAIN")) {
      int k = words.indexOf("EXPLAIN") + 1;
      if (k < words.size() && words.get(k).equals("VERBOSE")) {
        k++;
      }
      if (k < words.size() && words.get(k).equals("ANALYZE")) {
        return Statement.refused(Kind.FORBIDDEN, "EXPLAIN", "EXPLAIN ANALYZE runs the statement: GateRook only "
            + "explains reads.");
      }
      String explained = k < words.size() ? words.get(k) : "";
      if (!explained.equals("SELECT") && !explained.equals("WITH")) {
        return Statement.refused(Kind.FORBIDDEN, "EXPLAIN", "GateRook explains only SELECT and WITH here; "
            + "redshift_execute shows the plan of a change in its approval.");
      }
    }
    if (!first.equals("SHOW")) {
      for (String w : words) {
        if (WRITES_IN_READ.contains(w)) {
          return Statement.refused(Kind.FORBIDDEN, first, w.equals("INTO")
              ? "SELECT … INTO creates a table. Use CREATE TABLE … AS SELECT with redshift_execute."
              : "This statement holds " + w + ", so it isn't only a read. Send a change on its own, starting with "
                  + "its verb, to redshift_execute.");
        }
      }
    }
    return new Statement(Kind.READ, first, null, false, null, scan.parameters());
  }

  private static Statement change(String first, List<String> words, List<Token> tokens, Scan scan) {
    String type = first;
    if (first.equals("CREATE") || first.equals("ALTER") || first.equals("DROP")) {
      int k = 1;
      while (k < words.size() && MODIFIERS.contains(words.get(k))) {
        k++;
      }
      String object = k < words.size() ? words.get(k) : "";
      if (object.equals("MATERIALIZED") && k + 1 < words.size()) {
        object = "MATERIALIZED " + words.get(k + 1);
      }
      if (FORBIDDEN_OBJECTS.contains(object)) {
        return Statement.refused(Kind.FORBIDDEN, first + " " + object, first + " " + object + " isn't allowed "
            + "through GateRook: users, roles, databases, datashares, external objects, models, libraries, security "
            + "policies, functions and procedures stay with people.");
      }
      type = object.isEmpty() ? first : first + " " + object;
      String clause = first.equals("DROP") ? null : forbiddenClause(tokens);
      if (clause != null) {
        return Statement.refused(Kind.FORBIDDEN, type, clause + " in " + first + " isn't allowed through GateRook: "
            + "ownership, authorization, security policies and storage locations stay with people.");
      }
    }
    boolean explainable = first.equals("INSERT") || first.equals("UPDATE") || first.equals("DELETE")
        || (type.equals("CREATE TABLE") && createsFromQuery(words));
    return new Statement(Kind.CHANGE, type, target(first, tokens), explainable, null, scan.parameters());
  }

  /**
   * The clause of an ALTER or CREATE that hands access or storage to someone, or {@code null}: {@code OWNER TO},
   * {@code AUTHORIZATION}, {@code ROW LEVEL SECURITY}, and {@code LOCATION} after {@code SET} or before a string
   * (an external table's S3 location). A column named {@code owner} or {@code location} is none of these.
   */
  private static String forbiddenClause(List<Token> tokens) {
    for (int j = 0; j < tokens.size(); j++) {
      Token t = tokens.get(j);
      if (!t.word()) {
        continue;
      }
      String next = j + 1 < tokens.size() ? tokens.get(j + 1).upper() : "";
      String previous = j > 0 ? tokens.get(j - 1).upper() : "";
      switch (t.upper()) {
        case "OWNER" -> {
          if (next.equals("TO")) {
            return "OWNER TO";
          }
        }
        case "AUTHORIZATION" -> {
          return "AUTHORIZATION";
        }
        case "LOCATION" -> {
          if (previous.equals("SET") || next.equals("'")) {
            return "LOCATION";
          }
        }
        case "ROW" -> {
          if (next.equals("LEVEL") && j + 2 < tokens.size() && tokens.get(j + 2).upper().equals("SECURITY")) {
            return "ROW LEVEL SECURITY";
          }
        }
        default -> {
          // Not a clause that hands anything over.
        }
      }
    }
    return null;
  }

  /** {@code CREATE TABLE … AS SELECT}: the {@code AS} of a query, not of a column's default or an identity. */
  private static boolean createsFromQuery(List<String> words) {
    for (int k = 2; k + 1 < words.size(); k++) {
      if (words.get(k).equals("AS") && (words.get(k + 1).equals("SELECT") || words.get(k + 1).equals("WITH"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The first name after a change's keywords, as written ({@code sales.orders}, {@code "Sales"."Orders"}); the
   * object of {@code COMMENT ON TABLE …}; {@code null} without one.
   */
  static String target(String first, List<Token> tokens) {
    int k = 0;
    while (k < tokens.size() && !tokens.get(k).word()) {
      k++;
    }
    k++;
    if (first.equals("COMMENT")) {
      // COMMENT ON TABLE t IS '…', COMMENT ON COLUMN t.c IS '…': the name after the object's kind.
      if (k + 1 < tokens.size() && tokens.get(k).upper().equals("ON")) {
        k += 2;
      } else {
        return null;
      }
    } else {
      while (k < tokens.size() && tokens.get(k).word() && NOISE.contains(tokens.get(k).upper())) {
        k++;
      }
    }
    if (k >= tokens.size() || !(tokens.get(k).word() || tokens.get(k).quoted())) {
      return null;
    }
    StringBuilder name = new StringBuilder(tokens.get(k).text());
    while (k + 2 < tokens.size() && tokens.get(k + 1).text().equals(".")
        && (tokens.get(k + 2).word() || tokens.get(k + 2).quoted())) {
      name.append('.').append(tokens.get(k + 2).text());
      k += 2;
    }
    return name.toString();
  }

  /** The first keyword after opening parentheses, upper case; {@code null} without one. */
  private static String firstKeyword(List<Token> tokens) {
    for (Token t : tokens) {
      if (t.word()) {
        return t.upper();
      }
      if (!t.text().equals("(")) {
        return null;
      }
    }
    return null;
  }

  /** The first forbidden function the code calls, bare, qualified or quoted ({@code "set_config"(…)}); or null. */
  private static String forbiddenFunction(List<Token> tokens) {
    for (int k = 0; k + 1 < tokens.size(); k++) {
      Token t = tokens.get(k);
      if (!tokens.get(k + 1).text().equals("(") || !(t.word() || t.quoted())) {
        continue;
      }
      String name = t.quoted() ? t.text().substring(1, t.text().length() - 1).replace("\"\"", "\"") : t.text();
      if (FORBIDDEN_FUNCTIONS.contains(name.toLowerCase(Locale.ROOT))) {
        return name.toLowerCase(Locale.ROOT);
      }
    }
    return null;
  }

  /** Reads the text by Redshift's tokens. */
  static Scan scan(String sql) {
    StringBuilder code = new StringBuilder(sql.length());
    Set<String> parameters = new LinkedHashSet<>();
    int n = sql.length();
    int statements = 0;
    boolean pending = false;
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F) {
        return new Scan(code.toString(), statements, parameters, "A control character isn't analyzed.");
      }
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
          i++;
        }
        code.append(' ');
        continue;
      }
      if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int close = sql.indexOf("*/", i + 2);
        if (close < 0) {
          return new Scan(code.toString(), statements, parameters, "A comment isn't closed.");
        }
        if (sql.substring(i + 2, close).contains("/*")) {
          return new Scan(code.toString(), statements, parameters, "A comment inside a comment isn't analyzed.");
        }
        i = close + 2;
        code.append(' ');
        continue;
      }
      if (c == '\'') {
        int j = i + 1;
        int end = -1;
        while (j < n) {
          char d = sql.charAt(j);
          if (d == '\\') {
            return new Scan(code.toString(), statements, parameters, "A backslash in a string isn't analyzed: Redshift "
                + "may read it as an escape. Write the character itself, and double a quote ('') inside a string.");
          }
          if (d == '\'') {
            if (j + 1 < n && sql.charAt(j + 1) == '\'') {
              j += 2;
              continue;
            }
            end = j + 1;
            break;
          }
          j++;
        }
        if (end < 0) {
          return new Scan(code.toString(), statements, parameters, "A string isn't closed.");
        }
        code.append(" '' ");
        pending = true;
        i = end;
        continue;
      }
      if (c == '"') {
        int j = i + 1;
        int end = -1;
        while (j < n) {
          if (sql.charAt(j) == '"') {
            if (j + 1 < n && sql.charAt(j + 1) == '"') {
              j += 2;
              continue;
            }
            end = j + 1;
            break;
          }
          j++;
        }
        if (end < 0) {
          return new Scan(code.toString(), statements, parameters, "A quoted name isn't closed.");
        }
        code.append(sql, i, end);
        pending = true;
        i = end;
        continue;
      }
      if (c == '$' && (i == 0 || !identifierPart(sql.charAt(i - 1))) && dollarTag(sql, i) > 0) {
        return new Scan(code.toString(), statements, parameters, "A dollar-quoted body isn't analyzed: functions and "
            + "procedures stay with people.");
      }
      if (c == ':' && i + 1 < n && (i == 0 || sql.charAt(i - 1) != ':') && sql.charAt(i + 1) != ':'
          && parameterPart(sql.charAt(i + 1))) {
        int j = i + 1;
        while (j < n && parameterPart(sql.charAt(j))) {
          j++;
        }
        parameters.add(sql.substring(i + 1, j));
      }
      if (c == ';') {
        if (pending) {
          statements++;
          pending = false;
        }
        code.append(' ');
        i++;
        continue;
      }
      if (!Character.isWhitespace(c)) {
        pending = true;
      }
      code.append(c);
      i++;
    }
    if (pending) {
      statements++;
    }
    return new Scan(code.toString(), statements, parameters, null);
  }

  /** {@code $$} or {@code $tag$}: the index after its closing {@code $}, or -1 if {@code i} starts no dollar quote. */
  private static int dollarTag(String sql, int i) {
    int j = i + 1;
    if (j < sql.length() && sql.charAt(j) == '$') {
      return j + 1;
    }
    if (j >= sql.length() || !(Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) {
      return -1;
    }
    while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
      j++;
    }
    return j < sql.length() && sql.charAt(j) == '$' ? j + 1 : -1;
  }

  private static boolean identifierPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }

  /** The Data API's parameter names: {@code [0-9a-zA-Z_]+}. */
  private static boolean parameterPart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  /** The tokens of the code: words upper-cased, quoted names as written, punctuation one character at a time. */
  static List<Token> tokens(String code) {
    List<Token> out = new ArrayList<>();
    int i = 0;
    while (i < code.length()) {
      char c = code.charAt(i);
      if (c == '"') {
        int j = i + 1;
        while (j < code.length()) {
          if (code.charAt(j) == '"') {
            if (j + 1 < code.length() && code.charAt(j + 1) == '"') {
              j += 2;
              continue;
            }
            break;
          }
          j++;
        }
        String quoted = code.substring(i, Math.min(j + 1, code.length()));
        out.add(new Token(quoted, quoted, false, true));
        i = j + 1;
      } else if (Character.isLetter(c) || c == '_') {
        int j = i;
        while (j < code.length() && identifierPart(code.charAt(j))) {
          j++;
        }
        String word = code.substring(i, j);
        out.add(new Token(word, word.toUpperCase(Locale.ROOT), true, false));
        i = j;
      } else if (Character.isWhitespace(c)) {
        i++;
      } else {
        out.add(new Token(String.valueOf(c), String.valueOf(c), false, false));
        i++;
      }
    }
    return out;
  }
}
