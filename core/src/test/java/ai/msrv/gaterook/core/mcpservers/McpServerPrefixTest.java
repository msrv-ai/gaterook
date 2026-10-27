package ai.msrv.gaterook.core.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * An MCP server's tool prefix may not be a plugin's namespace (report 11 §3.2): on an endpoint with both, only the
 * first tool of a name is published, and a server's {@code jira_add_comment} could stand in for the plugin's.
 */
class McpServerPrefixTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ConnectorType type(String id, ConnectorManifest.Kind kind, String... tools) {
    List<ToolDefinition> defs = java.util.Arrays.stream(tools).map(name -> ToolDefinition.builder(name)
        .inputSchema(JSON.createObjectNode().put("type", "object")).annotations(Annotations.write(true))
        .nature(Nature.MUTATION).defaultMode(Mode.APPROVAL, Mode.APPROVAL).build()).toList();
    return new ConnectorType(ConnectorManifest.builder(id, "1.2.0", kind).name(id.equals("jira-service-desk")
        ? "Jira Service Desk" : id).tools(defs).build(), true, true);
  }

  @Test
  void aPluginsNamespaceIsNoPrefixForAServer() {
    List<ConnectorType> types = List.of(type("postgres", ConnectorManifest.Kind.BUILT_IN, "sql_query"),
        type("jira-service-desk", ConnectorManifest.Kind.PLUGIN, "jira_add_comment", "jira_get_request"));

    assertThat(McpServerService.pluginWithNamespace(types, "jira_")).contains("Jira Service Desk");
    assertThat(McpServerService.pluginWithNamespace(types, "github_")).isEmpty();
    // Built-in families are the registry's reserved prefixes, refused before; jir_ is another namespace.
    assertThat(McpServerService.pluginWithNamespace(types, "sql_")).isEmpty();
    assertThat(McpServerService.pluginWithNamespace(types, "jir_")).isEmpty();
  }
}
