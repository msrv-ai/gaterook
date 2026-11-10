package ai.msrv.gaterook.bridge;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code gw-bridge sandbox-exec} on Windows (ARCHITECTURE.md §7.5): low integrity. Before it starts the command, the
 * bridge, still at medium integrity:
 *
 * <ol>
 *   <li>joins a job object it never closes, so the command and everything it starts end with the bridge, and a crash
 *   ends a process instead of leaving it on an error dialog. No UI limits: a server may drive a browser;</li>
 *   <li>creates the folders its environment names inside a {@code --rw} path ({@code TEMP}, {@code APPDATA}, …), so
 *   the command finds them;</li>
 *   <li>labels each {@code --rw} path low, inherited by what is in it;</li>
 *   <li>lowers its own process token to low integrity, which the command, its child, inherits.</li>
 * </ol>
 *
 * <p>At low integrity the command can't write anything labelled medium, which is every file and registry key of the
 * account that isn't labelled low, and can't read GateRook's home, which the core labels medium with no-read-up,
 * except what is labelled low in it: the work folders of the servers and the data folders of the plugins keep their
 * low label, so a command at low integrity can read and write another's by its path.
 * {@code --ro} needs nothing here (the rest of the system stays readable), and {@code --deny-tcp} is the core's to
 * enforce on Windows: it refuses connections from processes in this sandbox.
 */
final class WindowsSandbox {

  /** The name the core reports for this sandbox. */
  static final String KIND = "low-integrity";

  static final int LIMIT_DIE_ON_UNHANDLED_EXCEPTION = 0x0400;
  static final int LIMIT_BREAKAWAY_OK = 0x0800;
  static final int LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
  /**
   * The command's job: it ends with the bridge and doesn't wait on a crash dialog. A child may leave it explicitly
   * ({@code CREATE_BREAKAWAY_FROM_JOB}, which a browser's own sandbox uses), still at low integrity.
   */
  static final int SERVER_LIMITS = LIMIT_KILL_ON_JOB_CLOSE | LIMIT_DIE_ON_UNHANDLED_EXCEPTION | LIMIT_BREAKAWAY_OK;
  static final int JOB_EXTENDED_LIMIT_INFORMATION = 9;
  /** {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION} on 64-bit Windows, {@code LimitFlags} at offset 16. */
  static final long EXTENDED_LIMIT_SIZE = 144;
  static final long LIMIT_FLAGS_OFFSET = 16;

  /** Low, inherited by files and folders: a low-integrity process writes there. */
  static final String WRITABLE_FOLDER = "S:(ML;OICI;NW;;;LW)";
  static final int SE_FILE_OBJECT = 1;
  static final int LABEL_SECURITY_INFORMATION = 0x10;
  static final int SDDL_REVISION_1 = 1;
  static final int TOKEN_INTEGRITY_LEVEL = 25;
  static final int TOKEN_QUERY = 0x0008;
  static final int TOKEN_ADJUST_DEFAULT = 0x0080;
  static final int SE_GROUP_INTEGRITY = 0x20;
  static final long MANDATORY_LABEL_SIZE = 16;
  static final int LOW_RID = 0x1000;

  /** The environment variables that name folders a command writes to; created when they are inside a --rw path. */
  static final List<String> FOLDER_VARIABLES = List.of("TEMP", "TMP", "APPDATA", "LOCALAPPDATA", "USERPROFILE",
      "HOME", "npm_config_cache");

  private WindowsSandbox() {}

  /** S-1-16-4096: revision 1, one sub-authority, authority 16, RID 0x1000 little-endian. */
  static byte[] lowSid() {
    return new byte[] {1, 1, 0, 0, 0, 0, 0, 16, 0, 0x10, 0, 0};
  }

  /**
   * Whether an ACL's first mandatory label is low and inherited by files and folders. {@code ACL}: 8 bytes of header
   * (revision, padding, size, ACE count, padding); each ACE a header (type, flags, size), then for a label its mask and
   * SID, whose last sub-authority is the level.
   */
  static boolean lowLabelAcl(MemorySegment acl) {
    if (acl.byteSize() < 8) {
      return false;
    }
    int count = Short.toUnsignedInt(acl.get(ValueLayout.JAVA_SHORT_UNALIGNED, 4));
    long offset = 8;
    for (int i = 0; i < count && offset + 8 <= acl.byteSize(); i++) {
      int type = Byte.toUnsignedInt(acl.get(ValueLayout.JAVA_BYTE, offset));
      int flags = Byte.toUnsignedInt(acl.get(ValueLayout.JAVA_BYTE, offset + 1));
      int size = Short.toUnsignedInt(acl.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset + 2));
      if (size < 8 || offset + size > acl.byteSize()) {
        return false;
      }
      if (type == 0x11) {
        MemorySegment sid = acl.asSlice(offset + 8, size - 8);
        int subAuthorities = sid.byteSize() >= 8 ? Byte.toUnsignedInt(sid.get(ValueLayout.JAVA_BYTE, 1)) : 0;
        long ridOffset = 8 + 4L * (subAuthorities - 1);
        return subAuthorities >= 1 && sid.byteSize() >= ridOffset + 4
            && sid.get(ValueLayout.JAVA_INT_UNALIGNED, ridOffset) == LOW_RID && (flags & 0x3) == 0x3;
      }
      offset += size;
    }
    return false;
  }

  /** The folders of {@code env} that are inside one of {@code readWrite}, which the command expects to exist. */
  static List<Path> foldersToCreate(Map<String, String> env, List<Path> readWrite) {
    return FOLDER_VARIABLES.stream().map(env::get).filter(v -> v != null && !v.isBlank())
        .map(v -> Path.of(v).toAbsolutePath().normalize())
        .filter(p -> readWrite.stream().anyMatch(rw -> p.startsWith(rw.toAbsolutePath().normalize())))
        .distinct().toList();
  }

  /**
   * Confines this process as above; null when it runs at low integrity in its job afterwards, else why not (nothing
   * is half done that matters: a failure before the drop leaves the process as it was).
   */
  static String confine(List<Path> readWrite, Map<String, String> env) {
    String prepared = prepare(readWrite, env);
    if (prepared != null) {
      return prepared;
    }
    try (Arena arena = Arena.ofConfined()) {
      Calls c = new Calls();
      MemorySegment self = (MemorySegment) c.currentProcess.invokeExact();
      String why = c.lower(arena, self);
      if (why != null) {
        return why;
      }
      int level = c.level(arena, self);
      return level == LOW_RID ? null : "the process is at integrity level 0x" + Integer.toHexString(level);
    } catch (Throwable t) {
      return t.toString();
    }
  }

  /**
   * What both Windows sandboxes need before the command starts: the folders its environment names inside a
   * {@code --rw} path, this process in a job that ends the command with it, and each {@code --rw} path labelled low
   * (an AppContainer's token is low integrity too). Null when all of it worked, else why not.
   */
  static String prepare(List<Path> readWrite, Map<String, String> env) {
    try {
      for (Path folder : foldersToCreate(env, readWrite)) {
        Files.createDirectories(folder);
      }
    } catch (IOException e) {
      return "couldn't create " + e.getMessage();
    }
    try (Arena arena = Arena.ofConfined()) {
      Calls c = new Calls();
      MemorySegment self = (MemorySegment) c.currentProcess.invokeExact();
      MemorySegment job = (MemorySegment) c.createJob.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
      if (job.address() == 0) {
        return "couldn't create a job object";
      }
      MemorySegment limits = arena.allocate(EXTENDED_LIMIT_SIZE, 8);
      limits.fill((byte) 0);
      limits.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, SERVER_LIMITS);
      if ((int) c.setInformation.invokeExact(job, JOB_EXTENDED_LIMIT_INFORMATION, limits, (int) EXTENDED_LIMIT_SIZE)
          == 0) {
        return "couldn't set the job's limits";
      }
      if ((int) c.assign.invokeExact(job, self) == 0) {
        return "couldn't join the job";
      }
      for (Path p : readWrite) {
        // Labelling goes through the whole tree (a server's node_modules): once, not at every start.
        if (c.isLow(arena, p)) {
          continue;
        }
        String why = c.labelLow(arena, p);
        if (why != null) {
          return why;
        }
      }
      return null;
    } catch (Throwable t) {
      return t.toString();
    }
  }

  /** The kernel32 and advapi32 calls, made only on Windows. */
  private static final class Calls {

    final MethodHandle currentProcess;
    final MethodHandle createJob;
    final MethodHandle setInformation;
    final MethodHandle assign;
    final MethodHandle localFree;
    final MethodHandle closeHandle;
    final MethodHandle toDescriptor;
    final MethodHandle saclOf;
    final MethodHandle setNamed;
    final MethodHandle getNamed;
    final MethodHandle openToken;
    final MethodHandle setTokenInformation;
    final MethodHandle getTokenInformation;

    Calls() {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
      SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
      currentProcess = linker.downcallHandle(find(kernel32, "GetCurrentProcess"),
          FunctionDescriptor.of(ValueLayout.ADDRESS));
      createJob = linker.downcallHandle(find(kernel32, "CreateJobObjectW"),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      // SetInformationJobObject and SetTokenInformation share one shape: (handle, class, info, length) -> BOOL.
      FunctionDescriptor setter = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
          ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
      setInformation = linker.downcallHandle(find(kernel32, "SetInformationJobObject"), setter);
      setTokenInformation = linker.downcallHandle(find(advapi32, "SetTokenInformation"), setter);
      assign = linker.downcallHandle(find(kernel32, "AssignProcessToJobObject"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      localFree = linker.downcallHandle(find(kernel32, "LocalFree"),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      closeHandle = linker.downcallHandle(find(kernel32, "CloseHandle"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      toDescriptor = linker.downcallHandle(find(advapi32, "ConvertStringSecurityDescriptorToSecurityDescriptorW"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      saclOf = linker.downcallHandle(find(advapi32, "GetSecurityDescriptorSacl"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      setNamed = linker.downcallHandle(find(advapi32, "SetNamedSecurityInfoW"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      getNamed = linker.downcallHandle(find(advapi32, "GetNamedSecurityInfoW"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      openToken = linker.downcallHandle(find(advapi32, "OpenProcessToken"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      getTokenInformation = linker.downcallHandle(find(advapi32, "GetTokenInformation"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
      return lookup.find(name).orElseThrow(() -> new IllegalStateException("no " + name));
    }

    /** Whether {@code path} carries an inherited low label already. */
    @SuppressWarnings("restricted")
    boolean isLow(Arena arena, Path path) throws Throwable {
      MemorySegment saclOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment descriptorOut = arena.allocate(ValueLayout.ADDRESS);
      if ((int) getNamed.invokeExact(arena.allocateFrom(path.toAbsolutePath().toString(), StandardCharsets.UTF_16LE),
          SE_FILE_OBJECT, LABEL_SECURITY_INFORMATION, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
          saclOut, descriptorOut) != 0) {
        return false;
      }
      MemorySegment descriptor = descriptorOut.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment acl = saclOut.get(ValueLayout.ADDRESS, 0);
        if (acl.address() == 0) {
          return false;
        }
        int size = Short.toUnsignedInt(acl.reinterpret(8).get(ValueLayout.JAVA_SHORT_UNALIGNED, 2));
        return lowLabelAcl(acl.reinterpret(size));
      } finally {
        MemorySegment unused = (MemorySegment) localFree.invokeExact(descriptor);
      }
    }

    String labelLow(Arena arena, Path path) throws Throwable {
      MemorySegment descriptorOut = arena.allocate(ValueLayout.ADDRESS);
      if ((int) toDescriptor.invokeExact(arena.allocateFrom(WRITABLE_FOLDER, StandardCharsets.UTF_16LE),
          SDDL_REVISION_1, descriptorOut, MemorySegment.NULL) == 0) {
        return "the low label didn't parse";
      }
      MemorySegment descriptor = descriptorOut.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment present = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment acl = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment defaulted = arena.allocate(ValueLayout.JAVA_INT);
        if ((int) saclOf.invokeExact(descriptor, present, acl, defaulted) == 0
            || present.get(ValueLayout.JAVA_INT, 0) == 0) {
          return "the low label has no SACL";
        }
        int rc = (int) setNamed.invokeExact(arena.allocateFrom(path.toAbsolutePath().toString(),
            StandardCharsets.UTF_16LE), SE_FILE_OBJECT, LABEL_SECURITY_INFORMATION, MemorySegment.NULL,
            MemorySegment.NULL, MemorySegment.NULL, acl.get(ValueLayout.ADDRESS, 0));
        return rc == 0 ? null : "couldn't label " + path + " low (error " + rc + ")";
      } finally {
        MemorySegment unused = (MemorySegment) localFree.invokeExact(descriptor);
      }
    }

    String lower(Arena arena, MemorySegment self) throws Throwable {
      MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
      if ((int) openToken.invokeExact(self, TOKEN_QUERY | TOKEN_ADJUST_DEFAULT, tokenOut) == 0) {
        return "couldn't open the process token";
      }
      MemorySegment token = tokenOut.get(ValueLayout.ADDRESS, 0);
      try {
        byte[] sid = lowSid();
        MemorySegment label = arena.allocate(MANDATORY_LABEL_SIZE + sid.length, 8);
        label.fill((byte) 0);
        MemorySegment sidBytes = label.asSlice(MANDATORY_LABEL_SIZE, sid.length);
        sidBytes.copyFrom(MemorySegment.ofArray(sid));
        label.set(ValueLayout.ADDRESS, 0, sidBytes);
        label.set(ValueLayout.JAVA_INT, ValueLayout.ADDRESS.byteSize(), SE_GROUP_INTEGRITY);
        if ((int) setTokenInformation.invokeExact(token, TOKEN_INTEGRITY_LEVEL, label, (int) label.byteSize()) == 0) {
          return "couldn't lower the process to low integrity";
        }
        return null;
      } finally {
        int unused = (int) closeHandle.invokeExact(token);
      }
    }

    int level(Arena arena, MemorySegment self) throws Throwable {
      MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
      if ((int) openToken.invokeExact(self, TOKEN_QUERY, tokenOut) == 0) {
        return -1;
      }
      MemorySegment token = tokenOut.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment buffer = arena.allocate(128, 8);
        MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
        if ((int) getTokenInformation.invokeExact(token, TOKEN_INTEGRITY_LEVEL, buffer, (int) buffer.byteSize(),
            length) == 0) {
          return -1;
        }
        MemorySegment sid = buffer.get(ValueLayout.ADDRESS, 0).reinterpret(68);
        int count = Byte.toUnsignedInt(sid.get(ValueLayout.JAVA_BYTE, 1));
        return count < 1 || count > 15 ? -1 : sid.get(ValueLayout.JAVA_INT_UNALIGNED, 8 + 4L * (count - 1));
      } finally {
        int unused = (int) closeHandle.invokeExact(token);
      }
    }
  }
}
