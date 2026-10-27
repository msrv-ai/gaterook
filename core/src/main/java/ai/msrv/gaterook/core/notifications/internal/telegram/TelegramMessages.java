package ai.msrv.gaterook.core.notifications.internal.telegram;

import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.Eligibility;
import ai.msrv.gaterook.core.notifications.internal.Outcomes;
import java.time.ZoneId;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telegram messages (report 13 §3): {@code sendMessage} bodies without {@code parse_mode}, plain text and entities,
 * {@code protect_content} (no forwarding, no saving) and no link previews. Buttons carry
 * {@code <kind>:<request>[:<digest prefix>[:<nonce>]]} in {@code callback_data} (≤ 64 bytes): {@code a} Approve (the
 * first of two steps), {@code A} Confirm approve, {@code b} Back, {@code d} Deny, {@code o} Open in app, {@code t}
 * a test button. The data comes from the client and can be forged, so a click is checked against the linked account,
 * its chat and the message we sent, never trusted by itself.
 */
public final class TelegramMessages {

  private TelegramMessages() {}

  /**
   * A request's text: the action when the verdict lets it show, the outcome line after a decision.
   *
   * @param outcome the line after a decision, or {@code null} while the request waits
   */
  public static TgText request(ApprovalNotice n, Eligibility.Verdict v, ZoneId zone, String outcome) {
    TgText t = new TgText();
    t.entity("bold", (n.prod() ? "PROD · " : "") + "Approval needed").text("\n");
    t.entity("code", n.tool());
    if (n.connection() != null) {
      t.text(" on ").entity("code", n.connection());
    }
    t.text(" · " + n.env().id() + " · from " + n.asker() + " · endpoint " + n.endpoint());
    if (n.prod()) {
      t.text("\nRuns on production");
    }
    t.text("\n\n");
    if (v.showAction()) {
      t.pre(n.action().text(), n.action().language());
    } else {
      t.text(v.withheld() == null ? "The action is shown only in GateRook." : v.withheld());
      if (n.summary() != null) {
        t.text("\n" + n.summary());
      }
    }
    t.text("\n\nAuto-deny at ");
    if (n.expiresAt() != null) {
      t.time(Outcomes.time(n.expiresAt(), zone), n.expiresAt().getEpochSecond());
    }
    t.text(" · rule " + (n.ruleId() == null ? "—" : n.ruleId()));
    if (outcome != null) {
      t.text("\n\n").entity("bold", outcome);
    } else if (v.reason() != null) {
      t.text("\n" + v.reason());
    }
    return t;
  }

  /** The buttons of a waiting request (an empty keyboard once decided). */
  public static ObjectNode keyboard(ApprovalNotice n, Eligibility.Verdict v) {
    ObjectNode markup = JsonNodeFactory.instance.objectNode();
    ArrayNode rows = markup.putArray("inline_keyboard");
    ArrayNode first = JsonNodeFactory.instance.arrayNode();
    if (v.approve()) {
      first.add(button("Approve", "success", "a:" + n.id() + ":" + n.digestPrefix()));
    }
    if (v.deny()) {
      first.add(button("Deny", "danger", "d:" + n.id()));
    }
    if (!first.isEmpty()) {
      rows.add(first);
    }
    rows.addArray().add(button("Open in app", null, "o:" + n.id()));
    return markup;
  }

  /** The second step of Approve: Confirm approve (with a nonce good for 60 s) or Back. */
  public static ObjectNode confirm(ApprovalNotice n, String nonce) {
    ObjectNode markup = JsonNodeFactory.instance.objectNode();
    ArrayNode rows = markup.putArray("inline_keyboard");
    rows.addArray().add(button("Confirm approve", "success", "A:" + n.id() + ":" + n.digestPrefix() + ":" + nonce));
    rows.addArray().add(button("Back", null, "b:" + n.id()));
    return markup;
  }

  public static ObjectNode noButtons() {
    ObjectNode markup = JsonNodeFactory.instance.objectNode();
    markup.putArray("inline_keyboard");
    return markup;
  }

  /** "12 requests are waiting", Open in app only. */
  public static TgText digest(long waiting) {
    TgText t = new TgText();
    if (waiting == 0) {
      return t.text("No requests are waiting.");
    }
    t.entity("bold", waiting == 1 ? "1 request is waiting" : waiting + " requests are waiting");
    return t.text(" for a decision in GateRook. Many arrived at once, so they aren't sent one by one and none can "
        + "be approved from Telegram.");
  }

  public static ObjectNode digestKeyboard() {
    ObjectNode markup = JsonNodeFactory.instance.objectNode();
    markup.putArray("inline_keyboard").addArray().add(button("Open in app", null, "o:digest"));
    return markup;
  }

  public static TgText test() {
    return new TgText().entity("bold", "Test · nothing will run").text("\nApproval requests from GateRook arrive "
        + "like this, with the exact action and the buttons a request may offer.");
  }

  public static ObjectNode testKeyboard() {
    ObjectNode markup = JsonNodeFactory.instance.objectNode();
    ArrayNode rows = markup.putArray("inline_keyboard");
    rows.addArray().add(button("Approve", "success", "t:a")).add(button("Deny", "danger", "t:d"));
    rows.addArray().add(button("Open in app", null, "t:o"));
    return markup;
  }

  /** {@code sendMessage}/{@code editMessageText} arguments for {@code text}. */
  public static ObjectNode body(long chatId, TgText text, ObjectNode keyboard, boolean protect) {
    ObjectNode b = JsonNodeFactory.instance.objectNode();
    b.put("chat_id", chatId);
    if (protect) {
      b.put("protect_content", true);
    }
    b.putObject("link_preview_options").put("is_disabled", true);
    b.put("text", text.text());
    if (!text.entities().isEmpty()) {
      b.set("entities", text.entities());
    }
    if (keyboard != null) {
      b.set("reply_markup", keyboard);
    }
    return b;
  }

  private static ObjectNode button(String text, String style, String data) {
    ObjectNode b = JsonNodeFactory.instance.objectNode().put("text", text);
    if (style != null) {
      b.put("style", style);
    }
    b.put("callback_data", data);
    return b;
  }
}
