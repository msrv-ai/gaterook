package ai.msrv.gaterook.core.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.SecretInput;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ConnectionImportServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
  private final JsonMapper json = JsonMapper.builder().build();
  private final Clock clock = mock(Clock.class);
  private final EndpointService endpoints = mock(EndpointService.class);
  private final EndpointConnections wiring = mock(EndpointConnections.class);
  private final ConnectionService connections = mock(ConnectionService.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final EventBus events = mock(EventBus.class);
  private final ConnectionImportFiles files = mock(ConnectionImportFiles.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final ConnectionImportService service = new ConnectionImportService(endpoints, wiring, connections,
      registry, events, Masker.standard(), clock, transactions, files, new LocalListener(() -> 18475));

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(NOW);
    EndpointSnapshot endpoint = mock(EndpointSnapshot.class);
    when(endpoint.id()).thenReturn("ep-source");
    when(endpoint.slug()).thenReturn("dev");
    when(endpoint.enabled()).thenReturn(true);
    when(endpoints.snapshot(anyString())).thenReturn(endpoint);
    when(endpoints.uncachedSnapshot(anyString())).thenReturn(endpoint);
    when(connections.withStores(any())).thenAnswer(call -> new LinkedHashMap<>(call.<Map<String, SecretInput>>getArgument(0)));
    when(files.create(anyString())).thenAnswer(i -> "/private-imports/" + i.getArgument(0) + ".json");
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    ObjectNode schema = (ObjectNode) json.readTree("""
        {"type":"object","properties":{"host":{"type":"string"},"credentials":{"type":"object","x-gateway-secret":"login",
        "properties":{"username":{"type":"string"},"password":{"type":"string","writeOnly":true}}},
        "replicas":{"type":"array","items":{"type":"object","properties":{"host":{"type":"string"}}}}}}
        """);
    ConnectorManifest manifest = ConnectorManifest.builder("pgfake", "1", ConnectorManifest.Kind.BUILT_IN)
        .name("Test SQL").configSchema(schema).build();
    ConnectorType type = new ConnectorType(manifest, true, true);
    when(registry.type("pgfake")).thenReturn(Optional.of(type));
    when(registry.manifest("pgfake")).thenReturn(Optional.of(manifest));
    when(registry.types()).thenReturn(List.of(type));
  }

  @Test
  void discoveryOnlyStagesDefensiveCopiesAndDoesNotTouchConnections() {
    var session = start();
    assertThat(session.endpointUrl()).isEqualTo("http://127.0.0.1:18475/mcp/dev");
    ObjectNode manifest = manifest();
    ObjectNode result = submit(session, manifest);
    assertThat(result.path("count").asInt()).isEqualTo(1);
    assertThat(result.toString()).doesNotContain(session.submissionToken(), "credentials", "private-imports");
    manifest.put("version", 99);
    var reviewed = service.get(session.id());
    assertThat(reviewed.endpointUrl()).isEqualTo(session.endpointUrl());
    assertThat(reviewed.status()).isEqualTo("review");
    assertThat(reviewed.manifest().path("version").asInt()).isEqualTo(1);
    ((ObjectNode) reviewed.manifest()).put("version", 98);
    assertThat(service.get(session.id()).manifest().path("version").asInt()).isEqualTo(1);
    verify(events).publish(eq("ui.open"), any());
    verifyNoInteractions(connections, wiring);
  }

  @Test
  void schemaAndSubmitRequireTheUserCapabilityAndItsOriginalEndpoint() {
    var session = start();
    for (String endpoint : List.of("other-endpoint", "")) {
      assertThatThrownBy(() -> service.submit(session.id(), session.submissionToken(), endpoint, "caller", manifest()))
          .isInstanceOf(ProblemException.class).hasMessageContaining("unavailable");
    }
    assertThatThrownBy(() -> service.schema(session.id(), "wrong", "ep-source", "pgfake"))
        .isInstanceOf(ProblemException.class).hasMessageContaining("unavailable");
    assertThat(service.schema(session.id(), session.submissionToken(), "ep-source", "pgfake")
        .path("secretFields").get(0).asString()).isEqualTo("/credentials/password");
    assertThat(service.schema(session.id(), session.submissionToken(), "ep-source", "pgfake")
        .path("referenceOnlyFields")).isEqualTo(json.readTree("""
        [{"pointer":"/credentials/username","kind":"login-user","allowedSources":["reference"],
          "passwordFields":["/credentials/password"]}]
        """));
    verifyNoInteractions(connections, wiring, events);
  }

  @Test
  void storeCatalogAndProposalsAreSafeMetadataWithoutAnyAutomaticConnect() {
    var session = start();
    assertThat(service.schema(session.id(), session.submissionToken(), "ep-source", null)
        .path("storeKinds").get(0).path("secretFields").toString()).isEqualTo("[\"/auth/token\",\"/auth/secretId\"]");
    ObjectNode proposal = manifest();
    store(proposal);
    submit(session, proposal);
    assertThat(service.get(session.id()).manifest().path("stores").size()).isEqualTo(1);
    verifyNoInteractions(connections, wiring);
    for (String bad : List.of("{\"auth\":{\"token\":\"private-value\"}}", "{\"auth\":{\"file\":\"/tmp/token\"}}",
        "{\"address\":\"https://user:password@vault.test\"}", "{\"allowPlainHttp\":true}")) {
      var rejected = start();
      ObjectNode input = manifest();
      store(input).set("config", json.readTree(bad));
      assertThatThrownBy(() -> submit(rejected, input)).isInstanceOf(ProblemException.class);
      assertThat(service.get(rejected.id()).status()).isEqualTo("awaiting");
    }
  }

  @Test
  void storeConnectConsumesOnlyExplicitMatchingPreparedFieldsAndRetryReturnsTheSameStore() {
    var session = start();
    ObjectNode input = manifest();
    store(input);
    var token = mock(ConnectionImportFiles.StagedSecret.class);
    var unused = mock(ConnectionImportFiles.StagedSecret.class);
    when(token.resolve()).thenReturn(SecretInput.local("prepared-vault-value".toCharArray()));
    when(files.read(session.id())).thenReturn(new ConnectionImportFiles.Prepared(input, Map.of(),
        Map.of("vault-discovery", Map.of("/auth/token", token, "/auth/secretId", unused))));
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller", null);
    verifyNoInteractions(token, unused);
    assertThatThrownBy(() -> service.connectStore(session.id(), "vault-discovery", "token",
        List.of("/auth/secretId"), null, (proposal, values) -> { throw new AssertionError(); })).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> service.connectStore(session.id(), "vault-discovery", "token",
        List.of(), Map.of("/auth/secretId", "unused-value".toCharArray()), (proposal, values) -> { throw new AssertionError(); }))
        .isInstanceOf(ProblemException.class);
    char[][] captured = new char[1][];
    assertThat(service.connectStore(session.id(), "vault-discovery", "token", List.of("/auth/token"), null, (proposal, values) -> {
      captured[0] = values.get("/auth/token");
      assertThat(new String(captured[0])).isEqualTo("prepared-vault-value");
      return "store-connected";
    })).isEqualTo("store-connected");
    assertThat(captured[0]).containsOnly('\0');
    assertThat(service.connectStore(session.id(), "vault-discovery", "token", List.of("/auth/token"), null,
        (proposal, values) -> { throw new AssertionError("A retry must not create or resolve again"); })).isEqualTo("store-connected");
    verify(token, times(1)).resolve();
    verify(unused, never()).resolve();
    verify(unused).clear();
    assertThat(service.get(session.id()).manifest().at("/stores/0/connectedStore").asString()).isEqualTo("store-connected");
  }

  @Test
  void reviewedStoreOverrideDoesNotReadTheOriginalSourceFile() {
    var session = start();
    ObjectNode input = manifest();
    store(input);
    var source = mock(ConnectionImportFiles.StagedSecret.class);
    when(files.read(session.id())).thenReturn(new ConnectionImportFiles.Prepared(input, Map.of(),
        Map.of("vault-discovery", Map.of("/auth/token", source))));
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller", null);
    char[] override = "person-entered-token".toCharArray();
    service.connectStore(session.id(), "vault-discovery", "token", List.of("/auth/token"),
        Map.of("/auth/token", override), (proposal, values) -> {
          assertThat(new String(values.get("/auth/token"))).isEqualTo("person-entered-token");
          return "store-connected";
        });
    verify(source, never()).resolve();
    verify(source).clear();
    assertThat(new String(override)).isEqualTo("person-entered-token");
  }

  private ObjectNode store(ObjectNode input) {
    ObjectNode store = input.putArray("stores").addObject().put("id", "vault-discovery").put("kind", "vault")
        .put("name", "discovered-vault");
    store.putObject("config").put("address", "https://vault.example.test").putObject("auth").put("method", "token");
    return store;
  }

  @Test
  void rejectsSecretFieldsUnknownFieldsStoredIdsAndAuthenticatedUrlsBeforeKeepingAnything() {
    var session = start();
    for (String config : List.of("{\"credentials\":{\"password\":\"example\"}}",
        "{\"credentials\":{\"password\":null}}", "{\"host\":{\"$secret\":\"secret-id\"}}",
        "{\"invented\":\"field\"}", "{\"replicas\":[{\"host\":\"https://person:password@db.invalid\"}]}",
        "{\"host\":\"https://db.invalid/?token=hidden\"}")) {
      ObjectNode manifest = manifest();
      ((ObjectNode) manifest.path("connections").get(0)).set("config", json.readTree(config));
      assertThatThrownBy(() -> submit(session, manifest)).isInstanceOf(ProblemException.class);
      assertThat(service.get(session.id()).status()).isEqualTo("awaiting");
    }
    verifyNoInteractions(connections, wiring, events);
  }

  /** A reference in a field would read that store at call time: only a person sets one, in the connection form. */
  @Test
  void rejectsStoreReferencesInCandidateConfig() {
    var session = start();
    for (String config : List.of("{\"credentials\":{\"username\":\"vault://kv/prod/db#password\"}}",
        "{\"credentials\":{\"username\":\" AWS-SM://eu-central-1/prod/db#password\"}}",
        "{\"replicas\":[{\"host\":\"gcp-sm://projects/acme/secrets/db/versions/latest\"}]}")) {
      ObjectNode manifest = manifest();
      ((ObjectNode) manifest.path("connections").get(0)).set("config", json.readTree(config));
      assertThatThrownBy(() -> submit(session, manifest)).isInstanceOf(ProblemException.class)
          .hasMessageContaining("Secret references belong in credentialHints");
      assertThat(service.get(session.id()).status()).isEqualTo("awaiting");
    }
    verifyNoInteractions(connections, wiring, events);
  }

  @Test
  void replayIsIdempotentButAnotherCallerOrChangedManifestCannotReplaceTheProposal() {
    var session = start();
    submit(session, manifest());
    submit(session, manifest());
    verify(events, times(1)).publish(eq("ui.open"), any());
    assertThatThrownBy(() -> service.submit(session.id(), session.submissionToken(), "ep-source", "another", manifest()))
        .isInstanceOf(ProblemException.class);
    ObjectNode changed = manifest();
    ((ObjectNode) changed.path("connections").get(0)).put("name", "changed-name");
    assertThatThrownBy(() -> submit(session, changed)).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> service.open(session.id(), "ep-source", "another")).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> service.open(session.id(), "another", "caller")).isInstanceOf(ProblemException.class);
    when(clock.instant()).thenReturn(NOW.plusSeconds(4));
    assertThat(service.open(session.id(), "ep-source", "caller").path("count").asInt()).isEqualTo(1);
    verify(events, times(2)).publish(eq("ui.open"), any());
    verifyNoInteractions(connections, wiring);
  }

  @Test
  void cancelledAndExpiredSessionsLoseTheirCapabilitiesAndFiles() {
    var cancelled = start();
    service.cancel(cancelled.id());
    assertThatThrownBy(() -> submit(cancelled, manifest())).isInstanceOf(ProblemException.class);
    verify(files).delete(cancelled.id());
    var expired = start();
    when(clock.instant()).thenReturn(NOW.plus(ConnectionImportService.LIFETIME));
    service.expireSessions();
    assertThatThrownBy(() -> service.get(expired.id())).isInstanceOf(ProblemException.class);
    verify(files).delete(expired.id());
  }

  @Test
  void boundsSessionCountAndRejectsDuplicateCandidateIds() {
    for (int i = 0; i < ConnectionImportService.MAX_SESSIONS; i++) start();
    assertThatThrownBy(this::start).isInstanceOf(ProblemException.class).hasMessageContaining("Cancel an import");
    service.close();
    var session = start();
    ObjectNode manifest = manifest();
    ((tools.jackson.databind.node.ArrayNode) manifest.path("connections")).add(manifest.path("connections").get(0).deepCopy());
    assertThatThrownBy(() -> submit(session, manifest)).isInstanceOf(ProblemException.class).hasMessageContaining("unique");
  }

  @Test
  void incompleteConfigIsReviewableAndUnknownTypesAreExplicitlyUnsupported() {
    var session = start();
    ObjectNode manifest = manifest();
    ObjectNode candidate = (ObjectNode) manifest.path("connections").get(0);
    candidate.put("type", "unsupported-service").set("config", json.createObjectNode());
    submit(session, manifest);
    var result = service.get(session.id()).manifest().path("connections").get(0);
    assertThat(result.path("supported").asBoolean()).isFalse();
    assertThat(result.path("unsupportedReason").asString()).contains("dedicated setup");
    assertThatThrownBy(() -> service.commit(session.id(), "dev", List.of(selection("dev")), null))
        .isInstanceOf(ProblemException.class).hasMessageContaining("dedicated setup");
    verifyNoInteractions(connections, wiring);
  }

  @Test
  void productionEnvironmentCannotBeDowngradedInTheReviewRequest() {
    var session = start();
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.path("connections").get(0)).put("env", "prod");
    submit(session, manifest);
    assertThatThrownBy(() -> service.commit(session.id(), "dev", List.of(selection("dev")), null))
        .isInstanceOf(ProblemException.class).hasMessageContaining("Production");
    verifyNoInteractions(connections, wiring);
  }

  @Test
  void fileSourcesAreInertUntilAdminCommitAndValuesNeverEnterTheReviewManifest() {
    var session = start();
    ObjectNode manifest = manifest();
    ((ObjectNode) manifest.path("connections").get(0)).putArray("secretSources").addObject()
        .put("field", "/credentials/password").put("source", "file").put("path", "/credentials/db.txt");
    ConnectionImportFiles.StagedSecret staged = mock(ConnectionImportFiles.StagedSecret.class);
    when(staged.resolve()).thenAnswer(i -> SecretInput.local("private-test-value".toCharArray()));
    when(files.read(session.id())).thenReturn(new ConnectionImportFiles.Prepared(manifest,
        Map.of("db", Map.of("/credentials/password", staged))));
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller", null);
    verify(staged, never()).resolve();
    assertThat(service.get(session.id()).manifest().toString()).contains("/credentials/db.txt")
        .doesNotContain("private-test-value");
    ConnectionService.Summary created = mock(ConnectionService.Summary.class);
    when(created.name()).thenReturn("reviewed-db");
    when(connections.create(anyString(), eq("pgfake"), eq(Env.DEV), any(), any(), any(), any())).thenReturn(created);
    assertThat(service.commit(session.id(), "dev", List.of(selection("dev")), null).connections()).containsExactly("reviewed-db");
    verify(staged, times(1)).resolve();
    verify(staged).clear();
    verify(wiring).add("dev", "reviewed-db", null);
    assertThat(service.get(session.id()).status()).isEqualTo("imported");
    assertThatThrownBy(() -> service.commit(session.id(), "dev", List.of(selection("dev")), null))
        .isInstanceOf(ProblemException.class);
  }

  @Test
  void rejectsStagedCredentialsOutsideActualConnectorSecretFields() {
    var session = start();
    ConnectionImportFiles.StagedSecret staged = mock(ConnectionImportFiles.StagedSecret.class);
    when(files.read(session.id())).thenReturn(new ConnectionImportFiles.Prepared(manifest(),
        Map.of("db", Map.of("/host", staged))));
    assertThatThrownBy(() -> service.submit(session.id(), session.submissionToken(), "ep-source", "caller", null))
        .isInstanceOf(ProblemException.class).hasMessageContaining("secret field");
    verify(staged).clear();
    verify(staged, never()).resolve();
    assertThat(service.get(session.id()).status()).isEqualTo("awaiting");
  }

  @Test
  void loginUserReferencesStageWithoutResolvingOrCreatingAnything() {
    var session = start();
    ConnectionImportFiles.StagedSecret staged = mock(ConnectionImportFiles.StagedSecret.class);
    when(staged.source()).thenReturn("reference");
    when(files.read(session.id())).thenReturn(new ConnectionImportFiles.Prepared(manifest(),
        Map.of("db", Map.of("/credentials/username", staged))));
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller", null);
    assertThat(service.get(session.id()).status()).isEqualTo("review");
    verify(staged, never()).resolve();
    verifyNoInteractions(connections, wiring);
  }

  @Test
  void loginUsersCannotStageLiteralsFilesOrDynamicCredentials() {
    for (String source : List.of("local", "file", "vault-dynamic")) {
      var session = start();
      ConnectionImportFiles.StagedSecret staged = mock(ConnectionImportFiles.StagedSecret.class);
      when(staged.source()).thenReturn(source);
      when(files.read(session.id())).thenReturn(new ConnectionImportFiles.Prepared(manifest(),
          Map.of("db", Map.of("/credentials/username", staged))));
      assertThatThrownBy(() -> service.submit(session.id(), session.submissionToken(), "ep-source", "caller", null))
          .isInstanceOf(ProblemException.class).hasMessageContaining("only an external-store reference");
      assertThat(service.get(session.id()).status()).isEqualTo("awaiting");
      verify(staged, never()).resolve();
      verify(staged).clear();
    }
    verifyNoInteractions(connections, wiring);
  }

  @Test
  void aReviewedOverrideDoesNotReadOrResolveTheOriginalCredentialSource() {
    var session = start();
    ConnectionImportFiles.StagedSecret original = mock(ConnectionImportFiles.StagedSecret.class);
    when(original.resolve()).thenThrow(new IllegalStateException("This source must not be read"));
    when(files.read(session.id())).thenReturn(new ConnectionImportFiles.Prepared(manifest(),
        Map.of("db", Map.of("/credentials/password", original))));
    service.submit(session.id(), session.submissionToken(), "ep-source", "caller", null);
    assertThatThrownBy(() -> submit(session, manifest())).isInstanceOf(ProblemException.class)
        .hasMessageContaining("without an inline manifest");
    ConnectionService.Summary created = mock(ConnectionService.Summary.class);
    when(created.name()).thenReturn("reviewed-db");
    when(connections.create(anyString(), eq("pgfake"), eq(Env.DEV), any(), any(), any(), any())).thenReturn(created);
    var selection = selection("dev");
    var replacement = new ConnectionImportService.Selection(selection.candidateId(), selection.name(), selection.env(),
        selection.config(), Map.of("/credentials/password", SecretInput.local("replacement".toCharArray())), null);
    service.commit(session.id(), "dev", List.of(replacement), null);
    verify(original, never()).resolve();
  }

  @Test
  void everyDestinationMustBeUsableBeforeAnyConnectionPreflight() {
    for (String target : List.of("disabled", "unverified", "missing", "")) {
      var session = start();
      ObjectNode manifest = manifest();
      ObjectNode second = ((ObjectNode) manifest.path("connections").get(0)).deepCopy()
          .put("id", "db-two").put("name", "example-db-two");
      ((tools.jackson.databind.node.ArrayNode) manifest.path("connections")).add(second);
      submit(session, manifest);
      EndpointSnapshot unavailable = mock(EndpointSnapshot.class);
      when(unavailable.enabled()).thenReturn(!target.equals("disabled"));
      when(unavailable.blocked()).thenReturn(target.equals("unverified"));
      if (target.equals("missing")) when(endpoints.uncachedSnapshot(target))
          .thenThrow(ProblemException.notFound("Endpoint not found", "There is no such endpoint."));
      else when(endpoints.uncachedSnapshot(target)).thenReturn(unavailable);
      var secondSelection = new ConnectionImportService.Selection("db-two", "reviewed-db-two", "dev",
          json.createObjectNode(), Map.of(), null, false, target);
      assertThatThrownBy(() -> service.commit(session.id(), "dev", List.of(selection("dev"), secondSelection), null))
          .isInstanceOfSatisfying(ProblemException.class, error -> assertThat(error.field()).isEqualTo("/selections/1/endpoint"));
      assertThat(service.get(session.id()).status()).isEqualTo("review");
    }
    verifyNoInteractions(connections, wiring);
  }

  @Test
  void anEndpointPausedDuringPreflightIsRejectedBeforeCreatingAnything() {
    var session = start();
    submit(session, manifest());
    EndpointSnapshot enabled = mock(EndpointSnapshot.class);
    when(enabled.id()).thenReturn("ep-destination");
    when(enabled.enabled()).thenReturn(true);
    EndpointSnapshot paused = mock(EndpointSnapshot.class);
    when(endpoints.uncachedSnapshot("dev")).thenReturn(enabled, paused);
    assertThatThrownBy(() -> service.commit(session.id(), "dev", List.of(selection("dev")), null))
        .isInstanceOfSatisfying(ProblemException.class, error -> assertThat(error.field()).isEqualTo("/selections/0/endpoint"));
    verify(connections).preflightCreate(anyString(), eq("pgfake"), eq(Env.DEV), any(), any(), any());
    verify(connections, never()).create(anyString(), anyString(), any(), any(), any(), any(), any());
    verifyNoInteractions(wiring);
    assertThat(service.get(session.id()).status()).isEqualTo("review");
  }

  private ConnectionImportService.Session start() { return service.start("/repositories/example", "dev"); }

  private ObjectNode submit(ConnectionImportService.Session session, ObjectNode manifest) {
    return service.submit(session.id(), session.submissionToken(), "ep-source", "caller", manifest);
  }

  private ConnectionImportService.Selection selection(String env) {
    return new ConnectionImportService.Selection("db", "reviewed-db", env,
        json.createObjectNode().put("host", "reviewed.invalid"), Map.of(), null);
  }

  private ObjectNode manifest() {
    return (ObjectNode) json.readTree("""
        {"version":1,"connections":[{"id":"db","name":"example-db","type":"pgfake","env":"dev",
        "config":{"host":"db.invalid","credentials":{"username":"reader"}},"evidence":["compose.yaml:8"],
        "credentialHints":[{"field":"/credentials/password","source":"environment","instructions":"DB_PASSWORD"}],
        "warnings":[]}],"warnings":[]}
        """);
  }
}
