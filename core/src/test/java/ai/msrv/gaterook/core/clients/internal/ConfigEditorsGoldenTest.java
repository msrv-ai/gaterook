package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.clients.ConfigEditException;
import ai.msrv.gaterook.core.clients.internal.ClientAdapter.Entry;
import ai.msrv.gaterook.core.clients.internal.ClientAdapter.EntryValues;
import ai.msrv.gaterook.core.clients.internal.ClientEnvironment.Os;
import ai.msrv.gaterook.core.clients.internal.persistence.ClientKind;
import ai.msrv.gaterook.core.clients.internal.persistence.LinkMethod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Golden before/after of the surgical edits (report 03 §8.1): {@code golden/<format>/<case>.before} is a client's
 * config as people write it, {@code .after} is it with our entry, byte for byte. Each edit also holds the tree invariant
 * and leaves every byte outside our entry as it was; Disconnect (the reverse edit) gives {@code .before} back
 * ({@code .removed} where the original can't be told apart, an emptied object). Regenerate with
 * {@code -Dgolden.update=true} and read the diff before committing it.
 */
class ConfigEditorsGoldenTest {

  private static final Path SOURCE = Path.of("src/test/resources/ai/msrv/gaterook/core/clients/golden");
  private static final ClientEnvironment ENV = new ClientEnvironment(Os.LINUX, Path.of("/home/me"),
      Path.of("/home/me/.gaterook"), null, Map.of(), false, "laptop");
  private static final EntryValues DEV = new EntryValues("dev", "http://127.0.0.1:7465/mcp/dev", "gr_cx7q2lmaabcd",
      ClientAdapter.TOKEN_PLACEHOLDER, "writes", 60);

  @ParameterizedTest
  @ValueSource(strings = {"kit", "missing", "empty-object", "no-servers", "four-spaces", "crlf", "bom",
      "no-final-newline", "minified", "unicode", "empty-servers"})
  void cursorTokenEntry(String name) throws IOException {
    Entry e = ClientAdapter.of(ClientKind.CURSOR).entry(ENV, LinkMethod.TOKEN, "gateway-dev", DEV);
    String before = read("json/" + name + ".before");
    String after = JsonEntryEditor.upsert(before, e.rootKey(), e.name(), e.json());
    golden("json/" + name + ".after", after);
    assertThat(JsonEntryEditor.entry(after, "mcpServers", "gateway-dev")).contains(e.json());
    assertUntouchedOutsideTheEntry(before, after);
    String removed = JsonEntryEditor.remove(after, "mcpServers", "gateway-dev");
    if (exists("json/" + name + ".removed")) {
      golden("json/" + name + ".removed", removed);
    } else {
      assertThat(removed).as("Disconnect gives the file back byte for byte").isEqualTo(before.isEmpty() ? removed
          : before);
    }
    assertThat(JsonEntryEditor.entry(removed, "mcpServers", "gateway-dev")).isEmpty();
  }

  @Test
  void theOtherJsonClientsWriteTheirOwnShapes() throws IOException {
    String before = read("json/kit.before");
    Entry claude = ClientAdapter.of(ClientKind.CLAUDE_DESKTOP).entry(ENV, LinkMethod.CONFIG_BRIDGE, "gateway-dev", DEV);
    golden("json/kit.claude-bridge.after", JsonEntryEditor.upsert(before, claude.rootKey(), claude.name(),
        claude.json()));
    Entry agToken = ClientAdapter.of(ClientKind.ANTIGRAVITY).entry(ENV, LinkMethod.TOKEN, "gateway-dev", DEV);
    golden("json/missing.antigravity.after", JsonEntryEditor.upsert("", agToken.rootKey(), agToken.name(),
        agToken.json()));
    Entry cursorBridge = ClientAdapter.of(ClientKind.CURSOR).entry(ENV, LinkMethod.CONFIG_BRIDGE, "gateway-dev", DEV);
    golden("json/kit.cursor-bridge.after", JsonEntryEditor.upsert(before, cursorBridge.rootKey(), cursorBridge.name(),
        cursorBridge.json()));
    Entry oauth = ClientAdapter.of(ClientKind.CURSOR).entry(ENV, LinkMethod.OAUTH, "gateway-dev", DEV);
    assertThat(oauth.json().toString()).isEqualTo("{\"url\":\"http://127.0.0.1:7465/mcp/dev\"}");
  }

  @Test
  void anEntryIsReplacedInPlace() throws IOException {
    Entry e = ClientAdapter.of(ClientKind.CURSOR).entry(ENV, LinkMethod.TOKEN, "gateway-dev", DEV);
    String once = JsonEntryEditor.upsert(read("json/kit.before"), e.rootKey(), e.name(), e.json());
    Entry moved = ClientAdapter.of(ClientKind.CURSOR).entry(ENV, LinkMethod.TOKEN, "gateway-dev",
        new EntryValues("dev", "http://127.0.0.1:7475/mcp/dev", null, ClientAdapter.TOKEN_PLACEHOLDER, "writes", 60));
    String repaired = JsonEntryEditor.upsert(once, e.rootKey(), e.name(), moved.json());
    assertThat(repaired).isEqualTo(once.replace("7465", "7475"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"jsonc", "trailing-comma", "duplicate"})
  void jsonThatIsntStrictIsRefusedWithItsPosition(String name) throws IOException {
    String before = read("json/" + name + ".before");
    assertThatThrownBy(() -> JsonEntryEditor.upsert(before, "mcpServers", "gateway-dev",
        ClientAdapter.JSON.createObjectNode().put("url", "x")))
        .isInstanceOfSatisfying(ConfigEditException.class, ex -> {
          assertThat(ex.reason()).isIn(ConfigEditException.Reason.JSONC, ConfigEditException.Reason.INVALID);
          assertThat(ex.line()).isPositive();
          assertThat(ex.getMessage()).contains("line " + ex.line());
        });
  }

  @Test
  void aTokenTheParserQuotesShowsAsItsPrefix() {
    String token = "gwt_" + "K9xP".repeat(10) + "abc";
    String broken = "{\n  \"mcpServers\": {\n    \"x\": {\"headers\": {\"Authorization\": " + token + "}}\n  }\n}\n";
    assertThatThrownBy(() -> JsonEntryEditor.upsert(broken, "mcpServers", "gateway-dev",
        ClientAdapter.JSON.createObjectNode().put("url", "x")))
        .isInstanceOfSatisfying(ConfigEditException.class, ex -> {
          assertThat(ex.getMessage()).doesNotContain(token).contains("gwt_K9xP…");
          assertThat(ex.line()).isEqualTo(3);
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"kit", "missing", "no-final-newline", "crlf", "comments"})
  void codexBlock(String name) throws IOException {
    Entry e = ClientAdapter.of(ClientKind.CODEX).entry(ENV, LinkMethod.HELPER, "gateway-dev", DEV);
    String before = read("toml/" + name + ".before");
    String after = TomlBlockEditor.append(before, e.name(), e.tomlBlock());
    golden("toml/" + name + ".after", after);
    assertThat(after).startsWith(before);
    assertThat(TomlBlockEditor.table(after, "gateway-dev").orElseThrow().path("http_headers_helper").asString())
        .isEqualTo("~/.gaterook/bin/gw-bridge headers --endpoint dev --grant gr_cx7q2lmaabcd");
    String removed = TomlBlockEditor.remove(after, "gateway-dev");
    String expected = before.isEmpty() || before.endsWith("\n") ? before : before + "\n";
    assertThat(removed).as("Disconnect gives the file back").isEqualTo(expected);
  }

  @Test
  void codexTablesRewrittenWithoutOurMarkersStillGoAndOrphanedMarkersToo() throws IOException {
    Entry e = ClientAdapter.of(ClientKind.CODEX).entry(ENV, LinkMethod.HELPER, "gateway-dev", DEV);
    String after = TomlBlockEditor.append(read("toml/comments.before"), e.name(), e.tomlBlock());
    // Codex's own Settings rewrote the table: our markers are gone, a key was added.
    String rewritten = after.replace(TomlBlockEditor.startMarker("gateway-dev") + "\n", "")
        .replace(TomlBlockEditor.endMarker("gateway-dev") + "\n", "") + "[mcp_servers.gateway-dev.env]\nX = \"1\"\n"
        + "[profiles.other]\nmodel = \"o4\"\n";
    String removed = TomlBlockEditor.remove(rewritten, "gateway-dev");
    assertThat(TomlBlockEditor.table(removed, "gateway-dev")).isEmpty();
    assertThat(removed).contains("[profiles.other]").contains("[mcp_servers.github.env]");
    // Markers left behind without the table.
    String orphaned = read("toml/kit.before") + "\n" + TomlBlockEditor.startMarker("gateway-dev") + "\n"
        + TomlBlockEditor.endMarker("gateway-dev") + "\n";
    assertThat(TomlBlockEditor.remove(orphaned, "gateway-dev")).isEqualTo(read("toml/kit.before"));
  }

  @Test
  void anInlineTableOfServersIsRefusedWithTheBlockToPaste() throws IOException {
    Entry e = ClientAdapter.of(ClientKind.CODEX).entry(ENV, LinkMethod.HELPER, "gateway-dev", DEV);
    assertThatThrownBy(() -> TomlBlockEditor.append(read("toml/inline.before"), e.name(), e.tomlBlock()))
        .isInstanceOfSatisfying(ConfigEditException.class, ex -> {
          assertThat(ex.reason()).isEqualTo(ConfigEditException.Reason.INLINE_TABLE);
          assertThat(ex.fragment()).contains("[mcp_servers.gateway-dev]");
        });
    assertThatThrownBy(() -> TomlBlockEditor.append("model = \n", e.name(), e.tomlBlock()))
        .isInstanceOfSatisfying(ConfigEditException.class, ex -> assertThat(ex.reason())
            .isEqualTo(ConfigEditException.Reason.INVALID));
  }

  @Test
  void helperCommandsQuoteForTheirShell() {
    ClientEnvironment elsewhere = new ClientEnvironment(Os.LINUX, Path.of("/home/me"), Path.of("/data/gw home"), null,
        Map.of(), true, "laptop");
    assertThat(elsewhere.helperCommand("headers", "--endpoint", "dev")).isEqualTo(
        "'/data/gw home/bin/gw-bridge' headers --endpoint dev");
    ClientEnvironment windows = new ClientEnvironment(Os.WINDOWS, Path.of("C:\\Users\\me"),
        Path.of("C:\\Users\\me").resolve(".gaterook"), null, Map.of(), false, "LAPTOP.corp");
    String helper = windows.helperCommand("headers", "--endpoint", "dev", "--grant", "gr_cx7q2lmaabcd");
    assertThat(helper).startsWith("\"").contains("gw-bridge.cmd\" headers --endpoint dev --grant gr_cx7q2lmaabcd");
    assertThat(windows.shortHostname()).isEqualTo("laptop");
    assertThat(TomlBlockEditor.literalOrBasic("C:\\a\\b")).isEqualTo("'C:\\a\\b'");
    assertThat(TomlBlockEditor.literalOrBasic("it's")).isEqualTo("\"it's\"");
    assertThat(TomlBlockEditor.basic("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
  }

  @Test
  void theDiffCountsAndNumbersLinesLikeTheKit() throws IOException {
    Entry e = ClientAdapter.of(ClientKind.CURSOR).entry(ENV, LinkMethod.TOKEN, "gateway-dev", DEV);
    String before = read("json/kit.before");
    String after = JsonEntryEditor.upsert(before, e.rootKey(), e.name(), e.json());
    DiffService.Diff d = DiffService.splice(before, after);
    // The kit's "What will change" of ~/.cursor/mcp.json: 7 additions, 1 deletion, @@ -1,8 +1,14 @@.
    assertThat(d.added()).isEqualTo(7);
    assertThat(d.removed()).isEqualTo(1);
    assertThat(d.hunks()).hasSize(1);
    assertThat(d.hunks().getFirst().header()).isEqualTo("@@ -1,8 +1,14 @@");
    List<DiffService.Line> lines = d.hunks().getFirst().lines();
    assertThat(lines.get(5)).isEqualTo(new DiffService.Line("removed", 6, null, "    }"));
    assertThat(lines.get(6)).isEqualTo(new DiffService.Line("added", null, 6, "    },"));
    assertThat(lines.get(9)).isEqualTo(new DiffService.Line("added", null, 9, "      \"headers\": {"));
    assertThat(lines.getLast()).isEqualTo(new DiffService.Line("context", 8, 14, "}"));
    assertThat(DiffService.splice(null, "a\n").hunks().getFirst().header()).isEqualTo("@@ -0,0 +1 @@");
    assertThat(DiffService.diff(null, "a\n").hunks().getFirst().header()).isEqualTo("@@ -0,0 +1 @@");
    assertThat(DiffService.diff("x\n", "x\n").added()).isZero();
    assertThat(DiffService.splice("x\n", "x\n").added()).isZero();
    // Disconnect is the mirror image: the same lines come back out.
    DiffService.Diff back = DiffService.splice(after, JsonEntryEditor.remove(after, e.rootKey(), e.name()));
    assertThat(back.added()).isEqualTo(1);
    assertThat(back.removed()).isEqualTo(7);
    assertThat(back.hunks().getFirst().header()).isEqualTo("@@ -1,14 +1,8 @@");
    // A TOML block appended at the end: only added lines, the context above it.
    String toml = read("toml/kit.before");
    String appended = TomlBlockEditor.append(toml, "gateway-dev", "[mcp_servers.gateway-dev]\nurl = \"x\"\n");
    DiffService.Diff t = DiffService.splice(toml, appended);
    assertThat(t.removed()).isZero();
    assertThat(t.hunks().getFirst().lines()).filteredOn(l -> l.type().equals("added")).first()
        .extracting(DiffService.Line::text).isEqualTo("");
  }

  /** Bytes outside our entry stay: the edited text keeps the original's head and tail. */
  private static void assertUntouchedOutsideTheEntry(String before, String after) {
    int head = 0;
    while (head < before.length() && head < after.length() && before.charAt(head) == after.charAt(head)) {
      head++;
    }
    int tail = 0;
    while (tail < before.length() - head && tail < after.length() - head
        && before.charAt(before.length() - 1 - tail) == after.charAt(after.length() - 1 - tail)) {
      tail++;
    }
    String changedBefore = before.substring(head, before.length() - tail);
    assertThat(changedBefore.strip().replace(",", "").replace("}", "").replace("{", "")).as(
        "only separators of the original change: %s", changedBefore).isEmpty();
  }

  private static String read(String name) throws IOException {
    try (InputStream in = ConfigEditorsGoldenTest.class.getResourceAsStream("/ai/msrv/gaterook/core/clients/golden/"
        + name)) {
      if (in == null) {
        throw new IOException("no golden file " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static boolean exists(String name) {
    return ConfigEditorsGoldenTest.class.getResource("/ai/msrv/gaterook/core/clients/golden/" + name) != null;
  }

  private static void golden(String name, String actual) throws IOException {
    if (Boolean.getBoolean("golden.update")) {
      Files.writeString(SOURCE.resolve(name), actual, StandardCharsets.UTF_8);
      return;
    }
    assertThat(actual).as("golden " + name).isEqualTo(read(name));
  }
}
