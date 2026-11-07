package ai.msrv.gaterook.pluginhost;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the host holds itself to on Windows, before any plugin code runs (ARCHITECTURE.md §7.5), besides the low
 * integrity it drops to at load ({@link WindowsIntegrity}): what a headless plugin needs and no more.
 *
 * <ul>
 *   <li>a job object the host joins and never closes, so when the host ends, crashed or killed, every process a plugin
 *   started ends with it, and a crash ends a process instead of leaving it on an error dialog. No process can leave
 *   the job;</li>
 *   <li>the job's UI limits: no clipboard, no handles to other programs' windows, no global atoms, no desktops, display
 *   or system settings, and no logging off;</li>
 *   <li>process mitigations: no legacy extension points (AppInit DLLs, global hooks) and no DLLs from a network share
 *   or with a low integrity label.</li>
 * </ul>
 *
 * <p>A step that fails is reported on stderr, and the host runs without it.
 */
final class WindowsJob {

  /** {@code JOBOBJECT_BASIC_LIMIT_INFORMATION.LimitFlags}. */
  static final int LIMIT_DIE_ON_UNHANDLED_EXCEPTION = 0x0400;
  static final int LIMIT_BREAKAWAY_OK = 0x0800;
  static final int LIMIT_SILENT_BREAKAWAY_OK = 0x1000;
  static final int LIMIT_KILL_ON_JOB_CLOSE = 0x2000;

  /** {@code JOBOBJECT_BASIC_UI_RESTRICTIONS.UIRestrictionsClass}. */
  static final int UILIMIT_HANDLES = 0x01;
  static final int UILIMIT_READCLIPBOARD = 0x02;
  static final int UILIMIT_WRITECLIPBOARD = 0x04;
  static final int UILIMIT_SYSTEMPARAMETERS = 0x08;
  static final int UILIMIT_DISPLAYSETTINGS = 0x10;
  static final int UILIMIT_GLOBALATOMS = 0x20;
  static final int UILIMIT_DESKTOP = 0x40;
  static final int UILIMIT_EXITWINDOWS = 0x80;

  /** The host's job: it ends with the host, and a crash doesn't wait on a dialog. No breakaway. */
  static final int HOST_LIMITS = LIMIT_KILL_ON_JOB_CLOSE | LIMIT_DIE_ON_UNHANDLED_EXCEPTION;
  /** Every UI limit: a plugin host is headless. */
  static final int HOST_UI_LIMITS = UILIMIT_HANDLES | UILIMIT_READCLIPBOARD | UILIMIT_WRITECLIPBOARD
      | UILIMIT_SYSTEMPARAMETERS | UILIMIT_DISPLAYSETTINGS | UILIMIT_GLOBALATOMS | UILIMIT_DESKTOP | UILIMIT_EXITWINDOWS;

  /** {@code JOBOBJECTINFOCLASS}. */
  static final int JOB_BASIC_UI_RESTRICTIONS = 4;
  static final int JOB_EXTENDED_LIMIT_INFORMATION = 9;

  /**
   * {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION} on 64-bit Windows (x64 and ARM64 alike): the basic limits (two
   * {@code LARGE_INTEGER}s, then {@code LimitFlags} at offset 16, 64 bytes in all), {@code IO_COUNTERS} (48) and four
   * {@code SIZE_T}s.
   */
  static final long EXTENDED_LIMIT_SIZE = 144;
  static final long LIMIT_FLAGS_OFFSET = 16;

  /** {@code PROCESS_MITIGATION_POLICY}. */
  static final int PROCESS_EXTENSION_POINT_DISABLE_POLICY = 6;
  static final int PROCESS_IMAGE_LOAD_POLICY = 10;
  /** {@code PROCESS_MITIGATION_EXTENSION_POINT_DISABLE_POLICY}: {@code DisableExtensionPoints}. */
  static final int DISABLE_EXTENSION_POINTS = 0x1;
  /** {@code PROCESS_MITIGATION_IMAGE_LOAD_POLICY}: {@code NoRemoteImages}, {@code NoLowMandatoryLabelImages}. */
  static final int NO_REMOTE_OR_LOW_LABEL_IMAGES = 0x1 | 0x2;

  private WindowsJob() {}

  static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
  }

  /** {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION} that sets {@code flags} and leaves every other limit off. */
  static MemorySegment extendedLimits(Arena arena, int flags) {
    MemorySegment info = arena.allocate(EXTENDED_LIMIT_SIZE, 8);
    info.fill((byte) 0);
    info.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, flags);
    return info;
  }

  /**
   * Puts this process in a job with the host's limits and turns the mitigations on. Returns what didn't work, a line
   * each for stderr; empty when all of it did, and on any other system than Windows.
   */
  static List<String> confineHost() {
    if (!isWindows()) {
      return List.of();
    }
    List<String> problems = new ArrayList<>();
    try (Arena arena = Arena.ofConfined()) {
      Kernel32 k = new Kernel32();
      MemorySegment state = arena.allocate(k.state);
      MemorySegment self = (MemorySegment) k.currentProcess.invokeExact();
      MemorySegment job = k.job(state, arena, HOST_UI_LIMITS, problems);
      if (job != null && !k.assign(state, job, self)) {
        int error = k.lastError(state);
        // A process already in a job can only join a nested one without UI limits; that one still ends the host's
        // processes with it. A job that couldn't be made says why itself.
        MemorySegment plain = k.job(state, arena, 0, problems);
        if (plain != null && k.assign(state, plain, self)) {
          problems.add("in a Windows job without UI limits (error " + error + " with them): the plugin keeps the "
              + "clipboard and other programs' windows");
        } else if (plain != null) {
          problems.add("couldn't join a Windows job (error " + error + ", then " + k.lastError(state) + "): "
              + "processes a plugin starts can outlive the host, and the plugin keeps the clipboard and other "
              + "programs' windows");
        }
      }
      if (!k.mitigate(state, arena, PROCESS_EXTENSION_POINT_DISABLE_POLICY, DISABLE_EXTENSION_POINTS)) {
        problems.add("couldn't turn legacy extension points off (error " + k.lastError(state) + ")");
      }
      if (!k.mitigate(state, arena, PROCESS_IMAGE_LOAD_POLICY, NO_REMOTE_OR_LOW_LABEL_IMAGES)) {
        problems.add("couldn't refuse DLLs from a network share or with a low label (error " + k.lastError(state)
            + ")");
      }
    } catch (Throwable t) {
      problems.add("Windows job and mitigations unavailable (" + t + ")");
    }
    return problems;
  }

  /**
   * The kernel32 calls, made only on Windows: {@code GetLastError} is in the call-state layout there, nowhere else.
   * The job handle is never closed, so it closes when the host ends, and that ends the job's other processes.
   */
  private static final class Kernel32 {

    final MemoryLayout state = Linker.Option.captureStateLayout();
    final VarHandle lastError = state.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
    final MethodHandle createJob;
    final MethodHandle setInformation;
    final MethodHandle assignProcess;
    final MethodHandle currentProcess;
    final MethodHandle setMitigation;

    Kernel32() {
      Linker linker = Linker.nativeLinker();
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
      Linker.Option capture = Linker.Option.captureCallState("GetLastError");
      createJob = linker.downcallHandle(find(kernel32, "CreateJobObjectW"),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), capture);
      setInformation = linker.downcallHandle(find(kernel32, "SetInformationJobObject"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.JAVA_INT), capture);
      assignProcess = linker.downcallHandle(find(kernel32, "AssignProcessToJobObject"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), capture);
      currentProcess = linker.downcallHandle(find(kernel32, "GetCurrentProcess"),
          FunctionDescriptor.of(ValueLayout.ADDRESS));
      setMitigation = linker.downcallHandle(find(kernel32, "SetProcessMitigationPolicy"),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
          capture);
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
      return lookup.find(name).orElseThrow(() -> new IllegalStateException("kernel32 has no " + name));
    }

    int lastError(MemorySegment captured) {
      return (int) lastError.get(captured, 0L);
    }

    /** A new job with the host's limits and {@code uiLimits}; null, with the reason in {@code problems}, if not. */
    MemorySegment job(MemorySegment captured, Arena arena, int uiLimits, List<String> problems) throws Throwable {
      MemorySegment job = (MemorySegment) createJob.invokeExact(captured, MemorySegment.NULL, MemorySegment.NULL);
      if (job.address() == 0) {
        problems.add("no Windows job (error " + lastError(captured) + "): processes a plugin starts can outlive the "
            + "host");
        return null;
      }
      MemorySegment limits = extendedLimits(arena, HOST_LIMITS);
      if ((int) setInformation.invokeExact(captured, job, JOB_EXTENDED_LIMIT_INFORMATION, limits,
          (int) EXTENDED_LIMIT_SIZE) == 0) {
        problems.add("the Windows job's limits couldn't be set (error " + lastError(captured) + ")");
        return null;
      }
      if (uiLimits != 0) {
        MemorySegment ui = arena.allocate(ValueLayout.JAVA_INT);
        ui.set(ValueLayout.JAVA_INT, 0, uiLimits);
        if ((int) setInformation.invokeExact(captured, job, JOB_BASIC_UI_RESTRICTIONS, ui, 4) == 0) {
          problems.add("the Windows job's UI limits couldn't be set (error " + lastError(captured) + "): the plugin "
              + "keeps the clipboard and other programs' windows");
        }
      }
      return job;
    }

    boolean assign(MemorySegment captured, MemorySegment job, MemorySegment process) throws Throwable {
      return (int) assignProcess.invokeExact(captured, job, process) != 0;
    }

    boolean mitigate(MemorySegment captured, Arena arena, int policy, int flags) throws Throwable {
      MemorySegment value = arena.allocate(ValueLayout.JAVA_INT);
      value.set(ValueLayout.JAVA_INT, 0, flags);
      return (int) setMitigation.invokeExact(captured, policy, value, 4L) != 0;
    }
  }
}
