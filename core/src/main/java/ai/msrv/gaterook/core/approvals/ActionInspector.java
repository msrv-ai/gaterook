package ai.msrv.gaterook.core.approvals;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Makes the exact action safe to read (ARCHITECTURE.md §8.5, §9 "Prompt-injection-safe approvals"; the complete action must remain visible): the text an approver sees is cut into segments the UI only paints —
 * <ul>
 *   <li>{@code invisible}: control characters (tab included), bidi controls U+202A–202E and U+2066–2069, every
 *       default-ignorable code point (zero-width, variation selectors, tags, fillers), unassigned, private-use and
 *       surrogate code points, blank-looking characters, a combining mark without a letter or digit to sit on, and
 *       trailing spaces of a line; shown as their code point {@code ‹U+202E›}, inside string literals too (Trojan
 *       Source works there);</li>
 *   <li>{@code confusable}: a non-ASCII letter outside a string literal (a DELETE look-alike containing Cyrillic U+0415),
 *       where it can pass for a keyword or a name; inside a literal, a letter that passes for an ASCII one in a word
 *       with ASCII letters (a host, a field name), in a word of such letters among ASCII words between the same
 *       spaces (a host's label), or in a literal whose every non-ASCII letter is one (a status value);</li>
 *   <li>{@code text}: everything else, as is.</li>
 * </ul>
 * String literals depend on the language: SQL has {@code '…'} (a double-quoted SQL identifier is a name, so a
 * homoglyph there is suspicious); shell, JSON, YAML and HTTP bodies have {@code '…'} and {@code "…"}, with
 * {@code \"} inside double quotes; a quoted JSON, YAML or HTTP-body key is a name, not a literal. Comments are not
 * literals and their quotes open nothing: SQL {@code --} to the end
 * of the line, {@code /* … *}{@code /} and MySQL's {@code #}, shell and YAML {@code #} at the start of a word. Where
 * the dialects of a language read quotes differently (PostgreSQL, MySQL or SQL Server, POSIX sh or bash), a character
 * is inside a literal only if all of them agree. A literal still open at the end of the text is no literal: an apostrophe can't
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
    boolean[] lookAlike = literalLookAlikes(text, literal);
    List<Segment> out = new ArrayList<>();
    StringBuilder run = new StringBuilder();
    int hidden = 0;
    int confusable = 0;
    int trailingFrom = trailingSpacesStart(text);
    int base = -1;
    int i = 0;
    while (i < text.length()) {
      int cp = text.codePointAt(i);
      int len = Character.charCount(cp);
      boolean newline = cp == '\n' || (cp == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n');
      boolean trailingSpace = cp == ' ' && isTrailing(text, i, trailingFrom);
      if (!newline && (invisible(cp, base) || trailingSpace)) {
        flush(run, out);
        out.add(new Segment("invisible", String.format(Locale.ROOT, "‹U+%04X›", cp)));
        hidden++;
      } else if (Character.isLetter(cp) && cp > 0x7f && (!literal[i] || lookAlike[i])) {
        flush(run, out);
        out.add(new Segment("confusable", new String(Character.toChars(cp))));
        confusable++;
      } else {
        run.appendCodePoint(cp);
      }
      if (!mark(cp)) {
        base = cp;
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
   * @param sqlServerIdentifiers bracketed and double-quoted names contain no string literals (SQL Server)
   * @param keys             a quoted string followed by {@code :} is an object key, a name (JSON, YAML)
   */
  private record Lexer(boolean dashComments, boolean dashNeedsSpace, boolean blockComments, boolean nestedBlocks,
      Hash hash, boolean doubleStrings, boolean singleEscapes, boolean doubleEscapes, char prefixEscapes,
      boolean dollarQuotes, boolean escapeOutside, boolean runnableComments, boolean substitutions,
      boolean sqlServerIdentifiers, boolean keys) {}

  /** Standard SQL: {@code '…'} with {@code ''}, {@code --} and flat block comments; {@code "…"} names. */
  private static final Lexer ANSI_SQL = new Lexer(true, false, true, false, Hash.NONE, false, false, false, (char) 0,
      false, false, false, false, false, false);
  private static final Lexer POSTGRES = new Lexer(true, false, true, true, Hash.NONE, false, false, false, 'E', true,
      false, false, false, false, false);
  private static final Lexer MYSQL = new Lexer(true, true, true, false, Hash.ANYWHERE, true, true, true, (char) 0,
      false, false, true, false, false, false);
  private static final Lexer SQL_SERVER = new Lexer(true, false, true, true, Hash.NONE, false, false, false, (char) 0,
      false, false, false, false, true, false);
  private static final Lexer POSIX_SH = new Lexer(false, false, false, false, Hash.WORD_START, true, false, true,
      (char) 0, false, true, false, true, false, false);
  private static final Lexer BASH = new Lexer(false, false, false, false, Hash.WORD_START, true, false, true, '$',
      false, true, false, true, false, false);
  private static final Lexer YAML = new Lexer(false, false, false, false, Hash.WORD_START, true, false, true, (char) 0,
      false, false, false, false, false, true);
  private static final Lexer JSON = new Lexer(false, false, false, false, Hash.NONE, true, false, true, (char) 0,
      false, false, false, false, false, true);
  private static final Lexer PLAIN = new Lexer(false, false, false, false, Hash.NONE, true, false, true, (char) 0,
      false, false, false, false, false, false);

  /**
   * Which characters of {@code text} are inside a string literal of {@code language} (the quotes themselves are not).
   * The action names a language, not a dialect ({@code sql} includes PostgreSQL, MySQL and SQL Server,
   * {@code sh} is any shell), and a dialect's quirk read the other way hides text: MySQL's {@code #} comment,
   * backslash escapes and the code inside {@code /*! … *}{@code /}, PostgreSQL's nested comments,
   * {@code E'…'} and dollar quotes, SQL Server's bracketed
   * identifiers, bash's {@code $'…'}. So a character counts as inside a literal only when every dialect of the
   * language agrees; any disagreement leaves it checked, which can only add warnings.
   * Comments are skipped, so their quotes open nothing; a literal still open at the end is not one.
   */
  static boolean[] literals(String text, String language) {
    String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
    List<Lexer> dialects = switch (lang) {
      case "sql" -> List.of(ANSI_SQL, POSTGRES, MYSQL, SQL_SERVER);
      case "sh", "bash", "shell" -> List.of(POSIX_SH, BASH);
      case "yaml", "yml" -> List.of(YAML);
      case "json", "http" -> List.of(JSON);
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
      } else if (lx.sqlServerIdentifiers() && (c == '[' || c == '"')) {
        i = endOfIdentifier(text, i, c == '[' ? ']' : '"');
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
        if (!lx.keys() || !keyEnd(text, j + 1)) {
          java.util.Arrays.fill(in, i + 1, j, true);
        }
        i = j + 1;
      } else {
        i++;
      }
    }
    return in;
  }

  /** SQL Server names escape their closing bracket or quote by doubling it; apostrophes remain identifier text. */
  private static int endOfIdentifier(String text, int from, char close) {
    int i = from + 1;
    while (i < text.length()) {
      if (text.charAt(i++) == close) {
        if (i < text.length() && text.charAt(i) == close) {
          i++;
        } else {
          return i;
        }
      }
    }
    return text.length();
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

  /** Whether a quote that closed just before {@code from} ends an object key: a {@code :} follows. */
  private static boolean keyEnd(String text, int from) {
    int i = from;
    while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
      i++;
    }
    return i < text.length() && text.charAt(i) == ':';
  }

  /**
   * Control characters except line breaks, format characters (bidi, zero-width, BOM), every default-ignorable code
   * point, unassigned, private-use and surrogate code points, NBSP-likes, the blank braille pattern.
   */
  static boolean invisible(int cp) {
    if (cp == '\n') {
      return false;
    }
    int type = Character.getType(cp);
    return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR || (type == Character.SPACE_SEPARATOR && cp != ' ')
        || type == Character.UNASSIGNED || type == Character.PRIVATE_USE || type == Character.SURROGATE
        || defaultIgnorable(cp) || cp == 0x2800;
  }

  /**
   * {@link #invisible(int)}, and a combining mark that has no letter or digit to sit on: after a space, a quote or
   * {@code #} it draws next to nothing, while the shell or SQL reads it as part of a word. A text or emoji
   * presentation selector after a symbol (U+2764 U+FE0F) only picks how the symbol is drawn.
   */
  static boolean invisible(int cp, int base) {
    if (cp == 0xFE0E || cp == 0xFE0F) {
      return base < 0 || Character.getType(base) != Character.OTHER_SYMBOL;
    }
    return invisible(cp) || (mark(cp) && (base < 0 || !Character.isLetterOrDigit(base)));
  }

  private static boolean mark(int cp) {
    int type = Character.getType(cp);
    return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
        || type == Character.COMBINING_SPACING_MARK;
  }

  /** Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties.txt): drawn as nothing when not supported. */
  static boolean defaultIgnorable(int cp) {
    return cp == 0x00AD || cp == 0x034F || cp == 0x061C || (cp >= 0x115F && cp <= 0x1160)
        || (cp >= 0x17B4 && cp <= 0x17B5) || (cp >= 0x180B && cp <= 0x180F) || (cp >= 0x200B && cp <= 0x200F)
        || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2060 && cp <= 0x206F) || cp == 0x3164
        || (cp >= 0xFE00 && cp <= 0xFE0F) || cp == 0xFEFF || cp == 0xFFA0 || (cp >= 0xFFF0 && cp <= 0xFFF8)
        || (cp >= 0x1BCA0 && cp <= 0x1BCA3) || (cp >= 0x1D173 && cp <= 0x1D17A) || (cp >= 0xE0000 && cp <= 0xE0FFF);
  }

  /**
   * Letters of other scripts that are drawn like ASCII ones (UTS #39 confusables): Cyrillic, Greek and Armenian
   * look-alikes and Latin letters that pass for plain ones (long s, script g, IPA small capitals). Turkish dotless i
   * is left out: it is ordinary text there.
   */
  private static final Set<Integer> LOOK_ALIKES = Set.of(
      0x0405, 0x0406, 0x0408, 0x0410, 0x0412, 0x0415, 0x041A, 0x041C, 0x041D, 0x041E, 0x0420, 0x0421, 0x0422,
      0x0423, 0x0425, 0x0430, 0x0435, 0x043E, 0x0440, 0x0441, 0x0443, 0x0445, 0x0455, 0x0456, 0x0458, 0x04AE,
      0x04AF, 0x04BA, 0x04BB, 0x04C0, 0x04CF, 0x0500, 0x0501, 0x051A, 0x051B, 0x051C, 0x051D,
      0x0391, 0x0392, 0x0395, 0x0396, 0x0397, 0x0399, 0x039A, 0x039C, 0x039D, 0x039F, 0x03A1, 0x03A4, 0x03A5,
      0x03A7, 0x03B1, 0x03B3, 0x03B9, 0x03BA, 0x03BD, 0x03BF, 0x03C1, 0x03C5, 0x03C7, 0x03C9, 0x03F2, 0x03F3,
      0x03F9, 0x0566, 0x0570, 0x0578, 0x057D, 0x0585,
      0x017F, 0x01C0, 0x0237, 0x0251, 0x0261, 0x0269, 0x026A, 0x0274, 0x0280, 0x028F, 0x029F);

  /** A letter that passes for an ASCII one: listed, Cherokee, a small capital, or a compatibility form (ｅ, 𝐞, ℯ). */
  static boolean lookAlike(int cp) {
    if (cp < 0x80) {
      return false;
    }
    if (LOOK_ALIKES.contains(cp) || (cp >= 0x13A0 && cp <= 0x13F5) || (cp >= 0xAB70 && cp <= 0xABBF)
        || (cp >= 0x1D00 && cp <= 0x1D2B)) {
      return true;
    }
    String folded = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC);
    return !folded.isEmpty() && folded.chars().allMatch(c -> c < 0x80 && Character.isLetterOrDigit(c));
  }

  /**
   * The look-alike letters inside literals that can change what the action does: in a word that also has ASCII
   * letters ({@code stаtus}, {@code gіthub}), in a word of look-alikes among ASCII words between the same spaces (the
   * label {@code аре} of {@code https://аре.example.com/Б}), or in a literal whose every non-ASCII letter is one
   * ({@code 'аре'}). Text of another script with letters of its own ({@code 'Ёжик'}, {@code 'Встреча с клиентом'},
   * {@code https://example.com/путь}) is data; so is a word of look-alikes standing alone in such text.
   */
  private static boolean[] literalLookAlikes(String text, boolean[] literal) {
    boolean[] out = new boolean[text.length()];
    int i = 0;
    while (i < text.length()) {
      if (!literal[i]) {
        i++;
        continue;
      }
      int start = i;
      boolean all = true;
      while (i < text.length() && literal[i]) {
        int c = text.codePointAt(i);
        all &= !Character.isLetter(c) || c < 0x80 || lookAlike(c);
        i += Character.charCount(c);
      }
      lookAlikeWords(text, start, i, all, out);
    }
    return out;
  }

  /**
   * Marks the look-alikes of {@code [from, to)}: all of them, or those in words with ASCII letters, or those in words
   * made only of look-alikes within a run between spaces that has ASCII letters (a host, a path, an identifier).
   */
  private static void lookAlikeWords(String text, int from, int to, boolean all, boolean[] out) {
    int i = from;
    while (i < to) {
      int runEnd = i;
      boolean asciiRun = false;
      while (runEnd < to && !Character.isWhitespace(text.codePointAt(runEnd))) {
        int c = text.codePointAt(runEnd);
        asciiRun |= c < 0x80 && Character.isLetter(c);
        runEnd += Character.charCount(c);
      }
      while (i < runEnd) {
        int start = i;
        boolean ascii = false;
        boolean onlyLookAlikes = true;
        while (i < runEnd && wordPart(text.codePointAt(i))) {
          int c = text.codePointAt(i);
          if (Character.isLetter(c)) {
            ascii |= c < 0x80;
            onlyLookAlikes &= c < 0x80 || lookAlike(c);
          }
          i += Character.charCount(c);
        }
        boolean suspect = all || ascii || (onlyLookAlikes && asciiRun);
        for (int j = start; j < i; j += Character.charCount(text.codePointAt(j))) {
          out[j] = suspect && lookAlike(text.codePointAt(j));
        }
        if (i == start) {
          i += Character.charCount(text.codePointAt(i));
        }
      }
      while (i < to && Character.isWhitespace(text.codePointAt(i))) {
        i += Character.charCount(text.codePointAt(i));
      }
    }
  }

  private static boolean wordPart(int cp) {
    return Character.isLetterOrDigit(cp) || cp == '_' || mark(cp);
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
