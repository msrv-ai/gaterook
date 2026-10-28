package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ExecContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Conservative grep candidates; the local masked parser remains the authority for every returned record. */
final class SshLogPrefilter {
  private SshLogPrefilter() {}

  /**
   * A POSIX ERE for grep in the C locale, or no prefilter when equivalence cannot be guaranteed. The caller must
   * scan fixed source windows, never expose raw match counts, and apply output limits only after masked matching.
   * JSON, control characters and non-ASCII bytes always pass because normalization can create new matches. Long
   * lines also pass so the scanner still counts oversized records, including those that do not match the query.
   */
  static Optional<String> grepExpression(SshLogQuery query, ExecContext ctx) {
    Optional<String> replacement = ctx.textMaskMarker();
    if (replacement.isEmpty() || replacement.get().isEmpty() || replacement.get().length() > 64
        || query.needle() == null || query.timeFiltered()) {
      // Time queries count unparseable records, including those that do not match the content filter.
      return Optional.empty();
    }
    List<String> alternatives;
    if (query.expression() == null) {
      alternatives = List.of(query.needle());
    } else {
      String expression = query.expression().pattern();
      if (!expression.matches("[A-Za-z0-9 _:/@%=-]+(?:\\|[A-Za-z0-9 _:/@%=-]+)*")) {
        return Optional.empty();
      }
      alternatives = List.of(expression.split("\\|", -1));
    }
    String marker = query.caseSensitive() ? replacement.get() : replacement.get().toLowerCase(Locale.ROOT);
    for (String term : alternatives) {
      if (term.isEmpty() || !term.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) {
        return Optional.empty();
      }
      String compared = query.caseSensitive() ? term : term.toLowerCase(Locale.ROOT);
      if (overlapsMarker(compared, marker)) {
        return Optional.empty();
      }
    }
    String content = String.join("|", alternatives.stream().map(term -> literal(term, query.caseSensitive())).toList());
    // Two small repetitions stay within POSIX's minimum supported repetition bound of 255.
    return Optional.of("^[[:space:]]*[{]|[^ -~]|.{128}.{128}|(" + content + ")");
  }

  /** Could a new occurrence overlap the replacement marker, with arbitrary unchanged text on either side? */
  private static boolean overlapsMarker(String needle, String marker) {
    for (int start = 1 - needle.length(); start < marker.length(); start++) {
      int from = Math.max(0, start);
      int to = Math.min(marker.length(), start + needle.length());
      boolean matches = true;
      for (int i = from; i < to; i++) {
        if (needle.charAt(i - start) != marker.charAt(i)) {
          matches = false;
          break;
        }
      }
      if (matches) return true;
    }
    return false;
  }

  private static String literal(String term, boolean caseSensitive) {
    StringBuilder escaped = new StringBuilder();
    for (int i = 0; i < term.length(); i++) {
      char c = term.charAt(i);
      if (!caseSensitive && ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
        escaped.append('[').append(Character.toLowerCase(c)).append(Character.toUpperCase(c)).append(']');
      } else {
        if ("\\.^$[]()|*+?{}".indexOf(c) >= 0) escaped.append('\\');
        escaped.append(c);
      }
    }
    return escaped.toString();
  }
}
