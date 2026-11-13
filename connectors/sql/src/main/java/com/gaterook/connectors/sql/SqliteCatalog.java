package com.gaterook.connectors.sql;

import com.gaterook.connectors.sql.IdentifierRules.TableRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tables and views of a SQLite file's {@code main} schema from {@code sqlite_schema} and the pragma functions, with
 * names bound. SQLite's own tables ({@code sqlite_*}) are left out; a row count is the estimate {@code ANALYZE} left
 * in {@code sqlite_stat1}, when there is one.
 */
final class SqliteCatalog implements SqlCatalog {

  static final int SCAN_LIMIT = 20_000;
  /** The CREATE statement of a table, as describe returns it. */
  static final int MAX_DEFINITION_CHARS = 8_000;
  private static final String OBJECTS = "SELECT name, type, sql FROM main.sqlite_schema "
      + "WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'";

  @Override
  public Listing list(Connection c, String onlySchema, String pattern, Predicate<TableRef> allowed, int limit)
      throws SQLException {
    if (onlySchema != null && !"main".equals(onlySchema)) {
      return new Listing(List.of(), List.of(), Map.of(), false);
    }
    Map<String, Long> rows = estimates(c);
    List<SqlCatalog.Table> tables = new ArrayList<>();
    Map<String, int[]> counts = new LinkedHashMap<>();
    counts.put("main", new int[2]);
    boolean truncated = false;
    try (PreparedStatement st = c.prepareStatement(OBJECTS + " AND (? IS NULL OR name LIKE ?) ORDER BY name LIMIT "
        + (SCAN_LIMIT + 1))) {
      st.setString(1, pattern);
      st.setString(2, pattern);
      int scanned = 0;
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          if (++scanned > SCAN_LIMIT) {
            truncated = true;
            break;
          }
          String name = rs.getString(1);
          if (!allowed.test(new TableRef("main", IdentifierRules.asciiLower(name)))) {
            continue;
          }
          String kind = kind(rs.getString(2), rs.getString(3));
          counts.get("main")["view".equals(kind) ? 1 : 0]++;
          if (tables.size() < limit) {
            tables.add(new SqlCatalog.Table("main", name, kind, rows.get(IdentifierRules.asciiLower(name)), null));
          } else {
            truncated = true;
          }
        }
      }
    }
    return new Listing(List.of("main"), List.copyOf(tables), counts, truncated);
  }

  @Override
  public Optional<ObjectNode> describe(Connection c, TableRef table) throws SQLException {
    if (!"main".equals(table.schema())) {
      return Optional.empty();
    }
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    String name;
    String sql;
    try (PreparedStatement st = c.prepareStatement(OBJECTS + " AND name = ? COLLATE NOCASE")) {
      st.setString(1, table.name());
      try (ResultSet rs = st.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        name = rs.getString(1);
        sql = rs.getString(3);
        out.put("schema", "main").put("name", name).put("kind", kind(rs.getString(2), sql));
      }
    }
    Long rows = estimates(c).get(IdentifierRules.asciiLower(name));
    if (rows == null) {
      out.putNull("estimatedRows");
    } else {
      out.put("estimatedRows", rows);
    }
    out.putNull("comment");
    ArrayNode columns = out.putArray("columns");
    Map<Integer, String> key = new TreeMap<>();
    try (PreparedStatement st = c.prepareStatement("SELECT name, type, \"notnull\", dflt_value, pk, hidden "
        + "FROM pragma_table_xinfo(?, 'main') ORDER BY cid")) {
      st.setString(1, name);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          if (rs.getInt(6) == 1) {
            continue; // a virtual table's hidden column
          }
          String column = rs.getString(1);
          ObjectNode node = columns.addObject().put("name", column)
              .put("type", rs.getString(2) == null || rs.getString(2).isEmpty() ? "ANY" : rs.getString(2))
              .put("nullable", rs.getInt(3) == 0).put("default", rs.getString(4));
          node.putNull("comment");
          if (rs.getInt(6) >= 2) {
            node.put("generated", rs.getInt(6) == 2 ? "virtual" : "stored");
          }
          if (rs.getInt(5) > 0) {
            key.put(rs.getInt(5), column);
          }
        }
      }
    }
    ArrayNode primaryKey = out.putArray("primaryKey");
    key.values().forEach(primaryKey::add);
    out.set("indexes", indexes(c, name));
    out.set("foreignKeys", foreignKeys(c, name));
    out.set("referencedBy", referencedBy(c, name));
    out.putArray("checks");
    if (sql != null) {
      out.put("definition", sql.length() <= MAX_DEFINITION_CHARS ? sql : sql.substring(0, MAX_DEFINITION_CHARS)
          + "…");
    }
    return Optional.of(out);
  }

  /** {@code table}, {@code view} or {@code virtual table}. */
  private static String kind(String type, String sql) {
    if ("view".equals(type)) {
      return "view";
    }
    return sql != null && sql.stripLeading().regionMatches(true, 0, "CREATE VIRTUAL TABLE", 0, 20)
        ? "virtual table" : "table";
  }

  /** Row estimates by lower-case table name, from {@code sqlite_stat1}; empty without ANALYZE. */
  private static Map<String, Long> estimates(Connection c) throws SQLException {
    boolean analyzed;
    try (PreparedStatement st = c.prepareStatement("SELECT 1 FROM main.sqlite_schema WHERE type = 'table' "
        + "AND name = 'sqlite_stat1'"); ResultSet rs = st.executeQuery()) {
      analyzed = rs.next();
    }
    if (!analyzed) {
      return Map.of();
    }
    Map<String, Long> out = new HashMap<>();
    try (PreparedStatement st = c.prepareStatement("SELECT tbl, stat FROM main.sqlite_stat1");
         ResultSet rs = st.executeQuery()) {
      while (rs.next()) {
        String stat = rs.getString(2);
        if (rs.getString(1) == null || stat == null) {
          continue;
        }
        int space = stat.indexOf(' ');
        try {
          long n = Long.parseLong(space < 0 ? stat.strip() : stat.substring(0, space));
          out.merge(IdentifierRules.asciiLower(rs.getString(1)), n, Math::max);
        } catch (NumberFormatException ignored) {
          // Not the row count ANALYZE writes.
        }
      }
    }
    return out;
  }

  private static ArrayNode indexes(Connection c, String table) throws SQLException {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    List<String[]> found = new ArrayList<>();
    try (PreparedStatement st = c.prepareStatement("SELECT name, \"unique\", origin, partial "
        + "FROM pragma_index_list(?, 'main') ORDER BY seq")) {
      st.setString(1, table);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          found.add(new String[] {rs.getString(1), Integer.toString(rs.getInt(2)), rs.getString(3)});
        }
      }
    }
    for (String[] index : found) {
      List<String> columns = new ArrayList<>();
      try (PreparedStatement st = c.prepareStatement("SELECT name, \"desc\" FROM pragma_index_xinfo(?, 'main') "
          + "WHERE key = 1 ORDER BY seqno")) {
        st.setString(1, index[0]);
        try (ResultSet rs = st.executeQuery()) {
          while (rs.next()) {
            String column = rs.getString(1);
            columns.add((column == null ? "<expression>" : IdentifierRules.quoteIfNeeded(column, SqlSyntax.SQLITE))
                + (rs.getInt(2) == 1 ? " DESC" : ""));
          }
        }
      }
      String definition = indexSql(c, index[0]);
      boolean primary = "pk".equals(index[2]);
      if (definition == null) {
        definition = (primary ? "PRIMARY KEY" : "1".equals(index[1]) ? "UNIQUE" : "INDEX") + " ("
            + String.join(", ", columns) + ")";
      }
      out.addObject().put("name", index[0]).put("unique", "1".equals(index[1])).put("primary", primary)
          .put("definition", definition);
    }
    return out;
  }

  /** The CREATE INDEX statement of an index made by one, {@code null} for those a constraint made. */
  private static String indexSql(Connection c, String index) throws SQLException {
    try (PreparedStatement st = c.prepareStatement("SELECT sql FROM main.sqlite_schema WHERE type = 'index' "
        + "AND name = ?")) {
      st.setString(1, index);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private static ArrayNode foreignKeys(Connection c, String table) throws SQLException {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    Map<Integer, ObjectNode> byId = new LinkedHashMap<>();
    try (PreparedStatement st = c.prepareStatement("SELECT id, \"table\", \"from\", \"to\", on_update, on_delete "
        + "FROM pragma_foreign_key_list(?, 'main') ORDER BY id, seq")) {
      st.setString(1, table);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          ObjectNode fk = byId.get(rs.getInt(1));
          if (fk == null) {
            fk = out.addObject().put("refSchema", "main").put("refTable", rs.getString(2));
            fk.putArray("columns");
            fk.putArray("refColumns");
            List<String> actions = new ArrayList<>();
            if (!"NO ACTION".equalsIgnoreCase(rs.getString(6))) {
              actions.add("ON DELETE " + rs.getString(6).toUpperCase(Locale.ROOT));
            }
            if (!"NO ACTION".equalsIgnoreCase(rs.getString(5))) {
              actions.add("ON UPDATE " + rs.getString(5).toUpperCase(Locale.ROOT));
            }
            if (!actions.isEmpty()) {
              fk.put("definition", String.join(" ", actions));
            }
            byId.put(rs.getInt(1), fk);
          }
          ((ArrayNode) fk.get("columns")).add(rs.getString(3));
          // "to" is NULL when the key refers to the parent's primary key without naming it.
          if (rs.getString(4) != null) {
            ((ArrayNode) fk.get("refColumns")).add(rs.getString(4));
          }
        }
      }
    }
    return out;
  }

  private static ArrayNode referencedBy(Connection c, String table) throws SQLException {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    try (PreparedStatement st = c.prepareStatement("SELECT DISTINCT m.name FROM main.sqlite_schema m, "
        + "pragma_foreign_key_list(m.name, 'main') f WHERE m.type = 'table' AND m.name NOT LIKE 'sqlite\\_%' "
        + "ESCAPE '\\' AND f.\"table\" = ? COLLATE NOCASE ORDER BY m.name LIMIT 1000")) {
      st.setString(1, table);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          out.addObject().put("schema", "main").put("table", rs.getString(1));
        }
      }
    }
    return out;
  }
}
