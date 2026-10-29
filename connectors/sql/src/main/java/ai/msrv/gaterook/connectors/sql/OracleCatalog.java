package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Oracle dictionary queries restricted to the objects visible to the authenticated user. */
final class OracleCatalog implements SqlCatalog {
  private static final String OBJECTS = "SELECT o.OWNER, o.OBJECT_NAME, o.OBJECT_TYPE, t.NUM_ROWS, c.COMMENTS "
      + "FROM ALL_OBJECTS o JOIN ALL_USERS u ON u.USERNAME = o.OWNER "
      + "LEFT JOIN ALL_TABLES t ON t.OWNER = o.OWNER AND t.TABLE_NAME = o.OBJECT_NAME "
      + "LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = o.OWNER AND c.TABLE_NAME = o.OBJECT_NAME "
      + "WHERE o.OBJECT_TYPE IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW') AND o.SUBOBJECT_NAME IS NULL "
      + "AND NOT (o.OBJECT_TYPE = 'TABLE' AND EXISTS (SELECT 1 FROM ALL_MVIEWS m "
      + "WHERE m.OWNER = o.OWNER AND m.MVIEW_NAME = o.OBJECT_NAME)) ";

  private final ExecContext context;
  private final long timeoutMs;

  OracleCatalog() {
    this(null, 5_000);
  }

  private OracleCatalog(ExecContext context, long timeoutMs) {
    this.context = context;
    this.timeoutMs = timeoutMs;
  }

  @Override
  public SqlCatalog forCall(ExecContext ctx, long timeoutMs) {
    return new OracleCatalog(ctx, timeoutMs);
  }

  @Override
  public Listing list(Connection c, String onlySchema, String pattern, Predicate<TableRef> allowed, int limit)
      throws SQLException {
    List<Table> tables = new ArrayList<>();
    LinkedHashSet<String> schemas = new LinkedHashSet<>();
    Map<String, int[]> counts = new LinkedHashMap<>();
    boolean truncated = false;
    try (PreparedStatement st = prepare(c, OBJECTS
        + "AND u.ORACLE_MAINTAINED = 'N' "
        + "AND (? IS NULL OR o.OWNER = ?) AND (? IS NULL OR o.OBJECT_NAME LIKE ?) ORDER BY 1, 2")) {
      st.setString(1, onlySchema);
      st.setString(2, onlySchema);
      st.setString(3, pattern);
      st.setString(4, pattern);
      st.setMaxRows(PostgresCatalog.SCAN_LIMIT + 1);
      try (ResultSet rows = st.executeQuery()) {
        int scanned = 0;
        while (rows.next()) {
          if (++scanned > PostgresCatalog.SCAN_LIMIT) { truncated = true; break; }
          String schema = rows.getString(1);
          String name = rows.getString(2);
          if (!allowed.test(new TableRef(schema, name))) continue;
          schemas.add(schema);
          String kind = kind(rows.getString(3));
          counts.computeIfAbsent(schema, key -> new int[2])[kind.endsWith("view") ? 1 : 0]++;
          long estimate = rows.getLong(4);
          Long estimatedRows = rows.wasNull() ? null : estimate;
          if (tables.size() < limit) tables.add(new Table(schema, name, kind, estimatedRows, rows.getString(5)));
          else truncated = true;
        }
      }
    }
    return new Listing(List.copyOf(schemas), List.copyOf(tables), counts, truncated);
  }

  @Override
  public Optional<ObjectNode> describe(Connection c, TableRef table) throws SQLException {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    try (PreparedStatement st = query(c, OBJECTS + "AND o.OWNER = ? AND o.OBJECT_NAME = ?", table);
         ResultSet rows = st.executeQuery()) {
      if (!rows.next()) return Optional.empty();
      out.put("schema", rows.getString(1)).put("name", rows.getString(2)).put("kind", kind(rows.getString(3)));
      long estimate = rows.getLong(4);
      if (rows.wasNull()) out.putNull("estimatedRows"); else out.put("estimatedRows", estimate);
      out.put("comment", rows.getString(5));
    }
    ArrayNode columns = out.putArray("columns");
    try (PreparedStatement st = query(c, "SELECT c.COLUMN_NAME, c.DATA_TYPE, c.CHAR_LENGTH, c.DATA_PRECISION, "
        + "c.DATA_SCALE, c.NULLABLE, c.DATA_DEFAULT, m.COMMENTS FROM ALL_TAB_COLUMNS c "
        + "LEFT JOIN ALL_COL_COMMENTS m ON m.OWNER = c.OWNER AND m.TABLE_NAME = c.TABLE_NAME "
        + "AND m.COLUMN_NAME = c.COLUMN_NAME WHERE c.OWNER = ? AND c.TABLE_NAME = ? ORDER BY c.COLUMN_ID", table);
         ResultSet rows = st.executeQuery()) {
      while (rows.next()) {
        String type = rows.getString(2);
        if (List.of("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR").contains(type)) type += "(" + rows.getInt(3) + ")";
        else if ("NUMBER".equals(type)) {
          String precision = rows.getString(4);
          String scale = rows.getString(5);
          if (precision != null) type += "(" + precision + (scale == null ? "" : "," + scale) + ")";
        }
        columns.addObject().put("name", rows.getString(1)).put("type", type)
            .put("nullable", "Y".equals(rows.getString(6))).put("default", rows.getString(7))
            .put("comment", rows.getString(8));
      }
    }
    ArrayNode primary = out.putArray("primaryKey");
    try (PreparedStatement st = query(c, "SELECT c.COLUMN_NAME FROM ALL_CONSTRAINTS p "
        + "JOIN ALL_CONS_COLUMNS c ON c.OWNER = p.OWNER AND c.CONSTRAINT_NAME = p.CONSTRAINT_NAME "
        + "WHERE p.OWNER = ? AND p.TABLE_NAME = ? AND p.CONSTRAINT_TYPE = 'P' ORDER BY c.POSITION", table);
         ResultSet rows = st.executeQuery()) {
      while (rows.next()) primary.add(rows.getString(1));
    }
    ArrayNode indexes = out.putArray("indexes");
    try (PreparedStatement st = query(c, "SELECT i.INDEX_NAME, i.UNIQUENESS, c.COLUMN_NAME, c.DESCEND, "
        + "CASE WHEN EXISTS (SELECT 1 FROM ALL_CONSTRAINTS p WHERE p.OWNER = i.TABLE_OWNER "
        + "AND p.TABLE_NAME = i.TABLE_NAME AND p.INDEX_NAME = i.INDEX_NAME AND p.CONSTRAINT_TYPE = 'P') "
        + "THEN 1 ELSE 0 END FROM ALL_INDEXES i JOIN ALL_IND_COLUMNS c ON c.INDEX_OWNER = i.OWNER "
        + "AND c.INDEX_NAME = i.INDEX_NAME WHERE i.TABLE_OWNER = ? AND i.TABLE_NAME = ? "
        + "ORDER BY i.INDEX_NAME, c.COLUMN_POSITION", table);
         ResultSet rows = st.executeQuery()) {
      Map<String, ObjectNode> byName = new LinkedHashMap<>();
      Map<String, List<String>> names = new LinkedHashMap<>();
      while (rows.next()) {
        String name = rows.getString(1);
        byName.computeIfAbsent(name, key -> JsonNodeFactory.instance.objectNode().put("name", key))
            .put("unique", "UNIQUE".equals(rows.getString(2))).put("primary", rows.getInt(5) == 1);
        names.computeIfAbsent(name, key -> new ArrayList<>()).add(quote(rows.getString(3))
            + ("DESC".equals(rows.getString(4)) ? " DESC" : ""));
      }
      byName.forEach((name, index) -> {
        index.put("definition", (index.path("unique").asBoolean() ? "UNIQUE " : "") + "INDEX " + quote(name)
            + " (" + String.join(", ", names.get(name)) + ")");
        indexes.add(index);
      });
    }
    ArrayNode foreignKeys = out.putArray("foreignKeys");
    try (PreparedStatement st = query(c, "SELECT f.CONSTRAINT_NAME, fc.COLUMN_NAME, p.OWNER, p.TABLE_NAME, "
        + "pc.COLUMN_NAME FROM ALL_CONSTRAINTS f "
        + "JOIN ALL_CONS_COLUMNS fc ON fc.OWNER = f.OWNER AND fc.CONSTRAINT_NAME = f.CONSTRAINT_NAME "
        + "JOIN ALL_CONSTRAINTS p ON p.OWNER = f.R_OWNER AND p.CONSTRAINT_NAME = f.R_CONSTRAINT_NAME "
        + "JOIN ALL_CONS_COLUMNS pc ON pc.OWNER = p.OWNER AND pc.CONSTRAINT_NAME = p.CONSTRAINT_NAME "
        + "AND pc.POSITION = fc.POSITION WHERE f.OWNER = ? AND f.TABLE_NAME = ? AND f.CONSTRAINT_TYPE = 'R' "
        + "ORDER BY f.CONSTRAINT_NAME, fc.POSITION", table); ResultSet rows = st.executeQuery()) {
      Map<String, ObjectNode> keys = new LinkedHashMap<>();
      while (rows.next()) {
        String name = rows.getString(1);
        ObjectNode key = keys.computeIfAbsent(name, n -> {
          ObjectNode node = JsonNodeFactory.instance.objectNode().put("name", n);
          node.putArray("columns"); node.putArray("refColumns"); return node;
        });
        key.put("refSchema", rows.getString(3)).put("refTable", rows.getString(4));
        ((ArrayNode) key.get("columns")).add(rows.getString(2));
        ((ArrayNode) key.get("refColumns")).add(rows.getString(5));
      }
      keys.values().forEach(foreignKeys::add);
    }
    ArrayNode referencedBy = out.putArray("referencedBy");
    try (PreparedStatement st = query(c, "SELECT f.OWNER, f.TABLE_NAME, f.CONSTRAINT_NAME "
        + "FROM ALL_CONSTRAINTS f JOIN ALL_CONSTRAINTS p ON p.OWNER = f.R_OWNER "
        + "AND p.CONSTRAINT_NAME = f.R_CONSTRAINT_NAME WHERE p.OWNER = ? AND p.TABLE_NAME = ? "
        + "AND f.CONSTRAINT_TYPE = 'R' ORDER BY 1, 2, 3", table); ResultSet rows = st.executeQuery()) {
      while (rows.next()) referencedBy.addObject().put("schema", rows.getString(1)).put("table", rows.getString(2))
          .put("name", rows.getString(3));
    }
    ArrayNode checks = out.putArray("checks");
    try (PreparedStatement st = query(c, "SELECT CONSTRAINT_NAME, SEARCH_CONDITION FROM ALL_CONSTRAINTS "
        + "WHERE OWNER = ? AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'C' ORDER BY CONSTRAINT_NAME", table);
         ResultSet rows = st.executeQuery()) {
      while (rows.next()) checks.addObject().put("name", rows.getString(1))
          .put("definition", "CHECK (" + rows.getString(2) + ")");
    }
    return Optional.of(out);
  }

  private PreparedStatement query(Connection c, String sql, TableRef table) throws SQLException {
    PreparedStatement statement = prepare(c, sql);
    try {
      statement.setString(1, table.schema());
      statement.setString(2, table.name());
      return statement;
    } catch (SQLException | RuntimeException e) {
      statement.close();
      throw e;
    }
  }

  private PreparedStatement prepare(Connection c, String sql) throws SQLException {
    long remaining = context == null ? timeoutMs : Math.min(timeoutMs, context.remaining().toMillis());
    if (remaining <= 0 || (context != null && context.cancelled())) {
      throw new SQLTimeoutException("The Oracle catalog call was cancelled or reached its deadline.", "57014", 1013);
    }
    PreparedStatement statement = c.prepareStatement(sql);
    try {
      statement.setQueryTimeout((int) Math.max(1, (remaining + 999) / 1000));
      if (context != null) {
        context.onCancel(() -> {
          try { statement.cancel(); }
          catch (SQLException ignored) { /* A completed statement may already be closed. */ }
        });
        if (context.cancelled() || context.remaining().toMillis() <= 0) {
          throw new SQLTimeoutException("The Oracle catalog call was cancelled or reached its deadline.", "57014", 1013);
        }
      }
      return statement;
    } catch (SQLException | RuntimeException e) {
      statement.close();
      throw e;
    }
  }

  private static String kind(String type) { return type.toLowerCase(java.util.Locale.ROOT); }
  private static String quote(String name) { return IdentifierRules.quote(name, false); }
}
