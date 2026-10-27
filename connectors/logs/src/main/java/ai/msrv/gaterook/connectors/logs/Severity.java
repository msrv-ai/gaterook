package ai.msrv.gaterook.connectors.logs;

import java.util.Locale;

/**
 * Severity of a log entry, Cloud Logging's scale (report 07 §3.3): every backend's level maps onto it, so an agent
 * filters and reads {@code ERROR} the same way on Loki, GCP, CloudWatch and OpenSearch.
 */
enum Severity {
  DEFAULT, DEBUG, INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, EMERGENCY;

  /** The level a backend or a structured message names ({@code warn}, {@code E}, {@code fatal}, {@code 50}). */
  static Severity of(String level) {
    if (level == null) {
      return DEFAULT;
    }
    String l = level.strip().toLowerCase(Locale.ROOT);
    return switch (l) {
      case "trace", "debug", "dbg", "d", "verbose", "fine", "finer", "finest", "10", "20", "7" -> DEBUG;
      case "info", "information", "informational", "i", "30", "6" -> INFO;
      case "notice", "5" -> NOTICE;
      case "warn", "warning", "w", "40", "4" -> WARNING;
      case "error", "err", "e", "fail", "failure", "severe", "50", "3" -> ERROR;
      case "critical", "crit", "fatal", "f", "60", "2" -> CRITICAL;
      case "alert", "1" -> ALERT;
      case "emergency", "emerg", "panic", "0" -> EMERGENCY;
      default -> {
        try {
          yield valueOf(level.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          yield DEFAULT;
        }
      }
    };
  }

  /**
   * The level a plain-text line starts with ({@code ERROR …}, {@code [WARN] …}, {@code 2026-… INFO …}): CloudWatch
   * events carry no level of their own. Only the first few words are looked at, so a message that merely mentions
   * "error" further on stays {@code DEFAULT}.
   */
  static Severity ofLine(String message) {
    if (message == null || message.isEmpty()) {
      return DEFAULT;
    }
    int end = Math.min(message.length(), 64);
    String head = message.substring(0, end);
    int words = 0;
    int i = 0;
    while (i < head.length() && words < 4) {
      while (i < head.length() && !Character.isLetter(head.charAt(i))) {
        i++;
      }
      int start = i;
      while (i < head.length() && Character.isLetter(head.charAt(i))) {
        i++;
      }
      if (start == i) {
        break;
      }
      words++;
      String word = head.substring(start, i);
      if (word.length() >= 4 && word.equals(word.toUpperCase(Locale.ROOT))) {
        Severity s = of(word);
        if (s != DEFAULT) {
          return s;
        }
      }
    }
    return DEFAULT;
  }
}
