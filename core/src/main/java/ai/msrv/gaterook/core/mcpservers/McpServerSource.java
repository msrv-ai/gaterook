package ai.msrv.gaterook.core.mcpservers;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import ai.msrv.gaterook.core.connectors.ConnectorProcessStatus;
import ai.msrv.gaterook.core.connectors.ConnectorSource;
import ai.msrv.gaterook.core.mcpservers.internal.McpServerRows;
import ai.msrv.gaterook.core.mcpservers.internal.persistence.McpServerEntity;
import ai.msrv.gaterook.core.mcpservers.internal.persistence.McpServerRepository;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The connector of each {@code mcp-server} connection (ARCHITECTURE.md §2.3, §7.5): the runtime's connector over the
 * reviewed snapshot in {@code mcp_server}. A connection without a verified row (changed outside MCP Gateway, or a
 * draft) gets nothing here and falls back to the type's bean, which serves no tools.
 *
 * <p>Every change of a snapshot comes with a new connection version ({@code ConnectionService.recordMetadata}), so a
 * connector built for {@code (id, version)} stays right until the version changes; the row is read and verified once
 * per version, never on the call path.
 */
@Component
public class McpServerSource implements ConnectorSource, ConnectorProcessStatus {

  private record Cached(long version, Connector connector) {}

  private final ObjectProvider<McpServerRuntime> runtime;
  private final McpServerRepository repository;
  private final McpServerRows rows;
  private final IntegrityAlerts alerts;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public McpServerSource(ObjectProvider<McpServerRuntime> runtime, McpServerRepository repository,
      McpServerRows rows, IntegrityAlerts alerts) {
    this.runtime = runtime;
    this.repository = repository;
    this.rows = rows;
    this.alerts = alerts;
  }

  /** The type itself is the {@code mcp-server} connector bean's. */
  @Override
  public List<ConnectorManifest> types() {
    return List.of();
  }

  @Override
  public Optional<Connector> connectorFor(ConnectionConfig cfg) {
    McpServerRuntime r = runtime.getIfAvailable();
    if (r == null || !McpServerService.TYPE.equals(cfg.type()) || cfg.id().startsWith("draft-")) {
      return Optional.empty();
    }
    Cached cached = cache.get(cfg.id());
    if (cached != null && cached.version() == cfg.version()) {
      return Optional.of(cached.connector());
    }
    Optional<McpServerRuntime.Snapshot> snapshot = snapshot(cfg);
    if (snapshot.isEmpty()) {
      cache.remove(cfg.id());
      return Optional.empty();
    }
    Connector connector = r.connector(cfg, snapshot.get());
    cache.put(cfg.id(), new Cached(cfg.version(), connector));
    return Optional.of(connector);
  }

  /** The reviewed snapshot of a connection, or empty when it has none or its row doesn't verify. */
  Optional<McpServerRuntime.Snapshot> snapshot(ConnectionConfig cfg) {
    McpServerEntity row = repository.findByConnectionId(cfg.id()).orElse(null);
    if (row == null) {
      return Optional.empty();
    }
    if (!rows.verify(row)) {
      alerts.report("mcp-server", cfg.id(), cfg.name(), "MCP server row");
      return Optional.empty();
    }
    return Optional.of(snapshot(row));
  }

  McpServerRuntime.Snapshot snapshot(McpServerEntity row) {
    List<JsonNode> tools = new ArrayList<>();
    for (JsonNode t : rows.tree(row.getToolsSnapshot())) {
      tools.add(t);
    }
    String version = row.getServerInfo() == null ? null : String.valueOf(row.getServerInfo().get("version"));
    return new McpServerRuntime.Snapshot(row.getToolPrefix(), version, row.getProtocolVersion(), tools,
        row.getNpmIntegrity());
  }

  /** A connection removed or a snapshot replaced: the next lookup reads the row again. */
  void forget(String connectionId) {
    cache.remove(connectionId);
  }

  @Override
  public Optional<ProcessInfo> processOf(String id) {
    McpServerRuntime r = runtime.getIfAvailable();
    if (r == null) {
      return Optional.empty();
    }
    return r.process(id).map(McpServerSource::info);
  }

  static ProcessInfo info(McpServerRuntime.ProcessView v) {
    return new ProcessInfo(State.valueOf(v.state().name()), v.pid(), v.rssMb(), v.since(), v.detail());
  }
}
