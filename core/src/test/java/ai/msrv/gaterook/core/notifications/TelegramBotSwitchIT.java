package ai.msrv.gaterook.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.testfixtures.TestCore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * Another Telegram bot replaces the first (K2 review, finding 1). Telegram counts updates per bot and numbers the
 * messages of each private chat from the start, while a private chat's id is the user's id whatever the bot: the new
 * bot must start from its own updates (the old offset would drop its {@code /start} and its clicks), and the old bot's
 * messages must never be mistaken for the new bot's ones that carry the same number.
 */
class TelegramBotSwitchIT {

  private static NotifyWorld w;
  private static ApprovalService approvals;

  @BeforeAll
  static void start() throws Exception {
    w = NotifyWorld.start("telegram-switch", "--gateway.notifications.flood-per-minute=1000");
    approvals = w.core.bean(ApprovalService.class);
  }

  @AfterAll
  static void stop() throws Exception {
    if (w != null) {
      w.close();
    }
  }

  private static JsonNode telegram() {
    for (JsonNode c : w.api.ok("/api/notifications").path("channels")) {
      if ("telegram".equals(c.path("kind").asString())) {
        return c;
      }
    }
    throw new AssertionError("No telegram channel");
  }

  /** Ravi links his private chat: the code, {@code /start <code>}, the confirmation in the app. */
  private static void link() {
    JsonNode pairing = w.api.expect(201, HttpMethod.POST, "/api/notifications/telegram/pairing", null);
    w.telegram.message(FakeTelegram.RAVI, "/start " + pairing.path("code").asString());
    TestCore.await(() -> w.api.ok("/api/notifications").path("links").findValuesAsString("state")
        .contains("presented"), "the code presented to " + telegram().at("/config/bot/username").asString());
    JsonNode linked = w.api.expect(200, HttpMethod.POST, "/api/notifications/telegram/pairing/"
        + pairing.path("id").asString() + "/confirm", "{\"reason\":\"my phone\"}");
    assertThat(linked.path("state").asString()).isEqualTo("linked");
  }

  @Test
  void anotherBotStartsFromItsOwnUpdatesAndNeverTouchesTheOldBotsMessages() throws Exception {
    w.api.expect(200, HttpMethod.PUT, "/api/notifications/telegram", """
        {"botToken":"%s","version":0}""".formatted(FakeTelegram.TOKEN));
    link();
    String first = w.dev.execute("dev-db", "UPDATE orders SET status = 'held' WHERE id = 48301;");
    long oldMessage = NotifyWorld.messageId(w.awaitTelegramMessage(first));

    // The new bot's update ids lie far below the offset the old one reached (1001 and up).
    w.telegram.switchBot(FakeTelegram.TOKEN_B, FakeTelegram.BOT_B, "mcpgw_other_bot", 10);
    JsonNode saved = w.api.expect(200, HttpMethod.PUT, "/api/notifications/telegram", """
        {"botToken":"%s","version":%d}""".formatted(FakeTelegram.TOKEN_B, telegram().path("version").asLong()));
    assertThat(saved.at("/config/bot/username").asString()).isEqualTo("mcpgw_other_bot");
    assertThat(w.api.ok("/api/notifications").path("links").findValuesAsString("channel"))
        .as("the old bot's chat is unlinked").doesNotContain("telegram");
    link();

    // A request whose message in the new bot's chat gets the number the old request's message has there.
    w.telegram.nextMessageId(oldMessage);
    String second = w.dev.execute("dev-db", "UPDATE orders SET status = 'held' WHERE id = 48302;");
    assertThat(NotifyWorld.messageId(w.awaitTelegramMessage(second))).isEqualTo(oldMessage);
    // The old request ends: its message is the old bot's, and the new bot's message of that number isn't touched.
    approvals.decide(first, ApprovalService.Decision.DENY, "app", null, null);
    Thread.sleep(1_000);
    assertThat(w.telegram.calls("editMessageText")).as("the new bot edits nothing for the old request")
        .noneMatch(c -> c.args().path("message_id").asLong() == oldMessage);

    // The new message answers its own buttons.
    JsonNode denied = w.telegramClick(FakeTelegram.RAVI, FakeTelegram.RAVI, oldMessage, "d:" + second);
    assertThat(denied.path("text").asString()).isEqualTo("Denied: nothing ran.");
    assertThat(approvals.find(second).orElseThrow().status()).isEqualTo("rejected");
    assertThat(approvals.find(second).orElseThrow().decidedVia()).isEqualTo("telegram");
  }
}
