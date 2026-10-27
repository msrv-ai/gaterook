package ai.msrv.gaterook.core.connectors.plugins;

import ai.msrv.gaterook.connector.api.ApiVersion;
import ai.msrv.gaterook.connector.api.ToolDefinition;
import ai.msrv.gaterook.core.platform.CoreBuild;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.SealedHandles;
import ai.msrv.gaterook.plugin.protocol.Gpp;
import ai.msrv.gaterook.plugin.protocol.JsonRpcPeer;
import ai.msrv.gaterook.plugin.protocol.Messages;
import ai.msrv.gaterook.plugin.protocol.PluginManifest;
import ai.msrv.gaterook.plugin.protocol.WireCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Starts a plugin host (ARCHITECTURE.md §2.3, §7.5, report 16 §3.3): the argv of a bundled JRE with the host's flags
 * (serial GC, C1 only, the manifest's heap, attach and dumps off, exit on OOM, cleared env, the plugin's tmp dir), no
 * shell. It then does {@code host/hello} (and refuses a runtime whose protocol or SPI version differs) and
 * {@code plugin/load}; the result carries the tools the plugin's code reports, which the caller checks against the
 * manifest.
 *
 * <p>The process runs in the OS sandbox of this system, set up before the host's JVM starts: on Linux the launch goes
 * through the host's own first stage ({@code pluginhost.SandboxExec}), which confines its main thread with Landlock
 * and then execs the host's JVM — Landlock confines threads, not processes, so a host restricting itself would leave
 * its other threads, and with them the plugin's calls, unconfined (review 2026-10-27); on macOS the core wraps the
 * launch with {@code sandbox-exec} and an SBPL profile that denies reading MCP Gateway's home, the credential
 * folders and the connected clients' configs (the API is deprecated, so this is UNVERIFIED in the kit's words).
 * Whether Landlock works here is asked once ({@code SandboxExec --probe}); where there is no sandbox the host runs
 * without one and the log says so.
 *
 * <p>The host's answers are untrusted once the plugin's code ran: a malformed {@code plugin/load} ends the process,
 * and the tools it reports must be the manifest's, by definition hash ("tools ⊆ manifest").
 */
public final class HostLauncher {

  private static final Logger log = LoggerFactory.getLogger(HostLauncher.class);
  private static final AtomicInteger INSTANCES = new AtomicInteger();

  /**
   * A plugin generation to start.
   *
   * @param toolHashes    the manifest's tools by name and definition hash: what the host may report from
   *                      {@code plugin/load} ({@code null}: not checked)
   * @param readOnlyPaths more paths the sandbox lets the host read
   */
  public record Spec(String id, String version, Path jar, String sha256, int maxHeapMb,
      Map<String, String> toolHashes, List<String> readOnlyPaths) {

    /** The spec of {@code manifest}'s JAR at {@code jar}: its heap and its tools as the manifest declares them. */
    static Spec of(PluginManifest manifest, Path jar, String sha256) {
      return new Spec(manifest.id(), manifest.version(), jar, sha256, manifest.maxHeapMb(),
          manifest.definitionHashes(), List.of());
    }

    String generation() {
      return id + "@" + version;
    }
  }

  /** Requests one host may have the core work on at once (secrets, handles); one more is busy at once. */
  static final int MAX_INCOMING = 64;

  /** The first stage that confines a host on Linux before its JVM starts. */
  static final String SANDBOX_EXEC = "ai.msrv.gaterook.pluginhost.SandboxExec";
  /** Set in a host's environment by its sandbox ({@code SandboxExec} on Linux, the core for sandbox-exec). */
  static final String SANDBOX_ENV = "MCP_GATEWAY_SANDBOX";

  private final HostRuntimeLocator runtime;
  private final PluginPaths paths;
  private final SecretCallbackRegistry secrets;
  private final SealedHandles handles;
  private final AotCacheManager aot;
  private final HostPids pids;
  private final java.time.Clock clock;
  private final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
  /** Whether {@code SandboxExec --probe} passed on this system: known after the first start on Linux. */
  private volatile Boolean landlock;

  public HostLauncher(HostRuntimeLocator runtime, PluginPaths paths, SecretCallbackRegistry secrets,
      SealedHandles handles, AotCacheManager aot, java.time.Clock clock) {
    this(runtime, paths, secrets, handles, aot, new HostPids(paths.pidFile()), clock);
  }

  HostLauncher(HostRuntimeLocator runtime, PluginPaths paths, SecretCallbackRegistry secrets, SealedHandles handles,
      AotCacheManager aot, HostPids pids, java.time.Clock clock) {
    this.runtime = runtime;
    this.paths = paths;
    this.secrets = secrets;
    this.handles = handles;
    this.aot = aot;
    this.pids = pids;
    this.clock = clock;
  }

  /** The pids of the hosts this core runs ({@code run/plugin-hosts.json}). */
  HostPids pids() {
    return pids;
  }

  /**
   * The sandbox hosts run in on this system: {@code landlock} where Linux confines a probe of the first stage,
   * {@code sandbox-exec} on macOS, else {@code null} (the host runs as the person's user, unconfined). A Linux probe
   * that couldn't tell (it didn't finish) answers {@code null} here and is asked again next time.
   */
  public String sandboxKind() {
    try {
      return sandboxForStart();
    } catch (HostStartException e) {
      return null;
    }
  }

  /** {@link #sandboxKind()} for a start: a Linux probe that couldn't tell fails the start rather than run unconfined. */
  private String sandboxForStart() throws HostStartException {
    if (osName.startsWith("linux")) {
      Boolean known = landlock;
      if (known == null) {
        synchronized (this) {
          if (landlock == null) {
            Boolean probed = probeLandlock();
            if (probed == null) {
              throw new HostStartException("sandbox", "Couldn't tell whether this system can sandbox plugins: the "
                  + "probe didn't finish. Try again.");
            }
            landlock = probed;
          }
          known = landlock;
        }
      }
      return known ? "landlock" : null;
    }
    if (osName.startsWith("mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
      return "sandbox-exec";
    }
    return null;
  }

  /**
   * Runs {@code SandboxExec --probe}: {@code TRUE} when Landlock confined it, {@code FALSE} when it said this system
   * has no Landlock (exit 4) or there is no runtime to ask, {@code null} when it couldn't tell.
   */
  private Boolean probeLandlock() {
    Optional<Path> javaExe = runtime.javaExecutable();
    Optional<Path> hostJar = runtime.hostJar();
    if (javaExe.isEmpty() || hostJar.isEmpty()) {
      return Boolean.FALSE;
    }
    List<String> argv = new ArrayList<>(List.of(javaExe.get().toAbsolutePath().toString()));
    argv.addAll(firstStageFlags());
    argv.addAll(List.of("-cp", hostJar.get().toAbsolutePath().toString(), SANDBOX_EXEC, "--probe"));
    ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
    pb.environment().clear();
    pb.environment().put("LANG", "C.UTF-8");
    pb.environment().put("PATH", "/usr/bin:/bin");
    try {
      Process p = pb.start();
      p.getOutputStream().close();
      if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
        log.warn("The plugin sandbox probe didn't finish in 20 s");
        return null;
      }
      // A few lines at most, read once the probe ended: the pipe holds them.
      String said = new String(p.getInputStream().readNBytes(4096), StandardCharsets.UTF_8).strip();
      return switch (p.exitValue()) {
        case 0 -> Boolean.TRUE;
        case 4 -> {
          log.warn("Plugins run unconfined: Landlock can't confine them here ({})", said);
          yield Boolean.FALSE;
        }
        default -> {
          log.warn("The plugin sandbox probe failed (exit {}): {}", p.exitValue(), said);
          yield null;
        }
      };
    } catch (IOException e) {
      log.warn("The plugin sandbox probe couldn't run: {}", e.getMessage());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * Starts a host for {@code spec} and loads the plugin. The host is Running on return.
   *
   * @param logLevel the level to send in {@code host/hello}
   * @throws HostStartException when the process won't start, the handshake fails or the plugin won't load
   */
  public HostProcess start(Spec spec, String logLevel) throws HostStartException {
    Path java = runtime.javaExecutable().orElseThrow(() -> new HostStartException("hello",
        runtime.unavailableReason().orElse("no Java runtime for plugins")));
    Path hostJar = runtime.hostJar().orElseThrow(() -> new HostStartException("hello", "plugin-host.jar is missing"));
    int instance = INSTANCES.incrementAndGet();
    Path dataDir = paths.dataDir(spec.id());
    PluginLog pluginLog = new PluginLog(paths.log(spec.id()), clock);
    Instant started = clock.instant();
    boolean aotUsed = aot.cacheFor(runtime.jreRelease(), hostJar).isPresent();

    String sandbox = sandboxForStart();
    ProcessBuilder pb = new ProcessBuilder(command(java, hostJar, spec, dataDir, aotUsed, sandbox));
    pb.directory(dataDir.toFile());
    pb.redirectErrorStream(false);
    pb.environment().clear();
    pb.environment().putAll(minimalEnv(dataDir));
    if ("sandbox-exec".equals(sandbox)) {
      // On Linux only the first stage says so: a host started without it must not claim a sandbox.
      pb.environment().put(SANDBOX_ENV, sandbox);
    }
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      pluginLog.note("Couldn't start the plugin host: " + e.getMessage());
      pluginLog.close();
      throw new HostStartException("hello", "Can't start the plugin host: " + e.getMessage());
    }
    pids.record(process.toHandle());
    long pid = process.pid();
    process.onExit().thenRun(() -> pids.forget(pid));
    pluginLog.note("Started plugin host for " + spec.generation() + ", pid " + pid
        + (sandbox == null ? " (not sandboxed on this system)" : " (" + sandbox + ")"));

    // A host runs a plugin's code: what it may make the core do at once is bounded (review 2026-10-27).
    JsonRpcPeer peer = new JsonRpcPeer(process.getInputStream(), process.getOutputStream(),
        new HostInbound(instance, secrets, handles, pluginLog), JsonRpcPeer.Options.defaults()
            .name("host-" + spec.id()).maxIncoming(MAX_INCOMING)
            .strayLine(line -> pluginLog.stderr("stdout(non-JSON): " + line)));
    HostProcess host = new HostProcess(spec.generation(), instance, process, peer, pluginLog, started);
    Thread.ofVirtual().name("host-stderr-" + spec.id()).start(() -> pumpStderr(process, pluginLog));
    peer.start();
    try {
      hello(host, logLevel, aotUsed);
      load(host, spec, dataDir, sandbox);
      checkTools(host, spec);
    } catch (HostStartException e) {
      host.stopForcibly(Duration.ofSeconds(1));
      pluginLog.close();
      throw e;
    } catch (RuntimeException e) {
      // An answer that isn't GPP/1 (a plugin can write to the channel itself): the process goes with it.
      host.stopForcibly(Duration.ofSeconds(1));
      pluginLog.note("The plugin host answered something that isn't GPP/1: " + e);
      pluginLog.close();
      throw new HostStartException("load", "The plugin host's answer isn't valid: " + e.getMessage());
    }
    host.state(HostProcess.State.RUNNING);
    return host;
  }

  /**
   * The tools the plugin's code reports must be the manifest's (report 16 §4.4, §7.5): each by name and definition
   * hash. What the core publishes comes from the stored manifest either way; a plugin whose code disagrees with its
   * own manifest isn't run.
   */
  private static void checkTools(HostProcess host, Spec spec) throws HostStartException {
    Messages.Loaded loaded = host.loaded();
    if (spec.toolHashes() == null || loaded == null) {
      return;
    }
    for (ToolDefinition t : loaded.tools()) {
      String expected = spec.toolHashes().get(t.name());
      if (expected == null || !expected.equals(WireCodec.definitionHash(t))) {
        throw new HostStartException("tools", t.name() + " isn't the tool the manifest declares.");
      }
    }
  }

  private void hello(HostProcess host, String logLevel, boolean aot) throws HostStartException {
    Messages.Hello hello = new Messages.Hello(Gpp.PROTOCOL, CoreBuild.VERSION, ApiVersion.CURRENT,
        "native".equals(CoreBuild.runtime()), Gpp.MAX_FRAME_BYTES, 8, logLevel);
    JsonNode result = send(host, Gpp.HOST_HELLO, hello.write(), aot ? Duration.ofSeconds(15) : Duration.ofSeconds(30),
        "hello");
    Messages.HelloResult parsed = Messages.HelloResult.read(result);
    if (parsed.protocol() != Gpp.PROTOCOL || !sameMajor(parsed.connectorApi(), ApiVersion.CURRENT)) {
      throw new HostStartException("hello", "Plugin runtime mismatch: the host speaks GPP/" + parsed.protocol()
          + " connector-api " + parsed.connectorApi());
    }
    host.hello(parsed);
  }

  private void load(HostProcess host, Spec spec, Path dataDir, String sandboxKind) throws HostStartException {
    // enabled: the host must be running in the sandbox it was started in, or it refuses to load the plugin.
    Messages.Sandbox sandbox = new Messages.Sandbox(sandboxKind != null, spec.readOnlyPaths(),
        List.of(dataDir.toString()));
    Messages.Load load = new Messages.Load(spec.generation(), host.instance(), spec.jar().toString(), spec.sha256(),
        dataDir.toString(), sandbox);
    host.loaded(Messages.Loaded.read(send(host, Gpp.PLUGIN_LOAD, load.write(), Duration.ofSeconds(30), "load")));
  }

  private JsonNode send(HostProcess host, String method, JsonNode params, Duration timeout, String phase)
      throws HostStartException {
    try {
      return host.request(method, params).get(timeout);
    } catch (ai.msrv.gaterook.plugin.protocol.RpcException e) {
      throw new HostStartException(phaseOf(e, phase), e.getMessage());
    } catch (TimeoutException e) {
      throw new HostStartException(phase, "The plugin host didn't answer " + method + " in " + timeout.toSeconds()
          + " s.");
    } catch (JsonRpcPeer.PeerClosedException e) {
      throw new HostStartException(phase, "The plugin host ended before it answered " + method + ": "
          + tailOrMessage(host, e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HostStartException(phase, "Interrupted while starting the plugin host.");
    }
  }

  private static String phaseOf(ai.msrv.gaterook.plugin.protocol.RpcException e, String fallback) {
    JsonNode data = e.data();
    return data != null && data.path("phase").isString() ? data.get("phase").asString() : fallback;
  }

  private static String tailOrMessage(HostProcess host, Throwable e) {
    List<String> tail = host.log().tail();
    return tail.isEmpty() ? e.getMessage() : String.join(" / ", tail.subList(Math.max(0, tail.size() - 3),
        tail.size()));
  }

  List<String> command(Path java, Path hostJar, Spec spec, Path dataDir, boolean aotUsed, String sandbox) {
    List<String> argv = new ArrayList<>();
    Optional<Path> cache = aot.cacheFor(runtime.jreRelease(), hostJar);
    if ("sandbox-exec".equals(sandbox)) {
      argv.add("/usr/bin/sandbox-exec");
      argv.add("-p");
      argv.add(macProfile(spec, dataDir));
    } else if ("landlock".equals(sandbox)) {
      argv.addAll(firstStage(java, hostJar, spec, dataDir, cache));
    }
    argv.add(java.toAbsolutePath().toString());
    cache.ifPresent(c -> argv.add("-XX:AOTCache=" + c));
    argv.add("-Xlog:disable");
    argv.add("-Xlog:all=warning:stderr");
    argv.add("-XX:+UseSerialGC");
    argv.add("-XX:TieredStopAtLevel=1");
    argv.add("-Xms8m");
    argv.add("-Xmx" + Math.clamp(spec.maxHeapMb(), 64, 1024) + "m");
    argv.add("-Xss512k");
    argv.add("-XX:ReservedCodeCacheSize=32m");
    argv.add("-XX:MaxMetaspaceSize=128m");
    argv.add("-XX:-UsePerfData");
    argv.add("-XX:+DisableAttachMechanism");
    argv.add("-XX:+ExitOnOutOfMemoryError");
    argv.add("-XX:ErrorFile=" + dataDir.resolve("hs_err_%p.log"));
    argv.add("--enable-native-access=ALL-UNNAMED");
    argv.add("-Dfile.encoding=UTF-8");
    argv.add("-Dstdout.encoding=UTF-8");
    argv.add("-Djava.io.tmpdir=" + dataDir.resolve("tmp"));
    if (aotUsed) {
      argv.add("-Dgateway.host.aot=1");
    }
    argv.add("-cp");
    argv.add(hostJar.toAbsolutePath().toString());
    argv.add("ai.msrv.gaterook.pluginhost.Main");
    argv.add("--protocol");
    argv.add(String.valueOf(Gpp.PROTOCOL));
    return argv;
  }

  /**
   * {@code java … -cp plugin-host.jar SandboxExec --ro … --rw … --}: the Linux first stage, which confines its main
   * thread with Landlock and execs the host's JVM (the argv that follows). The host may read the JRE, its own JAR, the
   * plugin's version folder and the AOT cache, and write only the plugin's data folder; the system paths are the
   * stage's own (/usr, /etc, /proc/self, …).
   */
  private List<String> firstStage(Path java, Path hostJar, Spec spec, Path dataDir, Optional<Path> cache) {
    List<String> argv = new ArrayList<>(List.of(java.toAbsolutePath().toString()));
    argv.addAll(firstStageFlags());
    argv.add("-Djava.io.tmpdir=" + dataDir.resolve("tmp"));
    cache.ifPresent(c -> argv.add("-XX:AOTCache=" + c));
    argv.addAll(List.of("-cp", hostJar.toAbsolutePath().toString(), SANDBOX_EXEC));
    List<Path> readOnly = new ArrayList<>();
    runtime.jreHome().ifPresent(readOnly::add);
    readOnly.add(hostJar);
    readOnly.add(spec.jar().toAbsolutePath().getParent());
    cache.ifPresent(readOnly::add);
    spec.readOnlyPaths().forEach(p -> readOnly.add(Path.of(p)));
    for (Path p : readOnly) {
      for (String form : forms(p)) {
        argv.add("--ro");
        argv.add(form);
      }
    }
    for (String form : forms(dataDir)) {
      argv.add("--rw");
      argv.add(form);
    }
    argv.add("--");
    return argv;
  }

  /** A small, quick JVM that writes nothing to stdout (the protocol channel of the host it becomes). */
  private static List<String> firstStageFlags() {
    return List.of("-Xshare:auto", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xms8m", "-Xmx32m",
        "-XX:-UsePerfData", "-XX:+DisableAttachMechanism", "-Xlog:disable", "-Xlog:all=warning:stderr",
        "--enable-native-access=ALL-UNNAMED");
  }

  /** The env a host gets: nothing of the core's, only what a JVM needs (report 16 §3.3). */
  private Map<String, String> minimalEnv(Path dataDir) {
    Map<String, String> env = new java.util.LinkedHashMap<>();
    env.put("LANG", "C.UTF-8");
    env.put("TMPDIR", dataDir.resolve("tmp").toString());
    if (runtime.osIsWindows()) {
      for (String key : List.of("SystemRoot", "SystemDrive", "TEMP", "TMP", "USERPROFILE", "APPDATA",
          "LOCALAPPDATA")) {
        String value = System.getenv(key);
        if (value != null) {
          env.put(key, value);
        }
      }
    } else {
      env.put("PATH", "/usr/bin:/bin");
    }
    return env;
  }

  /**
   * SBPL: everything as usual, but reading MCP Gateway's home, the credential folders and the configs of the clients
   * MCP Gateway connects denied (report 16 §7.5; the same list as {@code gw-bridge sandbox-exec}: Cursor's and
   * Antigravity's configs may hold a gateway token), and writing anywhere but the plugin's data folder and the devices
   * denied too (review 2026-10-27: a login item or a shell profile written from inside would run unconfined later).
   * Seatbelt compares a {@code subpath} with the canonical path only, so every denied path is written as given and as
   * the kernel sees it ({@code /var} is {@code /private/var}). Still open (UNVERIFIED, like the deprecated API itself):
   * the network, the rest of the person's files to read, and Mach services such as Launch Services.
   *
   * <p>Inside the denied home the host may read its plugin's version folder and the AOT cache, and read and write its
   * own data folder; and it may {@code stat} — metadata only, no listing, no content — the folders on the way to them:
   * the JVM and PF4J resolve a JAR's canonical path, which needs the metadata of every parent (step K6 found an
   * installed plugin failing to load without it: "Operation not permitted" from PF4J's class loader).
   */
  String macProfile(Spec spec, Path dataDir) {
    GatewayPaths home = paths.gatewayPaths();
    Path homeDir = real(home.home());
    Path user = Path.of(System.getProperty("user.home"));
    List<Path> denied = new ArrayList<>(List.of(home.home()));
    for (String p : MAC_DENIED) {
      denied.add(user.resolve(p));
    }
    // Nothing is written outside the plugin's data folder and the devices (review 2026-10-27): a login item or a shell
    // profile planted from inside would run unconfined later. Later rules win in SBPL: the allows below reopen data.
    StringBuilder b = new StringBuilder("(version 1)\n(allow default)\n(deny file-write* (subpath \"/\"))\n"
        + "(allow file-write* (subpath \"/dev\"))\n(deny file-read* file-write*\n");
    for (Path p : denied) {
      for (String form : forms(p)) {
        b.append("  (subpath ").append(sbpl(form)).append(")\n");
      }
    }
    b.append(")\n");
    Path data = real(dataDir);
    Path version = real(spec.jar().toAbsolutePath().getParent());
    Path cache = real(paths.cache());
    // The plugin's own data dir (read-write) and the files the JVM needs under the gateway home (read-only).
    b.append("(allow file-read* file-write* (subpath ").append(sbpl(data.toString())).append("))\n");
    b.append("(allow file-read* (subpath ").append(sbpl(version.toString())).append("))\n");
    b.append("(allow file-read* (subpath ").append(sbpl(cache.toString())).append("))\n");
    java.util.Set<Path> parents = new java.util.LinkedHashSet<>();
    for (Path p : List.of(data, version, cache)) {
      for (Path a = p.getParent(); a != null && a.startsWith(homeDir); a = a.getParent()) {
        parents.add(a);
      }
    }
    if (!parents.isEmpty()) {
      b.append("(allow file-read-metadata");
      parents.forEach(a -> b.append(" (literal ").append(sbpl(a.toString())).append(')'));
      b.append(")\n");
    }
    return b.toString();
  }

  /** Under the person's home, what a plugin host on macOS may not read (as {@code gw-bridge sandbox-exec}). */
  static final List<String> MAC_DENIED = List.of(".ssh", ".aws", ".config/gcloud", ".kube", "Library/Keychains",
      ".docker", ".netrc", ".codex", ".cursor", ".gemini", "Library/Application Support/Claude");

  /** {@code p} absolute, and its canonical form when that differs (a path not there yet: through its deepest parent). */
  static List<String> forms(Path p) {
    Path abs = p.toAbsolutePath().normalize();
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
    out.add(abs.toString());
    Path existing = abs;
    java.util.ArrayDeque<Path> missing = new java.util.ArrayDeque<>();
    while (existing != null && !Files.exists(existing)) {
      missing.push(existing.getFileName());
      existing = existing.getParent();
    }
    if (existing != null) {
      try {
        Path canonical = existing.toRealPath();
        for (Path name : missing) {
          canonical = canonical.resolve(name);
        }
        out.add(canonical.toString());
      } catch (IOException e) {
        // Unreadable: the path as given is all there is.
      }
    }
    return List.copyOf(out);
  }

  /** The real path of {@code path} (symlinks resolved), or its normalized absolute path when it doesn't exist yet. */
  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return path.toAbsolutePath().normalize();
    }
  }

  private static String sbpl(String path) {
    return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static boolean sameMajor(String a, String b) {
    try {
      return ai.msrv.gaterook.plugin.protocol.Semver.parse(a).major()
          == ai.msrv.gaterook.plugin.protocol.Semver.parse(b).major();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * The host's stderr, line by line into the masked log; a line longer than {@link PluginLog#MAX_READ} is taken in
   * pieces (review 2026-10-27: a plugin printing one endless line grew a reader's buffer until the core ran out of
   * memory).
   */
  private static void pumpStderr(Process process, PluginLog log) {
    try (InputStream err = process.getErrorStream()) {
      PluginLog.lines(err, PluginLog.MAX_READ, log::stderr);
    } catch (IOException e) {
      // The process ended; nothing more to read.
    }
  }

  /** The host couldn't start or the plugin couldn't load; {@code phase} is {@code verify|sandbox|pf4j|…}. */
  public static final class HostStartException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String phase;

    public HostStartException(String phase, String message) {
      super(message);
      this.phase = phase;
    }

    public String phase() {
      return phase;
    }
  }

  static Path writeString(Path file, String content) {
    try {
      Files.writeString(file, content);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
