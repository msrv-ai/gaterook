package com.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolDefinition;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

class RedisWriteTest {

  private final RedisConnector connector = new RedisConnector();
  private final ConnectionConfig cfg = Configs.redis("127.0.0.1", 1)
      .advanced("keyAllowlist", new String[] {"cache:*"})
      .advanced("keyDenylist", new String[] {"cache:secret*"}).build();

  @AfterEach
  void close() {
    connector.close();
  }

  @Test
  void writeDefinitionsRequireApprovalInEveryEnvironment() {
    var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    for (ToolDefinition tool : connector.manifest().tools().subList(2, 8)) {
      assertThat(tool.nature()).isEqualTo(ToolDefinition.Nature.MUTATION);
      assertThat(tool.defaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
      assertThat(tool.prodDefaultMode()).isEqualTo(ToolDefinition.Mode.APPROVAL);
      assertThat(tool.readOnlyModeAllowed()).isFalse();
      assertThat(tool.annotations().readOnlyHint()).isFalse();
      assertThat(tool.annotations().idempotentHint()).isFalse();
      assertThat(tool.outputSchema()).isNull();
      var schema = registry.getSchema(SchemaLocation.of("urn:test:" + tool.name()), tool.inputSchema());
      ObjectNode valid = TestCtx.JSON.createObjectNode().put("connection", "cache-redis").put("key", "cache:k");
      switch (tool.name()) {
        case "redis_set" -> valid.put("value", "");
        case "redis_hset" -> valid.putObject("fields").put("a", "1");
        case "redis_zadd" -> valid.putArray("members").addObject().put("score", 1.5).put("value", "a");
        default -> valid.putArray("values").add("a");
      }
      assertThat(schema.validate(valid)).as(tool.name()).isEmpty();
      valid.put("command", "FLUSHALL");
      assertThat(schema.validate(valid)).as(tool.name()).isNotEmpty();
    }
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      redis_set   | {"key":"cache:k","value":""}
      redis_hset  | {"key":"cache:k","fields":{"a":"1"}}
      redis_lpush | {"key":"cache:k","values":["a","b"]}
      redis_rpush | {"key":"cache:k","values":["a","b"]}
      redis_sadd  | {"key":"cache:k","values":["a","a"]}
      redis_zadd  | {"key":"cache:k","members":[{"score":-1.5,"value":"a"}]}
      """)
  void mutationsRoundTripThroughTheExactApprovedPayload(String tool, String arguments) throws Exception {
    var call = Configs.call(tool, arguments);
    for (Env env : Env.values()) {
      assertThat(connector.classify(call, cfg, Configs.policy(env)).kind()).isEqualTo(Classification.Kind.MUTATION);
    }
    var preview = connector.describeAction(call, cfg);
    var restored = RedisRequest.of(tool, preview.payload(), cfg, RedisSettings.parse(cfg));
    assertThat(restored.payload()).isEqualTo(preview.payload());
    assertThat(((RedisWrite) restored).preview()).isEqualTo(preview.text());
    assertThat(preview.target()).endsWith("/0");
    ObjectNode denied = (ObjectNode) TestCtx.JSON.readTree(arguments);
    for (String key : List.of("other:k", "cache:secret1")) {
      denied.put("key", key);
      var deniedCall = Configs.call(tool, denied.toString());
      assertThat(connector.classify(deniedCall, cfg, Configs.policy(Env.DEV)).ruleId())
          .isEqualTo("dev.redis.key-not-allowed");
      assertThatThrownBy(() -> connector.execute(deniedCall, cfg, TestSecrets.password("x"), TestCtx.of()))
          .isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    assertThat(connector.clients().pools()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
      redis_set   | {"key":"cache:k"}
      redis_set   | {"key":"cache:k","value":null}
      redis_set   | {"key":"cache:k","value":42}
      redis_set   | {"key":"cache:k","value":"a","ttlMs":0}
      redis_set   | {"key":"cache:k","value":"a","ttlMs":1.5}
      redis_set   | {"key":"cache:k","value":"a","ttlMs":18446744073709551617}
      redis_set   | {"key":"cache:k","value":"a","ttlMs":100,"keepTtl":true}
      redis_set   | {"key":"cache:k","value":"a","keepTtl":"true"}
      redis_set   | {"key":"cache:k","value":"a","condition":"NX XX"}
      redis_set   | {"key":"cache:k","value":"!","valueEncoding":"base64"}
      redis_set   | {"key":"cache:k","value":"a","valueEncoding":"hex"}
      redis_set   | {"key":"cache:k","value":"a","encoding":false}
      redis_set   | {"key":"cache:k","value":"a","database":18446744073709551616}
      redis_set   | {"key":"cache:k","value":"a","command":"FLUSHALL"}
      redis_hset  | {"key":"cache:k","fields":{}}
      redis_hset  | {"key":"cache:k","fields":{"a":1}}
      redis_lpush | {"key":"cache:k","values":[]}
      redis_rpush | {"key":"cache:k","values":"a"}
      redis_sadd  | {"key":"cache:k","values":[null]}
      redis_zadd  | {"key":"cache:k","members":[{"score":"1","value":"a"}]}
      redis_zadd  | {"key":"cache:k","members":[{"score":1e999,"value":"a"}]}
      redis_zadd  | {"key":"cache:k","members":[{"score":1,"value":"a","nx":true}]}
      """)
  void invalidWritesAreRefusedBeforeOpeningASocket(String tool, String arguments) {
    var call = Configs.call(tool, arguments);
    assertThat(connector.classify(call, cfg, Configs.policy(Env.DEV)).ruleId()).isEqualTo("dev.redis.bad-argument");
    assertThatThrownBy(() -> connector.execute(call, cfg, TestSecrets.password("x"), TestCtx.of()))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.BAD_INPUT));
    assertThat(connector.clients().pools()).isEmpty();
  }

  @Test
  void binaryKeyScopeAndSelectedDatabasesAreEnforced() {
    assertThat(connector.classify(Configs.call("redis_set",
        "{\"key\":\"b3RoZXI6aw==\",\"encoding\":\"base64\",\"value\":\"a\"}"), cfg,
        Configs.policy(Env.DEV)).ruleId()).isEqualTo("dev.redis.key-not-allowed");
    var selected = Configs.redis("127.0.0.1", 1).databases(1, 2).build();
    for (String db : List.of("", ",\"database\":0", ",\"database\":3")) {
      assertThat(connector.classify(Configs.call("redis_set", "{\"key\":\"k\",\"value\":\"v\"" + db + "}"),
          selected, Configs.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.DENY);
    }
    assertThat(connector.describeAction(Configs.call("redis_set",
        "{\"key\":\"k\",\"value\":\"v\",\"database\":2}"), selected).payload().path("database").asInt())
        .isEqualTo(2);
  }

  @Test
  void limitsRejectTheWholeWriteAndPreviewQuotesBytes() {
    var a = TestCtx.JSON.createObjectNode().put("key", "cache:k");
    var values = a.putArray("values");
    for (int i = 0; i < 101; i++) {
      values.add("a");
    }
    var call = Configs.call("redis_rpush", a.toString());
    assertThatThrownBy(() -> connector.execute(call, cfg, TestSecrets.password("x"), TestCtx.of().with(TestCtx.prod())))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    var small = Configs.redis("127.0.0.1", 1).advanced("maxValueKb", 1).build();
    a.remove("values");
    a.put("value", "x".repeat(1025));
    assertThat(connector.classify(Configs.call("redis_set", a.toString()), small, Configs.policy(Env.DEV)).kind())
        .isEqualTo(Classification.Kind.DENY);
    var preview = connector.describeAction(Configs.call("redis_set",
        "{\"key\":\"cache:k\",\"value\":\"AP8=\",\"valueEncoding\":\"base64\",\"condition\":\"NX\",\"ttlMs\":100}"), cfg);
    assertThat(preview.text()).isEqualTo("SET \"cache:k\" \"\\x00\\xff\" \"NX\" \"PX\" \"100\"");
    assertThat(connector.clients().pools()).isEmpty();
  }

  @Test
  void oversizedRequestsAndChangedApprovalScopeAreRejectedWithoutIo() {
    var large = TestCtx.JSON.createObjectNode().put("key", "cache:k");
    var values = large.putArray("values");
    for (int i = 0; i < 5; i++) {
      values.add("a".repeat(60_000));
    }
    assertThatThrownBy(() -> connector.execute(Configs.call("redis_rpush", large.toString()), cfg,
        TestSecrets.password("x"), TestCtx.of()))
        .isInstanceOf(ConnectorException.class).hasMessageContaining("request size limit");
    for (int i = 5; i < 18; i++) {
      values.add("a".repeat(60_000));
    }
    assertThat(connector.classify(Configs.call("redis_rpush", large.toString()), cfg, Configs.policy(Env.DEV))
        .reason()).contains("1 MiB");
    values.removeAll();
    for (int i = 0; i < 1_001; i++) {
      values.add("a");
    }
    assertThat(connector.classify(Configs.call("redis_sadd", large.toString()), cfg, Configs.policy(Env.DEV))
        .kind()).isEqualTo(Classification.Kind.DENY);
    var unrestricted = Configs.redis("127.0.0.1", 1).build();
    var deniedCall = Configs.call("redis_set", "{\"key\":\"cache:secret1\",\"value\":\"v\"}");
    var approved = connector.describeAction(deniedCall, unrestricted).payload();
    var replacement = Configs.call("redis_set", "{\"key\":\"cache:k\",\"value\":\"replacement\"}");
    assertThatThrownBy(() -> connector.execute(replacement, cfg, TestSecrets.password("x"), TestCtx.of().approved(approved)))
        .isInstanceOfSatisfying(ConnectorException.class,
            e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    assertThat(connector.clients().pools()).isEmpty();
  }
}
