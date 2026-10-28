package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connectors.support.SchemaResources;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RedisDatabaseSelectionTest {

  @Test
  void savedConfigurationCanOmitThePortAndBothDatabaseFields() throws Exception {
    var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(SchemaResources.of(RedisConnector.class, "redis").load("config"));
    Configs.Cfg draft = Configs.redis("cache.example.test", 6379);
    draft.root.withObjectProperty("connection").remove("port");
    assertThat(schema.validate(draft.root)).isEmpty();
    RedisSettings settings = RedisSettings.parse(draft.build());
    assertThat(settings.port()).isEqualTo(6379);
    assertThat(settings.database()).isZero();
    assertThat(settings.databases()).isEmpty();

    assertThat(schema.validate(Configs.redis("cache.example.test", 6379).database(4).build().config())).isEmpty();
    assertThat(schema.validate(Configs.redis("cache.example.test", 6379).databases(0, 2).build().config())).isEmpty();
    for (int[] databases : new int[][] {{}, {2, 2}, {-1}, {1024}}) {
      assertThat(schema.validate(Configs.redis("cache.example.test", 6379).databases(databases).build().config()))
          .as("An explicitly configured allowlist must contain valid, unique database indexes").isNotEmpty();
    }
  }

  @Test
  void theDefaultDatabaseNeedsNoSelectionAndCannotReachAnotherDatabase() throws Exception {
    ConnectionConfig cfg = Configs.redis("127.0.0.1", 1).build();
    RedisSettings settings = RedisSettings.parse(cfg);
    try (RedisConnector connector = new RedisConnector()) {
      for (String tool : new String[] {"redis_get", "redis_scan"}) {
        var call = Configs.call(tool, "{\"key\":\"a\"}");
        assertThat(connector.classify(call, cfg, Configs.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.READ);
        RedisRequest request = RedisRequest.of(tool, call.arguments(), cfg, settings);
        assertThat(request.database()).isZero();
        assertThat(request.payload().path("database").asInt(-1)).isZero();
        var denied = Configs.call(tool, "{\"key\":\"a\",\"database\":1}");
        assertThat(connector.classify(denied, cfg, Configs.policy(Env.DEV)).ruleId())
            .isEqualTo("dev.redis.database-not-allowed");
        assertThatThrownBy(() -> connector.execute(denied, cfg, new TestSecrets(), TestCtx.of()))
            .isInstanceOf(ConnectorException.class).hasMessageContaining("isn't selected");
      }
      assertThat(connector.clients().pools()).isEmpty();
    }
  }

  @Test
  void multipleSelectionsRequireAnExplicitDatabaseAndRejectUnselectedOnesBeforeIo() throws Exception {
    ConnectionConfig cfg = Configs.redis("127.0.0.1", 1).databases(0, 2).build();
    try (RedisConnector connector = new RedisConnector()) {
      assertThat(connector.classify(Configs.call("redis_get", "{\"key\":\"a\"}"), cfg,
          Configs.policy(Env.DEV)).ruleId()).isEqualTo("dev.redis.database-required");
      assertThat(connector.classify(Configs.call("redis_get", "{\"key\":\"a\",\"database\":2}"), cfg,
          Configs.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.READ);
      var denied = Configs.call("redis_get", "{\"key\":\"a\",\"database\":1}");
      assertThat(connector.classify(denied, cfg, Configs.policy(Env.DEV)).ruleId())
          .isEqualTo("dev.redis.database-not-allowed");
      assertThatThrownBy(() -> connector.execute(denied, cfg, new TestSecrets(), TestCtx.of()))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("isn't selected");
      assertThat(connector.clients().pools()).isEmpty();
    }
  }

  @Test
  void aSoleSelectionIsTheDefaultAndTheApprovedPayloadPinsIt() throws Exception {
    ConnectionConfig cfg = Configs.redis("127.0.0.1", 1).database(0).databases(2).build();
    RedisSettings settings = RedisSettings.parse(cfg);
    RedisRequest request = RedisRequest.of("redis_get", TestCtx.JSON.readTree("{\"key\":\"a\"}"), cfg, settings);
    assertThat(request.database()).isEqualTo(2);
    assertThat(request.payload().path("database").asInt()).isEqualTo(2);
    assertThat(RedisSettings.parse(Configs.redis("127.0.0.1", 1).database(4).build()).database()).isEqualTo(4);
    assertThatThrownBy(() -> RedisSettings.parse(Configs.redis("127.0.0.1", 1).databases().build()))
        .isInstanceOf(RedisSettings.InvalidSettingsException.class);
    assertThatThrownBy(() -> RedisSettings.parse(Configs.redis("127.0.0.1", 1).databases(2, 2).build()))
        .isInstanceOf(RedisSettings.InvalidSettingsException.class);
  }

  @Test
  void aPageCannotBeReusedInAnotherSelectedDatabase() throws Exception {
    ConnectionConfig cfg = Configs.redis("127.0.0.1", 1).databases(0, 2).build();
    RedisSettings settings = RedisSettings.parse(cfg);
    String cursor = Cursors.sealGet(TestCtx.of(), settings.withDatabase(0).cursorScope(cfg.id()),
        "a".getBytes(StandardCharsets.UTF_8), new Cursors.GetState("list", "1"));
    try (RedisConnector connector = new RedisConnector()) {
      assertThat(connector.classify(Configs.call("redis_get", "{\"key\":\"a\",\"database\":2,\"cursor\":\""
          + cursor + "\"}"), cfg, Configs.policy(Env.DEV)).ruleId()).isEqualTo("dev.redis.bad-cursor");
      assertThat(connector.classify(Configs.call("redis_get", "{\"key\":\"a\",\"database\":0,\"cursor\":\""
          + cursor + "\"}"), cfg, Configs.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.READ);
    }
  }

  @Test
  void legacyDatabaseChangesAlsoInvalidatePages() throws Exception {
    ConnectionConfig before = Configs.redis("127.0.0.1", 1).id("same-connection").database(0).build();
    ConnectionConfig after = Configs.redis("127.0.0.1", 1).id("same-connection").database(2).version(2).build();
    RedisSettings settings = RedisSettings.parse(before);
    String cursor = Cursors.sealGet(TestCtx.of(), settings.cursorScope(before.id()),
        "a".getBytes(StandardCharsets.UTF_8), new Cursors.GetState("list", "1"));
    try (RedisConnector connector = new RedisConnector()) {
      assertThat(connector.classify(Configs.call("redis_get", "{\"key\":\"a\",\"cursor\":\"" + cursor + "\"}"),
          after, Configs.policy(Env.DEV)).ruleId()).isEqualTo("dev.redis.bad-cursor");
    }
  }

  @Test
  void partialDiscoveryNeverInventsEmptyDatabaseNumbers() {
    assertThat(RedisProbe.keyspaceDatabases("# Keyspace\r\ndb2:keys=1,expires=0\r\ndb7:keys=2\r\n", 0))
        .containsExactly(0, 2, 7);
    assertThat(RedisProbe.keyspaceDatabases("", 3)).containsExactly(3);
  }

  @Test
  void multipleDatabaseDescriptionsListTheSelectionAndKeepKeyRestrictions() throws Exception {
    ConnectionConfig cfg = Configs.redis("127.0.0.1", 1).databases(0, 2)
        .advanced("keyDenylist", new String[] {"session:*"}).build();
    try (RedisConnector connector = new RedisConnector()) {
      var description = connector.describe(cfg, new TestSecrets(), TestCtx.of(), null).orElseThrow();
      assertThat(description.path("databases").toString()).isEqualTo("[0,2]");
      assertThat(description.path("keyDenylist").toString()).isEqualTo("[\"session:*\"]");
      assertThatThrownBy(() -> connector.describe(cfg, new TestSecrets(), TestCtx.of(), "cache:*"))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("Choose a database");
    }
  }
}
