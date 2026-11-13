package com.gaterook.connectors.sql;

import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.ExecContext;
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
public sealed interface SqlDialect permits PostgresDialect, MySqlDialect, OracleDialect, SqlServerDialect,
    SqliteDialect {

  /** Connector type: {@code postgres}, {@code mysql}, {@code oracle}, {@code sqlserver} or {@code sqlite}. */
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

  /**
   * The properties of a connection for reads ({@code write} false) or approved changes. A database file opens
   * read-only for reads ({@link #separateWriteConnections}); a server's connection is the same for both.
   */
  default Properties properties(ConnectionSettings s, Path certificates, int socketTimeoutMs, boolean write)
      throws IOException {
    return properties(s, certificates, socketTimeoutMs);
  }

  /** Whether changes need connections of their own: SQLite reads through a read-only handle of the file. */
  default boolean separateWriteConnections() {
    return false;
  }

  /**
   * The settings with Auto's transport chosen, asked of the server before any credential is sent: PostgreSQL's
   * {@code auto} becomes {@code verify-full} or {@code disable} ({@link PostgresTls}). Every other mode, and every other
   * dialect, keeps its settings: MySQL's {@code auto} is the driver's own PREFERRED.
   *
   * @param timeoutMs how long the question may take, at most the connect timeout of {@code s}
   */
  default ConnectionSettings resolveTransport(ConnectionSettings s, int timeoutMs) throws SQLException {
    return s;
  }

  /**
   * The driver properties of a pool's connections, except the login ({@link SqlPools}): {@link #properties} as they
   * are, or for Auto the properties of the transport the server chose, asked at the pool's first connection and kept
   * for every later one. A CA certificate that can't be used fails here, before any connection.
   */
  default CredentialAwareDataSource.Transport poolTransport(ConnectionSettings s, Path certificates,
      int socketTimeoutMs, boolean write) throws IOException {
    return CredentialAwareDataSource.Transport.fixed(properties(s, certificates, socketTimeoutMs, write));
  }

  /**
   * Checks where the connection points before anything connects there: SQLite's file must be a database file outside
   * GateRook's own folder. {@code ctx} gives the connector's work folder; a server dialect has nothing to check.
   */
  default void checkTarget(ConnectionSettings s, ExecContext ctx) throws ConnectorException {}

  /** After the call's work, before its COMMIT or ROLLBACK: SQLite stops counting down the statement deadline. */
  default void afterWork(Connection c) {}

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

    /** CockroachDB through the PostgreSQL dialect: its own version, PostgreSQL's protocol and catalog. */
    boolean cockroach() {
      return "CockroachDB".equals(product);
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
