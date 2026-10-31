package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.core.platform.mask.MaskCounter;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The secrets this process has handed out so far, kept as keyed fingerprints of their forms: what the {@link Masker}
 * checks every call's text against, whichever connection the call is for (ARCHITECTURE.md §9 "Agents do not receive
 * configured secrets"). A call's {@link Taint} knows the secrets of its own connection; the data it reads can hold
 * another connection's password, an application's config or log with the same credentials GateRook has, and that
 * one the taint has never seen. Every value a resolver hands out in any call is learned here, so from then on it is
 * masked in every call's results, names and numbers.
 *
 * <p>No plaintext is kept: each form becomes a 64-bit hash under a key drawn at start-up, and a text is scanned with a
 * rolling hash per form length, so the scan costs one pass per distinct length however many secrets are known. A
 * secret that was never handed out since the process started isn't known; a chance collision masks an innocent
 * value, at odds of about one in 2^64 per window.
 */
public final class SecretFingerprints {

  private static final SecretFingerprints PROCESS = new SecretFingerprints();

  /** The fingerprints of this process: what {@link Masker#standard()} uses. */
  public static SecretFingerprints process() {
    return PROCESS;
  }

  private final long base;
  private final long salt;
  private final ConcurrentHashMap<Integer, Set<Long>> byLength = new ConcurrentHashMap<>();
  private volatile int[] lengths = new int[0];

  public SecretFingerprints() {
    SecureRandom random = new SecureRandom();
    this.base = random.nextLong() | 1L;
    this.salt = random.nextLong();
  }

  public boolean isEmpty() {
    return lengths.length == 0;
  }

  /** Remembers a text secret in every form {@link Taint#add} tracks. */
  public void learnValue(CharSequence value) {
    Taint forms = new Taint();
    forms.add(value);
    learn(forms);
    forms.clear();
  }

  /** Remembers a binary secret in every form {@link Taint#addBytes} tracks. */
  public void learnBytes(byte[] value) {
    Taint forms = new Taint();
    forms.addBytes(value);
    learn(forms);
    forms.clear();
  }

  /** Remembers a login's password in every form {@link Taint#addLogin} tracks. */
  public void learnLogin(CharSequence username, CharSequence password) {
    Taint forms = new Taint();
    forms.addLogin(username, password);
    learn(forms);
    forms.clear();
  }

  /** Remembers every form of {@code taint}. */
  public synchronized void learn(Taint taint) {
    boolean more = false;
    for (char[] form : taint.forms()) {
      Set<Long> set = byLength.get(form.length);
      if (set == null) {
        set = ConcurrentHashMap.newKeySet();
        byLength.put(form.length, set);
        more = true;
      }
      long h = 0;
      for (char c : form) {
        h = h * base + c;
      }
      set.add(mix(h));
    }
    if (more) {
      lengths = byLength.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
    }
  }

  /** {@code text} with every known form replaced by {@code mask}; a hit per replaced run counts as a stored secret. */
  public String mask(String text, String mask, MaskCounter counter) {
    int[] known = lengths;
    if (known.length == 0 || text.length() < known[0]) {
      return text;
    }
    long[] ranges = null;
    int count = 0;
    for (int length : known) {
      if (length > text.length()) {
        break;
      }
      Set<Long> set = byLength.get(length);
      long top = 1;
      for (int i = 1; i < length; i++) {
        top *= base;
      }
      long h = 0;
      for (int i = 0; i < length; i++) {
        h = h * base + text.charAt(i);
      }
      for (int start = 0; ; start++) {
        if (set.contains(mix(h))) {
          if (ranges == null) {
            ranges = new long[8];
          } else if (count == ranges.length) {
            ranges = Arrays.copyOf(ranges, count * 2);
          }
          ranges[count++] = ((long) start << 32) | (start + length);
        }
        if (start + length >= text.length()) {
          break;
        }
        h = (h - text.charAt(start) * top) * base + text.charAt(start + length);
      }
    }
    if (count == 0) {
      return text;
    }
    Arrays.sort(ranges, 0, count);
    StringBuilder out = new StringBuilder(text.length());
    int copied = 0;
    int i = 0;
    while (i < count) {
      int start = (int) (ranges[i] >>> 32);
      int end = (int) ranges[i];
      i++;
      while (i < count && (int) (ranges[i] >>> 32) < end) {
        end = Math.max(end, (int) ranges[i]);
        i++;
      }
      if (start < copied) {
        start = copied;
      }
      out.append(text, copied, start).append(mask);
      counter.hit("stored-secret");
      copied = Math.max(copied, end);
    }
    return out.append(text, copied, text.length()).toString();
  }

  /** The hash a form is kept as: the keyed polynomial, then a finalizer so that near values don't share bits. */
  private long mix(long h) {
    long z = h ^ salt;
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }
}
