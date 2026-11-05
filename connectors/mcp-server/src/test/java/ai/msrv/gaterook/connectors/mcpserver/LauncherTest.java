package ai.msrv.gaterook.connectors.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.connector.api.Env;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** How a server's process is started: its environment, its PATH, and the sandbox wrapper where it works. */
@DisabledOnOs(OS.WINDOWS)
class LauncherTest {

  @TempDir
  Path dir;

  private Path shim(String body) throws Exception {
    Path shim = dir.resolve("gw-bridge");
    Files.writeString(shim, "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwx------"));
    return shim;
  }

  private static ServerConfig server(String json) {
    return ServerConfig.of(Fixtures.cfg("c1", "fs", Env.DEV, 1, Fixtures.object(json)));
  }

  @Test
  void theEnvironmentIsTheBasicsTheAllowedVariablesAndTheSecrets() {
    Map<String, String> core = Map.of("PATH", "/usr/bin:/bin", "HOME", "/home/me", "USER", "me",
        "HTTPS_PROXY", "http://proxy:3128", "AWS_SECRET_ACCESS_KEY", "core-secret", "SSH_AUTH_SOCK", "/tmp/agent");
    Launcher l = new Launcher(core, "Linux", false, dir, null);
    ServerConfig s = server("""
        {"transport":"stdio","command":["node","x.js"],"inheritEnv":["HTTPS_PROXY"],"env":[{"name":"TOKEN"}]}""");

    Map<String, String> env = l.environment(s, Map.of("TOKEN", "t-1", "HOME", "/shadow"), dir);

    assertThat(env).containsEntry("USER", "me").containsEntry("HTTPS_PROXY", "http://proxy:3128")
        .containsEntry("TOKEN", "t-1").containsEntry("NPM_CONFIG_UPDATE_NOTIFIER", "false")
        .doesNotContainKeys("AWS_SECRET_ACCESS_KEY", "SSH_AUTH_SOCK");
    // A secret named like a basic variable is what the person configured.
    assertThat(env).containsEntry("HOME", "/shadow");
    assertThat(env.get("PATH")).startsWith("/usr/bin:/bin").contains("/usr/local/bin");
    assertThat(l.sandbox()).isNull();
    assertThat(l.argv(Path.of("/usr/bin/node"), List.of("x.js"), dir)).containsExactly("/usr/bin/node", "x.js");
  }

  @Test
  void whereTheSandboxWorksTheServerStartsInsideItWithItsOwnHome() throws Exception {
    // gw-bridge sandbox-exec [--ro p]… [--rw p]… -- cmd …: this stand-in only skips to "--" and runs the rest.
    Path shim = shim("shift; while [ \"$1\" != \"--\" ]; do shift; done; shift; exec \"$@\"");
    Launcher l = new Launcher(Map.of("PATH", "/usr/bin:/bin", "HOME", dir.toString()), "Linux", false,
        dir.resolve(".gaterook"), shim);

    assertThat(l.sandbox()).isEqualTo("landlock");
    Path work = dir.resolve("mcp/fs");
    assertThat(l.argv(Path.of("/usr/bin/node"), List.of("x.js"), work)).containsExactly(shim.toString(),
        "sandbox-exec", "--rw", work.toString(), "--", "/usr/bin/node", "x.js");
    Map<String, String> env = l.environment(server("{\"transport\":\"stdio\",\"command\":[\"node\"]}"), Map.of(),
        work);
    assertThat(env).containsEntry("HOME", work.toString()).containsEntry("TMPDIR", work.resolve("tmp").toString())
        .containsEntry("npm_config_cache", work.resolve(".npm").toString());
  }

  @Test
  void theSandboxReadsTheCommandsInstallationButNeverTheHomeNorGateRooksHome() throws Exception {
    Path shim = shim("exit 0");
    // As the kernel sees it: on macOS @TempDir is a link (/var is /private/var), and Landlock resolves links.
    Path home = Files.createDirectories(dir.toRealPath().resolve("home"));
    Path gateway = Files.createDirectories(home.resolve(".gaterook"));
    Path work = gateway.resolve("mcp/fs");
    Launcher l = new Launcher(Map.of("PATH", "/usr/bin:/bin", "HOME", home.toString()), "Linux", false, gateway, shim);
    assertThat(l.sandbox()).isEqualTo("landlock");

    // An nvm tree in the home: its lib/ beside bin/ (npm and npx live there).
    Path nvm = home.resolve(".nvm/versions/node/v24.1.0");
    assertThat(l.argv(executable(nvm.resolve("bin/node")), List.of(), work)).containsSequence("--ro", nvm.toString());
    // A link into a tool's own tree: the tree it points into.
    Path tool = home.resolve(".tools/srv");
    Path link = home.resolve(".local/bin/srv");
    Files.createDirectories(link.getParent());
    Files.createSymbolicLink(link, executable(tool.resolve("bin/srv")));
    assertThat(l.argv(link, List.of(), work)).containsSequence("--ro", tool.toString());
    // The folder above ~/bin is the home, above the home its parent, above GateRook's bin GateRook's home: each holds
    // GateRook's data and the credentials, so none is opened. A command in the work folder is read through --rw.
    for (Path command : List.of(home.resolve("bin/my-server"), home.resolve("my-server"),
        gateway.resolve("bin/gw-bridge"), work.resolve("node_modules/.bin/srv"))) {
      assertThat(l.argv(executable(command), List.of(), work)).as(command.toString()).doesNotContain("--ro")
          .containsSubsequence("--rw", work.toString(), "--", command.toString());
    }
    // A home given through a link is still the home.
    Launcher linked = new Launcher(Map.of("PATH", "/usr/bin:/bin", "HOME", dir.resolve("home").toString()), "Linux",
        false, gateway, shim);
    assertThat(linked.argv(nvm.resolve("bin/node"), List.of(), work)).containsSequence("--ro", nvm.toString());
    assertThat(linked.argv(home.resolve("bin/my-server"), List.of(), work)).doesNotContain("--ro");
  }

  private static Path executable(Path file) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "#!/bin/sh\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    return file;
  }

  @Test
  void whereItDoesntTheServerRunsUnconfinedAndTheReviewSaysSo() throws Exception {
    Path shim = shim("exit 4");
    Launcher l = new Launcher(Map.of("PATH", "/usr/bin:/bin", "HOME", dir.toString()), "Linux", false,
        dir.resolve(".gaterook"), shim);

    assertThat(l.sandbox()).isNull();
    assertThat(l.argv(Path.of("/usr/bin/node"), List.of(), dir)).containsExactly("/usr/bin/node");
  }

  @Test
  void commandsAreFoundOnThePathItGivesServers() throws Exception {
    Path bin = Files.createDirectories(dir.resolve("bin"));
    Path tool = bin.resolve("my-server");
    Files.writeString(tool, "#!/bin/sh\n");
    Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwx------"));
    Launcher l = new Launcher(Map.of("PATH", bin + ":/usr/bin:/bin", "HOME", dir.toString()), "Linux", false,
        dir.resolve(".gaterook"), null);

    assertThat(l.resolve("my-server", dir)).contains(tool.toAbsolutePath());
    assertThat(l.resolve("no-such-server", dir)).isEmpty();
    assertThat(l.resolve("./bin/my-server", dir)).contains(tool);
  }
}
