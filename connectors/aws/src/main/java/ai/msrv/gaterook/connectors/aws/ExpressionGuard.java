package ai.msrv.gaterook.connectors.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Metric math an agent may send in {@code aws_metrics} (report 09 §3.5 "Expression grammar"): arithmetic over the
 * call's own queries. CloudWatch's expression language also reaches outside the call — {@code LAMBDA()} invokes a
 * function, {@code SEARCH()} and {@code SELECT … FROM SCHEMA()} read any metric of the account, Insights rules,
 * Performance Insights and service quotas other APIs — so the guard is an allowlist of tokens, not a denylist:
 *
 * <ul>
 *   <li>ids of this call's queries ({@code m1}, {@code e1}), numbers, {@code + - * / ^ ( ) , [ ]}, comparisons and
 *       {@code AND}/{@code OR};</li>
 *   <li>the functions below, each followed by {@code (}, and the keywords {@code REPEAT LINEAR ASC DESC};</li>
 *   <li>a string only as the single argument of {@code METRICS("…")}, of letters, digits and {@code _ . : / -}.</li>
 * </ul>
 * Everything else is refused, including any character outside printable ASCII (homoglyphs, bidi controls).
 */
final class ExpressionGuard {

  static final Set<String> FUNCTIONS = Set.of("ABS", "AVG", "CEIL", "DATAPOINT_COUNT", "DIFF", "DIFF_TIME", "FILL",
      "FIRST", "LAST", "FLOOR", "IF", "LOG", "LOG10", "MAX", "METRIC_COUNT", "METRICS", "MIN", "MINUTE", "HOUR",
      "DAY", "DATE", "MONTH", "YEAR", "EPOCH", "PERIOD", "RATE", "REMOVE_EMPTY", "RUNNING_SUM", "SLICE", "SORT",
      "STDDEV", "SUM", "TIME_SERIES", "ANOMALY_DETECTION_BAND");
  /** Bare words: fill modes, sort orders, logic, and the statistics SORT and SLICE take by name. */
  static final Set<String> KEYWORDS = Set.of("REPEAT", "LINEAR", "ASC", "DESC", "AND", "OR", "AVG", "COUNT", "MAX",
      "MIN", "STDDEV", "SUM");
  /** Named so the refusal says why, not just "unknown". */
  private static final Set<String> OUTSIDE_THE_CALL = Set.of("LAMBDA", "SEARCH", "SELECT", "SCHEMA", "FROM",
      "WHERE", "GROUP", "ORDER", "BY", "LIMIT", "INSIGHT_RULE_METRIC", "DB_PERF_INSIGHTS", "SERVICE_QUOTA",
      "ANOMALY_DETECTION_MODEL");

  private static final java.util.regex.Pattern OUTSIDE_WORD = java.util.regex.Pattern.compile(
      "(?i)(?<![A-Za-z0-9_])(LAMBDA|SEARCH|SELECT|SCHEMA|INSIGHT_RULE_METRIC|DB_PERF_INSIGHTS|SERVICE_QUOTA"
          + "|ANOMALY_DETECTION_MODEL)(?![A-Za-z0-9_])");

  private enum Kind { NAME, NUMBER, STRING, SYMBOL }

  private record Token(Kind kind, String text, int at) {}

  /** An expression the guard refuses; the message says what and where. */
  static final class Rejected extends Exception {
    private static final long serialVersionUID = 1L;

    Rejected(String message) {
      super(message, null, false, false);
    }
  }

  private ExpressionGuard() {}

  /**
   * Checks one expression.
   *
   * @param ids the ids of this call's queries (metrics and expressions)
   * @throws Rejected with the reason, for {@code DENY cloud.metric_expression}
   */
  static void check(String expression, Set<String> ids) throws Rejected {
    if (expression == null || expression.isBlank()) {
      throw new Rejected("The expression is empty.");
    }
    java.util.regex.Matcher outside = OUTSIDE_WORD.matcher(expression);
    if (outside.find()) {
      throw new Rejected(outside.group(1).toUpperCase(Locale.ROOT) + " reads or runs something outside this call's "
          + "queries, so the gateway doesn't send it. Use arithmetic over the ids of the metrics you pass.");
    }
    List<Token> tokens = tokenize(expression);
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
      switch (t.kind()) {
        case NAME -> name(t, next, ids);
        case STRING -> {
          Token before = i >= 2 ? tokens.get(i - 2) : null;
          boolean metricsArgument = before != null && before.kind() == Kind.NAME
              && before.text().toUpperCase(Locale.ROOT).equals("METRICS") && tokens.get(i - 1).text().equals("(")
              && next != null && next.text().equals(")");
          if (!metricsArgument) {
            throw new Rejected("A string is allowed only as the argument of METRICS(\"…\") (at " + t.at() + ").");
          }
        }
        case SYMBOL -> {
          if (t.text().equals("(") || t.text().equals("[")) {
            depth++;
          } else if (t.text().equals(")") || t.text().equals("]")) {
            depth--;
            if (depth < 0) {
              throw new Rejected("Unbalanced " + t.text() + " at " + t.at() + ".");
            }
          }
        }
        case NUMBER -> {
          // Any number.
        }
      }
    }
    if (depth != 0) {
      throw new Rejected("Unbalanced brackets.");
    }
  }

  private static void name(Token t, Token next, Set<String> ids) throws Rejected {
    boolean call = next != null && next.text().equals("(");
    if (ids.contains(t.text()) && !call) {
      return;
    }
    String upper = t.text().toUpperCase(Locale.ROOT);
    if (OUTSIDE_THE_CALL.contains(upper)) {
      throw new Rejected(upper + " reads or runs something outside this call's queries, so the gateway doesn't send "
          + "it. Use arithmetic over the ids of the metrics you pass.");
    }
    if (KEYWORDS.contains(upper) && !call) {
      return;
    }
    if (FUNCTIONS.contains(upper)) {
      if (!call) {
        throw new Rejected(upper + " is a function: write " + upper + "(…).");
      }
      return;
    }
    throw new Rejected("\"" + t.text() + "\" is neither an id of this call's metrics (" + String.join(", ",
        new java.util.TreeSet<>(ids)) + ") nor an allowed function.");
  }

  private static List<Token> tokenize(String e) throws Rejected {
    List<Token> out = new ArrayList<>();
    int i = 0;
    int n = e.length();
    while (i < n) {
      char c = e.charAt(i);
      if (c == ' ' || c == '\t') {
        i++;
      } else if (c > 0x7e || c < 0x20) {
        throw new Rejected(String.format("The character U+%04X at %d isn't allowed in an expression.", (int) c, i));
      } else if (Character.isLetter(c) || c == '_') {
        int start = i;
        while (i < n && (isAsciiLetterOrDigit(e.charAt(i)) || e.charAt(i) == '_')) {
          i++;
        }
        out.add(new Token(Kind.NAME, e.substring(start, i), start));
      } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(e.charAt(i + 1)))) {
        int start = i;
        while (i < n && Character.isDigit(e.charAt(i))) {
          i++;
        }
        if (i < n && e.charAt(i) == '.') {
          i++;
          while (i < n && Character.isDigit(e.charAt(i))) {
            i++;
          }
        }
        if (i < n && (e.charAt(i) == 'e' || e.charAt(i) == 'E')) {
          int mark = i++;
          if (i < n && (e.charAt(i) == '+' || e.charAt(i) == '-')) {
            i++;
          }
          if (i >= n || !Character.isDigit(e.charAt(i))) {
            i = mark;
          } else {
            while (i < n && Character.isDigit(e.charAt(i))) {
              i++;
            }
          }
        }
        if (i < n && (Character.isLetter(e.charAt(i)) || e.charAt(i) == '_')) {
          throw new Rejected("\"" + e.substring(start, Math.min(n, i + 1)) + "…\" at " + start + " isn't a number "
              + "or a name.");
        }
        out.add(new Token(Kind.NUMBER, e.substring(start, i), start));
      } else if (c == '"') {
        int start = i++;
        while (i < n && e.charAt(i) != '"') {
          char s = e.charAt(i);
          if (!(isAsciiLetterOrDigit(s) || s == '_' || s == '.' || s == ':' || s == '/' || s == '-' || s == ' ')) {
            throw new Rejected("The string at " + start + " may hold letters, digits and _ . : / - only.");
          }
          i++;
        }
        if (i >= n) {
          throw new Rejected("The string at " + start + " isn't closed.");
        }
        i++;
        out.add(new Token(Kind.STRING, e.substring(start, i), start));
      } else {
        String two = i + 1 < n ? e.substring(i, i + 2) : "";
        if (two.equals("<=") || two.equals(">=") || two.equals("==") || two.equals("!=")) {
          out.add(new Token(Kind.SYMBOL, two, i));
          i += 2;
        } else if ("+-*/^(),[]<>".indexOf(c) >= 0) {
          out.add(new Token(Kind.SYMBOL, String.valueOf(c), i));
          i++;
        } else {
          throw new Rejected("\"" + c + "\" at " + i + " isn't allowed in an expression.");
        }
      }
    }
    return out;
  }

  private static boolean isAsciiLetterOrDigit(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
  }
}
