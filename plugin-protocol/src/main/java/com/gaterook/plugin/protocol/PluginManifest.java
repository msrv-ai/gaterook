package com.gaterook.plugin.protocol;

import com.gaterook.connector.api.ConnectorManifest;
import com.gaterook.connector.api.ToolDefinition;
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
 * <p>Two versions of the file exist. {@code manifestVersion 1} is a plugin: one connector type, named after the
 * plugin, whose class is {@code connectorClass}; its tools live in one namespace, declare no {@code connection}
 * parameter and start in Approval. {@code manifestVersion 2} is a connector module (docs/connector-delivery.md): one
 * or more built-in connector types behind one {@code moduleClass}; their tools are declared as a compiled-in
 * connector declares them, with the {@code connection} parameter and their own default modes. How far a module is
 * trusted is decided by where it came from, not by this file: the signed module channel pins GateRook's own, and a
 * package a person installs from a file is theirs, held as an unsigned plugin is.
 *
 * <p>For a module, the single-type components ({@code connectorClass}, {@code toolNamespace}, {@code icon},
 * {@code group}, {@code configSchema}, {@code policySchema}, {@code tools}) hold the first type's values or
 * {@code null}; {@link #types} is what to read for both versions.
 *
 * @param raw the manifest as read, for storage and diffs
 */
public record PluginManifest(String id, String version, String name, String provider, String license,
    String description, Requires requires, String connectorClass, String toolNamespace, String icon, String group,
    JsonNode configSchema, JsonNode policySchema, List<ToolDefinition> tools, ConnectorManifest.RequiredAccess access,
    int maxHeapMb, JsonNode raw, int manifestVersion, String moduleClass, List<Type> types, int maxMetaspaceMb,
    int maxInFlight) {

  /** The file's name at the root of the JAR. */
  public static final String FILE = "mcp-gateway-plugin.json";

  /** Largest plugin manifest read (report 11 §4.2). */
  public static final int MAX_BYTES = 1024 * 1024;

  /** Largest module manifest read: several connector types with their schemas and tools. */
  public static final int MAX_MODULE_BYTES = 4 * 1024 * 1024;

  /** Largest tool schema (report 11 §4.2). */
  public static final int MAX_SCHEMA_BYTES = 64 * 1024;

  static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{1,47}$");
  static final Pattern NAMESPACE = Pattern.compile("^[a-z][a-z0-9]{0,14}_$");
  static final Pattern TOOL = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
  static final Pattern CLASS = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$");
  static final Pattern MODULE = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");
  /**
   * An {@code access.tcp} entry: the connection's host, a DNS name, an IPv4 or a bracketed IPv6 literal, then a port
   * from 1 to 65535 or the connection's port. No wildcards: the TCP gate allows exactly these.
   */
  static final Pattern TCP_ENTRY = Pattern.compile("^(\\{connection\\.host\\}|[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}"
      + "[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]{2,45}\\]):(\\{connection\\.port\\}|[1-9][0-9]{0,4})$");

  /** Packages a plugin can't ship: the host serves them parent-first, so a copy inside would only shadow nothing. */
  public static final List<String> SHARED_PACKAGES = List.of("com.gaterook.", "tools.jackson.",
      "com.fasterxml.jackson.annotation.", "org.pf4j.", "org.slf4j.");

  /**
   * Packages a module can't ship. A module is GateRook's own connector code, which lives under
   * {@code com.gaterook.connectors.}, so only the parts of {@code com.gaterook.} that the host provides are
   * closed to it.
   */
  public static final List<String> MODULE_SHARED_PACKAGES = List.of("com.gaterook.connector.api.",
      "com.gaterook.plugin.protocol.", "com.gaterook.pluginhost.", "tools.jackson.",
      "com.fasterxml.jackson.annotation.", "org.pf4j.", "org.slf4j.");

  static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9-]{0,47}$");
  static final Pattern TEMPLATE_KIND = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

  /**
   * What a connector type of a module does beyond the calls every connector answers, so the core knows which of them
   * to forward: {@code prepare} (dry runs before a request exists), {@code describe} (its part of describe_connection),
   * {@code supportsTool}, {@code tools.endpoint} (its tool list depends on the endpoint), {@code discoverDatabases},
   * {@code suggest}, {@code quota} (shared remote quotas) and {@code masked}. A manifest that names another one needs
   * a newer GateRook and is refused.
   */
  public static final Set<String> CAPABILITIES = Set.of("prepare", "describe", "supportsTool", "tools.endpoint",
      "discoverDatabases", "suggest", "quota", "masked");

  /** A no-code template kind a connector type of a module offers: its form is here, it compiles in the host. */
  public record TemplateKindManifest(String kind, List<String> connectionTypes, JsonNode definitionSchema) {}

  /**
   * One connector type of the package: all a plugin has, one of several for a module.
   *
   * @param templateKinds  the template kinds the type offers (modules only)
   * @param maxConcurrency calls one connection of the type runs at once
   * @param commandTools   the tools whose deadline is the command's, not the tool's ({@code Connector.runsCommand})
   * @param capabilities   see {@link #CAPABILITIES}
   */
  public record Type(String id, String name, String icon, String group, String description, JsonNode configSchema,
      JsonNode policySchema, List<ToolDefinition> tools, List<TemplateKindManifest> templateKinds, int maxConcurrency,
      Set<String> commandTools, Set<String> capabilities) {

    /** The declared tool named {@code name}. */
    public java.util.Optional<ToolDefinition> tool(String name) {
      return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /** The type's definition hashes by tool name. */
    public Map<String, String> definitionHashes() {
      Map<String, String> out = new java.util.LinkedHashMap<>();
      tools.forEach(t -> out.put(t.name(), WireCodec.definitionHash(t)));
      return Map.copyOf(out);
    }
  }

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

  /** Whether this is a connector module ({@code manifestVersion 2}) rather than a plugin. */
  public boolean module() {
    return manifestVersion == 2;
  }

  /** The type named {@code typeId}. */
  public java.util.Optional<Type> type(String typeId) {
    return types.stream().filter(t -> t.id().equals(typeId)).findFirst();
  }

  /**
   * The connector types this package adds, as the registry takes them. A plugin's one type is {@link
   * #connectorManifest()}. A module's types are built-in connectors with another delivery: their kind is {@code
   * BUILT_IN}, their version the module's, their tools keep the modes they declare.
   */
  public List<ConnectorManifest> connectorManifests() {
    if (!module()) {
      return List.of(connectorManifest());
    }
    List<ConnectorManifest> out = new ArrayList<>();
    for (Type t : types) {
      out.add(ConnectorManifest.builder(t.id(), version, ConnectorManifest.Kind.BUILT_IN).name(t.name()).icon(t.icon())
          .group(t.group()).description(t.description()).configSchema(t.configSchema()).policySchema(t.policySchema())
          .tools(t.tools()).access(access).build());
    }
    return List.copyOf(out);
  }

  /** The definition hashes of every type's tools, by type id and tool name: what a host may report at load. */
  public Map<String, Map<String, String>> definitionHashesByType() {
    Map<String, Map<String, String>> out = new java.util.LinkedHashMap<>();
    types.forEach(t -> out.put(t.id(), t.definitionHashes()));
    return java.util.Collections.unmodifiableMap(out);
  }

  /**
   * Reads and checks the bytes of a manifest file: a plugin's is at most {@link #MAX_BYTES}, a module's at most
   * {@link #MAX_MODULE_BYTES}.
   *
   * @throws InvalidManifest too large, not JSON, or with every problem {@link #parse} found
   */
  public static PluginManifest read(byte[] bytes) throws InvalidManifest {
    if (bytes.length > MAX_MODULE_BYTES) {
      throw new InvalidManifest(List.of(FILE + " is larger than 4 MB"));
    }
    JsonNode tree;
    try {
      tree = GppJson.MAPPER.readTree(bytes);
    } catch (RuntimeException e) {
      throw new InvalidManifest(List.of(FILE + " is not JSON: " + e.getMessage()));
    }
    if (bytes.length > MAX_BYTES && (tree == null || tree.path("manifestVersion").asInt(0) != 2)) {
      throw new InvalidManifest(List.of(FILE + " is larger than 1 MB"));
    }
    return parse(tree);
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
    if (n.path("manifestVersion").asInt(0) == 2) {
      return parseModule(n);
    }
    if (n.path("manifestVersion").asInt(0) != 1) {
      problems.add("manifestVersion must be 1 or 2");
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
      problems.add("requires.connectorApi must be a version range (>=2.0.0 & <3.0.0)");
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
    List<ToolDefinition> tools = tools(n.path("tools"), namespace, false, "", 64, problems);
    ConnectorManifest.RequiredAccess access = access(n.path("access"), problems);
    int maxHeap = n.path("resources").path("maxHeapMb").asInt(256);
    if (maxHeap < 64 || maxHeap > 1024) {
      problems.add("resources.maxHeapMb must be 64 to 1024");
    }
    if (!problems.isEmpty()) {
      throw new InvalidManifest(problems);
    }
    String icon = n.path("icon").asString("puzzle");
    String group = n.path("group").asString("HTTP and MCP");
    Type only = new Type(id, name, icon, group, description, configSchema.deepCopy(), policySchema, tools, List.of(),
        PLUGIN_CONCURRENCY, Set.of(), Set.of());
    return new PluginManifest(id, version, name, WireCodec.text(n.get("provider")), WireCodec.text(n.get("license")),
        description, new Requires(gateway, api, modules), connectorClass, namespace, icon, group,
        configSchema.deepCopy(), policySchema, tools, access, maxHeap, n.deepCopy(), 1, null, List.of(only), 128,
        PLUGIN_CONCURRENCY);
  }

  /** Calls a plugin's host runs at once (the supervisor's bulkhead, report 16 §7). */
  public static final int PLUGIN_CONCURRENCY = 8;

  /** Calls a module's host runs at once unless its manifest says otherwise: several types share the host. */
  public static final int MODULE_CONCURRENCY = 16;

  private static ConnectorManifest.RequiredAccess access(JsonNode access, List<String> problems) {
    List<String> hosts = WireCodec.strings(access.path("hosts"));
    List<String> commands = WireCodec.strings(access.path("commands"));
    List<String> files = WireCodec.strings(access.path("files"));
    List<String> tcp = WireCodec.strings(access.path("tcp"));
    if (hosts.size() > 32 || commands.size() > 32 || files.size() > 32 || tcp.size() > 32) {
      problems.add("access lists at most 32 hosts, commands, files and TCP destinations each");
    }
    for (String entry : tcp) {
      if (!tcpEntry(entry)) {
        problems.add("access.tcp entries are host:port, such as {connection.host}:{connection.port} or "
            + "db.example.com:5432, not " + entry);
      }
    }
    return new ConnectorManifest.RequiredAccess(hosts, commands, files, tcp);
  }

  /**
   * A connector module's manifest ({@code manifestVersion 2}): what a compiled-in connector's manifest says, once per
   * type, plus what the host needs to run them.
   */
  private static PluginManifest parseModule(JsonNode n) throws InvalidManifest {
    List<String> problems = new ArrayList<>();
    String id = n.path("id").asString("");
    if (!ID.matcher(id).matches()) {
      problems.add("id must match " + ID.pattern());
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
      problems.add("requires.gateway must be a version range (>=1.0.0 & <2.0.0)");
    }
    if (api == null || !Semver.validRange(api)) {
      problems.add("requires.connectorApi must be a version range (>=2.0.0 & <3.0.0)");
    }
    List<String> javaModules = WireCodec.strings(req.path("javaModules"));
    for (String m : javaModules) {
      if (!MODULE.matcher(m).matches()) {
        problems.add("requires.javaModules: " + m + " is not a module name");
      }
    }
    String moduleClass = n.path("moduleClass").asString("");
    if (!CLASS.matcher(moduleClass).matches()) {
      problems.add("moduleClass must be a class name");
    } else if (MODULE_SHARED_PACKAGES.stream().anyMatch(moduleClass::startsWith) || moduleClass.startsWith("java.")) {
      problems.add("moduleClass can't be in " + moduleClass.substring(0, moduleClass.lastIndexOf('.')));
    }
    for (String single : List.of("connectorClass", "type", "toolNamespace", "configSchema", "tools")) {
      if (n.has(single)) {
        problems.add(single + " belongs to a plugin (manifestVersion 1): a module declares it in types");
      }
    }
    List<Type> types = types(n.path("types"), problems);
    ConnectorManifest.RequiredAccess access = access(n.path("access"), problems);
    JsonNode resources = n.path("resources");
    int maxHeap = resources.path("maxHeapMb").asInt(256);
    if (maxHeap < 64 || maxHeap > 1024) {
      problems.add("resources.maxHeapMb must be 64 to 1024");
    }
    int maxMetaspace = resources.path("maxMetaspaceMb").asInt(128);
    if (maxMetaspace < 64 || maxMetaspace > 512) {
      problems.add("resources.maxMetaspaceMb must be 64 to 512");
    }
    int maxInFlight = resources.path("maxInFlight").asInt(MODULE_CONCURRENCY);
    if (maxInFlight < 1 || maxInFlight > 64) {
      problems.add("resources.maxInFlight must be 1 to 64");
    }
    if (!problems.isEmpty()) {
      throw new InvalidManifest(problems);
    }
    Type first = types.getFirst();
    return new PluginManifest(id, version, name, WireCodec.text(n.get("provider")), WireCodec.text(n.get("license")),
        description, new Requires(gateway, api, javaModules), null, null, first.icon(), first.group(),
        first.configSchema(), first.policySchema(), first.tools(), access, maxHeap, n.deepCopy(), 2, moduleClass,
        types, maxMetaspace, maxInFlight);
  }

  private static List<Type> types(JsonNode array, List<String> problems) {
    List<Type> out = new ArrayList<>();
    if (!array.isArray() || array.isEmpty()) {
      problems.add("types must list at least one connector type");
      return out;
    }
    if (array.size() > 32) {
      problems.add("types lists at most 32 connector types");
    }
    Set<String> ids = new HashSet<>();
    for (JsonNode t : array) {
      String typeId = t.path("id").asString("");
      String where = "type " + (typeId.isEmpty() ? "#" + out.size() : typeId);
      if (!TYPE.matcher(typeId).matches()) {
        problems.add(where + ": the id must match " + TYPE.pattern());
        continue;
      }
      if (!ids.add(typeId)) {
        problems.add(where + " is declared twice");
      }
      String name = t.path("name").asString(typeId).strip();
      String description = t.path("description").asString("");
      if (name.isEmpty() || name.length() > 64 || description.length() > 500) {
        problems.add(where + ": name has 1 to 64 characters, description at most 500");
      }
      hidden(where + ": name", name, problems);
      hidden(where + ": description", description, problems);
      String icon = t.path("icon").asString("puzzle");
      String group = t.path("group").asString("HTTP and MCP");
      if (icon.length() > 64 || group.length() > 64) {
        problems.add(where + ": icon and group have at most 64 characters");
      }
      JsonNode configSchema = t.get("configSchema");
      if (configSchema == null || !configSchema.isObject() || !"object".equals(configSchema.path("type").asString())) {
        problems.add(where + ": configSchema must be a JSON Schema of an object");
        continue;
      }
      JsonNode policySchema = WireCodec.tree(t.get("policySchema"));
      if (policySchema != null && !policySchema.isObject()) {
        problems.add(where + ": policySchema must be a JSON Schema object or null");
      }
      List<ToolDefinition> tools = tools(t.path("tools"), "", true, where + ": ", 128, problems);
      Set<String> toolNames = new HashSet<>();
      tools.forEach(tool -> toolNames.add(tool.name()));
      List<TemplateKindManifest> kinds = new ArrayList<>();
      JsonNode declaredKinds = t.path("templateKinds");
      if (declaredKinds.size() > 8) {
        problems.add(where + ": templateKinds lists at most 8 kinds");
      }
      for (JsonNode k : declaredKinds) {
        String kind = k.path("kind").asString("");
        JsonNode definition = k.get("definitionSchema");
        List<String> connectionTypes = WireCodec.strings(k.path("connectionTypes"));
        if (!TEMPLATE_KIND.matcher(kind).matches() || definition == null || !definition.isObject()
            || connectionTypes.isEmpty() || connectionTypes.stream().anyMatch(c -> !TYPE.matcher(c).matches())) {
          problems.add(where + ": a template kind has a kind, the connection types it runs on and a "
              + "definitionSchema");
          continue;
        }
        kinds.add(new TemplateKindManifest(kind, List.copyOf(connectionTypes), definition.deepCopy()));
      }
      int maxConcurrency = t.path("maxConcurrency").asInt(4);
      if (maxConcurrency < 1 || maxConcurrency > 64) {
        problems.add(where + ": maxConcurrency must be 1 to 64");
      }
      Set<String> commandTools = new java.util.LinkedHashSet<>(WireCodec.strings(t.path("commandTools")));
      for (String tool : commandTools) {
        if (!toolNames.contains(tool)) {
          problems.add(where + ": commandTools names " + tool + ", which is not one of its tools");
        }
      }
      Set<String> capabilities = new java.util.LinkedHashSet<>(WireCodec.strings(t.path("capabilities")));
      for (String capability : capabilities) {
        if (!CAPABILITIES.contains(capability)) {
          problems.add(where + ": this GateRook doesn't know the capability " + capability);
        }
      }
      out.add(new Type(typeId, name, icon, group, description, configSchema.deepCopy(), policySchema, tools,
          List.copyOf(kinds), maxConcurrency, java.util.Collections.unmodifiableSet(commandTools),
          java.util.Collections.unmodifiableSet(capabilities)));
    }
    return List.copyOf(out);
  }

  /** Whether {@code entry} is a valid {@code access.tcp} destination ({@link #TCP_ENTRY}, a port up to 65535). */
  static boolean tcpEntry(String entry) {
    if (entry == null || !TCP_ENTRY.matcher(entry).matches()) {
      return false;
    }
    String port = entry.substring(entry.lastIndexOf(':') + 1);
    return port.startsWith("{") || Integer.parseInt(port) <= 65_535;
  }

  /**
   * The declared tools. A plugin's tools ({@code module} false) live in {@code namespace}, declare no {@code
   * connection} parameter and start in Approval whatever they say. A module's tools are a compiled-in connector's:
   * they carry the {@code connection} parameter and their own default modes, under the rules {@code
   * ToolDefinition.Builder} holds a connector to.
   */
  private static List<ToolDefinition> tools(JsonNode array, String namespace, boolean module, String prefix, int max,
      List<String> problems) {
    List<ToolDefinition> out = new ArrayList<>();
    if (!array.isArray() || array.isEmpty()) {
      problems.add(prefix + "tools must list at least one tool");
      return out;
    }
    if (array.size() > max) {
      problems.add(prefix + "tools lists at most " + max + " tools");
    }
    Set<String> names = new HashSet<>();
    for (JsonNode t : array) {
      String name = t.path("name").asString("");
      String where = prefix + "tool " + (name.isEmpty() ? "#" + out.size() : name);
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
      if (!module && input.path("properties").has("connection")) {
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
      ToolDefinition.Mode dev = ToolDefinition.Mode.APPROVAL;
      ToolDefinition.Mode prod = ToolDefinition.Mode.APPROVAL;
      if (module) {
        try {
          dev = ToolDefinition.Mode.valueOf(t.path("defaultMode").asString(""));
          prod = ToolDefinition.Mode.valueOf(t.path("prodDefaultMode").asString(""));
        } catch (IllegalArgumentException e) {
          problems.add(where + ": defaultMode and prodDefaultMode must be OFF, READ_ONLY, APPROVAL or BYPASS");
          continue;
        }
        if (n == ToolDefinition.Nature.MUTATION
            && (dev == ToolDefinition.Mode.READ_ONLY || prod == ToolDefinition.Mode.READ_ONLY)) {
          problems.add(where + ": a mutating tool can't default to Read only");
        }
      }
      out.add(new ToolDefinition(name, title, description, input.deepCopy(), output,
          new ToolDefinition.Annotations(bool(a, "readOnlyHint"), bool(a, "destructiveHint"),
              bool(a, "idempotentHint"), bool(a, "openWorldHint")),
          n, dev, prod, ToolDefinition.ResultShape.valueOf(shape),
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
