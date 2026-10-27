package ai.msrv.gaterook.core.notifications.internal.slack;

import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.Eligibility;
import ai.msrv.gaterook.core.notifications.internal.Outcomes;
import java.time.Instant;
import java.time.ZoneId;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Block Kit messages (report 13 §3), built as JSON trees. The exact action goes into {@code rich_text_preformatted},
 * whose text Slack shows as it is (no mrkdwn, so {@code & < > *} need no escaping — UNVERIFIED on a live workspace,
 * see report 13 §1); everything in mrkdwn is escaped ({@code & < >}). Names are in code spans, times are
 * {@code <!date^…>} (the viewer's time zone) with the computer's time as the fallback. Prod requests start with
 * "PROD ·". Buttons carry {@code v1|<instance>|<request>|<digest prefix>}: a click is checked against all of it.
 * Limits (docs.slack.dev): header ≤ 150, button text ≤ 75, confirm title ≤ 100 and text ≤ 300.
 */
public final class SlackBlocks {

  static final String APPROVE = "gw_approve";
  static final String DENY = "gw_deny";
  static final String OPEN = "gw_open";
  static final String LINK = "gw_link";
  static final String NOT_ME = "gw_link_deny";
  static final String TEST = "gw_test";

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private SlackBlocks() {}

  /** {@code v1|<instance>|<subject>|<check>}: what a button carries. */
  public static String value(String instance, String subject, String check) {
    return "v1|" + instance + "|" + subject + "|" + (check == null || check.isEmpty() ? "-" : check);
  }

  /** "Approval needed: sql_execute on dev-db" / "PROD · Approval needed: ssh_run on prod-vm" (≤ 150). */
  public static String title(ApprovalNotice n) {
    String t = (n.prod() ? "PROD · " : "") + "Approval needed: " + n.tool()
        + (n.connection() == null ? "" : " on " + n.connection());
    return cut(t, 150);
  }

  /**
   * The message of one request.
   *
   * @param verdict what the message may offer and show
   * @param rev     a fresh suffix for the block ids ({@code 1} for the first post; an update needs a new one)
   * @param outcome the line after a decision, or {@code null} while the request waits (then the buttons show)
   */
  public static ObjectNode request(ApprovalNotice n, Eligibility.Verdict verdict, String instance, String rev,
      ZoneId zone, String outcome) {
    ObjectNode msg = F.objectNode();
    msg.put("text", title(n));
    msg.put("unfurl_links", false);
    msg.put("unfurl_media", false);
    ArrayNode blocks = msg.putArray("blocks");
    blocks.addObject().put("type", "header").set("text", plain(title(n)));
    if (n.prod()) {
      blocks.addObject().put("type", "section").set("text", mrkdwn("*PROD* · Runs on production"));
    }
    ArrayNode fields = blocks.addObject().put("type", "section").putArray("fields");
    fields.add(mrkdwn("*Connection*\n" + (n.connection() == null ? "—" : code(n.connection())) + " · "
        + n.env().id()));
    fields.add(mrkdwn("*Endpoint*\n" + code(n.endpoint())));
    String client = n.clientName();
    fields.add(mrkdwn("*Client*\n" + (client == null ? "" : escape(client) + " · ")
        + (n.clientLabel() == null ? "—" : code(n.clientLabel()))));
    fields.add(mrkdwn("*Auto-deny*\n" + date(n.expiresAt(), zone)));
    if (verdict.showAction()) {
      ObjectNode pre = F.objectNode().put("type", "rich_text_preformatted");
      if (n.action().language() != null && !n.action().language().isBlank()) {
        pre.put("language", n.action().language());
      }
      pre.put("border", 0);
      pre.putArray("elements").addObject().put("type", "text").put("text", n.action().text());
      ObjectNode rich = blocks.addObject().put("type", "rich_text").put("block_id", "act." + n.id() + "." + rev);
      rich.putArray("elements").add(pre);
    } else {
      String withheld = verdict.withheld() == null ? "The action is shown only in MCP Gateway." : verdict.withheld();
      blocks.addObject().put("type", "section").put("block_id", "act." + n.id() + "." + rev)
          .set("text", mrkdwn(escape(withheld) + (n.summary() == null ? "" : "\n" + escape(n.summary()))));
    }
    StringBuilder context = new StringBuilder("Rule " + code(n.ruleId() == null ? "—" : n.ruleId()));
    if (outcome == null && verdict.reason() != null) {
      context.append(" · ").append(escape(verdict.reason()));
    }
    blocks.addObject().put("type", "context").putArray("elements").add(mrkdwn(context.toString()));
    if (outcome != null) {
      blocks.addObject().put("type", "context").putArray("elements").add(mrkdwn(escape(outcome)));
      return msg;
    }
    // Open in app is always there: without decisions from linked accounts it is the only button.
    ArrayNode actions = blocks.addObject().put("type", "actions").put("block_id", "dec." + n.id() + "." + rev)
        .putArray("elements");
    String value = value(instance, n.id(), n.digestPrefix());
    if (verdict.approve()) {
      ObjectNode approve = button(APPROVE, "Approve", value).put("style", "primary");
      ObjectNode confirm = approve.putObject("confirm");
      confirm.set("title", plain(cut(n.connection() == null ? "Run this?" : "Run this on " + n.connection() + "?",
          100)));
      confirm.set("text", plain("The action above runs once, exactly as shown."));
      confirm.set("confirm", plain("Approve and run"));
      confirm.set("deny", plain("Cancel"));
      confirm.put("style", "primary");
      actions.add(approve);
    }
    if (verdict.deny()) {
      actions.add(button(DENY, "Deny", value).put("style", "danger"));
    }
    actions.add(button(OPEN, "Open in app", value));
    return msg;
  }

  /** One message instead of many: "12 requests are waiting" with Open in app only (a flood is no time to tap). */
  public static ObjectNode digest(int waiting, String instance, String rev) {
    ObjectNode msg = F.objectNode();
    String head = waiting == 1 ? "1 request is waiting" : waiting + " requests are waiting";
    msg.put("text", waiting == 0 ? "No requests are waiting" : head);
    ArrayNode blocks = msg.putArray("blocks");
    if (waiting == 0) {
      blocks.addObject().put("type", "section").set("text", mrkdwn("No requests are waiting."));
      return msg;
    }
    blocks.addObject().put("type", "section").set("text", mrkdwn("*" + head + "* for a decision in MCP Gateway. "
        + "Many arrived at once, so they aren't sent one by one and none can be approved from Slack."));
    blocks.addObject().put("type", "actions").put("block_id", "digest." + rev).putArray("elements")
        .add(button(OPEN, "Open in app", value(instance, "digest", null)));
    return msg;
  }

  /** "Send test notification": the look of a request, with buttons that only answer "Test button works". */
  public static ObjectNode test(String instance) {
    ObjectNode msg = F.objectNode();
    msg.put("text", "Test · nothing will run");
    ArrayNode blocks = msg.putArray("blocks");
    blocks.addObject().put("type", "header").set("text", plain("Test · nothing will run"));
    blocks.addObject().put("type", "section").set("text", mrkdwn("Approval requests from MCP Gateway arrive like "
        + "this, with the exact action and the buttons a request may offer."));
    String value = value(instance, "test", null);
    blocks.addObject().put("type", "actions").put("block_id", "test.1").putArray("elements")
        .add(button(TEST, "Approve", value).put("style", "primary"))
        .add(button(TEST, "Deny", value).put("style", "danger"))
        .add(button(TEST, "Open in app", value));
    return msg;
  }

  /** The DM that links an account: the code the app shows, and Link account / Not me. */
  public static ObjectNode linkRequest(String host, String code, String instance, String nonce) {
    ObjectNode msg = F.objectNode();
    String text = "Link MCP Gateway on " + host + " to this Slack account? Code " + code;
    msg.put("text", text);
    ArrayNode blocks = msg.putArray("blocks");
    blocks.addObject().put("type", "section").set("text", mrkdwn("Link MCP Gateway on *" + escape(host) + "* to this "
        + "Slack account? Code *" + escape(code) + "*\nCheck that MCP Gateway shows the same code. Once linked, "
        + "approval requests come here and you can decide them with the buttons."));
    String value = value(instance, "link", nonce);
    blocks.addObject().put("type", "actions").put("block_id", "link.1").putArray("elements")
        .add(button(LINK, "Link account", value).put("style", "primary"))
        .add(button(NOT_ME, "Not me", value));
    return msg;
  }

  /** The link DM once answered. */
  public static ObjectNode linkAnswered(String text) {
    ObjectNode msg = F.objectNode();
    msg.put("text", text);
    msg.putArray("blocks").addObject().put("type", "section").set("text", mrkdwn(escape(text)));
    return msg;
  }

  /** mrkdwn needs {@code & < >} escaped; nothing else is special outside formatting characters we never emit. */
  public static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** A name in a code span; a backtick inside would end it, so it is replaced by a look-alike quote. */
  static String code(String s) {
    return "`" + escape(s).replace('`', 'ˋ') + "`";
  }

  /** {@code <!date^1792672452^{time_secs}|14:34:12 UTC+02:00>}: the viewer's time, ours as the fallback. */
  static String date(Instant at, ZoneId zone) {
    if (at == null) {
      return "—";
    }
    return "<!date^" + at.getEpochSecond() + "^{time_secs}|" + Outcomes.time(at, zone) + " "
        + Outcomes.zoneLabel(at, zone) + ">";
  }

  private static ObjectNode button(String actionId, String text, String value) {
    ObjectNode b = F.objectNode();
    b.put("type", "button");
    b.put("action_id", actionId);
    b.set("text", plain(text));
    b.put("value", value);
    return b;
  }

  private static ObjectNode plain(String text) {
    return F.objectNode().put("type", "plain_text").put("text", text);
  }

  private static ObjectNode mrkdwn(String text) {
    return F.objectNode().put("type", "mrkdwn").put("text", text);
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }
}
