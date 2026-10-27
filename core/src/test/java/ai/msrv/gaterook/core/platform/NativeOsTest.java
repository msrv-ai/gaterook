package ai.msrv.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@code prctl(PR_SET_DUMPABLE, 0)} on this test JVM: harmless for the test run (it only stops core dumps and ptrace
 * by other processes), and the same call the native image makes (report 12 §6: 1 → 0).
 */
class NativeOsTest {

  @Test
  @EnabledOnOs(OS.LINUX)
  void theProcessStopsBeingDumpable() {
    assertThat(NativeOs.isLinux()).isTrue();
    assertThat(NativeOs.dumpable()).isPresent();

    assertThat(NativeOs.disableCoreDumps()).isTrue();
    assertThat(NativeOs.dumpable()).contains(0);
  }

  @Test
  @EnabledOnOs({OS.MAC, OS.WINDOWS})
  void elsewhereItDegradesSilently() {
    assertThat(NativeOs.disableCoreDumps()).isFalse();
    assertThat(NativeOs.dumpable()).isEmpty();
  }
}
