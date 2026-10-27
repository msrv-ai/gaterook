package ai.msrv.gaterook.core.platform.mask;

import java.util.List;

/**
 * The 16 secret rules of the masker (report 12 §7.7; patterns after gitleaks, MIT). Each guards its regex with
 * literals; groups keep the non-secret part readable so the agent still understands what it got
 * ({@code PASSWORD ‹masked›}, {@code postgres://app:‹masked›@db.dev.internal}).
 */
public final class SecretRules {

  /** Never re-mask a mask: rules run one after another over the same text. */
  private static final String NOT_MASKED = "(?!‹masked›)";

  public static final List<TextRule> ALL = List.of(
      TextRule.of("pem-private-key",
          "-----BEGIN [A-Z0-9 ]{0,30}PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z0-9 ]{0,30}PRIVATE KEY-----|\\z)", 0, 41, 16384,
          "private key-----"),
      TextRule.of("aws-access-key-id",
          "(?<![A-Z0-9])(?:AKIA|ASIA|ABIA|ACCA)[A-Z0-9]{16}(?![A-Z0-9])", 0, 0, 24,
          "akia", "asia", "abia", "acca"),
      TextRule.of("aws-secret-key",
          "(?i)(aws[\\w.-]{0,30}?(?:secret|sk)[\\w.-]{0,30}?[\"']?\\s*[:=]\\s*[\"']?)" + NOT_MASKED
              + "([A-Za-z0-9/+]{40})(?![A-Za-z0-9/+=])", 2, 0, 160,
          "aws"),
      TextRule.of("jwt",
          "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{5,}\\.eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{8,}", 0, 0, 16384,
          "eyj"),
      TextRule.of("gateway-token",
          "(?<![A-Za-z0-9_])gw[tbor]_[A-Za-z0-9_-]{20,}", 0, 0, 320,
          "gwt_", "gwb_", "gwo_", "gwr_"),
      TextRule.of("github",
          "(?<![A-Za-z0-9_])(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{22,255})", 0, 0, 320,
          "ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_"),
      TextRule.of("slack",
          "(?<![A-Za-z0-9])(?:xox[abposr]-[A-Za-z0-9-]{10,}|xapp-\\d-[A-Za-z0-9-]{10,})", 0, 0, 320,
          "xox", "xapp-"),
      TextRule.of("google-api-key",
          "(?<![A-Za-z0-9_-])AIza[0-9A-Za-z_-]{35}(?![0-9A-Za-z_-])", 0, 0, 48,
          "aiza"),
      TextRule.of("gcp-oauth",
          "(?<![A-Za-z0-9_.-])ya29\\.[0-9A-Za-z_-]{20,}", 0, 0, 4096,
          "ya29."),
      TextRule.of("vault-token",
          "(?<![A-Za-z0-9_.-])hv[sbr]\\.[A-Za-z0-9_-]{20,}", 0, 0, 320,
          "hvs.", "hvb.", "hvr."),
      TextRule.of("stripe",
          "(?<![A-Za-z0-9_])[sr]k_(?:live|test)_[0-9A-Za-z]{16,}", 0, 1, 320,
          "k_live_", "k_test_"),
      TextRule.of("bearer-token",
          "(?i)(?<![a-z])(bearer|token)[\"']?(\\s+|\\s*[=:]\\s*[\"']?)" + NOT_MASKED + "([A-Za-z0-9._~+/=-]{20,})", 3, 0, 8192,
          "bearer", "token"),
      TextRule.of("basic-auth",
          "(?i)(?<![a-z])basic\\s+" + NOT_MASKED + "([A-Za-z0-9+/]{8,}={0,2})(?![A-Za-z0-9+/=])", 1, 0, 8192,
          "basic"),
      TextRule.of("url-credentials",
          "([A-Za-z][A-Za-z0-9+.-]{1,30}://[^\\s/:@?#'\"]{1,256}:)" + NOT_MASKED + "([^\\s/@?#'\"]{1,256})(@)", 2, 31, 560,
          "://"),
      TextRule.of("kv-password",
          "(?i)(?<![a-z])(password|passwd|pwd|secret|client_secret|api[_-]?key|access[_-]?token|auth[_-]?token"
              + "|refresh[_-]?token|private[_-]?key)([\"']?\\s*[=:]\\s*)([\"']?)" + NOT_MASKED
              + "([^\\s\"',;&}<>]{3,})", 4, 8, 4200,
          "pass", "pwd", "secret", "api_key", "apikey", "api-key", "token", "private"),
      TextRule.of("sql-password",
          "(?i)(?<![a-z])(password|identified\\s+by)(\\s+|\\s*=\\s*)" + NOT_MASKED
              + "('(?:[^']|'')*'|\"(?:[^\"]|\"\")*\")", 3, 0, 4200,
          "password", "identified"));

  private static final Literals LITERALS = new Literals(ALL.stream().map(TextRule::literals).toList());

  private SecretRules() {}

  /**
   * {@code text} with every secret found by the rules replaced by {@code mask}. All rules look at the same text and
   * overlapping finds are merged, so one secret matched by two rules is one mask.
   */
  public static String apply(String text, String mask, MaskCounter counter) {
    if (text.length() < 8) {
      return text;
    }
    int[][] starts = new int[ALL.size()][];
    int[] counts = new int[ALL.size()];
    LITERALS.scan(asciiLower(text), (start, rule) -> {
      if (starts[rule] == null) {
        starts[rule] = new int[8];
      } else if (counts[rule] == starts[rule].length) {
        starts[rule] = java.util.Arrays.copyOf(starts[rule], counts[rule] * 2);
      }
      starts[rule][counts[rule]++] = start;
    });
    LongList ranges = new LongList();
    for (int rule = 0; rule < ALL.size(); rule++) {
      if (counts[rule] > 0) {
        java.util.Arrays.sort(starts[rule], 0, counts[rule]);
        ALL.get(rule).collect(text, starts[rule], counts[rule], ranges, counter, mask);
      }
    }
    if (ranges.size() == 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    int copied = 0;
    long[] sorted = ranges.sorted();
    int i = 0;
    while (i < sorted.length) {
      int start = (int) (sorted[i] >>> 32);
      int end = (int) sorted[i];
      i++;
      while (i < sorted.length && (int) (sorted[i] >>> 32) < end) {
        end = Math.max(end, (int) sorted[i]);
        i++;
      }
      if (start < copied) {
        start = copied;
      }
      out.append(text, copied, start).append(mask);
      copied = Math.max(copied, end);
    }
    return out.append(text, copied, text.length()).toString();
  }

  /** ASCII-only lower case: same length as the input, so positions carry over (Unicode lower case may not). */
  static String asciiLower(String text) {
    char[] chars = null;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        if (chars == null) {
          chars = text.toCharArray();
        }
        chars[i] = (char) (c + 32);
      }
    }
    return chars == null ? text : new String(chars);
  }
}
