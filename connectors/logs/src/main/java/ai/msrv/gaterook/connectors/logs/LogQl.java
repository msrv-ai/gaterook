package ai.msrv.gaterook.connectors.logs;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How far behind each point of its window a LogQL query reads: a metric query such as
 * {@code count_over_time({app="api"} | json [30d])} reads 30 days before {@code from}, whatever the window the gateway
 * asked for, and {@code label_format} can carry whole lines of those 30 days into its labels. The range vectors are
 * read outside string literals ({@code "…"} with escapes, {@code `…`} raw); an {@code offset} only moves a range back,
 * like an earlier window would, and doesn't widen it.
 */
final class LogQl {

  /** One range of the query: as written and how long it is. */
  record Range(String text, Duration length) {}

  /** {@code 1h30m}, {@code 500ms}, {@code 7d}, {@code 1.5h}: Prometheus' units and Go's. */
  private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ns|us|µs|ms|s|m|h|d|w|y)");
  private static final Pattern DURATION = Pattern.compile("(?:" + PART.pattern() + ")+");

  private LogQl() {}

  /** The longest range vector of {@code query}, or empty for a log query. */
  static Optional<Range> longestRange(String query) {
    Range longest = null;
    for (int i = 0; i < query.length(); i++) {
      char c = query.charAt(i);
      if (c == '"') {
        for (i++; i < query.length() && query.charAt(i) != '"'; i++) {
          if (query.charAt(i) == '\\') {
            i++;
          }
        }
      } else if (c == '`') {
        int end = query.indexOf('`', i + 1);
        i = end < 0 ? query.length() : end;
      } else if (c == '[') {
        int end = query.indexOf(']', i + 1);
        if (end < 0) {
          break;
        }
        String inside = query.substring(i + 1, end).strip();
        // A subquery's step ([1h:5m]) doesn't widen what is read; its range does.
        int colon = inside.indexOf(':');
        Optional<Duration> d = duration(colon < 0 ? inside : inside.substring(0, colon).strip());
        if (d.isPresent() && (longest == null || d.get().compareTo(longest.length()) > 0)) {
          longest = new Range(inside, d.get());
        }
        i = end;
      }
    }
    return Optional.ofNullable(longest);
  }

  static Optional<Duration> duration(String text) {
    if (!DURATION.matcher(text).matches()) {
      return Optional.empty();
    }
    BigDecimal nanos = BigDecimal.ZERO;
    Matcher m = PART.matcher(text);
    while (m.find()) {
      long unit = switch (m.group(2)) {
        case "ns" -> 1L;
        case "us", "µs" -> 1_000L;
        case "ms" -> 1_000_000L;
        case "s" -> 1_000_000_000L;
        case "m" -> 60_000_000_000L;
        case "h" -> 3_600_000_000_000L;
        case "d" -> 86_400_000_000_000L;
        case "w" -> 7 * 86_400_000_000_000L;
        default -> 365 * 86_400_000_000_000L;
      };
      nanos = nanos.add(new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(unit)));
    }
    BigDecimal max = BigDecimal.valueOf(Long.MAX_VALUE);
    return Optional.of(Duration.ofNanos(nanos.min(max).longValue()));
  }
}
