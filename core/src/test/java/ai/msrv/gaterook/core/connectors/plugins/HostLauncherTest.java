package ai.msrv.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.SealedHandles;
import ai.msrv.gaterook.plugin.protocol.GppJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // None known: nothing is closed, and the network isn't handled.
    corePorts.set(Set.of());
    assertThat(launcher.command(java, hostJar, spec, data, false, "landlock")).doesNotContain("--deny-tcp");
    assertThat(launcher.macProfile(spec, data)).doesNotContain("(deny network");
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
