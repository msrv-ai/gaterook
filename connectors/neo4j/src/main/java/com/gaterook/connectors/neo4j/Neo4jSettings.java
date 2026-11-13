package com.gaterook.connectors.neo4j;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connectors.support.Pem;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The settings of one Neo4j connection ({@code config.schema.json}).
 *
 * <p>The URI names the scheme, the host and the port, nothing else: {@code neo4j://} routes through a cluster,
 * {@code bolt://} talks to one server, {@code +s} verifies TLS with the platform's CAs and the host name. A private CA
 * keeps the same checks with that CA instead. Without TLS GateRook connects only to this computer, and it never trusts
 * a certificate it can't verify ({@code +ssc}).
 *
 * @param routing        {@code neo4j://}: the cluster's routing table decides which server runs a call
 * @param tls            {@code +s}, with the platform's CAs or {@link #caCertificates}
 * @param database       the database every call runs in; never {@code system}
 * @param method         how GateRook signs in
 * @param user           the user of {@link Method#PASSWORD}, else {@code null}
 * @param caPem          the private CA as PEM, re-encoded from {@link #caCertificates}, or {@code null}
 * @param maxRows        the connection's own row limit, or {@code null}: the policy's
 * @param transactionTimeout the connection's own transaction timeout, or {@code null}: the policy's statement timeout
 */
record Neo4jSettings(boolean routing, boolean tls, String host, int port, String database, Method method, String user,
    List<X509Certificate> caCertificates, String caPem, Integer maxRows, Duration transactionTimeout,
    Duration connectTimeout) {

  /** How GateRook signs in: Neo4j's basic scheme, or a bearer token of single sign-on. */
  enum Method { PASSWORD, TOKEN }

  static final String DEFAULT_DATABASE = "neo4j";
  static final int DEFAULT_PORT = 7687;
  static final String PASSWORD = "/authentication/password";
  static final String TOKEN = "/authentication/token";
  /** Neo4j's database names: a letter, then letters, digits, dots and dashes, 3 to 63 characters. */
  static final Pattern DATABASE = Pattern.compile("[A-Za-z][A-Za-z0-9.-]{2,62}");
  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
      + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*|\\[[0-9A-Fa-f:.]+]");

  static Neo4jSettings of(ConnectionConfig cfg) throws ConnectorException {
    String text = cfg.string("/uri");
    if (text == null || text.isBlank()) {
      throw ConnectorException.badInput("Enter the URI of the Neo4j server, such as neo4j+s://graph.example.com.");
    }
    URI uri;
    try {
      uri = new URI(text.strip());
    } catch (URISyntaxException e) {
      throw ConnectorException.badInput("The URI isn't one: write it as neo4j+s://host:7687.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (scheme.endsWith("+ssc")) {
      throw ConnectorException.badInput(scheme + ":// accepts any certificate, so GateRook doesn't use it. Use "
          + scheme.replace("+ssc", "+s") + ":// and paste the server's CA certificate under Advanced.");
    }
    boolean routing;
    boolean tls;
    switch (scheme) {
      case "neo4j" -> { routing = true; tls = false; }
      case "neo4j+s" -> { routing = true; tls = true; }
      case "bolt" -> { routing = false; tls = false; }
      case "bolt+s" -> { routing = false; tls = true; }
      default -> throw ConnectorException.badInput("Use a Neo4j URI: neo4j+s://, bolt+s://, or neo4j:// and bolt:// "
          + "to this computer.");
    }
    if (uri.getRawUserInfo() != null) {
      throw ConnectorException.badInput("Leave the user out of the URI: enter it under Authentication.");
    }
    if (uri.getRawQuery() != null || uri.getRawFragment() != null
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
      throw ConnectorException.badInput("The URI names the server only: scheme, host and port, such as "
          + "neo4j+s://graph.example.com:7687. Choose the database below.");
    }
    String host = uri.getHost();
    if (host == null || !HOST.matcher(host).matches()) {
      throw ConnectorException.badInput("The URI needs a host name or an IP address, such as "
          + "neo4j+s://graph.example.com.");
    }
    int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
    if (port < 1 || port > 65_535) {
      throw ConnectorException.badInput("The port must be between 1 and 65535.");
    }
    if (!tls && !loopback(host)) {
      throw ConnectorException.badInput("Without TLS GateRook connects only to this computer (localhost): use "
          + scheme + "+s:// for " + host + ".");
    }
    String database = cfg.string("/database");
    if (database == null || database.isBlank()) {
      database = DEFAULT_DATABASE;
    }
    if (!DATABASE.matcher(database).matches()) {
      throw ConnectorException.badInput(database + " isn't a Neo4j database name: 3 to 63 letters, digits, dots and "
          + "dashes, starting with a letter.");
    }
    if (database.equalsIgnoreCase("system")) {
      throw ConnectorException.badInput("The system database holds users, roles and databases: connect a database "
          + "with your graph, such as neo4j.");
    }
    String method = cfg.string("/authentication/method");
    Method m;
    String user = null;
    if (method == null || method.equals("password")) {
      m = Method.PASSWORD;
      user = cfg.string("/authentication/user");
      if (user == null || user.isBlank()) {
        throw ConnectorException.badInput("Enter the Neo4j user GateRook signs in as.");
      }
    } else if (method.equals("token")) {
      m = Method.TOKEN;
    } else {
      throw ConnectorException.badInput("Choose how GateRook signs in: a user and password, or a bearer token.");
    }
    List<X509Certificate> ca = List.of();
    String caPem = null;
    String caText = cfg.string("/caCertificate");
    if (caText != null && !caText.isBlank()) {
      if (!tls) {
        throw ConnectorException.badInput("A CA certificate needs TLS: use " + scheme + "+s://.");
      }
      try {
        ca = Pem.certificates(caText);
      } catch (IllegalArgumentException e) {
        // Pem's messages never quote the input.
        throw ConnectorException.badInput("The CA certificate can't be used: " + e.getMessage() + ".");
      }
      caPem = Pem.encode(ca);
    }
    Integer maxRows = cfg.config().at("/advanced/maxRows").isIntegralNumber()
        ? Math.max(1, Math.min(cfg.config().at("/advanced/maxRows").asInt(), 10_000)) : null;
    Duration timeout = cfg.config().at("/advanced/transactionTimeoutMs").isIntegralNumber()
        ? Duration.ofMillis(Math.max(100, Math.min(cfg.config().at("/advanced/transactionTimeoutMs").asLong(),
            600_000))) : null;
    Duration connect = Duration.ofMillis(Math.max(1_000, Math.min(cfg.integer("/advanced/connectTimeoutMs", 5_000),
        30_000)));
    return new Neo4jSettings(routing, tls, host, port, database, m, user, ca, caPem, maxRows, timeout, connect);
  }

  /** Only to this computer by name or address: {@code localhost}, {@code 127.0.0.0/8}, {@code [::1]}. */
  static boolean loopback(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (h.equals("localhost") || h.equals("[::1]") || h.equals("::1")) {
      return true;
    }
    return h.matches("127\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)\\."
        + "(25[0-5]|2[0-4]\\d|1?\\d?\\d)");
  }

  /** The scheme as the person wrote it: {@code neo4j+s}, {@code bolt}. */
  String scheme() {
    return (routing ? "neo4j" : "bolt") + (tls ? "+s" : "");
  }

  /** {@code graph.example.com:7687}, for targets and messages. */
  String address() {
    return host + ":" + port;
  }

  /** The secret field of the sign-in. */
  String secretField() {
    return method == Method.PASSWORD ? PASSWORD : TOKEN;
  }
}
