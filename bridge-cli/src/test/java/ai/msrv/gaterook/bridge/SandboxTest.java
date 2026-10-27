package ai.msrv.gaterook.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code gw-bridge sandbox-exec} (ARCHITECTURE.md §7.5): Landlock can't be undone, so each case runs the bridge as a
 * child process — the way the core starts an MCP server — and the child's command reports what it could open. With
 * {@code -Dgateway.bridge.binary} the child is the native executable.
 */
class SandboxTest {

  @TempDir
  Path dir;

  @Test
  void parsesItsArguments() {
    Sandbox.Request r = Sandbox.parse(List.of("--ro", "/a", "--rw", "/b", "--no-defaults", "--", "node", "x.js"));
    assertThat(r.readOnly()).containsExactly("/a");
    assertThat(r.readWrite()).containsExactly("/b");
    assertThat(r.defaults()).isFalse();
    assertThat(r.command()).containsExactly("node", "x.js");
    assertThatThrownBy(() -> Sandbox.parse(List.of("--ro"))).hasMessageContaining("needs a path");
    assertThatThrownBy(() -> Sandbox.parse(List.of("node"))).hasMessageContaining("unexpected");
    assertThatThrownBy(() -> Sandbox.parse(List.of("--"))).hasMessageContaining("needs a command");
    assertThat(Sandbox.readRights(false) & Sandbox.READ_DIR).as("READ_DIR isn't a file's right").isZero();
    assertThat(Sandbox.writeRights(5, false) & Sandbox.MAKE_DIR).isZero();
    assertThat(Sandbox.handledRights(1) & Sandbox.REFER).isZero();
    assertThat(Sandbox.handledRights(3) & Sandbox.TRUNCATE).isNotZero();
  }

  @Test
  void theCommandReadsAndWritesOnlyWhatItWasGiven() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Linux") && Sandbox.landlockAbi() >= 1,
        "Landlock is needed (Linux 5.13+ with the LSM on)");
    Path readable = Files.createDirectories(dir.resolve("plugin"));
    Files.writeString(readable.resolve("server.js"), "ok");
    Path writable = Files.createDirectories(dir.resolve("data"));
    Path secret = Files.createDirectories(dir.resolve("gateway-home"));
    Files.writeString(secret.resolve("master.keyset"), "the key");
    String script = "cat " + readable.resolve("server.js") + "; echo; "
        + "echo w > " + writable.resolve("out.txt") + " && echo wrote; "
        + "cat " + secret.resolve("master.keyset") + " || echo denied-read; "
        + "echo x > " + readable.resolve("new.txt") + " || echo denied-write";
    BridgeProcess.Result r = child(List.of("sandbox-exec", "--ro", readable.toString(), "--rw", writable.toString(), "--", "/bin/sh",
        "-c", script));
    assertThat(r.out()).contains("ok").contains("wrote").contains("denied-read").contains("denied-write")
        .doesNotContain("the key");
    assertThat(r.err()).contains("Permission denied");
    assertThat(writable.resolve("out.txt")).exists();
    assertThat(readable.resolve("new.txt")).doesNotExist();
    assertThat(r.code()).isZero();
  }

  @Test
  void onMacOsTheCommandCantReadTheGatewayHomeThroughALinkedPathEither() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec")),
        "macOS with sandbox-exec");
    // @TempDir is under /var/folders, a link to /private/var/folders: Seatbelt matches the canonical path only.
    assertThat(dir.toRealPath()).isNotEqualTo(dir.toAbsolutePath());
    Path gateway = Files.createDirectories(dir.resolve("gateway-home"));
    Files.writeString(gateway.resolve("master.keyset"), "the key");
    Path own = Files.createDirectories(gateway.resolve("mcp-servers/github"));
    Files.writeString(own.resolve("state.json"), "its own");
    String script = "cat " + gateway.resolve("master.keyset") + " || echo denied-read; "
        + "cat " + own.resolve("state.json") + "; echo; "
        + "echo w > " + own.resolve("out.txt") + " && echo wrote";
    BridgeProcess.Result r = BridgeProcess.run(List.of("sandbox-exec", "--rw", own.toString(), "--", "/bin/sh", "-c",
        script), Map.of("GW_HOME", gateway.toString()), "", Duration.ofSeconds(30));
    assertThat(r.out()).as(r.err()).contains("denied-read").contains("its own").contains("wrote")
        .doesNotContain("the key");
    assertThat(r.code()).isZero();
    // The profile names both forms of each path; clients' configs are closed too.
    String profile = Sandbox.macProfile(Map.of("GW_HOME", gateway.toString()), List.of(own.toString()));
    assertThat(profile).contains("(subpath \"" + gateway + "\")").contains("(subpath \"" + gateway.toRealPath() + "\")")
        .contains("/.cursor\")").contains("/.codex\")").contains("/Library/Application Support/Claude\")");
  }

  @Test
  void aMissingCommandIs127AndBadArgumentsAre3() throws Exception {
    assertThat(child(List.of("sandbox-exec", "--", "no-such-command-anywhere")).code()).isEqualTo(127);
    assertThat(child(List.of("sandbox-exec", "--ro")).code()).isEqualTo(3);
  }

  private static BridgeProcess.Result child(List<String> args) throws IOException, InterruptedException {
    return BridgeProcess.run(args, Map.of(), "", Duration.ofSeconds(30));
  }
}
