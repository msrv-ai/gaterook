package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionChanged;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.ToolPairs;
import ai.msrv.gaterook.core.endpoints.internal.Wiring;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointAccess;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.TokenKind;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.templates.TemplateChanged;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The connections of an endpoint (ARCHITECTURE.md §6 connection-new step 4, endpoint-detail Connections): what adding
 * one would do ({@code preview}: modes, locks, warnings, refusals), adding and removing it. Also keeps the pairs in step
 * with templates: a new template's tools start with their defaults, tools a template gains later start Off, a tool that
 * starts changing data leaves Read only for Approval.
 */
@Service
public class EndpointConnections {

  /** The mode a tool would get, and why it can't be another one. */
  public record PreviewTool(String toolKey, String tool, String mode, String locked, String declaredBy) {}

  /**
   * {@code POST /api/endpoints/{slug}/connections/preview}.
   *
   * @param refusal why the connection can't be added ("Accepts prod connections only"), or {@code null}
   */
  public record Preview(String connection, Env env, List<PreviewTool> tools, List<String> warnings, String refusal) {}

  private final EndpointRepository endpoints;
  private final EndpointConnectionRepository wiringRows;
  private final EndpointToolRepository tools;
  private final ApiTokenRepository apiTokens;
  private final ClientGrantRepository grants;
  private final EndpointRows rows;
  private final Wiring wiring;
  private final ToolPairs pairs;
  private final ConnectionConfigs connections;
  private final ConnectorRegistry registry;
  private final EndpointSnapshotService snapshots;
  private final RiskAnalyzer risks;
  private final ObjectProvider<EndpointRemovalParticipant> participants;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ApplicationEventPublisher publisher;
  private final IntegrityAlerts alerts;
  private final Clock clock;
  private final TransactionTemplate writeTx;

  public EndpointConnections(EndpointRepository endpoints, EndpointConnectionRepository wiringRows,
      EndpointToolRepository tools, ApiTokenRepository apiTokens, ClientGrantRepository grants, EndpointRows rows,
      Wiring wiring, ToolPairs pairs, ConnectionConfigs connections,
      ConnectorRegistry registry, EndpointSnapshotService snapshots, RiskAnalyzer risks,
      ObjectProvider<EndpointRemovalParticipant> participants, ObjectProvider<SetupChangeRecorder> setupChanges,
      ApplicationEventPublisher publisher, IntegrityAlerts alerts, Clock clock,
      PlatformTransactionManager transactions) {
    this.endpoints = endpoints;
    this.wiringRows = wiringRows;
    this.tools = tools;
    this.apiTokens = apiTokens;
    this.grants = grants;
    this.rows = rows;
    this.wiring = wiring;
    this.pairs = pairs;
    this.connections = connections;
    this.registry = registry;
    this.snapshots = snapshots;
    this.risks = risks;
    this.participants = participants;
    this.setupChanges = setupChanges;
    this.publisher = publisher;
    this.alerts = alerts;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
  }

  public Preview preview(String slug, String connectionName) {
    EndpointSnapshot snapshot = snapshots.bySlug(slug).orElseThrow(() -> EndpointService.notFound(slug));
    ConnectionConfigs.Stored c = connection(connectionName);
    ConnectorType type = registry.type(c.config().type()).orElseThrow();
    Optional<String> refusal = refusal(snapshot, c, type);
    Env env = snapshot.connections().isEmpty() ? c.config().env() : snapshot.env();
    List<ToolPairs.Offered> offeredHere = offeredJoining(snapshot, c, type, env);
    List<PreviewTool> out = new ArrayList<>();
    for (ToolPairs.Offered offered : offeredHere) {
      Mode mode = ToolPairs.initial(offered, env, snapshot.readOnly());
      String locked = null;
      if (snapshot.readOnly() && mode == Mode.OFF
          && ToolPairs.clampReadOnly(offered.definition(), Mode.APPROVAL) == Mode.OFF
          && offered.definition().mutating()) {
        locked = "Locked by the read-only endpoint";
      } else if (!offered.definition().readOnlyModeAllowed()) {
        locked = "Read only isn't available: " + offered.definition().name() + " changes data";
      }
      out.add(new PreviewTool(offered.toolKey(), offered.definition().name(), ToolPairs.label(mode), locked,
          offered.declaredBy()));
    }
    List<String> warnings = new ArrayList<>();
    if (snapshot.published() && c.config().env() == Env.DEV) {
      warnings.add("Dev data on a public endpoint: " + slug + " is published through the tunnel");
    }
    if (!"present".equals(risks.analyze(snapshot).underlying())) {
      List<EndpointSnapshot.Pair> after = new ArrayList<>(snapshot.onPairs());
      // The pairs the connection would bring, with the modes they would start in.
      for (ToolPairs.Offered offered : offeredHere) {
        Mode mode = ToolPairs.initial(offered, env, snapshot.readOnly());
        if (mode != Mode.OFF) {
          after.add(new EndpointSnapshot.Pair(null, 0, offered.toolKey(), offered.definition(), type.manifest(),
              c.config(), mode, mode, null, offered.declaredBy(), false, offered.provenanceVersion(), null, null,
              pairs.contributions(c.id())));
        }
      }
      if ("present".equals(risks.analyze(after, snapshot.published(), null).underlying())) {
        warnings.add("Adding " + connectionName + " completes a risky combination on " + slug
            + ": private data, untrusted content and an external channel");
      }
    }
    return new Preview(connectionName, c.config().env(), List.copyOf(out), List.copyOf(warnings),
        refusal.orElse(null));
  }

  /** {@code POST /api/endpoints/{slug}/connections {connection}}: the tools start in their default modes. */
  public EndpointSnapshot add(String slug, String connectionName, String reason) {
    String id = writeTx.execute(s -> {
      // The prod line is checked on what this transaction reads: the single writer serialises it with a concurrent
      // env change of the connection or another connection joining the endpoint, which a cached view would miss.
      ConnectionConfigs.Stored c = connection(connectionName);
      EndpointEntity e = endpoints.findBySlug(slug).orElseThrow(() -> EndpointService.notFound(slug));
      requireVerified(e);
      EndpointSnapshot snapshot = snapshots.uncached(e.getId()).orElseThrow();
      ConnectorType type = registry.type(c.config().type()).orElseThrow();
      refusal(snapshot, c, type).ifPresent(r -> {
        throw ProblemException.unprocessable("Can't add " + connectionName, r, "/connection");
      });
      Env env = snapshot.connections().isEmpty() ? c.config().env() : e.getEnv();
      e.setEnv(env);
      boolean readOnly = e.getAccess() == EndpointAccess.READ_ONLY;
      List<Wiring.Written> written = wiring.wire(e, c, type.manifest(), o -> ToolPairs.initial(o, env, readOnly));
      e.setEnv(wiring.deriveEnv(e.getId(), env));
      e.touch(clock.instant());
      rows.save(e);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          connectionName + " added to " + slug, null, summary(written), reason, e.getEnv(), slug, connectionName,
          null));
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
      return e.getId();
    });
    return snapshots.get(id).orElseThrow();
  }

  /** {@code DELETE /api/endpoints/{slug}/connections/{name}}: its tools stop; its pending requests there end. */
  public List<String> remove(String slug, String connectionName, String reason) {
    ConnectionConfigs.Stored c = connection(connectionName);
    return writeTx.execute(s -> {
      EndpointEntity e = endpoints.findBySlug(slug).orElseThrow(() -> EndpointService.notFound(slug));
      // The endpoint row is signed again below: an edit made outside the core must not ride along.
      requireVerified(e);
      if (wiringRows.findByEndpointIdAndConnectionId(e.getId(), c.id()).isEmpty()) {
        throw ProblemException.notFound("Not on this endpoint", connectionName + " is not a connection of " + slug
            + ".");
      }
      for (EndpointRemovalParticipant p : participants.orderedStream().toList()) {
        p.onConnectionUnwiring(e.getId(), slug, c.id(), connectionName, reason);
      }
      List<String> stopped = wiring.unwire(e, c.id());
      e.setEnv(wiring.deriveEnv(e.getId(), e.getEnv()));
      e.touch(clock.instant());
      rows.save(e);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.CONNECTION,
          connectionName + " removed from " + slug, String.join(", ", stopped), null, reason, e.getEnv(), slug,
          connectionName, null));
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
      return stopped;
    });
  }

  // ── Keeping pairs in step ───────────────────────────────────────────────────────────────────────────────────

  /** A template changed: every endpoint that wires its connection reconciles that connection's pairs. */
  @Async
  @TransactionalEventListener
  public void onTemplateChanged(TemplateChanged event) {
    if (event.connectionId() != null) {
      reconcile(event.connectionId(), "Template changed");
    }
  }

  /** A connection changed env (or its type's tools): endpoints derive their env again and reconcile its pairs. */
  @Async
  @TransactionalEventListener
  public void onConnectionChanged(ConnectionChanged event) {
    reconcile(event.connectionId(), null);
  }

  /**
   * Brings the pairs of {@code connectionId} on every endpoint in line with what it offers now: new tools get a row
   * (Off, or the defaults while the first version of a template is new), tools that went away lose theirs, a tool
   * that changes data now leaves Read only for Approval. Each endpoint's env is derived again.
   */
  public void reconcile(String connectionId, String reason) {
    ConnectionConfigs.Stored c = connections.byId(connectionId).orElse(null);
    if (c == null || !c.verified()) {
      return;
    }
    ConnectorType type = registry.type(c.config().type()).orElse(null);
    if (type == null) {
      return;
    }
    // An MCP server's tools are its own: no other connection shares its view.
    boolean sharesViews = type.manifest().kind() != ConnectorManifest.Kind.MCP_SERVER;
    writeTx.executeWithoutResult(s -> {
      for (EndpointConnectionEntity w : wiringRows.findByConnectionId(connectionId)) {
        EndpointEntity e = w.getEndpoint();
        if (!rows.verify(e)) {
          alerts.report("endpoint", e.getId(), e.getSlug(), "endpoint row");
          continue;
        }
        // The endpoint's connections of this type share one EndpointView: what changed here (a runbook, the env) may
        // change the tools of each of them, so all of them are brought in line.
        List<ToolPairs.Member> members = wiring.members(e.getId());
        Map<String, List<ToolPairs.Offered>> offered = pairs.offered(members, e.getSlug(), e.getEnv());
        boolean changed = false;
        for (ToolPairs.Member m : members) {
          boolean sameView = m.config().id().equals(connectionId) || (m.usable() && sharesViews
              && m.config().type().equals(c.config().type()));
          if (sameView) {
            changed |= reconcileOne(e, m.config(), offered.get(m.config().id()));
          }
        }
        Env env = wiring.deriveEnv(e.getId(), e.getEnv());
        if (env != e.getEnv()) {
          e.setEnv(env);
          changed = true;
        }
        if (changed) {
          e.touch(clock.instant());
          rows.save(e);
          publisher.publishEvent(new EndpointChanged(e.getId(), e.getSlug()));
        }
      }
    });
  }

  private boolean reconcileOne(EndpointEntity e, ConnectionConfig c, List<ToolPairs.Offered> offeredHere) {
    Map<String, EndpointToolEntity> stored = new HashMap<>();
    for (EndpointToolEntity t : tools.findByEndpointIdAndConnectionId(e.getId(), c.id())) {
      stored.put(t.getToolKey(), t);
    }
    boolean readOnly = e.getAccess() == EndpointAccess.READ_ONLY;
    boolean changed = false;
    java.util.Set<String> offeredKeys = new java.util.HashSet<>();
    for (ToolPairs.Offered offered : offeredHere) {
      offeredKeys.add(offered.toolKey());
      EndpointToolEntity row = stored.get(offered.toolKey());
      ToolDefinition d = offered.definition();
      if (row == null) {
        boolean freshTemplate = offered.template() != null && offered.template().version() == 1;
        Mode mode = freshTemplate ? ToolPairs.initial(offered, e.getEnv(), readOnly) : Mode.OFF;
        rows.save(new EndpointToolEntity(e, c.id(), offered.toolKey(), mode, clock.instant()));
        changed = true;
      } else if (row.getMode() == Mode.READ_ONLY && !d.readOnlyModeAllowed()) {
        if (!rows.verify(row)) {
          // Never signed again unchecked: the endpoint stays off until the person reviews it.
          alerts.report("endpoint", e.getId(), e.getSlug(), "tool " + row.getToolKey());
          continue;
        }
        // Approval where it can wait for a person; a read-only endpoint holds what changes data Off (as setMode and
        // the snapshot do), and the row and the Setup change say what the endpoint really does.
        Mode next = readOnly ? ToolPairs.clampReadOnly(d, Mode.APPROVAL) : Mode.APPROVAL;
        row.setMode(next, clock.instant());
        rows.save(row);
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MODE,
            d.name() + " on " + c.name() + ", endpoint " + e.getSlug(), "Read only", ToolPairs.label(next),
            d.name() + " now changes data" + (readOnly ? " and " + e.getSlug() + " is read only" : ""), e.getEnv(),
            e.getSlug(), c.name(), null));
        changed = true;
      }
    }
    for (EndpointToolEntity row : stored.values()) {
      if (!offeredKeys.contains(row.getToolKey())) {
        tools.delete(row);
        changed = true;
      }
    }
    tools.flush();
    return changed;
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private Optional<String> refusal(EndpointSnapshot snapshot, ConnectionConfigs.Stored c, ConnectorType type) {
    if (!c.verified()) {
      return Optional.of(c.name() + " was changed outside GateRook. Review it first.");
    }
    if (!type.usable()) {
      return Optional.of(type.manifest().name() + " is switched off");
    }
    if (snapshot.connection(c.name()).isPresent()) {
      return Optional.of(c.name() + " is already on " + snapshot.slug());
    }
    Optional<String> mixing = Wiring.mixing(snapshot.env(), !snapshot.connections().isEmpty(), c.config().env(),
        snapshot.slug(), c.name());
    return mixing.isPresent() ? mixing : prodLine(snapshot, c);
  }

  /**
   * The prod line where {@link Wiring#mixing} can't see it (ARCHITECTURE.md §9 "Stricter production rules", A6). A prod connection
   * never goes on a published endpoint: a prod endpoint is never published. And an endpoint without connections takes
   * the env of the first one, so it would cross the prod line with whatever access was made under the other side's
   * rules (named tokens, a Cursor config token, grants of bridges and helpers, OAuth grants): the same hazard as a
   * wired connection changing env ({@code EndpointUsage.envChangeProblem}), refused until that access is revoked.
   */
  private Optional<String> prodLine(EndpointSnapshot snapshot, ConnectionConfigs.Stored c) {
    boolean prodConnection = c.config().env() == Env.PROD;
    if (prodConnection && snapshot.published()) {
      return Optional.of(snapshot.slug() + " is published through the tunnel, and a prod endpoint never is. "
          + "Unpublish " + snapshot.slug() + " first, or put " + c.name() + " on a prod endpoint.");
    }
    if (!snapshot.connections().isEmpty() || (snapshot.env() == Env.PROD) == prodConnection) {
      return Optional.empty();
    }
    List<String> access = liveAccess(snapshot.id());
    if (access.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(snapshot.slug() + " is a " + snapshot.env().id() + " endpoint with " + String.join(", ", access)
        + ": " + c.name() + " would turn it into a " + c.config().env().id() + " endpoint that keeps them. Revoke "
        + "them first, or create a " + (prodConnection ? "prod" : "dev or stage") + " endpoint for " + c.name() + ".");
  }

  /** What still gives access to an endpoint ("2 live tokens", "1 grant"), as the refusal names it. */
  private List<String> liveAccess(String endpointId) {
    java.time.Instant now = clock.instant();
    List<String> out = new ArrayList<>();
    long named = apiTokens.findByEndpointIdAndKindOrderByCreatedAtDesc(endpointId, TokenKind.NAMED).stream()
        .filter(t -> t.isLive(now)).count();
    if (named > 0) {
      out.add(named + (named == 1 ? " live token" : " live tokens"));
    }
    long unrevoked = grants.findByEndpointId(endpointId).stream().filter(g -> !g.isRevoked()).count();
    if (unrevoked > 0) {
      out.add(unrevoked + (unrevoked == 1 ? " client grant" : " client grants"));
    }
    for (EndpointRemovalParticipant p : participants.orderedStream().toList()) {
      out.addAll(p.liveAccess(endpointId));
    }
    return out;
  }

  /** What {@code c} would offer on the endpoint, asked together with the endpoint's connections of its type. */
  private List<ToolPairs.Offered> offeredJoining(EndpointSnapshot snapshot, ConnectionConfigs.Stored c,
      ConnectorType type, Env env) {
    List<ToolPairs.Member> members = new ArrayList<>();
    for (EndpointSnapshot.Wired w : snapshot.connections()) {
      if (!w.config().id().equals(c.id())) {
        members.add(new ToolPairs.Member(w.config(), w.manifest(), w.usable()));
      }
    }
    members.add(new ToolPairs.Member(c.config(), type.manifest(), c.verified() && type.usable()));
    return pairs.offeredFor(c.config(), members, snapshot.slug(), env);
  }

  private ConnectionConfigs.Stored connection(String name) {
    return connections.byName(name).orElseThrow(() -> ProblemException.unprocessable("Connection not found",
        "There is no connection " + name + ".", "/connection"));
  }

  private void requireVerified(EndpointEntity e) {
    if (!rows.verify(e)) {
      alerts.report("endpoint", e.getId(), e.getSlug(), "endpoint row");
      throw ProblemException.conflict("Changed outside GateRook", e.getSlug() + " was changed outside "
          + "GateRook. Review it first.");
    }
  }

  private static String summary(List<Wiring.Written> written) {
    int read = 0;
    int approval = 0;
    int off = 0;
    for (Wiring.Written w : written) {
      switch (w.mode()) {
        case READ_ONLY -> read++;
        case APPROVAL -> approval++;
        case OFF -> off++;
      }
    }
    return read + " read only · " + approval + " approval · " + off + " off";
  }
}
