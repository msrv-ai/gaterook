package com.gaterook.pluginhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The layout and flags {@link WindowsJob} hands to kernel32, pinned here so a regression shows without Windows; the
 * calls themselves are checked on a Windows machine.
 */
class WindowsJobTest {

  @Test
  void theHostsJobEndsWithItAndAllowsNoBreakaway() {
    assertThat(WindowsJob.HOST_LIMITS).isEqualTo(0x2000 | 0x0400);
    assertThat(WindowsJob.HOST_LIMITS & (WindowsJob.LIMIT_BREAKAWAY_OK | WindowsJob.LIMIT_SILENT_BREAKAWAY_OK))
        .isZero();
  }

  @Test
  void aHeadlessHostGetsEveryUiLimit() {
    // JOB_OBJECT_UILIMIT_HANDLES through JOB_OBJECT_UILIMIT_EXITWINDOWS: clipboard both ways, other programs'
    // windows, global atoms, desktops, display and system settings, logging off.
    assertThat(WindowsJob.HOST_UI_LIMITS).isEqualTo(0xFF);
  }

  @Test
  void theExtendedLimitsCarryOnlyTheFlagsAtTheirOffset() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment info = WindowsJob.extendedLimits(arena, WindowsJob.HOST_LIMITS);
      assertThat(info.byteSize()).isEqualTo(144);
      assertThat(info.get(ValueLayout.JAVA_INT, 16)).isEqualTo(WindowsJob.HOST_LIMITS);
      for (long offset = 0; offset < info.byteSize(); offset++) {
        if (offset < 16 || offset >= 20) {
          assertThat(info.get(ValueLayout.JAVA_BYTE, offset)).as("byte %d", offset).isZero();
        }
      }
    }
  }

  @Test
  void theInformationClassesAndPoliciesAreKernel32s() {
    assertThat(WindowsJob.JOB_BASIC_UI_RESTRICTIONS).isEqualTo(4);
    assertThat(WindowsJob.JOB_EXTENDED_LIMIT_INFORMATION).isEqualTo(9);
    assertThat(WindowsJob.PROCESS_EXTENSION_POINT_DISABLE_POLICY).isEqualTo(6);
    assertThat(WindowsJob.PROCESS_IMAGE_LOAD_POLICY).isEqualTo(10);
    assertThat(WindowsJob.NO_REMOTE_OR_LOW_LABEL_IMAGES).isEqualTo(0b11);
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void elsewhereThereIsNothingToDo() {
    assertThat(WindowsJob.isWindows()).isFalse();
    assertThat(WindowsJob.confineHost()).isEmpty();
  }
}
