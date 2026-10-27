package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.IdentifierRules.Part;
import ai.msrv.gaterook.connectors.sql.IdentifierRules.TableRef;
import ai.msrv.gaterook.connectors.support.Glob;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The tables agents may use (report 04 §6.3): the policy's {@code allowlist} ({@code schema.table}, {@code schema.*};
 * a single name is a schema) narrowed by the connection's {@code schemaAllowlist} (schema names). Empty lists restrict
 * nothing. Names are compared as the server resolves them ({@link IdentifierRules}); {@code *} in an entry matches any
 * run of characters.
 *
 * <p>For MySQL, the selected databases narrow these lists further using exact catalog names, never globs.
 * PostgreSQL databases are selected by the physical connection and its schema allowlist stays independent.
 *
 * <p>System catalogs ({@code pg_catalog}, {@code information_schema}; MySQL's {@code mysql}, {@code sys},
 * {@code performance_schema}) are closed while a list restricts anything, unless an entry names the catalog itself:
 * a {@code *} never opens them. The gateway's own introspection reads them with its own SQL, past this list.
 *
 * <p>This is convenience and defence in depth, not the boundary: a view on the list shows its base tables. The
 * boundary is the database user's {@code GRANT}s.
 */
final class Allowlist {

  private static final Set<String> POSTGRES_SYSTEM = Set.of("pg_catalog", "information_schema", "pg_toast");
  private static final Set<String> MYSQL_SYSTEM = Set.of("mysql", "sys", "performance_schema", "information_schema");

  private record Entry(String schema, String table) {}

  private final boolean mysql;
  private final List<Entry> tables;
  private final List<Entry> schemas;
  private final Set<String> databases;

  private Allowlist(boolean mysql, List<Entry> tables, List<Entry> schemas, List<String> databases) {
    this.mysql = mysql;
    this.tables = tables;
    this.schemas = schemas;
    this.databases = Set.copyOf(databases);
  }

  static Allowlist of(List<String> policy, List<String> connectionSchemas, boolean mysql) {
    return of(policy, connectionSchemas, mysql, List.of());
  }

  static Allowlist of(List<String> policy, List<String> connectionSchemas, boolean mysql, List<String> databases) {
    List<Entry> tables = new ArrayList<>();
    for (String raw : policy) {
      List<Part> parts = IdentifierRules.parts(raw, mysql);
      if (parts.size() == 1) {
        tables.add(new Entry(IdentifierRules.normalize(parts.getFirst(), mysql), "*"));
      } else if (parts.size() == 2) {
        tables.add(new Entry(IdentifierRules.normalize(parts.get(0), mysql),
            IdentifierRules.normalize(parts.get(1), mysql)));
      } else {
        // Not a name (or the merge's "nothing in common" marker): it matches nothing, and still restricts.
        tables.add(new Entry("\u0000", "\u0000"));
      }
    }
    List<Entry> schemas = new ArrayList<>();
    for (String raw : connectionSchemas) {
      List<Part> parts = IdentifierRules.parts(raw, mysql);
      String schema = parts.isEmpty() ? "\u0000" : IdentifierRules.normalize(parts.getFirst(), mysql);
      schemas.add(new Entry(schema, "*"));
    }
    return new Allowlist(mysql, List.copyOf(tables), List.copyOf(schemas), mysql ? databases : List.of());
  }

  /** Whether anything is restricted: then statements whose tables the gateway can't name are refused. */
  boolean restricted() {
    return !tables.isEmpty() || !schemas.isEmpty() || !databases.isEmpty();
  }

  boolean allows(TableRef t) {
    if (t == null) {
      return !restricted();
    }
    if (mysql && t.schema() == null && "dual".equalsIgnoreCase(t.name())) {
      return true;
    }
    if (!restricted()) {
      return true;
    }
    if (t.schema() == null) {
      return false;
    }
    return (databases.isEmpty() || databases.contains(t.schema())) && matches(tables, t) && matches(schemas, t);
  }

  /** Whether some table of {@code schema} may be allowed: for listing schemas. */
  boolean mayAllowSchema(String schema) {
    if (!restricted()) {
      return true;
    }
    return (databases.isEmpty() || databases.contains(schema)) && matchesSchema(tables, schema)
        && matchesSchema(schemas, schema);
  }

  private boolean matches(List<Entry> entries, TableRef t) {
    if (entries.isEmpty()) {
      return true;
    }
    for (Entry e : entries) {
      if (schemaMatches(e.schema(), t.schema()) && Glob.text(e.table()).matches(t.name())) {
        return true;
      }
    }
    return false;
  }

  private boolean matchesSchema(List<Entry> entries, String schema) {
    if (entries.isEmpty()) {
      return true;
    }
    for (Entry e : entries) {
      if (schemaMatches(e.schema(), schema)) {
        return true;
      }
    }
    return false;
  }

  private boolean schemaMatches(String entry, String schema) {
    if (schema == null) {
      return false;
    }
    if ((mysql ? MYSQL_SYSTEM : POSTGRES_SYSTEM).contains(mysql ? schema.toLowerCase(java.util.Locale.ROOT) : schema)) {
      return entry.equals(schema);
    }
    return Glob.text(entry).matches(schema);
  }
}
