package com.gaterook.connectors.snowflake;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The name of a Snowflake object, each part as Snowflake stores it: an unquoted part in upper case ({@code orders} is
 * {@code ORDERS}), a quoted one exactly as written inside its quotes ({@code "Orders"} is {@code Orders}).
 *
 * @param parts one to three parts ({@code TABLE}, {@code SCHEMA.TABLE}, {@code DATABASE.SCHEMA.TABLE}); four for a
 *              column
 */
record ObjectName(List<String> parts) {

  /** An unquoted Snowflake identifier. */
  static final Pattern UNQUOTED = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,254}");

  ObjectName {
    parts = List.copyOf(parts);
  }

  /**
   * One identifier as a person or an agent writes it ({@code analytics}, {@code "Mixed Case"}) as Snowflake stores it,
   * or {@code null} when it isn't one.
   */
  static String identifier(String text) {
    if (text == null) {
      return null;
    }
    String t = text.strip();
    if (UNQUOTED.matcher(t).matches()) {
      return t.toUpperCase(Locale.ROOT);
    }
    ObjectName name = parse(t);
    return name != null && name.parts().size() == 1 ? name.parts().getFirst() : null;
  }

  /** A dotted name as written ({@code orders}, {@code public.orders}, {@code "Db"."S"."T"}), or {@code null}. */
  static ObjectName parse(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    SnowflakeLexer.Scan scan = SnowflakeLexer.scan(text);
    if (!scan.readable() || scan.statements() != 1) {
      return null;
    }
    Read read = read(scan, 0);
    return read == null || read.next() != scan.tokens().size() ? null : read.name();
  }

  /** A name read from tokens and the index of the token after it. */
  record Read(ObjectName name, int next) {}

  /**
   * The name that starts at token {@code i}: names separated by dots, at most four parts. {@code null} when there is
   * none there, or when it is computed ({@code IDENTIFIER(…)}, a {@code $variable}) and so can't be read without I/O.
   */
  static Read read(SnowflakeLexer.Scan scan, int i) {
    List<String> parts = new ArrayList<>();
    int at = i;
    while (true) {
      SnowflakeLexer.Token t = scan.at(at);
      if (t == null || !t.name() || (t.type() == SnowflakeLexer.Type.WORD && t.text().startsWith("$"))) {
        return null;
      }
      if (t.word("IDENTIFIER") && scan.called(at)) {
        return null;
      }
      if (t.type() == SnowflakeLexer.Type.QUOTED_NAME && t.text().isEmpty()) {
        return null;
      }
      parts.add(t.text());
      at++;
      SnowflakeLexer.Token dot = scan.at(at);
      if (dot == null || !dot.symbol(".")) {
        break;
      }
      if (parts.size() == 4) {
        return null;
      }
      at++;
    }
    return new Read(new ObjectName(parts), at);
  }

  /**
   * The full {@code DATABASE.SCHEMA.TABLE} with the connection's database and schema for missing parts, or
   * {@code null} when a part is missing and there is no default for it.
   */
  ObjectName resolve(String database, String schema) {
    return switch (parts.size()) {
      case 1 -> database == null || schema == null ? null : new ObjectName(List.of(database, schema, parts.get(0)));
      case 2 -> database == null ? null : new ObjectName(List.of(database, parts.get(0), parts.get(1)));
      case 3 -> this;
      default -> null;
    };
  }

  /** The name without its last part: the table of a column. */
  ObjectName parent() {
    return parts.size() <= 1 ? null : new ObjectName(parts.subList(0, parts.size() - 1));
  }

  String database() {
    return parts.size() == 3 ? parts.get(0) : null;
  }

  String schema() {
    return parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
  }

  String table() {
    return parts.getLast();
  }

  /** How SQL writes it: parts that aren't plain upper-case names are quoted. */
  String display() {
    List<String> out = new ArrayList<>();
    for (String p : parts) {
      out.add(quoteIfNeeded(p));
    }
    return String.join(".", out);
  }

  /** {@code ORDERS} as it is, {@code Orders} or {@code my table} in double quotes. */
  static String quoteIfNeeded(String part) {
    return part.matches("[A-Z_][A-Z0-9_$]*") ? part : quote(part);
  }

  /** Always quoted, a quote inside doubled: safe in any SQL text GateRook writes itself. */
  static String quote(String part) {
    return "\"" + part.replace("\"", "\"\"") + "\"";
  }

  @Override
  public String toString() {
    return display();
  }
}
