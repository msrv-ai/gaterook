/**
 * SQL connectors for PostgreSQL ({@code postgres}), MySQL/MariaDB ({@code mysql}), Oracle Database ({@code oracle}),
 * SQL Server ({@code sqlserver}) and SQLite files ({@code sqlite}).
 *
 * <p>One {@link com.gaterook.connectors.sql.JdbcSqlConnector} per dialect, with the same four tools. Reads always
 * end with ROLLBACK. PostgreSQL, MySQL and Oracle enforce READ ONLY transactions; SQL Server requires strictly
 * analyzed SELECTs and known built-in functions; SQLite reads through a read-only handle of the file with
 * {@code query_only}. The account's grants provide server-enforced access boundaries; for a SQLite file, the file's
 * permissions do. Oracle autonomous routines invoked indirectly remain outside the read-only transaction.
 */
package com.gaterook.connectors.sql;
