/**
 * SQL connectors for PostgreSQL ({@code postgres}), MySQL/MariaDB ({@code mysql}) and Oracle Database ({@code oracle}).
 *
 * <p>One {@link ai.msrv.gaterook.connectors.sql.JdbcSqlConnector} per dialect, with the same four tools. Reads run in a
 * READ ONLY transaction that always ends with ROLLBACK; the JSqlParser classifier picks the route and the account's
 * grants are the access boundary. Oracle autonomous routines invoked indirectly remain outside that transaction.
 */
package ai.msrv.gaterook.connectors.sql;
