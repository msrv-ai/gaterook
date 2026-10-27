package ai.msrv.gaterook.core.mcpservers;

import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionRemovalParticipant;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.mcpservers.internal.persistence.McpServerRepository;
import ai.msrv.gaterook.core.platform.EventBus;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The core as the MCP server runtime's host: the gateway's home, the names no server may take, what a start listed
 * (into the snapshot), state changes (SSE {@code mcp-server.state}) and starts of servers set to start with the core.
 * It attaches once every singleton exists; the runtime does nothing slow there (warm start ≤ 0.5 s).
 *
 * <p>It also takes part in the deletion of a connection: the snapshot row goes in the deletion's transaction, the
 * process stops after it (the type's connector hears {@code onConnectionRemoved}).
 */
@Component
@Order(50)
class RuntimeHost implements McpServerRuntime.Host, SmartInitializingSingleton, ConnectionRemovalParticipant {

  private final ObjectProvider<McpServerRuntime> runtime;
  private final GatewayPaths paths;
  private final ObjectProvider<ConnectorRegistry> registry;
  private final ObjectProvider<McpServerService> service;
  private final ObjectProvider<ConnectionConfigs> configs;
  private final McpServerRepository repository;
  private final McpServerSource source;
  private final EventBus events;

  RuntimeHost(ObjectProvider<McpServerRuntime> runtime, GatewayPaths paths, ObjectProvider<ConnectorRegistry> registry,
      ObjectProvider<McpServerService> service, ObjectProvider<ConnectionConfigs> configs,
      McpServerRepository repository, McpServerSource source, EventBus events) {
    this.runtime = runtime;
    this.paths = paths;
    this.registry = registry;
    this.service = service;
    this.configs = configs;
    this.repository = repository;
    this.source = source;
    this.events = events;
  }

  @Override
  public void afterSingletonsInstantiated() {
    McpServerRuntime r = runtime.getIfAvailable();
    if (r != null) {
      r.attach(this);
    }
  }

  /** Servers set to start with the core start once it is ready, each on its own thread. */
  @EventListener(ApplicationReadyEvent.class)
  void startWithCore() {
    if (runtime.getIfAvailable() == null) {
      return;
    }
    Thread.ofVirtual().name("mcp-servers-boot").start(() -> {
      McpServerService s = service.getObject();
      for (String id : s.startWithCore()) {
        Thread.ofVirtual().name("mcp-start-" + id).start(() -> s.warm(id));
      }
    });
  }

  @Override
  public Path home() {
    return paths.home();
  }

  @Override
  public Optional<String> reservedProblem(String toolName) {
    ConnectorRegistry r = registry.getIfAvailable();
    return r == null ? Optional.empty() : r.reservedProblem(toolName);
  }

  @Override
  public void handshake(String connectionId, McpServerRuntime.Handshake handshake) {
    service.getObject().recordHandshake(connectionId, handshake);
  }

  @Override
  public void state(String connectionId, McpServerRuntime.ProcessView process) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    configs.getObject().byId(connectionId).ifPresent(c -> data.put("name", c.name()));
    if (!data.has("name")) {
      return;
    }
    data.put("state", process.state().name().toLowerCase(Locale.ROOT));
    if (process.pid() != null) {
      data.put("pid", process.pid());
    }
    if (process.rssMb() != null) {
      data.put("rssMb", process.rssMb());
    }
    if (process.detail() != null) {
      data.put("detail", process.detail());
    }
    events.publish("mcp-server.state", data);
  }

  @Override
  public void wanted(String connectionId) {
    service.getObject().warm(connectionId);
  }

  @Override
  public Removal onRemoving(String connectionId, String connectionName, String reason) {
    repository.findByConnectionId(connectionId).ifPresent(repository::delete);
    repository.flush();
    source.forget(connectionId);
    return Removal.none();
  }
}
