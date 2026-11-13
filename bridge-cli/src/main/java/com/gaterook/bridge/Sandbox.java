package com.gaterook.bridge;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

/**
 * {@code gw-bridge sandbox-exec [--ro <path>]… [--rw <path>]… [--deny-tcp <port>]… [--no-defaults]
 * [--allow-unsandboxed] [--appcontainer] -- <command> [args…]} (ARCHITECTURE.md §7.5): starts an MCP server, or
 * anything the core runs on the person's behalf, confined to what it needs. On Linux (Landlock, kernel 5.13 or later) the bridge restricts
 * itself — {@code PR_SET_NO_NEW_PRIVS}, a Landlock ruleset that reads only the {@code --ro} paths and writes only the
 * {@code --rw} paths — and then {@code execve}s the command, which inherits the restriction and can't lift it. The
 * rest of {@code $HOME}, GateRook's data included, can't be opened.
 *
 * <p>The network stays open, since MCP servers need it, except the {@code --deny-tcp} ports: the core passes its own
 * listeners (its local API and the tunnel's listener), which the command's sockets may neither connect to nor bind,
 * on any address. On Linux that takes Landlock ABI 4 (Linux 6.7), whose rules only allow: the ruleset handles TCP bind
 * and connect and allows every other port, one rule each (port 0 included, which binds an ephemeral port). Landlock
 * judges TCP ports, not addresses, and TCP alone (not UDP, nor MPTCP). With an older ABI the ports stay open, which is
 * said on stderr. On macOS the profile denies them on every host ({@link #macProfile}).
 *
 * <p>Defaults (unless {@code --no-defaults}): the system read-only ({@code /usr}, {@code /lib*}, {@code /bin},
 * {@code /sbin}, {@code /etc}, {@code /opt}, {@code /proc}, {@code /sys}, {@code /nix}, and the resolver's
 * configuration in {@code /run}, where {@code /etc/resolv.conf} often links: without it no name resolves, and
 * {@code npm ci} can't reach its registry), {@code /dev} for reading and writing devices, and the command's own folder
 * read-only (only the command itself where that folder holds more: a command in the home). No path is opened that is
 * or holds the person's home, GateRook's home, its folder of all work folders or a {@link #DENIED} path, nor a
 * read-only path in GateRook's home, its folder of all work folders or a {@link #DENIED} path, nor one that is or holds
 * a folder many programs keep their files in ({@link #SHARED}: {@code ~/.local}, {@code ~/.config}, {@code ~/.cache}
 * …) ({@link Closed}; a read-only path elsewhere in the person's home, such as an nvm tree or a tool's environment in
 * {@code ~/.local/share}, is opened): such a {@code --ro} path is left
 * out and said on stderr, such a {@code --rw} path refused (exit 3) on every system. A writable path may lie in
 * GateRook's home or in its folder of all work folders, wherever that lies, where the work folders are.
 *
 * <p>On macOS the command runs under {@code sandbox-exec} with a profile that denies reading GateRook's home, its
 * folder of all work folders (also where a link at {@code mcp} leads), the usual credential folders and the configs of
 * the connected clients, and denies writing anywhere but the {@code --rw} paths, the temporary folders and the devices
 * (the API is deprecated, but works on macOS 27): a login item, a shell profile or a package in a cache shared with
 * the person's own tools, written from inside, would run unconfined later. Nor may it have anything outside the
 * sandbox act for it: it can't open applications, scripts or documents through Launch Services, send Apple events,
 * write preferences, signal a process outside its sandbox, or connect to a Unix socket other than the system's
 * resolver and its own folders' (a container command-line tool also reaches its engine's) ({@link #macProfile}).
 * The core gives a server its work folder as {@code HOME}, so {@code npx} and {@code uvx} keep their caches there.
 * Reading is open there except for the denied paths, so {@code --ro} adds nothing; {@code --no-defaults} can't be
 * honored and is refused with exit 4 unless {@code --allow-unsandboxed}. Seatbelt judges the path a link leads to, and
 * a command that writes in its {@code --rw} folder can replace an entry of it, such as the {@code tmp} that is its
 * {@code TMPDIR}, with a link before its next start. So a writable path of the profile ({@code --rw}, {@code TMPDIR})
 * is written as given, with the system's own aliases ({@code /tmp}, {@code /var}) spelled out and with the person's
 * home, GateRook's home or the folder of all work folders in it ({@code mcp}) in their resolved form, never as where a
 * link below them leads, and is refused (exit 3) when it is, or lies behind, such a link ({@link Closed#link}); the
 * links at or above those folders are the person's own. On Linux with Landlock ABI 6 or later the command is also
 * kept from abstract Unix sockets and signals outside its own tree. On Windows the command runs at low integrity in
 * a job that ends it with the bridge ({@link WindowsSandbox}), or, with
 * {@code --appcontainer}, in an AppContainer of its work folder that the bridge creates it in
 * ({@link WindowsAppContainer}); {@code --ro} and
 * {@code --deny-tcp} need nothing there (the core refuses its sandboxed processes' connections itself). Elsewhere, or
 * when Landlock is unavailable, it refuses with exit 4, unless {@code --allow-unsandboxed} says to run it as it is (the
 * core then shows "Not sandboxed on this system"). Exit codes: 3 wrong arguments, 4 no sandbox, 126/127 the command
 * can't run.
 */
final class Sandbox {

  static final int NO_SANDBOX = 4;

  /** Landlock access rights (linux/landlock.h), by the ABI version that introduced them. */
  static final long EXECUTE = 1L;
  static final long WRITE_FILE = 1L << 1;
  static final long READ_FILE = 1L << 2;
  static final long READ_DIR = 1L << 3;
  static final long REMOVE_DIR = 1L << 4;
  static final long REMOVE_FILE = 1L << 5;
  static final long MAKE_CHAR = 1L << 6;
  static final long MAKE_DIR = 1L << 7;
  static final long MAKE_REG = 1L << 8;
  static final long MAKE_SOCK = 1L << 9;
  static final long MAKE_FIFO = 1L << 10;
  static final long MAKE_BLOCK = 1L << 11;
  static final long MAKE_SYM = 1L << 12;
  static final long REFER = 1L << 13;
  static final long TRUNCATE = 1L << 14;
  static final long IOCTL_DEV = 1L << 15;

  /** {@code landlock_ruleset_attr.scoped} (ABI 6): what the process and its children can't reach outside the domain. */
  static final long SCOPE_ABSTRACT_UNIX_SOCKET = 1L;
  static final long SCOPE_SIGNAL = 1L << 1;

  /** Landlock's network rights (ABI 4): binding a TCP socket to a port, connecting one to a port. */
  static final long BIND_TCP = 1L;
  static final long CONNECT_TCP = 1L << 1;
  /** The first ABI with network rules. */
  static final int NET_ABI = 4;

  private static final long SYS_LANDLOCK_CREATE_RULESET = 444;
  private static final long SYS_LANDLOCK_ADD_RULE = 445;
  private static final long SYS_LANDLOCK_RESTRICT_SELF = 446;
  private static final int LANDLOCK_CREATE_RULESET_VERSION = 1;
  private static final int LANDLOCK_RULE_PATH_BENEATH = 1;
  private static final int LANDLOCK_RULE_NET_PORT = 2;
  private static final int PR_SET_NO_NEW_PRIVS = 38;
  private static final int O_PATH = 0x200000;
  private static final int O_CLOEXEC = 0x80000;

  /**
   * What any program reads. Landlock judges the file a link leads to, so {@code /etc/resolv.conf} needs its target as
   * well: systemd-resolved's, resolvconf's or NetworkManager's in {@code /run} (as the plugin host's sandbox has it).
   */
  static final List<String> SYSTEM_READ = List.of("/usr", "/lib", "/lib64", "/lib32", "/bin", "/sbin", "/etc", "/opt",
      "/proc", "/sys", "/nix", "/run/current-system", "/run/systemd/resolve", "/run/resolvconf",
      "/run/NetworkManager/resolv.conf");
  static final List<String> SYSTEM_WRITE = List.of("/dev");

  /**
   * The parsed command line; {@code deniedPorts} ascending. {@code appContainer}: on Windows, the command in an
   * AppContainer instead of at low integrity ({@link WindowsAppContainer}); ignored elsewhere.
   */
  record Request(List<String> readOnly, List<String> readWrite, Set<Integer> deniedPorts, boolean defaults,
      boolean allowUnsandboxed, boolean appContainer, List<String> command) {}

  private Sandbox() {}

  static Request parse(List<String> args) {
    List<String> ro = new ArrayList<>();
    List<String> rw = new ArrayList<>();
    Set<Integer> ports = new TreeSet<>();
    boolean defaults = true;
    boolean allow = false;
    boolean appContainer = false;
    int i = 0;
    for (; i < args.size(); i++) {
      String a = args.get(i);
      switch (a) {
        case "--" -> {
          List<String> command = List.copyOf(args.subList(i + 1, args.size()));
          if (command.isEmpty()) {
            throw new IllegalArgumentException("sandbox-exec needs a command after --");
          }
          return new Request(List.copyOf(ro), List.copyOf(rw), Collections.unmodifiableSet(ports), defaults, allow,
              appContainer, command);
        }
        case "--ro", "--rw" -> {
          if (i + 1 >= args.size()) {
            throw new IllegalArgumentException(a + " needs a path");
          }
          (a.equals("--ro") ? ro : rw).add(args.get(++i));
        }
        case "--deny-tcp" -> {
          String port = i + 1 < args.size() ? args.get(++i) : "";
          if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65_535) {
            throw new IllegalArgumentException("--deny-tcp needs a port from 1 to 65535");
          }
          ports.add(Integer.parseInt(port));
        }
        case "--no-defaults" -> defaults = false;
        case "--allow-unsandboxed" -> allow = true;
        case "--appcontainer" -> appContainer = true;
        default -> throw new IllegalArgumentException("unexpected " + a);
      }
    }
    throw new IllegalArgumentException("sandbox-exec needs -- before the command");
  }

  static int run(List<String> args, Map<String, String> env, PrintStream err) {
    Request r;
    try {
      r = parse(args);
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      err.println("usage: gw-bridge sandbox-exec [--ro <path>]… [--rw <path>]… [--deny-tcp <port>]… "
          + "[--no-defaults] [--allow-unsandboxed] [--appcontainer] -- <command> [args…]");
      return 3;
    }
    Closed closed = Closed.of(env);
    for (String rw : r.readWrite()) {
      String why = closed.refusal(Path.of(rw), true);
      if (why != null) {
        err.println("gw-bridge: --rw " + why + ": it can't be opened to the command");
        return 3;
      }
    }
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.startsWith("mac")) {
      // Nothing is started behind a link the command could have put there: see macProfile.
      String why = linkRefusal(closed, r.readWrite(), env.get("TMPDIR"));
      if (why != null) {
        err.println("gw-bridge: " + why + ": it can't be opened to the command");
        return 3;
      }
    }
    Path executable = resolve(r.command().getFirst(), env);
    if (executable == null) {
      err.println("gw-bridge: " + r.command().getFirst() + ": command not found");
      return 127;
    }
    List<String> argv = new ArrayList<>(r.command());
    argv.set(0, executable.toString());
    if (os.startsWith("linux")) {
      int abi = landlockAbi();
      if (abi < 1) {
        if (!r.allowUnsandboxed()) {
          err.println("gw-bridge: Landlock isn't available on this system, so the command can't be sandboxed");
          return NO_SANDBOX;
        }
        err.println("gw-bridge: not sandboxed on this system: the command can read everything your account can");
      } else {
        List<String> rw = new ArrayList<>(r.readWrite());
        if (r.defaults()) {
          rw.addAll(SYSTEM_WRITE);
        }
        if (!r.deniedPorts().isEmpty() && abi < NET_ABI) {
          err.println("gw-bridge: Landlock ABI " + abi + " can't close TCP ports (ABI 4, Linux 6.7, can): the "
              + "command can still reach GateRook's ports " + r.deniedPorts());
        }
        try {
          restrict(abi, readable(r, executable, closed, err), rw, r.deniedPorts());
        } catch (IllegalStateException e) {
          err.println("gw-bridge: " + e.getMessage());
          return NO_SANDBOX;
        }
      }
      return exec(argv, env, err);
    }
    if (os.startsWith("mac")) {
      if (!r.defaults()) {
        if (!r.allowUnsandboxed()) {
          err.println("gw-bridge: --no-defaults can't be honored on macOS: its sandbox can't keep the command to its "
              + "--ro paths");
          return NO_SANDBOX;
        }
        err.println("gw-bridge: on macOS the command reads more than its --ro paths: the system and the home, minus "
            + "GateRook's data and the credentials");
      }
      List<String> wrapped = new ArrayList<>(List.of("/usr/bin/sandbox-exec", "-p", macProfile(closed,
          r.readWrite(), r.deniedPorts(), env.get("TMPDIR"), containerSockets(executable))));
      wrapped.addAll(argv);
      return exec(wrapped, env, err);
    }
    if (os.startsWith("windows")) {
      // Low integrity, set on this process and inherited by the command (WindowsSandbox), or an AppContainer the
      // bridge starts the command in (WindowsAppContainer); the core refuses the command's connections to its own
      // ports itself, so --deny-tcp needs nothing here.
      if (r.appContainer()) {
        return WindowsAppContainer.run(argv, r.readWrite().stream().map(Path::of).toList(), env, err);
      }
      String why = WindowsSandbox.confine(r.readWrite().stream().map(Path::of).toList(), env);
      if (why == null) {
        return spawn(argv, err);
      }
      if (!r.allowUnsandboxed()) {
        err.println("gw-bridge: the command can't run at low integrity here: " + why);
        return NO_SANDBOX;
      }
      err.println("gw-bridge: not sandboxed on this system (" + why + "): the command can read everything your "
          + "account can");
      return spawn(argv, err);
    }
    if (r.allowUnsandboxed()) {
      err.println("gw-bridge: not sandboxed on this system: the command can read everything your account can");
      return spawn(argv, err);
    }
    err.println("gw-bridge: no sandbox on this system");
    return NO_SANDBOX;
  }

  /** A command name looked up in {@code PATH}, or an existing path; {@code null} when there is none. */
  static Path resolve(String command, Map<String, String> env) {
    return resolve(command, env, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
  }

  /**
   * {@link #resolve(String, Map)} for one system. On Windows a path has a drive or a backslash, {@code PATH} (any case)
   * is split at {@code ;}, and a name without an extension is tried with each of {@code PATHEXT}'s.
   */
  static Path resolve(String command, Map<String, String> env, boolean windows) {
    if (command.contains("/") || (windows && (command.contains("\\") || command.contains(":")))) {
      Path p = Path.of(command).toAbsolutePath();
      return Files.isRegularFile(p) && (windows || Files.isExecutable(p)) ? p : null;
    }
    String path = windows ? variable(env, "PATH", "") : env.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
    List<String> names = List.of(command);
    if (windows && !command.contains(".")) {
      names = new ArrayList<>();
      for (String ext : variable(env, "PATHEXT", ".COM;.EXE;.BAT;.CMD").split(";")) {
        if (!ext.isBlank()) {
          names.add(command + ext.strip().toLowerCase(Locale.ROOT));
        }
      }
    }
    for (String dir : path.split(windows ? ";" : ":")) {
      if (dir.isBlank()) {
        continue;
      }
      for (String name : names) {
        try {
          Path p = Path.of(dir.strip(), name);
          if (Files.isRegularFile(p) && (windows || Files.isExecutable(p))) {
            return p.toAbsolutePath();
          }
        } catch (java.nio.file.InvalidPathException e) {
          // A PATH entry that isn't a path on this system.
        }
      }
    }
    return null;
  }

  /** An environment variable whose name matches in any case, as Windows reads them. */
  private static String variable(Map<String, String> env, String name, String otherwise) {
    String exact = env.get(name);
    if (exact != null) {
      return exact;
    }
    for (Map.Entry<String, String> e : env.entrySet()) {
      if (e.getKey().equalsIgnoreCase(name)) {
        return e.getValue();
      }
    }
    return otherwise;
  }

  // ── What no grant opens ─────────────────────────────────────────────────────────────────────────────────────

  /**
   * Under the person's home, what the command may neither read nor write: GateRook's earlier home and its desktop
   * app's storage, the credentials of tools and registries, and the configs of the AI apps (GateRook's clients hold a
   * gateway token or name its grants; Claude Code's, Cursor's and Antigravity's own sign-ins). The plugin host's
   * profile on macOS denies the same list ({@code HostLauncher.MAC_DENIED}).
   */
  static final List<String> DENIED = List.of(".mcp-gateway", "Library/Application Support/GateRook",
      ".config/GateRook", ".ssh", ".aws", ".config/gcloud", ".kube", "Library/Keychains", ".docker", ".netrc",
      ".gnupg", ".config/gh", ".config/op", ".vault-token", ".pgpass", ".my.cnf", ".mylogin.cnf", ".azure", ".oci",
      ".password-store", ".npmrc", ".pypirc", ".git-credentials", ".config/git/credentials", ".cargo/credentials",
      ".cargo/credentials.toml", ".gem/credentials", ".m2/settings.xml", ".m2/settings-security.xml",
      ".gradle/gradle.properties", ".terraform.d/credentials.tfrc.json", ".cloudflared", ".config/ngrok", ".ngrok2",
      "Library/Application Support/ngrok", ".codex", ".cursor", ".gemini", ".claude", ".claude.json",
      ".config/github-copilot", "Library/Application Support/Claude", ".config/Claude",
      "Library/Application Support/Cursor", ".config/Cursor", "Library/Application Support/Antigravity",
      ".config/Antigravity");

  /**
   * Under the person's home, the folders many programs keep their files in (the XDG base folders, {@code ~/.local}
   * that holds two of them, macOS's {@code ~/Library}): no read-only path may be or hold one, since Landlock opens all
   * of a path, and {@code ~/.local} would open other programs' data, keyrings and histories. Paths in them, such as a
   * tool's own environment, may still be read. The launcher keeps the same list ({@code Launcher.sharedFolders}).
   */
  static final List<String> SHARED = List.of(".local", ".local/share", ".local/state", ".config", ".cache", "Library");

  /**
   * The folders the system itself keeps behind a link, and where they lead (macOS puts {@code /tmp}, {@code /var} and
   * {@code /etc} in {@code /private}). A writable path may pass them: no command can re-point them, and a grant for
   * the folder is written for both names ({@link Closed#grantForms}).
   */
  static final Map<String, String> SYSTEM_ALIASES = Map.of("/tmp", "/private/tmp", "/var", "/private/var", "/etc",
      "/private/etc");

  /** The folder of all MCP server work folders in GateRook's home: each server's is {@code mcp/<connection>}. */
  static final String WORK_FOLDERS = "mcp";

  /**
   * What the macOS profile denies a command besides files and ports: Launch Services opens ({@code lsopen}: an
   * application, a script or a document, one the command wrote included, would start outside this sandbox), Apple
   * events (another application would act for it) and preference writes (cfprefsd would write another program's
   * settings for it). The plugin host's profile denies the same ({@code HostLauncher.MAC_CLOSED}).
   */
  static final List<String> MAC_CLOSED = List.of("lsopen", "appleevent-send", "user-preference-write");

  /** The system's resolver: a name is looked up through this Unix socket on macOS. */
  static final String MAC_RESOLVER = "/private/var/run/mDNSResponder";

  /**
   * The container command-line tools whose engine's Unix socket the macOS profile opens to them: the command a
   * container-based server is started with talks to the engine, and the server itself runs in the container.
   */
  static final Set<String> CONTAINER_CLIS = Set.of("docker", "podman", "nerdctl");

  /** Where container engines on macOS put the socket that the container command-line tools use by default. */
  static final String CONTAINER_SOCKET = "/var/run/docker.sock";

  /**
   * What no grant may open: the person's home (from the account, not {@code HOME}, which the core sets to the work
   * folder), GateRook's home ({@code GW_HOME}, which its shim exports), its folder of all work folders and the
   * {@link #DENIED} paths in the home.
   */
  record Closed(Path home, Path gateway, List<Path> credentials) {

    static Closed of(Map<String, String> env) {
      Path home = Path.of(System.getProperty("user.home"));
      return new Closed(home, BrokerClient.home(env), DENIED.stream().map(home::resolve).toList());
    }

    /**
     * GateRook's home, the folder of all work folders in it, then the credential paths: what the command may neither
     * read nor write. The folder of all work folders is named on its own because the person may keep it on another
     * disk, behind a link at {@code <GateRook home>/mcp}: the other servers' work folders are then where the link
     * leads, outside GateRook's home as the file system resolves it, and Seatbelt judges that path.
     */
    List<Path> denied() {
      List<Path> all = new ArrayList<>(List.of(gateway, workFolders()));
      all.addAll(credentials);
      return all;
    }

    /** The folder of all work folders, as given: {@code <GateRook home>/mcp}. */
    Path workFolders() {
      return gateway.resolve(WORK_FOLDERS);
    }

    /**
     * Why {@code path} can't be opened to the command, or {@code null}. It may not be or hold the home or a denied
     * path: Landlock opens all of a path, and in SBPL a later allow reopens what an earlier deny closed. Nor may it lie
     * in a denied path, except a writable path in GateRook's home or in its folder of all work folders, wherever that
     * lies: the servers' work folders are there.
     */
    String refusal(Path path, boolean writable) {
      List<Path> forms = forms(path);
      if (within(forms(home), forms)) {
        return path + " holds the home folder";
      }
      for (Path d : denied()) {
        if (within(forms(d), forms)) {
          return path + " holds " + d;
        }
      }
      if (!writable) {
        for (String folder : SHARED) {
          Path shared = home.resolve(folder);
          if (within(forms(shared), forms)) {
            return path + " holds " + shared + ", where many programs keep their files";
          }
        }
      }
      if (!writable && within(forms, forms(gateway))) {
        return path + " is in " + gateway;
      }
      if (!writable && within(forms, forms(workFolders()))) {
        return path + " is in " + workFolders();
      }
      for (Path c : credentials) {
        if (within(forms, forms(c))) {
          return path + " is in " + c;
        }
      }
      return null;
    }

    /**
     * Why {@code path} can't be a writable path of the macOS profile because of a symbolic link ({@link #link}), or
     * {@code null}.
     */
    String linkRefusal(Path path) {
      Path link = link(path);
      if (link == null) {
        return null;
      }
      return path + (link.equals(path.toAbsolutePath().normalize()) ? " is a symbolic link"
          : " lies behind the symbolic link " + link);
    }

    /**
     * The first symbolic link on the way to {@code path}, the path itself last, that the command could have put there,
     * or {@code null}. Seatbelt judges the path a link leads to, and a command that writes in a folder can replace an
     * entry of it by a link, so a writable path must name the folder itself. Below the nearest of the person's home,
     * GateRook's home and the folder of all work folders in it ({@code <GateRook home>/mcp}), each as given or as
     * resolved, every component must be a real folder, and so must every component of a path in none of them, except
     * the system's own aliases ({@link Sandbox#SYSTEM_ALIASES}). The links at or above those three are the person's
     * own (GateRook's home or its {@code mcp} folder kept on another disk, a home that is a link), and the profile lets
     * the command write nothing there, so it can't re-point them. A server writes its own work folder below
     * {@code mcp}, which is why a link at its work folder, or below it, is refused.
     */
    Path link(Path path) {
      Path abs = path.toAbsolutePath().normalize();
      Path anchor = anchor(abs);
      Path root = abs.getRoot();
      for (int i = anchor == null ? 0 : anchor.getNameCount(); i < abs.getNameCount(); i++) {
        Path prefix = root.resolve(abs.subpath(0, i + 1));
        if (Files.isSymbolicLink(prefix) && !systemAlias(prefix)) {
          return prefix;
        }
      }
      return null;
    }

    /**
     * The forms a writable path is written in: as given, with the system's own alias spelled out ({@code /var/x} is
     * {@code /private/var/x} to the kernel) and, where it lies in the home, GateRook's home or the folder of all work
     * folders, with the nearest of those as the file system resolves it. Never the resolved form of what lies below:
     * that is where a link a command can re-point would lead ({@link #link}).
     */
    List<String> grantForms(Path path) {
      Path abs = path.toAbsolutePath().normalize();
      java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
      out.add(abs.toString());
      for (Map.Entry<String, String> alias : SYSTEM_ALIASES.entrySet()) {
        Path name = Path.of(alias.getKey());
        if (abs.startsWith(name)) {
          out.add(Path.of(alias.getValue()).resolve(name.relativize(abs)).toString());
        }
      }
      Path anchor = anchor(abs);
      Path real = anchor == null ? null : realPath(anchor);
      if (real != null) {
        out.add(real.resolve(anchor.relativize(abs)).toString());
      }
      return List.copyOf(out);
    }

    /**
     * The nearest of the home, GateRook's home and the folder of all work folders, as given or resolved, that holds
     * {@code abs}; else {@code null}.
     */
    private Path anchor(Path abs) {
      Path nearest = null;
      for (Path base : List.of(home, gateway, workFolders())) {
        Path given = base.toAbsolutePath().normalize();
        Path resolved = realPath(given);
        for (Path candidate : resolved == null ? List.of(given) : List.of(given, resolved)) {
          if (abs.startsWith(candidate) && (nearest == null || candidate.getNameCount() > nearest.getNameCount())) {
            nearest = candidate;
          }
        }
      }
      return nearest;
    }

    private static Path realPath(Path p) {
      try {
        return p.toRealPath();
      } catch (IOException e) {
        return null;
      }
    }

    /** Whether {@code link} is one of {@link Sandbox#SYSTEM_ALIASES} on this system. */
    private static boolean systemAlias(Path link) {
      String target = SYSTEM_ALIASES.get(link.toString());
      return target != null && Path.of(target).equals(realPath(link));
    }

    private static List<Path> forms(Path p) {
      return canonicalForms(p).stream().map(Path::of).toList();
    }

    /** Whether some form of {@code inner} is some form of {@code outer} or lies in it. */
    private static boolean within(List<Path> inner, List<Path> outer) {
      for (Path i : inner) {
        for (Path o : outer) {
          if (i.startsWith(o)) {
            return true;
          }
        }
      }
      return false;
    }
  }

  /**
   * What Landlock lets the command read: the {@code --ro} paths and the defaults (the system, the command's folder —
   * only the command where its folder holds more), minus each one that would open what {@code closed} keeps closed,
   * which is said on {@code err}.
   */
  static List<String> readable(Request r, Path executable, Closed closed, PrintStream err) {
    List<String> wanted = new ArrayList<>(r.readOnly());
    if (r.defaults()) {
      wanted.addAll(SYSTEM_READ);
    }
    List<String> out = new ArrayList<>();
    for (String p : wanted) {
      String why = closed.refusal(Path.of(p), false);
      if (why == null) {
        out.add(p);
      } else {
        err.println("gw-bridge: not readable to the command: " + why);
      }
    }
    if (r.defaults()) {
      // Nothing in GateRook's home: a command in a work folder is read through its --rw path.
      Path parent = executable.getParent();
      if (parent != null && closed.refusal(parent, false) == null) {
        out.add(parent.toString());
      } else if (closed.refusal(executable, false) == null) {
        out.add(executable.toString());
      }
    }
    return out;
  }

  // ── Linux: Landlock ─────────────────────────────────────────────────────────────────────────────────────────

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC = LINKER.defaultLookup();
  private static final StructLayout ERRNO = new StructLayout();

  /** The capture of {@code errno}, so a failure can say why. */
  private static final class StructLayout {
    final MemoryLayout layout = Linker.Option.captureStateLayout();
    final VarHandle errno = layout.varHandle(MemoryLayout.PathElement.groupElement("errno"));
  }

  private static MethodHandle handle(String name, FunctionDescriptor fd, Linker.Option... options) {
    MemorySegment address = LIBC.find(name).orElseThrow(() -> new IllegalStateException("libc has no " + name));
    return LINKER.downcallHandle(address, fd, options);
  }

  // The native image links only the downcalls its metadata lists (src/main/native-linux/.../reachability-metadata.json,
  // in this order: the ABI probe, create_ruleset, add_rule (path and port rules alike), restrict_self, prctl, open,
  // close, execve; on macOS src/main/native-mac/…: execve alone): a descriptor or option changed here must change
  // there too.
  private static final FunctionDescriptor SYSCALL_CREATE = FunctionDescriptor.of(ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor SYSCALL_ADD = FunctionDescriptor.of(ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor SYSCALL_RESTRICT = FunctionDescriptor.of(ValueLayout.JAVA_LONG,
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor PRCTL = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
      ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
  private static final FunctionDescriptor OPEN = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.JAVA_INT);
  private static final FunctionDescriptor CLOSE = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
  private static final FunctionDescriptor EXECVE = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
      ValueLayout.ADDRESS, ValueLayout.ADDRESS);

  /** The Landlock ABI version of this kernel; 0 or less when there is none (old kernel, LSM off). */
  static int landlockAbi() {
    try {
      MethodHandle create = handle("syscall", SYSCALL_CREATE, Linker.Option.firstVariadicArg(1));
      long v = (long) create.invokeExact(SYS_LANDLOCK_CREATE_RULESET, MemorySegment.NULL, 0L,
          LANDLOCK_CREATE_RULESET_VERSION);
      return (int) v;
    } catch (Throwable t) {
      return -1;
    }
  }

  /** Every right of ABI {@code abi}: the ruleset handles them all, so what isn't allowed is denied. */
  static long handledRights(int abi) {
    long all = EXECUTE | WRITE_FILE | READ_FILE | READ_DIR | REMOVE_DIR | REMOVE_FILE | MAKE_CHAR | MAKE_DIR | MAKE_REG
        | MAKE_SOCK | MAKE_FIFO | MAKE_BLOCK | MAKE_SYM;
    if (abi >= 2) {
      all |= REFER;
    }
    if (abi >= 3) {
      all |= TRUNCATE;
    }
    if (abi >= 5) {
      all |= IOCTL_DEV;
    }
    return all;
  }

  /** What a read-only path allows: run and read, list folders. */
  static long readRights(boolean directory) {
    return directory ? EXECUTE | READ_FILE | READ_DIR : EXECUTE | READ_FILE;
  }

  /** What a writable path allows: everything handled (on a file only the rights a file can have). */
  static long writeRights(int abi, boolean directory) {
    long all = handledRights(abi);
    long fileOnly = EXECUTE | WRITE_FILE | READ_FILE | TRUNCATE | IOCTL_DEV;
    return directory ? all : all & fileOnly;
  }

  /**
   * The TCP ports a ruleset of ABI {@code abi} opens when {@code closed} are to stay closed: every port but those
   * (port 0 too, which binds an ephemeral port), from ABI 4; none before, when nothing is closed, and the network
   * isn't handled then.
   */
  static int[] openPorts(int abi, Set<Integer> closed) {
    if (abi < NET_ABI || closed.isEmpty()) {
      return new int[0];
    }
    return IntStream.rangeClosed(0, 65_535).filter(p -> !closed.contains(p)).toArray();
  }

  /**
   * Restricts this process: Landlock ruleset, no new privileges, restrict self. Missing paths are skipped. From ABI 4
   * the TCP ports in {@code closedPorts} can be neither bound nor connected to; before it they stay open, which the
   * caller says.
   */
  static void restrict(int abi, List<String> readOnly, List<String> readWrite, Set<Integer> closedPorts) {
    int[] tcpPorts = openPorts(abi, closedPorts);
    boolean net = tcpPorts.length > 0;
    try (Arena arena = Arena.ofConfined()) {
      MethodHandle create = handle("syscall", SYSCALL_CREATE, Linker.Option.firstVariadicArg(1),
          Linker.Option.captureCallState("errno"));
      MethodHandle add = handle("syscall", SYSCALL_ADD, Linker.Option.firstVariadicArg(1),
          Linker.Option.captureCallState("errno"));
      MethodHandle restrictSelf = handle("syscall", SYSCALL_RESTRICT, Linker.Option.firstVariadicArg(1),
          Linker.Option.captureCallState("errno"));
      MethodHandle prctl = handle("prctl", PRCTL, Linker.Option.firstVariadicArg(1),
          Linker.Option.captureCallState("errno"));
      MethodHandle open = handle("open", OPEN, Linker.Option.firstVariadicArg(2),
          Linker.Option.captureCallState("errno"));
      MethodHandle close = handle("close", CLOSE);
      MemorySegment state = arena.allocate(ERRNO.layout);
      // struct landlock_ruleset_attr { __u64 handled_access_fs; __u64 handled_access_net; __u64 scoped; }: the
      // size of ABI 1 (8 bytes, which every later ABI accepts) up to ABI 5, or up to handled_access_net (16 bytes,
      // ABI 4) when ports are closed; from ABI 6 the whole struct, with the process scoped out of the abstract Unix
      // sockets (the session bus, X11) and the signals of the rest of the session. The network is handled only to
      // close ports: MCP servers need the rest of it.
      long size = abi >= 6 ? 24 : net ? 16 : 8;
      MemorySegment attr = arena.allocate(size, 8);
      long handled = handledRights(abi);
      attr.set(ValueLayout.JAVA_LONG, 0, handled);
      if (size >= 16) {
        attr.set(ValueLayout.JAVA_LONG, 8, net ? BIND_TCP | CONNECT_TCP : 0L);
      }
      if (abi >= 6) {
        attr.set(ValueLayout.JAVA_LONG, 16, SCOPE_ABSTRACT_UNIX_SOCKET | SCOPE_SIGNAL);
      }
      long fd = (long) create.invokeExact(state, SYS_LANDLOCK_CREATE_RULESET, attr, size, 0);
      if (fd < 0) {
        throw new IllegalStateException("landlock_create_ruleset failed (errno " + errno(state) + ")");
      }
      int ruleset = (int) fd;
      // struct landlock_path_beneath_attr { __u64 allowed_access; __s32 parent_fd; } __attribute__((packed)) — 12 bytes.
      MemorySegment beneath = arena.allocate(12, 8);
      for (int pass = 0; pass < 2; pass++) {
        for (String path : pass == 0 ? readOnly : readWrite) {
          Path p = Path.of(path).toAbsolutePath().normalize();
          if (!Files.exists(p)) {
            continue;
          }
          boolean dir = Files.isDirectory(p);
          long allowed = (pass == 0 ? readRights(dir) : writeRights(abi, dir)) & handled;
          int pathFd = (int) open.invokeExact(state, arena.allocateFrom(p.toString(), StandardCharsets.UTF_8),
              O_PATH | O_CLOEXEC);
          if (pathFd < 0) {
            continue;
          }
          beneath.set(ValueLayout.JAVA_LONG, 0, allowed);
          beneath.set(ValueLayout.JAVA_INT_UNALIGNED, 8, pathFd);
          long rc = (long) add.invokeExact(state, SYS_LANDLOCK_ADD_RULE, ruleset, LANDLOCK_RULE_PATH_BENEATH, beneath,
              0);
          int ignored = (int) close.invokeExact(pathFd);
          if (rc < 0) {
            throw new IllegalStateException("landlock_add_rule failed for " + p + " (errno " + errno(state) + ")");
          }
        }
      }
      // struct landlock_net_port_attr { __u64 allowed_access; __u64 port; }: one per open port.
      MemorySegment port = arena.allocate(16, 8);
      port.set(ValueLayout.JAVA_LONG, 0, BIND_TCP | CONNECT_TCP);
      for (int p : tcpPorts) {
        port.set(ValueLayout.JAVA_LONG, 8, p);
        long rc = (long) add.invokeExact(state, SYS_LANDLOCK_ADD_RULE, ruleset, LANDLOCK_RULE_NET_PORT, port, 0);
        if (rc < 0) {
          throw new IllegalStateException("landlock_add_rule failed for TCP port " + p + " (errno " + errno(state)
              + ")");
        }
      }
      int rc = (int) prctl.invokeExact(state, PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L);
      if (rc != 0) {
        throw new IllegalStateException("prctl(PR_SET_NO_NEW_PRIVS) failed (errno " + errno(state) + ")");
      }
      long restricted = (long) restrictSelf.invokeExact(state, SYS_LANDLOCK_RESTRICT_SELF, ruleset, 0);
      int ignored = (int) close.invokeExact(ruleset);
      if (restricted < 0) {
        throw new IllegalStateException("landlock_restrict_self failed (errno " + errno(state) + ")");
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException("Landlock can't be set up: " + t, t);
    }
  }

  private static int errno(MemorySegment state) {
    return (int) ERRNO.errno.get(state, 0L);
  }

  /** Replaces this process with {@code argv}; returns only when that fails. */
  static int exec(List<String> argv, Map<String, String> env, PrintStream err) {
    try (Arena arena = Arena.ofConfined()) {
      MethodHandle execve = handle("execve", EXECVE, Linker.Option.captureCallState("errno"));
      MemorySegment state = arena.allocate(ERRNO.layout);
      MemorySegment args = strings(arena, argv);
      List<String> vars = new ArrayList<>();
      env.forEach((k, v) -> vars.add(k + "=" + v));
      MemorySegment envp = strings(arena, vars);
      err.flush();
      System.out.flush();
      int rc = (int) execve.invokeExact(state, arena.allocateFrom(argv.getFirst(), StandardCharsets.UTF_8), args,
          envp);
      err.println("gw-bridge: can't run " + argv.getFirst() + " (errno " + errno(state) + ", rc " + rc + ")");
      return 126;
    } catch (Throwable t) {
      err.println("gw-bridge: can't run " + argv.getFirst() + ": " + t.getMessage());
      return 126;
    }
  }

  private static MemorySegment strings(Arena arena, List<String> values) {
    MemorySegment array = arena.allocate(ValueLayout.ADDRESS, values.size() + 1L);
    for (int i = 0; i < values.size(); i++) {
      array.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(values.get(i), StandardCharsets.UTF_8));
    }
    array.setAtIndex(ValueLayout.ADDRESS, values.size(), MemorySegment.NULL);
    return array;
  }

  /** Where there is no exec (Windows): the command as a child, its streams ours, its exit code ours. */
  private static int spawn(List<String> argv, PrintStream err) {
    try {
      return new ProcessBuilder(argv).inheritIO().start().waitFor();
    } catch (IOException e) {
      err.println("gw-bridge: can't run " + argv.getFirst() + ": " + e.getMessage());
      return 126;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 130;
    }
  }

  // ── macOS: sandbox-exec ─────────────────────────────────────────────────────────────────────────────────────

  /**
   * SBPL: reading as usual, except GateRook's home, its folder of all work folders and the {@link #DENIED} paths (the
   * credentials and the configs of the AI apps: Cursor's and Antigravity's may hold a token, Codex's and Claude's
   * name grants); writing nowhere but the {@code --rw} paths, the temporary folders and the devices. The rest of the
   * home stays closed to writes, the caches the person's own tools share included: a login item, a shell profile,
   * another app's settings, a repository's hooks or a package planted from inside would run unconfined later. The
   * core gives a server its work folder as {@code HOME}, so {@code npx} and {@code uvx} cache there. The folders
   * above a {@code --rw} path may be stat-ed, not read: GateRook's home is closed, yet the work folders of its MCP
   * servers live in it. Later rules win in SBPL, whichever is narrower, so the allows that reopen the work folders
   * come after the denial, and a {@code --rw} path that would reopen a denied one is refused before
   * ({@link Closed#refusal}). Seatbelt compares {@code subpath} with the canonical path only — a rule for
   * {@code /tmp/x} doesn't stop reading {@code /private/tmp/x} — so a denied path is written as given and as the
   * kernel sees it. The folder of all work folders is denied on its own for that reason: kept on another disk behind
   * a link, it lies outside GateRook's home as the kernel sees it, and the other servers' work folders (their
   * {@code HOME}, with caches and tokens) would stay readable.
   *
   * <p>A path the command may write is written differently, as {@link Closed#grantForms} has it: as given, with the
   * system's own aliases spelled out, and below the person's home, GateRook's home or its folder of all work folders
   * never as where a link leads. The command writes in its {@code --rw} folder, so it can replace an entry of it,
   * such as the {@code tmp} that is its {@code TMPDIR}, by a link before its next start; a grant that followed the
   * link would let that start write wherever it leads. {@code tmpDir} (the command's {@code TMPDIR}, or
   * {@code null}) is granted only when it is none of the paths {@link Closed#refusal} keeps closed and no link lies
   * on its way ({@link Closed#link}); {@link #run} refuses the start before that, so the profile is also right when
   * a link is put there after the check.
   *
   * <p>The profile starts from {@code (allow default)}, so what else a program does is open unless it is denied here:
   * {@link #MAC_CLOSED} (Launch Services, Apple events, preference writes: each would have something outside the
   * sandbox act for the command, an application it wrote itself or another program's settings included), signals to
   * any process outside its own sandbox (the person's other programs, the core), and connecting to a Unix socket
   * anywhere but the system's resolver ({@link #MAC_RESOLVER}), the {@code --rw} paths and its {@code TMPDIR}: the
   * person's SSH agent, a container engine, a terminal multiplexer's or an editor's socket would each act for it
   * outside the sandbox. A container command-line tool ({@link #CONTAINER_CLIS}) reaches its engine's socket
   * ({@code containerSockets}), since that is what it is started for. Seatbelt judges a socket by the path it really
   * has, so a link to another socket opens nothing. Mach services stay open (the keychain daemon and the pasteboard
   * among them); a hard link in a writable folder to a file elsewhere, and a launchd job, were already refused under
   * this profile (checked on macOS 27).
   *
   * <p>The network is open but for the {@code --deny-tcp} ports, which the command may neither connect to nor bind,
   * on every host: Seatbelt takes only {@code *} or {@code localhost} as a host, and a rule for {@code localhost} lets
   * {@code ::ffff:0.0.0.0} through, the IPv4-mapped wildcard that the kernel connects to {@code 127.0.0.1} (checked
   * on macOS 27).
   */
  static String macProfile(Map<String, String> env, List<String> readWrite, Set<Integer> deniedPorts) {
    return macProfile(Closed.of(env), readWrite, deniedPorts, env.get("TMPDIR"));
  }

  static String macProfile(Closed closed, List<String> readWrite, Set<Integer> deniedPorts, String tmpDir) {
    return macProfile(closed, readWrite, deniedPorts, tmpDir, List.of());
  }

  /**
   * @param sockets Unix sockets the command may connect to besides its own folders and the resolver: a container
   *                engine's, for a container command-line tool ({@code containerSockets})
   */
  static String macProfile(Closed closed, List<String> readWrite, Set<Integer> deniedPorts, String tmpDir,
      List<String> sockets) {
    StringBuilder b = new StringBuilder("(version 1)\n(allow default)\n(deny file-write* (subpath \"/\"))\n");
    List<Path> writable = new ArrayList<>(List.of(Path.of("/dev"), Path.of("/tmp"), Path.of("/private/tmp")));
    Path ownTmp = null;
    if (tmpDir != null && !tmpDir.isBlank()) {
      Path tmp = Path.of(tmpDir);
      if (closed.refusal(tmp, true) == null && closed.link(tmp) == null) {
        writable.add(tmp);
        ownTmp = tmp;
      }
    }
    for (Path p : writable) {
      for (String form : closed.grantForms(p)) {
        b.append("(allow file-write* (subpath ").append(sbplString(form)).append("))\n");
      }
    }
    b.append("(deny file-read* file-write*\n");
    for (Path p : closed.denied()) {
      for (String form : canonicalForms(p)) {
        b.append("  (subpath ").append(sbplString(form)).append(")\n");
      }
    }
    b.append(")\n");
    for (String rw : readWrite) {
      for (String form : closed.grantForms(Path.of(rw))) {
        b.append("(allow file-read* file-write* (subpath ").append(sbplString(form)).append("))\n");
      }
    }
    // A program finds its own path by lstat-ing every folder above it (Node's realpath does, before any script runs):
    // the folders above a --rw path, GateRook's home and its mcp/ among them, give their metadata, never a listing or
    // a file.
    java.util.LinkedHashSet<String> above = new java.util.LinkedHashSet<>();
    for (String rw : readWrite) {
      for (String form : closed.grantForms(Path.of(rw))) {
        for (Path p = Path.of(form).getParent(); p != null; p = p.getParent()) {
          above.add(p.toString());
        }
      }
    }
    if (!above.isEmpty()) {
      b.append("(allow file-read-metadata\n");
      for (String p : above) {
        b.append("  (literal ").append(sbplString(p)).append(")\n");
      }
      b.append(")\n");
    }
    b.append("(deny ").append(String.join(" ", MAC_CLOSED)).append(")\n");
    b.append("(deny signal)\n(allow signal (target same-sandbox))\n");
    b.append("(deny network-outbound (remote unix-socket))\n");
    b.append("(allow network-outbound\n  (remote unix-socket (path-literal ").append(sbplString(MAC_RESOLVER))
        .append("))\n");
    java.util.LinkedHashSet<String> own = new java.util.LinkedHashSet<>();
    for (String rw : readWrite) {
      own.addAll(closed.grantForms(Path.of(rw)));
    }
    if (ownTmp != null) {
      own.addAll(closed.grantForms(ownTmp));
    }
    for (String form : own) {
      b.append("  (remote unix-socket (subpath ").append(sbplString(form)).append("))\n");
    }
    for (String socket : sockets) {
      b.append("  (remote unix-socket (path-literal ").append(sbplString(socket)).append("))\n");
    }
    b.append(")\n");
    if (!deniedPorts.isEmpty()) {
      // Several filters in one rule are OR'ed; "*" is every host, the mapped wildcard included.
      Set<Integer> ports = new TreeSet<>(deniedPorts);
      b.append("(deny network-outbound");
      ports.forEach(p -> b.append(" (remote ip \"*:").append(p).append("\")"));
      b.append(")\n(deny network-bind");
      ports.forEach(p -> b.append(" (local ip \"*:").append(p).append("\")"));
      b.append(")\n");
    }
    return b.toString();
  }

  /**
   * The Unix sockets a container command-line tool ({@link #CONTAINER_CLIS}) may connect to on macOS:
   * {@link #CONTAINER_SOCKET} as given and where it leads (Docker Desktop, OrbStack and Podman's helper link it into
   * the person's home), since Seatbelt judges a socket by the path it really has. The tool's own settings in
   * {@code ~/.docker} are closed to it ({@link #DENIED}), so that is the socket it uses. Empty for any other command.
   */
  static List<String> containerSockets(Path executable) {
    Path name = executable.getFileName();
    if (name == null || !CONTAINER_CLIS.contains(name.toString().toLowerCase(Locale.ROOT))) {
      return List.of();
    }
    return canonicalForms(Path.of(CONTAINER_SOCKET));
  }

  /**
   * Why the macOS profile can't name one of its writable paths — a {@code --rw} path, or the command's {@code TMPDIR}
   * ({@code tmpDir}, or {@code null}) — because it is, or lies behind, a symbolic link ({@link Closed#link}); else
   * {@code null}.
   */
  static String linkRefusal(Closed closed, List<String> readWrite, String tmpDir) {
    for (String rw : readWrite) {
      String why = closed.linkRefusal(Path.of(rw));
      if (why != null) {
        return "--rw " + why;
      }
    }
    if (tmpDir != null && !tmpDir.isBlank()) {
      String why = closed.linkRefusal(Path.of(tmpDir));
      if (why != null) {
        return "TMPDIR " + why;
      }
    }
    return null;
  }

  /**
   * {@code p} absolute, and its canonical form when that differs (a path not there yet: via its deepest parent). For
   * what is kept closed, never for what is opened: the canonical form of a path the command can write below is where
   * its links lead ({@link Closed#grantForms}).
   */
  static List<String> canonicalForms(Path p) {
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
        Path real = existing.toRealPath();
        for (Path name : missing) {
          real = real.resolve(name);
        }
        out.add(real.toString());
      } catch (IOException e) {
        // Unreadable: the path as given is all there is.
      }
    }
    return List.copyOf(out);
  }

  private static String sbplString(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
