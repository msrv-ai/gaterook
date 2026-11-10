package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.core.platform.mask.MaskCounter;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The secrets this process has handed out so far, kept as keyed fingerprints of their forms: what the {@link Masker}
 * checks every call's text against, whichever connection the call is for (ARCHITECTURE.md §9 "Agents do not receive
 * configured secrets"). A call's {@link Taint} knows the secrets of its own connection; the data it reads can hold
 * another connection's password, an application's config or log with the same credentials GateRook has, and that
 * one the taint has never seen. Every value a resolver hands out in any call is learned here, so from then on it is
 * masked in every call's results, names and numbers.
 *
 * <p>No plaintext is kept: each form becomes a 64-bit hash under a key drawn at start-up. A text is scanned once: at
 * each position a filter over the first two characters of every known form says whether any form can start there
 * (almost never, for most text), and only then are the forms of those two characters hashed and looked up, so the
 * scan costs about one pass however many secrets are known. A secret that was never handed out since the process
 * started isn't known; a chance collision masks an innocent value, at odds of about one in 2^64 per candidate.
 *
 * <p>Most secrets are learned for the life of the process. The values of one source that replace each other over time,
 * such as the tokens an MCP server's authorization server issues at every renewal, are learned with
 * {@link #learnReplacing}: the most recent few stay known, older ones are forgotten (unless something else holds them
 * too), so a source that renews often can't make the set, and the work of every scan, grow without end.
 */
public final class SecretFingerprints {

  private static final SecretFingerprints PROCESS = new SecretFingerprints();

  /** The fingerprints of this process: what {@link Masker#standard()} uses. */
  public static SecretFingerprints process() {
    return PROCESS;
  }

  /** The two-character filter: 2^20 bits, a bit per hashed pair. */
  private static final int FILTER_BITS = 1 << 20;

  private final long base;
  private final long salt;
  private final long[] filter = new long[FILTER_BITS / 64];
  /** Per first two characters of a form, the distinct lengths of the forms that start so. */
  private final Map<Integer, int[]> lengthsByStart = new HashMap<>();
  private final ConcurrentHashMap<Long, Boolean> known = new ConcurrentHashMap<>();
  /** Every known form by its key: what holds it, and what rebuilding the filter after a forgotten one needs. */
  private final Map<Long, Form> forms = new HashMap<>();
  /** Per source of {@link #learnReplacing}, the keys of its values (sorted), the newest first. */
  private final Map<String, ArrayDeque<long[]>> sources = new HashMap<>();
  /** A snapshot of {@link #lengthsByStart} and the filter for readers, replaced whole on each learn. */
  private volatile Snapshot snapshot = new Snapshot(Map.of(), new long[FILTER_BITS / 64], 0);

  private record Snapshot(Map<Integer, int[]> lengthsByStart, long[] filter, int forms) {}

  /**
   * A known form: its first two characters and length, how many values of {@link #learnReplacing} sources hold it, and
   * whether a learn for the life of the process does.
   */
  private static final class Form {
    final int start;
    final int length;
    int replacing;
    boolean lasting;

    Form(int start, int length) {
      this.start = start;
      this.length = length;
    }
  }

  public SecretFingerprints() {
    SecureRandom random = new SecureRandom();
    this.base = random.nextLong() | 1L;
    this.salt = random.nextLong();
  }

  public boolean isEmpty() {
    return snapshot.forms() == 0;
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

  /** Remembers every form of {@code taint} for the life of the process. */
  public synchronized void learn(Taint taint) {
    boolean changed = false;
    for (char[] form : taint.forms()) {
      long key = keyOf(form);
      Form f = forms.get(key);
      if (f == null) {
        f = add(key, form);
        changed = true;
      }
      f.lasting = true;
    }
    if (changed) {
      publish();
    }
  }

  /**
   * Remembers values of {@code source} that replace each other, such as the access and refresh tokens of one MCP
   * server's sign-in: the {@code keep} most recently learned values of the source stay known, and an older one is
   * forgotten unless another source, or a learn for the life of the process, holds it too. A value learned again
   * counts as the most recent one.
   *
   * @param source who issues the values; a name of the caller's choosing
   */
  public synchronized void learnReplacing(String source, int keep, CharSequence... values) {
    ArrayDeque<long[]> held = sources.computeIfAbsent(source, s -> new ArrayDeque<>());
    boolean changed = false;
    for (CharSequence value : values) {
      Taint t = new Taint();
      t.add(value);
      char[][] valueForms = t.forms();
      if (valueForms.length == 0) {
        continue;
      }
      long[] keys = new long[valueForms.length];
      for (int i = 0; i < valueForms.length; i++) {
        keys[i] = keyOf(valueForms[i]);
      }
      Arrays.sort(keys);
      long[] again = held.stream().filter(k -> Arrays.equals(k, keys)).findFirst().orElse(null);
      if (again != null) {
        held.remove(again);
        held.addFirst(again);
      } else {
        for (int i = 0; i < valueForms.length; i++) {
          long key = keyOf(valueForms[i]);
          Form f = forms.get(key);
          if (f == null) {
            f = add(key, valueForms[i]);
            changed = true;
          }
          f.replacing++;
        }
        held.addFirst(keys);
      }
      t.clear();
    }
    boolean forgotten = false;
    while (held.size() > Math.max(0, keep)) {
      for (long key : held.removeLast()) {
        Form f = forms.get(key);
        if (f != null && --f.replacing <= 0 && !f.lasting) {
          forms.remove(key);
          known.remove(key);
          forgotten = true;
        }
      }
    }
    if (forgotten) {
      rebuild();
    }
    if (changed || forgotten) {
      publish();
    }
  }

  /** How many forms are known, for tests. */
  int size() {
    return known.size();
  }

  /** A new form: known from now on, its start and length in the filter. */
  private Form add(long key, char[] form) {
    Form f = new Form(start(form[0], form[1]), form.length);
    forms.put(key, f);
    known.put(key, Boolean.TRUE);
    index(f);
    return f;
  }

  private void index(Form f) {
    int[] lengths = lengthsByStart.getOrDefault(f.start, new int[0]);
    if (Arrays.stream(lengths).noneMatch(l -> l == f.length)) {
      int[] more = Arrays.copyOf(lengths, lengths.length + 1);
      more[lengths.length] = f.length;
      Arrays.sort(more);
      lengthsByStart.put(f.start, more);
    }
    int bit = bit(f.start);
    filter[bit >>> 6] |= 1L << (bit & 63);
  }

  /** The filter and the lengths of the forms still known, after some were forgotten. */
  private void rebuild() {
    Arrays.fill(filter, 0L);
    lengthsByStart.clear();
    forms.values().forEach(this::index);
  }

  private void publish() {
    snapshot = new Snapshot(Map.copyOf(lengthsByStart), filter.clone(), known.size());
  }

  private long keyOf(char[] form) {
    long h = 0;
    for (char c : form) {
      h = h * base + c;
    }
    return key(mix(h), form.length);
  }

  /** {@code text} with every known form replaced by {@code mask}; a hit per replaced run counts as a stored secret. */
  public String mask(String text, String mask, MaskCounter counter) {
    Snapshot s = snapshot;
    int n = text.length();
    if (s.forms() == 0 || n < Taint.MIN_LENGTH) {
      return text;
    }
    long[] ranges = null;
    int count = 0;
    long[] bits = s.filter();
    for (int i = 0; i + 1 < n; i++) {
      int start = start(text.charAt(i), text.charAt(i + 1));
      int bit = bit(start);
      if ((bits[bit >>> 6] & (1L << (bit & 63))) == 0) {
        continue;
      }
      int[] lengths = s.lengthsByStart().get(start);
      if (lengths == null) {
        continue;
      }
      long h = 0;
      int hashed = 0;
      for (int length : lengths) {
        if (i + length > n) {
          break;
        }
        for (; hashed < length; hashed++) {
          h = h * base + text.charAt(i + hashed);
        }
        if (known.containsKey(key(mix(h), length))) {
          if (ranges == null) {
            ranges = new long[8];
          } else if (count == ranges.length) {
            ranges = Arrays.copyOf(ranges, count * 2);
          }
          ranges[count++] = ((long) i << 32) | (i + length);
        }
      }
    }
    if (count == 0) {
      return text;
    }
    Arrays.sort(ranges, 0, count);
    StringBuilder out = new StringBuilder(n);
    int copied = 0;
    int i = 0;
    while (i < count) {
      int from = (int) (ranges[i] >>> 32);
      int end = (int) ranges[i];
      i++;
      while (i < count && (int) (ranges[i] >>> 32) < end) {
        end = Math.max(end, (int) ranges[i]);
        i++;
      }
      if (from < copied) {
        from = copied;
      }
      out.append(text, copied, from).append(mask);
      counter.hit("stored-secret");
      copied = Math.max(copied, end);
    }
    return out.append(text, copied, n).toString();
  }

  private static int start(char first, char second) {
    return (first << 16) | second;
  }

  /** The filter bit of a start: its two characters mixed, so that the common pairs of a script spread out. */
  private int bit(int start) {
    long z = (start ^ salt) * 0x9E3779B97F4A7C15L;
    return (int) (z >>> 44);
  }

  /** The key a form is kept under: its keyed hash and its length. */
  private static long key(long hash, int length) {
    return hash ^ ((long) length * 0xD6E8FEB86659FD93L);
  }

  /** The hash a form is kept as: the keyed polynomial, then a finalizer so that near values don't share bits. */
  private long mix(long h) {
    long z = h ^ salt;
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }
}
