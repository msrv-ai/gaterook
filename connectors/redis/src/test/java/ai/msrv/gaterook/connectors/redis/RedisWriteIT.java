package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import tools.jackson.databind.JsonNode;

/** Real Redis writes, including server permissions, exact approved payloads and uncertain connection failures. */
class RedisWriteIT {

  private static LocalRedis redis;
  private final RedisConnector connector = new RedisConnector();

  @BeforeAll
  static void start() {
    redis = LocalRedis.start("writes", LocalRedis.Options.of(LocalRedis.localBinary()));
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

  private ConnectionConfig cfg() {
    return Configs.local(redis).user("rw").build();
  }

  private JsonNode write(ConnectionConfig cfg, String tool, String arguments) throws ConnectorException {
    var call = Configs.call(tool, arguments);
    var preview = connector.describeAction(call, cfg);
    var result = connector.execute(call, cfg, TestSecrets.password("rw-pass"), TestCtx.of().approved(preview.payload()));
    assertThat(result.isError()).isFalse();
    return result.structuredContent();
  }

  @Test
  void setOverwritesWithAtomicConditionsAndExpirationOptions() throws Exception {
    var c = cfg();
    String key = "write:string";
    try (Jedis j = redis.admin()) {
      j.del(key);
      assertThat(write(c, "redis_set", "{\"key\":\"write:string\",\"value\":\"first\",\"condition\":\"XX\"}")
          .path("applied").asBoolean()).isFalse();
      assertThat(j.exists(key)).isFalse();
      assertThat(write(c, "redis_set", "{\"key\":\"write:string\",\"value\":\"first\",\"condition\":\"NX\",\"ttlMs\":60000}")
          .path("applied").asBoolean()).isTrue();
      assertThat(j.pttl(key)).isBetween(1L, 60_000L);
      assertThat(write(c, "redis_set", "{\"key\":\"write:string\",\"value\":\"ignored\",\"condition\":\"NX\"}")
          .path("applied").asBoolean()).isFalse();
      assertThat(j.get(key)).isEqualTo("first");
      write(c, "redis_set", "{\"key\":\"write:string\",\"value\":\"second\",\"condition\":\"XX\",\"keepTtl\":true}");
      assertThat(j.get(key)).isEqualTo("second");
      assertThat(j.pttl(key)).isPositive();
      write(c, "redis_set", "{\"key\":\"write:string\",\"value\":\"\"}");
      assertThat(j.get(key)).isEmpty();
      assertThat(j.pttl(key)).isEqualTo(-1);
      j.del(key);
      j.rpush(key, "list item");
      write(c, "redis_set", "{\"key\":\"write:string\",\"value\":\"now a string\"}");
      assertThat(j.type(key)).isEqualTo("string");
    }
  }

  @Test
  void collectionWritesPreserveRedisSemantics() throws Exception {
    var c = cfg();
    try (Jedis j = redis.admin()) {
      j.del("write:hash", "write:list", "write:set", "write:zset");
      assertThat(write(c, "redis_hset", "{\"key\":\"write:hash\",\"fields\":{\"a\":\"1\",\"b\":\"2\"}}")
          .path("added").asLong()).isEqualTo(2);
      j.pexpire("write:hash", 60_000);
      assertThat(write(c, "redis_hset", "{\"key\":\"write:hash\",\"fields\":{\"a\":\"3\"}}")
          .path("added").asLong()).isZero();
      assertThat(j.hgetAll("write:hash")).isEqualTo(Map.of("a", "3", "b", "2"));
      assertThat(j.pttl("write:hash")).isPositive();
      assertThat(write(c, "redis_lpush", "{\"key\":\"write:list\",\"values\":[\"a\",\"b\"]}")
          .path("length").asLong()).isEqualTo(2);
      assertThat(write(c, "redis_rpush", "{\"key\":\"write:list\",\"values\":[\"c\",\"d\"]}")
          .path("length").asLong()).isEqualTo(4);
      assertThat(j.lrange("write:list", 0, -1)).containsExactly("b", "a", "c", "d");
      assertThat(write(c, "redis_sadd", "{\"key\":\"write:set\",\"values\":[\"a\",\"a\",\"b\"]}")
          .path("added").asLong()).isEqualTo(2);
      assertThat(j.smembers("write:set")).containsExactlyInAnyOrder("a", "b");
      assertThat(write(c, "redis_zadd", "{\"key\":\"write:zset\",\"members\":[{\"score\":1.5,\"value\":\"a\"},"
          + "{\"score\":-2,\"value\":\"b\"}]}").path("added").asLong()).isEqualTo(2);
      assertThat(write(c, "redis_zadd", "{\"key\":\"write:zset\",\"members\":[{\"score\":3,\"value\":\"a\"}]}")
          .path("added").asLong()).isZero();
      assertThat(j.zscore("write:zset", "a")).isEqualTo(3);
      assertThat(j.zrange("write:zset", 0, -1)).containsExactly("b", "a");
      assertThatThrownBy(() -> write(c, "redis_hset", "{\"key\":\"write:list\",\"fields\":{\"a\":\"b\"}}"))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("WRONGTYPE");
      assertThat(j.lrange("write:list", 0, -1)).containsExactly("b", "a", "c", "d");
    }
  }

  @Test
  void approvedBytesAndDatabaseAreUsedInsteadOfReplacementArguments() throws Exception {
    var c = Configs.local(redis).user("rw").databases(0, 2).build();
    byte[] key = {'b', 'i', 'n', ':', (byte) 0xff};
    String encoded = Base64.getEncoder().encodeToString(key);
    var call = Configs.call("redis_set", "{\"key\":\"" + encoded + "\",\"encoding\":\"base64\",\"database\":2,"
        + "\"value\":\"AP8=\",\"valueEncoding\":\"base64\"}");
    var payload = connector.describeAction(call, c).payload();
    connector.execute(Configs.call("redis_rpush", "{\"key\":\"write:replacement\",\"values\":[\"ignored\"],\"database\":0}"),
        c, TestSecrets.password("rw-pass"), TestCtx.of().approved(payload));
    try (Jedis j = redis.admin()) {
      assertThat(j.exists("write:replacement")).isFalse();
      assertThat(j.exists(key)).isFalse();
      j.select(2);
      assertThat(j.get(key)).containsExactly((byte) 0, (byte) 0xff);
    }
    var read = connector.execute(Configs.call("redis_get", "{\"key\":\"" + encoded
        + "\",\"encoding\":\"base64\",\"database\":2}"), c, TestSecrets.password("rw-pass"), TestCtx.of());
    OutputSchema.get(read);
    assertThat(read.structuredContent().at("/value/$base64").asString()).isEqualTo("AP8=");
  }

  @Test
  void readOnlyCredentialsAndCancellationCannotWrite() throws Exception {
    var c = Configs.local(redis).user("ro").build();
    var call = Configs.call("redis_set", "{\"key\":\"write:denied\",\"value\":\"v\"}");
    assertThatThrownBy(() -> connector.execute(call, c, TestSecrets.password("ro-pass"), TestCtx.of()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("NOPERM");
    var cancelled = TestCtx.of();
    cancelled.cancel();
    assertThatThrownBy(() -> connector.execute(call, cfg(), TestSecrets.password("rw-pass"), cancelled))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    assertThatThrownBy(() -> connector.execute(call, cfg(), TestSecrets.password("rw-pass"),
        TestCtx.of().timeout(Duration.ofMillis(-1))))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT));
    try (Jedis j = redis.admin()) {
      assertThat(j.exists("write:denied")).isFalse();
    }
  }

  @Test
  void losingAWriteReplyNeverReborrowsOrRetries() throws Exception {
    var c = Configs.local(redis).advanced("noTouch", false).build();
    var settings = RedisSettings.parse(c);
    Instant deadline = Instant.now().plusSeconds(10);
    // Warm the pool, then replace one borrowed connection with a spy that loses the response after sending.
    try (var warm = connector.clients().open(c, settings,
        Credentials.resolve(settings, TestSecrets.password(LocalRedis.DEFAULT_PASSWORD)), deadline)) {
      warm.call(Cmd.PING);
    }
    var pool = spy(connector.clients().current(c.id()));
    var borrowed = pool.borrow(deadline);
    var connection = spy(borrowed.connection());
    var request = (RedisWrite) RedisRequest.of("redis_rpush",
        TestCtx.JSON.readTree("{\"key\":\"write:once\",\"values\":[\"a\"]}"), c, settings);
    doThrow(new JedisConnectionException("reply lost")).when(connection).getOne();
    try (var session = new RedisSession(pool, new RedisClients.Borrowed(connection, true), Clock.systemUTC(), deadline)) {
      assertThatThrownBy(() -> session.write(request)).isInstanceOf(ConnectorException.class)
          .hasMessageContaining("may have been applied");
      session.abort();
    }
    verify(pool, times(1)).borrow(any());
    verify(connection, times(1)).executeCommand(org.mockito.ArgumentMatchers.<redis.clients.jedis.CommandObject<Object>>any());
  }
}
