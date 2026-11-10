package ai.msrv.gaterook.core.platform;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * The core's only foreign function calls (report 15 §9 rule 9). Every downcall shape used here is listed in the
 * native-image metadata of its system ({@code META-INF/native-image/ai.msrv.gaterook/core-ffm-linux} for
 * {@code prctl}, {@code core-ffm-windows} for the mandatory labels); where FFM or the function is unavailable (macOS
 * x64 in native) the methods degrade silently and report it.
 */
public final class NativeOs {

  private static final int PR_GET_DUMPABLE = 3;
  private static final int PR_SET_DUMPABLE = 4;

  private static final int SE_FILE_OBJECT = 1;
  private static final int LABEL_SECURITY_INFORMATION = 0x10;
  private static final int SDDL_REVISION_1 = 1;
  private static final int SYSTEM_MANDATORY_LABEL_ACE_TYPE = 0x11;
  /** {@code OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE}. */
  private static final int INHERITED_BY_FILES_AND_FOLDERS = 0x3;
  /** {@code SYSTEM_MANDATORY_LABEL_NO_WRITE_UP | NO_READ_UP | NO_EXECUTE_UP}. */
  private static final int NO_UP = 0x7;
  /** {@code SECURITY_MANDATORY_MEDIUM_RID}. */
  private static final int MEDIUM_RID = 0x2000;
  /** GateRook's home on Windows: medium, no read, write or execute up, inherited by its files and folders. */
  static final String HOME_LABEL = "S:(ML;OICI;NRNWNX;;;ME)";

  private NativeOs() {}

  /** Whether the current OS is Windows, the only one with mandatory integrity labels. */
  public static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
  }

  /**
   * Closes {@code dir} to low integrity (ARCHITECTURE.md §7.5): a medium mandatory label with no-read-up, no-write-up
   * and no-execute-up, inherited by everything in it, so a process GateRook sandboxes at low integrity reads, writes
   * and runs nothing there unless a folder is labelled for it. A folder that has the label already is left as it is,
   * so the label goes through the folder's tree once.
   *
   * @return {@code null} when {@code dir} carries the label, else why not
   */
  @SuppressWarnings("restricted") // native access is enabled for the core: JVM flag and manifest, native-image flag
  public static String closeToLowIntegrity(Path dir) {
    if (!isWindows()) {
      return "mandatory labels exist only on Windows";
    }
    try (Arena arena = Arena.ofConfined()) {
      Linker linker = Linker.nativeLinker();
      SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
      MethodHandle getNamed = linker.downcallHandle(find(advapi32, "GetNamedSecurityInfoW"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      MethodHandle toDescriptor = linker.downcallHandle(
          find(advapi32, "ConvertStringSecurityDescriptorToSecurityDescriptorW"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      MethodHandle saclOf = linker.downcallHandle(find(advapi32, "GetSecurityDescriptorSacl"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      MethodHandle setNamed = linker.downcallHandle(find(advapi32, "SetNamedSecurityInfoW"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      MethodHandle localFree = linker.downcallHandle(find(kernel32, "LocalFree"),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      MemorySegment name = arena.allocateFrom(dir.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);

      MemorySegment saclOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment currentOut = arena.allocate(ValueLayout.ADDRESS);
      if ((int) getNamed.invokeExact(name, SE_FILE_OBJECT, LABEL_SECURITY_INFORMATION, MemorySegment.NULL,
          MemorySegment.NULL, MemorySegment.NULL, saclOut, currentOut) == 0) {
        MemorySegment current = currentOut.get(ValueLayout.ADDRESS, 0);
        try {
          if (closed(saclOut.get(ValueLayout.ADDRESS, 0))) {
            return null;
          }
        } finally {
          MemorySegment unused = (MemorySegment) localFree.invokeExact(current);
        }
      }

      MemorySegment descriptorOut = arena.allocate(ValueLayout.ADDRESS);
      if ((int) toDescriptor.invokeExact(arena.allocateFrom(HOME_LABEL, StandardCharsets.UTF_16LE), SDDL_REVISION_1,
          descriptorOut, MemorySegment.NULL) == 0) {
        return "the label " + HOME_LABEL + " didn't parse";
      }
      MemorySegment descriptor = descriptorOut.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment present = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment acl = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment defaulted = arena.allocate(ValueLayout.JAVA_INT);
        if ((int) saclOf.invokeExact(descriptor, present, acl, defaulted) == 0
            || present.get(ValueLayout.JAVA_INT, 0) == 0) {
          return "the label " + HOME_LABEL + " has no SACL";
        }
        int rc = (int) setNamed.invokeExact(name, SE_FILE_OBJECT, LABEL_SECURITY_INFORMATION, MemorySegment.NULL,
            MemorySegment.NULL, MemorySegment.NULL, acl.get(ValueLayout.ADDRESS, 0));
        return rc == 0 ? null : "SetNamedSecurityInfo failed with error " + rc;
      } finally {
        MemorySegment unused = (MemorySegment) localFree.invokeExact(descriptor);
      }
    } catch (Throwable e) {
      // No FFM, native access not enabled, or a missing native-image registration: the caller logs the outcome.
      return e.toString();
    }
  }

  private static final int AF_INET = 2;
  /** {@code TCP_TABLE_OWNER_PID_CONNECTIONS}. */
  private static final int TCP_TABLE_OWNER_PID_CONNECTIONS = 4;
  /** {@code MIB_TCPROW_OWNER_PID}: state, local address and port, remote address and port, owning pid. */
  private static final int TCP_ROW_SIZE = 24;
  private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
  private static final int TOKEN_QUERY = 0x0008;
  private static final int TOKEN_INTEGRITY_LEVEL = 25;
  private static final int TOKEN_IS_APP_CONTAINER = 29;

  /**
   * The pid of the process whose socket {@code client}:{@code clientPort} (any IPv4 loopback address a process bound
   * it to) is connected to the port {@code serverPort} (the IPv4 TCP table), or -1 where it isn't found or can't be
   * asked.
   */
  @SuppressWarnings("restricted")
  public static long loopbackPeer(Inet4Address client, int serverPort, int clientPort) {
    if (!isWindows() || client == null) {
      return -1;
    }
    int clientAddress = tableAddress(client);
    try (Arena arena = Arena.ofConfined()) {
      Linker linker = Linker.nativeLinker();
      MethodHandle table = linker.downcallHandle(
          find(SymbolLookup.libraryLookup("iphlpapi", Arena.global()), "GetExtendedTcpTable"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
              ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
      MemorySegment size = arena.allocate(ValueLayout.JAVA_INT);
      for (int attempt = 0; attempt < 3; attempt++) {
        int needed = Math.max(size.get(ValueLayout.JAVA_INT, 0), 4);
        MemorySegment rows = arena.allocate(needed + 4096L, 8);
        size.set(ValueLayout.JAVA_INT, 0, (int) rows.byteSize());
        int rc = (int) table.invokeExact(rows, size, 0, AF_INET, TCP_TABLE_OWNER_PID_CONNECTIONS, 0);
        if (rc == 0) {
          return peerIn(rows, clientAddress, serverPort, clientPort);
        }
        if (rc != 122) { // ERROR_INSUFFICIENT_BUFFER: the table grew, ask again
          return -1;
        }
      }
      return -1;
    } catch (Throwable e) {
      return -1;
    }
  }

  /**
   * The owning pid of the client end {@code clientAddress}:{@code clientPort} (as {@link #tableAddress} writes it) of
   * a connection to {@code serverPort} in a {@code MIB_TCPTABLE_OWNER_PID}, or -1.
   */
  static long peerIn(MemorySegment rows, int clientAddress, int serverPort, int clientPort) {
    long count = Integer.toUnsignedLong(rows.get(ValueLayout.JAVA_INT_UNALIGNED, 0));
    for (long i = 0; i < count && 4 + (i + 1) * TCP_ROW_SIZE <= rows.byteSize(); i++) {
      long row = 4 + i * TCP_ROW_SIZE;
      int localAddress = rows.get(ValueLayout.JAVA_INT_UNALIGNED, row + 4);
      int localPort = port(rows.get(ValueLayout.JAVA_INT_UNALIGNED, row + 8));
      int remotePort = port(rows.get(ValueLayout.JAVA_INT_UNALIGNED, row + 16));
      if (localAddress == clientAddress && localPort == clientPort && remotePort == serverPort) {
        return Integer.toUnsignedLong(rows.get(ValueLayout.JAVA_INT_UNALIGNED, row + 20));
      }
    }
    return -1;
  }

  /** An IPv4 address as {@code GetExtendedTcpTable} stores it: network byte order read as a little-endian DWORD. */
  static int tableAddress(Inet4Address address) {
    byte[] b = address.getAddress();
    return (b[0] & 0xFF) | (b[1] & 0xFF) << 8 | (b[2] & 0xFF) << 16 | (b[3] & 0xFF) << 24;
  }

  /** A port as the TCP table stores it: network byte order in the low 16 bits. */
  static int port(int stored) {
    return ((stored & 0xFF) << 8) | ((stored >>> 8) & 0xFF);
  }

  /**
   * The integrity level RID of process {@code pid} (0x2000 medium, 0x1000 low), 0 for an AppContainer, or -1 where it
   * can't be read.
   */
  @SuppressWarnings("restricted")
  public static int integrityLevel(long pid) {
    if (!isWindows()) {
      return -1;
    }
    try (Arena arena = Arena.ofConfined()) {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
      SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
      MethodHandle openProcess = linker.downcallHandle(find(kernel32, "OpenProcess"),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
      MethodHandle openToken = linker.downcallHandle(find(advapi32, "OpenProcessToken"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      MethodHandle tokenInformation = linker.downcallHandle(find(advapi32, "GetTokenInformation"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      MethodHandle closeHandle = linker.downcallHandle(find(kernel32, "CloseHandle"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      MemorySegment process = (MemorySegment) openProcess.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, (int) pid);
      if (process.address() == 0) {
        return -1;
      }
      try {
        MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
        if ((int) openToken.invokeExact(process, TOKEN_QUERY, tokenOut) == 0) {
          return -1;
        }
        MemorySegment token = tokenOut.get(ValueLayout.ADDRESS, 0);
        try {
          MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
          MemorySegment appContainer = arena.allocate(ValueLayout.JAVA_INT);
          if ((int) tokenInformation.invokeExact(token, TOKEN_IS_APP_CONTAINER, appContainer, 4, length) != 0
              && appContainer.get(ValueLayout.JAVA_INT, 0) != 0) {
            return 0;
          }
          MemorySegment label = arena.allocate(128, 8);
          if ((int) tokenInformation.invokeExact(token, TOKEN_INTEGRITY_LEVEL, label, (int) label.byteSize(), length)
              == 0) {
            return -1;
          }
          return lastRid(label.get(ValueLayout.ADDRESS, 0).reinterpret(68));
        } finally {
          int unused = (int) closeHandle.invokeExact(token);
        }
      } finally {
        int unused = (int) closeHandle.invokeExact(process);
      }
    } catch (Throwable e) {
      return -1;
    }
  }

  /** Whether the ACL at {@code acl} (a native pointer, maybe null) starts with the home's label. */
  @SuppressWarnings("restricted")
  private static boolean closed(MemorySegment acl) {
    if (acl.address() == 0) {
      return false;
    }
    int size = Short.toUnsignedInt(acl.reinterpret(8).get(ValueLayout.JAVA_SHORT_UNALIGNED, 2));
    return closedAcl(acl.reinterpret(size));
  }

  /**
   * Whether an ACL's first mandatory label is medium, with no read, write or execute up, inherited by files and
   * folders. {@code ACL}: revision, padding, size and count of ACEs, padding (8 bytes); each ACE a header (type, flags,
   * size), then for a label its mask and SID.
   */
  static boolean closedAcl(MemorySegment acl) {
    if (acl.byteSize() < 8) {
      return false;
    }
    int count = Short.toUnsignedInt(acl.get(ValueLayout.JAVA_SHORT_UNALIGNED, 4));
    long offset = 8;
    for (int i = 0; i < count && offset + 8 <= acl.byteSize(); i++) {
      int type = Byte.toUnsignedInt(acl.get(ValueLayout.JAVA_BYTE, offset));
      int flags = Byte.toUnsignedInt(acl.get(ValueLayout.JAVA_BYTE, offset + 1));
      int aceSize = Short.toUnsignedInt(acl.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset + 2));
      if (aceSize < 8 || offset + aceSize > acl.byteSize()) {
        return false;
      }
      if (type == SYSTEM_MANDATORY_LABEL_ACE_TYPE) {
        int mask = acl.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 4);
        int rid = lastRid(acl.asSlice(offset + 8, aceSize - 8));
        return (mask & NO_UP) == NO_UP && rid == MEDIUM_RID
            && (flags & INHERITED_BY_FILES_AND_FOLDERS) == INHERITED_BY_FILES_AND_FOLDERS;
      }
      offset += aceSize;
    }
    return false;
  }

  /** The last sub-authority of a binary SID (for a mandatory-label SID its level), or -1. */
  static int lastRid(MemorySegment sid) {
    if (sid.byteSize() < 8) {
      return -1;
    }
    int count = Byte.toUnsignedInt(sid.get(ValueLayout.JAVA_BYTE, 1));
    long offset = 8 + 4L * (count - 1);
    if (count < 1 || count > 15 || sid.byteSize() < offset + 4) {
      return -1;
    }
    return sid.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
  }

  private static MemorySegment find(SymbolLookup lookup, String name) {
    return lookup.find(name).orElseThrow(() -> new IllegalStateException("no " + name));
  }

  /** Whether the current OS is Linux, the only one with {@code prctl}. */
  public static boolean isLinux() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
  }

  /**
   * {@code prctl(PR_SET_DUMPABLE, 0)}: no core dumps and no ptrace or {@code /proc/<pid>/mem} by other processes of
   * the same user, so the master key and secrets in memory don't end up in a dump (ARCHITECTURE.md §9).
   *
   * @return {@code true} when the process is now non-dumpable
   */
  public static boolean disableCoreDumps() {
    Optional<MethodHandle> prctl = prctl();
    if (prctl.isEmpty()) {
      return false;
    }
    try {
      int rc = (int) prctl.get().invokeExact(PR_SET_DUMPABLE, 0L);
      return rc == 0 && dumpable(prctl.get()) == 0;
    } catch (Throwable e) {
      return false;
    }
  }

  /** {@code prctl(PR_GET_DUMPABLE)}: 1 dumpable, 0 not, empty where it can't be asked. */
  public static Optional<Integer> dumpable() {
    Optional<MethodHandle> prctl = prctl();
    if (prctl.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(dumpable(prctl.get()));
    } catch (Throwable e) {
      return Optional.empty();
    }
  }

  private static int dumpable(MethodHandle prctl) throws Throwable {
    // The same (int, long) shape as PR_SET_DUMPABLE: the kernel ignores arg2 here, and one shape keeps the
    // native-image metadata to a single downcall.
    return (int) prctl.invokeExact(PR_GET_DUMPABLE, 0L);
  }

  /** {@code int prctl(int option, ...)}, variadic after the first argument. */
  @SuppressWarnings("restricted") // native access is enabled for the core: JVM flag and manifest, native-image flag
  private static Optional<MethodHandle> prctl() {
    if (!isLinux()) {
      return Optional.empty();
    }
    try {
      Linker linker = Linker.nativeLinker();
      return linker.defaultLookup().find("prctl").map(address -> linker.downcallHandle(address,
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
          Linker.Option.firstVariadicArg(1)));
    } catch (Throwable e) {
      // UnsupportedOperationException (no FFM on this platform), IllegalCallerException (native access not enabled)
      // or a missing native-image registration: hardening is best effort, the caller logs the outcome.
      return Optional.empty();
    }
  }
}
