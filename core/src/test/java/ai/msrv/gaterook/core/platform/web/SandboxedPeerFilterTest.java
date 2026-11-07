package ai.msrv.gaterook.core.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.NativeOs;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Which loopback peers the core refuses on Windows; the native lookups replaced, and checked on Windows. */
class SandboxedPeerFilterTest {

  private static SandboxedPeerFilter filter(Map<Integer, Long> pidByClientPort, Map<Long, Integer> levels,
      long... descendants) {
    return new SandboxedPeerFilter(true, new SandboxedPeerFilter.Peers() {
      @Override
      public long pid(int serverPort, int clientPort) {
        return pidByClientPort.getOrDefault(clientPort, -1L);
      }

      @Override
      public int level(long pid) {
        return levels.getOrDefault(pid, -1);
      }
    }, pid -> java.util.Arrays.stream(descendants).anyMatch(d -> d == pid));
  }

  @Test
  void aLowIntegrityDescendantOfTheCoreIsRefused() {
    SandboxedPeerFilter f = filter(Map.of(50001, 11L), Map.of(11L, 0x1000), 11L);
    assertThat(f.refused("127.0.0.1", 7465, 50001)).isTrue();
  }

  @Test
  void anAppContainerDescendantIsRefusedToo() {
    assertThat(filter(Map.of(50001, 11L), Map.of(11L, 0), 11L).refused("127.0.0.1", 7466, 50001)).isTrue();
  }

  @Test
  void mediumPeersOtherProgramsAndUnknownPeersPass() {
    // The app or a tunnel agent: medium, even when it descends from the core.
    assertThat(filter(Map.of(50001, 12L), Map.of(12L, 0x2000), 12L).refused("127.0.0.1", 7465, 50001)).isFalse();
    // A browser's sandboxed network process: low, but not the core's.
    assertThat(filter(Map.of(50001, 13L), Map.of(13L, 0x1000)).refused("127.0.0.1", 7465, 50001)).isFalse();
    // Not in the table, or its level unreadable.
    assertThat(filter(Map.of(), Map.of(), 14L).refused("127.0.0.1", 7465, 50001)).isFalse();
    assertThat(filter(Map.of(50001, 14L), Map.of(), 14L).refused("127.0.0.1", 7465, 50001)).isFalse();
    // Not a loopback peer at all.
    assertThat(filter(Map.of(50001, 11L), Map.of(11L, 0x1000), 11L).refused("10.0.0.5", 7465, 50001)).isFalse();
  }

  @Test
  void theCoresParentsAreWalkedOnlyForALowPeer() {
    AtomicInteger walks = new AtomicInteger();
    SandboxedPeerFilter f = new SandboxedPeerFilter(true, new SandboxedPeerFilter.Peers() {
      @Override
      public long pid(int serverPort, int clientPort) {
        return 12L;
      }

      @Override
      public int level(long pid) {
        return 0x2000;
      }
    }, pid -> walks.incrementAndGet() > 0);
    assertThat(f.refused("127.0.0.1", 7465, 50001)).isFalse();
    assertThat(walks).hasValue(0);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void onWindowsAConnectionFromThisProcessIsFoundAtMediumIntegrity() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Socket client = new Socket(InetAddress.getByName("127.0.0.1"), server.getLocalPort());
        Socket accepted = server.accept()) {
      long self = ProcessHandle.current().pid();
      assertThat(NativeOs.loopbackPeer(server.getLocalPort(), client.getLocalPort())).isEqualTo(self);
      assertThat(NativeOs.integrityLevel(self)).isEqualTo(0x2000);
      assertThat(new SandboxedPeerFilter().refused("127.0.0.1", server.getLocalPort(), client.getLocalPort()))
          .isFalse();
    }
  }
}
