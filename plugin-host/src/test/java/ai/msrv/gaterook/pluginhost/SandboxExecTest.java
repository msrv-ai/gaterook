package ai.msrv.gaterook.pluginhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The first stage's command line and the TCP rules it asks Landlock for (ARCHITECTURE.md §7.5): the core names its
 * own ports with {@code --deny-tcp}, and from ABI 4 every other port stays open to bind and connect, since Landlock
 * rules only allow. Landlock itself is exercised where it runs (Linux 6.7 or later).
 */
class SandboxExecTest {

  @Test
  void theCoresPortsAreClosedAndEveryOtherPortStaysOpen() {
    SandboxExec.Request r = SandboxExec.parse(List.of("--ro", "/opt/jre", "--rw", "/data", "--deny-tcp", "7466",
        "--deny-tcp", "7465", "--deny-tcp", "7465", "--", "/opt/jre/bin/java", "-version"));

    assertThat(r.deniedPorts()).containsExactly(7465, 7466);
    assertThat(r.readOnly()).containsExactly("/opt/jre");
    assertThat(r.readWrite()).containsExactly("/data");
    assertThat(r.command()).containsExactly("/opt/jre/bin/java", "-version");
    // Port 0 stays open too: binding it asks for an ephemeral port.
    assertThat(Sandbox.openPorts(4, r.deniedPorts())).hasSize(65_534).contains(0, 1, 7464, 7467, 65_535)
        .doesNotContain(7465, 7466);
    // Before ABI 4 Landlock has no network rules, and with no port to close the network isn't handled at all.
    assertThat(Sandbox.openPorts(3, r.deniedPorts())).isEmpty();
    assertThat(Sandbox.openPorts(8, Set.of())).isEmpty();
  }

  /**
   * The ruleset's attr grows with the ABI, and each field keeps its offset: {@code handled_access_net} at 8 for the
   * port rules, {@code scoped} at 16 (abstract Unix sockets and signals, from ABI 6, with or without a closed port).
   */
  @Test
  void theRulesetAttrGrowsWithTheAbiAndKeepsItsOffsets() {
    Sandbox.RulesetAttr scopedWithPorts = Sandbox.RulesetAttr.of(8, true);
    assertThat(scopedWithPorts.size()).isEqualTo(24);
    assertThat(scopedWithPorts.handledFs()).isEqualTo(Sandbox.handledRights(8));
    assertThat(scopedWithPorts.handledNet()).isEqualTo(Sandbox.BIND_TCP | Sandbox.CONNECT_TCP);
    assertThat(scopedWithPorts.scoped()).isEqualTo(Sandbox.SCOPE_ABSTRACT_UNIX_SOCKET | Sandbox.SCOPE_SIGNAL);
    // --probe, or no port to close: still scoped, the network not handled.
    Sandbox.RulesetAttr scopedOnly = Sandbox.RulesetAttr.of(6, false);
    assertThat(scopedOnly.size()).isEqualTo(24);
    assertThat(scopedOnly.handledNet()).isZero();
    assertThat(scopedOnly.scoped()).isEqualTo(Sandbox.SCOPE_ABSTRACT_UNIX_SOCKET | Sandbox.SCOPE_SIGNAL);
    // ABI 4 and 5 close ports but can't scope.
    Sandbox.RulesetAttr portsOnly = Sandbox.RulesetAttr.of(5, true);
    assertThat(portsOnly.size()).isEqualTo(16);
    assertThat(portsOnly.handledNet()).isEqualTo(Sandbox.BIND_TCP | Sandbox.CONNECT_TCP);
    assertThat(portsOnly.scoped()).isZero();
    // Before ABI 4 only the filesystem, whatever was asked: ABI 1's 8 bytes.
    assertThat(Sandbox.RulesetAttr.of(3, true)).isEqualTo(new Sandbox.RulesetAttr(8, Sandbox.handledRights(3), 0, 0));
    assertThat(Sandbox.RulesetAttr.of(4, false).size()).isEqualTo(8);
    assertThat(Sandbox.RulesetAttr.of(1, false).handledFs() & Sandbox.REFER).isZero();
    assertThat(Sandbox.RulesetAttr.of(5, false).handledFs() & Sandbox.IOCTL_DEV).isNotZero();

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment attr = arena.allocate(24, 8);
      scopedWithPorts.write(attr);
      assertThat(attr.get(ValueLayout.JAVA_LONG, 0)).isEqualTo(Sandbox.handledRights(8));
      assertThat(attr.get(ValueLayout.JAVA_LONG, 8)).isEqualTo(Sandbox.BIND_TCP | Sandbox.CONNECT_TCP);
      assertThat(attr.get(ValueLayout.JAVA_LONG, 16)).isEqualTo(Sandbox.SCOPE_ABSTRACT_UNIX_SOCKET
          | Sandbox.SCOPE_SIGNAL);
      // A 16-byte attr writes nothing past the network, an 8-byte one nothing past the filesystem.
      MemorySegment sixteen = arena.allocate(24, 8);
      portsOnly.write(sixteen);
      assertThat(sixteen.get(ValueLayout.JAVA_LONG, 8)).isEqualTo(Sandbox.BIND_TCP | Sandbox.CONNECT_TCP);
      assertThat(sixteen.get(ValueLayout.JAVA_LONG, 16)).isZero();
      MemorySegment eight = arena.allocate(24, 8);
      Sandbox.RulesetAttr.of(3, true).write(eight);
      assertThat(eight.get(ValueLayout.JAVA_LONG, 0)).isEqualTo(Sandbox.handledRights(3));
      assertThat(eight.get(ValueLayout.JAVA_LONG, 8)).isZero();
      assertThat(eight.get(ValueLayout.JAVA_LONG, 16)).isZero();
    }
  }

  @Test
  void aDeniedPortIsOneTo65535() {
    for (String bad : List.of("0", "65536", "99999", "-1", "7465x", "0x1D29", "")) {
      assertThatThrownBy(() -> SandboxExec.parse(List.of("--deny-tcp", bad, "--", "/bin/true"))).as(bad)
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--deny-tcp needs a port");
    }
    assertThatThrownBy(() -> SandboxExec.parse(List.of("--deny-tcp"))).isInstanceOf(IllegalArgumentException.class);
    assertThat(SandboxExec.parse(List.of("--deny-tcp", "65535", "--probe")).probe()).isTrue();
  }

  @Test
  void elsewhereThanLinuxTheStageRunsNothing() {
    org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Linux"));
    assertThat(SandboxExec.run(List.of("--deny-tcp", "7465", "--", "/bin/echo", "unconfined")))
        .isEqualTo(SandboxExec.NO_SANDBOX);
  }
}
