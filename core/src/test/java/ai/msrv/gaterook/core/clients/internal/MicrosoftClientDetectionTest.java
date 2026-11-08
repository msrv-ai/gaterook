package ai.msrv.gaterook.core.clients.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.core.clients.internal.ClientEnvironment.Os;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MicrosoftClientDetectionTest {
  @TempDir Path home;

  private ClientEnvironment env(Os os) {
    return new ClientEnvironment(os, home, home.resolve(".gaterook"), null, Map.of(), true, "test");
  }

  @ParameterizedTest
  @EnumSource(Os.class)
  void detectsVsCodeUserDataWithoutCreatingOrManagingConfiguration(Os os) throws IOException {
    var adapter = new ClientAdapter.VsCode();
    var env = env(os);
    assertThat(adapter.detect(env).installed()).isFalse();
    Path data = switch (os) {
      case MAC -> home.resolve("Library/Application Support/Code");
      case WINDOWS -> env.appData().resolve("Code");
      case LINUX -> home.resolve(".config/Code");
    };
    Files.createDirectories(data);
    var detection = adapter.detect(env);
    assertThat(detection.installed()).isTrue();
    assertThat(detection.evidence()).containsExactly(env.display(data));
    assertThat(detection.configPath()).isNull();
    assertThat(Files.exists(data.resolve("User/mcp.json"))).isFalse();
  }

  @Test
  void detectsWindowsUserInstallBeforeFirstLaunch() throws IOException {
    var env = env(Os.WINDOWS);
    Path exe = env.localAppData().resolve("Programs/Microsoft VS Code/Code.exe");
    Files.createDirectories(exe.getParent());
    Files.writeString(exe, "test executable marker");
    assertThat(new ClientAdapter.VsCode().detect(env).evidence()).containsExactly(env.display(exe));
  }

  @Test
  void consumerCopilotIsWindowsOnlyAndDoesNotConfuseGithubCopilotWithTheStoreApp() throws IOException {
    var adapter = new ClientAdapter.MicrosoftCopilot();
    Files.createDirectories(home.resolve(".copilot"));
    Files.createDirectories(env(Os.WINDOWS).localAppData().resolve("Packages/Microsoft.MicrosoftOfficeHub_8wekyb3d8bbwe"));
    assertThat(adapter.detect(env(Os.WINDOWS)).installed()).isFalse();
    Path packageData = Files.createDirectories(env(Os.WINDOWS).localAppData()
        .resolve("Packages/Microsoft.Copilot_8wekyb3d8bbwe"));
    assertThat(adapter.detect(env(Os.WINDOWS)).evidence()).containsExactly(env(Os.WINDOWS).display(packageData));
    assertThat(adapter.detect(env(Os.MAC)).installed()).isFalse();
    assertThat(adapter.available(env(Os.LINUX))).isFalse();
    assertThat(adapter.configPath(env(Os.WINDOWS))).isNull();
    assertThat(adapter.methods()).isEmpty();
    assertThat(adapter.setupWarnings()).singleElement().asString().contains("cannot connect", "VS Code");
  }
}
