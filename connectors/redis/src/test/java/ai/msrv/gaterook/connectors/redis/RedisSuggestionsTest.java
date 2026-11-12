package ai.msrv.gaterook.connectors.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.Suggestions.Option;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Help for the Redis form: {@code localhost} for an empty Host when a server answers on 6379 (offered, never filled
 * in), the form's defaults, and, signed in to a stand-in server, the transport Auto chose and the databases with their
 * keys.
 */
class RedisSuggestionsTest {

  private static final String PASSWORD = "cnry-redis-suggest-31";

  private final RedisConnector connector = new RedisConnector();

  @AfterEach
  void close() {
    connector.close();
  }

  private static ConnectionConfig withoutHost() {
    ConnectionConfig c = Configs.redis("x", 6379).build();
    ((ObjectNode) c.config().path("connection")).remove("host");
    return c;
  }

  @Test
  void anEmptyHostIsOfferedLocalhostWhenAServerAnswersOn6379() {
    AtomicInteger asked = new AtomicInteger();
    IntPredicate listening = port -> {
      asked.set(port);
      return true;
    };

    Suggestions s = RedisSuggestions.suggest(withoutHost(), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL,
        listening);

    Suggestions.Field host = s.fields().get("/connection/host");
    assertThat(asked).hasValue(6379);
    assertThat(host.value()).as("never filled in").isNull();
    assertThat(host.source()).isEqualTo("this computer");
    assertThat(host.options()).extracting(Option::value).containsExactly("localhost");
    assertThat(host.options().getFirst().detail()).isEqualTo("A Redis server answers on port 6379");
    assertThat(RedisSuggestions.suggest(withoutHost(), new TestSecrets(), TestCtx.of(), Suggestions.Scope.LOCAL,
        port -> false).isEmpty()).isTrue();
    assertThat(RedisSuggestions.suggest(Configs.redis("cache.dev.internal", 6379).build(), new TestSecrets(),
        TestCtx.of(), Suggestions.Scope.LOCAL, port -> true).isEmpty()).isTrue();
  }

  @Test
  void newConnectionsDefaultToAutoOutsideProdAndTheChoiceIsUnderAdvanced() {
    JsonNode tls = connector.manifest().configSchema().at("/properties/connection/properties/tls");

    assertThat(tls.path("default").asString()).isEqualTo("auto");
    assertThat(tls.at("/x-env-default/prod").asBoolean()).isTrue();
    assertThat(tls.path("enum").toString()).isEqualTo("[\"auto\",false,true]");
    assertThat(tls.has("x-advanced")).isFalse();
  }

  @Test
  void signedInItSaysWhichTransportAutoChoseAndListsTheDatabasesWithTheirKeys() throws Exception {
    try (FakeRedis plain = FakeRedis.plain(PASSWORD)) {
      Suggestions s = connector.suggest(Configs.redis("127.0.0.1", plain.port).auto(null).build(),
          TestSecrets.password(PASSWORD), TestCtx.of(), Suggestions.Scope.SERVICE);

      Suggestions.Field db = s.fields().get("/connection/database");
      assertThat(db).as("%s", s.notes()).isNotNull();
      assertThat(db.source()).isEqualTo("the server");
      assertThat(db.value()).isNull();
      assertThat(db.complete()).isTrue();
      assertThat(db.options()).hasSize(16);
      assertThat(db.options().get(0)).isEqualTo(new Option("0", null, "3 keys"));
      assertThat(db.options().get(1).detail()).isEqualTo("no keys");
      assertThat(db.options().get(2).detail()).isEqualTo("12 keys");
      assertThat(s.notes()).containsExactly(
          "The server doesn't speak TLS: Auto connects without it, so the traffic isn't encrypted.",
          "Signed in as default on Redis 7.2.4. Database 0 holds 3 keys.");
    }
  }

  @Test
  void anEmptyDatabaseSaysWhereTheKeysAre() throws Exception {
    try (FakeRedis tls = FakeRedis.tls(PASSWORD)) {
      tls.keyspace("# Keyspace\r\ndb3:keys=1,expires=0,avg_ttl=0\r\n");
      Suggestions s = connector.suggest(Configs.redis("localhost", tls.port).auto(tls.caPem()).user("gw_reader")
          .build(), TestSecrets.password(PASSWORD), TestCtx.of(), Suggestions.Scope.SERVICE);

      assertThat(s.notes()).containsExactly("The server speaks TLS: Auto connects with TLS.",
          "Signed in as gw_reader on Redis 7.2.4. Database 0 has no keys; database 3 (1 key) has some: choose one "
              + "under Database.");
    }
  }

  @Test
  void failuresAreNotesThatNameTheCause() throws Exception {
    try (FakeRedis plain = FakeRedis.plain(PASSWORD)) {
      Suggestions wrong = connector.suggest(Configs.redis("127.0.0.1", plain.port).auto(null).build(),
          TestSecrets.password("not-it"), TestCtx.of(), Suggestions.Scope.SERVICE);
      assertThat(wrong.fields()).isEmpty();
      assertThat(wrong.notes()).containsExactly("Wrong user name or password for default on 127.0.0.1:" + plain.port
          + ".");
    }
    Suggestions invalid = connector.suggest(Configs.redis("cache", 6380).env(Env.PROD).tls(true, "ca", null).build(),
        new TestSecrets(), TestCtx.of(), Suggestions.Scope.SERVICE);
    assertThat(invalid.notes()).containsExactly("TLS verification can't be ca on prod.");
  }
}
