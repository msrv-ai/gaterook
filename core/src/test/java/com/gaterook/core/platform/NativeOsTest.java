package com.gaterook.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.testfixtures.WindowsKernel;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code prctl(PR_SET_DUMPABLE, 0)} on this test JVM: harmless for the test run (it only stops core dumps and ptrace
 * by other processes), and the same call the native image makes (report 12 §6: 1 → 0). On Windows, the home's
 * mandatory label; its ACL parsing is pinned everywhere.
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

  @Test
  void theHomesLabelIsMediumWithNoUpInheritedByFilesAndFolders() {
    assertThat(NativeOs.closedAcl(acl(0x11, 0x3, 0x7, 0x2000))).isTrue();
    assertThat(NativeOs.closedAcl(acl(0x11, 0x13, 0x7, 0x2000))).as("inherited from above").isTrue();
    assertThat(NativeOs.closedAcl(acl(0x11, 0x3, 0x1, 0x2000))).as("no-write-up only").isFalse();
    assertThat(NativeOs.closedAcl(acl(0x11, 0x3, 0x7, 0x1000))).as("low").isFalse();
    assertThat(NativeOs.closedAcl(acl(0x11, 0x0, 0x7, 0x2000))).as("not inherited").isFalse();
    assertThat(NativeOs.closedAcl(acl(0x02, 0x3, 0x7, 0x2000))).as("an audit ACE").isFalse();
    assertThat(NativeOs.closedAcl(MemorySegment.ofArray(new byte[] {2, 0, 8, 0, 0, 0, 0, 0}))).isFalse();
  }

  @Test
  void theLevelIsTheSidsLastSubAuthority() {
    assertThat(NativeOs.lastRid(MemorySegment.ofArray(new byte[] {1, 1, 0, 0, 0, 0, 0, 16, 0, 0x20, 0, 0})))
        .isEqualTo(0x2000);
    assertThat(NativeOs.lastRid(MemorySegment.ofArray(new byte[] {1, 1, 0}))).isEqualTo(-1);
  }

  @Test
  @EnabledOnOs({OS.MAC, OS.LINUX})
  void labelsExistOnlyOnWindows(@TempDir Path dir) {
    assertThat(NativeOs.isWindows()).isFalse();
    assertThat(NativeOs.closeToLowIntegrity(dir)).contains("only on Windows");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void theHomeIsClosedOnceAndThenLeftAlone(@TempDir Path dir) {
    WindowsKernel.require("mandatory integrity labels");
    assertThat(NativeOs.closeToLowIntegrity(dir)).isNull();
    assertThat(NativeOs.closeToLowIntegrity(dir)).isNull();
  }

  @Test
  void theTableStoresPortsInNetworkOrder() {
    assertThat(NativeOs.port(0x611E)).isEqualTo(7777);
    ByteBuffer b = ByteBuffer.allocate(4 + 24).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(1);
    // MIB_TCPROW_OWNER_PID: state, local 127.0.0.1:50001, remote 127.0.0.1:7465, pid 4242.
    b.putInt(5).putInt(0x0100007F).putInt(Short.toUnsignedInt(Short.reverseBytes((short) 50001)))
        .putInt(0x0100007F).putInt(Short.toUnsignedInt(Short.reverseBytes((short) 7465))).putInt(4242);
    MemorySegment rows = MemorySegment.ofArray(b.array());
    assertThat(NativeOs.peerIn(rows, 0x0100007F, 7465, 50001)).isEqualTo(4242);
    assertThat(NativeOs.peerIn(rows, 0x0100007F, 7466, 50001)).isEqualTo(-1);
  }

  /**
   * A process can bind another address of 127.0.0.0/8 and connect from it: its row is found by that address, and a
   * row of another loopback address with the same port isn't taken for it.
   */
  @Test
  void aPeerOnAnotherLoopbackAddressIsFoundByThatAddress() {
    ByteBuffer b = ByteBuffer.allocate(4 + 2 * 24).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(2);
    int client = Short.toUnsignedInt(Short.reverseBytes((short) 50001));
    int core = Short.toUnsignedInt(Short.reverseBytes((short) 7465));
    // 127.0.0.1:50001 → :7465, pid 1111; 127.0.0.2:50001 → :7465, pid 2222.
    b.putInt(5).putInt(0x0100007F).putInt(client).putInt(0x0100007F).putInt(core).putInt(1111);
    b.putInt(5).putInt(0x0200007F).putInt(client).putInt(0x0100007F).putInt(core).putInt(2222);
    MemorySegment rows = MemorySegment.ofArray(b.array());
    assertThat(NativeOs.peerIn(rows, 0x0200007F, 7465, 50001)).isEqualTo(2222);
    assertThat(NativeOs.peerIn(rows, 0x0100007F, 7465, 50001)).isEqualTo(1111);
    assertThat(NativeOs.peerIn(rows, 0x0300007F, 7465, 50001)).isEqualTo(-1);
    // The table's form of an address: network byte order read as a little-endian DWORD.
    assertThat(NativeOs.tableAddress((java.net.Inet4Address) java.net.InetAddress.ofLiteral("127.0.0.1")))
        .isEqualTo(0x0100007F);
    assertThat(NativeOs.tableAddress((java.net.Inet4Address) java.net.InetAddress.ofLiteral("127.0.0.2")))
        .isEqualTo(0x0200007F);
    assertThat(NativeOs.tableAddress((java.net.Inet4Address) java.net.InetAddress.ofLiteral("127.1.2.3")))
        .isEqualTo(0x0302017F);
  }

  /** An ACL with one ACE: a label of {@code rid} with {@code mask}, or another type. */
  private static MemorySegment acl(int type, int flags, int mask, int rid) {
    ByteBuffer b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
    b.put((byte) 2).put((byte) 0).putShort((short) 28).putShort((short) 1).putShort((short) 0);
    b.put((byte) type).put((byte) flags).putShort((short) 20).putInt(mask);
    b.put((byte) 1).put((byte) 1).put(new byte[] {0, 0, 0, 0, 0, 16}).putInt(rid);
    return MemorySegment.ofArray(b.array());
  }
}
