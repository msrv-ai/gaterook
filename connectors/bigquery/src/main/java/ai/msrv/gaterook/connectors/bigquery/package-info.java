/**
 * Google BigQuery ({@code bigquery}): datasets, tables and GoogleSQL SELECT as reads, one approved DML or DDL statement
 * per change (docs/bigquery.md). Every statement is first checked by BigQuery's free dry run: its type, the tables it
 * reads and the bytes it would process, run with that limit as {@code maximumBytesBilled}.
 */
package ai.msrv.gaterook.connectors.bigquery;
