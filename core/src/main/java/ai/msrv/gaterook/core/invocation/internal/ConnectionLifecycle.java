package ai.msrv.gaterook.core.invocation.internal;

import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.core.connections.ConnectionChanged;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionRemoved;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connectors.CallContexts;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.invocation.ToolGuard;
import ai.msrv.gaterook.core.policy.PolicyChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps what lives per connection in step with it (ARCHITECTURE.md §3 {@code onConnectionChanged}): a changed
 * connection's connector drops its pools, its breaker and bulkhead start fresh and its pooled egress sockets close; a
 * removed one is forgotten. A policy change gives every connection of the environment a new version, so pools keyed on
 * {@code (id, version)} are rebuilt under the new limits.
 */
@Component
class ConnectionLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ConnectionLifecycle.class);

  private final ConnectorRegistry registry;
  private final ConnectionConfigs configs;
  private final ConnectionService connections;
  private final ToolGuard guard;
  private final CallContexts contexts;

  ConnectionLifecycle(ConnectorRegistry registry, ConnectionConfigs configs, ConnectionService connections,
      ToolGuard guard, CallContexts contexts) {
    this.registry = registry;
    this.configs = configs;
    this.connections = connections;
    this.guard = guard;
    this.contexts = contexts;
  }

  @Async
  @TransactionalEventListener
  public void onConnectionChanged(ConnectionChanged event) {
    guard.forget(event.connectionId());
    contexts.evict(event.connectionId());
    configs.byId(event.connectionId()).flatMap(s -> registry.connectorFor(s.config())).ifPresent(c -> notify(c,
        event.connectionId(), false));
  }

  @Async
  @TransactionalEventListener
  public void onConnectionRemoved(ConnectionRemoved event) {
    guard.forget(event.connectionId());
    contexts.evict(event.connectionId());
    for (Connector c : registry.builtInConnectors()) {
      if (c.manifest().id().equals(event.type())) {
        notify(c, event.connectionId(), true);
      }
    }
  }

  @Async
  @TransactionalEventListener
  public void onPolicyChanged(PolicyChanged event) {
    connections.touchEnv(event.env());
  }

  private static void notify(Connector c, String connectionId, boolean removed) {
    try {
      if (removed) {
        c.onConnectionRemoved(connectionId);
      } else {
        c.onConnectionChanged(connectionId);
      }
    } catch (RuntimeException e) {
      // A connector that fails to drop a pool must not stop the others from hearing of the change.
      log.warn("{} failed to drop the pools of connection {}: {}", c.manifest().id(), connectionId, e.toString());
    }
  }
}
