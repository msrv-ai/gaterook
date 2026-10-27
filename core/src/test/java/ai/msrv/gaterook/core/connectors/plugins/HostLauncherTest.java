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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a host is launched (ARCHITECTURE.md §7.5, review 2026-10-27), without starting one: on Linux through the host's
 * first stage, which confines the process before its JVM starts (Landlock confines threads; a host that restricted
 * itself left the plugin's calls unconfined), with the JRE, the host's JAR, the plugin's folder and the AOT cache to
 * read and only its data folder to write; on macOS with a profile that also keeps the connected clients' configs
 * (where a gateway token may be) away from a plugin, as {@code gw-bridge sandbox-exec} does for MCP servers.
 */
class HostLauncherTest {

  @TempDir
  Path tmp;

  private GatewayPaths gatewayPaths;
  private PluginPaths paths;
  private HostLauncher launcher;
  private Path hostJar;
  private Path java;

  @BeforeEach
  void setUp() throws Exception {
    gatewayPaths = GatewayPaths.open(tmp.resolve("home"));
    paths = new PluginPaths(gatewayPaths);
    hostJar = Files.writeString(tmp.resolve("plugin-host.jar"), "jar");
    java = Path.of(System.getProperty("java.home"), "bin", "java");
    HostRuntimeLocator runtime = new HostRuntimeLocator(null, hostJar.toString());
    launcher = new HostLauncher(runtime, paths, new SecretCallbackRegistry(),
        new SealedHandles((purpose, data) -> new byte[32], GppJson.MAPPER, Clock.systemUTC()),
        new AotCacheManager(runtime, paths, false), new HostPids(paths.pidFile()), Clock.systemUTC());
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
        .allMatch(p -> p.endsWith("plugin-data/jira-service-desk"));
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
    assertThat(argv).doesNotContain(HostLauncher.SANDBOX_EXEC, "/usr/bin/sandbox-exec");
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
    assertThat(denied).contains("(subpath \"" + gatewayPaths.home().toRealPath() + "\")");
    for (String p : List.of(".ssh", ".aws", ".kube", ".cursor", ".codex", ".gemini",
        "Library/Application Support/Claude")) {
      assertThat(denied).contains("(subpath \"" + user + "/" + p + "\")");
    }
    // Inside the denied home: the plugin's data folder to write, its version folder to read.
    assertThat(profile.substring(allow)).contains(paths.dataDir("jira-service-desk").toRealPath().toString(),
        jar.getParent().toRealPath().toString());
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
