package ai.msrv.gaterook.connectors.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.connectors.support.egress.EgressSpec;
import ai.msrv.gaterook.connectors.support.egress.HostResolver;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Where a backend a person configured may be reached (ARCHITECTURE.md §9): private addresses as before, whatever the
 * environment's HTTP policy; never cloud metadata or GateRook's own listener, by IP literal or by a name that
 * resolves there, on the REST tools, the log tools, Test connection and health checks.
 */
class BackendAddressesTest {

  private static final String QUERY = """
      {"method":"POST","path":"/logs/_search","body":{"query":{"match_all":{}}}}""";
  private static final String LOKI_SEARCH = """
      {"query":"{app=\\"api\\"}","from":"2026-10-22T12:00:00Z","to":"2026-10-22T13:00:00Z","limit":5}""";

  private final MockWebServer server = new MockWebServer();
  private final CloudCredentials cloud = new CloudCredentials(CloudEndpoints.PRODUCTION, Clock.systemUTC());
  private final LogsConnector connector = new LogsConnector(cloud, Clock.systemUTC());
  private final TestSecrets secrets = new TestSecrets().with("/auth/secret", "backend-address-canary");
  /** Names the core's resolver answers itself, like a DNS server a person doesn't control. */
  private final Map<String, String> names = new ConcurrentHashMap<>();
  private final List<EgressClients> clients = new ArrayList<>();
  private int port;

  @BeforeEach
  void start() throws IOException {
    server.start(InetAddress.getByName("127.0.0.1"), 0);
    port = server.getPort();
  }

  @AfterEach
  void stop() {
    clients.forEach(EgressClients::close);
    connector.close();
    cloud.close();
    server.close();
  }

  @Test
  void restToolsNeverReachTheCoreByAddressOrByName() {
    names.put("search.dev.internal", "127.0.0.1");
    for (String host : List.of("127.0.0.1", "localhost", "search.dev.internal")) {
      ConnectionConfig cfg = fullApi("http://" + host + ":" + port);
      assertThatThrownBy(() -> connector.execute(Configs.call("opensearch_query", QUERY), cfg, secrets, core(port)))
          .as(host).isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
            assertThat(e.getMessage()).contains("is GateRook itself");
          });

      ToolCall change = Configs.call("opensearch_request", """
          {"method":"PUT","path":"/logs/_settings","body":{"index":{"number_of_replicas":1}}}""");
      JsonNode approved = connector.describeAction(change, cfg).payload();
      assertThatThrownBy(() -> connector.execute(change, cfg, secrets, core(port).approved(approved)))
          .as("an approved request is refused before it is sent, so its outcome is known")
          .isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED));
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void elasticsearchNeverReachesTheCore() throws Exception {
    ConnectionConfig cfg = new ConnectionConfig("c-es", "es", "elasticsearch", Env.DEV, 1, TestCtx.JSON.readTree("""
        {"backend":"elasticsearch","url":"http://localhost:%d","auth":{"mode":"none"}}""".formatted(port)),
        Set.of(), null);
    try (var elasticsearch = new BackendConnector("elasticsearch", cloud)) {
      assertThatThrownBy(() -> elasticsearch.execute(Configs.call("elasticsearch_query", QUERY), cfg, secrets,
          core(port))).isInstanceOfSatisfying(ConnectorException.class,
              e -> assertThat(e.getMessage()).contains("is GateRook itself"));
    }
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void logToolsNeverReachTheCoreByName() {
    // Answered only if a request got through.
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}").build());

    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", LOKI_SEARCH),
        Configs.loki("http://localhost:" + port), secrets, core(port)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("is GateRook itself");
        });
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void namesThatResolveToCloudMetadataAreRefusedOnEveryPath() {
    names.put("search.dev.internal", "169.254.169.254");
    names.put("loki.dev.internal", "169.254.169.254");

    assertThatThrownBy(() -> connector.execute(Configs.call("opensearch_query", QUERY),
        fullApi("http://search.dev.internal:9200"), secrets, core(port)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("search.dev.internal resolves to 169.254.169.254");
        });
    assertThatThrownBy(() -> connector.execute(Configs.call("logs_search", LOKI_SEARCH),
        Configs.loki("http://loki.dev.internal:3100"), secrets, core(port)))
        .isInstanceOfSatisfying(ConnectorException.class, e -> {
          assertThat(e.kind()).isEqualTo(ConnectorException.Kind.DENIED);
          assertThat(e.getMessage()).contains("loki.dev.internal resolves to 169.254.169.254");
        });
  }

  @Test
  void testConnectionAndHealthExplainTheRefusal() {
    ConnectionConfig cfg = fullApi("http://127.0.0.1:" + port);

    TestResult t = connector.test(cfg, secrets, core(port));
    assertThat(t.ok()).isFalse();
    assertThat(t.error().code()).isEqualTo("logs.refused");
    assertThat(t.error().message()).contains("is GateRook itself");

    HealthResult h = connector.health(Configs.openSearch("http://localhost:" + port), secrets, core(port));
    assertThat(h.state()).isEqualTo(HealthResult.State.UNREACHABLE);
    assertThat(h.message()).contains("is GateRook itself");
    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void privateBackendsStillWorkWithoutAnEnvironmentRange() throws Exception {
    int corePort = port == 7465 ? 7466 : 7465;
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body("{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}").build());
    connector.execute(Configs.call("logs_search", LOKI_SEARCH), Configs.loki("http://127.0.0.1:" + port), secrets,
        core(corePort));

    // The REST transport resolves through the core's resolver and connects to the address it judged.
    names.put("search.dev.internal", "127.0.0.1");
    server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
        .body("{\"hits\":{\"hits\":[]}}").build());
    JsonNode result = connector.execute(Configs.call("opensearch_query", QUERY),
        fullApi("http://search.dev.internal:" + port), secrets, core(corePort)).structuredContent();
    assertThat(result.path("status").asInt()).isEqualTo(200);
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  /**
   * The context of a call in the core: its egress guard names the core's own ports and resolves names; the
   * environment allows no private range (the default), which configured log and search backends don't need.
   */
  private TestCtx core(int corePort) {
    HostResolver resolver = host -> {
      String address = names.get(host);
      return address != null ? List.of(InetAddress.getByName(address)) : HostResolver.SYSTEM.resolve(host);
    };
    EgressClients egress = new EgressClients(new EgressClients.Settings("GateRook/test", Set.of(corePort), resolver));
    clients.add(egress);
    return TestCtx.of().egress(egress.egress(EgressSpec.of("connection:c-search",
        AddressPolicy.publicOnly(Set.of(corePort)))));
  }

  private static ConnectionConfig fullApi(String url) {
    return Configs.of("search", Env.DEV, """
        {"backend":"opensearch","url":"%s","auth":{"mode":"none"}}""".formatted(url));
  }
}
