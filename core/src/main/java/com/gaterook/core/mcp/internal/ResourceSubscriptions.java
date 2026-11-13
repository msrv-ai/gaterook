package com.gaterook.core.mcp.internal;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ResourceDescriptor;
import com.gaterook.core.connectors.ConnectorRegistry;
import com.gaterook.core.endpoints.EndpointSnapshot;
import com.gaterook.core.endpoints.EndpointSnapshotService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who hears updates of which resources (ARCHITECTURE.md §5.5): a 2025 client's {@code resources/subscribe} (by
 * principal, whose GET stream tells them, until it unsubscribes or the core stops) and a 2026-07-28 client's
 * {@code subscriptions/listen} stream (for as long as it is open). Each connection gets the union of what its endpoints' clients subscribed to
 * ({@code Connector.subscriptions}), so an external MCP server is asked for updates of each resource once; an update
 * goes to every subscriber of that URI.
 */
@Component
public class ResourceSubscriptions {

  /** At most this many resources one subscriber hears. */
  static final int MAX_PER_OWNER = 100;
  /** At most this many subscribers at once. */
  static final int MAX_OWNERS = 1_024;

  private record Entry(String endpointId, Set<String> uris) {}

  /** What a connection was last told, and its config to tell it again. */
  private record Told(ConnectionConfig cfg, Set<String> uris) {}

  private final EndpointSnapshotService snapshots;
  private final ConnectorRegistry registry;
  private final Map<Object, Entry> owners = new LinkedHashMap<>();
  /** What each connection was last told, by id. */
  private final Map<String, Told> told = new HashMap<>();

  public ResourceSubscriptions(EndpointSnapshotService snapshots, ConnectorRegistry registry) {
    this.snapshots = snapshots;
    this.registry = registry;
  }

  /** The 2025 key of a client: its principal, whose GET streams carry the updates. */
  public static String legacy(String principal) {
    return "legacy\0" + principal;
  }

  /** @return whether {@code owner} subscribes to {@code uri} now */
  public synchronized boolean has(Object owner, String uri) {
    Entry e = owners.get(owner);
    return e != null && e.uris().contains(uri);
  }

  synchronized Set<String> of(Object owner) {
    Entry e = owners.get(owner);
    return e == null ? Set.of() : e.uris();
  }

  /** @return whether it was taken (a subscriber hears at most {@value #MAX_PER_OWNER}) */
  public boolean add(Object owner, String endpointId, String uri) {
    synchronized (this) {
      Entry e = owners.get(owner);
      Set<String> uris = new LinkedHashSet<>(e == null ? Set.of() : e.uris());
      if (uris.contains(uri)) {
        return true;
      }
      if (uris.size() >= MAX_PER_OWNER || (e == null && owners.size() >= MAX_OWNERS)) {
        return false;
      }
      uris.add(uri);
      owners.put(owner, new Entry(endpointId, Set.copyOf(uris)));
    }
    push();
    return true;
  }

  void remove(Object owner, String uri) {
    synchronized (this) {
      Entry e = owners.get(owner);
      if (e == null || !e.uris().contains(uri)) {
        return;
      }
      Set<String> uris = new LinkedHashSet<>(e.uris());
      uris.remove(uri);
      if (uris.isEmpty()) {
        owners.remove(owner);
      } else {
        owners.put(owner, new Entry(e.endpointId(), Set.copyOf(uris)));
      }
    }
    push();
  }

  /** Everything {@code owner} subscribed to is let go (its stream closed). */
  public void forget(Object owner) {
    synchronized (this) {
      if (owners.remove(owner) == null) {
        return;
      }
    }
    push();
  }

  /** Tells each connection whose union changed what its endpoints' clients subscribe to now. */
  private void push() {
    Map<String, ConnectionConfig> configs = new HashMap<>();
    Map<String, Set<String>> wanted = new HashMap<>();
    synchronized (this) {
      for (Entry e : owners.values()) {
        EndpointSnapshot s = snapshots.get(e.endpointId()).orElse(null);
        if (s == null) {
          continue;
        }
        for (String uri : e.uris()) {
          for (EndpointSnapshot.Wired w : s.connections()) {
            if (uri.startsWith(ResourceDescriptor.prefix(w.config().name()))) {
              configs.put(w.config().id(), w.config());
              wanted.computeIfAbsent(w.config().id(), k -> new LinkedHashSet<>()).add(uri);
            }
          }
        }
      }
      told.forEach((id, t) -> {
        configs.putIfAbsent(id, t.cfg());
        wanted.putIfAbsent(id, Set.of());
      });
    }
    for (Map.Entry<String, Set<String>> w : wanted.entrySet()) {
      Set<String> now = Set.copyOf(w.getValue());
      ConnectionConfig cfg = configs.get(w.getKey());
      synchronized (this) {
        Told before = told.get(w.getKey());
        if ((before == null ? Set.of() : before.uris()).equals(now)) {
          continue;
        }
        if (now.isEmpty()) {
          told.remove(w.getKey());
        } else {
          told.put(w.getKey(), new Told(cfg, now));
        }
      }
      registry.connectorFor(cfg).ifPresent(c -> {
        try {
          c.subscriptions(cfg, now);
        } catch (RuntimeException e) {
          // A connector that can't subscribe sends no updates; the subscription itself stands.
        }
      });
    }
  }
}
