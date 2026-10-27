package ai.msrv.gaterook.core.endpoints.internal;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.endpoints.CoreTools;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * The rows that wire connections and tools into an endpoint ({@code endpoint_connection}, {@code endpoint_tool}),
 * written inside the callers' transactions, each one signed. Also the environment rule: an endpoint's env is the
 * strictest of its connections, and prod never mixes with dev or stage.
 */
@Component
public class Wiring {

  /** One pair as it was written. */
  public record Written(String toolKey, String tool, Mode mode) {}

  private final EndpointConnectionRepository wiring;
  private final EndpointToolRepository tools;
  private final EndpointRows rows;
  private final ToolPairs pairs;
  private final ConnectionConfigs connections;
  private final ConnectorRegistry registry;
  private final Clock clock;

  public Wiring(EndpointConnectionRepository wiring, EndpointToolRepository tools, EndpointRows rows, ToolPairs pairs,
      ConnectionConfigs connections, ConnectorRegistry registry, Clock clock) {
    this.wiring = wiring;
    this.tools = tools;
    this.rows = rows;
    this.pairs = pairs;
    this.connections = connections;
    this.registry = registry;
    this.clock = clock;
  }

  /**
   * Wires {@code c} into {@code e} with a mode per offered tool. Its tools are asked for together with the endpoint's
   * other connections of its type (one {@code EndpointView}, see {@link ToolPairs#offered}).
   */
  public List<Written> wire(EndpointEntity e, ConnectionConfigs.Stored c, ConnectorManifest m,
      Function<ToolPairs.Offered, Mode> modes) {
    rows.save(new EndpointConnectionEntity(e, c.id(), clock.instant()));
    List<Written> out = new ArrayList<>();
    for (ToolPairs.Offered offered : offered(e, c.config())) {
      Mode mode = modes.apply(offered);
      rows.save(new EndpointToolEntity(e, c.id(), offered.toolKey(), mode, clock.instant()));
      out.add(new Written(offered.toolKey(), offered.definition().name(), mode));
    }
    return out;
  }

  /** Removes a connection and its pairs from {@code e}; returns the tools that were on. */
  public List<String> unwire(EndpointEntity e, String connectionId) {
    List<String> stopped = new ArrayList<>();
    for (EndpointToolEntity t : tools.findByEndpointIdAndConnectionId(e.getId(), connectionId)) {
      if (t.getMode() != Mode.OFF) {
        stopped.add(toolName(t.getToolKey()));
      }
      tools.delete(t);
    }
    wiring.findByEndpointIdAndConnectionId(e.getId(), connectionId).ifPresent(wiring::delete);
    tools.flush();
    wiring.flush();
    return stopped;
  }

  /** The endpoint-wide core tools, if missing. */
  public void ensureCoreTools(EndpointEntity e, Mode mode) {
    for (String name : CoreTools.WITH_MODE) {
      if (tools.findByEndpointIdAndConnectionIdIsNullAndToolKey(e.getId(), name).isEmpty()) {
        rows.save(new EndpointToolEntity(e, null, name, mode, clock.instant()));
      }
    }
  }

  public List<EndpointConnectionEntity> wired(String endpointId) {
    return wiring.findByEndpointIdOrderByAddedAtAscIdAsc(endpointId);
  }

  /** What {@code cfg}, wired into {@code e}, offers there: asked together with the others of its type on {@code e}. */
  public List<ToolPairs.Offered> offered(EndpointEntity e, ConnectionConfig cfg) {
    return pairs.offeredFor(cfg, members(e.getId()), e.getSlug(), e.getEnv());
  }

  /**
   * The connections wired into an endpoint, in wiring order, as {@link ToolPairs#offered} groups them: usable when the
   * row verified and the type is on.
   */
  public List<ToolPairs.Member> members(String endpointId) {
    List<ToolPairs.Member> out = new ArrayList<>();
    for (EndpointConnectionEntity w : wiring.findByEndpointIdOrderByAddedAtAscIdAsc(endpointId)) {
      Optional<ConnectionConfigs.Stored> c = connections.byId(w.getConnectionId());
      Optional<ConnectorType> type = c.flatMap(s -> registry.type(s.config().type()));
      if (c.isPresent() && type.isPresent()) {
        out.add(new ToolPairs.Member(c.get().config(), type.get().manifest(), c.get().verified()
            && type.get().usable()));
      }
    }
    return out;
  }

  /** The strictest env of the wired connections, or {@code current} when there are none. */
  public Env deriveEnv(String endpointId, Env current) {
    Env out = null;
    for (EndpointConnectionEntity w : wiring.findByEndpointIdOrderByAddedAtAscIdAsc(endpointId)) {
      Optional<ConnectionConfigs.Stored> c = connections.byId(w.getConnectionId());
      if (c.isEmpty()) {
        continue;
      }
      Env env = c.get().config().env();
      if (out == null || env.ordinal() > out.ordinal()) {
        out = env;
      }
    }
    return out == null ? current : out;
  }

  /**
   * Why a connection of {@code connectionEnv} can't join an endpoint of {@code endpointEnv} (prod never mixes), or
   * empty. An endpoint without connections takes any.
   */
  public static Optional<String> mixing(Env endpointEnv, boolean hasConnections, Env connectionEnv, String slug,
      String connection) {
    if (!hasConnections) {
      return Optional.empty();
    }
    if (endpointEnv == Env.PROD && connectionEnv != Env.PROD) {
      return Optional.of(slug + " accepts prod connections only");
    }
    if (endpointEnv != Env.PROD && connectionEnv == Env.PROD) {
      return Optional.of(connection + " is a prod connection: it goes only on a prod endpoint, and " + slug + " is "
          + endpointEnv.id());
    }
    return Optional.empty();
  }

  /** The name a tool key shows: {@code tpl:…:q} → the key itself is not a name, the caller passes names it knows. */
  public static String toolName(String toolKey) {
    if (toolKey.startsWith("plugin:") || toolKey.startsWith("mcp:")) {
      return toolKey.substring(toolKey.lastIndexOf(':') + 1);
    }
    return toolKey;
  }
}
