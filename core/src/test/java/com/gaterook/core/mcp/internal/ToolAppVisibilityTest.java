package com.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.connector.api.ToolDefinition;
import com.gaterook.core.endpoints.CoreTools;
import com.gaterook.core.endpoints.ToolCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Who may call each tool of an endpoint, as an AI app that renders MCP Apps hears it ({@code _meta.ui.visibility},
 * ARCHITECTURE.md §5.3, §5.4): the gateway's cards call only their two app-only tools, every other tool of the gateway
 * is the model's alone, and an external server's tool says what the catalog decided for it.
 */
class ToolAppVisibilityTest {

  @Test
  void theGatewaysCardsCallOnlyTheirTwoToolsAndTheGatewaysOtherToolsAreTheModels() {
    assertThat(ui(core(CoreTools.APPROVAL_CARD))).isEqualTo("{\"resourceUri\":\"" + McpAppResource.URI
        + "\",\"visibility\":[\"model\"]}");
    assertThat(ui(core(CoreTools.SUBMIT_CONNECTION_IMPORT))).isEqualTo("{\"resourceUri\":\""
        + ConnectionImportCards.URI + "\",\"visibility\":[\"model\"]}");
    for (String cardAction : List.of(CoreTools.APPROVAL_CARD_ACTION, CoreTools.OPEN_CONNECTION_IMPORT)) {
      assertThat(ui(core(cardAction))).as(cardAction).isEqualTo("{\"visibility\":[\"app\"]}");
    }
    for (String own : List.of(CoreTools.APPROVAL_STATUS, CoreTools.LIST_CONNECTIONS, CoreTools.DESCRIBE_CONNECTION,
        CoreTools.CONNECTION_IMPORT_SCHEMA, CoreTools.CONNECTION_IMPORT_PROGRESS)) {
      assertThat(ui(core(own))).as(own).isEqualTo("{\"visibility\":[\"model\"]}");
    }
  }

  @Test
  void anExternalServersToolSaysWhatTheCatalogDecided() {
    ObjectNode meta = (ObjectNode) JsonMapper.builder().build().readTree("{\"ui\":{\"visibility\":[\"model\",\"app\"]}}");
    ObjectNode input = JsonNodeFactory.instance.objectNode().put("type", "object");
    ToolCatalog.PublishedTool ofAppConnection = new ToolCatalog.PublishedTool("srv_tool", "t", "d", input, input, null,
        ToolDefinition.Annotations.read(), List.of(), null, false, meta, true);
    ToolCatalog.PublishedTool ofOtherConnection = new ToolCatalog.PublishedTool("srv_tool", "t", "d", input, input,
        null, ToolDefinition.Annotations.read(), List.of(), null, false, meta, false);

    assertThat(ui(ofAppConnection)).isEqualTo("{\"visibility\":[\"model\",\"app\"]}");
    assertThat(ui(ofOtherConnection)).isEqualTo("{\"visibility\":[\"model\"]}");
  }

  private static ToolCatalog.PublishedTool core(String name) {
    ObjectNode input = JsonNodeFactory.instance.objectNode().put("type", "object");
    return new ToolCatalog.PublishedTool(name, name, name, input, input, null, ToolDefinition.Annotations.read(),
        List.of(), null, true);
  }

  private static String ui(ToolCatalog.PublishedTool t) {
    return String.valueOf(McpDispatcher.clientUi(t));
  }
}
