package ai.msrv.gaterook.connectors.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Glob patterns of policies and configs, matched without regular expressions: patterns come from people and
 * targets from agents, and a backtracking regex on {@code **a**a**a**b} is a denial of service. Matching is a dynamic
 * program over pattern × text, O(pattern · text).
 *
 * <ul>
 *   <li>{@link #text}: {@code *} matches any run of characters, including {@code /} and {@code :} — cloud resource
 *       masks such as {@code arn:aws:ecs:eu-central-1:*:service/stage/*} (report 09 §6);</li>
 *   <li>{@link #path}: {@code *} stays inside one {@code /} segment and {@code **} crosses segments — HTTP
 *       {@code allowedPaths} such as {@code /invoices/**}, {@code /refunds/*} (report 10 §4); a trailing
 *       {@code /**} also matches the parent itself ({@code /invoices}).</li>
 * </ul>
 * In both, {@code ?} is one character (in {@link #path}, not {@code /}); every other character is literal and
 * case-sensitive.
 */
public final class Glob {

  private enum Kind { LITERAL, ANY_ONE, STAR, DOUBLE_STAR }

  private record Token(Kind kind, char literal) {}

  private final String pattern;
  private final List<Token> tokens;
  private final boolean segmented;
  /** {@code /a/**} also matches {@code /a} itself, as in Ant and Spring path patterns. */
  private final Glob parent;

  private Glob(String pattern, boolean segmented) {
    this.pattern = Objects.requireNonNull(pattern, "pattern");
    this.segmented = segmented;
    this.tokens = tokenize(pattern, segmented);
    this.parent = segmented && pattern.endsWith("/**") && pattern.length() > 3
        ? new Glob(pattern.substring(0, pattern.length() - 3), true) : null;
  }

  /** {@code *} matches anything, separators included. */
  public static Glob text(String pattern) {
    return new Glob(pattern, false);
  }

  /** {@code *} within a segment, {@code **} across segments. */
  public static Glob path(String pattern) {
    return new Glob(pattern, true);
  }

  /** Whether any of {@code patterns} matches {@code value}, as {@link #text} globs. */
  public static boolean anyText(List<String> patterns, String value) {
    for (String p : patterns) {
      if (text(p).matches(value)) {
        return true;
      }
    }
    return false;
  }

  public boolean matches(String value) {
    if (value == null) {
      return false;
    }
    if (parent != null && parent.matches(value)) {
      return true;
    }
    int n = value.length();
    // reachable[j]: the tokens consumed so far can match value[0, j).
    boolean[] reachable = new boolean[n + 1];
    reachable[0] = true;
    for (Token token : tokens) {
      boolean[] next = new boolean[n + 1];
      switch (token.kind()) {
        case LITERAL -> {
          for (int j = 0; j < n; j++) {
            next[j + 1] = reachable[j] && value.charAt(j) == token.literal();
          }
        }
        case ANY_ONE -> {
          for (int j = 0; j < n; j++) {
            next[j + 1] = reachable[j] && !(segmented && value.charAt(j) == '/');
          }
        }
        case STAR -> {
          boolean run = false;
          for (int j = 0; j <= n; j++) {
            // A star extends a reachable position over characters it may cover.
            run = reachable[j] || (run && j > 0 && !(segmented && value.charAt(j - 1) == '/'));
            next[j] = run;
          }
        }
        case DOUBLE_STAR -> {
          boolean run = false;
          for (int j = 0; j <= n; j++) {
            run = run || reachable[j];
            next[j] = run;
          }
        }
      }
      reachable = next;
    }
    return reachable[n];
  }

  public String pattern() {
    return pattern;
  }

  private static List<Token> tokenize(String pattern, boolean segmented) {
    List<Token> out = new ArrayList<>();
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '*') {
        boolean twice = i + 1 < pattern.length() && pattern.charAt(i + 1) == '*';
        if (twice) {
          i++;
          while (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
            i++;
          }
        }
        Kind kind = segmented && twice ? Kind.DOUBLE_STAR : (segmented ? Kind.STAR : Kind.DOUBLE_STAR);
        // Collapse runs: "a**" in text mode or "***" are one token; they match the same strings.
        if (!out.isEmpty() && out.getLast().kind() == kind) {
          continue;
        }
        out.add(new Token(kind, '\0'));
      } else if (c == '?') {
        out.add(new Token(Kind.ANY_ONE, '\0'));
      } else {
        out.add(new Token(Kind.LITERAL, c));
      }
    }
    return out;
  }

  @Override
  public String toString() {
    return (segmented ? "Glob.path(" : "Glob.text(") + pattern + ")";
  }
}
