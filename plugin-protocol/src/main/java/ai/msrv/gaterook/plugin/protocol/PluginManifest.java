package ai.msrv.gaterook.plugin.protocol;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code mcp-gateway-plugin.json}, the one file a plugin JAR describes itself with (report 11 §4.2): read by the core
 * from the ZIP without loading a class ("Check plugin") and by the host before PF4J loads anything. The same parser on
 * both sides, so they agree on what a plugin declared.
 *
 * <p>Only what the file says is checked here (shapes, names, ranges); whether it fits this gateway — its version, the
 * names already taken, the modules of the bundled runtime — is the core's check at install.
 *
 * @param raw the manifest as read, for storage and diffs
 */
public record PluginManifest(String id, String version, String name, String provider, String license,
    String description, Requires requires, String connectorClass, String toolNamespace, String icon, String group,
    JsonNode configSchema, JsonNode policySchema, List<ToolDefinition> tools, ConnectorManifest.RequiredAccess access,
    int maxHeapMb, JsonNode raw) {

  /** The file's name at the root of the JAR. */
  public static final String FILE = "mcp-gateway-plugin.json";

  /** Largest manifest read (report 11 §4.2). */
  public static final int MAX_BYTES = 1024 * 1024;

  /** Largest tool schema (report 11 §4.2). */
  public static final int MAX_SCHEMA_BYTES = 64 * 1024;

  static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{1,47}$");
  static final Pattern NAMESPACE = Pattern.compile("^[a-z][a-z0-9]{0,14}_$");
  static final Pattern TOOL = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
  static final Pattern CLASS = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$");
  static final Pattern MODULE = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

  /** Packages a plugin can't ship: the host serves them parent-first, so a copy inside would only shadow nothing. */
  public static final List<String> SHARED_PACKAGES = List.of("ai.msrv.gaterook.", "tools.jackson.",
      "com.fasterxml.jackson.annotation.", "org.pf4j.", "org.slf4j.");

  /** {@code requires}: version ranges of the gateway and the SPI, and the Java modules the plugin needs. */
  public record Requires(String gateway, String connectorApi, List<String> javaModules) {}

  /** What's wrong with a manifest, every problem at once. */
  public static final class InvalidManifest extends Exception {
    private static final long serialVersionUID = 1L;
    private final List<String> problems;

    public InvalidManifest(List<String> problems) {
      super(String.join("; ", problems));
      this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
      return problems;
    }
  }

  /** The connector type this plugin adds: its id is the plugin's, its tools wait for approval until a person says. */
  public ConnectorManifest connectorManifest() {
    List<ToolDefinition> published = new ArrayList<>();
    for (ToolDefinition t : tools) {
      // Plugin tools start in Approval wherever they are added (ARCHITECTURE.md §3); the declared nature stays.
      published.add(new ToolDefinition(t.name(), t.title(), t.description(), t.inputSchema(), t.outputSchema(),
          t.annotations(), t.nature(), ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL, t.resultShape(),
          t.risk()));
    }
    return ConnectorManifest.builder(id, version, ConnectorManifest.Kind.PLUGIN).name(name).icon(icon).group(group)
        .description(description).configSchema(configSchema).policySchema(policySchema).tools(published)
        .access(access).build();
  }

  /** The declared tool named {@code name}. */
  public java.util.Optional<ToolDefinition> tool(String name) {
    return tools.stream().filter(t -> t.name().equals(name)).findFirst();
  }

  /**
   * Parses and checks a manifest.
   *
   * @throws InvalidManifest with every problem found
   */
  public static PluginManifest parse(JsonNode n) throws InvalidManifest {
    List<String> problems = new ArrayList<>();
    if (n == null || !n.isObject()) {
      throw new InvalidManifest(List.of(FILE + " is not a JSON object"));
    }
    if (n.path("manifestVersion").asInt(0) != 1) {
      problems.add("manifestVersion must be 1");
    }
    String id = n.path("id").asString("");
    if (!ID.matcher(id).matches()) {
      problems.add("id must match " + ID.pattern());
    }
    String type = WireCodec.text(n.get("type"));
    if (type != null && !type.equals(id)) {
      problems.add("type must be the plugin's id (" + id + "): one plugin adds one connector type");
    }
    String version = n.path("version").asString("");
    if (!Semver.valid(version)) {
      problems.add("version must be a semantic version (1.3.0)");
    }
    String name = n.path("name").asString(id).strip();
    if (name.isEmpty() || name.length() > 64) {
      problems.add("name must have 1 to 64 characters");
    }
    String description = n.path("description").asString("");
    if (description.length() > 500) {
      problems.add("description must have at most 500 characters");
    }
    hidden("name", name, problems);
    hidden("description", description, problems);
    JsonNode req = n.path("requires");
    String gateway = WireCodec.text(req.get("gateway"));
    String api = WireCodec.text(req.get("connectorApi"));
    if (gateway == null || !Semver.validRange(gateway)) {
      problems.add("requires.gateway must be a version range (>=0.4.0 & <1.0.0)");
    }
    if (api == null || !Semver.validRange(api)) {
      problems.add("requires.connectorApi must be a version range (>=1.0.0 & <2.0.0)");
    }
    List<String> modules = WireCodec.strings(req.path("javaModules"));
    for (String m : modules) {
      if (!MODULE.matcher(m).matches()) {
        problems.add("requires.javaModules: " + m + " is not a module name");
      }
    }
    String connectorClass = n.path("connectorClass").asString("");
    if (!CLASS.matcher(connectorClass).matches()) {
      problems.add("connectorClass must be a class name");
    } else if (SHARED_PACKAGES.stream().anyMatch(connectorClass::startsWith) || connectorClass.startsWith("java.")) {
      problems.add("connectorClass can't be in " + connectorClass.substring(0, connectorClass.lastIndexOf('.')));
    }
    String namespace = n.path("toolNamespace").asString("");
    if (!NAMESPACE.matcher(namespace).matches()) {
      problems.add("toolNamespace must match " + NAMESPACE.pattern() + " (jira_)");
    }
    if (n.path("dynamicTools").asBoolean(false)) {
      problems.add("dynamicTools isn't supported for plugins yet: declare every tool in the manifest");
    }
    JsonNode configSchema = n.get("configSchema");
    if (configSchema == null || !configSchema.isObject() || !"object".equals(configSchema.path("type").asString())) {
      problems.add("configSchema must be a JSON Schema of an object");
    }
    JsonNode policySchema = WireCodec.tree(n.get("policySchema"));
    if (policySchema != null && !policySchema.isObject()) {
      problems.add("policySchema must be a JSON Schema object or null");
    }
    List<ToolDefinition> tools = tools(n.path("tools"), namespace, problems);
    JsonNode access = n.path("access");
    List<String> hosts = WireCodec.strings(access.path("hosts"));
    List<String> commands = WireCodec.strings(access.path("commands"));
    List<String> files = WireCodec.strings(access.path("files"));
    if (hosts.size() > 32 || commands.size() > 32 || files.size() > 32) {
      problems.add("access lists at most 32 hosts, commands and files each");
    }
    int maxHeap = n.path("resources").path("maxHeapMb").asInt(256);
    if (maxHeap < 64 || maxHeap > 1024) {
      problems.add("resources.maxHeapMb must be 64 to 1024");
    }
    if (!problems.isEmpty()) {
      throw new InvalidManifest(problems);
    }
    return new PluginManifest(id, version, name, WireCodec.text(n.get("provider")), WireCodec.text(n.get("license")),
        description, new Requires(gateway, api, modules), connectorClass, namespace, n.path("icon").asString("puzzle"),
        n.path("group").asString("HTTP and MCP"), configSchema.deepCopy(), policySchema, tools,
        new ConnectorManifest.RequiredAccess(hosts, commands, files), maxHeap, n.deepCopy());
  }

  private static List<ToolDefinition> tools(JsonNode array, String namespace, List<String> problems) {
    List<ToolDefinition> out = new ArrayList<>();
    if (!array.isArray() || array.isEmpty()) {
      problems.add("tools must list at least one tool");
      return out;
    }
    if (array.size() > 64) {
      problems.add("tools lists at most 64 tools");
    }
    Set<String> names = new HashSet<>();
    for (JsonNode t : array) {
      String name = t.path("name").asString("");
      String where = "tool " + (name.isEmpty() ? "#" + out.size() : name);
      if (!TOOL.matcher(name).matches()) {
        problems.add(where + ": the name must match " + TOOL.pattern());
        continue;
      }
      if (!namespace.isEmpty() && !name.startsWith(namespace)) {
        problems.add(where + ": the name must start with the toolNamespace " + namespace);
      }
      if (!names.add(name)) {
        problems.add(where + " is declared twice");
      }
      String nature = t.path("nature").asString("");
      if (!Set.of("READ", "MUTATION", "MIXED").contains(nature)) {
        problems.add(where + ": nature must be READ, MUTATION or MIXED");
        continue;
      }
      JsonNode a = t.path("annotations");
      for (String hint : List.of("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint")) {
        if (!a.path(hint).isBoolean()) {
          problems.add(where + ": annotations." + hint + " must be declared (true or false)");
        }
      }
      JsonNode input = t.get("inputSchema");
      if (input == null || !input.isObject() || !"object".equals(input.path("type").asString())) {
        problems.add(where + ": inputSchema must be a JSON Schema of an object");
        continue;
      }
      if (input.path("properties").has("connection")) {
        problems.add(where + ": connection is added by GateRook, a tool doesn't declare it");
      }
      JsonNode output = WireCodec.tree(t.get("outputSchema"));
      if (output != null && !"READ".equals(nature)) {
        problems.add(where + ": only a READ tool may declare an outputSchema");
      }
      if (Canonical.bytes(input).length > MAX_SCHEMA_BYTES
          || (output != null && Canonical.bytes(output).length > MAX_SCHEMA_BYTES)) {
        problems.add(where + ": a schema is larger than 64 KB");
      }
      String shape = t.path("resultShape").asString("JSON");
      if (!Set.of("TABLE", "JSON", "TEXT", "LOGS").contains(shape)) {
        problems.add(where + ": resultShape must be TABLE, JSON, TEXT or LOGS");
        continue;
      }
      ToolDefinition.Nature n = ToolDefinition.Nature.valueOf(nature);
      boolean openWorld = a.path("openWorldHint").asBoolean(true);
      JsonNode risk = t.path("risk");
      ToolDefinition.Egress egress;
      try {
        egress = risk.has("egress") ? ToolDefinition.Egress.valueOf(risk.get("egress").asString())
            : openWorld || n != ToolDefinition.Nature.READ ? ToolDefinition.Egress.CHANNEL : ToolDefinition.Egress.NONE;
      } catch (IllegalArgumentException e) {
        problems.add(where + ": risk.egress must be NONE, LIMITED or CHANNEL");
        continue;
      }
      String title = t.path("title").asString(name);
      String description = t.path("description").asString("");
      if (title.length() > 200 || description.length() > 2_000) {
        problems.add(where + ": title or description is too long (200 and 2,000 characters)");
      }
      hidden(where + ": title", title, problems);
      hidden(where + ": description", description, problems);
      out.add(new ToolDefinition(name, title, description, input.deepCopy(), output,
          new ToolDefinition.Annotations(bool(a, "readOnlyHint"), bool(a, "destructiveHint"),
              bool(a, "idempotentHint"), bool(a, "openWorldHint")),
          n, ToolDefinition.Mode.APPROVAL, ToolDefinition.Mode.APPROVAL, ToolDefinition.ResultShape.valueOf(shape),
          new ToolDefinition.Risk(risk.path("privateData").asBoolean(true),
              risk.path("untrustedContent").asBoolean(true), egress)));
    }
    return List.copyOf(out);
  }

  /**
   * Text an agent or a person reads — the plugin's name and description, each tool's title and description — may not
   * hide anything (ARCHITECTURE.md §3 "RemoteConnector", §9 "Prompt-injection-safe approvals"): control characters
   * other than a line break or a tab, bidirectional overrides and isolates, zero-width characters, a BOM and the soft
   * hyphen change what a reader sees without being seen. An MCP server's descriptions are cleaned; a plugin is
   * packaged for GateRook, so its manifest is refused instead, and the definition hash stays the plugin's own.
   */
  static void hidden(String what, String text, List<String> problems) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean control = (c < 0x20 && c != '\n' && c != '\t') || (c >= 0x7F && c <= 0x9F);
      boolean invisible = (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069) || c == 0x200E || c == 0x200F
          || c == 0x061C || (c >= 0x200B && c <= 0x200D) || c == 0x2060 || c == 0xFEFF || c == 0x00AD;
      if (control || invisible) {
        problems.add(what + " has a hidden character (U+" + String.format("%04X", (int) c) + ")");
        return;
      }
    }
  }

  private static Boolean bool(JsonNode a, String name) {
    JsonNode v = a.get(name);
    return v == null || !v.isBoolean() ? null : v.asBoolean();
  }

  /** The manifest's definition hashes by tool name. */
  public Map<String, String> definitionHashes() {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    tools.forEach(t -> out.put(t.name(), WireCodec.definitionHash(t)));
    return Map.copyOf(out);
  }

  /** A copy of {@link #raw()} as an object node. */
  public ObjectNode rawObject() {
    return (ObjectNode) raw.deepCopy();
  }
}
