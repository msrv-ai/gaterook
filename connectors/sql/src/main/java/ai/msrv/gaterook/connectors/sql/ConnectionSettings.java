package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The non-secret settings of one SQL connection, read from its config ({@code postgres.config.schema.json},
 * {@code mysql.config.schema.json}, {@code oracle.config.schema.json}, {@code sqlserver.config.schema.json})
 * with the form's defaults. The core validated the config against the schema before the connector sees it.
 *
 * @param database      PostgreSQL: required; MySQL and SQL Server: the default database, or {@code null}; Oracle: the service name
 * @param defaultSchema      where unqualified names live: PostgreSQL {@code defaultSchema} ({@code public}), MySQL
 *                           the database (or {@code null} without one); Oracle: the user or explicit schema;
 *                           SQL Server: the expected database user schema ({@code dbo})
 * @param statementTimeoutMs the connection's own statement timeout ({@code advanced.statementTimeoutMs}), 0 when it
 *                           has none: a call gets the stricter of it and the policy's (the core's limits don't
 *                           read connection settings)
 * @param sslMode            the form's choice ({@code verify-full} when a stored PostgreSQL connection has none);
 *                           once {@link SqlDialect#resolveTransport} chose for PostgreSQL's {@code auto}, what it chose
 * @param file               SQLite: the database file on this computer; {@code null} for a database server
 * @param autoTls            whether {@code sslMode} is what Auto chose after asking the server ({@link PostgresTls})
 */
record ConnectionSettings(String host, int port, String database, String sslMode, String caCertificate, String user,
    int poolSize, int connectTimeoutMs, String defaultSchema, List<String> schemaAllowlist,
    List<String> allowedFunctions, boolean allowPublicKeyRetrieval, int statementTimeoutMs, FileDatabase file,
    boolean autoTls) {

  /** Settings as the form gave them: the transport is the form's choice, not one Auto made. */
  ConnectionSettings(String host, int port, String database, String sslMode, String caCertificate, String user,
      int poolSize, int connectTimeoutMs, String defaultSchema, List<String> schemaAllowlist,
      List<String> allowedFunctions, boolean allowPublicKeyRetrieval, int statementTimeoutMs, FileDatabase file) {
    this(host, port, database, sslMode, caCertificate, user, poolSize, connectTimeoutMs, defaultSchema,
        schemaAllowlist, allowedFunctions, allowPublicKeyRetrieval, statementTimeoutMs, file, false);
  }

  /**
   * A database file ({@code sqlite.config.schema.json}).
   *
   * @param path          the file as the person chose it, an absolute path or {@code ~/…}
   * @param busyTimeoutMs how long a call waits for another program's write lock
   * @param foreignKeys   whether SQLite enforces the file's foreign keys on approved changes
   */
  record FileDatabase(String path, int busyTimeoutMs, boolean foreignKeys) {}

  static final String PASSWORD = "/credentials/password";
  /** The login's user: typed into the form, or a secret field too when a store holds it with the password. */
  static final String USER = "/credentials/user";

  static ConnectionSettings of(ConnectionConfig cfg, SqlDialect dialect) {
    if (dialect.syntax() == SqlSyntax.SQLITE) {
      return new ConnectionSettings(null, 0, null, "disable", null, null,
          Math.max(1, Math.min(4, cfg.integer("/advanced/poolSize", 2))), 5_000, "main", List.of(), List.of(), false,
          Math.max(0, Math.min(600_000, cfg.integer("/advanced/statementTimeoutMs", 0))),
          new FileDatabase(blankToNull(cfg.string("/connection/file")),
              Math.max(0, Math.min(30_000, cfg.integer("/advanced/busyTimeoutMs", 2_000))),
              cfg.bool("/advanced/foreignKeys", true)));
    }
    if (dialect.sqlServer()) {
      ConnectionSettings base = of(cfg, false);
      return new ConnectionSettings(base.host(), cfg.integer("/connection/port", 1433), base.database(),
          base.sslMode(), base.caCertificate(), base.user(), base.poolSize(), base.connectTimeoutMs(),
          orDefault(cfg.string("/advanced/defaultSchema"), "dbo"), base.schemaAllowlist(), List.of(),
          false, base.statementTimeoutMs(), null);
    }
    if (dialect.syntax() != SqlSyntax.ORACLE) return of(cfg, dialect.mysql());
    String user = blankToNull(cfg.string("/credentials/user"));
    String schema = blankToNull(cfg.string("/advanced/defaultSchema"));
    if (schema == null && user != null) schema = oracleIdentifier(user);
    else if (schema != null) schema = oracleIdentifier(schema);
    return new ConnectionSettings(cfg.string("/connection/host"), cfg.integer("/connection/port", 1521),
        blankToNull(cfg.string("/connection/database")),
        orDefault(cfg.string("/connection/sslMode"), "verify-full"),
        blankToNull(cfg.string("/connection/caCertificate")), user,
        Math.max(1, Math.min(10, cfg.integer("/advanced/poolSize", 2))),
        Math.max(1_000, Math.min(30_000, cfg.integer("/advanced/connectTimeoutMs", 5_000))),
        schema, List.copyOf(cfg.strings("/advanced/schemaAllowlist")), List.of(), false,
        Math.max(0, Math.min(600_000, cfg.integer("/advanced/statementTimeoutMs", 0))), null);
  }

  private static String oracleIdentifier(String name) {
    return name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2
        ? name.substring(1, name.length() - 1).replace("\"\"", "\"") : name.toUpperCase(Locale.ROOT);
  }

  ConnectionSettings withDatabase(String value, SqlDialect dialect) {
    return withDatabase(value, dialect.mysql());
  }

  static ConnectionSettings of(ConnectionConfig cfg, boolean mysql) {
    String database = blankToNull(DatabaseSelection.first(cfg));
    String defaultSchema = mysql ? database : orDefault(cfg.string("/advanced/defaultSchema"), "public");
    String ca = blankToNull(cfg.string("/connection/caCertificate"));
    return new ConnectionSettings(
        cfg.string("/connection/host"),
        cfg.integer("/connection/port", mysql ? 3306 : 5432),
        database,
        orDefault(cfg.string("/connection/sslMode"), mysql ? "auto" : "verify-full"),
        ca,
        blankToNull(cfg.string("/credentials/user")),
        Math.max(1, Math.min(10, cfg.integer("/advanced/poolSize", 2))),
        Math.max(1_000, Math.min(30_000, cfg.integer("/advanced/connectTimeoutMs", 5_000))),
        defaultSchema,
        List.copyOf(cfg.strings("/advanced/schemaAllowlist")),
        List.copyOf(cfg.strings("/advanced/allowedFunctions")),
        cfg.bool("/advanced/allowPublicKeyRetrieval", mysql),
        Math.max(0, Math.min(600_000, cfg.integer("/advanced/statementTimeoutMs", 0))), null);
  }

  /** The same server and credentials, routed to one explicitly selected database. */
  ConnectionSettings withDatabase(String value, boolean mysql) {
    return new ConnectionSettings(host, port, value, sslMode, caCertificate, user, poolSize, connectTimeoutMs,
        mysql ? value : defaultSchema, schemaAllowlist, allowedFunctions, allowPublicKeyRetrieval, statementTimeoutMs,
        file, autoTls);
  }

  /** The same settings with a connect timeout of at most {@code maxMs}: a test or health check has its budget. */
  ConnectionSettings withConnectTimeoutAtMost(int maxMs) {
    return new ConnectionSettings(host, port, database, sslMode, caCertificate, user, poolSize,
        Math.min(connectTimeoutMs, maxMs), defaultSchema, schemaAllowlist, allowedFunctions, allowPublicKeyRetrieval,
        statementTimeoutMs, file, autoTls);
  }

  /** The same settings with the transport Auto chose for this server: {@code verify-full} or {@code disable}. */
  ConnectionSettings withTransport(String mode) {
    return new ConnectionSettings(host, port, database, mode, caCertificate, user, poolSize, connectTimeoutMs,
        defaultSchema, schemaAllowlist, allowedFunctions, allowPublicKeyRetrieval, statementTimeoutMs, file, true);
  }

  /** The statement timeout of a call: the policy's, or the connection's own where that is stricter. */
  Duration statementTimeout(Duration policy) {
    return statementTimeoutMs > 0 && statementTimeoutMs < policy.toMillis() ? Duration.ofMillis(statementTimeoutMs)
        : policy;
  }

  /** {@code db.dev.internal:5432/app}, as the approver and Activity see it. */
  String target() {
    if (file != null) {
      return file.path() == null ? "?" : file.path();
    }
    String h = host == null ? "?" : host;
    return h + ":" + port + (database == null ? "" : "/" + database);
  }

  private static String orDefault(String value, String dflt) {
    return value == null || value.isBlank() ? dflt : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
