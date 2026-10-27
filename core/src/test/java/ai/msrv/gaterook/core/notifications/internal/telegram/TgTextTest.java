package ai.msrv.gaterook.core.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.Eligibility;
import ai.msrv.gaterook.core.notifications.NoticeFixtures;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Telegram's plain text + entities (report 13 §3, §8): offsets and lengths in UTF-16 units with Cyrillic and emoji
 * (surrogate pairs), the report's own example, the 4,096 boundary, {@code callback_data} ≤ 64 bytes for a 32-character
 * request id.
 */
class TgTextTest {

  @Test
  void entitiesCountUtf16UnitsWithCyrillicAndEmoji() {
    TgText t = new TgText().entity("bold", "Заявка 🚀").text(" и ").entity("code", "dev-db").pre("SELECT '😀';", "sql");
    JsonNode e = t.entities();
    // The Unicode fixture is 6 letters, a space and a surrogate pair: 9 UTF-16 units, as String.length() counts them.
    assertThat(e.get(0).path("length").asInt()).isEqualTo(9);
    assertThat(e.get(1).path("offset").asInt()).isEqualTo(12);
    assertThat(e.get(1).path("length").asInt()).isEqualTo(6);
    assertThat(e.get(2).path("offset").asInt()).isEqualTo(18);
    assertThat(e.get(2).path("length").asInt()).isEqualTo("SELECT '😀';".length()).isEqualTo(12);
    assertThat(e.get(2).path("language").asString()).isEqualTo("sql");
    String text = t.text();
    for (JsonNode x : e) {
      assertThat(text.substring(x.path("offset").asInt(), x.path("offset").asInt() + x.path("length").asInt()))
          .isNotBlank();
    }
  }

  @Test
  void theDevRequestHasTheReportsEntities() {
    ApprovalNotice n = NoticeFixtures.dev();
    Eligibility.Verdict v = Eligibility.evaluate(new Eligibility.Facts(Eligibility.Channel.TELEGRAM, n, true, false,
        true, false, true));
    TgText t = TelegramMessages.request(n, v, ZoneOffset.ofHours(2), null);
    assertThat(t.text()).isEqualTo("Approval needed\nsql_execute on dev-db · dev · from Codex · endpoint dev\n\n"
        + NoticeFixtures.SQL + "\n\nAuto-deny at 14:34:12 · rule dev.sql.write-approval");
    JsonNode e = t.entities();
    assertThat(e.toString()).isEqualTo("[{\"type\":\"bold\",\"offset\":0,\"length\":15},"
        + "{\"type\":\"code\",\"offset\":16,\"length\":11},{\"type\":\"code\",\"offset\":31,\"length\":6},"
        + "{\"type\":\"pre\",\"offset\":73,\"length\":56,\"language\":\"sql\"},"
        + "{\"type\":\"date_time\",\"offset\":144,\"length\":8,\"unix_time\":1792672452,\"date_time_format\":\"T\"}]");
    ObjectNode keyboard = TelegramMessages.keyboard(n, v);
    assertThat(keyboard.toString()).isEqualTo("{\"inline_keyboard\":[[{\"text\":\"Approve\",\"style\":\"success\","
        + "\"callback_data\":\"a:ap_3kq7m2x9vt4p:9f2c1a7e5d3b\"},{\"text\":\"Deny\",\"style\":\"danger\","
        + "\"callback_data\":\"d:ap_3kq7m2x9vt4p\"}],[{\"text\":\"Open in app\","
        + "\"callback_data\":\"o:ap_3kq7m2x9vt4p\"}]]}");
    ObjectNode body = TelegramMessages.body(424242, t, keyboard, true);
    assertThat(body.path("protect_content").asBoolean()).isTrue();
    assertThat(body.at("/link_preview_options/is_disabled").asBoolean()).isTrue();
    assertThat(body.has("parse_mode")).isFalse();
  }

  @Test
  void prodOffersDenyAndOpenOnlyAndSaysProd() {
    ApprovalNotice n = NoticeFixtures.prod();
    Eligibility.Verdict v = Eligibility.evaluate(new Eligibility.Facts(Eligibility.Channel.TELEGRAM, n, true, true,
        true, false, true));
    TgText t = TelegramMessages.request(n, v, ZoneOffset.ofHours(2), null);
    assertThat(t.text()).startsWith("PROD · Approval needed\n").contains("\nRuns on production\n");
    assertThat(TelegramMessages.keyboard(n, v).toString()).doesNotContain("Approve").contains("\"d:ap_9pz2kq7m3x1v\"");
  }

  @Test
  void theWholeMessageMustFit4096() {
    ApprovalNotice base = NoticeFixtures.dev();
    Eligibility.Verdict shown = new Eligibility.Verdict(false, false, true, null, null);
    int frame = TelegramMessages.request(NoticeFixtures.with(base, NoticeFixtures.action("sql", "sql", "x")), shown,
        ZoneOffset.ofHours(2), null).length() - 1;
    ApprovalNotice fits = NoticeFixtures.with(base, NoticeFixtures.action("sql", "sql", "x".repeat(4_096 - frame)));
    ApprovalNotice over = NoticeFixtures.with(base, NoticeFixtures.action("sql", "sql", "x".repeat(4_097 - frame)));
    assertThat(TelegramMessages.request(fits, shown, ZoneOffset.ofHours(2), null).length()).isEqualTo(4_096);
    assertThat(TelegramMessages.request(fits, shown, ZoneOffset.ofHours(2), null).fits()).isTrue();
    assertThat(TelegramMessages.request(over, shown, ZoneOffset.ofHours(2), null).fits()).isFalse();
  }

  @Test
  void callbackDataStaysWithin64BytesForA32CharacterId() {
    String id = "ap_" + "q".repeat(29);
    ApprovalNotice n = new ApprovalNotice(id, "sql_execute", "sql_execute", "dev-db",
        ai.msrv.gaterook.connector.api.Env.DEV, "dev", "codex", "codex-laptop", NoticeFixtures.dev().action(), null,
        "dev.sql.write-approval", "optional", NoticeFixtures.CREATED, NoticeFixtures.EXPIRES,
        NoticeFixtures.dev().digest(), false);
    Eligibility.Verdict v = new Eligibility.Verdict(true, true, true, null, null);
    for (JsonNode row : TelegramMessages.keyboard(n, v).path("inline_keyboard")) {
      for (JsonNode b : row) {
        assertThat(b.path("callback_data").asString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
      }
    }
    for (JsonNode row : TelegramMessages.confirm(n, "n7Kq9x2A").path("inline_keyboard")) {
      for (JsonNode b : row) {
        assertThat(b.path("callback_data").asString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
      }
    }
  }
}
