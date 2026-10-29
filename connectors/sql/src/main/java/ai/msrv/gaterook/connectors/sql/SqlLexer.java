package ai.msrv.gaterook.connectors.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits SQL text into code, literals and comments the way the server's lexer does (report 04 §6.1): enough to find
 * the first keyword, the statement boundaries and the {@code :name} placeholders of a template, and to notice text the
 * parser would read differently from the server.
 *
 * <p>PostgreSQL: {@code '…'} with {@code ''} (standard_conforming_strings, which the read and write preludes switch on
 * for every call), {@code E'…'} with backslash escapes, {@code "…"} identifiers, {@code $tag$…$tag$}, {@code --} line
 * comments and nested {@code /* … *}{@code /}. MySQL and MariaDB: {@code '…'} and {@code "…"} with backslash escapes
 * and doubled quotes (the prelude switches {@code NO_BACKSLASH_ESCAPES} and {@code ANSI_QUOTES} off), {@code `…`}
 * identifiers, {@code #} and {@code -- } line comments, {@code /* … *}{@code /}; {@code /*!…*}{@code /} and
 * {@code /*M!…*}{@code /} are code the server runs although every parser reads them as comments.
 */
final class SqlLexer {

  /** What a run of characters is. */
  enum Kind { CODE, STRING, QUOTED_IDENTIFIER, COMMENT, EXECUTABLE_COMMENT }

  /** One run: {@code [start, end)} of the text. */
  record Segment(Kind kind, int start, int end) {}

  /**
   * The text cut into runs.
   *
   * @param unterminated a literal, quoted identifier or comment runs to the end of the text: the server would reject
   *                     it or read the rest differently, so nothing derived from this scan can be trusted
   */
  record Scan(String sql, List<Segment> segments, boolean unterminated) {

    /**
     * Whether the text holds a comment the server acts on: MySQL's {@code /*!…*}{@code /} and optimizer hints, and
     * {@code /*+…*}{@code /} hints on PostgreSQL (pg_hint_plan).
     */
    boolean executableComment() {
      return segments.stream().anyMatch(s -> s.kind() == Kind.EXECUTABLE_COMMENT);
    }

    /** The first word of the first statement, upper case, after comments and opening parentheses; "" if none. */
    String firstKeyword() {
      for (Segment s : segments) {
        if (s.kind() == Kind.COMMENT || s.kind() == Kind.EXECUTABLE_COMMENT) {
          continue;
        }
        if (s.kind() != Kind.CODE) {
          return "";
        }
        for (int i = s.start(); i < s.end(); i++) {
          char c = sql.charAt(i);
          if (Character.isWhitespace(c) || c == '(') {
            continue;
          }
          if (!Character.isLetter(c)) {
            return "";
          }
          int j = i;
          while (j < s.end() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
          }
          return sql.substring(i, j).toUpperCase(Locale.ROOT);
        }
      }
      return "";
    }

    /**
     * The text with every literal, quoted name and comment replaced by one space: what the server reads as code,
     * for checks on words and operators that must not see the inside of a string.
     */
    String code() {
      StringBuilder out = new StringBuilder(sql.length());
      for (Segment s : segments) {
        if (s.kind() == Kind.CODE) {
          out.append(sql, s.start(), s.end());
        } else {
          out.append(' ');
        }
      }
      return out.toString();
    }

    /**
     * The statements: the text between top-level semicolons, each with its comments, without surrounding white
     * space; parts with nothing but white space and comments are left out.
     */
    List<String> statements() {
      List<String> out = new ArrayList<>();
      int from = 0;
      for (Segment s : segments) {
        if (s.kind() != Kind.CODE) {
          continue;
        }
        for (int i = s.start(); i < s.end(); i++) {
          if (sql.charAt(i) == ';') {
            add(out, from, i);
            from = i + 1;
          }
        }
      }
      add(out, from, sql.length());
      return out;
    }

    private void add(List<String> out, int from, int to) {
      if (hasCode(from, to)) {
        out.add(sql.substring(from, to).strip());
      }
    }

    /** Whether {@code [from, to)} holds anything but white space and comments. */
    private boolean hasCode(int from, int to) {
      for (Segment s : segments) {
        int a = Math.max(from, s.start());
        int b = Math.min(to, s.end());
        if (a >= b || s.kind() == Kind.COMMENT) {
          continue;
        }
        if (s.kind() != Kind.CODE) {
          return true;
        }
        for (int i = a; i < b; i++) {
          if (!Character.isWhitespace(sql.charAt(i))) {
            return true;
          }
        }
      }
      return false;
    }
  }

  private SqlLexer() {}

  static Scan scan(String sql, boolean mysql) {
    return mysql ? scanMySql(sql) : scanPostgres(sql);
  }

  static Scan scan(String sql, SqlDialect dialect) {
    return scan(sql, dialect.mysql(), dialect.sqlServer());
  }

  static Scan scan(String sql, boolean mysql, boolean sqlServer) {
    return sqlServer ? scanSqlServer(sql) : scan(sql, mysql);
  }

  /** T-SQL with QUOTED_IDENTIFIER ON: brackets escape as ]], and block comments nest. */
  private static Scan scanSqlServer(String sql) {
    List<Segment> out = new ArrayList<>();
    int code = 0;
    int i = 0;
    boolean unterminated = false;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      int end = -1;
      Kind kind = null;
      if (c == '\'') {
        end = quoted(sql, i, '\'', false);
        kind = Kind.STRING;
      } else if (c == '"' || c == '[') {
        end = quoted(sql, i, c == '[' ? ']' : '"', false);
        kind = Kind.QUOTED_IDENTIFIER;
      } else if (sql.startsWith("--", i)) {
        end = lineEnd(sql, i);
        kind = Kind.COMMENT;
      } else if (sql.startsWith("/*", i)) {
        end = nestedComment(sql, i);
        kind = Kind.COMMENT;
      }
      if (kind == null) {
        i++;
        continue;
      }
      if (code < i) {
        out.add(new Segment(Kind.CODE, code, i));
      }
      if (end < 0) {
        unterminated = true;
        end = sql.length();
      }
      out.add(new Segment(kind, i, end));
      i = end;
      code = end;
    }
    if (code < sql.length()) {
      out.add(new Segment(Kind.CODE, code, sql.length()));
    }
    return new Scan(sql, List.copyOf(out), unterminated);
  }

  // ── PostgreSQL ──────────────────────────────────────────────────────────────────────────────────────────────

  private static Scan scanPostgres(String sql) {
    List<Segment> out = new ArrayList<>();
    int n = sql.length();
    int code = 0;
    int i = 0;
    boolean unterminated = false;
    while (i < n) {
      char c = sql.charAt(i);
      int end = -1;
      Kind kind = null;
      if (c == '\'') {
        boolean escapes = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
            && (i < 2 || !identifierPart(sql.charAt(i - 2)));
        end = quoted(sql, i, '\'', escapes);
        kind = Kind.STRING;
      } else if (c == '"') {
        end = quoted(sql, i, '"', false);
        kind = Kind.QUOTED_IDENTIFIER;
      } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        end = lineEnd(sql, i);
        kind = Kind.COMMENT;
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        end = nestedComment(sql, i);
        // pg_hint_plan reads /*+ … */ and can set GUCs for the statement (Set(statement_timeout 0)).
        kind = sql.startsWith("/*+", i) ? Kind.EXECUTABLE_COMMENT : Kind.COMMENT;
      } else if (c == '$' && (i == 0 || !identifierPart(sql.charAt(i - 1)))) {
        int tagEnd = dollarTag(sql, i);
        if (tagEnd > 0) {
          String tag = sql.substring(i, tagEnd);
          int close = sql.indexOf(tag, tagEnd);
          end = close < 0 ? -2 : close + tag.length();
          kind = Kind.STRING;
        }
      }
      if (kind == null) {
        i++;
        continue;
      }
      if (code < i) {
        out.add(new Segment(Kind.CODE, code, i));
      }
      if (end < 0) {
        unterminated = true;
        end = n;
      }
      out.add(new Segment(kind, i, end));
      i = end;
      code = end;
    }
    if (code < n) {
      out.add(new Segment(Kind.CODE, code, n));
    }
    return new Scan(sql, List.copyOf(out), unterminated);
  }

  /** {@code $tag$}: the index after its closing {@code $}, or -1 if {@code i} doesn't start a dollar quote. */
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

  /** PostgreSQL block comments nest: the index after the matching close, or -1. */
  private static int nestedComment(String sql, int i) {
    int depth = 0;
    int j = i;
    while (j < sql.length() - 1) {
      if (sql.charAt(j) == '/' && sql.charAt(j + 1) == '*') {
        depth++;
        j += 2;
      } else if (sql.charAt(j) == '*' && sql.charAt(j + 1) == '/') {
        depth--;
        j += 2;
        if (depth == 0) {
          return j;
        }
      } else {
        j++;
      }
    }
    return -1;
  }

  // ── MySQL and MariaDB ───────────────────────────────────────────────────────────────────────────────────────

  private static Scan scanMySql(String sql) {
    List<Segment> out = new ArrayList<>();
    int n = sql.length();
    int code = 0;
    int i = 0;
    boolean unterminated = false;
    while (i < n) {
      char c = sql.charAt(i);
      int end = -1;
      Kind kind = null;
      if (c == '\'' || c == '"') {
        end = quoted(sql, i, c, true);
        kind = Kind.STRING;
      } else if (c == '`') {
        end = quoted(sql, i, '`', false);
        kind = Kind.QUOTED_IDENTIFIER;
      } else if (c == '#') {
        end = lineEnd(sql, i);
        kind = Kind.COMMENT;
      } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-' && (i + 2 == n || sql.charAt(i + 2) <= ' ')) {
        // "--" starts a comment only before white space or a control character ("--1" is minus minus one).
        end = lineEnd(sql, i);
        kind = Kind.COMMENT;
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int close = sql.indexOf("*/", i + 2);
        end = close < 0 ? -1 : close + 2;
        // /*! … */ runs as code; /*+ … */ is an optimizer hint, whose SET_VAR() changes session variables such as
        // max_execution_time for the statement.
        boolean executable = sql.startsWith("/*!", i) || sql.startsWith("/*M!", i) || sql.startsWith("/*m!", i)
            || sql.startsWith("/*+", i);
        kind = executable ? Kind.EXECUTABLE_COMMENT : Kind.COMMENT;
      }
      if (kind == null) {
        i++;
        continue;
      }
      if (code < i) {
        out.add(new Segment(Kind.CODE, code, i));
      }
      if (end < 0) {
        unterminated = true;
        end = n;
      }
      out.add(new Segment(kind, i, end));
      i = end;
      code = end;
    }
    if (code < n) {
      out.add(new Segment(Kind.CODE, code, n));
    }
    return new Scan(sql, List.copyOf(out), unterminated);
  }

  // ── Shared ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * A literal or quoted identifier opened at {@code i} by {@code quote}: a doubled quote stays inside, and with
   * {@code backslash} a backslash escapes the next character. The index after the closing quote, or -1.
   */
  private static int quoted(String sql, int i, char quote, boolean backslash) {
    int j = i + 1;
    while (j < sql.length()) {
      char c = sql.charAt(j);
      if (backslash && c == '\\') {
        j += 2;
        continue;
      }
      if (c == quote) {
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

  private static int lineEnd(String sql, int i) {
    int j = i;
    while (j < sql.length() && sql.charAt(j) != '\n' && sql.charAt(j) != '\r') {
      j++;
    }
    return j;
  }

  static boolean identifierPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }
}
