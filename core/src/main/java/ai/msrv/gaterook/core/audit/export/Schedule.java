package ai.msrv.gaterook.core.audit.export;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * When a destination exports ({@code audit_export.schedule}, kit Settings › Audit & retention): {@code stream} (soon
 * after each call), {@code hourly} (at the top of every hour) or {@code daily@HH:MM} (once a day at that time of this
 * computer, {@code daily@02:00} by default).
 */
public record Schedule(Kind kind, LocalTime at) {

  public enum Kind { STREAM, HOURLY, DAILY }

  public static final Schedule STREAM = new Schedule(Kind.STREAM, null);
  public static final Schedule HOURLY = new Schedule(Kind.HOURLY, null);
  public static final Schedule DAILY = new Schedule(Kind.DAILY, LocalTime.of(2, 0));

  public Schedule {
    Objects.requireNonNull(kind, "kind");
    if ((kind == Kind.DAILY) != (at != null)) {
      throw new IllegalArgumentException("Only a daily schedule has a time");
    }
    at = at == null ? null : at.truncatedTo(ChronoUnit.MINUTES);
  }

  /**
   * @throws IllegalArgumentException for anything but {@code stream}, {@code hourly}, {@code daily} and
   *     {@code daily@HH:MM}
   */
  public static Schedule parse(String text) {
    String s = text == null ? "" : text.strip();
    switch (s) {
      case "stream" -> {
        return STREAM;
      }
      case "hourly" -> {
        return HOURLY;
      }
      case "daily" -> {
        return DAILY;
      }
      default -> {
        if (s.startsWith("daily@") && s.length() == 11) {
          try {
            return new Schedule(Kind.DAILY, LocalTime.parse(s.substring(6)));
          } catch (DateTimeParseException e) {
            // Refused below.
          }
        }
        throw new IllegalArgumentException("Export as a stream, hourly or daily at a time: stream, hourly, "
            + "daily@02:00");
      }
    }
  }

  /** The stored form: {@code stream}, {@code hourly}, {@code daily@02:00}. */
  public String id() {
    return switch (kind) {
      case STREAM -> "stream";
      case HOURLY -> "hourly";
      case DAILY -> "daily@" + at;
    };
  }

  /**
   * The first scheduled time after {@code after}, in {@code zone}: the next top of the hour, or the next {@code at}.
   * A stream has no schedule of its own: {@code after} itself.
   */
  public Instant nextAfter(Instant after, ZoneId zone) {
    ZonedDateTime t = after.atZone(zone);
    return switch (kind) {
      case STREAM -> after;
      case HOURLY -> t.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant();
      case DAILY -> {
        ZonedDateTime today = t.toLocalDate().atTime(at).atZone(zone);
        yield today.isAfter(t) ? today.toInstant() : t.toLocalDate().plusDays(1).atTime(at).atZone(zone).toInstant();
      }
    };
  }

  @Override
  public String toString() {
    return id();
  }
}
