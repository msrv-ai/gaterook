package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SshLogPrefilterTest {
  private static final String MARKER = "‹masked›";

  @Test
  void requiresAnExplicitMaskingContractAndKeepsUnsupportedQueriesLocal() throws Exception {
    assertThat(SshLogPrefilter.grepExpression(query("ERROR", false, true), TestCtx.read())).isEmpty();
    for (String expression : List.of("", "masked", "ask", "échec", "first\nsecond")) {
      assertThat(prefilter(expression, false, true, MARKER)).as(expression).isEmpty();
    }
    for (String expression : List.of("^ERROR", "ERR.*", "[A-Z]+", "(?i)error", "error|")) {
      assertThat(prefilter(expression, true, true, MARKER)).as(expression).isEmpty();
    }
    var timed = args("ERROR", false, true).put("from", "-1h");
    assertThat(SshLogPrefilter.grepExpression(parse(timed), context(MARKER))).isEmpty();
  }

  @Test
  void literalMetacharactersRemainLiteralAndCaseFoldingUsesAsciiClasses() throws Exception {
    String expression = prefilter("$(touch sample); [ERROR].*", false, true, MARKER).orElseThrow();
    assertThat(grep(expression, List.of("$(touch sample); [ERROR].*", "touch sample ERROR")))
        .containsExactly("$(touch sample); [ERROR].*");
    expression = prefilter("error|connection refused", true, false, MARKER).orElseThrow();
    assertThat(grep(expression, List.of("ERROR failed", "Connection Refused", "normal event")))
        .containsExactly("ERROR failed", "Connection Refused");
  }

  @Test
  void retainsEveryLineThatMayChangeDuringNormalizationOrOversizeAccounting() throws Exception {
    String expression = prefilter("ERROR", false, true, MARKER).orElseThrow();
    List<String> special = List.of(" {\"message\":\"\\u0045RROR\"}", "control\0line", "unicode échec",
        "x".repeat(256), "ERROR request failed");
    var source = new java.util.ArrayList<>(special);
    source.add("ordinary event");
    assertThat(grep(expression, source)).containsExactlyElementsOf(special);
  }

  @Test
  void rejectsMatchesCreatedInsideOrAcrossAReplacementBoundary() throws Exception {
    for (String expression : List.of("marker", "premarker", "markerpost", "premarkerpost", "kerpost")) {
      assertThat(prefilter(expression, false, true, "marker")).as(expression).isEmpty();
    }
    assertThat(prefilter("MASKED", false, false, MARKER)).isEmpty();
    assertThat(prefilter("ERROR", false, true, MARKER)).isPresent();
  }

  @Test
  void matchingStillNeedsTheLocalMaskedFilter() throws Exception {
    var query = query("hidden-value", false, true);
    String expression = SshLogPrefilter.grepExpression(query, context(MARKER)).orElseThrow();
    assertThat(grep(expression, List.of("hidden-value", "ordinary event"))).containsExactly("hidden-value");
    assertThat(query.matches(query.parseRecord(MARKER))).isFalse();
  }

  private static Optional<String> prefilter(String text, boolean regex, boolean caseSensitive, String marker)
      throws Exception {
    return SshLogPrefilter.grepExpression(query(text, regex, caseSensitive), context(marker));
  }

  private static ExecContext context(String marker) {
    ExecContext ctx = mock(ExecContext.class);
    when(ctx.textMaskMarker()).thenReturn(Optional.of(marker));
    return ctx;
  }

  private static SshLogQuery query(String text, boolean regex, boolean caseSensitive) throws Exception {
    return parse(args(text, regex, caseSensitive));
  }

  private static ObjectNode args(String text, boolean regex, boolean caseSensitive) {
    return TestConfigs.JSON.createObjectNode().put("file", "app.log").put("query", text)
        .put("matchMode", regex ? "regex" : "literal").put("caseSensitive", caseSensitive);
  }

  private static SshLogQuery parse(ObjectNode args) throws Exception {
    var settings = new SshLogSettings(null, null, "/logs", List.of("app.log"), TestConfigs.json("{}"));
    return SshLogQuery.parse(new ToolCall("tc_filter", "ssh_logs_search", "logs", args, null), settings,
        TestCtx.devLimits(), Instant.parse("2026-10-28T14:00:00Z"));
  }

  private static List<String> grep(String expression, List<String> lines) throws Exception {
    var process = new ProcessBuilder("grep", "-aE", "-e", expression);
    process.environment().put("LC_ALL", "C");
    Process child = process.start();
    try {
      try (var input = child.getOutputStream()) {
        input.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
      }
      assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue()).isIn(0, 1);
      assertThat(new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)).isEmpty();
      return new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    } finally {
      child.destroyForcibly();
    }
  }
}
