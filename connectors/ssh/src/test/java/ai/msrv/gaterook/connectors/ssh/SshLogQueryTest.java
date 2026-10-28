package ai.msrv.gaterook.connectors.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolCall;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SshLogQueryTest {
  private static final Instant NOW = Instant.parse("2026-10-28T14:00:00Z");

  private SshLogSettings settings() {
    return new SshLogSettings(null, null, "/logs", List.of("app.log", "nested/worker.log"), TestConfigs.json("{}"));
  }

  private SshLogQuery query(String args) throws ConnectorException {
    return query("ssh_logs_search", args, TestCtx.devLimits());
  }

  private SshLogQuery query(String tool, String args, Limits limits) throws ConnectorException {
    return SshLogQuery.parse(new ToolCall("tc_query", tool, "logs", (ObjectNode) TestConfigs.json(args), null),
        settings(), limits, NOW);
  }

  @Test
  void defaultsToAllSelectedFilesAndPreservesConfiguredOrder() throws Exception {
    assertThat(query("{}").files()).containsExactly("app.log", "nested/worker.log");
    assertThat(query("{\"files\":[\"nested/worker.log\",\"app.log\"]}").files())
        .containsExactly("app.log", "nested/worker.log");
    assertThat(query("{}").scanBytes()).isEqualTo(64 * 1024 * 1024);
    assertThat(query("{}").timeFiltered()).isFalse();
  }

  @Test
  void rejectsUnselectedFilesAndAmbiguousSelectorsBeforeIo() {
    for (String args : new String[] {"{\"file\":\"private.log\"}", "{\"file\":\"../app.log\"}",
        "{\"file\":\"app.log\",\"files\":[\"app.log\"]}", "{\"files\":[]}",
        "{\"files\":[\"app.log\",\"app.log\"]}", "{\"files\":[1]}"}) {
      assertThatThrownBy(() -> query(args)).as(args).isInstanceOf(ConnectorException.class);
    }
    assertThatThrownBy(() -> query("ssh_logs_read", "{}", TestCtx.devLimits())).isInstanceOf(ConnectorException.class);
  }

  @Test
  void matchesLiteralTextAndCaseInsensitiveTextWithoutInterpretingMetacharacters() throws Exception {
    var literal = query("{\"query\":\".*\"}");
    assertThat(literal.matches(literal.parseRecord("literal .* marker"))).isTrue();
    assertThat(literal.matches(literal.parseRecord("other text"))).isFalse();
    var folded = query("{\"query\":\"error\",\"caseSensitive\":false}");
    assertThat(folded.matches(folded.parseRecord("ERROR request failed"))).isTrue();
    assertThat(query("{\"query\":\"\"}").matches(folded.parseRecord("anything"))).isTrue();
  }

  @Test
  void supportsLinearTimeRegexAndRejectsBackreferencesAndLookaround() throws Exception {
    var expression = query("{\"query\":\"error [0-9]+\",\"matchMode\":\"regex\",\"caseSensitive\":false}");
    assertThat(expression.matches(expression.parseRecord("service ERROR 503 retry"))).isTrue();
    for (String expressionText : new String[] {"(?=error)", "(a)\\1", "["}) {
      var args = TestConfigs.JSON.createObjectNode().put("query", expressionText).put("matchMode", "regex");
      assertThatThrownBy(() -> query(args.toString())).isInstanceOf(ConnectorException.class);
    }
    var safe = query("{\"query\":\"(a+)+$\",\"matchMode\":\"regex\"}");
    assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
        assertThat(safe.matches(safe.parseRecord("a".repeat(200_000) + "!"))).isFalse());
  }

  @Test
  void resolvesRelativeTimesOnceAndUsesAHalfOpenRange() throws Exception {
    var query = query("{\"from\":\"-1h\",\"to\":\"now\"}");
    assertThat(query.from()).isEqualTo(Instant.parse("2026-10-28T13:00:00Z"));
    assertThat(query.to()).isEqualTo(NOW);
    assertThat(query.matches(query.parseRecord("2026-10-28T13:00:00Z INFO included"))).isTrue();
    assertThat(query.matches(query.parseRecord("2026-10-28T14:00:00Z INFO excluded"))).isFalse();
    assertThat(query.matches(query.parseRecord("2026-10-28T12:59:59Z INFO older"))).isFalse();
  }

  @Test
  void suppliesOneSidedTimeBoundsAndRejectsExcessiveOrAmbiguousRanges() throws Exception {
    assertThat(query("{\"from\":\"-1h\"}").to()).isEqualTo(NOW);
    var before = query("{\"to\":\"-1h\"}");
    assertThat(Duration.between(before.from(), before.to())).isEqualTo(TestCtx.devLimits().maxRange());
    for (String args : new String[] {"{\"from\":\"-8d\"}", "{\"from\":\"now\",\"to\":\"-1h\"}",
        "{\"from\":\"2026-10-28T12:00:00\"}", "{\"from\":\"1700000000\"}", "{\"from\":\"\"}"}) {
      assertThatThrownBy(() -> query(args)).as(args).isInstanceOf(ConnectorException.class);
    }
  }

  @Test
  void excludesMissingInvalidOrMaskedTimestampsOnlyWhenFilteringByTime() throws Exception {
    var timed = query("{\"from\":\"-1h\"}");
    for (String line : new String[] {"at example.Service.run(Service.java:42)", "{\"timestamp\":\"[masked]\"}",
        "{\"timestamp\":\"bad\"}", "{broken"}) {
      assertThat(timed.matches(timed.parseRecord(line))).as(line).isFalse();
      var all = query("{}");
      assertThat(all.matches(all.parseRecord(line))).isTrue();
    }
  }

  @Test
  void combinesLevelsWithTextAndTimeAndNormalizesAliases() throws Exception {
    var query = query("{\"query\":\"failed\",\"levels\":[\"warning\",\"severe\"],\"from\":\"-1h\"}");
    assertThat(query.matches(query.parseRecord("2026-10-28T13:30:00Z WARN failed request"))).isTrue();
    assertThat(query.matches(query.parseRecord("{\"time\":\"2026-10-28T13:30:00Z\",\"level\":\"ERROR\",\"message\":\"failed\"}"))).isTrue();
    assertThat(query.matches(query.parseRecord("2026-10-28T13:30:00Z INFO failed request"))).isFalse();
    assertThat(query.matches(query.parseRecord("2026-10-28T13:30:00Z ERROR success"))).isFalse();
    assertThatThrownBy(() -> query("{\"levels\":[\"urgent-ish\"]}")).isInstanceOf(ConnectorException.class);
  }

  @Test
  void levelFiltersExcludeMissingAndUnrecognizedLevelsWithoutThrowing() throws Exception {
    var query = query("{\"levels\":[\"ERROR\"]}");
    for (String line : new String[] {"plain message", "{\"message\":\"plain\"}", "{\"level\":\"custom\"}"}) {
      assertThat(query.matches(query.parseRecord(line))).as(line).isFalse();
    }
  }

  @Test
  void validatesScanBoundsTypesAndCursorOffsetCompatibility() {
    for (String args : new String[] {"{\"scanBytes\":0}", "{\"scanBytes\":67108865}", "{\"maxLines\":0}",
        "{\"caseSensitive\":\"true\"}", "{\"offset\":-1}", "{\"file\":\"app.log\",\"offset\":2,\"cursor\":\"handle\"}",
        "{\"file\":\"app.log\",\"offset\":2,\"tail\":true}", "{\"offset\":2}", "{\"matchMode\":\"wildcard\"}"}) {
      assertThatThrownBy(() -> query(args)).as(args).isInstanceOf(ConnectorException.class);
    }
  }

  @Test
  void acceptsInclusiveLineRangesAndOneSidedBoundsOnOneFile() throws Exception {
    assertThat(query("{}").startLine()).isZero();
    assertThat(query("{}").endLine()).isZero();
    var bounded = query("{\"file\":\"app.log\",\"startLine\":20,\"endLine\":40,\"maxLines\":5}");
    assertThat(bounded.startLine()).isEqualTo(20);
    assertThat(bounded.endLine()).isEqualTo(40);
    assertThat(bounded.maxLines()).isEqualTo(5);
    var first = query("ssh_logs_read", "{\"file\":\"app.log\",\"endLine\":10}", TestCtx.devLimits());
    assertThat(first.startLine()).isEqualTo(1);
    assertThat(first.endLine()).isEqualTo(10);
    var onward = query("{\"file\":\"app.log\",\"startLine\":20}");
    assertThat(onward.startLine()).isEqualTo(20);
    assertThat(onward.endLine()).isZero();
    assertThat(query("{\"files\":[\"app.log\"],\"startLine\":1,\"endLine\":1}").endLine()).isEqualTo(1);
  }

  @Test
  void rejectsInvalidLineRangesBeforeIo() {
    for (String fields : new String[] {"\"startLine\":0", "\"endLine\":0", "\"startLine\":-1",
        "\"startLine\":1.5", "\"startLine\":\"1\"", "\"startLine\":null",
        "\"endLine\":9007199254740992", "\"startLine\":20,\"endLine\":10",
        "\"startLine\":1,\"tail\":true", "\"endLine\":10,\"offset\":1"}) {
      String args = "{\"file\":\"app.log\"," + fields + "}";
      assertThatThrownBy(() -> query(args)).as(args).isInstanceOf(ConnectorException.class);
    }
    assertThatThrownBy(() -> query("{\"startLine\":1}"))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("one forward file");
  }

  @Test
  void lineRangeCursorHashBindsEffectiveRangeAndAllowsContinuation() throws Exception {
    String range = query("{\"file\":\"app.log\",\"startLine\":1,\"endLine\":10}").queryHash();
    assertThat(query("{\"file\":\"app.log\",\"endLine\":10,\"cursor\":\"handle\"}").queryHash()).isEqualTo(range);
    assertThat(query("{\"file\":\"app.log\",\"startLine\":2,\"endLine\":10}").queryHash()).isNotEqualTo(range);
    assertThat(query("{\"file\":\"app.log\",\"startLine\":1,\"endLine\":11}").queryHash()).isNotEqualTo(range);
    assertThat(query("{\"file\":\"app.log\"}").queryHash()).isNotEqualTo(range);
  }

  @Test
  void cursorHashBindsEffectiveFiltersAndAnchorButNotContinuationOffset() throws Exception {
    String base = query("{\"file\":\"app.log\",\"from\":\"-1h\"}").queryHash();
    assertThat(query("{\"file\":\"app.log\",\"from\":\"-1h\",\"cursor\":\"handle\"}").queryHash()).isEqualTo(base);
    assertThat(query("{\"file\":\"app.log\",\"from\":\"-1h\",\"offset\":20}").queryHash()).isEqualTo(base);
    assertThat(query("{\"file\":\"app.log\",\"from\":\"-2h\"}").queryHash()).isNotEqualTo(base);
    assertThat(query("{\"file\":\"app.log\",\"from\":\"-1h\",\"levels\":[\"ERROR\"]}").queryHash()).isNotEqualTo(base);
  }
}
