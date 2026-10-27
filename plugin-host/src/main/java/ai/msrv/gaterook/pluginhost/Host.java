package ai.msrv.gaterook.pluginhost;

import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One plugin host over a pair of streams: the process's stdin/stdout in {@link Main}, in-memory pipes in
 * {@link Train} and the host's own tests. The end is the same either way (report 16 §3.4): the input ends or
 * {@code host/shutdown} arrives → calls in flight get up to the grace period → plugins are closed → exit code 0.
 */
final class Host {

  /** How long calls in flight may finish once the core's end of the pipe is gone. */
  static final Duration EOF_DRAIN = Duration.ofSeconds(2);

  private final JsonRpcPeer peer;
  private final PluginRegistry registry;
  private final CompletableFuture<Integer> exit = new CompletableFuture<>();
  private final AtomicBoolean ending = new AtomicBoolean();

  Host(InputStream in, OutputStream out, Instant processStart) {
    this(in, out, new PluginRegistry(), processStart);
  }

  Host(InputStream in, OutputStream out, PluginRegistry registry, Instant processStart) {
    this.registry = registry;
    JsonRpcPeer[] self = new JsonRpcPeer[1];
    HostDispatcher dispatcher = new HostDispatcher(() -> self[0], registry, this::finish, processStart);
    this.peer = new JsonRpcPeer(in, out, dispatcher, JsonRpcPeer.Options.defaults()
        .name("plugin-host")
        .hostIds(true)
        .maxFrameBytes(Gpp.MAX_FRAME_BYTES)
        .strayLine(line -> System.err.println("plugin-host: ignored a line from the core that isn't GPP/1")));
    self[0] = peer;
  }

  /** Starts reading; the returned future completes with the exit code. */
  CompletableFuture<Integer> start() {
    HostLog.attach(peer);
    peer.closed().whenComplete((cause, error) -> {
      if (cause != null) {
        System.err.println("plugin-host: the protocol channel broke: " + cause);
      }
      Thread.ofVirtual().name("eof").start(() -> finish(EOF_DRAIN));
    });
    peer.start();
    return exit;
  }

  JsonRpcPeer peer() {
    return peer;
  }

  private void finish(Duration drain) {
    if (!ending.compareAndSet(false, true)) {
      return;
    }
    try {
      if (!peer.awaitIdle(drain)) {
        System.err.println("plugin-host: " + peer.inFlight() + " call(s) still running after " + drain.toMillis()
            + " ms; stopping anyway");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      registry.unloadAll();
    } catch (RuntimeException | LinkageError e) {
      System.err.println("plugin-host: closing the plugin failed: " + e);
    }
    HostLog.attach(null);
    peer.close();
    exit.complete(0);
  }
}
