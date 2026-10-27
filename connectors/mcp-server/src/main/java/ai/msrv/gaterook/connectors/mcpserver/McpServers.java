package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The runtime of External MCP servers (ARCHITECTURE.md §7.5): one {@link Supervisor} per connection, the launcher
 * (PATH, sandbox), the pid file and a ticker on a virtual thread for idle stops, pings and restarts. Nothing here runs
 * at construction or at {@link #attach}: the login shell's PATH, the sandbox probe and the reaping of a previous run's
 * orphans happen on a background thread, so the core's warm start doesn't wait for them.
 */
public final class McpServers implements McpServerRuntime, Supervisor.Runtime {

  private static final System.Logger LOG = System.getLogger(McpServers.class.getName());
  /** A connection name is a safe folder name; anything else (a draft) gets a folder named by its hash. */
  private static final Pattern FOLDER = Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");

  private final Map<String, String> coreEnv;
  private final String osName;
  private final boolean loginShellPath;
  private final Clock clock;
  private final Duration tick;
  private final ConnectorManifest type;
  private final Map<String, Supervisor> supervisors = new ConcurrentHashMap<>();
  private final Map<String, ServerLog> logs = new ConcurrentHashMap<>();
  private volatile Host host;
  private volatile PidFile pids;
  private volatile Launcher launcher;
  private volatile Thread ticker;
  private volatile boolean closed;

  /**
   * @param coreEnv        the core's environment (servers get a minimal part of it)
   * @param loginShellPath ask the login shell for the PATH a terminal has (off in tests)
   * @param tick           how often idle stops, pings and restarts are looked at
   * @param type           the {@code mcp-server} type's manifest (name, icon, config schema)
   */
  public McpServers(Map<String, String> coreEnv, String osName, boolean loginShellPath, Clock clock, Duration tick,
      ConnectorManifest type) {
    this.coreEnv = Map.copyOf(coreEnv);
    this.osName = osName;
    this.loginShellPath = loginShellPath;
    this.clock = clock;
    this.tick = tick;
    this.type = type;
  }

  @Override
  public synchronized void attach(Host host) {
    if (this.host != null) {
      throw new IllegalStateException("The MCP server runtime is attached already");
    }
    this.host = Objects.requireNonNull(host, "host");
    this.pids = new PidFile(host.home().resolve("run").resolve("mcp-pids.json"));
    Thread.ofVirtual().name("mcp-servers-init").start(() -> {
      int reaped = pids.reapOrphans();
      if (reaped > 0) {
        LOG.log(System.Logger.Level.INFO, "Stopped {0} MCP server process(es) a previous run left behind", reaped);
      }
      launcher();
    });
    ticker = Thread.ofVirtual().name("mcp-servers-tick").start(this::tickLoop);
  }

  // ── McpServerRuntime ────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Connector connector(ConnectionConfig cfg, Snapshot snapshot) {
    ServerConfig server = ServerConfig.of(cfg);
    supervisor(cfg.id()).configured(configFingerprint(server, snapshot.npmIntegrity()), server.startWithCore());
    return new ServerConnector(this, cfg, snapshot, type);
  }

  @Override
  public Review review(ConnectionConfig cfg) {
    ServerConfig server = ServerConfig.of(cfg);
    Path workDir = workDir(cfg.name());
    List<String> warnings = new ArrayList<>();
    List<String> variables = new ArrayList<>();
    (server.http() ? server.headers() : server.env()).forEach(b -> variables.add(b.name()));
    if (server.http()) {
      if (server.url() != null && server.url().toLowerCase(Locale.ROOT).startsWith("http://")) {
        warnings.add("Plain HTTP to this computer: any program here can answer on that port.");
      }
      return new Review(ServerConfig.HTTP, List.of(), null, null, server.url(), variables, List.of(), false, null,
          null, logFile(cfg.name()).toString(), warnings);
    }
    Launcher l = launcher();
    List<String> argv = server.command();
    String resolved = null;
    List<String> runs = argv;
    if (!argv.isEmpty()) {
      Optional<Path> p = l.resolve(argv.getFirst(), workDir);
      if (p.isPresent()) {
        resolved = p.get().toString();
        runs = l.argv(p.get(), argv.subList(1, argv.size()), workDir);
      } else {
        warnings.add(argv.getFirst() + " isn't on the PATH GateRook gives servers: " + l.path());
      }
    }
    String node = argv.isEmpty() ? null : l.nodeVersion(argv.getFirst(), workDir).orElse(null);
    boolean notPinned = Commands.notPinned(argv);
    if (notPinned) {
      warnings.add("Not pinned: it can fetch new code on every start. Name an exact version (name@1.2.3).");
    }
    if (l.sandbox() == null) {
      warnings.add("Not sandboxed on this system: it can read everything your account can, including GateRook's "
          + "data.");
    }
    if (!variables.isEmpty()) {
      warnings.add("Its secrets reach it as environment variables: programs of your account can read them while it "
          + "runs.");
    }
    List<String> inherited = server.inheritEnv().stream().filter(Launcher.INHERITABLE::contains).toList();
    return new Review(ServerConfig.STDIO, runs, resolved, node, null, variables, inherited, notPinned, l.sandbox(),
        workDir.toString(), logFile(cfg.name()).toString(), warnings);
  }

  @Override
  public Handshake probe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx) throws ConnectorException {
    ServerConfig server = ServerConfig.of(cfg);
    Session.Request request = new Session.Request(cfg, server, server.toolPrefix(), null, secrets, ctx);
    Session s = Session.open(request, workDir(cfg.name()), launcher(), log(cfg.name()), pids(), now(), x -> {},
        x -> {});
    try {
      return handshake(request, s, s.tools);
    } finally {
      s.close(pids());
    }
  }

  @Override
  public Handshake start(ConnectionConfig cfg, Snapshot snapshot, SecretResolver secrets, ExecContext ctx,
      boolean restart) throws ConnectorException {
    Session s = session(cfg, snapshot, secrets, ctx, restart, Supervisor.Why.PERSON);
    Session.Request request = new Session.Request(cfg, ServerConfig.of(cfg), snapshot.toolPrefix(),
        snapshot.npmIntegrity(), secrets, ctx);
    if (!s.announced.compareAndSet(false, true)) {
      // It ran already: Test and Start list its tools again.
      s.listTools(Session.min(Duration.ofSeconds(30), ctx.remaining()));
    }
    Handshake h = handshake(request, s, s.tools);
    announce(cfg.id(), h);
    return h;
  }

  @Override
  public void warm(ConnectionConfig cfg, Snapshot snapshot, SecretResolver secrets, ExecContext ctx)
      throws ConnectorException {
    session(cfg, snapshot, secrets, ctx, false, Supervisor.Why.CALL);
  }

  @Override
  public void stop(String connectionId) {
    Supervisor s = supervisors.get(connectionId);
    if (s != null) {
      s.stop("Stopped");
    }
  }

  @Override
  public Optional<ProcessView> process(String connectionId) {
    Supervisor s = supervisors.get(connectionId);
    return s == null ? Optional.empty() : Optional.of(s.view());
  }

  @Override
  public NpmInstall install(String serverName, NpmPackage pkg, Duration timeout) throws ConnectorException {
    Path workDir = workDir(serverName);
    Launcher l = launcher();
    return new NpmInstaller(l).install(workDir, pkg, timeout, l.installEnvironment(workDir));
  }

  @Override
  public void close() {
    closed = true;
    Thread t = ticker;
    if (t != null) {
      t.interrupt();
    }
    List<Thread> stopping = new ArrayList<>();
    for (Supervisor s : supervisors.values()) {
      stopping.add(Thread.ofVirtual().start(() -> s.stop("GateRook quit")));
    }
    for (Thread th : stopping) {
      try {
        th.join(Duration.ofSeconds(8));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    logs.values().forEach(ServerLog::close);
  }

  // ── For the connectors ──────────────────────────────────────────────────────────────────────────────────────

  /** The running session of a stored connection for a call, started when needed. */
  Session session(ConnectionConfig cfg, Snapshot snapshot, SecretResolver secrets, ExecContext ctx, boolean restart,
      Supervisor.Why why) throws ConnectorException {
    if (closed) {
      throw ConnectorException.unavailable("GateRook is quitting.", null);
    }
    ServerConfig server = ServerConfig.of(cfg);
    Session.Request request = new Session.Request(cfg, server, snapshot.toolPrefix(), snapshot.npmIntegrity(),
        secrets, ctx);
    String config = configFingerprint(server, snapshot.npmIntegrity());
    String full = fingerprint(config, server, secrets, ctx);
    Duration budget = Session.min(server.startTimeout().plusSeconds(5), ctx.remaining());
    Session s = supervisor(cfg.id()).acquire(request, full, config, restart, why, budget);
    if (why == Supervisor.Why.CALL && s.announced.compareAndSet(false, true)) {
      announce(cfg.id(), handshake(request, s, s.tools));
    }
    return s;
  }

  /** The session that runs now, or {@code null}: health checks never start a server. */
  Session running(String connectionId) {
    Supervisor s = supervisors.get(connectionId);
    return s == null ? null : s.running();
  }

  void broken(Session s, String why) {
    Supervisor sup = supervisors.get(s.connectionId);
    if (sup != null) {
      sup.broken(s, why);
    }
  }

  /** A removed connection: its process stops and nothing of it stays in memory. */
  void forget(String connectionId) {
    Supervisor s = supervisors.remove(connectionId);
    if (s != null) {
      s.stop("Removed");
    }
  }

  // ── Supervisor.Runtime ──────────────────────────────────────────────────────────────────────────────────────

  @Override
  public Session open(Session.Request request, Supervisor owner) throws ConnectorException {
    String name = request.cfg().name();
    return Session.open(request, workDir(name), launcher(), log(name), pids(), now(),
        s -> toolsChanged(s, request, owner), owner::exited);
  }

  /**
   * The server said its tools changed and the client listed them again (on the transport's thread). The same list is
   * no change. Each list the host hears is a new snapshot, connection version and Setup change, and a server may say
   * {@code list_changed} as often as it likes: so the supervisor counts the changes (too many and the server is
   * stopped as if it crashed), and the host hears the newest list at most once a tick, on a thread of its own (it
   * writes to the database).
   */
  private void toolsChanged(Session s, Session.Request request, Supervisor owner) {
    synchronized (s) {
      String hash = Session.listHash(s.tools);
      if (hash.equals(s.listHash)) {
        return;
      }
      s.listHash = hash;
    }
    if (owner.listChanged(s)) {
      s.toolsDirty = true;
      announceSoon(s, request, owner);
    }
  }

  private void announceSoon(Session s, Session.Request request, Supervisor owner) {
    if (!s.announcing.compareAndSet(false, true)) {
      return;
    }
    Thread.ofVirtual().name("mcp-tools-" + request.cfg().name()).start(() -> {
      try {
        while (s.toolsDirty && !s.closing && !closed) {
          s.toolsDirty = false;
          announce(owner.connectionId, handshake(request, s, s.tools));
          Thread.sleep(tick);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        s.announcing.set(false);
      }
      // A change that came after the last look, before the flag was down.
      if (s.toolsDirty && !s.closing && !closed) {
        announceSoon(s, request, owner);
      }
    });
  }

  @Override
  public void close(Session session) {
    Thread.ofVirtual().name("mcp-stop-" + session.server.name()).start(() -> session.close(pids()));
  }

  @Override
  public void state(String connectionId, ProcessView view) {
    Host h = host;
    if (h != null && !connectionId.startsWith("draft-")) {
      try {
        h.state(connectionId, view);
      } catch (RuntimeException e) {
        LOG.log(System.Logger.Level.WARNING, "The core didn't take the state of an MCP server", e);
      }
    }
  }

  @Override
  public void wanted(String connectionId) {
    Host h = host;
    if (h != null && !closed) {
      Thread.ofVirtual().name("mcp-start-" + connectionId).start(() -> {
        try {
          h.wanted(connectionId);
        } catch (RuntimeException e) {
          LOG.log(System.Logger.Level.WARNING, "An MCP server set to start with the core didn't start", e);
        }
      });
    }
  }

  @Override
  public Instant now() {
    return clock.instant();
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private Supervisor supervisor(String connectionId) {
    return supervisors.computeIfAbsent(connectionId, id -> new Supervisor(id, this));
  }

  /** The launcher, made once: the login shell and the sandbox probe take a moment, so never at start-up. */
  Launcher launcher() {
    Launcher l = launcher;
    if (l == null) {
      synchronized (this) {
        l = launcher;
        if (l == null) {
          Path shim = home().resolve("bin").resolve(osName.toLowerCase(Locale.ROOT).startsWith("windows")
              ? "gw-bridge.cmd" : "gw-bridge");
          l = new Launcher(coreEnv, osName, loginShellPath, shim);
          launcher = l;
        }
      }
    }
    return l;
  }

  private Handshake handshake(Session.Request request, Session s, List<McpSchema.Tool> tools) {
    Host h = host;
    Snapshots.Mapped mapped = Snapshots.of(request.toolPrefix(), tools, name -> h == null ? Optional.empty()
        : h.reservedProblem(name));
    ObjectNode info = Texts.JSON.createObjectNode();
    McpSchema.Implementation impl = s.init == null ? null : s.init.serverInfo();
    if (impl != null) {
      info.put("name", Texts.clean(String.valueOf(impl.name()), 200, false));
      if (impl.title() != null) {
        info.put("title", Texts.clean(impl.title(), 200, false));
      }
      info.put("version", Texts.clean(String.valueOf(impl.version()), 100, false));
    }
    List<String> notes = new ArrayList<>();
    notes.add(mapped.tools().size() + " tools, " + Snapshots.annotated(mapped.tools()) + " declare annotations");
    if (!mapped.rejected().isEmpty()) {
      notes.add(mapped.rejected().size() + " not published");
    }
    if (!request.server().http() && Commands.notPinned(request.server().command())) {
      notes.add("Not pinned: it can fetch new code on every start");
    }
    String instructions = s.init == null || s.init.instructions() == null ? null
        : Texts.clean(s.init.instructions(), 4_000, true);
    return new Handshake(info, s.init == null ? null : s.init.protocolVersion(), instructions, mapped.tools(),
        mapped.rejected(), s.startMillis, notes);
  }

  private void announce(String connectionId, Handshake h) {
    Host host = this.host;
    if (host == null || connectionId.startsWith("draft-")) {
      return;
    }
    try {
      host.handshake(connectionId, h);
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "The core didn't take the tools an MCP server listed", e);
    }
  }

  /** What a process was started from, without secret versions: a change drains a running process at once. */
  static String configFingerprint(ServerConfig server, String npmIntegrity) {
    ObjectNode n = Texts.JSON.createObjectNode();
    n.put("transport", server.transport());
    ArrayNode command = n.putArray("command");
    server.command().forEach(command::add);
    n.put("url", server.url());
    ArrayNode bindings = n.putArray("bindings");
    for (ServerConfig.Binding b : server.http() ? server.headers() : server.env()) {
      bindings.addObject().put("name", b.name()).put("field", b.secretField());
    }
    ArrayNode inherit = n.putArray("inherit");
    server.inheritEnv().forEach(inherit::add);
    n.put("npmIntegrity", npmIntegrity);
    return Texts.sha256(n);
  }

  /**
   * The config fingerprint, the versions of the secrets it binds (a rotated secret starts a new process) and, over
   * HTTP, the egress guard the session's client was made under: a private range the policy no longer allows must not
   * live on in an open session.
   */
  private static String fingerprint(String config, ServerConfig server, SecretResolver secrets, ExecContext ctx) {
    StringBuilder out = new StringBuilder(config);
    if (server.http()) {
      out.append("|egress=").append(GuardedEgress.of(ctx).map(g -> g.spec().toString()).orElse("none"));
    }
    for (ServerConfig.Binding b : server.http() ? server.headers() : server.env()) {
      String version = "?";
      if (b.secretField() != null) {
        try {
          SecretResolver.Meta meta = secrets.meta(b.secretField());
          version = meta == null ? "?" : String.valueOf(meta.version());
        } catch (RuntimeException e) {
          version = "?";
        }
      }
      out.append('|').append(b.name()).append('=').append(version);
    }
    return Texts.sha256(out.toString());
  }

  Path workDir(String name) {
    return home().resolve("mcp").resolve(folder(name));
  }

  private Path logFile(String name) {
    return home().resolve("logs").resolve("mcp").resolve(folder(name) + ".log");
  }

  private ServerLog log(String name) {
    return logs.computeIfAbsent(folder(name), n -> new ServerLog(logFile(name), clock));
  }

  private static String folder(String name) {
    if (name != null && name.length() <= 64 && FOLDER.matcher(name).matches()) {
      return name;
    }
    return "draft-" + Texts.sha256(String.valueOf(name)).substring(0, 12);
  }

  private Path home() {
    Host h = host;
    if (h == null) {
      throw new IllegalStateException("The MCP server runtime isn't attached to the core");
    }
    return h.home();
  }

  private PidFile pids() {
    PidFile p = pids;
    if (p == null) {
      throw new IllegalStateException("The MCP server runtime isn't attached to the core");
    }
    return p;
  }

  private void tickLoop() {
    while (!closed) {
      try {
        Thread.sleep(tick);
      } catch (InterruptedException e) {
        return;
      }
      for (Supervisor s : supervisors.values()) {
        try {
          s.tick();
        } catch (RuntimeException e) {
          LOG.log(System.Logger.Level.WARNING, "MCP server supervision failed for one tick", e);
        }
      }
    }
  }
}
