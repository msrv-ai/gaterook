package ai.msrv.gaterook.e2e.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * ClickHouse as the connector module a release downloads (docs/connector-delivery.md, docs/clickhouse.md): the
 * package of {@code modules/clickhouse}, installed by the assembled core when the type's form is asked for, and run
 * in a module host. Its HTTP goes through the host's guarded client under the grant the core makes of the module's
 * {@code access.hosts}: the connection's host on ClickHouse's two HTTP ports and nowhere else. A stand-in for
 * ClickHouse answers on this computer's port 8123, which the environment's policy must allow first.
 */
class ClickHouseModuleCoreProcessIT {

  private static final String PASSWORD = "cnry-ch-pass-31f0";
  private static BuiltModule module;
  private static MockWebServer clickHouse;

  @TempDir
  static Path home;

  @BeforeAll
  static void start() throws Exception {
    Path built = BuiltModule.packageOf("clickhouse");
    assumeTrue(built != null, "Build modules/clickhouse first: mvn -pl modules/clickhouse -am package");
    clickHouse = new MockWebServer();
    clickHouse.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("{\"meta\":[{\"name\":\"event\",\"type\":\"String\"},{\"name\":\"n\",\"type\":\"UInt64\"}],"
                + "\"data\":[[\"click\",\"120\"]],\"rows\":1,\"statistics\":{\"elapsed\":0.002,\"rows_read\":120}}")
            .build();
      }
    });
    try {
      // The module may reach the connection's host on ClickHouse's HTTP ports only: the stand-in needs one of them.
      clickHouse.start(InetAddress.getByName("127.0.0.1"), 8123);
    } catch (IOException e) {
      clickHouse = null;
      assumeTrue(false, "Port 8123 of this computer is taken: " + e.getMessage());
    }
    module = BuiltModule.start(home, built);
  }

  @AfterAll
  static void stop() {
    if (module != null) {
      module.close();
    }
    if (clickHouse != null) {
      clickHouse.close();
    }
  }

  @Test
  void aQueryGoesThroughTheHostsGuardedClientToTheConnectionsHostAndPort() throws Exception {
    JsonNode card = module.api.card("clickhouse");
    assertThat(card.path("kind").asString()).isEqualTo("built-in");
    assertThat(card.path("tools").asInt()).isEqualTo(6);
    assertThat(card.at("/module/installed").asBoolean()).isFalse();
    JsonNode form = module.api.get("/api/connector-types/clickhouse/form");
    assertThat(form.at("/schema/properties/host").isObject()).as(form.toString()).isTrue();
    assertThat(module.api.module("clickhouse").path("state").asString()).isEqualTo("ready");

    module.api.json(module.api.admin("POST", "/api/connections", """
        {"name":"events-ch","type":"clickhouse","env":"dev",
         "config":{"host":"127.0.0.1","port":8123,"database":"analytics","user":"gw_reader","options":{"tls":false}},
         "secrets":{"/password":{"source":"local","value":"%s"}}}""".formatted(PASSWORD)), 201);
    module.api.endpoint("events", "events-ch");
    assertThat(module.api.modes("events", "events-ch")).as("six reads of one of GateRook's own types")
        .containsEntry("clickhouse_query", "READ_ONLY").containsEntry("clickhouse_list_databases", "READ_ONLY")
        .hasSize(6).containsOnlyKeys("clickhouse_query", "clickhouse_list_databases", "clickhouse_list_tables",
            "clickhouse_describe_table", "clickhouse_explain", "clickhouse_running_queries");

    String query = "{\"connection\":\"events-ch\",\"sql\":\"SELECT event, count() AS n FROM events GROUP BY event\"}";
    JsonNode refused = module.api.call(2, "clickhouse_query", query);
    assertThat(refused.toString()).as("a private address needs the environment's policy").doesNotContain("click\"")
        .contains("127.0.0.1");
    assertThat(clickHouse.takeRequest(300, TimeUnit.MILLISECONDS)).as("nothing was sent").isNull();

    module.api.json(module.api.admin("POST", "/api/connections/events-ch/policy-suggestion",
        "{\"kind\":\"allow-private\",\"cidr\":\"127.0.0.1/32\",\"reason\":\"the e2e ClickHouse stand-in\"}"), 200);
    JsonNode rows = module.api.call(3, "clickhouse_query", query);
    assertThat(rows.at("/result/isError").asBoolean(true)).as(rows.toString()).isFalse();
    assertThat(rows.at("/result/content/0/text").asString()).as(rows.toString()).contains("click").contains("120");
    RecordedRequest seen = clickHouse.takeRequest(5, TimeUnit.SECONDS);
    assertThat(seen).isNotNull();
    assertThat(seen.getTarget()).contains("readonly=1").contains("allow_ddl=0");
    assertThat(seen.getHeaders().get("X-ClickHouse-Key")).as("the password came by the secret callback")
        .isEqualTo(PASSWORD);

    // The connector's own classification: a write never leaves it.
    JsonNode write = module.api.call(4, "clickhouse_query", "{\"connection\":\"events-ch\",\"sql\":\"DROP TABLE "
        + "events\"}");
    assertThat(write.toString()).doesNotContain("\"isError\":false").contains("never changes data");
    assertThat(clickHouse.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    assertThat(module.core.coreLog()).doesNotContain(PASSWORD);
  }
}
