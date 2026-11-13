package com.gaterook.core.connectors.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The staged packages a start clears away: what an earlier run checked and never installed. A package that was
 * checked since this core started stays, because the admin API answers before the start's cleaning runs, and that
 * package is about to be installed.
 */
class StagedPackagesTest {

  @Test
  void onlyWhatAnEarlierRunLeftIsDeleted(@TempDir Path staging) throws Exception {
    Instant started = Instant.parse("2026-11-13T10:00:00Z");
    Path earlier = Files.write(staging.resolve("stg_earlier.jar"), new byte[] {1});
    Files.setLastModifiedTime(earlier, FileTime.from(started.minusSeconds(3_600)));
    Path thisRun = Files.write(staging.resolve("stg_this-run.jar"), new byte[] {2});
    Files.setLastModifiedTime(thisRun, FileTime.from(started.plusMillis(40)));

    PluginService.deleteStagedBefore(staging, started);

    assertThat(earlier).doesNotExist();
    assertThat(thisRun).exists();
    assertThat(staging).isDirectory();
  }

  @Test
  void aFolderThatIsNotThereIsNothingToClear(@TempDir Path home) {
    PluginService.deleteStagedBefore(home.resolve("plugins/.staging"), Instant.now());
    PluginService.deleteStagedBefore(null, Instant.now());
    assertThat(home.resolve("plugins")).doesNotExist();
  }
}
