package com.gaterook.connectors.snowflake;

import com.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tables agents may use ({@code tables} of the connection): {@code DATABASE.SCHEMA.TABLE} globs in which
 * {@code *} matches any characters, such as {@code ANALYTICS.PUBLIC.*}. Case doesn't matter: Snowflake stores an
 * unquoted name in upper case, and a person writes {@code analytics.public.orders} as often as the stored name. An
 * empty list restricts nothing: the role's grants decide.
 */
final class TableList {

  private record Entry(String pattern, Glob database, Glob schema, Glob table, String[] parts) {}

  private final List<Entry> entries;

  private TableList(List<Entry> entries) {
    this.entries = List.copyOf(entries);
  }

  /** @throws IllegalArgumentException for an entry that isn't {@code DATABASE.SCHEMA.TABLE} */
  static TableList of(List<String> patterns) {
    List<Entry> out = new ArrayList<>();
    for (String p : patterns) {
      String[] parts = p.strip().toUpperCase(Locale.ROOT).split("\\.", -1);
      if (parts.length != 3) {
        throw new IllegalArgumentException(p + " isn't DATABASE.SCHEMA.TABLE");
      }
      for (String part : parts) {
        if (!part.matches("[A-Z0-9_$*]{1,255}")) {
          throw new IllegalArgumentException(p + " isn't DATABASE.SCHEMA.TABLE");
        }
      }
      out.add(new Entry(p, Glob.text(parts[0]), Glob.text(parts[1]), Glob.text(parts[2]), parts));
    }
    return new TableList(out);
  }

  /** Whether the list restricts anything. */
  boolean restricted() {
    return !entries.isEmpty();
  }

  List<String> patterns() {
    return entries.stream().map(Entry::pattern).toList();
  }

  /** Whether agents may use the table {@code DATABASE.SCHEMA.TABLE}; any table when the list is empty. */
  boolean allows(ObjectName full) {
    if (!restricted()) {
      return true;
    }
    if (full == null || full.parts().size() != 3) {
      return false;
    }
    return allows(full.parts().get(0), full.parts().get(1), full.parts().get(2));
  }

  boolean allows(String database, String schema, String table) {
    if (!restricted()) {
      return true;
    }
    if (database == null || schema == null || table == null) {
      return false;
    }
    String d = upper(database);
    String s = upper(schema);
    String t = upper(table);
    for (Entry e : entries) {
      if (e.database().matches(d) && e.schema().matches(s) && e.table().matches(t)) {
        return true;
      }
    }
    return false;
  }

  /** Whether some table of {@code database} may be on the list; a database not known yet may hold one. */
  boolean mayAllowDatabase(String database) {
    if (!restricted() || database == null) {
      return true;
    }
    String d = upper(database);
    return entries.stream().anyMatch(e -> e.database().matches(d));
  }

  /** Whether some table of {@code database.schema} may be on the list ({@code database} {@code null}: any). */
  boolean mayAllowSchema(String database, String schema) {
    if (!restricted()) {
      return true;
    }
    if (schema == null) {
      return false;
    }
    String d = database == null ? null : upper(database);
    String s = upper(schema);
    return entries.stream().anyMatch(e -> (d == null || e.database().matches(d)) && e.schema().matches(s));
  }

  /**
   * {@code [schema, table]} ILIKE patterns (escape character {@code !}) of the entries that may hold tables of
   * {@code database} (of every entry when it is {@code null}): a filter for INFORMATION_SCHEMA that keeps at least
   * the tables the list allows. The list itself still decides about every row.
   */
  List<String[]> likePatterns(String database) {
    List<String[]> out = new ArrayList<>();
    String d = database == null ? null : upper(database);
    for (Entry e : entries) {
      if (d == null || e.database().matches(d)) {
        out.add(new String[] {like(e.parts()[1]), like(e.parts()[2])});
      }
    }
    return out;
  }

  /** A glob part as an ILIKE pattern: {@code *} is {@code %}, and {@code _}, {@code %} and {@code !} are literal. */
  static String like(String glob) {
    StringBuilder out = new StringBuilder();
    for (char c : glob.toCharArray()) {
      switch (c) {
        case '*' -> out.append('%');
        case '_', '%', '!' -> out.append('!').append(c);
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  private static String upper(String name) {
    return name.toUpperCase(Locale.ROOT);
  }
}
