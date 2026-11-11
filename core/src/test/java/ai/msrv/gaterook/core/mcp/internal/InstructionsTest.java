package ai.msrv.gaterook.core.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.endpoints.EndpointSnapshot;
import ai.msrv.gaterook.core.endpoints.ToolCatalog;
import ai.msrv.gaterook.core.policy.PolicyEngine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** The instructions GateRook writes for an endpoint when the person wrote none. */
class InstructionsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void theApprovalListNamesOnlyWhatTheModelCanCall() {
    Endpoint e = new Endpoint();
    e.tool("crm", "crm_send", Mode.APPROVAL, "{\"ui\":{\"resourceUri\":\"ui://gaterook/connections/crm/app.html\"}}");
    e.tool("crm", "crm_confirm", Mode.APPROVAL, "{\"ui\":{\"visibility\":[\"app\"]}}");

    String text = Instructions.defaults(e.snapshot(null), e.catalog(null));

    assertThat(text).contains("- crm_send needs a person.").doesNotContain("crm_confirm");
  }

  @Test
  void aSearchedEndpointSaysHowToFindItsToolsFirstAndNamesTheirConnectionsLast() {
    Endpoint e = new Endpoint();
    for (int i = 0; i < 45; i++) {
      e.tool("sentry", "sentry_tool_" + i, i == 0 ? Mode.APPROVAL : Mode.READ_ONLY, null);
    }
    e.tool("notes", "notes_write", Mode.APPROVAL, null);
    e.tool("notes", "notes_read", Mode.READ_ONLY, null);
    String settings = "{\"toolList\":{\"pinned\":[\"notes\"]}}";

    String text = Instructions.defaults(e.snapshot(settings), e.catalog(settings));

    assertThat(text.substring(0, 512).replaceAll("\\s+", " ")).contains("Finding tools - tools/list shows only "
        + "GateRook's own tools and those of notes. find_tools finds the other 45 by what they do or by name.");
    assertThat(text).contains("- notes_write needs a person.").doesNotContain("sentry_tool_0 ")
        .contains("- find_tools says which of the tools it finds wait for a person.")
        .endsWith("Tools find_tools finds\n- sentry (Sentry): 45 tools");
  }

  /** Every tool on an MCP server's connection, as the person set its mode. */
  private static final class Endpoint {
    final PolicyEngine engine = mock(PolicyEngine.class);
    final List<EndpointSnapshot.Wired> connections = new ArrayList<>();
    final List<EndpointSnapshot.Pair> pairs = new ArrayList<>();

    void tool(String connection, String name, Mode mode, String meta) {
      ToolDefinition t = ToolDefinition.builder(name).description(name)
          .inputSchema(JsonNodeFactory.instance.objectNode().put("type", "object"))
          .nature(ToolDefinition.Nature.READ)
          .meta(meta == null ? null : JSON.readTree(meta)).build();
      String typeName = Character.toUpperCase(connection.charAt(0)) + connection.substring(1);
      ConnectorManifest manifest = ConnectorManifest.builder(connection + "-mcp", "1.0.0",
          ConnectorManifest.Kind.MCP_SERVER).name(typeName).build();
      ConnectionConfig config = connections.stream().map(EndpointSnapshot.Wired::config)
          .filter(c -> c.name().equals(connection)).findFirst().orElseGet(() -> {
            ConnectionConfig c = new ConnectionConfig(connection, connection, connection + "-mcp", Env.DEV, 1,
                JsonNodeFactory.instance.objectNode(), Set.of(), null);
            connections.add(new EndpointSnapshot.Wired(c, manifest, null));
            when(engine.view(null, c)).thenReturn(new PolicyView(Env.DEV, "dev." + connection,
                JsonNodeFactory.instance.objectNode(), new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536,
                    8192, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), null)));
            return c;
          });
      pairs.add(new EndpointSnapshot.Pair(connection + "/" + name, 1, name, t, manifest, config, mode, mode, null,
          null, true, null, null, null, List.of(), null));
    }

    EndpointSnapshot snapshot(String settings) {
      return new EndpointSnapshot("ep", "dev", "", Env.DEV, false, true, null,
          settings == null ? null : JSON.readTree(settings), false, false, null, 1, List.copyOf(connections),
          List.copyOf(pairs), List.of(), null);
    }

    ToolCatalog.Catalog catalog(String settings) {
      return new ToolCatalog(engine).forEndpoint(snapshot(settings));
    }
  }
}
