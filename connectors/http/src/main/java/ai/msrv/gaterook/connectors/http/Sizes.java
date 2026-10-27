package ai.msrv.gaterook.connectors.http;

import java.util.Locale;

/** Byte counts and durations as the kit writes them: {@code 43 B}, {@code 12 KB}, {@code 1.2 MB}, {@code 84 ms}. */
final class Sizes {

  private Sizes() {}

  static String text(long bytes) {
    if (bytes < 1_024) {
      return bytes + " B";
    }
    if (bytes < 1_024 * 1_024) {
      return (bytes + 512) / 1_024 + " KB";
    }
    return String.format(Locale.US, "%.1f MB", bytes / (1_024.0 * 1_024.0));
  }

  /** "The body has 262,145 bytes; stage allows 262,144 (256 KB)". */
  static String tooLarge(long bytes, long max, String env) {
    return String.format(Locale.US, "The body has %,d bytes; %s allows %,d (%s)", bytes, env, max, text(max));
  }

  static String seconds(java.time.Duration d) {
    long ms = d.toMillis();
    return ms % 1_000 == 0 ? ms / 1_000 + " s" : String.format(Locale.US, "%.1f s", ms / 1_000.0);
  }
}
