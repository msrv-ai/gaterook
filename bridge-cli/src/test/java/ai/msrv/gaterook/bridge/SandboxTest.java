package ai.msrv.gaterook.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
  void theCoresPortsAreClosedWithDenyTcp() {
    assertThat(Sandbox.parse(List.of("--", "node")).deniedPorts()).isEmpty();
    Sandbox.Request r = Sandbox.parse(List.of("--deny-tcp", "7466", "--rw", "/w", "--deny-tcp", "7465", "--deny-tcp",
        "7466", "--", "node"));
    assertThat(r.deniedPorts()).containsExactly(7465, 7466);
    assertThat(r.readWrite()).containsExactly("/w");
    for (String bad : List.of("0", "65536", "-1", "+80", "80a", "123456", "", "--")) {
      assertThatThrownBy(() -> Sandbox.parse(List.of("--deny-tcp", bad, "--", "node"))).as(bad)
          .hasMessage("--deny-tcp needs a port from 1 to 65535");
    }
    assertThatThrownBy(() -> Sandbox.parse(List.of("--deny-tcp"))).hasMessage("--deny-tcp needs a port from 1 to 65535");

    // Landlock's rules only allow: from ABI 4 every other port gets one (0 too: an ephemeral bind), before it none.
    int[] open = Sandbox.openPorts(Sandbox.NET_ABI, Set.of(7465, 7466));
    assertThat(open).hasSize(65_534).contains(0, 1, 7464, 7467, 65_535).doesNotContain(7465, 7466);
    assertThat(Sandbox.openPorts(3, Set.of(7465))).isEmpty();
    assertThat(Sandbox.openPorts(8, Set.of())).isEmpty();

    // On macOS: last, every host ("localhost" lets ::ffff:0.0.0.0 reach 127.0.0.1), ascending whatever the order given.
    Sandbox.Closed closed = closed(dir.resolve("home"), dir.resolve("home/.gaterook"));
    assertThat(Sandbox.macProfile(closed, List.of(), new java.util.LinkedHashSet<>(List.of(7466, 7465))))
        .endsWith("(deny network-outbound (remote ip \"*:7465\") (remote ip \"*:7466\"))\n"
            + "(deny network-bind (local ip \"*:7465\") (local ip \"*:7466\"))\n");
    assertThat(Sandbox.macProfile(closed, List.of(), Set.of())).doesNotContain("network");
  }

  /**
   * The {@code --deny-tcp} ports (the core's listeners) can be neither reached nor bound by the command, on any address
   * — {@code ::ffff:0.0.0.0}, which the kernel connects to {@code 127.0.0.1}, included — while another loopback port
   * stays reachable and an ephemeral port bindable. The probe is Perl: its sockets are the kernel's, with no runtime
   * mapping an address first. Every address it reaches unconfined must be refused inside the sandbox.
   */
  @Test
  @DisabledOnOs(OS.WINDOWS)
  void theCommandNeitherReachesNorBindsADeniedPort() throws Exception {
    String os = System.getProperty("os.name");
    assumeTrue(os.startsWith("Mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))
        || os.startsWith("Linux") && Sandbox.landlockAbi() >= Sandbox.NET_ABI,
        "sandbox-exec on macOS, or Landlock ABI 4 (Linux 6.7) for its TCP rules");
    Path perl = Sandbox.resolve("perl", System.getenv());
    assumeTrue(perl != null, "perl is on the PATH");
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    try (ServerSocket core = new ServerSocket(0, 50, loopback); ServerSocket other = new ServerSocket(0, 50, loopback)) {
      for (ServerSocket listener : List.of(core, other)) {
        Thread.ofVirtual().start(() -> {
          while (!listener.isClosed()) {
            try {
              listener.accept().close();
            } catch (IOException e) {
              // Closed at the end of the test.
            }
          }
        });
      }
      int free;
      try (ServerSocket probe = new ServerSocket(0, 1, loopback)) {
        free = probe.getLocalPort();
      }
      List<String> args = List.of(perl.toString(), "-e", PORT_PROBE, Integer.toString(core.getLocalPort()),
          Integer.toString(free), Integer.toString(other.getLocalPort()));

      Map<String, String> unconfined = probe(new ProcessBuilder(args).redirectErrorStream(true).start());
      List<String> sandboxed = new java.util.ArrayList<>(List.of("sandbox-exec", "--deny-tcp",
          Integer.toString(core.getLocalPort()), "--deny-tcp", Integer.toString(free), "--"));
      sandboxed.addAll(args);
      BridgeProcess.Result r = BridgeProcess.run(sandboxed, Map.of("GW_HOME", dir.resolve("gateway-home").toString()),
          "", Duration.ofSeconds(60));
      assertThat(r.code()).as(r.err()).isZero();
      Map<String, String> confined = lines(r.out());

      // Unconfined, the core's listener is reached by its address, the mapped forms and the wildcard (which the kernel
      // connects to 127.0.0.1), and the free port binds.
      assertThat(unconfined).containsEntry("connect 127.0.0.1 core", "ok").containsEntry("connect ::ffff:0.0.0.0 core",
          "ok").containsEntry("bind 127.0.0.1 free", "ok").containsEntry("connect 127.0.0.1 open", "ok");
      unconfined.forEach((what, result) -> {
        if (result.equals("ok") && (what.endsWith(" core") || what.endsWith(" free"))) {
          assertThat(confined.get(what)).as(what + "\n" + r.out()).isNotNull().isNotEqualTo("ok");
        }
      });
      // EPERM from Seatbelt, EACCES from Landlock: a refusal of the sandbox, not "connection refused".
      assertThat(confined.get("connect ::ffff:0.0.0.0 core")).isIn("Operation not permitted", "Permission denied");
      assertThat(confined).containsEntry("bind 127.0.0.1 0", "ok").containsEntry("connect 127.0.0.1 open", "ok");
    }
  }

  /**
   * {@code perl -e PORT_PROBE <core> <free> <open>}: connects to the core's port by every address form, binds the free
   * one by each loopback form and an ephemeral one, connects to the open port; one {@code <what>: <ok|error>} line each.
   */
  private static final String PORT_PROBE = """
      use strict; use warnings; use Socket qw(:all);
      my ($core, $free, $open) = @ARGV;
      sub attempt {
        my ($what, $host, $port) = @_;
        my ($family, $addr) = $host =~ /:/ ? (AF_INET6, pack_sockaddr_in6($port, inet_pton(AF_INET6, $host)))
            : (AF_INET, pack_sockaddr_in($port, inet_aton($host)));
        socket(my $s, $family, SOCK_STREAM, 0) or return "socket: $!";
        my $ok = $what eq 'connect' ? connect($s, $addr) : bind($s, $addr);
        my $result = $ok ? 'ok' : "$!";
        close $s;
        return $result;
      }
      $| = 1;
      for my $h ('127.0.0.1', '0.0.0.0', '::ffff:127.0.0.1', '::ffff:0.0.0.0', '::1', '::') {
        print "connect $h core: ", attempt('connect', $h, $core), "\\n";
      }
      for my $h ('127.0.0.1', '::ffff:127.0.0.1', '::1') {
        print "bind $h free: ", attempt('bind', $h, $free), "\\n";
      }
      print "bind 127.0.0.1 0: ", attempt('bind', '127.0.0.1', 0), "\\n";
      print "connect 127.0.0.1 open: ", attempt('connect', '127.0.0.1', $open), "\\n";
      """;

  private static Map<String, String> probe(Process p) throws Exception {
    p.getOutputStream().close();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(p.exitValue()).as(out).isZero();
    return lines(out);
  }

  /** {@code <what>: <result>} lines, by what (an IPv6 address has no colon followed by a space). */
  private static Map<String, String> lines(String out) {
    Map<String, String> results = new java.util.LinkedHashMap<>();
    for (String line : out.split("\n")) {
      int colon = line.indexOf(": ");
      if (colon > 0) {
        results.put(line.substring(0, colon), line.substring(colon + 2).strip());
      }
    }
    return results;
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void noGrantOpensTheHomeGateRooksHomeOrTheCredentials() throws Exception {
    Path home = Files.createDirectories(dir.resolve("home"));
    Path gateway = Files.createDirectories(home.resolve(".gaterook"));
    Files.createDirectories(home.resolve(".ssh"));
    Sandbox.Closed closed = closed(home, gateway);

    // Landlock opens all of a path: the home or a folder above it would open everything in it.
    assertThat(closed.refusal(home, false)).contains("holds the home folder");
    assertThat(closed.refusal(dir, false)).contains("holds the home folder");
    assertThat(closed.refusal(Path.of("/"), true)).contains("holds the home folder");
    // GateRook's home, in it or above it; a credential folder, in it or above it.
    assertThat(closed.refusal(gateway, false)).contains("holds " + gateway);
    assertThat(closed.refusal(gateway.resolve("bin"), false)).contains("is in " + gateway);
    assertThat(closed.refusal(home.resolve(".ssh/keys"), false)).contains("is in " + home.resolve(".ssh"));
    assertThat(closed.refusal(home.resolve(".cargo"), false)).contains("holds " + home.resolve(".cargo/credentials"));
    assertThat(closed.refusal(home.resolve(".ssh/keys"), true)).contains("is in");
    assertThat(closed.refusal(gateway, true)).contains("holds " + gateway);
    // Through a link, as the kernel resolves it.
    Path link = Files.createSymbolicLink(home.resolve("data"), gateway);
    assertThat(closed.refusal(link, false)).isNotNull();
    // An installation in the home, its bin/ folder, a project; a work folder in GateRook's home is writable.
    assertThat(closed.refusal(home.resolve(".nvm/versions/node/v24.1.0"), false)).isNull();
    assertThat(closed.refusal(home.resolve(".cargo/bin"), false)).isNull();
    assertThat(closed.refusal(home.resolve("bin"), false)).isNull();
    assertThat(closed.refusal(gateway.resolve("mcp/github"), true)).isNull();
    assertThat(closed.refusal(Path.of("/usr"), false)).isNull();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void landlockReadsTheCommandsFolderOrOnlyTheCommandWhereItsFolderHoldsMore() throws Exception {
    Path home = Files.createDirectories(dir.resolve("home"));
    Path gateway = Files.createDirectories(home.resolve(".gaterook"));
    Sandbox.Closed closed = closed(home, gateway);
    ByteArrayOutputStream said = new ByteArrayOutputStream();
    PrintStream err = new PrintStream(said, true, StandardCharsets.UTF_8);

    Path node = home.resolve(".nvm/versions/node/v24.1.0/bin/node");
    assertThat(Sandbox.readable(Sandbox.parse(List.of("--", "node")), node, closed, err))
        .contains(node.getParent().toString(), "/usr", "/etc").doesNotContain(home.toString())
        // Where /etc/resolv.conf links (Landlock judges a link's target): without it no name resolves.
        .contains("/run/systemd/resolve", "/run/resolvconf", "/run/NetworkManager/resolv.conf");
    // A command in the home itself: the command, not the home.
    Path server = home.resolve("my-server");
    assertThat(Sandbox.readable(Sandbox.parse(List.of("--", "x")), server, closed, err)).contains(server.toString())
        .doesNotContain(home.toString());
    // A --ro path that would open the home or GateRook's home is left out, and said.
    assertThat(Sandbox.readable(Sandbox.parse(List.of("--ro", home.toString(), "--ro", gateway.toString(), "--ro",
        home.resolve(".volta").toString(), "--no-defaults", "--", "x")), server, closed, err))
        .containsExactly(home.resolve(".volta").toString());
    assertThat(said.toString(StandardCharsets.UTF_8)).contains(home + " holds the home folder")
        .contains(gateway + " holds " + gateway);
    // In GateRook's home even the command alone isn't read.
    assertThat(Sandbox.readable(Sandbox.parse(List.of("--", "x")), gateway.resolve("bin/gw-bridge"), closed, err))
        .noneMatch(p -> p.startsWith(gateway.toString()));
  }

  @Test
  void aWritablePathHoldingTheHomeOrGateRooksHomeIsRefused() throws Exception {
    Path gateway = Files.createDirectories(dir.resolve("gateway-home"));
    Map<String, String> env = Map.of("GW_HOME", gateway.toString());
    for (Path rw : List.of(gateway, dir, Path.of(System.getProperty("user.home")))) {
      BridgeProcess.Result r = BridgeProcess.run(List.of("sandbox-exec", "--rw", rw.toString(), "--allow-unsandboxed",
          "--", "true"), env, "", Duration.ofSeconds(30));
      assertThat(r.code()).as(r.err()).isEqualTo(3);
      assertThat(r.err()).contains("can't be opened to the command");
    }
  }

  @Test
  void onMacOsNoDefaultsIsRefusedRatherThanIgnored() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec")),
        "macOS with sandbox-exec");
    Map<String, String> env = Map.of("GW_HOME", dir.resolve("gateway-home").toString());
    BridgeProcess.Result refused = BridgeProcess.run(List.of("sandbox-exec", "--no-defaults", "--", "true"), env, "",
        Duration.ofSeconds(30));
    assertThat(refused.code()).as(refused.err()).isEqualTo(Sandbox.NO_SANDBOX);
    assertThat(refused.err()).contains("--no-defaults");
    BridgeProcess.Result allowed = BridgeProcess.run(List.of("sandbox-exec", "--no-defaults", "--allow-unsandboxed",
        "--", "true"), env, "", Duration.ofSeconds(30));
    assertThat(allowed.code()).as(allowed.err()).isZero();
  }

  private static Sandbox.Closed closed(Path home, Path gateway) {
    return new Sandbox.Closed(home, gateway, Sandbox.DENIED.stream().map(home::resolve).toList());
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
    // A file of the home outside the work folder: where a login item or a shell profile would go; and one in a cache
    // the person's own tools share, where a planted package would run unconfined later.
    Path stray = Path.of(System.getProperty("user.home")).resolve(".gaterook-sandbox-test-" + System.nanoTime());
    Path cached = Path.of(System.getProperty("user.home"), "Library", "Caches",
        "gaterook-sandbox-test-" + System.nanoTime());
    // Node lstat-s every folder above a script before it runs it (realpath): the folders above the work folder give
    // their metadata, while GateRook's home still can't be listed or read.
    String script = "cat " + gateway.resolve("master.keyset") + " || echo denied-read; "
        + "/usr/bin/stat -f %N " + gateway + " >/dev/null && echo stat-home; "
        + "/usr/bin/stat -f %N " + own.getParent() + " >/dev/null && echo stat-above; "
        + "ls " + gateway + " >/dev/null 2>&1 || echo denied-list; "
        + "cat " + own.resolve("state.json") + "; echo; "
        + "echo w > " + own.resolve("out.txt") + " && echo wrote; "
        + "echo w > " + stray + " 2>/dev/null || echo denied-write; "
        + "echo w > " + cached + " 2>/dev/null || echo denied-cache; "
        + "echo w > \"${TMPDIR:-/tmp}/gaterook-sandbox-test-$$\" && echo wrote-tmp && rm -f "
        + "\"${TMPDIR:-/tmp}/gaterook-sandbox-test-$$\"";
    BridgeProcess.Result r;
    try {
      // Reading is open on macOS but for the denied paths: a --ro path adds nothing, even one above GateRook's home.
      r = BridgeProcess.run(List.of("sandbox-exec", "--rw", own.toString(), "--ro", dir.toString(), "--", "/bin/sh",
          "-c", script), Map.of("GW_HOME", gateway.toString()), "", Duration.ofSeconds(30));
    } finally {
      Files.deleteIfExists(stray);
      Files.deleteIfExists(cached);
    }
    assertThat(r.out()).as(r.err()).contains("denied-read").contains("its own").contains("wrote")
        .contains("denied-write").contains("denied-cache").contains("wrote-tmp").doesNotContain("the key")
        .contains("stat-home").contains("stat-above").contains("denied-list");
    assertThat(r.code()).isZero();
    // The profile names both forms of each path; clients' configs and the credentials are closed too, and writes
    // outside the work folder, the temporary folders and the devices.
    String profile = Sandbox.macProfile(Map.of("GW_HOME", gateway.toString()), List.of(own.toString()), Set.of());
    assertThat(profile).contains("(subpath \"" + gateway + "\")").contains("(subpath \"" + gateway.toRealPath() + "\")")
        .contains("/.cursor\")").contains("/.codex\")").contains("/Library/Application Support/Claude\")")
        .contains("/.config/gh\")").contains("/.npmrc\")").contains("/.git-credentials\")").contains("/.claude.json\")")
        .contains("/Library/Application Support/GateRook\")").contains("/Library/Application Support/Cursor\")")
        .contains("(deny file-write* (subpath \"/\"))").contains("(allow file-write* (subpath \"/dev\"))")
        .doesNotContain("/.npm\"))").doesNotContain("/.cache\"))").doesNotContain("/Library/Caches\"))")
        .contains("(allow file-read* file-write* (subpath \"" + own + "\"))")
        .contains("(allow file-read-metadata\n").contains("(literal \"" + gateway + "\")")
        .contains("(literal \"" + gateway.toRealPath() + "\")").contains("(literal \"" + own.getParent() + "\")");
    // Metadata only, and after the denial: later rules win in SBPL.
    assertThat(profile.indexOf("(allow file-read-metadata")).isGreaterThan(profile.indexOf("(deny file-read* file-write*"));
    assertThat(profile).doesNotContain("(allow file-read* file-write* (subpath \"" + gateway + "\"))");
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
