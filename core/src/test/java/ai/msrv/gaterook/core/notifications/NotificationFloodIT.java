package ai.msrv.gaterook.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.testfixtures.TestCore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * Slack posting to a channel (ARCHITECTURE.md §7.4): its test names the audience, more than five new requests a
 * minute become one digest without Approve that counts down as they are decided, the settings survive a restart, and
 * settings edited in {@code gateway.db} stop the channel until they are saved again (V30's row MAC).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationFloodIT {

  private static final String CHANNEL = "C0DEPLOY0001";

  private static NotifyWorld w;

  @BeforeAll
  static void start() throws Exception {
    w = NotifyWorld.start("notify-flood");
  }

  /** The approvals of the core running now (the test restarts it). */
  private static ApprovalService approvals() {
    return w.core.bean(ApprovalService.class);
  }

  @AfterAll
  static void stop() throws Exception {
    if (w != null) {
      w.close();
    }
  }

  private static JsonNode slack() {
    for (JsonNode c : w.api.ok("/api/notifications").path("channels")) {
      if ("slack".equals(c.path("kind").asString())) {
        return c;
      }
    }
    throw new AssertionError("no Slack");
  }

  private static List<JsonNode> notified(String id) {
    return approvals().timeline(id).stream().filter(e -> "notified".equals(e.kind()))
        .map(ApprovalService.TimelineEntry::detail).toList();
  }

  private static JsonNode slackNotified(String id) {
    TestCore.await(() -> notified(id).stream().anyMatch(n -> "slack".equals(n.path("channel").asString())),
        "Slack's entry for " + id);
    return notified(id).stream().filter(n -> "slack".equals(n.path("channel").asString())).findFirst()
        .orElseThrow();
  }

  @Test
  @Order(1)
  void aChannelsTestNamesWhoSeesTheExactAction() {
    JsonNode saved = w.api.expect(200, HttpMethod.PUT, "/api/notifications/slack", """
        {"botToken":"%s","appToken":"%s","deliverTo":"channel","channelId":"%s","version":0}"""
        .formatted(FakeSlack.BOT, FakeSlack.APP, CHANNEL));
    assertThat(saved.path("config").path("channelName").asString()).isEqualTo("#deploy-approvals");
    TestCore.await(() -> "connected".equals(slack().path("state").asString()), "Slack connected");
    JsonNode test = w.api.expect(200, HttpMethod.POST, "/api/notifications/slack/test", null);
    assertThat(test.path("checks").findValuesAsString("name")).containsExactly("auth.test", "socket-mode", "channel");
    assertThat(test.path("hints").findValuesAsString("code")).containsExactly("public-channel", "audience");
    assertThat(test.path("hints").get(1).path("text").asString())
        .isEqualTo("Everyone in the channel (23 members) sees the exact action.");
    // A channel the app isn't in is a warning the person sees before saving.
    w.slack.channelMember = false;
    JsonNode outside = w.api.expect(200, HttpMethod.POST, "/api/notifications/slack/test", null);
    assertThat(outside.path("hints").findValuesAsString("code")).contains("not-in-channel");
    w.slack.channelMember = true;
  }

  @Test
  @Order(2)
  void moreThanFiveRequestsAMinuteBecomeOneDigestWithoutApprove() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      String id = w.dev.execute("dev-db", "UPDATE orders SET status = 'cancelled' WHERE id = " + (50_000 + i) + ";");
      ids.add(id);
      // One at a time, as a person's agent would send them: the digest is posted once and then edited.
      slackNotified(id);
    }
    for (String id : ids.subList(0, 5)) {
      assertThat(slackNotified(id).has("digest")).isFalse();
      FakeSlack.Call post = w.awaitSlackPost(id);
      assertThat(post.args().path("channel").asString()).isEqualTo(CHANNEL);
      assertThat(NotifyWorld.actionIds(post.args())).containsExactly("gw_approve", "gw_deny", "gw_open");
    }
    for (String id : ids.subList(5, 7)) {
      assertThat(slackNotified(id).path("digest").asBoolean()).isTrue();
      assertThat(w.slackPosts(id)).as("no message of its own").isEmpty();
    }
    List<FakeSlack.Call> digests = w.slack.calls("chat.postMessage").stream()
        .filter(c -> c.args().path("text").asString().endsWith("requests are waiting")).toList();
    assertThat(digests).singleElement().satisfies(d -> {
      assertThat(d.args().path("text").asString()).isEqualTo("6 requests are waiting");
      assertThat(NotifyWorld.actionIds(d.args())).containsExactly("gw_open");
      assertThat(NotifyWorld.buttonValue(d.args(), "gw_open")).endsWith("|digest|-");
    });
    String digestTs = digests.getFirst().answer().path("ts").asString();
    TestCore.await(() -> w.slackUpdates(digestTs).stream().anyMatch(u -> "7 requests are waiting".equals(u.args()
        .path("text").asString())), "the digest counts the seventh");

    // As they are decided the digest counts down, to none.
    for (String id : ids) {
      approvals().decide(id, ApprovalService.Decision.DENY, "app", null, null);
    }
    TestCore.await(() -> w.slackUpdates(digestTs).stream().anyMatch(u -> "No requests are waiting".equals(u.args()
        .path("text").asString())), "the digest at zero");
    assertThat(w.slackUpdates(digestTs)).allSatisfy(u -> assertThat(NotifyWorld.actionIds(u.args()))
        .doesNotContain("gw_approve"));
  }

  @Test
  @Order(3)
  void theSettingsSurviveARestartAndAnEditInTheDatabaseStopsSlack() {
    int opens = w.slack.calls("apps.connections.open").size();
    w.reopen();
    // The row still says what the last run saw: wait for this run's own connection.
    TestCore.await(() -> w.slack.calls("apps.connections.open").size() > opens, "a connection after the restart");
    TestCore.await(() -> "connected".equals(slack().path("state").asString()), "Slack connected after the restart");
    assertThat(slack().path("verified").asBoolean()).isTrue();

    // Somebody points the channel elsewhere in gateway.db: the row no longer verifies.
    w.core.jdbc().update("update notification_channel set config = replace(config, ?, ?) where kind = 'slack'",
        CHANNEL, "C0ELSEWHERE1");
    w.reopen();
    TestCore.await(() -> "changed-outside".equals(slack().path("state").asString()), "Slack changed outside");
    assertThat(slack().path("verified").asBoolean()).isFalse();
    int posts = w.slack.calls("chat.postMessage").size();
    String id = w.dev.execute("dev-db", "UPDATE orders SET status = 'cancelled' WHERE id = 50100;");
    TestCore.await(() -> notified(id).stream().anyMatch(n -> "desktop".equals(n.path("channel").asString())),
        "the desktop notified");
    assertThat(notified(id)).extracting(n -> n.path("channel").asString()).doesNotContain("slack");
    assertThat(w.slack.calls("chat.postMessage")).hasSize(posts);
    assertThat(w.slack.calls).noneMatch(c -> c.args().toString().contains("C0ELSEWHERE1"));
    approvals().decide(id, ApprovalService.Decision.DENY, "app", null, null);

    // Saving it again, as the person sees it, signs it again.
    long version = slack().path("version").asLong();
    w.api.expect(200, HttpMethod.PUT, "/api/notifications/slack", "{\"enabled\":true,\"channelId\":\"" + CHANNEL
        + "\",\"version\":" + version + "}");
    TestCore.await(() -> "connected".equals(slack().path("state").asString()), "Slack connected again");
    assertThat(slack().path("verified").asBoolean()).isTrue();
  }
}
