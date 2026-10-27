package ai.msrv.gaterook.core.notifications.internal.slack;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.Eligibility;
import ai.msrv.gaterook.core.notifications.NoticeFixtures;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Golden Block Kit (report 13 §3): the dev request with Approve, prod without it and with "PROD ·", the 3,000/3,001
 * boundary of the action, hidden characters, a secret-like action, the message after a decision, the digest; Slack's
 * limits (header ≤ 150, button text ≤ 75, confirm title ≤ 100) and mrkdwn escaping.
 */
class SlackBlocksTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ZoneId UTC = ZoneOffset.ofHours(2);

  private static Eligibility.Verdict verdict(ApprovalNotice n, boolean pending) {
    return Eligibility.evaluate(new Eligibility.Facts(Eligibility.Channel.SLACK, n, true, n.commentRequired(),
        SlackChannel.fits(n), false, pending));
  }

  @Test
  void theDevRequestIsTheGolden() {
    ApprovalNotice n = NoticeFixtures.dev();
    ObjectNode msg = SlackBlocks.request(n, verdict(n, true), "ins_test0000001", "1", UTC, null);
    JsonNode expected = JSON.readTree("""
        {"text":"Approval needed: sql_execute on dev-db","unfurl_links":false,"unfurl_media":false,"blocks":[
         {"type":"header","text":{"type":"plain_text","text":"Approval needed: sql_execute on dev-db"}},
         {"type":"section","fields":[{"type":"mrkdwn","text":"*Connection*\\n`dev-db` · dev"},
           {"type":"mrkdwn","text":"*Endpoint*\\n`dev`"},{"type":"mrkdwn","text":"*Client*\\nCodex · `codex-laptop`"},
           {"type":"mrkdwn","text":"*Auto-deny*\\n<!date^1792672452^{time_secs}|14:34:12 UTC+02:00>"}]},
         {"type":"rich_text","block_id":"act.ap_3kq7m2x9vt4p.1","elements":[{"type":"rich_text_preformatted",
           "language":"sql","border":0,"elements":[{"type":"text",
           "text":"UPDATE orders SET status = 'cancelled' WHERE id = 48213;"}]}]},
         {"type":"context","elements":[{"type":"mrkdwn","text":"Rule `dev.sql.write-approval`"}]},
         {"type":"actions","block_id":"dec.ap_3kq7m2x9vt4p.1","elements":[
          {"type":"button","action_id":"gw_approve","text":{"type":"plain_text","text":"Approve"},
           "value":"v1|ins_test0000001|ap_3kq7m2x9vt4p|9f2c1a7e5d3b","style":"primary",
           "confirm":{"title":{"type":"plain_text","text":"Run this on dev-db?"},
             "text":{"type":"plain_text","text":"The action above runs once, exactly as shown."},
             "confirm":{"type":"plain_text","text":"Approve and run"},"deny":{"type":"plain_text","text":"Cancel"},
             "style":"primary"}},
          {"type":"button","action_id":"gw_deny","text":{"type":"plain_text","text":"Deny"},
           "value":"v1|ins_test0000001|ap_3kq7m2x9vt4p|9f2c1a7e5d3b","style":"danger"},
          {"type":"button","action_id":"gw_open","text":{"type":"plain_text","text":"Open in app"},
           "value":"v1|ins_test0000001|ap_3kq7m2x9vt4p|9f2c1a7e5d3b"}]}]}""");
    assertThat(msg).isEqualTo(expected);
  }

  @Test
  void prodStartsWithProdOffersNoApproveAndSaysWhy() {
    ApprovalNotice n = NoticeFixtures.prod();
    ObjectNode msg = SlackBlocks.request(n, verdict(n, true), "ins_test0000001", "1", UTC, null);
    assertThat(msg.path("text").asString()).isEqualTo("PROD · Approval needed: ssh_run on prod-vm");
    assertThat(msg.at("/blocks/0/text/text").asString()).isEqualTo("PROD · Approval needed: ssh_run on prod-vm");
    assertThat(msg.at("/blocks/1/text/text").asString()).isEqualTo("*PROD* · Runs on production");
    assertThat(actionIds(msg)).containsExactly("gw_deny", "gw_open");
    assertThat(context(msg)).isEqualTo("Rule `prod.ssh.runbook-approval` · Runs on production: approve in the app.");
    assertThat(msg.at("/blocks/3/elements/0/language").asString()).isEqualTo("sh");
  }

  @Test
  void anActionOf3000CharactersIsSentAndOneOf3001IsNot() {
    ApprovalNotice fits = NoticeFixtures.with(NoticeFixtures.dev(), NoticeFixtures.action("sql", "sql",
        "-- " + "x".repeat(2_997)));
    ApprovalNotice tooLong = NoticeFixtures.with(NoticeFixtures.dev(), NoticeFixtures.action("sql", "sql",
        "-- " + "x".repeat(2_998)));
    assertThat(fits.action().text()).hasSize(3_000);
    assertThat(tooLong.action().text()).hasSize(3_001);

    ObjectNode a = SlackBlocks.request(fits, verdict(fits, true), "ins_test0000001", "1", UTC, null);
    assertThat(a.toString()).contains("rich_text_preformatted");
    assertThat(actionIds(a)).containsExactly("gw_approve", "gw_deny", "gw_open");

    ObjectNode b = SlackBlocks.request(tooLong, verdict(tooLong, true), "ins_test0000001", "1", UTC, null);
    assertThat(b.toString()).doesNotContain("rich_text_preformatted").doesNotContain("xxxxxxxxxx");
    assertThat(b.at("/blocks/2/text/text").asString()).isEqualTo("The action is 3,001 characters long and is shown "
        + "only in MCP Gateway.\n1 row in orders");
    assertThat(actionIds(b)).containsExactly("gw_deny", "gw_open");
  }

  @Test
  void hiddenCharactersShowAsEscapesAndDropApprove() {
    ApprovalNotice n = NoticeFixtures.with(NoticeFixtures.dev(), new ApprovalNotice.Action("sql", "sql",
        "DELETE FROM orders‹U+202E› WHERE id = 1;", 39, false, 1, 0, false));
    ObjectNode msg = SlackBlocks.request(n, verdict(n, true), "ins_test0000001", "1", UTC, null);
    assertThat(msg.at("/blocks/2/elements/0/elements/0/text").asString()).contains("‹U+202E›");
    assertThat(actionIds(msg)).containsExactly("gw_deny", "gw_open");
    assertThat(context(msg)).endsWith("Contains 1 hidden character. Review in the app.");
  }

  @Test
  void anActionThatLooksLikeASecretIsNeverSent() {
    ApprovalNotice n = NoticeFixtures.with(NoticeFixtures.dev(), new ApprovalNotice.Action("sql", "sql", "", 64, true,
        0, 0, false));
    ObjectNode msg = SlackBlocks.request(n, verdict(n, true), "ins_test0000001", "1", UTC, null);
    assertThat(msg.toString()).doesNotContain("rich_text");
    assertThat(msg.at("/blocks/2/text/text").asString()).startsWith("The action contains values that look like "
        + "secrets, so it is shown only in MCP Gateway.");
    assertThat(actionIds(msg)).containsExactly("gw_deny", "gw_open");
  }

  @Test
  void afterADecisionTheMessageShowsTheOutcomeWithNewBlockIdsAndNoButtons() {
    ApprovalNotice n = NoticeFixtures.dev();
    ObjectNode msg = SlackBlocks.request(n, verdict(n, false), "ins_test0000001", "kq3", UTC,
        "Approved by you from Slack · executed in 38 ms");
    assertThat(actionIds(msg)).isEmpty();
    assertThat(msg.toString()).doesNotContain("\"actions\"").contains("act.ap_3kq7m2x9vt4p.kq3");
    List<String> contexts = new ArrayList<>();
    msg.path("blocks").forEach(b -> {
      if ("context".equals(b.path("type").asString())) {
        contexts.add(b.at("/elements/0/text").asString());
      }
    });
    assertThat(contexts).containsExactly("Rule `dev.sql.write-approval`",
        "Approved by you from Slack · executed in 38 ms");
  }

  @Test
  void aDigestOffersOnlyOpenInApp() {
    ObjectNode msg = SlackBlocks.digest(12, "ins_test0000001", "1");
    assertThat(msg.path("text").asString()).isEqualTo("12 requests are waiting");
    assertThat(actionIds(msg)).containsExactly("gw_open");
    assertThat(msg.toString()).contains("v1|ins_test0000001|digest|-").doesNotContain("gw_approve");
    assertThat(SlackBlocks.digest(0, "ins_test0000001", "2").path("text").asString())
        .isEqualTo("No requests are waiting");
  }

  @Test
  void slackLimitsHoldAndMrkdwnIsEscaped() {
    ApprovalNotice n = NoticeFixtures.notice(ai.msrv.gaterook.connector.api.Env.DEV, "t".repeat(140), "<!channel>&x",
        NoticeFixtures.action("sql", "sql", NoticeFixtures.SQL));
    ObjectNode msg = SlackBlocks.request(n, verdict(n, true), "ins_test0000001", "1", UTC, null);
    assertThat(msg.at("/blocks/0/text/text").asString().length()).isLessThanOrEqualTo(150);
    assertThat(msg.at("/blocks/1/fields/0/text").asString()).isEqualTo("*Connection*\n`&lt;!channel&gt;&amp;x` · dev");
    msg.at("/blocks").forEach(b -> b.path("elements").forEach(e -> {
      if ("button".equals(e.path("type").asString())) {
        assertThat(e.at("/text/text").asString().length()).isLessThanOrEqualTo(75);
        assertThat(e.path("value").asString().length()).isLessThanOrEqualTo(2_000);
        if (e.has("confirm")) {
          assertThat(e.at("/confirm/title/text").asString().length()).isLessThanOrEqualTo(100);
          assertThat(e.at("/confirm/text/text").asString().length()).isLessThanOrEqualTo(300);
        }
      }
    }));
  }

  @Test
  void theLinkMessageCarriesTheCodeAndTheNonce() {
    ObjectNode msg = SlackBlocks.linkRequest("ravi-mbp", "4F7-2KQ", "ins_test0000001", "nonce-123");
    assertThat(msg.path("text").asString()).isEqualTo("Link MCP Gateway on ravi-mbp to this Slack account? Code "
        + "4F7-2KQ");
    assertThat(actionIds(msg)).containsExactly("gw_link", "gw_link_deny");
    assertThat(msg.toString()).contains("v1|ins_test0000001|link|nonce-123");
  }

  private static List<String> actionIds(JsonNode msg) {
    List<String> out = new ArrayList<>();
    msg.path("blocks").forEach(b -> {
      if ("actions".equals(b.path("type").asString())) {
        b.path("elements").forEach(e -> out.add(e.path("action_id").asString()));
      }
    });
    return out;
  }

  private static String context(JsonNode msg) {
    for (JsonNode b : msg.path("blocks")) {
      if ("context".equals(b.path("type").asString())) {
        return b.at("/elements/0/text").asString();
      }
    }
    return null;
  }
}
