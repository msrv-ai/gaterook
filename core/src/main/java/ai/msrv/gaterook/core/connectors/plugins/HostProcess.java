package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.Messages;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * One running plugin host: the child process, the GPP/1 peer over its pipes, and what the core tracks about it (report
 * 16 §3, §6). A generation ({@code id@version}) is served by at most one host at a time; a restart of the same
 * generation gets a new {@code instance}. The in-flight count and the stuck count drive the supervisor's healing: a
 * call that doesn't come back is counted, and two stuck calls move the generation to a fresh process.
 */
final class HostProcess {

  enum State { STARTING, RUNNING, DRAINING, STOPPED }

  private final String generation;
  private final int instance;
  private final Process process;
  private final JsonRpcPeer peer;
  private final PluginLog log;
  private final Instant startedAt;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger stuck = new AtomicInteger();
  private volatile State state = State.STARTING;
  private volatile Messages.HelloResult hello;
  private volatile Messages.Loaded loaded;

  HostProcess(String generation, int instance, Process process, JsonRpcPeer peer, PluginLog log, Instant startedAt) {
    this.generation = generation;
    this.instance = instance;
    this.process = process;
    this.peer = peer;
    this.log = log;
    this.startedAt = startedAt;
  }

  String generation() {
    return generation;
  }

  int instance() {
    return instance;
  }

  long pid() {
    return process.pid();
  }

  Process process() {
    return process;
  }

  JsonRpcPeer peer() {
    return peer;
  }

  PluginLog log() {
    return log;
  }

  Instant startedAt() {
    return startedAt;
  }

  State state() {
    return state;
  }

  void state(State value) {
    state = value;
  }

  Messages.HelloResult hello() {
    return hello;
  }

  void hello(Messages.HelloResult value) {
    hello = value;
  }

  Messages.Loaded loaded() {
    return loaded;
  }

  void loaded(Messages.Loaded value) {
    loaded = value;
  }

  int inFlight() {
    return inFlight.get();
  }

  void enter() {
    inFlight.incrementAndGet();
  }

  void leave(boolean returned) {
    inFlight.decrementAndGet();
    if (returned) {
      stuck.set(0);
    }
  }

  /** A call passed its deadline and the host didn't answer: it is stuck. */
  int markStuck() {
    return stuck.incrementAndGet();
  }

  int stuck() {
    return stuck.get();
  }

  boolean alive() {
    return process.isAlive();
  }

  JsonRpcPeer.Call<JsonNode> request(String method, JsonNode params) {
    return peer.request(method, params);
  }

  void cancel(JsonRpcPeer.Call<?> call, String reason) {
    peer.cancel(call, reason);
  }

  /** Asks the host to shut down, then ends its whole process tree after {@code grace}. */
  void shutdown(Duration grace) {
    try {
      peer.request(Gpp.HOST_SHUTDOWN, shutdownParams(grace)).get(Duration.ofSeconds(3));
    } catch (Exception e) {
      // It may already be gone; the forced stop below is the fallback.
    }
    stopForcibly(grace);
  }

  /** Closes the pipe (the host sees EOF and exits) and, if it lingers, kills the whole tree. */
  void stopForcibly(Duration grace) {
    List<ProcessHandle> tree = process.descendants().toList();
    peer.close();
    try {
      if (process.waitFor(grace.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    process.destroyForcibly();
    tree.forEach(ProcessHandle::destroyForcibly);
  }

  private static JsonNode shutdownParams(Duration grace) {
    return GppJson.object().put("graceMs", Math.max(0, grace.toMillis()));
  }
}
