package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The escaping property (ARCHITECTURE.md §9 "Command injection", report 06 §7.5): whatever a value holds, the shell
 * gets it back as one word, byte for byte, and runs nothing of it. Checked against the real {@code /bin/sh}: the
 * quoted words are written into a script file (no argv encoding in between), the script prints each word followed by
 * NUL, and the output must split back into exactly the values.
 */
class ShellQuoteTest {

  /** What breaks naive quoting: quotes, expansions, separators, globs, comments, escapes and non-ASCII. */
  private static final String HOSTILE = "'\"$`\\;&|<>(){}[]*?~#!%^=+-., \n\t/:@aZ09üß日本🙂  ";

  private static final List<String> CORPUS = List.of(
      "", "'", "''", "'\\''", "\"", "'; id #", "$(id)", "`id`", "${HOME}", "$HOME", "a b", "*", "~", "-rf /",
      "line1\nline2", "\\", "\\'", "'\"'\"'", "!!", "$((1+1))", ">/tmp/x", "a;b&&c||d", "{a,b}", "#comment",
      "üñî©ødé 日本語 🙂", "\t", "x'y\"z$w`v", "$(touch \"$MARKER\")", "`touch $MARKER`", "'; touch $MARKER; '",
      "\"; touch $MARKER; \"", "\n touch $MARKER");

  @TempDir
  Path dir;

  @Test
  void theCorpusComesBackByteForByte() throws Exception {
    assertRoundTrip(CORPUS);
  }

  @Test
  void randomHostileValuesComeBackByteForByte() throws Exception {
    Random random = new Random(20261025L);
    int[] alphabet = HOSTILE.codePoints().toArray();
    for (int batch = 0; batch < 10; batch++) {
      List<String> values = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        StringBuilder v = new StringBuilder();
        int length = random.nextInt(24);
        for (int j = 0; j < length; j++) {
          v.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
        }
        values.add(v.toString());
      }
      assertRoundTrip(values);
    }
  }

  /** The same through a runbook: a string parameter reaches {@code printf} as one argument and nothing runs. */
  @Test
  void aRunbookStringParameterIsOneWordOnTheHost() throws Exception {
    Runbook rb = RunbookCompiler.compile(TestConfigs.runbook("echo-it", "printf '%s\\0' {text} {mode}", false, """
        [{"name":"text","type":"string","pattern":"(?s).*","maxLength":256,"allowLeadingDash":true},
         {"name":"mode","type":"enum","values":["a b","$(id)"]}]""")).runbook();
    Random random = new Random(7L);
    String printable = "'\"$`\\;&|<>(){}[]*?~#!%^=+-., /:@aZ09üß日本";
    int[] alphabet = printable.codePoints().toArray();
    StringBuilder script = new StringBuilder();
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      StringBuilder v = new StringBuilder();
      for (int j = random.nextInt(20); j > 0; j--) {
        v.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
      }
      String mode = i % 2 == 0 ? "a b" : "$(id)";
      Map<String, JsonNode> values = rb.values(JsonNodeFactory.instance.objectNode().put("text", v.toString())
          .put("mode", mode));
      script.append(rb.render(values, false, 0)).append('\n');
      expected.add(v.toString());
      expected.add(mode);
    }
    assertThat(run(script.toString())).containsExactlyElementsOf(expected);
  }

  /**
   * Why the lexer refuses a placeholder inside {@code "…"} nested in {@code "$(…)"} (review 2026-10-26): quoted the
   * gateway's way, the value is still inside the inner double quotes, and {@code /bin/sh} runs its {@code $(…)}.
   */
  @Test
  void aValueInDoubleQuotesNestedInASubstitutionWouldRunSoTheLexerRefusesIt() throws Exception {
    String command = "printf '%s' \"$(printf '%s' \"{text}\")\"";
    assertThat(CommandLexer.lex(command, java.util.Set.of("text")).errors()).singleElement().asString()
        .contains("inside \"…\"");

    // What the lexer used to let through, rendered: the value single-quoted, inside the inner double quotes.
    Path marker = dir.resolve("nested-ran");
    String rendered = command.replace("{text}", ShellQuote.quote("$(touch \"$MARKER\")"));
    Path file = Files.writeString(dir.resolve("nested.sh"), rendered + "\n", StandardCharsets.UTF_8);
    ProcessBuilder pb = new ProcessBuilder("/bin/sh", file.toString());
    pb.environment().put("MARKER", marker.toString());
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(marker).exists();
  }

  private void assertRoundTrip(List<String> values) throws Exception {
    StringBuilder script = new StringBuilder("printf '%s\\0'");
    for (String v : values) {
      script.append(' ').append(ShellQuote.quote(v));
    }
    script.append('\n');
    assertThat(run(script.toString())).containsExactlyElementsOf(values);
  }

  /** Runs a script with {@code /bin/sh} and splits its output at NUL. */
  private List<String> run(String script) throws IOException, InterruptedException {
    Path file = Files.writeString(dir.resolve("quote-" + System.nanoTime() + ".sh"), script, StandardCharsets.UTF_8);
    Path marker = dir.resolve("ran");
    ProcessBuilder pb = new ProcessBuilder("/bin/sh", file.toString()).redirectErrorStream(false);
    pb.environment().put("LC_ALL", "C.UTF-8");
    pb.environment().put("MARKER", marker.toString());
    Process p = pb.start();
    byte[] out = p.getInputStream().readAllBytes();
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(p.exitValue()).as(err).isZero();
    assertThat(err).isEmpty();
    assertThat(marker).doesNotExist();
    String text = new String(out, StandardCharsets.UTF_8);
    List<String> words = new ArrayList<>(List.of(text.split("\0", -1)));
    assertThat(words.remove(words.size() - 1)).isEmpty(); // after the last NUL
    return words;
  }
}
