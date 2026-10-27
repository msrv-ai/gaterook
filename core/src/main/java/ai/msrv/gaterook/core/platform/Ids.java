package ai.msrv.gaterook.core.platform;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identifiers the core hands out. Rows get them in their constructors, never from the database (ARCHITECTURE.md §4):
 * a UUID for internal rows, a short prefixed id ({@code ap_3kq7m2x9vt4p}, {@code tc_…}, {@code gr_…}, {@code tok_…},
 * {@code og_…}, {@code tpl_…}) where people or agents see the id.
 *
 * <p>Prefixed ids carry 60 random bits from {@link SecureRandom}: they are references, not credentials, but an agent
 * must not be able to guess the handle of another principal's approval request.
 */
public final class Ids {

  /** Lower-case letters and digits: ids survive URLs, shells, TOML and case-insensitive file systems unchanged. */
  private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

  /** Characters after the prefix; 36^12 ≈ 2^62. */
  public static final int RANDOM_LENGTH = 12;

  private static final Pattern PREFIX = Pattern.compile("[a-z]{2,4}_");

  private static final SecureRandom RANDOM = new SecureRandom();

  private Ids() {}

  /** A random UUID string (36 characters), for rows nobody types. */
  public static String uuid() {
    return UUID.randomUUID().toString();
  }

  /**
   * {@code prefix} followed by {@value #RANDOM_LENGTH} random characters of {@code [a-z0-9]}.
   *
   * @param prefix two to four lower-case letters and an underscore, e.g. {@code "ap_"}
   */
  public static String next(String prefix) {
    if (!PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("Invalid id prefix: " + prefix);
    }
    char[] out = new char[prefix.length() + RANDOM_LENGTH];
    prefix.getChars(0, prefix.length(), out, 0);
    for (int i = prefix.length(); i < out.length; i++) {
      out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    }
    return new String(out);
  }

  /** Whether {@code id} has the shape {@link #next(String)} produces for {@code prefix}. */
  public static boolean matches(String prefix, String id) {
    if (id == null || !id.startsWith(prefix) || id.length() != prefix.length() + RANDOM_LENGTH) {
      return false;
    }
    for (int i = prefix.length(); i < id.length(); i++) {
      char c = id.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
        return false;
      }
    }
    return true;
  }
}
