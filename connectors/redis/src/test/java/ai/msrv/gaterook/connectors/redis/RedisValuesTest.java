package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Values as the agent sees them: text only when it is text, cuts on character boundaries, one spelling per byte. */
class RedisValuesTest {

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void textIsTextAndEverythingElseIsBase64() {
    assertThat(RedisValues.value(utf8("élan 🚀")).asString()).isEqualTo("élan 🚀");
    JsonNode bin = RedisValues.value(new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0x41});
    assertThat(bin.path("$base64").asString()).isEqualTo("//4AQQ==");
    // A strict decoder: an overlong encoding or a lone surrogate is not text.
    assertThat(RedisValues.utf8(new byte[] {(byte) 0xc0, (byte) 0xaf})).isEmpty();
    assertThat(RedisValues.utf8(new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80})).isEmpty();
  }

  @Test
  void aHashFieldThatIsNotTextIsMarked() {
    assertThat(RedisValues.field(utf8("name"))).isEqualTo("name");
    assertThat(RedisValues.field(new byte[] {(byte) 0xff})).isEqualTo("base64:/w==");
  }

  @Test
  void aCutNeverSplitsACharacter() {
    // "aé" is 61 C3 A9: two bytes would end inside é.
    RedisValues.Cut c = RedisValues.cut(utf8("aé"), 2);
    assertThat(new String(c.head(), StandardCharsets.UTF_8)).isEqualTo("a");
    assertThat(c.omitted()).isEqualTo(2);
    // A four-byte character cut after one, two or three of its bytes.
    for (int max = 2; max <= 4; max++) {
      RedisValues.Cut r = RedisValues.cut(utf8("x🚀y"), max);
      assertThat(new String(r.head(), StandardCharsets.UTF_8)).as("max %d", max).isEqualTo("x");
    }
    assertThat(RedisValues.cut(utf8("abc"), 3)).isNull();
  }

  @Test
  void binaryIsCutToTheByte() {
    byte[] continuation = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80};
    RedisValues.Cut c = RedisValues.cut(continuation, 3);
    assertThat(c.head()).hasSize(3);
    assertThat(c.omitted()).isEqualTo(2);
    RedisValues.Cut ff = RedisValues.cut(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff}, 2);
    assertThat(ff.head()).hasSize(2);
  }

  @Test
  void aLongElementCarriesAMarker() {
    boolean[] cut = new boolean[1];
    JsonNode text = RedisValues.element(utf8("x".repeat(5_000)), 1_000, cut);
    assertThat(cut[0]).isTrue();
    assertThat(text.asString()).startsWith("x".repeat(1_000)).endsWith("…‹truncated 4,000 bytes›");
    boolean[] cut2 = new boolean[1];
    JsonNode bin = RedisValues.element(new byte[] {(byte) 0xff, 1, 2, 3}, 2, cut2);
    assertThat(cut2[0]).isTrue();
    assertThat(bin.path("$base64").asString()).isEqualTo("/wE=");
    assertThat(bin.path("truncatedBytes").asLong()).isEqualTo(2);
    boolean[] none = new boolean[1];
    assertThat(RedisValues.element(utf8("ok"), 10, none).asString()).isEqualTo("ok");
    assertThat(none[0]).isFalse();
  }

  @Test
  void quotingSpellsOutEveryByteThatIsNotPrintableAscii() {
    assertThat(RedisValues.quote(utf8("cache:1"))).isEqualTo("\"cache:1\"");
    assertThat(RedisValues.quote(utf8("a\"b\\c"))).isEqualTo("\"a\\\"b\\\\c\"");
    assertThat(RedisValues.quote(new byte[] {'\n', '\r', '\t', 7, 8, 0, 0x7f})).isEqualTo(
        "\"\\n\\r\\t\\a\\b\\x00\\x7f\"");
    // Cyrillic U+0430 looks like a Latin "a" but is spelled out.
    assertThat(RedisValues.quote("cаche")).isEqualTo("\"c\\xd0\\xb0che\"");
    // Invisible characters too.
    assertThat(RedisValues.quote("a​b")).isEqualTo("\"a\\xe2\\x80\\x8bb\"");
  }
}
