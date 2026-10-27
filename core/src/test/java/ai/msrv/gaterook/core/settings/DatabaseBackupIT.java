package ai.msrv.gaterook.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import ai.msrv.gaterook.testfixtures.TestCore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The backup job inside a whole core: a start owes a copy when there is none from the last 24 hours, and the copy it
 * makes leaves the folder with seven (M1.6). {@link DatabaseBackupJobTest} covers the rules one by one.
 */
class DatabaseBackupIT {

  @Test
  void aStartMakesTheMissedCopyAndKeepsSeven() throws Exception {
    Path home;
    try (TestCore core = TestCore.start("backup-start", "--gateway.backup.catch-up-delay=0s")) {
      home = core.home();
      TestCore.await(() -> copies(home).size() == 1, "the catch-up copy of a fresh home");
      DatabaseBackupJob.Status status = core.bean(DatabaseBackupJob.class).status();
      assertThat(status.lastFile()).isEqualTo(copies(home).getFirst());
      assertThat(status.lastError()).isNull();
      assertThat(status.nextRunAt()).isAfter(Instant.now());
    }

    // The computer was off for a while: the copies are days old, none is from today.
    Path folder = home.resolve("backups");
    for (String name : copies(home)) {
      Files.delete(folder.resolve(name));
    }
    Instant now = Instant.now();
    for (int day = 1; day <= 8; day++) {
      Path old = folder.resolve("gateway-2026-09-0" + day + ".db");
      Files.writeString(old, "an old copy");
      Files.setLastModifiedTime(old, FileTime.from(now.minus(Duration.ofDays(10 - day))));
    }
    Files.writeString(folder.resolve("pre-migration-2.db"), "before V3");

    try (TestCore core = TestCore.reopen(home, "--gateway.backup.catch-up-delay=0s")) {
      TestCore.await(() -> copies(home).stream().noneMatch(n -> n.startsWith("gateway-2026-09-01"))
          && copies(home).size() == DatabaseBackupJob.KEEP, "the catch-up copy and the pruning");
      List<String> kept = copies(home);
      assertThat(kept).doesNotContain("gateway-2026-09-01.db", "gateway-2026-09-02.db")
          .contains("gateway-2026-09-08.db", core.bean(DatabaseBackupJob.class).status().lastFile());
      assertThat(folder.resolve("pre-migration-2.db")).exists();
    }
  }

  /** This job's copies in the home's backup folder. */
  private static List<String> copies(Path home) {
    Path folder = home.resolve("backups");
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(folder)) {
      return entries.map(p -> p.getFileName().toString())
          .filter(n -> DatabaseBackupJob.COPY.matcher(n).matches())
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
