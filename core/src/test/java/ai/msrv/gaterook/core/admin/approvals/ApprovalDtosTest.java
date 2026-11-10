package ai.msrv.gaterook.core.admin.approvals;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.approvals.ActionInspector;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.platform.Caller;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The exact action as the approver's screen draws it: hunks only for a diff they show completely, and what is shown
 * with it inspected as it is (§8.5).
 */
class ApprovalDtosTest {

  /** The value of a SQL template's parameter as the connector describes it ({@code TemplateParams.describe}). */
  private static ActionPreview.Field parameters(String value) {
    return new ActionPreview.Field("Parameters", ":status = '" + value + "' (text)");
  }

  @Test
  void whatTheReviewShowsBesidesTheActionIsInspectedAsTheActionIs() {
    // A SQL template runs fixed SQL: the agent's values exist only in the Parameters field.
    ActionPreview p = ActionPreview.of(ActionPreview.Kind.SQL, "sql", "UPDATE orders SET status = :status", "dev-db",
            "cancel_orders on dev-db (dev)")
        .withFields(List.of(new ActionPreview.Field("Template", "cancel_orders · version 3"),
            parameters("аctive")))
        .withImpact(List.of(new ActionPreview.Field("Target table", "orders‮")))
        .withWarnings(List.of("Runs on dev-db", "Check sta​tus"));

    ApprovalDtos.Detail d = ApprovalDtos.detail(view(p, "Cancels the order of a customer⁦"), List.of());

    assertThat(d.action().confusables()).as("the action itself is plain").isZero();
    assertThat(d.fields().getFirst().segments()).as("nothing to mark").isNull();
    assertThat(d.fields().getFirst().labelSegments()).isNull();
    assertThat(d.fields().get(1).segments()).as("a look-alike letter in a parameter value")
        .contains(new ActionInspector.Segment("confusable", "а"));
    assertThat(d.impact().getFirst().segments()).as("a direction control in an impact line")
        .contains(new ActionInspector.Segment("invisible", "‹U+202E›"));
    assertThat(d.warningSegments()).hasSize(2);
    assertThat(d.warningSegments().getFirst()).isNull();
    assertThat(d.warningSegments().get(1)).as("a zero-width space in a warning")
        .contains(new ActionInspector.Segment("invisible", "‹U+200B›"));
    assertThat(d.agentNoteSegments()).as("the agent's note too")
        .contains(new ActionInspector.Segment("invisible", "‹U+2066›"));
    assertThat(d.targetSegments()).isNull();
    assertThat(d.detailsHiddenChars()).isEqualTo(3);
    assertThat(d.detailsConfusables()).isEqualTo(1);
    // The texts are unchanged: the segments are only what the UI draws.
    assertThat(d.fields().get(1).text()).isEqualTo(":status = 'аctive' (text)");
    assertThat(d.fields().get(1).segments().stream().map(ActionInspector.Segment::v).collect(Collectors.joining()))
        .isEqualTo(":status = 'аctive' (text)");
  }

  @Test
  void ordinaryDetailsCarryNoSegmentsAndAQuotedValueInAnotherScriptIsData() {
    ActionPreview p = ActionPreview.of(ActionPreview.Kind.SQL, "sql", "UPDATE meetings SET title = :title",
            "dev-db · db.dev.internal:5432/app", "rename_meeting on dev-db (dev)")
        .withFields(List.of(parameters("Встреча с клиентом"), new ActionPreview.Field("Binding",
            "Values are bound by the driver, never pasted into the SQL")))
        .withImpact(List.of(new ActionPreview.Field("Target table", "meetings"),
            new ActionPreview.Field("Transaction", "One transaction, rolled back on error")))
        .withWarnings(List.of("Changes up to 1 row"));

    ApprovalDtos.Detail d = ApprovalDtos.detail(view(p, "Renames the meeting as the ticket asks"), List.of());

    assertThat(d.fields()).allSatisfy(f -> {
      assertThat(f.segments()).as(f.label()).isNull();
      assertThat(f.labelSegments()).as(f.label()).isNull();
    });
    assertThat(d.impact()).allSatisfy(f -> assertThat(f.segments()).as(f.label()).isNull());
    assertThat(d.warningSegments()).containsExactly((List<ActionInspector.Segment>) null);
    assertThat(d.targetSegments()).isNull();
    assertThat(d.agentNoteSegments()).isNull();
    assertThat(d.detailsHiddenChars()).isZero();
    assertThat(d.detailsConfusables()).isZero();
  }

  @Test
  void aTargetAndALabelAreNamesOfTheirOwn() {
    // A connector's target names the database the agent picked; a plugin chooses its own labels.
    ActionPreview p = ActionPreview.of(ActionPreview.Kind.SQL, "sql", "SELECT 1", "dev-db · dеv",
            "query on dev-db")
        .withFields(List.of(new ActionPreview.Field("Collеction", "orders")));

    ApprovalDtos.Detail d = ApprovalDtos.detail(view(p, null), List.of());

    assertThat(d.targetSegments()).contains(new ActionInspector.Segment("confusable", "е"));
    assertThat(d.fields().getFirst().labelSegments()).contains(new ActionInspector.Segment("confusable", "е"));
    assertThat(d.fields().getFirst().segments()).isNull();
    assertThat(d.detailsConfusables()).isEqualTo(2);
    assertThat(d.agentNoteSegments()).isNull();
  }

  @Test
  void aTemplatePreviewIsInspectedLikeARequest() {
    ActionPreview p = ActionPreview.of(ActionPreview.Kind.SQL, "sql", "UPDATE orders SET status = :status", "dev-db",
            "cancel_orders on dev-db (dev)")
        .withFields(List.of(parameters("ok‮")))
        .withWarnings(List.of("Check stаtus"));

    ApprovalDtos.Preview preview = ApprovalDtos.preview(p);

    assertThat(preview.fields().getFirst().segments()).contains(new ActionInspector.Segment("invisible", "‹U+202E›"));
    assertThat(preview.warningSegments().getFirst()).contains(new ActionInspector.Segment("confusable", "а"));
    assertThat(preview.detailsHiddenChars()).isEqualTo(1);
    assertThat(preview.detailsConfusables()).isEqualTo(1);
  }

  private static ApprovalView view(ActionPreview action, String note) {
    Instant at = Instant.parse("2026-11-10T10:00:00Z");
    return new ApprovalView("ap_1", "tc_1", "ep_1", "dev", "cn_1", "dev-db", "cancel_orders", "template:cancel_orders",
        Env.DEV, Caller.Kind.LOCAL_TOKEN, "tok_1", "ci-runner", "pending", "d".repeat(64), action, "a".repeat(64), note,
        "dev.sql.write-approval", "optional", null, null, null, at, at.plusSeconds(300), null, null, null, null, null,
        null);
  }

  @Test
  void aUnifiedDiffBecomesItsHunks() {
    String text = "--- deployment/api (live)\n+++ deployment/api (after apply)\n@@ -2,3 +2,3 @@ spec:\n spec:\n"
        + "-  replicas: 3\n+  replicas: 5\n   paused: false\n@@ -9 +9 @@\n-  a: 1\n\\ No newline at end of file\n"
        + "+  a: 2\n";

    ApprovalDtos.Diff diff = ApprovalDtos.diff(text);

    assertThat(diff.added()).isEqualTo(2);
    assertThat(diff.removed()).isEqualTo(2);
    assertThat(diff.hunks()).extracting(ApprovalDtos.Hunk::header).containsExactly("@@ -2,3 +2,3 @@ spec:",
        "@@ -9 +9 @@");
    assertThat(diff.hunks().getFirst().lines()).containsExactly(new ApprovalDtos.DiffLine("context", "spec:"),
        new ApprovalDtos.DiffLine("del", "  replicas: 3"), new ApprovalDtos.DiffLine("add", "  replicas: 5"),
        new ApprovalDtos.DiffLine("context", "  paused: false"));
    assertThat(diff.hunks().get(1).lines()).containsExactly(new ApprovalDtos.DiffLine("del", "  a: 1"),
        new ApprovalDtos.DiffLine("add", "  a: 2"));
    assertThat(ApprovalDtos.diff("@@ -1 +1 @@\n-x\n+y\n").hunks()).hasSize(1);
    assertThat(ApprovalDtos.diff("@@ -1,2 +1,2 @@\r\n-x\r\n\r\n+y\r\n\\ No newline at end of file\r\n").hunks()
        .getFirst().lines()).as("CRLF").containsExactly(new ApprovalDtos.DiffLine("del", "x\r"),
            new ApprovalDtos.DiffLine("context", ""), new ApprovalDtos.DiffLine("add", "y\r"));
  }

  @Test
  void aDiffWhoseHunksWouldLeaveSomethingOutIsShownAsItsText() {
    for (String text : List.of(
        "--- live-state\n+++ desired\n-replicas: 3\n+replicas: 5\n",
        "Also removes the namespace\n--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y\n",
        "--- a\n@@ -1 +1 @@\n-x\n+y\n",
        "--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y\nrm -rf /srv/app\n",
        "--- a\n+++ b\n@@ -1 +1 @@\n-x\n\\ and then restarts\n+y\n")) {
      assertThat(ApprovalDtos.diff(text)).as(text).isNull();
      ApprovalDtos.Action action = ApprovalDtos.action(ActionPreview.of(ActionPreview.Kind.DIFF, "diff", text,
          "dev-cluster", "apply on dev-cluster"));
      assertThat(action.diff()).as(text).isNull();
      assertThat(action.segments().stream().map(ActionInspector.Segment::v).collect(Collectors.joining()))
          .as("the whole text, segmented").isEqualTo(text);
    }
  }
}
