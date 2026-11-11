package ai.msrv.gaterook.e2e.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionForms;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.ConnectionShares;
import ai.msrv.gaterook.core.connections.internal.SchemaPointers;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A trust decision never travels in an endpoint share, checked against the real schemas of the built-in connectors:
 * every setting they mark {@code x-person-only} (what Test connection records after Trust, the opt-in to run a
 * credential command) is left out of an export, and an entry that carries one, with any value, is imported without it
 * as a disabled draft that asks the person to confirm it on this computer. Older GateRook versions exported these
 * settings, and anyone can edit a share file.
 */
class SharePersonOnlySettingsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

  /** Values a trust setting may hold in a file: each counts by being there. */
  private static final List<String> VALUES = List.of("\"0000\"", "null", "false", "true", "\"\"", "[]", "{}", "0");

  private static Map<String, ConnectorManifest> manifests;

  private final ConnectionConfigs configs = mock(ConnectionConfigs.class);
  private final ConnectionService connections = mock(ConnectionService.class);
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final SecretService secrets = mock(SecretService.class);
  private ConnectionShares shares;

  @BeforeAll
  static void loadConnectorModules() {
    manifests = BuiltInManifests.load();
  }

  @BeforeEach
  void setup() {
    for (ConnectorManifest manifest : manifests.values()) {
      when(registry.type(manifest.id())).thenReturn(Optional.of(new ConnectorType(manifest, true, true)));
      when(registry.manifest(manifest.id())).thenReturn(Optional.of(manifest));
    }
    when(configs.all()).thenReturn(List.of());
    shares = new ConnectionShares(configs, connections, registry, secrets, Masker.standard());
  }

  @Test
  void everyTrustSettingOfEveryBuiltInConnectorIsLeftOutOfAnExport() {
    TreeSet<String> checked = new TreeSet<>();
    int n = 0;
    for (ConnectorManifest connector : manifests.values()) {
      for (String template : ConnectionForms.personOnlyFields(connector.configSchema())) {
        String id = "c-" + n++;
        ConnectionConfig cfg = new ConnectionConfig(id, "example-" + connector.id(), connector.id(), Env.DEV, 1,
            holding(template, "\"0000\""), Set.of(), null);
        when(configs.byId(id)).thenReturn(Optional.of(new ConnectionConfigs.Stored(cfg, true, null, NOW, NOW,
            Map.of())));

        ObjectNode entry = shares.export(id, false);

        assertThat(SchemaPointers.values(entry.path("config"), template)).as(connector.id() + " " + template)
            .isEmpty();
        assertThat(entry.path("warnings").toString()).as(connector.id() + " " + template)
            .contains("Trust settings were omitted (" + template + "); confirm them on the destination computer "
                + "with Test connection.");
        checked.add(connector.id() + " " + template);
      }
    }
    // The walk found the settings (schemas without marks would otherwise make this test pass for nothing).
    assertThat(checked).contains("kubernetes /allowExecPlugin", "kubernetes /pinnedCluster", "kubernetes /trustedExec",
        "ssh /hostKeys/pinned", "ssh /jump/hostKeys/knownHosts", "ssh-logs /hostKeys/knownHosts", "http /tls/pins",
        "http /tls/caCertificates", "aws /accountId", "azure /trustedCli", "azure-monitor /trustedCli");
  }

  @Test
  void anEntryCarryingAnyTrustSettingOfABuiltInConnectorImportsAsADraftWithoutIt() {
    TreeSet<String> checked = new TreeSet<>();
    int drafts = 0;
    for (ConnectorManifest connector : manifests.values()) {
      List<String> templates = ConnectionForms.personOnlyFields(connector.configSchema());
      if (templates.isEmpty()) continue;
      // The same connector without the setting is no draft: the setting alone makes it one.
      assertThat(shares.prepareImport(entry(connector.id(), JSON.createObjectNode()), true, new HashSet<>())
          .needsSetup()).as(connector.id()).isFalse();
      for (String template : templates) {
        for (String value : VALUES) {
          String what = connector.id() + " " + template + " = " + value;
          ConnectionShares.Prepared prepared = shares.prepareImport(entry(connector.id(), holding(template, value)),
              true, new HashSet<>());

          assertThat(SchemaPointers.values(prepared.config(), template)).as(what).isEmpty();
          assertThat(prepared.needsSetup()).as(what).isTrue();
          assertThat(prepared.missing()).as(what).containsExactly(template + ": Not imported. Confirm this trust "
              + "setting on this computer with Test connection, or set it yourself.");
          assertThat(shares.trustNote(entry(connector.id(), holding(template, value)))).as(what).isPresent();
          assertThat(SchemaPointers.values(shares.previewConfig(entry(connector.id(), holding(template, value))),
              template)).as(what).isEmpty();
          shares.preflightImport(prepared, null);
          shares.importPrepared(prepared, "test");
          drafts++;
        }
        checked.add(connector.id() + " " + template);
      }
    }
    verify(connections, never()).preflightCreate(anyString(), anyString(), any(), any(), any(), any());
    verify(connections, never()).create(anyString(), anyString(), any(), any(), any(), any(), any());
    ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<JsonNode> created = ArgumentCaptor.forClass(JsonNode.class);
    verify(connections, times(drafts)).createImportDraft(anyString(), types.capture(), eq(Env.DEV), created.capture(),
        any(), any(), isNull(), eq("test"));
    for (int i = 0; i < drafts; i++) {
      JsonNode config = created.getAllValues().get(i);
      for (String template : ConnectionForms.personOnlyFields(manifests.get(types.getAllValues().get(i))
          .configSchema())) {
        assertThat(SchemaPointers.values(config, template)).as(types.getAllValues().get(i) + " " + template).isEmpty();
      }
    }
    assertThat(checked).contains("kubernetes /allowExecPlugin", "kubernetes /pinnedCluster", "kubernetes /trustedExec",
        "ssh /hostKeys/pinned", "ssh /jump/hostKeys/pinned", "ssh-logs /hostKeys/pinned", "http /tls/pins",
        "aws /accountId", "azure /trustedCli");
  }

  /** A share entry of a built-in connector whose config is {@code config}, as an older GateRook wrote one. */
  private static ObjectNode entry(String type, JsonNode config) {
    ObjectNode entry = JSON.createObjectNode().put("id", "shared").put("name", "example-" + type).put("type", type)
        .put("env", "dev");
    entry.set("config", config);
    entry.putObject("secrets");
    entry.putArray("omittedSecretFields");
    entry.putArray("tools");
    entry.putArray("templates");
    entry.putArray("warnings");
    return entry;
  }

  /** A config that holds {@code value} at a pointer template, every {@code *} standing for the first array element. */
  private static ObjectNode holding(String template, String value) {
    JsonNode node = JSON.readTree(value);
    String[] parts = template.substring(1).split("/", -1);
    for (int i = parts.length - 1; i >= 0; i--) {
      String part = parts[i].replace("~1", "/").replace("~0", "~");
      if ("*".equals(part)) {
        node = JSON.createArrayNode().add(node);
      } else {
        ObjectNode object = JSON.createObjectNode();
        object.set(part, node);
        node = object;
      }
    }
    return (ObjectNode) node;
  }
}
