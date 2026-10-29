package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.databind.node.ObjectNode;

/**
 * Introspection with bind parameters, in the call's READ ONLY transaction (report 04 §7.4). Only what the
 * connection's user may use: schemas with {@code USAGE}, tables with {@code SELECT} (MySQL's
 * {@code information_schema} filters by privileges itself).
 */
interface SqlCatalog {

  /** Per-call cancellation and timeout context for dialects without server-side statement timeouts. */
  default SqlCatalog forCall(ExecContext ctx, long timeoutMs) {
    return this;
  }

  /**
   * One table or view.
   *
   * @param kind {@code table}, {@code partitioned table}, {@code view}, {@code materialized view},
   *             {@code foreign table}
   */
  record Table(String schema, String name, String kind, Long estimatedRows, String comment) {}

  /** What a listing found: tables in order, and per schema how many tables and views matched in all. */
  record Listing(List<String> schemas, List<Table> tables, java.util.Map<String, int[]> counts, boolean truncated) {}

  /**
   * Schemas and tables, ordered by schema and name.
   *
   * @param onlySchema one schema (MySQL: database), or {@code null}
   * @param pattern    a {@code LIKE} pattern on table names, or {@code null}
   * @param allowed    the allowlist: tables it refuses are neither listed nor counted
   * @param limit      at most this many tables come back; {@code truncated} if more matched
   */
  Listing list(Connection c, String onlySchema, String pattern, Predicate<TableRef> allowed, int limit)
      throws SQLException;

  /** Columns, keys, indexes and checks of one table, or empty when the user can't see it. */
  Optional<ObjectNode> describe(Connection c, TableRef table) throws SQLException;
}
