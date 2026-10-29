package ai.msrv.gaterook.connectors.sql;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * What differs between the SQL servers behind {@link JdbcSqlConnector} (report 04 §7.1): the connector type and its
 * catalog card, how to connect (a JDBC URL from checked fields and a fixed list of properties, never free-form
 * parameters: report 04 §6.5), the settings of every call, plan estimates, introspection, the probes of Test
 * connection and password changes.
 */
public sealed interface SqlDialect permits PostgresDialect, MySqlDialect, OracleDialect, SqlServerDialect {

  /** Connector type: {@code postgres}, {@code mysql}, {@code oracle}, or {@code sqlserver}. */
  String type();

  /** Catalog name, e.g. "PostgreSQL". */
  String displayName();

  /** Catalog description in the kit's tone. */
  String description();

  /** Name of the connection form under {@code connectors/sql/}, without {@code .schema.json}. */
  String configSchemaName();

  /** Whether MySQL-specific driver behavior applies; use {@link #syntax()} for parsing and identifiers. */
  boolean mysql();

  /** Identifier, literal and policy rules for this database. */
  default SqlSyntax syntax() {
    return sqlServer() ? SqlSyntax.SQLSERVER : mysql() ? SqlSyntax.MYSQL : SqlSyntax.POSTGRES;
  }

  /** Microsoft SQL Server uses T-SQL and has no read-only transaction mode. */
  default boolean sqlServer() {
    return "sqlserver".equals(type());
  }

  /** A minimal query supported by the server. */
  default String pingSql() {
    return "SELECT 1";
  }

  /** The driver itself: no {@code DriverManager} scan, no driver chosen by URL. */
  Driver driver();

  /** A driver URL built from fields the form validated, without arbitrary JDBC properties. */
  String url(ConnectionSettings s);

  /**
   * The driver properties of a connection, except user and password: a fixed list. A private CA is written below
   * {@code certificates} (0600) and named here.
   *
   * @param socketTimeoutMs the read timeout of the connection's socket; statements stop earlier at their own
   *                        timeouts
   */
  Properties properties(ConnectionSettings s, Path certificates, int socketTimeoutMs) throws IOException;

  /** Product and version of the server behind an open connection. */
  ServerInfo identify(Connection c) throws SQLException;

  /**
   * The settings of one call, the first statement of its transaction: server-side statement and lock timeouts, how
   * unqualified names and literals are read. PostgreSQL, MySQL and Oracle enforce READ ONLY for reads. SQL Server has
   * no read-only transaction mode and requires strict T-SQL admission checks before this method is called.
   */
  void prelude(Connection c, ServerInfo server, ConnectionSettings s, long timeoutMs, boolean readOnly)
      throws SQLException;

  /**
   * Estimated rows of a DML statement from its plan, without running it (EXPLAIN without ANALYZE); empty when the plan
   * doesn't say. A template's statement is explained with its values bound.
   */
  OptionalLong estimateRows(Connection c, ServerInfo server, SqlExecutor.BoundSql statement) throws SQLException;

  /** Whether {@link #estimateRows} needs a read-write transaction (MySQL refuses EXPLAIN UPDATE in READ ONLY). */
  boolean explainNeedsWrite();

  /** Schemas, tables and their structure. */
  SqlCatalog catalog();

  /** What Test connection asks the server. */
  SqlProbe probe();

  /** Sets the password of the connection's own user: Rotate at the source. */
  void changePassword(Connection c, ServerInfo server, String user, char[] next) throws SQLException;

  /** Product and version of a server: {@code PostgreSQL 16.15}, {@code MySQL 8.4.10}, {@code MariaDB 11.4.5}. */
  record ServerInfo(String product, String version, int major, int minor) {

    boolean mariaDb() {
      return "MariaDB".equals(product);
    }

    /** {@code PostgreSQL 16.15}. */
    String full() {
      return product + " " + version;
    }

    /** {@code PostgreSQL 16}, {@code MySQL 8.4}: the label in tool descriptions. */
    String label() {
      return product + " " + ("PostgreSQL".equals(product) ? Integer.toString(major) : major + "." + minor);
    }

    static ServerInfo parse(String product, String version) {
      String v = version == null ? "" : version.strip();
      int end = 0;
      while (end < v.length() && (Character.isDigit(v.charAt(end)) || v.charAt(end) == '.')) {
        end++;
      }
      String numbers = v.substring(0, end);
      String[] parts = numbers.split("\\.");
      int major = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : 0;
      int minor = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 0;
      return new ServerInfo(product, numbers.isEmpty() ? v : numbers, major, minor);
    }
  }
}
