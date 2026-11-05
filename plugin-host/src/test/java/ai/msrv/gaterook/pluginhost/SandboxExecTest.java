package ai.msrv.gaterook.pluginhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
