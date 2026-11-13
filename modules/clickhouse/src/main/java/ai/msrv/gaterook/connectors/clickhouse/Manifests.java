package ai.msrv.gaterook.connectors.clickhouse;

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
 * The connector's {@code connectors/clickhouse/connector.json} as a {@link ConnectorManifest}: one file says what the
 * connector is (its type, its form and its tools), and the code serves exactly the tools it declares. The module's
 * manifest is written from this manifest when the package is built, so the core reads the same definitions from the
 * JAR without loading a class.
 *
 * <p>The type is one of GateRook's own: its reads start in Read only and its changes in Approval, on every
 * environment.
 */
final class Manifests {

  /** Where the connector's definition lives in its JAR. */
  static final String FILE = "/connectors/clickhouse/connector.json";

  static final JsonMapper JSON = JsonMapper.builder().build();

  private Manifests() {}

  /** The definition in the JAR this class was loaded from. */
  static JsonNode own() {
    try (InputStream in = Manifests.class.getResourceAsStream(FILE)) {
      if (in == null) {
        throw new IllegalStateException(FILE + " is missing: this JAR isn't a build of the connector");
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
            ConnectorManifest.Kind.BUILT_IN)
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
    ToolDefinition.Nature nature = ToolDefinition.Nature.valueOf(t.path("nature").asString("MIXED"));
    ToolDefinition.Mode mode = nature == ToolDefinition.Nature.READ ? ToolDefinition.Mode.READ_ONLY
        : ToolDefinition.Mode.APPROVAL;
    ToolDefinition.Builder b = ToolDefinition.builder(name)
        .title(t.path("title").asString(name))
        .description(t.path("description").asString(""))
        .inputSchema(t.path("inputSchema").deepCopy())
        .annotations(new ToolDefinition.Annotations(a.path("readOnlyHint").asBoolean(false),
            a.path("destructiveHint").asBoolean(true), a.path("idempotentHint").asBoolean(false),
            a.path("openWorldHint").asBoolean(true)))
        .nature(nature)
        // A read runs at once; anything else waits for a person, on every environment.
        .defaultMode(mode, mode)
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
