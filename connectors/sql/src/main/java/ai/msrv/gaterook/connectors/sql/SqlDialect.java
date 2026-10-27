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
public sealed interface SqlDialect permits PostgresDialect, MySqlDialect {

  /** Connector type: {@code postgres} or {@code mysql}. */
  String type();

  /** Catalog name, e.g. "PostgreSQL". */
  String displayName();

  /** Catalog description in the kit's tone. */
  String description();

  /** Name of the connection form under {@code connectors/sql/}, without {@code .schema.json}. */
  String configSchemaName();

  /** MySQL's lexer and parser rules (backslash escapes, backticks); PostgreSQL's otherwise. */
  boolean mysql();

  /** The driver itself: no {@code DriverManager} scan, no driver chosen by URL. */
  Driver driver();

  /** {@code jdbc:postgresql://host:port/database}, from fields the form validated. */
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
   * unqualified names and literals are read. For a read it also checks that the transaction is READ ONLY: if the
   * driver didn't make it so, nothing runs.
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
