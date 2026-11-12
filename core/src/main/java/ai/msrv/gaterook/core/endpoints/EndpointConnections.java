package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionChanged;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.connectors.ConnectorTypeChanged;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.TemporaryBypass;
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
import ai.msrv.gaterook.core.platform.StartupFacts;
import ai.msrv.gaterook.core.templates.TemplateChanged;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
 * starts changing data leaves Read only for Approval. An external MCP server's tools are its own: one whose definition
 * changed leaves Read only and Bypass for Approval, and the row of one it stops listing stays for when it comes back
 * (ARCHITECTURE.md §7.5).
 */
@Service
public class EndpointConnections implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(EndpointConnections.class);

  /**
   * How many rows of one external MCP server's tools on an endpoint, listed and unlisted together, a reconcile stops
   * creating rows at: a new tool it finds then gets no row, which is Off until a person sets a mode. The server chooses
   * the names it lists, and what a person decided for a name is never removed to make room. It limits only what a
   * reconcile creates: adding the server to an endpoint writes a row for every tool it lists then, and a person setting
   * the mode of a tool without a row writes that tool's row.
   */
  static final int MAX_SERVER_ROWS = 2_000;

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
  private final StartupFacts facts;
  private final Clock clock;
  private final TransactionTemplate writeTx;

  public EndpointConnections(EndpointRepository endpoints, EndpointConnectionRepository wiringRows,
      EndpointToolRepository tools, ApiTokenRepository apiTokens, ClientGrantRepository grants, EndpointRows rows,
      Wiring wiring, ToolPairs pairs, ConnectionConfigs connections,
      ConnectorRegistry registry, EndpointSnapshotService snapshots, RiskAnalyzer risks,
      ObjectProvider<EndpointRemovalParticipant> participants, ObjectProvider<SetupChangeRecorder> setupChanges,
      ApplicationEventPublisher publisher, IntegrityAlerts alerts, StartupFacts facts, Clock clock,
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
    this.facts = facts;
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
              pairs.contributions(c.id()), null));
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
   * A connector type changed (a plugin update, a gateway build that changes a built-in's tools, the type switched on
   * or off): every connection of the type is reconciled, so a tool it starts offering joins the endpoints of those
   * connections in its default mode instead of staying out of them.
   */
  @Async
  @TransactionalEventListener
  public void onConnectorTypeChanged(ConnectorTypeChanged event) {
    for (ConnectionConfigs.Stored c : connections.all()) {
      if (c.config().type().equals(event.typeId())) {
        reconcile(c.config().id(), null);
      }
    }
  }

  /**
   * Brings the pairs of {@code connectionId} on every endpoint in line with what it offers now: new tools get a row
   * in their default mode ({@link ToolPairs#initial}: reads in Read only, the rest in Approval), tools that went away
   * lose theirs, a tool that changes data now leaves Read only for Approval. Each endpoint's env is derived again.
   */
  public void reconcile(String connectionId, String reason) {
    ConnectionConfigs.Stored c = connections.byId(connectionId).orElse(null);
    if (c == null || !c.verified() || ConnectionService.needsSetup(c.config())) {
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
            changed |= reconcileOne(e, m.config(), offered.get(m.config().id()),
                m.manifest().kind() == ConnectorManifest.Kind.MCP_SERVER);
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

  /**
   * @param external whether the connection is an external MCP server: its tools keep their rows when it stops listing
   *                 them, and what runs without a person only holds for the definition it was set for
   */
  private boolean reconcileOne(EndpointEntity e, ConnectionConfig c, List<ToolPairs.Offered> offeredHere,
      boolean external) {
    Map<String, EndpointToolEntity> stored = new HashMap<>();
    for (EndpointToolEntity t : tools.findByEndpointIdAndConnectionId(e.getId(), c.id())) {
      stored.put(t.getToolKey(), t);
    }
    boolean readOnly = e.getAccess() == EndpointAccess.READ_ONLY;
    boolean changed = false;
    int rowCount = stored.size();
    Set<String> offeredKeys = new HashSet<>();
    for (ToolPairs.Offered offered : offeredHere) {
      offeredKeys.add(offered.toolKey());
      EndpointToolEntity row = stored.get(offered.toolKey());
      if (row == null) {
        if (external && rowCount >= MAX_SERVER_ROWS) {
          continue;
        }
        Mode mode = ToolPairs.initial(offered, e.getEnv(), readOnly);
        EndpointToolEntity created = new EndpointToolEntity(e, c.id(), offered.toolKey(), mode, clock.instant());
        created.setReviewedDefinition(ToolPairs.reviewedDefinition(offered), clock.instant());
        rows.save(created);
        rowCount++;
        changed = true;
      } else {
        changed |= review(e, c, row, offered, readOnly);
      }
    }
    if (!external) {
      for (EndpointToolEntity row : stored.values()) {
        if (!offeredKeys.contains(row.getToolKey())) {
          tools.delete(row);
          changed = true;
        }
      }
    }
    tools.flush();
    return changed;
  }

  /**
   * What a row keeps of its mode once its tool is offered as it is now. Read only goes when the tool now changes data.
   * For an external MCP server's tool, Read only and Bypass go too when its definition isn't the one they were set for
   * (the server changed it, or nothing is recorded): the tool needs a person again, and the person can set the mode
   * again for what it is now (ARCHITECTURE.md §7.5). What falls goes to Approval, or Off where the endpoint is read
   * only and can't wait for a person (as {@code setMode} and the snapshot hold it); the row and the Setup change say
   * what the endpoint really does.
   *
   * <p>A temporary bypass is judged by its own binding, the definition it was granted for
   * ({@link TemporaryBypass#activeUntil}), not by the row's reviewed definition: a person may grant one to a tool held
   * in Approval whose stored mode is Read only (a stored Bypass is ineligible, ARCHITECTURE.md §5.1), and whose row
   * keeps the definition of an earlier mode or none. The grant of an external server's tool is
   * dropped when the tool's definition is no longer the one it was granted for. A fall of the mode drops any grant too:
   * the reconcile that writes the fall saves the endpoint, and a grant is bound to the endpoint's version, so it would
   * stay in the row without ever applying again. The Setup change says whether the grant was still in force or had
   * already stopped applying (its deadline passed, or the server's tool list, the connection or the endpoint changed
   * since it was granted).
   *
   * @return whether the row changed
   */
  private boolean review(EndpointEntity e, ConnectionConfig c, EndpointToolEntity row, ToolPairs.Offered offered,
      boolean readOnly) {
    ToolDefinition d = offered.definition();
    Mode before = row.getMode();
    boolean external = offered.kind() == ConnectorManifest.Kind.MCP_SERVER;
    boolean unattended = before == Mode.READ_ONLY || before == Mode.BYPASS;
    boolean changesData = before == Mode.READ_ONLY && !d.readOnlyModeAllowed();
    boolean redefined = unattended && !ToolPairs.reviewed(row, offered);
    boolean falls = changesData || redefined;
    Map<String, Object> grant = row.getTemporaryBypass();
    boolean grantEnds = external && grant != null
        && !ToolPairs.definitionHash(d).equals(grant.get("definitionHash"));
    if (!falls && !grantEnds) {
      return false;
    }
    if (!rows.verify(row)) {
      // Never signed again unchecked: the endpoint stays off until the person reviews it.
      alerts.report("endpoint", e.getId(), e.getSlug(), "tool " + row.getToolKey());
      return false;
    }
    Mode next = falls ? ToolPairs.demoted(d, readOnly) : before;
    // Whether the grant applies to the tool as it is offered now, before this reconcile saves anything: one that had
    // stopped, or was granted for another definition, is not shown as in force.
    boolean inForce = grant != null
        && TemporaryBypass.activeUntil(row, e, c, offered, readOnly, clock.instant()) != null;
    boolean recorded = row.getReviewedDefinition() != null;
    if (falls) {
      // Clears the grant as well (see above).
      row.setMode(next, clock.instant());
    } else {
      row.setTemporaryBypass(null, clock.instant());
    }
    rows.save(row);
    String why;
    if (changesData) {
      why = d.name() + " now changes data" + (readOnly ? " and " + e.getSlug() + " is read only" : "");
    } else if (falls) {
      why = recorded ? "The definition of " + d.name() + " changed since its mode was set"
          : "No definition of " + d.name() + " is recorded for the mode it was set to";
    } else {
      why = "The definition of " + d.name() + " changed since its temporary bypass was granted";
    }
    if (falls && grant != null) {
      why += grantEnds ? ", and its temporary bypass was granted for an earlier definition"
          : inForce ? ", and its temporary bypass ended with the mode change"
          : ", and its temporary bypass had already stopped applying";
    }
    String from = grant == null ? ToolPairs.label(before)
        : ToolPairs.label(before) + ", temporary bypass until " + grant.get("until")
            + (inForce ? "" : ", no longer in force");
    setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MODE,
        d.name() + " on " + c.name() + ", endpoint " + e.getSlug(), from, ToolPairs.label(next), why, e.getEnv(),
        e.getSlug(), c.name(), null));
    return true;
  }

  // ── The upgrade that records definitions ────────────────────────────────────────────────────────────────────

  /**
   * The start that applies V89 records, for the tools of external MCP servers that already have a row, the
   * definitions the endpoints publish for them now, so that the Read only and Bypass modes people set before it keep
   * working until a server changes a definition. Rows written before V89 are trusted as they are (the same stance as
   * {@code LegacyRowSigning}); it runs before any server starts, so nothing a server lists after the upgrade is
   * recorded as reviewed. A start that can't verify the rows (the master key stays locked) records nothing, and those
   * tools are in Approval until a person sets them again.
   *
   * <p>Every start with rows that verify then stores what the snapshot already holds for a row that still has no
   * definition ({@link #holdUnrecorded}), so that the stored mode, the tool list and a Setup change say why.
   */
  @Override
  public void afterSingletonsInstantiated() {
    if (facts.firstRun()) {
      return;
    }
    if (facts.migratedNow("89")) {
      try {
        recordReviewedDefinitions();
      } catch (RuntimeException ex) {
        log.warn("The definitions of external MCP server tools weren't recorded ({}): their Read only and Bypass "
            + "modes stay in Approval until they are set again", ex.toString());
      }
    }
    try {
      holdUnrecorded();
    } catch (RuntimeException ex) {
      log.warn("The modes of external MCP server tools without a recorded definition weren't stored as Approval ({}): "
          + "they stay in Approval all the same", ex.toString());
    }
  }

  /**
   * The rows of external MCP server tools that keep a stored Read only or Bypass without any recorded definition go to
   * Approval (Off on a read-only endpoint), with one Setup change for all of them. Such a row runs in Approval already
   * (the snapshot holds it, ARCHITECTURE.md §7.5), and nothing would record a definition for it later, so its stored
   * mode only misleads. It comes from a V89 start that couldn't verify the rows (the master key was locked), from a
   * server that wasn't usable at that start (its connector type switched off or changed outside GateRook, its
   * connection changed outside GateRook or still waiting for setup) or a tool it didn't list then, from a reset of a
   * definition changed outside GateRook, from a mode set without the definition it is for (an API request without
   * {@code definitionHash} for a tool whose row had no record, such as one beyond {@link #MAX_SERVER_ROWS}), or from
   * a duplicate of such a row. Rows that don't verify are left alone (a locked start changes nothing), and so is a
   * temporary bypass, which has its own binding ({@link TemporaryBypass}). Nothing that is enforced changes and
   * nothing is granted. Built-in, template and plugin tools record no definition by design and are never
   * considered.
   *
   * @return how many rows went to Approval or Off
   */
  int holdUnrecorded() {
    Integer held = writeTx.execute(s -> {
      int count = 0;
      int off = 0;
      for (EndpointEntity e : endpoints.findAll()) {
        Mode next = e.getAccess() == EndpointAccess.READ_ONLY ? Mode.OFF : Mode.APPROVAL;
        int before = count;
        for (ToolPairs.Member m : wiring.members(e.getId())) {
          if (m.manifest().kind() != ConnectorManifest.Kind.MCP_SERVER) {
            continue;
          }
          for (EndpointToolEntity row : tools.findByEndpointIdAndConnectionId(e.getId(), m.config().id())) {
            boolean unattended = row.getMode() == Mode.READ_ONLY || row.getMode() == Mode.BYPASS;
            if (!unattended || row.getReviewedDefinition() != null || !rows.verify(row)) {
              continue;
            }
            Map<String, Object> grant = row.getTemporaryBypass();
            row.setMode(next, clock.instant());
            if (grant != null && next != Mode.OFF) {
              row.setTemporaryBypass(grant, clock.instant());
            }
            rows.save(row);
            count++;
            off += next == Mode.OFF ? 1 : 0;
          }
        }
        if (count > before) {
          publisher.publishEvent(new EndpointChanged(e.getId(), e.getSlug()));
        }
      }
      if (count > 0) {
        List<String> to = new ArrayList<>();
        if (count > off) {
          to.add("Approval for " + serverTools(count - off));
        }
        if (off > 0) {
          to.add("Off for " + serverTools(off) + " on read-only endpoints");
        }
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MODE,
            "MCP server tools without a recorded definition", "Read only or Bypass", String.join(", ", to),
            "No definition is recorded for the mode they were set to: for example, GateRook upgraded its database "
                + "while the master key was locked or the server didn't list the tool, or the mode was set without "
                + "the definition it is for. Set the mode again to review the definition they have now.", null, null,
            null, null));
        log.info("{} MCP server tools without a recorded definition went to Approval", count);
      }
      tools.flush();
      return count;
    });
    return held == null ? 0 : held;
  }

  private static String serverTools(int count) {
    return count + (count == 1 ? " MCP server tool" : " MCP server tools");
  }

  /** @return how many rows got the definition their tool has now */
  int recordReviewedDefinitions() {
    Integer recorded = writeTx.execute(s -> {
      int count = 0;
      for (EndpointEntity e : endpoints.findAll()) {
        List<ToolPairs.Member> members = wiring.members(e.getId());
        Map<String, List<ToolPairs.Offered>> offered = pairs.offered(members, e.getSlug(), e.getEnv());
        int before = count;
        for (ToolPairs.Member m : members) {
          if (!m.usable() || m.manifest().kind() != ConnectorManifest.Kind.MCP_SERVER) {
            continue;
          }
          Map<String, ToolPairs.Offered> byKey = new HashMap<>();
          offered.get(m.config().id()).forEach(o -> byKey.put(o.toolKey(), o));
          for (EndpointToolEntity row : tools.findByEndpointIdAndConnectionId(e.getId(), m.config().id())) {
            ToolPairs.Offered o = byKey.get(row.getToolKey());
            if (o == null || row.getReviewedDefinition() != null || !rows.verify(row)) {
              continue;
            }
            row.setReviewedDefinition(ToolPairs.reviewedDefinition(o), clock.instant());
            rows.save(row);
            count++;
          }
        }
        if (count > before) {
          publisher.publishEvent(new EndpointChanged(e.getId(), e.getSlug()));
        }
      }
      if (count > 0) {
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
            "Rows from an older version", "no definition recorded", "recorded for " + count
                + (count == 1 ? " MCP server tool" : " MCP server tools"), "GateRook records the definitions "
                + "external MCP servers publish for the tools that already had a mode, once, when it upgrades the "
                + "database. From now on a tool whose definition changes goes back to Approval.", null, null, null,
            null));
        log.info("Recorded the definitions of {} MCP server tools as reviewed", count);
      }
      return count;
    });
    return recorded == null ? 0 : recorded;
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
    int bypass = 0;
    for (Wiring.Written w : written) {
      switch (w.mode()) {
        case READ_ONLY -> read++;
        case APPROVAL -> approval++;
        case OFF -> off++;
        case BYPASS -> bypass++;
      }
    }
    return read + " read only · " + approval + " approval · " + off + " off"
        + (bypass == 0 ? "" : " · " + bypass + " bypass");
  }
}
