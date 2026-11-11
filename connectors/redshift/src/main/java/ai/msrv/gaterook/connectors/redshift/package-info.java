/**
 * Amazon Redshift ({@code redshift}): schemas, tables and SQL reads through the Redshift Data API, one approved change
 * per call (docs/redshift.md). Every read runs in a READ ONLY transaction that Redshift enforces and that is rolled
 * back; with an IAM role, a generated session policy keeps each call to the connection's warehouse, database and
 * sign-in.
 */
package ai.msrv.gaterook.connectors.redshift;
