package ai.msrv.gaterook.core.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Results cut to the call's limits (report 01 §8.1): tables by whole rows, JSON by whole entries, text by chars. */
class ResultLimiterTest {

  static final long KB512 = 512 * 1024;

  @Test
  void aSmallTableBecomesTheOutputSchemaAndAPaddedText() {
    ToolResult r = ResultLimiter.limit(table(3, 6), limits(1_000, KB512), 38);
    ObjectNode s = (ObjectNode) r.structuredContent();
    assertThat(s.path("columns").toString()).isEqualTo("[\"id\",\"status\"]");
    assertThat(s.path("types").toString()).isEqualTo("[\"int4\",\"text\"]");
    assertThat(s.path("rowCount").asInt()).isEqualTo(3);
    assertThat(s.path("truncated").asBoolean()).isFalse();
    assertThat(s.has("truncatedReason")).isFalse();
    assertThat(s.path("elapsedMs").asLong()).isEqualTo(38);
    assertThat(s.path("masked").isArray()).isTrue();
    assertThat(r.truncated()).isFalse();
    assertThat(r.content().getFirst().text()).isEqualTo("""
        3 rows · 38 ms
        id  status
        0   xxxxxx
        1   xxxxxx
        2   xxxxxx""");
  }

  @Test
  void theRowLimitKeepsTheFirstRows() {
    ToolResult r = ResultLimiter.limit(table(150, 10), limits(100, KB512), 5);
    assertThat(r.structuredContent().path("rowCount").asInt()).isEqualTo(100);
    assertThat(r.structuredContent().path("rows").size()).isEqualTo(100);
    assertThat(r.structuredContent().path("truncatedReason").asString()).isEqualTo("row_limit");
    assertThat(r.truncatedReason()).isEqualTo("row_limit");
    assertThat(r.content().getFirst().text()).startsWith("100 rows of 150, cut by the row limit · 5 ms");
  }

  @Test
  void aTableLargerThan512KbIsCutByWholeRowsToFit() {
    ToolResult r = ResultLimiter.limit(table(5_000, 400), limits(10_000, KB512), 12);
    long size = ResultLimiter.size(r);
    assertThat(size).isLessThanOrEqualTo(KB512);
    int rows = r.structuredContent().path("rowCount").asInt();
    assertThat(rows).isBetween(1, 4_999);
    assertThat(r.structuredContent().path("rows").size()).isEqualTo(rows);
    assertThat(r.structuredContent().path("truncatedReason").asString()).isEqualTo("size_limit");
    assertThat(r.content().getFirst().text()).startsWith(rows + " rows of 5000, cut by the size limit");
    // The cut is the largest that fits: one more row (about 810 bytes in rows and text) would not have.
    assertThat(size).isGreaterThan(KB512 - 1_700);
  }

  @Test
  void longCellsAreCutInTheTextButKeptInTheRows() {
    ToolResult r = ResultLimiter.limit(table(1, 500), limits(10, KB512), 1);
    String line = r.content().getFirst().text().lines().toList().get(2);
    assertThat(line).endsWith("…").hasSizeLessThan(220);
    assertThat(r.structuredContent().at("/rows/0/1").asString()).hasSize(500);
  }

  @Test
  void jsonLosesWholeEntriesOfItsLargestArray() {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("kind", "pods");
    ArrayNode small = s.putArray("warnings");
    small.add("w1");
    ArrayNode items = s.putArray("items");
    for (int i = 0; i < 4_000; i++) {
      items.addObject().put("name", "pod-" + i).put("spec", "y".repeat(200));
    }
    ToolResult r = ResultLimiter.limit(ToolResult.json(s, "4000 pods"), limits(1_000, KB512), 0);
    assertThat(ResultLimiter.size(r)).isLessThanOrEqualTo(KB512);
    assertThat(r.truncated()).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
    assertThat(r.structuredContent().path("warnings").size()).isEqualTo(1);
    int kept = r.structuredContent().path("items").size();
    assertThat(kept).isBetween(1, 3_999);
    assertThat(r.structuredContent().at("/items/" + (kept - 1) + "/name").asString()).isEqualTo("pod-" + (kept - 1));
    assertThat(r.structuredContent().path("truncated").asBoolean()).isTrue();
  }

  @Test
  void textIsCutAtACharacterBoundaryWithANote() {
    String big = "é🙂".repeat(200_000);
    ToolResult r = ResultLimiter.limit(ToolResult.text(big), limits(1_000, KB512), 0);
    assertThat(ResultLimiter.size(r)).isLessThanOrEqualTo(KB512);
    String text = r.content().getFirst().text();
    assertThat(text).endsWith("\n… cut by the size limit");
    String body = text.substring(0, text.length() - "\n… cut by the size limit".length());
    // No half of a surrogate pair, no half of a multi-byte character.
    assertThat(Character.isHighSurrogate(body.charAt(body.length() - 1))).isFalse();
    assertThat(body.codePoints().allMatch(cp -> cp == 'é' || cp == 0x1F642)).isTrue();
    assertThat(r.truncatedReason()).isEqualTo("size_limit");
  }

  @Test
  void aSmallResultIsLeftAsItIs() {
    ToolResult small = ToolResult.text("ok");
    assertThat(ResultLimiter.limit(small, limits(1_000, KB512), 0)).isSameAs(small);
  }

  @Test
  void prefixCountsUtf8Bytes() {
    assertThat(ResultLimiter.prefix("aé🙂b", 1)).isEqualTo("a");
    assertThat(ResultLimiter.prefix("aé🙂b", 2)).isEqualTo("a");
    assertThat(ResultLimiter.prefix("aé🙂b", 3)).isEqualTo("aé");
    assertThat(ResultLimiter.prefix("aé🙂b", 6)).isEqualTo("aé");
    assertThat(ResultLimiter.prefix("aé🙂b", 7)).isEqualTo("aé🙂");
    assertThat(ResultLimiter.prefix("aé🙂b", 100)).isEqualTo("aé🙂b");
  }

  @Test
  void theHashAndPreviewAreOfWhatTheAgentGets() {
    ToolResult a = ResultLimiter.limit(table(3, 6), limits(1_000, KB512), 38);
    ToolResult b = ResultLimiter.limit(table(3, 6), limits(1_000, KB512), 38);
    assertThat(ResultLimiter.sha256(a)).isEqualTo(ResultLimiter.sha256(b)).hasSize(64);
    assertThat(ResultLimiter.preview(a, 14)).isEqualTo("3 rows · 38 ms");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  static ToolResult table(int rows, int cellChars) {
    ArrayNode r = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < rows; i++) {
      r.addArray().add(i).add("x".repeat(cellChars));
    }
    return ToolResult.table(new ToolResult.Table(List.of(new ToolResult.Column("id", "int4", null, null, null),
        new ToolResult.Column("status", "text", null, null, null)), r, null), null);
  }

  static Limits limits(int maxRows, long maxBytes) {
    return new Limits(maxRows, 1_000, maxBytes, 1 << 20, 256 * 1024, 64 * 1024, 8_192, Duration.ofSeconds(5),
        Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));
  }
}
