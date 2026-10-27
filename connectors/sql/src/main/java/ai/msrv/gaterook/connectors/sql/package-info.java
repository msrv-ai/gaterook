/**
 * SQL connectors for PostgreSQL ({@code postgres}) and MySQL/MariaDB ({@code mysql}), report 04.
 *
 * <p>One {@link ai.msrv.gaterook.connectors.sql.JdbcSqlConnector} per dialect, with the same four tools. Reads run in a
 * READ ONLY transaction that always ends with ROLLBACK (the database is the guarantee); the JSqlParser classifier
 * picks the route; the account's grants are the real boundary. Step C1 implements execution; this package has the
 * final manifests.
 */
package ai.msrv.gaterook.connectors.sql;
