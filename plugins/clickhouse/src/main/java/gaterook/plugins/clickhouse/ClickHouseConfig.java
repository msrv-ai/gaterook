package gaterook.plugins.clickhouse;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * A connection's non-secret settings as the manifest's configSchema defines them: where the HTTP interface is, the
 * database queries run in, the user, TLS and the limits every query carries. The password is only named
 * ({@link #PASSWORD}); its value comes through the call's {@code SecretResolver}.
 *
 * @param maxRows             rows a query returns at most (the environment's limit applies on top)
 * @param maxExecutionSeconds ClickHouse's {@code max_execution_time} for every query
 */
record ClickHouseConfig(String host, int port, String database, String user, boolean tls, int maxRows,
    int maxExecutionSeconds) {

  /** The secret field: the user's password. */
  static final String PASSWORD = "/password";

  private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$|^\\[[0-9A-Fa-f:.]{2,45}]$");
  private static final Pattern NAME = Pattern.compile("^[^\\x00-\\x1f`]{1,128}$");

  static ClickHouseConfig of(ConnectionConfig cfg) throws ConnectorException {
    String host = cfg.string("/host");
    if (host == null || host.isBlank()) {
      throw ConnectorException.badInput("The connection has no host.");
    }
    host = host.strip();
    if (host.contains("://")) {
      throw ConnectorException.badInput("The host is a name or an address, without https:// (" + host + ").");
    }
    if (!HOST.matcher(host).matches()) {
      throw ConnectorException.badInput("The host isn't a host name or an address: " + host);
    }
    int port = cfg.integer("/port", 8443);
    if (port < 1 || port > 65535) {
      throw ConnectorException.badInput("The port is 1 to 65535.");
    }
    String database = text(cfg.string("/database"), "default");
    String user = text(cfg.string("/user"), "default");
    if (!NAME.matcher(database).matches() || !NAME.matcher(user).matches()) {
      throw ConnectorException.badInput("The database and the user are names of up to 128 characters.");
    }
    boolean tls = cfg.bool("/options/tls", true);
    int maxRows = Math.clamp(cfg.integer("/options/maxRows", 1000), 1, 10_000);
    int maxSeconds = Math.clamp(cfg.integer("/options/maxExecutionSeconds", 30), 1, 300);
    return new ClickHouseConfig(host, port, database, user, tls, maxRows, maxSeconds);
  }

  /** {@code https://host:port/} (or {@code http://} without TLS). */
  URI base() {
    return URI.create((tls ? "https" : "http") + "://" + host + ":" + port + "/");
  }

  private static String text(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.strip();
  }
}
