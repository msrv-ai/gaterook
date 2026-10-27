package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.TestResult.Level;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import tools.jackson.databind.JsonNode;

/**
 * The oldest supported server, Redis 6.2.11 (report 05 §8; {@code mvn -Predis-it}): no {@code ACL DRYRUN}, no
 * {@code CLIENT NO-TOUCH}, ACL rules as lists, a read-only user that needs {@code CLIENT SETNAME} and {@code ID} by
 * name. Test connection must still tell the levels apart and give the 6.2 form of the read-only line; stream pages
 * ({@code XREVRANGE} pages), {@code SCAN TYPE}, TLS and the glob matcher must work the same.
 */
@EnabledIfSystemProperty(named = "gateway.it.redis62", matches = "true")
class Redis62IT {

  private static LocalRedis redis;
  private static LocalRedis.Certificates certs;
  private final RedisConnector connector = new RedisConnector(Clock.systemUTC());

  @BeforeAll
  static void start() {
    certs = LocalRedis.Certificates.create(Path.of("target", "redis-it", "tls62-" + ProcessHandle.current().pid())
        .toAbsolutePath());
    redis = LocalRedis.start("v62", LocalRedis.Options.of(LocalRedis.redis62()).withV62().withTls(certs));
    try (Jedis j = redis.admin()) {
      j.set("cache:str", "hello");
      j.hset("cache:hash", Map.of("a", "1"));
      for (int i = 1; i <= 50; i++) {
        j.xadd("cache:stream", XAddParams.xAddParams().id(new StreamEntryID(1_000 + i, 0)),
            Map.of("n", Integer.toString(i)));
      }
    }
  }

  @AfterAll
  static void stop() {
    if (redis != null) {
      redis.close();
    }
  }

  @AfterEach
  void close() {
    connector.close();
  }

  private JsonNode get(ConnectionConfig c, String user, String arguments) throws ConnectorException {
    return OutputSchema.get(connector.execute(Configs.call("redis_get", arguments), c, TestSecrets.password(user),
        TestCtx.of())).structuredContent();
  }

  @Test
  void theServerIsSixTwo() {
    try (Jedis j = redis.admin()) {
      assertThat(j.info("server")).contains("redis_version:6.2.11");
    }
  }

  @Test
  void aReadOnlyUserIsReadWithoutDryRun() {
    TestResult r = connector.test(Configs.local(redis).user("ro").build(), TestSecrets.password("ro-pass"),
        TestCtx.of());
    assertThat(r.ok()).as("%s", r.error()).isTrue();
    assertThat(r.serverVersion()).isEqualTo("Redis 6.2.11");
    assertThat(r.metadataUpdate().path("serverVersion").asString()).isEqualTo("Redis 6");
    assertThat(r.permissions().level()).isEqualTo(Level.READ);
    assertThat(r.permissions().details().path("writeProbe").asString()).isEqualTo("SET: NOPERM");
    assertThat(r.hints()).extracting(TestResult.Hint::code).doesNotContain("redis.user-can-write", "redis.old");
    // 6.2 has no COMMAND LIST and INFO is @dangerous: how it compares range bytes stays unknown, so the key lists
    // hold under both readings — and with a list that depends on it, the hint says so.
    assertThat(r.metadataUpdate().has(RedisSettings.GLOB_CHARS)).isFalse();
    assertThat(r.details().path(RedisSettings.GLOB_CHARS).asString()).isEqualTo("unknown");
    TestResult listed = connector.test(Configs.local(redis).user("ro").advanced("keyDenylist",
        new String[] {"[a-é]*"}).build(), TestSecrets.password("ro-pass"), TestCtx.of());
    assertThat(listed.hints()).extracting(TestResult.Hint::code).contains("redis.glob-sign");
  }

  @Test
  void theDefaultUserGetsTheSixTwoReadOnlyLine() {
    TestResult r = connector.test(Configs.local(redis).build(), TestSecrets.password(LocalRedis.DEFAULT_PASSWORD),
        TestCtx.of());
    assertThat(r.ok()).as("%s", r.error()).isTrue();
    assertThat(r.permissions().level()).isEqualTo(Level.ADMIN);
    // No ACL DRYRUN on 6.2: the SET … NX XX probe tells.
    assertThat(r.permissions().details().path("writeProbe").asString())
        .isEqualTo("SET … NX XX: syntax error after the ACL check");
    assertThat(r.details().path("snippets").path("redis.user-can-write").path("text").asString())
        .isEqualTo("ACL SETUSER gw_reader on >CHANGE-ME ~* resetchannels -@all +@read +@connection -@dangerous "
            + "+client|setname +client|id -dump -xread -lolwut");
    try (Jedis j = redis.admin()) {
      assertThat(j.keys(ReadOnlyCommandExecutor.PROBE_PREFIX + "*")).isEmpty();
    }
  }

  @Test
  void aUserThatCanWriteIsFoundBySetNxXx() {
    TestResult r = connector.test(Configs.local(redis).user("rw").build(), TestSecrets.password("rw-pass"),
        TestCtx.of());
    assertThat(r.permissions().level()).isEqualTo(Level.WRITE);
  }

  @Test
  void theReadOnlyUserReadsAndPagesAStream() throws Exception {
    ConnectionConfig c = Configs.local(redis).user("ro").build();
    assertThat(get(c, "ro-pass", "{\"key\":\"cache:str\"}").path("value").asString()).isEqualTo("hello");
    List<String> ids = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode s = get(c, "ro-pass", "{\"key\":\"cache:stream\",\"limit\":7" + (cursor == null ? ""
          : ",\"cursor\":\"" + cursor + "\"") + "}");
      s.path("value").forEach(e -> ids.add(e.path("id").asString()));
      cursor = s.path("nextCursor").isString() ? s.path("nextCursor").asString() : null;
    } while (cursor != null && ids.size() < 100);
    assertThat(ids).hasSize(50).doesNotHaveDuplicates();
    assertThat(ids.getFirst()).isEqualTo("1050-0");
    assertThat(ids.getLast()).isEqualTo("1001-0");
    // CLIENT NO-TOUCH is 7.2+: asked once, then not again for this pool.
    assertThat(connector.clients().current(c.id()).noTouchUnsupported()).isTrue();
  }

  @Test
  void scanFiltersByType() throws Exception {
    JsonNode s = OutputSchema.scan(connector.execute(Configs.call("redis_scan",
        "{\"pattern\":\"cache:*\",\"type\":\"hash\",\"count\":1000}"), Configs.local(redis).user("ro").build(),
        TestSecrets.password("ro-pass"), TestCtx.of())).structuredContent();
    List<String> names = new ArrayList<>();
    s.path("keys").forEach(k -> names.add(k.path("key").asString()));
    assertThat(names).containsExactly("cache:hash");
  }

  @Test
  void tlsWithFullVerification() {
    TestResult r = connector.test(Configs.redis("localhost", redis.tlsPort).user("ro").tls(true, "full",
        certs.caPem()).build(), TestSecrets.password("ro-pass"), TestCtx.of());
    assertThat(r.ok()).as("%s", r.error()).isTrue();
    assertThat(r.checks().getFirst().detail()).startsWith("TLS · ");
  }

  @Test
  void theGlobMatcherAgreesWithSixTwo() {
    // An administrator's Test connection reads the platform from INFO server (6.2 has no COMMAND LIST).
    boolean signed = RedisGlobPropertyIT.signedChars(redis);
    List<byte[]> keys = RedisGlobPropertyIT.randomKeys(new Random(RedisGlobPropertyIT.SEED + 62), 300);
    // Database 2 holds only the generated keys.
    try (Jedis j = redis.admin()) {
      j.select(2);
      var p = j.pipelined();
      keys.forEach(k -> p.set(k, new byte[] {'v'}));
      p.sync();
      List<String> patterns = new ArrayList<>(RedisGlobPropertyIT.randomPatterns(
          new Random(RedisGlobPropertyIT.SEED + 63), 100, keys));
      patterns.addAll(RedisGlobPropertyIT.TRICKY);
      for (String pattern : patterns) {
        assertThat(RedisGlobPropertyIT.ourMatches(keys, pattern, signed)).as("pattern %s", pattern)
            .isEqualTo(RedisGlobPropertyIT.serverMatches(j, pattern));
      }
    }
  }
}
