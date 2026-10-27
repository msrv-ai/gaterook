package ai.msrv.gaterook.pluginit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The catalog's ClickHouse and RabbitMQ plugins (step K6) in a real host, like Jira: the host loads the release JAR the
 * catalog lists, the plugin reads its password through the secret callback, reaches the fake server only on the port
 * the grant names, and the password leaves no trace in the result. A write to ClickHouse and an unapproved publish to
 * RabbitMQ are refused inside the plugin, before anything is sent.
 */
class CatalogPluginsIT {

  @TempDir
  Path home;

  private MockWebServer server;
  private Harness host;

  @AfterEach
  void stop() throws Exception {
    if (host != null) {
      host.close();
    }
    if (server != null) {
      server.close();
    }
  }

  @Test
  void clickHouseQueriesReadOnlyWithItsPasswordFromTheCore() throws Exception {
    server = serve(request -> new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body("{\"meta\":[{\"name\":\"event\",\"type\":\"String\"},{\"name\":\"n\",\"type\":\"UInt64\"}],"
            + "\"data\":[[\"click\",\"120\"]],\"rows\":1,\"statistics\":{\"elapsed\":0.002,\"rows_read\":120}}")
        .build());
    host = Harness.start("clickhouse@0.6.1", Harness.pluginJar("clickhouse", "gw-clickhouse-0.6.1.jar"), home, true);
    assertThat(host.alive()).isTrue();

    ObjectNode c = GppJson.object();
    c.put("host", "127.0.0.1");
    c.put("port", server.getPort());
    c.put("database", "analytics");
    c.put("user", "gw_reader");
    c.putObject("options").put("tls", false);
    ConnectionConfig cfg = new ConnectionConfig("c_ch", "events-ch", "clickhouse", Env.DEV, 1, c,
        Set.of("/password"), null);

    JsonNode result = host.execute("clickhouse@0.6.1", call("clickhouse_query", "events-ch",
        "{\"sql\":\"SELECT event, count() AS n FROM events GROUP BY event\"}"), cfg, false, grant(server),
        Map.of("/password", "cnry-ch-pass-31f0"), Duration.ofSeconds(10));
    var tool = WireCodec.result(result);
    assertThat(tool.isError()).isFalse();
    assertThat(tool.table().rows().get(0).get(0).asString()).isEqualTo("click");
    assertThat(result.toString()).doesNotContain("cnry-ch-pass");
    RecordedRequest seen = server.takeRequest(2, TimeUnit.SECONDS);
    assertThat(seen.getTarget()).contains("readonly=1").contains("allow_ddl=0");
    assertThat(seen.getHeaders().get("X-ClickHouse-Key")).isEqualTo("cnry-ch-pass-31f0");

    assertThatThrownBy(() -> host.execute("clickhouse@0.6.1", call("clickhouse_query", "events-ch",
        "{\"sql\":\"DROP TABLE events\"}"), cfg, false, grant(server), Map.of("/password", "cnry-ch-pass-31f0"),
        Duration.ofSeconds(10))).isInstanceOf(Harness.ProtocolError.class).hasMessageContaining("never changes data");
    assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void rabbitMqReadsQueuesAndPublishesOnlyWhenApproved() throws Exception {
    server = serve(request -> {
      if (request.getTarget().startsWith("/api/queues/%2F")) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("{\"page\":1,\"page_count\":1,\"filtered_count\":1,\"items\":[{\"name\":\"orders\","
                + "\"messages\":12,\"messages_ready\":10,\"messages_unacknowledged\":2,\"consumers\":1,"
                + "\"state\":\"running\",\"type\":\"quorum\",\"arguments\":{}}]}").build();
      }
      if (request.getTarget().equals("/api/exchanges/%2F/orders/publish")) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("{\"routed\":true}").build();
      }
      return new MockResponse.Builder().code(404).body("{\"error\":\"Object Not Found\"}").build();
    });
    host = Harness.start("rabbitmq@0.3.0", Harness.pluginJar("rabbitmq", "gw-rabbitmq-0.3.0.jar"), home, true);
    ObjectNode c = GppJson.object();
    c.put("host", "127.0.0.1");
    c.put("port", server.getPort());
    c.put("vhost", "/");
    c.put("user", "gw-monitor");
    ConnectionConfig cfg = new ConnectionConfig("c_rmq", "orders-rabbit", "rabbitmq", Env.DEV, 1, c,
        Set.of("/password"), null);
    Map<String, String> secret = Map.of("/password", "cnry-rmq-pass-7d2c");

    var queues = WireCodec.result(host.execute("rabbitmq@0.3.0", call("rabbitmq_queues", "orders-rabbit", "{}"),
        cfg, false, grant(server), secret, Duration.ofSeconds(10)));
    assertThat(queues.table().rows().get(0).get(0).asString()).isEqualTo("orders");
    assertThat(server.takeRequest(2, TimeUnit.SECONDS).getHeaders().get("Authorization")).startsWith("Basic ");

    String publish = "{\"exchange\":\"orders\",\"routingKey\":\"orders.retry\",\"payload\":\"{}\"}";
    assertThatThrownBy(() -> host.execute("rabbitmq@0.3.0", call("rabbitmq_publish", "orders-rabbit", publish), cfg,
        false, grant(server), secret, Duration.ofSeconds(10))).isInstanceOf(Harness.ProtocolError.class)
        .hasMessageContaining("approved");
    assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();

    JsonNode published = host.execute("rabbitmq@0.3.0", call("rabbitmq_publish", "orders-rabbit", publish), cfg,
        true, grant(server), secret, Duration.ofSeconds(10));
    assertThat(WireCodec.result(published).structuredContent().path("routed").asBoolean()).isTrue();
    assertThat(published.toString()).doesNotContain("cnry-rmq-pass");
    assertThat(server.takeRequest(2, TimeUnit.SECONDS).getMethod()).isEqualTo("POST");
  }

  private interface Answer {
    MockResponse to(RecordedRequest request);
  }

  private static MockWebServer serve(Answer answer) throws Exception {
    MockWebServer s = new MockWebServer();
    s.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        return answer.to(request);
      }
    });
    s.start(InetAddress.getByName("127.0.0.1"), 0);
    return s;
  }

  /** The fake server's port only, as the core grants a connection's host (plain HTTP to an allowed private range). */
  private static Messages.EgressGrant grant(MockWebServer server) {
    return new Messages.EgressGrant(List.of("127.0.0.1:" + server.getPort()), List.of("127.0.0.0/8"),
        List.of(7465, 7466));
  }

  private static ToolCall call(String tool, String connection, String json) {
    return new ToolCall("tc_1", tool, connection, (ObjectNode) GppJson.MAPPER.readTree(json), null);
  }
}
