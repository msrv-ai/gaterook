package com.gaterook.connectors.snowflake;

import com.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Snowflake SQL text read the way Snowflake reads it, without I/O: code, {@code '…'} strings (a backslash escapes the
 * next character and {@code ''} is a quote), {@code $$…$$} strings (no escapes), {@code "…"} names ({@code ""} is a
 * quote), {@code --} and {@code //} line comments and {@code /* … *}{@code /} block comments. The code becomes tokens:
 * words (keywords and unquoted names, in upper case), quoted names, strings, numbers and symbols.
 *
 * <p>What this scan can't read exactly as Snowflake would is a problem, and nothing derived from such a text is
 * trusted: a string, name or comment left open, a block comment inside another (where the two might end it in
 * different places), {@code $$} inside a word, parentheses that don't balance, a control character anywhere, an
 * invisible character (bidirectional controls, zero-width characters) outside a string, and a character outside ASCII
 * in the code itself: Snowflake's keywords and unquoted names are plain ASCII, so such a character is either a mistake
 * or a look-alike.
 */
final class SnowflakeLexer {

  /** Longest statement read; the tools' schemas allow the same. */
  static final int MAX_LENGTH = 100_000;

  /** What a token is. */
  enum Type { WORD, QUOTED_NAME, STRING, NUMBER, SYMBOL }

  /**
   * One token of the code.
   *
   * @param text  a word in upper case; a quoted name without its quotes, {@code ""} undone; a string's content as
   *              written, without its quotes; a number or a symbol as written
   * @param start where it starts in the text
   * @param depth the parentheses open around it (a parenthesis itself counts as outside)
   */
  record Token(Type type, String text, int start, int depth) {

    boolean word(String w) {
      return type == Type.WORD && text.equals(w);
    }

    boolean symbol(String s) {
      return type == Type.SYMBOL && text.equals(s);
    }

    /** A word or a quoted name: what can be a part of an object's name. */
    boolean name() {
      return type == Type.WORD || type == Type.QUOTED_NAME;
    }
  }

  /**
   * A text read.
   *
   * @param tokens     the tokens of the code, comments left out
   * @param statements how many statements hold code: Snowflake ignores empty ones between semicolons
   * @param problem    why the text can't be read as Snowflake reads it, in words for the agent; {@code null} if it can
   */
  record Scan(String sql, List<Token> tokens, int statements, String problem) {

    boolean readable() {
      return problem == null;
    }

    /** The first word of the statement after any opening parentheses, upper case; "" when it starts otherwise. */
    String firstWord() {
      for (Token t : tokens) {
        if (t.symbol("(")) {
          continue;
        }
        return t.type() == Type.WORD ? t.text() : "";
      }
      return "";
    }

    /** The token at {@code i}, or {@code null} past the end. */
    Token at(int i) {
      return i >= 0 && i < tokens.size() ? tokens.get(i) : null;
    }

    /** Whether the token at {@code i} is a word that a dot qualifies ({@code t.call}: a column, not a keyword). */
    boolean qualified(int i) {
      Token before = at(i - 1);
      return before != null && before.symbol(".");
    }

    /** Whether a parenthesis follows the token at {@code i}: a call, when it is a name. */
    boolean called(int i) {
      Token after = at(i + 1);
      return after != null && after.symbol("(");
    }

    /** Whether the code holds {@code word} as a keyword: unqualified, anywhere. */
    boolean hasKeyword(String word) {
      for (int i = 0; i < tokens.size(); i++) {
        if (tokens.get(i).word(word) && !qualified(i)) {
          return true;
        }
      }
      return false;
    }
  }

  private SnowflakeLexer() {}

  static Scan scan(String sql) {
    if (sql == null || sql.isBlank()) {
      return problem(sql, "The statement is empty.");
    }
    if (sql.length() > MAX_LENGTH) {
      return problem(sql, "The statement is longer than " + MAX_LENGTH + " characters.");
    }
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
        return problem(sql, "The statement holds a control character.");
      }
    }
    List<Token> tokens = new ArrayList<>();
    int n = sql.length();
    int i = 0;
    int depth = 0;
    int statements = 0;
    boolean code = false;
    while (i < n) {
      char c = sql.charAt(i);
      char next = i + 1 < n ? sql.charAt(i + 1) : 0;
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        i++;
        continue;
      }
      if (c == '\'') {
        int end = singleQuoted(sql, i);
        if (end < 0) {
          return problem(sql, "A string isn't closed.");
        }
        tokens.add(new Token(Type.STRING, sql.substring(i + 1, end - 1), i, depth));
        code = true;
        i = end;
        continue;
      }
      if (c == '"') {
        int end = doubleQuoted(sql, i);
        if (end < 0) {
          return problem(sql, "A quoted name isn't closed.");
        }
        String name = sql.substring(i + 1, end - 1).replace("\"\"", "\"");
        if (!OutputSanitizer.isClean(name)) {
          return problem(sql, "A quoted name holds an invisible character.");
        }
        tokens.add(new Token(Type.QUOTED_NAME, name, i, depth));
        code = true;
        i = end;
        continue;
      }
      if (c == '$' && next == '$') {
        if (i > 0 && wordPart(sql.charAt(i - 1))) {
          return problem(sql, "$$ right after a name isn't read: put a space before a $$ string.");
        }
        int close = sql.indexOf("$$", i + 2);
        if (close < 0) {
          return problem(sql, "A $$ string isn't closed.");
        }
        tokens.add(new Token(Type.STRING, sql.substring(i + 2, close), i, depth));
        code = true;
        i = close + 2;
        continue;
      }
      if ((c == '-' && next == '-') || (c == '/' && next == '/')) {
        int end = i;
        while (end < n && sql.charAt(end) != '\n' && sql.charAt(end) != '\r') {
          end++;
        }
        if (!OutputSanitizer.isClean(sql.substring(i, end))) {
          return problem(sql, "A comment holds an invisible character.");
        }
        i = end;
        continue;
      }
      if (c == '/' && next == '*') {
        int close = sql.indexOf("*/", i + 2);
        if (close < 0) {
          return problem(sql, "A comment isn't closed.");
        }
        String body = sql.substring(i + 2, close);
        if (body.contains("/*")) {
          return problem(sql, "A comment inside a comment isn't read: Snowflake and GateRook might end it in "
              + "different places.");
        }
        if (!OutputSanitizer.isClean(body)) {
          return problem(sql, "A comment holds an invisible character.");
        }
        i = close + 2;
        continue;
      }
      if (c > 0x7E) {
        return problem(sql, "The code holds a character outside ASCII: write such names in double quotes.");
      }
      if (wordStart(c) || c == '$') {
        int j = i + 1;
        while (j < n && wordPart(sql.charAt(j))) {
          j++;
        }
        String word = sql.substring(i, j);
        if (word.contains("$$")) {
          return problem(sql, "$$ inside a name isn't read: put a space before a $$ string.");
        }
        tokens.add(new Token(Type.WORD, word.toUpperCase(Locale.ROOT), i, depth));
        code = true;
        i = j;
        continue;
      }
      if (c >= '0' && c <= '9') {
        int j = i + 1;
        while (j < n && (wordPart(sql.charAt(j)) || sql.charAt(j) == '.')) {
          j++;
        }
        String number = sql.substring(i, j);
        if (number.contains("$$")) {
          return problem(sql, "$$ right after a number isn't read: put a space before a $$ string.");
        }
        tokens.add(new Token(Type.NUMBER, number, i, depth));
        code = true;
        i = j;
        continue;
      }
      if (c == ';') {
        if (code) {
          statements++;
        }
        code = false;
        tokens.add(new Token(Type.SYMBOL, ";", i, depth));
        i++;
        continue;
      }
      if (c == '(') {
        tokens.add(new Token(Type.SYMBOL, "(", i, depth));
        depth++;
        code = true;
        i++;
        continue;
      }
      if (c == ')') {
        depth--;
        if (depth < 0) {
          return problem(sql, "The parentheses don't balance.");
        }
        tokens.add(new Token(Type.SYMBOL, ")", i, depth));
        code = true;
        i++;
        continue;
      }
      // The flow operator ->> chains statements; read it as one symbol so that rules can see it.
      String symbol = sql.startsWith("->>", i) ? "->>" : String.valueOf(c);
      tokens.add(new Token(Type.SYMBOL, symbol, i, depth));
      code = true;
      i += symbol.length();
    }
    if (depth != 0) {
      return problem(sql, "The parentheses don't balance.");
    }
    if (code) {
      statements++;
    }
    return new Scan(sql, List.copyOf(tokens), statements, statements == 0 ? "The statement has no code." : null);
  }

  private static Scan problem(String sql, String problem) {
    return new Scan(sql == null ? "" : sql, List.of(), 0, problem);
  }

  /** A {@code '…'} string opened at {@code i}: the index after its closing quote, or -1. */
  private static int singleQuoted(String sql, int i) {
    int j = i + 1;
    while (j < sql.length()) {
      char c = sql.charAt(j);
      if (c == '\\') {
        j += 2;
        continue;
      }
      if (c == '\'') {
        if (j + 1 < sql.length() && sql.charAt(j + 1) == '\'') {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    return -1;
  }

  /** A {@code "…"} name opened at {@code i}: the index after its closing quote, or -1. */
  private static int doubleQuoted(String sql, int i) {
    int j = i + 1;
    while (j < sql.length()) {
      if (sql.charAt(j) == '"') {
        if (j + 1 < sql.length() && sql.charAt(j + 1) == '"') {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    return -1;
  }

  static boolean wordStart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
  }

  static boolean wordPart(char c) {
    return wordStart(c) || (c >= '0' && c <= '9') || c == '$';
  }
}
