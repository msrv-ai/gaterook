package ai.msrv.gaterook.core.platform.mask;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One secret detector: a regular expression anchored on literals. The regex never scans a whole text: it runs only in
 * windows around the places where one of its literals occurs ({@link SecretRules#apply} finds them all in one pass),
 * which keeps the rule set at a few milliseconds per megabyte (report 12 §7.7: 428 ms/MB for the naive set).
 *
 * @param id       rule id reported in {@code byRule} ({@code aws-access-key-id})
 * @param literals lower-case literals, one of which every match contains
 * @param group    the group that is the secret ({@code 0}: the whole match); the rest stays readable
 *                 ({@code postgres://app:‹masked›@db})
 * @param before   how far before a literal a match may start
 * @param after    how far after a literal's start a match may end
 */
public record TextRule(String id, List<String> literals, Pattern pattern, int group, int before, int after) {

  public static TextRule of(String id, String regex, int group, int before, int after, String... literals) {
    if (literals.length == 0) {
      throw new IllegalArgumentException("A rule needs at least one literal: " + id);
    }
    for (String literal : literals) {
      if (!literal.equals(literal.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("Prefilter literals are lower case: " + literal);
      }
    }
    return new TextRule(id, List.of(literals), Pattern.compile(regex), group, before, after);
  }

  /**
   * Finds this rule's secrets in {@code text} around the literal positions {@code starts[0, count)} (ascending) and
   * adds their ranges to {@code ranges} as {@code start << 32 | end}.
   */
  void collect(String text, int[] starts, int count, LongList ranges, MaskCounter counter, String mask) {
    Matcher m = pattern.matcher(text);
    m.useTransparentBounds(true);
    m.useAnchoringBounds(false);
    int length = text.length();
    int i = 0;
    int lastEnd = -1;
    while (i < count) {
      int windowStart = Math.max(0, starts[i] - before);
      int windowEnd = Math.min(length, starts[i] + after);
      // Merge the windows of nearby occurrences: one regex run covers them all.
      while (i + 1 < count && starts[i + 1] - before <= windowEnd) {
        i++;
        windowEnd = Math.min(length, Math.max(windowEnd, starts[i] + after));
      }
      i++;
      int regionStart = Math.max(windowStart, lastEnd);
      if (regionStart >= windowEnd) {
        continue;
      }
      m.region(regionStart, windowEnd);
      while (m.find()) {
        int start = m.start(group);
        int end = m.end(group);
        if (start < 0 || end <= start || text.startsWith(mask, start)) {
          continue;
        }
        ranges.add(((long) start << 32) | end);
        counter.hit(id);
        lastEnd = Math.max(lastEnd, m.end());
      }
    }
  }
}
