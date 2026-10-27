package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.BuilderFactory;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.params.XAddParams;
import tools.jackson.databind.JsonNode;

/**
 * The tools against a real redis-server (7.0.15 here; report 05 §8): every type and its pages, long and binary
 * values, SCAN with MATCH and TYPE over 5,000 keys, the connection's key lists, deadlines and cancellation, the pool —
 * and that nothing but read commands ever reached the server, by its own command statistics.
 */
class RedisIT {

  private static LocalRedis redis;
  private static final List<String> HASH_FIELDS = new ArrayList<>();
  private final RedisConnector connector = new RedisConnector(Clock.systemUTC());

  @BeforeAll
  static void start() {
    redis = LocalRedis.start("it", LocalRedis.Options.of(LocalRedis.localBinary()));
    try (Jedis j = redis.admin()) {
      j.set("cache:str", "hello", SetParams.setParams().ex(3_600));
      j.hset("cache:hash", Map.of("a", "1", "b", "2", "c", "3"));
      for (int i = 0; i < 250; i++) {
        j.rpush("cache:list", "l" + i);
      }
      for (int i = 0; i < 300; i++) {
        j.sadd("cache:set", "m" + i);
      }
      for (int i = 0; i < 150; i++) {
        j.zadd("cache:zset", i * 0.5, "z" + i);
      }
      for (int i = 1; i <= 120; i++) {
        j.xadd("cache:stream", XAddParams.xAddParams().id(new StreamEntryID(1_000 + i, i % 3)),
            Map.of("n", Integer.toString(i)));
      }
      j.set("cache:big", "x".repeat(200 * 1024));
      j.set("cache:bin".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0x41});
      j.set("cache:utf", "élan 🚀");
      Map<String, String> big = new HashMap<>();
      for (int i = 0; i < 2_000; i++) {
        HASH_FIELDS.add("f" + i);
        big.put("f" + i, "v" + i);
      }
      j.hset("cache:bighash", big);
      j.set(new byte[] {'b', 'i', 'n', ':', 0x01, (byte) 0xff}, "binary key".getBytes(StandardCharsets.UTF_8));
      j.set("session:1", "token-of-a-user");
      j.set("api:token:1", "sk_live_redis_test_value");
      Pipeline p = j.pipelined();
      for (int i = 0; i < 5_000; i++) {
        p.set("bulk:" + i, "v");
      }
      p.sync();
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

  private static Configs.Cfg cfg() {
    return Configs.local(redis);
  }

  private ToolResult get(ConnectionConfig c, String arguments) throws ConnectorException {
    return OutputSchema.get(connector.execute(Configs.call("redis_get", arguments), c,
        TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of()));
  }

  private ToolResult scan(ConnectionConfig c, String arguments) throws ConnectorException {
    return OutputSchema.scan(connector.execute(Configs.call("redis_scan", arguments), c,
        TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of()));
  }

  @Test
  void readsEveryTypeWithItsTtlAndMemory() throws Exception {
    ConnectionConfig c = cfg().build();
    JsonNode str = get(c, "{\"key\":\"cache:str\"}").structuredContent();
    assertThat(str.path("type").asString()).isEqualTo("string");
    assertThat(str.path("value").asString()).isEqualTo("hello");
    assertThat(str.path("ttlMs").asLong()).isBetween(3_500_000L, 3_600_000L);
    assertThat(str.path("persistent").asBoolean()).isFalse();
    assertThat(str.path("memoryBytes").isNumber()).isTrue();
    assertThat(str.path("length").asLong()).isEqualTo(5);

    JsonNode hash = get(c, "{\"key\":\"cache:hash\"}").structuredContent();
    // HSCAN returns fields in the server's order; the object holds them as they came.
    Map<String, String> fields = new HashMap<>();
    hash.path("value").properties().forEach(f -> fields.put(f.getKey(), f.getValue().asString()));
    assertThat(fields).isEqualTo(Map.of("a", "1", "b", "2", "c", "3"));
    assertThat(hash.path("persistent").asBoolean()).isTrue();
    assertThat(hash.path("ttlMs").isNull()).isTrue();
    assertThat(hash.path("length").asLong()).isEqualTo(3);
    assertThat(hash.path("nextCursor").isNull()).isTrue();

    JsonNode zset = get(c, "{\"key\":\"cache:zset\",\"limit\":3}").structuredContent();
    assertThat(zset.path("value").toString()).isEqualTo(
        "[{\"member\":\"z0\",\"score\":0},{\"member\":\"z1\",\"score\":0.5},{\"member\":\"z2\",\"score\":1}]");

    JsonNode stream = get(c, "{\"key\":\"cache:stream\",\"limit\":2}").structuredContent();
    assertThat(stream.path("value").toString()).isEqualTo(
        "[{\"id\":\"1120-0\",\"fields\":{\"n\":\"120\"}},{\"id\":\"1119-2\",\"fields\":{\"n\":\"119\"}}]");
    assertThat(stream.path("length").asLong()).isEqualTo(120);

    JsonNode utf = get(c, "{\"key\":\"cache:utf\"}").structuredContent();
    assertThat(utf.path("value").asString()).isEqualTo("élan 🚀");

    ToolResult text = get(c, "{\"key\":\"cache:hash\"}");
    String body = text.content().getFirst().text();
    assertThat(body).startsWith("hash \"cache:hash\" · 3 fields · persistent · ").contains("bytes in memory");
    // The value under the summary line is the structured value, in the order the server sent it.
    assertThat(body).contains("\n" + hash.path("value").toString());
  }

  @Test
  void aMissingKeyIsAnAnswerNotAnError() throws Exception {
    ToolResult r = get(cfg().build(), "{\"key\":\"cache:nothing\"}");
    assertThat(r.structuredContent().path("exists").asBoolean(true)).isFalse();
    assertThat(r.structuredContent().path("type").asString()).isEqualTo("none");
    assertThat(r.content().getFirst().text()).isEqualTo("\"cache:nothing\" doesn't exist in database 0.");
  }

  @Test
  void aLongStringIsReadByItsHeadOnly() throws Exception {
    JsonNode big = get(cfg().build(), "{\"key\":\"cache:big\"}").structuredContent();
    assertThat(big.path("value").asString()).hasSize(64 * 1024);
    assertThat(big.path("length").asLong()).isEqualTo(200 * 1024);
    assertThat(big.path("truncated").asBoolean()).isTrue();
    assertThat(big.path("notes").get(0).asString()).isEqualTo("The value is 204,800 bytes; these are the first 65,536.");

    // A smaller limit of the connection reads less.
    JsonNode small = get(cfg().advanced("maxValueKb", 1).build(), "{\"key\":\"cache:big\"}").structuredContent();
    assertThat(small.path("value").asString()).hasSize(1024);
  }

  @Test
  void bytesThatArentTextComeAsBase64AndBinaryKeysRoundTrip() throws Exception {
    ConnectionConfig c = cfg().build();
    JsonNode bin = get(c, "{\"key\":\"cache:bin\"}").structuredContent();
    assertThat(bin.path("value").toString()).isEqualTo("{\"$base64\":\"//4AQQ==\"}");

    JsonNode keys = scan(c, "{\"pattern\":\"bin:*\"}").structuredContent();
    assertThat(keys.path("keys")).hasSize(1);
    JsonNode k = keys.path("keys").get(0);
    assertThat(k.path("encoding").asString()).isEqualTo("base64");
    assertThat(Base64.getDecoder().decode(k.path("key").asString())).containsExactly('b', 'i', 'n', ':', 0x01,
        (byte) 0xff);
    JsonNode value = get(c, "{\"key\":\"" + k.path("key").asString() + "\",\"encoding\":\"base64\"}")
        .structuredContent();
    assertThat(value.path("value").asString()).isEqualTo("binary key");
    assertThat(value.path("encoding").asString()).isEqualTo("base64");
  }

  @Test
  void pagesOfAListSortedSetAndStreamFollowEachOtherExactly() throws Exception {
    ConnectionConfig c = cfg().build();
    List<String> list = pages(c, "cache:list", 40, n -> n.asString());
    assertThat(list).hasSize(250);
    for (int i = 0; i < 250; i++) {
      assertThat(list.get(i)).isEqualTo("l" + i);
    }
    List<String> zset = pages(c, "cache:zset", 33, n -> n.path("member").asString());
    assertThat(zset).hasSize(150).doesNotHaveDuplicates();
    assertThat(zset.getFirst()).isEqualTo("z0");
    assertThat(zset.getLast()).isEqualTo("z149");
    List<String> stream = pages(c, "cache:stream", 7, n -> n.path("id").asString());
    assertThat(stream).hasSize(120).doesNotHaveDuplicates();
    assertThat(stream.getFirst()).isEqualTo("1120-0");
    assertThat(stream.getLast()).isEqualTo("1001-1");
  }

  @Test
  void pagesOfAHashAndASetReachEveryElement() throws Exception {
    ConnectionConfig c = cfg().build();
    Set<String> fields = new HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode s = get(c, "{\"key\":\"cache:bighash\",\"limit\":100" + (cursor == null ? "" : ",\"cursor\":\""
          + cursor + "\"") + "}").structuredContent();
      assertThat(s.path("returned").asInt()).isLessThanOrEqualTo(200);
      s.path("value").propertyNames().forEach(fields::add);
      cursor = s.path("nextCursor").isString() ? s.path("nextCursor").asString() : null;
      if (cursor != null) {
        assertThat(cursor).startsWith("rgc.");
      }
      pages++;
    } while (cursor != null && pages < 100);
    assertThat(fields).containsExactlyInAnyOrderElementsOf(HASH_FIELDS);
    assertThat(pages).isGreaterThan(5);

    Set<String> members = new HashSet<>(pages(c, "cache:set", 50, JsonNode::asString));
    assertThat(members).hasSize(300);
  }

  private List<String> pages(ConnectionConfig c, String key, int limit,
      java.util.function.Function<JsonNode, String> item) throws ConnectorException {
    List<String> out = new ArrayList<>();
    String cursor = null;
    int guard = 0;
    do {
      JsonNode s = get(c, "{\"key\":\"" + key + "\",\"limit\":" + limit + (cursor == null ? "" : ",\"cursor\":\""
          + cursor + "\"") + "}").structuredContent();
      for (JsonNode v : s.path("value")) {
        out.add(item.apply(v));
      }
      cursor = s.path("nextCursor").isString() ? s.path("nextCursor").asString() : null;
    } while (cursor != null && guard++ < 1_000);
    return out;
  }

  @Test
  void aCursorOnlyWorksForItsKeyAndConnection() throws Exception {
    ConnectionConfig c = cfg().build();
    String cursor = get(c, "{\"key\":\"cache:list\",\"limit\":10}").structuredContent().path("nextCursor").asString();
    assertThatThrownBy(() -> connector.execute(Configs.call("redis_get", "{\"key\":\"cache:zset\",\"cursor\":\""
        + cursor + "\"}"), c, TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT);
          assertThat(e.getMessage()).isEqualTo(Cursors.OTHER_KEY);
        });
    String forged = cursor.substring(0, cursor.length() - 3) + (cursor.endsWith("AAA") ? "BBB" : "AAA");
    assertThatThrownBy(() -> connector.execute(Configs.call("redis_get", "{\"key\":\"cache:list\",\"cursor\":\""
        + forged + "\"}"), c, TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of()))
        .hasMessageContaining("invalid or has expired");
  }

  @Test
  void aKeyThatChangedTypeUnderItsCursorSaysSo() throws Exception {
    ConnectionConfig c = cfg().build();
    try (Jedis j = redis.admin()) {
      for (int i = 0; i < 30; i++) {
        j.rpush("cache:morph", "x" + i);
      }
      String cursor = get(c, "{\"key\":\"cache:morph\",\"limit\":10}").structuredContent().path("nextCursor")
          .asString();
      j.del("cache:morph");
      j.set("cache:morph", "now a string");
      assertThatThrownBy(() -> connector.execute(Configs.call("redis_get", "{\"key\":\"cache:morph\",\"cursor\":\""
          + cursor + "\"}"), c, TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of()))
          .hasMessage("The key changed its type since the cursor was made (list → string). Call redis_get without "
              + "cursor.");
    }
  }

  @Test
  void scanFindsEveryMatchingKeyAcrossPages() throws Exception {
    ConnectionConfig c = cfg().build();
    Set<String> found = new LinkedHashSet<>();
    String cursor = null;
    int pages = 0;
    boolean complete;
    do {
      JsonNode s = scan(c, "{\"pattern\":\"bulk:1*\",\"count\":100,\"details\":false"
          + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}").structuredContent();
      assertThat(s.path("keys").size()).isLessThanOrEqualTo(200);
      for (JsonNode k : s.path("keys")) {
        found.add(k.path("key").asString());
        assertThat(k.has("type")).isFalse();
      }
      complete = s.path("complete").asBoolean();
      cursor = s.path("nextCursor").isString() ? s.path("nextCursor").asString() : null;
      assertThat(cursor == null).isEqualTo(complete);
      pages++;
    } while (cursor != null && pages < 200);
    Set<String> expected = new HashSet<>();
    for (int i = 0; i < 5_000; i++) {
      if (Integer.toString(i).startsWith("1")) {
        expected.add("bulk:" + i);
      }
    }
    assertThat(found).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(pages).isGreaterThan(5);
  }

  @Test
  void scanFiltersByTypeAndAddsTypeAndTtl() throws Exception {
    ConnectionConfig c = cfg().build();
    JsonNode hashes = scan(c, "{\"pattern\":\"cache:*\",\"type\":\"hash\",\"count\":1000}").structuredContent();
    List<String> names = new ArrayList<>();
    hashes.path("keys").forEach(k -> names.add(k.path("key").asString()));
    assertThat(names).containsExactlyInAnyOrder("cache:hash", "cache:bighash");
    JsonNode str = scan(c, "{\"pattern\":\"cache:str\"}").structuredContent();
    assertThat(str.path("keys").get(0).path("type").asString()).isEqualTo("string");
    assertThat(str.path("keys").get(0).path("ttlMs").asLong()).isPositive();
    ToolResult text = scan(c, "{\"pattern\":\"cache:str\"}");
    assertThat(text.content().getFirst().text()).startsWith("1 key matches \"cache:str\"")
        .contains("cache:str  string  ttl 59m");
  }

  @Test
  void theConnectionsKeyListsHideKeysFromScanAndRefuseThemToGet() throws Exception {
    ConnectionConfig deny = cfg().advanced("keyDenylist", new String[] {"session:*", "*token*", "bulk:*"}).build();
    JsonNode all = scan(deny, "{\"count\":1000}").structuredContent();
    for (JsonNode k : all.path("keys")) {
      assertThat(k.path("key").asString()).doesNotStartWith("session:").doesNotContain("token")
          .doesNotStartWith("bulk:");
    }
    assertThat(all.path("hiddenByPolicy").asInt()).isPositive();
    assertThatThrownBy(() -> get(deny, "{\"key\":\"session:1\"}"))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));

    ConnectionConfig allow = cfg().advanced("keyAllowlist", new String[] {"cache:*"}).build();
    Set<String> names = new HashSet<>();
    String cursor = null;
    do {
      JsonNode s = scan(allow, "{\"count\":1000,\"details\":false" + (cursor == null ? "" : ",\"cursor\":\""
          + cursor + "\"") + "}").structuredContent();
      s.path("keys").forEach(k -> names.add(k.path("key").asString()));
      cursor = s.path("nextCursor").isString() ? s.path("nextCursor").asString() : null;
    } while (cursor != null);
    assertThat(names).allMatch(n -> n.startsWith("cache:")).contains("cache:str", "cache:bighash");
  }

  @Test
  void writesNeverReachTheServerAndToolsSendOnlyReads() throws Exception {
    ConnectionConfig c = cfg().build();
    try (Jedis admin = redis.admin()) {
      long keys = admin.dbSize();
      admin.configResetStat();
      // The executor of a call refuses what the tools never send, before the network.
      try (RedisSession s = connector.clients().open(c, RedisSettings.parse(c),
          Credentials.resolve(RedisSettings.parse(c), TestSecrets.password(LocalRedis.DEFAULT_PASSWORD)),
          Instant.now().plusSeconds(5))) {
        assertThatThrownBy(() -> s.call(Cmd.SET, "cache:str", "pwned"))
            .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
        assertThatThrownBy(() -> s.batch(List.of(new CommandArguments(Protocol.Command.FLUSHALL))))
            .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
        assertThatThrownBy(() -> s.batch(List.of(RedisSession.args(Cmd.TYPE, "a"),
            new CommandArguments(Protocol.Command.KEYS).add("*"))))
            .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
      }
      // Even a Jedis connection of its own, behind the executor, sends nothing refused.
      try (Connection raw = new Connection(new HostAndPort("127.0.0.1", redis.port),
          DefaultJedisClientConfig.builder().password(LocalRedis.DEFAULT_PASSWORD).protocol(RedisProtocol.RESP2)
              .build())) {
        ReadOnlyCommandExecutor x = new ReadOnlyCommandExecutor(raw, ReadOnlyCommandExecutor.Mode.READS,
            Clock.systemUTC(), Instant.now().plusSeconds(5), 2_000);
        for (Protocol.Command cmd : List.of(Protocol.Command.SET, Protocol.Command.FLUSHALL, Protocol.Command.KEYS,
            Protocol.Command.EVAL, Protocol.Command.DEL)) {
          CommandArguments a = new CommandArguments(cmd).add("cache:str").add("x");
          assertThatThrownBy(() -> x.executeCommand(new CommandObject<>(a, BuilderFactory.RAW_OBJECT)))
              .isInstanceOf(ReadOnlyCommandExecutor.RefusedCommandException.class);
        }
      }
      // Every tool, then the server's own count of what it ran.
      get(c, "{\"key\":\"cache:str\"}");
      get(c, "{\"key\":\"cache:hash\"}");
      get(c, "{\"key\":\"cache:list\",\"limit\":5}");
      get(c, "{\"key\":\"cache:set\",\"limit\":5}");
      get(c, "{\"key\":\"cache:zset\",\"limit\":5}");
      get(c, "{\"key\":\"cache:stream\",\"limit\":5}");
      get(c, "{\"key\":\"cache:big\"}");
      scan(c, "{\"pattern\":\"cache:*\",\"type\":\"string\"}");
      connector.describe(c, TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of(), null);
      connector.health(c, TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of());

      Set<String> ran = commands(admin.info("commandstats"));
      // The admin's own INFO, and the handshake of Jedis (HELLO … AUTH, CLIENT SETNAME/SETINFO).
      ran.removeAll(Set.of("info", "config|resetstat", "hello", "auth", "client|setname", "client|setinfo"));
      // A primary with a replica attached (the shared test servers) also counts the replica's REPLCONF ACK, sent
      // every second; the connector never sends it.
      if (!admin.info("replication").contains("connected_slaves:0")) {
        ran.remove("replconf");
      }
      assertThat(ran).isSubsetOf("ping", "type", "pttl", "memory|usage", "strlen", "get", "getrange", "hlen", "hscan",
          "llen", "lrange", "scard", "sscan", "zcard", "zrange", "xlen", "xrevrange", "scan", "dbsize",
          "client|no-touch");
      assertThat(ran).doesNotContain("set", "flushall", "keys", "eval", "del");
      assertThat(admin.dbSize()).isEqualTo(keys);
      assertThat(admin.get("cache:str")).isEqualTo("hello");
    }
  }

  /** {@code cmdstat_<name>:calls=…} lines of INFO commandstats as command names. */
  static Set<String> commands(String info) {
    Set<String> out = new HashSet<>();
    for (String line : info.split("\r?\n")) {
      if (line.startsWith("cmdstat_")) {
        out.add(line.substring("cmdstat_".length(), line.indexOf(':')));
      }
    }
    return out;
  }

  @Test
  void aSilentServerTimesOutAtTheCommandTimeout() throws Exception {
    ConnectionConfig c = cfg().advanced("commandTimeoutMs", 500).build();
    get(c, "{\"key\":\"cache:str\"}");
    try (Jedis admin = redis.admin()) {
      admin.clientPause(3_000);
      long t0 = System.nanoTime();
      assertThatThrownBy(() -> get(c, "{\"key\":\"cache:str\"}"))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
            assertThat(e.getMessage()).isEqualTo("127.0.0.1:" + redis.port + " didn't answer within 0.5 s. Check the "
                + "VPN or the host.");
          });
      assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1_500));
    } finally {
      try (Jedis admin = redis.admin()) {
        admin.clientUnpause();
      }
    }
  }

  @Test
  void aCancelledCallCutsItsSocketAtOnce() throws Exception {
    ConnectionConfig c = cfg().advanced("commandTimeoutMs", 10_000).build();
    get(c, "{\"key\":\"cache:str\"}");
    try (Jedis admin = redis.admin()) {
      admin.clientPause(5_000);
      TestCtx ctx = TestCtx.of();
      CompletableFuture<Throwable> call = CompletableFuture.supplyAsync(() -> {
        try {
          connector.execute(Configs.call("redis_get", "{\"key\":\"cache:str\"}"), c,
              TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), ctx);
          return null;
        } catch (Throwable t) {
          return t;
        }
      }, r -> Thread.ofVirtual().start(r));
      Thread.sleep(300);
      long t0 = System.nanoTime();
      ctx.cancel();
      Throwable t = call.get(3, TimeUnit.SECONDS);
      assertThat(t).isInstanceOf(ConnectorException.class);
      assertThat(((ConnectorException) t).kind()).isEqualTo(ConnectorException.Kind.TIMEOUT);
      assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(1));
    } finally {
      try (Jedis admin = redis.admin()) {
        admin.clientUnpause();
      }
    }
    // The cut connection left the pool; the next call gets a fresh one.
    assertThat(get(c, "{\"key\":\"cache:str\"}").structuredContent().path("value").asString()).isEqualTo("hello");
  }

  @Test
  void aBusyPoolSaysSoInsteadOfQueueingForever() throws Exception {
    ConnectionConfig c = cfg().advanced("poolSize", 1).advanced("commandTimeoutMs", 5_000).build();
    get(c, "{\"key\":\"cache:str\"}");
    try (Jedis admin = redis.admin()) {
      admin.clientPause(2_500);
      CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
        try {
          connector.execute(Configs.call("redis_get", "{\"key\":\"cache:str\"}"), c,
              TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of());
        } catch (ConnectorException e) {
          throw new IllegalStateException(e);
        }
      }, r -> Thread.ofVirtual().start(r));
      Thread.sleep(200);
      assertThatThrownBy(() -> get(c, "{\"key\":\"cache:str\"}"))
          .isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
            assertThat(e.countsForCircuitBreaker()).isFalse();
            assertThat(e.getMessage()).isEqualTo("The connection is busy: 1 call is running. Try again.");
          });
      first.get(10, TimeUnit.SECONDS);
    } finally {
      try (Jedis admin = redis.admin()) {
        admin.clientUnpause();
      }
    }
  }

  @Test
  void aNewVersionOrPasswordGetsANewPoolAndAKilledSocketIsReplaced() throws Exception {
    ConnectionConfig v1 = cfg().id("c-pool").user("ro").build();
    TestSecrets ro = new TestSecrets().with(RedisSettings.PASSWORD, "ro-pass", "v1");
    connector.execute(Configs.call("redis_get", "{\"key\":\"cache:str\"}"), v1, ro, TestCtx.of());
    RedisClients.Pool first = connector.clients().current("c-pool");
    connector.execute(Configs.call("redis_get", "{\"key\":\"cache:str\"}"), v1, ro, TestCtx.of());
    assertThat(connector.clients().current("c-pool")).isSameAs(first);

    // The server drops our idle socket (a restart, a timeout): the next call reconnects once, silently.
    try (Jedis admin = redis.admin()) {
      admin.sendCommand(Protocol.Command.CLIENT, "KILL", "USER", "ro");
    }
    assertThat(OutputSchema.get(connector.execute(Configs.call("redis_get", "{\"key\":\"cache:str\"}"), v1, ro,
        TestCtx.of())).structuredContent().path("value").asString()).isEqualTo("hello");

    ConnectionConfig v2 = cfg().id("c-pool").user("ro").version(2).build();
    connector.execute(Configs.call("redis_get", "{\"key\":\"cache:str\"}"), v2, ro, TestCtx.of());
    assertThat(connector.clients().current("c-pool")).isNotSameAs(first);

    RedisClients.Pool second = connector.clients().current("c-pool");
    TestSecrets wrong = new TestSecrets().with(RedisSettings.PASSWORD, "not-the-password", "v2");
    assertThatThrownBy(() -> connector.execute(Configs.call("redis_get", "{\"key\":\"cache:str\"}"), v2, wrong,
        TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_ERROR);
          assertThat(e.getMessage()).isEqualTo("Wrong user name or password for ro on 127.0.0.1:" + redis.port + ".");
        });
    assertThat(connector.clients().current("c-pool")).isNotSameAs(second);

    connector.onConnectionRemoved("c-pool");
    assertThat(connector.clients().current("c-pool")).isNull();
  }

  @Test
  void aShortLivedLoginGetsItsOwnPool() throws Exception {
    try (Jedis admin = redis.admin()) {
      admin.aclSetUser("v-token-1", "on", ">lease-pass-1", "~*", "+@read", "+@connection", "-@dangerous");
    }
    ConnectionConfig c = cfg().id("c-dyn").build();
    TestSecrets lease = new TestSecrets().dynamic("v-token-1", "lease-pass-1", "lease abcd1234");
    JsonNode s = OutputSchema.get(connector.execute(Configs.call("redis_get", "{\"key\":\"cache:str\"}"), c, lease,
        TestCtx.of())).structuredContent();
    assertThat(s.path("value").asString()).isEqualTo("hello");
    assertThat(connector.clients().current("c-dyn").credentials.user()).isEqualTo("v-token-1");
  }

  @Test
  void aSmallRowLimitOrSizeLimitCutsAtElementsAndTheCursorGoesOn() throws Exception {
    ConnectionConfig c = cfg().build();
    TestCtx prod = TestCtx.of().with(TestCtx.prod());
    JsonNode list = OutputSchema.get(connector.execute(Configs.call("redis_get", "{\"key\":\"cache:list\","
        + "\"limit\":1000}"), c, TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), prod)).structuredContent();
    assertThat(list.path("returned").asInt()).isEqualTo(100);
    assertThat(list.path("nextCursor").isString()).isTrue();

    try (Jedis admin = redis.admin()) {
      for (int i = 0; i < 40; i++) {
        admin.rpush("cache:fat", "y".repeat(8_000) + i);
      }
    }
    ai.msrv.gaterook.connector.api.Limits tiny = new ai.msrv.gaterook.connector.api.Limits(1_000, 1_000, 64 * 1024,
        64 * 1024, 64 * 1024, 64 * 1024, 8 * 1024, Duration.ofSeconds(5), Duration.ofSeconds(30),
        Duration.ofSeconds(10), Duration.ofHours(1));
    List<String> all = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode s = OutputSchema.get(connector.execute(Configs.call("redis_get", "{\"key\":\"cache:fat\""
          + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}"), c,
          TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of().with(tiny))).structuredContent();
      assertThat(s.toString().length()).isLessThan(64 * 1024 / 2);
      s.path("value").forEach(v -> all.add(v.asString()));
      cursor = s.path("nextCursor").isString() ? s.path("nextCursor").asString() : null;
    } while (cursor != null);
    assertThat(all).hasSize(40).doesNotHaveDuplicates();
  }

  @Test
  void describeSaysWhatTheServerIsAndWhichPrefixesTheAgentMayRead() throws Exception {
    ConnectionConfig c = cfg().advanced("keyDenylist", new String[] {"session:*", "*token*"}).build();
    JsonNode d = connector.describe(c, TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of(), null)
        .orElseThrow();
    assertThat(d.path("server").asString()).isEqualTo("redis");
    assertThat(d.path("version").asString()).startsWith("7.");
    assertThat(d.path("mode").asString()).isEqualTo("standalone");
    assertThat(d.path("role").asString()).isEqualTo("master");
    assertThat(d.path("keys").asLong()).isGreaterThan(5_000);
    assertThat(d.path("sampled").asInt() + d.path("hiddenByPolicy").asInt()).isEqualTo(RedisDescribe.SAMPLE);
    assertThat(d.path("prefixes").toString()).contains("\"prefix\":\"bulk:\"").doesNotContain("session:");
    assertThat(d.path("keyDenylist").toString()).isEqualTo("[\"session:*\",\"*token*\"]");

    JsonNode cache = connector.describe(c, TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of(),
        "cache:*").orElseThrow();
    assertThat(cache.path("prefixes").toString()).contains("\"prefix\":\"cache:\"").doesNotContain("bulk:");
  }

  @Test
  void healthPingsThroughThePool() {
    HealthResult h = connector.health(cfg().build(), TestSecrets.password(LocalRedis.DEFAULT_PASSWORD), TestCtx.of());
    assertThat(h.state()).isEqualTo(HealthResult.State.HEALTHY);
    HealthResult wrong = connector.health(cfg().build(), TestSecrets.password("nope"), TestCtx.of());
    assertThat(wrong.state()).isEqualTo(HealthResult.State.UNREACHABLE);
    assertThat(wrong.message()).startsWith("Wrong user name or password");
  }

  @Test
  void noTouchIsSkippedWhereTheServerDoesntKnowIt() throws Exception {
    ConnectionConfig c = cfg().id("c-touch").build();
    get(c, "{\"key\":\"cache:str\"}");
    // 7.0 has no CLIENT NO-TOUCH (7.2+): the pool notes it once and reads on.
    assertThat(connector.clients().current("c-touch").noTouchUnsupported())
        .isEqualTo(!redisAtLeast("7.2"));
  }

  private static boolean redisAtLeast(String version) {
    try (Jedis admin = redis.admin()) {
      String v = admin.info("server").lines().filter(l -> l.startsWith("redis_version:")).findFirst().orElse("")
          .substring("redis_version:".length()).strip();
      String[] a = v.split("\\.");
      String[] b = version.split("\\.");
      int major = Integer.parseInt(a[0]);
      int minor = Integer.parseInt(a[1]);
      return major > Integer.parseInt(b[0]) || (major == Integer.parseInt(b[0]) && minor >= Integer.parseInt(b[1]));
    }
  }
}
