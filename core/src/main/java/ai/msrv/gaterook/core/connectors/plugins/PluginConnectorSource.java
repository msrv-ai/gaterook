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

  private record Live(ConnectorManifest manifest, PluginSupervisor supervisor, RemoteConnector connector) {}

  private final HostLauncher launcher;
  private final SecretCallbackRegistry secrets;
  private final ObjectProvider<EnvironmentPolicies> policies;
  private final EgressClients egress;
  private final Clock clock;
  private final EventBus events;
  private final Map<String, Live> byId = new ConcurrentHashMap<>();
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
  public Optional<Connector> connectorFor(ConnectionConfig connection) {
    Live live = byId.get(connection.type());
    return live == null ? Optional.empty() : Optional.of(live.connector());
  }

  // ── ConnectorProcessStatus ──────────────────────────────────────────────────────────────────────────────────

  @Override
  public Optional<ProcessInfo> processOf(String id) {
    Live live = byId.get(id);
    return live == null ? Optional.empty() : live.supervisor().status();
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
    byId.put(manifest.id(), new Live(manifest, supervisor, connector));
  }

  /** A supervisor whose state changes reach the UI as {@code plugin.state} (the Connectors page reloads on them). */
  private PluginSupervisor supervisorFor(String id) {
    PluginSupervisor[] self = new PluginSupervisor[1];
    self[0] = new PluginSupervisor(id, launcher, clock, () -> publishState(id, self[0]));
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

  PluginSupervisor supervisor(String id) {
    Live live = byId.get(id);
    return live == null ? null : live.supervisor();
  }

  Optional<ConnectorManifest> manifest(String id) {
    Live live = byId.get(id);
    return Optional.ofNullable(live == null ? null : live.manifest());
  }

  void remove(String id) {
    Live live = byId.remove(id);
    if (live != null) {
      live.supervisor().stop();
    }
  }

  java.util.Set<String> ids() {
    return java.util.Set.copyOf(byId.keySet());
  }

  /** Stops every host (core shutdown). */
  void stopAll() {
    byId.values().forEach(live -> live.supervisor().stop());
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
    for (Live live : byId.values()) {
      try {
        live.supervisor().ping();
        live.supervisor().idleSweep();
      } catch (RuntimeException e) {
        log.debug("Plugin {} housekeeping failed: {}", live.manifest().id(), e.toString());
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
