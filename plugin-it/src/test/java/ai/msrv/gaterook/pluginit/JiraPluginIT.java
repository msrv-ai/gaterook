package ai.msrv.gaterook.pluginit;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The Jira Service Desk plugin in a real host against Jira's REST in MockWebServer (ARCHITECTURE.md §10 K4): the host
 * spawns, loads the 1.3.0 JAR, reads its {@code apiToken} through the secret callback, and runs {@code
 * jira_search_requests} end to end — the request reaches the mock, the result comes back, and the token leaves no
 * trace in the result.
 */
class JiraPluginIT {

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
  void loadsReadsItsSecretAndCallsJira() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        if (request.getTarget().startsWith("/rest/servicedeskapi/request")) {
          return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""
              {"size":1,"start":0,"limit":20,"isLastPage":true,"values":[
               {"issueKey":"SD-123","serviceDeskId":"1","currentStatus":{"status":"Open"},
                "createdDate":{"iso8601":"2026-10-22T14:30:00+0200"},"reporter":{"displayName":"Ann Lee"},
                "requestFieldValues":[{"fieldId":"summary","value":"Payment worker stuck"}]}]}""").build();
        }
        return new MockResponse.Builder().code(404).body("{}").build();
      }
    });
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    int port = server.getPort();

    host = Harness.start("jira-service-desk@1.3.0", Harness.jiraJar("1.3.0"), home, true);
    assertThat(host.alive()).isTrue();

    ConnectionConfig cfg = connection(port);
    ToolCall call = new ToolCall("tc_1", "jira_search_requests", "acme-jsm",
        (ObjectNode) json("{\"searchTerm\":\"payment\",\"limit\":20}"), null);
    Messages.EgressGrant egress = new Messages.EgressGrant(List.of("127.0.0.1:" + port),
        List.of("127.0.0.0/8"), List.of(7465, 7466));

    JsonNode result = host.execute("jira-service-desk@1.3.0", call, cfg, false, egress,
        Map.of("/apiToken", "ATATT-cnry-token-5c1d9e"), Duration.ofSeconds(10));

    var tool = WireCodec.result(result);
    assertThat(tool.isError()).isFalse();
    assertThat(tool.table().rows().get(0).get(0).asString()).isEqualTo("SD-123");
    assertThat(result.toString()).doesNotContain("ATATT-cnry-token");
    RecordedRequest seen = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(seen.getHeaders().get("Authorization")).startsWith("Basic ");
  }

  private static ConnectionConfig connection(int port) {
    ObjectNode c = GppJson.object();
    c.put("baseUrl", "http://127.0.0.1:" + port);
    c.put("deployment", "cloud");
    c.put("email", "svc-gateway@acme.com");
    c.putArray("serviceDeskIds");
    c.put("timeoutMs", 8000);
    return new ConnectionConfig("c_jsm", "acme-jsm", "jira-service-desk", Env.DEV, 1, c, Set.of("/apiToken"), null);
  }

  private static JsonNode json(String text) {
    return GppJson.MAPPER.readTree(text);
  }
}
