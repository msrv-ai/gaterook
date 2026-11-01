package ai.msrv.gaterook.core.clients.internal;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a bridge is older than this app, by Semantic Versioning 2.0 precedence. A Claude Desktop extension keeps the
 * {@code gw-bridge.mjs} it was built with and reports that version with every token request; this app builds its
 * extensions with its own version's bridge. A version that isn't semantic, on either side, is never older: the app
 * doesn't ask for a reinstall it can't justify.
 */
public final class BridgeVersion {

  private static final Pattern SEMVER = Pattern.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
      + "(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?"
      + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
  private static final Pattern NUMERIC = Pattern.compile("[0-9]+");

  private BridgeVersion() {}

  /**
   * {@code 0.4.3} against {@code 0.5.0}, {@code 0.5.0-SNAPSHOT} against {@code 0.5.0}: older. The same version, a
   * newer one, one that differs only in build metadata, or one that is missing or not semantic: not older.
   */
  public static boolean older(String reported, String current) {
    Integer order = compare(reported, current);
    return order != null && order < 0;
  }

  /** The precedence of {@code a} against {@code b} (negative, zero, positive); {@code null} if one isn't semantic. */
  static Integer compare(String a, String b) {
    Matcher x = a == null ? null : SEMVER.matcher(a);
    Matcher y = b == null ? null : SEMVER.matcher(b);
    if (x == null || y == null || !x.matches() || !y.matches()) {
      return null;
    }
    for (int group = 1; group <= 3; group++) {
      int order = new BigInteger(x.group(group)).compareTo(new BigInteger(y.group(group)));
      if (order != 0) {
        return order;
      }
    }
    String p = x.group(4);
    String q = y.group(4);
    if (p == null || q == null) {
      // A pre-release comes before its release: 0.5.0-SNAPSHOT < 0.5.0.
      return p == null ? (q == null ? 0 : 1) : -1;
    }
    String[] left = p.split("\\.");
    String[] right = q.split("\\.");
    for (int i = 0; i < Math.min(left.length, right.length); i++) {
      int order = identifier(left[i], right[i]);
      if (order != 0) {
        return order;
      }
    }
    return Integer.compare(left.length, right.length);
  }

  /** Numeric identifiers by value and before alphanumeric ones, which compare in ASCII order. */
  private static int identifier(String a, String b) {
    boolean numericA = NUMERIC.matcher(a).matches();
    boolean numericB = NUMERIC.matcher(b).matches();
    if (numericA && numericB) {
      return new BigInteger(a).compareTo(new BigInteger(b));
    }
    if (numericA || numericB) {
      return numericA ? -1 : 1;
    }
    return Integer.signum(a.compareTo(b));
  }
}
