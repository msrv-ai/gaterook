package com.gaterook.connectors.spanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one Spanner statement is, without I/O: the route a call takes. Both dialects are read by their tokens at once
 * — {@code --} and {@code #} line comments, block comments, {@code '…'} strings, {@code "…"} strings (GoogleSQL) or
 * names (PostgreSQL) with doubled quotes or backslash escapes, backtick names, PostgreSQL's dollar quotes ({@code $$…$$},
 * {@code $tag$…$tag$}) — so a semicolon or a keyword inside a string or a comment is neither. A leading statement hint
 * ({@code @{…}}) is skipped. Whatever this can't read with certainty (an open quote, comment or hint, a nested comment)
 * is {@link Kind#UNREADABLE}, and a call refuses it.
 *
 * <p>This only picks the route. A read runs in a read-only transaction, in which Spanner refuses DML, and DDL never
 * runs through a query at all ({@link SpannerConnector}); a change shows its exact text to the person who approves it.
 */
final class SpannerSql {

  /** The route of a statement, in increasing order of what it may do. */
  enum Kind { READ, DML, DDL, FORBIDDEN, UNREADABLE }

  /**
   * One call's text.
   *
   * @param type    the first keyword, upper case ({@code SELECT}, {@code UPDATE}, {@code CREATE}), or a description
   * @param target  for DML and DDL, the table the statement names after its keywords, as written; or {@code null}
   * @param problem why it is FORBIDDEN or UNREADABLE, for the agent; {@code null} otherwise
   */
  record Statement(Kind kind, String type, String target, String problem) {

    Statement(Kind kind, String type, String problem) {
      this(kind, type, null, problem);
    }
  }

  static final int MAX_LENGTH = 100_000;
  /** Queries: GoogleSQL's pipe syntax starts with FROM, Spanner Graph's with GRAPH, PostgreSQL's TABLE t is one. */
  private static final Set<String> READ = Set.of("SELECT", "WITH", "FROM", "GRAPH", "VALUES", "TABLE");
  private static final Set<String> DML = Set.of("INSERT", "UPDATE", "DELETE");
  private static final Set<String> DDL = Set.of("CREATE", "ALTER", "DROP", "RENAME", "ANALYZE");
  /** Transactions, session settings, procedures and access control: never through GateRook. */
  private static final Set<String> FORBIDDEN = Set.of("CALL", "GRANT", "REVOKE", "BEGIN", "START", "COMMIT",
      "ROLLBACK", "ABORT", "RUN", "SET", "RESET", "SHOW", "EXPLAIN", "DECLARE", "EXECUTE", "PREPARE", "DEALLOCATE");
  /** DDL objects that set access, the database's own options or models that call other services: with people. */
  private static final Set<String> FORBIDDEN_OBJECTS = Set.of("ROLE", "DATABASE", "MODEL", "PLACEMENT", "LOCALITY");
  /**
   * Functions that call Vertex AI from Spanner: what they cost and send isn't the database's. Read in the code without
   * quoted names or strings, so a name inside a string or a comment is none.
   */
  private static final Pattern OTHER_SERVICES = Pattern.compile("(?i)(?<![\\w.])(?:ML\\s*\\.\\s*PREDICT\\w*|"
      + "(?:SPANNER\\s*\\.\\s*)?ML_PREDICT_ROW|(?:SPANNER\\s*\\.\\s*)?ML_PREDICT)\\s*\\(");
  private static final Pattern QUOTED = Pattern.compile("\"(?:[^\"\\\\]|\\\\.|\"\")*\"|`[^`]*`");

  private SpannerSql() {}

  /**
   * Code (literals and comments as spaces; backtick and double-quoted tokens kept as written, as they may be names) and
   * the count of top-level statements.
   */
  record Scan(String code, int statements, String problem) {}

  static Statement read(String sql) {
    if (sql == null || sql.isBlank()) {
      return new Statement(Kind.UNREADABLE, "statement", "The statement is empty.");
    }
    if (sql.length() > MAX_LENGTH) {
      return new Statement(Kind.UNREADABLE, "statement", "The statement is longer than " + MAX_LENGTH
          + " characters.");
    }
    Scan scan = scan(sql);
    if (scan.problem() != null) {
      return new Statement(Kind.UNREADABLE, "statement", scan.problem());
    }
    if (scan.statements() > 1) {
      return new Statement(Kind.FORBIDDEN, "script", "One statement per call: this text has "
          + scan.statements() + ". Send them one by one.");
    }
    String code = skipHint(scan.code());
    if (code == null) {
      return new Statement(Kind.UNREADABLE, "statement", "A statement hint @{…} isn't closed.");
    }
    List<String> words = words(code);
    if (words.isEmpty()) {
      return new Statement(Kind.UNREADABLE, "statement", "The statement has no keyword.");
    }
    String first = words.getFirst().toUpperCase(Locale.ROOT);
    Matcher call = OTHER_SERVICES.matcher(QUOTED.matcher(code).replaceAll(" "));
    if (call.find()) {
      String name = call.group().replaceAll("[\\s(]", "").toUpperCase(Locale.ROOT);
      return new Statement(Kind.FORBIDDEN, first, name + " calls Vertex AI from Spanner: GateRook doesn't run it, as "
          + "what it costs and sends isn't the database's.");
    }
    // A parenthesized query, "(SELECT …) UNION ALL (SELECT …)", starts with its bracket.
    if (code.strip().startsWith("(") && !READ.contains(first)) {
      return new Statement(Kind.UNREADABLE, first, "A statement in brackets must be a query.");
    }
    if (READ.contains(first)) {
      return new Statement(Kind.READ, first, null);
    }
    if (DML.contains(first) || DDL.contains(first)) {
      for (int k = 1; k < words.size(); k++) {
        String w = words.get(k).toUpperCase(Locale.ROOT);
        if (DDL.contains(first) && FORBIDDEN_OBJECTS.contains(w)) {
          return new Statement(Kind.FORBIDDEN, first, first + " " + w + " isn't allowed through GateRook: roles, the "
              + "database's options, placements and models stay with people.");
        }
        if (!NOISE.contains(w)) {
          break;
        }
      }
      return new Statement(DML.contains(first) ? Kind.DML : Kind.DDL, first, target(words), null);
    }
    if (FORBIDDEN.contains(first)) {
      return new Statement(Kind.FORBIDDEN, first, first + " isn't allowed through GateRook: transactions, session "
          + "settings, procedures and access control stay with people.");
    }
    return new Statement(Kind.UNREADABLE, first, first + " isn't a statement GateRook runs on Spanner.");
  }

  /** Reads the text by the tokens of both dialects. */
  static Scan scan(String sql) {
    StringBuilder code = new StringBuilder(sql.length());
    int n = sql.length();
    int statements = 0;
    boolean pending = false;
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '#' || (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-')) {
        while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
          i++;
        }
        code.append(' ');
        continue;
      }
      if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        // PostgreSQL nests block comments and GoogleSQL doesn't: a nested opener is refused rather than guessed.
        int close = sql.indexOf("*/", i + 2);
        if (close < 0) {
          return new Scan(code.toString(), statements, "A comment isn't closed.");
        }
        if (sql.substring(i + 2, close).contains("/*")) {
          return new Scan(code.toString(), statements, "A comment inside a comment isn't analyzed.");
        }
        i = close + 2;
        code.append(' ');
        continue;
      }
      if (c == '`') {
        int j = i + 1;
        while (j < n && sql.charAt(j) != '`') {
          if (sql.charAt(j) == '\\' || sql.charAt(j) == '\n' || sql.charAt(j) == '\r') {
            return new Scan(code.toString(), statements, "A quoted name with a backslash or a line break isn't "
                + "analyzed.");
          }
          j++;
        }
        if (j >= n) {
          return new Scan(code.toString(), statements, "A quoted name isn't closed.");
        }
        code.append(sql, i, j + 1);
        pending = true;
        i = j + 1;
        continue;
      }
      String tag = dollarTag(sql, i);
      if (tag != null) {
        int close = sql.indexOf(tag, i + tag.length());
        if (close < 0) {
          return new Scan(code.toString(), statements, "A dollar-quoted string isn't closed.");
        }
        code.append(" ' ");
        pending = true;
        i = close + tag.length();
        continue;
      }
      if (c == '\'' || c == '"') {
        int end = endOfQuoted(sql, i);
        if (end < 0) {
          return new Scan(code.toString(), statements, "A string isn't closed.");
        }
        // A "…" token may be a PostgreSQL name: kept, so the statement's target reads as written.
        code.append(c == '"' ? sql.substring(i, end) : " ' ");
        pending = true;
        i = end;
        continue;
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
    return new Scan(code.toString(), statements, null);
  }

  /**
   * The end (exclusive) of the quoted token that starts at {@code i}: a doubled quote is one quote, a backslash escapes
   * the next character; {@code -1} when it isn't closed.
   */
  private static int endOfQuoted(String s, int i) {
    char q = s.charAt(i);
    int j = i + 1;
    while (j < s.length()) {
      char d = s.charAt(j);
      if (d == '\\') {
        j += 2;
        continue;
      }
      if (d == q) {
        if (j + 1 < s.length() && s.charAt(j + 1) == q) {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    return -1;
  }

  /** PostgreSQL's {@code $$} or {@code $tag$} opening a string at {@code i}, or {@code null} ({@code $1} is none). */
  private static String dollarTag(String s, int i) {
    if (s.charAt(i) != '$' || (i > 0 && isNamePart(s.charAt(i - 1)))) {
      return null;
    }
    int j = i + 1;
    if (j < s.length() && s.charAt(j) == '$') {
      return "$$";
    }
    if (j >= s.length() || !(Character.isLetter(s.charAt(j)) || s.charAt(j) == '_')) {
      return null;
    }
    while (j < s.length() && isNamePart(s.charAt(j))) {
      j++;
    }
    return j < s.length() && s.charAt(j) == '$' ? s.substring(i, j + 1) : null;
  }

  private static boolean isNamePart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /** The code after a leading statement hint {@code @{…}}, or {@code null} when the hint isn't closed. */
  private static String skipHint(String code) {
    String s = code.stripLeading();
    if (!s.startsWith("@{")) {
      return code;
    }
    int close = s.indexOf('}');
    return close < 0 ? null : s.substring(close + 1);
  }

  /**
   * The words and names of the code in order: identifiers and keywords (with their dots: {@code s.t}) and quoted
   * names, without punctuation.
   */
  private static List<String> words(String code) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < code.length()) {
      char c = code.charAt(i);
      if (c == '`' || c == '"' || Character.isLetter(c) || c == '_') {
        int j = i;
        while (j < code.length()) {
          char d = code.charAt(j);
          if (d == '`') {
            j = code.indexOf('`', j + 1) + 1;
          } else if (d == '"') {
            j = endOfQuoted(code, j);
          } else if (isNamePart(d) || d == '.') {
            j++;
          } else {
            break;
          }
        }
        out.add(code.substring(i, j));
        i = j;
      } else {
        i++;
      }
    }
    return out;
  }

  /** Words that sit between a DML or DDL keyword and the name it acts on. */
  private static final Set<String> NOISE = Set.of("INTO", "FROM", "TABLE", "VIEW", "OR", "REPLACE", "IGNORE", "UPDATE",
      "IF", "NOT", "EXISTS", "UNIQUE", "NULL_FILTERED", "SEARCH", "VECTOR", "INDEX", "CHANGE", "STREAM", "SEQUENCE",
      "SCHEMA", "PROPERTY", "GRAPH", "STATISTICS", "ONLY", "SQL", "SECURITY", "INVOKER", "DEFINER", "FUNCTION");

  /** The first name after the statement's keywords; the table after {@code ON} for an index; {@code null} without one. */
  private static String target(List<String> words) {
    if (words.size() > 2 && words.subList(1, Math.min(words.size(), 6)).stream()
        .anyMatch(w -> w.equalsIgnoreCase("INDEX"))) {
      for (int k = 1; k + 1 < words.size(); k++) {
        if (words.get(k).equalsIgnoreCase("ON")) {
          return words.get(k + 1);
        }
      }
      return null;
    }
    for (int k = 1; k < words.size(); k++) {
      String w = words.get(k);
      if (!NOISE.contains(w.toUpperCase(Locale.ROOT))) {
        return w;
      }
    }
    return null;
  }
}
