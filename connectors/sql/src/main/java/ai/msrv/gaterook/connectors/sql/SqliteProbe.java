package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * What Test connection learns about a SQLite file: there are no users, roles or TLS. What may change it is the
 * account GateRook runs as: whether that account may write the file and its folder (SQLite writes its journal there).
 */
final class SqliteProbe implements SqlProbe {

  @Override
  public Tls tls(Connection c, ServerInfo server) {
    return null;
  }

  @Override
  public Access access(Connection c, ConnectionSettings s, ServerInfo server) throws SQLException {
    int tables;
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM main.sqlite_schema "
        + "WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'")) {
      tables = rs.next() ? rs.getInt(1) : 0;
    }
    boolean writable = writable(s);
    return new Access(null, tables, writable ? tables : 0, writable ? "INSERT, UPDATE, DELETE on main.*" : null,
        writable, false, List.of(), List.of(), List.of(), false);
  }

  /** Whether this account may write the file and create the journal next to it. */
  static boolean writable(ConnectionSettings s) {
    try {
      Path file = SqliteFiles.path(s.file() == null ? null : s.file().path()).toRealPath();
      Path folder = file.getParent();
      return Files.isWritable(file) && folder != null && Files.isWritable(folder);
    } catch (ConnectorException | java.io.IOException | SecurityException e) {
      return false;
    }
  }

  @Override
  public List<String> builtInFunctions(Connection c) {
    // Every single-part function is SQLite's own (FunctionRules): there is no list to keep.
    return List.of();
  }

  @Override
  public List<String> missingSchemas(Connection c, List<String> schemas) {
    return List.of();
  }

  @Override
  public String readOnlyUserSql(ConnectionSettings s) {
    return "";
  }
}
