package ai.msrv.gaterook.pluginhost;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * The host process can't be read from outside (ARCHITECTURE.md §9 "Core and host processes resist inspection"): on Linux
 * {@code prctl(PR_SET_DUMPABLE, 0)} through FFM, the same as the core's {@code NativeOs} — no core dump with secrets in
 * it, and another process of the same user can't {@code ptrace} it or read its {@code /proc/<pid>/mem}. The JVM flags
 * ({@code -XX:+DisableAttachMechanism}, no heap dumps) are the launcher's.
 */
final class Hardening {

  private static final int PR_SET_DUMPABLE = 4;
  private static final int PR_GET_DUMPABLE = 3;

  private Hardening() {}

  /** Turns core dumps and same-user ptrace off; true when the kernel confirmed it. */
  static boolean noDumps() {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux")) {
      return false;
    }
    try {
      Linker linker = Linker.nativeLinker();
      MemorySegment address = linker.defaultLookup().find("prctl").orElseThrow();
      MethodHandle prctl = linker.downcallHandle(address, FunctionDescriptor.of(ValueLayout.JAVA_INT,
          ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
          ValueLayout.JAVA_LONG), Linker.Option.firstVariadicArg(1));
      int set = (int) prctl.invokeExact(PR_SET_DUMPABLE, 0L, 0L, 0L, 0L);
      int now = (int) prctl.invokeExact(PR_GET_DUMPABLE, 0L, 0L, 0L, 0L);
      return set == 0 && now == 0;
    } catch (Throwable t) {
      return false;
    }
  }
}
