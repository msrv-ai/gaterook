package gaterook.plugins.fixtures;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.TestResult;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * What every fixture shares: its manifest is the {@code mcp-gateway-plugin.json} of its JAR (each fixture JAR carries
 * all fixture classes and names one of them as {@code connectorClass}), READ tools run, the rest wait for approval, a
 * test always passes. The misbehaviour is each subclass's {@code execute}.
 */
abstract class Fixture implements Connector {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ConnectorManifest manifest = read();

  @Override
  public ConnectorManifest manifest() {
    return manifest;
  }

  @Override
  public Classification classify(ToolCall call, ConnectionConfig cfg, PolicyView policy) {
    return manifest.tools().stream().filter(t -> t.name().equals(call.tool())).findFirst()
        .map(t -> t.nature() == ToolDefinition.Nature.READ ? Classification.read()
            : Classification.mutation(call.tool() + " is a fixture's change"))
        .orElse(Classification.deny(policy.rule("unknown-tool"), call.tool() + " is unknown", null));
  }

  @Override
  public ActionPreview describeAction(ToolCall call, ConnectionConfig cfg) {
    return ActionPreview.of(ActionPreview.Kind.TEXT, "text", call.tool() + " " + call.arguments(), cfg.name(),
        call.tool());
  }

  @Override
  public TestResult test(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) {
    return TestResult.success(1, manifest.version(), getClass().getSimpleName(), null);
  }

  private static ConnectorManifest read() {
    JsonNode m;
    try (InputStream in = Fixture.class.getResourceAsStream("/mcp-gateway-plugin.json")) {
      if (in == null) {
        throw new IllegalStateException("No mcp-gateway-plugin.json in this JAR");
      }
      m = JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<ToolDefinition> tools = new ArrayList<>();
    for (JsonNode t : m.path("tools")) {
      String name = t.path("name").asString();
      JsonNode a = t.path("annotations");
      ToolDefinition.Nature nature = ToolDefinition.Nature.valueOf(t.path("nature").asString());
      tools.add(ToolDefinition.builder(name).title(t.path("title").asString(name))
          .description(t.path("description").asString(""))
          .inputSchema(t.path("inputSchema").deepCopy())
          .annotations(new ToolDefinition.Annotations(a.path("readOnlyHint").asBoolean(),
              a.path("destructiveHint").asBoolean(), a.path("idempotentHint").asBoolean(),
              a.path("openWorldHint").asBoolean()))
          .nature(nature)
          .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL)
          .resultShape(ToolDefinition.ResultShape.valueOf(t.path("resultShape").asString("JSON")))
          .build());
    }
    return ConnectorManifest.builder(m.path("id").asString(), m.path("version").asString(),
            ConnectorManifest.Kind.PLUGIN)
        .name(m.path("name").asString())
        .description(m.path("description").asString(""))
        .configSchema(m.path("configSchema").deepCopy())
        .tools(tools)
        .build();
  }
}
