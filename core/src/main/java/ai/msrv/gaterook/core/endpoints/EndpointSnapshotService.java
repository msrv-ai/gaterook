package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionChanged;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionRemoved;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.connectors.ConnectorTypeChanged;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.ToolPairs;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointAccess;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.MasterKeyChanged;
import ai.msrv.gaterook.core.policy.PairView;
import ai.msrv.gaterook.core.policy.PolicyChanged;
import ai.msrv.gaterook.core.templates.TemplateChanged;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Builds and caches {@link EndpointSnapshot}s (ARCHITECTURE.md §5 step 4). Every row the snapshot reads is verified
 * against its MAC; one that doesn't verify blocks the endpoint ("Changed outside GateRook"). Invalidation is
 * synchronous after commit: the change that triggered it is visible to the very next call.
 */
@Service
public class EndpointSnapshotService {

  private final EndpointRepository endpoints;
  private final EndpointConnectionRepository wiring;
  private final EndpointToolRepository tools;
  private final EndpointRows rows;
  private final ConnectionConfigs connections;
  private final ConnectorRegistry registry;
  private final ToolPairs pairs;
  private final IntegrityAlerts alerts;
  private final TransactionTemplate readTx;
  private final Map<String, EndpointSnapshot> cache = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

  public EndpointSnapshotService(EndpointRepository endpoints, EndpointConnectionRepository wiring,
      EndpointToolRepository tools, EndpointRows rows, ConnectionConfigs connections, ConnectorRegistry registry,
      ToolPairs pairs, IntegrityAlerts alerts, PlatformTransactionManager transactions) {
    this.endpoints = endpoints;
    this.wiring = wiring;
    this.tools = tools;
    this.rows = rows;
    this.connections = connections;
    this.registry = registry;
    this.pairs = pairs;
    this.alerts = alerts;
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  public Optional<EndpointSnapshot> get(String endpointId) {
    EndpointSnapshot cached = cache.get(endpointId);
    if (cached != null) {
      return Optional.of(cached);
    }
    // Inside a caller's transaction the rows may hold what it hasn't committed (and may roll back), or a database
    // snapshot older than a change whose eviction already ran (SQLite fixes a read transaction's view at its first
    // statement): such a snapshot serves the caller and is never cached.
    boolean joined = TransactionSynchronizationManager.isActualTransactionActive();
    long generationAtStart = generation.get();
    Optional<EndpointSnapshot> built = readTx.execute(s -> endpoints.findById(endpointId).map(this::build));
    // A change that committed while this was being built has already evicted: don't put the old view back.
    if (!joined) {
      built.ifPresent(snapshot -> {
        synchronized (cache) {
          if (generation.get() == generationAtStart) {
            cache.put(endpointId, snapshot);
          }
        }
      });
    }
    return built;
  }

  /**
   * The endpoint as its rows say right now, every row verified again, never from or into the cache: what a change
   * checks before it copies or signs again what it read (duplicate, split, read-only switch). Call it before the
   * change touches any row, or the change itself reads as an edit made outside the core.
   */
  public Optional<EndpointSnapshot> uncached(String endpointId) {
    return readTx.execute(s -> endpoints.findById(endpointId).map(this::build));
  }

  public Optional<EndpointSnapshot> bySlug(String slug) {
    boolean inWrite = TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    for (EndpointSnapshot s : inWrite ? java.util.List.<EndpointSnapshot>of() : cache.values()) {
      if (s.slug().equals(slug)) {
        return Optional.of(s);
      }
    }
    return readTx.execute(st -> endpoints.findBySlug(slug).map(e -> e.getId())).flatMap(this::get);
  }

  // ── Invalidation ────────────────────────────────────────────────────────────────────────────────────────────

  @TransactionalEventListener
  public void onEndpointChanged(EndpointChanged e) {
    evict(e.endpointId());
  }

  @TransactionalEventListener
  public void onEndpointRemoved(EndpointRemoved e) {
    evict(e.endpointId());
  }

  @TransactionalEventListener
  public void onConnectionChanged(ConnectionChanged e) {
    evictAll();
  }

  @TransactionalEventListener
  public void onConnectionRemoved(ConnectionRemoved e) {
    evictAll();
  }

  @TransactionalEventListener
  public void onPolicyChanged(PolicyChanged e) {
    evictAll();
  }

  @TransactionalEventListener
  public void onTemplateChanged(TemplateChanged e) {
    evictAll();
  }

  @TransactionalEventListener
  public void onConnectorTypeChanged(ConnectorTypeChanged e) {
    evictAll();
  }

  /** New derived keys: every MAC must be checked again. */
  @EventListener
  public void onMasterKeyChanged(MasterKeyChanged e) {
    evictAll();
  }

  public void invalidateAll() {
    evictAll();
  }

  private void evict(String endpointId) {
    synchronized (cache) {
      generation.incrementAndGet();
      cache.remove(endpointId);
    }
  }

  private void evictAll() {
    synchronized (cache) {
      generation.incrementAndGet();
      cache.clear();
    }
  }

  // ── Building ────────────────────────────────────────────────────────────────────────────────────────────────

  private EndpointSnapshot build(EndpointEntity e) {
    String blocked = null;
    if (!rows.verify(e)) {
      blocked = "endpoint row";
    }
    boolean readOnly = e.getAccess() == EndpointAccess.READ_ONLY;
    Map<String, EndpointToolEntity> byKey = new HashMap<>();
    List<EndpointSnapshot.CorePair> core = new ArrayList<>();
    for (EndpointToolEntity t : tools.findByEndpointId(e.getId())) {
      if (blocked == null && !rows.verify(t)) {
        blocked = "tool " + t.getToolKey();
      }
      if (t.getConnectionId() == null) {
        if (CoreTools.WITH_MODE.contains(t.getToolKey())) {
          Mode mode = readOnly ? ToolPairs.clampReadOnly(CoreTools.withMode(t.getToolKey()), t.getMode())
              : t.getMode();
          core.add(new EndpointSnapshot.CorePair(t.getId(), t.getVersion(), t.getToolKey(), mode, t.getMode()));
        }
      } else {
        byKey.put(t.getConnectionId() + "\0" + t.getToolKey(), t);
      }
    }
    List<EndpointSnapshot.Wired> wired = new ArrayList<>();
    List<ToolPairs.Member> members = new ArrayList<>();
    for (EndpointConnectionEntity w : wiring.findByEndpointIdOrderByAddedAtAscIdAsc(e.getId())) {
      if (blocked == null && !rows.verify(w)) {
        blocked = "connection wiring";
      }
      Optional<ConnectionConfigs.Stored> stored = connections.byId(w.getConnectionId());
      if (stored.isEmpty()) {
        continue;
      }
      ConnectorType type = registry.type(stored.get().config().type()).orElse(null);
      if (type == null) {
        continue;
      }
      ConnectorManifest manifest = type.manifest();
      String problem = !stored.get().verified() ? stored.get().name() + " was changed outside GateRook"
          : !type.usable() ? manifest.name() + " is switched off" : null;
      wired.add(new EndpointSnapshot.Wired(stored.get().config(), manifest, problem));
      members.add(new ToolPairs.Member(stored.get().config(), manifest, problem == null));
    }
    // One EndpointView per connector type: same-named pairs of one type carry one definition (report 06 §3.1).
    Map<String, List<ToolPairs.Offered>> offeredById = pairs.offered(members, e.getSlug(), e.getEnv());
    List<EndpointSnapshot.Pair> out = new ArrayList<>();
    for (EndpointSnapshot.Wired w : wired) {
      ConnectorManifest manifest = w.manifest();
      String problem = w.problem();
      String connectionId = w.config().id();
      List<JsonNode> contributions = pairs.contributions(connectionId);
      for (ToolPairs.Offered offered : offeredById.get(connectionId)) {
        EndpointToolEntity row = byKey.get(connectionId + "\0" + offered.toolKey());
        Mode storedMode = row == null ? Mode.OFF : row.getMode();
        boolean confirmed = row != null && confirmed(row.getRoConfirmation(), offered);
        Mode mode = storedMode;
        if (mode == Mode.READ_ONLY && offered.needsReadOnlyConfirmation() && !confirmed) {
          mode = Mode.APPROVAL;
        }
        if (mode == Mode.READ_ONLY && !offered.definition().readOnlyModeAllowed()) {
          mode = Mode.APPROVAL;
        }
        if (readOnly) {
          mode = ToolPairs.clampReadOnly(offered.definition(), mode);
        }
        if (problem != null) {
          mode = Mode.OFF;
        }
        JsonNode overrides = row == null ? null : rows.tree(row.getOverrides());
        ToolCall.TemplateBinding binding = offered.template() == null ? null : new ToolCall.TemplateBinding(
            offered.template().templateId(), offered.template().version(), offered.compiled().opKey(),
            offered.compiled().binding());
        PairView view = new PairView(e.getId(), e.getSlug(), e.getEnv(), readOnly, connectionId,
            w.config().name(), w.config().env(), manifest, offered.definition(), offered.toolKey(), mode,
            rows.tree(e.getSettings()).path("limits"), overrides);
        out.add(new EndpointSnapshot.Pair(row == null ? null : row.getId(), row == null ? 0 : row.getVersion(),
            offered.toolKey(), offered.definition(), manifest, w.config(), mode, storedMode, overrides,
            offered.declaredBy(), confirmed, offered.provenanceVersion(), binding, view, contributions));
      }
    }
    if (blocked != null) {
      alerts.report("endpoint", e.getId(), e.getSlug(), blocked);
    }
    return new EndpointSnapshot(e.getId(), e.getSlug(), e.getDescription(), e.getEnv(), readOnly, e.isEnabled(),
        e.getInstructions(), rows.tree(e.getSettings()), e.isPublished(), e.isOauthEnabled(),
        e.getRiskDismissed() == null ? null : rows.tree(e.getRiskDismissed()), e.getVersion(), List.copyOf(wired),
        List.copyOf(out), List.copyOf(core), blocked == null ? null : "Changed outside GateRook (" + blocked + ")");
  }

  /**
   * A Read only confirmation counts while it names the tool's current definition and the version of what declared it
   * ({@link ToolPairs.Offered#provenanceVersion}: the plugin's, the upstream MCP server's), report 11: a new server
   * version takes the confirmation away, a new gateway version doesn't.
   */
  static boolean confirmed(Map<String, Object> confirmation, ToolPairs.Offered offered) {
    if (confirmation == null || offered.provenanceVersion() == null) {
      return false;
    }
    return ToolPairs.definitionHash(offered.definition()).equals(confirmation.get("definitionHash"))
        && offered.provenanceVersion().equals(confirmation.get("version"));
  }
}
