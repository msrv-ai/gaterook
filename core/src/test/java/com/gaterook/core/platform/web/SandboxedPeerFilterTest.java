package com.gaterook.core.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.gaterook.core.platform.NativeOs;
import com.gaterook.testfixtures.WindowsKernel;
import java.net.Inet4Address;
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
      public long pid(Inet4Address client, int serverPort, int clientPort) {
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

  /**
   * A process can bind any address of 127.0.0.0/8 and connect to the core from it, and an address may be written as
   * IPv4-mapped IPv6: each is a loopback peer, looked up by the address it really came from, and refused the same way.
   */
  @Test
  void aSandboxedDescendantIsRefusedFromAnyLoopbackAddress() {
    java.util.List<String> asked = new java.util.ArrayList<>();
    SandboxedPeerFilter f = new SandboxedPeerFilter(true, new SandboxedPeerFilter.Peers() {
      @Override
      public long pid(Inet4Address client, int serverPort, int clientPort) {
        asked.add(client.getHostAddress());
        return 11L;
      }

      @Override
      public int level(long pid) {
        return 0x1000;
      }
    }, pid -> pid == 11L);
    for (String address : java.util.List.of("127.0.0.2", "127.255.255.254", "::ffff:127.0.0.9",
        "0:0:0:0:0:ffff:7f00:1")) {
      assertThat(f.refused(address, 7465, 50001)).as(address).isTrue();
    }
    assertThat(asked).containsExactly("127.0.0.2", "127.255.255.254", "127.0.0.9", "127.0.0.1");
    // Not loopback, or no address literal: not judged here, and nothing is looked up for it.
    asked.clear();
    for (String address : java.util.List.of("10.0.0.5", "128.0.0.1", "localhost", "", "::2")) {
      assertThat(f.refused(address, 7465, 50001)).as(address).isFalse();
    }
    assertThat(asked).isEmpty();
  }

  @Test
  void theCoresParentsAreWalkedOnlyForALowPeer() {
    AtomicInteger walks = new AtomicInteger();
    SandboxedPeerFilter f = new SandboxedPeerFilter(true, new SandboxedPeerFilter.Peers() {
      @Override
      public long pid(Inet4Address client, int serverPort, int clientPort) {
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
    WindowsKernel.require("loopback ownership and medium token integrity (Wine exposes IPv4-mapped IPv6 sockets and high integrity)");
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Socket client = new Socket(InetAddress.getByName("127.0.0.1"), server.getLocalPort());
        Socket accepted = server.accept()) {
      long self = ProcessHandle.current().pid();
      assertThat(NativeOs.loopbackPeer((Inet4Address) client.getLocalAddress(), server.getLocalPort(),
          client.getLocalPort())).isEqualTo(self);
      assertThat(NativeOs.integrityLevel(self)).isEqualTo(0x2000);
      assertThat(new SandboxedPeerFilter().refused("127.0.0.1", server.getLocalPort(), client.getLocalPort()))
          .isFalse();
    }
  }
}
