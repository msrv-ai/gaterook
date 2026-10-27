package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.util.List;

/** Exact database names selected for a server connection; legacy connections keep their one default database. */
final class DatabaseSelection {
  private DatabaseSelection() {}

  static boolean configured(ConnectionConfig cfg) {
    return cfg.config().at("/connection/databases").isArray();
  }

  static List<String> selected(ConnectionConfig cfg) {
    return List.copyOf(cfg.strings("/connection/databases"));
  }

  static String resolve(ConnectionConfig cfg, String requested) throws ConnectorException {
    if (requested != null && requested.isBlank()) requested = null;
    if (configured(cfg)) {
      List<String> selected = selected(cfg);
      if (selected.isEmpty()) throw ConnectorException.badInput("Select at least one database for " + cfg.name() + ".");
      if (requested == null) {
        if (selected.size() == 1) return selected.getFirst();
        throw ConnectorException.badInput("Choose a database for this call: " + String.join(", ", selected) + ".");
      }
      if (!selected.contains(requested)) {
        throw ConnectorException.denied("Database " + requested + " is not selected for " + cfg.name() + ".");
      }
      return requested;
    }
    String legacy = cfg.string("/connection/database");
    if (requested != null && !requested.equals(legacy)) {
      throw ConnectorException.denied("Database " + requested + " is not configured for " + cfg.name() + ".");
    }
    return legacy;
  }

  /** A database for non-tool operations: every selected database is checked by Test connection. */
  static String first(ConnectionConfig cfg) {
    List<String> selected = selected(cfg);
    return configured(cfg) && !selected.isEmpty() ? selected.getFirst() : cfg.string("/connection/database");
  }
}
