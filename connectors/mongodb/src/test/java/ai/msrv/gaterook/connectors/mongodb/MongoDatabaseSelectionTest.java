package ai.msrv.gaterook.connectors.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import java.util.List;
import org.junit.jupiter.api.Test;

class MongoDatabaseSelectionTest {

  @Test
  void selectionRestrictsNamespacesWithoutWideningCollectionAccess() {
    Namespaces scope = new Namespaces(List.of("orders.public", "inventory.*"), List.of("orders"));
    assertThat(scope.allowed("orders", "public")).isTrue();
    assertThat(scope.allowed("orders", "private")).isFalse();
    assertThat(scope.allowed("inventory", "public")).isFalse();
    assertThat(scope.databaseVisible("inventory")).isFalse();
    assertThat(new Namespaces(List.of(), List.of("admin")).allowed("admin", "system.users")).isFalse();
  }

  @Test
  void aSoleSelectionOverridesTheUriAndMultipleSelectionsRequireAnArgument() {
    MongoSettings one = MongoSettings.of(config("[\"inventory\"]"));
    MongoSettings many = MongoSettings.of(config("[\"orders\",\"inventory\"]"));
    assertThat(one.defaultDatabase()).isEqualTo("inventory");
    assertThat(many.defaultDatabase()).isNull();
    assertThat(MongoSettings.of(Fixtures.uri(Env.DEV, "mongodb://127.0.0.1:1/orders")).defaultDatabase())
        .isEqualTo("orders");
    try (MongoConnector connector = new MongoConnector()) {
      assertThat(connector.classify(Fixtures.call("mongo_find", "{\"collection\":\"items\"}"),
          config("[\"orders\",\"inventory\"]"), Fixtures.policy(Env.DEV)).kind())
          .isEqualTo(Classification.Kind.DENY);
      assertThat(connector.classify(Fixtures.call("mongo_find", "{\"collection\":\"items\"}"),
          config("[\"inventory\"]"), Fixtures.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.READ);
    }
  }

  @Test
  void omittedSelectionPreservesLegacyBehaviorButInvalidSelectionsFailClosed() {
    for (String selection : List.of("[]", "null", "[\"orders\",\"orders\"]", "[3]", "[\"bad.db\"]")) {
      assertThat(MongoSettings.of(config(selection)).usable()).as(selection).isFalse();
    }
  }

  @Test
  void executeRefusesAnUnselectedDatabaseBeforeOpeningASocket() {
    try (MongoConnector connector = new MongoConnector()) {
      var call = Fixtures.call("mongo_find", "{\"database\":\"inventory\",\"collection\":\"items\"}");
      ConnectionConfig cfg = config("[\"orders\"]");
      assertThat(connector.classify(call, cfg, Fixtures.policy(Env.DEV)).kind()).isEqualTo(Classification.Kind.DENY);
      assertThatThrownBy(() -> connector.execute(call, cfg, Fixtures.Secrets.none(), Fixtures.Context.of(Env.DEV)))
          .isInstanceOf(ConnectorException.class).hasMessageContaining("isn't one of this connection's selected databases");
    }
  }

  private static ConnectionConfig config(String selection) {
    return Fixtures.config(Env.DEV, "{\"connection\":{\"uri\":\"mongodb://127.0.0.1:1/orders\",\"databases\":"
        + selection + "}}");
  }
}
