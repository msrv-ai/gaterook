package ai.msrv.gaterook.connectors.support;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time arguments of tools ({@code from}, {@code to}, {@code since} of logs, metrics and cloud tools, reports 07 §7,
 * 09 §3): ISO-8601 with an offset ({@code 2026-10-22T14:00:00+02:00}, {@code …Z}) or relative to now ({@code -15m},
 * {@code now-1h}, {@code -2d}, {@code now}). Bare numbers are refused: seconds, milliseconds and nanoseconds would all
 * be plausible, and the agent would not know which one it got. Times without an offset are refused for the same
 * reason (whose local time?).
 */
public final class TimeArg {

  private static final Pattern RELATIVE = Pattern.compile("(?:now)?-(\\d{1,6})([smhdw])");
  private static final String FORMATS = "Use ISO-8601 with an offset (2026-10-22T14:00:00+02:00) or a relative time "
      + "(-15m, -1h, -2d, now-30m, now)";

  private TimeArg() {}

  /** A time argument that doesn't parse; the message is the hint the agent gets ({@code DENY logs.range}). */
  public static final class InvalidTimeException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public InvalidTimeException(String message) {
      super(message);
    }
  }

  /** A checked range: {@code from < to} and not longer than the policy allows. */
  public record Range(Instant from, Instant to) {
    public Duration length() {
      return Duration.between(from, to);
    }
  }

  /**
   * Parses one time argument.
   *
   * @param text the argument; {@code null}, blank or {@code now} is {@code now}
   * @throws InvalidTimeException with the accepted formats
   */
  public static Instant parse(String text, Instant now) {
    Objects.requireNonNull(now, "now");
    if (text == null || text.isBlank()) {
      return now;
    }
    String s = text.trim();
    if (s.equals("now")) {
      return now;
    }
    Matcher m = RELATIVE.matcher(s);
    if (m.matches()) {
      long amount = Long.parseLong(m.group(1));
      Duration unit = switch (m.group(2)) {
        case "s" -> Duration.ofSeconds(1);
        case "m" -> Duration.ofMinutes(1);
        case "h" -> Duration.ofHours(1);
        case "d" -> Duration.ofDays(1);
        default -> Duration.ofDays(7);
      };
      return now.minus(unit.multipliedBy(amount));
    }
    if (s.chars().allMatch(Character::isDigit)) {
      throw new InvalidTimeException("\"" + abbreviate(s) + "\" is a bare number: seconds or milliseconds? " + FORMATS);
    }
    try {
      return OffsetDateTime.parse(s).toInstant();
    } catch (DateTimeParseException e) {
      throw new InvalidTimeException("Can't read the time \"" + abbreviate(s) + "\". " + FORMATS);
    }
  }

  /**
   * Parses {@code from} and {@code to} and checks the range.
   *
   * @param maxRange the policy's limit ({@code maxRangeHours}), or {@code null} for none
   * @throws InvalidTimeException when a time doesn't parse, {@code from} isn't before {@code to} or the range is too long
   */
  public static Range range(String from, String to, Instant now, Duration maxRange) {
    Instant start = parse(from, now);
    Instant end = parse(to, now);
    if (!start.isBefore(end)) {
      throw new InvalidTimeException("from must be before to (from " + start + ", to " + end + ")");
    }
    Duration length = Duration.between(start, end);
    if (maxRange != null && length.compareTo(maxRange) > 0) {
      throw new InvalidTimeException("The range is " + human(length) + "; this environment allows at most "
          + human(maxRange) + ". Narrow from/to.");
    }
    return new Range(start, end);
  }

  private static String human(Duration d) {
    long hours = d.toHours();
    if (hours >= 48 && hours % 24 == 0) {
      return d.toDays() + " days";
    }
    if (hours >= 1) {
      return hours + " h";
    }
    return d.toMinutes() + " min";
  }

  private static String abbreviate(String s) {
    return s.length() <= 40 ? s : s.substring(0, 40) + "…";
  }
}
