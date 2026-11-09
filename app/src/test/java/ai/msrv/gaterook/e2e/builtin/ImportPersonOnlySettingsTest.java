package ai.msrv.gaterook.e2e.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connectors.support.PersonOnly;
import ai.msrv.gaterook.core.connections.ConnectionForms;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.connections.internal.SchemaPointers;
import ai.msrv.gaterook.core.endpoints.ConnectionImportFiles;
import ai.msrv.gaterook.core.endpoints.ConnectionImportService;
import ai.msrv.gaterook.core.endpoints.EndpointConnections;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.ProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An AI agent's import proposal cannot stand in for the person's Trust decision or switch on a local command, checked
 * against the real schemas of the built-in connectors: what Test connection records after "Trust" and the opt-ins that
 * run a command carry {@code x-person-only}, and a proposal that writes one is refused.
 */
class ImportPersonOnlySettingsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIG4q5cLnwuK6nh3KekSjpSxn3RUE8TATPm0X+fK02R8D";
  private static final String PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  /** What the schemas say Test connection fills in, or that runs a command with the person's rights. */
  private static final Pattern DOCUMENTED = Pattern.compile(
      "(?i)\\b(?:filled|set) by test connection\\b|runs on this computer with your user rights");

  private static Map<String, ConnectorManifest> manifests;

  private final Clock clock = mock(Clock.class);
  private final EndpointService endpoints = mock(EndpointService.class);
  private final ConnectionService connections = mock(ConnectionService.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final EndpointConnections wiring = mock(EndpointConnections.class);
  private final EventBus events = mock(EventBus.class);
  private final ConnectionImportFiles files = mock(ConnectionImportFiles.class);
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
    when(files.create(anyString())).thenAnswer(i -> "/private-imports/" + i.getArgument(0) + ".json");
    for (ConnectorManifest manifest : manifests.values()) {
      ConnectorType type = new ConnectorType(manifest, true, true);
      when(registry.type(manifest.id())).thenReturn(Optional.of(type));
      when(registry.manifest(manifest.id())).thenReturn(Optional.of(manifest));
    }
    when(registry.types()).thenReturn(manifests.values().stream().map(m -> new ConnectorType(m, true, true)).toList());
    service = new ConnectionImportService(endpoints, wiring, connections, registry, events, Masker.standard(), clock,
        mock(PlatformTransactionManager.class), files, new LocalListener(() -> 18475));
  }

  /** One refused proposal: the connector, the config the agent wrote and the setting the refusal names. */
  private record Refused(String type, String config, String setting) {}

  private static final List<Refused> REFUSED = List.of(
      new Refused("kubernetes", "{\"auth\":\"kubeconfig\",\"allowExecPlugin\":true}", "/allowExecPlugin"),
      new Refused("kubernetes", "{\"auth\":\"kubeconfig\",\"allowExecPlugin\":false}", "/allowExecPlugin"),
      new Refused("kubernetes", "{\"auth\":\"kubeconfig\",\"trustedExec\":\"" + FINGERPRINT + "\"}", "/trustedExec"),
      new Refused("kubernetes", "{\"auth\":\"kubeconfig-file\",\"kubeconfigPath\":\"~/.kube/config\",\"context\":\"dev\","
          + "\"pinnedCluster\":{\"serverUrl\":\"https://k8s.example.test:6443\",\"caSha256\":\"\"}}", "/pinnedCluster"),
      new Refused("ssh", "{\"connection\":{\"host\":\"ssh.example.test\",\"username\":\"reader\"},"
          + "\"hostKeys\":{\"pinned\":[\"" + KEY + "\"]}}", "/hostKeys/pinned"),
      new Refused("ssh", "{\"connection\":{\"host\":\"ssh.example.test\",\"username\":\"reader\"},"
          + "\"hostKeys\":{\"knownHosts\":\"ssh.example.test " + KEY + "\"}}", "/hostKeys/knownHosts"),
      new Refused("ssh", "{\"connection\":{\"host\":\"ssh.example.test\",\"username\":\"reader\"},"
          + "\"jump\":{\"host\":\"bastion.example.test\",\"hostKeys\":{\"pinned\":[\"" + KEY + "\"]}}}",
          "/jump/hostKeys/pinned"),
      new Refused("ssh", "{\"connection\":{\"host\":\"ssh.example.test\",\"username\":\"reader\"},"
          + "\"jump\":{\"host\":\"bastion.example.test\",\"hostKeys\":{\"knownHosts\":\"bastion.example.test " + KEY
          + "\"}}}", "/jump/hostKeys/knownHosts"),
      new Refused("ssh-logs", "{\"connection\":{\"host\":\"logs.example.test\",\"username\":\"reader\"},"
          + "\"hostKeys\":{\"pinned\":[\"" + KEY + "\"]}}", "/hostKeys/pinned"),
      new Refused("ssh-logs", "{\"connection\":{\"host\":\"logs.example.test\",\"username\":\"reader\"},"
          + "\"hostKeys\":{\"knownHosts\":\"logs.example.test " + KEY + "\"}}", "/hostKeys/knownHosts"),
      new Refused("http", "{\"baseUrls\":[\"https://api.example.test\"],\"tls\":{\"pins\":[\"" + PIN + "\"]}}",
          "/tls/pins"),
      new Refused("http", "{\"baseUrls\":[\"https://api.example.test\"],\"tls\":{\"caCertificates\":"
          + "\"-----BEGIN CERTIFICATE----- MIIB -----END CERTIFICATE-----\"}}", "/tls/caCertificates"),
      new Refused("aws", "{\"regions\":[\"eu-central-1\"],\"accountId\":\"123456789012\"}", "/accountId"));

  @Test
  void aProposalCannotCarryWhatTestConnectionRecordsAfterTrustOrSwitchOnALocalCommand() {
    for (Refused refused : REFUSED) {
      var session = service.start("/repositories/example", "dev");
      ObjectNode manifest = manifest(refused.type(), refused.config());
      assertThatThrownBy(() -> service.submit(session.id(), session.submissionToken(), "ep-source", "caller", manifest))
          .as(refused.type() + " " + refused.config())
          .isInstanceOfSatisfying(ProblemException.class, error -> {
            assertThat(error.kind()).isEqualTo(ProblemException.Kind.UNPROCESSABLE);
            assertThat(error.getMessage()).contains("Leave " + refused.setting() + " out of config");
          });
      assertThat(service.get(session.id()).status()).isEqualTo("awaiting");
    }
    verifyNoInteractions(connections, wiring);
  }

  @Test
  void ordinaryProposalsOfTheSameConnectorsAreStillAccepted() {
    for (Map.Entry<String, String> ordinary : Map.of(
        "kubernetes", "{\"auth\":\"kubeconfig-file\",\"kubeconfigPath\":\"~/.kube/config\",\"context\":\"dev\","
            + "\"namespace\":\"app-dev\"}",
        "ssh", "{\"connection\":{\"host\":\"ssh.example.test\",\"username\":\"reader\"},"
            + "\"jump\":{\"host\":\"bastion.example.test\"},\"credentials\":{\"method\":\"vaultSshCa\"}}",
        "ssh-logs", "{\"connection\":{\"host\":\"logs.example.test\",\"username\":\"reader\"}}",
        "aws", "{\"regions\":[\"eu-central-1\"],\"credentials\":{\"source\":\"accessKey\"}}",
        "http", "{\"baseUrls\":[\"https://api.example.test\"],\"tls\":{\"minVersion\":\"TLSv1.3\"}}").entrySet()) {
      var session = service.start("/repositories/example", "dev");
      ObjectNode manifest = manifest(ordinary.getKey(), ordinary.getValue());
      assertThat(service.submit(session.id(), session.submissionToken(), "ep-source", "caller", manifest)
          .path("status").asString()).as(ordinary.getKey()).isEqualTo("review");
    }
  }

  @Test
  void theSchemaAnAgentReadsOffersNoneOfThem() {
    var session = service.start("/repositories/example", "dev");
    for (String type : List.of("kubernetes", "ssh", "ssh-logs", "http", "aws")) {
      ObjectNode result = service.schema(session.id(), session.submissionToken(), "ep-source", type);
      String schema = result.path("configSchema").toString();
      assertThat(schema).as(type).doesNotContain(PersonOnly.KEYWORD, "trustedExec", "pinnedCluster", "allowExecPlugin",
          "\"pinned\"", "knownHosts", "caCertificates", "\"pins\"", "accountId");
      assertThat(result.path("personOnlyFields")).as(type).isNotEmpty();
    }
    assertThat(service.schema(session.id(), session.submissionToken(), "ep-source", "kubernetes")
        .path("personOnlyFields")).isEqualTo(JSON.readTree("[\"/allowExecPlugin\",\"/pinnedCluster\",\"/trustedExec\"]"));
  }

  /**
   * A setting that Test connection or a person's Trust fills in must be marked, or an agent could fill it in too. The
   * descriptions of the schemas are the contract that new fields follow; the producers of the patches are checked
   * next to their code (Kubernetes, SSH and HTTP tests).
   */
  @Test
  void everySettingTheSchemasSayTestConnectionFillsInOrThatRunsACommandIsPersonOnly() {
    TreeSet<String> found = new TreeSet<>();
    for (ConnectorManifest manifest : manifests.values()) {
      for (var located : SchemaPointers.collect(manifest.configSchema(),
          s -> DOCUMENTED.matcher(s.path("description").asString("")).find())) {
        found.add(manifest.id() + " " + located.template());
        assertThat(PersonOnly.isMarked(located.schema()))
            .as("%s %s is described as filled in by Test connection or as running a command: mark it x-person-only",
                manifest.id(), located.template())
            .isTrue();
      }
    }
    // The scan finds the settings (a rewritten description would otherwise make this test pass for nothing).
    assertThat(found).contains("kubernetes /pinnedCluster", "kubernetes /trustedExec", "kubernetes /allowExecPlugin",
        "ssh /hostKeys/pinned", "ssh-logs /hostKeys/pinned", "http /tls/caCertificates", "aws /accountId");
  }

  @Test
  void theMarkOnTheSchemasIsTheOneTheImportServiceEnforces() {
    Map<String, List<String>> marked = new LinkedHashMap<>();
    manifests.values().forEach(m -> {
      List<String> templates = ConnectionForms.personOnlyFields(m.configSchema());
      if (!templates.isEmpty()) marked.put(m.id(), templates);
    });
    assertThat(marked).containsOnlyKeys("kubernetes", "ssh", "ssh-logs", "http", "aws");
    assertThat(marked.get("ssh")).containsExactly("/hostKeys/knownHosts", "/hostKeys/pinned",
        "/jump/hostKeys/knownHosts", "/jump/hostKeys/pinned");
    assertThat(marked.get("http")).containsExactly("/tls/caCertificates", "/tls/pins");
  }

  private ObjectNode manifest(String type, String config) {
    ObjectNode manifest = JSON.createObjectNode().put("version", 1);
    ObjectNode candidate = manifest.putArray("connections").addObject().put("id", "candidate")
        .put("name", "example-" + type).put("type", type).put("env", "dev");
    candidate.set("config", JSON.readTree(config));
    candidate.putArray("evidence").add("deploy/config.yaml:12");
    return manifest;
  }
}
