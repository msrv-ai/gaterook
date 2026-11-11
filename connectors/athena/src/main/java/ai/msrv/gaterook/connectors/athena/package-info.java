/**
 * Amazon Athena ({@code athena}): data catalogs, databases, tables and SQL reads in one workgroup, one approved change
 * per call (docs/athena.md). Athena's JSON API is signed with SigV4 through connectors/cloud-auth; with a role, each
 * call's session policy allows only what it needs. A query runs only after Athena's own
 * {@code EXPLAIN (TYPE IO, FORMAT JSON)} names no table it writes and only databases of the connection.
 */
package ai.msrv.gaterook.connectors.athena;
