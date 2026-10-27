package gaterook.plugins.rabbitmq;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * A connection's non-secret settings as the manifest's configSchema defines them: where the management API is, the
 * virtual host every tool works in, the user, TLS and the HTTP timeout. The password is only named ({@link #PASSWORD});
 * its value comes through the call's {@code SecretResolver}.
 */
record RabbitConfig(String host, int port, String vhost, String user, boolean tls, int timeoutMs) {

  /** The secret field: the user's password. */
  static final String PASSWORD = "/password";

  private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]{1,253}$|^\\[[0-9A-Fa-f:.]{2,45}]$");

  static RabbitConfig of(ConnectionConfig cfg) throws ConnectorException {
    String host = cfg.string("/host");
    if (host == null || host.isBlank()) {
      throw ConnectorException.badInput("The connection has no host.");
    }
    host = host.strip();
    if (host.contains("://")) {
      throw ConnectorException.badInput("The host is a name or an address, without http:// (" + host + ").");
    }
    if (!HOST.matcher(host).matches()) {
      throw ConnectorException.badInput("The host isn't a host name or an address: " + host);
    }
    int port = cfg.integer("/port", 15672);
    if (port < 1 || port > 65535) {
      throw ConnectorException.badInput("The port is 1 to 65535.");
    }
    String vhost = cfg.string("/vhost");
    String user = cfg.string("/user");
    if (user == null || user.isBlank()) {
      throw ConnectorException.badInput("The connection has no user.");
    }
    return new RabbitConfig(host, port, vhost == null || vhost.isEmpty() ? "/" : vhost, user.strip(),
        cfg.bool("/options/tls", false), Math.clamp(cfg.integer("/options/timeoutMs", 10_000), 1000, 30_000));
  }

  /** {@code http://host:15672} + {@code path} (with TLS: https). */
  URI uri(String pathAndQuery) {
    return URI.create((tls ? "https" : "http") + "://" + host + ":" + port + pathAndQuery);
  }

  /** The virtual host as one path segment ({@code /} is {@code %2F}). */
  String vhostSegment() {
    return segment(vhost);
  }

  /** A name as one path segment of the management API. */
  static String segment(String name) {
    return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
