package com.gaterook.core.platform;

/**
 * Rows of a table that got its row MAC in a later version (ARCHITECTURE.md §4): what an older version wrote there has
 * no MAC yet. The start that applies {@link #migration()} signs them once, as they are, before anything reads them
 * ({@code LegacyRowSigning}); after that a row without a valid MAC is refused like any row changed outside GateRook.
 *
 * <p>A row whose absence would count as an edit (the audit retention: without it, retention would fall back to its
 * shortest period) exists from that migration on: the upgrade writes what the older version read in its place, and a
 * database the start creates gets it from {@link #signNewDatabase()}.
 */
public interface LegacyRows {

  /** The Flyway version of the migration that added the {@code mac} column ({@code "86"}). */
  String migration();

  /**
   * Signs every row that has no MAC yet, inside the caller's write transaction.
   *
   * @return what was signed, for the Setup change ("2 access tokens"), or {@code null} when nothing was
   */
  String signLegacyRows();

  /**
   * Writes, signed, the rows that a database must hold from {@link #migration()} on, in the start that creates the
   * database and inside the caller's write transaction. Nothing for most tables: they start empty.
   */
  default void signNewDatabase() {}
}
