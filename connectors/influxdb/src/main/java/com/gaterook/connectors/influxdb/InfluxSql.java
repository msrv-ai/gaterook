package com.gaterook.connectors.influxdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one SQL statement for InfluxDB 3 is, without I/O. InfluxDB 3 parses SQL with DataFusion's generic dialect; this
 * reads the text by the tokens both that dialect and a stricter one agree on: {@code '…'} strings with {@code ''}
 * doubled, {@code "…"} names with {@code ""} doubled, {@code --} line comments, block comments, {@code $name}
 * placeholders. What the readings could split differently is {@link Kind#UNREADABLE} and never runs: a backslash
 * right before a quote (a dialect with backslash escapes ends the string elsewhere), a block comment inside a block
 * comment (nested or not), a prefixed string ({@code E'…'}, {@code U&'…'}, {@code X'…'}), a dollar-quoted string, a
 * backtick name, {@code #} and {@code @}, characters outside ASCII outside quotes, control characters.
 *
 * <p>A read is one {@code SELECT}, {@code WITH … SELECT}, {@code VALUES}, {@code SHOW} or {@code EXPLAIN} of those,
 * without {@code ANALYZE} (which runs the statement), without a word that writes or runs anything else
 * ({@code INTO}, {@code INSERT}, {@code DELETE}, {@code COPY}, {@code CREATE}, …) and without InfluxDB's {@code system}
 * tables, which hold other callers' queries and the server's internals.
 */
final class InfluxSql {

  /** The route of a statement. */
  enum Kind { READ, WRITE, FORBIDDEN, UNREADABLE }

  /**
   * One statement.
   *
   * @param type    the statement's first keyword, upper case ({@code SELECT}, {@code SHOW}), or a description
   * @param problem why it is not a read, for the agent; {@code null} for a read
   */
  record Statement(Kind kind, String type, String problem) {}

  static final int MAX_LENGTH = 20_000;
  private static final Set<String> READS = Set.of("SELECT", "WITH", "VALUES", "SHOW", "EXPLAIN");
  private static final Set<String> WRITES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE",
      "CREATE", "DROP", "ALTER", "TRUNCATE", "COPY");
  /**
   * Words that never stand in a read, wherever they are: a change after a {@code WITH} ({@code WITH … INSERT},
   * {@code WITH d AS (DELETE … RETURNING *)}), {@code SELECT … INTO}, and statements that write. Words that can only
   * start a statement of their own ({@code GRANT}, {@code EXECUTE}, {@code LOAD}) are kept out by the first word, and
   * stay usable as names.
   */
  private static final Set<String> NEVER_IN_A_READ = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT",
      "CREATE", "DROP", "ALTER", "TRUNCATE", "COPY", "INTO");

  private InfluxSql() {}

  /** One token: a bare word, a quoted name (its content), or punctuation. */
  record Token(Type type, String text) {
    enum Type { WORD, QUOTED, STRING, NUMBER, PARAM, PUNCT }
  }

  static Statement read(String sql) {
    if (sql == null || sql.isBlank()) {
      return new Statement(Kind.UNREADABLE, "statement", "The statement is empty.");
    }
    if (sql.length() > MAX_LENGTH) {
      return new Statement(Kind.UNREADABLE, "statement", "The statement is longer than " + MAX_LENGTH
          + " characters.");
    }
    List<List<Token>> statements = new ArrayList<>();
    String problem = tokens(sql, statements);
    if (problem != null) {
      return new Statement(Kind.UNREADABLE, "statement", problem + " GateRook doesn't run what it can't read "
          + "with certainty.");
    }
    if (statements.size() > 1) {
      return new Statement(Kind.FORBIDDEN, "script", "One statement per call: this text has " + statements.size()
          + ". Send them one by one.");
    }
    if (statements.isEmpty()) {
      return new Statement(Kind.UNREADABLE, "statement", "The statement has no keyword.");
    }
    List<Token> t = statements.getFirst();
    if (t.getFirst().type() != Token.Type.WORD) {
      return new Statement(Kind.UNREADABLE, "statement", "The statement doesn't start with a keyword.");
    }
    String first = t.getFirst().text().toUpperCase(Locale.ROOT);
    if (WRITES.contains(first)) {
      return new Statement(Kind.WRITE, first, first + " changes data or schema.");
    }
    if (!READS.contains(first)) {
      return new Statement(Kind.FORBIDDEN, first, first + " isn't a statement GateRook runs on InfluxDB: it "
          + "runs SELECT, WITH … SELECT, VALUES, SHOW and EXPLAIN.");
    }
    if (first.equals("EXPLAIN")) {
      Statement explained = explained(t);
      if (explained != null) {
        return explained;
      }
    }
    for (int k = 0; k < t.size(); k++) {
      Token token = t.get(k);
      if (token.type() == Token.Type.WORD) {
        String w = token.text().toUpperCase(Locale.ROOT);
        if (NEVER_IN_A_READ.contains(w)) {
          return new Statement(WRITES.contains(w) || w.equals("INTO") ? Kind.WRITE : Kind.FORBIDDEN, first,
              w + " has no place in a read: GateRook runs only statements that read. Quote a name that is "
                  + "spelled like this keyword (\"" + token.text() + "\").");
        }
      }
      boolean system = (token.type() == Token.Type.WORD && token.text().equalsIgnoreCase("system"))
          || (token.type() == Token.Type.QUOTED && token.text().equals("system"));
      if (system && k + 1 < t.size() && t.get(k + 1).type() == Token.Type.PUNCT && t.get(k + 1).text().equals(".")) {
        return new Statement(Kind.FORBIDDEN, first, "The system tables (queries of other callers, caches, the "
            + "processing engine) stay with people: GateRook doesn't read them.");
      }
    }
    return new Statement(Kind.READ, first, null);
  }

  /** {@code EXPLAIN [VERBOSE] [FORMAT x] SELECT …}: a read; {@code EXPLAIN ANALYZE} runs the statement. */
  private static Statement explained(List<Token> t) {
    int k = 1;
    while (k < t.size() && t.get(k).type() == Token.Type.WORD) {
      String w = t.get(k).text().toUpperCase(Locale.ROOT);
      if (w.equals("ANALYZE")) {
        return new Statement(Kind.FORBIDDEN, "EXPLAIN", "EXPLAIN ANALYZE runs the statement: GateRook runs "
            + "EXPLAIN without ANALYZE.");
      }
      if (w.equals("VERBOSE")) {
        k++;
      } else if (w.equals("FORMAT") && k + 1 < t.size()) {
        k += 2;
      } else {
        break;
      }
    }
    if (k >= t.size() || t.get(k).type() != Token.Type.WORD) {
      return new Statement(Kind.UNREADABLE, "EXPLAIN", "EXPLAIN needs the statement it explains.");
    }
    String w = t.get(k).text().toUpperCase(Locale.ROOT);
    if (!w.equals("SELECT") && !w.equals("WITH") && !w.equals("VALUES")) {
      return new Statement(WRITES.contains(w) ? Kind.WRITE : Kind.FORBIDDEN, "EXPLAIN", "GateRook explains reads "
          + "only: SELECT, WITH … SELECT or VALUES.");
    }
    return null;
  }

  /**
   * Splits the text into statements of tokens at top-level semicolons, or says why it can't be read with certainty.
   * Comments are dropped; a statement without a token is no statement.
   */
  static String tokens(String sql, List<List<Token>> out) {
    List<Token> current = new ArrayList<>();
    int n = sql.length();
    int i = 0;
    while (i < n) {
      char c = sql.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
        continue;
      }
      if (c < 0x20 || c == 0x7F) {
        return "The statement holds a control character.";
      }
      if (c > 0x7E) {
        return "Characters outside ASCII are read only inside quotes: write \"name\" or '…'.";
      }
      char next = i + 1 < n ? sql.charAt(i + 1) : 0;
      if (c == '-' && next == '-') {
        while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
          i++;
        }
        continue;
      }
      if (c == '/' && next == '*') {
        int close = sql.indexOf("*/", i + 2);
        if (close < 0) {
          return "A comment isn't closed.";
        }
        if (sql.substring(i + 2, close).contains("/*")) {
          return "A comment inside a comment isn't analyzed.";
        }
        i = close + 2;
        continue;
      }
      if (c == '\'' || c == '"') {
        if (i > 0 && (identifierPart(sql.charAt(i - 1)) || sql.charAt(i - 1) == '&')) {
          return "A prefixed string or name, such as E'…' or U&'…', isn't analyzed: write a plain one.";
        }
        int j = i + 1;
        StringBuilder value = new StringBuilder();
        boolean closed = false;
        while (j < n) {
          char d = sql.charAt(j);
          if (d == c) {
            // A dialect with backslash escapes reads \' as a quote that doesn't close, and then \'' as one that does.
            if (sql.charAt(j - 1) == '\\') {
              return "A backslash right before a quote is read differently by SQL dialects: GateRook doesn't "
                  + "analyze it.";
            }
            if (j + 1 < n && sql.charAt(j + 1) == c) {
              value.append(c);
              j += 2;
              continue;
            }
            closed = true;
            j++;
            break;
          }
          value.append(d);
          j++;
        }
        if (!closed) {
          return c == '\'' ? "A string isn't closed." : "A quoted name isn't closed.";
        }
        current.add(new Token(c == '\'' ? Token.Type.STRING : Token.Type.QUOTED, value.toString()));
        i = j;
        continue;
      }
      if (c == '`') {
        return "Backtick names aren't analyzed: quote names with double quotes.";
      }
      if (c == '#' || c == '@') {
        return c + " isn't analyzed outside quotes.";
      }
      if (c == '$') {
        if (i > 0 && identifierPart(sql.charAt(i - 1))) {
          return "A $ inside a name isn't analyzed: quote the name.";
        }
        int j = i + 1;
        while (j < n && identifierPart(sql.charAt(j))) {
          j++;
        }
        if (j == i + 1 || (j < n && sql.charAt(j) == '$')) {
          return "Dollar-quoted strings aren't analyzed: use '…'.";
        }
        current.add(new Token(Token.Type.PARAM, sql.substring(i + 1, j)));
        i = j;
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        int j = i + 1;
        while (j < n && identifierPart(sql.charAt(j))) {
          j++;
        }
        if (j < n && sql.charAt(j) == '$') {
          return "A $ inside a name isn't analyzed: quote the name.";
        }
        current.add(new Token(Token.Type.WORD, sql.substring(i, j)));
        i = j;
        continue;
      }
      if (Character.isDigit(c) || (c == '.' && Character.isDigit(next))) {
        int j = i;
        while (j < n && (Character.isDigit(sql.charAt(j)) || sql.charAt(j) == '.')) {
          j++;
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
        // Letters right after a number form a word of their own, so a keyword can't hide behind digits.
        current.add(new Token(Token.Type.NUMBER, sql.substring(i, j)));
        i = j;
        continue;
      }
      if (c == ';') {
        if (!current.isEmpty()) {
          out.add(current);
          current = new ArrayList<>();
        }
        i++;
        continue;
      }
      current.add(new Token(Token.Type.PUNCT, String.valueOf(c)));
      i++;
    }
    if (!current.isEmpty()) {
      out.add(current);
    }
    return null;
  }

  private static boolean identifierPart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }
}
