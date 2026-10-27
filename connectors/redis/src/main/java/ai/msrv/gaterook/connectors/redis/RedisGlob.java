package ai.msrv.gaterook.connectors.redis;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Redis glob patterns ({@code * ? [a-z] [^a] \x}) matched exactly as the server's {@code stringmatchlen} matches them
 * for {@code SCAN MATCH} (util.c of Redis 7.0, the same results as 6.2): over bytes, not characters, so {@code ?} is
 * one byte of a UTF-8 key. The key allowlist and denylist of a connection use this matcher, so a pattern means here
 * what it means to the server; a property test runs it against a real {@code SCAN MATCH}.
 *
 * <p>Quirks kept on purpose, because the server has them: a range compares C {@code char}s, so how bytes from
 * {@code 0x80} sort against ASCII inside {@code [..]} depends on the server's platform ({@link Chars}); an
 * unterminated class ends at the end of the pattern; a trailing lone backslash matches a backslash.
 *
 * <p>Like the server since 7.0 (CVE-2022-36021), a star that failed to place the rest of the pattern stops every
 * earlier star from trying longer matches, which keeps the work polynomial, and nesting stops at 1,000 stars.
 */
final class RedisGlob {

  /**
   * How the server compares the bytes of a {@code [a-z]} range: as the C {@code char}s of its platform — signed on
   * x86-64 (and on Apple's arm64), unsigned on arm64 Linux (AWS Graviton, Docker on Apple silicon), ppc64le and s390x.
   * Only a range with one end below {@code 0x80} and the other from {@code 0x80} on means two things
   * ({@link #dependsOnSign()}). Test connection finds out which ({@code RedisProbe}); until then it is
   * {@link #UNKNOWN}, and the key lists hold under both readings.
   */
  enum Chars {
    SIGNED("signed"), UNSIGNED("unsigned"), UNKNOWN(null);

    /** The value of {@code globChars} in the connection's metadata; {@code null} for {@link #UNKNOWN}. */
    final String id;

    Chars(String id) {
      this.id = id;
    }

    static Chars of(String id) {
      for (Chars c : values()) {
        if (c.id != null && c.id.equals(id)) {
          return c;
        }
      }
      return UNKNOWN;
    }

    /**
     * The sign of {@code char} on the platform {@code INFO server} names in {@code os} ({@code Linux 6.8.0 aarch64},
     * {@code Darwin 23.4.0 arm64}): the C ABI of each architecture, with Apple's arm64 keeping it signed.
     */
    static Chars ofPlatform(String os) {
      if (os == null || os.isBlank()) {
        return UNKNOWN;
      }
      String[] parts = os.strip().split("\\s+");
      String system = parts[0].toLowerCase(Locale.ROOT);
      if (system.equals("darwin") || system.startsWith("windows")) {
        return SIGNED;
      }
      return switch (parts[parts.length - 1].toLowerCase(Locale.ROOT)) {
        case "x86_64", "amd64", "i386", "i486", "i586", "i686", "i86pc" -> SIGNED;
        case "aarch64", "arm64", "armv6l", "armv7l", "armv8l", "ppc64le", "ppc64", "s390x", "riscv64" -> UNSIGNED;
        default -> UNKNOWN;
      };
    }
  }

  private static final int MAX_NESTING = 1_000;

  private final byte[] pattern;
  private final String text;

  private RedisGlob(String text) {
    this.text = text;
    this.pattern = text.getBytes(StandardCharsets.UTF_8);
  }

  static RedisGlob of(String pattern) {
    return new RedisGlob(pattern);
  }

  String pattern() {
    return text;
  }

  /** Whether {@code key} matches, ranges compared as signed or unsigned bytes ({@link Chars}). */
  boolean matches(byte[] key, boolean signedChars) {
    // SCAN skips matching for a lone "*" (it would not match the empty key, which is a valid key).
    if (pattern.length == 1 && pattern[0] == '*') {
      return true;
    }
    return match(pattern, 0, pattern.length, key, 0, key.length, new boolean[1], 0, signedChars);
  }

  /** As an x86-64 server matches. */
  boolean matches(byte[] key) {
    return matches(key, true);
  }

  boolean matches(String key) {
    return matches(key.getBytes(StandardCharsets.UTF_8));
  }

  /** Whether any of {@code globs} matches {@code key}. */
  static boolean any(List<RedisGlob> globs, byte[] key, boolean signedChars) {
    for (RedisGlob g : globs) {
      if (g.matches(key, signedChars)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the pattern has a class range from below {@code 0x80} to {@code 0x80} or above: the one thing a signed and
   * an unsigned server match differently. The walk reads the classes as the matcher does (escapes, {@code ]} first,
   * unterminated classes).
   */
  boolean dependsOnSign() {
    byte[] p = pattern;
    int i = 0;
    while (i < p.length) {
      if (p[i] == '\\' && p.length - i >= 2) {
        i += 2;
      } else if (p[i] != '[') {
        i++;
      } else {
        i++;
        if (i < p.length && p[i] == '^') {
          i++;
        }
        while (i < p.length && p[i] != ']') {
          if (p[i] == '\\' && p.length - i >= 2) {
            i += 2;
          } else if (p.length - i >= 3 && p[i + 1] == '-') {
            if ((p[i] < 0) != (p[i + 2] < 0)) {
              return true;
            }
            i += 3;
          } else {
            i++;
          }
        }
        i++;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return "RedisGlob(" + text + ")";
  }

  /** A port of {@code stringmatchlen_impl}: {@code p} and {@code s} walk the pattern and the string. */
  private static boolean match(byte[] pat, int p, int pEnd, byte[] str, int s, int sEnd, boolean[] skipLonger,
      int nesting, boolean signedChars) {
    if (nesting > MAX_NESTING) {
      return false;
    }
    while (p < pEnd && s < sEnd) {
      switch (pat[p]) {
        case '*' -> {
          while (p + 1 < pEnd && pat[p + 1] == '*') {
            p++;
          }
          if (p + 1 == pEnd) {
            return true;
          }
          while (s < sEnd) {
            if (match(pat, p + 1, pEnd, str, s, sEnd, skipLonger, nesting + 1, signedChars)) {
              return true;
            }
            if (skipLonger[0]) {
              return false;
            }
            s++;
          }
          // The rest of the pattern fits nowhere in the rest of the string: a longer match of an earlier star
          // would only start the rest later, so no earlier star needs to try one.
          skipLonger[0] = true;
          return false;
        }
        case '?' -> s++;
        case '[' -> {
          p++;
          boolean not = p < pEnd && pat[p] == '^';
          if (not) {
            p++;
          }
          boolean match = false;
          while (true) {
            if (p < pEnd && pat[p] == '\\' && pEnd - p >= 2) {
              p++;
              if (pat[p] == str[s]) {
                match = true;
              }
            } else if (p < pEnd && pat[p] == ']') {
              break;
            } else if (p >= pEnd) {
              // An unterminated class: the server steps back onto the last byte so the loop below ends the pattern.
              p--;
              break;
            } else if (pEnd - p >= 3 && pat[p + 1] == '-') {
              // C chars of the server's platform: signed (x86-64) or unsigned (arm64 Linux).
              int start = signedChars ? pat[p] : pat[p] & 0xff;
              int end = signedChars ? pat[p + 2] : pat[p + 2] & 0xff;
              int c = signedChars ? str[s] : str[s] & 0xff;
              if (start > end) {
                int t = start;
                start = end;
                end = t;
              }
              p += 2;
              if (c >= start && c <= end) {
                match = true;
              }
            } else if (pat[p] == str[s]) {
              match = true;
            }
            p++;
          }
          if (not) {
            match = !match;
          }
          if (!match) {
            return false;
          }
          s++;
        }
        case '\\' -> {
          if (pEnd - p >= 2) {
            p++;
          }
          if (pat[p] != str[s]) {
            return false;
          }
          s++;
        }
        default -> {
          if (pat[p] != str[s]) {
            return false;
          }
          s++;
        }
      }
      p++;
      if (s == sEnd) {
        while (p < pEnd && pat[p] == '*') {
          p++;
        }
        break;
      }
    }
    return p == pEnd && s == sEnd;
  }
}
