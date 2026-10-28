package ai.msrv.gaterook.core.admin.connectortypes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connectors.ConnectorProcessStatus;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.connectors.Plugins;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.secrets.SecretStores;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class ConnectorTypesControllerTest {

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
  private final ConnectionConfigs connections = mock(ConnectionConfigs.class);
  @SuppressWarnings("unchecked")
  private final ObjectProvider<ConnectorProcessStatus> processes = mock(ObjectProvider.class);
  private final Plugins plugins = mock(Plugins.class);
  private final ConnectorTypesController controller = new ConnectorTypesController(registry, connections,
      processes, mock(SecretStores.class), plugins);

  @BeforeEach
  void setup() {
    when(processes.iterator()).thenAnswer(ignored -> List.<ConnectorProcessStatus>of().iterator());
  }

  @Test
  void compatibilityTypesRemainEditableButCannotCreateNewCategoryConnections() {
    ConnectorManifest legacy = manifest("logs", "Logs", true, ConnectorManifest.Kind.BUILT_IN);
    ConnectorManifest database = manifest("opensearch", "Databases", false, ConnectorManifest.Kind.BUILT_IN);
    ConnectorManifest logging = manifest("gcp-logging", "Logs", false, ConnectorManifest.Kind.BUILT_IN);
    var types = List.of(type(legacy), type(database), type(logging));
    when(registry.types()).thenReturn(types);
    when(registry.type("logs")).thenReturn(Optional.of(type(legacy)));

    var empty = controller.list(null);
    assertThat(empty.groups()).extracting(ConnectorTypesController.Group::name).containsExactly("Databases", "Logs");
    assertThat(empty.groups().getFirst().cards()).extracting(ConnectorTypesController.Card::type)
        .containsExactly("opensearch");
    assertThat(empty.groups().getLast().cards()).extracting(ConnectorTypesController.Card::type)
        .containsExactly("gcp-logging");
    assertThat(empty.installed()).extracting(ConnectorTypesController.Installed::id)
        .containsExactly("opensearch", "gcp-logging");

    ConnectionConfig saved = new ConnectionConfig("old", "old-logs", "logs", Env.DEV, 1,
        JSON.objectNode().put("backend", "gcp-logging"), Set.of(), null);
    when(connections.all()).thenReturn(List.of(new ConnectionConfigs.Stored(saved, true, null, null, null, Map.of())));
    var existing = controller.list(null);
    assertThat(existing.groups()).flatExtracting(ConnectorTypesController.Group::cards)
        .extracting(ConnectorTypesController.Card::type).doesNotContain("logs");
    assertThat(existing.installed()).filteredOn(row -> row.id().equals("logs")).singleElement()
        .satisfies(row -> assertThat(row.connections()).isEqualTo(1));
    assertThat(controller.get("logs").type().id()).isEqualTo("logs");
  }

  @Test
  void previewFiltersToolsByTheDraftWithoutResolvingCredentialsOrTestingTheConnection() {
    ToolDefinition read = ToolDefinition.builder("read_entries").description("Read the selected service.").build();
    ToolDefinition write = ToolDefinition.builder("write_entries").build();
    ConnectorManifest manifest = ConnectorManifest.builder("service", "1.0.0", ConnectorManifest.Kind.BUILT_IN)
        .tools(List.of(read, write)).build();
    Connector connector = mock(Connector.class, CALLS_REAL_METHODS);
    when(connector.manifest()).thenReturn(manifest);
    when(connector.supportsTool(any(), any())).thenAnswer(invocation -> {
      ConnectionConfig config = invocation.getArgument(1);
      return invocation.getArgument(0).equals(read.name()) || config.bool("/allowWrites", false);
    });
    when(registry.type("service")).thenReturn(Optional.of(type(manifest)));
    when(registry.connectorFor(any())).thenReturn(Optional.of(connector));
    ObjectNode config = JSON.objectNode().put("allowWrites", false);

    var preview = controller.previewTools("service", new ConnectorTypesController.ToolPreviewRequest(config, "prod"));
    assertThat(preview.tools()).extracting(ConnectorTypesController.Tool::name).containsExactly(read.name());
    assertThat(preview.tools().getFirst().description()).isEqualTo(read.description());
    config.put("allowWrites", true);
    assertThat(controller.previewTools("service", new ConnectorTypesController.ToolPreviewRequest(config, null)).tools())
        .extracting(ConnectorTypesController.Tool::name).containsExactly(read.name(), write.name());
    verify(connector, never()).test(any(), any(), any());
    verify(connections, never()).all();
  }

  @Test
  void pluginPreviewUsesDeclaredCapabilitiesWithoutStartingItsHost() {
    ToolDefinition tool = ToolDefinition.builder("plugin_read").build();
    ConnectorManifest manifest = ConnectorManifest.builder("plugin-test", "1.0.0", ConnectorManifest.Kind.PLUGIN)
        .tools(List.of(tool)).build();
    when(registry.type("plugin-test")).thenReturn(Optional.of(type(manifest)));
    var preview = controller.previewTools("plugin-test", new ConnectorTypesController.ToolPreviewRequest(JSON.objectNode(), null));
    assertThat(preview.tools()).extracting(ConnectorTypesController.Tool::name).containsExactly("plugin_read");
    verify(registry, never()).connectorFor(any());
  }

  @Test
  void invalidDraftShapeAndEnvironmentAreRefused() {
    var manifest = manifest("service", "Logs", false, ConnectorManifest.Kind.BUILT_IN);
    when(registry.type("service")).thenReturn(Optional.of(type(manifest)));
    assertThatThrownBy(() -> controller.previewTools("service", new ConnectorTypesController.ToolPreviewRequest(null, null)))
        .isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> controller.previewTools("service", new ConnectorTypesController.ToolPreviewRequest(JSON.objectNode(), "unknown")))
        .isInstanceOf(ProblemException.class);
    verify(registry, never()).connectorFor(any());
  }

  private static ConnectorManifest manifest(String id, String group, boolean legacy, ConnectorManifest.Kind kind) {
    return ConnectorManifest.builder(id, "1.0.0", kind).name(id).group(group)
        .configSchema(JSON.objectNode().put("type", "object").put("x-legacy", legacy)).build();
  }

  private static ConnectorType type(ConnectorManifest manifest) {
    return new ConnectorType(manifest, true, true);
  }
}
