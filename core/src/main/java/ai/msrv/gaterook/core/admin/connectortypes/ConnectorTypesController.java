package ai.msrv.gaterook.core.admin.connectortypes;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.admin.Reasons;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionForms;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connectors.ConnectorProcessStatus;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.connectors.Plugins;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.secrets.SecretStores;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector catalog (ARCHITECTURE.md §6 connection-new and connectors, kit {@code connection-new.html} step 1 and
 * {@code connectors.html} › Installed): one card per type in the kit's groups, a type with {@code x-variants} as one
 * card per variant (Logs: GCP Cloud Logging, AWS CloudWatch, Loki, OpenSearch), the
 * generic MCP server type last with "Tools from the server". The Installed list shows each type once with its
 * process; the form gives the schema, its secret fields and what an environment changes.
 */
@RestController
@RegisterReflectionForBinding({ConnectorTypesController.Catalog.class, ConnectorTypesController.Group.class,
    ConnectorTypesController.Card.class, ConnectorTypesController.Installed.class,
    ConnectorTypesController.Process.class, ConnectorTypesController.Variant.class,
    ConnectorTypesController.TypeDetail.class, ConnectorTypesController.Tool.class, ConnectorTypesController.Form.class,
    ConnectorTypesController.SecretField.class, ConnectorTypesController.SecretSource.class,
    ConnectorTypesController.EnvChange.class, ConnectorTypesController.EnvDefaults.class})
class ConnectorTypesController {

  /** The kit's groups in its order (BLUEPRINT §6 connection-new). */
  static final List<String> GROUPS = List.of("Databases", "Remote access", "Logs", "Kubernetes", "Cloud",
      "HTTP and MCP");

  /** Built-in types in the kit's order inside their groups. */
  static final List<String> KIT_ORDER = List.of("postgres", "mysql", "mongodb", "redis", "ssh", "logs", "kubernetes",
      "gcp", "aws", "http", "mcp-server");

  record Reason(String reason) {}

  // ── Responses ───────────────────────────────────────────────────────────────────────────────────────────────

  record Catalog(List<Group> groups, List<Installed> installed) {}

  record Group(String name, List<Card> cards) {}

  /**
   * One card of the wizard's type picker.
   *
   * @param kind        {@code built-in}, {@code plugin} or {@code mcp-server}
   * @param tools       how many tools, or {@code null} when they come from the server ({@code toolsFrom})
   * @param available   whether a connection can be made with it now; else {@code reason}
   * @param variant     the {@code x-variants} entry this card stands for, or {@code null}
   * @param connections connections of this type (and variant)
   */
  record Card(String type, String variant, String name, String icon, String description, String kind,
      String version, Boolean signed, Integer tools, String toolsFrom, int connections, boolean available,
      String reason, JsonNode presetConfig) {}

  /** {@code state}: {@code enabled}, {@code disabled} or {@code changed-outside} (fail closed until reviewed). */
  record Installed(String id, String kind, String name, String icon, String group, String description,
      String version, Boolean signed, Integer tools, int connections, boolean enabled, boolean verified,
      String state, Process process, List<Variant> variants) {}

  /** {@code state}: {@code running}, {@code stopped} ("Sleeping"), {@code failed}, … */
  record Process(String state, Long pid, Integer rssMb, String detail) {}

  record Variant(String id, String name, String icon, String description, JsonNode presetConfig) {}

  record Tool(String name, String title, String description, String nature, String defaultMode,
      String prodDefaultMode, ToolDefinition.Annotations annotations) {}

  record TypeDetail(Installed type, List<Tool> tools, List<String> templateKinds) {}

  /** A secret field of the form and the kind of secret it takes ({@code value}, {@code login}, {@code pem}, …). */
  record SecretField(String pointer, String kind, String title) {}

  /** Where a secret can come from; external stores arrive with S1 and stay unavailable until one is connected. */
  record SecretSource(String id, String label, boolean available, String reason) {}

  record EnvChange(String pointer, String title, JsonNode from, JsonNode to) {}

  /** {@code prod}: what changes when the person picks prod ("Prod starts stricter"). */
  record EnvDefaults(List<EnvChange> prod, List<EnvChange> stage) {}

  /**
   * {@code GET /api/connector-types/{type}/form}: the type's {@code configSchema} as is, plus the name and env fields
   * every connection has.
   */
  record Form(String type, String name, String env, JsonNode schema, JsonNode common, List<SecretField> secretFields,
      List<SecretSource> secretSources, EnvDefaults envDefaults, List<Variant> variants) {}

  /** The picker's line for a catalog plugin that isn't installed (kit {@code connection-new.html}, ClickHouse). */
  static final String NOT_INSTALLED = "Not installed. Install it from the catalog in Connectors first.";

  private final ConnectorRegistry registry;
  private final ConnectionConfigs connections;
  private final ObjectProvider<ConnectorProcessStatus> processes;
  private final SecretStores stores;
  private final Plugins plugins;

  ConnectorTypesController(ConnectorRegistry registry, ConnectionConfigs connections,
      ObjectProvider<ConnectorProcessStatus> processes, SecretStores stores, Plugins plugins) {
    this.registry = registry;
    this.connections = connections;
    this.processes = processes;
    this.stores = stores;
    this.plugins = plugins;
  }

  @GetMapping("/api/connector-types")
  Catalog list(@RequestParam(name = "group", required = false) String group) {
    List<ConnectorType> types = registry.types().stream().sorted(order()).toList();
    List<ConnectionConfigs.Stored> stored = connections.all();
    Map<String, List<Card>> byGroup = new LinkedHashMap<>();
    GROUPS.forEach(g -> byGroup.put(g, new ArrayList<>()));
    List<Installed> installed = new ArrayList<>();
    Map<String, Plugins.Installed> pluginRows = new LinkedHashMap<>();
    plugins.installed().forEach(p -> pluginRows.put(p.id(), p));
    for (ConnectorType t : types) {
      ConnectorManifest m = t.manifest();
      byGroup.computeIfAbsent(m.group(), k -> new ArrayList<>()).addAll(cards(t, stored, pluginRows));
      if (t.kind() != ConnectorManifest.Kind.MCP_SERVER) {
        installed.add(installed(t, stored, pluginRows));
      } else {
        // Each external MCP server is installed on its own (C10): its connection, version, tools and process.
        stored.stream().filter(s -> s.config().type().equals(m.id())).forEach(s -> installed.add(server(t, s)));
      }
    }
    // The catalog's plugins that aren't installed: disabled cards in their group, so the wizard says where to get them
    // (kit: ClickHouse under Databases, "Not installed. Install it from the catalog in Connectors first.").
    for (Plugins.Entry e : plugins.notInstalledPlugins()) {
      if (registry.type(e.id()).isPresent()) {
        continue;
      }
      String g = e.group() != null && GROUPS.contains(e.group()) ? e.group() : "HTTP and MCP";
      byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(new Card(e.id(), null, e.name(),
          e.icon() == null ? "puzzle" : e.icon(), e.description(), "plugin", e.version(), e.signed(), e.tools(), null,
          0, false, NOT_INSTALLED, null));
    }
    List<Group> groups = new ArrayList<>();
    byGroup.forEach((name, cards) -> {
      if (!cards.isEmpty() && (group == null || group.isBlank() || group.equalsIgnoreCase(name))) {
        groups.add(new Group(name, List.copyOf(cards)));
      }
    });
    return new Catalog(List.copyOf(groups), List.copyOf(installed));
  }

  @GetMapping("/api/connector-types/{type}")
  TypeDetail get(@PathVariable String type) {
    ConnectorType t = require(type);
    List<Tool> tools = t.manifest().tools().stream().map(d -> new Tool(d.name(), d.title(), d.description(),
        d.nature().name().toLowerCase(Locale.ROOT), mode(d.defaultMode()), mode(d.prodDefaultMode()),
        d.annotations())).toList();
    List<String> kinds = registry.templateKinds().values().stream()
        .filter(k -> k.connectionTypes().contains(type)).map(ai.msrv.gaterook.connector.api.TemplateKind::kind).sorted()
        .toList();
    return new TypeDetail(installed(t, connections.all()), tools, kinds);
  }

  @GetMapping("/api/connector-types/{type}/form")
  Form form(@PathVariable String type, @RequestParam(name = "env", required = false) String env) {
    ConnectorType t = require(type);
    ConnectorManifest m = t.manifest();
    if (!t.usable()) {
      throw ProblemException.unprocessable("Connector off", m.name() + " is switched off or was changed outside "
          + "GateRook.", "/type");
    }
    ObjectNode common = JsonNodeFactory.instance.objectNode();
    common.putObject("name").put("type", "string").put("maxLength", 64)
        .put("pattern", ConnectionService.NAME.pattern()).put("title", "Name");
    common.putObject("env").put("title", "Environment").putArray("enum").add("dev").add("stage").add("prod");
    List<SecretField> secrets = ConnectionForms.secretFields(m.configSchema()).stream()
        .map(f -> new SecretField(f.pointer(), f.kind(), f.title())).toList();
    // Where a secret field's value can come from: here, or a connected store (S1).
    List<SecretSource> sources = stores.sources().stream()
        .map(src -> new SecretSource(src.id(), src.title(), src.available(), src.reason())).toList();
    return new Form(m.id(), m.name(), env, m.configSchema(), common, secrets, sources,
        new EnvDefaults(envChanges(m, Env.PROD), envChanges(m, Env.STAGE)), variants(m));
  }

  @PostMapping("/api/connector-types/{type}/enable")
  Installed enable(@PathVariable String type, @RequestBody(required = false) Reason request) {
    require(type);
    registry.setEnabled(type, true, request == null ? null : Reasons.check(request.reason()));
    return installed(require(type), connections.all());
  }

  @PostMapping("/api/connector-types/{type}/disable")
  Installed disable(@PathVariable String type, @RequestBody(required = false) Reason request) {
    require(type);
    registry.setEnabled(type, false, request == null ? null : Reasons.check(request.reason()));
    return installed(require(type), connections.all());
  }

  /** The person reviewed a type row changed outside GateRook and keeps it as stored. */
  @PostMapping("/api/connector-types/{type}/confirm-integrity")
  Installed confirmIntegrity(@PathVariable String type, @RequestBody(required = false) Reason request) {
    require(type);
    registry.confirmIntegrity(type, request == null ? null : Reasons.check(request.reason()));
    return installed(require(type), connections.all());
  }

  // ── Mapping ─────────────────────────────────────────────────────────────────────────────────────────────────

  private List<Card> cards(ConnectorType t, List<ConnectionConfigs.Stored> stored,
      Map<String, Plugins.Installed> pluginRows) {
    ConnectorManifest m = t.manifest();
    String kind = kind(m.kind());
    Integer tools = m.dynamicTools() ? null : m.tools().size();
    String toolsFrom = m.dynamicTools() ? "server" : null;
    String reason = !t.verified() ? m.name() + " was changed outside GateRook. Review it in Connectors first."
        : !t.enabled() ? m.name() + " is switched off in Connectors." : null;
    // Whether a plugin is signed is its plugin row's (K4, shown since K6); a built-in needs no signature.
    Boolean signed = signed(m, pluginRows);
    List<Variant> variants = variants(m);
    if (variants.isEmpty()) {
      return List.of(new Card(m.id(), null, m.name(), m.icon(), cardDescription(m), kind, m.version(), signed, tools,
          toolsFrom, count(stored, m.id(), null), t.usable(), reason, null));
    }
    List<Card> out = new ArrayList<>();
    for (Variant v : variants) {
      out.add(new Card(m.id(), v.id(), v.name(), v.icon() == null ? m.icon() : v.icon(), v.description(), kind,
          m.version(), signed, variantToolCount(m, v.id(), tools), toolsFrom,
          count(stored, m.id(), v.presetConfig()), t.usable(), reason,
          v.presetConfig()));
    }
    return out;
  }

  /** A variant may name its available tools; the catalog counts only names present in the manifest. */
  private static Integer variantToolCount(ConnectorManifest manifest, String variant, Integer fallback) {
    if (fallback == null || manifest.configSchema() == null) {
      return fallback;
    }
    for (JsonNode entry : manifest.configSchema().path("x-variants")) {
      if (!entry.path("id").asString("").equals(variant) || !entry.path("tools").isArray()) {
        continue;
      }
      List<String> names = new ArrayList<>();
      entry.path("tools").forEach(name -> names.add(name.asString("")));
      return (int) manifest.tools().stream().filter(tool -> names.contains(tool.name())).count();
    }
    return fallback;
  }

  /**
   * The picker's line for a type: the kit words it shorter than Connectors › Installed ("Read queries, schema,
   * EXPLAIN." against "Schemas, tables and queries on PostgreSQL 12 and later."), so a form may carry its own in
   * {@code x-card.description}; the manifest's description otherwise.
   */
  private static String cardDescription(ConnectorManifest m) {
    JsonNode card = m.configSchema() == null ? null : m.configSchema().path("x-card").path("description");
    return card != null && card.isString() && !card.asString().isBlank() ? card.asString() : m.description();
  }

  /** A type as Installed shows it; a plugin's {@code signed} is its plugin row's. */
  private Installed installed(ConnectorType t, List<ConnectionConfigs.Stored> stored) {
    Map<String, Plugins.Installed> pluginRows = new LinkedHashMap<>();
    if (t.kind() == ConnectorManifest.Kind.PLUGIN) {
      plugins.view(t.id()).ifPresent(p -> pluginRows.put(p.id(), p));
    }
    return installed(t, stored, pluginRows);
  }

  /** Signed or not, for a plugin's type; {@code null} for a built-in or an MCP server type. */
  private static Boolean signed(ConnectorManifest m, Map<String, Plugins.Installed> pluginRows) {
    if (m.kind() != ConnectorManifest.Kind.PLUGIN) {
      return null;
    }
    Plugins.Installed row = pluginRows.get(m.id());
    return row == null ? null : row.signed();
  }

  private Installed installed(ConnectorType t, List<ConnectionConfigs.Stored> stored,
      Map<String, Plugins.Installed> pluginRows) {
    ConnectorManifest m = t.manifest();
    Process process = null;
    for (ConnectorProcessStatus status : processes) {
      Optional<ConnectorProcessStatus.ProcessInfo> info = status.processOf(m.id());
      if (info.isPresent()) {
        ConnectorProcessStatus.ProcessInfo p = info.get();
        process = new Process(p.state().name().toLowerCase(Locale.ROOT), p.pid(), p.rssMb(), p.detail());
        break;
      }
    }
    String state = !t.verified() ? "changed-outside" : t.enabled() ? "enabled" : "disabled";
    return new Installed(m.id(), kind(m.kind()), m.name(), m.icon(), m.group(), m.description(), m.version(),
        signed(m, pluginRows), m.dynamicTools() ? null
            : m.tools().size(), count(stored, m.id(), null), t.enabled(), t.verified(), state, process, variants(m));
  }

  /**
   * One external MCP server as Connectors › Installed and MCP servers show it: {@code id} is its connection's name,
   * the version and tool count are what it reported at its last start (the connection's metadata), the process is
   * its own.
   */
  private Installed server(ConnectorType t, ConnectionConfigs.Stored s) {
    ConnectorManifest m = t.manifest();
    JsonNode meta = s.config().metadata() == null ? JsonNodeFactory.instance.objectNode() : s.config().metadata();
    Process process = null;
    for (ConnectorProcessStatus status : processes) {
      Optional<ConnectorProcessStatus.ProcessInfo> info = status.processOf(s.id());
      if (info.isPresent()) {
        ConnectorProcessStatus.ProcessInfo p = info.get();
        process = new Process(p.state().name().toLowerCase(Locale.ROOT), p.pid(), p.rssMb(), p.detail());
        break;
      }
    }
    boolean verified = t.verified() && s.verified();
    String state = !verified ? "changed-outside" : t.enabled() ? "enabled" : "disabled";
    String name = meta.path("serverName").isString() ? meta.path("serverName").asString() : s.name();
    return new Installed(s.name(), kind(m.kind()), name, m.icon(), m.group(), m.description(),
        meta.path("serverVersion").asString(null), null, meta.path("tools").isNumber() ? meta.path("tools").asInt()
            : null, 1, t.enabled(), verified, state, process, List.of());
  }

  /** Connections of a type; with a variant, those whose config holds every value of its preset. */
  private static int count(List<ConnectionConfigs.Stored> stored, String type, JsonNode preset) {
    int n = 0;
    for (ConnectionConfigs.Stored s : stored) {
      if (s.config().type().equals(type) && (preset == null || matches(s.config().config(), preset))) {
        n++;
      }
    }
    return n;
  }

  private static boolean matches(JsonNode config, JsonNode preset) {
    for (Map.Entry<String, JsonNode> e : preset.properties()) {
      JsonNode value = config.path(e.getKey());
      if (e.getValue().isObject() ? !matches(value, e.getValue()) : !e.getValue().equals(value)) {
        return false;
      }
    }
    return true;
  }

  private static List<Variant> variants(ConnectorManifest m) {
    List<Variant> out = new ArrayList<>();
    if (m.configSchema() == null) {
      return out;
    }
    for (JsonNode v : m.configSchema().path("x-variants")) {
      if (v.path("id").isString()) {
        out.add(new Variant(v.get("id").asString(), v.path("name").asString(v.get("id").asString()),
            v.path("icon").asString(null), v.path("description").asString(null),
            v.path("presetConfig").isObject() ? v.get("presetConfig") : JsonNodeFactory.instance.objectNode()));
      }
    }
    return out;
  }

  private static List<EnvChange> envChanges(ConnectorManifest m, Env env) {
    return ConnectionForms.envDefaults(m.configSchema(), env).stream()
        .map(d -> new EnvChange(d.pointer(), d.title(), d.from(), d.to())).toList();
  }

  private ConnectorType require(String type) {
    return registry.type(type).orElseThrow(() -> ProblemException.notFound("Connector not found",
        "There is no connector type " + type + "."));
  }

  /** Built-ins in the kit's order, then plugins by name; the generic MCP server type always last in its group. */
  private static Comparator<ConnectorType> order() {
    return Comparator.<ConnectorType>comparingInt(t -> t.kind() == ConnectorManifest.Kind.MCP_SERVER ? 2
            : t.kind() == ConnectorManifest.Kind.PLUGIN ? 1 : 0)
        .thenComparingInt(t -> KIT_ORDER.indexOf(t.id()) < 0 ? Integer.MAX_VALUE : KIT_ORDER.indexOf(t.id()))
        .thenComparing(t -> t.manifest().name());
  }

  private static String kind(ConnectorManifest.Kind kind) {
    return switch (kind) {
      case BUILT_IN -> "built-in";
      case PLUGIN -> "plugin";
      case MCP_SERVER -> "mcp-server";
    };
  }

  private static String mode(ToolDefinition.Mode mode) {
    return mode == null ? null : switch (mode) {
      case OFF -> "off";
      case READ_ONLY -> "read-only";
      case APPROVAL -> "approval";
    };
  }
}
