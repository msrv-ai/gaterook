package com.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gaterook.core.platform.GatewayPaths;
import com.gaterook.core.platform.ProblemException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PluginPathsTest {

  @TempDir
  Path tmp;

  private GatewayPaths gateway;
  private PluginPaths paths;

  @BeforeEach
  void openHome() {
    gateway = GatewayPaths.open(tmp.resolve("home"));
    paths = new PluginPaths(gateway);
  }

  @AfterEach
  void closeHome() {
    gateway.close();
  }

  @Test
  void preservesPluginLayoutAndPrereleaseBuildVersions() {
    assertThat(paths.jar("jira-service-desk", "1.3.0-rc.1+build.2"))
        .isEqualTo(gateway.home().resolve("plugins/jira-service-desk/1.3.0-rc.1+build.2/plugin.jar"));
    assertThat(paths.dataDir("jira-service-desk").resolve("tmp")).isDirectory();
    assertThat(paths.log("jira-service-desk"))
        .isEqualTo(gateway.logs().resolve("plugins/jira-service-desk.log"));
    assertThat(paths.staging()).isDirectory();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {".", "..", "../outside", "plugin/../../outside", "plugin/child", "plugin\\child",
      "/outside", "\\outside", "C:\\outside", "C:outside", "plugin:stream", "plugin\n", "plugin\u0000"})
  void rejectsUnsafeIdsAndVersionsBeforeCreatingDirectories(String value) {
    assertThatThrownBy(() -> paths.pluginDir(value)).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> paths.dataDir(value)).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> paths.log(value)).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> paths.jar("safe-plugin", value)).isInstanceOf(ProblemException.class);
    assertThat(gateway.home().resolve("plugin-data")).doesNotExist();
    assertThat(gateway.home().resolve("plugins/safe-plugin")).doesNotExist();
  }

  @Test
  void rejectsAbsolutePathsWithoutTouchingTheirContents() throws Exception {
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    Path sentinel = Files.writeString(outside.resolve("sentinel"), "unchanged");
    assertThatThrownBy(() -> paths.dataDir(outside.toString())).isInstanceOf(ProblemException.class);
    assertThatThrownBy(() -> paths.jar("safe-plugin", outside.toString())).isInstanceOf(ProblemException.class);
    assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
    assertThat(outside.resolve("tmp")).doesNotExist();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void rejectsSymlinkedPluginRootsBeforeCreatingOutsideDirectories() throws Exception {
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    Files.createSymbolicLink(gateway.home().resolve("plugin-data"), outside);
    assertThatThrownBy(() -> paths.dataDir("safe-plugin")).isInstanceOf(ProblemException.class);
    assertThat(outside.resolve("safe-plugin")).doesNotExist();

    Files.createSymbolicLink(gateway.home().resolve("plugins"), outside);
    assertThatThrownBy(paths::plugins).isInstanceOf(ProblemException.class);
    assertThatThrownBy(paths::staging).isInstanceOf(ProblemException.class);
    assertThat(outside.resolve(".staging")).doesNotExist();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void rejectsLinksToAnotherPluginsDirectoryAndOutsideJar() throws Exception {
    Path other = Files.createDirectories(paths.pluginDir("other-plugin"));
    Files.createSymbolicLink(paths.plugins().resolve("linked-plugin"), other);
    assertThatThrownBy(() -> paths.pluginDir("linked-plugin")).isInstanceOf(ProblemException.class);

    Path version = Files.createDirectories(paths.versionDir("safe-plugin", "1.0.0"));
    Path outside = Files.writeString(tmp.resolve("outside.jar"), "unchanged");
    Files.createSymbolicLink(version.resolve("plugin.jar"), outside);
    assertThatThrownBy(() -> paths.jar("safe-plugin", "1.0.0")).isInstanceOf(ProblemException.class);
    assertThat(Files.readString(outside)).isEqualTo("unchanged");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void rejectsPluginCreatedTemporaryDirectoryLinks() throws Exception {
    Path data = paths.dataDir("safe-plugin");
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    Files.delete(data.resolve("tmp"));
    Files.createSymbolicLink(data.resolve("tmp"), outside);
    assertThatThrownBy(() -> paths.dataDir("safe-plugin")).isInstanceOf(ProblemException.class);
  }
}
