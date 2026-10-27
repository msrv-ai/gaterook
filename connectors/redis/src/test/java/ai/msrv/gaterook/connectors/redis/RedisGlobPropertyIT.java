package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.TestResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * {@link RedisGlob} against the server's own {@code SCAN MATCH} (report 05 §8): 1,000 random keys — bytes, high bytes
 * and UTF-8 among them, the empty key too — and 200 random patterns plus the tricky ones by hand (ranges across 0x80,
 * unterminated classes, escapes, a trailing backslash). The key lists of a connection must mean exactly what the
 * server would match, or a denylist could let through what its author meant to hide.
 *
 * <p>Whatever the server's platform: ranges compare C chars, signed on x86-64 and unsigned on arm64 Linux, so the
 * matcher compares the way Test connection found the server does ({@code globChars}) — and that must be what the
 * platform in {@code INFO server} says.
 */
class RedisGlobPropertyIT {

  static final long SEED = 20_261_025L;
  private static final byte[] KEY_BYTES = {'a', 'b', 'c', 'z', ':', '-', '*', '?', '[', ']', '\\', '^', '0',
      (byte) 0x80, (byte) 0xc3, (byte) 0xa9, (byte) 0xff, 0x01};
  private static final String[] PATTERN_PARTS = {"a", "b", "c", "z", ":", "-", "*", "?", "[", "]", "^", "\\", "0",
      "é", "\u0080", "[a-c]", "[^a]", "[\u0080-z]", "[z-a]", "\\*", "\\[", "*:", "a*", "[]", "[!a]"};
  static final List<String> TRICKY = List.of("*", "**", "?", "", "\\", "a\\", "\\\\", "[", "[a", "[a-", "[^", "[^a",
      "[]]", "[]a]*", "[a-]*", "[-a]*", "[z-a]*", "[\u0080-z]*", "[\u0000-\u007f]*", "?é*", "[é]*",
      "*\\*", "[\\]]*", "[\\", "a[b", "*[*", "[!a]*", "[^a-c]*", "*?*?*", "*a*a*a*a*", "\\?", "[[]*", "[a-c-z]*",
      "*\\");

  private static LocalRedis redis;
  private static boolean signed;
  static final List<byte[]> KEYS = new ArrayList<>();

  @BeforeAll
  static void start() {
    redis = LocalRedis.start("glob", LocalRedis.Options.of(LocalRedis.localBinary()));
    signed = signedChars(redis);
    KEYS.addAll(randomKeys(new Random(SEED), 1_000));
    seed(redis, KEYS);
  }

  /**
   * Whether {@code r} compares range bytes as signed chars: what Test connection writes into the connection's metadata
   * (a read-only user, as the hint recommends, asks with {@code COMMAND LIST}) — checked against the C ABI of the
   * platform {@code INFO server} names.
   */
  static boolean signedChars(LocalRedis r) {
    RedisGlob.Chars platform;
    try (Jedis j = r.admin()) {
      platform = RedisGlob.Chars.ofPlatform(RedisProbe.infoField(j.info("server"), "os"));
    }
    assertThat(platform).as("the platform of the server").isNotEqualTo(RedisGlob.Chars.UNKNOWN);
    try (RedisConnector connector = new RedisConnector(Clock.systemUTC())) {
      TestResult t = connector.test(Configs.local(r).build(),
          TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of());
      assertThat(t.ok()).as("%s", t.error()).isTrue();
      assertThat(RedisGlob.Chars.of(t.metadataUpdate().path(RedisSettings.GLOB_CHARS).asString("")))
          .as("Test connection finds how the server compares range bytes").isEqualTo(platform);
    }
    return platform == RedisGlob.Chars.SIGNED;
  }

  @AfterAll
  static void stop() {
    if (redis != null) {
      redis.close();
    }
  }

  static List<byte[]> randomKeys(Random random, int n) {
    Set<String> seen = new LinkedHashSet<>();
    List<byte[]> keys = new ArrayList<>();
    keys.add(new byte[0]);
    seen.add("");
    for (String fixed : List.of("abc", "a:b:c", "élan", "a*b", "a?b", "[a]", "a\\b", "\\", "]", "-", "^a")) {
      byte[] k = fixed.getBytes(StandardCharsets.UTF_8);
      if (seen.add(HexFormat.of().formatHex(k))) {
        keys.add(k);
      }
    }
    while (keys.size() < n) {
      byte[] k = new byte[1 + random.nextInt(8)];
      for (int i = 0; i < k.length; i++) {
        k[i] = KEY_BYTES[random.nextInt(KEY_BYTES.length)];
      }
      if (seen.add(HexFormat.of().formatHex(k))) {
        keys.add(k);
      }
    }
    return keys;
  }

  /**
   * Half free-form (pieces of glob syntax strung together), half made from a key of {@code keys} — some bytes kept,
   * some turned into {@code ?}, {@code *}, a class or an escape — so that most patterns match some keys and miss
   * others.
   */
  static List<String> randomPatterns(Random random, int n, List<byte[]> keys) {
    List<String> patterns = new ArrayList<>();
    while (patterns.size() < n) {
      StringBuilder b = new StringBuilder();
      if (patterns.size() % 2 == 0) {
        int parts = 1 + random.nextInt(5);
        for (int i = 0; i < parts; i++) {
          b.append(PATTERN_PARTS[random.nextInt(PATTERN_PARTS.length)]);
        }
      } else {
        byte[] key = keys.get(random.nextInt(keys.size()));
        for (byte k : key) {
          int c = k & 0xff;
          switch (random.nextInt(8)) {
            case 0 -> b.append('?');
            case 1 -> b.append('*');
            // A range from 0x80 to z: holds a high byte where chars are signed (x86-64), not where they are
            // unsigned (arm64 Linux) — the case that tells the two apart.
            case 2 -> b.append(c < 0x80 ? "[" + (char) c + "x]" : "[\u0080-z]");
            case 3 -> b.append(c < 0x80 ? "\\" + (char) c : "?");
            default -> b.append(c < 0x80 ? Character.toString(c) : "?");
          }
        }
      }
      patterns.add(b.toString());
    }
    return patterns;
  }

  static void seed(LocalRedis r, List<byte[]> keys) {
    try (Jedis j = r.admin()) {
      Pipeline p = j.pipelined();
      for (byte[] k : keys) {
        p.set(k, new byte[] {'v'});
      }
      p.sync();
    }
  }

  /** What {@code SCAN … MATCH pattern} returns over the whole database, as hex. */
  static Set<String> serverMatches(Jedis j, String pattern) {
    Set<String> out = new TreeSet<>();
    byte[] cursor = ScanParams.SCAN_POINTER_START_BINARY;
    ScanParams params = new ScanParams().match(pattern.getBytes(StandardCharsets.UTF_8)).count(5_000);
    do {
      ScanResult<byte[]> page = j.scan(cursor, params);
      page.getResult().forEach(k -> out.add(HexFormat.of().formatHex(k)));
      cursor = page.getCursorAsBytes();
    } while (!(cursor.length == 1 && cursor[0] == '0'));
    return out;
  }

  static Set<String> ourMatches(List<byte[]> keys, String pattern, boolean signedChars) {
    RedisGlob g = RedisGlob.of(pattern);
    Set<String> out = new TreeSet<>();
    for (byte[] k : keys) {
      if (g.matches(k, signedChars)) {
        out.add(HexFormat.of().formatHex(k));
      }
    }
    return out;
  }

  static void assertSameAsServer(LocalRedis r, boolean signedChars, List<byte[]> keys, List<String> patterns) {
    try (Jedis j = r.admin()) {
      for (String pattern : patterns) {
        assertThat(ourMatches(keys, pattern, signedChars)).as("pattern %s (UTF-8 %s, %s chars)", pattern,
            HexFormat.of().formatHex(pattern.getBytes(StandardCharsets.UTF_8)), signedChars ? "signed" : "unsigned")
            .isEqualTo(serverMatches(j, pattern));
      }
    }
  }

  @Test
  void twoHundredRandomPatternsMatchWhatScanMatches() {
    assertSameAsServer(redis, signed, KEYS, randomPatterns(new Random(SEED + 1), 200, KEYS));
  }

  @Test
  void theTrickyPatternsMatchWhatScanMatches() {
    assertSameAsServer(redis, signed, KEYS, TRICKY);
  }

  @Test
  void theSampleIsNotTrivial() {
    // Most random patterns must match something and miss something, or the comparison proves little; and the
    // patterns whose ranges cross 0x80 must match differently on the two platforms, or the sign is never tested.
    List<String> patterns = randomPatterns(new Random(SEED + 1), 200, KEYS);
    long informative = patterns.stream().map(p -> ourMatches(KEYS, p, signed).size())
        .filter(n -> n > 0 && n < KEYS.size()).count();
    assertThat(informative).isGreaterThan(100);
    List<String> all = new ArrayList<>(patterns);
    all.addAll(TRICKY);
    assertThat(all.stream().filter(p -> !ourMatches(KEYS, p, true).equals(ourMatches(KEYS, p, false))).count())
        .isGreaterThan(5);
  }

  @Test
  void aReadOnlyUserFindsTheSignWithoutInfo() {
    // INFO is @dangerous: the read-only user of the hint can't run it, but COMMAND LIST (@connection) tells the same.
    try (RedisConnector connector = new RedisConnector(Clock.systemUTC())) {
      TestResult t = connector.test(Configs.local(redis).user("ro").build(),
          TestSecrets.password("ro-pass"), TestCtx.of());
      assertThat(t.ok()).as("%s", t.error()).isTrue();
      assertThat(t.metadataUpdate().path(RedisSettings.GLOB_CHARS).asString())
          .isEqualTo(signed ? "signed" : "unsigned");
    }
  }
}
