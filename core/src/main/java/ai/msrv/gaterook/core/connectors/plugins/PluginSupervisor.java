package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.core.connectors.ConnectorProcessStatus;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The life cycle of one plugin's host (ARCHITECTURE.md §7.5, report 16 §6): one host per generation, started on the
 * first call, stopped after its idle time, restarted with a backoff after a crash, given up on after five crashes in
 * ten minutes. A bulkhead of eight calls protects the host; a call waits up to two seconds for a permit. An update
 * runs side by side: the new generation's host is started and verified, the database switched, then the old one is
 * drained and stopped. Everything the UI shows ("Running · 54 MB", "Sleeping", "Restarting", "Failed") is
 * {@link #status()}.
 */
final class PluginSupervisor {

  /** Calls one host runs at once (report 16 §7). */
  static final int BULKHEAD = 8;
  static final Duration BULKHEAD_WAIT = Duration.ofSeconds(2);
  static final Duration DRAIN = Duration.ofSeconds(60);
  static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(3);
  static final Duration CRASH_WINDOW = Duration.ofMinutes(10);
  static final int CRASHES_TO_FAIL = 5;
  static final int STUCK_TO_REPLACE = 2;

  private static final Logger log = LoggerFactory.getLogger(PluginSupervisor.class);

  private final String pluginId;
  private final HostLauncher launcher;
  private final Clock clock;
  private final Runnable onStateChange;
  private final Semaphore bulkhead = new Semaphore(BULKHEAD, true);
  private final Deque<Instant> crashes = new ArrayDeque<>();
  private final Object lock = new Object();

  private volatile HostLauncher.Spec spec;
  private volatile String logLevel = "info";
  private volatile int idleStopMinutes = 15;
  private volatile boolean disabled;
  private volatile HostProcess current;
  private volatile ConnectorProcessStatus.State state = ConnectorProcessStatus.State.STOPPED;
  private volatile Instant since;
  private volatile String detail;
  private volatile Instant retryAt;
  private volatile Instant lastUsed = Instant.EPOCH;

  PluginSupervisor(String pluginId, HostLauncher launcher, Clock clock) {
    this(pluginId, launcher, clock, () -> { });
  }

  /** {@code onStateChange} runs after each change of the process state: the admin API's {@code plugin.state}. */
  PluginSupervisor(String pluginId, HostLauncher launcher, Clock clock, Runnable onStateChange) {
    this.pluginId = pluginId;
    this.launcher = launcher;
    this.clock = clock;
    this.onStateChange = onStateChange;
    this.since = clock.instant();
  }

  String pluginId() {
    return pluginId;
  }

  /** The spec a host is (re)started from; set at install and update, used by the next start. */
  void spec(HostLauncher.Spec value) {
    this.spec = value;
  }

  HostLauncher.Spec spec() {
    return spec;
  }

  void logLevel(String level) {
    this.logLevel = level == null ? "info" : level;
  }

  void idleStopMinutes(int minutes) {
    this.idleStopMinutes = Math.max(0, minutes);
  }

  // ── Running calls ───────────────────────────────────────────────────────────────────────────────────────────

  /** A permit on a running host; close it when the call ends. */
  Permit permit(Instant deadline) throws ConnectorException {
    long wait = Math.min(BULKHEAD_WAIT.toMillis(), Math.max(0, Duration.between(clock.instant(), deadline).toMillis()));
    boolean acquired;
    try {
      acquired = bulkhead.tryAcquire(wait, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ConnectorException.timeout("The call was cancelled.");
    }
    if (!acquired) {
      throw ConnectorException.unavailable(pluginId + " is busy (" + BULKHEAD + " calls running). Try again.", null);
    }
    try {
      HostProcess host = ensureRunning();
      host.enter();
      lastUsed = clock.instant();
      return new Permit(host);
    } catch (ConnectorException e) {
      bulkhead.release();
      throw e;
    }
  }

  final class Permit implements AutoCloseable {
    private final HostProcess host;
    private boolean returned;

    Permit(HostProcess host) {
      this.host = host;
    }

    HostProcess host() {
      return host;
    }

    /** The call came back (not stuck at the deadline). */
    void returned() {
      returned = true;
    }

    @Override
    public void close() {
      host.leave(returned);
      bulkhead.release();
      if (!returned) {
        onStuck(host);
      }
    }
  }

  // ── Starting and stopping ───────────────────────────────────────────────────────────────────────────────────

  private HostProcess ensureRunning() throws ConnectorException {
    HostProcess host = current;
    if (host != null && host.alive() && host.state() == HostProcess.State.RUNNING) {
      return host;
    }
    synchronized (lock) {
      host = current;
      if (host != null && host.alive() && host.state() == HostProcess.State.RUNNING) {
        return host;
      }
      if (disabled) {
        throw ConnectorException.denied(pluginId + " is disabled.");
      }
      if (spec == null) {
        throw ConnectorException.unavailable(pluginId + " isn't installed.", null);
      }
      if (state == ConnectorProcessStatus.State.FAILED && retryAt != null && clock.instant().isBefore(retryAt)) {
        throw ConnectorException.unavailable(pluginId + " keeps crashing. Restart it in Connectors.", null);
      }
      if (state == ConnectorProcessStatus.State.BACKOFF && retryAt != null && clock.instant().isBefore(retryAt)) {
        throw ConnectorException.unavailable(pluginId + " restarts in "
            + Duration.between(clock.instant(), retryAt).toSeconds() + " s.", null);
      }
      mark(ConnectorProcessStatus.State.STARTING, null);
      try {
        HostProcess started = launcher.start(spec, logLevel);
        wire(started);
        current = started;
        mark(ConnectorProcessStatus.State.RUNNING, "pid " + started.pid());
        return started;
      } catch (HostLauncher.HostStartException e) {
        recordFailure("couldn't start (" + e.phase() + "): " + e.getMessage());
        throw ConnectorException.unavailable(pluginId + " couldn't start: " + e.getMessage(), null);
      }
    }
  }

  /** The person's Restart: stop what runs, clear the backoff, start fresh. */
  void restart() throws ConnectorException {
    synchronized (lock) {
      stopCurrent(SHUTDOWN_GRACE, ConnectorProcessStatus.State.STOPPED, "restarting");
      crashes.clear();
      retryAt = null;
      disabled = false;
    }
    ensureRunning();
  }

  /** Disable: stop the host and refuse to start it until enabled. */
  void disable() {
    synchronized (lock) {
      disabled = true;
      stopCurrent(SHUTDOWN_GRACE, ConnectorProcessStatus.State.STOPPED, "disabled");
    }
  }

  void enable() {
    synchronized (lock) {
      disabled = false;
      crashes.clear();
      retryAt = null;
      mark(ConnectorProcessStatus.State.STOPPED, "enabled");
    }
  }

  /** Stop entirely (uninstall, shutdown): the host ends and nothing restarts it. */
  void stop() {
    synchronized (lock) {
      disabled = true;
      stopCurrent(SHUTDOWN_GRACE, ConnectorProcessStatus.State.STOPPED, "stopped");
    }
  }

  // ── Update (side by side) ───────────────────────────────────────────────────────────────────────────────────

  /** Starts and loads a new generation beside the current one; the caller verifies and then {@link #switchTo}. */
  HostProcess startAlternate(HostLauncher.Spec next) throws HostLauncher.HostStartException {
    return launcher.start(next, logLevel);
  }

  /** Switches to a prepared generation: it becomes current, the old one drains then stops. */
  void switchTo(HostProcess next, HostLauncher.Spec nextSpec) {
    HostProcess old;
    synchronized (lock) {
      wire(next);
      old = current;
      current = next;
      spec = nextSpec;
      crashes.clear();
      retryAt = null;
      disabled = false;
      mark(ConnectorProcessStatus.State.RUNNING, "pid " + next.pid());
    }
    if (old != null && old.alive()) {
      drainAndStop(old);
    }
  }

  /** A prepared generation the update didn't take: stop it. */
  void discard(HostProcess prepared) {
    prepared.stopForcibly(SHUTDOWN_GRACE);
  }

  // ── Sweeps and health ───────────────────────────────────────────────────────────────────────────────────────

  /** Stops the host after its idle time (prod: 5 min). */
  void idleSweep() {
    HostProcess host = current;
    if (host == null || host.state() != HostProcess.State.RUNNING || host.inFlight() > 0) {
      return;
    }
    int minutes = idleStopMinutes;
    if (minutes <= 0) {
      return;
    }
    if (Duration.between(lastUsed, clock.instant()).compareTo(Duration.ofMinutes(minutes)) >= 0) {
      synchronized (lock) {
        if (current == host && host.inFlight() == 0) {
          stopCurrent(SHUTDOWN_GRACE, ConnectorProcessStatus.State.STOPPED, "idle " + minutes + " min");
        }
      }
    }
  }

  /** Pings the running host; two misses, or a dead process, restart it on the next call. */
  void ping() {
    HostProcess host = current;
    if (host == null || host.state() != HostProcess.State.RUNNING) {
      return;
    }
    if (!host.alive()) {
      onExit(host, exitCode(host));
      return;
    }
    try {
      host.request(ai.msrv.gaterook.plugin.protocol.Gpp.HOST_PING,
          ai.msrv.gaterook.plugin.protocol.GppJson.object()).get(Duration.ofSeconds(5));
    } catch (Exception e) {
      if (host.markStuck() >= STUCK_TO_REPLACE) {
        log.info("Plugin {} host didn't answer two pings; replacing it", pluginId);
        replaceStuck(host);
      }
    }
  }

  /** The process ended: fail its calls, record the crash, set the backoff or Failed. */
  void onExit(HostProcess host, int code) {
    synchronized (lock) {
      if (current != host) {
        return;
      }
      current = null;
      host.log().note("Plugin host exited (code " + code + ")");
      recordFailure("stopped (exit " + code + ")");
    }
  }

  private void onStuck(HostProcess host) {
    if (host.markStuck() >= STUCK_TO_REPLACE) {
      replaceStuck(host);
    }
  }

  private void replaceStuck(HostProcess host) {
    synchronized (lock) {
      if (current == host) {
        current = null;
        mark(ConnectorProcessStatus.State.STOPPED, "replaced a stuck host");
      }
    }
    // Let its remaining calls finish briefly, then kill the whole tree.
    drainAndStop(host);
  }

  // ── Status ──────────────────────────────────────────────────────────────────────────────────────────────────

  Optional<ConnectorProcessStatus.ProcessInfo> status() {
    HostProcess host = current;
    Long pid = host != null && host.alive() ? host.pid() : null;
    Integer rss = pid == null ? null : rssMb(pid);
    return Optional.of(new ConnectorProcessStatus.ProcessInfo(state, pid, rss, since, detail));
  }

  boolean running() {
    HostProcess host = current;
    return host != null && host.alive() && host.state() == HostProcess.State.RUNNING;
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private void wire(HostProcess host) {
    host.process().onExit().thenAccept(p -> onExit(host, p.exitValue()));
  }

  private void drainAndStop(HostProcess host) {
    host.state(HostProcess.State.DRAINING);
    Thread.ofVirtual().name("plugin-drain-" + pluginId).start(() -> {
      Instant end = clock.instant().plus(DRAIN);
      while (host.inFlight() > 0 && clock.instant().isBefore(end)) {
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      host.shutdown(SHUTDOWN_GRACE);
      host.log().close();
    });
  }

  private void stopCurrent(Duration grace, ConnectorProcessStatus.State next, String why) {
    HostProcess host = current;
    current = null;
    mark(next, why);
    if (host != null && host.alive()) {
      Thread.ofVirtual().name("plugin-stop-" + pluginId).start(() -> {
        host.shutdown(grace);
        host.log().close();
      });
    }
  }

  private void recordFailure(String why) {
    Instant now = clock.instant();
    crashes.addLast(now);
    while (!crashes.isEmpty() && Duration.between(crashes.peekFirst(), now).compareTo(CRASH_WINDOW) > 0) {
      crashes.pollFirst();
    }
    if (crashes.size() >= CRASHES_TO_FAIL) {
      retryAt = now.plus(CRASH_WINDOW);
      mark(ConnectorProcessStatus.State.FAILED, why + "; keeps crashing");
    } else {
      long backoff = Math.min(60, 1L << Math.min(6, crashes.size() - 1));
      retryAt = now.plusSeconds(backoff);
      mark(ConnectorProcessStatus.State.BACKOFF, why + "; retry in " + backoff + " s");
    }
  }

  private void mark(ConnectorProcessStatus.State next, String why) {
    this.state = next;
    this.since = clock.instant();
    this.detail = why;
    try {
      onStateChange.run();
    } catch (RuntimeException e) {
      log.warn("Plugin {}: its state change wasn't published", pluginId, e);
    }
  }

  private static int exitCode(HostProcess host) {
    try {
      return host.process().exitValue();
    } catch (IllegalThreadStateException e) {
      return -1;
    }
  }

  /** Resident memory on Linux ({@code /proc/<pid>/status}); elsewhere the host's reported heap would be used. */
  private static Integer rssMb(long pid) {
    java.nio.file.Path status = java.nio.file.Path.of("/proc", String.valueOf(pid), "status");
    if (!java.nio.file.Files.isReadable(status)) {
      return null;
    }
    try {
      for (String line : java.nio.file.Files.readAllLines(status)) {
        if (line.startsWith("VmRSS:")) {
          String[] parts = line.split("\\s+");
          return (int) (Long.parseLong(parts[1]) / 1024);
        }
      }
    } catch (IOException | RuntimeException e) {
      return null;
    }
    return null;
  }
}
