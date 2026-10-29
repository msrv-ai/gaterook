package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.time.Duration;
import java.util.List;

/**
 * The non-secret settings of one SQL connection, read from its config ({@code postgres.config.schema.json},
 * {@code mysql.config.schema.json}, {@code sqlserver.config.schema.json}) with the form's defaults. The core
 * validated the config against the schema before the connector sees it.
 *
 * @param database      PostgreSQL: required; MySQL and SQL Server: the default database, or {@code null}
 * @param defaultSchema      where unqualified names live: PostgreSQL {@code defaultSchema} ({@code public}), MySQL
 *                           the database (or {@code null} without one); SQL Server: the user's expected default
 *                           schema ({@code dbo})
 * @param statementTimeoutMs the connection's own statement timeout ({@code advanced.statementTimeoutMs}), 0 when it
 *                           has none: a call gets the stricter of it and the policy's (the core's limits don't
 *                           read connection settings)
 */
record ConnectionSettings(String host, int port, String database, String sslMode, String caCertificate, String user,
    int poolSize, int connectTimeoutMs, String defaultSchema, List<String> schemaAllowlist,
    List<String> allowedFunctions, boolean allowPublicKeyRetrieval, int statementTimeoutMs) {

  static final String PASSWORD = "/credentials/password";

  static ConnectionSettings of(ConnectionConfig cfg, SqlDialect dialect) {
    if (!dialect.sqlServer()) {
      return of(cfg, dialect.mysql());
    }
    ConnectionSettings base = of(cfg, false);
    return new ConnectionSettings(base.host(), cfg.integer("/connection/port", 1433), base.database(),
        base.sslMode(), base.caCertificate(), base.user(), base.poolSize(), base.connectTimeoutMs(),
        orDefault(cfg.string("/advanced/defaultSchema"), "dbo"), base.schemaAllowlist(), base.allowedFunctions(),
        false, base.statementTimeoutMs());
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
        Math.max(0, Math.min(600_000, cfg.integer("/advanced/statementTimeoutMs", 0))));
  }

  /** The same server and credentials, routed to one explicitly selected database. */
  ConnectionSettings withDatabase(String value, boolean mysql) {
    return new ConnectionSettings(host, port, value, sslMode, caCertificate, user, poolSize, connectTimeoutMs,
        mysql ? value : defaultSchema, schemaAllowlist, allowedFunctions, allowPublicKeyRetrieval, statementTimeoutMs);
  }

  /** The same settings with a connect timeout of at most {@code maxMs}: a test or health check has its budget. */
  ConnectionSettings withConnectTimeoutAtMost(int maxMs) {
    return new ConnectionSettings(host, port, database, sslMode, caCertificate, user, poolSize,
        Math.min(connectTimeoutMs, maxMs), defaultSchema, schemaAllowlist, allowedFunctions, allowPublicKeyRetrieval,
        statementTimeoutMs);
  }

  /** The statement timeout of a call: the policy's, or the connection's own where that is stricter. */
  Duration statementTimeout(Duration policy) {
    return statementTimeoutMs > 0 && statementTimeoutMs < policy.toMillis() ? Duration.ofMillis(statementTimeoutMs)
        : policy;
  }

  /** {@code db.dev.internal:5432/app}, as the approver and Activity see it. */
  String target() {
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
