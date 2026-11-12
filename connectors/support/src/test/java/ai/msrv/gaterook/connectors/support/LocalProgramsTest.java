package ai.msrv.gaterook.connectors.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Programs are found where a terminal finds them; the login shell is asked only when nothing else has them. */
@DisabledOnOs(OS.WINDOWS)
class LocalProgramsTest {

  @TempDir
  Path dir;

  private Path program(Path folder, String name) throws Exception {
    Files.createDirectories(folder);
    Path p = Files.writeString(folder.resolve(name), "#!/bin/sh\nexit 0\n");
    p.toFile().setExecutable(true);
    return p;
  }

  @Test
  void theUsualFoldersComeBeforeTheLoginShellWhichIsAskedOnlyWhenNeeded() throws Exception {
    Path usual = program(dir.resolve("usual"), "gaterook-test-plugin");
    Path sdk = program(dir.resolve("sdk/bin"), "gaterook-test-sdk-plugin");
    AtomicInteger asked = new AtomicInteger();

    assertThat(LocalPrograms.find("gaterook-test-plugin", List.of(dir.resolve("usual")), () -> {
      asked.incrementAndGet();
      return List.of(sdk.getParent());
    })).contains(usual);
    assertThat(asked).hasValue(0);

    // Only the login shell's PATH has it, as for an SDK unpacked outside the usual folders.
    assertThat(LocalPrograms.find("gaterook-test-sdk-plugin", List.of(dir.resolve("usual")), () -> {
      asked.incrementAndGet();
      return List.of(sdk.getParent());
    })).contains(sdk);
    assertThat(asked).hasValue(1);
    assertThat(LocalPrograms.find("gaterook-test-missing", List.of(dir.resolve("usual")), List::of)).isEmpty();
  }

  @Test
  void aFileThatIsntExecutableIsNotAProgram() throws Exception {
    Path folder = Files.createDirectories(dir.resolve("plain"));
    Files.writeString(folder.resolve("gaterook-test-text"), "not a program");
    assertThat(LocalPrograms.find("gaterook-test-text", List.of(folder), List::of)).isEmpty();
  }

  @Test
  void theLoginShellsLastLineIsItsPath() throws Exception {
    // A shell whose rc files print a banner first: only the last line counts.
    Path shell = Files.writeString(dir.resolve("fake-shell"),
        "#!/bin/sh\necho 'Welcome back'\nprintf %s '/opt/sdk/bin:/usr/bin'\n");
    shell.toFile().setExecutable(true);
    assertThat(LocalPrograms.loginShellPath(Map.of("SHELL", shell.toString(), "HOME", dir.toString())))
        .contains("/opt/sdk/bin:/usr/bin");

    Path silent = Files.writeString(dir.resolve("silent-shell"), "#!/bin/sh\necho 'no path here'\n");
    silent.toFile().setExecutable(true);
    assertThat(LocalPrograms.loginShellPath(Map.of("SHELL", silent.toString()))).isEmpty();
    assertThat(LocalPrograms.loginShellPath(Map.of())).isEmpty();
  }

  @Test
  void aProgramRunsWithItsOwnFolderFirstInItsPath() {
    Path plugin = dir.resolve("sdk/bin/gaterook-test-plugin");
    assertThat(LocalPrograms.pathFor(plugin)).startsWith(plugin.getParent() + File.pathSeparator);
  }
}
