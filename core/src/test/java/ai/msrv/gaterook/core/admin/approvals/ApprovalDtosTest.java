package ai.msrv.gaterook.core.admin.approvals;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.core.approvals.ActionInspector;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The exact action as the approver's screen draws it: hunks only for a diff they show completely (§8.5). */
class ApprovalDtosTest {

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
