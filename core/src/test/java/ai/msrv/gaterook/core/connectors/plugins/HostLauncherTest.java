package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.SealedHandles;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a host is launched (ARCHITECTURE.md §7.5, review 2026-10-27), without starting one: on Linux through the host's
 * first stage, which confines the process before its JVM starts (Landlock confines threads; a host that restricted
 * itself left the plugin's calls unconfined), with the JRE, the host's JAR, the plugin's folder and the AOT cache to
 * read and only its data folder to write; on macOS with a profile that also keeps the connected clients' configs
 * (where a gateway token may be) away from a plugin, as {@code gw-bridge sandbox-exec} does for MCP servers. Both keep
 * the plugin's own sockets from the core's listeners, as they are when the host starts.
 */
class HostLauncherTest {

  @TempDir
  Path tmp;

  private GatewayPaths gatewayPaths;
  private PluginPaths paths;
  private HostLauncher launcher;
  private Path hostJar;
  private Path java;
  /** The core's listeners as the egress settings report them: the local API, the tunnel's, and the bound port. */
  private final AtomicReference<Set<Integer>> corePorts = new AtomicReference<>(Set.of(7466, 7465, 0));

  @BeforeEach
  void setUp() throws Exception {
    gatewayPaths = GatewayPaths.open(tmp.resolve("home"));
    paths = new PluginPaths(gatewayPaths);
    hostJar = Files.writeString(tmp.resolve("plugin-host.jar"), "jar");
    java = Path.of(System.getProperty("java.home"), "bin", "java");
    HostRuntimeLocator runtime = new HostRuntimeLocator(null, hostJar.toString());
    launcher = new HostLauncher(runtime, paths, new SecretCallbackRegistry(),
        new SealedHandles((purpose, data) -> new byte[32], GppJson.MAPPER, Clock.systemUTC()),
        new AotCacheManager(runtime, paths, false), new HostPids(paths.pidFile()), corePorts::get,
        Clock.systemUTC());
  }

  @AfterEach
  void close() {
    gatewayPaths.close();
  }

  @Test
  void missingOptionalJavaDoesNotPermanentlyDisableTheWindowsOrLinuxSandbox() {
    HostRuntimeLocator missing = new HostRuntimeLocator(null, hostJar.toString(), null, true);
    HostLauncher waiting = new HostLauncher(missing, paths, new SecretCallbackRegistry(),
        new SealedHandles((purpose, data) -> new byte[32], GppJson.MAPPER, Clock.systemUTC()),
        new AotCacheManager(missing, paths, false), corePorts::get, Clock.systemUTC());
    // null means unknown: sandboxForStart retries after preparation. false would cache an unsandboxed launch.
    assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(waiting, "probeLowIntegrity")).isNull();
    assertThat((Boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(waiting, "probeLandlock")).isNull();
  }

  @Test
  void onLinuxTheHostStartsInsideTheFirstStagesLandlockDomain() throws Exception {
    Path jar = Files.createDirectories(paths.versionDir("jira-service-desk", "1.3.0")).resolve("plugin.jar");
    Files.writeString(jar, "plugin");
    HostLauncher.Spec spec = new HostLauncher.Spec("jira-service-desk", "1.3.0", jar, "00", 256, Map.of(),
        List.of());
    Path data = paths.dataDir("jira-service-desk");

    List<String> argv = launcher.command(java, hostJar, spec, data, false, "landlock");

    int stage = argv.indexOf(HostLauncher.SANDBOX_EXEC);
    int split = argv.indexOf("--");
    assertThat(stage).as("the first stage runs before the host").isPositive();
    assertThat(split).isGreaterThan(stage);
    List<String> rules = argv.subList(stage + 1, split);
    assertThat(pairs(rules, "--ro")).contains(Path.of(System.getProperty("java.home")).toRealPath().toString(),
        hostJar.toRealPath().toString(), jar.getParent().toRealPath().toString());
    assertThat(pairs(rules, "--rw")).contains(data.toRealPath().toString())
        .allMatch(p -> Path.of(p).endsWith(Path.of("plugin-data", "jira-service-desk")));
    // Neither bound nor connected to by the plugin's own sockets: the core's local and public ports (an unbound 0 is
    // no port).
    assertThat(pairs(rules, "--deny-tcp")).containsExactly("7465", "7466");
    // What it execs is the host itself, on the same JRE.
    List<String> host = argv.subList(split + 1, argv.size());
    assertThat(host.getFirst()).isEqualTo(java.toAbsolutePath().toString());
    assertThat(host).containsSequence("ai.msrv.gaterook.pluginhost.Main", "--protocol", "1");
    assertThat(argv.subList(0, stage)).contains("--enable-native-access=ALL-UNNAMED", "-Xlog:disable",
        "-Xlog:all=warning:stderr");
  }

  @Test
  void withoutASandboxTheHostIsStartedAsItIs() throws Exception {
    Path jar = Files.writeString(tmp.resolve("plugin.jar"), "plugin");
    HostLauncher.Spec spec = new HostLauncher.Spec("x", "1.0.0", jar, "00", 256, Map.of(), List.of());
    List<String> argv = launcher.command(java, hostJar, spec, paths.dataDir("x"), false, null);
    assertThat(argv.getFirst()).isEqualTo(java.toAbsolutePath().toString());
    assertThat(argv).doesNotContain(HostLauncher.SANDBOX_EXEC, "/usr/bin/sandbox-exec", "--deny-tcp");
  }

  @Test
  void theCoresPortsAreTheOnesItListensOnWhenTheHostStarts() throws Exception {
    Path jar = Files.writeString(tmp.resolve("plugin.jar"), "plugin");
    HostLauncher.Spec spec = new HostLauncher.Spec("x", "1.0.0", jar, "00", 256, Map.of(), List.of());
    Path data = paths.dataDir("x");

    // A core asked for any free port: the one its web server bound joins the bootstrap's.
    corePorts.set(Set.of(7466, 51_234));
    List<String> argv = launcher.command(java, hostJar, spec, data, false, "landlock");
    assertThat(pairs(argv.subList(0, argv.indexOf("--")), "--deny-tcp")).containsExactly("7466", "51234");
    assertThat(launcher.macProfile(spec, data)).contains("(remote ip \"*:51234\")", "(local ip \"*:51234\")")
        .doesNotContain("*:7465");

    // None known: no port is closed.
    corePorts.set(Set.of());
    assertThat(launcher.command(java, hostJar, spec, data, false, "landlock")).doesNotContain("--deny-tcp");
    assertThat(launcher.macProfile(spec, data)).doesNotContain("(remote ip").doesNotContain("network-bind");
  }

  @Test
  void theMacProfileDeniesTheGatewayHomeTheCredentialsAndTheClientsConfigs() throws Exception {
    Path jar = Files.createDirectories(paths.versionDir("jira-service-desk", "1.3.0")).resolve("plugin.jar");
    Files.writeString(jar, "plugin");
    HostLauncher.Spec spec = new HostLauncher.Spec("jira-service-desk", "1.3.0", jar, "00", 256, Map.of(),
        List.of());
    String profile = launcher.macProfile(spec, paths.dataDir("jira-service-desk"));

    String user = System.getProperty("user.home");
    // Writes go nowhere but the data folder and the devices: no login item or shell profile planted from inside.
    assertThat(profile).startsWith("(version 1)\n(allow default)\n(deny file-write* (subpath \"/\"))\n"
        + "(allow file-write* (subpath \"/dev\"))\n");
    int deny = profile.indexOf("(deny file-read* file-write*");
    int allow = profile.indexOf("(allow file-read* file-write*");
    assertThat(deny).isPositive();
    assertThat(allow).isGreaterThan(deny);
    String denied = profile.substring(deny, allow);
    assertThat(denied).contains("(subpath \"" + profilePath(gatewayPaths.home().toRealPath()) + "\")");
    // The folder of all MCP work folders on its own: kept on another disk behind a link it lies outside the home.
    assertThat(denied).contains("(subpath \"" + profilePath(gatewayPaths.home().toRealPath().resolve("mcp"))
        + "\")");
    for (String p : List.of(".ssh", ".aws", ".kube", ".gnupg", ".config/gh", ".password-store", ".npmrc",
        ".git-credentials", ".mcp-gateway", "Library/Application Support/GateRook", ".cursor", ".codex", ".gemini",
        ".claude.json", "Library/Application Support/Claude", "Library/Application Support/Cursor")) {
      assertThat(denied).contains("(subpath \"" + profilePath(Path.of(user).resolve(p)) + "\")");
    }
    // Inside the denied home: the plugin's data folder to write, its version folder to read.
    assertThat(profile.substring(allow)).contains(profilePath(paths.dataDir("jira-service-desk").toRealPath()),
        profilePath(jar.getParent().toRealPath()));
    // The core's listeners, last, on every address: "localhost" alone lets ::ffff:0.0.0.0 reach 127.0.0.1.
    assertThat(profile).endsWith("(deny network-outbound (remote ip \"*:7465\") (remote ip \"*:7466\"))\n"
        + "(deny network-bind (local ip \"*:7465\") (local ip \"*:7466\"))\n");
  }

  @Test
  void theHostDeniesWhatMcpServersAreDenied() throws Exception {
    // gw-bridge sandbox-exec keeps the same paths from MCP servers; this module can't depend on it, so its source says.
    Path sandbox = Path.of("..", "bridge-cli", "src", "main", "java", "ai", "msrv", "gaterook", "bridge",
        "Sandbox.java");
    String source = Files.readString(sandbox);
    Matcher list = Pattern.compile("List<String> DENIED = List\\.of\\(([^;]*)\\);").matcher(source);
    assertThat(list.find()).as("Sandbox.DENIED in " + sandbox).isTrue();
    List<String> bridge = Pattern.compile("\"([^\"]*)\"").matcher(list.group(1)).results().map(m -> m.group(1))
        .toList();
    assertThat(bridge).hasSizeGreaterThan(10);
    assertThat(HostLauncher.MAC_DENIED).containsExactlyElementsOf(bridge);
    Matcher folder = Pattern.compile("String WORK_FOLDERS = \"([^\"]*)\";").matcher(source);
    assertThat(folder.find()).as("Sandbox.WORK_FOLDERS in " + sandbox).isTrue();
    assertThat(HostLauncher.WORK_FOLDERS).isEqualTo(folder.group(1));
    Matcher closed = Pattern.compile("List<String> MAC_CLOSED = List\\.of\\(([^;]*)\\);").matcher(source);
    assertThat(closed.find()).as("Sandbox.MAC_CLOSED in " + sandbox).isTrue();
    assertThat(HostLauncher.MAC_CLOSED).containsExactlyElementsOf(Pattern.compile("\"([^\"]*)\"")
        .matcher(closed.group(1)).results().map(m -> m.group(1)).toList());
    Matcher resolver = Pattern.compile("String MAC_RESOLVER = \"([^\"]*)\";").matcher(source);
    assertThat(resolver.find()).as("Sandbox.MAC_RESOLVER in " + sandbox).isTrue();
    assertThat(HostLauncher.MAC_RESOLVER).isEqualTo(resolver.group(1));
  }

  /**
   * The profile also closes what would have something outside the sandbox act for the plugin, after the file rules and
   * before the port rules: Launch Services, Apple events, preference writes, signals outside its sandbox, and Unix
   * sockets but the resolver's and its data folder's.
   */
  @Test
  void theMacProfileClosesWhatWouldActForThePluginOutsideItsSandbox() throws Exception {
    Path jar = Files.writeString(tmp.resolve("plugin.jar"), "plugin");
    HostLauncher.Spec spec = new HostLauncher.Spec("x", "1.0.0", jar, "00", 256, Map.of(), List.of());
    Path data = Files.createDirectories(paths.dataDir("x")).toRealPath();
    String profile = launcher.macProfile(spec, data);

    assertThat(profile).contains("(deny lsopen appleevent-send user-preference-write)\n")
        .contains("(deny signal)\n(allow signal (target same-sandbox))\n")
        .contains("(deny network-outbound (remote unix-socket))\n")
        .contains("(allow network-outbound (remote unix-socket (path-literal \"/private/var/run/mDNSResponder\")) "
            + "(remote unix-socket (subpath \"" + profilePath(data) + "\")))\n")
        .endsWith("(deny network-bind (local ip \"*:7465\") (local ip \"*:7466\"))\n");
    assertThat(profile.indexOf("(deny lsopen")).isGreaterThan(profile.lastIndexOf("(allow file-read"));
  }

  /**
   * macOS, the real profile: an application the plugin wrote in its data folder isn't started by Launch Services
   * (it would run unconfined), no preference is written for it, a process of the person's outside its sandbox can't
   * be signalled, and a Unix socket outside its data folder can't be reached; its own children and sockets work.
   */
  @Test
  void onMacOsNothingOutsideTheSandboxActsForThePlugin() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec")),
        "macOS with sandbox-exec");
    assumeTrue(Files.isExecutable(Path.of("/usr/bin/perl")), "perl");
    // A Unix socket's path holds at most 104 bytes: a short folder in the system's temporary folder.
    Path base = Files.createTempDirectory(Path.of("/private/tmp"), "gwp").toRealPath();
    String domain = "dev.gaterook.plugin-sandbox-test-" + ProcessHandle.current().pid() + "-" + System.nanoTime();
    Process victim = new ProcessBuilder("/bin/sleep", "120").start();
    try {
      GatewayPaths shortHome = GatewayPaths.open(base.resolve("h"));
      try {
        PluginPaths shortPaths = new PluginPaths(shortHome);
        HostRuntimeLocator runtime = new HostRuntimeLocator(null, hostJar.toString());
        HostLauncher shortLauncher = new HostLauncher(runtime, shortPaths, new SecretCallbackRegistry(),
            new SealedHandles((purpose, d) -> new byte[32], GppJson.MAPPER, Clock.systemUTC()),
            new AotCacheManager(runtime, shortPaths, false), new HostPids(shortPaths.pidFile()), corePorts::get,
            Clock.systemUTC());
        Path jar = Files.createDirectories(shortPaths.versionDir("p", "1.0.0")).resolve("plugin.jar");
        Files.writeString(jar, "plugin");
        HostLauncher.Spec spec = new HostLauncher.Spec("p", "1.0.0", jar, "00", 256, Map.of(), List.of());
        Path data = Files.createDirectories(shortPaths.dataDir("p")).toRealPath();
        Path outside = Files.createDirectories(base.resolve("o"));
        Path launched = outside.resolve("launched");
        Path app = Files.createDirectories(data.resolve("Probe.app/Contents/MacOS"));
        Files.writeString(app.getParent().resolve("Info.plist"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict><key>CFBundleExecutable</key><string>probe</string>
            <key>CFBundleIdentifier</key><string>%s</string><key>CFBundlePackageType</key><string>APPL</string>
            <key>LSBackgroundOnly</key><true/></dict></plist>
            """.formatted(domain));
        Path probe = Files.writeString(app.resolve("probe"), "#!/bin/sh\n/usr/bin/touch '" + launched + "'\n");
        Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("rwx------"));
        try (var agent = listen(outside.resolve("agent.sock")); var own = listen(data.resolve("own.sock"))) {
          String script = "/usr/bin/open -g '" + data.resolve("Probe.app") + "' 2>/dev/null; echo \"open: $?\"; "
              + "/usr/bin/defaults write " + domain + " probe 1 2>/dev/null; echo \"defaults: $?\"; "
              + "kill -TERM " + victim.pid() + " 2>/dev/null; echo \"signal other: $?\"; "
              + "/bin/sleep 30 & child=$!; kill -TERM $child; echo \"signal child: $?\"; "
              + "/usr/bin/perl -MIO::Socket::UNIX -e 'for my $p (@ARGV) { my $c = IO::Socket::UNIX->new(Type => "
              + "SOCK_STREAM(), Peer => $p); print \"connect $p: \", ($c ? \"ok\" : \"$!\"), \"\\n\" }' '"
              + outside.resolve("agent.sock") + "' '" + data.resolve("own.sock") + "'";
          Process p = new ProcessBuilder("/usr/bin/sandbox-exec", "-p", shortLauncher.macProfile(spec, data),
              "/bin/sh", "-c", script).start();
          p.getOutputStream().close();
          String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
          String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
          assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();

          assertThat(out).as(err).doesNotContain("open: 0").doesNotContain("defaults: 0")
              .doesNotContain("signal other: 0").contains("signal child: 0")
              .contains("connect " + outside.resolve("agent.sock") + ": Operation not permitted")
              .contains("connect " + data.resolve("own.sock") + ": ok");
          Thread.sleep(1_500);
          assertThat(launched).doesNotExist();
          assertThat(victim.isAlive()).isTrue();
        }
      } finally {
        shortHome.close();
      }
    } finally {
      victim.destroyForcibly();
      new ProcessBuilder("/usr/bin/defaults", "delete", domain).redirectErrorStream(true).start().waitFor();
      try (var paths = Files.walk(base)) {
        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  private static ServerSocketChannel listen(Path socket) throws IOException {
    ServerSocketChannel s = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    s.bind(UnixDomainSocketAddress.of(socket));
    return s;
  }

  /**
   * The folder of all MCP work folders kept on another disk behind a link (reached through a second link): the
   * servers' work folders are where the link leads, outside GateRook's home as the kernel sees it. The plugin host
   * must not read them (each is a server's HOME, with caches and tokens), by any of their names, and still writes its
   * own data folder.
   */
  @Test
  void onMacOsALinkAtTheFolderOfAllWorkFoldersDoesNotOpenTheMcpServersFoldersToThePlugin() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec")),
        "macOS with sandbox-exec");
    Path real = tmp.toRealPath();
    Path disk = Files.createDirectories(real.resolve("disk/mcp"));
    Path alias = Files.createSymbolicLink(real.resolve("alias"), real.resolve("disk"));
    Path other = Files.createDirectories(disk.resolve("other"));
    Files.writeString(other.resolve("token"), "the server's token");
    Files.createSymbolicLink(gatewayPaths.home().resolve("mcp"), alias.resolve("mcp"));
    Path jar = Files.createDirectories(paths.versionDir("jira-service-desk", "1.3.0")).resolve("plugin.jar");
    Files.writeString(jar, "plugin");
    HostLauncher.Spec spec = new HostLauncher.Spec("jira-service-desk", "1.3.0", jar, "00", 256, Map.of(),
        List.of());
    Path data = Files.createDirectories(paths.dataDir("jira-service-desk")).toRealPath();
    String profile = launcher.macProfile(spec, data);
    String script = "cat \"" + other.resolve("token") + "\" 2>/dev/null || echo denied-resolved; "
        + "cat \"" + alias.resolve("mcp/other/token") + "\" 2>/dev/null || echo denied-alias; "
        + "cat \"" + gatewayPaths.home().resolve("mcp/other/token") + "\" 2>/dev/null || echo denied-given; "
        + "ls \"" + disk + "\" >/dev/null 2>&1 || echo denied-list; "
        + "echo y > \"" + data.resolve("own") + "\" && echo wrote-data";

    Process p = new ProcessBuilder("/usr/bin/sandbox-exec", "-p", profile, "/bin/sh", "-c", script).start();
    p.getOutputStream().close();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();

    assertThat(out).as(err).contains("denied-resolved").contains("denied-alias").contains("denied-given")
        .contains("denied-list").contains("wrote-data").doesNotContain("the server's token");
  }

  private static String profilePath(Path path) {
    return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static List<String> pairs(List<String> args, String flag) {
    List<String> out = new java.util.ArrayList<>();
    for (int i = 0; i + 1 < args.size(); i++) {
      if (args.get(i).equals(flag)) {
        out.add(args.get(i + 1));
      }
    }
    return out;
  }
}
