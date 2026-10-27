package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime.ProcessView;
import ai.msrv.gaterook.connectors.support.mcp.McpServerRuntime.State;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The life of one server's process (ARCHITECTURE.md §7.5): {@code STOPPED → STARTING → RUNNING}; a call after the idle
 * period starts it again; a crash is {@code BACKOFF} for 1, 2, 4 … 60 s; five crashes (or failed starts) in ten
 * minutes are {@code FAILED} until a person starts it; a launch config that changed drains the old process (calls in
 * flight finish, new calls go to a new one); a process that misses two pings in a row is treated as hung and killed.
 *
 * <p>Calls that arrive while it starts share that start. The supervisor never holds its lock while it waits for a
 * process.
 */
final class Supervisor {

  static final int MAX_CRASHES = 5;
  static final Duration CRASH_WINDOW = Duration.ofMinutes(10);
  static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
  static final Duration PING_EVERY = Duration.ofSeconds(60);
  static final Duration PING_TIMEOUT = Duration.ofSeconds(10);
  static final int PING_MISSES = 2;
  /** Changes of the tool list a server may make in {@link #CRASH_WINDOW} before it is stopped as if it crashed. */
  static final int MAX_LIST_CHANGES = 10;

  /** How a start is wanted. */
  enum Why {
    /** An agent's call or the core's own start: refused while the server is in backoff or failed. */
    CALL,
    /** A person's Test, Start or Restart: tries again whatever happened before. */
    PERSON
  }

  /** What the runtime does for a supervisor. */
  interface Runtime {
    Session open(Session.Request request, Supervisor owner) throws ConnectorException;

    void close(Session session);

    void state(String connectionId, ProcessView view);

    void wanted(String connectionId);

    Instant now();
  }

  final String connectionId;
  private final Runtime runtime;
  private final Object lock = new Object();
  private Session session;
  private String fingerprint;
  private String configFingerprint;
  private CompletableFuture<Session> starting;
  private boolean stopAfterStart;
  private final List<Session> draining = new ArrayList<>();
  private final Deque<Instant> crashes = new ArrayDeque<>();
  private final Deque<Instant> listChanges = new ArrayDeque<>();
  private State state = State.STOPPED;
  private Instant since;
  private String detail;
  private Instant retryAt;
  private boolean startWithCore;
  private int pingMisses;
  private boolean pinging;

  Supervisor(String connectionId, Runtime runtime) {
    this.connectionId = connectionId;
    this.runtime = runtime;
  }

  /**
   * The running session for {@code request}, started (or restarted) when needed.
   *
   * @param fingerprint what the process was started from (command, variables, secret versions): another one drains it
   * @param restart     stop the running process first
   * @param budget      how long the caller may wait for a start in progress
   */
  Session acquire(Session.Request request, String fingerprint, String configFingerprint, boolean restart, Why why,
      Duration budget) throws ConnectorException {
    CompletableFuture<Session> mine = null;
    CompletableFuture<Session> wait;
    List<Session> retire = new ArrayList<>();
    synchronized (lock) {
      startWithCore = request.server().startWithCore();
      Instant now = runtime.now();
      if (why == Why.PERSON) {
        crashes.clear();
        retryAt = null;
      } else if (state == State.FAILED) {
        throw ConnectorException.unavailable(request.server().name() + " failed: " + detail + " Start it again in "
            + "Connectors › MCP servers.", null);
      } else if (state == State.BACKOFF && retryAt != null && now.isBefore(retryAt)) {
        throw ConnectorException.unavailable(request.server().name() + " crashed (" + detail + "); it can start again "
            + "in " + Math.max(1, Duration.between(now, retryAt).toSeconds()) + " s.", null);
      }
      if (session != null && !restart && session.alive() && fingerprint.equals(this.fingerprint)) {
        session.lastUsed = now;
        return session;
      }
      if (session != null) {
        retire.add(session);
        session = null;
      }
      if (starting != null) {
        // A start in progress is a fresh process already, also for a Restart that arrives meanwhile.
        wait = starting;
      } else {
        mine = new CompletableFuture<>();
        starting = mine;
        wait = mine;
        stopAfterStart = false;
        this.fingerprint = fingerprint;
        this.configFingerprint = configFingerprint;
        set(State.STARTING, null, null);
      }
    }
    for (Session old : retire) {
      drain(old, restart);
    }
    publish();
    if (mine != null) {
      return start(request, mine);
    }
    return await(wait, budget, request.server().name());
  }

  private Session start(Session.Request request, CompletableFuture<Session> mine) throws ConnectorException {
    Session s;
    try {
      s = runtime.open(request, this);
    } catch (ConnectorException e) {
      synchronized (lock) {
        if (starting == mine) {
          starting = null;
        }
        crashed("Didn't start: " + firstLine(e.getMessage()));
      }
      mine.completeExceptionally(e);
      publish();
      throw e;
    } catch (RuntimeException e) {
      ConnectorException wrapped = ConnectorException.unavailable(firstLine(String.valueOf(e.getMessage())), e);
      synchronized (lock) {
        if (starting == mine) {
          starting = null;
        }
        crashed("Didn't start: " + wrapped.getMessage());
      }
      mine.completeExceptionally(wrapped);
      publish();
      throw wrapped;
    }
    boolean stop;
    synchronized (lock) {
      if (starting == mine) {
        starting = null;
      }
      stop = stopAfterStart;
      if (!stop) {
        session = s;
        pingMisses = 0;
        set(State.RUNNING, pid(s), null);
      }
    }
    if (stop) {
      runtime.close(s);
      mine.completeExceptionally(ConnectorException.unavailable(request.server().name() + " was stopped while it "
          + "started.", null));
      publish();
      throw ConnectorException.unavailable(request.server().name() + " was stopped while it started.", null);
    }
    mine.complete(s);
    publish();
    return s;
  }

  private static Session await(CompletableFuture<Session> wait, Duration budget, String name)
      throws ConnectorException {
    try {
      return wait.get(Math.max(1, budget.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw ConnectorException.timeout(name + " is still starting.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("Waiting for " + name + " to start was cancelled.");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof ConnectorException ce) {
        throw new ConnectorException(ce.kind(), ce.getMessage(), ce);
      }
      throw ConnectorException.unavailable(String.valueOf(e.getCause().getMessage()), e.getCause());
    }
  }

  /** The config of the connection changed: a process started from another launch config is drained now. */
  void configured(String configFingerprint, boolean startWithCore) {
    Session old = null;
    synchronized (lock) {
      this.startWithCore = startWithCore;
      if (session != null && this.configFingerprint != null && !this.configFingerprint.equals(configFingerprint)) {
        old = session;
        session = null;
        set(State.STOPPED, null, "Settings changed: starts again on the next call");
      }
    }
    if (old != null) {
      drain(old, false);
      publish();
      if (startWithCore) {
        runtime.wanted(connectionId);
      }
    }
  }

  /** The process ended. Ours (stopped, drained) is nothing; any other end is a crash. */
  void exited(Session s) {
    boolean crash;
    synchronized (lock) {
      draining.remove(s);
      crash = session == s && !s.closing;
      if (crash) {
        session = null;
        Integer code = s.stdio != null && s.stdio.process() != null && !s.stdio.process().isAlive()
            ? s.stdio.process().exitValue() : null;
        String why = s.stdio != null && s.stdio.failure() != null ? s.stdio.failure()
            : "Exited" + (code == null ? "" : " with code " + code);
        List<String> tail = s.log.tail();
        crashed(why + (tail.isEmpty() ? "" : ": " + Texts.clean(tail.getLast(), 200, false)));
        s.log.note("Crashed: " + why);
      }
    }
    if (crash) {
      // Whatever the root left behind (npx → sh → node) is stopped with it.
      runtime.close(s);
      publish();
    }
  }

  /**
   * A running server listed other tools. Now and then is fine; more than {@link #MAX_LIST_CHANGES} times in
   * {@link #CRASH_WINDOW} (over its restarts too) it is stopped as if it crashed — backoff, then {@code FAILED} — since
   * every change the core hears is a new snapshot, connection version and Setup change.
   *
   * @return whether the core may hear this change ({@code false}: the server was stopped)
   */
  boolean listChanged(Session s) {
    boolean flood;
    synchronized (lock) {
      Instant now = runtime.now();
      listChanges.addLast(now);
      while (!listChanges.isEmpty() && Duration.between(listChanges.getFirst(), now).compareTo(CRASH_WINDOW) > 0) {
        listChanges.removeFirst();
      }
      flood = listChanges.size() > MAX_LIST_CHANGES;
      if (flood) {
        listChanges.clear();
        if (session == s) {
          session = null;
          crashed("Changed its list of tools more than " + MAX_LIST_CHANGES + " times in "
              + CRASH_WINDOW.toMinutes() + " minutes");
        }
      }
    }
    if (flood) {
      s.log.note("Changed its list of tools more than " + MAX_LIST_CHANGES + " times; stopped");
      s.closing = true;
      runtime.close(s);
      publish();
    }
    return !flood;
  }

  /** A transport error during a call over HTTP: the session is dropped, the next call opens another. */
  void broken(Session s, String why) {
    boolean drop;
    synchronized (lock) {
      drop = session == s;
      if (drop) {
        session = null;
        set(State.STOPPED, null, Texts.clean(why, 200, false));
      }
    }
    if (drop) {
      runtime.close(s);
      publish();
    }
  }

  /** A person's Stop, a removed connection, the core's shutdown: everything of this server stops now. */
  void stop(String why) {
    List<Session> close = new ArrayList<>();
    synchronized (lock) {
      if (session != null) {
        close.add(session);
        session = null;
      }
      close.addAll(draining);
      draining.clear();
      if (starting != null) {
        stopAfterStart = true;
      }
      retryAt = null;
      set(State.STOPPED, null, why);
    }
    for (Session s : close) {
      s.closing = true;
      runtime.close(s);
    }
    publish();
  }

  /** Runs every few seconds: idle stop, pings, draining, the descendants record and starts after a backoff. */
  void tick() {
    Instant now = runtime.now();
    Session s;
    List<Session> done = new ArrayList<>();
    boolean idle = false;
    boolean ping = false;
    boolean wantStart = false;
    synchronized (lock) {
      s = session;
      for (Session d : draining) {
        Duration limit = d.server.callTimeout().plusSeconds(5);
        if (d.inFlight.get() == 0 || Duration.between(d.lastUsed, now).compareTo(limit) > 0) {
          done.add(d);
        }
      }
      draining.removeAll(done);
      if (s != null && s.alive()) {
        Duration idleStop = s.server.idleStop();
        if (!s.server.startWithCore() && !idleStop.isZero() && s.inFlight.get() == 0
            && Duration.between(s.lastUsed, now).compareTo(idleStop) >= 0) {
          session = null;
          idle = true;
          set(State.STOPPED, null, "Stopped after " + idleStop.toMinutes() + " min idle");
        } else if (!pinging && s.inFlight.get() == 0 && Duration.between(s.lastPing, now).compareTo(PING_EVERY) >= 0) {
          pinging = true;
          ping = true;
          s.lastPing = now;
        }
      }
      if (session == null && starting == null && startWithCore && state == State.BACKOFF && retryAt != null
          && !now.isBefore(retryAt)) {
        wantStart = true;
      }
    }
    for (Session d : done) {
      d.closing = true;
      runtime.close(d);
    }
    if (s != null) {
      s.observe();
    }
    if (idle) {
      s.log.note("Stopped after " + s.server.idleStop().toMinutes() + " min idle");
      s.closing = true;
      runtime.close(s);
      publish();
    }
    if (ping) {
      // A hung server must not hold up the ticks of the others.
      Thread.ofVirtual().name("mcp-ping-" + connectionId).start(() -> ping(s));
    }
    if (wantStart) {
      runtime.wanted(connectionId);
    }
  }

  private void ping(Session s) {
    boolean ok;
    try {
      s.client.ping().block(PING_TIMEOUT);
      ok = true;
    } catch (RuntimeException e) {
      ok = false;
    }
    boolean hung = false;
    synchronized (lock) {
      pinging = false;
      if (session != s) {
        return;
      }
      pingMisses = ok ? 0 : pingMisses + 1;
      if (pingMisses >= PING_MISSES) {
        hung = true;
        session = null;
        crashed("Didn't answer ping " + PING_MISSES + " times in a row");
      }
    }
    if (hung) {
      s.log.note("Hung: didn't answer ping " + PING_MISSES + " times; stopped");
      s.closing = true;
      runtime.close(s);
      publish();
    }
  }

  /** The state as the UI shows it, with the root's resident memory where the system tells it. */
  ProcessView view() {
    synchronized (lock) {
      Long pid = session == null ? null : pid(session);
      return new ProcessView(state, pid, pid == null ? null : rssMb(pid), since, detail);
    }
  }

  /** Whether this server's process runs now (Test and health don't start it just to look). */
  Session running() {
    synchronized (lock) {
      return session != null && session.alive() ? session : null;
    }
  }

  boolean idle() {
    synchronized (lock) {
      return session == null && starting == null && draining.isEmpty();
    }
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  /** Under the lock: a crash or failed start, and what follows from the count in the window. */
  private void crashed(String why) {
    Instant now = runtime.now();
    crashes.addLast(now);
    while (!crashes.isEmpty() && Duration.between(crashes.getFirst(), now).compareTo(CRASH_WINDOW) > 0) {
      crashes.removeFirst();
    }
    if (crashes.size() >= MAX_CRASHES) {
      retryAt = null;
      set(State.FAILED, null, "Crashed " + crashes.size() + " times in " + CRASH_WINDOW.toMinutes() + " minutes. "
          + "Last: " + why + ".");
      return;
    }
    long seconds = Math.min(MAX_BACKOFF.toSeconds(), 1L << Math.min(6, crashes.size() - 1));
    retryAt = now.plusSeconds(seconds);
    set(State.BACKOFF, null, why + " · can start again in " + seconds + " s");
  }

  private void set(State next, Long pid, String why) {
    if (next != state || !java.util.Objects.equals(why, detail)) {
      since = runtime.now();
    }
    state = next;
    detail = why;
  }

  private void drain(Session old, boolean now) {
    if (now || old.inFlight.get() == 0) {
      old.closing = true;
      runtime.close(old);
      return;
    }
    synchronized (lock) {
      // Calls in flight finish on the old process; tick() closes it when they are done.
      old.closing = true;
      draining.add(old);
    }
  }

  private void publish() {
    runtime.state(connectionId, view());
  }

  private static Long pid(Session s) {
    ProcessHandle root = s.root();
    return root == null ? null : root.pid();
  }

  /** Linux: {@code VmRSS} of {@code /proc/<pid>/status}; elsewhere unknown. */
  static Integer rssMb(long pid) {
    Path status = Path.of("/proc", Long.toString(pid), "status");
    if (!Files.isReadable(status)) {
      return null;
    }
    try {
      for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
        if (line.startsWith("VmRSS:")) {
          String kb = line.substring(6).trim().split("\\s+")[0];
          return (int) Math.max(1, Long.parseLong(kb) / 1024);
        }
      }
    } catch (java.io.IOException | NumberFormatException e) {
      return null;
    }
    return null;
  }

  private static String firstLine(String text) {
    if (text == null) {
      return "unknown error";
    }
    int nl = text.indexOf('\n');
    return Texts.clean(nl < 0 ? text : text.substring(0, nl), 200, false);
  }
}
