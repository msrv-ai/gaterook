package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.clients.internal.persistence.ClientKind;
import ai.msrv.gaterook.core.clients.internal.persistence.LinkMethod;
import ai.msrv.gaterook.core.platform.ProblemException;
import ai.msrv.gaterook.core.testing.TestFileLinks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreClientPolicyTest {
  @TempDir Path home;

  private ClientEnvironment environment(boolean store, Map<String, String> variables) {
    return new ClientEnvironment(ClientEnvironment.Os.WINDOWS, home, home.resolve(".gaterook"), null,
        variables, false, "test", store);
  }

  @Test
  void permitsSharedHomeFilesAndKeepsTheLegacyConstructorUnrestricted() {
    StoreClientPolicy policy = new StoreClientPolicy(environment(true, Map.of()));
    assertThatCode(() -> {
      policy.runtime();
      policy.path(home.resolve(".codex/config.toml"));
      policy.path(home.resolve(".cursor/mcp.json"));
      policy.path(home.resolve("AppData-copy/file.json"));
      policy.method(ClientKind.CLAUDE_DESKTOP, LinkMethod.MCPB);
    }).doesNotThrowAnyException();
    ClientEnvironment legacy = new ClientEnvironment(ClientEnvironment.Os.WINDOWS, home, home, null,
        Map.of(), false, "test");
    assertThat(legacy.windowsStore()).isFalse();
    assertThatCode(() -> new StoreClientPolicy(legacy).path(home.resolve("AppData/config")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsClaudeConfigAndEveryKnownOrRedirectedAppDataRootCaseInsensitively() {
    Path roaming = home.resolve("redirected-roaming");
    Path local = home.resolve("redirected-local");
    Path profile = home.resolve("alternate-profile");
    StoreClientPolicy policy = new StoreClientPolicy(environment(true, Map.of("APPDATA", roaming.toString(),
        "LOCALAPPDATA", local.toString(), "USERPROFILE", profile.toString())));
    for (Path path : new Path[] { home.resolve("aPpDaTa/Roaming/codex/config.toml"), roaming.resolve("config"),
        local.resolve("config"), profile.resolve("AppData/LocalLow/config") }) {
      assertThatThrownBy(() -> policy.path(path)).isInstanceOf(ProblemException.class).hasMessageContaining("AppData");
    }
    assertThatThrownBy(() -> policy.method(ClientKind.CLAUDE_DESKTOP, LinkMethod.CONFIG_BRIDGE))
        .isInstanceOf(ProblemException.class).hasMessageContaining("extension");
  }

  @Test
  void resolvesExistingAncestorsAndFilesBeforeAcceptingMissingTargets() throws IOException {
    Path appData = Files.createDirectories(home.resolve("AppData/Roaming"));
    Path linked = TestFileLinks.directoryLink(home.resolve(".codex"), appData);
    StoreClientPolicy policy = new StoreClientPolicy(environment(true, Map.of()));
    assertThatThrownBy(() -> policy.path(linked.resolve("new/nested/config.toml")))
        .isInstanceOf(ProblemException.class).hasMessageContaining("AppData");
    Path target = Files.writeString(appData.resolve("mcp.json"), "{}");
    Path file = TestFileLinks.windows()
        ? TestFileLinks.directoryLink(home.resolve("client"), appData).resolve("mcp.json")
        : Files.createSymbolicLink(home.resolve("client.json"), target);
    assertThatThrownBy(() -> policy.path(file)).isInstanceOf(ProblemException.class);
    Path broken = TestFileLinks.directoryLink(home.resolve("broken"), home.resolve("missing"));
    assertThatThrownBy(() -> policy.path(broken.resolve("file"))).isInstanceOf(ProblemException.class);
  }

  @Test
  void rejectsAGatewayHomeRedirectedIntoAppData() throws IOException {
    Path target = Files.createDirectories(home.resolve("AppData/GateRook"));
    TestFileLinks.directoryLink(home.resolve(".gaterook"), target);
    assertThatThrownBy(() -> new StoreClientPolicy(environment(true, Map.of())).runtime())
        .isInstanceOf(ProblemException.class);
  }

  @Test
  void writerRejectsAParentRelocatedAfterPreviewWithoutWritingOrCreatingBackups() throws IOException {
    Path original = Files.createDirectories(home.resolve(".cursor"));
    Path file = Files.writeString(original.resolve("mcp.json"), "{}");
    var snapshot = ConfigFileWriter.read(file);
    Path moved = Files.move(original, home.resolve("original"));
    Path target = Files.createDirectories(home.resolve("AppData/Cursor"));
    Files.writeString(target.resolve("mcp.json"), "{}");
    TestFileLinks.directoryLink(original, target);
    var env = environment(true, Map.of());
    var writer = new ConfigFileWriter(env.backups(), Clock.systemUTC(), new StoreClientPolicy(env)::path);
    assertThatThrownBy(() -> writer.write(snapshot, "changed", "cursor", true))
        .isInstanceOf(ProblemException.class);
    assertThat(Files.readString(target.resolve("mcp.json"))).isEqualTo("{}");
    assertThat(Files.readString(moved.resolve("mcp.json"))).isEqualTo("{}");
    assertThat(Files.exists(env.backups())).isFalse();
    try (var files = Files.list(target)) {
      assertThat(files.toList()).containsExactly(target.resolve("mcp.json"));
    }
  }
}
