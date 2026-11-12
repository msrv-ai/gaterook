package ai.msrv.gaterook.plugin.protocol;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ConnectorModule;
import ai.msrv.gaterook.connector.api.EndpointView;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.ModuleContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connector.api.Suggestions;
import ai.msrv.gaterook.connector.api.TemplateKind;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the manifest of a connector module ({@code mcp-gateway-plugin.json}, {@code manifestVersion 2}) from the
 * module's own connectors, so the file can't disagree with the code: each connector's type, schemas and tools are
 * taken from its {@link ConnectorManifest} as a compiled-in connector declares them. What the code doesn't say comes
 * from the module's descriptor ({@code module.json}): its id, version, name, description, {@code moduleClass},
 * {@code requires}, {@code access} and {@code resources}.
 *
 * <p>The build of a module runs {@link #main} on the module's class path; the result is checked by the parser the
 * core and the host read it with ({@link PluginManifest#read}), so a module whose manifest they would refuse doesn't
 * build.
 */
public final class ModuleManifests {

  private ModuleManifests() {}

  /**
   * The manifest of a module.
   *
   * @param descriptor the module's {@code module.json}; {@code typeCapabilities} may name, per type, the capabilities
   *                   that can't be read off the code ({@code quota}, {@code masked})
   * @param connectors the module's connectors, one per type
   */
  public static ObjectNode write(JsonNode descriptor, List<Connector> connectors) {
    if (descriptor == null || !descriptor.isObject()) {
      throw new IllegalArgumentException("The module descriptor is not a JSON object");
    }
    if (descriptor.has("types") || descriptor.has("manifestVersion")) {
      throw new IllegalArgumentException("The module descriptor declares neither manifestVersion nor types: both "
          + "are written from the module's connectors");
    }
    ObjectNode out = GppJson.object();
    out.put("manifestVersion", 2);
    JsonNode extra = descriptor.path("typeCapabilities");
    for (var field : descriptor.properties()) {
      if (!"typeCapabilities".equals(field.getKey())) {
        out.set(field.getKey(), field.getValue().deepCopy());
      }
    }
    ArrayNode types = out.putArray("types");
    for (Connector connector : connectors) {
      ConnectorManifest m = connector.manifest();
      if (m.kind() != ConnectorManifest.Kind.BUILT_IN) {
        throw new IllegalArgumentException(m.id() + ": a module's connectors are built-in connectors, not "
            + m.kind());
      }
      if (m.dynamicTools()) {
        throw new IllegalArgumentException(m.id() + ": a connector with dynamic tools can't be a module");
      }
      ObjectNode type = types.addObject();
      type.put("id", m.id());
      type.put("name", m.name());
      type.put("icon", m.icon());
      type.put("group", m.group());
      type.put("description", m.description());
      type.set("configSchema", m.configSchema() == null ? GppJson.object().put("type", "object")
          : m.configSchema().deepCopy());
      if (m.policySchema() != null) {
        type.set("policySchema", m.policySchema().deepCopy());
      }
      ArrayNode tools = type.putArray("tools");
      ArrayNode commandTools = GppJson.NODES.arrayNode();
      for (ToolDefinition t : m.tools()) {
        if (t.meta() != null || t.declared() != null) {
          throw new IllegalArgumentException(m.id() + ": " + t.name() + " carries what only an external MCP "
              + "server's tool has");
        }
        tools.add(WireCodec.tool(t));
        if (connector.runsCommand(t.name())) {
          commandTools.add(t.name());
        }
      }
      ArrayNode kinds = GppJson.NODES.arrayNode();
      for (TemplateKind kind : connector.templateKinds()) {
        ObjectNode k = kinds.addObject();
        k.put("kind", kind.kind());
        WireCodec.strings(k.putArray("connectionTypes"), kind.connectionTypes());
        k.set("definitionSchema", kind.definitionSchema().deepCopy());
      }
      if (!kinds.isEmpty()) {
        type.set("templateKinds", kinds);
      }
      type.put("maxConcurrency", connector.maxConcurrency());
      if (!commandTools.isEmpty()) {
        type.set("commandTools", commandTools);
      }
      Set<String> capabilities = new LinkedHashSet<>(capabilitiesOf(connector));
      extra.path(m.id()).forEach(c -> capabilities.add(c.asString()));
      WireCodec.strings(type.putArray("capabilities"), List.copyOf(capabilities));
    }
    return out;
  }

  /** The capabilities a connector's class shows: the optional SPI methods it implements itself. */
  static Set<String> capabilitiesOf(Connector connector) {
    Set<String> out = new LinkedHashSet<>();
    Class<?> c = connector.getClass();
    if (own(c, "prepare", ToolCall.class, ConnectionConfig.class, SecretResolver.class, ExecContext.class)) {
      out.add("prepare");
    }
    if (own(c, "describe", ConnectionConfig.class, SecretResolver.class, ExecContext.class, String.class)) {
      out.add("describe");
    }
    if (own(c, "supportsTool", String.class, ConnectionConfig.class)) {
      out.add("supportsTool");
    }
    if (own(c, "tools", EndpointView.class)) {
      out.add("tools.endpoint");
    }
    if (own(c, "discoverDatabases", ConnectionConfig.class, SecretResolver.class, ExecContext.class)) {
      out.add("discoverDatabases");
    }
    if (own(c, "suggest", ConnectionConfig.class, SecretResolver.class, ExecContext.class, Suggestions.Scope.class)) {
      out.add("suggest");
    }
    return out;
  }

  private static boolean own(Class<?> type, String method, Class<?>... parameters) {
    try {
      return type.getMethod(method, parameters).getDeclaringClass() != Connector.class;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  /**
   * {@code <module.json> <out>}: instantiates the descriptor's {@code moduleClass} from the class path and writes the
   * module's manifest to {@code out}.
   */
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("Usage: ModuleManifests <module.json> <mcp-gateway-plugin.json>");
      System.exit(2);
    }
    JsonNode descriptor = GppJson.MAPPER.readTree(Files.readAllBytes(Path.of(args[0])));
    ObjectNode manifest = fromClassPath(descriptor);
    Path out = Path.of(args[1]);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    // Canonical JSON: the same module gives the same bytes on every build.
    Files.write(out, (new String(Canonical.bytes(manifest), StandardCharsets.UTF_8) + "\n")
        .getBytes(StandardCharsets.UTF_8));
  }

  static ObjectNode fromClassPath(JsonNode descriptor) throws ReflectiveOperationException {
    String moduleClass = descriptor.path("moduleClass").asString("");
    String id = descriptor.path("id").asString("");
    String version = descriptor.path("version").asString("");
    Object made = Class.forName(moduleClass).getDeclaredConstructor().newInstance();
    if (!(made instanceof ConnectorModule module)) {
      throw new IllegalArgumentException(moduleClass + " doesn't implement ConnectorModule");
    }
    ObjectNode manifest;
    try {
      List<Connector> connectors = module.connectors(new ModuleContext() {
        @Override
        public String moduleId() {
          return id;
        }

        @Override
        public String moduleVersion() {
          return version;
        }
      });
      manifest = write(descriptor, connectors);
      connectors.forEach(Connector::close);
    } finally {
      try {
        module.close();
      } catch (Exception e) {
        // The manifest is written; nothing depends on how the module closes.
      }
    }
    try {
      PluginManifest.read(Canonical.bytes(manifest));
    } catch (PluginManifest.InvalidManifest e) {
      throw new IllegalArgumentException("The module's manifest would be refused: " + e.getMessage(), e);
    }
    return manifest;
  }
}
