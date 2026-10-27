package ai.msrv.gaterook.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.msrv.gaterook.core.platform.GatewayDatabase;
import ai.msrv.gaterook.core.platform.TestGateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.node.ObjectNode;

/**
 * The daily database copy (M1.6 "backup: consistent copy with retention of seven") on a real SQLite file in WAL mode, with a
 * clock the test moves: the catch-up copy a start owes, the 03:00 schedule, "Back up now", and the folder pruned to
 * seven copies without touching anything else in it.
 */
class DatabaseBackupJobTest {

  private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
  /** 2026-10-24 14:30 in Berlin. */
  private static final Instant NOW = ZonedDateTime.of(2026, 10, 24, 14, 30, 0, 0, ZONE).toInstant();

  private Path home;
  private Path folder;
  private MovableClock clock;
  private GatewayDatabase database;
  private final AtomicBoolean daily = new AtomicBoolean(true);
  private final AtomicReference<ObjectNode> state = new AtomicReference<>();
  private DatabaseBackupJob job;

  @BeforeEach
  void setUp() throws SQLException {
    home = TestGateway.newHome("backup-job");
    folder = home.resolve("backups");
    Path file = home.resolve("gateway.db");
    String url = "jdbc:sqlite:" + file;
    try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
      s.execute("pragma journal_mode=wal");
      s.execute("create table audit (id integer primary key, line text)");
      for (int i = 0; i < 50; i++) {
        s.execute("insert into audit (line) values ('call " + i + "')");
      }
    }
    SQLiteDataSource reads = new SQLiteDataSource();
    reads.setUrl(url);
    database = new GatewayDatabase(file, url, reads);
    clock = new MovableClock(NOW);
    job = new DatabaseBackupJob(database, folder, daily::get, () -> Optional.ofNullable(state.get()), state::set,
        clock, ZONE, Duration.ZERO);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    job.destroy();
  }

  // ── Catch-up ────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aStartWithoutAnyCopyMakesOne() throws Exception {
    Optional<DatabaseBackupJob.Copy> copy = job.catchUp();

    assertThat(copy).isPresent();
    Path written = folder.resolve("gateway-2026-10-24.db");
    assertThat(copy.get().path()).isEqualTo(written.toAbsolutePath().toString());
    assertThat(copy.get().at()).isEqualTo(NOW);
    assertThat(rows(written)).as("the copy is a whole database").isEqualTo(50);
    assertThat(journalMode(written)).as("one self-contained file").isEqualTo("delete");
    assertThat(Files.getPosixFilePermissions(written)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(state.get().path("lastBackupAt").asString()).isEqualTo(NOW.toString());
    assertThat(state.get().path("lastFile").asString()).isEqualTo("gateway-2026-10-24.db");
    assertThat(job.status().lastError()).isNull();
  }

  @Test
  void aStartAfterMoreThanADayWithoutACopyCatchesUp() throws Exception {
    old("gateway-2026-10-22.db", NOW.minus(Duration.ofHours(25)));

    assertThat(job.catchUp()).isPresent();
    assertThat(folder.resolve("gateway-2026-10-24.db")).exists();
  }

  @Test
  void aStartWithAFreshCopyOwesNothing() throws Exception {
    old("gateway-2026-10-24.db", NOW.minus(Duration.ofHours(11)));
    old("gateway-2026-10-23-2210.db", NOW.minus(Duration.ofHours(16)));

    assertThat(job.catchUp()).isEmpty();
    assertThat(names()).containsExactlyInAnyOrder("gateway-2026-10-24.db", "gateway-2026-10-23-2210.db");
  }

  @Test
  void aCopyBeforeAMigrationDoesNotCountAsTheDailyOne() throws Exception {
    old("pre-migration-3.db", NOW.minus(Duration.ofHours(1)));

    assertThat(job.catchUp()).as("the folder has none of this job's copies").isPresent();
  }

  @Test
  void theDailyCopySwitchedOffMeansNoCatchUpEither() {
    daily.set(false);

    assertThat(job.catchUp()).isEmpty();
    assertThat(folder).doesNotExist();
  }

  @Test
  void theCatchUpRunsSoonAfterTheStartAndTheDailyIsPlannedAtThree() throws Exception {
    job.onApplicationEvent(null);

    Path written = folder.resolve("gateway-2026-10-24.db");
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (!Files.exists(written) || state.get() == null) {
      assertThat(System.nanoTime()).as("the catch-up copy").isLessThan(deadline);
      Thread.sleep(20);
    }
    assertThat(job.status().nextRunAt()).isEqualTo(ZonedDateTime.of(2026, 10, 25, 3, 0, 0, 0, ZONE).toInstant());
    long stop = System.nanoTime();
    job.destroy();
    assertThat(Duration.ofNanos(System.nanoTime() - stop)).as("a stop doesn't wait for 03:00")
        .isLessThan(Duration.ofSeconds(5));
  }

  // ── The 03:00 schedule ──────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theNextRunIsTheNextThreeOClockLocalTime() {
    assertThat(job.nextRun(at(2026, 10, 24, 2, 59))).isEqualTo(at(2026, 10, 24, 3, 0));
    assertThat(job.nextRun(at(2026, 10, 24, 3, 0))).as("strictly after").isEqualTo(at(2026, 10, 25, 3, 0));
    assertThat(job.nextRun(at(2026, 10, 24, 14, 30))).isEqualTo(at(2026, 10, 25, 3, 0));
    // The night clocks go back, two 03:00 are 25 hours apart: planned again after each run, not at a fixed rate.
    assertThat(Duration.between(job.nextRun(at(2026, 10, 23, 14, 30)), job.nextRun(at(2026, 10, 24, 14, 30))))
        .isEqualTo(Duration.ofHours(25));
  }

  @Test
  void theDailyCopyIsMadeOncePerDay() {
    clock.set(at(2026, 10, 24, 3, 0));
    assertThat(job.daily()).isPresent();
    clock.set(at(2026, 10, 24, 23, 59));
    assertThat(job.daily()).as("today's copy exists").isEmpty();
    clock.set(at(2026, 10, 25, 3, 0));
    assertThat(job.daily()).isPresent();
    assertThat(names()).containsExactlyInAnyOrder("gateway-2026-10-24.db", "gateway-2026-10-25.db");
  }

  // ── Back up now ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void backUpNowNamesItsCopiesByTheMinute() {
    DatabaseBackupJob.Copy first = job.now(null);
    DatabaseBackupJob.Copy second = job.now(null);

    assertThat(first.file()).isEqualTo("gateway-2026-10-24-1430.db");
    assertThat(second.file()).isEqualTo("gateway-2026-10-24-1430-2.db");
    assertThat(job.status().lastFile()).isEqualTo("gateway-2026-10-24-1430-2.db");
  }

  @Test
  void backUpNowReplacesTheChosenFileAtOnce() throws Exception {
    Path chosen = home.resolve("exports").resolve("before-upgrade.db");
    Files.createDirectories(chosen.getParent());
    Files.writeString(chosen, "an older copy");

    DatabaseBackupJob.Copy copy = job.now(chosen);

    assertThat(copy.path()).isEqualTo(chosen.toAbsolutePath().toString());
    assertThat(rows(chosen)).isEqualTo(50);
    try (Stream<Path> left = Files.list(chosen.getParent())) {
      assertThat(left.map(p -> p.getFileName().toString())).as("no temp file left").containsExactly(
          "before-upgrade.db");
    }
    assertThat(folder).as("a chosen file isn't one of the folder's copies").doesNotExist();
  }

  @Test
  void aFailedCopyIsRecordedAndTheLastGoodOneKept() throws Exception {
    job.now(null);
    Instant good = clock.instant();
    // The folder vanished and a file took its name: nothing can be written there.
    try (Stream<Path> copies = Files.list(folder)) {
      for (Path p : copies.toList()) {
        Files.delete(p);
      }
    }
    Files.delete(folder);
    Files.writeString(folder, "not a folder");
    clock.set(NOW.plus(Duration.ofMinutes(5)));

    assertThatThrownBy(() -> job.now(null)).isInstanceOf(UncheckedIOException.class);

    DatabaseBackupJob.Status status = job.status();
    assertThat(status.lastError()).isNotBlank();
    assertThat(status.lastAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    assertThat(status.lastBackupAt()).isEqualTo(good);
    assertThat(status.lastFile()).isEqualTo("gateway-2026-10-24-1430.db");
  }

  @Test
  void withoutAStateTheFolderTellsTheLastCopy() throws Exception {
    old("gateway-2026-10-21.db", NOW.minus(Duration.ofDays(3)));
    old("gateway-2026-10-22.db", NOW.minus(Duration.ofDays(2)));

    DatabaseBackupJob.Status status = job.status();

    assertThat(status.lastFile()).isEqualTo("gateway-2026-10-22.db");
    assertThat(status.lastBackupAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
    assertThat(status.keep()).isEqualTo(7);
    assertThat(status.at()).isEqualTo("03:00");
    assertThat(status.daily()).isTrue();
  }

  // ── Pruning ─────────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theFolderKeepsTheSevenNewestCopiesAndNothingElseIsTouched() throws Exception {
    // The new copy's file time is the computer's, whatever the test clock says: the old ones are older than that.
    Instant written = Instant.now();
    for (int day = 13; day <= 21; day++) {
      old("gateway-2026-10-" + day + ".db", written.minus(Duration.ofDays(24 - day)));
    }
    old("gateway-2026-10-20-1105.db", written.minus(Duration.ofDays(4)).plus(Duration.ofHours(8)));
    // Not this job's: kept whatever their age.
    old("pre-migration-3.db", written.minus(Duration.ofDays(60)));
    old("gateway.db.txt", written.minus(Duration.ofDays(60)));
    old("notes.txt", written.minus(Duration.ofDays(60)));
    Files.createDirectories(folder.resolve("clients"));
    Files.writeString(folder.resolve("clients").resolve("claude_desktop_config.json"), "{}");
    Files.createDirectories(folder.resolve("gateway-2026-01-01.db"));

    DatabaseBackupJob.Copy copy = job.now(null);

    assertThat(names()).containsExactlyInAnyOrder(
        copy.file(),
        "gateway-2026-10-21.db", "gateway-2026-10-20-1105.db", "gateway-2026-10-20.db", "gateway-2026-10-19.db",
        "gateway-2026-10-18.db", "gateway-2026-10-17.db",
        "pre-migration-3.db", "gateway.db.txt", "notes.txt", "clients", "gateway-2026-01-01.db");
    assertThat(folder.resolve("clients").resolve("claude_desktop_config.json")).exists();
    assertThat(job.copies()).hasSize(DatabaseBackupJob.KEEP);
  }

  @Test
  void pruningAloneRemovesOnlyWhatIsBeyondSeven() throws Exception {
    for (int day = 15; day <= 21; day++) {
      old("gateway-2026-10-" + day + ".db", NOW.minus(Duration.ofDays(24 - day)));
    }
    assertThat(job.prune()).isEmpty();

    old("gateway-2026-10-14.db", NOW.minus(Duration.ofDays(10)));
    assertThat(job.prune()).extracting(p -> p.getFileName().toString()).containsExactly("gateway-2026-10-14.db");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

  private void old(String name, Instant modified) throws IOException {
    Files.createDirectories(folder);
    Path p = folder.resolve(name);
    Files.writeString(p, "copy " + name);
    Files.setLastModifiedTime(p, FileTime.from(modified));
  }

  private List<String> names() {
    try (Stream<Path> entries = Files.list(folder)) {
      return entries.map(p -> p.getFileName().toString()).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Instant at(int year, int month, int day, int hour, int minute) {
    return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant();
  }

  private static long rows(Path copy) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copy); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("select count(*) from audit")) {
      return rs.next() ? rs.getLong(1) : -1;
    }
  }

  private static String journalMode(Path copy) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + copy); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("pragma journal_mode")) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  /** A clock the test sets. */
  private static final class MovableClock extends Clock {

    private volatile Instant now;

    MovableClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
