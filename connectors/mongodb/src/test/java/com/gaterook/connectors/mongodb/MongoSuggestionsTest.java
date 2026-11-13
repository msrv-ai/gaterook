package com.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.Suggestions;
import com.gaterook.connector.api.Suggestions.Option;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Help for the MongoDB form without a server: {@code localhost} while Host or the connection string is empty and a
 * server answers on 27017, never filled in; the form's defaults (Auto for new dev and stage connections, TLS on prod);
 * and service suggestions that stop at settings the connector can't use. Signing in is covered against real mongods
 * by {@code MongoConnectionIT}.
 */
class MongoSuggestionsTest {

  private final MongoConnector connector = new MongoConnector();
  private final List<Integer> asked = new ArrayList<>();

  @AfterEach
  void close() {
    connector.close();
  }

  private IntPredicate answering(int... ports) {
    return port -> {
      asked.add(port);
      for (int p : ports) {
        if (p == port) {
          return true;
        }
      }
      return false;
    };
  }

  private Suggestions local(String json, IntPredicate listening) {
    return MongoSuggestions.suggest(Fixtures.config(Env.DEV, json), Fixtures.Secrets.none(),
        Fixtures.Context.of(Env.DEV), Suggestions.Scope.LOCAL, null, listening);
  }

  @Test
  void anEmptyHostIsOfferedLocalhostWhenAServerAnswersOn27017() {
    Suggestions s = local("{\"connection\":{\"mode\":\"host\"}}", answering(27017));

    Suggestions.Field host = s.fields().get("/connection/host");
    assertThat(host.value()).as("never filled in").isNull();
    assertThat(host.source()).isEqualTo("this computer");
    assertThat(host.options()).extracting(Option::value).containsExactly("localhost");
    assertThat(host.options().getFirst().detail()).isEqualTo("A MongoDB server answers on port 27017");
    assertThat(asked).containsExactly(27017);
  }

  @Test
  void aConnectionStringModeIsOfferedTheLocalServersString() {
    Suggestions s = local("{\"connection\":{\"mode\":\"uri\"}}", answering(27017));

    assertThat(s.fields().get("/connection/uri").options()).extracting(Option::value)
        .containsExactly("mongodb://localhost:27017/");
    assertThat(s.fields()).doesNotContainKey("/connection/host");
  }

  @Test
  void nothingIsOfferedForATypedHostOrWithoutALocalServer() {
    assertThat(local("{\"connection\":{\"host\":\"mongo.example.test\"}}", answering(27017)).isEmpty()).isTrue();
    assertThat(asked).as("no port tried for a typed host").isEmpty();
    assertThat(local("{\"connection\":{}}", answering()).isEmpty()).isTrue();
  }

  @Test
  void newConnectionsDefaultToAutoOutsideProdAndTheChoiceIsUnderAdvanced() {
    JsonNode tls = connector.manifest().configSchema().at("/properties/connection/properties/tls");

    assertThat(tls.path("default").asString()).isEqualTo("auto");
    assertThat(tls.at("/x-env-default/prod").asBoolean()).isTrue();
    assertThat(tls.has("x-advanced")).as("optional with a default: the form puts it under Advanced").isFalse();
    // Stored connections without the key keep their old transport: none on dev, TLS on prod.
    assertThat(MongoSettings.of(Fixtures.config(Env.DEV, "{\"connection\":{\"host\":\"db.test\"}}")).tls())
        .isFalse();
    assertThat(MongoSettings.of(Fixtures.config(Env.PROD, "{\"connection\":{\"host\":\"db.test\"}}")).tls())
        .isTrue();
  }

  @Test
  void serviceSuggestionsNameWhatStopsThemBeforeAnySignIn() {
    ConnectionConfig unusable = Fixtures.config(Env.PROD, "{\"connection\":{\"host\":\"db.test\","
        + "\"allowInvalidHostnames\":true}}");
    Suggestions s = connector.suggest(unusable, Fixtures.Secrets.none(), Fixtures.Context.of(Env.PROD),
        Suggestions.Scope.SERVICE);

    assertThat(s.notes()).containsExactly("Skip the host name check isn't allowed on prod.");
    assertThat(s.fields()).isEmpty();

    ConnectionConfig noPassword = Fixtures.config(Env.DEV, "{\"connection\":{\"host\":\"db.test\"},"
        + "\"credentials\":{\"user\":\"gw_ro\"}}");
    Suggestions missing = connector.suggest(noPassword, Fixtures.Secrets.none(), Fixtures.Context.of(Env.DEV),
        Suggestions.Scope.SERVICE);
    assertThat(missing.notes()).contains("Enter the password of gw_ro to list its databases.");
  }
}
