package com.gaterook.pluginhost;

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
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Landlock through FFM, for the OS sandbox of a plugin process on Linux (ARCHITECTURE.md §7.5, §9 "Signatures of
 * plugins"): SecurityManager is gone for good (JEP 486), so the boundary is the kernel's. A ruleset reads only the
 * system, the JRE, the host's JAR and the plugin's own folder, and writes only the plugin's data folder and devices;
 * the rest of {@code $HOME} — GateRook's database and master key, {@code ~/.ssh}, {@code ~/.aws} — can't be opened,
 * and Landlock's ptrace scoping keeps another process's {@code /proc/<pid>} (the core's environment) out of reach too.
 *
 * <p><b>The network</b> stays open, since a plugin talks to its service, except the core's own listeners: from ABI 4
 * (Linux 6.7) the ruleset handles TCP bind and connect and allows them on every port but the closed ones, so a
 * plugin's own client (a JDBC driver, a raw socket, an address literal: nothing {@link GuardedResolverProvider} sees)
 * can neither reach the core's local and public ports nor take them over. Landlock judges a TCP port, not an address:
 * those ports are closed on every host. Its rules only allow, so this takes a rule per open port (65,534 with the two
 * default ports closed), about 50 ms at start and 4.5 MB of kernel memory for the host's domain (measured on Linux
 * 7.0). They judge genuine TCP only: since Landlock's erratum 1 the bind and connect hooks fire only for a
 * {@code sk_is_tcp} socket, so UDP and a stream protocol that falls back to TCP — MPTCP ({@code IPPROTO_MPTCP}), SCTP —
 * slip past the port rules. A plugin's native code can open an MPTCP socket, which the core's plain-TCP listener
 * answers as TCP, and so reach a closed port. Only a seccomp filter on the socket protocol would close that (§7.5).
 *
 * <p><b>Scoping</b> (Landlock ABI 6, Linux 6.12) closes two more ways out of the process's own domain, the same ones
 * {@code gw-bridge sandbox-exec} closes for MCP servers. The host can't connect to the session's abstract Unix sockets
 * (the D-Bus session bus, and through it the Secret Service keyring; X11) or signal the person's other processes; it
 * still signals its own threads, which share its domain, so the JVM runs. Pathname Unix sockets are a residual through
 * ABI 8: Landlock mediates opening a file, not {@code connect(2)} to a socket, so native code can reach a listener by
 * its path — a database's socket, which a plugin may need, but also {@code /run/user/<uid>/bus}. The file rules keep
 * the credentials on disk unreadable either way. ABI 9's {@code LANDLOCK_ACCESS_FS_RESOLVE_UNIX} would govern that
 * connect, at the cost of a plugin's own Unix-socket databases (§7.5).
 *
 * <p><b>A thread, not a process.</b> {@code landlock_restrict_self} and {@code PR_SET_NO_NEW_PRIVS} confine the calling
 * thread and the threads it creates afterwards, never the threads that already run. A JVM runs dozens before
 * {@code main} (and a virtual thread moves between carriers), so restricting the host itself left its plugin's calls
 * unconfined (review 2026-10-27: on Linux the secret-thief fixture read the master key on every call). The restriction
 * is therefore made by {@link SandboxExec}, a first stage that confines its main thread and then {@link #exec}s the
 * host's JVM: every thread of that process descends from the confined one. Same rights model as
 * {@code gw-bridge sandbox-exec} (K1).
 */
final class Sandbox {

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

  /** Landlock's network rights (ABI 4): binding a TCP socket to a port, connecting one to a port. */
  static final long BIND_TCP = 1L;
  static final long CONNECT_TCP = 1L << 1;
  /** The first ABI with network rules. */
  static final int NET_ABI = 4;

  /**
   * {@code landlock_ruleset_attr.scoped} (ABI 6, Linux 6.12): what the domain's processes can't reach outside it — the
   * session's abstract Unix sockets and the signals of the person's other processes. {@code bridge-cli}'s sandbox uses
   * the same two.
   */
  static final long SCOPE_ABSTRACT_UNIX_SOCKET = 1L;
  static final long SCOPE_SIGNAL = 1L << 1;
  /** The first ABI with {@code scoped}. */
  static final int SCOPE_ABI = 6;

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
   * The system a JVM needs to keep running: libraries, configuration, CA bundles and the resolver's configuration
   * ({@code /etc/resolv.conf} is often a link into {@code /run}), devices' metadata, and this process's own
   * {@code /proc} entries ({@code /proc/self} is opened before the exec, which keeps the pid).
   */
  static final List<String> SYSTEM_READ = List.of("/usr", "/lib", "/lib64", "/lib32", "/bin", "/sbin", "/etc",
      "/sys", "/nix", "/run/current-system", "/run/systemd/resolve", "/run/resolvconf",
      "/run/NetworkManager/resolv.conf", "/proc/self", "/proc/meminfo", "/proc/cpuinfo", "/proc/stat");
  /** {@code /dev/urandom}, {@code /dev/null}, a terminal: read and write devices, nothing else of {@code /dev}. */
  static final List<String> SYSTEM_WRITE = List.of("/dev");

  private Sandbox() {}

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

  // ── FFM ─────────────────────────────────────────────────────────────────────────────────────────────────────

  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC = LINKER.defaultLookup();
  private static final MemoryLayout STATE = Linker.Option.captureStateLayout();
  private static final VarHandle ERRNO = STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

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

  private static MethodHandle handle(String name, FunctionDescriptor fd, Linker.Option... options) {
    MemorySegment address = LIBC.find(name).orElseThrow(() -> new IllegalStateException("libc has no " + name));
    return LINKER.downcallHandle(address, fd, options);
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

  static long readRights(boolean directory) {
    return directory ? EXECUTE | READ_FILE | READ_DIR : EXECUTE | READ_FILE;
  }

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
   * {@code struct landlock_ruleset_attr} as ABI {@code abi} takes it: {@code handled_access_fs} (the rights of
   * {@link #handledRights}); from ABI 4 {@code handled_access_net}, the TCP rights when a port is to be closed and
   * else 0, so the network isn't handled; from ABI 6 {@code scoped}. The size says which fields are there: ABI 1's 8
   * bytes, which every later ABI accepts, when only the filesystem is handled; 16 with the network; the whole 24 with
   * scoping, whether or not a port is closed.
   */
  record RulesetAttr(long size, long handledFs, long handledNet, long scoped) {

    static RulesetAttr of(int abi, boolean closesPorts) {
      boolean scope = abi >= SCOPE_ABI;
      boolean net = closesPorts && abi >= NET_ABI;
      long size = scope ? 24 : net ? 16 : 8;
      return new RulesetAttr(size, handledRights(abi), net ? BIND_TCP | CONNECT_TCP : 0L,
          scope ? SCOPE_ABSTRACT_UNIX_SOCKET | SCOPE_SIGNAL : 0L);
    }

    /** Fills {@code attr}, a zeroed segment of at least {@link #size} bytes, with the fields the size covers. */
    void write(MemorySegment attr) {
      attr.set(ValueLayout.JAVA_LONG, 0, handledFs);
      if (size >= 16) {
        attr.set(ValueLayout.JAVA_LONG, 8, handledNet);
      }
      if (size >= 24) {
        attr.set(ValueLayout.JAVA_LONG, 16, scoped);
      }
    }
  }

  /**
   * Restricts <em>the calling thread</em> and every thread it creates from now on, for good: Landlock ruleset, no new
   * privileges, restrict self. Missing paths are skipped. From ABI 4 the TCP ports in {@code closedPorts} can be
   * neither bound nor connected to; before it they stay open, which the caller says. Only {@link SandboxExec} calls
   * it, on its main thread, just before it execs the host.
   */
  static void restrict(int abi, List<String> readOnly, List<String> readWrite, Set<Integer> closedPorts) {
    int[] tcpPorts = openPorts(abi, closedPorts);
    RulesetAttr spec = RulesetAttr.of(abi, tcpPorts.length > 0);
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
      MemorySegment state = arena.allocate(STATE);
      // The attr as this ABI takes it (RulesetAttr): handled_access_net stays at offset 8 for the --deny-tcp rules,
      // scoped at 16. The host only ever signals its own threads, which share its domain, so SCOPE_SIGNAL doesn't
      // stop the JVM; the core, outside the domain, can still stop the host.
      MemorySegment attr = arena.allocate(spec.size(), 8);
      spec.write(attr);
      long handled = spec.handledFs();
      long fd = (long) create.invokeExact(state, SYS_LANDLOCK_CREATE_RULESET, attr, spec.size(), 0);
      if (fd < 0) {
        throw new IllegalStateException("landlock_create_ruleset failed (errno " + errno(state) + ")");
      }
      int ruleset = (int) fd;
      // struct landlock_path_beneath_attr { __u64 allowed_access; __s32 parent_fd; } __attribute__((packed)).
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

  /**
   * Replaces this process with {@code argv} and {@code env} ({@code execve} on the calling thread: the new program
   * runs on it alone, with its Landlock domain); returns only when that fails, with the reason.
   */
  static String exec(List<String> argv, Map<String, String> env) {
    try (Arena arena = Arena.ofConfined()) {
      MethodHandle execve = handle("execve", EXECVE, Linker.Option.captureCallState("errno"));
      MemorySegment state = arena.allocate(STATE);
      MemorySegment args = strings(arena, argv);
      List<String> vars = new ArrayList<>();
      env.forEach((k, v) -> vars.add(k + "=" + v));
      MemorySegment envp = strings(arena, vars);
      System.err.flush();
      int rc = (int) execve.invokeExact(state, arena.allocateFrom(argv.getFirst(), StandardCharsets.UTF_8), args,
          envp);
      return "execve failed (errno " + errno(state) + ", rc " + rc + ")";
    } catch (Throwable t) {
      return "execve failed: " + t;
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

  private static int errno(MemorySegment state) {
    return (int) ERRNO.get(state, 0L);
  }
}
