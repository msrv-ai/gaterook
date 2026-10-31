package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** What the audit keeps of a client's words: bounded, however much the client sends with every call. */
class CallsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void aClientsSelfDescriptionIsItsNameTitleAndVersionCutShort() {
    JsonNode sent = JSON.readTree("{\"name\":\"" + "n".repeat(200) + "\",\"title\":\"Codex\",\"version\":\"1.2.3\","
        + "\"padding\":\"" + "x".repeat(10_000) + "\",\"nested\":{\"a\":[1,2,3]}}");

    JsonNode kept = Calls.clientInfo(sent);

    assertThat(kept.size()).isEqualTo(3);
    assertThat(kept.path("name").asString()).hasSize(64).endsWith("…");
    assertThat(kept.path("title").asString()).isEqualTo("Codex");
    assertThat(kept.path("version").asString()).isEqualTo("1.2.3");
    assertThat(Calls.clientInfo(JSON.readTree("\"a string\""))).isNull();
    assertThat(Calls.clientInfo(null)).isNull();
    assertThat(Calls.clientInfo(JSON.readTree("{\"name\":7}")).isEmpty()).isTrue();
  }

  @Test
  void argumentsAreKeptWholeUpToTheCapAndAsSizeHashAndPreviewBeyond() {
    ObjectNode small = JSON.createObjectNode().put("sql", "SELECT 1");
    assertThat(Calls.storedArguments(small, "abc")).isSameAs(small);

    ObjectNode big = JSON.createObjectNode().put("sql", "SELECT '" + "y".repeat(Calls.MAX_STORED_ARGUMENTS) + "'");
    String sha = CanonicalJson.sha256Hex(big);
    JsonNode kept = Calls.storedArguments(big, sha);

    assertThat(kept.size()).isEqualTo(1);
    JsonNode cut = kept.path("‹truncated›");
    assertThat(cut.path("chars").asInt()).isEqualTo(CanonicalJson.text(big).length());
    assertThat(cut.path("sha256").asString()).isEqualTo(sha);
    assertThat(cut.path("preview").asString()).hasSize(Calls.ARGUMENTS_PREVIEW + 1).startsWith("{\"sql\":\"SELECT '")
        .endsWith("…");
    assertThat(CanonicalJson.text(kept).length()).isLessThan(Calls.ARGUMENTS_PREVIEW + 256);
    assertThat(Calls.storedArguments(null, null)).isNull();
  }
}
