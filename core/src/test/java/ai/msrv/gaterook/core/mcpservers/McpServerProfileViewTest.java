package ai.msrv.gaterook.core.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connectors.support.mcp.VerifiedProfiles;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * GateRook's verified profile of a vendor's server as the admin API shows it (§7.5): on a card, its setup and pins;
 * in a review, how each pin stands in the reviewed URL, without the value the person put there.
 */
class McpServerProfileViewTest {

  private static final VerifiedProfiles.Profile SUPABASE = VerifiedProfiles.byId("supabase").orElseThrow();

  @Test
  void aCardShowsTheSetupAndPinsWithoutAState() {
    McpServerService.ProfileView card = McpServerService.profileView(SUPABASE, null);

    assertThat(card.id()).isEqualTo("supabase");
    assertThat(card.transport()).isEqualTo("http");
    assertThat(card.url()).isEqualTo("https://mcp.supabase.com/mcp?project_ref={project_ref}&read_only=true");
    assertThat(card.checked()).isEqualTo("2026-11-11");
    assertThat(card.registryName()).isEqualTo("com.supabase/mcp");
    assertThat(card.placeholders()).extracting(McpServerService.PlaceholderView::name).containsExactly("project_ref");
    assertThat(card.pins()).extracting(McpServerService.PinView::parameter).containsExactly("read_only", "project_ref");
    assertThat(card.pins()).extracting(McpServerService.PinView::set).containsOnlyNulls();
    assertThat(card.pins().getFirst().setTo()).isEqualTo("true");
    assertThat(card.pins().get(1).setTo()).isNull();
    assertThat(card.tools()).extracting(McpServerService.ToolRuleView::start).containsOnly("approval");
    assertThat(card.tools()).extracting(McpServerService.ToolRuleView::kind).containsOnly("code");
  }

  @Test
  void aReviewSaysHowEachPinStandsWithoutEchoingTheValue() {
    McpServerService.ProfileView review = McpServerService.profileView(SUPABASE,
        "https://mcp.supabase.com/mcp?project_ref=abcdefghijklmnopqrst");

    assertThat(review.pins()).extracting(McpServerService.PinView::set).containsExactly(false, true);
    assertThat(review.pins().getFirst().missing()).startsWith("Without read_only=true");
    // A pin's view has no field that could carry the value from the person's URL.
    assertThat(Arrays.stream(McpServerService.PinView.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly("parameter", "setTo", "purpose", "missing", "set");
    assertThat(review.toString()).doesNotContain("abcdefghijklmnopqrst");
  }

  @Test
  void aCommandsProfileHasNoUrlAndNoPins() {
    McpServerService.ProfileView firebase = McpServerService.profileView(
        VerifiedProfiles.byId("firebase").orElseThrow(), null);

    assertThat(firebase.transport()).isEqualTo("stdio");
    assertThat(firebase.url()).isNull();
    assertThat(firebase.command()).containsExactly("npx", "-y", "firebase-tools@15.35.0", "mcp");
    assertThat(firebase.pins()).isEmpty();
    assertThat(firebase.tools()).filteredOn(t -> t.tool().equals("apptesting_run_test"))
        .extracting(McpServerService.ToolRuleView::start).containsExactly("off");
  }
}
