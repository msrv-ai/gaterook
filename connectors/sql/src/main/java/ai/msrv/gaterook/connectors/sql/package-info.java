/**
 * SQL connectors for PostgreSQL ({@code postgres}), MySQL/MariaDB ({@code mysql}), Oracle Database ({@code oracle})
 * and SQL Server ({@code sqlserver}).
 *
 * <p>One {@link ai.msrv.gaterook.connectors.sql.JdbcSqlConnector} per dialect, with the same four tools. Reads always
 * end with ROLLBACK. PostgreSQL, MySQL and Oracle enforce READ ONLY transactions; SQL Server requires strictly
 * analyzed SELECTs and known built-in functions. The account's grants provide server-enforced access boundaries.
 * Oracle autonomous routines invoked indirectly remain outside the read-only transaction.
 */
package ai.msrv.gaterook.connectors.sql;
