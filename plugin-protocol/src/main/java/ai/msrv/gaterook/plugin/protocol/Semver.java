package ai.msrv.gaterook.plugin.protocol;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic versions and the range expressions of a plugin's {@code requires} (report 11 §4.2):
 * {@code ">=0.4.0 & <1.0.0"}, {@code "1.x"} is not supported. Conditions joined by {@code &} must all hold, groups
 * joined by {@code |} any. Pre-release tags compare by semver rules ({@code 1.0.0-rc.1 < 1.0.0}), build metadata is
 * ignored.
 */
public record Semver(int major, int minor, int patch, String preRelease) implements Comparable<Semver> {

  private static final Pattern VERSION = Pattern.compile(
      "^(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})(?:-([0-9A-Za-z.-]{1,64}))?(?:\\+[0-9A-Za-z.-]{1,64})?$");
  private static final Pattern CONDITION = Pattern.compile("^(>=|<=|>|<|=|==)?\\s*(\\S+)$");

  public Semver {
    if (preRelease != null && preRelease.isEmpty()) {
      preRelease = null;
    }
  }

  public static boolean valid(String text) {
    return text != null && VERSION.matcher(text.strip()).matches();
  }

  /** @throws IllegalArgumentException when {@code text} isn't a semantic version */
  public static Semver parse(String text) {
    Matcher m = text == null ? null : VERSION.matcher(text.strip());
    if (m == null || !m.matches()) {
      throw new IllegalArgumentException("Not a semantic version: " + text);
    }
    return new Semver(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
        m.group(4));
  }

  /** The same version without its pre-release tag: a development build of 0.4.0 satisfies {@code >=0.4.0}. */
  public Semver release() {
    return new Semver(major, minor, patch, null);
  }

  /**
   * Whether {@code version} satisfies {@code range}.
   *
   * @throws IllegalArgumentException when the range is malformed
   */
  public static boolean satisfies(Semver version, String range) {
    Objects.requireNonNull(version, "version");
    if (range == null || range.isBlank() || range.strip().equals("*")) {
      return true;
    }
    for (String group : range.split("\\|")) {
      boolean all = true;
      boolean any = false;
      for (String part : group.split("&")) {
        String condition = part.strip();
        if (condition.isEmpty()) {
          continue;
        }
        any = true;
        if (!holds(version, condition)) {
          all = false;
        }
      }
      if (!any) {
        throw new IllegalArgumentException("Empty condition in " + range);
      }
      if (all) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code range} parses. */
  public static boolean validRange(String range) {
    try {
      satisfies(new Semver(0, 0, 0, null), range);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean holds(Semver version, String condition) {
    Matcher m = CONDITION.matcher(condition);
    if (!m.matches()) {
      throw new IllegalArgumentException("Not a version condition: " + condition);
    }
    String op = m.group(1) == null ? "=" : m.group(1);
    int c = version.compareTo(parse(m.group(2)));
    return switch (op) {
      case ">=" -> c >= 0;
      case "<=" -> c <= 0;
      case ">" -> c > 0;
      case "<" -> c < 0;
      default -> c == 0;
    };
  }

  @Override
  public int compareTo(Semver o) {
    int c = Integer.compare(major, o.major);
    if (c == 0) {
      c = Integer.compare(minor, o.minor);
    }
    if (c == 0) {
      c = Integer.compare(patch, o.patch);
    }
    if (c != 0) {
      return c;
    }
    if (preRelease == null || o.preRelease == null) {
      return preRelease == null ? (o.preRelease == null ? 0 : 1) : -1;
    }
    String[] a = preRelease.split("\\.");
    String[] b = o.preRelease.split("\\.");
    for (int i = 0; i < Math.min(a.length, b.length); i++) {
      boolean na = !a[i].isEmpty() && a[i].length() < 18 && a[i].chars().allMatch(Character::isDigit);
      boolean nb = !b[i].isEmpty() && b[i].length() < 18 && b[i].chars().allMatch(Character::isDigit);
      int r;
      if (na && nb) {
        r = Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]));
      } else if (na != nb) {
        r = na ? -1 : 1;
      } else {
        r = a[i].compareTo(b[i]);
      }
      if (r != 0) {
        return r;
      }
    }
    return Integer.compare(a.length, b.length);
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease);
  }
}
