package ai.msrv.gaterook.e2e.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.endpoints.ConnectionImportFiles;
import ai.msrv.gaterook.core.endpoints.ConnectionImportService;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.Masker;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The review card of an AI import proposal lists every setting that differs from the connector's default for the
 * candidate's environment, checked against the real schemas of the built-in connectors: on prod, a value equal to the
 * plain default that prod overrides (TLS off) is a change, and a value that is the default of only
 * another branch of the schema is listed too.
 */
class ImportSummaryDefaultsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

  private static Map<String, ConnectorManifest> manifests;

  private final Clock clock = mock(Clock.class);
  private final EndpointService endpoints = mock(EndpointService.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private ConnectionImportService service;

  @BeforeAll
  static void loadConnectorModules() {
    manifests = BuiltInManifests.load();
  }

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(NOW);
    EndpointSnapshot endpoint = mock(EndpointSnapshot.class);
    when(endpoint.id()).thenReturn("ep-source");
    when(endpoint.slug()).thenReturn("dev");
    when(endpoint.enabled()).thenReturn(true);
    when(endpoints.snapshot(anyString())).thenReturn(endpoint);
    for (ConnectorManifest manifest : manifests.values()) {
      ConnectorType type = new ConnectorType(manifest, true, true);
      when(registry.type(manifest.id())).thenReturn(Optional.of(type));
      when(registry.manifest(manifest.id())).thenReturn(Optional.of(manifest));
    }
    service = new ConnectionImportService(endpoints, mock(EndpointConnections.class), mock(ConnectionService.class),
        registry, mock(EventBus.class), Masker.standard(), clock, mock(PlatformTransactionManager.class),
        mock(ConnectionImportFiles.class), new LocalListener(() -> 18475));
  }

  /** The {@code path=value} rows of the review card of one candidate. */
  private List<String> rows(String type, String env, String config) {
    ObjectNode manifest = JSON.createObjectNode().put("version", 1);
    ObjectNode candidate = manifest.putArray("connections").addObject().put("id", "candidate")
        .put("name", "example-" + type).put("type", type).put("env", env);
    candidate.set("config", JSON.readTree(config));
    candidate.putArray("evidence").add("deploy/config.yaml:12");
    var session = service.start("/repositories/example", "dev");
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller", manifest);
    JsonNode summary = service.get(session.id()).manifest().path("connections").get(0).path("summary");
    List<String> out = new ArrayList<>();
    summary.path("settings").forEach(row -> out.add(row.path("path").asString() + "=" + row.path("value").asString()));
    return out;
  }

  @Test
  void redisTlsIsListedWhenItIsNotTheDefaultOfTheCandidatesEnvironment() {
    String off = "{\"connection\":{\"host\":\"cache.example.test\",\"tls\":false}}";
    String on = "{\"connection\":{\"host\":\"cache.example.test\",\"tls\":true}}";
    String auto = "{\"connection\":{\"host\":\"cache.example.test\",\"tls\":\"auto\"}}";

    assertThat(rows("redis", "prod", off)).containsExactly("connection.host=cache.example.test",
        "connection.tls=false");
    assertThat(rows("redis", "prod", on)).containsExactly("connection.host=cache.example.test");
    // Dev's default is Auto: a transport fixed either way is listed.
    assertThat(rows("redis", "dev", on)).containsExactly("connection.host=cache.example.test", "connection.tls=true");
    assertThat(rows("redis", "dev", off)).containsExactly("connection.host=cache.example.test",
        "connection.tls=false");
    assertThat(rows("redis", "dev", auto)).containsExactly("connection.host=cache.example.test");
  }

  @Test
  void mongoTlsAndReadPreferenceAreListedWhenTheyAreNotTheDefaultOfTheCandidatesEnvironment() {
    String off = "{\"connection\":{\"host\":\"mongo.example.test\",\"tls\":false}}";
    String on = "{\"connection\":{\"host\":\"mongo.example.test\",\"tls\":true}}";

    String auto = "{\"connection\":{\"host\":\"mongo.example.test\",\"tls\":\"auto\"}}";
    assertThat(rows("mongodb", "prod", off)).contains("connection.tls=false");
    assertThat(rows("mongodb", "prod", on)).noneMatch(row -> row.startsWith("connection.tls="));
    // Dev's default is Auto: a transport fixed either way is listed.
    assertThat(rows("mongodb", "dev", on)).contains("connection.tls=true");
    assertThat(rows("mongodb", "dev", off)).contains("connection.tls=false");
    assertThat(rows("mongodb", "dev", auto)).noneMatch(row -> row.startsWith("connection.tls="));

    String primary = "{\"connection\":{\"host\":\"mongo.example.test\",\"readPreference\":\"primary\"}}";
    String secondary = "{\"connection\":{\"host\":\"mongo.example.test\",\"readPreference\":\"secondaryPreferred\"}}";
    assertThat(rows("mongodb", "prod", primary)).contains("connection.readPreference=primary");
    assertThat(rows("mongodb", "prod", secondary)).noneMatch(row -> row.startsWith("connection.readPreference="));
    assertThat(rows("mongodb", "dev", primary)).noneMatch(row -> row.startsWith("connection.readPreference="));
    assertThat(rows("mongodb", "dev", secondary)).contains("connection.readPreference=secondaryPreferred");
  }

  @Test
  void sshRunbooksOnlyOnIsListedOnEveryEnvironment() {
    String off = "{\"connection\":{\"host\":\"ssh.example.test\",\"username\":\"reader\"},\"runbooksOnly\":false}";
    String on = "{\"connection\":{\"host\":\"ssh.example.test\",\"username\":\"reader\"},\"runbooksOnly\":true}";

    for (String env : List.of("dev", "prod")) {
      assertThat(rows("ssh", env, on)).as(env).contains("runbooksOnly=true");
      assertThat(rows("ssh", env, off)).as(env).noneMatch(row -> row.startsWith("runbooksOnly="));
    }
  }

  /**
   * The Loki branch of the logs schema defaults to a bearer token and the OpenSearch branch to a username and
   * password. The card does not resolve the branch, so either mode is a change under one of them and is listed.
   */
  @Test
  void aLogsAuthenticationModeThatIsTheDefaultOfAnotherBackendIsListed() {
    assertThat(rows("logs", "dev", "{\"backend\":\"loki\",\"url\":\"https://loki.example.test\","
        + "\"auth\":{\"mode\":\"basic\"}}")).contains("auth.mode=basic");
    assertThat(rows("logs", "dev", "{\"backend\":\"opensearch\",\"url\":\"https://search.example.test:9200\","
        + "\"auth\":{\"mode\":\"bearer\"}}")).contains("auth.mode=bearer");
  }

  /** A branch that only allows or forbids a setting (a boolean schema) leaves the default to the branch that sets one. */
  @Test
  void aDefaultThatOnlyOneBranchStatesIsStillLeftOut() {
    assertThat(rows("mongodb", "dev", "{\"connection\":{\"host\":\"mongo.example.test\",\"port\":27017}}"))
        .containsExactly("connection.host=mongo.example.test");
    assertThat(rows("mongodb", "dev", "{\"connection\":{\"host\":\"mongo.example.test\",\"port\":27018}}"))
        .containsExactly("connection.host=mongo.example.test", "connection.port=27018");
  }
}
