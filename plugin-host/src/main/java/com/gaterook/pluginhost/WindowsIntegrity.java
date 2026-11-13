package com.gaterook.pluginhost;

import java.io.IOException;
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
import java.util.List;

/**
 * The plugin host's sandbox on Windows (ARCHITECTURE.md §7.5): low integrity. At {@code plugin/load}, before any plugin
 * code runs, the host, still at medium integrity, labels what the plugin may read (its version folder and the core's
 * read-only paths: medium with no-write-up only) and write (its data folder: low), then lowers its own process token to
 * low integrity, which every later thread and child process shares. From then on the process can't write anything
 * labelled medium, which is every file and registry key of the account that isn't labelled low, and can't read what
 * carries a medium no-read-up label, which the core puts on GateRook's home.
 *
 * <p>The core asks for it with {@code MCP_GATEWAY_SANDBOX=low-integrity} once {@code --probe} worked on this system;
 * a host that can't drop refuses the load.
 */
final class WindowsIntegrity {

  /** What the core and the host call this sandbox ({@code MCP_GATEWAY_SANDBOX}). */
  static final String KIND = "low-integrity";

  /** Mandatory labels as SDDL. Medium with no-write-up only: a low-integrity process reads but doesn't write. */
  static final String READABLE_FOLDER = "S:(ML;OICI;NW;;;ME)";
  static final String READABLE_FILE = "S:(ML;;NW;;;ME)";
  /** Low: a low-integrity process writes there too. */
  static final String WRITABLE_FOLDER = "S:(ML;OICI;NW;;;LW)";
  static final String WRITABLE_FILE = "S:(ML;;NW;;;LW)";

  /** {@code SECURITY_MANDATORY_LOW_RID}. */
  static final int LOW_RID = 0x1000;

  static final int SE_FILE_OBJECT = 1;
  static final int LABEL_SECURITY_INFORMATION = 0x10;
  static final int SDDL_REVISION_1 = 1;
  static final int TOKEN_INTEGRITY_LEVEL = 25;
  static final int TOKEN_QUERY = 0x0008;
  static final int TOKEN_ADJUST_DEFAULT = 0x0080;
  static final int SE_GROUP_INTEGRITY = 0x20;
  /** {@code TOKEN_MANDATORY_LABEL} on 64-bit Windows: a pointer to the SID and its attributes, padded to 16 bytes. */
  static final long MANDATORY_LABEL_SIZE = 16;

  private WindowsIntegrity() {}

  /** S-1-16-4096, the low mandatory level: revision 1, one sub-authority, authority 16, RID 0x1000 little-endian. */
  static byte[] lowSid() {
    return new byte[] {1, 1, 0, 0, 0, 0, 0, 16, 0, 0x10, 0, 0};
  }

  /** The label a path gets: readable or writable, with inheritance for a folder. */
  static String label(boolean folder, boolean writable) {
    return writable ? (folder ? WRITABLE_FOLDER : WRITABLE_FILE) : (folder ? READABLE_FOLDER : READABLE_FILE);
  }

  /**
   * The RID of a binary SID's last sub-authority (its integrity level, for a mandatory-label SID); -1 when the bytes
   * aren't a SID.
   */
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

  /**
   * Labels {@code readable} and {@code writable}, then lowers this process to low integrity. Null when the process is
   * at low integrity afterwards, else why not.
   */
  static String drop(List<Path> readable, List<Path> writable) {
    if (!WindowsJob.isWindows()) {
      return "low integrity exists only on Windows";
    }
    try (Arena arena = Arena.ofConfined()) {
      Advapi32 a = new Advapi32();
      MemorySegment state = arena.allocate(a.state);
      for (Path p : readable) {
        // Needed only where a no-read-up label would keep the process out (GateRook's home); a folder the account
        // can't relabel elsewhere stays readable as it is, and a load that can't read it says so itself.
        String why = a.setLabel(state, arena, p, label(Files.isDirectory(p), false));
        if (why != null) {
          System.err.println("plugin-host: " + why + "; it keeps the label it had");
        }
      }
      for (Path p : writable) {
        String why = a.setLabel(state, arena, p, label(Files.isDirectory(p), true));
        if (why != null) {
          return why;
        }
      }
      String why = a.lower(state, arena);
      if (why != null) {
        return why;
      }
      int level = a.level(state, arena);
      return level == LOW_RID ? null : "the process is at integrity level 0x" + Integer.toHexString(level)
          + " after dropping";
    } catch (Throwable t) {
      return t.toString();
    }
  }

  /** Sets the mandatory label of one path from SDDL; null when it worked, else why not. */
  static String setLabel(Path path, String sddl) {
    if (!WindowsJob.isWindows()) {
      return "mandatory labels exist only on Windows";
    }
    try (Arena arena = Arena.ofConfined()) {
      Advapi32 a = new Advapi32();
      return a.setLabel(arena.allocate(a.state), arena, path, sddl);
    } catch (Throwable t) {
      return t.toString();
    }
  }

  /** This process's integrity level RID (0x2000 medium, 0x1000 low), or -1 where it can't be read. */
  static int currentLevel() {
    if (!WindowsJob.isWindows()) {
      return -1;
    }
    try (Arena arena = Arena.ofConfined()) {
      Advapi32 a = new Advapi32();
      return a.level(arena.allocate(a.state), arena);
    } catch (Throwable t) {
      return -1;
    }
  }

  /**
   * {@code --probe}: whether this system runs a host at low integrity: a new folder in the temporary folder labelled
   * low, the process dropped, a file written there and removed. Exit 0 when all of it worked, 4 when it didn't (the
   * reason on stderr), 64 for a wrong call.
   */
  public static void main(String[] args) {
    if (args.length != 1 || !"--probe".equals(args[0])) {
      System.err.println("usage: WindowsIntegrity --probe");
      System.exit(64);
    }
    try {
      Path dir = Files.createTempDirectory("gaterook-integrity-probe");
      String why = drop(List.of(), List.of(dir));
      if (why != null) {
        System.err.println(why);
        System.exit(4);
      }
      Path file = dir.resolve("probe");
      Files.writeString(file, "ok");
      Files.delete(file);
      try {
        Files.delete(dir);
      } catch (IOException e) {
        // The empty folder stays: the temporary folder it is in is medium.
      }
      System.exit(0);
    } catch (IOException | RuntimeException e) {
      System.err.println(e);
      System.exit(4);
    }
  }

  /** The advapi32 and kernel32 calls, made only on Windows, where the call-state layout has {@code GetLastError}. */
  private static final class Advapi32 {

    final MemoryLayout state = Linker.Option.captureStateLayout();
    final VarHandle lastError = state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    final MethodHandle toDescriptor;
    final MethodHandle saclOf;
    final MethodHandle setNamed;
    final MethodHandle localFree;
    final MethodHandle currentProcess;
    final MethodHandle openToken;
    final MethodHandle setTokenInformation;
    final MethodHandle getTokenInformation;
    final MethodHandle closeHandle;

    Advapi32() {
      Linker linker = Linker.nativeLinker();
      SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
      Linker.Option capture = Linker.Option.captureCallState("GetLastError");
      toDescriptor = linker.downcallHandle(find(advapi32, "ConvertStringSecurityDescriptorToSecurityDescriptorW"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS), capture);
      saclOf = linker.downcallHandle(find(advapi32, "GetSecurityDescriptorSacl"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS), capture);
      setNamed = linker.downcallHandle(find(advapi32, "SetNamedSecurityInfoW"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      localFree = linker.downcallHandle(find(kernel32, "LocalFree"),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      currentProcess = linker.downcallHandle(find(kernel32, "GetCurrentProcess"),
          FunctionDescriptor.of(ValueLayout.ADDRESS));
      openToken = linker.downcallHandle(find(advapi32, "OpenProcessToken"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
          capture);
      setTokenInformation = linker.downcallHandle(find(advapi32, "SetTokenInformation"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.JAVA_INT), capture);
      getTokenInformation = linker.downcallHandle(find(advapi32, "GetTokenInformation"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.JAVA_INT, ValueLayout.ADDRESS), capture);
      closeHandle = linker.downcallHandle(find(kernel32, "CloseHandle"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
      return lookup.find(name).orElseThrow(() -> new IllegalStateException("no " + name));
    }

    int lastError(MemorySegment captured) {
      return (int) lastError.get(captured, 0L);
    }

    /** Sets the mandatory label of {@code path} from {@code sddl}; null when it worked. */
    String setLabel(MemorySegment captured, Arena arena, Path path, String sddl) throws Throwable {
      MemorySegment descriptorOut = arena.allocate(ValueLayout.ADDRESS);
      if ((int) toDescriptor.invokeExact(captured, arena.allocateFrom(sddl, StandardCharsets.UTF_16LE),
          SDDL_REVISION_1, descriptorOut, MemorySegment.NULL) == 0) {
        return "the label " + sddl + " didn't parse (error " + lastError(captured) + ")";
      }
      MemorySegment descriptor = descriptorOut.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment present = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment acl = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment defaulted = arena.allocate(ValueLayout.JAVA_INT);
        if ((int) saclOf.invokeExact(captured, descriptor, present, acl, defaulted) == 0
            || present.get(ValueLayout.JAVA_INT, 0) == 0) {
          return "the label " + sddl + " has no SACL (error " + lastError(captured) + ")";
        }
        int rc = (int) setNamed.invokeExact(arena.allocateFrom(path.toString(), StandardCharsets.UTF_16LE),
            SE_FILE_OBJECT, LABEL_SECURITY_INFORMATION, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
            acl.get(ValueLayout.ADDRESS, 0));
        return rc == 0 ? null : "couldn't label " + path + " (error " + rc + ")";
      } finally {
        MemorySegment unused = (MemorySegment) localFree.invokeExact(descriptor);
      }
    }

    /** Sets this process's token to low integrity; null when the call worked. */
    String lower(MemorySegment captured, Arena arena) throws Throwable {
      MemorySegment token = open(captured, arena, TOKEN_QUERY | TOKEN_ADJUST_DEFAULT);
      if (token == null) {
        return "couldn't open the process token (error " + lastError(captured) + ")";
      }
      try {
        byte[] sid = lowSid();
        MemorySegment label = arena.allocate(MANDATORY_LABEL_SIZE + sid.length, 8);
        label.fill((byte) 0);
        MemorySegment sidBytes = label.asSlice(MANDATORY_LABEL_SIZE, sid.length);
        sidBytes.copyFrom(MemorySegment.ofArray(sid));
        label.set(ValueLayout.ADDRESS, 0, sidBytes);
        label.set(ValueLayout.JAVA_INT, ValueLayout.ADDRESS.byteSize(), SE_GROUP_INTEGRITY);
        if ((int) setTokenInformation.invokeExact(captured, token, TOKEN_INTEGRITY_LEVEL, label,
            (int) label.byteSize()) == 0) {
          return "couldn't lower the process to low integrity (error " + lastError(captured) + ")";
        }
        return null;
      } finally {
        int unused = (int) closeHandle.invokeExact(token);
      }
    }

    /** The integrity level RID of this process's token, or -1. */
    int level(MemorySegment captured, Arena arena) throws Throwable {
      MemorySegment token = open(captured, arena, TOKEN_QUERY);
      if (token == null) {
        return -1;
      }
      try {
        MemorySegment buffer = arena.allocate(128, 8);
        MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
        if ((int) getTokenInformation.invokeExact(captured, token, TOKEN_INTEGRITY_LEVEL, buffer,
            (int) buffer.byteSize(), length) == 0) {
          return -1;
        }
        MemorySegment sid = buffer.get(ValueLayout.ADDRESS, 0).reinterpret(68);
        return lastRid(sid);
      } finally {
        int unused = (int) closeHandle.invokeExact(token);
      }
    }

    private MemorySegment open(MemorySegment captured, Arena arena, int access) throws Throwable {
      MemorySegment tokenOut = arena.allocate(ValueLayout.ADDRESS);
      MemorySegment self = (MemorySegment) currentProcess.invokeExact();
      if ((int) openToken.invokeExact(captured, self, access, tokenOut) == 0) {
        return null;
      }
      return tokenOut.get(ValueLayout.ADDRESS, 0);
    }
  }
}
