package ai.msrv.gaterook.connectors.bigquery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What one GoogleSQL statement is, without I/O: the gate before BigQuery's own answer. The text is read by GoogleSQL's
 * tokens — {@code '…'} and {@code "…"} strings with backslash escapes, their triple-quoted forms, raw {@code r'…'} and
 * bytes {@code b'…'} prefixes, backtick names, {@code #} and {@code --} line comments, block comments that don't
 * nest — so a semicolon or a keyword inside a string or a comment is neither. Whatever this can't read with certainty
 * (an open quote or comment, a backslash in a raw string or a quoted name) is {@link Kind#UNREADABLE}, and a call
 * refuses it. Access policies, reservations, project options and calls that reach other services or databases from
 * BigQuery (AI functions, {@code EXTERNAL_QUERY}) are {@link Kind#FORBIDDEN}: the byte limit doesn't cover them.
 *
 * <p>This only picks the route. For a read, BigQuery's dry run then names the statement type, and only
 * {@code SELECT} runs ({@link BigQueryConnector}).
 */
final class GoogleSql {

  /** The route of a statement, in increasing order of what it may do. */
  enum Kind { READ, DML, DDL, FORBIDDEN, UNREADABLE }

  /**
   * One call's text.
   *
   * @param type    the first keyword, upper case ({@code SELECT}, {@code UPDATE}, {@code CREATE}), or a description
   * @param target  for DML and DDL, the table or dataset the statement names after its keywords, as written; or
   *                {@code null}
   * @param schema  whether {@code target} names a dataset ({@code CREATE SCHEMA}) rather than a table
   * @param problem why it is FORBIDDEN or UNREADABLE, for the agent; {@code null} otherwise
   */
  record Statement(Kind kind, String type, String target, boolean schema, String problem) {

    Statement(Kind kind, String type, String problem) {
      this(kind, type, null, false, problem);
    }
  }

  static final int MAX_LENGTH = 100_000;
  private static final Set<String> READ = Set.of("SELECT", "WITH");
  private static final Set<String> DML = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE");
  private static final Set<String> DDL = Set.of("CREATE", "ALTER", "DROP", "UNDROP");
  /** Scripting, transactions, procedures, data movement and access control: never through GateRook. */
  private static final Set<String> FORBIDDEN = Set.of("EXPORT", "LOAD", "DECLARE", "SET", "BEGIN", "COMMIT",
      "ROLLBACK", "CALL", "EXECUTE", "IF", "LOOP", "WHILE", "REPEAT", "FOR", "BREAK", "LEAVE", "CONTINUE", "ITERATE",
      "RAISE", "RETURN", "ASSERT", "GRANT", "REVOKE");
  /** DDL objects that set access or spend money beyond a statement: they stay with people. */
  private static final Set<String> FORBIDDEN_OBJECTS = Set.of("CAPACITY", "RESERVATION", "ASSIGNMENT", "BI_CAPACITY",
      "PROJECT", "ORGANIZATION");
  /**
   * Functions that call another service or database from BigQuery: their cost and effects aren't BigQuery bytes.
   * Read in the code without backticks, so a name inside a string or a comment is none.
   */
  private static final Pattern OTHER_SERVICES = Pattern.compile("(?i)(?<![\\w.])(?:EXTERNAL_QUERY|"
      + "EXTERNAL_OBJECT_TRANSFORM|ML\\s*\\.\\s*(?:GENERATE\\w*|PROCESS_DOCUMENT|TRANSCRIBE|UNDERSTAND_TEXT|TRANSLATE|"
      + "ANNOTATE_IMAGE)|AI\\s*\\.\\s*(?:GENERATE\\w*|EMBED|SIMILARITY|CLASSIFY|IF|SCORE))\\s*\\(");

  private GoogleSql() {}

  /** Code (literals and comments as spaces, names kept as written) and the count of top-level statements. */
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
    List<String> words = words(scan.code());
    if (words.isEmpty()) {
      return new Statement(Kind.UNREADABLE, "statement", "The statement has no keyword.");
    }
    String first = words.getFirst().toUpperCase(Locale.ROOT);
    java.util.regex.Matcher call = OTHER_SERVICES.matcher(scan.code().replace("`", ""));
    if (call.find()) {
      String name = call.group().replaceAll("[\\s(]", "").toUpperCase(Locale.ROOT);
      return new Statement(Kind.FORBIDDEN, first, name + " calls another service or database from BigQuery: "
          + "GateRook doesn't run it, as the byte limit doesn't cover what it costs or changes.");
    }
    if (READ.contains(first)) {
      return new Statement(Kind.READ, first, null);
    }
    if (DML.contains(first) || DDL.contains(first)) {
      for (int k = 1; k < words.size(); k++) {
        String w = words.get(k).toUpperCase(Locale.ROOT);
        boolean accessPolicy = w.equals("ROW") && k + 1 < words.size()
            && words.get(k + 1).equalsIgnoreCase("ACCESS");
        if (accessPolicy || FORBIDDEN_OBJECTS.contains(w)) {
          return new Statement(Kind.FORBIDDEN, first, first + " " + (accessPolicy ? "ROW ACCESS POLICY" : w)
              + " isn't allowed through GateRook: access policies, reservations and project options stay with "
              + "people.");
        }
        if (!NOISE.contains(w)) {
          break;
        }
      }
      boolean schema = words.size() > 1 && words.subList(1, Math.min(words.size(), 5)).stream()
          .anyMatch(w -> w.equalsIgnoreCase("SCHEMA"));
      return new Statement(DML.contains(first) ? Kind.DML : Kind.DDL, first, target(words), schema, null);
    }
    if (FORBIDDEN.contains(first)) {
      return new Statement(Kind.FORBIDDEN, first, first + " isn't allowed through GateRook: scripting, "
          + "transactions, procedures, exports, loads and access control stay with people.");
    }
    return new Statement(Kind.UNREADABLE, first, first + " isn't a statement GateRook runs on BigQuery.");
  }

  /** Reads the text by GoogleSQL's tokens. */
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
        int close = sql.indexOf("*/", i + 2);
        if (close < 0) {
          return new Scan(code.toString(), statements, "A comment isn't closed.");
        }
        i = close + 2;
        code.append(' ');
        continue;
      }
      if (c == '`') {
        int j = i + 1;
        while (j < n && sql.charAt(j) != '`') {
          if (sql.charAt(j) == '\\') {
            return new Scan(code.toString(), statements, "A backslash in a quoted name isn't analyzed.");
          }
          if (sql.charAt(j) == '\n' || sql.charAt(j) == '\r') {
            return new Scan(code.toString(), statements, "A quoted name isn't closed on its line.");
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
      if (c == '\'' || c == '"') {
        // The prefix letters just before the quote: r (raw), b (bytes), in either order.
        boolean raw = false;
        int p = code.length() - 1;
        while (p >= 0 && p >= code.length() - 2 && "rRbB".indexOf(code.charAt(p)) >= 0) {
          if (code.charAt(p) == 'r' || code.charAt(p) == 'R') {
            raw = true;
          }
          p--;
        }
        if (p >= 0 && (Character.isLetterOrDigit(code.charAt(p)) || code.charAt(p) == '_')) {
          raw = false; // the letters end a longer name: no prefix
        }
        boolean triple = i + 2 < n && sql.charAt(i + 1) == c && sql.charAt(i + 2) == c;
        String close = triple ? String.valueOf(c).repeat(3) : String.valueOf(c);
        int j = i + close.length();
        int end = -1;
        while (j < n) {
          char d = sql.charAt(j);
          if (d == '\\') {
            if (raw) {
              // A raw string keeps backslashes, but what one does before a quote differs between readers.
              return new Scan(code.toString(), statements, "A backslash in a raw string isn't analyzed: use a plain "
                  + "string.");
            }
            j += 2;
            continue;
          }
          if (!triple && (d == '\n' || d == '\r')) {
            return new Scan(code.toString(), statements, "A string isn't closed on its line.");
          }
          if (sql.startsWith(close, j)) {
            end = j + close.length();
            break;
          }
          j++;
        }
        if (end < 0) {
          return new Scan(code.toString(), statements, "A string isn't closed.");
        }
        code.append(" ' ");
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

  /** The words and names of the code in order: identifiers, keywords and backtick names, without punctuation. */
  private static List<String> words(String code) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < code.length()) {
      char c = code.charAt(i);
      if (c == '`') {
        int j = code.indexOf('`', i + 1);
        out.add(code.substring(i, j + 1));
        i = j + 1;
      } else if (Character.isLetter(c) || c == '_') {
        int j = i;
        while (j < code.length() && (Character.isLetterOrDigit(code.charAt(j)) || code.charAt(j) == '_'
            || code.charAt(j) == '-' || code.charAt(j) == '.' || code.charAt(j) == '`')) {
          if (code.charAt(j) == '`') {
            j = code.indexOf('`', j + 1) + 1;
          } else {
            j++;
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
  private static final Set<String> NOISE = Set.of("INTO", "FROM", "TABLE", "VIEW", "MATERIALIZED", "SCHEMA",
      "EXTERNAL", "SNAPSHOT", "CLONE", "OR", "REPLACE", "IF", "NOT", "EXISTS", "TEMP", "TEMPORARY", "SEARCH", "VECTOR",
      "INDEX", "FUNCTION", "AGGREGATE", "PROCEDURE", "MODEL", "DATA", "ALL");

  /**
   * The first name after the statement's keywords: {@code ds.t}, {@code `p.ds.t`}; the table after {@code ON} for
   * an index; {@code null} without one.
   */
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
