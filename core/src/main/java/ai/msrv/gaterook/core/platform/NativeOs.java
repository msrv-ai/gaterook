package ai.msrv.gaterook.core.platform;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Optional;

/**
 * The core's only foreign function calls (report 15 §9 rule 9). Every downcall shape used here is listed in the
 * Linux-only native-image metadata ({@code META-INF/native-image/ai.msrv.gaterook/core-ffm-linux}); where FFM or the
 * function is unavailable (macOS x64 in native, Windows) the methods degrade silently and report it.
 */
public final class NativeOs {

  private static final int PR_GET_DUMPABLE = 3;
  private static final int PR_SET_DUMPABLE = 4;

  private NativeOs() {}

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
