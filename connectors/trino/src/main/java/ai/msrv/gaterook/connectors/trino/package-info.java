/**
 * Trino and Presto ({@code trino}): catalogs, schemas, tables and SQL reads over Trino's client protocol, one approved
 * change per call (docs/trino.md). Every read is first planned by Trino itself with
 * {@code EXPLAIN (TYPE IO, FORMAT JSON)}: it runs only when the plan writes no table and reads only the connection's
 * catalogs.
 */
package ai.msrv.gaterook.connectors.trino;
