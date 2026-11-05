package ai.msrv.gaterook.bridge;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code gw-bridge sandbox-exec --appcontainer} on Windows (ARCHITECTURE.md §7.5): the command in an AppContainer, for
 * an MCP server the person keeps off this computer. Its files are closed to it unless a folder grants its container,
 * which only its work folder (all of it) and the command's own folder outside {@code Program Files} (read and run) do;
 * the internet and the private network stay open ({@code internetClient}, {@code privateNetworkClientServer}), this
 * computer's services don't: an AppContainer has no loopback. Each work folder has its own container, so servers don't
 * share files.
 *
 * <p>An AppContainer can't be entered by a running process, so the bridge creates the command itself
 * ({@code CreateProcessW} with the container's security capabilities and only its standard handles), after
 * {@link WindowsSandbox#prepare} put the bridge in the command's job and labelled the work folder low. It runs
 * executables only: {@code npx.cmd} and {@code npm.cmd} beside {@code node.exe} become node and npm's own script, and
 * any other batch file is refused, since {@code cmd.exe} would parse the server's arguments again.
 */
final class WindowsAppContainer {

  /** What the core reports for a server in an AppContainer. */
  static final String KIND = "appcontainer";

  static final int INTERNET_CLIENT = 1;
  static final int PRIVATE_NETWORK_CLIENT_SERVER = 3;
  static final int SE_GROUP_ENABLED = 0x4;
  static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002L;
  static final long PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x00020009L;
  static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
  static final int STARTF_USESTDHANDLES = 0x00000100;
  static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
  static final int HANDLE_FLAG_INHERIT = 0x1;
  static final int INFINITE = 0xFFFFFFFF;
  static final int SE_FILE_OBJECT = 1;
  static final int DACL_SECURITY_INFORMATION = 0x4;
  static final int GRANT_ACCESS = 1;
  static final int SUB_CONTAINERS_AND_OBJECTS_INHERIT = 3;
  static final int TRUSTEE_IS_SID = 0;
  static final int FILE_ALL_ACCESS = 0x1F01FF;
  static final int FILE_READ_AND_EXECUTE = 0x1200A9;
  static final int ALREADY_EXISTS = 0x800700B7;
  /** {@code STARTUPINFOEXW} on 64-bit Windows: {@code STARTUPINFOW} (104 bytes), then the attribute list. */
  static final long STARTUPINFOEX_SIZE = 112;

  private WindowsAppContainer() {}

  /** A capability SID S-1-15-3-{@code rid}: revision 1, two sub-authorities, authority 15 (app package). */
  static byte[] capabilitySid(int rid) {
    return new byte[] {1, 2, 0, 0, 0, 0, 0, 15, 3, 0, 0, 0, (byte) rid, 0, 0, 0};
  }

  /** The container of one work folder: {@code GateRook.McpServer.} and 16 hex digits of its path's SHA-256. */
  static String containerName(Path workDir) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(
          workDir.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      return "GateRook.McpServer." + HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * argv an AppContainer runs: an executable as it is; {@code npx.cmd} or {@code npm.cmd} beside {@code node.exe} as
   * node with npm's script; {@code null} for any other script.
   */
  static List<String> executable(List<String> argv) {
    Path command = Path.of(argv.getFirst());
    String name = command.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".exe")) {
      return argv;
    }
    if ((name.equals("npx.cmd") || name.equals("npm.cmd")) && command.getParent() != null) {
      Path dir = command.getParent();
      Path node = dir.resolve("node.exe");
      Path script = dir.resolve("node_modules").resolve("npm").resolve("bin")
          .resolve(name.equals("npx.cmd") ? "npx-cli.js" : "npm-cli.js");
      if (Files.isRegularFile(node) && Files.isRegularFile(script)) {
        List<String> out = new ArrayList<>(List.of(node.toString(), script.toString()));
        out.addAll(argv.subList(1, argv.size()));
        return out;
      }
    }
    return null;
  }

  /** argv as one Windows command line, quoted the way {@code CommandLineToArgvW} reads it back. */
  static String commandLine(List<String> argv) {
    StringBuilder b = new StringBuilder();
    for (String arg : argv) {
      if (!b.isEmpty()) {
        b.append(' ');
      }
      b.append(quote(arg));
    }
    return b.toString();
  }

  static String quote(String arg) {
    if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '"')) {
      return arg;
    }
    StringBuilder b = new StringBuilder("\"");
    int backslashes = 0;
    for (int i = 0; i < arg.length(); i++) {
      char c = arg.charAt(i);
      if (c == '\\') {
        backslashes++;
        continue;
      }
      // Backslashes count only before a quote: doubled, plus one to escape the quote itself.
      b.append("\\".repeat(c == '"' ? backslashes * 2 + 1 : backslashes)).append(c);
      backslashes = 0;
    }
    return b.append("\\".repeat(backslashes * 2)).append('"').toString();
  }

  /** Node's flags in an AppContainer: no realpath of a module's folders, which starts at the drive's root. */
  static final String NODE_IN_CONTAINER = "--preserve-symlinks --preserve-symlinks-main";

  /**
   * The command's environment: the bridge's, and for Node, {@code NODE_OPTIONS} that keep its module loader from the
   * drive's root, which an AppContainer can't read (it resolves real paths component by component). npm's children
   * inherit it.
   */
  static Map<String, String> environment(List<String> command, Map<String, String> env) {
    Map<String, String> out = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    out.putAll(env);
    String program = command.getFirst();
    String name = program.substring(Math.max(program.lastIndexOf('\\'), program.lastIndexOf('/')) + 1);
    if (name.equalsIgnoreCase("node.exe")) {
      String options = out.get("NODE_OPTIONS");
      out.put("NODE_OPTIONS", options == null || options.isBlank() ? NODE_IN_CONTAINER
          : options.strip() + " " + NODE_IN_CONTAINER);
    }
    return out;
  }

  /** A Unicode environment block: {@code NAME=value} entries, each ended by a NUL, sorted, then one more NUL. */
  static String environmentBlock(Map<String, String> env) {
    StringBuilder b = new StringBuilder();
    env.entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
        .filter(e -> !e.getKey().isEmpty() && e.getKey().indexOf('=') < 0 && e.getKey().indexOf('\0') < 0
            && e.getValue().indexOf('\0') < 0)
        .forEach(e -> b.append(e.getKey()).append('=').append(e.getValue()).append('\0'));
    return b.append('\0').toString();
  }

  /** Folders the system already opens to every AppContainer: nothing to grant there. */
  static boolean systemFolder(Path dir, Map<String, String> env) {
    for (String variable : List.of("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432", "SystemRoot")) {
      String root = variable(env, variable);
      if (root != null && !root.isBlank() && dir.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT)
          .startsWith(Path.of(root).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private static String variable(Map<String, String> env, String name) {
    String exact = env.get(name);
    if (exact != null) {
      return exact;
    }
    return env.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
        .findFirst().orElse(null);
  }

  /**
   * Runs {@code argv} in its work folder's AppContainer and waits for it: its exit code, or {@link Sandbox#NO_SANDBOX}
   * with the reason on {@code err} when it can't.
   */
  static int run(List<String> argv, List<Path> readWrite, Map<String, String> env, PrintStream err) {
    List<String> command = executable(argv);
    if (command == null) {
      err.println("gw-bridge: an AppContainer runs executables only, and " + Path.of(argv.getFirst()).getFileName()
          + " is a script: turn \"Keep it off this computer\" off, or run node, python or the server's .exe");
      return Sandbox.NO_SANDBOX;
    }
    if (readWrite.isEmpty()) {
      err.println("gw-bridge: an AppContainer needs the server's work folder (--rw)");
      return Sandbox.NO_SANDBOX;
    }
    String prepared = WindowsSandbox.prepare(readWrite, env);
    if (prepared != null) {
      err.println("gw-bridge: the command can't run in an AppContainer here: " + prepared);
      return Sandbox.NO_SANDBOX;
    }
    try (Arena arena = Arena.ofConfined()) {
      Calls c = new Calls();
      MemorySegment state = arena.allocate(c.state);
      MemorySegment container = c.container(arena, containerName(readWrite.getFirst()));
      if (container == null) {
        err.println("gw-bridge: couldn't create the server's AppContainer profile");
        return Sandbox.NO_SANDBOX;
      }
      try {
        for (Path p : readWrite) {
          String why = c.grant(arena, p, container, FILE_ALL_ACCESS);
          if (why != null) {
            err.println("gw-bridge: " + why);
            return Sandbox.NO_SANDBOX;
          }
        }
        // The program's folder (Node's, with npm's script inside), unless the system or the work folder opens it.
        Path folder = Path.of(command.getFirst()).toAbsolutePath().getParent();
        boolean inWorkFolder = folder != null
            && readWrite.stream().anyMatch(rw -> folder.startsWith(rw.toAbsolutePath()));
        if (folder != null && !inWorkFolder && !systemFolder(folder, env)) {
          String why = c.grant(arena, folder, container, FILE_READ_AND_EXECUTE);
          if (why != null) {
            err.println("gw-bridge: " + why);
            return Sandbox.NO_SANDBOX;
          }
        }
        return c.start(arena, state, container, commandLine(command), environmentBlock(environment(command, env)),
            err);
      } finally {
        c.freeSid(container);
      }
    } catch (Throwable t) {
      err.println("gw-bridge: the command can't run in an AppContainer here: " + t);
      return Sandbox.NO_SANDBOX;
    }
  }

  /** The userenv, advapi32 and kernel32 calls, made only on Windows. */
  private static final class Calls {

    final MemoryLayout state = Linker.Option.captureStateLayout();
    final VarHandle lastError = state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    final MethodHandle createProfile;
    final MethodHandle deriveSid;
    final MethodHandle freeSid;
    final MethodHandle getNamed;
    final MethodHandle setEntries;
    final MethodHandle setNamed;
    final MethodHandle localFree;
    final MethodHandle getStdHandle;
    final MethodHandle setHandleInformation;
    final MethodHandle initializeAttributes;
    final MethodHandle updateAttribute;
    final MethodHandle deleteAttributes;
    final MethodHandle createProcess;
    final MethodHandle waitFor;
    final MethodHandle exitCode;
    final MethodHandle closeHandle;

    Calls() {
      Linker linker = Linker.nativeLinker();
      SymbolLookup userenv = SymbolLookup.libraryLookup("userenv", Arena.global());
      SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
      ValueLayout a = ValueLayout.ADDRESS;
      ValueLayout i = ValueLayout.JAVA_INT;
      ValueLayout l = ValueLayout.JAVA_LONG;
      createProfile = linker.downcallHandle(find(userenv, "CreateAppContainerProfile"),
          FunctionDescriptor.of(i, a, a, a, a, i, a));
      deriveSid = linker.downcallHandle(find(userenv, "DeriveAppContainerSidFromAppContainerName"),
          FunctionDescriptor.of(i, a, a));
      freeSid = linker.downcallHandle(find(advapi32, "FreeSid"), FunctionDescriptor.of(a, a));
      getNamed = linker.downcallHandle(find(advapi32, "GetNamedSecurityInfoW"),
          FunctionDescriptor.of(i, a, i, i, a, a, a, a, a));
      setEntries = linker.downcallHandle(find(advapi32, "SetEntriesInAclW"), FunctionDescriptor.of(i, i, a, a, a));
      setNamed = linker.downcallHandle(find(advapi32, "SetNamedSecurityInfoW"),
          FunctionDescriptor.of(i, a, i, i, a, a, a, a));
      localFree = linker.downcallHandle(find(kernel32, "LocalFree"), FunctionDescriptor.of(a, a));
      getStdHandle = linker.downcallHandle(find(kernel32, "GetStdHandle"), FunctionDescriptor.of(a, i));
      setHandleInformation = linker.downcallHandle(find(kernel32, "SetHandleInformation"),
          FunctionDescriptor.of(i, a, i, i));
      initializeAttributes = linker.downcallHandle(find(kernel32, "InitializeProcThreadAttributeList"),
          FunctionDescriptor.of(i, a, i, i, a));
      updateAttribute = linker.downcallHandle(find(kernel32, "UpdateProcThreadAttribute"),
          FunctionDescriptor.of(i, a, i, l, a, l, a, a));
      deleteAttributes = linker.downcallHandle(find(kernel32, "DeleteProcThreadAttributeList"),
          FunctionDescriptor.ofVoid(a));
      createProcess = linker.downcallHandle(find(kernel32, "CreateProcessW"),
          FunctionDescriptor.of(i, a, a, a, a, i, i, a, a, a, a), Linker.Option.captureCallState("GetLastError"));
      waitFor = linker.downcallHandle(find(kernel32, "WaitForSingleObject"), FunctionDescriptor.of(i, a, i));
      exitCode = linker.downcallHandle(find(kernel32, "GetExitCodeProcess"), FunctionDescriptor.of(i, a, a));
      closeHandle = linker.downcallHandle(find(kernel32, "CloseHandle"), FunctionDescriptor.of(i, a));
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
      return lookup.find(name).orElseThrow(() -> new IllegalStateException("no " + name));
    }

    /** The container's SID, its profile created on first use; null when neither works. */
    MemorySegment container(Arena arena, String name) throws Throwable {
      MemorySegment wide = arena.allocateFrom(name, StandardCharsets.UTF_16LE);
      MemorySegment sidOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment display = arena.allocateFrom("GateRook MCP server", StandardCharsets.UTF_16LE);
      MemorySegment description = arena.allocateFrom("An MCP server GateRook keeps off this computer",
          StandardCharsets.UTF_16LE);
      int hr = (int) createProfile.invokeExact(wide, display, description, MemorySegment.NULL, 0, sidOut);
      if (hr == ALREADY_EXISTS) {
        hr = (int) deriveSid.invokeExact(wide, sidOut);
      }
      return hr == 0 ? sidOut.get(ValueLayout.ADDRESS, 0) : null;
    }

    void freeSid(MemorySegment sid) throws Throwable {
      MemorySegment unused = (MemorySegment) freeSid.invokeExact(sid);
    }

    /**
     * Grants {@code sid} {@code access} on {@code path}, inherited by everything in it; a DACL that names the SID
     * already is left as it is, since a change goes through the whole tree. Null when it worked.
     */
    @SuppressWarnings("restricted")
    String grant(Arena arena, Path path, MemorySegment sid, int access) throws Throwable {
      MemorySegment name = arena.allocateFrom(path.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
      MemorySegment daclOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment descriptorOut = arena.allocate(ValueLayout.ADDRESS);
      int rc = (int) getNamed.invokeExact(name, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, MemorySegment.NULL,
          MemorySegment.NULL, daclOut, MemorySegment.NULL, descriptorOut);
      if (rc != 0) {
        return "couldn't read the access list of " + path + " (error " + rc + ")";
      }
      MemorySegment descriptor = descriptorOut.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment dacl = daclOut.get(ValueLayout.ADDRESS, 0);
        byte[] sidBytes = sidBytes(sid);
        if (dacl.address() != 0) {
          int size = Short.toUnsignedInt(dacl.reinterpret(8).get(ValueLayout.JAVA_SHORT_UNALIGNED, 2));
          if (names(dacl.reinterpret(size), sidBytes, access)) {
            return null;
          }
        }
        // EXPLICIT_ACCESS_W: permissions, mode, inheritance (16 bytes with padding), then TRUSTEE_W (32 bytes):
        // multiple trustee, its operation, form, type, padding, then the name (here the SID) at 24.
        MemorySegment entry = arena.allocate(48, 8);
        entry.fill((byte) 0);
        entry.set(ValueLayout.JAVA_INT, 0, access);
        entry.set(ValueLayout.JAVA_INT, 4, GRANT_ACCESS);
        entry.set(ValueLayout.JAVA_INT, 8, SUB_CONTAINERS_AND_OBJECTS_INHERIT);
        entry.set(ValueLayout.JAVA_INT, 16 + 12, TRUSTEE_IS_SID);
        entry.set(ValueLayout.ADDRESS, 16 + 24, sid);
        MemorySegment newDacl = arena.allocate(ValueLayout.ADDRESS);
        rc = (int) setEntries.invokeExact(1, entry, dacl, newDacl);
        if (rc != 0) {
          return "couldn't add the server's container to the access list of " + path + " (error " + rc + ")";
        }
        try {
          rc = (int) setNamed.invokeExact(name, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, MemorySegment.NULL,
              MemorySegment.NULL, newDacl.get(ValueLayout.ADDRESS, 0), MemorySegment.NULL);
          return rc == 0 ? null : "couldn't open " + path + " to the server's container (error " + rc + ")";
        } finally {
          MemorySegment unused = (MemorySegment) localFree.invokeExact(newDacl.get(ValueLayout.ADDRESS, 0));
        }
      } finally {
        MemorySegment unused = (MemorySegment) localFree.invokeExact(descriptor);
      }
    }

    @SuppressWarnings("restricted")
    private static byte[] sidBytes(MemorySegment sid) {
      MemorySegment head = sid.reinterpret(8);
      int count = Byte.toUnsignedInt(head.get(ValueLayout.JAVA_BYTE, 1));
      return sid.reinterpret(8 + 4L * count).toArray(ValueLayout.JAVA_BYTE);
    }

    /** Starts the command line in the container with only the standard handles, waits, and returns its exit code. */
    int start(Arena arena, MemorySegment state, MemorySegment container, String commandLine, String environment,
        PrintStream err) throws Throwable {
      // The capabilities: SID_AND_ATTRIBUTES (pointer, attributes, padded to 16) for the internet and the LAN.
      MemorySegment capabilities = arena.allocate(32, 8);
      capabilities.fill((byte) 0);
      int slot = 0;
      for (int rid : new int[] {INTERNET_CLIENT, PRIVATE_NETWORK_CLIENT_SERVER}) {
        byte[] bytes = capabilitySid(rid);
        MemorySegment sid = arena.allocate(bytes.length, 4);
        sid.copyFrom(MemorySegment.ofArray(bytes));
        capabilities.set(ValueLayout.ADDRESS, slot * 16L, sid);
        capabilities.set(ValueLayout.JAVA_INT, slot * 16L + 8, SE_GROUP_ENABLED);
        slot++;
      }
      // SECURITY_CAPABILITIES: the container, the capabilities, their count, reserved.
      MemorySegment security = arena.allocate(24, 8);
      security.fill((byte) 0);
      security.set(ValueLayout.ADDRESS, 0, container);
      security.set(ValueLayout.ADDRESS, 8, capabilities);
      security.set(ValueLayout.JAVA_INT, 16, 2);

      List<MemorySegment> handles = new ArrayList<>();
      MemorySegment[] std = new MemorySegment[3];
      for (int n = 0; n < 3; n++) {
        MemorySegment handle = (MemorySegment) getStdHandle.invokeExact(-10 - n);
        std[n] = handle;
        // The handle list takes each handle once: stdout and stderr may be the same pipe.
        if (handle.address() != 0 && handle.address() != -1L
            && handles.stream().noneMatch(h -> h.address() == handle.address())) {
          int unused = (int) setHandleInformation.invokeExact(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
          handles.add(handle);
        }
      }
      MemorySegment handleList = arena.allocate(8L * Math.max(1, handles.size()), 8);
      for (int n = 0; n < handles.size(); n++) {
        handleList.set(ValueLayout.ADDRESS, 8L * n, handles.get(n));
      }

      MemorySegment size = arena.allocate(ValueLayout.JAVA_LONG);
      int unusedSize = (int) initializeAttributes.invokeExact(MemorySegment.NULL, 2, 0, size);
      MemorySegment attributes = arena.allocate(size.get(ValueLayout.JAVA_LONG, 0), 8);
      if ((int) initializeAttributes.invokeExact(attributes, 2, 0, size) == 0) {
        err.println("gw-bridge: couldn't set up the AppContainer's process attributes");
        return Sandbox.NO_SANDBOX;
      }
      try {
        if ((int) updateAttribute.invokeExact(attributes, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, security,
            security.byteSize(), MemorySegment.NULL, MemorySegment.NULL) == 0
            || (!handles.isEmpty() && (int) updateAttribute.invokeExact(attributes, 0,
                PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handleList, 8L * handles.size(), MemorySegment.NULL,
                MemorySegment.NULL) == 0)) {
          err.println("gw-bridge: couldn't set up the AppContainer's process attributes");
          return Sandbox.NO_SANDBOX;
        }
        // STARTUPINFOEXW: cb, the standard handles (flag at 60, handles at 80, 88, 96), the attribute list at 104.
        MemorySegment startup = arena.allocate(STARTUPINFOEX_SIZE, 8);
        startup.fill((byte) 0);
        startup.set(ValueLayout.JAVA_INT, 0, (int) STARTUPINFOEX_SIZE);
        startup.set(ValueLayout.JAVA_INT, 60, STARTF_USESTDHANDLES);
        startup.set(ValueLayout.ADDRESS, 80, std[0]);
        startup.set(ValueLayout.ADDRESS, 88, std[1]);
        startup.set(ValueLayout.ADDRESS, 96, std[2]);
        startup.set(ValueLayout.ADDRESS, 104, attributes);
        // PROCESS_INFORMATION: process and thread handles, their ids.
        MemorySegment process = arena.allocate(24, 8);
        MemorySegment line = arena.allocateFrom(commandLine, StandardCharsets.UTF_16LE);
        // The block ends with two NULs already; allocateFrom adds a third, harmless one.
        MemorySegment block = arena.allocateFrom(environment, StandardCharsets.UTF_16LE);
        if ((int) createProcess.invokeExact(state, MemorySegment.NULL, line, MemorySegment.NULL, MemorySegment.NULL,
            handles.isEmpty() ? 0 : 1, EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT, block,
            MemorySegment.NULL, startup, process) == 0) {
          err.println("gw-bridge: couldn't start the command in its AppContainer (error "
              + (int) lastError.get(state, 0L) + ")");
          return 126;
        }
        MemorySegment child = process.get(ValueLayout.ADDRESS, 0);
        int unusedThread = (int) closeHandle.invokeExact(process.get(ValueLayout.ADDRESS, 8));
        try {
          int unusedWait = (int) waitFor.invokeExact(child, INFINITE);
          MemorySegment code = arena.allocate(ValueLayout.JAVA_INT);
          return (int) exitCode.invokeExact(child, code) == 0 ? 1 : code.get(ValueLayout.JAVA_INT, 0);
        } finally {
          int unused = (int) closeHandle.invokeExact(child);
        }
      } finally {
        deleteAttributes.invokeExact(attributes);
      }
    }
  }

  /**
   * Whether an ACL has an access-allowed ACE for {@code sid} with every bit of {@code access}: header (revision,
   * padding, size, ACE count, padding), then ACEs of type 0 (header, mask, SID).
   */
  static boolean names(MemorySegment acl, byte[] sid, int access) {
    if (acl.byteSize() < 8) {
      return false;
    }
    int count = Short.toUnsignedInt(acl.get(ValueLayout.JAVA_SHORT_UNALIGNED, 4));
    long offset = 8;
    for (int n = 0; n < count && offset + 8 <= acl.byteSize(); n++) {
      int type = Byte.toUnsignedInt(acl.get(ValueLayout.JAVA_BYTE, offset));
      int size = Short.toUnsignedInt(acl.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset + 2));
      if (size < 8 || offset + size > acl.byteSize()) {
        return false;
      }
      if (type == 0 && size - 8 >= sid.length) {
        int mask = acl.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 4);
        byte[] aceSid = acl.asSlice(offset + 8, sid.length).toArray(ValueLayout.JAVA_BYTE);
        if (java.util.Arrays.equals(aceSid, sid) && (mask & access) == access) {
          return true;
        }
      }
      offset += size;
    }
    return false;
  }
}
