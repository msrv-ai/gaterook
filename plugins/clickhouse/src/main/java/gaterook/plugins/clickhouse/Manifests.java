package gaterook.plugins.clickhouse;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plugin's {@code mcp-gateway-plugin.json} as a {@link ConnectorManifest}: one file says what the plugin is, the
 * core reads it from the JAR without loading a class, and the code serves exactly the tools it declares.
 *
 * <p>A tool is read with the defaults the gateway's manifest parser uses (title = name, no description, JSON result),
 * so the definitions the host reports at load hash as the manifest's do.
 */
final class Manifests {

  /** Where the manifest lives in a plugin JAR. */
  static final String FILE = "/mcp-gateway-plugin.json";

  static final JsonMapper JSON = JsonMapper.builder().build();

  private Manifests() {}

  /** The manifest of the JAR this class was loaded from. */
  static JsonNode own() {
    try (InputStream in = Manifests.class.getResourceAsStream(FILE)) {
      if (in == null) {
        throw new IllegalStateException(FILE + " is missing: this JAR isn't a release of the plugin");
      }
      return JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Can't read " + FILE, e);
    }
  }

  static ConnectorManifest read(JsonNode m) {
    List<ToolDefinition> tools = new ArrayList<>();
    for (JsonNode t : m.path("tools")) {
      tools.add(tool(t));
    }
    JsonNode access = m.path("access");
    return ConnectorManifest.builder(m.path("id").asString(), m.path("version").asString(),
            ConnectorManifest.Kind.PLUGIN)
        .name(m.path("name").asString())
        .icon(m.path("icon").asString("puzzle"))
        .group(m.path("group").asString("HTTP and MCP"))
        .description(m.path("description").asString(""))
        .configSchema(m.path("configSchema").deepCopy())
        .tools(tools)
        .access(new ConnectorManifest.RequiredAccess(strings(access.path("hosts")), strings(access.path("commands")),
            strings(access.path("files"))))
        .build();
  }

  private static ToolDefinition tool(JsonNode t) {
    String name = t.path("name").asString();
    JsonNode a = t.path("annotations");
    JsonNode risk = t.path("risk");
    ToolDefinition.Builder b = ToolDefinition.builder(name)
        .title(t.path("title").asString(name))
        .description(t.path("description").asString(""))
        .inputSchema(t.path("inputSchema").deepCopy())
        .annotations(new ToolDefinition.Annotations(a.path("readOnlyHint").asBoolean(false),
            a.path("destructiveHint").asBoolean(true), a.path("idempotentHint").asBoolean(false),
            a.path("openWorldHint").asBoolean(true)))
        .nature(ToolDefinition.Nature.valueOf(t.path("nature").asString("MIXED")))
        // Plugin tools start in Approval wherever they are added; the core decides, the plugin only declares.
        .defaultMode(ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL)
        .resultShape(ToolDefinition.ResultShape.valueOf(t.path("resultShape").asString("JSON")))
        .risk(new ToolDefinition.Risk(risk.path("privateData").asBoolean(true),
            risk.path("untrustedContent").asBoolean(true),
            ToolDefinition.Egress.valueOf(risk.path("egress").asString("CHANNEL"))));
    JsonNode output = t.get("outputSchema");
    if (output != null && output.isObject()) {
      b.outputSchema(output.deepCopy());
    }
    return b.build();
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode v : array) {
      out.add(v.asString());
    }
    return List.copyOf(out);
  }
}
