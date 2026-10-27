package ai.msrv.gaterook.core.platform.boot;

/**
 * Why the core didn't start: the {@code reason} of the {@code {"ready":false}} line (ARCHITECTURE.md §7.1). Electron
 * maps each to a state of the status screen; anything it doesn't know is shown as "stopped" with the log.
 */
public enum StartupReason {
  /** The local API port is taken; the line carries {@code portOwner} when the core could find it. */
  PORT_IN_USE,
  /** Another core holds this gateway home, or SQLite stayed busy. */
  DB_LOCKED,
  /** A Flyway migration failed; the pre-migration backup is in {@code backups/}. */
  DB_MIGRATION_FAILED,
  /** The database connections don't have the settings the core relies on (pragmas, read-only routing). */
  DB_CHECK_FAILED,
  /** No bootstrap line arrived on stdin in time. */
  BOOTSTRAP_TIMEOUT,
  /** The bootstrap line was not valid. */
  BOOTSTRAP_INVALID,
  /** Anything else; the details are in {@code core.log}. */
  STARTUP_FAILED
}
