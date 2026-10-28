package ai.msrv.gaterook.pluginit;

import ai.msrv.gaterook.connector.api.ApiVersion;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.RpcException;
import ai.msrv.gaterook.plugin.protocol.SecretWire;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A real plugin host, spawned the way the core spawns it (ARCHITECTURE.md §2.3, §7.5): a child JVM on this process's
 * {@code java.home} running {@code plugin-host.jar}, GPP/1 over its stdin/stdout, env cleared, a per-instance data
 * dir. With the sandbox on, the launch is confined before the host's JVM starts, as the core does it: on macOS
 * through {@code sandbox-exec} and an SBPL that denies GateRook's home, on Linux through the host's first stage
 * {@code SandboxExec} (Landlock on its main thread, then exec), where Landlock works here. The test drives the host
 * directly over the protocol and answers its {@code secrets/*} and {@code log} callbacks itself.
 */
final class Harness implements AutoCloseable {

  static final Limits DEV = new Limits(1000, 1000, 524_288, 1_048_576, 262_144, 65_536, 8192, Duration.ofSeconds(5),
      Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(168));

  private final Process process;
  private final JsonRpcPeer peer;
  private final Path home;
  private final List<String> stderr = new CopyOnWriteArrayList<>();
  private final List<String> stray = new CopyOnWriteArrayList<>();
  private final List<String> logs = new CopyOnWriteArrayList<>();
  private final Map<String, Map<String, String>> secretsByToken = new ConcurrentHashMap<>();
  private volatile long readyMs;
  private volatile Messages.Loaded loaded;
  /** Whether Linux's first stage confines a probe here: asked once per test JVM. */
  private static volatile Boolean landlock;

  private Harness(Process process, JsonRpcPeer peer, Path home) {
    this.process = process;
    this.peer = peer;
    this.home = home;
  }

  /** Spawns a host, says hello and loads {@code jar}; the sandbox is on where this OS has one. */
  static Harness start(String generation, Path jar, Path home, boolean sandbox) {
    return start(generation, jar, home, sandbox, Map.of());
  }

  static Harness start(String generation, Path jar, Path home, boolean sandbox, Map<String, String> jvmProps) {
    try {
      Path dataDir = home.resolve("plugin-data").resolve(id(generation));
      Files.createDirectories(dataDir.resolve("tmp"));
      String kind = sandbox ? sandboxKind() : null;
      long t0 = System.nanoTime();
      Process process = spawn(home, dataDir, jar, jvmProps, kind);
      Harness[] self = new Harness[1];
      JsonRpcPeer peer = new JsonRpcPeer(process.getInputStream(), process.getOutputStream(),
          new JsonRpcPeer.Handler() {
            @Override
            public Object handle(String method, JsonNode params, JsonRpcPeer.Incoming call) throws RpcException {
              return self[0].inbound(method, params);
            }

            @Override
            public void notification(String method, JsonNode params) {
              self[0].notification(method, params);
            }
          }, JsonRpcPeer.Options.defaults().name("core").strayLine(line -> {}));
      Harness h = new Harness(process, peer, home);
      self[0] = h;
      Thread.ofVirtual().start(h::pumpStderr);
      peer.start();
      h.hello(sandbox);
      h.loaded = h.load(generation, jar, dataDir, kind != null);
      h.readyMs = (System.nanoTime() - t0) / 1_000_000;
      return h;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The sandbox a host starts in here, as the core decides it: {@code sandbox-exec} on macOS, {@code landlock} on a
   * Linux where the first stage's probe passes, else {@code null}.
   */
  static String sandboxKind() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.startsWith("mac")) {
      return Files.isExecutable(Path.of("/usr/bin/sandbox-exec")) ? "sandbox-exec" : null;
    }
    if (!os.startsWith("linux")) {
      return null;
    }
    if (landlock == null) {
      landlock = probeLandlock();
    }
    return landlock ? "landlock" : null;
  }

  private static boolean probeLandlock() {
    List<String> argv = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xshare:auto", "-XX:+UseSerialGC", "-Xmx32m", "--enable-native-access=ALL-UNNAMED", "-cp",
        System.getProperty("gateway.pluginHostJar"), "ai.msrv.gaterook.pluginhost.SandboxExec", "--probe"));
    try {
      Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      return p.waitFor() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static Process spawn(Path home, Path dataDir, Path jar, Map<String, String> jvmProps, String sandbox)
      throws IOException {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    String hostJar = System.getProperty("gateway.pluginHostJar");
    List<String> argv = new ArrayList<>();
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if ("sandbox-exec".equals(sandbox)) {
      argv.add("/usr/bin/sandbox-exec");
      argv.add("-p");
      argv.add(macProfile(home, dataDir));
    } else if ("landlock".equals(sandbox)) {
      // The first stage: confine its main thread, then exec the host's JVM below (the core's HostLauncher).
      argv.addAll(List.of(java.toString(), "-Xshare:auto", "-XX:+UseSerialGC", "-Xmx32m", "-XX:-UsePerfData",
          "-Xlog:disable", "-Xlog:all=warning:stderr", "--enable-native-access=ALL-UNNAMED", "-cp", hostJar,
          "ai.msrv.gaterook.pluginhost.SandboxExec", "--ro", real(Path.of(System.getProperty("java.home"))), "--ro",
          real(Path.of(hostJar)), "--ro", real(jar.toAbsolutePath().getParent()), "--rw", real(dataDir), "--"));
    }
    argv.add(java.toString());
    argv.add("-XX:+UseSerialGC");
    argv.add("-XX:TieredStopAtLevel=1");
    argv.add("-Xmx64m");
    argv.add("-XX:+ExitOnOutOfMemoryError");
    argv.add("--enable-native-access=ALL-UNNAMED");
    argv.add("-Djava.io.tmpdir=" + dataDir.resolve("tmp"));
    jvmProps.forEach((k, v) -> argv.add("-D" + k + "=" + v));
    argv.add("-cp");
    argv.add(hostJar);
    argv.add("ai.msrv.gaterook.pluginhost.Main");
    argv.add("--protocol");
    argv.add("1");
    ProcessBuilder pb = new ProcessBuilder(argv).directory(dataDir.toFile());
    pb.environment().clear();
    pb.environment().put("LANG", "C.UTF-8");
    pb.environment().put("TMPDIR", dataDir.resolve("tmp").toString());
    if (!os.startsWith("win")) {
      pb.environment().put("PATH", "/usr/bin:/bin");
    }
    if ("sandbox-exec".equals(sandbox)) {
      pb.environment().put("MCP_GATEWAY_SANDBOX", sandbox);
    }
    return pb.start();
  }

  // ── Protocol ────────────────────────────────────────────────────────────────────────────────────────────────

  void hello(boolean sandbox) {
    Messages.Hello hello = new Messages.Hello(Gpp.PROTOCOL, "0.4.0", ApiVersion.CURRENT, false, Gpp.MAX_FRAME_BYTES,
        8, "debug");
    await(peer.request(Gpp.HOST_HELLO, hello.write()), Duration.ofSeconds(30), "hello");
  }

  Messages.Loaded load(String generation, Path jar, Path dataDir, boolean sandboxed) {
    Messages.Sandbox s = new Messages.Sandbox(sandboxed, List.of(), List.of(dataDir.toString()));
    Messages.Load load = new Messages.Load(generation, 1, jar.toString(), sha256(jar), dataDir.toString(), s);
    return Messages.Loaded.read(await(peer.request(Gpp.PLUGIN_LOAD, load.write()), Duration.ofSeconds(30), "load"));
  }

  /** An execute with a one-field secret ({@code field -> value}); returns the raw result node. */
  JsonNode execute(String generation, ai.msrv.gaterook.connector.api.ToolCall call, ConnectionConfig cfg, boolean approved,
      Messages.EgressGrant egress, Map<String, String> secretValues, Duration timeout) {
    String token = "ct_" + Long.toHexString(System.nanoTime());
    secretsByToken.put(token, Map.copyOf(secretValues));
    try {
      Messages.Execute e = new Messages.Execute(generation, token, call, cfg, timeout.toMillis(), approved,
          approved ? "ap_1" : null, null, approved ? call.arguments() : null, DEV, null, null, egress);
      return await(peer.request(Gpp.CONNECTOR_EXECUTE, e.write()), timeout.plusSeconds(2), "execute");
    } finally {
      secretsByToken.remove(token);
    }
  }

  JsonRpcPeer.Call<JsonNode> executeAsync(String generation, ai.msrv.gaterook.connector.api.ToolCall call,
      ConnectionConfig cfg, Messages.EgressGrant egress, Map<String, String> secretValues, Duration timeout) {
    String token = "ct_" + Long.toHexString(System.nanoTime());
    secretsByToken.put(token, Map.copyOf(secretValues));
    Messages.Execute e = new Messages.Execute(generation, token, call, cfg, timeout.toMillis(), false, null, null,
        null, DEV, null, null, egress);
    return peer.request(Gpp.CONNECTOR_EXECUTE, e.write());
  }

  JsonNode test(String generation, ConnectionConfig cfg, Messages.EgressGrant egress, Map<String, String> secrets,
      Duration timeout) {
    return testMethod(Gpp.CONNECTOR_TEST, generation, cfg, egress, secrets, timeout);
  }

  JsonNode discoverDatabases(String generation, ConnectionConfig cfg, Messages.EgressGrant egress,
      Map<String, String> secrets, Duration timeout) {
    return testMethod(Gpp.CONNECTOR_DISCOVER_DATABASES, generation, cfg, egress, secrets, timeout);
  }

  private JsonNode testMethod(String method, String generation, ConnectionConfig cfg, Messages.EgressGrant egress,
      Map<String, String> secrets, Duration timeout) {
    String token = "ct_" + Long.toHexString(System.nanoTime());
    secretsByToken.put(token, Map.copyOf(secrets));
    try {
      Messages.Test t = new Messages.Test(generation, "tt_1", token, cfg, timeout.toMillis(), DEV, egress);
      return await(peer.request(method, t.write()), timeout.plusSeconds(2), method);
    } finally {
      secretsByToken.remove(token);
    }
  }

  void ping() {
    await(peer.request(Gpp.HOST_PING, GppJson.object()), Duration.ofSeconds(5), "ping");
  }

  void shutdown() {
    try {
      peer.request(Gpp.HOST_SHUTDOWN, GppJson.object().put("graceMs", 500)).get(Duration.ofSeconds(3));
    } catch (Exception e) {
      // It may already be gone.
    }
  }

  // ── Callbacks the host makes to the core ────────────────────────────────────────────────────────────────────

  private Object inbound(String method, JsonNode params) throws RpcException {
    return switch (method) {
      case Gpp.SECRETS_GET -> {
        Map<String, String> fields = secretsByToken.get(params.path("callToken").asString());
        String field = params.path("field").asString();
        if (fields == null || !fields.containsKey(field)) {
          throw new RpcException(Gpp.SECRET_DENIED, "no secret " + field + " for this call");
        }
        yield SecretWire.text(fields.get(field).toCharArray(), "v1", null);
      }
      case Gpp.SECRETS_META -> {
        ObjectNode o = GppJson.object();
        Map<String, String> fields = secretsByToken.get(params.path("callToken").asString());
        o.put("set", fields != null && fields.containsKey(params.path("field").asString()));
        o.put("source", "local");
        o.put("version", "v1");
        yield o;
      }
      default -> throw new RpcException(Gpp.METHOD_NOT_FOUND, "No core method " + method);
    };
  }

  private void notification(String method, JsonNode params) {
    if (Gpp.LOG.equals(method)) {
      logs.add(params.path("level").asString("info") + " " + params.path("message").asString(""));
    }
  }

  // ── Accessors ───────────────────────────────────────────────────────────────────────────────────────────────

  Process process() {
    return process;
  }

  JsonRpcPeer peer() {
    return peer;
  }

  long readyMs() {
    return readyMs;
  }

  /** What {@code plugin/load} answered: the tools and the sandbox the host says it runs in. */
  Messages.Loaded loaded() {
    return loaded;
  }

  List<String> stderr() {
    return List.copyOf(stderr);
  }

  List<String> logs() {
    return List.copyOf(logs);
  }

  boolean alive() {
    return process.isAlive();
  }

  /** Closes the pipe: the host should see EOF and exit. */
  void closeStdin() {
    peer.closeOutput();
  }

  @Override
  public void close() {
    try {
      peer.close();
    } catch (RuntimeException e) {
      // Already gone.
    }
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private void pumpStderr() {
    try (InputStream err = process.getErrorStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        stderr.add(line);
      }
    } catch (IOException e) {
      // Ended.
    }
  }

  private static JsonNode await(JsonRpcPeer.Call<JsonNode> call, Duration timeout, String what) {
    try {
      return call.get(timeout);
    } catch (RpcException e) {
      throw new ProtocolError(what + " failed: " + e.getMessage(), e);
    } catch (Exception e) {
      throw new ProtocolError(what + " failed: " + e, e);
    }
  }

  /** A GPP/1 call that answered with an error or didn't answer. */
  static final class ProtocolError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient RpcException rpc;

    ProtocolError(String message, Throwable cause) {
      super(message, cause);
      this.rpc = cause instanceof RpcException r ? r : null;
    }
  }

  // ── Launch helpers ──────────────────────────────────────────────────────────────────────────────────────────

  private static String macProfile(Path home, Path dataDir) {
    String user = System.getProperty("user.home");
    // Real paths: on macOS the temp dir is /var/folders/… which resolves to /private/var/folders/…, and SBPL
    // subpaths match the resolved path the kernel sees.
    // As the core's HostLauncher: no writes outside the data folder and the devices, GateRook's home unreadable.
    StringBuilder b = new StringBuilder("(version 1)\n(allow default)\n(deny file-write* (subpath \"/\"))\n"
        + "(allow file-write* (subpath \"/dev\"))\n(deny file-read* file-write*\n");
    for (String denied : List.of(real(home), user + "/.ssh", user + "/.aws")) {
      b.append("  (subpath \"").append(denied).append("\")\n");
    }
    b.append(")\n");
    b.append("(allow file-read* file-write* (subpath \"").append(real(dataDir)).append("\"))\n");
    return b.toString();
  }

  private static String real(Path path) {
    try {
      return path.toRealPath().toString();
    } catch (IOException e) {
      return path.toAbsolutePath().toString();
    }
  }

  static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
        in.transferTo(java.io.OutputStream.nullOutputStream());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new UncheckedIOException(new IOException(e));
    }
  }

  static String id(String generation) {
    int at = generation.indexOf('@');
    return at < 0 ? generation : generation.substring(0, at);
  }

  static Path jiraJar(String version) {
    return Path.of(System.getProperty("gateway.jiraDir"), "plugins-jira-service-desk-0.4.1-" + version
        + ".jar");
  }

  static Path fixtureJar(String name) {
    return Path.of(System.getProperty("gateway.fixturesDir"), "plugins-test-fixtures-0.4.1-" + name + ".jar");
  }

  /** A catalog plugin's release JAR, as its module builds it ({@code plugins/<module>/target/<file>}, step K6). */
  static Path pluginJar(String module, String file) {
    return Path.of(System.getProperty("gateway.pluginsDir"), module, "target", file);
  }
}
