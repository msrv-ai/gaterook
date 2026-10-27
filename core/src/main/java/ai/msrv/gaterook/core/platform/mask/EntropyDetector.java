package ai.msrv.gaterook.core.platform.mask;

import java.util.List;

/**
 * Unknown secrets: runs of at least {@value #MIN_RUN} base64-alphabet characters with upper case, lower case and
 * digits and a Shannon entropy of at least {@value #MIN_ENTROPY} bits per character (report 12 §7.7). Hex digests and
 * UUIDs never qualify (no mixed case, and {@code -} ends a run). The gateway's own handles are left alone (see
 * {@link #handleEnd}). One linear pass, no regular expression.
 */
public final class EntropyDetector {

  static final int MIN_RUN = 32;
  static final double MIN_ENTROPY = 4.3;

  /**
   * Prefixes of the opaque handles the gateway hands out and the agent must pass back unchanged: the logs cursor
   * {@code lc1.} (report 07 §7), the Redis cursors {@code rgc.}/{@code rsc.} (report 05 §3.4–3.5), the Kubernetes
   * cursor {@code k8c.}, the GCP cursor {@code gc1.} and the AWS cursor {@code ac1.} (report 09 §3). A new handle format
   * adds its prefix here; anything else with a short dotted prefix ({@code sk.…}) is checked like any token.
   */
  static final List<String> HANDLE_PREFIXES = List.of("lc1.", "rgc.", "rsc.", "k8c.", "gc1.", "ac1.");

  private EntropyDetector() {}

  public static String apply(String text, String mask, MaskCounter counter) {
    StringBuilder out = null;
    int copied = 0;
    int length = text.length();
    int i = 0;
    while (i < length) {
      if (!inAlphabet(text.charAt(i))) {
        i++;
        continue;
      }
      int start = i;
      int handle = handleEnd(text, start);
      if (handle > 0) {
        i = handle;
        continue;
      }
      while (i < length && inAlphabet(text.charAt(i))) {
        i++;
      }
      if (i - start >= MIN_RUN && randomLooking(text, start, i)) {
        if (out == null) {
          out = new StringBuilder(length);
        }
        out.append(text, copied, start).append(mask);
        copied = i;
        counter.hit("entropy");
      }
    }
    if (out == null) {
      return text;
    }
    out.append(text, copied, length);
    return out.toString();
  }

  /**
   * The end of a gateway handle whose payload starts at {@code start}, or {@code -1}. A handle is one of
   * {@link #HANDLE_PREFIXES} at a token boundary, a base64url JSON payload ({@code eyJ…}, so {@code -} and {@code _}
   * belong to it) and optionally a dot and a base64url MAC. Masking it would break paging, and it carries no secret;
   * a token that merely looks alike (a standard-Base64 {@code +}, {@code /} or {@code =} right after it) is not one.
   */
  static int handleEnd(CharSequence text, int start) {
    if (start < 4 || !startsWith(text, start, "eyJ") || !handlePrefix(text, start - 4)) {
      return -1;
    }
    int end = base64urlRun(text, start);
    if (end + 1 < text.length() && text.charAt(end) == '.' && base64url(text.charAt(end + 1))) {
      end = base64urlRun(text, end + 1);
    }
    if (end < text.length()) {
      char next = text.charAt(end);
      if (next == '+' || next == '/' || next == '=') {
        return -1;
      }
    }
    return end;
  }

  private static boolean handlePrefix(CharSequence text, int at) {
    if (at > 0) {
      char before = text.charAt(at - 1);
      if (base64url(before) || before == '.' || before == '+' || before == '/') {
        return false;
      }
    }
    for (String prefix : HANDLE_PREFIXES) {
      if (startsWith(text, at, prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWith(CharSequence text, int at, String prefix) {
    if (at + prefix.length() > text.length()) {
      return false;
    }
    for (int i = 0; i < prefix.length(); i++) {
      if (text.charAt(at + i) != prefix.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private static int base64urlRun(CharSequence text, int from) {
    int i = from;
    while (i < text.length() && base64url(text.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean base64url(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
  }

  /** Whether {@code text[start, end)} looks like a random token. */
  static boolean randomLooking(CharSequence text, int start, int end) {
    boolean upper = false;
    boolean lower = false;
    boolean digit = false;
    boolean hexOnly = true;
    int[] counts = new int[128];
    for (int i = start; i < end; i++) {
      char c = text.charAt(i);
      counts[c]++;
      upper |= c >= 'A' && c <= 'Z';
      lower |= c >= 'a' && c <= 'z';
      digit |= c >= '0' && c <= '9';
      hexOnly &= (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
    if (!upper || !lower || !digit || hexOnly) {
      return false;
    }
    int n = end - start;
    double entropy = 0;
    for (int count : counts) {
      if (count > 0) {
        double p = (double) count / n;
        entropy -= p * (Math.log(p) / Math.log(2));
      }
    }
    return entropy >= MIN_ENTROPY;
  }

  private static boolean inAlphabet(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/'
        || c == '_' || c == '=';
  }
}
