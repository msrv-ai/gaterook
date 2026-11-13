/**
 * Google Cloud Spanner ({@code spanner}): tables and one GoogleSQL or PostgreSQL-dialect read per call, run in a
 * read-only transaction in which Spanner itself refuses every change; one approved DML or DDL statement per change
 * (docs/spanner.md). DML runs in its own read-write transaction and commits once; DDL is one schema change whose
 * operation id comes from the approval, so it can't apply twice.
 */
package com.gaterook.connectors.spanner;
