package com.gaterook.pluginhost;

import com.gaterook.connector.api.ApiVersion;
import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ExecContext;
import com.gaterook.plugin.protocol.Gpp;
import com.gaterook.plugin.protocol.GppJson;
import com.gaterook.plugin.protocol.JsonRpcPeer;
import com.gaterook.plugin.protocol.Messages;
import com.gaterook.plugin.protocol.RpcException;
import com.gaterook.plugin.protocol.Semver;
import com.gaterook.plugin.protocol.host.ConnectorEndpoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the host answers (report 16 §4.3): {@code host/*} and {@code plugin/*} here, {@code connector/*} through the
 * shared {@link ConnectorEndpoint}. {@code $/cancel} is the peer's own (it interrupts the call's virtual thread). At
 * most {@link #MAX_CALLS} connector calls run at once; one more is {@code -32012} HostBusy, the core's bulkhead
 * normally keeps far below that.
 */
final class HostDispatcher implements JsonRpcPeer.Handler {

  static final int MAX_CALLS = 32;
  static final List<String> CAPABILITIES = List.of("cancel", "secrets.callback", "egress", "egress.tcp");

  /** What {@code host/shutdown} and stdin EOF end with. */
  interface Exit {
    void exit(Duration drain);
  }

  private final Supplier<JsonRpcPeer> peer;
  private final PluginRegistry registry;
  private final ConnectorEndpoint endpoint;
  private final Exit exit;
  private final Instant processStart;
  private final Semaphore calls = new Semaphore(MAX_CALLS);
  private volatile boolean helloDone;

  HostDispatcher(Supplier<JsonRpcPeer> peer, PluginRegistry registry, Exit exit, Instant processStart) {
    this.peer = peer;
    this.registry = registry;
    this.exit = exit;
    this.processStart = processStart;
    ConnectorEndpoint[] self = new ConnectorEndpoint[1];
    this.endpoint = new ConnectorEndpoint(peer, registry, new ConnectorEndpoint.EgressFactory() {
      @Override
      public ExecContext.Egress egress(Messages.EgressGrant grant, ConnectionConfig cfg) {
        return egress(grant, cfg, TcpGate.MAX_CONNECT);
      }

      @Override
      public ExecContext.Egress egress(Messages.EgressGrant grant, ConnectionConfig cfg, Duration timeout) {
        return callEgress(grant, cfg, timeout, self[0]);
      }
    }, Clock.systemUTC(), HostLog::level);
    self[0] = endpoint;
  }

  /**
   * HTTP for every plugin; where the plugin declared TCP destinations, also the call's grant at the TCP gate, whose
   * change for a connection makes the plugin drop what it pooled for it.
   */
  private static ExecContext.Egress callEgress(Messages.EgressGrant grant, ConnectionConfig cfg, Duration timeout,
      ConnectorEndpoint endpoint) {
    EgressHttpClient http = new EgressHttpClient(grant);
    TcpGate gate = TcpGate.installed();
    if (gate == null) {
      return http;
    }
    if (endpoint != null) {
      gate.onGrantChanged(endpoint::invalidate);
    }
    return new GatedEgress(http, gate, gate.register(grant, cfg, Instant.now().plus(timeout)));
  }

  @Override
  public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming incoming) throws RpcException {
    if (!helloDone && !Gpp.HOST_HELLO.equals(method)) {
      throw new RpcException(Gpp.INVALID_PARAMS, "host/hello comes first");
    }
    return switch (method) {
      case Gpp.HOST_HELLO -> hello(Messages.Hello.read(params));
      case Gpp.HOST_PING -> ping();
      case Gpp.HOST_SHUTDOWN -> shutdown(params);
      case Gpp.PLUGIN_LOAD -> registry.load(Messages.Load.read(params)).write();
      case Gpp.PLUGIN_UNLOAD -> {
        registry.unload(params.path("generation").asString());
        yield GppJson.object();
      }
      default -> {
        if (!ConnectorEndpoint.handles(method)) {
          throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
        }
        if (!calls.tryAcquire()) {
          throw new RpcException(Gpp.HOST_BUSY, "The plugin host is busy (" + MAX_CALLS + " calls running)");
        }
        try {
          yield endpoint.handle(method, params, incoming);
        } finally {
          calls.release();
        }
      }
    };
  }

  @Override
  public void notification(String method, JsonNode params) {
    if (Gpp.CONNECTION_INVALIDATE.equals(method)) {
      String connectionId = params.path("connectionId").asString();
      // Off the reader thread: a plugin closing its pool may block, and frames must keep flowing meanwhile.
      Thread.ofVirtual().name("invalidate").start(() -> endpoint.invalidate(connectionId));
    }
  }

  private Object hello(Messages.Hello hello) throws RpcException {
    if (hello.protocol() != Gpp.PROTOCOL) {
      throw new RpcException(Gpp.INVALID_PARAMS, "Plugin runtime mismatch: the core speaks GPP/" + hello.protocol()
          + ", this host GPP/" + Gpp.PROTOCOL);
    }
    if (!sameMajor(hello.connectorApi(), ApiVersion.CURRENT)) {
      throw new RpcException(Gpp.INVALID_PARAMS, "Plugin runtime mismatch: the core has connector-api "
          + hello.connectorApi() + ", this host " + ApiVersion.CURRENT);
    }
    HostLog.level(hello.logLevel());
    helloDone = true;
    long readyMs = Math.max(0, Duration.between(processStart, Instant.now()).toMillis());
    return new Messages.HelloResult(Gpp.PROTOCOL, Main.version(), ApiVersion.CURRENT, ProcessHandle.current().pid(),
        Runtime.version().toString(), System.getProperty("gateway.host.aot") != null, CAPABILITIES, readyMs).write();
  }

  private Object ping() {
    Runtime rt = Runtime.getRuntime();
    ObjectNode o = GppJson.object();
    o.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
    o.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
    o.put("inFlight", MAX_CALLS - calls.availablePermits());
    o.put("generations", registry.all().size());
    return o;
  }

  private Object shutdown(JsonNode params) {
    long graceMs = Math.clamp(params.path("graceMs").asLong(2000), 0, 60_000);
    // Answer first, then go: the core waits for the reply and then for the process to end.
    Thread.ofVirtual().name("shutdown").start(() -> exit.exit(Duration.ofMillis(graceMs)));
    return GppJson.object();
  }

  private static boolean sameMajor(String a, String b) {
    if (!Semver.valid(a) || !Semver.valid(b)) {
      return false;
    }
    return Semver.parse(a).major() == Semver.parse(b).major();
  }

  JsonRpcPeer peer() {
    return peer.get();
  }
}
