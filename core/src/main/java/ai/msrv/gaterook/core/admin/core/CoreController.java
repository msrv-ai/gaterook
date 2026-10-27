package ai.msrv.gaterook.core.admin.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.audit.AuditWriter;
import ai.msrv.gaterook.core.connections.ConnectionService;
import ai.msrv.gaterook.core.endpoints.EndpointService;
import ai.msrv.gaterook.core.platform.Bootstrap;
import ai.msrv.gaterook.core.platform.CoreBuild;
import ai.msrv.gaterook.core.platform.GatewayDatabase;
import ai.msrv.gaterook.core.platform.IntegrityAlerts;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.MasterKeyLock;
import ai.msrv.gaterook.core.platform.StartupFacts;
import ai.msrv.gaterook.core.secrets.MasterKeyService;
import ai.msrv.gaterook.core.secrets.MasterKeyStatus;
import ai.msrv.gaterook.core.settings.CoreInfo;
import ai.msrv.gaterook.core.settings.DatabaseBackupJob;
import ai.msrv.gaterook.core.tunnel.TunnelService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The frame of the app (ARCHITECTURE.md §6 "General"): {@code /api/health} for Electron after the ready line and for the
 * status screen (it answers in the locked mode too), {@code /api/core/info} for Settings › General › Core, the
 * sidebar's counters and the command palette's search.
 */
@RestController
@RegisterReflectionForBinding({CoreController.Health.class, CoreController.Ports.class, CoreController.Db.class,
    CoreController.MasterKey.class, CoreController.Tunnel.class, CoreController.Warning.class,
    CoreController.Counters.class, CoreController.SearchResult.class, CoreController.GoTo.class,
    CoreController.ConnectionHit.class, CoreController.EndpointHit.class, CoreInfo.Info.class,
    CoreInfo.PluginRuntime.class})
class CoreController {

  /**
   * {@code GET /api/health}.
   *
   * @param status {@code ok}, {@code locked} (the master key, §7.1) or {@code degraded} (the audit is catching up)
   */
  record Health(String status, String locked, String version, long uptimeMs, Ports ports, String runtime, Db db,
      MasterKey masterKey, Tunnel tunnel, List<Warning> warnings) {}

  record Ports(int local, @JsonProperty("public") int publicPort) {}

  record Db(String path, String sqlite, String journal, String schema, long sizeBytes) {}

  /** {@code protection}: {@code keychain}, {@code dpapi}, {@code libsecret}, {@code kwallet}, {@code off}, … */
  record MasterKey(String protection, List<String> problems, List<String> warnings) {}

  /** The tunnel: {@code off}, {@code starting}, {@code on} or {@code error}; the host while it publishes. */
  record Tunnel(String state, String host) {}

  /** A banner of the frame: {@code new-key-created}, {@code integrity}, {@code audit-catching-up}, {@code backup}. */
  record Warning(String kind, String text, String subject) {}

  record Counters(long pendingApprovals, String tunnel) {}

  /** The command palette: pages to go to, actions, connections and endpoints whose name matches. */
  record SearchResult(List<GoTo> goTo, List<GoTo> actions, List<ConnectionHit> connections,
      List<EndpointHit> endpoints) {}

  record GoTo(String id, String label, String route) {}

  record ConnectionHit(String name, String type, String env, String route) {}

  record EndpointHit(String slug, String env, String route) {}

  /** Pages of the kit (BLUEPRINT §2), each with words a person might type for it. */
  private static final List<String[]> PAGES = List.of(
      new String[] {"overview", "Overview", "/overview", "home dashboard getting started"},
      new String[] {"approvals", "Approvals", "/approvals", "requests decide approve deny pending"},
      new String[] {"connections", "Connections", "/connections", "databases hosts resources"},
      new String[] {"connectors", "Connectors", "/connectors", "plugins catalog templates mcp servers"},
      new String[] {"secrets", "Secrets", "/secrets", "passwords tokens master key vault"},
      new String[] {"endpoints", "Endpoints", "/endpoints", "mcp urls tools modes"},
      new String[] {"clients", "Clients", "/clients", "claude codex cursor antigravity tunnel"},
      new String[] {"policies", "Policies", "/policies/dev", "limits masking prod dev stage approvals"},
      new String[] {"playground", "Playground", "/playground", "try call tool"},
      new String[] {"activity", "Activity", "/activity", "audit log calls traces setup changes"},
      new String[] {"insights", "Insights", "/insights", "charts latency usage"},
      new String[] {"settings", "Settings", "/settings/general", "preferences port backup audit retention"});

  private static final List<String[]> ACTIONS = List.of(
      new String[] {"add-connection", "Add connection", "/connections/new", "new resource postgres mongodb redis"},
      new String[] {"create-endpoint", "Create endpoint", "/endpoints#create", "new mcp url"},
      new String[] {"test-all", "Test all connections", "/connections#test-all", "health check"},
      new String[] {"back-up-key", "Back up master key", "/secrets#master-key", "backup export key"},
      new String[] {"back-up-db", "Back up the database now", "/settings/backup", "backup copy database"},
      new String[] {"connect-client", "Connect a client", "/clients", "claude codex cursor"});

  private final Bootstrap bootstrap;
  private final LocalListener listener;
  private final StartupFacts facts;
  private final GatewayDatabase database;
  private final MasterKeyService masterKey;
  private final MasterKeyLock lock;
  private final IntegrityAlerts alerts;
  private final AuditWriter audit;
  private final DatabaseBackupJob backups;
  private final CoreInfo coreInfo;
  private final ApprovalService approvals;
  private final ConnectionService connections;
  private final EndpointService endpoints;
  private final TunnelService tunnel;
  private final Clock clock;

  CoreController(Bootstrap bootstrap, LocalListener listener, StartupFacts facts, GatewayDatabase database,
      MasterKeyService masterKey, MasterKeyLock lock, IntegrityAlerts alerts, AuditWriter audit,
      DatabaseBackupJob backups, CoreInfo coreInfo, ApprovalService approvals, ConnectionService connections,
      EndpointService endpoints, TunnelService tunnel, Clock clock) {
    this.bootstrap = bootstrap;
    this.listener = listener;
    this.facts = facts;
    this.database = database;
    this.masterKey = masterKey;
    this.lock = lock;
    this.alerts = alerts;
    this.audit = audit;
    this.backups = backups;
    this.coreInfo = coreInfo;
    this.approvals = approvals;
    this.connections = connections;
    this.endpoints = endpoints;
    this.tunnel = tunnel;
    this.clock = clock;
  }

  @GetMapping("/api/health")
  Health health() {
    MasterKeyStatus key = masterKey.status();
    List<Warning> warnings = new ArrayList<>();
    if (key.warnings().contains("new-key-created")) {
      warnings.add(new Warning("new-key-created", "GateRook created a new master key: none was found. Rows "
          + "signed with the old one show as changed outside GateRook until you review them.", null));
    }
    for (IntegrityAlerts.Alert a : alerts.current()) {
      warnings.add(new Warning("integrity", a.label() + " was changed outside GateRook (" + a.detail()
          + "). It stays off until you review it.", a.kind() + ":" + a.id()));
    }
    boolean auditOk = audit.available();
    if (!auditOk) {
      warnings.add(new Warning("audit-catching-up", "GateRook is catching up with its audit; calls wait until "
          + "it has.", null));
    }
    String backupError = lock.locked() ? null : backups.status().lastError();
    if (backupError != null) {
      warnings.add(new Warning("backup", "The last database backup failed: " + backupError, null));
    }
    String status = lock.locked() ? "locked" : auditOk ? "ok" : "degraded";
    GatewayDatabase.Info db = database.info();
    return new Health(status, lock.reason().map(Enum::name).orElse(null), CoreBuild.VERSION,
        Duration.between(facts.startedAt(), clock.instant()).toMillis(),
        new Ports(listener.port(), bootstrap.publicPort()), CoreBuild.runtime(),
        new Db(db.path(), db.sqlite(), db.journal(), db.schema(), db.sizeBytes()),
        new MasterKey(key.osProtection(), key.problems(), key.warnings()), new Tunnel(tunnel.summary(),
            tunnel.host().orElse(null)),
        List.copyOf(warnings));
  }

  @GetMapping("/api/core/info")
  CoreInfo.Info info() {
    return coreInfo.info();
  }

  @GetMapping("/api/nav/counters")
  Counters counters() {
    return new Counters(approvals.pendingTotal(), tunnel.summary());
  }

  @GetMapping("/api/search")
  SearchResult search(@RequestParam(name = "q", required = false) String q) {
    String query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
    List<GoTo> pages = matches(PAGES, query);
    List<GoTo> actions = matches(ACTIONS, query);
    List<ConnectionHit> conns = connections.list().stream()
        .filter(c -> query.isEmpty() || c.name().contains(query) || c.typeName().toLowerCase(Locale.ROOT)
            .contains(query) || (c.target() != null && c.target().toLowerCase(Locale.ROOT).contains(query)))
        .limit(8)
        .map(c -> new ConnectionHit(c.name(), c.type(), c.env().id(), "/connections/" + c.name()))
        .toList();
    List<EndpointHit> eps = endpoints.list().stream()
        .filter(e -> query.isEmpty() || e.slug().contains(query) || (e.description() != null
            && e.description().toLowerCase(Locale.ROOT).contains(query)))
        .limit(8)
        .map(e -> new EndpointHit(e.slug(), e.env().id(), "/endpoints/" + e.slug()))
        .toList();
    return new SearchResult(pages, actions, conns, eps);
  }

  private static List<GoTo> matches(List<String[]> entries, String query) {
    return entries.stream()
        .filter(e -> query.isEmpty() || e[1].toLowerCase(Locale.ROOT).contains(query) || e[3].contains(query))
        .map(e -> new GoTo(e[0], e[1], e[2]))
        .toList();
  }
}
