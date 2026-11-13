package com.gaterook.pluginit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.Env;
import com.gaterook.connector.api.ToolCall;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.WireCodec;
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
 * The catalog's RabbitMQ plugin in a real host, like Jira: the host loads the package as it is released, the code
 * reads its password through the secret callback, reaches the fake server only on the port the grant names, and the
 * password leaves no trace in the result. An unapproved publish is refused inside the connector, before anything is
 * sent.
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
