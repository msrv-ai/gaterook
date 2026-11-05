package ai.msrv.gaterook.pluginit;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * What the core does to its home on Windows ({@code NativeOs.closeToLowIntegrity}), for a test's home: a medium
 * mandatory label with no read, write or execute up, inherited by everything in it.
 */
final class WindowsLabels {

  private WindowsLabels() {}

  static void closeToLowIntegrity(Path dir) {
    try (Arena arena = Arena.ofConfined()) {
      Linker linker = Linker.nativeLinker();
      SymbolLookup advapi32 = SymbolLookup.libraryLookup("advapi32", Arena.global());
      SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
      MethodHandle toDescriptor = linker.downcallHandle(
          advapi32.find("ConvertStringSecurityDescriptorToSecurityDescriptorW").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      MethodHandle saclOf = linker.downcallHandle(advapi32.find("GetSecurityDescriptorSacl").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
              ValueLayout.ADDRESS));
      MethodHandle setNamed = linker.downcallHandle(advapi32.find("SetNamedSecurityInfoW").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      MethodHandle localFree = linker.downcallHandle(kernel32.find("LocalFree").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
      if ((int) toDescriptor.invokeExact(arena.allocateFrom("S:(ML;OICI;NRNWNX;;;ME)", StandardCharsets.UTF_16LE), 1,
          out, MemorySegment.NULL) == 0) {
        throw new IllegalStateException("the label didn't parse");
      }
      MemorySegment descriptor = out.get(ValueLayout.ADDRESS, 0);
      try {
        MemorySegment present = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment acl = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment defaulted = arena.allocate(ValueLayout.JAVA_INT);
        if ((int) saclOf.invokeExact(descriptor, present, acl, defaulted) == 0) {
          throw new IllegalStateException("the label has no SACL");
        }
        int rc = (int) setNamed.invokeExact(arena.allocateFrom(dir.toAbsolutePath().toString(),
            StandardCharsets.UTF_16LE), 1, 0x10, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
            acl.get(ValueLayout.ADDRESS, 0));
        if (rc != 0) {
          throw new IllegalStateException("couldn't label " + dir + " (error " + rc + ")");
        }
      } finally {
        MemorySegment unused = (MemorySegment) localFree.invokeExact(descriptor);
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }
}
