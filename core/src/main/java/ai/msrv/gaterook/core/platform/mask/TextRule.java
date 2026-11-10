package ai.msrv.gaterook.core.platform.mask;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One secret detector: a regular expression anchored on literals. The regex never scans a whole text: it runs only in
 * windows around the places where one of its literals occurs ({@link SecretRules#apply} finds them all in one pass),
 * which keeps the rule set at a few milliseconds per megabyte (report 12 §7.7: 428 ms/MB for the naive set). A match
 * is read no further than its own literal's window, so what a literal costs does not depend on the text around it,
 * and the work of a whole value is capped by its {@link Work} budget.
 *
 * @param id       rule id reported in {@code byRule} ({@code aws-access-key-id})
 * @param literals lower-case literals, one of which every match contains
 * @param group    the group that is the secret ({@code 0}: the whole match); the rest stays readable
 *                 ({@code postgres://app:‹masked›@db})
 * @param before   how far before a literal a match may start
 * @param after    how far after a literal's start a match may end
 * @param toWindowEnd whether {@code \z} in the pattern also matches at the end of the literal's window, not only at
 *                 the end of the text: a value whose end lies beyond the window is then masked to the window's end
 *                 instead of not at all ({@link #toWindowEnd})
 */
public record TextRule(String id, List<String> literals, Pattern pattern, int group, int before, int after,
    boolean toWindowEnd) {

  /** What {@code byRule} reports for the windows masked whole because the value's work budget was spent. */
  static final String WORK_LIMIT = "work-limit";

  public static TextRule of(String id, String regex, int group, int before, int after, String... literals) {
    return create(id, regex, group, before, after, false, literals);
  }

  /**
   * A rule whose {@code \z} stands for the end of its literal's window as well as the end of the text, for values with
   * a closing delimiter that may be missing or lie beyond the window: such a value is masked to the window's end. The
   * pattern must use no other anchor ({@code ^}, {@code $}, {@code \A}, {@code \Z}), since those would then match at
   * the window's edges too.
   */
  public static TextRule toWindowEnd(String id, String regex, int group, int before, int after, String... literals) {
    return create(id, regex, group, before, after, true, literals);
  }

  private static TextRule create(String id, String regex, int group, int before, int after, boolean toWindowEnd,
      String... literals) {
    if (literals.length == 0) {
      throw new IllegalArgumentException("A rule needs at least one literal: " + id);
    }
    for (String literal : literals) {
      if (!literal.equals(literal.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("Prefilter literals are lower case: " + literal);
      }
    }
    return new TextRule(id, List.of(literals), Pattern.compile(regex), group, before, after, toWindowEnd);
  }

  /**
   * Finds this rule's secrets in the text of {@code work} around the literal positions {@code starts[0, count)}
   * (ascending) and adds their ranges to {@code ranges} as {@code start << 32 | end}. Each literal gets its own start
   * positions, also where an earlier match spans it: {@code mysql …} reaching across a script's lines covers another
   * command's {@code --password}, but not its secret. A secret found again from a later literal is not counted twice.
   *
   * <p>When the budget of {@code work} is spent, the windows of the literals not checked yet are masked whole
   * ({@link #withhold}): the text around a keyword is never passed unchecked.
   */
  void collect(Work work, int[] starts, int count, LongList ranges, MaskCounter counter, String mask) {
    String text = work.text();
    int length = text.length();
    if (work.spent()) {
      withhold(starts, 0, count, length, ranges, counter);
      return;
    }
    Matcher m = pattern.matcher(work);
    m.useTransparentBounds(true);
    m.useAnchoringBounds(toWindowEnd);
    // The last secret found and the one reaching furthest: what a later literal's match may find again.
    int lastFrom = -1;
    int lastTo = -1;
    int furthestFrom = -1;
    int furthestTo = -1;
    // Start positions are tried once, in ascending order. 'nextStart' is the first one that no literal has tried or
    // jumped over with a match, 'untried' the first one that nobody has tried.
    int nextStart = 0;
    int untried = 0;
    int literal = 0;
    try {
      for (; literal < count; literal++) {
        int at = starts[literal];
        // A match is read no further than its own literal's window, not to the end of the windows merged with it.
        int windowEnd = Math.min(length, at + after);
        // A literal inside an earlier match gets the starts that match skipped: it may have a secret of its own.
        int from = at < nextStart ? untried : nextStart;
        // A match can start only within 'before' characters of an actual literal. Keep the full end window for
        // long secrets, but do not ask the regex engine to search thousands of impossible starting positions.
        int candidate = Math.max(from, at - before);
        while (candidate <= at) {
          work.charge(Work.ATTEMPT);
          m.region(candidate, windowEnd);
          untried = candidate + 1;
          if (!m.lookingAt()) {
            candidate++;
            continue;
          }
          int start = m.start(group);
          int end = m.end(group);
          candidate = Math.max(candidate + 1, m.end());
          if (start < 0 || end <= start || text.startsWith(mask, start) || (lastFrom <= start && end <= lastTo)
              || (furthestFrom <= start && end <= furthestTo)) {
            continue;
          }
          ranges.add(((long) start << 32) | end);
          counter.hit(id);
          lastFrom = start;
          lastTo = end;
          if (end > furthestTo) {
            furthestFrom = start;
            furthestTo = end;
          }
        }
        nextStart = Math.max(nextStart, candidate);
      }
    } catch (Work.Spent e) {
      withhold(starts, literal, count, length, ranges, counter);
    }
  }

  /**
   * Masks the windows of the literals {@code starts[from, count)} whole. A secret of this rule lies between
   * {@code before} characters before its literal and {@code after} characters after it, so this covers whatever the
   * rule would have found there. Overlapping windows become one range.
   */
  private void withhold(int[] starts, int from, int count, int length, LongList ranges, MaskCounter counter) {
    int start = -1;
    int end = -1;
    for (int i = from; i < count; i++) {
      int windowStart = Math.max(0, starts[i] - before);
      int windowEnd = Math.min(length, starts[i] + after);
      if (start >= 0 && windowStart <= end) {
        end = Math.max(end, windowEnd);
        continue;
      }
      if (start >= 0) {
        ranges.add(((long) start << 32) | end);
        counter.hit(WORK_LIMIT);
      }
      start = windowStart;
      end = windowEnd;
    }
    if (start >= 0) {
      ranges.add(((long) start << 32) | end);
      counter.hit(WORK_LIMIT);
    }
  }
}
