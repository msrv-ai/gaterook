package com.gaterook.connectors.logs;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Logs Insights query (CWLI) reads beyond the log groups of its connection, judged from its text without I/O
 * (report 07 §3.5; the AWS query syntax as of 2026):
 * <ul>
 *   <li>{@code SOURCE} names log groups, data sources or tags inside the query. It starts a query or a sub-query: at
 *       the beginning, after a pipe, or after an opening parenthesis — {@code join … (SOURCE '/aws/lambda/x')},
 *       {@code filter id in (SOURCE '/other' | fields id)}, {@code appendcols (SOURCE lg | stats …)};</li>
 *   <li>{@code lookup} enriches the result from a lookup table of the account;</li>
 *   <li>{@code unmask} shows what a data protection policy of the log group masked (the gateway reads with
 *       {@code unmask=false}, and an assumed role's session policy denies {@code logs:Unmask}).</li>
 * </ul>
 * A comment ({@code #} to the end of the line) may stand between a pipe or a parenthesis and the command. The text is
 * read twice — as it is, and with every {@code #…} removed — and either reading finding a command refuses the query:
 * whether a {@code #} or a quote inside a regular expression or a string starts something is the backend's grammar,
 * so the check doesn't guess it and errs on the side of refusing (a field called {@code source} is still fine in
 * {@code fields source} or {@code filter source = "api"}).
 *
 * <p>Characters that draw as nothing (format characters such as a BOM or a zero-width space, other default-ignorable
 * code points, controls, combining marks) are read both ways too: as they are, and removed, in case the backend skips
 * them. An opening bracket starts a sub-query like a parenthesis does.
 */
final class InsightsQuery {

  /** What the query reaches, as the last segment of the policy rule ({@code <env>.logs.<rule>}). */
  enum Reach {
    SOURCE("cw_source_command"), LOOKUP("cw_lookup"), UNMASK("cw_unmask");

    final String rule;

    Reach(String rule) {
      this.rule = rule;
    }
  }

  /** A command word at the start of the text or of a (sub-)query: after a pipe or an opening parenthesis. */
  private static final Pattern COMMAND = Pattern.compile("(?i)(?<![\\w@.$])(source|lookup)(?![\\w@.$])");
  private static final Pattern UNMASK = Pattern.compile("(?i)(?<![\\w@.$])unmask(?![\\w@.$])");
  private static final Pattern COMMENT = Pattern.compile("#[^\\r\\n]*");

  private InsightsQuery() {}

  /** The first way {@code query} reaches outside its log groups, or empty. */
  static Optional<Reach> outside(String query) {
    for (String text : List.of(query, withoutInvisible(query))) {
      if (UNMASK.matcher(text).find()) {
        return Optional.of(Reach.UNMASK);
      }
      Optional<Reach> raw = command(text);
      if (raw.isPresent()) {
        return raw;
      }
      Optional<Reach> uncommented = command(COMMENT.matcher(text).replaceAll(" "));
      if (uncommented.isPresent()) {
        return uncommented;
      }
    }
    return Optional.empty();
  }

  private static Optional<Reach> command(String text) {
    Matcher m = COMMAND.matcher(text);
    while (m.find()) {
      int i = m.start() - 1;
      while (i >= 0 && (Character.isWhitespace(text.charAt(i)) || Character.isSpaceChar(text.charAt(i))
          || invisible(text.charAt(i)))) {
        i--;
      }
      if (i < 0 || text.charAt(i) == '|' || text.charAt(i) == '(' || text.charAt(i) == '[') {
        return Optional.of(m.group(1).toLowerCase(Locale.ROOT).equals("source") ? Reach.SOURCE : Reach.LOOKUP);
      }
    }
    return Optional.empty();
  }

  /** The text without what draws as nothing, as a backend that skips those characters reads it. */
  private static String withoutInvisible(String text) {
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints().filter(cp -> cp == '\n' || cp == '\r' || cp == '\t' || !invisible(cp))
        .forEach(out::appendCodePoint);
    return out.toString();
  }

  /**
   * Format and control characters, combining marks, unassigned and private-use code points, the Hangul fillers and
   * the other default-ignorable code points that aren't format characters (variation selectors are marks).
   */
  private static boolean invisible(int cp) {
    int type = Character.getType(cp);
    return type == Character.FORMAT || type == Character.CONTROL || type == Character.NON_SPACING_MARK
        || type == Character.ENCLOSING_MARK || type == Character.UNASSIGNED || type == Character.PRIVATE_USE
        || type == Character.SURROGATE || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0;
  }
}
