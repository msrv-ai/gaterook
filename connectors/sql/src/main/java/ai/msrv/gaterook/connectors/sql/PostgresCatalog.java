package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Introspection of PostgreSQL through {@code pg_catalog} (SQL of report 04 §7.4, checked on PostgreSQL 16). */
final class PostgresCatalog implements SqlCatalog {

  /** A listing reads at most this many catalog rows: the counts of a huge database stay cheap and approximate. */
  static final int SCAN_LIMIT = 20_000;

  private static final String VISIBLE = "c.relkind IN ('r','p','v','m','f') AND NOT c.relispartition "
      + "AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg\\_toast%' "
      + "AND n.nspname NOT LIKE 'pg\\_temp%' AND has_schema_privilege(n.oid, 'USAGE') "
      + "AND has_table_privilege(c.oid, 'SELECT')";

  @Override
  public Listing list(Connection c, String onlySchema, String pattern, Predicate<TableRef> allowed, int limit)
      throws SQLException {
    List<String> schemas = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT n.nspname FROM pg_namespace n WHERE "
        + "n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg\\_%' "
        + "AND has_schema_privilege(n.oid, 'USAGE') AND (?::text IS NULL OR n.nspname = ?) ORDER BY 1")) {
      bind(st, 1, onlySchema);
      bind(st, 2, onlySchema);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          schemas.add(rs.getString(1));
        }
      }
    }
    List<Table> tables = new ArrayList<>();
    Map<String, int[]> counts = new LinkedHashMap<>();
    boolean truncated = false;
    try (PreparedStatement st = c.prepareStatement("SELECT n.nspname, c.relname, c.relkind, "
        + "CASE WHEN c.reltuples < 0 THEN NULL ELSE c.reltuples::bigint END, obj_description(c.oid, 'pg_class') "
        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE " + VISIBLE
        + " AND (?::text IS NULL OR n.nspname = ?) AND (?::text IS NULL OR c.relname LIKE ?) ORDER BY 1, 2 LIMIT "
        + (SCAN_LIMIT + 1))) {
      bind(st, 1, onlySchema);
      bind(st, 2, onlySchema);
      bind(st, 3, pattern);
      bind(st, 4, pattern);
      st.setFetchSize(500);
      int scanned = 0;
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          if (++scanned > SCAN_LIMIT) {
            truncated = true;
            break;
          }
          String schema = rs.getString(1);
          String name = rs.getString(2);
          if (!allowed.test(new TableRef(schema, name))) {
            continue;
          }
          String kind = kind(rs.getString(3));
          int[] count = counts.computeIfAbsent(schema, k -> new int[2]);
          count[kind.endsWith("view") ? 1 : 0]++;
          if (tables.size() < limit) {
            long rows = rs.getLong(4);
            tables.add(new Table(schema, name, kind, rs.wasNull() ? null : rows, rs.getString(5)));
          } else {
            truncated = true;
          }
        }
      }
    }
    return new Listing(List.copyOf(schemas), List.copyOf(tables), counts, truncated);
  }

  @Override
  public Optional<ObjectNode> describe(Connection c, TableRef table) throws SQLException {
    long oid;
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    try (PreparedStatement st = c.prepareStatement("SELECT c.oid, n.nspname, c.relname, c.relkind, "
        + "CASE WHEN c.reltuples < 0 THEN NULL ELSE c.reltuples::bigint END, obj_description(c.oid, 'pg_class') "
        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ? "
        + "AND c.relkind IN ('r','p','v','m','f') AND has_schema_privilege(n.oid, 'USAGE') "
        + "AND has_table_privilege(c.oid, 'SELECT')")) {
      st.setString(1, table.schema());
      st.setString(2, table.name());
      try (ResultSet rs = st.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        oid = rs.getLong(1);
        out.put("schema", rs.getString(2));
        out.put("name", rs.getString(3));
        out.put("kind", kind(rs.getString(4)));
        long rows = rs.getLong(5);
        if (rs.wasNull()) {
          out.putNull("estimatedRows");
        } else {
          out.put("estimatedRows", rows);
        }
        out.put("comment", rs.getString(6));
      }
    }
    ArrayNode columns = out.putArray("columns");
    try (PreparedStatement st = c.prepareStatement("SELECT a.attname, format_type(a.atttypid, a.atttypmod), "
        + "NOT a.attnotnull, pg_get_expr(d.adbin, d.adrelid), col_description(a.attrelid, a.attnum) "
        + "FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
        + "WHERE a.attrelid = ?::oid AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum")) {
      st.setLong(1, oid);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          columns.addObject().put("name", rs.getString(1)).put("type", rs.getString(2))
              .put("nullable", rs.getBoolean(3)).put("default", rs.getString(4)).put("comment", rs.getString(5));
        }
      }
    }
    ArrayNode primaryKey = out.putArray("primaryKey");
    ArrayNode foreignKeys = out.putArray("foreignKeys");
    ArrayNode checks = JsonNodeFactory.instance.arrayNode();
    try (PreparedStatement st = c.prepareStatement("SELECT co.conname, co.contype, pg_get_constraintdef(co.oid, true), "
        + "ARRAY(SELECT a.attname::text FROM unnest(co.conkey) WITH ORDINALITY k(n, i) JOIN pg_attribute a "
        + "ON a.attrelid = co.conrelid AND a.attnum = k.n ORDER BY k.i), rn.nspname, rc.relname, "
        + "ARRAY(SELECT a.attname::text FROM unnest(co.confkey) WITH ORDINALITY k(n, i) JOIN pg_attribute a "
        + "ON a.attrelid = co.confrelid AND a.attnum = k.n ORDER BY k.i) "
        + "FROM pg_constraint co LEFT JOIN pg_class rc ON rc.oid = co.confrelid "
        + "LEFT JOIN pg_namespace rn ON rn.oid = rc.relnamespace WHERE co.conrelid = ?::oid ORDER BY co.conname")) {
      st.setLong(1, oid);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String type = rs.getString(2);
          switch (type) {
            case "p" -> strings(rs.getArray(4)).forEach(primaryKey::add);
            case "f" -> {
              ObjectNode fk = foreignKeys.addObject().put("name", rs.getString(1));
              ArrayNode cols = fk.putArray("columns");
              strings(rs.getArray(4)).forEach(cols::add);
              fk.put("refSchema", rs.getString(5)).put("refTable", rs.getString(6));
              ArrayNode refs = fk.putArray("refColumns");
              strings(rs.getArray(7)).forEach(refs::add);
              fk.put("definition", rs.getString(3));
            }
            case "c" -> checks.addObject().put("name", rs.getString(1)).put("definition", rs.getString(3));
            default -> {
              // Unique and exclusion constraints show up with their indexes.
            }
          }
        }
      }
    }
    ArrayNode referencedBy = out.putArray("referencedBy");
    try (PreparedStatement st = c.prepareStatement("SELECT n.nspname, c.relname, co.conname FROM pg_constraint co "
        + "JOIN pg_class c ON c.oid = co.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
        + "WHERE co.confrelid = ?::oid AND co.contype = 'f' ORDER BY 1, 2, 3")) {
      st.setLong(1, oid);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          referencedBy.addObject().put("schema", rs.getString(1)).put("table", rs.getString(2))
              .put("name", rs.getString(3));
        }
      }
    }
    ArrayNode indexes = out.putArray("indexes");
    try (PreparedStatement st = c.prepareStatement("SELECT i.relname, ix.indisunique, ix.indisprimary, "
        + "pg_get_indexdef(ix.indexrelid) FROM pg_index ix JOIN pg_class i ON i.oid = ix.indexrelid "
        + "WHERE ix.indrelid = ?::oid ORDER BY 1")) {
      st.setLong(1, oid);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          indexes.addObject().put("name", rs.getString(1)).put("unique", rs.getBoolean(2))
              .put("primary", rs.getBoolean(3)).put("definition", rs.getString(4));
        }
      }
    }
    out.set("checks", checks);
    return Optional.of(out);
  }

  static String kind(String relkind) {
    return switch (relkind) {
      case "p" -> "partitioned table";
      case "v" -> "view";
      case "m" -> "materialized view";
      case "f" -> "foreign table";
      default -> "table";
    };
  }

  private static void bind(PreparedStatement st, int index, String value) throws SQLException {
    if (value == null) {
      st.setNull(index, Types.VARCHAR);
    } else {
      st.setString(index, value);
    }
  }

  private static List<String> strings(Array array) throws SQLException {
    List<String> out = new ArrayList<>();
    if (array == null) {
      return out;
    }
    Object values = array.getArray();
    if (values instanceof Object[] items) {
      for (Object item : items) {
        out.add(String.valueOf(item));
      }
    }
    return out;
  }
}
