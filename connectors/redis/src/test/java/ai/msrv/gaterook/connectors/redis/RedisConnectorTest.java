package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector without a server: manifest, classification (READ or DENY — never MUTATION), the exact action of a
 * read set to Approval, and refusals that happen before any socket is opened.
 */
class RedisConnectorTest {

  private final RedisConnector connector = new RedisConnector();

  @AfterEach
  void close() {
    connector.close();
  }

  private static ConnectionConfig cfg() {
    return Configs.redis("cache.dev.internal", 6379)
        .advanced("keyDenylist", new String[] {"session:*", "*token*"}).id("c-cache").build();
  }

  @Test
  void manifestIsTheCatalogCard() {
    ConnectorManifest m = connector.manifest();

    assertThat(m.id()).isEqualTo("redis");
    assertThat(m.kind()).isEqualTo(ConnectorManifest.Kind.BUILT_IN);
    assertThat(m.name()).isEqualTo("Redis");
    assertThat(m.group()).isEqualTo("Databases");
    assertThat(m.icon()).isEqualTo("layers");
    assertThat(m.description()).isEqualTo("Read keys and scan by pattern. Read only.");
    assertThat(m.configSchema().path("type").asString()).isEqualTo("object");
    assertThat(m.policySchema()).isNull();
  }

  @Test
  void publishesTheToolsOfTheKit() {
    List<ToolDefinition> tools = connector.manifest().tools();

    assertThat(tools).extracting(ToolDefinition::name).containsExactly("redis_get", "redis_scan");
    for (ToolDefinition t : tools) {
      assertThat(t.nature()).as(t.name()).isEqualTo(Nature.READ);
      assertThat(t.defaultMode()).isEqualTo(Mode.READ_ONLY);
      assertThat(t.prodDefaultMode()).isEqualTo(Mode.READ_ONLY);
      assertThat(t.outputSchema()).isNotNull();
      assertThat(t.annotations().readOnlyHint()).isTrue();
      assertThat(t.annotations().destructiveHint()).isFalse();
      assertThat(t.inputSchema().at("/properties/connection").toString()).isEqualTo("{\"type\":\"string\"}");
      assertThat(t.inputSchema().at("/required").toString()).contains("\"connection\"");
      assertThat(t.readOnlyModeAllowed()).isTrue();
    }
  }

  @Test
  void anEndpointSeesItsRowLimitInTheDescriptions() {
    ConnectionConfig c = cfg();
    EndpointView prod = new EndpointView("incident-prod", Env.PROD, List.of(c), Map.of(c.id(), TestCtx.prod()),
        List.of());
    List<ToolDefinition> tools = connector.tools(prod);
    assertThat(tools.get(0).description()).endsWith("Max 100 elements per call.");
    assertThat(tools.get(1).description()).endsWith("Max 100 keys per call.");
    assertThat(tools.get(0).inputSchema()).isEqualTo(connector.manifest().tools().get(0).inputSchema());
  }

  @ParameterizedTest(name = "{0} {1} → {2}")
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      redis_get  | {"key":"cache:users:7"}                         | READ |
      redis_get  | {"key":"cache:users:7","limit":1000}            | READ |
      redis_get  | {"key":"session:48213"}                         | DENY | dev.redis.key-not-allowed
      redis_get  | {"key":"api:token:1"}                           | DENY | dev.redis.key-not-allowed
      redis_get  | {"key":"c2Vzc2lvbjoxMjM=","encoding":"base64"}  | DENY | dev.redis.key-not-allowed
      redis_get  | {"key":"not base64!","encoding":"base64"}       | DENY | dev.redis.bad-argument
      redis_get  | {"key":"cache:1","cursor":"rsc.eyJ4"}           | DENY | dev.redis.bad-cursor
      redis_get  | {"key":"cache:1","cursor":"garbage"}            | DENY | dev.redis.bad-cursor
      redis_scan | {}                                              | READ |
      redis_scan | {"pattern":"cache:*","type":"hash","count":5}   | READ |
      redis_scan | {"pattern":"session:*"}                         | READ |
      redis_scan | {"pattern":"a\\u0000b"}                         | DENY | dev.redis.bad-pattern
      redis_scan | {"pattern":"line\\nbreak"}                      | DENY | dev.redis.bad-pattern
      redis_scan | {"cursor":"rgc.eyJ4"}                           | DENY | dev.redis.bad-cursor
      redis_set  | {"key":"x","value":"y"}                         | DENY | dev.redis.unknown-tool
      """)
  void classifiesWithoutIo(String tool, String arguments, Classification.Kind kind, String rule) {
    Classification c = connector.classify(Configs.call(tool, arguments), cfg(), Configs.policy(Env.DEV));
    assertThat(c.kind()).as(c.reason()).isEqualTo(kind);
    if (rule != null) {
      assertThat(c.ruleId()).isEqualTo(rule);
      assertThat(c.reason()).isNotBlank();
    }
  }

  @Test
  void aCursorOfAnotherKeyOrConnectionIsDeniedBeforeItIsOpened() {
    String cursor = Cursors.sealGet(TestCtx.of(), "c-cache/database/0", "cache:a".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        new Cursors.GetState("hash", "17"));
    assertThat(connector.classify(Configs.call("redis_get", "{\"key\":\"cache:a\",\"cursor\":\"" + cursor + "\"}"),
        cfg(), Configs.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.READ);
    Classification other = connector.classify(Configs.call("redis_get", "{\"key\":\"cache:b\",\"cursor\":\"" + cursor
        + "\"}"), cfg(), Configs.policy(Env.DEV));
    assertThat(other.ruleId()).isEqualTo("dev.redis.bad-cursor");
    assertThat(other.reason()).isEqualTo(Cursors.OTHER_KEY);
    ConnectionConfig elsewhere = Configs.redis("cache.dev.internal", 6379).id("c-other").build();
    assertThat(connector.classify(Configs.call("redis_get", "{\"key\":\"cache:a\",\"cursor\":\"" + cursor + "\"}"),
        elsewhere, Configs.policy(Env.DEV)).ruleId()).isEqualTo("dev.redis.bad-cursor");
  }

  @Test
  void aDeniedKeySaysWhichListAndQuotesTheBytes() {
    Classification c = connector.classify(Configs.call("redis_get", "{\"key\":\"session:\\u202e48213\"}"), cfg(),
        Configs.policy(Env.DEV));
    assertThat(c.reason()).isEqualTo("cache-redis doesn't let agents read \"session:\\xe2\\x80\\xae48213\": it "
        + "matches a pattern of \"Keys the agent never reads\".");
  }

  @Test
  void anAllowlistLimitsWhatCanBeRead() {
    ConnectionConfig c = Configs.redis("cache.dev.internal", 6379).advanced("keyAllowlist", new String[] {"cache:*"})
        .build();
    assertThat(connector.classify(Configs.call("redis_get", "{\"key\":\"cache:1\"}"), c, Configs.policy(Env.DEV))
        .kind()).isEqualTo(Classification.Kind.READ);
    Classification other = connector.classify(Configs.call("redis_get", "{\"key\":\"orders:1\"}"), c,
        Configs.policy(Env.DEV));
    assertThat(other.ruleId()).isEqualTo("dev.redis.key-not-allowed");
    assertThat(other.reason()).contains("matches none of \"Keys the agent may read\"");
  }

  @Test
  void settingsThatCantBeUsedDenyEveryCall() {
    for (String host : List.of("169.254.169.254", "metadata.google.internal", "0177.0.0.1", "cache dev")) {
      ConnectionConfig c = Configs.redis(host, 6379).build();
      Classification k = connector.classify(Configs.call("redis_scan", "{}"), c, Configs.policy(Env.DEV));
      assertThat(k.kind()).as(host).isEqualTo(Classification.Kind.DENY);
      assertThat(k.ruleId()).isEqualTo("dev.redis.config-invalid");
    }
  }

  @Test
  void noArgumentsEverMakeAMutation() {
    Random random = new Random(7);
    String[] words = {"key", "pattern", "cursor", "limit", "count", "type", "encoding", "details", "command", "value"};
    for (int i = 0; i < 2_000; i++) {
      ObjectNode a = TestCtx.JSON.createObjectNode();
      for (int j = 0; j < random.nextInt(5); j++) {
        String w = words[random.nextInt(words.length)];
        switch (random.nextInt(4)) {
          case 0 -> a.put(w, random.nextInt(2_000) - 500);
          case 1 -> a.put(w, random.nextBoolean());
          case 2 -> a.put(w, "SET k v");
          default -> a.put(w, "rgc." + Long.toHexString(random.nextLong()));
        }
      }
      String tool = random.nextBoolean() ? "redis_get" : "redis_scan";
      Classification c = connector.classify(new ToolCall("tc", tool, "cache-redis", a, null), cfg(),
          Configs.policy(random.nextBoolean() ? Env.DEV : Env.PROD));
      assertThat(c.kind()).as(a.toString()).isNotEqualTo(Classification.Kind.MUTATION);
    }
  }

  @Test
  void theExactActionListsTheCommandsAsTheyGoToTheServer() {
    ActionPreview p = connector.describeAction(Configs.call("redis_get", "{\"key\":\"cache:hash\",\"limit\":100}"),
        cfg());
    assertThat(p.kind()).isEqualTo(ActionPreview.Kind.COMMAND);
    assertThat(p.text()).isEqualTo("""
        TYPE "cache:hash"
        PTTL "cache:hash"
        MEMORY USAGE "cache:hash"
        then one read for its type:
          string  STRLEN "cache:hash"; GET "cache:hash" (GETRANGE "cache:hash" 0 65535 when it is longer)
          hash    HLEN "cache:hash"; HSCAN "cache:hash" 0 COUNT 100
          list    LLEN "cache:hash"; LRANGE "cache:hash" 0 99
          set     SCARD "cache:hash"; SSCAN "cache:hash" 0 COUNT 100
          zset    ZCARD "cache:hash"; ZRANGE "cache:hash" 0 99 WITHSCORES
          stream  XLEN "cache:hash"; XREVRANGE "cache:hash" + - COUNT 100""");
    assertThat(p.target()).isEqualTo("cache-redis · cache.dev.internal:6379/0");
    assertThat(p.summary()).isEqualTo("redis_get \"cache:hash\" · cache-redis (dev)");
    assertThat(p.payload().toString()).isEqualTo("{\"tool\":\"redis_get\",\"key\":\"cache:hash\",\"database\":0,\"limit\":100}");

    ActionPreview scan = connector.describeAction(Configs.call("redis_scan",
        "{\"pattern\":\"cache:*\",\"type\":\"string\",\"count\":400}"), cfg());
    assertThat(scan.text()).isEqualTo("""
        SCAN 0 MATCH "cache:*" COUNT 400 TYPE string
        again with the returned cursor until 400 keys, the end, 50 rounds or 2 s
        then TYPE and PTTL of each key returned""");
    assertThat(scan.payload().toString())
        .isEqualTo("{\"tool\":\"redis_scan\",\"pattern\":\"cache:*\",\"database\":0,\"type\":\"string\",\"count\":400,"
            + "\"details\":true}");
  }

  @Test
  void hiddenCharactersInAKeyAreSpelledOutInTheAction() {
    ActionPreview p = connector.describeAction(Configs.call("redis_get", "{\"key\":\"cache:\\u200bx\\ny\"}"), cfg());
    assertThat(p.text()).startsWith("TYPE \"cache:\\xe2\\x80\\x8bx\\ny\"\n");
  }

  @Test
  void aKeyTheConnectionDeniesIsRefusedBeforeAnySocket() {
    // Port 1 on loopback: a connection attempt would fail differently; the refusal comes first.
    ConnectionConfig c = Configs.redis("127.0.0.1", 1).advanced("keyDenylist", new String[] {"session:*"}).build();
    assertThatThrownBy(() -> connector.execute(Configs.call("redis_get", "{\"key\":\"session:1\"}"), c,
        TestSecrets.password("x"), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("doesn't let agents read");
        });
    assertThat(connector.clients().pools()).isEmpty();
  }

  @Test
  void anApprovedCallRunsThePayloadNotTheArguments() {
    ConnectionConfig c = Configs.redis("127.0.0.1", 1).advanced("keyDenylist", new String[] {"session:*"}).build();
    ObjectNode payload = TestCtx.JSON.createObjectNode().put("tool", "redis_get").put("key", "session:1");
    // The arguments ask for an allowed key; the stored payload names a denied one, and that is what is checked.
    assertThatThrownBy(() -> connector.execute(Configs.call("redis_get", "{\"key\":\"cache:1\"}"), c,
        TestSecrets.password("x"), TestCtx.of().approved(payload)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
  }

  @Test
  void anUnreachableServerIsAnUnavailableResource() {
    ConnectionConfig c = Configs.redis("127.0.0.1", 1).build();
    assertThatThrownBy(() -> connector.execute(Configs.call("redis_scan", "{}"), c, TestSecrets.password("x"),
        TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.RESOURCE_UNAVAILABLE);
          assertThat(e.countsForCircuitBreaker()).isTrue();
          assertThat(e.getMessage()).isEqualTo("Connection refused on port 1 of 127.0.0.1. Is redis-server running "
              + "there?");
        });
    assertThat(connector.test(c, TestSecrets.password("x"), TestCtx.of()).error().code()).isEqualTo("redis.refused");
  }
}
