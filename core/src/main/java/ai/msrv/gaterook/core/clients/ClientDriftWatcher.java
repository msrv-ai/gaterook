package ai.msrv.gaterook.core.clients;

import ai.msrv.gaterook.core.clients.internal.BridgeShimInstaller;
import ai.msrv.gaterook.core.clients.internal.ClientEnvironment;
import ai.msrv.gaterook.core.platform.EventBus;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the Clients page true while the app runs (ARCHITECTURE.md §7.2, report 03 §6 "Configuration drift"): at start it rewrites
 * the bridge shim (so configs written by an older install keep working), scans this computer, and then watches the
 * folders of the client configs. When an entry disappears ("Removed outside the app"), stops pointing at this
 * gateway's port ("Needs repair") or comes back, or a client's config appears (installed or first opened while the app
 * runs: the clients are scanned again), {@code clients.drift} tells the window. A slow minute-long rescan covers file
 * systems where watching doesn't work and folders that don't exist yet. None of it delays the ready line: it all runs
 * on its own thread.
 */
@Component
class ClientDriftWatcher implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ClientDriftWatcher.class);

  private final ClientService clients;
  private final ClientEnvironment env;
  private final EventBus events;
  private final Path devJar;
  private volatile Thread thread;
  private volatile WatchService watcher;
  private Map<String, String> last = new HashMap<>();
  private Map<String, Boolean> configs = new HashMap<>();

  ClientDriftWatcher(ClientService clients, ClientEnvironment env, EventBus events,
      @Value("${gateway.clients.bridge-jar:}") String devJar) {
    this.clients = clients;
    this.env = env;
    this.events = events;
    this.devJar = devJar == null || devJar.isBlank() ? null : Path.of(devJar);
  }

  @EventListener(ApplicationReadyEvent.class)
  synchronized void start() {
    if (thread != null) {
      return;
    }
    thread = Thread.ofVirtual().name("clients-drift").start(this::run);
  }

  /** Writes the shim now (start, and a development override of the bridge jar). */
  Path installShim() throws IOException {
    String javaHome = "native".equals(ai.msrv.gaterook.core.platform.CoreBuild.runtime()) ? null
        : System.getProperty("java.home");
    BridgeShimInstaller.Target target = BridgeShimInstaller.target(env, javaHome == null ? null : Path.of(javaHome),
        ai.msrv.gaterook.core.platform.BuildFlags.RELEASE ? null : devJar);
    if (!target.present()) {
      log.info("No gw-bridge in this build: {} says so when a client runs it", env.display(env.shim()));
    }
    return BridgeShimInstaller.install(env, target);
  }

  private void run() {
    try {
      // The ready line goes first: the warm start (exec → ready) shares this machine's CPUs with nothing of ours.
      Thread.sleep(1_500);
    } catch (InterruptedException e) {
      return;
    }
    try {
      installShim();
    } catch (IOException | RuntimeException e) {
      log.warn("Couldn't write the bridge launcher {}: {}", env.shim(), e.getMessage());
    }
    try {
      watcher = FileSystems.getDefault().newWatchService();
    } catch (IOException e) {
      log.warn("Watching the client configs isn't possible here: {}", e.getMessage());
      return;
    }
    Set<Path> watched = new HashSet<>();
    pass(watched, true);
    try {
      while (!Thread.currentThread().isInterrupted()) {
        WatchKey key = watcher.poll(60, TimeUnit.SECONDS);
        if (key != null) {
          key.pollEvents();
          key.reset();
          // A burst of writes (an editor's save, our own atomic move) settles first.
          Thread.sleep(300);
          WatchKey more;
          while ((more = watcher.poll()) != null) {
            more.pollEvents();
            more.reset();
          }
        }
        pass(watched, false);
      }
    } catch (InterruptedException | ClosedWatchServiceException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * One look: the first scans this computer and takes the statuses as they are, the next ones publish what changed;
   * then the folders to watch. A look that fails (a busy database, a file that can't be read just now) waits for the
   * next one instead of ending the watching for the rest of the run.
   */
  void pass(Set<Path> watched, boolean first) {
    try {
      if (first) {
        clients.scan();
        last = clients.statuses();
        configs = clients.configsPresent();
      } else {
        appeared();
        compare();
      }
      register(watched);
    } catch (RuntimeException e) {
      log.warn("Looking at the client configs failed, the next look tries again: {}", e.toString());
    }
  }

  private void register(Set<Path> watched) {
    for (Path file : clients.watchedFiles()) {
      Path dir = file.toAbsolutePath().getParent();
      if (dir != null && Files.isDirectory(dir) && watched.add(dir)) {
        try {
          dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
              StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
          watched.remove(dir);
        }
      }
    }
  }

  /**
   * A client's config (or its folder) that appeared or went since the last look — a client installed or first opened
   * while the app runs: the clients are looked for again, and the window hears it as {@code clients.drift} with the
   * client's card status.
   */
  void appeared() {
    Map<String, Boolean> now = clients.configsPresent();
    if (now.equals(configs)) {
      return;
    }
    Map<String, String> cards = new HashMap<>();
    for (ClientViews.Card c : clients.scan().clients()) {
      cards.put(c.client(), c.status());
    }
    for (Map.Entry<String, Boolean> e : now.entrySet()) {
      if (!e.getValue().equals(configs.get(e.getKey()))) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("client", e.getKey());
        data.put("status", cards.getOrDefault(e.getKey(), ""));
        events.publish("clients.drift", data);
      }
    }
    configs = now;
  }

  /** Publishes the links whose status changed since the last look. */
  void compare() {
    Map<String, String> now = clients.statuses();
    for (Map.Entry<String, String> e : now.entrySet()) {
      if (!e.getValue().equals(last.get(e.getKey()))) {
        String[] parts = e.getValue().split(" ", 2);
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("link", e.getKey());
        data.put("client", parts[0]);
        data.put("status", parts.length > 1 ? parts[1] : "");
        events.publish("clients.drift", data);
      }
    }
    last = now;
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    Thread t = thread;
    thread = null;
    WatchService w = watcher;
    if (w != null) {
      try {
        w.close();
      } catch (IOException ignored) {
        // Closing anyway.
      }
    }
    if (t != null) {
      t.interrupt();
      t.join(2_000);
    }
  }
}
