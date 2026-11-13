package com.gaterook.core.mcpservers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** What the Setup change of a refreshed server counts as changed (ARCHITECTURE.md §7.5). */
class McpServerDiffTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ObjectNode tool(String original, String hash, String uri) {
    ObjectNode t = JSON.createObjectNode().put("exposed", "x_" + original).put("original", original)
        .put("definitionHash", hash);
    if (uri != null) {
      t.putObject("ui").put("resourceUri", uri);
    }
    return t;
  }

  private static ArrayNode list(ObjectNode... tools) {
    ArrayNode out = JSON.createArrayNode();
    for (ObjectNode t : tools) {
      out.add(t);
    }
    return out;
  }

  @Test
  void aToolIsChangedWhenWhatItDeclaresOrTheAppItShowsDiffers() {
    ArrayNode before = list(tool("a", "h1", null), tool("chart", "h2", "ui://x/a.html"), tool("gone", "h3", null));

    assertThat(McpServerService.diff(before, List.<JsonNode>of(tool("a", "h1", null), tool("chart", "h2",
        "ui://x/a.html"), tool("gone", "h3", null)))).isNull();
    assertThat(McpServerService.diff(before, List.<JsonNode>of(tool("a", "h9", null), tool("chart", "h2",
        "ui://x/a.html"), tool("gone", "h3", null)))).isEqualTo("1 changed");
    // The app alone: the declared definition is the same.
    assertThat(McpServerService.diff(before, List.<JsonNode>of(tool("a", "h1", null), tool("chart", "h2",
        "ui://x/b.html"), tool("gone", "h3", null)))).isEqualTo("1 changed");
    assertThat(McpServerService.diff(before, List.<JsonNode>of(tool("a", "h1", "ui://x/a.html"), tool("chart", "h2",
        null), tool("gone", "h3", null)))).as("one came, one went").isEqualTo("2 changed");
    assertThat(McpServerService.diff(before, List.<JsonNode>of(tool("a", "h1", null), tool("new", "h4", null),
        tool("chart", "h2", "ui://x/b.html")))).isEqualTo("1 new, 1 changed, 1 gone");
  }
}
