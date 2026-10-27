package ai.msrv.gaterook.connectors.mcpserver.boot;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class McpServerConnectorsAutoConfigurationTest {

  @Test
  void isListedInTheModulesAutoConfigurationImports() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
        .contains(McpServerConnectorsAutoConfiguration.class.getName());
  }

  @Test
  void registersTheConnectorTypeAndTheRuntimeTheCoreAttachesTo() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(McpServerConnectorsAutoConfiguration.class))
        .run(ctx -> {
          assertThat(ctx.getBeansOfType(Connector.class).values())
              .extracting(c -> c.manifest().id())
              .containsExactly("mcp-server");
          assertThat(ctx.getBeansOfType(McpServerRuntime.class)).hasSize(1);
          assertThat(ctx.getBean(Connector.class).manifest().tools()).isEmpty();
          assertThat(ctx.getBean(Connector.class).manifest().dynamicTools()).isTrue();
        });
  }

  @Test
  void schemasAreNativeImageResources() {
    RuntimeHints hints = new RuntimeHints();
    new McpServerHints().registerHints(hints, getClass().getClassLoader());

    assertThat(RuntimeHintsPredicates.resource().forResource("connectors/mcp-server/config.schema.json"))
        .accepts(hints);
  }

  /** A record three levels deep that the SDK binds with Jackson: missing in native, initialize would hang. */
  @Test
  void everyNestedTypeOfTheProtocolIsReflectable() {
    RuntimeHints hints = new RuntimeHints();
    new McpServerHints().registerHints(hints, getClass().getClassLoader());

    for (Class<?> type : java.util.List.of(McpSchema.InitializeResult.class, McpSchema.ServerCapabilities.class,
        McpSchema.ServerCapabilities.ToolCapabilities.class, McpSchema.ListToolsResult.class,
        McpSchema.ToolAnnotations.class, McpSchema.CallToolResult.class, McpSchema.ResourceLink.class)) {
      assertThat(RuntimeHintsPredicates.reflection().onType(type)).as(type.getName()).accepts(hints);
    }
  }
}
