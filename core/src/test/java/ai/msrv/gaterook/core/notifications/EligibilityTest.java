package ai.msrv.gaterook.core.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Report 13 §6.2 as a table: Approve in Slack or Telegram only when every condition holds — decisions from linked
 * accounts, not prod, no comment required (then or now), not a plugin, the whole action fits and nothing of it was
 * left out, nothing looks like a secret, nothing hidden or look-alike, no flood, still pending. Every combination of
 * the conditions is tried; one failing condition is enough to drop Approve.
 */
class EligibilityTest {

  @Test
  void approveNeedsEveryConditionInEveryCombination() {
    int conditions = 11;
    int checked = 0;
    for (int mask = 0; mask < (1 << conditions); mask++) {
      boolean linked = bit(mask, 0);
      boolean prod = bit(mask, 1);
      boolean commentThen = bit(mask, 2);
      boolean commentNow = bit(mask, 3);
      boolean plugin = bit(mask, 4);
      boolean fits = bit(mask, 5);
      boolean partial = bit(mask, 6);
      boolean secret = bit(mask, 7);
      boolean hidden = bit(mask, 8);
      boolean flood = bit(mask, 9);
      boolean pending = bit(mask, 10);
      ApprovalNotice n = NoticeFixtures.notice(prod ? Env.PROD : Env.DEV, "sql_execute", "dev-db",
          new ApprovalNotice.Action("sql", "sql", secret ? "" : NoticeFixtures.SQL, 57, secret, hidden ? 1 : 0, 0,
              partial));
      n = NoticeFixtures.withComment(n, commentThen ? "required" : "optional");
      if (plugin) {
        n = NoticeFixtures.plugin(n);
      }
      Eligibility.Verdict v = Eligibility.evaluate(new Eligibility.Facts(Eligibility.Channel.SLACK, n, linked,
          commentNow, fits, flood, pending));
      boolean expected = linked && !prod && !commentThen && !commentNow && !plugin && fits && !partial && !secret
          && !hidden && !flood && pending;
      assertThat(v.approve()).as("mask %s", Integer.toBinaryString(mask)).isEqualTo(expected);
      // Deny stays for a linked account whenever decisions from linked accounts are allowed and the request waits.
      assertThat(v.deny()).as("deny, mask %s", Integer.toBinaryString(mask)).isEqualTo(linked && pending);
      // An action that doesn't fit or looks like a secret is never sent: a cut one would look whole.
      assertThat(v.showAction()).isEqualTo(fits && !secret);
      assertThat(v.reason() == null).isEqualTo(expected);
      checked++;
    }
    assertThat(checked).isEqualTo(2048);
  }

  @Test
  void prodNeverOffersApproveEvenWithoutARequiredComment() {
    ApprovalNotice n = NoticeFixtures.withComment(NoticeFixtures.prod(), "optional");
    Eligibility.Verdict v = Eligibility.evaluate(new Eligibility.Facts(Eligibility.Channel.TELEGRAM, n, true, false,
        true, false, true));
    assertThat(v.approve()).isFalse();
    assertThat(v.deny()).isTrue();
    assertThat(v.reason()).isEqualTo("Runs on production: approve in the app.");
  }

  @Test
  void eachFailingConditionSaysWhyInTheMessage() {
    ApprovalNotice dev = NoticeFixtures.dev();
    List<String> reasons = new ArrayList<>();
    reasons.add(reason(dev, false, false, true, false, true));
    reasons.add(reason(dev, true, false, true, true, true));
    reasons.add(reason(NoticeFixtures.withComment(dev, "required"), true, false, true, false, true));
    reasons.add(reason(NoticeFixtures.plugin(dev), true, false, true, false, true));
    reasons.add(reason(dev, true, false, false, false, true));
    reasons.add(reason(NoticeFixtures.with(dev, new ApprovalNotice.Action("sql", "sql", "DELETE‹U+202E›", 8, false,
        1, 0, false)), true, false, true, false, true));
    reasons.add(reason(NoticeFixtures.with(dev, new ApprovalNotice.Action("sql", "sql", "DЕLETE", 6, false, 0, 1,
        false)), true, false, true, false, true));
    reasons.add(reason(dev, true, false, true, false, false));
    assertThat(reasons).containsExactly(
        "Decisions are made in GateRook only.",
        "Many requests are waiting: decide in the app.",
        "A comment is required: approve in the app.",
        "A plugin's action: approve in the app.",
        "The action is too long for this message: approve in the app.",
        "Contains 1 hidden character. Review in the app.",
        "Contains letters that look like others. Review in the app.",
        "The request was decided.");
  }

  @Test
  void aWithheldActionSaysWhyAndHowLongItIs() {
    ApprovalNotice big = NoticeFixtures.with(NoticeFixtures.dev(), new ApprovalNotice.Action("sql", "sql",
        "x".repeat(12_345), 12_345, false, 0, 0, false));
    Eligibility.Verdict v = Eligibility.evaluate(new Eligibility.Facts(Eligibility.Channel.SLACK, big, true, false,
        false, false, true));
    assertThat(v.withheld()).isEqualTo("The action is 12,345 characters long and is shown only in GateRook.");
    ApprovalNotice secret = NoticeFixtures.with(NoticeFixtures.dev(), new ApprovalNotice.Action("sql", "sql", "", 40,
        true, 0, 0, false));
    Eligibility.Verdict s = Eligibility.evaluate(new Eligibility.Facts(Eligibility.Channel.SLACK, secret, true, false,
        true, false, true));
    assertThat(s.showAction()).isFalse();
    assertThat(s.withheld()).isEqualTo("The action contains values that look like secrets, so it is shown only in "
        + "GateRook.");
  }

  private static String reason(ApprovalNotice n, boolean linked, boolean commentNow, boolean fits, boolean flood,
      boolean pending) {
    return Eligibility.evaluate(new Eligibility.Facts(Eligibility.Channel.SLACK, n, linked, commentNow, fits, flood,
        pending)).reason();
  }

  private static boolean bit(int mask, int i) {
    return (mask & (1 << i)) != 0;
  }
}
