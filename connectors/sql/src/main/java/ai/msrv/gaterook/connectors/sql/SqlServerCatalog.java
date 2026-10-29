package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Metadata from SQL Server's permission-filtered sys catalog, with all lookup values bound. */
final class SqlServerCatalog implements SqlCatalog {

  static final int SCAN_LIMIT = 20_000;
  static final String READABLE = "o.type IN ('U','V') AND o.is_ms_shipped = 0 "
      + "AND HAS_PERMS_BY_NAME(QUOTENAME(s.name) + '.' + QUOTENAME(o.name), 'OBJECT', 'SELECT') = 1";
  private static final String TABLE_FIELDS = "s.name, o.name, o.type, "
      + "(SELECT SUM(p.rows) FROM sys.partitions p WHERE p.object_id = o.object_id AND p.index_id IN (0,1)), "
      + "CONVERT(nvarchar(4000), ep.value)";
  private static final String TABLE_FROM = " FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id "
      + "LEFT JOIN sys.extended_properties ep ON ep.class = 1 AND ep.major_id = o.object_id "
      + "AND ep.minor_id = 0 AND ep.name = 'MS_Description'";

  @Override
  public Listing list(Connection c, String onlySchema, String pattern, Predicate<TableRef> allowed, int limit)
      throws SQLException {
    List<String> schemas = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT s.name FROM sys.schemas s "
        + "WHERE s.name NOT IN ('sys','INFORMATION_SCHEMA') AND (? IS NULL OR s.name = ?) AND "
        + "(HAS_PERMS_BY_NAME(s.name, 'SCHEMA', 'SELECT') = 1 OR EXISTS "
        + "(SELECT 1 FROM sys.objects o WHERE o.schema_id = s.schema_id AND " + READABLE + ")) ORDER BY s.name")) {
      st.setString(1, onlySchema);
      st.setString(2, onlySchema);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          schemas.add(rs.getString(1));
        }
      }
    }
    List<Table> tables = new ArrayList<>();
    Map<String, int[]> counts = new LinkedHashMap<>();
    boolean truncated = false;
    try (PreparedStatement st = c.prepareStatement("SELECT TOP (" + (SCAN_LIMIT + 1) + ") " + TABLE_FIELDS
        + TABLE_FROM + " WHERE " + READABLE + " AND (? IS NULL OR s.name = ?) "
        + "AND (? IS NULL OR o.name LIKE ?) ORDER BY s.name, o.name")) {
      st.setString(1, onlySchema);
      st.setString(2, onlySchema);
      st.setString(3, pattern);
      st.setString(4, pattern);
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
          boolean view = "V".equals(rs.getString(3).strip());
          counts.computeIfAbsent(schema, unused -> new int[2])[view ? 1 : 0]++;
          if (tables.size() < limit) {
            long rows = rs.getLong(4);
            Long estimate = rs.wasNull() ? null : rows;
            tables.add(new Table(schema, name, view ? "view" : "table", estimate, rs.getString(5)));
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
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    int id;
    try (PreparedStatement st = c.prepareStatement("SELECT o.object_id, " + TABLE_FIELDS + TABLE_FROM
        + " WHERE " + READABLE + " AND s.name = ? AND o.name = ?")) {
      st.setString(1, table.schema());
      st.setString(2, table.name());
      try (ResultSet rs = st.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        id = rs.getInt(1);
        out.put("schema", rs.getString(2)).put("name", rs.getString(3))
            .put("kind", "V".equals(rs.getString(4).strip()) ? "view" : "table");
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
    try (PreparedStatement st = c.prepareStatement("SELECT c.name, t.name, c.max_length, c.precision, c.scale, "
        + "c.is_nullable, COALESCE(dc.definition, cc.definition), CONVERT(nvarchar(4000), ep.value) "
        + "FROM sys.columns c JOIN sys.types t ON t.user_type_id = c.user_type_id "
        + "LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id "
        + "LEFT JOIN sys.computed_columns cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id "
        + "LEFT JOIN sys.extended_properties ep ON ep.class = 1 AND ep.major_id = c.object_id "
        + "AND ep.minor_id = c.column_id AND ep.name = 'MS_Description' WHERE c.object_id = ? ORDER BY c.column_id")) {
      st.setInt(1, id);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          columns.addObject().put("name", rs.getString(1))
              .put("type", columnType(rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)))
              .put("nullable", rs.getBoolean(6)).put("default", rs.getString(7)).put("comment", rs.getString(8));
        }
      }
    }
    ArrayNode primaryKey = out.putArray("primaryKey");
    ArrayNode indexes = out.putArray("indexes");
    Map<Integer, ObjectNode> indexById = new LinkedHashMap<>();
    Map<Integer, List<String>> indexColumns = new LinkedHashMap<>();
    try (PreparedStatement st = c.prepareStatement("SELECT i.index_id, i.name, i.is_unique, i.is_primary_key, "
        + "i.type_desc, c.name, ic.is_descending_key, ic.is_included_column, i.filter_definition "
        + "FROM sys.indexes i JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id "
        + "JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id "
        + "WHERE i.object_id = ? AND i.index_id > 0 ORDER BY i.index_id, ic.is_included_column, "
        + "ic.key_ordinal, ic.index_column_id")) {
      st.setInt(1, id);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          int index = rs.getInt(1);
          ObjectNode node = indexById.get(index);
          if (node == null) {
            node = indexes.addObject().put("name", rs.getString(2)).put("unique", rs.getBoolean(3))
                .put("primary", rs.getBoolean(4)).put("definition", rs.getString(5));
            if (rs.getString(9) != null) {
              node.put("definition", node.path("definition").asString() + " WHERE " + rs.getString(9));
            }
            indexById.put(index, node);
          }
          String column = rs.getString(6);
          if (rs.getBoolean(4) && !rs.getBoolean(8)) {
            primaryKey.add(column);
          }
          indexColumns.computeIfAbsent(index, unused -> new ArrayList<>()).add(quote(column)
              + (rs.getBoolean(8) ? " INCLUDED" : rs.getBoolean(7) ? " DESC" : " ASC"));
        }
      }
    }
    indexById.forEach((index, node) -> node.put("definition", node.path("definition").asString()
        + " (" + String.join(", ", indexColumns.get(index)) + ")"));
    ArrayNode foreignKeys = out.putArray("foreignKeys");
    Map<Integer, ObjectNode> foreignById = new LinkedHashMap<>();
    try (PreparedStatement st = c.prepareStatement("SELECT fk.object_id, fk.name, pc.name, rs.name, rt.name, rc.name "
        + "FROM sys.foreign_keys fk JOIN sys.foreign_key_columns fc ON fc.constraint_object_id = fk.object_id "
        + "JOIN sys.columns pc ON pc.object_id = fc.parent_object_id AND pc.column_id = fc.parent_column_id "
        + "JOIN sys.objects rt ON rt.object_id = fc.referenced_object_id "
        + "JOIN sys.schemas rs ON rs.schema_id = rt.schema_id "
        + "JOIN sys.columns rc ON rc.object_id = fc.referenced_object_id AND rc.column_id = fc.referenced_column_id "
        + "WHERE fk.parent_object_id = ? ORDER BY fk.name, fc.constraint_column_id")) {
      st.setInt(1, id);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          int key = rs.getInt(1);
          ObjectNode fk = foreignById.get(key);
          if (fk == null) {
            fk = foreignKeys.addObject().put("name", rs.getString(2)).put("refSchema", rs.getString(4))
                .put("refTable", rs.getString(5));
            fk.putArray("columns");
            fk.putArray("refColumns");
            foreignById.put(key, fk);
          }
          ((ArrayNode) fk.get("columns")).add(rs.getString(3));
          ((ArrayNode) fk.get("refColumns")).add(rs.getString(6));
        }
      }
    }
    ArrayNode referencedBy = out.putArray("referencedBy");
    try (PreparedStatement st = c.prepareStatement("SELECT s.name, o.name, fk.name FROM sys.foreign_keys fk "
        + "JOIN sys.objects o ON o.object_id = fk.parent_object_id JOIN sys.schemas s ON s.schema_id = o.schema_id "
        + "WHERE fk.referenced_object_id = ? ORDER BY s.name, o.name, fk.name")) {
      st.setInt(1, id);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          referencedBy.addObject().put("schema", rs.getString(1)).put("table", rs.getString(2))
              .put("name", rs.getString(3));
        }
      }
    }
    ArrayNode checks = out.putArray("checks");
    try (PreparedStatement st = c.prepareStatement("SELECT name, definition FROM sys.check_constraints "
        + "WHERE parent_object_id = ? ORDER BY name")) {
      st.setInt(1, id);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          checks.addObject().put("name", rs.getString(1)).put("definition", rs.getString(2));
        }
      }
    }
    return Optional.of(out);
  }

  static String columnType(String name, int length, int precision, int scale) {
    return switch (name) {
      case "varchar", "char", "varbinary", "binary" -> name + "(" + (length < 0 ? "max" : length) + ")";
      case "nvarchar", "nchar" -> name + "(" + (length < 0 ? "max" : length / 2) + ")";
      case "decimal", "numeric" -> name + "(" + precision + "," + scale + ")";
      case "datetime2", "datetimeoffset", "time" -> name + "(" + scale + ")";
      default -> name;
    };
  }

  static String quote(String name) { return "[" + name.replace("]", "]]") + "]"; }
}
