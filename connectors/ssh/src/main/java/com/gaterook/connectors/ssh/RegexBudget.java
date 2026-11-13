package com.gaterook.connectors.ssh;

import com.gaterook.connector.api.ConnectorException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * How large an agent's RE2 expression becomes once compiled, estimated from its text before RE2/J sees it. RE2/J
 * matches in linear time, but it compiles a counted repetition into copies of what it repeats ({@code x{3}} becomes
 * {@code xxx}) and limits each count to 1,000 without limiting counts inside counts, so a short expression could ask
 * for millions of instructions: more memory than the core has, spent while classifying the call, before its audit
 * row. The estimate follows that expansion (a count multiplies everything it repeats) and errs on the large side; it
 * never decides what an expression means, RE2/J still parses and refuses it.
 */
final class RegexBudget {

  /**
   * Instructions an expression may compile to: far beyond realistic log searches (a timestamp, an address, a long list
   * of words, {@code \w{1,1000}}) and far below what costs the core noticeable memory or matching time per character.
   */
  static final long MAX_INSTRUCTIONS = 10_000;

  /** Where a saturating count stops: beyond every limit, far below overflow. */
  private static final long CAP = 1L << 40;

  private RegexBudget() {}

  /** Refuses an expression that would compile to more than {@link #MAX_INSTRUCTIONS}. */
  static void check(String expression) throws ConnectorException {
    if (instructions(expression) > MAX_INSTRUCTIONS) {
      throw ConnectorException.badInput("The regular expression is too large once its repetitions are expanded "
          + "(over " + MAX_INSTRUCTIONS + " steps). Use smaller counts in {n,m}, especially inside other repetitions, "
          + "or a literal search.");
    }
  }

  /** An upper estimate of the instructions RE2/J compiles {@code expression} to; text it can't parse counts as is. */
  static long instructions(String expression) {
    Deque<long[]> outer = new ArrayDeque<>();
    long sum = 0;
    // The size of the item a quantifier would apply to: the last atom or group, 0 after '|' or '(' or a quantifier.
    long last = 0;
    boolean quantified = false;
    int i = 0;
    int n = expression.length();
    while (i < n) {
      char c = expression.charAt(i);
      switch (c) {
        case '\\' -> {
          if (i + 1 < n && expression.charAt(i + 1) == 'Q') {
            // Literal text up to \E: one instruction per character; a quantifier applies to the last one.
            int end = expression.indexOf("\\E", i + 2);
            int stop = end < 0 ? n : end;
            int count = stop - (i + 2);
            sum = add(sum, count);
            last = count > 0 ? 1 : 0;
            i = end < 0 ? n : end + 2;
          } else {
            i = escapeEnd(expression, i);
            sum = add(sum, 1);
            last = 1;
          }
          quantified = false;
        }
        case '[' -> {
          i = classEnd(expression, i);
          sum = add(sum, 1);
          last = 1;
          quantified = false;
        }
        case '(' -> {
          int body = groupBody(expression, i);
          if (body < 0) {
            // Flags only, such as (?i): nothing to repeat.
            i = flagsEnd(expression, i);
            last = 0;
          } else {
            outer.push(new long[] {sum, capturing(expression, i) ? 2 : 0});
            sum = 0;
            last = 0;
            i = body;
          }
          quantified = false;
        }
        case ')' -> {
          i++;
          if (outer.isEmpty()) {
            // Unbalanced: RE2/J refuses it; count it as one more instruction.
            sum = add(sum, 1);
            last = 1;
          } else {
            long[] frame = outer.pop();
            long group = add(sum, frame[1]);
            sum = add(frame[0], group);
            last = group;
          }
          quantified = false;
        }
        case '|' -> {
          i++;
          sum = add(sum, 1);
          last = 0;
          quantified = false;
        }
        case '*', '+', '?' -> {
          i++;
          if (quantified && c == '?') {
            // x*? x+? x?? x{n,m}?: a lazy quantifier is the same size.
            quantified = false;
            continue;
          }
          long grown = add(last, 1);
          sum = add(sum - last, grown);
          last = grown;
          quantified = true;
        }
        case '{' -> {
          int[] repeat = repeat(expression, i);
          if (repeat == null) {
            // Not a valid count: RE2/J reads '{' as a literal.
            i++;
            sum = add(sum, 1);
            last = 1;
            quantified = false;
          } else {
            i = repeat[2];
            int min = repeat[0];
            int max = repeat[1];
            long grown;
            if (max < 0) {
              // x{n,}: n copies, the last one repeated.
              grown = add(multiply(last, Math.max(min, 1)), 1);
            } else {
              // x{n,m}: m copies, m - n of them optional.
              grown = add(multiply(last, max), max - min);
            }
            sum = add(sum - last, grown);
            last = grown;
            quantified = true;
          }
        }
        default -> {
          // A character (surrogate pairs count twice, which only errs on the large side), '.', '^' or '$'.
          i++;
          sum = add(sum, 1);
          last = 1;
          quantified = false;
        }
      }
    }
    while (!outer.isEmpty()) {
      long[] frame = outer.pop();
      sum = add(frame[0], add(sum, frame[1]));
    }
    // The match and its bookkeeping around the whole expression.
    return add(sum, 4);
  }

  /** The end of the escape at {@code at} ({@code \d}, {@code \x41}, {@code \x{263a}}, {@code \pL}, {@code \p{Greek}}). */
  private static int escapeEnd(String s, int at) {
    int i = at + 1;
    if (i >= s.length()) {
      return s.length();
    }
    char kind = s.charAt(i);
    i++;
    if ((kind == 'x' || kind == 'p' || kind == 'P') && i < s.length() && s.charAt(i) == '{') {
      int close = s.indexOf('}', i);
      return close < 0 ? s.length() : close + 1;
    }
    if (kind == 'x') {
      return Math.min(s.length(), i + 2);
    }
    if (kind == 'p' || kind == 'P') {
      return Math.min(s.length(), i + 1);
    }
    if (kind >= '0' && kind <= '7') {
      int end = i;
      while (end < s.length() && end < i + 2 && s.charAt(end) >= '0' && s.charAt(end) <= '7') {
        end++;
      }
      return end;
    }
    return i;
  }

  /** The end of the character class at {@code at}, after its closing bracket. */
  private static int classEnd(String s, int at) {
    int i = at + 1;
    if (i < s.length() && s.charAt(i) == '^') {
      i++;
    }
    if (i < s.length() && s.charAt(i) == ']') {
      // A ']' first is a member, not the end.
      i++;
    }
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '\\') {
        i = escapeEnd(s, i);
      } else if (c == '[' && i + 1 < s.length() && s.charAt(i + 1) == ':') {
        int close = s.indexOf(":]", i + 2);
        i = close < 0 ? i + 1 : close + 2;
      } else if (c == ']') {
        return i + 1;
      } else {
        i++;
      }
    }
    return s.length();
  }

  /** Where the content of the group at {@code at} starts, or -1 for a group of flags only such as {@code (?i)}. */
  private static int groupBody(String s, int at) {
    int i = at + 1;
    if (i >= s.length() || s.charAt(i) != '?') {
      return i;
    }
    i++;
    if (i < s.length() && (s.charAt(i) == 'P' || s.charAt(i) == '<')) {
      int close = s.indexOf('>', i);
      return close < 0 ? s.length() : close + 1;
    }
    while (i < s.length() && "imsU-".indexOf(s.charAt(i)) >= 0) {
      i++;
    }
    if (i < s.length() && s.charAt(i) == ')') {
      return -1;
    }
    // (?:...), (?i:...); anything else is refused by RE2/J and counted as a group.
    return i < s.length() && s.charAt(i) == ':' ? i + 1 : i;
  }

  private static int flagsEnd(String s, int at) {
    int close = s.indexOf(')', at);
    return close < 0 ? s.length() : close + 1;
  }

  private static boolean capturing(String s, int at) {
    return at + 1 >= s.length() || s.charAt(at + 1) != '?' || at + 2 < s.length()
        && (s.charAt(at + 2) == 'P' || s.charAt(at + 2) == '<');
  }

  /**
   * {@code {min, max, end}} of the count at {@code at} ({@code max} -1 for {@code {n,}}), or {@code null} when it is
   * no count RE2/J reads (ASCII digits without a leading zero) and the brace is a literal. A count above 1,000 or one
   * of more than eight digits makes RE2/J refuse the expression before it expands anything: it counts once here.
   */
  private static int[] repeat(String s, int at) {
    int i = at + 1;
    int start = i;
    i = digitsEnd(s, i);
    if (!number(s, start, i) || i >= s.length()) {
      return null;
    }
    long min = Long.parseLong(s.substring(start, Math.min(i, start + 9)));
    long max = min;
    if (s.charAt(i) == ',') {
      i++;
      int from = i;
      i = digitsEnd(s, i);
      if (i == from) {
        max = -1;
      } else if (!number(s, from, i)) {
        return null;
      } else {
        max = Long.parseLong(s.substring(from, Math.min(i, from + 9)));
      }
    }
    if (i >= s.length() || s.charAt(i) != '}') {
      return null;
    }
    if (min > 1000 || max > 1000 || (max >= 0 && max < min)) {
      return new int[] {1, 1, i + 1};
    }
    return new int[] {(int) min, (int) max, i + 1};
  }

  private static int digitsEnd(String s, int at) {
    int i = at;
    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
      i++;
    }
    return i;
  }

  /** Digits RE2/J reads as a number: at least one, no leading zero. */
  private static boolean number(String s, int from, int to) {
    return to > from && !(to - from > 1 && s.charAt(from) == '0');
  }

  private static long add(long a, long b) {
    long sum = a + b;
    return sum > CAP || sum < 0 ? CAP : sum;
  }

  private static long multiply(long a, long b) {
    if (a == 0 || b == 0) {
      return 0;
    }
    return a > CAP / b ? CAP : a * b;
  }
}
