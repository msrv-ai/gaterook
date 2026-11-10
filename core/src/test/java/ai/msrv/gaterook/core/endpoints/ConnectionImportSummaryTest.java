package ai.msrv.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The review card says where a candidate connects, which settings differ from the connector's defaults and where its
 * prepared credentials come from, without opening the editor, and never says a credential's value.
 */
class ConnectionImportSummaryTest {

  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private static final String CANARY = "canary-password-4f8b1c";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path temporary;

  private final Clock clock = mock(Clock.class);
  private final EndpointService endpoints = mock(EndpointService.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(NOW);
    EndpointSnapshot endpoint = mock(EndpointSnapshot.class);
    when(endpoint.id()).thenReturn("ep-source");
    when(endpoint.slug()).thenReturn("dev");
    when(endpoint.enabled()).thenReturn(true);
    when(endpoints.snapshot(anyString())).thenReturn(endpoint);
    ObjectNode schema = (ObjectNode) JSON.readTree("""
        {"type":"object","properties":{
          "connection":{"type":"object","properties":{"host":{"type":"string"},
            "port":{"type":"integer","default":5432},"database":{"type":"string"},
            "tls":{"type":"boolean","default":false,"x-env-default":{"prod":true}}}},
          "credentials":{"type":"object","x-gateway-secret":"login","properties":{"username":{"type":"string"},
            "password":{"type":"string","writeOnly":true}}},
          "replicas":{"type":"array","items":{"type":"object","properties":{"host":{"type":"string"}}}},
          "databases":{"type":"array","items":{"type":"string"}},
          "endpoint":{"type":"string"},"label":{"type":"string"},"mode":{"type":"string","default":"read"}}}
        """);
    ConnectorManifest manifest = ConnectorManifest.builder("pgfake", "1", ConnectorManifest.Kind.BUILT_IN)
        .name("Test SQL").configSchema(schema).build();
    when(registry.type("pgfake")).thenReturn(Optional.of(new ConnectorType(manifest, true, true)));
    when(registry.manifest("pgfake")).thenReturn(Optional.of(manifest));
  }

  private ConnectionImportService service(ConnectionImportFiles files) {
    return new ConnectionImportService(endpoints, mock(EndpointConnections.class), mock(ConnectionService.class),
        registry, mock(EventBus.class), Masker.standard(), clock, mock(PlatformTransactionManager.class), files,
        new LocalListener(() -> 18475));
  }

  private static ObjectNode manifest(String env, String config) {
    ObjectNode manifest = JSON.createObjectNode().put("version", 1);
    ObjectNode candidate = manifest.putArray("connections").addObject().put("id", "db").put("name", "example-db")
        .put("type", "pgfake").put("env", env);
    candidate.set("config", JSON.readTree(config));
    candidate.putArray("evidence").add("compose.yaml:8");
    return manifest;
  }

  private JsonNode summaryOf(String env, String config) {
    ConnectionImportService service = service(mock(ConnectionImportFiles.class));
    var session = service.start("/repositories/example", "dev");
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller", manifest(env, config));
    return service.get(session.id()).manifest().path("connections").get(0).path("summary");
  }

  private static List<String> rows(JsonNode summary) {
    List<String> out = new ArrayList<>();
    summary.path("settings").forEach(row -> out.add(row.path("path").asString() + "=" + row.path("value").asString()
        + (row.path("target").asBoolean() ? " [target]" : "")));
    return out;
  }

  @Test
  void aCandidateCarriesItsTargetAndEverySettingThatIsNotADefault() {
    JsonNode summary = summaryOf("dev", """
        {"connection":{"host":"db.example.test","port":5433,"database":"orders","tls":false},
         "credentials":{"username":"reader"},"replicas":[{"host":"replica.example.test"}],
         "databases":["billing","orders"],"mode":"read","label":"  "}
        """);

    assertThat(summary.path("target").asString()).isEqualTo("db.example.test:5433/orders");
    // Where credentials go comes first; defaults ("port 5432", "tls off", "mode read") and blanks are left out.
    assertThat(rows(summary)).containsExactly("connection.host=db.example.test [target]",
        "replicas[0].host=replica.example.test [target]", "connection.port=5433", "connection.database=orders",
        "credentials.username=reader", "databases=billing, orders");
    assertThat(summary.path("omitted").asInt()).isZero();
  }

  @Test
  void theDefaultOfTheCandidatesEnvironmentIsNotShownAsASetting() {
    String on = "{\"connection\":{\"host\":\"db.example.test\",\"tls\":true}}";
    String off = "{\"connection\":{\"host\":\"db.example.test\",\"tls\":false}}";

    assertThat(rows(summaryOf("prod", on))).containsExactly("connection.host=db.example.test [target]");
    assertThat(rows(summaryOf("dev", on))).containsExactly("connection.host=db.example.test [target]",
        "connection.tls=true");
    // On prod the plain default is the value prod overrides: turning TLS off there is a change the card lists.
    assertThat(rows(summaryOf("prod", off))).containsExactly("connection.host=db.example.test [target]",
        "connection.tls=false");
    assertThat(rows(summaryOf("dev", off))).containsExactly("connection.host=db.example.test [target]");
  }

  /** Gives the test connector another config schema, so a test can lay out definitions no built-in schema has. */
  private void useSchema(String schema) {
    ConnectorManifest manifest = ConnectorManifest.builder("pgfake", "1", ConnectorManifest.Kind.BUILT_IN)
        .name("Test SQL").configSchema((ObjectNode) JSON.readTree(schema)).build();
    when(registry.type("pgfake")).thenReturn(Optional.of(new ConnectorType(manifest, true, true)));
    when(registry.manifest("pgfake")).thenReturn(Optional.of(manifest));
  }

  /**
   * A plugin schema can define one property in several branches, only some of which state a default. The walk does not
   * resolve the branch the config selects, so a value that is the default of one branch only is still listed.
   */
  @Test
  void aValueIsListedWhenABranchThatCanApplyStatesNoDefault() {
    useSchema("""
        {"type":"object","properties":{"connection":{"type":"object","properties":{"host":{"type":"string"}}}},
         "oneOf":[{"properties":{"mode":{"type":"string","default":"a"}}},{"properties":{"mode":{"type":"string"}}}]}
        """);

    assertThat(rows(summaryOf("dev", "{\"connection\":{\"host\":\"db.example.test\"},\"mode\":\"a\"}")))
        .containsExactly("connection.host=db.example.test [target]", "mode=a");
  }

  @Test
  void aValueIsLeftOutWhenEveryBranchThatCanApplyHasItAsTheDefault() {
    useSchema("""
        {"type":"object","properties":{"connection":{"type":"object","properties":{"host":{"type":"string"}}}},
         "oneOf":[{"properties":{"mode":{"type":"string","default":"a"}}},
                  {"properties":{"mode":{"type":"string","default":"a"}}}]}
        """);

    assertThat(rows(summaryOf("dev", "{\"connection\":{\"host\":\"db.example.test\"},\"mode\":\"a\"}")))
        .containsExactly("connection.host=db.example.test [target]");
  }

  /**
   * A boolean schema ({@code true}) states no default and leaves the decision to the other definitions. A property that
   * has no other definition has nothing that could make its value a default, so the card lists it.
   */
  @Test
  void aValueWhoseOnlyDefinitionIsABooleanSchemaIsListed() {
    useSchema("""
        {"type":"object","properties":{"connection":{"type":"object","properties":{"host":{"type":"string"}}},
         "mode":true}}
        """);

    assertThat(rows(summaryOf("dev", "{\"connection\":{\"host\":\"db.example.test\"},\"mode\":\"a\"}")))
        .containsExactly("connection.host=db.example.test [target]", "mode=a");
  }

  /** A proposal's values are not checked against the form's types yet: a port that is no int must not fail a submission. */
  @Test
  void aPortOutsideTheIntegerRangeStillGetsASummaryThatShowsTheValueAsASetting() {
    for (String port : List.of("2147483648", "99999999999", "-99999999999")) {
      JsonNode summary = summaryOf("dev", "{\"connection\":{\"host\":\"db.example.test\",\"port\":" + port + "}}");

      assertThat(summary.path("target").asString()).as(port).isEqualTo("db.example.test");
      assertThat(rows(summary)).as(port).containsExactly("connection.host=db.example.test [target]",
          "connection.port=" + port);
    }
  }

  @Test
  void aCandidateThatNamesNoTargetSaysSo() {
    JsonNode summary = summaryOf("dev", "{\"label\":\"reporting\"}");

    assertThat(summary.path("target").isNull()).isTrue();
    assertThat(rows(summary)).containsExactly("label=reporting");
  }

  @Test
  void longValuesAreCutFromTheEndSoAHostNeverDisappears() {
    String host = "very-long-host-name.internal.example.test";
    String url = "https://" + host + ":8443/" + "x".repeat(900);
    JsonNode summary = summaryOf("dev", "{\"endpoint\":\"" + url + "\",\"label\":\"" + "y".repeat(500) + "\"}");

    String endpoint = summary.path("settings").get(0).path("value").asString();
    assertThat(endpoint).startsWith("https://" + host + ":8443/").endsWith("…").hasSizeLessThanOrEqualTo(512);
    String label = summary.path("settings").get(1).path("value").asString();
    assertThat(label).hasSize(200).endsWith("…");
    // No connection.host names it: the target line is the first setting that says where credentials go.
    assertThat(summary.path("target").asString()).isEqualTo(endpoint);
  }

  @Test
  void invisibleCharactersAreShownAsEscapesNotHidden() {
    String override = Character.toString(0x202E);
    JsonNode summary = summaryOf("dev", "{\"connection\":{\"host\":\"db.example.test\\u202e.evil\"}}");

    assertThat(summary.path("target").asString()).doesNotContain(override).contains("\\u202E");
    assertThat(summary.path("settings").get(0).path("value").asString()).doesNotContain(override);
  }

  @Test
  void aDraftThePersonChangedIsSummarizedByTheSameWordsFromTheConfigAsItStands() {
    ConnectionImportService service = service(mock(ConnectionImportFiles.class));
    var session = service.start("/repositories/example", "dev");
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller",
        manifest("dev", "{\"connection\":{\"host\":\"ai-chosen.example.test\"}}"));

    JsonNode changed = service.summarize(session.id(), "db", "dev",
        JSON.readTree("{\"connection\":{\"host\":\"mine.example.test\",\"port\":5432}}"));

    assertThat(changed.path("target").asString()).isEqualTo("mine.example.test:5432");
    assertThat(rows(changed)).containsExactly("connection.host=mine.example.test [target]");
    assertThatThrownBy(() -> service.summarize(session.id(), "other", "dev", JSON.createObjectNode()))
        .isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> service.summarize(session.id(), "db", "dev", JSON.readTree("[]")))
        .isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> service.summarize(session.id(), "db", "somewhere", JSON.createObjectNode()))
        .isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> service.summarize("ci_000000000000", "db", "dev", JSON.createObjectNode()))
        .isInstanceOf(ProblemException.class);
  }

  @Test
  void aSummaryListsAtMostFortyOtherSettingsAndCountsTheRestButKeepsEveryHost() {
    ConnectionImportService service = service(mock(ConnectionImportFiles.class));
    var session = service.start("/repositories/example", "dev");
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller", manifest("dev", "{}"));
    ObjectNode many = JSON.createObjectNode();
    for (int i = 0; i < 55; i++) many.put("setting" + i, "value" + i);
    many.putObject("connection").put("host", "last-in-the-file.example.test");
    var replicas = many.putArray("replicas");
    for (int i = 0; i < 120; i++) replicas.addObject().put("host", "replica" + i + ".example.test");

    JsonNode summary = service.summarize(session.id(), "db", "dev", many);

    // Every host comes first, up to a hundred; the other settings are cut at forty, and the card says how many remain.
    assertThat(summary.path("settings")).hasSize(140);
    assertThat(summary.path("omitted").asInt()).isEqualTo(21 + 15);
    assertThat(summary.path("settings").get(1).path("path").asString()).isEqualTo("replicas[0].host");
    assertThat(summary.path("settings").get(99).path("target").asBoolean()).isTrue();
    assertThat(summary.path("settings").get(100).path("target").asBoolean()).isFalse();
  }

  @Test
  void aFewHostsAreAllListedWhateverElseTheConfigHolds() {
    ConnectionImportService service = service(mock(ConnectionImportFiles.class));
    var session = service.start("/repositories/example", "dev");
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller", manifest("dev", "{}"));
    ObjectNode many = JSON.createObjectNode();
    for (int i = 0; i < 55; i++) many.put("setting" + i, "value" + i);
    many.putObject("connection").put("host", "last-in-the-file.example.test");

    JsonNode summary = service.summarize(session.id(), "db", "dev", many);

    assertThat(summary.path("settings")).hasSize(41);
    assertThat(summary.path("omitted").asInt()).isEqualTo(15);
    assertThat(summary.path("settings").get(0).path("path").asString()).isEqualTo("connection.host");
  }

  /** One candidate of a file submission: its settings and one prepared credential source. */
  private static ObjectNode withSecret(String id, JsonNode source) {
    ObjectNode candidate = (ObjectNode) manifest("dev", "{\"connection\":{\"host\":\"db.example.test\"}}")
        .path("connections").get(0);
    candidate.put("id", id).put("name", "example-" + id);
    candidate.putObject("secrets").set("/credentials/password", source);
    return candidate;
  }

  @Test
  void preparedCredentialSourcesAreListedAsSourcesAndNoValueReachesTheReview() throws Exception {
    Path credentials = temporary.resolve("db-password.json");
    Files.writeString(credentials, "{\"database\":{\"password\":\"" + CANARY + "-from-file\"}}");
    try (GatewayPaths paths = GatewayPaths.open(temporary.resolve("home"))) {
      ConnectionImportService service = service(new ConnectionImportFiles(paths, JSON));
      var session = service.start("/repositories/example", "dev");
      ObjectNode input = JSON.createObjectNode().put("version", 1);
      input.putArray("connections")
          .add(withSecret("literal", JSON.createObjectNode().put("source", "local").put("value", CANARY)))
          .add(withSecret("file", JSON.createObjectNode().put("source", "file").put("path", credentials.toString())
              .put("jsonPointer", "/database/password")))
          .add(withSecret("reference", JSON.createObjectNode().put("source", "reference")
              .put("ref", "vault://secret/example/dev#password").put("store", "team-vault")));
      Files.writeString(Path.of(session.importFile()), input.toString());

      service.submit(session.id(), session.submissionToken(), "ep-source", "caller", null);

      JsonNode review = service.get(session.id()).manifest();
      assertThat(review.toString()).doesNotContain(CANARY);
      assertThat(review.at("/connections/0/secretSources/0").toString())
          .isEqualTo("{\"field\":\"/credentials/password\",\"source\":\"local\"}");
      assertThat(review.at("/connections/1/secretSources/0/path").asString()).isEqualTo(credentials.toString());
      assertThat(review.at("/connections/1/secretSources/0/jsonPointer").asString()).isEqualTo("/database/password");
      assertThat(review.at("/connections/2/secretSources/0/reference").asString())
          .isEqualTo("vault://secret/example/dev#password");
      assertThat(review.at("/connections/2/secretSources/0/store").asString()).isEqualTo("team-vault");
      for (int i = 0; i < 3; i++) {
        assertThat(review.at("/connections/" + i + "/summary/target").asString()).isEqualTo("db.example.test:5432");
      }
      // A credential file is read when the person imports, never to describe the candidate (its text has the canary too).
      assertThat(review.toString()).doesNotContain("from-file");
    }
  }
}
