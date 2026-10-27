package ai.msrv.gaterook.connectors.logs;

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
    if (UNMASK.matcher(query).find()) {
      return Optional.of(Reach.UNMASK);
    }
    Optional<Reach> raw = command(query);
    return raw.isPresent() ? raw : command(COMMENT.matcher(query).replaceAll(" "));
  }

  private static Optional<Reach> command(String text) {
    Matcher m = COMMAND.matcher(text);
    while (m.find()) {
      int i = m.start() - 1;
      while (i >= 0 && (Character.isWhitespace(text.charAt(i)) || Character.isSpaceChar(text.charAt(i)))) {
        i--;
      }
      if (i < 0 || text.charAt(i) == '|' || text.charAt(i) == '(') {
        return Optional.of(m.group(1).toLowerCase(Locale.ROOT).equals("source") ? Reach.SOURCE : Reach.LOOKUP);
      }
    }
    return Optional.empty();
  }
}
