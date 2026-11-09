package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.connector.api.ToolDefinition.Mode;
import ai.msrv.gaterook.core.connections.ConnectionConfigs;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.connectors.ConnectorType;
import ai.msrv.gaterook.core.endpoints.internal.EndpointRows;
import ai.msrv.gaterook.core.endpoints.internal.ToolPairs;
import ai.msrv.gaterook.core.endpoints.internal.Wiring;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ApiTokenRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.ClientGrantRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointAccess;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointConnectionRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolEntity;
import ai.msrv.gaterook.core.endpoints.internal.persistence.EndpointToolRepository;
import ai.msrv.gaterook.core.endpoints.internal.persistence.TokenKind;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.Caller;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.JsonMergePatch;
import ai.msrv.gaterook.core.platform.KebabCaseEnumConverter;
import ai.msrv.gaterook.core.platform.PrincipalRevoked;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.platform.SchemaValidator;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder;
import ai.msrv.gaterook.core.platform.SetupChangeRecorder.SetupChange;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Endpoints (ARCHITECTURE.md §2.2 {@code endpoints}, §6 endpoints and endpoint-detail): create from Empty, Read-only
 * dev, Read-only stage or Incident response; duplicate; disable and enable; settings (access, instructions ≤ 4,000,
 * stricter limits, protocol); dismiss the risk warning; split; delete. The endpoint's env is derived from its
 * connections and prod never mixes. A read-only endpoint keeps every pair that could change data Off. Every change
 * is signed and a Setup change.
 */
@Service
public class EndpointService {

  /** Slugs as {@code endpoint.slug} allows them, without leading or trailing hyphens. */
  public static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

  public static final int MAX_INSTRUCTIONS = 4_000;

  /**
   * The settings an endpoint stores besides its limits (report 01 §4): the revisions it serves (by era: the legacy
   * one also answers 2025-06-18 and 2025-03-26), how approvals reach the agent ({@code auto}: MRTR when the client
   * declares {@code elicitation.url}, else a handle), how long the first answer waits for a person. ARCHITECTURE.md
   * A3 fixes the rest of the wait (20 s per MRTR round, 45 s budget, 5 rounds), so it is no setting.
   */
  static final ObjectNode SETTINGS_SCHEMA = (ObjectNode) JsonMapper.builder().build()
      .readTree("""
          {"protocol":{"type":"object","additionalProperties":false,"required":["revisions","approvalDelivery"],
             "properties":{
               "revisions":{"type":"array","minItems":1,"uniqueItems":true,
                 "items":{"enum":["2026-07-28","2025-11-25"]}},
               "approvalDelivery":{"enum":["auto","handle-only"]}}},
           "approvalWait":{"type":"object","additionalProperties":false,"required":["firstWaitSeconds"],
             "properties":{"firstWaitSeconds":{"type":"integer","minimum":0,"maximum":25}}}}""");

  /** How a new endpoint starts (kit "Start from"). */
  public enum StartFrom {
    /** No connections, every tool off. */
    EMPTY(Env.DEV, EndpointAccess.STANDARD),
    /** Selected dev connections with their read-only tools on; the endpoint is read only. */
    READ_ONLY_DEV(Env.DEV, EndpointAccess.READ_ONLY),
    /** Selected stage connections with their read-only tools on; the endpoint is read only. */
    READ_ONLY_STAGE(Env.STAGE, EndpointAccess.READ_ONLY),
    /** Selected prod connections: reads on, tools that only change data off, runbooks through approval. */
    INCIDENT_RESPONSE(Env.PROD, EndpointAccess.STANDARD);

    private final Env env;
    private final EndpointAccess access;

    StartFrom(Env env, EndpointAccess access) {
      this.env = env;
      this.access = access;
    }

    public static StartFrom of(String id) {
      for (StartFrom s : values()) {
        if (KebabCaseEnumConverter.idOf(s).equals(id)) {
          return s;
        }
      }
      throw ProblemException.unprocessable("Unknown start",
          "Start from empty, read-only-dev, read-only-stage or incident-response.", "/startFrom");
    }
  }

  /** Tools of an endpoint by mode, as its list row counts them (core tools included). */
  public record ToolCounts(int read, int approval, int off, int bypass) {
    public ToolCounts(int read, int approval, int off) {
      this(read, approval, off, 0);
    }
  }

  /** One row of Endpoints. */
  public record Summary(String id, String slug, String description, String url, Env env, String access,
      String status, ToolCounts tools, int connections, Map<Env, Integer> connectionsByEnv,
      Map<String, Integer> health, int tokensActive, int tokensExpiringSoon, boolean published,
      RiskAnalyzer.Report risk, String blockedReason, long version) {}

  /** {@code PUT /api/endpoints/{slug}/settings}; a {@code null} field stays as it is. */
  public record SettingsUpdate(String description, String access, String instructions, JsonNode limits,
      JsonNode protocol, JsonNode approvalWait, Boolean oauthEnabled) {}

  /**
   * What a deletion disconnected, revoked and rejected; the audit stays.
   *
   * @param clients the clients disconnected, by name
   * @param notes   what is left for the person to do in a client, one sentence each
   */
  public record DeleteSummary(List<String> clients, List<String> tokensRevoked, int rejectedApprovals,
      List<String> notes) {}

  /** Before and after of a split. */
  public record SplitPreview(String newSlug, RiskAnalyzer.Report before, RiskAnalyzer.Report remaining,
      RiskAnalyzer.Report moved, List<String> movedTools) {}

  private final EndpointRepository endpoints;
  private final EndpointConnectionRepository wiringRows;
  private final EndpointToolRepository tools;
  private final ApiTokenRepository tokens;
  private final ClientGrantRepository grants;
  private final EndpointRows rows;
  private final Wiring wiring;
  private final ConnectionConfigs connections;
  private final ConnectorRegistry registry;
  private final ToolPairs pairs;
  private final EndpointSnapshotService snapshots;
  private final RiskAnalyzer risks;
  private final PolicyService policies;
  private final SchemaValidator validator;
  private final IntegrityAlerts alerts;
  private final ObjectProvider<EndpointRemovalParticipant> participants;
  private final ObjectProvider<SetupChangeRecorder> setupChanges;
  private final ApplicationEventPublisher publisher;
  private final Bootstrap bootstrap;
  private final Clock clock;
  private final TransactionTemplate writeTx;
  private final TransactionTemplate readTx;

  public EndpointService(EndpointRepository endpoints, EndpointConnectionRepository wiringRows,
      EndpointToolRepository tools, ApiTokenRepository tokens, ClientGrantRepository grants, EndpointRows rows,
      Wiring wiring, ConnectionConfigs connections, ConnectorRegistry registry, ToolPairs pairs,
      EndpointSnapshotService snapshots, RiskAnalyzer risks, PolicyService policies, SchemaValidator validator,
      IntegrityAlerts alerts, ObjectProvider<EndpointRemovalParticipant> participants,
      ObjectProvider<SetupChangeRecorder> setupChanges, ApplicationEventPublisher publisher, Bootstrap bootstrap,
      Clock clock, PlatformTransactionManager transactions) {
    this.endpoints = endpoints;
    this.wiringRows = wiringRows;
    this.tools = tools;
    this.tokens = tokens;
    this.grants = grants;
    this.rows = rows;
    this.wiring = wiring;
    this.connections = connections;
    this.registry = registry;
    this.pairs = pairs;
    this.snapshots = snapshots;
    this.risks = risks;
    this.policies = policies;
    this.validator = validator;
    this.alerts = alerts;
    this.participants = participants;
    this.setupChanges = setupChanges;
    this.publisher = publisher;
    this.bootstrap = bootstrap;
    this.clock = clock;
    this.writeTx = new TransactionTemplate(transactions);
    this.readTx = new TransactionTemplate(transactions);
    this.readTx.setReadOnly(true);
  }

  // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────────────

  public List<Summary> list() {
    List<String> ids = readTx.execute(s -> endpoints.findAllByOrderBySlugAsc().stream().map(EndpointEntity::getId)
        .toList());
    List<Summary> out = new ArrayList<>();
    for (String id : ids) {
      snapshots.get(id).map(this::summary).ifPresent(out::add);
    }
    return out;
  }

  public Summary get(String slug) {
    return summary(snapshot(slug));
  }

  public EndpointSnapshot snapshot(String slug) {
    return snapshots.bySlug(slug).orElseThrow(() -> notFound(slug));
  }

  /** Fresh state for a reviewed multi-endpoint write, also callable inside its transaction. */
  EndpointSnapshot uncachedSnapshot(String slug) {
    return snapshots.uncached(require(slug).getId()).orElseThrow(() -> notFound(slug));
  }

  public RiskAnalyzer.Report risk(String slug) {
    return risks.analyze(snapshot(slug));
  }

  /** {@code http://127.0.0.1:7465/mcp/<slug>}. */
  public String url(String slug) {
    return "http://127.0.0.1:" + bootstrap.port() + "/mcp/" + slug;
  }

  // ── Create, duplicate ───────────────────────────────────────────────────────────────────────────────────────

  /** Creates an endpoint without connections. A preset alone never grants access to existing connections. */
  public Summary create(String slug, String description, StartFrom startFrom, String reason) {
    return create(slug, description, startFrom, List.of(), reason);
  }

  /** Creates from the preset's default environment; only selected names are wired. */
  public Summary create(String slug, String description, StartFrom startFrom, List<String> selectedConnections,
      String reason) {
    return create(slug, description, startFrom, selectedConnections, null, reason);
  }

  /** An empty endpoint may start in a chosen environment; connections still determine its environment once wired. */
  public Summary create(String slug, String description, StartFrom startFrom, List<String> selectedConnections,
      Env initialEnv, String reason) {
    checkSlug(slug);
    checkDescription(description);
    Env presetEnv = startFrom.env;
    if (initialEnv != null && startFrom != StartFrom.EMPTY && initialEnv != presetEnv) {
      throw ProblemException.unprocessable("Environment does not match preset",
          "This preset uses " + presetEnv.id() + ". Start from empty to choose another environment.", "/env");
    }
    Env env = initialEnv == null ? presetEnv : initialEnv;
    String id = writeTx.execute(s -> {
      if (endpoints.existsBySlug(slug)) {
        throw ProblemException.conflict("Slug in use", "An endpoint /mcp/" + slug + " already exists.");
      }
      List<ConnectionConfigs.Stored> selected = selectedStartConnections(startFrom, selectedConnections);
      EndpointAccess access = startFrom.access;
      EndpointEntity e = new EndpointEntity(slug, env, access, rows.map(defaultSettings()), clock.instant());
      e.setDescription(description);
      e = rows.save(e);
      wiring.ensureCoreTools(e, startFrom == StartFrom.EMPTY ? Mode.OFF : Mode.READ_ONLY);
      boolean readOnly = access == EndpointAccess.READ_ONLY;
      for (ConnectionConfigs.Stored c : selected) {
        wiring.wire(e, c, registry.type(c.config().type()).orElseThrow().manifest(),
            offered -> startMode(startFrom, offered, readOnly));
      }
      e.setEnv(wiring.deriveEnv(e.getId(), env));
      e = rows.save(e);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Endpoint " + slug + " created", null, "Start from " + KebabCaseEnumConverter.idOf(startFrom), reason,
          e.getEnv(), slug, null, null));
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
      return e.getId();
    });
    return summary(snapshots.get(id).orElseThrow());
  }

  /**
   * What a start offers for selection: verified connections whose type is on — dev for Read-only dev, stage for
   * Read-only stage, prod for Incident response, none for Empty. External MCP servers join an endpoint only when the
   * person adds them (the kit's "6 dev connections" while github-mcp is installed): their tools all start in Approval,
   * which a read-only endpoint turns off, and an incident endpoint shouldn't call an outside server nobody chose for it.
   */
  private List<ConnectionConfigs.Stored> startConnections(StartFrom startFrom) {
    if (startFrom == StartFrom.EMPTY) {
      return List.of();
    }
    Env wanted = startFrom.env;
    List<ConnectionConfigs.Stored> out = new ArrayList<>();
    for (ConnectionConfigs.Stored c : connections.all()) {
      Optional<ConnectorType> type = registry.type(c.config().type());
      if (c.config().env() == wanted && c.verified() && type.isPresent() && type.get().usable()
          && type.get().manifest().kind() != ConnectorManifest.Kind.MCP_SERVER) {
        out.add(c);
      }
    }
    return out;
  }

  /** Rechecks the explicit selection at creation time, including connections changed since the preview. */
  private List<ConnectionConfigs.Stored> selectedStartConnections(StartFrom startFrom, List<String> selected) {
    if (selected == null || selected.isEmpty()) {
      return List.of();
    }
    Set<String> names = new HashSet<>(selected);
    if (names.contains(null) || names.size() != selected.size()) {
      throw ProblemException.unprocessable("Invalid connections", "Select each connection only once.", "/connections");
    }
    List<ConnectionConfigs.Stored> available = startConnections(startFrom).stream()
        .filter(c -> names.contains(c.name())).toList();
    if (available.size() != names.size()) {
      throw ProblemException.unprocessable("Connections unavailable",
          "Some selected connections are no longer available for this preset. Review the selection.", "/connections");
    }
    return available;
  }

  /** The mode a start gives an offered tool: the type's default, clamped on a read-only endpoint; incidents never write. */
  private static Mode startMode(StartFrom startFrom, ToolPairs.Offered offered, boolean readOnly) {
    Mode mode = ToolPairs.initial(offered, startFrom.env, readOnly);
    return startFrom == StartFrom.INCIDENT_RESPONSE
        && offered.definition().nature() == ToolDefinition.Nature.MUTATION ? Mode.OFF : mode;
  }

  /**
   * One "Start from" card of New endpoint: the eligible connections and tool counts if all were selected.
   *
   * @param connections the connections available for explicit selection, in wiring order
   * @param tools       pairs by mode with all candidates selected, the core tools included
   */
  public record StartPreview(String startFrom, Env env, String access, List<String> connections, ToolCounts tools) {}

  /**
   * Previews a preset with all eligible connections selected, without saving anything. Creation requires an
   * explicit subset of these names; new or unselected connections never join implicitly.
   */
  public StartPreview previewStart(StartFrom startFrom) {
    boolean readOnly = startFrom.access == EndpointAccess.READ_ONLY;
    Env env = startFrom.env;
    Mode core = startFrom == StartFrom.EMPTY ? Mode.OFF : Mode.READ_ONLY;
    int[] counts = new int[Mode.values().length];
    for (int i = 0; i < ai.msrv.gaterook.core.endpoints.CoreTools.WITH_MODE.size(); i++) {
      counts[core.ordinal()]++;
    }
    List<ConnectionConfigs.Stored> start = startConnections(startFrom);
    List<ToolPairs.Member> members = start.stream().map(c -> new ToolPairs.Member(c.config(),
        registry.type(c.config().type()).orElseThrow().manifest(), true)).toList();
    Map<String, List<ToolPairs.Offered>> offered = members.isEmpty() ? Map.of()
        : pairs.offered(members, "new-endpoint", env);
    for (List<ToolPairs.Offered> tools : offered.values()) {
      for (ToolPairs.Offered t : tools == null ? List.<ToolPairs.Offered>of() : tools) {
        counts[startMode(startFrom, t, readOnly).ordinal()]++;
      }
    }
    return new StartPreview(KebabCaseEnumConverter.idOf(startFrom), env, readOnly ? "read-only" : "standard",
        start.stream().map(ConnectionConfigs.Stored::name).toList(), new ToolCounts(counts[Mode.READ_ONLY.ordinal()],
            counts[Mode.APPROVAL.ordinal()], counts[Mode.OFF.ordinal()], counts[Mode.BYPASS.ordinal()]));
  }

  /**
   * {@code POST /api/endpoints/{slug}/duplicate {slug}}: connections, modes, limits and settings; no tokens, no
   * clients. Plugin tools in Read only return to Approval (their confirmations stay with the original); MCP
   * server tools retain their saved modes subject to the target endpoint's restrictions.
   */
  public Summary duplicate(String slug, String newSlug, String reason) {
    checkSlug(newSlug);
    String id = writeTx.execute(s -> {
      EndpointEntity source = require(slug);
      requireVerified(source);
      // The copy is signed afresh: every row it copies must verify now, or an edit made outside the core would come
      // out of the copy as the gateway's own.
      requireUnblocked(source);
      if (endpoints.existsBySlug(newSlug)) {
        throw ProblemException.conflict("Slug in use", "An endpoint /mcp/" + newSlug + " already exists.");
      }
      EndpointEntity copy = new EndpointEntity(newSlug, source.getEnv(), source.getAccess(),
          rows.map(rows.tree(source.getSettings())), clock.instant());
      copy.setDescription(source.getDescription());
      copy.setInstructions(source.getInstructions());
      copy = rows.save(copy);
      for (EndpointToolEntity t : tools.findByEndpointId(source.getId())) {
        if (t.getConnectionId() == null) {
          EndpointToolEntity core = new EndpointToolEntity(copy, null, t.getToolKey(), t.getMode(), clock.instant());
          rows.save(core);
        }
      }
      for (EndpointConnectionEntity w : wiringRows.findByEndpointIdOrderByAddedAtAscIdAsc(source.getId())) {
        copyWiring(source, copy, w.getConnectionId());
      }
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Endpoint " + newSlug + " created", null, "Duplicate of " + slug, reason, copy.getEnv(), newSlug, null,
          null));
      publisher.publishEvent(new EndpointChanged(copy.getId(), newSlug));
      return copy.getId();
    });
    return summary(snapshots.get(id).orElseThrow());
  }

  /** Copies one connection's pairs from {@code source} to {@code target}, modes and overrides included. */
  private void copyWiring(EndpointEntity source, EndpointEntity target, String connectionId) {
    Map<String, EndpointToolEntity> stored = new LinkedHashMap<>();
    for (EndpointToolEntity t : tools.findByEndpointIdAndConnectionId(source.getId(), connectionId)) {
      stored.put(t.getToolKey(), t);
    }
    ConnectionConfigs.Stored c = connections.byId(connectionId).orElse(null);
    if (c == null) {
      return;
    }
    ConnectorManifest m = registry.requireManifest(c.config().type());
    boolean readOnly = target.getAccess() == EndpointAccess.READ_ONLY;
    wiring.wire(target, c, m, offered -> {
      EndpointToolEntity old = stored.get(offered.toolKey());
      Mode mode = old == null ? Mode.OFF : old.getMode();
      if (mode == Mode.READ_ONLY && offered.needsReadOnlyConfirmation()) {
        mode = Mode.APPROVAL;
      }
      return readOnly ? ToolPairs.clampReadOnly(offered.definition(), mode) : mode;
    });
    for (EndpointToolEntity t : tools.findByEndpointIdAndConnectionId(target.getId(), connectionId)) {
      EndpointToolEntity old = stored.get(t.getToolKey());
      if (old == null) {
        continue;
      }
      boolean copied = false;
      if (old.getOverrides() != null) {
        t.setOverrides(old.getOverrides(), clock.instant());
        copied = true;
      }
      // An external server's tool carries the definition its mode was set for, not the one it has today.
      if (t.getReviewedDefinition() != null && !t.getReviewedDefinition().equals(old.getReviewedDefinition())) {
        t.setReviewedDefinition(old.getReviewedDefinition(), clock.instant());
        copied = true;
      }
      if (copied) {
        rows.save(t);
      }
    }
    if (m.kind() == ConnectorManifest.Kind.MCP_SERVER) {
      // What an external server doesn't list now keeps its choices in the copy too: the tool comes back with them.
      for (EndpointToolEntity old : stored.values()) {
        if (tools.findByEndpointIdAndConnectionIdAndToolKey(target.getId(), connectionId, old.getToolKey())
            .isEmpty()) {
          // Without its definition a tool can't be clamped for a read-only endpoint: Off is what it can always be.
          EndpointToolEntity kept = new EndpointToolEntity(target, connectionId, old.getToolKey(),
              readOnly ? Mode.OFF : old.getMode(), clock.instant());
          kept.setOverrides(old.getOverrides(), clock.instant());
          kept.setReviewedDefinition(old.getReviewedDefinition(), clock.instant());
          rows.save(kept);
        }
      }
    }
  }

  // ── Disable, enable, delete ─────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/endpoints/{slug}/disable}: keeps discovery available; resource calls explain the user pause. */
  public Summary disable(String slug, String reason) {
    return setEnabled(slug, false, reason);
  }

  public Summary enable(String slug, String reason) {
    return setEnabled(slug, true, reason);
  }

  private Summary setEnabled(String slug, boolean enabled, String reason) {
    writeTx.executeWithoutResult(s -> {
      EndpointEntity e = require(slug);
      requireVerified(e);
      if (e.isEnabled() == enabled) {
        return;
      }
      if (enabled) {
        e.enable(clock.instant());
      } else {
        e.disable(clock.instant());
      }
      rows.save(e);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS, "Endpoint " + slug,
          enabled ? "Disabled" : "Running", enabled ? "Running" : "Disabled", reason, e.getEnv(), slug, null, null));
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
    });
    return get(slug);
  }

  /**
   * The tunnel chose (or dropped) this endpoint as the one it publishes (ARCHITECTURE.md §7.3, step K3). The flag
   * stays while the tunnel is off: it marks "the tunnel's endpoint", whose connections must never become prod. A prod
   * endpoint is never published (422). Joins the caller's transaction, which records the tunnel's Setup change.
   */
  public void setPublished(String slug, boolean published) {
    writeTx.executeWithoutResult(s -> {
      EndpointEntity e = require(slug);
      if (e.isPublished() == published) {
        return;
      }
      // Withdrawing is always safe; publishing vouches for the row, so it must be as the core wrote it. A row that
      // doesn't verify is withdrawn without a new signature: it stays "changed outside GateRook".
      boolean trusted = rows.verify(e);
      if (published) {
        requireVerified(e);
        if (e.getEnv() == Env.PROD) {
          throw ProblemException.unprocessable("Prod endpoint", slug + " has prod connections, and a prod endpoint "
              + "is never published through the tunnel.", "/endpoint");
        }
      }
      e.setPublished(published);
      if (trusted) {
        rows.save(e);
      } else {
        endpoints.saveAndFlush(e);
      }
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
    });
  }

  /**
   * {@code DELETE /api/endpoints/{slug} {confirmSlug}}: its tokens and grants go, its pending requests are rejected,
   * the clients connected to it are disconnected; the audit and the requests' history keep the slug.
   */
  public DeleteSummary delete(String slug, String confirmSlug, String reason) {
    if (!slug.equals(confirmSlug)) {
      throw ProblemException.unprocessable("Type the slug to delete", "Type " + slug + " to confirm.", "/confirmSlug");
    }
    return writeTx.execute(s -> {
      EndpointEntity e = require(slug);
      String id = e.getId();
      Env env = e.getEnv();
      Instant now = clock.instant();
      List<String> revoked = new ArrayList<>();
      // Their pending requests go with the endpoint's below; the rest of the core forgets them after commit.
      for (ApiTokenEntity t : tokens.findByEndpointIdAndKindOrderByCreatedAtDesc(id, TokenKind.NAMED)) {
        if (t.isLive(now)) {
          revoked.add(t.getName());
        }
        publisher.publishEvent(new PrincipalRevoked(Caller.Kind.LOCAL_TOKEN, t.getId(), id, slug));
      }
      for (ClientGrantEntity g : grants.findByEndpointId(id)) {
        if (!g.isRevoked()) {
          revoked.add(g.getLabel());
        }
        publisher.publishEvent(new PrincipalRevoked(Caller.Kind.BRIDGE, g.getId(), id, slug));
      }
      List<String> clients = new ArrayList<>();
      List<String> notes = new ArrayList<>();
      int rejected = 0;
      for (EndpointRemovalParticipant p : participants.orderedStream().toList()) {
        EndpointRemovalParticipant.Removal r = p.onEndpointRemoving(id, slug, reason);
        clients.addAll(r.clients());
        notes.addAll(r.notes());
        rejected += r.rejectedApprovals();
      }
      // Rows that point at the endpoint go first: the database would cascade, Hibernate's context wouldn't know.
      tools.deleteAll(tools.findByEndpointId(id));
      wiringRows.deleteAll(wiringRows.findByEndpointIdOrderByAddedAtAscIdAsc(id));
      tokens.deleteAll(tokens.findByEndpointIdAndKindOrderByCreatedAtDesc(id, TokenKind.NAMED));
      tokens.deleteAll(tokens.findByEndpointIdAndKindOrderByCreatedAtDesc(id, TokenKind.BRIDGE));
      grants.deleteAll(grants.findByEndpointId(id));
      tools.flush();
      endpoints.deleteById(id);
      endpoints.flush();
      alerts.resolve("endpoint", id);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Endpoint " + slug + " deleted", null, null, reason, env, slug, null, null));
      publisher.publishEvent(new EndpointRemoved(id, slug));
      return new DeleteSummary(List.copyOf(clients), List.copyOf(revoked), rejected, List.copyOf(notes));
    });
  }

  // ── Settings ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code PUT /api/endpoints/{slug}/settings}. Making an endpoint read only switches every pair that could change
   * data to Off (a Setup change each); making it standard again changes no mode.
   */
  public Summary updateSettings(String slug, SettingsUpdate update, long version, String reason) {
    writeTx.executeWithoutResult(s -> {
      EndpointEntity e = require(slug);
      requireVerified(e);
      if (e.getVersion() != version) {
        throw new OptimisticLockingFailureException("Endpoint " + slug + " is at version " + e.getVersion());
      }
      // Becoming read only rewrites and signs tool rows again, so they must all verify now: before anything here
      // changes the endpoint row, which would otherwise read as an edit made outside the core.
      EndpointSnapshot beforeReadOnly = update.access() != null && access(update.access()) == EndpointAccess.READ_ONLY
          && e.getAccess() != EndpointAccess.READ_ONLY ? requireUnblocked(e) : null;
      List<String[]> changes = new ArrayList<>();
      if (update.description() != null) {
        checkDescription(update.description());
        if (!update.description().equals(e.getDescription())) {
          e.setDescription(update.description().isBlank() ? null : update.description());
          changes.add(new String[] {"description", null, null});
        }
      }
      if (update.instructions() != null) {
        if (update.instructions().length() > MAX_INSTRUCTIONS) {
          throw ProblemException.unprocessable("Instructions too long", "Instructions are at most 4,000 characters.",
              "/instructions");
        }
        if (!update.instructions().equals(e.getInstructions())) {
          e.setInstructions(update.instructions().isBlank() ? null : update.instructions());
          changes.add(new String[] {"instructions", null, null});
        }
      }
      ObjectNode settings = (ObjectNode) rows.tree(e.getSettings());
      if (update.limits() != null) {
        // The whole Limits form: a limit left out is the policy's again.
        policies.checkNarrowing(update.limits(), "/limits", "per endpoint");
        if (!update.limits().equals(settings.path("limits"))) {
          changes.add(new String[] {"limits", settings.path("limits").toString(), update.limits().toString()});
          settings.set("limits", update.limits().deepCopy());
        }
      }
      Map<String, JsonNode> patches = new LinkedHashMap<>();
      if (update.protocol() != null) {
        patches.put("protocol", update.protocol());
      }
      if (update.approvalWait() != null) {
        patches.put("approvalWait", update.approvalWait());
      }
      for (Map.Entry<String, JsonNode> patch : patches.entrySet()) {
        ObjectNode next = patchSetting(settings, patch.getKey(), patch.getValue());
        if (!next.equals(settings.path(patch.getKey()))) {
          changes.add(new String[] {patch.getKey(), settings.path(patch.getKey()).toString(), next.toString()});
          settings.set(patch.getKey(), next);
        }
      }
      e.setSettings(rows.map(settings));
      if (update.oauthEnabled() != null && update.oauthEnabled() != e.isOauthEnabled()) {
        e.setOauthEnabled(update.oauthEnabled());
        changes.add(new String[] {"oauth", String.valueOf(!update.oauthEnabled()),
            String.valueOf(update.oauthEnabled())});
      }
      if (update.access() != null) {
        EndpointAccess access = access(update.access());
        if (access != e.getAccess()) {
          changes.add(new String[] {"access", KebabCaseEnumConverter.idOf(e.getAccess()), update.access()});
          e.setAccess(access);
          if (access == EndpointAccess.READ_ONLY) {
            clampToReadOnly(e, beforeReadOnly, reason);
          }
        }
      }
      if (changes.isEmpty()) {
        return;
      }
      e.touch(clock.instant());
      rows.save(e);
      for (String[] c : changes) {
        setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
            "Endpoint " + slug + " · " + c[0], c[1], c[2], reason, e.getEnv(), slug, null, null));
      }
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
    });
    return get(slug);
  }

  /** @param snapshot the endpoint as it was before this change, every row verified in this transaction */
  private void clampToReadOnly(EndpointEntity e, EndpointSnapshot snapshot, String reason) {
    for (EndpointSnapshot.Pair p : snapshot.pairs()) {
      Mode clamped = ToolPairs.clampReadOnly(p.tool(), p.storedMode());
      if (p.rowId() == null || clamped == p.storedMode()) {
        continue;
      }
      EndpointToolEntity row = tools.findById(p.rowId()).orElseThrow();
      row.setMode(clamped, clock.instant());
      rows.save(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MODE,
          p.tool().name() + " on " + p.connectionName() + ", endpoint " + e.getSlug(), ToolPairs.label(p.storedMode()),
          ToolPairs.label(clamped), reason == null ? "The endpoint became read only" : reason, e.getEnv(), e.getSlug(),
          p.connectionName(), null));
    }
    for (EndpointSnapshot.CorePair c : snapshot.coreTools()) {
      Mode clamped = ToolPairs.clampReadOnly(CoreTools.withMode(c.tool()), c.storedMode());
      if (clamped == c.storedMode()) {
        continue;
      }
      EndpointToolEntity row = tools.findById(c.rowId()).orElseThrow();
      row.setMode(clamped, clock.instant());
      rows.save(row);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.MODE,
          c.tool() + ", endpoint " + e.getSlug(), ToolPairs.label(c.storedMode()), ToolPairs.label(clamped),
          reason == null ? "The endpoint became read only" : reason, e.getEnv(), e.getSlug(), null, null));
    }
  }

  /**
   * A merge patch of one settings object ({@code protocol}, {@code approvalWait}): the keys it names change, the rest
   * stay; the result must match its schema in {@link #SETTINGS_SCHEMA}.
   */
  private ObjectNode patchSetting(ObjectNode settings, String name, JsonNode patch) {
    if (!patch.isObject()) {
      throw ProblemException.unprocessable("Invalid " + name, name + " must be an object.", "/" + name);
    }
    JsonNode current = settings.path(name).isObject() ? settings.get(name) : JsonNodeFactory.instance.objectNode();
    JsonNode next = JsonMergePatch.apply(current, patch);
    for (SchemaValidator.Violation v : validator.validate(SETTINGS_SCHEMA.get(name), next)) {
      throw ProblemException.unprocessable("Invalid " + name, v.message(),
          "/" + name + ("/".equals(v.pointer()) ? "" : v.pointer()));
    }
    return (ObjectNode) next;
  }

  // ── Risk ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/endpoints/{slug}/risk/dismiss {reason}}: until a tool changes the combination. */
  public RiskAnalyzer.Report dismissRisk(String slug, String reason) {
    if (reason == null || reason.isBlank()) {
      throw ProblemException.unprocessable("Reason missing", "Say why the warning doesn't apply: it goes into "
          + "Setup changes.", "/reason");
    }
    EndpointSnapshot snapshot = snapshot(slug);
    RiskAnalyzer.Report report = risks.analyze(snapshot.onPairs(), snapshot.published(), null);
    if ("none".equals(report.state())) {
      throw ProblemException.unprocessable("Nothing to dismiss", slug + " has no risky combination.", null);
    }
    writeTx.executeWithoutResult(s -> {
      EndpointEntity e = require(slug);
      requireVerified(e);
      ObjectNode dismissed = JsonNodeFactory.instance.objectNode();
      dismissed.put("at", clock.instant().toString());
      dismissed.put("reason", reason);
      dismissed.set("ingredients", report.ingredients().toJson());
      e.setRiskDismissed(rows.map(dismissed));
      e.touch(clock.instant());
      rows.save(e);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.RISK_WARNING,
          "Risk warning for " + slug, report.state(), "Dismissed", reason, e.getEnv(), slug, null,
          report.ingredients().toJson()));
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
    });
    return risk(slug);
  }

  /** "Show warning": the dismissal is forgotten. */
  public RiskAnalyzer.Report restoreRisk(String slug, String reason) {
    writeTx.executeWithoutResult(s -> {
      EndpointEntity e = require(slug);
      requireVerified(e);
      if (e.getRiskDismissed() == null) {
        return;
      }
      e.setRiskDismissed(null);
      e.touch(clock.instant());
      rows.save(e);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.RISK_WARNING,
          "Risk warning for " + slug, "Dismissed", "Shown", reason, e.getEnv(), slug, null, null));
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
    });
    return risk(slug);
  }

  // ── Split ───────────────────────────────────────────────────────────────────────────────────────────────────

  /** {@code POST /api/endpoints/{slug}/split/preview {move[], newSlug}}: both endpoints' risk before and after. */
  public SplitPreview previewSplit(String slug, List<String> move, String newSlug) {
    EndpointSnapshot snapshot = snapshot(slug);
    Set<String> moving = checkMove(snapshot, move);
    List<EndpointSnapshot.Pair> remaining = new ArrayList<>();
    List<EndpointSnapshot.Pair> moved = new ArrayList<>();
    for (EndpointSnapshot.Pair p : snapshot.onPairs()) {
      (moving.contains(p.connectionName()) ? moved : remaining).add(p);
    }
    return new SplitPreview(newSlug, risks.analyze(snapshot), risks.analyze(remaining, snapshot.published(), null),
        risks.analyze(moved, false, null), moved.stream().map(p -> p.tool().name() + " on " + p.connectionName())
            .toList());
  }

  /**
   * {@code POST /api/endpoints/{slug}/split}: the chosen connections move to a new endpoint with their modes and
   * limits; the original keeps its URL, tokens and clients. Pending requests of the moved pairs on the original are
   * rejected (they can't run there any more).
   */
  public Summary split(String slug, List<String> move, String newSlug, String reason) {
    checkSlug(newSlug);
    checkMove(snapshot(slug), move);
    String id = writeTx.execute(s -> {
      EndpointEntity source = require(slug);
      requireVerified(source);
      // What moves is copied into rows signed afresh: it comes from the rows as they are now, each one verified.
      EndpointSnapshot snapshot = requireUnblocked(source);
      Set<String> moving = checkMove(snapshot, move);
      if (endpoints.existsBySlug(newSlug)) {
        throw ProblemException.conflict("Slug in use", "An endpoint /mcp/" + newSlug + " already exists.");
      }
      EndpointEntity target = new EndpointEntity(newSlug, source.getEnv(), source.getAccess(),
          rows.map(rows.tree(source.getSettings())), clock.instant());
      target.setDescription(source.getDescription());
      target.setInstructions(source.getInstructions());
      target = rows.save(target);
      for (EndpointToolEntity t : tools.findByEndpointId(source.getId())) {
        if (t.getConnectionId() == null) {
          rows.save(new EndpointToolEntity(target, null, t.getToolKey(), t.getMode(), clock.instant()));
        }
      }
      for (EndpointSnapshot.Wired w : snapshot.connections()) {
        if (!moving.contains(w.config().name())) {
          continue;
        }
        copyWiring(source, target, w.config().id());
        for (EndpointRemovalParticipant p : participants.orderedStream().toList()) {
          p.onConnectionUnwiring(source.getId(), slug, w.config().id(), w.config().name(), reason);
        }
        wiring.unwire(source, w.config().id());
      }
      source.setEnv(wiring.deriveEnv(source.getId(), source.getEnv()));
      source.touch(clock.instant());
      rows.save(source);
      target.setEnv(wiring.deriveEnv(target.getId(), target.getEnv()));
      target = rows.save(target);
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Endpoint " + slug + " split", String.join(", ", moving), "Moved to " + newSlug, reason, source.getEnv(),
          slug, null, null));
      publisher.publishEvent(new EndpointChanged(source.getId(), slug));
      publisher.publishEvent(new EndpointChanged(target.getId(), newSlug));
      return target.getId();
    });
    return summary(snapshots.get(id).orElseThrow());
  }

  private static Set<String> checkMove(EndpointSnapshot snapshot, List<String> move) {
    if (move == null || move.isEmpty()) {
      throw ProblemException.unprocessable("Nothing to move", "Pick a connection to move.", "/move");
    }
    Set<String> names = new HashSet<>();
    for (String name : move) {
      if (snapshot.connection(name).isEmpty()) {
        throw ProblemException.unprocessable("Not on this endpoint", name + " is not a connection of "
            + snapshot.slug() + ".", "/move");
      }
      names.add(name);
    }
    if (names.size() == snapshot.connections().size()) {
      throw ProblemException.unprocessable("Nothing would stay", "Keep at least one connection on " + snapshot.slug()
          + ".", "/move");
    }
    return names;
  }

  // ── Integrity ───────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The person reviewed an endpoint changed outside the core and keeps it as stored (Setup change). Only its env,
   * which nobody chooses, is derived again from its connections: a connection may have left while it was unverified.
   *
   * <p>What an ordinary change would refuse is not kept silently: limits and pair overrides that don't narrow or are
   * out of range, {@code protocol} or {@code approvalWait} that don't match {@link #SETTINGS_SCHEMA}, instructions over
   * 4,000 characters answer 422 at their pointer with {@code confirm:"reset-invalid"}. So do the rules that the
   * endpoint's own changes keep: a connection on the other side of the prod line from the endpoint, a published prod
   * endpoint, modes a read-only endpoint doesn't allow, and what skips a person's review that was set outside GateRook
   * (Bypass, a temporary bypass, a Read only confirmation of a tool row that didn't verify). An endpoint has no reset of
   * its own, and an unverified one can't be edited, so repeating with that confirmation resets exactly those values to
   * their defaults (a limit or override left out is the policy's again; a connection across the prod line leaves the
   * endpoint; Bypass becomes Approval) and keeps the rest; the Setup change names them.
   */
  public Summary confirmIntegrity(String slug, String reason) {
    return confirmIntegrity(slug, reason, null);
  }

  /** As {@link #confirmIntegrity(String, String)}; {@code confirm} {@code "reset-invalid"} resets what is invalid. */
  public Summary confirmIntegrity(String slug, String reason, String confirm) {
    writeTx.executeWithoutResult(s -> {
      EndpointEntity e = require(slug);
      List<String> reset = resetInvalid(e, "reset-invalid".equals(confirm));
      Env derived = wiring.deriveEnv(e.getId(), e.getEnv());
      if (derived != e.getEnv() || !reset.isEmpty()) {
        e.setEnv(derived);
        e.touch(clock.instant());
        e = endpoints.saveAndFlush(e);
      }
      rows.resign(e);
      wiringRows.findByEndpointIdOrderByAddedAtAscIdAsc(e.getId()).forEach(rows::resign);
      tools.findByEndpointId(e.getId()).forEach(rows::resign);
      alerts.resolve("endpoint", e.getId());
      setupChanges.getObject().record(new SetupChange(SetupChangeRecorder.What.SETTINGS,
          "Endpoint " + slug + " changed outside GateRook", "Unverified", reset.isEmpty() ? "Kept as stored"
              : "Kept as stored, reset: " + String.join(", ", reset), reason, e.getEnv(), slug, null, null));
      publisher.publishEvent(new EndpointChanged(e.getId(), slug));
    });
    return get(slug);
  }

  /**
   * The checks of {@link #updateSettings} and {@code EndpointTools.setMode} on the rows as stored. Without
   * {@code reset}, the first violation is a 422 at its pointer (with {@code confirm:"reset-invalid"}); with it, every
   * invalid value is reset to its default (flushed, so that the review signs the settled rows) and named.
   */
  private List<String> resetInvalid(EndpointEntity e, boolean reset) {
    List<String> out = new ArrayList<>();
    // Before anything here changes a row: which tool rows are as the core signed them.
    Set<String> unverifiedTools = new HashSet<>();
    for (EndpointToolEntity t : tools.findByEndpointId(e.getId())) {
      if (!rows.verify(t)) {
        unverifiedTools.add(t.getId());
      }
    }
    JsonNode stored = rows.tree(e.getSettings());
    ObjectNode settings = stored.isObject() ? (ObjectNode) stored.deepCopy() : JsonNodeFactory.instance.objectNode();
    if (!stored.isObject()) {
      invalid(reset, "The stored settings aren't an object.", "/settings");
      settings = defaultSettings();
      out.add("settings");
    }
    if (settings.path("limits").isObject()) {
      ObjectNode limits = (ObjectNode) settings.get("limits");
      for (String key : List.copyOf(limits.propertyNames())) {
        Optional<ProblemException> p = narrowingProblem(limits.get(key), key, "/limits", "per endpoint");
        if (p.isPresent()) {
          invalid(reset, p.get().getMessage(), p.get().field());
          limits.remove(key);
          out.add("limits." + key);
        }
      }
    } else if (settings.has("limits")) {
      invalid(reset, "limits must be an object.", "/limits");
      settings.putObject("limits");
      out.add("limits");
    }
    for (String name : List.of("protocol", "approvalWait")) {
      for (SchemaValidator.Violation v : validator.validate(SETTINGS_SCHEMA.get(name), settings.path(name))) {
        invalid(reset, v.message(), "/" + name + ("/".equals(v.pointer()) ? "" : v.pointer()));
        settings.set(name, defaultSettings().get(name));
        out.add(name);
        break;
      }
    }
    if (!out.isEmpty()) {
      e.setSettings(rows.map(settings));
    }
    if (e.getInstructions() != null && e.getInstructions().length() > MAX_INSTRUCTIONS) {
      invalid(reset, "Instructions are at most 4,000 characters.", "/instructions");
      e.setInstructions(null);
      out.add("instructions");
    }
    for (EndpointToolEntity t : tools.findByEndpointId(e.getId())) {
      JsonNode overrides = rows.tree(t.getOverrides());
      if (overrides.isNull()) {
        continue;
      }
      ObjectNode kept = overrides.isObject() ? (ObjectNode) overrides.deepCopy()
          : JsonNodeFactory.instance.objectNode();
      boolean changed = !overrides.isObject();
      if (changed) {
        invalid(reset, "overrides must be an object.", "/overrides");
      }
      for (String key : List.copyOf(kept.propertyNames())) {
        Optional<ProblemException> p = narrowingProblem(kept.get(key), key, "/overrides", "per tool");
        if (p.isPresent()) {
          invalid(reset, Wiring.toolName(t.getToolKey()) + ": " + p.get().getMessage(), p.get().field());
          kept.remove(key);
          changed = true;
        }
      }
      if (changed) {
        t.setOverrides(kept.isEmpty() ? null : rows.map(kept), clock.instant());
        tools.saveAndFlush(t);
        out.add(Wiring.toolName(t.getToolKey()) + " overrides");
      }
    }
    resetProdLine(e, reset, out);
    resetUnreviewedGrants(e, unverifiedTools, reset, out);
    if (e.getAccess() == EndpointAccess.READ_ONLY) {
      resetReadOnlyModes(e, reset, out);
    }
    if (e.isPublished() && wiring.deriveEnv(e.getId(), e.getEnv()) == Env.PROD) {
      invalid(reset, e.getSlug() + " has prod connections, and a prod endpoint is never published through the "
          + "tunnel. Resetting withdraws it.", "/published");
      e.setPublished(false);
      out.add("published");
    }
    return out;
  }

  /**
   * Prod never mixes with dev or stage, and an endpoint doesn't cross between them ({@code EndpointUsage}): a
   * connection on the other side of the line would carry the access made under one side's rules (named tokens,
   * client grants, sign-ins) to the other. Resetting removes it from the endpoint, as removing it in GateRook would.
   */
  private void resetProdLine(EndpointEntity e, boolean reset, List<String> out) {
    boolean prodEndpoint = e.getEnv() == Env.PROD;
    for (EndpointConnectionEntity w : wiringRows.findByEndpointIdOrderByAddedAtAscIdAsc(e.getId())) {
      Optional<ConnectionConfigs.Stored> c = connections.byId(w.getConnectionId());
      if (c.isEmpty() || (c.get().config().env() == Env.PROD) == prodEndpoint) {
        continue;
      }
      String name = c.get().name();
      invalid(reset, name + " is a " + c.get().config().env().id() + " connection on " + e.getSlug() + ", a "
          + e.getEnv().id() + " endpoint: prod never mixes with dev or stage. Resetting removes " + name + " from "
          + e.getSlug() + ".", "/connections");
      for (EndpointRemovalParticipant p : participants.orderedStream().toList()) {
        p.onConnectionUnwiring(e.getId(), e.getSlug(), c.get().id(), name, "Changed outside GateRook");
      }
      wiring.unwire(e, c.get().id());
      out.add(name + " removed");
    }
  }

  /**
   * What lets calls skip a person's review is set only in GateRook: on a tool row that didn't verify, Bypass becomes
   * Approval, and a temporary bypass, a Read only confirmation or the reviewed definition of an external MCP server's
   * tool (which Read only and Bypass need) is dropped (the person can set them again).
   */
  private void resetUnreviewedGrants(EndpointEntity e, Set<String> unverifiedTools, boolean reset, List<String> out) {
    for (EndpointToolEntity t : tools.findByEndpointId(e.getId())) {
      if (!unverifiedTools.contains(t.getId())) {
        continue;
      }
      String tool = Wiring.toolName(t.getToolKey());
      boolean changed = false;
      if (t.getMode() == Mode.BYPASS) {
        invalid(reset, tool + " was set to Bypass outside GateRook. Resetting sets it to Approval.", "/tools");
        t.setMode(Mode.APPROVAL, clock.instant());
        out.add(tool + " Bypass");
        changed = true;
      }
      if (t.getTemporaryBypass() != null) {
        invalid(reset, tool + " has a temporary bypass that was set outside GateRook. Resetting removes it.",
            "/tools");
        t.setTemporaryBypass(null, clock.instant());
        out.add(tool + " temporary bypass");
        changed = true;
      }
      if (t.getRoConfirmation() != null) {
        invalid(reset, tool + " has a Read only confirmation that was set outside GateRook. Resetting removes it; "
            + "confirm it again in GateRook.", "/tools");
        t.setRoConfirmation(null, clock.instant());
        out.add(tool + " Read only confirmation");
        changed = true;
      }
      if (t.getReviewedDefinition() != null) {
        invalid(reset, tool + " has a reviewed definition that was set outside GateRook. Resetting removes it, and "
            + "Read only or Bypass go back to Approval until you set them again in GateRook.", "/tools");
        t.setReviewedDefinition(null, clock.instant());
        out.add(tool + " reviewed definition");
        changed = true;
      }
      if (changed) {
        tools.saveAndFlush(t);
      }
    }
  }

  /** A read-only endpoint keeps only the modes {@link ToolPairs#clampReadOnly} allows, as becoming read only does. */
  private void resetReadOnlyModes(EndpointEntity e, boolean reset, List<String> out) {
    EndpointSnapshot s = snapshots.uncached(e.getId()).orElseThrow(() -> notFound(e.getSlug()));
    Map<String, Mode> clamped = new LinkedHashMap<>();
    Map<String, String> names = new LinkedHashMap<>();
    for (EndpointSnapshot.Pair p : s.pairs()) {
      Mode mode = ToolPairs.clampReadOnly(p.tool(), p.storedMode());
      if (p.rowId() != null && mode != p.storedMode()) {
        clamped.put(p.rowId(), mode);
        names.put(p.rowId(), p.tool().name() + " on " + p.connectionName());
      }
    }
    for (EndpointSnapshot.CorePair c : s.coreTools()) {
      Mode mode = ToolPairs.clampReadOnly(CoreTools.withMode(c.tool()), c.storedMode());
      if (mode != c.storedMode()) {
        clamped.put(c.rowId(), mode);
        names.put(c.rowId(), c.tool());
      }
    }
    for (Map.Entry<String, Mode> c : clamped.entrySet()) {
      invalid(reset, e.getSlug() + " is read only: " + names.get(c.getKey()) + " can only be "
          + ToolPairs.label(c.getValue()) + " there. Resetting sets it so.", "/tools");
      EndpointToolEntity row = tools.findById(c.getKey()).orElseThrow();
      row.setMode(c.getValue(), clock.instant());
      tools.saveAndFlush(row);
      out.add(names.get(c.getKey()) + " " + ToolPairs.label(c.getValue()));
    }
  }

  /** Why one limit can't narrow ({@link PolicyService#checkNarrowing}), or empty. */
  private Optional<ProblemException> narrowingProblem(JsonNode value, String key, String pointer, String scope) {
    try {
      policies.checkNarrowing(JsonNodeFactory.instance.objectNode().set(key, value), pointer, scope);
      return Optional.empty();
    } catch (ProblemException p) {
      return Optional.of(p);
    }
  }

  private static void invalid(boolean reset, String detail, String field) {
    if (!reset) {
      throw new ProblemException(ProblemException.Kind.UNPROCESSABLE, "Can't keep it as stored", detail
          + " Reset it to its default (confirm reset-invalid) and keep the rest, or delete the endpoint.", field,
          "reset-invalid");
    }
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private Summary summary(EndpointSnapshot s) {
    int read = 0;
    int approval = 0;
    int off = 0;
    int bypass = 0;
    for (EndpointSnapshot.Pair p : s.pairs()) {
      switch (p.mode()) {
        case READ_ONLY -> read++;
        case APPROVAL -> approval++;
        case OFF -> off++;
        case BYPASS -> bypass++;
      }
    }
    for (EndpointSnapshot.CorePair c : s.coreTools()) {
      switch (c.mode()) {
        case READ_ONLY -> read++;
        case APPROVAL -> approval++;
        case OFF -> off++;
        case BYPASS -> bypass++;
      }
    }
    Map<Env, Integer> byEnv = new EnumMap<>(Env.class);
    Map<String, Integer> health = new LinkedHashMap<>();
    health.put("healthy", 0);
    health.put("degraded", 0);
    health.put("unreachable", 0);
    health.put("unknown", 0);
    for (EndpointSnapshot.Wired w : s.connections()) {
      byEnv.merge(w.config().env(), 1, Integer::sum);
      HealthResult.State state = connections.byId(w.config().id()).map(c -> c.health().state()).orElse(null);
      String key = state == null ? "unknown" : state.name().toLowerCase(java.util.Locale.ROOT);
      health.merge(key, 1, Integer::sum);
    }
    Instant now = clock.instant();
    int active = 0;
    int expiring = 0;
    List<ApiTokenEntity> named = readTx.execute(st -> tokens.findByEndpointIdAndKindOrderByCreatedAtDesc(s.id(),
        TokenKind.NAMED));
    for (ApiTokenEntity t : named) {
      if (t.isLive(now)) {
        active++;
        if (t.getExpiresAt().isBefore(now.plus(Duration.ofDays(3)))) {
          expiring++;
        }
      }
    }
    return new Summary(s.id(), s.slug(), s.description(), url(s.slug()), s.env(),
        s.readOnly() ? "read-only" : "standard", s.enabled() ? "running" : "disabled",
        new ToolCounts(read, approval, off, bypass), s.connections().size(), Map.copyOf(byEnv), Map.copyOf(health), active,
        expiring, s.published(), risks.analyze(s), s.blockedReason(), s.version());
  }

  private EndpointEntity require(String slug) {
    return endpoints.findBySlug(slug).orElseThrow(() -> notFound(slug));
  }

  private void requireVerified(EndpointEntity e) {
    if (!rows.verify(e)) {
      alerts.report("endpoint", e.getId(), e.getSlug(), "endpoint row");
      throw ProblemException.conflict("Changed outside GateRook", e.getSlug() + " was changed outside "
          + "GateRook. Review it first.");
    }
  }

  /**
   * The endpoint with every one of its rows verified in this transaction (not the cached view: an edit made since it
   * was built would slip through); 409 when one doesn't.
   */
  private EndpointSnapshot requireUnblocked(EndpointEntity e) {
    EndpointSnapshot fresh = snapshots.uncached(e.getId()).orElseThrow(() -> notFound(e.getSlug()));
    if (fresh.blocked()) {
      throw ProblemException.conflict("Changed outside GateRook", e.getSlug() + ": " + fresh.blockedReason()
          + ". Review it first.");
    }
    return fresh;
  }

  static ProblemException notFound(String slug) {
    return ProblemException.notFound("Endpoint not found", "There is no endpoint " + slug + ".");
  }

  private static void checkSlug(String slug) {
    if (slug == null || !SLUG.matcher(slug).matches()) {
      throw ProblemException.unprocessable("Invalid slug", "Lowercase letters, digits and hyphens, up to 63.",
          "/slug");
    }
  }

  private static void checkDescription(String description) {
    if (description != null && description.length() > 500) {
      throw ProblemException.unprocessable("Description too long", "A description is at most 500 characters.",
          "/description");
    }
  }

  private static EndpointAccess access(String id) {
    return switch (id) {
      case "standard" -> EndpointAccess.STANDARD;
      case "read-only" -> EndpointAccess.READ_ONLY;
      default -> throw ProblemException.unprocessable("Invalid access", "Access is standard or read-only.",
          "/access");
    };
  }

  /** Protocol revisions, the endpoint's own limits and the approval wait (report 01 §4). */
  static ObjectNode defaultSettings() {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    ObjectNode protocol = s.putObject("protocol");
    protocol.putArray("revisions").add("2026-07-28").add("2025-11-25");
    protocol.put("approvalDelivery", "auto");
    s.putObject("limits");
    s.putObject("approvalWait").put("firstWaitSeconds", 15);
    return s;
  }
}
