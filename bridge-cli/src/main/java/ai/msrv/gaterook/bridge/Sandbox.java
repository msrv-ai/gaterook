package ai.msrv.gaterook.bridge;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code gw-bridge sandbox-exec [--ro <path>]… [--rw <path>]… [--no-defaults] [--allow-unsandboxed] -- <command> [args…]}
 * (ARCHITECTURE.md §7.5): starts an MCP server, or anything the core runs on the person's behalf, confined to what it
 * needs. On Linux (Landlock, kernel 5.13 or later) the bridge restricts itself — {@code PR_SET_NO_NEW_PRIVS}, a
 * Landlock ruleset that reads only the {@code --ro} paths and writes only the {@code --rw} paths — and then
 * {@code execve}s the command, which inherits the restriction and can't lift it. The rest of {@code $HOME}, MCP
 * Gateway's data included, can't be opened. The network isn't restricted: MCP servers need it.
 *
 * <p>Defaults (unless {@code --no-defaults}): the system read-only ({@code /usr}, {@code /lib*}, {@code /bin},
 * {@code /sbin}, {@code /etc}, {@code /opt}, {@code /proc}, {@code /sys}, {@code /nix}), {@code /dev} for reading and
 * writing devices, and the command's own folder read-only. On macOS the command runs under {@code sandbox-exec} with
 * a profile that denies reading MCP Gateway's home, the usual credential folders and the configs of the connected
 * clients except the {@code --rw} ones (the API is deprecated, but works on macOS 27). Elsewhere, or when Landlock is
 * unavailable, it refuses with exit 4, unless
 * {@code --allow-unsandboxed} says to run it as it is (the core then shows "Not sandboxed on this system").
 * Exit codes: 3 wrong arguments, 4 no sandbox, 126/127 the command can't run.
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

  private static final long SYS_LANDLOCK_CREATE_RULESET = 444;
  private static final long SYS_LANDLOCK_ADD_RULE = 445;
  private static final long SYS_LANDLOCK_RESTRICT_SELF = 446;
  private static final int LANDLOCK_CREATE_RULESET_VERSION = 1;
  private static final int LANDLOCK_RULE_PATH_BENEATH = 1;
  private static final int PR_SET_NO_NEW_PRIVS = 38;
  private static final int O_PATH = 0x200000;
  private static final int O_CLOEXEC = 0x80000;

  static final List<String> SYSTEM_READ = List.of("/usr", "/lib", "/lib64", "/lib32", "/bin", "/sbin", "/etc", "/opt",
      "/proc", "/sys", "/nix", "/run/current-system");
  static final List<String> SYSTEM_WRITE = List.of("/dev");

  /** The parsed command line. */
  record Request(List<String> readOnly, List<String> readWrite, boolean defaults, boolean allowUnsandboxed,
      List<String> command) {}

  private Sandbox() {}

  static Request parse(List<String> args) {
    List<String> ro = new ArrayList<>();
    List<String> rw = new ArrayList<>();
    boolean defaults = true;
    boolean allow = false;
    int i = 0;
    for (; i < args.size(); i++) {
      String a = args.get(i);
      switch (a) {
        case "--" -> {
          List<String> command = List.copyOf(args.subList(i + 1, args.size()));
          if (command.isEmpty()) {
            throw new IllegalArgumentException("sandbox-exec needs a command after --");
          }
          return new Request(List.copyOf(ro), List.copyOf(rw), defaults, allow, command);
        }
        case "--ro", "--rw" -> {
          if (i + 1 >= args.size()) {
            throw new IllegalArgumentException(a + " needs a path");
          }
          (a.equals("--ro") ? ro : rw).add(args.get(++i));
        }
        case "--no-defaults" -> defaults = false;
        case "--allow-unsandboxed" -> allow = true;
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
      err.println("usage: gw-bridge sandbox-exec [--ro <path>]… [--rw <path>]… [--no-defaults] [--allow-unsandboxed] "
          + "-- <command> [args…]");
      return 3;
    }
    Path executable = resolve(r.command().getFirst(), env);
    if (executable == null) {
      err.println("gw-bridge: " + r.command().getFirst() + ": command not found");
      return 127;
    }
    List<String> argv = new ArrayList<>(r.command());
    argv.set(0, executable.toString());
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.startsWith("linux")) {
      int abi = landlockAbi();
      if (abi < 1) {
        if (!r.allowUnsandboxed()) {
          err.println("gw-bridge: Landlock isn't available on this system, so the command can't be sandboxed");
          return NO_SANDBOX;
        }
        err.println("gw-bridge: not sandboxed on this system: the command can read everything your account can");
      } else {
        List<String> ro = new ArrayList<>(r.readOnly());
        List<String> rw = new ArrayList<>(r.readWrite());
        if (r.defaults()) {
          ro.addAll(SYSTEM_READ);
          rw.addAll(SYSTEM_WRITE);
          Path parent = executable.getParent();
          if (parent != null) {
            ro.add(parent.toString());
          }
        }
        try {
          restrict(abi, ro, rw);
        } catch (IllegalStateException e) {
          err.println("gw-bridge: " + e.getMessage());
          return NO_SANDBOX;
        }
      }
      return exec(argv, env, err);
    }
    if (os.startsWith("mac")) {
      List<String> wrapped = new ArrayList<>(List.of("/usr/bin/sandbox-exec", "-p", macProfile(env, r.readWrite())));
      wrapped.addAll(argv);
      return exec(wrapped, env, err);
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
    if (command.contains("/")) {
      Path p = Path.of(command).toAbsolutePath();
      return Files.isExecutable(p) ? p : null;
    }
    String path = env.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
    for (String dir : path.split(java.io.File.pathSeparator)) {
      if (dir.isEmpty()) {
        continue;
      }
      Path p = Path.of(dir, command);
      if (Files.isRegularFile(p) && Files.isExecutable(p)) {
        return p.toAbsolutePath();
      }
    }
    return null;
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
  // in this order: the ABI probe, create_ruleset, add_rule, restrict_self, prctl, open, close, execve; on macOS
  // src/main/native-mac/…: execve alone): a descriptor or option changed here must change there too.
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

  /** Restricts this process: Landlock ruleset, no new privileges, restrict self. Missing paths are skipped. */
  static void restrict(int abi, List<String> readOnly, List<String> readWrite) {
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
      // struct landlock_ruleset_attr { __u64 handled_access_fs; } — the size of ABI 1, which every later ABI accepts.
      MemorySegment attr = arena.allocate(8, 8);
      long handled = handledRights(abi);
      attr.set(ValueLayout.JAVA_LONG, 0, handled);
      long fd = (long) create.invokeExact(state, SYS_LANDLOCK_CREATE_RULESET, attr, 8L, 0);
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
   * SBPL: everything as usual, except reading MCP Gateway's home, the credential folders and the configs of the
   * clients MCP Gateway connects (Cursor's and Antigravity's may hold a token, Codex's and Claude's name grants), minus
   * the --rw ones (later rules win). Seatbelt compares {@code subpath} with the canonical path only — a rule for
   * {@code /tmp/x} doesn't stop reading {@code /private/tmp/x} — so every path is written as given and as the kernel
   * sees it.
   */
  static String macProfile(Map<String, String> env, List<String> readWrite) {
    Path home = Path.of(System.getProperty("user.home"));
    List<Path> denied = new ArrayList<>(List.of(BrokerClient.home(env)));
    for (String p : List.of(".ssh", ".aws", ".config/gcloud", ".kube", "Library/Keychains", ".docker", ".netrc",
        ".codex", ".cursor", ".gemini", "Library/Application Support/Claude")) {
      denied.add(home.resolve(p));
    }
    StringBuilder b = new StringBuilder("(version 1)\n(allow default)\n(deny file-read* file-write*\n");
    for (Path p : denied) {
      for (String form : canonicalForms(p)) {
        b.append("  (subpath ").append(sbplString(form)).append(")\n");
      }
    }
    b.append(")\n");
    for (String rw : readWrite) {
      for (String form : canonicalForms(Path.of(rw))) {
        b.append("(allow file-read* file-write* (subpath ").append(sbplString(form)).append("))\n");
      }
    }
    return b.toString();
  }

  /** {@code p} absolute, and its canonical form when that differs (a path not there yet: via its deepest parent). */
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
