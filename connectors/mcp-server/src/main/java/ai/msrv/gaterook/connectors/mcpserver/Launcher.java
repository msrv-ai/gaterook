package ai.msrv.gaterook.connectors.mcpserver;

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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * How a stdio server is started (report 11 §4.1, §6.9, ARCHITECTURE.md §7.5): argv without a shell, the command found
 * on a PATH that also has what a login shell adds (a GUI app starts with {@code /usr/bin:/bin:/usr/sbin:/sbin}, where
 * Homebrew, nvm and Volta aren't), and an environment of only the basic user variables, the ones the person chose to
 * pass and the values of the server's secrets. Where {@code gw-bridge sandbox-exec} works (Landlock on Linux,
 * {@code sandbox-exec} on macOS) the process runs confined to its work folder and the system; elsewhere it runs as the
 * person's user and the review says so.
 */
final class Launcher {

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
  private final String path;
  private final Path sandboxLauncher;
  private final String sandbox;
  private final Map<String, Optional<String>> nodeVersions = new ConcurrentHashMap<>();

  /**
   * @param loginShellPath whether to ask {@code $SHELL -ilc} for the PATH a terminal would have (off in tests)
   * @param sandboxLauncher {@code ~/.mcp-gateway/bin/gw-bridge}, used when its {@code sandbox-exec} works here
   */
  Launcher(Map<String, String> coreEnv, String osName, boolean loginShellPath, Path sandboxLauncher) {
    this.coreEnv = Map.copyOf(coreEnv);
    String os = osName.toLowerCase(Locale.ROOT);
    this.windows = os.startsWith("windows");
    this.mac = os.startsWith("mac");
    String home = coreEnv.getOrDefault(windows ? "USERPROFILE" : "HOME", System.getProperty("user.home"));
    this.userHome = Path.of(home);
    this.path = computePath(loginShellPath);
    this.sandboxLauncher = sandboxLauncher;
    this.sandbox = probeSandbox();
  }

  String path() {
    return path;
  }

  /** {@code landlock}, {@code sandbox-exec}, or {@code null} where servers run unconfined. */
  String sandbox() {
    return sandbox;
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
    return environment(server.inheritEnv(), secrets, workDir, sandbox != null);
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
    }
    // Last: a secret's variable is what the person configured, even if a basic one has the same name.
    env.putAll(secrets);
    return env;
  }

  /** argv as it starts: the resolved command, wrapped by {@code gw-bridge sandbox-exec} where it works. */
  List<String> argv(Path command, List<String> args, Path workDir) {
    List<String> out = new ArrayList<>();
    if (sandbox != null) {
      out.add(sandboxLauncher.toString());
      out.add("sandbox-exec");
      out.add("--rw");
      out.add(workDir.toString());
      // The command's installation (an nvm or Volta tree in the home folder): its lib/ beside bin/.
      Path real = realPath(command);
      Path bin = real.getParent();
      if (bin != null && bin.getParent() != null && real.startsWith(userHome)) {
        out.add("--ro");
        out.add(bin.getParent().toString());
      }
      out.add("--");
    }
    out.add(command.toString());
    out.addAll(args);
    return out;
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

  /** Whether {@code gw-bridge sandbox-exec} confines a process on this system: one probe at start. */
  private String probeSandbox() {
    if (sandboxLauncher == null || !Files.isExecutable(sandboxLauncher) || windows) {
      return null;
    }
    Optional<Path> truth = resolve("true", userHome);
    if (truth.isEmpty()) {
      return null;
    }
    try {
      Process p = new ProcessBuilder(sandboxLauncher.toString(), "sandbox-exec", "--", truth.get().toString())
          .redirectErrorStream(true).start();
      p.getOutputStream().close();
      if (!p.waitFor(10, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return null;
      }
      p.getInputStream().readAllBytes();
      return p.exitValue() == 0 ? (mac ? "sandbox-exec" : "landlock") : null;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static Path realPath(Path p) {
    try {
      return p.toRealPath();
    } catch (IOException e) {
      return p.toAbsolutePath();
    }
  }
}
