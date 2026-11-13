package ai.msrv.gaterook.connectors.h2;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One H2 connection as GateRook uses it: an H2 server's host and TCP port, the database as the server names it, the
 * user, and the limits of each call. The JDBC URL is built from these fields only, so no setting of the URL comes from
 * anywhere else: {@code jdbc:h2:tcp://host:port/database;IFEXISTS=TRUE}, which never creates a database on the server
 * (a server started with {@code -ifNotExists} would create one, with the connecting user as its administrator).
 *
 * @param statementTimeoutMs the connection's own statement timeout, or {@code null} for the environment's
 */
record H2Config(String host, int port, String database, String user, int maxRows, Integer statementTimeoutMs) {

  static final int DEFAULT_PORT = 9092;
  static final int DEFAULT_MAX_ROWS = 1_000;

  private static final Pattern HOST = Pattern.compile("^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?"
      + "|\\[[0-9A-Fa-f:.]{2,45}\\]|[0-9A-Fa-f:.]{2,45})$");

  static H2Config of(ConnectionConfig cfg) throws ConnectorException {
    String host = strip(cfg.string("/host"));
    if (host == null) {
      throw ConnectorException.badInput("The connection names no host: set the H2 server's host.");
    }
    if (!HOST.matcher(host).matches()) {
      throw ConnectorException.badInput("The host isn't a host name or an address: " + host);
    }
    int port = cfg.integer("/port", DEFAULT_PORT);
    if (port < 1 || port > 65_535) {
      throw ConnectorException.badInput("The port must be 1 to 65535.");
    }
    String database = strip(cfg.string("/database"));
    String problem = databaseProblem(database);
    if (problem != null) {
      throw ConnectorException.badInput(problem);
    }
    String user = strip(cfg.string("/user"));
    if (user == null) {
      throw ConnectorException.badInput("The connection names no user.");
    }
    if (user.chars().anyMatch(Character::isISOControl)) {
      throw ConnectorException.badInput("The user name holds a control character.");
    }
    int maxRows = Math.clamp(cfg.integer("/options/maxRows", DEFAULT_MAX_ROWS), 1, 10_000);
    int timeout = cfg.integer("/options/statementTimeoutMs", -1);
    return new H2Config(host, port, database, user, maxRows, timeout > 0 ? timeout : null);
  }

  /**
   * Why {@code database} isn't a database name GateRook passes to the server, or {@code null}: a path the server
   * reads ({@code ~/orders}, {@code ./data/orders}, {@code /srv/h2/orders}, {@code C:/h2/orders}) or an in-memory
   * database ({@code mem:orders}), without a {@code ;} (the URL's settings are GateRook's), a control character or
   * another server ({@code tcp://}, {@code ssl://}).
   */
  static String databaseProblem(String database) {
    if (database == null) {
      return "The connection names no database: set the database's name or path on the server.";
    }
    if (database.length() > 1_024) {
      return "The database name is longer than 1024 characters.";
    }
    if (database.indexOf(';') >= 0) {
      return "The database name holds a ';': GateRook sets the URL's settings itself.";
    }
    if (database.chars().anyMatch(Character::isISOControl)) {
      return "The database name holds a control character.";
    }
    String lower = database.toLowerCase(Locale.ROOT);
    if (lower.contains("://") || lower.startsWith("tcp:") || lower.startsWith("ssl:")) {
      return "The database is a name or a path on this server, not another server.";
    }
    return null;
  }

  /** The URL of this connection: only the server's TCP protocol, and only an existing database. */
  String url() {
    String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    String db = database.startsWith("/") ? database.substring(1) : database;
    // An absolute path keeps its leading slash after the one that ends the authority: tcp://host:port//srv/h2/db.
    String path = database.startsWith("/") ? "/" + db : db;
    return "jdbc:h2:tcp://" + h + ":" + port + "/" + path + ";IFEXISTS=TRUE";
  }

  /** The host as it is shown: {@code h2.acme.internal:9092}. */
  String address() {
    String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    return h + ":" + port;
  }

  private static String strip(String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }
}
