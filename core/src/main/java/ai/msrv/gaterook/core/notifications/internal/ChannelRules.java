package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.Eligibility;
import ai.msrv.gaterook.core.policy.PolicyService;
import ai.msrv.gaterook.core.settings.SettingsService;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The rules every channel applies the same way: where an environment's requests are announced
 * ({@code policy.sections.approvals.announceIn}), whether decisions from linked accounts are allowed (Settings ›
 * Approval rules), the flood window, and {@link Eligibility} with today's policy — at send time and again at the
 * click. A decision from a channel goes to {@link ApprovalService#decide}, the one path every decision takes.
 */
@Component
public class ChannelRules {

  /** The window of the flood rule. */
  public static final Duration FLOOD_WINDOW = Duration.ofMinutes(1);

  private final PolicyService policies;
  private final SettingsService settings;
  private final ApprovalService approvals;
  private final Notices notices;
  private final Pacer pacer;
  private final int floodPerMinute;

  ChannelRules(PolicyService policies, SettingsService settings, ApprovalService approvals, Notices notices,
      Pacers pacers, NotifyConfig config) {
    this.policies = policies;
    this.settings = settings;
    this.approvals = approvals;
    this.notices = notices;
    this.pacer = pacers.flood();
    this.floodPerMinute = config.floodPerMinute();
  }

  /** The channels requests of {@code env} are announced in ({@code desktop}, {@code slack}, {@code telegram}). */
  public Set<String> announceIn(ai.msrv.gaterook.connector.api.Env env) {
    Set<String> out = new LinkedHashSet<>();
    policies.section(env, "approvals").path("announceIn").forEach(c -> out.add(c.asString()));
    return out;
  }

  /** Settings › Approval rules: decisions also from linked Slack and Telegram accounts. */
  public boolean decisionsFromLinked() {
    try {
      return "app-and-linked".equals(settings.value("approval-rules", "decisionsFrom").asString("app-and-linked"));
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The environment's policy requires a comment now. */
  public boolean commentRequiredNow(ai.msrv.gaterook.connector.api.Env env) {
    JsonNode comment = policies.section(env, "approvals").path("comment");
    return "required".equals(comment.asString(env == ai.msrv.gaterook.connector.api.Env.PROD ? "required" : "optional"));
  }

  /** Counts one new request for {@code channel} and tells whether the channel is flooded now. */
  public boolean flood(String channel) {
    return pacer.hit("flood:" + channel, FLOOD_WINDOW) > floodPerMinute;
  }

  /** Whether {@code channel} is flooded, without counting a request. */
  public boolean flooded(String channel) {
    return pacer.count("flood:" + channel, FLOOD_WINDOW) > floodPerMinute;
  }

  public Eligibility.Verdict verdict(Eligibility.Channel channel, ApprovalNotice n, boolean fits, boolean flood,
      boolean pending) {
    return Eligibility.evaluate(new Eligibility.Facts(channel, n, decisionsFromLinked(), commentRequiredNow(n.env()),
        fits, flood, pending));
  }

  /** The outcome of a click, in the words the clicker sees. */
  public record Clicked(boolean decided, String text) {}

  /**
   * A linked account's decision. Approve is checked again against everything (§7.4: at send and at the click): the
   * request as it is now, today's policy, the message's own offer; then it goes the one way every decision goes.
   *
   * @param offeredApprove the message carried Approve
   * @param fits           the request's action fits this channel's message, as rendered now
   */
  public Clicked decide(Eligibility.Channel channel, String approvalId, boolean approve, String digestPrefix,
      String externalId, boolean offeredApprove, java.util.function.Predicate<ApprovalNotice> fits) {
    ApprovalView a = approvals.find(approvalId).orElse(null);
    if (a == null) {
      return new Clicked(false, "This request no longer exists in GateRook.");
    }
    if (!a.pending()) {
      return new Clicked(false, alreadyText(a));
    }
    String via = channel.name().toLowerCase(Locale.ROOT);
    if (!decisionsFromLinked()) {
      return new Clicked(false, "Decisions are made in GateRook only. Open the request in the app.");
    }
    if (approve) {
      ApprovalNotice n = notices.of(a);
      Eligibility.Verdict v = verdict(channel, n, fits.test(n), flooded(via), true);
      if (!offeredApprove || !v.approve()) {
        return new Clicked(false, "Approve isn't available here. Open the request in the app."
            + (v.reason() == null ? "" : " " + v.reason()));
      }
    }
    try {
      approvals.decide(approvalId, approve ? ApprovalService.Decision.APPROVE : ApprovalService.Decision.DENY, via,
          null, digestPrefix, externalId);
      return new Clicked(true, approve ? "Approved: GateRook runs it now." : "Denied: nothing ran.");
    } catch (ai.msrv.gaterook.core.platform.ProblemException e) {
      ApprovalView now = approvals.find(approvalId).orElse(a);
      return new Clicked(false, now.pending() ? e.getMessage() : alreadyText(now));
    }
  }

  /** "Already approved by you in GateRook": what a late click learns. */
  public static String alreadyText(ApprovalView a) {
    String via = Outcomes.via(a.decidedVia());
    return switch (a.status()) {
      case "running", "completed", "failed" -> "Already approved by you " + via + ".";
      case "rejected" -> "core".equals(a.decidedVia()) || "client".equals(a.decidedVia())
          ? "Already rejected: nothing ran." : "Already denied by you " + via + ".";
      case "expired" -> "This request expired: nothing ran.";
      default -> "This request was already decided.";
    };
  }

  /** {@code channel}, {@code state} and the rest of a {@code notified} timeline entry. */
  public static Map<String, Object> notified(String channel, String state, String error, boolean digest) {
    Map<String, Object> d = new java.util.LinkedHashMap<>();
    d.put("channel", channel);
    d.put("state", state);
    if (error != null) {
      d.put("error", error);
    }
    if (digest) {
      d.put("digest", true);
    }
    return d;
  }
}
