package ai.msrv.gaterook.connectors.prometheus;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connectors.support.egress.AddressPolicy;
import ai.msrv.gaterook.connectors.support.egress.UrlRules;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import tools.jackson.databind.JsonNode;

/**
 * The settings of one Prometheus connection ({@code config.schema.json}). Everything an agent can't change lives here:
 * the server's URL, the tenant, the credentials' kind, a private CA and the limits of one call.
 *
 * @param base             the server's URL with any path before {@code /api/v1}, always ending in {@code /}
 * @param tenant           the {@code X-Scope-OrgID} of Mimir, Cortex and multi-tenant Thanos, or {@code null}
 * @param username         the user of basic authentication, else {@code null}
 * @param caCertificate    the PEM of a private CA, or {@code null}
 * @param maxSeries        the most series one answer holds
 * @param maxPoints        the most points a range query may ask for per series
 * @param maxResponseBytes the most of one server answer GateRook reads
 */
record PrometheusSettings(URI base, String tenant, Auth auth, String username, String caCertificate, int maxSeries,
    int maxPoints, long maxResponseBytes) {

  /** How GateRook signs in to the server. */
  enum Auth { NONE, BASIC, BEARER }

  /** The JSON pointer of the password or token. */
  static final String SECRET = "/auth/secret";
  static final int DEFAULT_MAX_SERIES = 1_000;
  /** Prometheus refuses a range query with more points per series than this. */
  static final int MAX_POINTS = 11_000;
  static final int DEFAULT_RESPONSE_MB = 16;
  static final Pattern TENANT = Pattern.compile("[A-Za-z0-9!._*'()|-]{1,150}");

  static PrometheusSettings of(ConnectionConfig cfg) throws ConnectorException {
    JsonNode c = cfg.config();
    URI base = base(text(c, "url"));
    String tenant = text(c, "tenant");
    if (tenant != null && !TENANT.matcher(tenant).matches()) {
      throw ConnectorException.badInput("A tenant has letters, digits and !._*'()- only; several are joined by |.");
    }
    String mode = text(c.path("auth"), "mode");
    Auth auth = switch (mode == null ? "none" : mode) {
      case "none" -> Auth.NONE;
      case "basic" -> Auth.BASIC;
      case "bearer" -> Auth.BEARER;
      default -> throw ConnectorException.badInput("Choose how GateRook signs in: no authentication, username and "
          + "password, or a bearer token.");
    };
    String username = null;
    if (auth == Auth.BASIC) {
      username = text(c.path("auth"), "username");
      if (username == null || username.length() > 200 || username.indexOf(':') >= 0
          || username.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
        throw ConnectorException.badInput("Basic authentication needs a username without colons or control "
            + "characters.");
      }
    }
    String ca = text(c, "caCertificate");
    if (ca != null && !ca.contains("-----BEGIN CERTIFICATE-----")) {
      throw ConnectorException.badInput("The CA certificate must be PEM: -----BEGIN CERTIFICATE----- … "
          + "-----END CERTIFICATE-----.");
    }
    JsonNode advanced = c.path("advanced");
    int maxSeries = clamp(advanced.path("maxSeries").asInt(DEFAULT_MAX_SERIES), 1, 10_000);
    int maxPoints = clamp(advanced.path("maxPoints").asInt(MAX_POINTS), 10, MAX_POINTS);
    int mb = clamp(advanced.path("maxResponseMb").asInt(DEFAULT_RESPONSE_MB), 1, 64);
    return new PrometheusSettings(base, tenant, auth, username, ca, maxSeries, maxPoints, (long) mb << 20);
  }

  /**
   * The URL a person typed, checked by the egress guard's URL rules (no credentials, query or fragment, a canonical
   * host, no dot segments): https anywhere, plain http only to this computer, never an IP literal of a cloud metadata
   * or reserved address. Its path is kept (Mimir answers under {@code /prometheus}); {@code /api/v1} is GateRook's to
   * add. What a name resolves to and the gateway's own ports are judged before each request ({@link Addresses}).
   */
  static URI base(String url) throws ConnectorException {
    if (url == null) {
      throw ConnectorException.badInput("Enter the server's URL, such as https://prometheus.example.com.");
    }
    HttpUrl parsed;
    try {
      parsed = UrlRules.parse(url);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput(e.getMessage() + ".");
    }
    if (parsed.query() != null) {
      throw ConnectorException.badInput("The server URL can't have a ?query.");
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
    if (parsed.scheme().equals("http") && !loopbackHost(host)) {
      throw ConnectorException.badInput("Plain http:// reaches only this computer (localhost, 127.0.0.1). Use "
          + "https:// for " + host + ".");
    }
    String path = parsed.encodedPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (path.endsWith("/api/v1") || path.contains("/api/v1/")) {
      throw ConnectorException.badInput("Leave /api/v1 out of the server URL: GateRook adds the API's paths itself.");
    }
    String origin = parsed.newBuilder().encodedPath("/").build().toString();
    return URI.create(origin.substring(0, origin.length() - 1) + path + "/");
  }

  /** Whether {@code host} names this computer: {@code localhost}, or a loopback IP literal. */
  static boolean loopbackHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (h.equals("localhost") || h.equals("localhost.")) {
      return true;
    }
    Optional<InetAddress> ip = AddressPolicy.canonicalIp(h);
    return ip.isPresent() && ip.get().isLoopbackAddress();
  }

  /** Whether the server is this computer. */
  boolean loopback() {
    return loopbackHost(base.getHost());
  }

  /** Where the server is, for previews and texts: {@code prometheus.example.com:9090/prometheus}. */
  String target() {
    String path = base.getRawPath();
    String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    return (base.getPort() < 0 ? base.getHost() : base.getHost() + ":" + base.getPort()) + trimmed;
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    return n == null || n.isNull() || !n.isValueNode() || n.asString().isBlank() ? null : n.asString().strip();
  }
}
