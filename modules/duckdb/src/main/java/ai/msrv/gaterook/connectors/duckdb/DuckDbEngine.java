package ai.msrv.gaterook.connectors.duckdb;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * DuckDB instances as GateRook opens them (docs/duckdb.md): one instance per call, isolated from every other (the
 * driver's instance cache off), closed when the call ends, so nothing an agent does outlives its call.
 *
 * <p>Every instance starts with DuckDB's own limits, set before the file opens: {@code access_mode=READ_ONLY} for
 * reads ({@code READ_WRITE} only for an approved change), {@code enable_external_access=false} with
 * {@code allowed_directories} limited to the connection's data folders, extensions neither installed nor loaded
 * automatically, community extensions and persistent secrets off, no temporary directory (nothing spills to disk), a
 * memory limit, a thread limit, and {@code lock_configuration=true}, after which no statement can change a setting.
 * The settings are read back from the open instance and a mismatch refuses the call.
 *
 * <p>A call runs on a platform thread of its own with a large stack (DuckDB's native code runs on the calling thread;
 * on the host's virtual threads it would pin a carrier for the whole query). The caller waits until the deadline,
 * then stops the statement with {@link Statement#cancel()} (DuckDB's interrupt, which also stops a runaway recursive
 * query) and waits a short grace for DuckDB to wind down. Calls on one file wait for each other through a
 * read/write lock: DuckDB refuses a read-write instance next to read-only ones in one process.
 */
final class DuckDbEngine {

  /** The stack of a call's thread: DuckDB's planner recurses deeply on nested expressions. */
  static final long STACK_BYTES = 16L * 1024 * 1024;
  /** How long a stopped statement may take to wind down. */
  static final long GRACE_MS = 2_000;

  private static final ConcurrentHashMap<Path, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();
  private static final AtomicBoolean CLEANED = new AtomicBoolean();

  /**
   * One instance to open.
   *
   * @param allowedDirectories what {@code allowed_directories} holds: the data folders' prefixes ({@code /…/})
   */
  record Spec(Path database, boolean writable, List<String> allowedDirectories, int memoryLimitMb, int threads) {}

  /** The work of a call on its open instance. */
  interface Work<T> {
    T run(Connection c, Call call) throws SQLException, ConnectorException;
  }

  /** What a call shares with whoever stops it: the statement that runs now, and why it was stopped. */
  static final class Call {
    private volatile Statement running;
    private volatile boolean stopped;
    private volatile boolean deadline;

    /** Registers the statement about to run; refuses it when the call was stopped already. */
    void running(Statement statement) throws SQLException {
      running = statement;
      if (stopped) {
        throw new SQLException("The call was stopped before the statement ran.");
      }
    }

    void stop(boolean atDeadline) {
      if (atDeadline && !stopped) {
        deadline = true;
      }
      stopped = true;
      Statement st = running;
      if (st != null) {
        try {
          st.cancel();
        } catch (SQLException | RuntimeException ignored) {
          // Already done or closed.
        }
      }
    }

    boolean stopped() {
      return stopped;
    }
  }

  /**
   * Why a call didn't give a result.
   *
   * @param opening      DuckDB didn't open the file (or another call kept it busy): nothing ran
   * @param stopped      the statement was stopped, at the deadline ({@code deadline}) or by cancellation
   * @param stillRunning it didn't end within the grace after it was stopped
   * @param sql          DuckDB's error, or {@code null}
   */
  static final class Failure extends Exception {
    private static final long serialVersionUID = 1L;
    final boolean opening;
    final boolean stopped;
    final boolean deadline;
    final boolean stillRunning;
    final transient SQLException sql;

    Failure(String message, boolean opening, boolean stopped, boolean deadline, boolean stillRunning,
        SQLException sql) {
      super(message, sql);
      this.opening = opening;
      this.stopped = stopped;
      this.deadline = deadline;
      this.stillRunning = stillRunning;
      this.sql = sql;
    }
  }

  private DuckDbEngine() {}

  // ── Running a call ──────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Runs {@code work} on a fresh instance of {@code spec} within {@code timeoutMs}: the lock wait, the opening, the
   * statement and the reading of its rows.
   *
   * @throws Failure           DuckDB's refusal, or the call stopped at its deadline or by cancellation
   * @throws ConnectorException what {@code work} itself refused
   */
  static <T> T run(Spec spec, ExecContext ctx, long timeoutMs, Work<T> work) throws Failure, ConnectorException {
    Call call = new Call();
    ctx.onCancel(() -> call.stop(false));
    CompletableFuture<T> result = new CompletableFuture<>();
    Thread worker = Thread.ofPlatform().name("duckdb-call").daemon(true).stackSize(STACK_BYTES)
        .unstarted(() -> work(spec, timeoutMs, work, call, result));
    worker.start();
    try {
      return result.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      call.stop(true);
      return stopped(result, call, ctx);
    } catch (InterruptedException e) {
      call.stop(false);
      try {
        return stopped(result, call, ctx);
      } finally {
        Thread.currentThread().interrupt();
      }
    } catch (ExecutionException e) {
      throw unwrap(e.getCause(), call);
    }
  }

  /** The worker: the file's lock, a fresh instance, the work, and the result handed over before the instance closes. */
  private static <T> void work(Spec spec, long timeoutMs, Work<T> work, Call call, CompletableFuture<T> result) {
    ReentrantReadWriteLock rw = LOCKS.computeIfAbsent(spec.database(), p -> new ReentrantReadWriteLock(true));
    Lock lock = spec.writable() ? rw.writeLock() : rw.readLock();
    try {
      if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
        result.completeExceptionally(new Failure("Another call of this connection kept the database file busy.",
            true, false, false, false, null));
        return;
      }
    } catch (InterruptedException e) {
      result.completeExceptionally(new Failure("Interrupted while waiting for the database file.", true, true,
          false, false, null));
      return;
    }
    try {
      if (call.stopped()) {
        result.completeExceptionally(new Failure("The call was stopped before DuckDB opened the file.", true, true,
            call.deadline, false, null));
        return;
      }
      Connection c;
      try {
        c = open(spec);
      } catch (SQLException e) {
        result.completeExceptionally(new Failure(e.getMessage(), true, false, false, false, e));
        return;
      }
      try {
        result.complete(work.run(c, call));
      } catch (SQLException | ConnectorException | RuntimeException | StackOverflowError e) {
        result.completeExceptionally(e);
      } finally {
        try {
          c.close();
        } catch (SQLException | RuntimeException ignored) {
          // A read-write instance checkpoints when it closes; what it committed is in its log either way.
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /** After a stop: the result if the work still ends (a change that committed at the deadline did happen). */
  private static <T> T stopped(CompletableFuture<T> result, Call call, ExecContext ctx)
      throws Failure, ConnectorException {
    long grace = Math.max(100, Math.min(GRACE_MS, ctx.remaining().toMillis() - 100));
    long until = System.nanoTime() + grace * 1_000_000;
    boolean interrupted = false;
    while (true) {
      long left = (until - System.nanoTime()) / 1_000_000;
      if (left <= 0) {
        break;
      }
      try {
        return result.get(Math.min(100, left), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        // A cancel can land just before DuckDB starts the statement: send it again.
        call.stop(call.deadline);
      } catch (InterruptedException e) {
        interrupted = true;
      } catch (ExecutionException e) {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
        throw unwrap(e.getCause(), call);
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    throw new Failure("DuckDB didn't stop the statement within " + grace + " ms.", false, true, call.deadline, true,
        null);
  }

  /** What ended the work: DuckDB's refusal as a {@link Failure}, the work's own refusal thrown as it is. */
  private static Failure unwrap(Throwable cause, Call call) throws ConnectorException {
    if (cause instanceof Failure f) {
      return f;
    }
    if (cause instanceof ConnectorException e) {
      throw e;
    }
    if (cause instanceof SQLException e) {
      return new Failure(e.getMessage(), false, call.stopped(), call.deadline, false, e);
    }
    if (cause instanceof StackOverflowError) {
      throw ConnectorException.badInput("The statement is nested too deeply for DuckDB.");
    }
    throw new ConnectorException(ConnectorException.Kind.INTERNAL, "DuckDB failed: " + cause, cause);
  }

  // ── Opening an instance ─────────────────────────────────────────────────────────────────────────────────────

  /** The settings an instance starts with, before DuckDB opens the file. */
  static Properties properties(Spec spec) {
    Properties p = new Properties();
    // The driver's own: an instance per connection, results read in full (its streaming mode loses DuckDB's errors).
    p.setProperty("jdbc_instance_cache", "false");
    p.setProperty("jdbc_stream_results", "false");
    p.setProperty("access_mode", spec.writable() ? "READ_WRITE" : "READ_ONLY");
    if (!spec.allowedDirectories().isEmpty()) {
      p.setProperty("allowed_directories", listLiteral(spec.allowedDirectories()));
    }
    p.setProperty("enable_external_access", "false");
    p.setProperty("autoinstall_known_extensions", "false");
    p.setProperty("autoload_known_extensions", "false");
    p.setProperty("allow_community_extensions", "false");
    p.setProperty("allow_persistent_secrets", "false");
    p.setProperty("temp_directory", "");
    p.setProperty("memory_limit", spec.memoryLimitMb() + "MB");
    p.setProperty("threads", Integer.toString(spec.threads()));
    p.setProperty("lock_configuration", "true");
    return p;
  }

  /** {@code ['/a/', '/b c/']}: DuckDB reads a list setting's text with backslash escapes. */
  static String listLiteral(List<String> values) {
    List<String> quoted = new ArrayList<>();
    for (String v : values) {
      quoted.add("'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'");
    }
    return "[" + String.join(", ", quoted) + "]";
  }

  /** A fresh instance of {@code spec}'s file with its settings in place, read back and checked. */
  static Connection open(Spec spec) throws SQLException {
    Connection c = Jdbc.DRIVER.connect("jdbc:duckdb:" + spec.database(), properties(spec));
    if (c == null) {
      throw new SQLException("The DuckDB driver didn't take the file " + spec.database().getFileName() + ".");
    }
    try {
      verify(c, spec);
      return c;
    } catch (SQLException | RuntimeException e) {
      try {
        c.close();
      } catch (SQLException ignored) {
        // Refused already.
      }
      throw e;
    }
  }

  /** The settings as the open instance reports them: what GateRook asked for, or the call is refused. */
  static void verify(Connection c, Spec spec) throws SQLException {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT "
        + "current_setting('access_mode')::VARCHAR, current_setting('enable_external_access')::VARCHAR, "
        + "current_setting('lock_configuration')::VARCHAR, current_setting('autoload_known_extensions')::VARCHAR, "
        + "current_setting('autoinstall_known_extensions')::VARCHAR, "
        + "current_setting('allow_community_extensions')::VARCHAR, "
        + "current_setting('allow_persistent_secrets')::VARCHAR, current_setting('temp_directory')::VARCHAR, "
        + "current_setting('allowed_directories')")) {
      if (!rs.next()) {
        throw new SQLException("DuckDB didn't report its settings.");
      }
      List<String> wrong = new ArrayList<>();
      expect(wrong, "access_mode", rs.getString(1), spec.writable() ? "read_write" : "read_only");
      expect(wrong, "enable_external_access", rs.getString(2), "false");
      expect(wrong, "lock_configuration", rs.getString(3), "true");
      expect(wrong, "autoload_known_extensions", rs.getString(4), "false");
      expect(wrong, "autoinstall_known_extensions", rs.getString(5), "false");
      expect(wrong, "allow_community_extensions", rs.getString(6), "false");
      expect(wrong, "allow_persistent_secrets", rs.getString(7), "false");
      expect(wrong, "temp_directory", rs.getString(8), "");
      // DuckDB keeps each folder as it resolves it: what it reports must be among the forms GateRook gave, all of them
      // when there are folders, none when there aren't.
      Set<String> allowed = new LinkedHashSet<>();
      java.sql.Array array = rs.getArray(9);
      if (array != null) {
        for (Object o : (Object[]) array.getArray()) {
          allowed.add(String.valueOf(o));
        }
      }
      if (allowed.isEmpty() != spec.allowedDirectories().isEmpty()
          || !spec.allowedDirectories().containsAll(allowed)) {
        wrong.add("allowed_directories");
      }
      if (!wrong.isEmpty()) {
        throw new SQLException("DuckDB didn't take GateRook's settings (" + String.join(", ", wrong) + ").");
      }
    }
  }

  private static void expect(List<String> wrong, String name, String actual, String expected) {
    if (actual == null || !actual.toLowerCase(Locale.ROOT).equals(expected)) {
      wrong.add(name);
    }
  }

  /** The driver, loaded on first use (after {@link #missingLibrary}): its first connection loads the library. */
  private static final class Jdbc {
    static final java.sql.Driver DRIVER = new org.duckdb.DuckDBDriver();
  }

  // ── The native library ──────────────────────────────────────────────────────────────────────────────────────

  /** The resource DuckDB's driver loads its library from on this system ({@code libduckdb_java.so_macos_arm64}). */
  static String libraryName() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).trim();
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT).trim();
    String osName = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos"
        : os.startsWith("linux") ? "linux" : os.replaceAll("[^a-z0-9_\\-.]", "");
    String cpu = switch (arch) {
      case "x86_64", "amd64" -> "amd64";
      case "aarch64", "arm64" -> "arm64";
      default -> arch.replaceAll("[^a-z0-9_\\-.]", "");
    };
    return "libduckdb_java.so_" + osName + "_" + cpu;
  }

  /**
   * Why DuckDB can't run here, or {@code null}: this JAR carries DuckDB's library for some systems only, and without
   * it the driver would look for one in the system's library folders instead.
   */
  static String missingLibrary() {
    String name = libraryName();
    if (DuckDbEngine.class.getResource("/" + name) != null) {
      return null;
    }
    return "This build of the DuckDB connector carries DuckDB for macOS on Apple Silicon and Windows x64, not for "
        + System.getProperty("os.name", "this system") + " " + System.getProperty("os.arch", "") + ".";
  }

  /**
   * DuckDB's driver copies its library into the temporary folder at every start of the connector's process and deletes
   * the copy when the process ends normally; a process that was stopped leaves its copy behind. Copies older than an
   * hour are deleted, once per process, and only from the connector's own temporary folder.
   */
  static void cleanStaleLibraries(Path workDir) {
    if (workDir == null || !CLEANED.compareAndSet(false, true)) {
      return;
    }
    try {
      Path tmp = Path.of(System.getProperty("java.io.tmpdir", "")).toRealPath();
      if (!tmp.equals(workDir.resolve("tmp").toRealPath())) {
        return;
      }
      Instant before = Instant.now().minus(Duration.ofHours(1));
      try (DirectoryStream<Path> copies = Files.newDirectoryStream(tmp, "libduckdb_java*.so")) {
        for (Path copy : copies) {
          if (Files.isRegularFile(copy) && Files.getLastModifiedTime(copy).toInstant().isBefore(before)) {
            Files.deleteIfExists(copy);
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      // In use (Windows keeps a loaded library) or gone: left for a later start.
    }
  }
}
