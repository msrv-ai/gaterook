package ai.msrv.gaterook.core.notifications;

import java.util.Objects;

/**
 * When a Slack or Telegram message may carry Approve (ARCHITECTURE.md §7.4, report 13 §6.2), checked when the message
 * is sent and again on the click. Approve is offered only if every condition holds:
 * <ol>
 *   <li>decisions from linked accounts are allowed (Settings › Approval rules, {@code app-and-linked});</li>
 *   <li>the request isn't on prod — never, whatever the comment rule says (§8.6.7);</li>
 *   <li>no comment is required (the request's own rule, and the environment's rule now);</li>
 *   <li>the tool isn't a plugin's (its signature can't be vouched for here);</li>
 *   <li>the whole action fits the message (Slack ≤ {@value #SLACK_ACTION_LIMIT} characters, Telegram's whole message
 *       ≤ {@value #TELEGRAM_MESSAGE_LIMIT}) and nothing of it was left out;</li>
 *   <li>the masker found nothing that looks like a secret in it;</li>
 *   <li>no hidden characters and no letters that pass for others ({@code ActionInspector});</li>
 *   <li>no flood: more than {@value #FLOOD_PER_MINUTE} new requests a minute turn the channel into one digest message;</li>
 *   <li>the request still waits for a person.</li>
 * </ol>
 * Otherwise the message offers Deny and Open in app, and says why in its text. With decisions in the app only it
 * offers Open in app alone. An action that doesn't fit or looks like a secret isn't sent at all: a cut action would
 * look whole.
 */
public final class Eligibility {

  /** The exact action of a Slack message, in UTF-16 units (≥ code points: a conservative count). */
  public static final int SLACK_ACTION_LIMIT = 3_000;

  /** A whole Telegram message, in UTF-16 units (what the Bot API counts). */
  public static final int TELEGRAM_MESSAGE_LIMIT = 4_096;

  /** New requests a minute a channel announces one by one; more and it posts a digest. */
  public static final int FLOOD_PER_MINUTE = 5;

  /** A chat channel that can carry buttons. */
  public enum Channel { SLACK, TELEGRAM }

  /**
   * What the decision depends on.
   *
   * @param decisionsFromLinked   Settings › Approval rules allow decisions from linked accounts
   * @param commentRequiredNow    the environment's policy requires a comment now (it may have changed since the request)
   * @param actionFits            the whole action fits this channel's message
   * @param flood                 the channel is in digest mode
   * @param pending               the request still waits for a person
   */
  public record Facts(Channel channel, ApprovalNotice notice, boolean decisionsFromLinked, boolean commentRequiredNow,
      boolean actionFits, boolean flood, boolean pending) {

    public Facts {
      Objects.requireNonNull(channel, "channel");
      Objects.requireNonNull(notice, "notice");
    }
  }

  /**
   * What the message offers.
   *
   * @param approve    Approve is offered
   * @param deny       Deny is offered (a linked account may always deny, unless decisions are made in the app only)
   * @param showAction the exact action goes into the message
   * @param reason     why there is no Approve, in the message's words; {@code null} when there is
   * @param withheld   why the action isn't in the message; {@code null} when it is
   */
  public record Verdict(boolean approve, boolean deny, boolean showAction, String reason, String withheld) {}

  private Eligibility() {}

  public static Verdict evaluate(Facts f) {
    ApprovalNotice n = f.notice();
    ApprovalNotice.Action a = n.action();
    boolean showAction = !a.secretLike() && f.actionFits();
    String withheld = a.secretLike()
        ? "The action contains values that look like secrets, so it is shown only in MCP Gateway."
        : !f.actionFits() ? String.format(java.util.Locale.ROOT, "The action is %,d characters long and is shown only "
            + "in MCP Gateway.", a.length())
        : null;
    if (!f.pending()) {
      return new Verdict(false, false, showAction, "The request was decided.", withheld);
    }
    if (!f.decisionsFromLinked()) {
      return new Verdict(false, false, showAction, "Decisions are made in MCP Gateway only.", withheld);
    }
    String reason = reason(f, n, a);
    return new Verdict(reason == null, true, showAction, reason, withheld);
  }

  private static String reason(Facts f, ApprovalNotice n, ApprovalNotice.Action a) {
    if (f.flood()) {
      return "Many requests are waiting: decide in the app.";
    }
    if (n.prod()) {
      return "Runs on production: approve in the app.";
    }
    if (n.commentRequired() || f.commentRequiredNow()) {
      return "A comment is required: approve in the app.";
    }
    if (n.plugin()) {
      return "A plugin's action: approve in the app.";
    }
    if (a.secretLike()) {
      return "The action may contain a secret: approve in the app.";
    }
    if (!f.actionFits()) {
      return "The action is too long for this message: approve in the app.";
    }
    if (a.partial()) {
      return "Part of the action is shown only in MCP Gateway: approve in the app.";
    }
    if (a.hiddenChars() > 0) {
      return "Contains " + a.hiddenChars() + (a.hiddenChars() == 1 ? " hidden character" : " hidden characters")
          + ". Review in the app.";
    }
    if (a.confusables() > 0) {
      return "Contains letters that look like others. Review in the app.";
    }
    return null;
  }
}
