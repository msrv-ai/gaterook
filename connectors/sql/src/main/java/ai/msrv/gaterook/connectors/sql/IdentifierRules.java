package ai.msrv.gaterook.connectors.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Names of tables and functions as the server resolves them (report 04 §6.3).
 *
 * <p>PostgreSQL folds an unquoted name to lower case and keeps a quoted one ({@code "Orders"} is not {@code orders});
 * Oracle folds an unquoted name to upper case and keeps quoted names. MySQL keeps the name as written, and the comparison is exact: with {@code lower_case_table_names=0} (Linux) that is
 * the server's rule, elsewhere exactness errs towards refusing. A name without a schema is in the connection's
 * default schema (PostgreSQL: {@code defaultSchema}, set as {@code search_path} in every prelude; MySQL: the
 * connection's database; Oracle: the current schema set in every prelude).
 */
final class IdentifierRules {

  /** One part of a dotted name and whether it was quoted. */
  record Part(String text, boolean quoted) {}

  /** A table as the server resolves it; {@code schema} is {@code null} for a MySQL connection without a database. */
  record TableRef(String schema, String name) {

    String qualified() {
      return schema == null ? name : schema + "." + name;
    }
  }

  private IdentifierRules() {}

  /**
   * Splits {@code orders}, {@code billing.invoices}, {@code "Mixed"."Case"} or {@code `db`.`t`} into its parts; a
   * dot inside quotes belongs to the name. Empty for a malformed name (an unclosed quote, an empty part).
   */
  static List<Part> parts(String text, boolean mysql) {
    return parts(text, SqlSyntax.of(mysql));
  }

  static List<Part> parts(String text, SqlDialect dialect) {
    return parts(text, dialect.syntax());
  }

  static List<Part> parts(String text, SqlSyntax syntax) {
    boolean mysql = syntax == SqlSyntax.MYSQL;
    List<Part> out = new ArrayList<>();
    if (text == null) {
      return List.of();
    }
    String s = text.strip();
    char quote = mysql ? '`' : '"';
    int i = 0;
    while (true) {
      if (i >= s.length()) {
        // A trailing dot, or nothing at all: a part is missing.
        return List.of();
      }
      if (s.charAt(i) == quote || (mysql && s.charAt(i) == '"')
          || (syntax == SqlSyntax.SQLSERVER && s.charAt(i) == '[')) {
        char q = s.charAt(i) == '[' ? ']' : s.charAt(i);
        StringBuilder name = new StringBuilder();
        int j = i + 1;
        boolean closed = false;
        while (j < s.length()) {
          if (s.charAt(j) == q) {
            if (j + 1 < s.length() && s.charAt(j + 1) == q) {
              name.append(q);
              j += 2;
              continue;
            }
            closed = true;
            j++;
            break;
          }
          name.append(s.charAt(j++));
        }
        if (!closed || name.isEmpty()) {
          return List.of();
        }
        out.add(new Part(name.toString(), true));
        i = j;
      } else {
        int j = i;
        while (j < s.length() && s.charAt(j) != '.') {
          j++;
        }
        String name = s.substring(i, j).strip();
        if ((syntax == SqlSyntax.ORACLE && !name.matches("[\\p{L}*][\\p{L}\\p{N}_$#*]*"))
            || name.isEmpty() || name.indexOf(quote) >= 0
            || (syntax == SqlSyntax.SQLSERVER && (name.indexOf('[') >= 0 || name.indexOf(']') >= 0))
            || name.chars().anyMatch(Character::isWhitespace)) {
          return List.of();
        }
        out.add(new Part(name, false));
        i = j;
      }
      if (i == s.length()) {
        return List.copyOf(out);
      }
      if (s.charAt(i) != '.') {
        return List.of();
      }
      i++;
    }
  }

  /** A part as the server spells it. */
  static String normalize(Part part, boolean mysql) {
    return normalize(part, SqlSyntax.of(mysql));
  }

  static String normalize(Part part, SqlDialect dialect) {
    return normalize(part, dialect.syntax());
  }

  static String normalize(Part part, SqlSyntax syntax) {
    boolean mysql = syntax == SqlSyntax.MYSQL;
    if (part.quoted() || mysql || syntax == SqlSyntax.SQLSERVER) {
      return part.text();
    }
    return syntax == SqlSyntax.ORACLE ? part.text().toUpperCase(Locale.ROOT) : part.text().toLowerCase(Locale.ROOT);
  }

  /**
   * A table name of the agent or of the parser's table list, resolved: one part is in {@code defaultSchema}, two are
   * {@code schema.table}; PostgreSQL also takes {@code database.schema.table} (the server refuses another database).
   * {@code null} for a name that isn't one.
   *
   * <p>An unqualified PostgreSQL name starting with {@code pg_} counts as {@code pg_catalog}: the server searches the
   * catalog before {@code search_path}, so {@code pg_stat_activity} is the system view, not a table of the default
   * schema.
   */
  static TableRef table(String text, boolean mysql, String defaultSchema) {
    return table(text, SqlSyntax.of(mysql), defaultSchema);
  }

  static TableRef table(String text, SqlDialect dialect, String defaultSchema) {
    return table(text, dialect.syntax(), defaultSchema);
  }

  static TableRef table(String text, SqlSyntax syntax, String defaultSchema) {
    List<Part> p = parts(text, syntax);
    if (p.isEmpty() || p.size() > (syntax == SqlSyntax.POSTGRES ? 3 : 2)) {
      return null;
    }
    String name = normalize(p.getLast(), syntax);
    if (syntax == SqlSyntax.SQLSERVER && (name.startsWith("#") || name.startsWith("@"))) {
      return null;
    }
    if (p.size() == 1) {
      if (syntax == SqlSyntax.POSTGRES && !p.getFirst().quoted() && name.startsWith("pg_")) {
        return new TableRef("pg_catalog", name);
      }
      return new TableRef(defaultSchema, name);
    }
    return new TableRef(normalize(p.get(p.size() - 2), syntax), name);
  }

  /** {@code "Mixed"."Case"} for display and for SQL the core shows: quoted only where the server needs it. */
  static String display(TableRef t, boolean mysql) {
    return display(t, SqlSyntax.of(mysql));
  }

  static String display(TableRef t, SqlDialect dialect) {
    return display(t, dialect.syntax());
  }

  static String display(TableRef t, SqlSyntax syntax) {
    return t.schema() == null ? quoteIfNeeded(t.name(), syntax)
        : quoteIfNeeded(t.schema(), syntax) + "." + quoteIfNeeded(t.name(), syntax);
  }

  /** Quotes names that require it: lower case for PostgreSQL, upper case for Oracle, either for MySQL. */
  static String quoteIfNeeded(String name, boolean mysql) {
    return quoteIfNeeded(name, SqlSyntax.of(mysql));
  }

  static String quoteIfNeeded(String name, SqlDialect dialect) {
    return quoteIfNeeded(name, dialect.syntax());
  }

  static String quoteIfNeeded(String name, SqlSyntax syntax) {
    if (syntax == SqlSyntax.SQLSERVER) {
      return quote(name, syntax);
    }
    boolean mysql = syntax == SqlSyntax.MYSQL;
    boolean plain = !name.isEmpty() && (Character.isLetter(name.charAt(0)) || name.charAt(0) == '_');
    for (int i = 0; plain && i < name.length(); i++) {
      char c = name.charAt(i);
      plain = (mysql ? Character.isLetterOrDigit(c)
          : ((syntax == SqlSyntax.ORACLE ? Character.isUpperCase(c) : Character.isLowerCase(c)) || Character.isDigit(c)))
          || c == '_' || (mysql && c == '$') || (syntax == SqlSyntax.ORACLE && (c == '$' || c == '#'));
    }
    return plain ? name : quote(name, syntax);
  }

  /** Always quoted, the quote character doubled inside. */
  static String quote(String name, boolean mysql) {
    return quote(name, SqlSyntax.of(mysql));
  }

  static String quote(String name, SqlDialect dialect) {
    return quote(name, dialect.syntax());
  }

  static String quote(String name, SqlSyntax syntax) {
    if (syntax == SqlSyntax.SQLSERVER) {
      return "[" + name.replace("]", "]]") + "]";
    }
    boolean mysql = syntax == SqlSyntax.MYSQL;
    char q = mysql ? '`' : '"';
    return q + name.replace(String.valueOf(q), String.valueOf(q) + q) + q;
  }
}
