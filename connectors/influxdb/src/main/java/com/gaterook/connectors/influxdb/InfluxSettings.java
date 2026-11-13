package com.gaterook.connectors.influxdb;

import com.gaterook.connector.api.ConnectionConfig;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.Env;
import com.gaterook.connectors.support.Glob;
import com.gaterook.connectors.support.egress.AddressPolicy;
import com.gaterook.connectors.support.egress.UrlRules;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/**
 * The settings of one InfluxDB connection ({@code config.schema.json}).
 *
 * @param base             the server's URL without a trailing slash: https, or plain http to this computer only; a
 *                         path prefix is kept and the API's fixed paths go after it
 * @param family           the API the person chose: InfluxDB 3, 2.x or 1.x; Test connection checks it
 * @param username         the user of {@link AuthMethod#PASSWORD}, or {@code null}
 * @param organization     InfluxDB 2.x: the organization of a write ({@code org}), or {@code null}
 * @param databases        globs of the databases (buckets) agents may use; empty: every one the credentials may read
 * @param maxRows          the most rows one read returns
 * @param maxResponseBytes the most bytes of rows one answer carries
 * @param maxWritePoints   the most points one write holds
 * @param caCertificate    a private CA in PEM, or {@code null} for the platform's CAs
 */
record InfluxSettings(URI base, Family family, AuthMethod auth, String username, String organization,
    List<String> databases, int maxRows, long maxResponseBytes, int maxWritePoints, String caCertificate) {

  /** The API families: the server's major version decides which endpoints exist. */
  enum Family {
    V1("1", "InfluxDB 1.x"), V2("2", "InfluxDB 2.x"), V3("3", "InfluxDB 3");

    final String id;
    final String label;

    Family(String id, String label) {
      this.id = id;
      this.label = label;
    }
  }

  enum AuthMethod { TOKEN, PASSWORD, NONE }

  static final String TOKEN = "/auth/token";
  static final String PASSWORD = "/auth/password";
  static final int DEFAULT_MAX_ROWS = 1_000;
  static final int DEFAULT_MAX_RESPONSE_KB = 1_024;
  static final int DEFAULT_MAX_WRITE_POINTS = 1_000;
  /** A database, bucket, retention policy or measurement name: printable, at most 255 characters. */
  static final Pattern NAME = Pattern.compile("[^\\x00-\\x1F\\x7F]{1,255}");
  private static final Pattern USERNAME = Pattern.compile("[^\\x00-\\x1F\\x7F:]{1,256}");

  static InfluxSettings of(ConnectionConfig cfg) throws ConnectorException {
    URI base = baseUrl(cfg.string("/url"));
    String version = cfg.string("/version");
    Family family = switch (version == null ? "3" : version) {
      case "3" -> Family.V3;
      case "2" -> Family.V2;
      case "1" -> Family.V1;
      default -> throw ConnectorException.badInput("Choose the server: InfluxDB 3, 2.x or 1.x.");
    };
    String method = cfg.string("/auth/method");
    AuthMethod auth = switch (method == null ? "token" : method) {
      case "token" -> AuthMethod.TOKEN;
      case "password" -> AuthMethod.PASSWORD;
      case "none" -> AuthMethod.NONE;
      default -> throw ConnectorException.badInput("Choose how GateRook signs in: an API token, a username and "
          + "password, or none.");
    };
    if (auth == AuthMethod.PASSWORD && family != Family.V1) {
      throw ConnectorException.badInput(family.label + " signs in with an API token: choose API token under "
          + "Credentials.");
    }
    if (auth == AuthMethod.TOKEN && family == Family.V1) {
      throw ConnectorException.badInput("InfluxDB 1.x signs in with a username and password: choose them under "
          + "Credentials.");
    }
    if (auth == AuthMethod.NONE && family == Family.V2) {
      throw ConnectorException.badInput("InfluxDB 2.x needs an API token: choose API token under Credentials.");
    }
    if (auth == AuthMethod.NONE && cfg.env() == Env.PROD) {
      throw ConnectorException.badInput("A prod connection signs in: choose an API token or a username and "
          + "password under Credentials.");
    }
    String username = null;
    if (auth == AuthMethod.PASSWORD) {
      username = cfg.string("/auth/username");
      if (username == null || !USERNAME.matcher(username).matches()) {
        throw ConnectorException.badInput("Enter the InfluxDB user under Credentials, without a colon.");
      }
    }
    String organization = cfg.string("/organization");
    if (organization != null && (organization.isBlank() || !NAME.matcher(organization).matches())) {
      throw ConnectorException.badInput("The organization is a name such as acme.");
    }
    List<String> databases = new ArrayList<>();
    for (String glob : cfg.strings("/databases")) {
      if (glob == null || !NAME.matcher(glob).matches()) {
        throw ConnectorException.badInput("A database of the list is a name or a glob such as iot_*.");
      }
      databases.add(glob);
    }
    int maxRows = clamp(cfg.integer("/advanced/maxRows", DEFAULT_MAX_ROWS), 1, 10_000);
    long maxResponseBytes = (long) clamp(cfg.integer("/advanced/maxResponseKb", DEFAULT_MAX_RESPONSE_KB), 16,
        16_384) * 1024;
    int maxWritePoints = clamp(cfg.integer("/advanced/maxWritePoints", DEFAULT_MAX_WRITE_POINTS), 1, 10_000);
    String ca = cfg.string("/advanced/caCertificate");
    return new InfluxSettings(base, family, auth, username, family == Family.V2 ? organization : null,
        List.copyOf(databases), maxRows, maxResponseBytes, maxWritePoints, ca == null || ca.isBlank() ? null : ca);
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  /**
   * The base URL a person typed: http(s), a canonical host, no credentials, query or fragment, no dot segments, and no
   * literal of a cloud metadata or reserved address (UrlRules, AddressPolicy). Plain http only to this computer: the
   * token would travel unencrypted. What a name resolves to, and GateRook's own ports, are judged before each request
   * ({@link Addresses}).
   */
  static URI baseUrl(String url) throws ConnectorException {
    if (url == null || url.isBlank()) {
      throw ConnectorException.badInput("Enter the server's URL, such as https://influxdb.example.com:8181.");
    }
    HttpUrl parsed;
    try {
      parsed = UrlRules.parse(url);
    } catch (UrlRules.UrlRejectedException e) {
      throw ConnectorException.badInput(e.getMessage() + ".");
    }
    if (parsed.query() != null) {
      throw ConnectorException.badInput("The URL can't have a ?query: give the server's address, such as "
          + "https://influxdb.example.com:8181.");
    }
    String host = parsed.host();
    Optional<InetAddress> literal = AddressPolicy.canonicalIp(host);
    if (literal.isPresent()) {
      AddressPolicy.Verdict v = AddressPolicy.builder().build().check(literal.get(), parsed.port());
      if (v.reason() != AddressPolicy.Reason.PUBLIC && v.reason() != AddressPolicy.Reason.PRIVATE
          && v.reason() != AddressPolicy.Reason.ALLOWED_PRIVATE) {
        throw ConnectorException.badInput(v.text());
      }
    }
    if ("http".equals(parsed.scheme()) && !loopback(host, literal)) {
      throw ConnectorException.badInput("Plain http:// goes only to this computer (localhost, 127.0.0.1 or ::1): "
          + "use https:// for " + host + ".");
    }
    String text = parsed.toString();
    return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
  }

  private static boolean loopback(String host, Optional<InetAddress> literal) {
    return literal.map(InetAddress::isLoopbackAddress).orElse(false)
        || "localhost".equals(host.toLowerCase(Locale.ROOT));
  }

  /** Whether agents may use {@code database}: on the list, or any when the list is empty. */
  boolean allows(String database) {
    if (database == null) {
      return false;
    }
    if (databases.isEmpty()) {
      return true;
    }
    return Glob.anyText(databases, database);
  }

  boolean plainHttp() {
    return "http".equals(base.getScheme());
  }

  /** {@code host:port} of the server, for targets and messages. */
  String target() {
    return base.getPort() < 0 ? base.getHost() : base.getHost() + ":" + base.getPort();
  }
}
