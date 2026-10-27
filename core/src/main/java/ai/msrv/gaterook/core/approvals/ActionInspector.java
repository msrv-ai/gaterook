package ai.msrv.gaterook.core.approvals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Makes the exact action safe to read (ARCHITECTURE.md §8.5, §9 "Prompt-injection-safe approvals"; kit DESIGN §8
 * "The complete action must remain visible"): the text an approver sees is cut into segments the UI only paints —
 * <ul>
 *   <li>{@code invisible}: control characters (tab included), bidi controls U+202A–202E and U+2066–2069, zero-width
 *       and other format characters, trailing spaces of a line; shown as their code point {@code ‹U+202E›}, inside
 *       string literals too (Trojan Source works there);</li>
 *   <li>{@code confusable}: a non-ASCII letter outside a string literal (a DELETE look-alike containing Cyrillic U+0415), where it
 *       can pass for a keyword or a name;</li>
 *   <li>{@code text}: everything else, as is.</li>
 * </ul>
 * String literals depend on the language: SQL has {@code '…'} (a double-quoted SQL identifier is a name, so a
 * homoglyph there is suspicious); shell, JSON, YAML and HTTP bodies have {@code '…'} and {@code "…"}, with
 * {@code \"} inside double quotes. Comments are not literals and their quotes open nothing: SQL {@code --} to the end
 * of the line, {@code /* … *}{@code /} and MySQL's {@code #}, shell and YAML {@code #} at the start of a word. Where
 * the dialects of a language read quotes differently (PostgreSQL or MySQL, POSIX sh or bash), a character is inside a
 * literal only if all of them agree. A literal still open at the end of the text is no literal: an apostrophe can't
 * hide the rest of the action. A message with any such segment never offers Approve in a channel (§7.4).
 */
public final class ActionInspector {

  /** One run of the text. {@code t}: {@code text}, {@code invisible} or {@code confusable}. */
  public record Segment(String t, String v) {}

  /** The segments and what they found. */
  public record Inspection(List<Segment> segments, int hiddenChars, int confusables) {

    /** Nothing hidden and nothing that passes for something else. */
    public boolean clean() {
      return hiddenChars == 0 && confusables == 0;
    }

    /** The kit's warning above the block ("Contains 2 hidden characters…"), or {@code null}. */
    public String warning() {
      if (clean()) {
        return null;
      }
      List<String> parts = new ArrayList<>();
      if (hiddenChars > 0) {
        parts.add(hiddenChars + (hiddenChars == 1 ? " hidden character" : " hidden characters"));
      }
      if (confusables > 0) {
        parts.add(confusables + (confusables == 1 ? " letter that looks like another" : " letters that look like "
            + "others"));
      }
      return "Contains " + String.join(" and ", parts) + ". They are shown as codes and highlighted: check the action "
          + "before you approve it.";
    }
  }

  private ActionInspector() {}

  public static Inspection inspect(String text, String language) {
    if (text == null || text.isEmpty()) {
      return new Inspection(List.of(), 0, 0);
    }
    boolean[] literal = literals(text, language);
    List<Segment> out = new ArrayList<>();
    StringBuilder run = new StringBuilder();
    int hidden = 0;
    int confusable = 0;
    int trailingFrom = trailingSpacesStart(text);
    int i = 0;
    while (i < text.length()) {
      int cp = text.codePointAt(i);
      int len = Character.charCount(cp);
      boolean newline = cp == '\n' || (cp == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n');
      boolean trailingSpace = cp == ' ' && isTrailing(text, i, trailingFrom);
      if (!newline && (invisible(cp) || trailingSpace)) {
        flush(run, out);
        out.add(new Segment("invisible", String.format(Locale.ROOT, "‹U+%04X›", cp)));
        hidden++;
      } else if (!literal[i] && Character.isLetter(cp) && cp > 0x7f) {
        flush(run, out);
        out.add(new Segment("confusable", new String(Character.toChars(cp))));
        confusable++;
      } else {
        run.appendCodePoint(cp);
      }
      i += len;
    }
    flush(run, out);
    return new Inspection(List.copyOf(out), hidden, confusable);
  }

  /** Where a {@code #} starts a comment: nowhere, at the start of a word (shell, YAML), anywhere (MySQL). */
  private enum Hash { NONE, WORD_START, ANYWHERE }

  /**
   * How one dialect reads quotes and comments.
   *
   * @param dashComments     {@code --} to the end of the line
   * @param dashNeedsSpace   only {@code -- } followed by whitespace (MySQL: {@code 1--1} is arithmetic)
   * @param blockComments    {@code /* … *}{@code /}
   * @param nestedBlocks     block comments nest (PostgreSQL)
   * @param doubleStrings    {@code "…"} is a string, not an identifier
   * @param singleEscapes    a backslash escapes inside {@code '…'} (MySQL)
   * @param doubleEscapes    a backslash escapes inside {@code "…"}
   * @param prefixEscapes    the prefix before {@code '} that turns backslash escapes on ({@code E'…'} in PostgreSQL,
   *                         {@code $'…'} in bash), or {@code 0}
   * @param dollarQuotes     {@code $tag$…$tag$} is a string (PostgreSQL)
   * @param escapeOutside    a backslash outside quotes makes the next character plain (shell: {@code \'} opens nothing)
   * @param runnableComments {@code /*! … *}{@code /} is code, not a comment (MySQL runs it): its quotes count
   * @param substitutions    {@code $(…)}, {@code ${…}} and backticks inside {@code "…"} are code (shell)
   */
  private record Lexer(boolean dashComments, boolean dashNeedsSpace, boolean blockComments, boolean nestedBlocks,
      Hash hash, boolean doubleStrings, boolean singleEscapes, boolean doubleEscapes, char prefixEscapes,
      boolean dollarQuotes, boolean escapeOutside, boolean runnableComments, boolean substitutions) {}

  /** Standard SQL: {@code '…'} with {@code ''}, {@code --} and flat block comments; {@code "…"} names. */
  private static final Lexer ANSI_SQL = new Lexer(true, false, true, false, Hash.NONE, false, false, false, (char) 0,
      false, false, false, false);
  private static final Lexer POSTGRES = new Lexer(true, false, true, true, Hash.NONE, false, false, false, 'E', true,
      false, false, false);
  private static final Lexer MYSQL = new Lexer(true, true, true, false, Hash.ANYWHERE, true, true, true, (char) 0,
      false, false, true, false);
  private static final Lexer POSIX_SH = new Lexer(false, false, false, false, Hash.WORD_START, true, false, true,
      (char) 0, false, true, false, true);
  private static final Lexer BASH = new Lexer(false, false, false, false, Hash.WORD_START, true, false, true, '$',
      false, true, false, true);
  private static final Lexer YAML = new Lexer(false, false, false, false, Hash.WORD_START, true, false, true, (char) 0,
      false, false, false, false);
  private static final Lexer PLAIN = new Lexer(false, false, false, false, Hash.NONE, true, false, true, (char) 0,
      false, false, false, false);

  /**
   * Which characters of {@code text} are inside a string literal of {@code language} (the quotes themselves are not).
   * The action names a language, not a dialect ({@code sql} is PostgreSQL or MySQL, {@code sh} is any shell), and a
   * dialect's quirk read the other way hides text: MySQL's {@code #} comment, backslash escapes and the code inside
   * {@code /*! … *}{@code /}, PostgreSQL's nested comments, {@code E'…'} and dollar quotes, bash's {@code $'…'}. So a
   * character counts as inside a literal only when every dialect of the language agrees; any disagreement leaves it
   * checked, which can only add warnings.
   * Comments are skipped, so their quotes open nothing; a literal still open at the end is not one.
   */
  static boolean[] literals(String text, String language) {
    String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
    List<Lexer> dialects = switch (lang) {
      case "sql" -> List.of(ANSI_SQL, POSTGRES, MYSQL);
      case "sh", "bash", "shell" -> List.of(POSIX_SH, BASH);
      case "yaml", "yml" -> List.of(YAML);
      default -> List.of(PLAIN);
    };
    boolean[] in = null;
    for (Lexer lexer : dialects) {
      boolean[] one = literals(text, lexer);
      if (in == null) {
        in = one;
      } else {
        for (int i = 0; i < in.length; i++) {
          in[i] &= one[i];
        }
      }
    }
    return in;
  }

  private static boolean[] literals(String text, Lexer lx) {
    int n = text.length();
    boolean[] in = new boolean[n];
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      char next = i + 1 < n ? text.charAt(i + 1) : 0;
      if (lx.escapeOutside() && c == '\\') {
        i += 2;
      } else if (lx.dashComments() && c == '-' && next == '-'
          && (!lx.dashNeedsSpace() || i + 2 >= n || Character.isWhitespace(text.charAt(i + 2)))) {
        i = endOfLine(text, i);
      } else if (c == '#' && (lx.hash() == Hash.ANYWHERE
          || (lx.hash() == Hash.WORD_START && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))))) {
        i = endOfLine(text, i);
      } else if (lx.runnableComments() && c == '/' && next == '*' && i + 2 < n && text.charAt(i + 2) == '!') {
        i += 3;                            // /*!50100 … */: what follows is read as code, its */ as plain text
      } else if (lx.blockComments() && c == '/' && next == '*') {
        i = endOfBlock(text, i, lx.nestedBlocks());
      } else if (lx.dollarQuotes() && c == '$' && dollarTag(text, i) != null) {
        String tag = dollarTag(text, i);
        int close = text.indexOf(tag, i + tag.length());
        if (close < 0) {
          return in;                       // never closed: no literal, and nothing after it is one either
        }
        java.util.Arrays.fill(in, i + tag.length(), close, true);
        i = close + tag.length();
      } else if (c == '\'' || (c == '"' && lx.doubleStrings())) {
        boolean escapes = c == '\'' ? lx.singleEscapes() || prefixed(text, i, lx.prefixEscapes()) : lx.doubleEscapes();
        int j = i + 1;
        while (j < n && text.charAt(j) != c) {
          if (c == '"' && lx.substitutions() && substitution(text, j)) {
            // $(…), ${…} and `…` run as code inside "…", with quotes of their own: from here on nothing is trusted
            // to be a literal.
            java.util.Arrays.fill(in, i + 1, j, true);
            return in;
          }
          j += escapes && text.charAt(j) == '\\' ? 2 : 1;
        }
        if (j >= n) {
          return in;                       // an open literal is no literal: the rest of the action stays checked
        }
        java.util.Arrays.fill(in, i + 1, j, true);
        i = j + 1;
      } else {
        i++;
      }
    }
    return in;
  }

  /** A shell substitution starts at {@code at}: {@code $(}, {@code ${} or a backtick. */
  private static boolean substitution(String text, int at) {
    char c = text.charAt(at);
    char next = at + 1 < text.length() ? text.charAt(at + 1) : 0;
    return c == '`' || (c == '$' && (next == '(' || next == '{'));
  }

  private static int endOfLine(String text, int from) {
    int eol = text.indexOf('\n', from);
    return eol < 0 ? text.length() : eol;
  }

  /** Index after the block comment that starts at {@code from}; the end of the text if it never closes. */
  private static int endOfBlock(String text, int from, boolean nested) {
    int depth = 0;
    int i = from;
    while (i + 1 < text.length()) {
      if (text.charAt(i) == '/' && text.charAt(i + 1) == '*' && (nested || depth == 0)) {
        depth++;
        i += 2;
      } else if (text.charAt(i) == '*' && text.charAt(i + 1) == '/') {
        if (--depth == 0) {
          return i + 2;
        }
        i += 2;
      } else {
        i++;
      }
    }
    return text.length();
  }

  /** PostgreSQL's {@code $tag$} at {@code at} (the tag may be empty), or {@code null}: {@code $1} is a parameter. */
  private static String dollarTag(String text, int at) {
    if (at > 0 && (Character.isLetterOrDigit(text.charAt(at - 1)) || text.charAt(at - 1) == '_'
        || text.charAt(at - 1) == '$')) {
      return null;                         // inside a name: a$b$ is an identifier
    }
    int j = at + 1;
    while (j < text.length() && (Character.isLetter(text.charAt(j)) || text.charAt(j) == '_'
        || (j > at + 1 && Character.isDigit(text.charAt(j))))) {
      j++;
    }
    return j < text.length() && text.charAt(j) == '$' ? text.substring(at, j + 1) : null;
  }

  /** Whether the quote at {@code at} has {@code prefix} right before it, as a word of its own ({@code E'…'}). */
  private static boolean prefixed(String text, int at, char prefix) {
    if (prefix == 0 || at == 0 || Character.toUpperCase(text.charAt(at - 1)) != prefix) {
      return false;
    }
    return at == 1 || !(Character.isLetterOrDigit(text.charAt(at - 2)) || text.charAt(at - 2) == '_');
  }

  /** Control characters except line breaks, format characters (bidi, zero-width, BOM), soft hyphen, NBSP-likes. */
  static boolean invisible(int cp) {
    if (cp == '\n') {
      return false;
    }
    int type = Character.getType(cp);
    return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR || (type == Character.SPACE_SEPARATOR && cp != ' ')
        || cp == 0x00AD || (cp >= 0x2060 && cp <= 0x2064) || cp == 0xFEFF || cp == 0x115F || cp == 0x1160
        || cp == 0x3164 || cp == 0xFFA0;
  }

  /** Index after which every space up to the end is trailing, for the text's last line. */
  private static int trailingSpacesStart(String text) {
    int end = text.length();
    while (end > 0 && text.charAt(end - 1) == ' ') {
      end--;
    }
    return end;
  }

  /** A space is trailing when only spaces follow it before the line break or the end. */
  private static boolean isTrailing(String text, int at, int lastLineTrailing) {
    if (at >= lastLineTrailing) {
      return true;
    }
    int j = at;
    while (j < text.length() && text.charAt(j) == ' ') {
      j++;
    }
    return j < text.length() && (text.charAt(j) == '\n' || text.charAt(j) == '\r');
  }

  private static void flush(StringBuilder run, List<Segment> out) {
    if (!run.isEmpty()) {
      out.add(new Segment("text", run.toString()));
      run.setLength(0);
    }
  }
}
