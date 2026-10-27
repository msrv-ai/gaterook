package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Env;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The connection form as the connector reads it: defaults, ranges, key lists, TLS, and texts that say what to fix. */
class RedisSettingsTest {

  private static RedisSettings parse(Configs.Cfg c) throws RedisSettings.InvalidSettingsException {
    return RedisSettings.parse(c.build());
  }

  @Test
  void defaultsAreTheKits() throws Exception {
    RedisSettings s = parse(Configs.redis("cache.dev.internal", 6379));
    assertThat(s.database()).isZero();
    assertThat(s.tls()).isFalse();
    assertThat(s.verifyHost()).isTrue();
    assertThat(s.user()).isNull();
    assertThat(s.userOrDefault()).isEqualTo("default");
    assertThat(s.commandTimeoutMs()).isEqualTo(2_000);
    assertThat(s.connectTimeoutMs()).isEqualTo(2_000);
    assertThat(s.maxValueBytes()).isEqualTo(64 * 1024);
    assertThat(s.poolSize()).isEqualTo(2);
    assertThat(s.noTouch()).isTrue();
    assertThat(s.hostPort()).isEqualTo("cache.dev.internal:6379");
  }

  @Test
  void tlsIsOnByDefaultOnProdOnly() throws Exception {
    assertThat(parse(Configs.redis("cache", 6379).env(Env.PROD)).tls()).isTrue();
    assertThat(parse(Configs.redis("cache", 6379).env(Env.STAGE)).tls()).isFalse();
    assertThat(parse(Configs.redis("cache", 6379).env(Env.PROD).tls(false, null, null)).tls()).isFalse();
  }

  @Test
  void caOnlyVerificationIsNeverUsedOnProd() throws Exception {
    assertThat(parse(Configs.redis("cache", 6380).tls(true, "ca", null)).verifyHost()).isFalse();
    // The core refuses to save it (x-forbidden-env-values); the connector doesn't trust that alone.
    assertThatThrownBy(() -> parse(Configs.redis("cache", 6380).env(Env.PROD).tls(true, "ca", null)))
        .hasMessage("TLS verification can't be ca on prod.");
    assertThatThrownBy(() -> parse(Configs.redis("cache", 6380).tls(true, "none", null)))
        .hasMessage("TLS verification is full or ca.");
  }

  @Test
  void anIpv6HostIsBracketed() throws Exception {
    assertThat(parse(Configs.redis("fd00::12", 6379)).hostPort()).isEqualTo("[fd00::12]:6379");
  }

  @Test
  void hostsThatAreNotJustAHostAreRefused() {
    for (String host : new String[] {"", " ", "redis://cache", "user@cache", "cache/0", "ca che"}) {
      assertThatThrownBy(() -> parse(Configs.redis(host, 6379))).as(host)
          .isInstanceOf(RedisSettings.InvalidSettingsException.class);
    }
  }

  @Test
  void theCloudMetadataAddressIsRefusedInTheForm() {
    assertThatThrownBy(() -> parse(Configs.redis("169.254.169.254", 6379)))
        .isInstanceOf(RedisSettings.InvalidSettingsException.class);
  }

  @Test
  void rangesSayTheirBounds() {
    assertThatThrownBy(() -> parse(Configs.redis("cache", 6379).advanced("poolSize", 11)))
        .hasMessage("The pool size is a whole number from 1 to 10.");
    assertThatThrownBy(() -> parse(Configs.redis("cache", 6379).advanced("commandTimeoutMs", 50)))
        .hasMessage("The command timeout is a whole number from 200 to 30000.");
    assertThatThrownBy(() -> parse(Configs.redis("cache", 6379).database(1_024)))
        .hasMessage("The database is a whole number from 0 to 1023.");
  }

  @Test
  void keyListsAreRedisGlobs() throws Exception {
    RedisSettings s = parse(Configs.redis("cache", 6379).advanced("keyAllowlist", new String[] {"cache:*", "jobs:*"})
        .advanced("keyDenylist", new String[] {"cache:secret:*"}));
    assertThat(s.keyAllowed("cache:a".getBytes(StandardCharsets.UTF_8))).isTrue();
    assertThat(s.keyAllowed("jobs:1".getBytes(StandardCharsets.UTF_8))).isTrue();
    assertThat(s.keyAllowed("session:1".getBytes(StandardCharsets.UTF_8))).isFalse();
    assertThat(s.whyNotAllowed("session:1".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("it matches none of \"Keys the agent may read\"");
    assertThat(s.keyAllowed("cache:secret:x".getBytes(StandardCharsets.UTF_8))).isFalse();
    assertThat(s.whyNotAllowed("cache:secret:x".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("it matches a pattern of \"Keys the agent never reads\"");
    // No allowlist: everything but the denylist.
    RedisSettings open = parse(Configs.redis("cache", 6379).advanced("keyDenylist", new String[] {"*token*"}));
    assertThat(open.keyAllowed("session:1".getBytes(StandardCharsets.UTF_8))).isTrue();
    assertThat(open.keyAllowed("api:token:1".getBytes(StandardCharsets.UTF_8))).isFalse();
  }

  @Test
  void keyListsFollowHowTheServerComparesRangeBytes() throws Exception {
    // [a-é] is the bytes [ a - C3 A9 ]: the range a..0xC3 crosses 0x80. On x86-64 (signed chars) 0xC3 is -61, the ends
    // swap and the range holds 0x00..a (so "!") but not "~"; on arm64 Linux (unsigned) it is a..0xC3, "~" but not
    // "!". Test connection writes which one the server is (metadata.globChars).
    byte[] bang = "!:1".getBytes(StandardCharsets.UTF_8);
    byte[] tilde = "~:1".getBytes(StandardCharsets.UTF_8);
    String[] lists = {"[a-é]:*"};
    for (String chars : new String[] {"signed", "unsigned", null}) {
      RedisSettings allow = parse(Configs.redis("cache", 6379).advanced("keyAllowlist", lists).globChars(chars));
      RedisSettings deny = parse(Configs.redis("cache", 6379).advanced("keyDenylist", lists).globChars(chars));
      assertThat(allow.chars()).isEqualTo(chars == null ? RedisGlob.Chars.UNKNOWN : RedisGlob.Chars.of(chars));
      switch (allow.chars()) {
        case SIGNED -> {
          assertThat(allow.keyAllowed(bang)).isTrue();
          assertThat(allow.keyAllowed(tilde)).isFalse();
          assertThat(deny.keyAllowed(bang)).isFalse();
          assertThat(deny.keyAllowed(tilde)).isTrue();
        }
        case UNSIGNED -> {
          assertThat(allow.keyAllowed(bang)).isFalse();
          assertThat(allow.keyAllowed(tilde)).isTrue();
          assertThat(deny.keyAllowed(bang)).isTrue();
          assertThat(deny.keyAllowed(tilde)).isFalse();
        }
        case UNKNOWN -> {
          // Not known yet: a key is read only if both readings allow it.
          assertThat(allow.keyAllowed(bang)).isFalse();
          assertThat(allow.keyAllowed(tilde)).isFalse();
          assertThat(deny.keyAllowed(bang)).isFalse();
          assertThat(deny.keyAllowed(tilde)).isFalse();
          assertThat(deny.whyNotAllowed(tilde)).isEqualTo("it matches a pattern of \"Keys the agent never reads\"");
        }
      }
      assertThat(allow.listsDependOnSign()).isTrue();
    }
    // Lists without such a range mean the same everywhere, known or not.
    RedisSettings plain = parse(Configs.redis("cache", 6379).advanced("keyAllowlist", new String[] {"cache:*"}));
    assertThat(plain.chars()).isEqualTo(RedisGlob.Chars.UNKNOWN);
    assertThat(plain.listsDependOnSign()).isFalse();
    assertThat(plain.keyAllowed("cache:a".getBytes(StandardCharsets.UTF_8))).isTrue();
  }

  @Test
  void aKeyListPatternWithAControlCharacterIsRefused() {
    assertThatThrownBy(() -> parse(Configs.redis("cache", 6379).advanced("keyDenylist", new String[] {"a\nb"})))
        .hasMessageStartingWith("Keys the agent never reads: each pattern is 1 to 256 characters");
    assertThatThrownBy(() -> parse(Configs.redis("cache", 6379).advanced("keyAllowlist", new String[] {""})))
        .hasMessageStartingWith("Keys the agent may read: each pattern");
  }

  @Test
  void aCaCertificateThatIsNotPemSaysWhatItShouldLookLike() {
    assertThatThrownBy(() -> parse(Configs.redis("cache", 6380).tls(true, "full", "not a certificate")))
        .hasMessage("The CA certificate isn't a PEM certificate (-----BEGIN CERTIFICATE-----).");
  }

  @Test
  void theUserIsTrimmedAndEmptyMeansDefault() throws Exception {
    assertThat(parse(Configs.redis("cache", 6379).user("  reader ")).user()).isEqualTo("reader");
    assertThat(parse(Configs.redis("cache", 6379).user("")).userOrDefault()).isEqualTo("default");
  }
}
