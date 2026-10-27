package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.internal.persistence.NotifyChannel;
import ai.msrv.gaterook.core.platform.EventBus;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The desktop channel (ARCHITECTURE.md §7.4): the core renders the notification and publishes it on the window's
 * event stream as {@code notify.desktop}; Electron main shows it (step E1). The core decides what it says, so every
 * channel has one renderer and one masking; main only decides where it goes — a toast when the window has focus, an
 * OS notification otherwise. The notification always offers Review and Deny and never Approve: Review opens the
 * request, Deny is {@code POST /api/approvals/{id}/decision} with {@code via: "desktop-notification"}, and the core
 * refuses an Approve that comes that way.
 *
 * <p>More than {@code maxBurst} requests within {@value #BURST_SECONDS} s make one summary notification instead
 * ({@code id: "approvals-summary"}, replaced in place), so a burst of requests can't bury the person in pop-ups.
 * Event shape — the contract E1 consumes, documented in ARCHITECTURE.md §7.4.
 */
@Component
public class DesktopNotices {

  /** The burst window. */
  public static final int BURST_SECONDS = 10;

  /** The id of the summary notification: one at a time, replaced in place. */
  public static final String SUMMARY_ID = "approvals-summary";

  /** macOS truncates a notification at 256 bytes of title, subtitle and body (Electron's tutorial). */
  static final int MAC_BYTES = 256;

  private final EventBus events;
  private final ChannelStore channels;
  private final ApprovalService approvals;
  private final Pacer burst;
  private final java.time.Clock clock;
  private final ZoneId zone;

  DesktopNotices(EventBus events, ChannelStore channels, ApprovalService approvals, Pacers pacers,
      java.time.Clock clock) {
    this.events = events;
    this.channels = channels;
    this.approvals = approvals;
    this.burst = pacers.flood();
    this.clock = clock;
    this.zone = ZoneId.systemDefault();
  }

  /** Whether the desktop channel is on (it is, until the person turns it off). */
  public boolean enabled() {
    return channels.read(NotifyChannel.DESKTOP).active();
  }

  /**
   * Announces one request: its own notification, or the summary during a burst.
   *
   * @return {@code true} when an event went out
   */
  public boolean request(ApprovalNotice n) {
    ChannelStore.Row row = channels.read(NotifyChannel.DESKTOP);
    if (!row.active()) {
      return false;
    }
    int maxBurst = Math.max(1, row.config().path("maxBurst").asInt(3));
    boolean sound = row.config().path("soundForProd").asBoolean(true);
    long pending = approvals.pendingTotal();
    int recent = burst.hit("desktop-burst", Duration.ofSeconds(BURST_SECONDS));
    ObjectNode e = recent > maxBurst ? summary(pending, n.prod()) : render(n, sound, pending, zone);
    events.publish("notify.desktop", e);
    channels.delivered(NotifyChannel.DESKTOP, clock.instant());
    return true;
  }

  /** "Send test notification" on the desktop: what a request looks like, decided nowhere. */
  public boolean test() {
    if (!channels.read(NotifyChannel.DESKTOP).active()) {
      return false;
    }
    ObjectNode e = JsonNodeFactory.instance.objectNode();
    e.put("kind", "test");
    e.put("id", "notify-test");
    e.put("title", "Test · nothing will run");
    e.put("subtitle", "MCP Gateway");
    e.put("body", "Approval requests arrive like this, with Review and Deny.");
    e.put("footer", "Deny in a test notification does nothing");
    e.put("urgency", "normal");
    e.put("silent", true);
    e.put("route", "/settings/notifications");
    actions(e, true);
    e.put("pendingCount", approvals.pendingTotal());
    events.publish("notify.desktop", e);
    return true;
  }

  /**
   * The {@code notify.desktop} event of one request.
   *
   * @param soundForProd play a sound for prod requests ({@code silent: false})
   */
  static ObjectNode render(ApprovalNotice n, boolean soundForProd, long pending, ZoneId zone) {
    ObjectNode e = JsonNodeFactory.instance.objectNode();
    String on = n.tool() + (n.connection() == null ? "" : " on " + n.connection());
    String from = " · from " + n.asker();
    String title = (n.prod() ? "PROD · " : "") + "Approval needed · " + on;
    String subtitle = (n.prod() ? "Runs on production" : n.env().id() + " environment") + from;
    String body = n.action().secretLike() || n.action().text().isEmpty()
        ? (n.summary() == null ? "The action is shown only in MCP Gateway." : n.summary())
        : Notices.firstLine(n.action().text(), 120);
    body = fit(title, subtitle, body);
    e.put("kind", "request");
    e.put("id", n.id());
    e.put("group", n.endpoint());
    e.put("env", n.env().id());
    e.put("prod", n.prod());
    e.put("title", title);
    e.put("subtitle", subtitle);
    e.put("body", body);
    e.put("footer", n.env().id() + " environment · " + n.asker() + " · auto-deny at " + Outcomes.time(n.expiresAt(),
        zone));
    e.put("urgency", n.prod() ? "critical" : "normal");
    e.put("silent", !(n.prod() && soundForProd));
    e.put("route", "/approvals/" + n.id());
    e.put("expiresAt", n.expiresAt() == null ? null : n.expiresAt().toString());
    actions(e, true);
    e.put("pendingCount", pending);
    return e;
  }

  /** One notification for a burst: how many wait, Review opens the queue. */
  static ObjectNode summary(long pending, boolean prod) {
    ObjectNode e = JsonNodeFactory.instance.objectNode();
    e.put("kind", "summary");
    e.put("id", SUMMARY_ID);
    e.put("title", pending == 1 ? "1 approval request is waiting" : pending + " approval requests are waiting");
    e.put("subtitle", "MCP Gateway");
    e.put("body", "Many requests arrived at once. Review them in MCP Gateway.");
    e.put("footer", "Each one is denied when its time runs out");
    e.put("urgency", prod ? "critical" : "normal");
    e.put("silent", true);
    e.put("route", "/approvals");
    actions(e, false);
    e.put("pendingCount", pending);
    return e;
  }

  private static void actions(ObjectNode e, boolean deny) {
    ArrayNode a = e.putArray("actions");
    a.addObject().put("id", "review").put("text", "Review");
    if (deny) {
      a.addObject().put("id", "deny").put("text", "Deny");
    }
  }

  /** Cuts {@code body} so that title, subtitle and body fit macOS's 256 bytes, with "…" when cut. */
  static String fit(String title, String subtitle, String body) {
    int room = MAC_BYTES - utf8(title) - utf8(subtitle);
    if (utf8(body) <= room) {
      return body;
    }
    int budget = Math.max(0, room - utf8("…"));
    StringBuilder out = new StringBuilder();
    int used = 0;
    for (int i = 0; i < body.length(); ) {
      int cp = body.codePointAt(i);
      int n = utf8(new String(Character.toChars(cp)));
      if (used + n > budget) {
        break;
      }
      out.appendCodePoint(cp);
      used += n;
      i += Character.charCount(cp);
    }
    return out + "…";
  }

  private static int utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }
}
