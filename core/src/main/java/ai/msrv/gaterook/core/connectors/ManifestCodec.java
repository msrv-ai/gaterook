package ai.msrv.gaterook.core.connectors;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Annotations;
import ai.msrv.gaterook.connector.api.ToolDefinition.Egress;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.connector.api.ToolDefinition.Nature;
import ai.msrv.gaterook.connector.api.ToolDefinition.ResultShape;
import ai.msrv.gaterook.connector.api.ToolDefinition.Risk;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ConnectorManifest} as a JSON tree and back, by hand: SPI records are never bound by reflection (ARCHITECTURE.md
 * §3, native rule 4). Used for the {@code connector_type.manifest} column, where the manifests of plugins live so that
 * {@code tools/list} never starts a host, and for its SHA-256.
 */
public final class ManifestCodec {

  private ManifestCodec() {}

  public static ObjectNode write(ConnectorManifest m) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("id", m.id());
    out.put("version", m.version());
    out.put("kind", m.kind().name());
    out.put("name", m.name());
    out.put("icon", m.icon());
    out.put("group", m.group());
    out.put("description", m.description());
    out.set("configSchema", copy(m.configSchema()));
    out.set("policySchema", copy(m.policySchema()));
    ArrayNode tools = out.putArray("tools");
    for (ToolDefinition t : m.tools()) {
      tools.add(writeTool(t));
    }
    ObjectNode access = out.putObject("access");
    strings(access.putArray("hosts"), m.access().hosts());
    strings(access.putArray("commands"), m.access().commands());
    strings(access.putArray("files"), m.access().files());
    out.put("dynamicTools", m.dynamicTools());
    out.put("connectorApi", m.connectorApi());
    return out;
  }

  public static ObjectNode writeTool(ToolDefinition t) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.put("name", t.name());
    out.put("title", t.title());
    out.put("description", t.description());
    out.set("inputSchema", copy(t.inputSchema()));
    out.set("outputSchema", copy(t.outputSchema()));
    ObjectNode a = out.putObject("annotations");
    a.put("readOnlyHint", t.annotations().readOnlyHint());
    a.put("destructiveHint", t.annotations().destructiveHint());
    a.put("idempotentHint", t.annotations().idempotentHint());
    a.put("openWorldHint", t.annotations().openWorldHint());
    out.put("nature", t.nature().name());
    out.put("defaultMode", t.defaultMode().name());
    out.put("prodDefaultMode", t.prodDefaultMode().name());
    out.put("resultShape", t.resultShape().name());
    ObjectNode risk = out.putObject("risk");
    risk.put("privateData", t.risk().privateData());
    risk.put("untrustedContent", t.risk().untrustedContent());
    risk.put("egress", t.risk().egress().name());
    return out;
  }

  public static ConnectorManifest read(JsonNode in) {
    ConnectorManifest.Kind kind = ConnectorManifest.Kind.valueOf(in.path("kind").asString());
    List<ToolDefinition> tools = new ArrayList<>();
    for (JsonNode t : in.path("tools")) {
      tools.add(readTool(t));
    }
    JsonNode access = in.path("access");
    return new ConnectorManifest(in.path("id").asString(), in.path("version").asString(), kind,
        in.path("name").asString(), in.path("icon").asString("puzzle"), in.path("group").asString("HTTP and MCP"),
        in.path("description").asString(""), nullable(in.get("configSchema")), nullable(in.get("policySchema")),
        List.copyOf(tools), new ConnectorManifest.RequiredAccess(strings(access.path("hosts")),
            strings(access.path("commands")), strings(access.path("files"))),
        in.path("dynamicTools").asBoolean(false), in.path("connectorApi").asString());
  }

  /**
   * A stored tool as it was published. Built with the canonical constructor rather than the builder: a plugin's
   * definition is taken as declared, the builder's checks ran when the plugin was installed.
   */
  public static ToolDefinition readTool(JsonNode t) {
    JsonNode a = t.path("annotations");
    JsonNode risk = t.path("risk");
    return new ToolDefinition(t.path("name").asString(), t.path("title").asString(), t.path("description").asString(""),
        nullable(t.get("inputSchema")), nullable(t.get("outputSchema")),
        new Annotations(bool(a.get("readOnlyHint")), bool(a.get("destructiveHint")), bool(a.get("idempotentHint")),
            bool(a.get("openWorldHint"))),
        Nature.valueOf(t.path("nature").asString("MIXED")), Mode.valueOf(t.path("defaultMode").asString("APPROVAL")),
        Mode.valueOf(t.path("prodDefaultMode").asString("APPROVAL")),
        ResultShape.valueOf(t.path("resultShape").asString("JSON")),
        new Risk(risk.path("privateData").asBoolean(true), risk.path("untrustedContent").asBoolean(true),
            Egress.valueOf(risk.path("egress").asString("CHANNEL"))));
  }

  private static JsonNode copy(JsonNode node) {
    return node == null ? JsonNodeFactory.instance.nullNode() : node.deepCopy();
  }

  private static JsonNode nullable(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.deepCopy();
  }

  private static Boolean bool(JsonNode node) {
    return node == null || node.isNull() ? null : node.asBoolean();
  }

  private static void strings(ArrayNode out, List<String> values) {
    values.forEach(out::add);
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    for (JsonNode v : array) {
      out.add(v.asString());
    }
    return List.copyOf(out);
  }
}
