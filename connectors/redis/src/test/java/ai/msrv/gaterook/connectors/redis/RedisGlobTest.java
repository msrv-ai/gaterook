package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The matcher behaves like the server's {@code stringmatchlen} on hand-picked cases; {@code RedisGlobPropertyIT}
 * compares it with a real {@code SCAN MATCH} on random keys and patterns.
 */
class RedisGlobTest {

  @ParameterizedTest(name = "{0} ~ {1} = {2}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      *            | ``                | true
      *            | abc               | true
      a*           | a                 | true
      a*           | ab                | true
      a*           | ba                | false
      ?            | a                 | true
      ?            | ``                | false
      ??           | a                 | false
      h?llo        | hello             | true
      h*llo        | heeeello          | true
      h*llo        | hello world       | false
      h[ae]llo     | hallo             | true
      h[ae]llo     | hillo             | false
      h[^e]llo     | hallo             | true
      h[^e]llo     | hello             | false
      h[a-b]llo    | hbllo             | true
      h[b-a]llo    | hallo             | true
      h[a-b]llo    | hcllo             | false
      \\*          | *                 | true
      \\*          | a                 | false
      [\\]]        | ]                 | true
      [abc         | a                 | true
      [abc         | ab                | false
      [            | [                 | false
      [^           | x                 | true
      a\\          | a\\               | true
      cache:*      | cache:users:7     | true
      cache:*      | session:1         | false
      *token*      | api:token:1       | true
      *token*      | api:tokn:1        | false
      order:[0-9]* | order:42          | true
      order:[0-9]* | order:x2          | false
      user:?:profile | user:7:profile  | true
      user:?:profile | user:17:profile | false
      **           | x                 | true
      a**b         | ab                | true
      a*b*c        | axxbyyc           | true
      a*b*c        | axxbyy            | false
      """)
  void matchesLikeTheServer(String pattern, String key, boolean expected) {
    assertThat(RedisGlob.of(pattern).matches(key)).isEqualTo(expected);
  }

  @Test
  void questionMarkIsOneByteNotOneCharacter() {
    assertThat(RedisGlob.of("?").matches("é")).isFalse();
    assertThat(RedisGlob.of("??").matches("é")).isTrue();
  }

  @Test
  void rangesCompareBytesAsTheServersPlatformComparesCChars() {
    // "[\u0080-z]" is the bytes [ C2 80 - z ]: the range starts at 0x80, a negative C char on x86-64, so it covers
    // 'a'; compared unsigned (arm64 Linux), the ends swap to [z..0x80] and 'a' is outside. The property IT checks
    // this pattern against the server, whichever platform it runs on.
    byte[] a = "a".getBytes(StandardCharsets.UTF_8);
    assertThat(RedisGlob.of("[\u0080-z]").matches(a, true)).isTrue();
    assertThat(RedisGlob.of("[\u0080-z]").matches(a, false)).isFalse();
    assertThat(RedisGlob.of("[\u0080-z]").matches(new byte[] {'|'}, false)).isTrue();
    assertThat(RedisGlob.of("[\u0080-z]").matches("a")).as("x86-64 by default").isTrue();
    byte[] key = "é".getBytes(StandardCharsets.UTF_8);
    assertThat(RedisGlob.of("[a-z]?").matches(key, true)).isFalse();
    assertThat(RedisGlob.of("[a-z]?").matches(key, false)).isFalse();
    // Both ends from 0x80 on, or both below: one meaning on every platform.
    assertThat(RedisGlob.of("[\u0080-\u00ff]*").matches(key, true)).isTrue();
    assertThat(RedisGlob.of("[\u0080-\u00ff]*").matches(key, false)).isTrue();
  }

  @Test
  void onlyARangeAcross0x80DependsOnTheSign() {
    assertThat(RedisGlob.of("[\u0080-z]*").dependsOnSign()).isTrue();
    assertThat(RedisGlob.of("cache:[a-\u00ff]").dependsOnSign()).isTrue();
    assertThat(RedisGlob.of("[^z-\u0080]").dependsOnSign()).isTrue();
    assertThat(RedisGlob.of("cache:*").dependsOnSign()).isFalse();
    assertThat(RedisGlob.of("[a-z]é*").dependsOnSign()).isFalse();
    assertThat(RedisGlob.of("[\u0080-\u00ff]").dependsOnSign()).as("both ends high").isFalse();
    assertThat(RedisGlob.of("\\[\u0080-z]").dependsOnSign()).as("an escaped [ opens no class").isFalse();
    assertThat(RedisGlob.of("[a\\-\u0080]").dependsOnSign()).as("an escaped - makes no range").isFalse();
    assertThat(RedisGlob.of("[a-z").dependsOnSign()).isFalse();
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(delimiter = '|', textBlock = """
      Linux 7.0.14-linuxkit x86_64          | SIGNED
      Linux 5.15.0-1034-aws aarch64         | UNSIGNED
      Linux 6.1.0 armv7l                    | UNSIGNED
      Linux 6.8.0-45-generic ppc64le        | UNSIGNED
      Linux 5.14.0 s390x                    | UNSIGNED
      Darwin 23.4.0 arm64                   | SIGNED
      Darwin 23.4.0 x86_64                  | SIGNED
      FreeBSD 14.1-RELEASE arm64            | UNSIGNED
      FreeBSD 14.1-RELEASE amd64            | SIGNED
      Windows                               | SIGNED
      Linux 6.1.0 mips64                    | UNKNOWN
      ''                                    | UNKNOWN
      """)
  void thePlatformOfInfoServerSaysTheSignOfChar(String os, RedisGlob.Chars expected) {
    assertThat(RedisGlob.Chars.ofPlatform(os)).isEqualTo(expected);
  }

  @Test
  void manyStarsAgainstALongKeyStayFast() {
    String key = "a".repeat(5_000);
    long t0 = System.nanoTime();
    assertThat(RedisGlob.of("*a*a*a*a*a*a*a*a*a*a*b").matches(key)).isFalse();
    assertThat((System.nanoTime() - t0) / 1_000_000).as("ms").isLessThan(2_000);
  }
}
