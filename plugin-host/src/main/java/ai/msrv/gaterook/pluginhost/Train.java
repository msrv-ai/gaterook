package ai.msrv.gaterook.pluginhost;

import com.sun.net.httpserver.HttpServer;
import ai.msrv.gaterook.connector.api.ApiVersion;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.MemoryPipe;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.SecretWire;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import gaterook.training.TrainingConnector;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The AOT cache's training workload ({@code java -XX:AOTCacheOutput=… Main --train}, report 16 §3.3): this process
 * plays both sides. A host on in-memory pipes loads a small plugin ({@link TrainingConnector}) through PF4J and the
 * core's side sends it what a real core does: hello, load, {@value #CALLS} calls with secret callbacks, a few over
 * the guarded HTTP client to a server in this process, test, classify, describeAction, ping and shutdown. The classes
 * all of that needs are what the cache then holds.
 *
 * <p>Nothing leaves the temporary folder; no secret is real.
 */
final class Train {

  static final int CALLS = 400;
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
  private static final String GENERATION = "aot-training@1.0.0";

  private Train() {}

  /** Runs the workload; the exit code of {@code --train}. */
  static int run() {
    Path dir = null;
    HttpServer server = null;
    long started = System.nanoTime();
    try {
      dir = Files.createTempDirectory("plugin-host-train-");
      Path jar = writePlugin(dir.resolve("plugin.jar"));
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/", exchange -> {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream o = exchange.getResponseBody()) {
          o.write(body);
        }
      });
      server.start();
      workload(jar, dir.resolve("data"), server.getAddress().getPort());
      System.err.println("plugin-host --train: " + CALLS + " calls in "
          + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
      return 0;
    } catch (Exception e) {
      System.err.println("plugin-host --train failed: " + e);
      return 1;
    } finally {
      if (server != null) {
        server.stop(0);
      }
      if (dir != null) {
        delete(dir);
      }
    }
  }

  private static void workload(Path jar, Path dataDir, int port) throws Exception {
    MemoryPipe toHost = new MemoryPipe();
    MemoryPipe fromHost = new MemoryPipe();
    Host host = new Host(toHost.in(), fromHost.out(), new PluginRegistry(), Instant.now());
    var exit = host.start();
    JsonRpcPeer core = new JsonRpcPeer(fromHost.in(), toHost.out(), new JsonRpcPeer.Handler() {
      @Override
      public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) throws RpcException {
        return switch (method) {
          case Gpp.SECRETS_GET -> SecretWire.text("training-token-0123456789".toCharArray(), "v1", null);
          case Gpp.SECRETS_META -> {
            ObjectNode o = GppJson.object();
            o.put("set", true);
            o.put("source", "local");
            o.put("version", "v1");
            yield o;
          }
          default -> throw new RpcException(Gpp.METHOD_NOT_FOUND, "No method " + method);
        };
      }
    }, JsonRpcPeer.Options.defaults().name("train-core"));
    core.start();
    try {
      call(core, Gpp.HOST_HELLO, new Messages.Hello(Gpp.PROTOCOL, Main.version(), ApiVersion.CURRENT, true,
          Gpp.MAX_FRAME_BYTES, 8, "debug").write());
      call(core, Gpp.PLUGIN_LOAD, new Messages.Load(GENERATION, 1, jar.toString(), PluginRegistry.sha256(jar),
          dataDir.toString(), new Messages.Sandbox(false, List.of(), List.of())).write());
      ObjectNode config = GppJson.object();
      config.put("url", "http://127.0.0.1:" + port + "/");
      ConnectionConfig cfg = new ConnectionConfig("c_train", "training", "aot-training", Env.DEV, 1, config,
          Set.of("token"), null);
      Limits limits = WireCodec.limits(GppJson.object());
      Messages.EgressGrant grant = new Messages.EgressGrant(List.of("127.0.0.1:" + port), List.of("127.0.0.0/8"),
          List.of());
      List<JsonRpcPeer.Call<JsonNode>> batch = new ArrayList<>();
      for (int i = 0; i < CALLS; i++) {
        ObjectNode args = GppJson.object();
        args.put("text", "row-");
        args.put("http", i % 10 == 0);
        String tool = i % 50 == 7 ? "train_note" : "train_echo";
        ToolCall toolCall = new ToolCall("tc_train" + i, tool, cfg.name(), args, null);
        boolean approved = tool.equals("train_note");
        batch.add(core.request(Gpp.CONNECTOR_EXECUTE, new Messages.Execute(GENERATION, "ct_train" + i, toolCall, cfg,
            5_000, approved, approved ? "ap_train" + i : null, null, approved ? args : null, limits, null,
            "00-4f2a9c71e8d04b5a9c3e2b1f0a6d8e81-7a1c2e9b4d3f6a10-01", grant).write()));
        if (batch.size() == 8) {
          for (JsonRpcPeer.Call<JsonNode> c : batch) {
            WireCodec.result(c.get(CALL_TIMEOUT));
          }
          batch.clear();
        }
        if (i % 40 == 0) {
          WireCodec.test(call(core, Gpp.CONNECTOR_TEST, new Messages.Test(GENERATION, "tt_train" + i, "ct_test" + i,
              cfg, 5_000, limits, grant).write()));
          ObjectNode mixed = GppJson.object();
          mixed.put("write", i % 80 == 0);
          ToolCall classify = new ToolCall("tc_mixed" + i, "train_mixed", cfg.name(), mixed, null);
          WireCodec.classification(call(core, Gpp.CONNECTOR_CLASSIFY, new Messages.Classify(GENERATION, classify, cfg,
              new PolicyView(Env.DEV, "dev.training", GppJson.object(), limits)).write()));
          WireCodec.action(call(core, Gpp.CONNECTOR_DESCRIBE_ACTION, new Messages.Describe(GENERATION,
              new ToolCall("tc_note" + i, "train_note", cfg.name(), args, null), cfg).write()));
          call(core, Gpp.HOST_PING, GppJson.object());
        }
      }
      for (JsonRpcPeer.Call<JsonNode> c : batch) {
        WireCodec.result(c.get(CALL_TIMEOUT));
      }
      ObjectNode shutdown = GppJson.object();
      shutdown.put("graceMs", 500);
      call(core, Gpp.HOST_SHUTDOWN, shutdown);
      exit.get(10, TimeUnit.SECONDS);
    } finally {
      core.close();
    }
  }

  private static JsonNode call(JsonRpcPeer core, String method, JsonNode params) throws Exception {
    return core.request(method, params).get(CALL_TIMEOUT);
  }

  /** The training plugin: its manifest, written from the connector's own definitions, and its one class. */
  private static Path writePlugin(Path jar) throws IOException {
    TrainingConnector connector = new TrainingConnector();
    ObjectNode m = GppJson.object();
    m.put("manifestVersion", 1);
    m.put("id", "aot-training");
    m.put("version", "1.0.0");
    m.put("name", "AOT training");
    m.put("provider", "MCP Gateway");
    m.put("license", "Apache-2.0");
    ObjectNode requires = m.putObject("requires");
    requires.put("gateway", ">=0.0.0");
    requires.put("connectorApi", ">=1.0.0 & <2.0.0");
    m.put("connectorClass", TrainingConnector.class.getName());
    m.put("toolNamespace", "train_");
    m.set("configSchema", connector.manifest().configSchema());
    ArrayNode tools = m.putArray("tools");
    for (ToolDefinition t : connector.manifest().tools()) {
      tools.add(WireCodec.tool(t));
    }
    m.putObject("access").putArray("hosts").add("127.0.0.1");
    String classFile = TrainingConnector.class.getName().replace('.', '/') + ".class";
    try (InputStream in = Train.class.getClassLoader().getResourceAsStream(classFile);
        JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      if (in == null) {
        throw new IOException("Can't find " + classFile);
      }
      out.putNextEntry(new JarEntry(PluginManifest.FILE));
      out.write(GppJson.MAPPER.writeValueAsBytes(m));
      out.closeEntry();
      out.putNextEntry(new JarEntry(classFile));
      in.transferTo(out);
      out.closeEntry();
    }
    return jar;
  }

  private static void delete(Path dir) {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException | UncheckedIOException e) {
      System.err.println("plugin-host --train: couldn't remove " + dir + ": " + e.getMessage());
    }
  }
}
