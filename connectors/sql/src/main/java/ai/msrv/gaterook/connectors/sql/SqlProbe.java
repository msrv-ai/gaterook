package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connectors.sql.SqlDialect.ServerInfo;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** What Test connection asks the server (report 04 §5); {@code TestProbe} turns the answers into a TestResult. */
interface SqlProbe {

  /** Whether the session is encrypted, and how. */
  record Tls(boolean enabled, String version) {}

  /**
   * What the user may really do.
   *
   * @param readableTables tables and views it may read, or -1 when unknown
   * @param writableTables tables it may change, or -1 when unknown
   * @param write          where it may write, in words ({@code INSERT, UPDATE, DELETE on public.*}), or {@code null}
   * @param schemaChanges  whether it may create objects or owns some
   * @param superuser      a superuser, {@code rds_superuser}, {@code cloudsqlsuperuser} or {@code ALL PRIVILEGES ON *.*}
   * @param dangerous      roles and privileges that reach outside the database: {@code pg_execute_server_program},
   *                       {@code FILE}, {@code SUPER}…
   * @param admin          other administrative abilities: {@code CREATEROLE}, {@code PROCESS}…
   * @param extensions     installed extensions and languages that run code outside the transaction: dblink…
   * @param replica        the server is a read replica: writes fail
   */
  record Access(String user, int readableTables, int writableTables, String write, boolean schemaChanges,
      boolean superuser, List<String> dangerous, List<String> admin, List<String> extensions, boolean replica) {}

  Tls tls(Connection c, ServerInfo server) throws SQLException;

  Access access(Connection c, ConnectionSettings s, ServerInfo server) throws SQLException;

  /** PostgreSQL: every function name in {@code pg_catalog}; MySQL: empty (a fixed list stands for them). */
  List<String> builtInFunctions(Connection c) throws SQLException;

  /** The schemas of the connection's allowlist the user can't see. */
  List<String> missingSchemas(Connection c, List<String> schemas) throws SQLException;

  /** SQL an admin runs to create a read-only user for this database; no password in it. */
  String readOnlyUserSql(ConnectionSettings s);
}
