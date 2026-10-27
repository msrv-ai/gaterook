package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The line a message gets once its request is decided (report 13 §3): "Approved by you from Slack · executed in 38
 * ms", "Denied by you in GateRook", "Expired at 14:34:12 · no decision in 5 min". It names no data of the resource:
 * a failure says where to look, not what the resource answered.
 */
public final class Outcomes {

  static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

  /**
   * @param text the line
   * @param done the request is over: the message gets no more updates
   */
  public record Outcome(String text, boolean done) {}

  private Outcomes() {}

  /**
   * @param a          the request as it is now
   * @param durationMs how long the approved call ran, when it ran
   */
  public static Outcome of(ApprovalView a, Long durationMs, ZoneId zone) {
    String via = via(a.decidedVia());
    return switch (a.status()) {
      case "running" -> new Outcome("Approved by you " + via + " · running", false);
      case "completed" -> new Outcome("Approved by you " + via + " · executed"
          + (durationMs == null ? "" : " in " + duration(durationMs)), true);
      case "failed" -> new Outcome(person(a.decidedVia())
          ? "Approved by you " + via + " · failed: the details are in GateRook"
          : "Failed: the details are in GateRook", true);
      case "rejected" -> new Outcome(switch (a.decidedVia() == null ? "" : a.decidedVia()) {
        case "core" -> "Rejected by GateRook" + (a.error() == null ? "" : ": " + a.error());
        case "client" -> "Declined in the client: nothing ran";
        default -> "Denied by you " + via;
      }, true);
      case "expired" -> new Outcome("Expired at " + time(a.expiresAt(), zone) + " · no decision in "
          + minutes(a.createdAt(), a.expiresAt()), true);
      case "cancelled" -> new Outcome("Cancelled: nothing ran", true);
      default -> new Outcome("Waiting for a decision", false);
    };
  }

  /** "from Slack", "in GateRook": where a decision was made, as the history writes it. */
  public static String via(String decidedVia) {
    return switch (decidedVia == null ? "" : decidedVia) {
      case "slack" -> "from Slack";
      case "telegram" -> "from Telegram";
      case "desktop-notification" -> "from the desktop notification";
      default -> "in GateRook";
    };
  }

  /** "14:34:12" in the zone of this computer. */
  public static String time(Instant at, ZoneId zone) {
    return at == null ? "" : CLOCK.format(at.atZone(zone));
  }

  /** "UTC+02:00" (or "UTC"): the zone a plain-text time is in, for viewers elsewhere. */
  public static String zoneLabel(Instant at, ZoneId zone) {
    if (at == null) {
      return "";
    }
    String offset = at.atZone(zone).getOffset().getId();
    return "Z".equals(offset) ? "UTC" : "UTC" + offset;
  }

  static String duration(long ms) {
    if (ms < 1_000) {
      return ms + " ms";
    }
    if (ms < 60_000) {
      return String.format(Locale.ROOT, "%.1f s", ms / 1_000.0);
    }
    return (ms / 60_000) + " min " + (ms % 60_000) / 1_000 + " s";
  }

  private static String minutes(Instant from, Instant to) {
    if (from == null || to == null) {
      return "time";
    }
    long m = Math.max(1, Duration.between(from, to).toMinutes());
    return m + " min";
  }

  private static boolean person(String via) {
    return via != null && ApprovalService.CHANNELS.contains(via);
  }
}
