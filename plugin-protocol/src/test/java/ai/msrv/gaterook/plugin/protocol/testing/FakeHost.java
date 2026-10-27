package ai.msrv.gaterook.plugin.protocol.testing;

import ai.msrv.gaterook.connector.api.ApiVersion;
import ai.msrv.gaterook.connector.api.Connector;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.MemoryPipe;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.host.ConnectorEndpoint;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A plugin host in this JVM, over in-memory pipes (ARCHITECTURE.md §10, K4: "core unit tests use the fake host from the test JAR of
 * plugin-protocol over pipes"). It speaks GPP/1 like the real host and runs {@code connector/*} through the same
 * {@link ConnectorEndpoint}; only loading differs: {@code plugin/load} asks {@code plugins} for a {@link Connector}
 * instead of PF4J. Tests make it crash, hang or misbehave through its switches.
 */
public final class FakeHost implements AutoCloseable {

  private static final AtomicLong PIDS = new AtomicLong(900_000);

  /** What a test can make the fake do. */
  public static final class Switches {
    /** Tools {@code plugin/load} reports instead of the connector's manifest (a host that lies). */
    public volatile List<ToolDefinition> reportedTools;
    /** {@code plugin/load} fails with this message. */
    public volatile String failLoad;
    /** {@code host/hello} answers with this protocol. */
    public volatile int protocol = Gpp.PROTOCOL;
    /** {@code host/ping} is never answered (a stuck host). */
    public volatile boolean ignorePing;
    /** {@code host/hello} waits this long before answering. */
    public volatile Duration helloDelay = Duration.ZERO;
  }

  private final MemoryPipe toHost = new MemoryPipe();
  private final MemoryPipe fromHost = new MemoryPipe();
  private final Function<Messages.Load, Connector> plugins;
  private final Map<String, ConnectorEndpoint.Loaded> loaded = new ConcurrentHashMap<>();
  private final CompletableFuture<Integer> exit = new CompletableFuture<>();
  private final long pid = PIDS.incrementAndGet();
  private final JsonRpcPeer peer;
  private final ConnectorEndpoint endpoint;
  private final Switches switches = new Switches();

  private FakeHost(Function<Messages.Load, Connector> plugins) {
    this.plugins = plugins;
    ConnectorEndpoint.Generations generations = new ConnectorEndpoint.Generations() {
      @Override
      public Optional<ConnectorEndpoint.Loaded> find(String generation) {
        return Optional.ofNullable(loaded.get(generation));
      }

      @Override
      public Collection<ConnectorEndpoint.Loaded> all() {
        return loaded.values();
      }
    };
    JsonRpcPeer[] self = new JsonRpcPeer[1];
    this.endpoint = new ConnectorEndpoint(() -> self[0], generations, null, Clock.systemUTC(),
        () -> System.Logger.Level.DEBUG);
    this.peer = new JsonRpcPeer(toHost.in(), fromHost.out(), new JsonRpcPeer.Handler() {
      @Override
      public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) throws RpcException {
        return FakeHost.this.handle(method, params, call);
      }

      @Override
      public void notification(String method, JsonNode params) {
        if (Gpp.CONNECTION_INVALIDATE.equals(method)) {
          endpoint.invalidate(params.path("connectionId").asString());
        }
      }
    }, JsonRpcPeer.Options.defaults().name("fake-host").hostIds(true));
    self[0] = peer;
    peer.closed().whenComplete((cause, error) -> Thread.ofVirtual().start(() -> {
      try {
        peer.awaitIdle(Duration.ofSeconds(2));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      exit(0);
    }));
  }

  /** A host that loads every generation with {@code plugins}. */
  public static FakeHost start(Function<Messages.Load, Connector> plugins) {
    FakeHost host = new FakeHost(plugins);
    host.peer.start();
    return host;
  }

  /** What the core reads: the host's stdout. */
  public InputStream stdout() {
    return fromHost.in();
  }

  /** What the core writes: the host's stdin. */
  public OutputStream stdin() {
    return toHost.out();
  }

  public long pid() {
    return pid;
  }

  public Switches switches() {
    return switches;
  }

  /** Completes with the exit code when the fake "process" ends. */
  public CompletableFuture<Integer> onExit() {
    return exit;
  }

  public boolean alive() {
    return !exit.isDone();
  }

  /** The process dies at once ({@code System.exit}, OOM, kill -9): both pipes break. */
  public void crash(int code) {
    exit(code);
  }

  /** The peer the plugin's callbacks go through (tests send their own host→core requests on it). */
  public JsonRpcPeer peer() {
    return peer;
  }

  @Override
  public void close() {
    crash(137);
  }

  private void exit(int code) {
    if (exit.isDone()) {
      return;
    }
    try {
      fromHost.out().close();
      toHost.in().close();
    } catch (java.io.IOException e) {
      // In memory.
    }
    exit.complete(code);
  }

  private Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) throws RpcException {
    switch (method) {
      case Gpp.HOST_HELLO -> {
        Messages.Hello hello = Messages.Hello.read(params);
        if (hello.protocol() != Gpp.PROTOCOL) {
          throw new RpcException(Gpp.INVALID_PARAMS, "Protocol " + hello.protocol() + " isn't spoken here");
        }
        sleep(switches.helloDelay);
        return new Messages.HelloResult(switches.protocol, "fake", ApiVersion.CURRENT, pid, "fake", false,
            List.of("cancel", "secrets.callback"), 1).write();
      }
      case Gpp.HOST_PING -> {
        if (switches.ignorePing) {
          sleep(Duration.ofMinutes(10));
        }
        ObjectNode o = GppJson.object();
        o.put("heapUsedMb", 1);
        return o;
      }
      case Gpp.HOST_SHUTDOWN -> {
        Thread.ofVirtual().start(() -> {
          sleep(Duration.ofMillis(20));
          exit(0);
        });
        return GppJson.object();
      }
      case Gpp.PLUGIN_LOAD -> {
        Messages.Load load = Messages.Load.read(params);
        if (switches.failLoad != null) {
          ObjectNode data = GppJson.object();
          data.put("phase", "instantiate");
          throw new RpcException(Gpp.PLUGIN_LOAD_FAILED, switches.failLoad, data);
        }
        Connector connector = plugins.apply(load);
        loaded.put(load.generation(), new ConnectorEndpoint.Loaded(load.generation(), connector,
            Path.of(load.dataDir())));
        List<ToolDefinition> tools = switches.reportedTools != null ? switches.reportedTools
            : connector.manifest().tools();
        return new Messages.Loaded(load.generation(), connector.manifest().id(), ApiVersion.CURRENT, 1, tools, null)
            .write();
      }
      case Gpp.PLUGIN_UNLOAD -> {
        ConnectorEndpoint.Loaded l = loaded.remove(params.path("generation").asString());
        if (l != null) {
          l.connector().close();
        }
        return GppJson.object();
      }
      default -> {
        if (ConnectorEndpoint.handles(method)) {
          return endpoint.handle(method, params, call);
        }
        throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
      }
    }
  }

  private static void sleep(Duration d) {
    if (d.isZero()) {
      return;
    }
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
