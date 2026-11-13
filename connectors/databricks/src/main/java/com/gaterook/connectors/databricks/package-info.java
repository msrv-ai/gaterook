/**
 * Databricks SQL ({@code databricks}): Unity Catalog catalogs, schemas and tables, and Databricks SQL reads on a SQL
 * warehouse through the SQL Statement Execution API 2.0, one approved change per call (docs/databricks.md). Databricks
 * SQL has no read-only session: GateRook reads each statement by Spark's tokens and sends reads only, and Unity
 * Catalog's grants are the boundary.
 */
package com.gaterook.connectors.databricks;
