package com.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.core.platform.Caller;
import com.gaterook.core.platform.CanonicalJson;
import com.gaterook.core.platform.Ingress;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the audit keeps of a client's words: bounded, however much the client sends with every call, and never taken
 * for who asked.
 */
class CallsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Caller caller(Caller.Kind kind, String label, String hint) {
    return new Caller(kind, "pr_1", label, hint, Ingress.LOCAL, "127.0.0.1");
  }

  private static JsonNode says(String name) {
    return JSON.createObjectNode().put("name", name);
  }

  @Test
  void aCredentialNamesItsClientAndAClientsOwnNameNeverOverridesIt() {
    assertThat(Calls.client(caller(Caller.Kind.BRIDGE, "codex-laptop", "codex"), says("claude-desktop")))
        .as("a bridge grant was made for one client").isEqualTo("codex");
    assertThat(Calls.client(caller(Caller.Kind.OAUTH, "claude.ai", "claude-ai"), says("codex"))).isEqualTo("claude-ai");
    assertThat(Calls.client(caller(Caller.Kind.PLAYGROUND, "playground", "playground"), says("codex")))
        .isEqualTo("playground");
  }

  @Test
  void onlyANamedTokensFamilyIsGuessedFromTheClientsOwnNameAndItStaysReported() {
    Caller named = caller(Caller.Kind.LOCAL_TOKEN, "ci-runner", null);
    // Kept for Activity's grouping, but only as what the client reports about itself.
    assertThat(Calls.client(named, says("claude-desktop-mcp"))).isEqualTo("claude-desktop");
    assertThat(Caller.familyReported(named.kind())).isTrue();
    assertThat(Calls.client(named, null)).isNull();
    // An OAuth sign-in GateRook doesn't recognise implies no family, whatever its client calls itself.
    assertThat(Calls.client(caller(Caller.Kind.OAUTH, "Some client", null), says("codex"))).isNull();
    assertThat(Caller.familyReported(Caller.Kind.OAUTH)).isFalse();
    assertThat(Caller.familyReported(Caller.Kind.BRIDGE)).isFalse();
    assertThat(Caller.familyReported(Caller.Kind.PLAYGROUND)).isFalse();
  }

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
