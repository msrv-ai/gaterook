package ai.msrv.gaterook.core.admin.overview;

import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.HealthResult;
import ai.msrv.gaterook.core.admin.activity.ActivityDtos;
import ai.msrv.gaterook.core.admin.approvals.ApprovalDtos;
import ai.msrv.gaterook.core.admin.clients.ClientCalls;
import ai.msrv.gaterook.core.audit.AuditQueries;
import ai.msrv.gaterook.core.clients.ClientService;
import ai.msrv.gaterook.core.clients.ClientViews;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.connections.HealthMonitor;
import ai.msrv.gaterook.core.connectors.ConnectorRegistry;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.endpoints.TokenService;
import ai.msrv.gaterook.core.platform.SettingStore;
import ai.msrv.gaterook.core.platform.SetupProgress;
import ai.msrv.gaterook.core.platform.StartupFacts;
import ai.msrv.gaterook.core.policy.PolicyService;
import ai.msrv.gaterook.core.secrets.MasterKeyService;
import ai.msrv.gaterook.core.secrets.MasterKeyStatus;
import ai.msrv.gaterook.core.secrets.SecretInfo;
import ai.msrv.gaterook.core.secrets.SecretService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.BooleanNode;

/**
 * Overview and onboarding (ARCHITECTURE.md §6 overview, onboarding; kit {@code overview.html}, {@code getting-started},
 * {@code setup-map}): getting started in the kit's six steps (master key backed up, a connection, an endpoint, a
 * client, a Playground call, approval notifications), the setup map's five nodes with their state, the day's numbers
 * against the day before, the three requests closest to their auto-deny, recent calls and the connections' health.
 */
@RestController
@RegisterReflectionForBinding({OverviewController.Overview.class, OverviewController.GettingStarted.class,
    OverviewController.Step.class, OverviewController.SetupMap.class, OverviewController.Node.class,
    OverviewController.Stats.class, OverviewController.Deltas.class, OverviewController.HealthRow.class,
    OverviewController.Onboarding.class, OverviewController.Skipped.class, ApprovalDtos.Item.class,
    ActivityDtos.Event.class})
class OverviewController {

  static final String DISMISSED = "overview.getting-started-dismissed";
  static final String SKIPPED = "onboarding.skipped";

  /**
   * One step of getting started.
   *
   * @param state  {@code done}, {@code current} (the first one not done) or {@code todo}
   * @param detail facts for the step's line ({@code lastBackupAt}, {@code name}, {@code tools}, {@code clients})
   */
  record Step(String id, String state, Map<String, Object> detail) {}

  record GettingStarted(int done, int total, boolean dismissed, List<Step> steps) {}

  /**
   * One node of the setup map.
   *
   * @param id    {@code secrets}, {@code connections}, {@code policies}, {@code endpoints} or {@code clients}
   * @param state {@code done}, {@code attention} or {@code todo}
   */
  record Node(String id, int count, String state, String status) {}

  record SetupMap(List<Node> nodes) {}

  /** The change against the 24 hours before. */
  record Deltas(long calls24h, long denied24h, Long gatewayP95Ms) {}

  /**
   * @param spark           calls per hour of the last 24 hours, oldest first
   * @param gatewayP95Spark the gateway's p95 overhead of each of those hours ({@code null}: no completed call)
   */
  record Stats(long calls24h, long pending, long denied24h, Long gatewayP95Ms, Deltas deltas, List<Long> spark,
      List<Long> gatewayP95Spark) {}

  /** {@code since}: when its health became what it is ("unreachable since 11:42"). */
  record HealthRow(String name, String type, String env, String state, Long latencyMs, String message,
      Instant checkedAt, Instant since, String typeLabel, String host) {}

  /**
   * @param health                the connections (not the external MCP servers: Connectors lists those), problems
   *                              first is the page's order
   * @param healthIntervalSeconds how often the core checks them ("checked every minute"); 0: it doesn't by itself
   */
  record Overview(GettingStarted gettingStarted, SetupMap setupMap, Stats stats, List<ApprovalDtos.Item> waiting,
      List<ActivityDtos.Event> recent, List<HealthRow> health, long healthIntervalSeconds) {}

  /** {@code GET /api/onboarding}: {@code firstRun} from this start's ready line. */
  record Onboarding(boolean firstRun, boolean skipped, GettingStarted gettingStarted, SetupMap setupMap) {}

  record Skipped(boolean skipped) {}

  private final MasterKeyService masterKey;
  private final SecretService secrets;
  private final ConnectionService connections;
  private final EndpointService endpoints;
  private final TokenService tokens;
  private final PolicyService policies;
  private final ai.msrv.gaterook.core.approvals.ApprovalService approvals;
  private final AuditQueries audit;
  private final SettingStore settings;
  private final StartupFacts facts;
  private final ObjectProvider<SetupProgress> progress;
  private final ConnectorRegistry registry;
  private final ClientService clientService;
  private final ClientCalls clientCalls;
  private final HealthMonitor monitor;
  private final Clock clock;

  OverviewController(MasterKeyService masterKey, SecretService secrets, ConnectionService connections,
      EndpointService endpoints, TokenService tokens, PolicyService policies,
      ai.msrv.gaterook.core.approvals.ApprovalService approvals, AuditQueries audit, SettingStore settings,
      StartupFacts facts, ObjectProvider<SetupProgress> progress, ConnectorRegistry registry,
      ClientService clientService, ClientCalls clientCalls, HealthMonitor monitor, Clock clock) {
    this.masterKey = masterKey;
    this.secrets = secrets;
    this.connections = connections;
    this.endpoints = endpoints;
    this.tokens = tokens;
    this.policies = policies;
    this.approvals = approvals;
    this.audit = audit;
    this.settings = settings;
    this.facts = facts;
    this.progress = progress;
    this.registry = registry;
    this.clientService = clientService;
    this.clientCalls = clientCalls;
    this.monitor = monitor;
    this.clock = clock;
  }

  @GetMapping("/api/overview")
  Overview overview() {
    World w = world();
    Instant now = clock.instant();
    AuditQueries.Stats today = audit.stats(now.minus(Duration.ofHours(24)), now);
    AuditQueries.Stats before = audit.stats(now.minus(Duration.ofHours(48)), now.minus(Duration.ofHours(24)));
    long denied = today.decisions().getOrDefault("denied", 0L);
    long deniedBefore = before.decisions().getOrDefault("denied", 0L);
    Long p95Delta = today.gatewayP95Ms() == null || before.gatewayP95Ms() == null ? null
        : today.gatewayP95Ms() - before.gatewayP95Ms();
    Stats stats = new Stats(today.calls(), approvals.pendingTotal(), denied, today.gatewayP95Ms(),
        new Deltas(today.calls() - before.calls(), denied - deniedBefore, p95Delta), lastHours(today.perHour()),
        lastHours(today.gatewayP95PerHour()));
    List<ApprovalDtos.Item> waiting = approvals.pending().stream().limit(3).map(ApprovalDtos::item).toList();
    List<ActivityDtos.Event> recent = audit.events(null, null, null, null, 8).events().stream()
        .map(ActivityDtos::event).toList();
    List<HealthRow> health = w.connections().stream().map(c -> new HealthRow(c.name(), c.type(), c.env().id(),
        c.health() == null || c.health().state() == null ? "unknown"
            : c.health().state().name().toLowerCase(Locale.ROOT), c.health() == null ? null : c.health().latencyMs(),
        c.health() == null ? null : c.health().message(), c.health() == null ? null : c.health().checkedAt(),
        c.health() == null ? null : c.health().since(), c.typeLabel(), c.target()))
        .toList();
    return new Overview(gettingStarted(w), setupMap(w), stats, waiting, recent, health,
        monitor.interval().toSeconds());
  }

  /** 24 hours span 25 clock hours unless now is on the hour; the sparkline shows the 24 latest. */
  private static <T> List<T> lastHours(List<T> perHour) {
    return perHour.size() <= 24 ? perHour : perHour.subList(perHour.size() - 24, perHour.size());
  }

  @PostMapping("/api/overview/getting-started/dismiss")
  GettingStarted dismiss() {
    settings.put(DISMISSED, BooleanNode.TRUE, null);
    return gettingStarted(world());
  }

  /** Undo of Dismiss. */
  @PostMapping("/api/overview/getting-started/restore")
  GettingStarted restore() {
    settings.put(DISMISSED, BooleanNode.FALSE, null);
    return gettingStarted(world());
  }

  @GetMapping("/api/onboarding")
  Onboarding onboarding() {
    World w = world();
    return new Onboarding(facts.firstRun(), flag(SKIPPED), gettingStarted(w), setupMap(w));
  }

  @PostMapping("/api/onboarding/skip")
  Skipped skip() {
    settings.put(SKIPPED, BooleanNode.TRUE, null);
    return new Skipped(true);
  }

  // ── The two views of the setup ──────────────────────────────────────────────────────────────────────────────

  /**
   * What both views read, once per request.
   *
   * @param connections  the connections, without the external MCP servers (the kit's "12 connections" while
   *                     github-mcp is installed: Connectors lists those)
   * @param activeTokens the tokens of every endpoint's token table that work now, as the endpoint cards count them:
   *                     named tokens and the grants of helpers (Codex's {@code codex-laptop}), not the Claude Desktop
   *                     bridge, which only fetches short-lived ones
   * @param cards        the Clients page's cards (connected, detected, needs restart), or empty when the clients
   *                     module couldn't say
   */
  private record World(MasterKeyStatus key, List<SecretInfo> secrets, List<ConnectionService.Summary> connections,
      List<EndpointService.Summary> endpoints, List<String> clients, int namedTokens, int activeTokens,
      List<ClientViews.Card> cards, Optional<SetupProgress.Progress> clientProgress,
      Optional<SetupProgress.Progress> notifications) {}

  private World world() {
    List<EndpointService.Summary> eps = endpoints.list();
    Set<String> clients = new LinkedHashSet<>();
    int named = 0;
    int active = 0;
    for (EndpointService.Summary e : eps) {
      for (TokenService.TokenRow t : tokens.list(e.slug())) {
        if (!"active".equals(t.state())) {
          continue;
        }
        if ("grant".equals(t.kind()) && t.client() != null) {
          clients.add(t.client());
          active += "claude-desktop".equals(t.client()) ? 0 : 1;
        } else if ("named".equals(t.kind())) {
          named++;
          active++;
        }
      }
    }
    List<ConnectionService.Summary> resources = connections.list().stream()
        .filter(c -> registry.manifest(c.type()).map(m -> m.kind() != ConnectorManifest.Kind.MCP_SERVER)
            .orElse(true))
        .toList();
    List<ClientViews.Card> cards;
    try {
      // As the Clients page shows them: actual calls and current OAuth sign-ins establish connectivity. Overview
      // reads only each link's last call, not the protocol revisions of the page.
      cards = clientCalls.withLastCalls(clientService.clients().clients());
    } catch (RuntimeException e) {
      cards = List.of();
    }
    return new World(masterKey.status(), secrets.list(), resources, eps, List.copyOf(clients), named, active,
        cards, area("clients"), area("notifications"));
  }

  private Optional<SetupProgress.Progress> area(String name) {
    return progress.orderedStream().filter(p -> name.equals(p.area())).findFirst().map(SetupProgress::progress);
  }

  private GettingStarted gettingStarted(World w) {
    List<Step> steps = new ArrayList<>();
    add(steps, "master-key", w.key().lastBackupAt() != null, detail("lastBackupAt", w.key().lastBackupAt()));
    add(steps, "connection", !w.connections().isEmpty(), w.connections().isEmpty() ? Map.of()
        : detail("name", w.connections().get(0).name(), "typeName", w.connections().get(0).typeName(), "count",
            w.connections().size()));
    Optional<EndpointService.Summary> firstEndpoint = w.endpoints().stream().findFirst();
    add(steps, "endpoint", firstEndpoint.isPresent(), firstEndpoint.map(e -> detail("name", e.slug(), "tools",
        e.tools().read() + e.tools().approval() + e.tools().bypass())).orElse(Map.of()));
    List<String> connectedClients = w.cards().stream().filter(c -> "connected".equals(c.status()))
        .map(ClientViews.Card::name).toList();
    boolean clientDone = !connectedClients.isEmpty() || w.clientProgress().map(SetupProgress.Progress::done)
        .orElse(!w.clients().isEmpty() || w.namedTokens() > 0 || audit.any("-client:playground"));
    add(steps, "client", clientDone, connectedClients.isEmpty()
        ? w.clientProgress().map(p -> detail("status", p.status())).orElse(detail("clients", w.clients()))
        : detail("status", String.join(", ", connectedClients)));
    add(steps, "playground", audit.any("client:playground"), Map.of());
    boolean notified = w.notifications().map(SetupProgress.Progress::done).orElse(false);
    add(steps, "notifications", notified, w.notifications().map(p -> detail("status", p.status())).orElse(Map.of()));
    List<Step> marked = new ArrayList<>();
    boolean current = false;
    int done = 0;
    for (Step s : steps) {
      if ("done".equals(s.state())) {
        done++;
        marked.add(s);
      } else if (!current) {
        current = true;
        marked.add(new Step(s.id(), "current", s.detail()));
      } else {
        marked.add(s);
      }
    }
    return new GettingStarted(done, steps.size(), flag(DISMISSED), List.copyOf(marked));
  }

  /**
   * The setup map's five nodes in the kit's words (design/elements/setup-map.html, design/pages/overview.html): "Master
   * key backed up · 11 secrets", "12 connections · billing-api unreachable", "3 environments · mutations need
   * approval", "3 endpoints · 2 active tokens", "2 connected · Cursor detected, not connected"; a first run reads
   * "Master key ready · no secrets yet", "None yet · start here", "Safe defaults · not reviewed yet", "None yet ·
   * needs a connection first", "3 detected · needs an endpoint first".
   */
  private SetupMap setupMap(World w) {
    List<Node> nodes = new ArrayList<>();
    nodes.add(secretsNode(w));
    nodes.add(connectionsNode(w));
    nodes.add(policiesNode(w));
    nodes.add(endpointsNode(w));
    nodes.add(clientsNode(w));
    return new SetupMap(List.copyOf(nodes));
  }

  /** The master key's problem codes ({@code GET /api/master-key} {@code problems}) in words. */
  private static final Map<String, String> KEY_PROBLEMS = Map.of("perms", "file readable by others",
      "synced-folder", "in a synced folder", "backup-needed", "not backed up", "no-os-protection", "no OS protection",
      "key-missing", "not found", "key-invalid", "can't be read");

  private static Node secretsNode(World w) {
    int count = w.secrets().size();
    boolean anyLocal = w.secrets().stream().anyMatch(s -> "local".equals(s.source()));
    // Nothing is lost without a backup while nothing is encrypted with the key: a first run is done here.
    List<String> problems = w.key().problems().stream().filter(p -> anyLocal || !"backup-needed".equals(p))
        .toList();
    String secrets = count == 0 ? "no secrets yet" : count + (count == 1 ? " secret" : " secrets");
    if (!problems.isEmpty()) {
      return new Node("secrets", count, "attention", "Master key: " + String.join(", ", problems.stream()
          .map(p -> KEY_PROBLEMS.getOrDefault(p, p)).toList()) + " · " + secrets);
    }
    String key = w.key().lastBackupAt() != null ? "Master key backed up" : "Master key ready";
    return new Node("secrets", count, "done", key + " · " + secrets);
  }

  private static Node connectionsNode(World w) {
    List<ConnectionService.Summary> all = w.connections();
    if (all.isEmpty()) {
      return new Node("connections", 0, "todo", "None yet · start here");
    }
    String counted = all.size() + (all.size() == 1 ? " connection" : " connections");
    List<String> changed = all.stream().filter(c -> !c.verified()).map(ConnectionService.Summary::name).toList();
    List<String> down = all.stream().filter(c -> c.verified() && state(c) == HealthResult.State.UNREACHABLE)
        .map(ConnectionService.Summary::name).toList();
    if (!changed.isEmpty()) {
      return new Node("connections", all.size(), "attention", counted + " · " + some(changed)
          + " changed outside GateRook");
    }
    if (!down.isEmpty()) {
      return new Node("connections", all.size(), "attention", counted + " · " + some(down) + " unreachable");
    }
    List<String> slow = all.stream().filter(c -> state(c) == HealthResult.State.DEGRADED)
        .map(ConnectionService.Summary::name).toList();
    if (!slow.isEmpty()) {
      return new Node("connections", all.size(), "done", counted + " · " + some(slow) + " slow");
    }
    boolean allHealthy = all.stream().allMatch(c -> state(c) == HealthResult.State.HEALTHY);
    return new Node("connections", all.size(), "done", counted + (allHealthy ? " · all healthy" : ""));
  }

  private Node policiesNode(World w) {
    List<String> changed = new ArrayList<>();
    boolean edited = false;
    for (Env e : Env.values()) {
      PolicyService.EnvPolicy p = policies.get(e);
      if (!p.serving()) {
        changed.add(e.id());
      }
      edited |= p.explicit() != null && !p.explicit().isEmpty();
    }
    if (!changed.isEmpty()) {
      return new Node("policies", Env.values().length, "attention", Env.values().length + " environments · "
          + some(changed) + " changed outside GateRook");
    }
    if (!edited && w.connections().isEmpty()) {
      return new Node("policies", Env.values().length, "todo", "Safe defaults · not reviewed yet");
    }
    boolean bypass = w.endpoints().stream().anyMatch(e -> e.tools().bypass() > 0);
    return new Node("policies", Env.values().length, bypass ? "attention" : "done", Env.values().length
        + (bypass ? " environments · Bypass enabled on tools" : " environments · mutations need approval"));
  }

  private static Node endpointsNode(World w) {
    List<EndpointService.Summary> all = w.endpoints();
    if (all.isEmpty()) {
      return new Node("endpoints", 0, "todo", "None yet" + (w.connections().isEmpty()
          ? " · needs a connection first" : ""));
    }
    String counted = all.size() + (all.size() == 1 ? " endpoint" : " endpoints");
    List<String> blocked = all.stream().filter(e -> e.blockedReason() != null).map(EndpointService.Summary::slug)
        .toList();
    if (!blocked.isEmpty()) {
      return new Node("endpoints", all.size(), "attention", counted + " · " + some(blocked)
          + " changed outside GateRook");
    }
    int tokens = w.activeTokens();
    return new Node("endpoints", all.size(), "done", counted + " · " + (tokens == 0 ? "no active tokens"
        : tokens + (tokens == 1 ? " active token" : " active tokens")));
  }

  /**
   * Clients from the Clients page's cards: connected (a link, also one waiting for a restart), detected, needing a
   * restart. Without cards (the clients module didn't answer) the grants and named tokens say what is connected.
   */
  private static Node clientsNode(World w) {
    List<ClientViews.Card> cards = w.cards();
    List<String> connected = cards.stream().filter(c -> List.of("connected", "needs-restart", "error")
        .contains(c.status())).map(ClientViews.Card::name).toList();
    List<String> restart = cards.stream().filter(c -> "needs-restart".equals(c.status()))
        .map(ClientViews.Card::name).toList();
    List<String> failing = cards.stream().filter(c -> "error".equals(c.status())).map(ClientViews.Card::name)
        .toList();
    List<String> detected = cards.stream().filter(c -> "detected".equals(c.status())).map(ClientViews.Card::name)
        .toList();
    if (cards.isEmpty() && !w.clients().isEmpty()) {
      return new Node("clients", w.clients().size(), "done", w.clients().size() + " connected");
    }
    if (connected.isEmpty()) {
      String found = detected.isEmpty() ? "None detected" : detected.size() + " detected";
      if (w.endpoints().isEmpty()) {
        return new Node("clients", 0, "todo", found + " · needs an endpoint first");
      }
      if (w.namedTokens() > 0) {
        return new Node("clients", w.namedTokens(), "done", w.namedTokens() + (w.namedTokens() == 1
            ? " named token" : " named tokens") + (detected.isEmpty() ? "" : " · " + found));
      }
      return new Node("clients", 0, "todo", found + " · none connected");
    }
    String counted = connected.size() + " connected";
    if (!restart.isEmpty()) {
      return new Node("clients", connected.size(), "attention", counted + " · " + some(restart)
          + (restart.size() == 1 ? " needs restart" : " need restart"));
    }
    if (!failing.isEmpty()) {
      return new Node("clients", connected.size(), "attention", counted + " · " + some(failing)
          + (failing.size() == 1 ? " has a problem" : " have a problem"));
    }
    String rest = detected.isEmpty() ? "" : detected.size() == 1 ? " · " + detected.get(0)
        + " detected, not connected" : " · " + detected.size() + " detected";
    return new Node("clients", connected.size(), "done", counted + rest);
  }

  private static HealthResult.State state(ConnectionService.Summary c) {
    return c.health() == null ? null : c.health().state();
  }

  /** "billing-api", "billing-api and cache-redis", "3": names while they fit the node, else a count. */
  private static String some(List<String> names) {
    return switch (names.size()) {
      case 1 -> names.get(0);
      case 2 -> names.get(0) + " and " + names.get(1);
      default -> Integer.toString(names.size());
    };
  }

  private static void add(List<Step> steps, String id, boolean done, Map<String, Object> detail) {
    steps.add(new Step(id, done ? "done" : "todo", detail));
  }

  private static Map<String, Object> detail(Object... pairs) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      if (pairs[i + 1] != null) {
        out.put((String) pairs[i], pairs[i + 1] instanceof Instant t ? t.toString() : pairs[i + 1]);
      }
    }
    return out;
  }

  private boolean flag(String key) {
    return settings.find(key).map(s -> s.value().asBoolean(false)).orElse(false);
  }
}
