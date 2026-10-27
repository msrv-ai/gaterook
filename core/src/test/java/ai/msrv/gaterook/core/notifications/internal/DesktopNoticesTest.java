package ai.msrv.gaterook.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.notifications.NoticeFixtures;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code notify.desktop} event Electron main turns into an OS notification (ARCHITECTURE.md §7.4): title, subtitle
 * and one line of the action within macOS's 256 bytes, Review and Deny and never Approve, prod critical and with
 * sound, a burst as one summary.
 */
class DesktopNoticesTest {

  @Test
  void aDevRequestHasReviewAndDenyAndOneLineOfTheAction() {
    ObjectNode e = DesktopNotices.render(NoticeFixtures.dev(), true, 3, ZoneOffset.ofHours(2));
    assertThat(e.path("kind").asString()).isEqualTo("request");
    assertThat(e.path("id").asString()).isEqualTo("ap_3kq7m2x9vt4p");
    assertThat(e.path("title").asString()).isEqualTo("Approval needed · sql_execute on dev-db");
    assertThat(e.path("subtitle").asString()).isEqualTo("dev environment · from Codex");
    assertThat(e.path("body").asString()).isEqualTo(NoticeFixtures.SQL);
    assertThat(e.path("footer").asString()).isEqualTo("dev environment · Codex · auto-deny at 14:34:12");
    assertThat(e.path("urgency").asString()).isEqualTo("normal");
    assertThat(e.path("silent").asBoolean()).isTrue();
    assertThat(e.path("route").asString()).isEqualTo("/approvals/ap_3kq7m2x9vt4p");
    assertThat(e.path("actions").toString()).isEqualTo("[{\"id\":\"review\",\"text\":\"Review\"},"
        + "{\"id\":\"deny\",\"text\":\"Deny\"}]");
    assertThat(e.toString()).doesNotContain("approve");
    assertThat(e.path("pendingCount").asLong()).isEqualTo(3);
  }

  @Test
  void prodIsCriticalWithSoundAndSaysSo() {
    ObjectNode e = DesktopNotices.render(NoticeFixtures.prod(), true, 1, ZoneOffset.UTC);
    assertThat(e.path("title").asString()).isEqualTo("PROD · Approval needed · ssh_run on prod-vm");
    assertThat(e.path("subtitle").asString()).isEqualTo("Runs on production · from Claude Desktop");
    assertThat(e.path("urgency").asString()).isEqualTo("critical");
    assertThat(e.path("silent").asBoolean()).isFalse();
    assertThat(DesktopNotices.render(NoticeFixtures.prod(), false, 1, ZoneOffset.UTC).path("silent").asBoolean())
        .isTrue();
  }

  @Test
  void titleSubtitleAndBodyFitMacOs256Bytes() {
    ApprovalNotice n = NoticeFixtures.with(NoticeFixtures.dev(), NoticeFixtures.action("sql", "sql",
        "UPDATE заказы SET статус = 'отменён' WHERE описание = '" + "ж".repeat(200) + "';"));
    ObjectNode e = DesktopNotices.render(n, true, 1, ZoneOffset.UTC);
    int bytes = (e.path("title").asString() + e.path("subtitle").asString() + e.path("body").asString())
        .getBytes(StandardCharsets.UTF_8).length;
    assertThat(bytes).isLessThanOrEqualTo(256);
    assertThat(e.path("body").asString()).endsWith("…");
  }

  @Test
  void aSecretLikeActionIsNotInTheNotification() {
    ApprovalNotice n = NoticeFixtures.with(NoticeFixtures.dev(), new ApprovalNotice.Action("sql", "sql", "", 30, true,
        0, 0, false));
    assertThat(DesktopNotices.render(n, true, 1, ZoneOffset.UTC).path("body").asString()).isEqualTo("1 row in orders");
  }

  @Test
  void aBurstIsOneSummaryWithReviewOnly() {
    ObjectNode s = DesktopNotices.summary(7, false);
    assertThat(s.path("id").asString()).isEqualTo(DesktopNotices.SUMMARY_ID);
    assertThat(s.path("title").asString()).isEqualTo("7 approval requests are waiting");
    assertThat(s.path("route").asString()).isEqualTo("/approvals");
    assertThat(s.path("actions").toString()).isEqualTo("[{\"id\":\"review\",\"text\":\"Review\"}]");
  }

  @Test
  void theFirstLineIsCutWithAnEllipsis() {
    assertThat(Notices.firstLine("SELECT 1;\nSELECT 2;", 120)).isEqualTo("SELECT 1; …");
    assertThat(Notices.firstLine("x".repeat(130), 120)).hasSize(120).endsWith("…");
    assertThat(Notices.firstLine("😀".repeat(70), 120)).doesNotContain("�").endsWith("…");
  }
}
