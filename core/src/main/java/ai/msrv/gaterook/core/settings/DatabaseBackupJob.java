package ai.msrv.gaterook.core.settings;

import ai.msrv.gaterook.core.platform.GatewayDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The daily copy of the gateway database (ARCHITECTURE.md M1.6, kit Settings › Backup: "At 03:00, or at the next start
 * if the computer was off. The last 7 copies are kept."). At 03:00 local time, and once shortly after a start when the
 * newest copy is older than 24 hours (a laptop that sleeps at night never sees 03:00), it writes
 * {@code backups/gateway-YYYY-MM-DD.db} with {@code VACUUM INTO}; "Back up now" writes
 * {@code gateway-YYYY-MM-DD-HHmm.db} there or to a file the person chose. After every copy the folder keeps the newest
 * seven {@code gateway-*.db}: the copies before migrations ({@code pre-migration-*.db}) and the clients' config
 * backups in subfolders are never touched.
 *
 * <p>The copies hold secrets only encrypted and never the master key (kept apart, §9). One copy runs at a time; the
 * schedule lives on one parked virtual thread.
 */
public class DatabaseBackupJob implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(DatabaseBackupJob.class);

  /** Copies the folder keeps. */
  public static final int KEEP = 7;

  /** When the daily copy runs, local time. */
  public static final LocalTime AT = LocalTime.of(3, 0);

  /** How old the newest copy may be at a start before a catch-up copy is made. */
  public static final Duration CATCH_UP_AFTER = Duration.ofHours(24);

  /** Copies this job writes and prunes: daily ({@code gateway-2026-10-22.db}) and Back up now ({@code …-1430.db}). */
  static final Pattern COPY = Pattern.compile("gateway-\\d{4}-\\d{2}-\\d{2}(-\\d{4,6})?(-\\d+)?\\.db");

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

  /**
   * One copy.
   *
   * @param path where it is, {@code file} its name
   */
  public record Copy(String path, String file, Instant at, long sizeBytes) {}

  /**
   * Settings › Backup ({@code GET /api/backup}).
   *
   * @param lastError why the last attempt failed, or {@code null} when it succeeded
   */
  public record Status(boolean daily, String at, Instant lastBackupAt, String lastFile, String folder, int keep,
      String lastError, Instant lastAttemptAt, Instant nextRunAt) {}

  private final GatewayDatabase database;
  private final Path folder;
  private final BooleanSupplier daily;
  private final Supplier<Optional<JsonNode>> loadState;
  private final Consumer<ObjectNode> saveState;
  private final Clock clock;
  private final ZoneId zone;
  private final Duration catchUpDelay;
  private final ReentrantLock running = new ReentrantLock();
  private ScheduledExecutorService executor;
  private volatile Instant nextRunAt;

  /**
   * @param daily        whether the daily copy is on (Settings › Backup)
   * @param loadState    the last attempt as stored, {@code saveState} stores it
   * @param catchUpDelay how long after the start the catch-up copy waits, so that the start itself stays fast
   */
  public DatabaseBackupJob(GatewayDatabase database, Path folder, BooleanSupplier daily,
      Supplier<Optional<JsonNode>> loadState, Consumer<ObjectNode> saveState, Clock clock, ZoneId zone,
      Duration catchUpDelay) {
    this.database = Objects.requireNonNull(database, "database");
    this.folder = Objects.requireNonNull(folder, "folder");
    this.daily = Objects.requireNonNull(daily, "daily");
    this.loadState = Objects.requireNonNull(loadState, "loadState");
    this.saveState = Objects.requireNonNull(saveState, "saveState");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.zone = Objects.requireNonNull(zone, "zone");
    this.catchUpDelay = Objects.requireNonNull(catchUpDelay, "catchUpDelay");
  }

  // ── Schedule ────────────────────────────────────────────────────────────────────────────────────────────────

  @Override
  public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
    if (executor != null) {
      return;
    }
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
        Thread.ofVirtual().name("database-backup").factory());
    // The next 03:00 is hours away: a stop must not wait for it.
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    executor = scheduler;
    executor.schedule(this::catchUpQuietly, catchUpDelay.toMillis(), TimeUnit.MILLISECONDS);
    scheduleNext();
  }

  /** The next 03:00 local time strictly after {@code now}. */
  Instant nextRun(Instant now) {
    ZonedDateTime local = now.atZone(zone);
    ZonedDateTime candidate = local.toLocalDate().atTime(AT).atZone(zone);
    if (!candidate.toInstant().isAfter(now)) {
      candidate = local.toLocalDate().plusDays(1).atTime(AT).atZone(zone);
    }
    return candidate.toInstant();
  }

  private synchronized void scheduleNext() {
    if (executor == null || executor.isShutdown()) {
      return;
    }
    Instant next = nextRun(clock.instant());
    nextRunAt = next;
    // Planned again after each run rather than at a fixed rate: daylight saving time moves 03:00.
    executor.schedule(() -> {
      try {
        dailyQuietly();
      } finally {
        scheduleNext();
      }
    }, Math.max(0, Duration.between(clock.instant(), next).toMillis()), TimeUnit.MILLISECONDS);
  }

  private void catchUpQuietly() {
    try {
      catchUp();
    } catch (RuntimeException e) {
      log.warn("Catch-up backup of the database failed: {}", e.getMessage());
    }
  }

  private void dailyQuietly() {
    try {
      if (daily.getAsBoolean()) {
        daily();
      }
    } catch (RuntimeException e) {
      log.warn("Daily backup of the database failed: {}", e.getMessage());
    }
  }

  // ── Copies ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The copy a start owes when the computer was off at 03:00: made when the daily copy is on and the newest copy is
   * older than {@link #CATCH_UP_AFTER} (or there is none).
   */
  public Optional<Copy> catchUp() {
    if (!daily.getAsBoolean()) {
      return Optional.empty();
    }
    Optional<Instant> newest = copies().stream().findFirst().map(DatabaseBackupJob::modified);
    if (newest.isPresent() && newest.get().isAfter(clock.instant().minus(CATCH_UP_AFTER))) {
      return Optional.empty();
    }
    return daily();
  }

  /** Today's daily copy, unless one was made today already. */
  public Optional<Copy> daily() {
    Path target = folder.resolve("gateway-" + DAY.format(LocalDate.ofInstant(clock.instant(), zone)) + ".db");
    if (Files.exists(target)) {
      return Optional.empty();
    }
    return Optional.of(write(target, true));
  }

  /**
   * "Back up now": into the backup folder, or over the file the person chose in a save dialog ({@code null}: the
   * folder). A copy in the folder counts towards the seven kept.
   */
  public Copy now(Path chosen) {
    if (chosen != null) {
      return write(chosen, false);
    }
    String base = "gateway-" + MINUTE.format(clock.instant().atZone(zone));
    Path target = folder.resolve(base + ".db");
    for (int i = 2; Files.exists(target); i++) {
      target = folder.resolve(base + "-" + i + ".db");
    }
    return write(target, true);
  }

  private Copy write(Path target, boolean inFolder) {
    running.lock();
    Instant started = clock.instant();
    try {
      if (inFolder) {
        Files.createDirectories(folder);
        database.backupInto(target);
      } else {
        database.backupOver(target);
      }
      Copy copy = new Copy(target.toAbsolutePath().toString(), target.getFileName().toString(), started,
          Files.size(target));
      record(started, copy, null);
      log.info("Database backed up to {} ({} bytes)", copy.path(), copy.sizeBytes());
      if (inFolder) {
        prune();
      }
      return copy;
    } catch (IOException e) {
      record(started, null, e.getMessage());
      throw new UncheckedIOException("Can't back up the database to " + target + ": " + e.getMessage(), e);
    } finally {
      running.unlock();
    }
  }

  /** Keeps the newest {@link #KEEP} copies of this job; nothing else in the folder is touched. */
  public List<Path> prune() {
    List<Path> copies = copies();
    List<Path> removed = new ArrayList<>();
    for (Path old : copies.subList(Math.min(KEEP, copies.size()), copies.size())) {
      try {
        Files.deleteIfExists(old);
        removed.add(old);
      } catch (IOException e) {
        log.warn("Can't remove the old database copy {}: {}", old, e.getMessage());
      }
    }
    return removed;
  }

  /** This job's copies in the folder, newest first (by time written, then name). */
  List<Path> copies() {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
      for (Path p : entries) {
        if (Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            && COPY.matcher(p.getFileName().toString()).matches()) {
          out.add(p);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    out.sort(Comparator.comparing(DatabaseBackupJob::modified).reversed()
        .thenComparing(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()));
    return out;
  }

  private static Instant modified(Path p) {
    try {
      FileTime t = Files.getLastModifiedTime(p);
      return t.toInstant();
    } catch (IOException e) {
      return Instant.EPOCH;
    }
  }

  // ── State ───────────────────────────────────────────────────────────────────────────────────────────────────

  public Status status() {
    JsonNode state = loadState.get().orElse(JsonNodeFactory.instance.objectNode());
    Instant lastAt = instant(state.path("lastBackupAt"));
    String lastFile = state.path("lastFile").isString() ? state.get("lastFile").asString() : null;
    if (lastAt == null) {
      // A copy from before this state existed (or made by an older core): the folder knows.
      Optional<Path> newest = copies().stream().findFirst();
      lastAt = newest.map(DatabaseBackupJob::modified).orElse(null);
      lastFile = newest.map(p -> p.getFileName().toString()).orElse(null);
    }
    return new Status(daily.getAsBoolean(), AT.toString(), lastAt, lastFile, folder.toAbsolutePath().toString(), KEEP,
        state.path("lastError").isString() ? state.get("lastError").asString() : null,
        instant(state.path("lastAttemptAt")), nextRunAt);
  }

  private void record(Instant attemptAt, Copy copy, String error) {
    JsonNode previous = loadState.get().orElse(JsonNodeFactory.instance.objectNode());
    ObjectNode state = JsonNodeFactory.instance.objectNode();
    state.put("lastAttemptAt", attemptAt.toString());
    if (copy != null) {
      state.put("lastBackupAt", copy.at().toString());
      state.put("lastFile", copy.file());
    } else {
      state.set("lastBackupAt", previous.path("lastBackupAt").isString() ? previous.get("lastBackupAt") : null);
      state.set("lastFile", previous.path("lastFile").isString() ? previous.get("lastFile") : null);
      state.put("lastError", error == null ? "The copy failed." : error);
    }
    try {
      saveState.accept(state);
    } catch (RuntimeException e) {
      // The copy itself is what matters; the folder still tells when the last one was made.
      log.warn("Can't record the database backup: {}", e.getMessage());
    }
  }

  private static Instant instant(JsonNode node) {
    if (node == null || !node.isString()) {
      return null;
    }
    try {
      return Instant.parse(node.asString());
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public synchronized void destroy() throws InterruptedException {
    if (executor == null) {
      return;
    }
    // A copy in progress finishes (it is a read transaction plus a file); the next one is cancelled.
    executor.shutdown();
    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
      executor.shutdownNow();
    }
    executor = null;
  }
}
