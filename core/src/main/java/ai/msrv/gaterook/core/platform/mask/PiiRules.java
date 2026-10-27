package ai.msrv.gaterook.core.platform.mask;

import ai.msrv.gaterook.core.platform.MaskPolicy;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Personal data of the Masking policy (kit: email, phone, card, IBAN; on prod by default, report 12 §6). Cards must
 * pass the Luhn check and start like a card (2-6), IBANs the ISO 7064 mod-97 check: plain numbers such as epoch
 * milliseconds and order ids stay readable. A card printed next to other numbers (an expiry, a CVV, an amount, an id
 * in front) is found inside the longer run of digits by its printed shape, see {@link #cards}.
 */
public final class PiiRules {

  private static final Pattern EMAIL = Pattern.compile(
      "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})*\\.[A-Za-z]{2,24}"
          + "(?![A-Za-z0-9-])");
  private static final Pattern PHONE = Pattern.compile("(?<![\\w+])\\+\\d[\\d\\s().-]{6,18}\\d(?!\\w)");
  private static final Pattern IBAN = Pattern.compile("(?<![A-Z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?"
      + "(?![A-Z0-9])");

  private PiiRules() {}

  public static String apply(String text, Set<MaskPolicy.Pii> kinds, String mask, MaskCounter counter) {
    String out = text;
    if (kinds.contains(MaskPolicy.Pii.EMAIL)) {
      out = replace(out, EMAIL, around(out, '@', 65, 256), "pii-email", mask, counter, null);
    }
    if (kinds.contains(MaskPolicy.Pii.PHONE)) {
      out = replace(out, PHONE, around(out, '+', 1, 22), "pii-phone", mask, counter, PiiRules::phoneDigits);
    }
    // IBANs first: their digit groups could pass as a card number.
    if (kinds.contains(MaskPolicy.Pii.IBAN)) {
      out = replace(out, IBAN, ibanStarts(out), "pii-iban", mask, counter, PiiRules::iban);
    }
    if (kinds.contains(MaskPolicy.Pii.CARD)) {
      out = cards(out, digitRuns(out, 13), mask, counter);
    }
    return out;
  }

  private interface Check {
    boolean test(String match);
  }

  /*
   * The patterns run only in windows around their anchors ('@', '+', "XX00", runs of 13+ digits), with transparent
   * bounds so the look-arounds still see the text outside. Scanning a megabyte of logs with the bare patterns cost
   * ~120 ms (the e-mail local part is tried at every position); the windows keep the prod policy within the masker's
   * 40 ms/MB (report 12 §7.7).
   */

  /** Merged windows {@code [anchor - before, anchor + after)} around every {@code anchor} character. */
  private static LongList around(String text, char anchor, int before, int after) {
    LongList windows = new LongList();
    for (int i = text.indexOf(anchor); i >= 0; i = text.indexOf(anchor, i + 1)) {
      add(windows, Math.max(0, i - before), Math.min(text.length(), i + after));
    }
    return windows;
  }

  /** Windows from every {@code [A-Z]{2}\d{2}} that could start an IBAN, as long as the longest one. */
  private static LongList ibanStarts(String text) {
    LongList windows = new LongList();
    for (int i = 0; i + 4 <= text.length(); i++) {
      if (upper(text.charAt(i)) && upper(text.charAt(i + 1)) && digit(text.charAt(i + 2)) && digit(text.charAt(i + 3))) {
        add(windows, i, Math.min(text.length(), i + 48));
      }
    }
    return windows;
  }

  /** Runs of at least {@code min} digits, single spaces or dashes allowed between them. */
  private static LongList digitRuns(String text, int min) {
    LongList windows = new LongList();
    int start = -1;
    int digits = 0;
    int end = 0;
    for (int i = 0; i <= text.length(); i++) {
      char c = i < text.length() ? text.charAt(i) : '\0';
      if (digit(c)) {
        if (start < 0) {
          start = i;
          digits = 0;
        }
        digits++;
        end = i + 1;
      } else if (start >= 0 && (c == ' ' || c == '-') && i > 0 && digit(text.charAt(i - 1))) {
        continue;
      } else if (start >= 0) {
        if (digits >= min) {
          add(windows, start, end);
        }
        start = -1;
      }
    }
    return windows;
  }

  private static void add(LongList windows, int start, int end) {
    int last = windows.size() - 1;
    if (last >= 0 && start <= (int) windows.get(last)) {
      long previous = windows.get(last);
      windows.set(last, ((previous >>> 32) << 32) | Math.max((int) previous, end));
    } else {
      windows.add(((long) start << 32) | end);
    }
  }

  private static boolean upper(char c) {
    return c >= 'A' && c <= 'Z';
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }

  private static String replace(String text, Pattern pattern, LongList windows, String rule, String mask,
      MaskCounter counter, Check check) {
    if (windows.size() == 0) {
      return text;
    }
    Matcher m = pattern.matcher(text).useTransparentBounds(true).useAnchoringBounds(false);
    StringBuilder out = null;
    int copied = 0;
    for (int w = 0; w < windows.size(); w++) {
      long window = windows.get(w);
      m.region((int) (window >>> 32), (int) window);
      while (m.find()) {
        if (m.start() < copied || (check != null && !check.test(m.group()))) {
          continue;
        }
        if (out == null) {
          out = new StringBuilder(text.length());
        }
        out.append(text, copied, m.start()).append(mask);
        copied = m.end();
        counter.hit(rule);
      }
    }
    if (out == null) {
      return text;
    }
    return out.append(text, copied, text.length()).toString();
  }

  /*
   * Card numbers inside runs of digit groups (single spaces or dashes between them). A run is tried whole first, as
   * long as it has 13-19 digits; when it fails the Luhn check, the card may be only a part of it, so every group that
   * can start one (2-6, at the run start or after a space) is tried with the parts of whole groups that end on a group
   * boundary, longest first. Such a part must look like a printed card number: one block of 13-19 digits, groups of
   * four with a shorter last group (4-4-4-4, 4-4-4-4-3, 4-4-4-1) or the Amex and Diners layouts 4-6-5 and 4-6-4, with
   * one kind of separator. The shapes keep lists of numbers from turning into "cards" one Luhn check in ten.
   */
  private static String cards(String text, LongList runs, String mask, MaskCounter counter) {
    StringBuilder out = null;
    int copied = 0;
    LongList groups = new LongList();
    for (int r = 0; r < runs.size(); r++) {
      int runStart = (int) (runs.get(r) >>> 32);
      int runEnd = (int) runs.get(r);
      groups(text, runStart, runEnd, groups);
      for (int s = 0; s < groups.size(); s++) {
        int e = card(text, groups, s);
        if (e < 0) {
          continue;
        }
        int start = (int) (groups.get(s) >>> 32);
        if (out == null) {
          out = new StringBuilder(text.length());
        }
        out.append(text, copied, start).append(mask);
        copied = (int) groups.get(e);
        counter.hit("pii-card");
        s = e;
      }
    }
    if (out == null) {
      return text;
    }
    return out.append(text, copied, text.length()).toString();
  }

  /** The digit groups of a run as {@code start << 32 | end}. */
  private static void groups(String text, int from, int to, LongList groups) {
    groups.clear();
    int start = -1;
    for (int i = from; i <= to; i++) {
      boolean d = i < to && digit(text.charAt(i));
      if (d && start < 0) {
        start = i;
      } else if (!d && start >= 0) {
        groups.add(((long) start << 32) | i);
        start = -1;
      }
    }
  }

  /** The last group of the card that starts at group {@code s}, or -1. */
  private static int card(String text, LongList groups, int s) {
    int start = (int) (groups.get(s) >>> 32);
    char first = text.charAt(start);
    if (first < '2' || first > '6' || length(groups, s) < 2) {
      return -1;
    }
    if (s == 0 ? fractionOrRange(text, start) : text.charAt(start - 1) != ' ') {
      return -1;
    }
    int last = -1;
    int digits = 0;
    for (int e = s; e < groups.size(); e++) {
      digits += length(groups, e);
      if (digits > 19) {
        break;
      }
      last = e;
    }
    for (int e = last; e >= s; e--) {
      if (digitCount(groups, s, e) < 13) {
        break;
      }
      boolean whole = s == 0 && e == groups.size() - 1;
      if ((whole || cardShaped(text, groups, s, e)) && luhn(text.substring(start, (int) groups.get(e)))) {
        return e;
      }
    }
    return -1;
  }

  /**
   * Whether the run at {@code start} continues a number: the digits of a decimal fraction ({@code 1.4111…}) or a
   * range or difference ({@code 12-4111…}). After a word or a sign the run is a number of its own: {@code card-4111…},
   * {@code No.4111 1111 1111 1111}, {@code x:-4111…} can be cards.
   */
  private static boolean fractionOrRange(String text, int start) {
    if (start < 2) {
      return false;
    }
    char before = text.charAt(start - 1);
    return (before == '.' || before == '-') && digit(text.charAt(start - 2));
  }

  private static boolean cardShaped(String text, LongList groups, int s, int e) {
    if (s == e) {
      return true;
    }
    char separator = text.charAt((int) groups.get(s));
    boolean fours = true;
    for (int g = s; g < e; g++) {
      if (text.charAt((int) groups.get(g)) != separator) {
        return false;
      }
      fours &= length(groups, g) == 4;
    }
    if (fours && length(groups, e) <= 4) {
      return true;
    }
    return e == s + 2 && length(groups, s) == 4 && length(groups, s + 1) == 6
        && (length(groups, e) == 5 || length(groups, e) == 4);
  }

  private static int length(LongList groups, int g) {
    long group = groups.get(g);
    return (int) group - (int) (group >>> 32);
  }

  private static int digitCount(LongList groups, int s, int e) {
    int count = 0;
    for (int g = s; g <= e; g++) {
      count += length(groups, g);
    }
    return count;
  }

  private static boolean phoneDigits(String match) {
    long digits = match.chars().filter(Character::isDigit).count();
    return digits >= 8 && digits <= 15;
  }

  static boolean luhn(String match) {
    int sum = 0;
    boolean twice = false;
    int digits = 0;
    for (int i = match.length() - 1; i >= 0; i--) {
      char c = match.charAt(i);
      if (c < '0' || c > '9') {
        continue;
      }
      int d = c - '0';
      if (twice) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      twice = !twice;
      digits++;
    }
    return digits >= 13 && digits <= 19 && sum % 10 == 0;
  }

  static boolean iban(String match) {
    String compact = match.replace(" ", "");
    if (compact.length() < 15 || compact.length() > 34) {
      return false;
    }
    String rearranged = compact.substring(4) + compact.substring(0, 4);
    int remainder = 0;
    for (int i = 0; i < rearranged.length(); i++) {
      char c = rearranged.charAt(i);
      int value = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
      remainder = value > 9 ? (remainder * 100 + value) % 97 : (remainder * 10 + value) % 97;
    }
    return remainder == 1;
  }
}
