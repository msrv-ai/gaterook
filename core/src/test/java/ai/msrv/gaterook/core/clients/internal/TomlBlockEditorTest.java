package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.clients.ConfigEditException;
import org.junit.jupiter.api.Test;

/**
 * Removing GateRook's Codex entry when the block between its markers no longer holds only that entry: what someone
 * else put there (another table, a key of the table above) stays, and only GateRook's table and markers go.
 */
class TomlBlockEditorTest {

  private static final String START = TomlBlockEditor.startMarker("gaterook-dev");
  private static final String END = TomlBlockEditor.endMarker("gaterook-dev");
  private static final String OURS = """
      [mcp_servers.gaterook-dev]
      url = "http://127.0.0.1:7465/mcp/dev"
      startup_timeout_sec = 20
      """;

  @Test
  void aTableSomeoneAddedBetweenOurMarkersStaysWhenOurEntryGoes() {
    String text = "model = \"o3\"\n\n" + START + "\n" + OURS + "\n[profiles.work]\nmodel = \"gpt-5\"\n" + END + "\n";
    assertThat(TomlBlockEditor.remove(text, "gaterook-dev"))
        .isEqualTo("model = \"o3\"\n\n[profiles.work]\nmodel = \"gpt-5\"\n");
  }

  @Test
  void anotherServerBetweenOurMarkersStaysWithItsSubTables() {
    String github = "[mcp_servers.github]\ncommand = \"npx\"\n\n[mcp_servers.github.env]\nTOKEN_FILE = \"x\"\n";
    String text = START + "\n" + OURS + "[mcp_servers.gaterook-dev.env]\nA = \"1\"\n" + github + END + "\n";
    String removed = TomlBlockEditor.remove(text, "gaterook-dev");
    assertThat(removed).isEqualTo(github);
    assertThat(TomlBlockEditor.names(removed)).containsExactly("github");
  }

  @Test
  void aTableBeforeOurHeaderInsideTheBlockStaysToo() {
    String text = "[tui]\nnotifications = true\n\n" + START + "\n[profiles.fast]\nmodel = \"o4-mini\"\n" + OURS + END
        + "\n";
    assertThat(TomlBlockEditor.remove(text, "gaterook-dev"))
        .isEqualTo("[tui]\nnotifications = true\n\n[profiles.fast]\nmodel = \"o4-mini\"\n");
  }

  @Test
  void keysAndCommentsAboveOurHeaderBelongToTheTableBeforeTheBlockAndStay() {
    String text = "[tui]\ntheme = \"dark\"\n\n" + START + "\nnotifications = true\n# a note\n" + OURS + END + "\n";
    String removed = TomlBlockEditor.remove(text, "gaterook-dev");
    assertThat(removed).isEqualTo("[tui]\ntheme = \"dark\"\n\nnotifications = true\n# a note\n");
    assertThat(TomlBlockEditor.parse(removed).path("tui").path("notifications").asBoolean()).isTrue();
  }

  @Test
  void ourTableOutsideTheMarkersGoesAndAForeignTableBetweenThemStays() {
    String text = OURS + "\n" + START + "\n[profiles.work]\nmodel = \"gpt-5\"\n" + END + "\n";
    String removed = TomlBlockEditor.remove(text, "gaterook-dev");
    assertThat(removed).isEqualTo("[profiles.work]\nmodel = \"gpt-5\"\n");
    assertThat(TomlBlockEditor.table(removed, "gaterook-dev")).isEmpty();
  }

  @Test
  void aBlockWithOnlyOurEntryStillGoesWithTheBlankLineBeforeIt() {
    String text = "model = \"o3\"\n\n" + START + "\n" + OURS + END + "\n";
    assertThat(TomlBlockEditor.remove(text, "gaterook-dev")).isEqualTo("model = \"o3\"\n");
  }

  @Test
  void aLineOfAMultiLineArrayIsNoTableHeader() {
    // Inside our own table: the nested array's lines are values, so our whole table goes and nothing else.
    String ours = "[mcp_servers.gaterook-dev]\nargs = [\n  [\"a\", \"b\"],\n  [1, 2],\n]\nurl = \"x\"\n";
    String text = "[profiles.work]\nmodel = \"gpt-5\"\n\n" + START + "\n" + ours + END + "\n";
    assertThat(TomlBlockEditor.remove(text, "gaterook-dev")).isEqualTo("[profiles.work]\nmodel = \"gpt-5\"\n");
  }

  @Test
  void aKeyAfterOurEndMarkerIsOneOfOurTablesAndGoesWithItWhileACommentThereStays() {
    // TOML reads a key below our table as one of its keys, wherever our end marker is; a comment is no key.
    String text = "[profiles.work]\nmodel = \"gpt-5\"\n\n" + START + "\n" + OURS + END + "\nextra = 1\n# a note\n";
    assertThat(TomlBlockEditor.remove(text, "gaterook-dev"))
        .isEqualTo("[profiles.work]\nmodel = \"gpt-5\"\n# a note\n");
  }

  @Test
  void anEntryWrittenAsDottedKeysOfAnotherTableIsRefusedNotGuessed() {
    String text = "[mcp_servers]\ngaterook-dev.url = \"http://127.0.0.1:7465/mcp/dev\"\n";
    assertThatThrownBy(() -> TomlBlockEditor.remove(text, "gaterook-dev"))
        .isInstanceOfSatisfying(ConfigEditException.class,
            e -> assertThat(e.reason()).isEqualTo(ConfigEditException.Reason.CHECK_FAILED));
  }
}
