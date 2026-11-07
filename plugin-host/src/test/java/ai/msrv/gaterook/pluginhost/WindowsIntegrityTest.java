package ai.msrv.gaterook.pluginhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The SID, labels and RID parsing {@link WindowsIntegrity} relies on, pinned without Windows. */
class WindowsIntegrityTest {

  @Test
  void theLowSidIsS_1_16_4096() {
    byte[] sid = WindowsIntegrity.lowSid();
    assertThat(sid).hasSize(12);
    assertThat(sid[0]).isEqualTo((byte) 1);
    assertThat(sid[1]).isEqualTo((byte) 1);
    assertThat(sid[7]).isEqualTo((byte) 16);
    assertThat(WindowsIntegrity.lastRid(MemorySegment.ofArray(sid))).isEqualTo(WindowsIntegrity.LOW_RID);
  }

  @Test
  void theRidIsTheLastSubAuthority() {
    // S-1-16-8192, medium.
    byte[] medium = {1, 1, 0, 0, 0, 0, 0, 16, 0, 0x20, 0, 0};
    assertThat(WindowsIntegrity.lastRid(MemorySegment.ofArray(medium))).isEqualTo(0x2000);
    // S-1-5-21-1-2-3-1001: five sub-authorities, the RID last.
    byte[] user = {1, 5, 0, 0, 0, 0, 0, 5, 21, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, (byte) 0xE9, 3, 0, 0};
    assertThat(WindowsIntegrity.lastRid(MemorySegment.ofArray(user))).isEqualTo(1001);
    assertThat(WindowsIntegrity.lastRid(MemorySegment.ofArray(new byte[] {1, 0, 0, 0, 0, 0, 0, 16}))).isEqualTo(-1);
    assertThat(WindowsIntegrity.lastRid(MemorySegment.ofArray(new byte[] {1, 2, 0}))).isEqualTo(-1);
  }

  @Test
  void readableKeepsMediumWithNoWriteUpAndWritableIsLow() {
    assertThat(WindowsIntegrity.label(true, false)).isEqualTo("S:(ML;OICI;NW;;;ME)");
    assertThat(WindowsIntegrity.label(false, false)).isEqualTo("S:(ML;;NW;;;ME)");
    assertThat(WindowsIntegrity.label(true, true)).isEqualTo("S:(ML;OICI;NW;;;LW)");
    assertThat(WindowsIntegrity.label(false, true)).isEqualTo("S:(ML;;NW;;;LW)");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void elsewhereThereIsNoLowIntegrity() {
    assertThat(WindowsIntegrity.drop(List.of(), List.of(Path.of(".")))).contains("only on Windows");
    assertThat(WindowsIntegrity.currentLevel()).isEqualTo(-1);
  }
}
