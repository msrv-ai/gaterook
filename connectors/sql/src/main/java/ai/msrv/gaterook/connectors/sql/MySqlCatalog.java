package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
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

/**
 * Introspection of MySQL and MariaDB through {@code information_schema}, which shows only what the user has some
 * privilege on (report 04 §3.1, §7.4). {@code TABLE_ROWS} is the storage engine's estimate (cached for
 * {@code information_schema_stats_expiry} on MySQL).
 */
final class MySqlCatalog implements SqlCatalog {

  static final String SYSTEM_SCHEMAS = "'mysql','sys','performance_schema','information_schema'";

  @Override
  public Listing list(Connection c, String onlySchema, String pattern, Predicate<TableRef> allowed, int limit)
      throws SQLException {
    List<String> schemas = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
        + "WHERE SCHEMA_NAME NOT IN (" + SYSTEM_SCHEMAS + ") AND (? IS NULL OR SCHEMA_NAME = ?) ORDER BY 1")) {
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
    try (PreparedStatement st = c.prepareStatement("SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_ROWS, "
        + "TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA NOT IN (" + SYSTEM_SCHEMAS + ") "
        + "AND (? IS NULL OR TABLE_SCHEMA = ?) AND (? IS NULL OR TABLE_NAME LIKE ?) "
        + "ORDER BY TABLE_SCHEMA, TABLE_NAME LIMIT " + (PostgresCatalog.SCAN_LIMIT + 1))) {
      bind(st, 1, onlySchema);
      bind(st, 2, onlySchema);
      bind(st, 3, pattern);
      bind(st, 4, pattern);
      int scanned = 0;
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          if (++scanned > PostgresCatalog.SCAN_LIMIT) {
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
            boolean noRows = rs.wasNull() || kind.endsWith("view");
            tables.add(new Table(schema, name, kind, noRows ? null : rows, comment(rs.getString(5), kind)));
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
    try (PreparedStatement st = c.prepareStatement("SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_ROWS, "
        + "TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
      st.setString(1, table.schema());
      st.setString(2, table.name());
      try (ResultSet rs = st.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        String kind = kind(rs.getString(3));
        out.put("schema", rs.getString(1));
        out.put("name", rs.getString(2));
        out.put("kind", kind);
        long rows = rs.getLong(4);
        if (rs.wasNull() || kind.endsWith("view")) {
          out.putNull("estimatedRows");
        } else {
          out.put("estimatedRows", rows);
        }
        out.put("comment", comment(rs.getString(5), kind));
      }
    }
    ArrayNode columns = out.putArray("columns");
    try (PreparedStatement st = c.prepareStatement("SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, "
        + "COLUMN_COMMENT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
        + "ORDER BY ORDINAL_POSITION")) {
      st.setString(1, table.schema());
      st.setString(2, table.name());
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String extra = rs.getString(6);
          String dflt = rs.getString(4);
          if (dflt == null && extra != null && extra.toLowerCase(java.util.Locale.ROOT).contains("auto_increment")) {
            dflt = "auto_increment";
          }
          columns.addObject().put("name", rs.getString(1)).put("type", rs.getString(2))
              .put("nullable", "YES".equalsIgnoreCase(rs.getString(3))).put("default", dflt)
              .put("comment", blankToNull(rs.getString(5)));
        }
      }
    }
    ArrayNode primaryKey = out.putArray("primaryKey");
    ArrayNode indexes = out.putArray("indexes");
    try (PreparedStatement st = c.prepareStatement("SELECT INDEX_NAME, NON_UNIQUE, INDEX_TYPE, COLUMN_NAME "
        + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
        + "ORDER BY INDEX_NAME, SEQ_IN_INDEX")) {
      st.setString(1, table.schema());
      st.setString(2, table.name());
      Map<String, ObjectNode> byName = new LinkedHashMap<>();
      Map<String, List<String>> columnsOf = new LinkedHashMap<>();
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(1);
          boolean primary = "PRIMARY".equals(name);
          if (primary) {
            primaryKey.add(rs.getString(4));
          }
          byName.computeIfAbsent(name, n -> JsonNodeFactory.instance.objectNode().put("name", n))
              .put("unique", rs.getInt(2) == 0).put("primary", primary).put("type", rs.getString(3));
          columnsOf.computeIfAbsent(name, n -> new ArrayList<>()).add(IdentifierRules.quoteIfNeeded(
              String.valueOf(rs.getString(4)), true));
        }
      }
      byName.forEach((name, index) -> {
        String type = index.path("type").asString("BTREE");
        index.remove("type");
        String kind = index.path("primary").asBoolean() ? "PRIMARY KEY" : index.path("unique").asBoolean()
            ? "UNIQUE INDEX " + IdentifierRules.quoteIfNeeded(name, true)
            : "INDEX " + IdentifierRules.quoteIfNeeded(name, true);
        index.put("definition", kind + " (" + String.join(", ", columnsOf.get(name)) + ") USING " + type);
        indexes.add(index);
      });
    }
    ArrayNode foreignKeys = out.putArray("foreignKeys");
    try (PreparedStatement st = c.prepareStatement("SELECT k.CONSTRAINT_NAME, k.COLUMN_NAME, "
        + "k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME, r.UPDATE_RULE, r.DELETE_RULE "
        // MariaDB shows REFERENTIAL_CONSTRAINTS only to some users (not to one with SELECT alone): the rules are
        // optional, the key itself comes from KEY_COLUMN_USAGE.
        + "FROM information_schema.KEY_COLUMN_USAGE k LEFT JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
        + "ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
        + "AND r.TABLE_NAME = k.TABLE_NAME WHERE k.TABLE_SCHEMA = ? AND k.TABLE_NAME = ? "
        + "AND k.REFERENCED_TABLE_NAME IS NOT NULL ORDER BY k.CONSTRAINT_NAME, k.ORDINAL_POSITION")) {
      st.setString(1, table.schema());
      st.setString(2, table.name());
      Map<String, ObjectNode> byName = new LinkedHashMap<>();
      Map<String, String[]> rules = new LinkedHashMap<>();
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(1);
          ObjectNode fk = byName.computeIfAbsent(name, n -> {
            ObjectNode o = JsonNodeFactory.instance.objectNode().put("name", n);
            o.putArray("columns");
            o.putArray("refColumns");
            return o;
          });
          ((ArrayNode) fk.get("columns")).add(rs.getString(2));
          fk.put("refSchema", rs.getString(3)).put("refTable", rs.getString(4));
          ((ArrayNode) fk.get("refColumns")).add(rs.getString(5));
          rules.put(name, new String[] {rs.getString(6), rs.getString(7)});
        }
      }
      byName.forEach((name, fk) -> {
        String[] r = rules.get(name);
        fk.put("definition", "FOREIGN KEY (" + join(fk.get("columns")) + ") REFERENCES "
            + IdentifierRules.quoteIfNeeded(fk.path("refTable").asString(), true) + " (" + join(fk.get("refColumns"))
            + ")" + (r[0] == null ? "" : " ON UPDATE " + r[0]) + (r[1] == null ? "" : " ON DELETE " + r[1]));
        foreignKeys.add(fk);
      });
    }
    ArrayNode referencedBy = out.putArray("referencedBy");
    try (PreparedStatement st = c.prepareStatement("SELECT DISTINCT TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME "
        + "FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME = ? "
        + "ORDER BY 1, 2, 3")) {
      st.setString(1, table.schema());
      st.setString(2, table.name());
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          referencedBy.addObject().put("schema", rs.getString(1)).put("table", rs.getString(2))
              .put("name", rs.getString(3));
        }
      }
    }
    ArrayNode checks = out.putArray("checks");
    try (PreparedStatement st = c.prepareStatement("SELECT cc.CONSTRAINT_NAME, cc.CHECK_CLAUSE "
        + "FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc "
        + "ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME "
        + "WHERE tc.TABLE_SCHEMA = ? AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK' ORDER BY 1")) {
      st.setString(1, table.schema());
      st.setString(2, table.name());
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          checks.addObject().put("name", rs.getString(1)).put("definition", "CHECK (" + rs.getString(2) + ")");
        }
      }
    } catch (SQLException e) {
      // MySQL before 8.0.16 has no CHECK_CONSTRAINTS: no checks to show.
    }
    return Optional.of(out);
  }

  static String kind(String tableType) {
    return switch (tableType == null ? "" : tableType) {
      case "VIEW", "SYSTEM VIEW" -> "view";
      default -> "table";
    };
  }

  /** A view's comment is the word "VIEW", and an empty comment is none. */
  private static String comment(String comment, String kind) {
    if (comment == null || comment.isBlank() || (kind.endsWith("view") && "VIEW".equals(comment))) {
      return null;
    }
    return comment;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static String join(tools.jackson.databind.JsonNode names) {
    List<String> out = new ArrayList<>();
    names.forEach(n -> out.add(IdentifierRules.quoteIfNeeded(n.asString(), true)));
    return String.join(", ", out);
  }

  private static void bind(PreparedStatement st, int index, String value) throws SQLException {
    if (value == null) {
      st.setNull(index, Types.VARCHAR);
    } else {
      st.setString(index, value);
    }
  }
}
