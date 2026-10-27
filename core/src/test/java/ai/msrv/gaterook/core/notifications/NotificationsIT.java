package ai.msrv.gaterook.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.core.admin.AdminMvc;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshotService;
import ai.msrv.gaterook.core.notifications.internal.Notices;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.Ids;
import ai.msrv.gaterook.core.platform.Ingress;
import ai.msrv.gaterook.testfixtures.FakeConnectors;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Approval notifications on a whole core (ARCHITECTURE.md §7.4, step K2) against a fake Slack — Web API and Socket
 * Mode on MockWebServer ({@code webSocketUpgrade}) — and a fake Bot API: connecting both through Settings ›
 * Notifications, linking Ravi's accounts, a dev request in every channel decided once from Slack, prod without
 * Approve, Telegram's two steps, forged clicks that change nothing, the desktop event contract, Socket Mode's refresh
 * and stop. In the end every answer, event and log line is scanned: no token and no agent's note leaves the core
 * (§9: "the agent-note marker never leaves the core").
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationsIT {

  /** What the agent wrote as its note: shown in the app as unverified, never sent to a channel. */
  private static final String NOTE = "agent-note-" + UUID.randomUUID().toString().substring(0, 12);
  private static final String SQL = "UPDATE orders SET status = 'cancelled' WHERE id = 48213;";

  private static NotifyWorld w;
  private static ApprovalService approvals;
  /** This installation's id, as its buttons carry it. */
  private static String instance;
  /** The dev request of the first decision test, whose Slack message later tests click again. */
  private static String devHandle;

  @BeforeAll
  static void start() throws Exception {
    // Many requests within a minute here: the flood rule has its own test (NotificationFloodIT).
    w = NotifyWorld.start("notifications", "--gateway.notifications.flood-per-minute=1000");
    approvals = w.core.bean(ApprovalService.class);
  }

  @AfterAll
  static void stop() throws Exception {
    if (w != null) {
      w.close();
    }
  }

  private static JsonNode overview() {
    return w.api.ok("/api/notifications");
  }

  private static JsonNode channel(String kind) {
    for (JsonNode c : overview().path("channels")) {
      if (kind.equals(c.path("kind").asString())) {
        return c;
      }
    }
    throw new AssertionError("No channel " + kind);
  }

  private static void awaitState(String kind, String state) {
    TestCore.await(() -> state.equals(channel(kind).path("state").asString()), kind + " " + state);
  }

  // ── Slack: connect and link ─────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(1)
  void slackTokensThatDontWorkAreRefusedAndNothingIsSaved() {
    JsonNode bad = w.api.expect(422, HttpMethod.PUT, "/api/notifications/slack", """
        {"botToken":"%s","appToken":"%s","deliverTo":"dm","version":0}"""
        .formatted(FakeSlack.BOT + "-invalid", FakeSlack.APP));
    assertThat(bad.path("title").asString()).isEqualTo("The test failed");
    assertThat(bad.path("test").path("ok").asBoolean()).isFalse();
    assertThat(bad.path("test").path("error").path("code").asString()).isEqualTo("invalid_auth");
    assertThat(bad.path("test").path("checks").get(0).path("name").asString()).isEqualTo("auth.test");
    // A token in the wrong shape never reaches Slack.
    int before = w.slack.calls.size();
    JsonNode shape = w.api.expect(422, HttpMethod.PUT, "/api/notifications/slack", """
        {"botToken":"xoxp-user-token","appToken":"%s","version":0}""".formatted(FakeSlack.APP));
    assertThat(shape.toString()).contains("/botToken").doesNotContain("xoxp-user-token");
    assertThat(w.slack.calls).hasSize(before);
    JsonNode slack = channel("slack");
    assertThat(slack.path("state").asString()).isEqualTo("off");
    assertThat(slack.path("version").asLong()).isZero();
    assertThat(slack.path("tokens")).isEmpty();
    // Desktop notifications are on from the start; a wider burst keeps this test's requests one by one.
    JsonNode desktop = w.api.expect(200, HttpMethod.PUT, "/api/notifications/desktop", "{\"maxBurst\":10}");
    assertThat(desktop.path("enabled").asBoolean()).isTrue();
    assertThat(desktop.path("config").path("maxBurst").asInt()).isEqualTo(10);
    assertThat(w.api.expect(422, HttpMethod.PUT, "/api/notifications/desktop", "{\"maxBurst\":11}").toString())
        .contains("/maxBurst");
    assertThat(w.api.send(HttpMethod.PUT, "/api/notifications/fax", "{}").status()).isEqualTo(404);
  }

  @Test
  @Order(2)
  void slackConnectsThroughSocketModeAfterItsTest() {
    JsonNode saved = w.api.expect(200, HttpMethod.PUT, "/api/notifications/slack", """
        {"botToken":"%s","appToken":"%s","deliverTo":"dm","version":0,"reason":"approvals on the phone"}"""
        .formatted(FakeSlack.BOT, FakeSlack.APP));
    assertThat(saved.path("enabled").asBoolean()).isTrue();
    assertThat(saved.path("config").path("workspace").path("teamId").asString()).isEqualTo(FakeSlack.TEAM);
    assertThat(saved.path("config").path("workspace").path("appId").asString()).isEqualTo(FakeSlack.APP_ID);
    assertThat(saved.path("config").path("workspace").path("team").asString()).isEqualTo("acme");
    assertThat(saved.path("config").has("botToken")).isFalse();
    assertThat(saved.path("tokens").findValuesAsString("state")).containsExactly("stored", "stored");
    assertThat(saved.toString()).doesNotContain(FakeSlack.BOT).doesNotContain(FakeSlack.APP);
    awaitState("slack", "connected");
    assertThat(channel("slack").path("stateDetail").asString()).isEqualTo("Connected · workspace acme");
    // The test opened one connection until hello, the running channel its own.
    assertThat(w.slack.calls("apps.connections.open")).hasSizeGreaterThanOrEqualTo(2)
        .allSatisfy(c -> assertThat(c.token()).isEqualTo(FakeSlack.APP));
    assertThat(w.slack.calls("auth.test").getLast().token()).isEqualTo(FakeSlack.BOT);
    w.awaitEvent("notify.state", e -> "slack".equals(e.path("channel").asString())
        && "connected".equals(e.path("state").asString()), "notify.state connected");
  }

  @Test
  @Order(3)
  void aSlackAccountIsLinkedOnlyByItsOwnClick() {
    assertThat(w.api.expect(422, HttpMethod.POST, "/api/notifications/slack/links",
        "{\"email\":\"nobody@acme.test\"}").path("detail").asString()).contains("No Slack account");
    JsonNode started = w.api.expect(201, HttpMethod.POST, "/api/notifications/slack/links",
        "{\"email\":\"ravi@acme.test\"}");
    assertThat(started.path("state").asString()).isEqualTo("pending");
    assertThat(started.path("display").asString()).isEqualTo("Ravi T. (@ravi)");
    String code = started.path("code").asString();
    assertThat(code).matches("[A-Z2-9]{3}-[A-Z2-9]{3}");

    FakeSlack.Call dm = w.slack.calls("chat.postMessage").stream()
        .filter(c -> FakeSlack.RAVI_DM.equals(c.args().path("channel").asString())).findFirst().orElseThrow();
    assertThat(dm.args().path("text").asString()).endsWith("Code " + code);
    String value = NotifyWorld.buttonValue(dm.args(), "gw_link");
    String[] parts = value.split("\\|");
    instance = parts[1];
    assertThat(parts[2]).isEqualTo("link");

    // Someone else clicking Ravi's link message links nothing.
    w.slackClick("gw_link", value, "U0EVE000001", dm);
    w.awaitEphemeral("U0EVE000001", "This link request is for another Slack account.");
    assertThat(overview().path("links").findValuesAsString("state")).containsExactly("pending");

    w.slackClick("gw_link", value, FakeSlack.RAVI, dm);
    TestCore.await(() -> overview().path("links").findValuesAsString("state").equals(List.of("linked")),
        "Ravi's Slack account linked");
    JsonNode link = overview().path("links").get(0);
    assertThat(link.path("channel").asString()).isEqualTo("slack");
    assertThat(link.path("display").asString()).isEqualTo("Ravi T. (@ravi)");
    TestCore.await(() -> w.slackUpdates(dm.answer().path("ts").asString()).stream()
        .anyMatch(u -> u.args().toString().contains("Linked to GateRook on")), "the link message answered");
    w.awaitEvent("notify.link.updated", e -> "linked".equals(e.path("state").asString()), "notify.link.updated");
    // Every envelope was acknowledged at once.
    TestCore.await(() -> w.slack.acks.stream().anyMatch(a -> a.contains("\"envelope_id\"")), "acks");

    // The same click again: the code is spent.
    w.slackClick("gw_link", value, FakeSlack.RAVI, dm);
    w.awaitEphemeral(FakeSlack.RAVI, "This link request expired or was already answered.");
    assertThat(overview().path("links")).hasSize(1);
  }

  // ── Telegram: connect and pair ──────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(4)
  void telegramRemovesAWebhookOnlyWithTheirYes() {
    w.telegram.webhook = "https://hooks.example.test/bot" + FakeTelegram.TOKEN + "/updates";
    JsonNode asked = w.api.expect(422, HttpMethod.PUT, "/api/notifications/telegram", """
        {"botToken":"%s","version":0}""".formatted(FakeTelegram.TOKEN));
    assertThat(asked.path("confirm").asString()).isEqualTo("remove-webhook");
    assertThat(asked.path("test").path("needsConfirmation").path("kind").asString()).isEqualTo("remove-webhook");
    assertThat(asked.toString()).doesNotContain(FakeTelegram.TOKEN).contains("hooks.example.test");
    assertThat(w.telegram.calls("deleteWebhook")).isEmpty();
    assertThat(channel("telegram").path("version").asLong()).isZero();

    JsonNode saved = w.api.expect(200, HttpMethod.PUT, "/api/notifications/telegram", """
        {"botToken":"%s","version":0,"confirm":"remove-webhook"}""".formatted(FakeTelegram.TOKEN));
    assertThat(w.telegram.calls("deleteWebhook")).hasSize(1);
    assertThat(saved.path("config").path("bot").path("username").asString()).isEqualTo("mcpgw_k2_bot");
    assertThat(saved.path("config").path("protectContent").asBoolean()).isTrue();
    assertThat(saved.toString()).doesNotContain(FakeTelegram.TOKEN);
    awaitState("telegram", "connected");
  }

  @Test
  @Order(5)
  void aTelegramChatIsLinkedByTheCodeAndTheAppsConfirmation() {
    JsonNode pairing = w.api.expect(201, HttpMethod.POST, "/api/notifications/telegram/pairing", null);
    String code = pairing.path("code").asString();
    assertThat(pairing.path("link").asString()).isEqualTo("https://t.me/mcpgw_k2_bot?start=" + code);
    String id = pairing.path("id").asString();
    assertThat(w.api.send(HttpMethod.POST, "/api/notifications/telegram/pairing/" + id + "/confirm", null).status())
        .as("nobody used the code yet").isEqualTo(409);

    w.telegram.message(FakeTelegram.RAVI, "/start not-the-code");
    TestCore.await(() -> w.telegram.calls("sendMessage").stream().anyMatch(c -> c.args().path("text").asString()
        .startsWith("This code is invalid or expired")), "the refusal of a wrong code");
    w.telegram.message(FakeTelegram.RAVI, "/start " + code);
    TestCore.await(() -> overview().path("links").findValuesAsString("state").contains("presented"),
        "the code presented");
    TestCore.await(() -> w.telegram.calls("sendMessage").stream().anyMatch(c -> c.args().path("text").asString()
        .startsWith("Code accepted. Confirm this account")), "the chat told to confirm in the app");

    JsonNode linked = w.api.expect(200, HttpMethod.POST, "/api/notifications/telegram/pairing/" + id + "/confirm",
        "{\"reason\":\"my phone\"}");
    assertThat(linked.path("state").asString()).isEqualTo("linked");
    assertThat(linked.path("display").asString()).isEqualTo("@ravi (Ravi T.)");
    TestCore.await(() -> w.telegram.calls("sendMessage").stream().anyMatch(c -> c.args().path("text").asString()
        .startsWith("Linked to GateRook on")), "the chat told it is linked");
    // The code works once.
    assertThat(w.api.send(HttpMethod.POST, "/api/notifications/telegram/pairing/" + id + "/confirm", null).status())
        .isEqualTo(404);
  }

  @Test
  @Order(6)
  void aCodeWhoseAccountWasChangedInTheDatabaseLinksNoOne() {
    JsonNode pairing = w.api.expect(201, HttpMethod.POST, "/api/notifications/telegram/pairing", null);
    String id = pairing.path("id").asString();
    w.telegram.message(FakeTelegram.RAVI, "/start " + pairing.path("code").asString());
    TestCore.await(() -> overview().path("links").findValues("id").stream().anyMatch(n -> id.equals(n.asString()))
        && overview().path("links").toString().contains("presented"), "the code presented");
    // Someone with access to gateway.db swaps the account the person is about to confirm as "@ravi (Ravi T.)".
    String candidate = w.core.jdbc().queryForObject("select candidate from notify_pairing where code_hash = ?",
        String.class, id);
    w.core.jdbc().update("update notify_pairing set candidate = ? where code_hash = ?",
        candidate.replace(Long.toString(FakeTelegram.RAVI), "999001"), id);

    assertThat(w.api.send(HttpMethod.POST, "/api/notifications/telegram/pairing/" + id + "/confirm", null).status())
        .isEqualTo(404);
    assertThat(w.core.jdbc().queryForObject("select count(*) from notify_link where user_id = '999001'",
        Integer.class)).isZero();
    assertThat(overview().path("links").findValues("id")).noneMatch(n -> id.equals(n.asString()));
  }

  // ── A dev request ───────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(10)
  void aDevRequestReachesEveryChannelAndIsApprovedOnceFromSlack() throws Exception {
    FakeConnectors.EXECUTIONS.clear();
    String id = w.dev.execute("dev-db", SQL);
    devHandle = id;

    FakeSlack.Call post = w.awaitSlackPost(id);
    assertThat(post.args().path("channel").asString()).isEqualTo(FakeSlack.RAVI_DM);
    assertThat(post.args().path("text").asString()).isEqualTo("Approval needed: sql_execute on dev-db");
    assertThat(post.args().toString()).contains(SQL).contains("rich_text_preformatted");
    assertThat(NotifyWorld.actionIds(post.args())).containsExactly("gw_approve", "gw_deny", "gw_open");
    String value = NotifyWorld.buttonValue(post.args(), "gw_approve");
    String digest = value.split("\\|")[3];
    assertThat(value).isEqualTo("v1|" + instance + "|" + id + "|" + digest);
    assertThat(approvals.find(id).orElseThrow().digest()).startsWith(digest);

    FakeTelegram.Call tg = w.awaitTelegramMessage(id);
    assertThat(tg.args().path("chat_id").asLong()).isEqualTo(FakeTelegram.RAVI);
    assertThat(tg.args().path("text").asString()).startsWith("Approval needed\nsql_execute on dev-db · dev")
        .contains(SQL);
    assertThat(tg.args().path("protect_content").asBoolean()).isTrue();
    assertThat(tg.args().has("parse_mode")).isFalse();
    assertThat(tg.args().path("entities").findValuesAsString("type")).contains("bold", "pre", "date_time");
    assertThat(NotifyWorld.callbackData(tg.args().path("reply_markup")))
        .containsExactly("a:" + id + ":" + digest, "d:" + id, "o:" + id);

    JsonNode desktop = w.awaitEvent("notify.desktop", e -> id.equals(e.path("id").asString()), "notify.desktop");
    assertThat(desktop.path("kind").asString()).isEqualTo("request");
    assertThat(desktop.path("title").asString()).isEqualTo("Approval needed · sql_execute on dev-db");
    assertThat(desktop.path("body").asString()).isEqualTo(SQL);
    assertThat(desktop.path("route").asString()).isEqualTo("/approvals/" + id);
    assertThat(desktop.path("actions").findValuesAsString("id")).containsExactly("review", "deny");
    assertThat(desktop.path("silent").asBoolean()).isTrue();

    TestCore.await(() -> notified(id).size() == 3, "three notified entries");
    assertThat(notified(id)).extracting(n -> n.path("channel").asString() + ":" + n.path("state").asString())
        .containsExactlyInAnyOrder("desktop:sent", "slack:sent", "telegram:sent");

    // Clicks that must change nothing.
    w.slackClick("gw_approve", value, "U0EVE000001", post);
    w.awaitEphemeral("U0EVE000001", "Only the linked approver can act on this request.");
    w.slack.envelope("interactive", FakeSlack.click("gw_approve", value, FakeSlack.RAVI, "T0OTHER0001",
        FakeSlack.APP_ID, FakeSlack.RAVI_DM, post.answer().path("ts").asString()));
    w.slack.envelope("interactive", FakeSlack.click("gw_approve", value, FakeSlack.RAVI, FakeSlack.TEAM,
        "A0SOMEOTHER", FakeSlack.RAVI_DM, post.answer().path("ts").asString()));
    w.slackClick("gw_approve", "v1|ins_someoneelse1|" + id + "|" + digest, FakeSlack.RAVI, post);
    w.awaitEphemeral(FakeSlack.RAVI, "This request belongs to another GateRook.");
    w.slack.envelope("interactive", FakeSlack.click("gw_approve", value, FakeSlack.RAVI, FakeSlack.TEAM,
        FakeSlack.APP_ID, FakeSlack.RAVI_DM, "1792672452.999000"));
    w.awaitEphemeral(FakeSlack.RAVI, "Only the linked approver can act on this request.");
    w.slackClick("gw_approve", "v1|" + instance + "|" + id + "|000000000000", FakeSlack.RAVI, post);
    w.awaitEphemeral(FakeSlack.RAVI, "This isn't the action you saw.");
    w.slackClick("gw_open", value, FakeSlack.RAVI, post);
    w.awaitEphemeral(FakeSlack.RAVI, "Opened in GateRook on");
    w.awaitEvent("ui.open", e -> ("/approvals/" + id).equals(e.path("path").asString()), "ui.open");
    Thread.sleep(300);
    assertThat(approvals.find(id).orElseThrow().status()).isEqualTo("pending");
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();

    // Ravi approves from his DM: once.
    w.slackClick("gw_approve", value, FakeSlack.RAVI, post);
    TestCore.await(() -> "completed".equals(approvals.find(id).orElseThrow().status()), "approved and executed");
    ApprovalView done = approvals.find(id).orElseThrow();
    assertThat(done.decidedVia()).isEqualTo("slack");
    assertThat(w.column(id, "decided_external_id")).isEqualTo(FakeSlack.RAVI);
    assertThat(FakeConnectors.EXECUTIONS).singleElement().satisfies(e -> {
      assertThat(e.approvalId()).isEqualTo(id);
      assertThat(e.sql()).isEqualTo(SQL);
    });
    assertThat(w.dev.status(id).path("status").asString()).isEqualTo("completed");
    String ts = post.answer().path("ts").asString();
    TestCore.await(() -> w.slackUpdates(ts).stream().anyMatch(u -> u.args().toString().contains(
        "Approved by you from Slack · executed")), "the Slack message shows the outcome");
    JsonNode updated = w.slackUpdates(ts).getLast().args();
    assertThat(NotifyWorld.actionIds(updated)).isEmpty();
    TestCore.await(() -> w.telegram.calls("editMessageText").stream().anyMatch(c -> c.args().path("message_id")
        .asLong() == NotifyWorld.messageId(tg) && c.args().path("text").asString().contains(
        "Approved by you from Slack")), "the Telegram message shows the outcome");
    assertThat(w.telegram.calls("editMessageText").getLast().args().path("reply_markup").path("inline_keyboard"))
        .isEmpty();

    // A second click (a retry, a second device) learns the outcome and runs nothing.
    w.slackClick("gw_approve", value, FakeSlack.RAVI, post);
    w.awaitEphemeral(FakeSlack.RAVI, "Already approved by you from Slack.");
    assertThat(FakeConnectors.EXECUTIONS).hasSize(1);
  }

  // ── Prod ────────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(11)
  void prodNeverOffersApproveAndAForgedApproveRunsNothing() {
    FakeConnectors.EXECUTIONS.clear();
    String id = w.prod.execute("prod-db", "DELETE FROM sessions WHERE expires_at < now();");
    String digest = approvals.find(id).orElseThrow().digest().substring(0, 12);

    FakeSlack.Call post = w.awaitSlackPost(id);
    assertThat(post.args().path("text").asString()).isEqualTo("PROD · Approval needed: sql_execute on prod-db");
    assertThat(NotifyWorld.actionIds(post.args())).containsExactly("gw_deny", "gw_open");
    FakeTelegram.Call tg = w.awaitTelegramMessage(id);
    assertThat(tg.args().path("text").asString()).startsWith("PROD · Approval needed\n");
    assertThat(NotifyWorld.callbackData(tg.args().path("reply_markup"))).containsExactly("d:" + id, "o:" + id);
    JsonNode desktop = w.awaitEvent("notify.desktop", e -> id.equals(e.path("id").asString()), "notify.desktop");
    assertThat(desktop.path("prod").asBoolean()).isTrue();
    assertThat(desktop.path("urgency").asString()).isEqualTo("critical");
    assertThat(desktop.path("silent").asBoolean()).as("soundForProd").isFalse();
    assertThat(desktop.path("title").asString()).startsWith("PROD · ");

    // An Approve the message never offered, forged with the right digest: refused in both channels.
    w.slackClick("gw_approve", "v1|" + instance + "|" + id + "|" + digest, FakeSlack.RAVI, post);
    w.awaitEphemeral(FakeSlack.RAVI, "Approve isn't available here.");
    long message = NotifyWorld.messageId(tg);
    assertThat(w.telegramClick(FakeTelegram.RAVI, FakeTelegram.RAVI, message, "a:" + id + ":" + digest)
        .path("text").asString()).startsWith("Approve isn't available here.");
    assertThat(w.telegramClick(FakeTelegram.RAVI, FakeTelegram.RAVI, message, "A:" + id + ":" + digest
        + ":n7Kq9x2A").path("text").asString()).isEqualTo("This confirmation expired. Tap Approve again.");
    assertThat(approvals.find(id).orElseThrow().status()).isEqualTo("pending");
    // Below the channels too: even with a comment, a decision coming from Slack or Telegram never approves prod.
    for (String via : List.of("slack", "telegram")) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.decide(id, ApprovalService.Decision.APPROVE,
          via, "looks fine", digest, "U0RAVI0001")).as(via)
          .isInstanceOfSatisfying(ai.msrv.gaterook.core.platform.ProblemException.class,
              e -> assertThat(e.getMessage()).contains("approved in GateRook only"));
    }
    assertThat(approvals.find(id).orElseThrow().status()).isEqualTo("pending");

    // Deny is always safe: Ravi denies from Telegram.
    assertThat(w.telegramClick(FakeTelegram.RAVI, FakeTelegram.RAVI, message, "d:" + id).path("text").asString())
        .isEqualTo("Denied: nothing ran.");
    ApprovalView denied = approvals.find(id).orElseThrow();
    assertThat(denied.status()).isEqualTo("rejected");
    assertThat(denied.decidedVia()).isEqualTo("telegram");
    assertThat(w.column(id, "decided_external_id")).isEqualTo(Long.toString(FakeTelegram.RAVI));
    assertThat(FakeConnectors.EXECUTIONS).isEmpty();
    TestCore.await(() -> w.slackUpdates(post.answer().path("ts").asString()).stream().anyMatch(u -> u.args()
        .toString().contains("Denied by you from Telegram")), "the Slack message shows the denial");
  }

  // ── Telegram's two steps ────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(12)
  void telegramApprovesInTwoStepsAndOnlyFromTheLinkedChat() {
    FakeConnectors.EXECUTIONS.clear();
    String sql = "UPDATE orders SET status = 'shipped' WHERE id = 48214;";
    String id = w.dev.execute("dev-db", sql);
    FakeTelegram.Call tg = w.awaitTelegramMessage(id);
    long message = NotifyWorld.messageId(tg);
    String approve = NotifyWorld.callbackData(tg.args().path("reply_markup")).getFirst();
    assertThat(approve).startsWith("a:" + id + ":");
    String digest = approve.substring(approve.lastIndexOf(':') + 1);

    JsonNode stranger = w.telegramClick(999_001L, 999_001L, message, approve);
    assertThat(stranger.path("text").asString()).isEqualTo("This bot only accepts decisions from its linked account.");
    assertThat(stranger.path("show_alert").asBoolean()).isTrue();
    JsonNode otherMessage = w.telegramClick(FakeTelegram.RAVI, FakeTelegram.RAVI, message + 1_000, approve);
    assertThat(otherMessage.path("text").asString()).isEqualTo("This message isn't one GateRook sent for this "
        + "request.");
    assertThat(otherMessage.path("show_alert").asBoolean()).isTrue();

    JsonNode first = w.telegramClick(FakeTelegram.RAVI, FakeTelegram.RAVI, message, approve);
    assertThat(first.path("text").asString()).isEqualTo("Tap Confirm approve to run it.");
    assertThat(approvals.find(id).orElseThrow().status()).as("the first tap decides nothing").isEqualTo("pending");
    JsonNode confirm = w.telegram.calls("editMessageReplyMarkup").stream()
        .filter(c -> c.args().path("message_id").asLong() == message).toList().getLast().args();
    String confirmData = NotifyWorld.callbackData(confirm.path("reply_markup")).getFirst();
    assertThat(confirmData).startsWith("A:" + id + ":" + digest + ":");
    assertThat(confirmData.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);

    assertThat(w.telegramClick(FakeTelegram.RAVI, FakeTelegram.RAVI, message, "A:" + id + ":" + digest
        + ":wrongNon").path("text").asString()).isEqualTo("This confirmation expired. Tap Approve again.");
    assertThat(approvals.find(id).orElseThrow().status()).isEqualTo("pending");
    // The confirmation of another chat's message is no confirmation either.
    assertThat(w.telegramClick(999_001L, 999_001L, message, confirmData).path("show_alert").asBoolean()).isTrue();

    assertThat(w.telegramClick(FakeTelegram.RAVI, FakeTelegram.RAVI, message, confirmData).path("text")
        .asString()).isEqualTo("Approved: GateRook runs it now.");
    TestCore.await(() -> "completed".equals(approvals.find(id).orElseThrow().status()), "executed");
    assertThat(approvals.find(id).orElseThrow().decidedVia()).isEqualTo("telegram");
    assertThat(w.column(id, "decided_external_id")).isEqualTo(Long.toString(FakeTelegram.RAVI));
    // The confirmation is spent: tapping it again runs nothing.
    w.telegramClick(FakeTelegram.RAVI, FakeTelegram.RAVI, message, confirmData);
    assertThat(FakeConnectors.EXECUTIONS).singleElement().satisfies(e -> assertThat(e.sql()).isEqualTo(sql));
  }

  // ── The agent's note ────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(13)
  void theAgentsNoteIsShownInTheAppAndSentNowhere() {
    String id = requestWithNote("UPDATE orders SET status = 'held' WHERE id = 48215;");
    FakeSlack.Call post = w.awaitSlackPost(id);
    FakeTelegram.Call tg = w.awaitTelegramMessage(id);
    JsonNode desktop = w.awaitEvent("notify.desktop", e -> id.equals(e.path("id").asString()), "notify.desktop");
    assertThat(post.args().toString()).doesNotContain(NOTE);
    assertThat(tg.args().toString()).doesNotContain(NOTE);
    assertThat(desktop.toString()).doesNotContain(NOTE);
    // The approver sees it in the app, marked unverified.
    assertThat(w.api.ok("/api/approvals/" + id).path("agentNote").asString()).isEqualTo(NOTE);

    // A tool whose action is its arguments (an MCP server's, a plugin's): the note is dropped from what a channel
    // shows, and so the view is partial and offers no Approve.
    ApprovalView a = approvals.find(id).orElseThrow();
    ObjectNode call = (ObjectNode) a.call().deepCopy();
    ((ObjectNode) call.path("arguments")).put("note", NOTE);
    ApprovalView external = new ApprovalView(a.id(), a.callId(), a.endpointId(), a.endpointSlug(), a.connectionId(),
        a.connectionName(), "create_issue", "mcp:github:create_issue", a.env(), a.principalKind(), a.principalId(),
        a.clientLabel(), a.status(), a.digest(), null, a.actionSha256(), a.agentNote(), a.ruleId(),
        a.commentPolicy(), a.comment(), a.decidedVia(), a.decidedAt(), a.createdAt(), a.expiresAt(), a.executedAt(),
        a.result(), a.resultExpiresAt(), a.error(), a.traceContext(), call);
    ApprovalNotice n = w.core.bean(Notices.class).of(external);
    assertThat(n.action().partial()).isTrue();
    assertThat(n.action().text()).doesNotContain(NOTE).contains("held");
    assertThat(Eligibility.evaluate(new Eligibility.Facts(Eligibility.Channel.SLACK, n, true, false, true, false,
        true)).approve()).isFalse();
    // A plugin's own line about the call may repeat the note: a channel shows the core's line instead.
    ApprovalView plugin = new ApprovalView(a.id(), a.callId(), a.endpointId(), a.endpointSlug(), a.connectionId(),
        a.connectionName(), "jira_add_comment", "plugin:jira-service-desk:jira_add_comment", a.env(),
        a.principalKind(), a.principalId(), a.clientLabel(), a.status(), a.digest(), ActionPreview.of(
            ActionPreview.Kind.JSON, "json", "{}", "dev-db", "Adds the comment: " + NOTE), a.actionSha256(),
        a.agentNote(), a.ruleId(), a.commentPolicy(), a.comment(), a.decidedVia(), a.decidedAt(), a.createdAt(),
        a.expiresAt(), a.executedAt(), a.result(), a.resultExpiresAt(), a.error(), a.traceContext(), call);
    ApprovalNotice p = w.core.bean(Notices.class).of(plugin);
    assertThat(p.summary()).isEqualTo("jira_add_comment on dev-db").doesNotContain(NOTE);
    // A built-in's line shows its invisible characters as escapes.
    ApprovalView hidden = new ApprovalView(a.id(), a.callId(), a.endpointId(), a.endpointSlug(), a.connectionId(),
        a.connectionName(), a.tool(), a.toolKey(), a.env(), a.principalKind(), a.principalId(), a.clientLabel(),
        a.status(), a.digest(), ActionPreview.of(ActionPreview.Kind.SQL, "sql", "UPDATE x SET y = 1", "dev-db",
            "1 row in orders‮"), a.actionSha256(), a.agentNote(), a.ruleId(), a.commentPolicy(), a.comment(),
        a.decidedVia(), a.decidedAt(), a.createdAt(), a.expiresAt(), a.executedAt(), a.result(), a.resultExpiresAt(),
        a.error(), a.traceContext(), a.call());
    assertThat(w.core.bean(Notices.class).of(hidden).summary()).isEqualTo("1 row in orders‹U+202E›");

    approvals.decide(id, ApprovalService.Decision.DENY, "app", null, null);
    TestCore.await(() -> w.slackUpdates(post.answer().path("ts").asString()).stream().anyMatch(u -> u.args()
        .toString().contains("Denied by you in GateRook")), "the denial shown");
  }

  /**
   * A request as the call path makes it for {@code sql_execute} when the agent wrote a note: the fake's schema has no
   * {@code note}, so the request is opened directly, with the note in the arguments and as the agent's note.
   */
  private static String requestWithNote(String sql) {
    EndpointSnapshot s = w.core.bean(EndpointSnapshotService.class).bySlug("dev").orElseThrow();
    EndpointSnapshot.Pair pair = s.pairs().stream().filter(p -> p.tool().name().equals("sql_execute")
        && p.connectionName().equals("dev-db")).findFirst().orElseThrow();
    Caller caller = new Caller(Caller.Kind.LOCAL_TOKEN, w.devTokenId, "codex-laptop", "codex", Ingress.LOCAL,
        "127.0.0.1");
    ObjectNode arguments = JsonNodeFactory.instance.objectNode().put("sql", sql).put("note", NOTE);
    String callId = Ids.next("tc_");
    ToolCall call = new ToolCall(callId, "sql_execute", "dev-db", arguments, null);
    ActionPreview preview = ActionPreview.of(ActionPreview.Kind.SQL, "sql", sql, "dev-db", "1 row in orders");
    ObjectNode args = arguments.deepCopy();
    args.put("connection", "dev-db");
    ObjectNode facets = JsonNodeFactory.instance.objectNode().put("nature", "mutation");
    AuditWriter.Call audit = new AuditWriter.Call(callId, "dev", "sql_execute", pair.toolKey(), "dev-db",
        pair.config().env(), caller, "codex", null, "2025-11-25", "0af7651916cd43dd8448eb211c80319c",
        "b7ad6b7169203331", false, args, CanonicalJson.sha256Hex(args), facets);
    return approvals.requestOrReuse(new ApprovalService.NewRequest(caller, s.id(), "dev", callId, call,
        pair.toolKey(), pair.config(), preview, null, NOTE, "dev.sql.write-approval", Classification.Kind.MUTATION,
        null, audit, AuditWriter.Delivery.HANDLE)).approval().id();
  }

  // ── Desktop ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(20)
  void aDesktopNotificationOnlyDeniesAndTheSwitchTurnsItOff() {
    String id = w.dev.execute("dev-db", "UPDATE orders SET status = 'cancelled' WHERE id = 48216;");
    w.awaitEvent("notify.desktop", e -> id.equals(e.path("id").asString()), "notify.desktop");
    String digest = approvals.find(id).orElseThrow().digest();
    assertThat(w.api.expect(422, HttpMethod.POST, "/api/approvals/" + id + "/decision", """
        {"decision":"approve","via":"desktop-notification","digest":"%s"}""".formatted(digest))
        .path("detail").asString()).contains("can only deny");
    JsonNode denied = w.api.expect(200, HttpMethod.POST, "/api/approvals/" + id + "/decision",
        "{\"decision\":\"deny\",\"via\":\"desktop-notification\"}");
    assertThat(denied.path("status").asString()).isEqualTo("rejected");
    TestCore.await(() -> w.slackUpdates(w.awaitSlackPost(id).answer().path("ts").asString()).stream()
        .anyMatch(u -> u.args().toString().contains("Denied by you from the desktop notification")),
        "the denial shown in Slack");

    // Off: the next request makes no desktop event, the chats still get it.
    long version = channel("desktop").path("version").asLong();
    w.api.expect(200, HttpMethod.PUT, "/api/notifications/desktop", "{\"enabled\":false,\"version\":" + version
        + "}");
    assertThat(channel("desktop").path("state").asString()).isEqualTo("off");
    String quiet = w.dev.execute("dev-db", "UPDATE orders SET status = 'cancelled' WHERE id = 48217;");
    w.awaitSlackPost(quiet);
    TestCore.await(() -> notified(quiet).size() == 2, "the chats notified");
    assertThat(w.events("notify.desktop")).noneMatch(e -> quiet.equals(e.path("id").asString()));
    assertThat(notified(quiet)).extracting(n -> n.path("channel").asString()).doesNotContain("desktop");
    approvals.decide(quiet, ApprovalService.Decision.DENY, "app", null, null);

    // On, with a burst of 1: the second request in 10 s is the summary that replaces itself.
    version = channel("desktop").path("version").asLong();
    w.api.expect(200, HttpMethod.PUT, "/api/notifications/desktop", "{\"enabled\":true,\"maxBurst\":1,\"version\":"
        + version + "}");
    String one = w.dev.execute("dev-db", "UPDATE orders SET status = 'cancelled' WHERE id = 48218;");
    String two = w.dev.execute("dev-db", "UPDATE orders SET status = 'cancelled' WHERE id = 48219;");
    JsonNode summary = w.awaitEvent("notify.desktop", e -> "summary".equals(e.path("kind").asString()),
        "the burst summary");
    assertThat(summary.path("id").asString()).isEqualTo("approvals-summary");
    assertThat(summary.path("route").asString()).isEqualTo("/approvals");
    assertThat(summary.path("actions").findValuesAsString("id")).containsExactly("review");
    assertThat(summary.path("title").asString()).matches("\\d+ approval requests? (is|are) waiting");
    for (String x : List.of(one, two)) {
      approvals.decide(x, ApprovalService.Decision.DENY, "app", null, null);
    }
    version = channel("desktop").path("version").asLong();
    w.api.expect(200, HttpMethod.PUT, "/api/notifications/desktop", "{\"maxBurst\":10,\"version\":" + version + "}");

    // What Electron main saw showing them: Settings shows it, and so does the event.
    assertThat(w.api.send(HttpMethod.POST, "/internal/notifications/desktop", """
        {"event":"failed","detail":"Notifications are off for GateRook in System Settings"}""").status())
        .isEqualTo(204);
    assertThat(channel("desktop").path("state").asString()).isEqualTo("failed");
    assertThat(channel("desktop").path("stateDetail").asString()).isEqualTo("Notifications are off for GateRook "
        + "in System Settings");
    w.awaitEvent("notify.state", e -> "desktop".equals(e.path("channel").asString())
        && "failed".equals(e.path("state").asString()), "notify.state failed");
    w.api.expect(204, HttpMethod.POST, "/internal/notifications/desktop", "{\"event\":\"shown\"}");
    assertThat(channel("desktop").path("state").asString()).isEqualTo("ok");
    assertThat(w.api.send(HttpMethod.POST, "/internal/notifications/desktop", "{\"event\":\"clicked\"}").status())
        .isEqualTo(422);
  }

  // ── Tests, test message, channels ───────────────────────────────────────────────────────────────────────────

  @Test
  @Order(30)
  void testsAndTheTestMessageAnswerInTheConnectionFormsShape() {
    JsonNode slack = w.api.expect(200, HttpMethod.POST, "/api/notifications/slack/test", null);
    assertThat(slack.path("ok").asBoolean()).isTrue();
    assertThat(slack.path("identity").asString()).isEqualTo("workspace acme · bot @mcp-gateway");
    assertThat(slack.path("checks").findValuesAsString("name")).containsExactly("auth.test", "socket-mode");
    JsonNode telegram = w.api.expect(200, HttpMethod.POST, "/api/notifications/telegram/test", null);
    assertThat(telegram.path("ok").asBoolean()).isTrue();
    assertThat(telegram.path("identity").asString()).isEqualTo("@mcpgw_k2_bot");
    assertThat(telegram.path("checks").findValuesAsString("name")).containsExactly("getMe", "webhook");
    assertThat(w.api.send(HttpMethod.POST, "/api/notifications/desktop/test", null).status()).isEqualTo(404);

    JsonNode sent = w.api.expect(200, HttpMethod.POST, "/api/notifications/test", null);
    assertThat(sent.path("channels").findValuesAsString("state")).containsExactly("sent", "sent", "sent");
    w.awaitEvent("notify.desktop", e -> "test".equals(e.path("kind").asString()), "the desktop test");
    FakeSlack.Call test = w.slack.calls("chat.postMessage").stream()
        .filter(c -> NotifyWorld.actionIds(c.args()).contains("gw_test")).findFirst().orElseThrow();
    assertThat(test.args().path("text").asString()).contains("Test · nothing will run");
    w.slackClick("gw_test", NotifyWorld.buttonValue(test.args(), "gw_test"), FakeSlack.RAVI, test);
    w.awaitEphemeral(FakeSlack.RAVI, "Test button works. Nothing ran.");
    assertThat(w.telegram.calls("sendMessage").stream().anyMatch(c -> c.args().path("text").asString()
        .startsWith("Test · nothing will run"))).isTrue();

    JsonNode channels = w.api.ok("/api/notifications/slack/channels");
    assertThat(channels.get(0).path("id").asString()).isEqualTo("C0DEPLOY0001");
    assertThat(channels.get(0).path("name").asString()).isEqualTo("#deploy-approvals");
  }

  // ── Socket Mode's lifecycle ─────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(40)
  void socketModeRefreshesWithoutAGapAndStopsWhenItIsTurnedOff() {
    int opens = w.slack.calls("apps.connections.open").size();
    w.slack.disconnect("refresh_requested");
    TestCore.await(() -> w.slack.calls("apps.connections.open").size() == opens + 1, "a new connection");
    awaitState("slack", "connected");
    // Clicks arrive on the new connection.
    FakeSlack.Call post = w.slackPosts(devHandle).getFirst();
    w.slackClick("gw_open", NotifyWorld.buttonValue(post.args(), "gw_open"), FakeSlack.RAVI, post);
    TestCore.await(() -> w.events("ui.open").size() >= 2, "a click on the new connection");

    w.slack.disconnect("link_disabled");
    awaitState("slack", "stopped");
    assertThat(channel("slack").path("stateDetail").asString()).startsWith("Socket Mode is off");
    int after = w.slack.calls("apps.connections.open").size();
    // Saving Slack again starts it again, with the stored tokens.
    long version = channel("slack").path("version").asLong();
    w.api.expect(200, HttpMethod.PUT, "/api/notifications/slack", "{\"enabled\":true,\"version\":" + version + "}");
    awaitState("slack", "connected");
    assertThat(w.slack.calls("apps.connections.open").size()).isGreaterThan(after);
    // A stale version is a conflict, not a silent overwrite.
    assertThat(w.api.send(HttpMethod.PUT, "/api/notifications/slack", "{\"enabled\":true,\"version\":" + version
        + "}").status()).isEqualTo(409);
  }

  // ── Disconnect ──────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(50)
  void disconnectingTelegramDestroysItsTokenAndUnlinksItsChat() {
    w.api.expect(201, HttpMethod.POST, "/api/notifications/telegram/pairing", null);
    w.api.expect(204, HttpMethod.DELETE, "/api/notifications/telegram", "{\"reason\":\"new phone\"}");
    JsonNode telegram = channel("telegram");
    assertThat(telegram.path("state").asString()).isEqualTo("off");
    assertThat(telegram.path("tokens")).isEmpty();
    assertThat(telegram.path("version").asLong()).isZero();
    assertThat(overview().path("links").findValuesAsString("channel")).containsExactly("slack");
    assertThat(w.api.send(HttpMethod.DELETE, "/api/notifications/telegram", null).status()).isEqualTo(404);
    assertThat(w.api.send(HttpMethod.DELETE, "/api/notifications/desktop", null).status()).isEqualTo(422);
    // A request now goes to Slack and the desktop only.
    String id = w.dev.execute("dev-db", "UPDATE orders SET status = 'cancelled' WHERE id = 48220;");
    TestCore.await(() -> notified(id).size() == 2, "desktop and Slack");
    assertThat(notified(id)).extracting(n -> n.path("channel").asString()).containsExactlyInAnyOrder("desktop",
        "slack");
    approvals.decide(id, ApprovalService.Decision.DENY, "app", null, null);
  }

  // ── A link changed in gateway.db ────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(60)
  void aLinkEditedOutsideTheCoreReceivesNothingAndDecidesNothing() {
    // Someone with access to the database points Ravi's link at their own Slack account.
    w.core.jdbc().update("update notify_link set user_id = 'U0EVE000001', chat_id = 'D0EVE000001' "
        + "where channel = 'slack'");
    TestCore.await(() -> overview().path("links").findValuesAsString("state").contains("changed-outside"),
        "the link shown as changed outside");
    String id = w.dev.execute("dev-db", "UPDATE orders SET status = 'cancelled' WHERE id = 48221;");
    TestCore.await(() -> notified(id).stream().anyMatch(n -> "slack".equals(n.path("channel").asString())),
        "Slack's attempt");
    JsonNode slack = notified(id).stream().filter(n -> "slack".equals(n.path("channel").asString())).findFirst()
        .orElseThrow();
    assertThat(slack.path("state").asString()).isEqualTo("failed");
    assertThat(slack.path("error").asString()).contains("No linked Slack account");
    assertThat(w.slackPosts(id)).isEmpty();
    // A click forged in that account's name, on an old message, decides nothing.
    FakeSlack.Call old = w.slackPosts(devHandle).getFirst();
    String digest = approvals.find(id).orElseThrow().digest().substring(0, 12);
    w.slack.envelope("interactive", FakeSlack.click("gw_approve", "v1|" + instance + "|" + id + "|" + digest,
        "U0EVE000001", FakeSlack.TEAM, FakeSlack.APP_ID, "D0EVE000001", old.answer().path("ts").asString()));
    w.awaitEphemeral("U0EVE000001", "Only the linked approver can act on this request.");
    assertThat(approvals.find(id).orElseThrow().status()).isEqualTo("pending");
    approvals.decide(id, ApprovalService.Decision.DENY, "app", null, null);
  }

  // ── Nothing leaves ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @Order(99)
  void noAnswerEventMessageOrLogLineCarriesATokenOrTheAgentsNote() throws Exception {
    List<String> secrets = List.of(FakeSlack.BOT, FakeSlack.APP, FakeTelegram.TOKEN);
    for (AdminMvc.Answer a : w.api.answers()) {
      if (!a.target().startsWith("/api/notifications") && !a.target().startsWith("/internal/notifications")) {
        continue;
      }
      assertThat(AdminMvc.secretLookingValues(a.json())).as(a.method() + " " + a.target()).isEmpty();
      for (String s : secrets) {
        assertThat(a.body()).as(a.method() + " " + a.target()).doesNotContain(s);
      }
    }
    for (var e : w.events) {
      assertThat(e.data().toString()).as(e.type()).doesNotContain(NOTE);
      secrets.forEach(s -> assertThat(e.data().toString()).as(e.type()).doesNotContain(s));
    }
    for (FakeSlack.Call c : w.slack.calls) {
      assertThat(c.args().toString()).as("Slack " + c.method()).doesNotContain(NOTE);
      secrets.forEach(s -> assertThat(c.args().toString()).as("Slack " + c.method()).doesNotContain(s));
    }
    for (FakeTelegram.Call c : w.telegram.calls) {
      assertThat(c.args().toString()).as("Telegram " + c.method()).doesNotContain(NOTE);
      secrets.forEach(s -> assertThat(c.args().toString()).as("Telegram " + c.method()).doesNotContain(s));
    }
    String log = w.logText();
    assertThat(log).contains("notifications");
    assertThat(log).doesNotContain(NOTE);
    secrets.forEach(s -> assertThat(log).doesNotContain(s));
  }

  private static List<JsonNode> notified(String id) {
    return approvals.timeline(id).stream().filter(e -> "notified".equals(e.kind()))
        .map(ApprovalService.TimelineEntry::detail).toList();
  }
}
