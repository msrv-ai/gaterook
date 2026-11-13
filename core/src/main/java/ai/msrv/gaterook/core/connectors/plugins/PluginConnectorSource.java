package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connectors.support.egress.EgressClients;
import ai.msrv.gaterook.core.connectors.ConnectorProcessStatus;
import ai.msrv.gaterook.core.connectors.ConnectorSource;
import ai.msrv.gaterook.core.connectors.EnvironmentPolicies;
import ai.msrv.gaterook.core.platform.EventBus;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The plugins as connectors (ARCHITECTURE.md §2.3): one {@link RemoteConnector} per installed plugin, its manifest read
 * from the database so the registry and {@code tools/list} never start a host, and one {@link PluginSupervisor} that
 * runs the host on demand. {@code ConnectorRegistry} collects this {@code ConnectorSource} like any other; the
 * admin API reads a plugin's process state through {@code ConnectorProcessStatus}.
 *
 * <p>This bean is the live set of installed plugins. {@link PluginService} fills it at start and on every install,
 * update, enable, disable and uninstall; the registry and the invocation path only read it.
 */
@Component
public class PluginConnectorSource implements ConnectorSource, ConnectorProcessStatus, DisposableBean {

  /** How often running hosts are pinged and idle ones stopped (report 16 §4.3: a ping every 30 s). */
  static final Duration SWEEP = Duration.ofSeconds(30);
  private static final Logger log = LoggerFactory.getLogger(PluginConnectorSource.class);

  /** One connector type: a plugin's ({@code module} is {@code null}), or one of a connector module's. */
  private record Live(ConnectorManifest manifest, PluginSupervisor supervisor, Connector connector, String module) {}

  /** One installed connector module: its host's supervisor and the types it adds. */
  private record Module(PluginSupervisor supervisor, List<String> types) {}

  private final HostLauncher launcher;
  private final SecretCallbackRegistry secrets;
  private final ObjectProvider<EnvironmentPolicies> policies;
  private final EgressClients egress;
  private final Clock clock;
  private final EventBus events;
  private final Map<String, Live> byId = new ConcurrentHashMap<>();
  private final Map<String, Module> modules = new ConcurrentHashMap<>();
  /** Tool names of the modules the channel lists (set by the module service). */
  private volatile java.util.function.Supplier<java.util.Set<String>> channelTools = java.util.Set::of;
  private ScheduledExecutorService housekeeping;

  PluginConnectorSource(HostLauncher launcher, SecretCallbackRegistry secrets,
      ObjectProvider<EnvironmentPolicies> policies, EgressClients egress, Clock clock, EventBus events) {
    this.launcher = launcher;
    this.secrets = secrets;
    this.policies = policies;
    this.egress = egress;
    this.clock = clock;
    this.events = events;
  }

  // ── ConnectorSource ─────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public List<ConnectorManifest> types() {
    return byId.values().stream().map(Live::manifest).toList();
  }

  @Override
  public boolean firstParty(String typeId) {
    Live live = byId.get(typeId);
    return live != null && live.module() != null;
  }

  @Override
  public boolean unverified(String typeId) {
    Live live = byId.get(typeId);
    return live != null && live.connector() instanceof ModuleConnector module && !module.signed();
  }

  @Override
  public Optional<String> packageOf(String typeId) {
    Live live = byId.get(typeId);
    return live == null ? Optional.empty() : Optional.of(live.module() != null ? live.module() : typeId);
  }

  @Override
  public List<Connector> firstPartyConnectors() {
    return byId.values().stream().filter(l -> l.module() != null).map(Live::connector).toList();
  }

  @Override
  public java.util.Set<String> reservedToolNames() {
    return channelTools.get();
  }

  @Override
  public Optional<Connector> connectorFor(ConnectionConfig connection) {
    Live live = byId.get(connection.type());
    return live == null ? Optional.empty() : Optional.of(live.connector());
  }

  // ── ConnectorProcessStatus ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public Optional<ProcessInfo> processOf(String id) {
    PluginSupervisor supervisor = supervisor(id);
    return supervisor == null ? Optional.empty() : supervisor.status();
  }

  // ── Managed by PluginService ────────────────────────────────────────────────────────────────────────────────

  /**
   * Installs or replaces the live entry for a plugin; its host starts on the first call. {@code signed} names the
   * build in the secondary block of its approvals ("(unsigned)").
   */
  void register(ConnectorManifest declared, HostLauncher.Spec spec, String logLevel, int idleStopMinutes,
      boolean enabled, boolean signed) {
    ConnectorManifest manifest = withConnectionParameter(declared);
    Live existing = byId.get(manifest.id());
    PluginSupervisor supervisor = existing != null ? existing.supervisor() : supervisorFor(manifest.id());
    supervisor.spec(spec);
    supervisor.logLevel(logLevel);
    supervisor.idleStopMinutes(idleStopMinutes);
    if (enabled) {
      supervisor.enable();
    } else {
      supervisor.disable();
    }
    RemoteConnector connector = new RemoteConnector(manifest, supervisor, secrets, policies.getObject(), egress,
        signed);
    byId.put(manifest.id(), new Live(manifest, supervisor, connector, null));
  }

  /**
   * Installs or replaces the live entries of a connector module: one {@link ModuleConnector} per connector type, all
   * on one host, which starts on the first call. A type another package already adds isn't taken from it.
   *
   * @param gate   asked before a call starts the host: the module's files may be missing (a restored backup)
   * @param signed whether GateRook's signed list pins the package; a person's own module from a file isn't
   * @return the types that couldn't be registered, because another package has them
   */
  List<String> registerModule(ai.msrv.gaterook.plugin.protocol.PluginManifest module, HostLauncher.Spec spec,
      boolean enabled, ModuleConnector.Gate gate, boolean signed) {
    String id = module.id();
    Module existing = modules.get(id);
    PluginSupervisor supervisor = existing != null ? existing.supervisor()
        : supervisorFor(id, module.maxInFlight());
    supervisor.spec(spec);
    supervisor.logLevel("info");
    supervisor.idleStopMinutes(15);
    if (enabled) {
      supervisor.enable();
    } else {
      supervisor.disable();
    }
    List<String> types = new ArrayList<>();
    List<String> taken = new ArrayList<>();
    for (ConnectorManifest manifest : module.connectorManifests()) {
      Live other = byId.get(manifest.id());
      if (other != null && !id.equals(other.module())) {
        taken.add(manifest.id());
        continue;
      }
      byId.put(manifest.id(), new Live(manifest, supervisor, new ModuleConnector(module, manifest, supervisor,
          secrets, policies.getObject(), egress, gate, signed), id));
      types.add(manifest.id());
    }
    if (existing != null) {
      // A type the new version no longer has leaves with the old one.
      existing.types().stream().filter(t -> !types.contains(t)).forEach(t -> byId.computeIfPresent(t,
          (k, live) -> id.equals(live.module()) ? null : live));
    }
    modules.put(id, new Module(supervisor, List.copyOf(types)));
    return List.copyOf(taken);
  }

  /** Removes a connector module's types and stops its host. */
  void removeModule(String id) {
    Module module = modules.remove(id);
    if (module != null) {
      module.types().forEach(t -> byId.computeIfPresent(t, (k, live) -> id.equals(live.module()) ? null : live));
      module.supervisor().stop();
    }
  }

  /** The connector types an installed module adds, or empty when it isn't registered. */
  List<String> moduleTypes(String id) {
    Module module = modules.get(id);
    return module == null ? List.of() : module.types();
  }

  /** The module that adds {@code typeId}, if one does. */
  Optional<String> moduleOf(String typeId) {
    Live live = byId.get(typeId);
    return live == null ? Optional.empty() : Optional.ofNullable(live.module());
  }

  /** The tool names of the modules the channel lists, which no foreign code may take. */
  void channelTools(java.util.function.Supplier<java.util.Set<String>> names) {
    this.channelTools = names;
  }

  /** A supervisor whose state changes reach the UI as {@code plugin.state} (the Connectors page reloads on them). */
  private PluginSupervisor supervisorFor(String id) {
    return supervisorFor(id, PluginSupervisor.BULKHEAD);
  }

  private PluginSupervisor supervisorFor(String id, int calls) {
    PluginSupervisor[] self = new PluginSupervisor[1];
    self[0] = new PluginSupervisor(id, launcher, clock, () -> publishState(id, self[0]), calls);
    return self[0];
  }

  private void publishState(String id, PluginSupervisor supervisor) {
    if (supervisor == null) {
      return;
    }
    supervisor.status().ifPresent(p -> {
      ObjectNode data = JsonNodeFactory.instance.objectNode().put("id", id)
          .put("state", p.state().name().toLowerCase(Locale.ROOT));
      if (p.pid() != null) {
        data.put("pid", p.pid());
      }
      if (p.rssMb() != null) {
        data.put("rssMb", p.rssMb());
      }
      if (p.detail() != null) {
        data.put("detail", p.detail());
      }
      events.publish("plugin.state", data);
    });
  }

  /** The supervisor of a plugin, of a connector module, or of the module that adds the connector type {@code id}. */
  PluginSupervisor supervisor(String id) {
    Module module = modules.get(id);
    if (module != null) {
      return module.supervisor();
    }
    Live live = byId.get(id);
    return live == null ? null : live.supervisor();
  }

  Optional<ConnectorManifest> manifest(String id) {
    Live live = byId.get(id);
    return Optional.ofNullable(live == null ? null : live.manifest());
  }

  /** Removes a plugin's type and stops its host. A connector module leaves with {@link #removeModule}. */
  void remove(String id) {
    Live live = byId.get(id);
    if (live != null && live.module() == null) {
      byId.remove(id);
      live.supervisor().stop();
    }
  }

  java.util.Set<String> ids() {
    return java.util.Set.copyOf(byId.keySet());
  }

  /** Every host's supervisor once: a module's types share one. */
  private java.util.Set<PluginSupervisor> supervisors() {
    java.util.Set<PluginSupervisor> all = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    byId.values().forEach(live -> all.add(live.supervisor()));
    modules.values().forEach(module -> all.add(module.supervisor()));
    return all;
  }

  /** Stops every host (core shutdown). */
  void stopAll() {
    supervisors().forEach(PluginSupervisor::stop);
  }

  /**
   * Every {@link #SWEEP}: each running host is pinged (two misses replace it) and one idle past its time is stopped
   * (report 16 §3.4, §6). Review 2026-10-27: nothing called the supervisors' ping and idle sweep, so a host, once
   * started, ran until the core stopped, and a hung one was never noticed between calls.
   */
  @EventListener(ApplicationReadyEvent.class)
  public synchronized void startHousekeeping() {
    if (housekeeping != null) {
      return;
    }
    housekeeping = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("plugin-housekeeping")
        .factory());
    housekeeping.scheduleWithFixedDelay(this::sweep, SWEEP.toSeconds(), SWEEP.toSeconds(), TimeUnit.SECONDS);
  }

  /** One round of pings and idle stops. */
  void sweep() {
    for (PluginSupervisor supervisor : supervisors()) {
      try {
        supervisor.ping();
        supervisor.idleSweep();
      } catch (RuntimeException e) {
        log.debug("Plugin {} housekeeping failed: {}", supervisor.pluginId(), e.toString());
      }
    }
  }

  /** The core stops: its hosts are asked to shut down (they also leave at the end of their stdin). */
  @Override
  public synchronized void destroy() throws InterruptedException {
    if (housekeeping != null) {
      housekeeping.shutdownNow();
      housekeeping.awaitTermination(5, TimeUnit.SECONDS);
      housekeeping = null;
    }
    stopAll();
  }

  /**
   * The tools as the gateway publishes them (report 11 §3.3: "the core adds {@code connection} to the schema"):
   * a plugin declares its tools without the {@code connection} parameter (the manifest parser refuses one), and the
   * core adds it first and required, like every built-in tool has it, so the endpoint lists the connections of the
   * type in its enum and a call names the one it runs on. The host's own definitions — checked against the manifest
   * by hash at every load — stay as declared; the plugin ignores the extra argument.
   */
  static ConnectorManifest withConnectionParameter(ConnectorManifest m) {
    List<ToolDefinition> tools = new ArrayList<>();
    for (ToolDefinition t : m.tools()) {
      ObjectNode input = t.inputSchema() instanceof ObjectNode o ? o.deepCopy()
          : JsonNodeFactory.instance.objectNode().put("type", "object");
      if (!input.path("properties").has("connection")) {
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        properties.putObject("connection").put("type", "string").put("description",
            "The connection to run on");
        input.path("properties").properties().forEach(e -> properties.set(e.getKey(), e.getValue()));
        input.set("properties", properties);
        ArrayNode required = JsonNodeFactory.instance.arrayNode().add("connection");
        input.path("required").forEach(r -> {
          if (!"connection".equals(r.asString())) {
            required.add(r);
          }
        });
        input.set("required", required);
      }
      tools.add(new ToolDefinition(t.name(), t.title(), t.description(), input, t.outputSchema(), t.annotations(),
          t.nature(), t.defaultMode(), t.prodDefaultMode(), t.resultShape(), t.risk()));
    }
    return new ConnectorManifest(m.id(), m.version(), m.kind(), m.name(), m.icon(), m.group(), m.description(),
        m.configSchema(), m.policySchema(), List.copyOf(tools), m.access(), m.dynamicTools(), m.connectorApi());
  }
}
