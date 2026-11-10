package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * How a stdio server is started (report 11 §4.1, §6.9, ARCHITECTURE.md §7.5): argv without a shell, the command found
 * on a PATH that also has what a login shell adds (a GUI app starts with {@code /usr/bin:/bin:/usr/sbin:/sbin}, where
 * Homebrew, nvm and Volta aren't), and an environment of only the basic user variables, the ones the person chose to
 * pass and the values of the server's secrets. Where {@code gw-bridge sandbox-exec} works (Landlock on Linux,
 * {@code sandbox-exec} on macOS) the process reads the system and the home minus GateRook's data and the credential
 * folders, and writes only its work folder and the temporary folders; its work folder is its {@code HOME}, so the
 * package managers keep their caches there, apart from those the person's own tools use. Its sockets can neither
 * connect to nor bind the core's own listeners ({@code --deny-tcp}, as they are at each start; on Linux from Landlock
 * ABI 4, else the server's log says they stay open). On Windows the bridge runs the process at low integrity: it
 * writes only its work folder, which is also its profile ({@code USERPROFILE}, {@code APPDATA}, {@code LOCALAPPDATA},
 * {@code TEMP}), and reads nothing of GateRook's home; the core refuses its connections to the core's own ports. The
 * bridge is started from its own argv ({@code gw-bridge.argv} beside the shim), not through {@code gw-bridge.cmd},
 * so a server's arguments never go through {@code cmd.exe}. Elsewhere it runs as the person's user and the review
 * says so.
 *
 * <p>Whether the sandbox works is asked of the bridge ({@code sandbox-exec -- true}): it confines the probe (exit 0),
 * or says this system has no sandbox (exit 4), and either answer is kept. Anything else is no answer (a launcher the
 * core wrote but that isn't there, one that fails or doesn't finish): then no server and no {@code npm ci} starts,
 * since running one unconfined is what the probe is there to rule out, and the next look asks again.
 */
final class Launcher {

  private static final System.Logger LOG = System.getLogger(Launcher.class.getName());
  /** The bridge's exit code for "no sandbox on this system" ({@code Sandbox.NO_SANDBOX}). */
  static final int NO_SANDBOX = 4;

  /** The variables a process gets from the core besides PATH, as the SDK's {@code ServerParameters} chose them. */
  private static final List<String> UNIX_BASICS = List.of("HOME", "LOGNAME", "SHELL", "TERM", "USER", "LANG");
  private static final List<String> WINDOWS_BASICS = List.of("APPDATA", "HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA",
      "PROCESSOR_ARCHITECTURE", "SYSTEMDRIVE", "SYSTEMROOT", "TEMP", "TMP", "USERNAME", "USERPROFILE", "PATHEXT");
  /** What {@code inheritEnv} may pass (config.schema.json). */
  static final Set<String> INHERITABLE = Set.of("HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "NODE_EXTRA_CA_CERTS",
      "SSL_CERT_FILE", "LANG");

  private final Map<String, String> coreEnv;
  private final boolean windows;
  private final boolean mac;
  private final Path userHome;
  private final Path gatewayHome;
  private final String path;
  private final Path sandboxLauncher;
  /**
   * Whether the core wrote {@link #sandboxLauncher} for this run ({@code McpServerRuntime.Bridge.WRITTEN}): a launcher
   * that isn't there is then no answer about the sandbox, not "no sandbox".
   */
  private final boolean launcherWritten;
  /** On Windows, the bridge's own argv from {@code gw-bridge.argv}, read at each probe; else empty. */
  private volatile List<String> windowsBridge = List.of();
  private final Supplier<Set<Integer>> corePorts;
  /** What the last probe found: kept once it is an answer, asked again while it isn't. */
  private volatile Probe probe;
  private final Object probing = new Object();
  private final Map<String, Optional<String>> nodeVersions = new ConcurrentHashMap<>();

  /**
   * What a look at the sandbox found: the sandbox servers run in, {@code null} where this system has none, or why the
   * probe couldn't tell ({@code unknown}), which is no answer.
   */
  record Probe(String sandbox, String unknown) {

    static final Probe NONE = new Probe(null, null);

    static Probe unknown(String why) {
      return new Probe(null, Objects.requireNonNull(why, "why"));
    }

    /** Whether the probe said something: the sandbox works here, or this system has none. */
    boolean answered() {
      return unknown == null;
    }
  }

  /** A launcher that closes no port to the servers (tests). */
  Launcher(Map<String, String> coreEnv, String osName, boolean loginShellPath, Path gatewayHome,
      Path sandboxLauncher) {
    this(coreEnv, osName, loginShellPath, gatewayHome, sandboxLauncher, Set::of);
  }

  /** A launcher of a host that keeps no bridge launcher itself (tests): no launcher there means no sandbox. */
  Launcher(Map<String, String> coreEnv, String osName, boolean loginShellPath, Path gatewayHome,
      Path sandboxLauncher, Supplier<Set<Integer>> corePorts) {
    this(coreEnv, osName, loginShellPath, gatewayHome, sandboxLauncher, false, corePorts);
  }

  /**
   * @param loginShellPath whether to ask {@code $SHELL -ilc} for the PATH a terminal would have (off in tests)
   * @param gatewayHome GateRook's home ({@code ~/.gaterook}), where the servers' work folders are
   * @param sandboxLauncher {@code ~/.gaterook/bin/gw-bridge}, used when its {@code sandbox-exec} works here;
   *                        {@code null} where this build has no bridge
   * @param launcherWritten whether the core wrote {@code sandboxLauncher} for this run: one that isn't there, or that
   *                        fails, then starts nothing until a later probe gets an answer
   * @param corePorts the core's own listeners ({@code McpServerRuntime.Host.corePorts()}), read for every argv: the
   *                  sandbox closes them to the process
   */
  Launcher(Map<String, String> coreEnv, String osName, boolean loginShellPath, Path gatewayHome,
      Path sandboxLauncher, boolean launcherWritten, Supplier<Set<Integer>> corePorts) {
    this.corePorts = Objects.requireNonNull(corePorts, "corePorts");
    this.coreEnv = Map.copyOf(coreEnv);
    String os = osName.toLowerCase(Locale.ROOT);
    this.windows = os.startsWith("windows");
    this.mac = os.startsWith("mac");
    String home = coreEnv.getOrDefault(windows ? "USERPROFILE" : "HOME", System.getProperty("user.home"));
    this.userHome = Path.of(home);
    this.gatewayHome = gatewayHome;
    this.path = computePath(loginShellPath);
    this.sandboxLauncher = sandboxLauncher;
    this.launcherWritten = launcherWritten && sandboxLauncher != null;
    look();
  }

  /** The bridge's own argv ({@code gw-bridge.argv} beside the shim, one argument a line), or empty. */
  private static List<String> windowsBridge(Path shim) {
    if (shim == null) {
      return List.of();
    }
    try {
      List<String> argv = Files.readAllLines(shim.resolveSibling("gw-bridge.argv"), StandardCharsets.UTF_8).stream()
          .filter(a -> !a.isEmpty()).toList();
      return !argv.isEmpty() && Files.isRegularFile(Path.of(argv.getFirst())) ? argv : List.of();
    } catch (IOException | RuntimeException e) {
      return List.of();
    }
  }

  String path() {
    return path;
  }

  /**
   * The sandbox of a server the person keeps off this computer: an AppContainer on Windows where the bridge works,
   * else the sandbox every server gets; {@code null} where there is none or while it couldn't be checked.
   */
  String sandbox(boolean offThisComputer) {
    return sandbox(look(), offThisComputer);
  }

  /** {@link #sandbox(boolean)} for what one look found. */
  String sandbox(Probe look, boolean offThisComputer) {
    String sandbox = look.sandbox();
    return windows && sandbox != null && offThisComputer ? "appcontainer" : sandbox;
  }

  /**
   * {@code landlock}, {@code sandbox-exec}, {@code low-integrity}, or {@code null} where servers run unconfined, or
   * while the sandbox couldn't be checked ({@link #look()} says which).
   */
  String sandbox() {
    return look().sandbox();
  }

  /**
   * What the probe found: kept once it answered (the sandbox works, or this system has none), asked again while it
   * couldn't tell. A look that couldn't tell is logged, so the core's log says why nothing starts.
   */
  Probe look() {
    Probe p = probe;
    if (p != null && p.answered()) {
      return p;
    }
    synchronized (probing) {
      p = probe;
      if (p == null || !p.answered()) {
        p = probeSandbox();
        probe = p;
        if (!p.answered()) {
          LOG.log(System.Logger.Level.WARNING, "GateRook couldn't check that its sandbox works here ({0}): no MCP "
              + "server starts until it can", p.unknown());
        }
      }
      return p;
    }
  }

  /**
   * The sandbox a start runs in, or {@code null} where this system has none.
   *
   * @throws ConnectorException when the sandbox couldn't be checked: nothing is started without it
   */
  private String sandboxForStart() throws ConnectorException {
    Probe p = look();
    if (!p.answered()) {
      throw ConnectorException.unavailable("GateRook couldn't check that its sandbox works here (" + p.unknown()
          + "), so it doesn't start the server without it. It checks again at the next start.", null);
    }
    return p.sandbox();
  }

  /** The executable {@code command} names on the PATH (PATHEXT on Windows), or empty. */
  Optional<Path> resolve(String command, Path workDir) {
    if (command == null || command.isEmpty()) {
      return Optional.empty();
    }
    if (command.contains("/") || (windows && command.contains("\\"))) {
      Path p = Path.of(command);
      if (!p.isAbsolute()) {
        p = workDir.resolve(p);
      }
      return Files.isRegularFile(p) && (windows || Files.isExecutable(p)) ? Optional.of(p.normalize())
          : Optional.empty();
    }
    List<String> exts = windows ? List.of(coreEnv.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD").split(";"))
        : List.of("");
    for (String dir : path.split(File.pathSeparator)) {
      if (dir.isEmpty()) {
        continue;
      }
      for (String ext : exts) {
        Path p = Path.of(dir, command + ext.toLowerCase(Locale.ROOT));
        if (Files.isRegularFile(p) && (windows || Files.isExecutable(p))) {
          return Optional.of(p.toAbsolutePath());
        }
      }
    }
    return Optional.empty();
  }

  /** {@code Node v24.22.1} for a command that runs Node (node, npx, npm, pnpm, pnpx, bunx is Bun's), else empty. */
  Optional<String> nodeVersion(String command, Path workDir) {
    String base = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("\\.(cmd|exe)$", "");
    if (!Set.of("node", "npx", "npm", "pnpm", "pnpx").contains(base)) {
      return Optional.empty();
    }
    Optional<Path> node = resolve("node", workDir);
    if (node.isEmpty()) {
      return Optional.empty();
    }
    return nodeVersions.computeIfAbsent(node.get().toString(), k -> {
      try {
        Process p = new ProcessBuilder(k, "--version").redirectErrorStream(true).start();
        p.getOutputStream().close();
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
          p.destroyForcibly();
          return Optional.empty();
        }
        String v = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return v.startsWith("v") ? Optional.of("Node " + v) : Optional.empty();
      } catch (IOException e) {
        return Optional.empty();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    });
  }

  /**
   * The environment of a server's process: PATH, the basic user variables, {@code inheritEnv} and the secrets.
   * Sandboxed, HOME is the work folder (npx keeps its cache there and the person's home stays closed).
   */
  Map<String, String> environment(ServerConfig server, Map<String, String> secrets, Path workDir) {
    return environment(server.inheritEnv(), secrets, workDir, look().sandbox() != null);
  }

  /**
   * The environment of {@code npm ci} for a catalog server: the same basics, and the proxy and CA variables the core
   * has, since npm reaches the registry through them.
   */
  Map<String, String> installEnvironment(Path workDir) {
    return environment(List.copyOf(INHERITABLE), Map.of(), workDir, true);
  }

  private Map<String, String> environment(List<String> inheritEnv, Map<String, String> secrets, Path workDir,
      boolean ownHome) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("PATH", path);
    for (String name : windows ? WINDOWS_BASICS : UNIX_BASICS) {
      String v = coreEnv.get(name);
      if (v != null) {
        env.put(name, v);
      }
    }
    env.putIfAbsent("HOME", userHome.toString());
    env.put("NPM_CONFIG_UPDATE_NOTIFIER", "false");
    env.put("NPM_CONFIG_FUND", "false");
    for (String name : inheritEnv) {
      String v = coreEnv.get(name);
      if (INHERITABLE.contains(name) && v != null) {
        env.put(name, v);
      }
    }
    if (ownHome) {
      env.put("HOME", workDir.toString());
      env.put("TMPDIR", workDir.resolve("tmp").toString());
      env.put("npm_config_cache", workDir.resolve(".npm").toString());
      if (windows && look().sandbox() != null) {
        // At low integrity the process writes only its work folder: it is its profile there too, as HOME is on
        // Unix. The bridge creates these folders before it drops.
        env.put("USERPROFILE", workDir.toString());
        env.put("APPDATA", workDir.resolve("AppData").resolve("Roaming").toString());
        env.put("LOCALAPPDATA", workDir.resolve("AppData").resolve("Local").toString());
        env.put("TEMP", workDir.resolve("tmp").toString());
        env.put("TMP", workDir.resolve("tmp").toString());
        env.remove("HOMEDRIVE");
        env.remove("HOMEPATH");
      }
    }
    // Last: a secret's variable is what the person configured, even if a basic one has the same name.
    env.putAll(secrets);
    return env;
  }

  /**
   * argv as it starts: the resolved command, wrapped by {@code gw-bridge sandbox-exec} where it works, which closes
   * the core's ports as they are now to the process ({@code --deny-tcp}, ascending).
   *
   * @throws ConnectorException when the sandbox couldn't be checked: nothing starts without it
   */
  List<String> argv(Path command, List<String> args, Path workDir) throws ConnectorException {
    return argv(command, args, workDir, false);
  }

  /**
   * {@link #argv(Path, List, Path)} for a server the person may keep off this computer: an AppContainer on Windows.
   */
  List<String> argv(Path command, List<String> args, Path workDir, boolean offThisComputer)
      throws ConnectorException {
    String sandbox = sandboxForStart();
    List<String> out = new ArrayList<>();
    if (sandbox != null && windows) {
      // Low integrity, or an AppContainer: the work folder is all it writes; the core refuses its connections to
      // the core's ports.
      out.addAll(windowsBridge);
      out.addAll(List.of("sandbox-exec", "--rw", workDir.toString()));
      if (offThisComputer) {
        out.add("--appcontainer");
      }
      out.add("--");
    } else if (sandbox != null) {
      out.add(sandboxLauncher.toString());
      out.add("sandbox-exec");
      out.add("--rw");
      out.add(workDir.toString());
      installation(command).ifPresent(tree -> {
        out.add("--ro");
        out.add(tree.toString());
      });
      for (int port : closedPorts()) {
        out.add("--deny-tcp");
        out.add(Integer.toString(port));
      }
      out.add("--");
    }
    out.add(command.toString());
    out.addAll(args);
    return out;
  }

  /**
   * The command's installation, which Landlock reads besides the command's own folder (the bridge opens that one
   * itself): the folder above the folder the command really is in, through its links (an nvm or Volta tree, a tool's
   * own environment in the home folder), whose {@code lib/} lies beside {@code bin/}. Landlock opens all of a
   * {@code --ro} path, so never the home or a folder above it (a command in ~/bin or in the home itself), nor GateRook's
   * home or a folder in it, which hold GateRook's data and the credentials, nor the folder of all work folders, which
   * can lie elsewhere behind a link (a command in a work folder is read through {@code --rw}). Nor a folder that is,
   * or holds, one that many programs keep their files in ({@link #sharedFolders}): for a command in
   * {@code ~/.local/bin} that would be all of {@code ~/.local}, other programs' data, keyrings and histories included,
   * so only the libraries beside the command's folder ({@code ~/.local/lib}) are read there.
   */
  private Optional<Path> installation(Path command) {
    Path bin = realPath(command).getParent();
    Path tree = bin == null ? null : bin.getParent();
    if (tree == null || !within(tree, userHome) || within(userHome, tree)) {
      return Optional.empty();
    }
    List<Path> shared = sharedFolders();
    if (shared.stream().anyMatch(s -> within(s, tree))) {
      Path lib = tree.resolve("lib");
      if (!Files.isDirectory(lib) || shared.stream().anyMatch(s -> within(s, lib))) {
        return Optional.empty();
      }
      return closedToCommands(lib) ? Optional.empty() : Optional.of(lib);
    }
    return closedToCommands(tree) ? Optional.empty() : Optional.of(tree);
  }

  /** Whether {@code tree} is, holds or lies in GateRook's home or the folder of all work folders. */
  private boolean closedToCommands(Path tree) {
    Path workFolders = gatewayHome.resolve("mcp");
    return within(tree, gatewayHome) || within(gatewayHome, tree) || within(tree, workFolders)
        || within(workFolders, tree);
  }

  /**
   * The folders of the home that many programs keep their files in, which no {@code --ro} grant may be or hold: the
   * XDG base folders ({@code ~/.local/share}, {@code ~/.local/state}, {@code ~/.config}, {@code ~/.cache}, and where
   * the core's {@code XDG_*_HOME} variables put them), {@code ~/.local} that holds two of them, and macOS's
   * {@code ~/Library}.
   */
  List<Path> sharedFolders() {
    List<Path> out = new ArrayList<>();
    for (String folder : List.of(".local", ".local/share", ".local/state", ".config", ".cache", "Library")) {
      out.add(userHome.resolve(folder));
    }
    for (String variable : List.of("XDG_DATA_HOME", "XDG_STATE_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME")) {
      String value = coreEnv.get(variable);
      if (value != null && !value.isBlank()) {
        try {
          Path p = Path.of(value);
          if (p.isAbsolute()) {
            out.add(p);
          }
        } catch (java.nio.file.InvalidPathException e) {
          // Not a path: XDG says to ignore it.
        }
      }
    }
    return out;
  }

  /** The core's listeners as they are now, ascending; what isn't a port (0: a web server not bound yet) is left out. */
  private Set<Integer> closedPorts() {
    Set<Integer> ports = new TreeSet<>();
    for (Integer port : corePorts.get()) {
      if (port != null && port > 0 && port <= 65_535) {
        ports.add(port);
      }
    }
    return ports;
  }

  // ── Internals ───────────────────────────────────────────────────────────────────────────────────────────────

  private String computePath(boolean loginShell) {
    Set<String> dirs = new LinkedHashSet<>();
    String own = coreEnv.getOrDefault(windows ? "Path" : "PATH", coreEnv.getOrDefault("PATH", ""));
    if (loginShell && !windows) {
      loginShellPath().ifPresent(p -> dirs.addAll(List.of(p.split(File.pathSeparator))));
    }
    dirs.addAll(List.of(own.split(File.pathSeparator)));
    if (!windows) {
      if (mac) {
        dirs.add("/opt/homebrew/bin");
      }
      dirs.add("/usr/local/bin");
      dirs.add(userHome.resolve(".volta/bin").toString());
      Path nvm = userHome.resolve(".nvm/versions/node");
      if (Files.isDirectory(nvm)) {
        try (Stream<Path> versions = Files.list(nvm)) {
          versions.sorted(Comparator.comparing(Path::toString).reversed())
              .forEach(v -> dirs.add(v.resolve("bin").toString()));
        } catch (IOException e) {
          // No nvm versions to add.
        }
      }
      dirs.add("/usr/bin");
      dirs.add("/bin");
    }
    dirs.removeIf(String::isBlank);
    return String.join(File.pathSeparator, dirs);
  }

  private Optional<String> loginShellPath() {
    String shell = coreEnv.get("SHELL");
    if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) {
      return Optional.empty();
    }
    try {
      ProcessBuilder pb = new ProcessBuilder(shell, "-ilc", "printf %s \"$PATH\"");
      pb.environment().clear();
      for (String name : UNIX_BASICS) {
        String v = coreEnv.get(name);
        if (v != null) {
          pb.environment().put(name, v);
        }
      }
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      Process p = pb.start();
      p.getOutputStream().close();
      if (!p.waitFor(5, TimeUnit.SECONDS)) {
        ProcessTree.stop(p.toHandle(), java.time.Duration.ofMillis(200));
        return Optional.empty();
      }
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      // Only the last line: an rc file that prints a banner comes first.
      String[] lines = out.split("\n");
      String last = lines[lines.length - 1].strip();
      return last.contains("/") ? Optional.of(last) : Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /**
   * Whether {@code gw-bridge sandbox-exec} confines a process on this system: the sandbox's name when it confined the
   * probe, {@link Probe#NONE} when the bridge says this system has none ({@link #NO_SANDBOX}) or there is no bridge,
   * else no answer, with the reason.
   */
  private Probe probeSandbox() {
    List<String> probe;
    if (windows) {
      // Read again at each probe: the core may write it after a look that found none.
      windowsBridge = windowsBridge(sandboxLauncher);
      if (windowsBridge.isEmpty()) {
        return launcherWritten ? Probe.unknown("the bridge that " + sandboxLauncher.resolveSibling("gw-bridge.argv")
            + " names isn't there") : Probe.NONE;
      }
      String root = coreEnv.getOrDefault("SystemRoot", coreEnv.getOrDefault("SYSTEMROOT", "C:\\Windows"));
      probe = new ArrayList<>(windowsBridge);
      probe.addAll(List.of("sandbox-exec", "--", Path.of(root, "System32", "cmd.exe").toString(), "/c", "exit", "0"));
    } else {
      if (sandboxLauncher == null) {
        return Probe.NONE;
      }
      if (!Files.isExecutable(sandboxLauncher)) {
        return launcherWritten ? Probe.unknown("its launcher " + sandboxLauncher + " isn't there") : Probe.NONE;
      }
      Optional<Path> truth = resolve("true", userHome);
      if (truth.isEmpty()) {
        return Probe.unknown("there is no true on the PATH GateRook gives servers to try it with");
      }
      probe = List.of(sandboxLauncher.toString(), "sandbox-exec", "--", truth.get().toString());
    }
    try {
      ProcessBuilder pb = new ProcessBuilder(probe).redirectErrorStream(true);
      // The probe asks whether the sandbox works here, not whether the core's own TMPDIR may be opened to a command:
      // the bridge refuses a TMPDIR behind a link, and that must not leave every server unsandboxed.
      pb.environment().remove("TMPDIR");
      Process p = pb.start();
      p.getOutputStream().close();
      if (!p.waitFor(10, TimeUnit.SECONDS)) {
        ProcessTree.stop(p.toHandle(), java.time.Duration.ofMillis(200));
        return Probe.unknown("its probe didn't finish in 10 s");
      }
      String said = new String(p.getInputStream().readNBytes(4096), StandardCharsets.UTF_8).strip();
      int code = p.exitValue();
      if (code == 0) {
        return new Probe(windows ? "low-integrity" : mac ? "sandbox-exec" : "landlock", null);
      }
      if (code == NO_SANDBOX) {
        LOG.log(System.Logger.Level.INFO, "MCP servers run without a sandbox on this system: {0}",
            Texts.clean(said, 300, false));
        return Probe.NONE;
      }
      String first = Texts.clean(said.lines().findFirst().orElse(""), 200, false);
      return Probe.unknown("its probe exited with " + code + (first.isEmpty() ? "" : ": " + first));
    } catch (IOException e) {
      return Probe.unknown("its probe couldn't run: " + Texts.clean(String.valueOf(e.getMessage()), 200, false));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Probe.unknown("its probe was interrupted");
    }
  }

  private static Path realPath(Path p) {
    try {
      return p.toRealPath();
    } catch (IOException e) {
      return p.toAbsolutePath().normalize();
    }
  }

  /** Whether {@code path} is {@code folder} or lies in it, as given or as the file system resolves either. */
  private static boolean within(Path path, Path folder) {
    Path p = path.toAbsolutePath().normalize();
    Path f = folder.toAbsolutePath().normalize();
    return p.startsWith(f) || realPath(p).startsWith(realPath(f));
  }
}
