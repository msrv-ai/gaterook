package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ConnectorManifest;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.SecretResolver;
import ai.msrv.gaterook.connectors.cloudauth.AwsSpec;
import ai.msrv.gaterook.connectors.cloudauth.CloudCredentials;
import ai.msrv.gaterook.connectors.cloudauth.CloudEndpoints;
import ai.msrv.gaterook.connectors.support.egress.GuardedEgress;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime;
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
  private final CloudCredentials cloud;
  private final Map<String, Supervisor> supervisors = new ConcurrentHashMap<>();
  private final Map<String, ServerLog> logs = new ConcurrentHashMap<>();
  /** The workers closing sessions, each with the connection whose session it closes. */
  private final Map<Thread, String> closingSessions = new ConcurrentHashMap<>();
  /** Connections whose folder and log move for a rename: none of their sessions starts until the move is settled. */
  private final java.util.Set<String> relocating = ConcurrentHashMap.newKeySet();
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
    this(coreEnv, osName, loginShellPath, clock, tick, type, new CloudCredentials(CloudEndpoints.PRODUCTION, clock));
  }

  /** @param cloud the core's cloud credentials: a server's Google token or AWS signature comes from them */
  public McpServers(Map<String, String> coreEnv, String osName, boolean loginShellPath, Clock clock, Duration tick,
      ConnectorManifest type, CloudCredentials cloud) {
    this.coreEnv = Map.copyOf(coreEnv);
    this.osName = osName;
    this.loginShellPath = loginShellPath;
    this.clock = clock;
    this.tick = tick;
    this.type = type;
    this.cloud = Objects.requireNonNull(cloud, "cloud");
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
      if (server.aws()) {
        warnings.addAll(profileWarnings(cfg));
      }
      return new Review(ServerConfig.HTTP, List.of(), null, null, server.url(), variables, List.of(), false, null,
          null, logFile(cfg.name()).toString(), warnings);
    }
    Launcher l = launcher();
    Launcher.Probe look = l.look();
    List<String> argv = server.command();
    String resolved = null;
    List<String> runs = argv;
    if (!argv.isEmpty()) {
      Optional<Path> p = l.resolve(argv.getFirst(), workDir);
      if (p.isPresent()) {
        resolved = p.get().toString();
        List<String> plain = new ArrayList<>(List.of(resolved));
        plain.addAll(argv.subList(1, argv.size()));
        try {
          runs = look.answered() ? l.argv(p.get(), argv.subList(1, argv.size()), workDir, server.offThisComputer())
              : plain;
        } catch (ConnectorException e) {
          // The sandbox couldn't be checked after all: the warning below says the server doesn't start until it is.
          look = l.look();
          runs = plain;
        }
      } else {
        warnings.add(argv.getFirst() + " isn't on the PATH GateRook gives servers: " + l.path());
      }
    }
    String node = argv.isEmpty() ? null : l.nodeVersion(argv.getFirst(), workDir).orElse(null);
    boolean notPinned = Commands.notPinned(argv);
    if (notPinned) {
      warnings.add("Not pinned: it can fetch new code on every start. Name an exact version (name@1.2.3).");
    }
    if (!look.answered()) {
      warnings.add("GateRook couldn't check that its sandbox works here (" + look.unknown() + "): it doesn't start "
          + "this server without it, and checks again at every start.");
    } else if (look.sandbox() == null) {
      warnings.add("Not sandboxed on this system: it can read everything your account can, including GateRook's "
          + "data.");
    }
    if (!variables.isEmpty()) {
      warnings.add("Its secrets reach it as environment variables: programs of your account can read them while it "
          + "runs.");
    }
    List<String> inherited = server.inheritEnv().stream().filter(Launcher.INHERITABLE::contains).toList();
    return new Review(ServerConfig.STDIO, runs, resolved, node, null, variables, inherited, notPinned,
        l.sandbox(look, server.offThisComputer()), workDir.toString(), logFile(cfg.name()).toString(), warnings);
  }

  @Override
  public Handshake probe(ConnectionConfig cfg, SecretResolver secrets, ExecContext ctx, Authorization authorization)
      throws ConnectorException {
    ServerConfig server = ServerConfig.of(cfg);
    // A new server: which revision it speaks is found out.
    Session.Request request = new Session.Request(cfg, server, server.toolPrefix(), null, secrets, ctx,
        authorization, credentials(cfg, server, secrets, ctx), null);
    try {
      Session s = Session.open(request, workDir(cfg.name()), launcher(), log(cfg.name()), pids(), now(), x -> {},
          x -> {});
      try {
        return handshake(request, s, s.tools);
      } finally {
        s.close(pids());
      }
    } finally {
      // A draft's credentials never serve a call: the stored server gets its own.
      cloud.evict(cfg.id());
    }
  }

  @Override
  public Handshake start(ConnectionConfig cfg, Snapshot snapshot, SecretResolver secrets, ExecContext ctx,
      boolean restart) throws ConnectorException {
    Session s = session(cfg, snapshot, secrets, ctx, restart, Supervisor.Why.PERSON);
    Session.Request request = new Session.Request(cfg, ServerConfig.of(cfg), snapshot.toolPrefix(),
        snapshot.npmIntegrity(), secrets, ctx, s.http == null ? null : s.http.authorization());
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
  public Relocation relocate(String connectionId, String from, String to) throws ConnectorException {
    Objects.requireNonNull(connectionId, "connectionId");
    if (!folderName(from) || !folderName(to)) {
      throw ConnectorException.badInput("A server's folder is named by its connection, so " + from + " can't become "
          + to + ".");
    }
    if (from.equals(to)) {
      return NO_RELOCATION;
    }
    if (closed) {
      throw ConnectorException.unavailable("GateRook is quitting.", null);
    }
    if (!relocating.add(connectionId)) {
      throw ConnectorException.unavailable(from + " is being renamed already.", null);
    }
    Moves moves = new Moves();
    try {
      stopForMove(connectionId, from);
      closeLog(from);
      closeLog(to);
      // Set aside under a name no connection can have (a dot first), in the same folder: a rename, never a copy.
      String aside = ".replaced-" + to + "-" + java.util.UUID.randomUUID();
      moves.replace(workDir(from), workDir(to), workDir(from).resolveSibling(aside));
      for (int i = 0; i < ServerLog.KEEP; i++) {
        String suffix = i == 0 ? "" : "." + i;
        Path log = logFile(from);
        moves.replace(log.resolveSibling(log.getFileName() + suffix),
            logFile(to).resolveSibling(logFile(to).getFileName() + suffix), log.resolveSibling(aside + ".log" + suffix));
      }
    } catch (ConnectorException | RuntimeException e) {
      moves.undo();
      relocating.remove(connectionId);
      throw e;
    }
    return new Relocation() {
      private final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();

      @Override
      public void commit() {
        if (settled.compareAndSet(false, true)) {
          moves.dropAside();
          relocating.remove(connectionId);
        }
      }

      @Override
      public void rollback() {
        if (settled.compareAndSet(false, true)) {
          closeLog(to);
          moves.undo();
          relocating.remove(connectionId);
        }
      }
    };
  }

  /** How long a rename waits for a server's process to stop before its folder moves. */
  static final Duration RELOCATE_STOP_WAIT = Duration.ofSeconds(10);

  private static final Relocation NO_RELOCATION = new Relocation() {
    @Override
    public void commit() {}

    @Override
    public void rollback() {}
  };

  /** Stops a server whose folder is about to move and waits until its process and its draining sessions are gone. */
  private void stopForMove(String connectionId, String name) throws ConnectorException {
    Supervisor s = supervisors.remove(connectionId);
    if (s != null) {
      s.stop("Stopped for a rename");
    }
    long deadline = System.nanoTime() + RELOCATE_STOP_WAIT.toNanos();
    while ((s != null && !s.idle()) || closingSessions.containsValue(connectionId)) {
      if (System.nanoTime() > deadline) {
        throw ConnectorException.unavailable(name + " didn't stop within " + RELOCATE_STOP_WAIT.toSeconds()
            + " s, so its folder wasn't moved. Try again.", null);
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw ConnectorException.unavailable(name + " was being stopped for a rename.", e);
      }
    }
  }

  private void requireNotRelocating(String connectionId, String name) throws ConnectorException {
    if (relocating.contains(connectionId)) {
      throw ConnectorException.unavailable(name + " is being renamed. Try again in a moment.", null);
    }
  }

  private void closeLog(String name) {
    ServerLog log = logs.remove(folder(name));
    if (log != null) {
      log.close();
    }
  }

  private static boolean folderName(String name) {
    return name != null && name.length() <= 64 && FOLDER.matcher(name).matches();
  }

  /**
   * The moves of one relocation, in order: undone in reverse when the rename isn't saved; what was set aside is deleted
   * once it is. Symbolic links are moved and deleted as links, never followed.
   */
  private static final class Moves {

    private final java.util.Deque<Path[]> done = new java.util.ArrayDeque<>();
    private final List<Path> aside = new ArrayList<>();

    /** Sets {@code to} aside when it exists, then moves {@code from} there when it exists. */
    void replace(Path from, Path to, Path asidePath) throws ConnectorException {
      if (java.nio.file.Files.exists(to, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        move(to, asidePath);
        aside.add(asidePath);
      }
      if (java.nio.file.Files.exists(from, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        move(from, to);
      }
    }

    private void move(Path from, Path to) throws ConnectorException {
      if (java.nio.file.Files.exists(to, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        throw ConnectorException.unavailable("Couldn't move " + from + ": " + to + " exists.", null);
      }
      try {
        java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.io.IOException e) {
        throw ConnectorException.unavailable("Couldn't move " + from + " to " + to + ": " + e.getMessage(), e);
      }
      done.push(new Path[] {from, to});
    }

    void undo() {
      while (!done.isEmpty()) {
        Path[] m = done.pop();
        try {
          java.nio.file.Files.move(m[1], m[0], java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
          LOG.log(System.Logger.Level.WARNING, "Couldn''t move {0} back to {1}: {2}", m[1], m[0], e.toString());
        }
      }
      aside.clear();
    }

    void dropAside() {
      for (Path p : aside) {
        deleteTree(p);
      }
      aside.clear();
      done.clear();
    }

    private static void deleteTree(Path root) {
      try {
        java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
          @Override
          public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes a)
              throws java.io.IOException {
            java.nio.file.Files.delete(file);
            return java.nio.file.FileVisitResult.CONTINUE;
          }

          @Override
          public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException e)
              throws java.io.IOException {
            if (e != null) {
              throw e;
            }
            java.nio.file.Files.delete(dir);
            return java.nio.file.FileVisitResult.CONTINUE;
          }
        });
      } catch (java.io.IOException e) {
        LOG.log(System.Logger.Level.WARNING, "Couldn''t delete {0}, which a renamed MCP server replaced: {1}", root,
            e.toString());
      }
    }
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
    // Supervisor.stop schedules the actual session close; wait for those workers too before releasing logs.
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!closingSessions.isEmpty()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        LOG.log(System.Logger.Level.WARNING, "MCP session shutdown did not finish within 10 seconds");
        break;
      }
      Thread worker = closingSessions.keySet().stream().findFirst().orElse(null);
      if (worker == null) break;
      try {
        worker.join(Duration.ofNanos(remaining));
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
    requireNotRelocating(cfg.id(), cfg.name());
    ServerConfig server = ServerConfig.of(cfg);
    // A call speaks the era the review recorded; a person's Test, Start or Restart finds out again.
    Session.Request request = new Session.Request(cfg, server, snapshot.toolPrefix(), snapshot.npmIntegrity(),
        secrets, ctx, server.oauth() ? authorization(cfg.id()) : null, credentials(cfg, server, secrets, ctx),
        why == Supervisor.Why.PERSON ? null : Session.Request.eraOf(snapshot.protocolVersion()));
    String config = configFingerprint(server, snapshot.npmIntegrity());
    String full = fingerprint(config, cfg, server, secrets, ctx);
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
    String id = request.cfg().id();
    requireNotRelocating(id, name);
    Session s = Session.open(request, workDir(name), launcher(), log(name), pids(), now(),
        x -> toolsChanged(x, request, owner), owner::exited, (what, uri) -> notice(id, name, what, uri));
    s.subscribe(upstream(name, subscriptions.getOrDefault(id, java.util.Set.of())));
    return s;
  }

  /** The resources of each stored server clients subscribe to now, as endpoints publish them. */
  private final Map<String, java.util.Set<String>> subscriptions = new ConcurrentHashMap<>();

  /** Clients subscribe to {@code uris} of a server now: its running session hears their updates, and its next one. */
  void subscriptions(String connectionId, String name, java.util.Set<String> uris) {
    if (uris.isEmpty()) {
      subscriptions.remove(connectionId);
    } else {
      subscriptions.put(connectionId, java.util.Set.copyOf(uris));
    }
    Session s = running(connectionId);
    if (s != null) {
      s.subscribe(upstream(name, uris));
    }
  }

  private static java.util.Set<String> upstream(String name, java.util.Set<String> exposed) {
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    for (String uri : exposed) {
      Federation.upstream(name, uri).ifPresent(out::add);
    }
    return out;
  }

  /** What a server said outside any call, for the host: a changed list, an updated resource (as published). */
  private void notice(String connectionId, String name, String what, String uri) {
    Host h = host;
    if (h == null || connectionId.startsWith("draft-")) {
      return;
    }
    try {
      if ("updated".equals(what)) {
        h.resourceUpdated(connectionId, Federation.exposed(name, uri));
      } else {
        h.listChanged(connectionId, what);
      }
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "The core didn't take a notification of an MCP server", e);
    }
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
    Thread worker = Thread.ofVirtual().name("mcp-stop-" + session.server.name()).unstarted(() -> {
      try {
        session.close(pids());
      } finally {
        closingSessions.remove(Thread.currentThread());
      }
    });
    closingSessions.put(worker, session.connectionId);
    worker.start();
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

  /** The core's sign-in of a stored server, or {@code null} when it keeps none (the start then says so). */
  private Authorization authorization(String connectionId) {
    Host h = host;
    return h == null ? null : h.authorization(connectionId).orElse(null);
  }

  /**
   * What opens the cloud credentials of a server that signs in with them (§7.6) when its session starts: a Google token
   * or SigV4 from the server's own credentials; {@code null} for any other server.
   */
  private Session.Credentials credentials(ConnectionConfig cfg, ServerConfig server, SecretResolver secrets,
      ExecContext ctx) {
    if (server.google()) {
      return () -> GoogleRequestCredentials.of(cloud, cfg, secrets, ctx::taint);
    }
    if (server.aws()) {
      return () -> AwsRequestCredentials.of(cloud, cfg, Session.checkUrl(server.url()), secrets, ctx::taint, clock);
    }
    return null;
  }

  /** What the review says about an AWS profile: it runs a program, it doesn't exist, it uses SSO. */
  private List<String> profileWarnings(ConnectionConfig cfg) {
    ServerConfig server = ServerConfig.of(cfg);
    try {
      java.net.URI url = Session.checkUrl(server.url());
      return AwsRequestCredentials.target(server.authSettings(), url)
          .map(t -> cloud.profileWarnings(AwsSpec.fromConfig(cfg.config(), AwsRequestCredentials.POINTER, t.region())))
          .orElse(List.of());
    } catch (ConnectorException | IllegalArgumentException e) {
      // The settings themselves are checked when the server is added.
      return List.of();
    }
  }

  private Supervisor supervisor(String connectionId) {
    return supervisors.computeIfAbsent(connectionId, id -> new Supervisor(id, this));
  }

  /**
   * The launcher, made once: the login shell and the sandbox probe take a moment, so never at start-up. Before its
   * first probe the host writes the bridge launcher of this run ({@link Host#prepareBridge}), so that neither a first
   * run, in which there is none yet, nor a launcher an earlier install left there decides whether servers are
   * sandboxed. The core's ports it closes to the servers are asked of the host for every start.
   */
  Launcher launcher() {
    Launcher l = launcher;
    if (l == null) {
      synchronized (this) {
        l = launcher;
        if (l == null) {
          Path home = home();
          McpServerRuntime.Bridge bridge;
          try {
            bridge = host.prepareBridge();
          } catch (RuntimeException e) {
            // Whether it was written isn't known: a launcher that isn't there is then no answer, never "unconfined".
            LOG.log(System.Logger.Level.WARNING, "The core couldn't prepare the bridge launcher", e);
            bridge = McpServerRuntime.Bridge.WRITTEN;
          }
          Path shim = home.resolve("bin").resolve(osName.toLowerCase(Locale.ROOT).startsWith("windows")
              ? "gw-bridge.cmd" : "gw-bridge");
          l = new Launcher(coreEnv, osName, loginShellPath, home, bridge == McpServerRuntime.Bridge.NONE ? null : shim,
              bridge == McpServerRuntime.Bridge.WRITTEN, this::corePorts);
          launcher = l;
        }
      }
    }
    return l;
  }

  /** The core's own listeners as the host knows them now; none before it attached. */
  private java.util.Set<Integer> corePorts() {
    Host h = host;
    return h == null ? java.util.Set.of() : h.corePorts();
  }

  private Handshake handshake(Session.Request request, Session s, List<ObjectNode> tools) {
    Host h = host;
    Snapshots.Mapped mapped = Snapshots.fromJson(request.toolPrefix(), tools, name -> h == null ? Optional.empty()
        : h.reservedProblem(name));
    ObjectNode info = Texts.JSON.createObjectNode();
    ObjectNode impl = s.init == null ? null : s.init.serverInfo();
    if (impl != null) {
      info.put("name", Texts.clean(Texts.str(impl.get("name"), String.valueOf(impl.get("name"))), 200, false));
      if (impl.path("title").isString()) {
        info.put("title", Texts.clean(impl.get("title").asString(), 200, false));
      }
      info.put("version", Texts.clean(Texts.str(impl.get("version"), String.valueOf(impl.get("version"))), 100,
          false));
    }
    List<String> notes = new ArrayList<>();
    notes.add(mapped.tools().size() + " tools, " + Snapshots.annotated(mapped.tools()) + " declare annotations");
    if (!mapped.rejected().isEmpty()) {
      notes.add(mapped.rejected().size() + " not published");
    }
    if (!request.server().http() && Commands.notPinned(request.server().command())) {
      notes.add("Not pinned: it can fetch new code on every start");
    }
    if (s.sse) {
      notes.add("Over the HTTP+SSE transport of 2024-11-05: the URL takes no Streamable HTTP");
    }
    List<String> offers = new ArrayList<>();
    if (s.init != null && s.init.has("resources")) {
      offers.add("resources");
    }
    if (s.init != null && s.init.has("prompts")) {
      offers.add("prompts");
    }
    if (!offers.isEmpty()) {
      notes.add("Offers " + String.join(" and ", offers) + " besides tools: agents get them once you let it share "
          + "them in its settings");
    }
    long apps = mapped.tools().stream().filter(t -> t.path("ui").isObject()).count();
    if (apps > 0) {
      notes.add(apps + (apps == 1 ? " tool shows an MCP App" : " tools show MCP Apps")
          + ": clients that render apps get them through GateRook");
    }
    String instructions = s.init == null || s.init.instructions() == null ? null
        : Texts.clean(s.init.instructions(), 4_000, true);
    return new Handshake(info, s.protocolVersion(), instructions, mapped.tools(), mapped.rejected(), s.startMillis,
        notes);
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
    // Another client, scope or callback is another sign-in: a session made with the old one drains.
    n.put("auth", server.auth());
    return Texts.sha256(n);
  }

  /**
   * The config fingerprint, the versions of the secrets it binds (a rotated secret starts a new process) and, over
   * HTTP, the egress guard the session's client was made under: a private range the policy no longer allows must not
   * live on in an open session.
   */
  private static String fingerprint(String config, ConnectionConfig cfg, ServerConfig server, SecretResolver secrets,
      ExecContext ctx) {
    StringBuilder out = new StringBuilder(config);
    if (server.http()) {
      out.append("|egress=").append(GuardedEgress.of(ctx).map(g -> g.spec().toString()).orElse("none"));
    }
    for (ServerConfig.Binding b : server.http() ? server.headers() : server.env()) {
      out.append('|').append(b.name()).append('=').append(version(secrets, b.secretField()));
    }
    // A new service account key or secret access key is new credentials: the session made with the old ones drains.
    for (String field : CLOUD_SECRETS) {
      if (cfg.secretFields().contains(field)) {
        out.append('|').append(field).append('=').append(version(secrets, field));
      }
    }
    return Texts.sha256(out.toString());
  }

  /** The secret fields of a server's cloud credentials. */
  private static final List<String> CLOUD_SECRETS = List.of(GoogleRequestCredentials.POINTER + "/serviceAccountKey",
      AwsRequestCredentials.POINTER + "/secretAccessKey");

  private static String version(SecretResolver secrets, String field) {
    if (field == null) {
      return "?";
    }
    try {
      SecretResolver.Meta meta = secrets.meta(field);
      return meta == null ? "?" : String.valueOf(meta.version());
    } catch (RuntimeException e) {
      return "?";
    }
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
